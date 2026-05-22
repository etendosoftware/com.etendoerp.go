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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.data.PreviewFile;

/**
 * Persists preview file attachments per (client, specName, recordId) tuple in ETGO_PREVIEW_FILE.
 *
 * One row per tuple — POST uses upsert semantics. File data is Base64-encoded in the CLOB column.
 *
 * Exposed at:
 *   GET    /sws/neo/preview-file?specName=&recordId=  — fetch stored file metadata and data
 *   POST   /sws/neo/preview-file                      — upsert (create or replace) file
 *   DELETE /sws/neo/preview-file?specName=&recordId=  — remove file
 */
class NeoPreviewFileService {

  private static final Logger log = LogManager.getLogger(NeoPreviewFileService.class);

  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_SPEC_NAME  = "specName";
  private static final String PARAM_RECORD_ID  = "recordId";
  private static final String PARAM_FILE_NAME  = "fileName";
  private static final String PARAM_MIME_TYPE  = "mimeType";
  private static final String PARAM_FILE_DATA  = "fileData";
  private static final String PARAM_USER_ID    = "userId";
  private static final String PARAM_ORG_ID     = "orgId";
  private static final String PARAM_ID         = "id";

  private NeoPreviewFileService() {
  }

  static NeoResponse getPreviewFile(String specName, String recordId) {
    try {
      PreviewFile pf = findByTuple(currentClientId(), specName, recordId);
      if (pf == null) {
        return NeoResponse.ok(new JSONObject());
      }
      JSONObject result = new JSONObject();
      result.put(PARAM_FILE_NAME, pf.getFileName());
      result.put(PARAM_MIME_TYPE, pf.getMIMEType());
      result.put(PARAM_FILE_DATA, pf.getFileData());
      return NeoResponse.ok(result);
    } catch (Exception e) {
      log.error("Error fetching preview file spec={} record={}", specName, recordId, e);
      return NeoResponse.error(500, "Internal error retrieving preview file");
    }
  }

  static NeoResponse savePreviewFile(String body) {
    try {
      JSONObject req = new JSONObject(body);
      String specName = req.optString(PARAM_SPEC_NAME, null);
      String recordId = req.optString(PARAM_RECORD_ID, null);
      String fileName = req.optString(PARAM_FILE_NAME, null);
      String mimeType = req.optString(PARAM_MIME_TYPE, null);
      String fileData = req.optString(PARAM_FILE_DATA, null);

      if (isBlank(specName) || isBlank(recordId) || isBlank(fileName)
          || isBlank(mimeType) || isBlank(fileData)) {
        return NeoResponse.error(400,
            "Required fields: specName, recordId, fileName, mimeType, fileData");
      }

      String clientId = currentClientId();
      PreviewFile existing = findByTuple(clientId, specName, recordId);

      Session session = OBDal.getInstance().getSession();
      String savedId;

      if (existing == null) {
        savedId = UUID.randomUUID().toString().toUpperCase().replace("-", "");
        session.createNativeQuery(
            "INSERT INTO ETGO_PREVIEW_FILE"
            + " (ETGO_PREVIEW_FILE_ID, AD_CLIENT_ID, AD_ORG_ID, ISACTIVE,"
            + "  CREATED, CREATEDBY, UPDATED, UPDATEDBY,"
            + "  RECORD_ID, SPEC_NAME, FILE_NAME, MIME_TYPE, FILE_DATA)"
            + " VALUES (:id, :clientId, :orgId, 'Y',"
            + "  now(), :userId, now(), :userId,"
            + "  :recordId, :specName, :fileName, :mimeType, :fileData)")
            .setParameter(PARAM_ID,        savedId)
            .setParameter(PARAM_CLIENT_ID, clientId)
            .setParameter(PARAM_ORG_ID,    currentOrgId())
            .setParameter(PARAM_USER_ID,   currentUserId())
            .setParameter(PARAM_RECORD_ID, recordId)
            .setParameter(PARAM_SPEC_NAME, specName)
            .setParameter(PARAM_FILE_NAME, fileName)
            .setParameter(PARAM_MIME_TYPE, mimeType)
            .setParameter(PARAM_FILE_DATA, fileData)
            .executeUpdate();
      } else {
        savedId = existing.getId();
        session.createNativeQuery(
            "UPDATE ETGO_PREVIEW_FILE"
            + " SET FILE_NAME = :fileName, MIME_TYPE = :mimeType,"
            + "     FILE_DATA = :fileData, UPDATED = now(), UPDATEDBY = :userId"
            + " WHERE ETGO_PREVIEW_FILE_ID = :id")
            .setParameter(PARAM_FILE_NAME, fileName)
            .setParameter(PARAM_MIME_TYPE, mimeType)
            .setParameter(PARAM_FILE_DATA, fileData)
            .setParameter(PARAM_USER_ID,   currentUserId())
            .setParameter(PARAM_ID,        savedId)
            .executeUpdate();
      }

      JSONObject result = new JSONObject();
      result.put("id", savedId);
      return NeoResponse.ok(result);
    } catch (JSONException e) {
      log.warn("Invalid JSON body in preview-file POST: {}", e.getMessage());
      return NeoResponse.error(400, "Invalid JSON body");
    } catch (Exception e) {
      log.error("Error saving preview file", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Internal error saving preview file");
    }
  }

  static NeoResponse deletePreviewFile(String specName, String recordId) {
    try {
      String clientId = currentClientId();
      Session session = OBDal.getInstance().getSession();
      int deleted = session.createNativeQuery(
          "DELETE FROM ETGO_PREVIEW_FILE"
          + " WHERE AD_CLIENT_ID = :clientId"
          + " AND SPEC_NAME = :specName"
          + " AND RECORD_ID = :recordId")
          .setParameter(PARAM_CLIENT_ID, clientId)
          .setParameter(PARAM_SPEC_NAME, specName)
          .setParameter(PARAM_RECORD_ID, recordId)
          .executeUpdate();
      if (deleted == 0) {
        return NeoResponse.error(404, "Preview file not found");
      }
      return NeoResponse.ok(new JSONObject());
    } catch (Exception e) {
      log.error("Error deleting preview file spec={} record={}", specName, recordId, e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Internal error deleting preview file");
    }
  }

  private static PreviewFile findByTuple(String clientId, String specName, String recordId) {
    OBQuery<PreviewFile> query = OBDal.getInstance().createQuery(PreviewFile.class,
        "as pf where pf.client.id = :clientId"
            + " and pf.specName = :specName"
            + " and pf.recordID = :recordId");
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setNamedParameter(PARAM_SPEC_NAME, specName);
    query.setNamedParameter(PARAM_RECORD_ID, recordId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  private static String currentClientId() {
    return OBContext.getOBContext().getCurrentClient().getId();
  }

  private static String currentOrgId() {
    return OBContext.getOBContext().getCurrentOrganization().getId();
  }

  private static String currentUserId() {
    return OBContext.getOBContext().getUser().getId();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
