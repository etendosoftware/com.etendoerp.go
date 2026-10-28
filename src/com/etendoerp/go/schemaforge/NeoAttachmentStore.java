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
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;

/**
 * The storing half of the attachment upload, shared by every entry point (ETP-5715): the multipart
 * {@code POST /sws/neo/attachments/{table}/{record}} the SPA uses and the single-use upload link
 * the MCP hands out.
 *
 * <p>Extracted from {@link NeoAttachmentsHelper#handleUpload} unchanged, so the size/extension
 * policy, the magic-byte check, the record-exists answer and the main-document replacement are
 * written once. The order is the original one and is relied on: cheap checks first (declared size,
 * extension), then table, tab and record, and only then the bytes are materialized and their
 * content verified.</p>
 */
final class NeoAttachmentStore {

  private static final Logger log = LogManager.getLogger(NeoAttachmentStore.class);
  /** What the core's MIME detection stores for a format it does not recognise. */
  private static final String GENERIC_DATA_TYPE = "application/octet-stream";

  private NeoAttachmentStore() {
  }

  /**
   * Validates and stores one upload.
   *
   * @param upload the upload to run
   * @return 201 Created on success, 400/404/500 on failure — the answers {@code handleUpload}
   *         always gave
   */
  static NeoResponse store(NeoAttachmentUpload upload) {
    AtomicReference<File> tempFile = new AtomicReference<>();
    try {
      return storeChecked(upload, tempFile);
    } catch (OBException e) {
      OBDal.getInstance().rollbackAndClose();
      log.warn("Attachment upload failed for {}/{}: {}", upload.getTableName(),
          upload.getRecordId(), e.getMessage());
      return NeoResponse.error(500, e.getMessage());
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      log.error("Attachment upload failed for {}/{}", upload.getTableName(),
          upload.getRecordId(), e);
      return NeoResponse.error(500, "Internal error uploading attachment");
    } finally {
      NeoAttachmentsHelper.cleanupTempFile(tempFile.get());
    }
  }

  private static NeoResponse storeChecked(NeoAttachmentUpload upload, AtomicReference<File> holder)
      throws Exception {
    // ETP-5038: the size/type policy is enforced HERE, not only in the dropzone — the browser is
    // not the only caller and the part's Content-Type is chosen by whoever is calling. Cheap checks
    // (declared size, extension) run before a single byte is written to disk; the magic-byte check
    // needs the materialized file and runs below.
    String rejection = NeoAttachmentPolicy.validateMetadata(upload.getFileName(),
        upload.getDeclaredSize());
    if (rejection != null) {
      return NeoResponse.error(400, rejection);
    }
    String tableName = upload.getTableName();
    String recordId = upload.getRecordId();
    String tableId = NeoAttachmentsHelper.resolveTableId(tableName);
    String tabId = NeoAttachmentsHelper.resolveTabId(tableId, upload.getTabIdHint());
    if (tabId == null) {
      return NeoResponse.error(400,
          "Could not resolve a standard tab for table '" + tableName + "'");
    }
    // ETP-5309: an unsaved record (the SPA's literal id "new") used to reach the core, whose
    // OBSecurityException came back as a raw 500. Answer a clean 404 instead.
    if (!NeoAttachmentsHelper.recordExists(tableId, recordId)) {
      return NeoResponse.error(404, "Record '" + recordId + "' does not exist in table '"
          + tableName + "'. Save it before attaching files.");
    }
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    File tempFile = upload.getMaterializer().materialize(upload.getFileName());
    holder.set(tempFile);
    rejection = NeoAttachmentPolicy.validateContent(upload.getFileName(), tempFile);
    if (rejection != null) {
      return NeoResponse.error(400, rejection);
    }
    AttachImplementationManager aim = NeoAttachmentsHelper.getAttachManager();
    aim.upload(new HashMap<>(), tabId, recordId, orgId, tempFile);

    JSONObject body = new JSONObject();
    body.put("name", tempFile.getName());
    body.put("message", "uploaded");
    // One lookup serves both the best-effort data type correction and markAsMain. Only
    // markAsMain depends on it (as before ETP-5735), so a failure there still fails the
    // request; any other upload just skips the correction.
    Attachment uploaded = upload.isMarkAsMain()
        ? NeoAttachmentsHelper.findLatestUploadedAttachment(tableId, recordId, tempFile.getName())
        : findForCorrection(tableId, recordId, tempFile.getName());
    correctDataType(uploaded, tempFile);
    if (upload.isMarkAsMain() && uploaded != null) {
      NeoAttachmentsHelper.markAsMain(uploaded);
      body.put("id", uploaded.getId());
      body.put("isMain", true);
    }
    return NeoResponse.created(body);
  }

  /** Best-effort lookup for the data type correction: a failure skips it, never the upload. */
  private static Attachment findForCorrection(String tableId, String recordId, String fileName) {
    try {
      return NeoAttachmentsHelper.findLatestUploadedAttachment(tableId, recordId, fileName);
    } catch (RuntimeException e) {
      log.warn("Could not look up the uploaded attachment to correct its data type", e);
      return null;
    }
  }

  /**
   * The core stores the MIME type Tika 0.9 detects, which is {@code application/octet-stream}
   * for formats it does not know (WEBP). The viewers route on that value, so replace a blank or
   * generic one with the type the attachments policy resolves. Never throws; leaves the value
   * untouched when nothing resolves.
   */
  private static void correctDataType(Attachment uploaded, File file) {
    if (uploaded == null) {
      return;
    }
    try {
      String stored = uploaded.getDataType();
      if (stored != null && !stored.isBlank() && !GENERIC_DATA_TYPE.equals(stored)) {
        return;
      }
      String resolved = NeoAttachmentPolicy.resolveMimeType(uploaded.getName(), file);
      if (resolved != null) {
        // Native SQL, not uploaded.setDataType: a dirty ADAttachment fails the DAL write-access
        // check at commit (OBSecurityException -> 500, upload rolled back).
        NeoAttachmentsHelper.updateDataType(uploaded.getId(), resolved);
      }
    } catch (RuntimeException e) {
      log.warn("Could not correct the data type of attachment {}", uploaded.getId(), e);
    }
  }
}
