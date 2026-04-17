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

import com.smf.jobs.hooks.CloneRecordHook;
import org.openbravo.erpCommon.utility.CSResponse;
import org.openbravo.erpCommon.utility.DocumentNoData;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.ComponentProvider;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Generic NEO handler that clones any record into a new draft using the
 * {@link CloneRecordHook} infrastructure, mirroring how the classic
 * {@code CloneRecords} job works.
 *
 * The correct hook for each entity (e.g. {@code CloneOrderHook},
 * {@code CloneInvoiceHook}) is resolved via CDI at runtime.
 * Falls back to {@link DalUtil#copy} when no hook is registered.
 *
 * Document numbering: the {@code DocumentNoHandlerLegacy} CDI observer fires
 * automatically during the hook's flush, but it relies on
 * {@code RequestContext.get().getVariablesSecureApp()} which does not carry the
 * correct {@code AD_Client_ID} in the NEO action-request context. We therefore
 * generate the document number explicitly after the hook returns, reading the
 * client ID directly from the cloned record so we are independent of the
 * session context.
 *
 * Invoked via:
 *   POST /sws/neo/{spec}/header/{recordId}/action/cloneRecord
 */
@ApplicationScoped
@Named("neoCloneRecordHandler")
public class NeoCloneRecordHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(NeoCloneRecordHandler.class);
  static final String ACTION_NAME = "cloneRecord";

  @Inject
  @Any
  private Instance<CloneRecordHook> cloneHooks;

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    if (!ACTION_NAME.equals(context.getFieldName()) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }

    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }

    Tab adTab = context.getAdTab();
    if (adTab == null || adTab.getTable() == null) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Entity metadata not available");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        Entity dalEntity = ModelProvider.getInstance()
            .getEntityByTableName(adTab.getTable().getDBTableName());
        if (dalEntity == null) {
          return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "Could not resolve DAL entity for table: " + adTab.getTable().getDBTableName());
        }

        @SuppressWarnings("unchecked")
        BaseOBObject source = (BaseOBObject) OBDal.getInstance()
            .get((Class<? extends BaseOBObject>) dalEntity.getMappingClass(), recordId);
        if (source == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
              "Record not found: " + recordId);
        }

        CloneRecordHook hook = selectHook(dalEntity.getName());
        BaseOBObject clone = hook != null
            ? hook.copy(source, false)
            : DalUtil.copy(source, false);

        // Generate document number using the record's own client ID, bypassing
        // RequestContext/VariablesSecureApp which does not carry AD_Client_ID
        // correctly in the NEO action context.
        generateDocumentNumberIfNeeded(dalEntity, clone, adTab.getTable().getDBTableName());

        OBDal.getInstance().save(clone);
        OBDal.getInstance().flush();

        JSONObject data = new JSONObject();
        data.put("id", clone.getId());
        return NeoResponse.createdWithData(data);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      OBDal.getInstance().rollbackAndClose();
      log.warn("Error cloning record {}: {}", recordId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      log.error("Error cloning record {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while cloning the record");
    }
  }

  /**
   * Generates a document number when the entity has a {@code documentNo} property
   * and the hook left it null (standard hook behaviour — they reset it expecting
   * the application layer to assign the next sequence value).
   *
   * {@code DocumentNoHandlerLegacy} fires automatically during the hook's internal
   * flush but cannot resolve the {@code AD_Client_ID} from the NEO request context.
   * This method reads the client ID directly from the cloned record, which is always
   * correct regardless of request context.
   */
  private void generateDocumentNumberIfNeeded(Entity dalEntity, BaseOBObject clone,
      String tableDBName) {
    Property docNoProp = null;
    try {
      docNoProp = dalEntity.getProperty("documentNo");
    } catch (Exception ex) {
      return; // entity has no documentNo property
    }
    if (docNoProp == null || StringUtils.isNotBlank((String) clone.get("documentNo"))) {
      return;
    }

    // Get the client ID directly from the record — not from RequestContext.
    String clientId = "";
    try {
      BaseOBObject client = (BaseOBObject) clone.get("client");
      if (client != null) {
        clientId = (String) client.getId();
      }
    } catch (Exception ex) {
      log.warn("Could not get client from cloned record of {}", dalEntity.getName());
    }
    if (StringUtils.isBlank(clientId) || "0".equals(clientId)) {
      log.warn("Skipping documentNo generation for {} — no valid AD_Client_ID on cloned record",
          dalEntity.getName());
      return;
    }

    // Try doctype-based sequence first (uses C_DocTypeTarget_ID if present, else C_DocType_ID).
    String docTypeId = resolveDocTypeId(clone);

    try {
      DalConnectionProvider conn = new DalConnectionProvider(false);
      CSResponse cs = null;

      if (!docTypeId.isEmpty()) {
        try {
          cs = DocumentNoData.nextDocType(conn, docTypeId, clientId, "Y");
        } catch (Exception ex) {
          log.debug("nextDocType failed for {} doctype {}: {}", dalEntity.getName(), docTypeId,
              ex.getMessage());
        }
      }

      // Fall back to table-level sequence (DocumentNo_{TableName}).
      if (cs == null || StringUtils.isBlank(cs.razon)) {
        try {
          cs = DocumentNoData.nextDoc(conn, "DocumentNo_" + tableDBName, clientId, "Y");
        } catch (Exception ex) {
          log.debug("nextDoc fallback failed for table {}: {}", tableDBName, ex.getMessage());
        }
      }

      if (cs != null && StringUtils.isNotBlank(cs.razon)) {
        clone.set("documentNo", cs.razon);
      } else {
        log.warn("Could not generate documentNo for {} (table {}): no sequence found for client {}",
            dalEntity.getName(), tableDBName, clientId);
      }
    } catch (Exception ex) {
      log.warn("Unexpected error generating documentNo for {}: {}", dalEntity.getName(),
          ex.getMessage());
    }
  }

  /**
   * Returns the doctype ID to use for sequence lookup.
   * Prefers the target/transaction doctype when present (mirrors what
   * {@code Utility.getDocumentNo} does internally).
   */
  private String resolveDocTypeId(BaseOBObject clone) {
    // Try C_DocTypeTarget_ID first (transactionDocument property on Order, similar on Invoice)
    for (String prop : new String[]{"transactionDocument", "documentTypeForDocument"}) {
      try {
        BaseOBObject target = (BaseOBObject) clone.get(prop);
        if (target != null) {
          String id = (String) target.getId();
          if (!StringUtils.isBlank(id)) {
            return id;
          }
        }
      } catch (Exception ignored) {
      }
    }
    // Fall back to primary doctype
    try {
      BaseOBObject docType = (BaseOBObject) clone.get("documentType");
      if (docType != null) {
        String id = (String) docType.getId();
        if (!StringUtils.isBlank(id)) {
          return id;
        }
      }
    } catch (Exception ignored) {
    }
    return "";
  }

  private CloneRecordHook selectHook(String entityName) {
    CloneRecordHook selected = null;
    for (CloneRecordHook hook : cloneHooks.select(new ComponentProvider.Selector(entityName))) {
      if (selected == null || hook.getPriority() < selected.getPriority()) {
        selected = hook;
      } else if (hook.getPriority() == selected.getPriority()) {
        log.warn("Multiple CloneRecordHooks with same priority for entity {}, using first.",
            entityName);
      }
    }
    return selected;
  }
}
