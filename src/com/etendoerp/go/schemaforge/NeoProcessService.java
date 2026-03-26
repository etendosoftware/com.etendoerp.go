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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.process.BaseProcessActionHandler;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessParameter;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Service for executing AD_Process definitions via the Neo headless API.
 *
 * Supports two process types:
 * <ul>
 *   <li>OBUIAPP processes (UIPattern = "Standard") -- invoked via
 *       {@link BaseProcessActionHandler} using reflection on the
 *       protected doExecute method</li>
 *   <li>Classic processes -- invoked directly via
 *       {@link DalBaseProcess#execute(ProcessBundle)}</li>
 * </ul>
 *
 * DB procedure processes are not supported (returns 501).
 */
public class NeoProcessService {

  private static final Logger log = LogManager.getLogger(NeoProcessService.class);

  /** UIPattern value for OBUIAPP (Standard) processes. */
  private static final String UI_PATTERN_STANDARD = "S";

  private NeoProcessService() {
  }

  /**
   * Execute a process with the given parameters.
   *
   * @param process the AD_Process to execute
   * @param params  JSON object with parameter values keyed by DB column name
   * @return NeoResponse with execution result
   */
  public static NeoResponse executeProcess(Process process, JSONObject params) {
    if (params == null) {
      params = new JSONObject();
    }
    try {
      OBContext.setAdminMode();
      try {
        // Validate mandatory parameters
        NeoResponse validationError = validateMandatoryParams(process, params);
        if (validationError != null) {
          return validationError;
        }

        String uiPattern = process.getUIPattern();
        if (UI_PATTERN_STANDARD.equals(uiPattern)
            && StringUtils.isNotBlank(process.getJavaClassName())) {
          return executeObuiappProcess(process, params);
        }

        if (StringUtils.isNotBlank(process.getJavaClassName())) {
          return executeClassicProcess(process, params);
        }

        if (StringUtils.isNotBlank(process.getProcedure())) {
          return NeoResponse.error(501,
              "DB procedure processes are not supported via Neo API: "
                  + process.getProcedure());
        }

        return NeoResponse.error(400,
            "Process has no executable handler: " + process.getName());

      } finally {
        OBContext.restorePreviousMode();
      }

    } catch (Exception e) {
      log.error("Error executing process {}", process.getName(), e);
      return NeoResponse.error(500,
          "Process execution failed: " + e.getMessage());
    }
  }

  /**
   * Execute a process in the context of a specific record.
   * Used for button processes (Type B) tied to an entity record.
   *
   * @param process  the AD_Process to execute
   * @param params   JSON object with parameter values
   * @param recordId the ID of the record the process operates on
   * @param tabId    the AD_Tab_ID for context (can be null)
   * @return NeoResponse with execution result
   */
  public static NeoResponse executeProcess(Process process, JSONObject params,
      String recordId, String tabId) {
    JSONObject enrichedParams;
    try {
      enrichedParams = params != null
          ? new JSONObject(params.toString())
          : new JSONObject();
      enrichedParams.put("inpRecordId", recordId);
      if (tabId != null) {
        enrichedParams.put("inpTabId", tabId);
      }
    } catch (Exception e) {
      log.error("Error enriching params with record context", e);
      return NeoResponse.error(500,
          "Error preparing record context: " + e.getMessage());
    }
    return executeProcess(process, enrichedParams);
  }

  /**
   * List available button actions for a given entity.
   * Finds included ETGO_SF_Field records whose AD_Column is a Button (ref 28)
   * with a linked process.
   *
   * @param specId     the ETGO_SF_Spec ID
   * @param entityName the entity name
   * @return NeoResponse with array of available actions
   */
  public static NeoResponse listActions(String specId, String entityName) {
    try {
      OBContext.setAdminMode();
      try {
        // Find ETGO_SF_Entity by specId + entityName
        OBCriteria<SFEntity> entityCriteria = OBDal.getInstance()
            .createCriteria(SFEntity.class);
        entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
        entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_NAME, entityName));
        entityCriteria.setMaxResults(1);

        SFEntity sfEntity = (SFEntity) entityCriteria.uniqueResult();
        if (sfEntity == null) {
          return NeoResponse.error(404,
              "Entity not found: " + entityName + " in spec " + specId);
        }

        // Find all included, active fields for this entity
        OBCriteria<SFField> fieldCriteria = OBDal.getInstance()
            .createCriteria(SFField.class);
        fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY, sfEntity));
        fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true));
        fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));

        JSONArray actions = new JSONArray();
        List<SFField> fields = fieldCriteria.list();

        for (SFField field : fields) {
          Column column = field.getADColumn();
          if (column == null || column.getReference() == null) {
            continue;
          }

          // Check if column reference is Button (AD_Reference_ID = '28')
          if (!"28".equals(column.getReference().getId())) {
            continue;
          }

          // Check for linked process (classic or OBUIAPP)
          Process process = column.getProcess();
          org.openbravo.client.application.Process obuiappProcess =
              column.getOBUIAPPProcess();

          if (process == null && obuiappProcess == null) {
            continue;
          }

          JSONObject action = new JSONObject();
          action.put("columnName", column.getDBColumnName());

          if (obuiappProcess != null) {
            action.put("processName", obuiappProcess.getName());
            action.put("processId", obuiappProcess.getId());
            action.put("processType", "OBUIAPP");
          } else {
            action.put("processName", process.getName());
            action.put("processId", process.getId());
            action.put("processType", resolveProcessType(process));
          }

          actions.put(action);
        }

        JSONObject result = new JSONObject();
        result.put("actions", actions);
        result.put("count", actions.length());
        return NeoResponse.ok(result);

      } finally {
        OBContext.restorePreviousMode();
      }

    } catch (Exception e) {
      log.error("Error listing actions for entity {}", entityName, e);
      return NeoResponse.error(500,
          "Error listing actions: " + e.getMessage());
    }
  }

  /**
   * Describe a process and its parameters for documentation/discovery.
   *
   * @param process the AD_Process to describe
   * @return NeoResponse with process metadata and parameter list
   */
  public static NeoResponse describeProcess(Process process) {
    try {
      OBContext.setAdminMode();
      try {
        JSONObject result = new JSONObject();
        result.put("id", process.getId());
        result.put("name", process.getName());
        result.put("description",
            StringUtils.defaultString(process.getDescription()));
        result.put("helpComment",
            StringUtils.defaultString(process.getHelpComment()));
        result.put("uiPattern", process.getUIPattern());
        result.put("javaClassName",
            StringUtils.defaultString(process.getJavaClassName()));

        // Determine process type label
        String processType = resolveProcessType(process);
        result.put("processType", processType);

        // Build parameter list
        JSONArray parameters = buildParameterArray(process);
        result.put("parameters", parameters);
        result.put("parameterCount", parameters.length());

        return NeoResponse.ok(result);

      } finally {
        OBContext.restorePreviousMode();
      }

    } catch (Exception e) {
      log.error("Error describing process {}", process.getName(), e);
      return NeoResponse.error(500,
          "Error describing process: " + e.getMessage());
    }
  }

  // ---- Process type resolution ----

  /**
   * Determine a human-readable process type label.
   */
  private static String resolveProcessType(Process process) {
    if (UI_PATTERN_STANDARD.equals(process.getUIPattern())
        && StringUtils.isNotBlank(process.getJavaClassName())) {
      return "OBUIAPP";
    }
    if (StringUtils.isNotBlank(process.getJavaClassName())) {
      return "Classic";
    }
    if (StringUtils.isNotBlank(process.getProcedure())) {
      return "DBProcedure";
    }
    return "Unknown";
  }

  // ---- Parameter metadata ----

  /**
   * Build a JSONArray describing all active parameters of a process.
   */
  private static JSONArray buildParameterArray(Process process)
      throws Exception {
    JSONArray parameters = new JSONArray();
    List<ProcessParameter> paramList = process.getADProcessParameterList();
    for (ProcessParameter param : paramList) {
      if (!Boolean.TRUE.equals(param.isActive())) {
        continue;
      }
      JSONObject paramObj = new JSONObject();
      paramObj.put("name", param.getName());
      paramObj.put("dbColumnName", param.getDBColumnName());
      paramObj.put("sequenceNumber", param.getSequenceNumber());
      paramObj.put("mandatory",
          Boolean.TRUE.equals(param.isMandatory()));
      paramObj.put("defaultValue",
          StringUtils.defaultString(param.getDefaultValue()));
      paramObj.put("description",
          StringUtils.defaultString(param.getDescription()));

      if (param.getReference() != null) {
        paramObj.put("referenceId", param.getReference().getId());
        paramObj.put("referenceType", param.getReference().getName());
      }
      if (param.getReferenceSearchKey() != null) {
        paramObj.put("referenceSearchKeyId",
            param.getReferenceSearchKey().getId());
      }

      paramObj.put("isRange", Boolean.TRUE.equals(param.isRange()));
      paramObj.put("length", param.getLength());

      parameters.put(paramObj);
    }
    return parameters;
  }

  // ---- Execution strategies ----

  /**
   * Execute an OBUIAPP process (BaseProcessActionHandler subclass).
   *
   * Since BaseProcessActionHandler.execute and doExecute are protected,
   * this method uses reflection to invoke doExecute directly on the
   * concrete handler instance obtained from the CDI container.
   */
  private static NeoResponse executeObuiappProcess(Process process,
      JSONObject params) throws Exception {

    String className = process.getJavaClassName();
    Class<?> handlerClass = Class.forName(className);
    Object handlerInstance =
        WeldUtils.getInstanceFromStaticBeanManager(handlerClass);

    if (!(handlerInstance instanceof BaseProcessActionHandler)) {
      return NeoResponse.error(500,
          "Process handler is not a BaseProcessActionHandler: "
              + className);
    }

    // Build the content JSON in the format expected by OBUIAPP handlers:
    // { "_params": { ... }, "_action": "<processId>" }
    JSONObject content = new JSONObject();

    // Build a clean _params object without internal context keys
    JSONObject cleanParams = new JSONObject();
    @SuppressWarnings("unchecked")
    Iterator<String> paramKeys = params.keys();
    while (paramKeys.hasNext()) {
      String pk = paramKeys.next();
      if (!"inpRecordId".equals(pk) && !"inpTabId".equals(pk)) {
        cleanParams.put(pk, params.get(pk));
      }
    }
    content.put("_params", cleanParams);
    content.put("_action", process.getId());

    // Add record context if present (for button processes / Type B)
    if (params.has("inpRecordId")) {
      content.put("inpRecordId", params.getString("inpRecordId"));
    }
    if (params.has("inpTabId")) {
      content.put("inpTabId", params.getString("inpTabId"));
    }

    // Build the parameters map as BaseProcessActionHandler.execute would
    Map<String, Object> handlerParams = new HashMap<>();
    handlerParams.put("processId", process.getId());
    @SuppressWarnings("unchecked")
    Iterator<String> keys = params.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      handlerParams.put(key, params.get(key));
    }

    // Invoke the protected doExecute via reflection.
    // doExecute(Map<String, Object>, String) is the method that concrete
    // handlers override with their business logic.
    Method doExecuteMethod = BaseProcessActionHandler.class
        .getDeclaredMethod("doExecute", Map.class, String.class);
    doExecuteMethod.setAccessible(true);

    JSONObject handlerResult = (JSONObject) doExecuteMethod.invoke(
        handlerInstance, handlerParams, content.toString());

    return translateObuiappResult(handlerResult);
  }

  /**
   * Execute a classic process (DalBaseProcess subclass).
   *
   * Classic processes extend DalBaseProcess and implement
   * doExecute(ProcessBundle). Since DalBaseProcess.execute() requires
   * a full ProcessContext (with VariablesSecureApp from HTTP session),
   * we invoke the protected doExecute method directly via reflection.
   * The caller is responsible for OBContext and transaction management.
   */
  private static NeoResponse executeClassicProcess(Process process,
      JSONObject params) throws Exception {

    String className = process.getJavaClassName();
    Class<?> processClass = Class.forName(className);
    Object processInstance =
        WeldUtils.getInstanceFromStaticBeanManager(processClass);

    if (!(processInstance instanceof DalBaseProcess)) {
      return NeoResponse.error(500,
          "Process class is not a DalBaseProcess: " + className);
    }

    // Build a ProcessBundle using the Map-based constructor
    Map<String, Object> bundleMap = new HashMap<>();
    bundleMap.put(ProcessBundle.FIELD_PROCESS_ID, process.getId());
    ProcessBundle bundle = new ProcessBundle(bundleMap);

    // Set the process parameters
    Map<String, Object> bundleParams = new HashMap<>();
    @SuppressWarnings("unchecked")
    Iterator<String> keys = params.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      // Map internal context keys to classic process conventions
      if ("inpRecordId".equals(key)) {
        bundleParams.put("recordID", params.get(key));
      } else if ("inpTabId".equals(key)) {
        bundleParams.put("tabId", params.get(key));
      } else {
        bundleParams.put(key, params.get(key));
      }
    }
    bundle.setParams(bundleParams);

    // Invoke protected doExecute directly via reflection.
    // We bypass DalBaseProcess.execute() because it requires a
    // ProcessContext with VariablesSecureApp (HTTP session-based).
    // OBContext is already set by the caller (admin mode).
    Method doExecuteMethod = DalBaseProcess.class
        .getDeclaredMethod("doExecute", ProcessBundle.class);
    doExecuteMethod.setAccessible(true);
    doExecuteMethod.invoke(processInstance, bundle);

    // Read the result from the bundle
    Object bundleResult = bundle.getResult();
    return translateClassicResult(bundleResult, process);
  }

  // ---- Validation ----

  /**
   * Validate that all mandatory parameters are present in the request.
   *
   * @return NeoResponse with error if validation fails, null if OK
   */
  private static NeoResponse validateMandatoryParams(Process process,
      JSONObject params) {
    List<ProcessParameter> paramList = process.getADProcessParameterList();
    for (ProcessParameter param : paramList) {
      if (!Boolean.TRUE.equals(param.isActive())) {
        continue;
      }
      if (!Boolean.TRUE.equals(param.isMandatory())) {
        continue;
      }

      String columnName = param.getDBColumnName();
      if (columnName == null) {
        continue;
      }

      if (!params.has(columnName) || params.isNull(columnName)) {
        if (StringUtils.isBlank(param.getDefaultValue())) {
          return NeoResponse.error(400,
              "Missing mandatory parameter: " + columnName
                  + " (" + param.getName() + ")");
        }
      }
    }
    return null;
  }

  // ---- Result translation ----

  /**
   * Translate an OBUIAPP handler result to a NeoResponse.
   * OBUIAPP handlers return JSON with a "message" object containing
   * "severity" and "text" fields.
   */
  @SuppressWarnings("unchecked")
  private static NeoResponse translateObuiappResult(
      JSONObject handlerResult) throws Exception {

    if (handlerResult == null) {
      JSONObject successResult = new JSONObject();
      successResult.put("status", "success");
      return NeoResponse.ok(successResult);
    }

    JSONObject result = new JSONObject();

    if (handlerResult.has("message")) {
      JSONObject message = handlerResult.getJSONObject("message");
      String severity = message.optString("severity", "success");
      String text = message.optString("text", "");

      result.put("status", severity);
      result.put("message", text);

      if ("error".equals(severity)) {
        return new NeoResponse(400, result);
      }
    } else {
      result.put("status", "success");
    }

    // Pass through any additional response data
    Iterator<String> keys = handlerResult.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!"message".equals(key)) {
        result.put(key, handlerResult.get(key));
      }
    }

    return NeoResponse.ok(result);
  }

  /**
   * Translate a classic process result (typically OBError) to a NeoResponse.
   */
  private static NeoResponse translateClassicResult(Object bundleResult,
      Process process) throws Exception {

    JSONObject result = new JSONObject();

    if (bundleResult instanceof OBError) {
      OBError error = (OBError) bundleResult;
      result.put("status", error.getType().toLowerCase());
      result.put("title", StringUtils.defaultString(error.getTitle()));
      result.put("message", StringUtils.defaultString(error.getMessage()));

      if ("error".equalsIgnoreCase(error.getType())) {
        return new NeoResponse(400, result);
      }
      return NeoResponse.ok(result);
    }

    if (bundleResult != null) {
      result.put("status", "success");
      result.put("message", bundleResult.toString());
    } else {
      result.put("status", "success");
      result.put("message",
          "Process " + process.getName() + " executed successfully");
    }

    return NeoResponse.ok(result);
  }
}
