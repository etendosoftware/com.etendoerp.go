/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.PendingUpload;
import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.Session;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Serves {@code PUT /sws/neo/attachment/upload/{token}}, the out-of-band leg of the MCP attachment
 * upload link (ETP-5715).
 *
 * <p><b>Why it has no bearer token.</b> The point of the link is that whoever holds the FILE (a shell
 * running curl, a person with a browser) uploads it directly, so the bytes never pass through the
 * model's context. The single-use, 10-minute, 192-bit token IS the credential, exactly as for the
 * image upload link and the document-download links; {@code NeoServlet} routes here before
 * authentication.</p>
 *
 * <p><b>It is not a privilege.</b> The upload does not run as an administrator: it runs as the MCP
 * session that issued the link (user, role, organization, client, warehouse are on the link), and the
 * document is authorized again here ({@link NeoAttachmentTarget#resolve}), because the role or the
 * record may have changed in the minutes since the link was issued. The bytes then go through the
 * very same policy and storage as the SPA's upload ({@link NeoAttachmentStore}: 10 MB cap,
 * extension allow-list, magic-byte check, main-document replacement).</p>
 *
 * <p>A rejected payload (too large, wrong type, bad content) does not burn the link — the remedy is
 * to PUT the right file to the same URL. A link whose document is no longer reachable is spent.</p>
 */
public final class NeoAttachmentUploadEndpoint {

  /** Path (inside the NEO servlet) the link points to; the token follows. */
  public static final String PATH = "/attachment/upload/";

  /** One answer for unknown, used and expired links, so a token cannot be probed. */
  static final String INVALID_LINK_MESSAGE = "This upload link is not valid, has already been used, "
      + "or has expired. Request a new one with etendo_request_attachment_upload.";

  private static final Logger log = LogManager.getLogger(NeoAttachmentUploadEndpoint.class);
  private static final long MAX_BYTES = NeoAttachmentPolicy.MAX_SIZE_MB * 1024L * 1024L;
  private static final String ENCODING = StandardCharsets.UTF_8.name();

  private NeoAttachmentUploadEndpoint() {
  }

  /**
   * Whether a request path addresses an upload link.
   *
   * @param pathInfo the servlet path info
   * @return {@code true} when it starts with {@link #PATH} and carries a token
   */
  public static boolean matches(String pathInfo) {
    return pathInfo != null && pathInfo.startsWith(PATH)
        && StringUtils.isNotBlank(StringUtils.substringAfter(pathInfo, PATH));
  }

  /**
   * Runs the upload for the link in the request path.
   *
   * @param request  the PUT whose body is the raw file
   * @param response where the JSON answer is written
   * @throws IOException when the answer cannot be written
   */
  public static void handle(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String token = StringUtils.substringAfter(request.getPathInfo(), PATH);
    NeoAttachmentUploadTickets tickets = NeoAttachmentUploadTickets.getInstance();
    PendingUpload ticket = tickets.claim(token).orElse(null);
    if (ticket == null) {
      send(response, HttpServletResponse.SC_FORBIDDEN, INVALID_LINK_MESSAGE);
      return;
    }
    OBContext previous = OBContext.getOBContext();
    AtomicBoolean committed = new AtomicBoolean();
    OBContext.setAdminMode(true);
    try {
      bindSession(ticket.getSession());
      NeoAttachmentTarget target = authorize(ticket);
      NeoResponse stored = store(ticket, target, request);
      settle(token, tickets, new Settlement(target, ticket, stored, committed), response);
    } catch (NeoAttachmentTarget.Refused e) {
      tickets.complete(token, null);
      send(response, e.getStatus(), e.getMessage());
    } catch (Exception e) {
      log.error("Ticketed attachment upload failed", e);
      if (!committed.get()) {
        // Once committed the link is spent and the file stored: a failure writing the answer must
        // neither hand the link back nor roll back (there is nothing left to roll back).
        tickets.release(token);
        OBDal.getInstance().rollbackAndClose();
      }
      send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Attachment upload failed");
    } finally {
      OBContext.restorePreviousMode();
      OBContext.setOBContext(previous);
    }
  }

  /** What {@link #settle} needs besides the token: the outcome of the store and the commit flag. */
  private static final class Settlement {
    private final NeoAttachmentTarget target;
    private final PendingUpload ticket;
    private final NeoResponse stored;
    private final AtomicBoolean committed;

    private Settlement(NeoAttachmentTarget target, PendingUpload ticket, NeoResponse stored,
        AtomicBoolean committed) {
      this.target = target;
      this.ticket = ticket;
      this.stored = stored;
      this.committed = committed;
    }
  }

  /** The context of the MCP session that asked for the link — never an elevated one. */
  private static void bindSession(Session session) {
    OBContext.setOBContext(SecureWebServicesUtils.createContext(session.getUserId(),
        session.getRoleId(), session.getOrganizationId(), session.getWarehouseId(),
        session.getClientId()));
  }

  private static NeoAttachmentTarget authorize(PendingUpload ticket) {
    SFSpec spec = OBDal.getInstance().get(SFSpec.class, ticket.getSubject().getSpecId());
    SFEntity entity = OBDal.getInstance().get(SFEntity.class, ticket.getSubject().getEntityId());
    // The spec is judged as findActiveSpecByName judged it when the link was issued: a spec switched
    // off within the TTL no longer takes uploads. Same answer as a link that never existed.
    if (spec == null || entity == null || !Boolean.TRUE.equals(spec.isActive())
        || !Boolean.TRUE.equals(spec.isShowInMCP()) || !Boolean.TRUE.equals(entity.isActive())
        || !Boolean.TRUE.equals(entity.isIncluded())) {
      throw new NeoAttachmentTarget.Refused(HttpServletResponse.SC_FORBIDDEN, INVALID_LINK_MESSAGE);
    }
    return NeoAttachmentTarget.resolve(spec, entity, ticket.getSubject().getRecordId(), true);
  }

  private static NeoResponse store(PendingUpload ticket, NeoAttachmentTarget target,
      HttpServletRequest request) {
    NeoAttachmentUploadTickets.Subject subject = ticket.getSubject();
    return NeoAttachmentStore.store(new NeoAttachmentUpload(target.getTableName(),
        target.getRecordId(), target.getTabId(), subject.getFileName(),
        request.getContentLengthLong(), name -> materialize(request, name),
        subject.isMarkAsMain()));
  }

  /** Writes the body to a directory of its own, never past the policy's size cap by more than a byte. */
  private static File materialize(HttpServletRequest request, String fileName) throws IOException {
    Path directory = Files.createTempDirectory("etgo-attachment-");
    File file = new File(directory.toFile(), fileName);
    try (InputStream in = request.getInputStream();
        OutputStream out = Files.newOutputStream(file.toPath())) {
      byte[] buffer = new byte[8192];
      long total = 0;
      int read = in.read(buffer);
      while (read > 0 && total <= MAX_BYTES) {
        out.write(buffer, 0, read);
        total += read;
        read = in.read(buffer);
      }
    } catch (IOException | RuntimeException e) {
      // The store only learns of the file once this method returns, so a copy that fails (client
      // abort, name too long) must clean up here or the directory and up to 10 MB are leaked.
      try {
        Files.deleteIfExists(file.toPath());
        Files.deleteIfExists(directory);
      } catch (IOException cleanup) {
        e.addSuppressed(cleanup);
      }
      throw e;
    }
    return file;
  }

  /** Spends the link on success, hands it back on a fixable rejection, and writes the answer. */
  private static void settle(String token, NeoAttachmentUploadTickets tickets,
      Settlement settlement, HttpServletResponse response) throws IOException, JSONException {
    NeoAttachmentTarget target = settlement.target;
    PendingUpload ticket = settlement.ticket;
    NeoResponse stored = settlement.stored;
    if (stored.getHttpStatus() >= HttpServletResponse.SC_BAD_REQUEST) {
      tickets.release(token);
      OBDal.getInstance().rollbackAndClose();
      writeJson(response, stored.getHttpStatus(), stored.getBody());
      return;
    }
    JSONObject body = stored.getBody();
    String name = ticket.getSubject().getFileName();
    Attachment attachment = NeoAttachmentsHelper.findLatestUploadedAttachment(
        target.getTableId(), target.getRecordId(), name);
    String attachmentId = attachment == null ? null : attachment.getId();
    OBDal.getInstance().commitAndClose();
    settlement.committed.set(true);
    tickets.complete(token, attachmentId);
    body.put("id", attachmentId == null ? JSONObject.NULL : attachmentId);
    body.put("isMain", ticket.getSubject().isMarkAsMain());
    writeJson(response, stored.getHttpStatus(), body);
  }

  private static void send(HttpServletResponse response, int status, String message)
      throws IOException {
    NeoResponse error = NeoResponse.error(status, message);
    writeJson(response, error.getHttpStatus(), error.getBody());
  }

  private static void writeJson(HttpServletResponse response, int status, JSONObject body)
      throws IOException {
    response.setStatus(status);
    if (body != null) {
      response.setContentType("application/json");
      response.setCharacterEncoding(ENCODING);
      response.getWriter().write(body.toString());
    }
  }
}
