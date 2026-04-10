/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Handles button/action endpoint requests for NEO Headless.
 *
 * <p>GET  /sws/neo/{spec}/{entity}/{recordId}/action         — list available actions
 * <p>POST /sws/neo/{spec}/{entity}/{recordId}/action/{name}  — execute a button action
 */
class NeoButtonHandler {

  private static final Logger log = LogManager.getLogger(NeoButtonHandler.class);

  /**
   * Handle button action requests.
   * GET with no actionName: list available button actions for the entity.
   * POST with actionName: execute the button process for a specific record.
   */
  NeoResponse handleButtonAction(SFSpec spec, NeoServlet.NeoPathInfo pathInfo,
      String method, HttpServletRequest request) {
    try {
      SFEntity entity = findEntity(spec.getId(), pathInfo.entityName);
      if (entity == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
            "Entity not found in spec: " + pathInfo.entityName);
      }
      if ("GET".equals(method) && pathInfo.actionName == null) {
        return listButtonActions(entity.getId());
      }
      if ("POST".equals(method) && pathInfo.actionName != null) {
        return executeButtonAction(entity.getId(), pathInfo, request);
      }
      if ("GET".equals(method)) {
        return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Use POST to execute an action, GET is only for listing actions");
      }
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "POST requires an action name: /{spec}/{entity}/{recordId}/action/{columnName}");
    } catch (Exception e) {
      log.error("Error handling button action: {}", e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Action error: " + e.getMessage());
    }
  }

  /**
   * Lists all button-type actions available on an entity.
   * Returns a JSON array of action objects with columnName, processType, and processName.
   */
  private NeoResponse listButtonActions(String entityId) throws Exception {
    OBCriteria<SFField> fieldCriteria = OBDal.getInstance().createCriteria(SFField.class);
    fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entityId));
    fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true));
    fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + "." + SFEntity.PROPERTY_ETGOSFSPEC + "." + SFSpec.PROPERTY_ISACTIVE, true));
    List<SFField> fields = fieldCriteria.list();

    JSONArray actions = new JSONArray();
    for (SFField field : fields) {
      JSONObject actionObj = buildButtonActionObject(field);
      if (actionObj != null) {
        actions.put(actionObj);
      }
    }
    JSONObject responseBody = new JSONObject();
    responseBody.put("actions", actions);
    return NeoResponse.ok(responseBody);
  }

  /**
   * Builds a button action JSON object for a given field.
   * Returns null if the field's column is not a button-type with an associated process.
   */
  private JSONObject buildButtonActionObject(SFField field) throws Exception {
    Column column = field.getADColumn();
    if (column == null || column.getReference() == null
        || !"28".equals((String) column.getReference().getId())) {
      return null;
    }
    Process classicProcess = column.getProcess();
    Object obuiappProcess = column.getOBUIAPPProcess();
    if (classicProcess == null && obuiappProcess == null) {
      return null;
    }
    JSONObject actionObj = new JSONObject();
    actionObj.put("columnName", column.getDBColumnName());
    if (obuiappProcess != null) {
      actionObj.put("processType", "OBUIAPP");
      org.openbravo.client.application.Process obuiProc =
          (org.openbravo.client.application.Process) obuiappProcess;
      actionObj.put("processName", obuiProc.getName() != null ? obuiProc.getName() : "");
    } else {
      actionObj.put("processType", "Classic");
      actionObj.put("processName", classicProcess.getName() != null ? classicProcess.getName() : "");
    }
    return actionObj;
  }

  /**
   * Executes a specific button action by column name.
   * Resolves the target column, validates it is a button with a process,
   * checks access, reads request body, and delegates to NeoProcessService.
   */
  private NeoResponse executeButtonAction(String entityId, NeoServlet.NeoPathInfo pathInfo,
      HttpServletRequest request) throws Exception {
    OBCriteria<SFField> fieldCriteria = OBDal.getInstance().createCriteria(SFField.class);
    fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entityId));
    fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true));
    fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + "." + SFEntity.PROPERTY_ETGOSFSPEC + "." + SFSpec.PROPERTY_ISACTIVE, true));
    List<SFField> fields = fieldCriteria.list();

    Column targetColumn = null;
    for (SFField field : fields) {
      Column column = field.getADColumn();
      if (column != null && pathInfo.actionName.equals(column.getDBColumnName())) {
        targetColumn = column;
        break;
      }
    }
    if (targetColumn == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Action not found: " + pathInfo.actionName);
    }
    if (targetColumn.getReference() == null
        || !"28".equals((String) targetColumn.getReference().getId())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Field is not a button: " + pathInfo.actionName);
    }
    Process adProcess = resolveButtonProcess(targetColumn);
    if (adProcess == null && targetColumn.getOBUIAPPProcess() == null) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "No process linked to button: " + pathInfo.actionName);
    }
    if (adProcess == null) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Button process not supported (OBUIAPP-only process without classic fallback): "
              + pathInfo.actionName);
    }
    if (!hasProcessAccess(adProcess.getId())) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
          "Access denied to process for current role");
    }
    String bodyStr = resolveActionBody(request);
    JSONObject params = StringUtils.isNotBlank(bodyStr) ? new JSONObject(bodyStr) : new JSONObject();
    params.put("recordId", pathInfo.recordId);
    return NeoProcessService.executeProcess(adProcess, params);
  }

  private String resolveActionBody(HttpServletRequest request) throws Exception {
    Object cached = request.getAttribute(NeoServlet.ACTION_REQUEST_BODY_ATTR);
    if (cached instanceof String) {
      return (String) cached;
    }
    return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  /**
   * Resolves the AD_Process for a button column, preferring classic process over OBUIAPP.
   * Returns null if only an OBUIAPP process exists without a classic fallback.
   */
  private Process resolveButtonProcess(Column targetColumn) {
    Process adProcess = targetColumn.getProcess();
    if (adProcess == null && targetColumn.getOBUIAPPProcess() != null) {
      // OBUIAPP-only: attempt classic process lookup as fallback
      adProcess = targetColumn.getProcess();
    }
    return adProcess;
  }

  private boolean hasProcessAccess(String processId) {
    String roleId = OBContext.getOBContext().getRole().getId();
    if ("0".equals(roleId)) {
      return true;
    }
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_PROCESS + ".id", processId));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE + ".id", roleId));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  private SFEntity findEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.ilike(SFEntity.PROPERTY_NAME, entityName,
        org.hibernate.criterion.MatchMode.EXACT));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    List<SFEntity> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }
}
