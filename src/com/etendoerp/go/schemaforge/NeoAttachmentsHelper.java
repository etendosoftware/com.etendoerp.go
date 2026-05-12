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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.utility.Attachment;

/**
 * Cross-cutting helper for the NEO Headless attachments endpoint
 * ({@code /sws/neo/attachments/*}).
 *
 * Exposes a small façade over {@link AttachImplementationManager} so the React
 * Attachments tab can list, upload, download (single + zip), delete and
 * update-description without each window having to declare its own handler.
 *
 * <p>The methods that return JSON yield a {@link NeoResponse}; the two methods
 * that stream binary content ({@link #handleDownload} and
 * {@link #handleDownloadAll}) write directly to the {@link HttpServletResponse}
 * and signal to the caller (via a {@code void} return) that the response body
 * has already been committed.</p>
 *
 * <p>This class assumes the caller has already activated admin mode — the
 * built-in endpoint dispatcher in {@link NeoServlet} sets it before delegating
 * to {@link NeoBuiltInEndpointHandler}.</p>
 */
public final class NeoAttachmentsHelper {

  private static final Logger log = LogManager.getLogger(NeoAttachmentsHelper.class);

  /** Cache of AD_Table_ID resolutions keyed by lower-case table name. */
  private static final ConcurrentHashMap<String, String> TABLE_ID_CACHE = new ConcurrentHashMap<>();

  /** UTC ISO-8601 formatter shared across responses for predictable client parsing. */
  private static final ThreadLocal<SimpleDateFormat> ISO_FORMATTER = ThreadLocal.withInitial(() -> {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
    return sdf;
  });

  private static final String MULTIPART_FILE_PART = "file";
  private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
  private static final String ZIP_CONTENT_TYPE = "application/zip";
  private static final String UI_PATTERN_STD = "STD";
  private static final String ERR_ATTACHMENT_NOT_FOUND = "Attachment not found";
  private static final String TABLENAME_RECORDID_REQUIRED = "tableName and recordId are required";
  private static final String ATTACHMENTID_REQUIRED = "attachmentId is required";
  private static final String CONTENT_DISPOSITION = "Content-Disposition";

  private NeoAttachmentsHelper() {
  }

  // ── List ────────────────────────────────────────────────────────────────────

  /**
   * Lists attachments bound to the given record.
   *
   * @param tableName the AD_Table.name (case-insensitive, e.g. {@code "C_Order"})
   * @param recordId  the record's primary key (string; all AD IDs are VARCHAR)
   * @return a NeoResponse wrapping {@code { "items": [ ... ] }}
   */
  public static NeoResponse handleList(String tableName, String recordId) {
    if (StringUtils.isBlank(tableName) || StringUtils.isBlank(recordId)) {
      return NeoResponse.error(400, TABLENAME_RECORDID_REQUIRED);
    }
    try {
      String tableId = resolveTableId(tableName);

      OBCriteria<Attachment> criteria = OBDal.getInstance().createCriteria(Attachment.class);
      criteria.add(Restrictions.eq(Attachment.PROPERTY_TABLE + ".id", tableId));
      criteria.add(Restrictions.eq(Attachment.PROPERTY_RECORD, recordId));
      criteria.addOrderBy(Attachment.PROPERTY_SEQUENCENUMBER, true);
      criteria.addOrderBy(Attachment.PROPERTY_CREATIONDATE, true);
      criteria.setFilterOnReadableOrganization(false);

      JSONArray items = new JSONArray();
      for (Attachment attachment : criteria.list()) {
        items.put(toAttachmentJson(attachment));
      }
      JSONObject body = new JSONObject();
      body.put("items", items);
      return NeoResponse.ok(body);
    } catch (OBException e) {
      log.warn("Attachments list failed: {}", e.getMessage());
      return NeoResponse.error(404, e.getMessage());
    } catch (Exception e) {
      log.error("Attachments list failed for {}/{}", tableName, recordId, e);
      return NeoResponse.error(500, "Internal error listing attachments");
    }
  }

  // ── Upload ──────────────────────────────────────────────────────────────────

  /**
   * Uploads a file as an attachment of the given record.
   *
   * <p>Expects a {@code multipart/form-data} request with a part named
   * {@code "file"}. Optional query parameter {@code tabId} overrides the
   * automatic tab resolution (useful when the same table has multiple tabs).</p>
   *
   * @param tableName the AD_Table.name (case-insensitive)
   * @param recordId  the record's primary key
   * @param request   the HTTP request carrying the multipart payload
   * @return 201 Created on success, 400/404/500 on failure
   */
  public static NeoResponse handleUpload(String tableName, String recordId,
      HttpServletRequest request) {
    if (StringUtils.isBlank(tableName) || StringUtils.isBlank(recordId)) {
      return NeoResponse.error(400, TABLENAME_RECORDID_REQUIRED);
    }
    String contentType = request.getContentType();
    if (contentType == null || !contentType.startsWith("multipart/")) {
      return NeoResponse.error(400, "Expected multipart/form-data");
    }

    File tempFile = null;
    try {
      Part filePart = request.getPart(MULTIPART_FILE_PART);
      if (filePart == null) {
        return NeoResponse.error(400, "Missing 'file' part");
      }

      String tableId = resolveTableId(tableName);
      String tabId = resolveTabId(tableId, request.getParameter("tabId"));
      if (tabId == null) {
        return NeoResponse.error(400,
            "Could not resolve a standard tab for table '" + tableName + "'");
      }

      String orgId = OBContext.getOBContext().getCurrentOrganization().getId();

      tempFile = materializeTempFile(filePart);

      AttachImplementationManager aim = getAttachManager();
      aim.upload(new HashMap<>(), tabId, recordId, orgId, tempFile);

      JSONObject body = new JSONObject();
      body.put("name", tempFile.getName());
      body.put("message", "uploaded");
      return NeoResponse.created(body);
    } catch (OBException e) {
      OBDal.getInstance().rollbackAndClose();
      log.warn("Attachment upload failed for {}/{}: {}", tableName, recordId, e.getMessage());
      return NeoResponse.error(500, e.getMessage());
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      log.error("Attachment upload failed for {}/{}", tableName, recordId, e);
      return NeoResponse.error(500, "Internal error uploading attachment");
    } finally {
      cleanupTempFile(tempFile);
    }
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
      writeError(response, 400, ATTACHMENTID_REQUIRED);
      return;
    }
    Attachment attachment = OBDal.getInstance().get(Attachment.class, attachmentId);
    if (attachment == null) {
      writeError(response, 404, ERR_ATTACHMENT_NOT_FOUND);
      return;
    }
    try {
      AttachImplementationManager aim = getAttachManager();
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      aim.download(attachmentId, buffer);

      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(resolveContentType(attachment.getDataType()));
      response.setHeader(CONTENT_DISPOSITION, buildContentDisposition(attachment.getName()));
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
   * Streams all attachments for a record as a single zip file.
   *
   * @param tableName the AD_Table.name (case-insensitive)
   * @param recordId  the record's primary key
   * @param response  the HTTP response to write to
   * @throws IOException if writing the response fails
   */
  public static void handleDownloadAll(String tableName, String recordId,
      HttpServletResponse response) throws IOException {
    if (StringUtils.isBlank(tableName) || StringUtils.isBlank(recordId)) {
      writeError(response, 400, TABLENAME_RECORDID_REQUIRED);
      return;
    }
    try {
      String tableId = resolveTableId(tableName);
      String tabId = resolveTabId(tableId, null);
      if (tabId == null) {
        writeError(response, 400,
            "Could not resolve a standard tab for table '" + tableName + "'");
        return;
      }

      AttachImplementationManager aim = getAttachManager();
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      aim.downloadAll(tabId, recordId, buffer);

      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(ZIP_CONTENT_TYPE);
      response.setHeader(CONTENT_DISPOSITION,
          buildContentDisposition("attachments_" + recordId + ".zip"));
      byte[] bytes = buffer.toByteArray();
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

  // ── Delete ──────────────────────────────────────────────────────────────────

  /**
   * Deletes a single attachment (DB record + file on disk).
   *
   * @param attachmentId the C_File_ID
   * @return 204 No Content on success, 404 if the attachment does not exist
   */
  public static NeoResponse handleDelete(String attachmentId) {
    if (StringUtils.isBlank(attachmentId)) {
      return NeoResponse.error(400, ATTACHMENTID_REQUIRED);
    }
    try {
      Attachment attachment = OBDal.getInstance().get(Attachment.class, attachmentId);
      if (attachment == null) {
        return NeoResponse.error(404, ERR_ATTACHMENT_NOT_FOUND);
      }
      AttachImplementationManager aim = getAttachManager();
      aim.delete(attachment);
      return NeoResponse.noContent();
    } catch (OBException e) {
      OBDal.getInstance().rollbackAndClose();
      log.warn("Attachment delete failed for id {}: {}", attachmentId, e.getMessage());
      return NeoResponse.error(500, e.getMessage());
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      log.error("Attachment delete failed for id {}", attachmentId, e);
      return NeoResponse.error(500, "Internal error deleting attachment");
    }
  }

  // ── Update description ──────────────────────────────────────────────────────

  /**
   * Updates the free-text description (C_File.text) of an attachment.
   *
   * @param attachmentId the C_File_ID
   * @param description  the new description (may be null / empty)
   * @return 200 OK with {@code { id, description }}, or 404 if not found
   */
  public static NeoResponse handleUpdateDescription(String attachmentId, String description) {
    if (StringUtils.isBlank(attachmentId)) {
      return NeoResponse.error(400, ATTACHMENTID_REQUIRED);
    }
    try {
      Attachment attachment = OBDal.getInstance().get(Attachment.class, attachmentId);
      if (attachment == null) {
        return NeoResponse.error(404, ERR_ATTACHMENT_NOT_FOUND);
      }
      attachment.setText(description);
      OBDal.getInstance().save(attachment);
      OBDal.getInstance().flush();

      JSONObject body = new JSONObject();
      body.put("id", attachment.getId());
      body.put("description", description == null ? JSONObject.NULL : description);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      log.error("Attachment description update failed for id {}", attachmentId, e);
      return NeoResponse.error(500, "Internal error updating description");
    }
  }

  // ── Internal helpers ────────────────────────────────────────────────────────

  /**
   * Resolves the AD_Table.id for the given physical table name, caching successful lookups.
   *
   * <p>Queries {@code AD_Table.tablename} (the physical DB table name, e.g. {@code "C_Order"})
   * rather than {@code AD_Table.name} (the entity display name, e.g. {@code "Order"}).
   * Uses native SQL to bypass ORM client/org filters, since AD_Table records always
   * belong to client {@code '0'} (system).</p>
   *
   * @param tableName the AD physical table name (case-insensitive, e.g. {@code "C_Order"})
   * @return the AD_Table.id
   * @throws OBException when no active table with that physical name exists
   */
  @SuppressWarnings("unchecked")
  private static String resolveTableId(String tableName) {
    String key = tableName.toLowerCase(Locale.ROOT);
    String cached = TABLE_ID_CACHE.get(key);
    if (cached != null) {
      return cached;
    }
    List<String> rows = OBDal.getInstance().getSession()
        .createNativeQuery(
            "SELECT ad_table_id FROM ad_table "
            + "WHERE UPPER(tablename) = UPPER(:tn) AND isactive = 'Y' LIMIT 1")
        .setParameter("tn", tableName)
        .list();
    if (rows.isEmpty()) {
      throw new OBException("Unknown table: " + tableName);
    }
    String tableId = rows.get(0);
    TABLE_ID_CACHE.put(key, tableId);
    return tableId;
  }

  /**
   * Resolves the AD_Tab.id for the given table. If {@code tabIdHint} is non-blank
   * it is returned unchanged; otherwise the first active STD tab is selected
   * (lowest tabLevel, then lowest sequenceNumber).
   *
   * @param tableId   the AD_Table.id
   * @param tabIdHint optional client-provided tab id
   * @return the AD_Tab.id, or {@code null} if no suitable tab exists
   */
  private static String resolveTabId(String tableId, String tabIdHint) {
    if (StringUtils.isNotBlank(tabIdHint)) {
      return tabIdHint;
    }
    OBCriteria<Tab> criteria = OBDal.getInstance().createCriteria(Tab.class);
    criteria.add(Restrictions.eq(Tab.PROPERTY_TABLE + ".id", tableId));
    criteria.add(Restrictions.eq(Tab.PROPERTY_UIPATTERN, UI_PATTERN_STD));
    criteria.add(Restrictions.eq(Tab.PROPERTY_ACTIVE, true));
    criteria.addOrderBy(Tab.PROPERTY_TABLEVEL, true);
    criteria.addOrderBy(Tab.PROPERTY_SEQUENCENUMBER, true);
    criteria.setMaxResults(1);
    criteria.setFilterOnReadableOrganization(false);
    @SuppressWarnings("unchecked")
    List<Tab> tabs = criteria.list();
    return tabs.isEmpty() ? null : tabs.get(0).getId();
  }

  /**
   * Resolves the shared {@link AttachImplementationManager} CDI bean.
   */
  static AttachImplementationManager getAttachManager() {
    return WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class);
  }

  /**
   * Builds the JSON projection for an attachment row used by the React UI.
   */
  private static JSONObject toAttachmentJson(Attachment attachment) throws JSONException {
    JSONObject json = new JSONObject();
    json.put("id", attachment.getId());
    json.put("name", attachment.getName());
    json.put("size", computeFileSize(attachment));
    json.put("dataType", attachment.getDataType() != null ? attachment.getDataType()
        : JSONObject.NULL);
    json.put("description", attachment.getText() != null ? attachment.getText() : JSONObject.NULL);
    json.put("uploadedAt", formatDate(attachment.getCreationDate()));
    json.put("updatedAt", formatDate(attachment.getUpdated()));
    json.put("uploadedBy", userToJson(attachment.getCreatedBy()));
    return json;
  }

  private static Object userToJson(User user) throws JSONException {
    if (user == null) {
      return JSONObject.NULL;
    }
    JSONObject json = new JSONObject();
    json.put("id", user.getId());
    json.put("name", user.getName());
    return json;
  }

  private static Object formatDate(Date date) {
    if (date == null) {
      return JSONObject.NULL;
    }
    return ISO_FORMATTER.get().format(date);
  }

  /**
   * Returns the file size in bytes by inspecting the attachment payload on disk.
   * Returns 0 when the file is missing (e.g. configured to an alternative
   * storage backend or moved out-of-band).
   */
  private static long computeFileSize(Attachment attachment) {
    if (attachment.getPath() == null || attachment.getName() == null) {
      return 0L;
    }
    String attachRoot = OBPropertiesProvider.getInstance()
        .getOpenbravoProperties()
        .getProperty("attach.path");
    if (StringUtils.isBlank(attachRoot)) {
      return 0L;
    }
    File file = new File(attachRoot + File.separator + attachment.getPath(),
        attachment.getName());
    return file.exists() ? file.length() : 0L;
  }

  /**
   * Persists the given multipart {@link Part} to a temporary file using its
   * original submitted file name.
   */
  private static File materializeTempFile(Part filePart) throws IOException {
    String fileName = resolveFileName(filePart);
    File tempDir = new File(System.getProperty("java.io.tmpdir"));
    File tempFile = new File(tempDir, fileName);
    try (InputStream in = filePart.getInputStream();
        OutputStream out = new FileOutputStream(tempFile)) {
      byte[] buf = new byte[8192];
      int read;
      while ((read = in.read(buf)) > 0) {
        out.write(buf, 0, read);
      }
    }
    return tempFile;
  }

  private static void cleanupTempFile(File tempFile) {
    if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
      log.warn("Could not delete temporary upload file: {}", tempFile.getAbsolutePath());
    }
  }

  /**
   * Reads the {@code filename} out of the part's Content-Disposition header.
   * Falls back to {@code "attachment"} when no name is provided.
   */
  private static String resolveFileName(Part part) {
    String submittedName = part.getSubmittedFileName();
    if (StringUtils.isNotBlank(submittedName)) {
      return sanitizeFileName(submittedName);
    }
    String disposition = part.getHeader(CONTENT_DISPOSITION);
    if (disposition != null) {
      for (String segment : disposition.split(";")) {
        segment = segment.trim();
        if (segment.startsWith("filename")) {
          String value = segment.substring(segment.indexOf('=') + 1).trim().replace("\"", "");
          if (StringUtils.isNotBlank(value)) {
            return sanitizeFileName(value);
          }
        }
      }
    }
    return "attachment";
  }

  /**
   * Strips any directory component from a client-supplied filename to avoid
   * path traversal when materializing the temporary file.
   */
  private static String sanitizeFileName(String name) {
    String trimmed = name.trim();
    int sep = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
    if (sep >= 0) {
      trimmed = trimmed.substring(sep + 1);
    }
    return trimmed.isEmpty() ? "attachment" : trimmed;
  }

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

  /**
   * Clears the table-id cache. Visible for tests.
   */
  static void clearTableIdCache() {
    TABLE_ID_CACHE.clear();
  }
}
