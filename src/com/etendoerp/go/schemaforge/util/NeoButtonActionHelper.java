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
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.common.BoundedJsonParser;
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
    // ETP-5684: posting buttons are not listed to a role without showAccountingFields.
    boolean hidePosting = fields.stream()
        .anyMatch(f -> NeoAccountingAccessPolicy.isPostingButton(f.getADColumn()))
        && !NeoAccountingAccessPolicy.currentRoleSeesAccounting();
    for (SFField field : fields) {
      if (hidePosting && NeoAccountingAccessPolicy.isPostingButton(field.getADColumn())) {
        continue;
      }
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
      actionObj.put("processId", obuiappProcess.getId());
    } else if (classicProcess != null) {
      actionObj.put("processType", "Classic");
      actionObj.put("processName", StringUtils.defaultString(classicProcess.getName()));
      actionObj.put("processId", classicProcess.getId());
    }
    return actionObj;
  }

  /**
   * Executes a button action on a specific record.
   * <p>
   * Extracts {@code recordId} and any JSON body parameters from the HTTP request,
   * then delegates to {@link #executeButtonActionCore(SFEntity, String, String, JSONObject)}
   * so that all guard-clause and process-execution logic is single-sourced.
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
    Object cachedBody = request.getAttribute(NeoServlet.ACTION_REQUEST_BODY_ATTR);
    String bodyStr = cachedBody instanceof String
        ? (String) cachedBody
        : new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    JSONObject params = StringUtils.isNotBlank(bodyStr) ? BoundedJsonParser.parseObject(bodyStr) : null;
    return executeButtonActionCore(entity, pathInfo.recordId, pathInfo.actionName, params);
  }

  /**
   * Servlet-free core: executes a button action given plain parameters.
   * Called by the MCP router. The existing servlet path delegates here.
   *
   * @param entity     the SFEntity that owns the button field
   * @param recordId   the record ID to act upon
   * @param actionName the column name or Java qualifier of the button field
   * @param params     optional JSON parameters (may be null → treated as empty object)
   * @return NeoResponse with the process result or an error response
   * @throws Exception if process resolution, execution, or JSON construction fails
   */
  public static NeoResponse executeButtonActionCore(SFEntity entity, String recordId,
      String actionName, JSONObject params) throws Exception {
    Column targetColumn = findButtonColumn(entity.getId(), actionName);
    if (targetColumn == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Action not found: " + actionName);
    }
    if (targetColumn.getReference() == null
        || !"28".equals((String) targetColumn.getReference().getId())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Field is not a button: " + actionName);
    }
    org.openbravo.client.application.Process obuiappProcess = targetColumn.getOBUIAPPProcess();
    Process adProcess = targetColumn.getProcess();
    if (adProcess == null && obuiappProcess == null) {
      obuiappProcess = NeoAccessHelper.resolveFallbackObuiappProcess(targetColumn);
      if (adProcess == null && obuiappProcess == null) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "No process linked to button: " + actionName);
      }
    }
    JSONObject effectiveParams = params != null ? params : new JSONObject();
    effectiveParams.put("recordId", recordId);
    effectiveParams.put("inpRecordId", recordId);
    addTabParamsCore(entity, recordId, effectiveParams);
    NeoResponse denied = processAccessRefusal(adProcess, obuiappProcess);
    if (denied != null) {
      return denied;
    }
    return obuiappProcess != null
        ? NeoProcessService.executeObuiappProcess(obuiappProcess, effectiveParams)
        : NeoProcessService.executeProcess(adProcess, effectiveParams);
  }

  /**
   * Whether the current role may run the process behind a button, resolved as
   * {@link #executeButtonActionCore} resolves it. ETP-5684: a reactivation un-posts (and commits)
   * before its process runs, so it asks this first — a role that cannot run the process must not
   * get the un-post either.
   *
   * Fails closed: an action that does not resolve to a button process is refused with the
   * error the action itself would answer, so nothing is un-posted for it.
   *
   * @param entity the entity the action belongs to; {@code null} is refused
   * @param actionName the action (button) name, as the request names it
   * @return the error the action would answer, or {@code null} when the role may run it
   */
  public static NeoResponse refusalForButtonProcess(SFEntity entity, String actionName) {
    Column column = entity == null ? null : findButtonColumn(entity.getId(), actionName);
    if (column == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Action not found: " + actionName);
    }
    org.openbravo.client.application.Process obuiappProcess = column.getOBUIAPPProcess();
    Process adProcess = column.getProcess();
    if (adProcess == null && obuiappProcess == null) {
      obuiappProcess = NeoAccessHelper.resolveFallbackObuiappProcess(column);
      if (obuiappProcess == null) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "No process linked to button: " + actionName);
      }
    }
    return processAccessRefusal(adProcess, obuiappProcess);
  }

  private static NeoResponse processAccessRefusal(Process adProcess,
      org.openbravo.client.application.Process obuiappProcess) {
    boolean allowed = obuiappProcess != null
        ? NeoAccessHelper.hasObuiappProcessAccess(obuiappProcess.getId())
        : adProcess == null || NeoAccessHelper.hasProcessAccess(adProcess.getId());
    return allowed ? null
        : NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
            "Access denied to process for current role");
  }

  private static void addTabParamsCore(SFEntity entity, String recordId,
      JSONObject params) throws Exception {
    if (entity.getADTab() == null) {
      return;
    }
    params.put("inpTabId", entity.getADTab().getId());
    Table table = entity.getADTab().getTable();
    String tableName = table != null ? table.getDBTableName() : null;
    if (tableName == null) {
      return;
    }
    String derivedKey = tableName + "_ID";
    params.put(derivedKey, recordId);
    // ETP-5447: the table name and its key column do not always share casing. Classic
    // FIN_BankStatementProcess reads "FIN_Bankstatement_ID" — the real key column of
    // FIN_BankStatement — so "FIN_BankStatement_ID" alone left its recordID null and the process
    // failed with "id to load is required for loading". The derived key is kept for processes
    // that read the table-name casing.
    String keyColumn = findKeyColumnName(table);
    if (keyColumn != null && !keyColumn.equals(derivedKey)) {
      params.put(keyColumn, recordId);
    }
  }

  /**
   * @return the DB name of the table's primary-key column ({@code AD_Column.IsKey = 'Y'}), or
   *         {@code null} when the table declares none
   */
  private static String findKeyColumnName(Table table) {
    // ETP-5684: re-read by id, as a reactivation's un-post commits and closes the session first,
    // detaching the table this request loaded (its lazy column list would throw).
    Table current = OBDal.getInstance().get(Table.class, table.getId());
    List<Column> columns = current != null ? current.getADColumnList() : null;
    if (columns == null) {
      return null;
    }
    for (Column column : columns) {
      if (Boolean.TRUE.equals(column.isKeyColumn())) {
        return column.getDBColumnName();
      }
    }
    return null;
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
   * Matches against the DB column name and the field's Java qualifier first; then, for button
   * columns only, against the DAL property name.
   *
   * <p>ETP-5669: {@code etendo_schema} advertises a button by its DAL property, and the pushed
   * qualifier does not always equal it (e.g. {@code C_Order.RM_CreateInvoice}: qualifier
   * {@code rMCreateInvoice}, property {@code createInvoice}). The second pass never shadows an
   * exact match of the first.
   *
   * @param entityId   the ID of the entity to search within
   * @param actionName the action identifier (DB column name, Java qualifier or DAL property)
   * @return the matching {@link org.openbravo.model.ad.datamodel.Column}, or {@code null} if not found
   */
  public static Column findButtonColumn(String entityId, String actionName) {
    List<SFField> fields = loadEntityFields(entityId);
    for (SFField field : fields) {
      Column column = field.getADColumn();
      if (column == null) continue;
      // Match by DB column name (e.g. "Processing") or by camelCase field name (e.g. "processNow")
      if (actionName.equals(column.getDBColumnName())
          || actionName.equals(field.getJavaQualifier())) {
        return column;
      }
    }
    for (SFField field : fields) {
      Column column = field.getADColumn();
      if (column != null && column.getReference() != null
          && "28".equals((String) column.getReference().getId())
          && actionName.equals(propertyNameOf(column))) {
        return column;
      }
    }
    return null;
  }

  /**
   * The DAL property name of a column (e.g. {@code documentAction} for {@code GL_Journal.DocAction}),
   * or {@code null} when the table or column is not in the runtime model.
   *
   * @param column the AD column
   * @return the property name, or {@code null}
   */
  public static String propertyNameOf(Column column) {
    try {
      Entity dal = ModelProvider.getInstance()
          .getEntityByTableName(column.getTable().getDBTableName());
      Property property = dal == null ? null : dal.getPropertyByColumnName(column.getDBColumnName());
      return property == null ? null : property.getName();
    } catch (RuntimeException e) {
      return null;
    }
  }
}
