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

package com.etendoerp.go.schemaforge.util;

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
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoServlet;
import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Static helpers for button action listing and execution.
 */
public final class NeoButtonActionHelper {

  private NeoButtonActionHelper() {
  }

  /**
   * Returns the list of available button actions for the given entity.
   *
   * @param entityId the ID of the {@link com.etendoerp.go.schemaforge.data.SFEntity} to inspect
   * @return a {@link NeoResponse} containing a JSON object with an {@code actions} array
   * @throws Exception if loading fields or building the response fails
   */
  public static NeoResponse listButtonActions(String entityId) throws Exception {
    List<SFField> fields = loadEntityFields(entityId);
    JSONArray actions = new JSONArray();
    for (SFField field : fields) {
      JSONObject actionObj = buildButtonActionEntry(field);
      if (actionObj != null) {
        actions.put(actionObj);
      }
    }
    JSONObject responseBody = new JSONObject();
    responseBody.put("actions", actions);
    return NeoResponse.ok(responseBody);
  }

  /**
   * Builds a JSON descriptor for a single button action field, or returns {@code null}
   * if the field is not a button (AD reference 28) or has no linked process.
   *
   * @param field the {@link com.etendoerp.go.schemaforge.data.SFField} to evaluate
   * @return a {@link JSONObject} with {@code columnName}, {@code processType}, and
   *         {@code processName}, or {@code null} if the field is not an actionable button
   * @throws Exception if JSON construction fails
   */
  public static JSONObject buildButtonActionEntry(SFField field) throws Exception {
    Column column = field.getADColumn();
    if (column == null || column.getReference() == null
        || !"28".equals((String) column.getReference().getId())) {
      return null;
    }
    Process classicProcess = column.getProcess();
    org.openbravo.client.application.Process obuiappProcess = column.getOBUIAPPProcess();
    if (classicProcess == null && obuiappProcess == null) {
      obuiappProcess = NeoAccessHelper.resolveFallbackObuiappProcess(column);
      if (obuiappProcess == null) {
        return null;
      }
    }
    JSONObject actionObj = new JSONObject();
    actionObj.put("columnName", column.getDBColumnName());
    if (obuiappProcess != null) {
      actionObj.put("processType", "OBUIAPP");
      actionObj.put("processName", obuiappProcess.getName() != null ? obuiappProcess.getName() : "");
    } else {
      actionObj.put("processType", "Classic");
      actionObj.put("processName", classicProcess.getName() != null ? classicProcess.getName() : "");
    }
    return actionObj;
  }

  /**
   * Executes a button action on a specific record.
   *
   * @param entity   the entity configuration that owns the button field
   * @param pathInfo parsed path components containing the action name and record ID
   * @param request  the HTTP request carrying optional JSON body parameters
   * @return a {@link NeoResponse} with the process result, or an error response
   *         if the action is not found, the field is not a button, or access is denied
   * @throws Exception if process execution or I/O fails
   */
  public static NeoResponse executeButtonAction(SFEntity entity,
      NeoPathInfo pathInfo, HttpServletRequest request) throws Exception {
    Column targetColumn = findButtonColumn(entity.getId(), pathInfo.actionName);
    if (targetColumn == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Action not found: " + pathInfo.actionName);
    }
    if (targetColumn.getReference() == null
        || !"28".equals((String) targetColumn.getReference().getId())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Field is not a button: " + pathInfo.actionName);
    }
    org.openbravo.client.application.Process obuiappProcess = targetColumn.getOBUIAPPProcess();
    Process adProcess = targetColumn.getProcess();
    if (adProcess == null && obuiappProcess == null) {
      obuiappProcess = NeoAccessHelper.resolveFallbackObuiappProcess(targetColumn);
      if (adProcess == null && obuiappProcess == null) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "No process linked to button: " + pathInfo.actionName);
      }
    }
    Object cachedBody = request.getAttribute(NeoServlet.ACTION_REQUEST_BODY_ATTR);
    String bodyStr = cachedBody instanceof String
        ? (String) cachedBody
        : new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    JSONObject params = StringUtils.isNotBlank(bodyStr) ? new JSONObject(bodyStr) : new JSONObject();
    params.put("recordId", pathInfo.recordId);
    params.put("inpRecordId", pathInfo.recordId);
    addTabParams(entity, pathInfo, params);
    if (obuiappProcess != null) {
      if (!NeoAccessHelper.hasObuiappProcessAccess(obuiappProcess.getId())) {
        return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
            "Access denied to process for current role");
      }
      return NeoProcessService.executeObuiappProcess(obuiappProcess, params);
    }
    if (!NeoAccessHelper.hasProcessAccess(adProcess.getId())) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
          "Access denied to process for current role");
    }
    return NeoProcessService.executeProcess(adProcess, params);
  }

  private static void addTabParams(SFEntity entity, NeoPathInfo pathInfo,
      JSONObject params) throws Exception {
    if (entity.getADTab() == null) {
      return;
    }
    params.put("inpTabId", entity.getADTab().getId());
    // Pass the table-specific ID key expected by scheduling processes (e.g. M_Inventory_ID)
    String tableName = entity.getADTab().getTable() != null
        ? entity.getADTab().getTable().getDBTableName()
        : null;
    if (tableName != null) {
      params.put(tableName + "_ID", pathInfo.recordId);
    }
  }

  /**
   * Loads all active, included fields for the given entity from the database.
   *
   * @param entityId the ID of the {@link com.etendoerp.go.schemaforge.data.SFEntity}
   * @return an ordered list of {@link com.etendoerp.go.schemaforge.data.SFField} records
   */
  public static List<SFField> loadEntityFields(String entityId) {
    OBCriteria<SFField> fieldCriteria = OBDal.getInstance().createCriteria(SFField.class);
    fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entityId));
    fieldCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    fieldCriteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    return fieldCriteria.list();
  }

  /**
   * Finds the AD column that corresponds to the given action name within an entity.
   * Matches against both the DB column name and the field's Java qualifier.
   *
   * @param entityId   the ID of the entity to search within
   * @param actionName the action identifier (DB column name or camelCase Java qualifier)
   * @return the matching {@link org.openbravo.model.ad.datamodel.Column}, or {@code null} if not found
   */
  public static Column findButtonColumn(String entityId, String actionName) {
    for (SFField field : loadEntityFields(entityId)) {
      Column column = field.getADColumn();
      if (column == null) continue;
      // Match by DB column name (e.g. "Processing") or by camelCase field name (e.g. "processNow")
      if (actionName.equals(column.getDBColumnName())
          || actionName.equals(field.getJavaQualifier())) {
        return column;
      }
    }
    return null;
  }
}
