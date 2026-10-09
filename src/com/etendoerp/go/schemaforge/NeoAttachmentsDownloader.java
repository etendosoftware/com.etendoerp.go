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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;

/**
 * The binary-streaming half of the NEO Headless attachments endpoint
 * ({@code /sws/neo/attachments/*}): the single-file download and the zip archive.
 *
 * <p>Split out of {@link NeoAttachmentsHelper} (ETP-5526) along the boundary that class's
 * own contract already drew: everything here writes directly to the
 * {@link HttpServletResponse} and returns {@code void}, signalling to the caller that the
 * response body is already committed, whereas everything left in
 * {@link NeoAttachmentsHelper} yields a {@link NeoResponse} the servlet serializes. The
 * HTTP plumbing those streaming methods need — the {@code Content-Disposition} builder,
 * the content-type fallback and the JSON error writer — is used by nothing else, so it
 * moved with them instead of staying behind as a shared back-reference.</p>
 *
 * <p>Behaviour is unchanged by the move: the error messages, status codes and the
 * ownership rule enforced on the {@code ids} subset are exactly those
 * {@link NeoAttachmentsHelper} served before, and the shared message constants are still
 * read from it so the two halves cannot drift apart.</p>
 *
 * <p>Like {@link NeoAttachmentsHelper}, this class assumes the caller has already
 * activated admin mode — {@link NeoServlet} sets it before delegating to
 * {@link NeoBuiltInEndpointHandler}.</p>
 */
public final class NeoAttachmentsDownloader {

  private static final Logger log = LogManager.getLogger(NeoAttachmentsDownloader.class);

  private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
  private static final String ZIP_CONTENT_TYPE = "application/zip";
  private static final String ERR_IDS_REQUIRED = "ids must name at least one attachment";

  private NeoAttachmentsDownloader() {
  }

  // ── Download (single) ───────────────────────────────────────────────────────

  /**
   * Streams a single attachment to the response.
   *
   * <p>Writes the response body directly. Callers MUST NOT serialize a
   * {@link NeoResponse} after this method returns — the body is already
   * committed.</p>
   *
   * @param attachmentId the C_File_ID
   * @param response     the HTTP response to write to
   * @throws IOException if writing the response fails
   */
  public static void handleDownload(String attachmentId, HttpServletResponse response)
      throws IOException {
    if (StringUtils.isBlank(attachmentId)) {
      writeError(response, 400, NeoAttachmentsHelper.ATTACHMENTID_REQUIRED);
      return;
    }
    Attachment attachment = OBDal.getInstance().get(Attachment.class, attachmentId);
    if (attachment == null) {
      writeError(response, 404, NeoAttachmentsHelper.ERR_ATTACHMENT_NOT_FOUND);
      return;
    }
    try {
      AttachImplementationManager aim = NeoAttachmentsHelper.getAttachManager();
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      aim.download(attachmentId, buffer);

      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(resolveContentType(attachment.getDataType()));
      response.setHeader(NeoAttachmentsHelper.CONTENT_DISPOSITION,
          buildContentDisposition(attachment.getName()));
      byte[] bytes = buffer.toByteArray();
      response.setContentLength(bytes.length);
      try (OutputStream out = response.getOutputStream()) {
        out.write(bytes);
        out.flush();
      }
    } catch (OBException e) {
      log.warn("Attachment download failed for id {}: {}", attachmentId, e.getMessage());
      if (!response.isCommitted()) {
        writeError(response, 500, e.getMessage());
      }
    } catch (Exception e) {
      log.error("Attachment download failed for id {}", attachmentId, e);
      if (!response.isCommitted()) {
        writeError(response, 500, "Internal error downloading attachment");
      }
    }
  }

  // ── Download (zip of all attachments for a record) ──────────────────────────

  /**
   * Streams all of a record's attachments as a single zip file, including
   * whichever one is marked as the record's main document — it now appears
   * in the Attachments tab's own list (see {@link NeoAttachmentsHelper#handleList}),
   * so omitting it here would silently download fewer files than the tab shows.
   *
   * <p>Backwards-compatible overload of
   * {@link #handleDownloadAll(String, String, String, HttpServletResponse)} with no id
   * filter. Kept as its own method (rather than making every caller pass {@code null})
   * because "the whole record" is the endpoint's original contract and several callers
   * — including {@code SifAttachmentsSection} in the SPA — depend on exactly it.</p>
   *
   * @param tableName the AD_Table.name (case-insensitive)
   * @param recordId  the record's primary key
   * @param response  the HTTP response to write to
   * @throws IOException if writing the response fails
   */
  public static void handleDownloadAll(String tableName, String recordId,
      HttpServletResponse response) throws IOException {
    handleDownloadAll(tableName, recordId, null, response);
  }

  /**
   * Streams a record's attachments as a single zip file, optionally restricted to the
   * subset named by {@code idsParam} (ETP-5526 — the Attachments tab's selection bar
   * downloads only the ticked rows).
   *
   * <p><b>Authorization.</b> The subset is never taken on trust: the candidate set is
   * always the record's own attachments, resolved by exactly the same criteria
   * {@link NeoAttachmentsHelper#handleList} uses, and every requested id must be found
   * in it. An id that belongs to a different record — or to nothing at all — answers
   * {@code 404} and <em>nothing is streamed</em>, so the endpoint cannot be used to read
   * another record's files by guessing ids. The two failure modes answer the same 404 on
   * purpose: distinguishing them would confirm the existence of an attachment the
   * caller is not entitled to.</p>
   *
   * <p>This is a stricter check than the single-file
   * {@link #handleDownload(String, HttpServletResponse)} performs: that one resolves an
   * attachment by id alone, because its URL carries no record to validate against. Here
   * the record IS in the URL, so the ownership relation is checkable and is checked.</p>
   *
   * @param tableName the AD_Table.name (case-insensitive)
   * @param recordId  the record's primary key
   * @param idsParam  comma-separated attachment ids, or {@code null} for every
   *                  attachment of the record (the original behaviour). Supplying the
   *                  parameter with no usable id is a {@code 400}, not an implicit
   *                  "everything" — an empty selection is a client bug, and silently
   *                  zipping the whole record would hide it
   * @param response  the HTTP response to write to
   * @throws IOException if writing the response fails
   */
  public static void handleDownloadAll(String tableName, String recordId, String idsParam,
      HttpServletResponse response) throws IOException {
    if (StringUtils.isBlank(tableName) || StringUtils.isBlank(recordId)) {
      writeError(response, 400, NeoAttachmentsHelper.TABLENAME_RECORDID_REQUIRED);
      return;
    }
    final Set<String> requestedIds = idsParam == null ? null : parseIdList(idsParam);
    if (requestedIds != null && requestedIds.isEmpty()) {
      writeError(response, 400, ERR_IDS_REQUIRED);
      return;
    }
    try {
      String tableId = NeoAttachmentsHelper.resolveTableId(tableName);

      OBCriteria<Attachment> criteria = OBDal.getInstance().createCriteria(Attachment.class);
      criteria.add(Restrictions.eq(Attachment.PROPERTY_TABLE + ".id", tableId));
      criteria.add(Restrictions.eq(Attachment.PROPERTY_RECORD, recordId));
      criteria.setFilterOnReadableOrganization(false);

      List<Attachment> recordAttachments = criteria.list();
      String foreignId = firstIdNotOwnedBy(requestedIds, recordAttachments);
      if (foreignId != null) {
        log.warn("Attachments zip rejected: id {} does not belong to {}/{}",
            foreignId, tableName, recordId);
        writeError(response, 404, NeoAttachmentsHelper.ERR_ATTACHMENT_NOT_FOUND);
        return;
      }

      byte[] bytes = buildAttachmentsZip(recordAttachments, requestedIds);

      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(ZIP_CONTENT_TYPE);
      response.setHeader(NeoAttachmentsHelper.CONTENT_DISPOSITION,
          buildContentDisposition("attachments_" + recordId + ".zip"));
      response.setContentLength(bytes.length);
      try (OutputStream out = response.getOutputStream()) {
        out.write(bytes);
        out.flush();
      }
    } catch (OBException e) {
      log.warn("Attachment downloadAll failed for {}/{}: {}",
          tableName, recordId, e.getMessage());
      if (!response.isCommitted()) {
        writeError(response, 500, e.getMessage());
      }
    } catch (Exception e) {
      log.error("Attachment downloadAll failed for {}/{}", tableName, recordId, e);
      if (!response.isCommitted()) {
        writeError(response, 500, "Internal error downloading attachments archive");
      }
    }
  }

  /**
   * Builds the zip archive served by {@link #handleDownloadAll(String, String, String,
   * HttpServletResponse)} and returns its bytes.
   *
   * <p>Entries are written in the order {@code recordAttachments} comes in (the criteria's
   * own order), under each attachment's own name. When {@code requestedIds} is non-null,
   * attachments outside it are skipped — and never read from storage at all, so an
   * unselected file costs nothing. Ownership is NOT checked here: the caller has already
   * refused the whole request if any requested id was foreign, which is why nothing is
   * streamed in that case.</p>
   *
   * <p>Purely an assembly step: it neither catches nor wraps anything, so an
   * {@link OBException} from {@link AttachImplementationManager#download} and an
   * {@link IOException} from the zip stream both propagate to the caller's existing
   * handlers unchanged.</p>
   *
   * @param recordAttachments every attachment of the record, in the order to zip them
   * @param requestedIds      the subset to include, or {@code null} for all of them
   * @return the complete archive, ready to write to the response
   * @throws IOException if the zip stream fails
   */
  private static byte[] buildAttachmentsZip(List<Attachment> recordAttachments,
      Set<String> requestedIds) throws IOException {
    AttachImplementationManager aim = NeoAttachmentsHelper.getAttachManager();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
      for (Attachment attachment : recordAttachments) {
        if (requestedIds != null && !requestedIds.contains(attachment.getId())) {
          continue;
        }
        ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();
        aim.download(attachment.getId(), fileBuffer);
        zip.putNextEntry(new ZipEntry(attachment.getName()));
        zip.write(fileBuffer.toByteArray());
        zip.closeEntry();
      }
    }
    return buffer.toByteArray();
  }

  /**
   * Splits the {@code ids} query parameter of the zip endpoint into a set of attachment
   * ids, dropping blanks and duplicates while preserving the caller's order.
   *
   * @param idsParam the raw comma-separated parameter (never {@code null} here)
   * @return the parsed ids; empty when the parameter carried none
   */
  private static Set<String> parseIdList(String idsParam) {
    Set<String> ids = new LinkedHashSet<>();
    for (String raw : StringUtils.split(idsParam, ',')) {
      String id = StringUtils.trimToNull(raw);
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
  }

  /**
   * Ownership check behind the zip subset: the first requested id that is NOT among the
   * record's own attachments, or {@code null} when every one of them is (or when no
   * subset was requested at all).
   *
   * @param requestedIds      the ids the caller asked for, or {@code null} for "all"
   * @param recordAttachments every attachment of the record in the URL
   * @return the offending id, or {@code null} when the request is legitimate
   */
  private static String firstIdNotOwnedBy(Set<String> requestedIds,
      List<Attachment> recordAttachments) {
    if (requestedIds == null) {
      return null;
    }
    Set<String> owned = new HashSet<>();
    for (Attachment attachment : recordAttachments) {
      owned.add(attachment.getId());
    }
    for (String id : requestedIds) {
      if (!owned.contains(id)) {
        return id;
      }
    }
    return null;
  }

  // ── HTTP plumbing (used only by the streaming methods above) ────────────────

  private static String resolveContentType(String dataType) {
    return StringUtils.isBlank(dataType) ? DEFAULT_CONTENT_TYPE : dataType;
  }

  /**
   * Builds an RFC 5987 compliant {@code Content-Disposition} header so that
   * UTF-8 filenames survive across browsers.
   */
  private static String buildContentDisposition(String fileName) {
    String safe = fileName == null ? "download" : fileName.replace("\"", "_");
    String encoded;
    try {
      encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8.name()).replace("+", "%20");
    } catch (java.io.UnsupportedEncodingException e) {
      encoded = safe;
    }
    return "attachment; filename=\"" + safe + "\"; filename*=UTF-8''" + encoded;
  }

  /**
   * Writes a NEO-style JSON error directly to the response. Used by the
   * streaming endpoints, which cannot return a {@link NeoResponse}.
   */
  private static void writeError(HttpServletResponse response, int status, String message)
      throws IOException {
    NeoResponse error = NeoResponse.error(status, message);
    response.setStatus(error.getHttpStatus());
    if (error.getBody() != null) {
      response.setContentType("application/json");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(error.getBody().toString());
    }
  }
}
