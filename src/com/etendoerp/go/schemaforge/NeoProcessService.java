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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.process.BaseProcessActionHandler;
import org.openbravo.client.kernel.BaseActionHandler;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessParameter;
import org.openbravo.model.ad.ui.ProcessParameterTrl;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.CallProcess;
import org.openbravo.model.ad.domain.ModelImplementation;
import org.openbravo.service.db.DalBaseProcess;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Service for executing AD_Process definitions via the Neo headless API.
 *
 * Supports three process types:
 * <ul>
 *   <li>OBUIAPP processes (UIPattern = "Standard") -- invoked via
 *       {@link BaseProcessActionHandler} using reflection on the
 *       protected doExecute method</li>
 *   <li>Classic processes extending {@link DalBaseProcess} -- invoked via
 *       reflection on the protected doExecute method</li>
 *   <li>Scheduling processes implementing {@link Process} -- invoked via
 *       {@link Process#execute(ProcessBundle)}</li>
 * </ul>
 */
public class NeoProcessService {

  private static final Logger log = LogManager.getLogger(NeoProcessService.class);

  /** UIPattern value for OBUIAPP (Standard) processes. */
  private static final String UI_PATTERN_STANDARD = "S";

  public static final String MESSAGE = "message";
  public static final String PROCESS_TYPE = "processType";
  public static final String INP_RECORD_ID = "inpRecordId";
  public static final String INP_TAB_ID = "inpTabId";
  public static final String STATUS = "status";
  public static final String ERROR = "error";
  public static final String SUCCESS = "success";
  public static final String PROCESS_ID = "processId";
  private static final String ACCESS_DENIED_FOR_CURRENT_ROLE =
      "Access denied to process for current role";

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
    if (process == null || !NeoAccessHelper.hasProcessAccess(process.getId())) {
      return NeoResponse.error(403, ACCESS_DENIED_FOR_CURRENT_ROLE);
    }
    try {
      OBContext.setAdminMode();
      try {
        NeoResponse validationError = validateMandatoryParams(process, params);
        if (validationError != null) {
          return validationError;
        }
        return executeResolvedProcess(process, params);
      } finally {
        OBContext.restorePreviousMode();
      }

    } catch (Exception e) {
      log.error("Error executing process {}", process.getName(), e);
      return NeoResponse.error(500,
          "Process execution failed: " + e.getMessage());
    }
  }

  private static NeoResponse executeResolvedProcess(Process process, JSONObject params)
      throws Exception {
    if (isStandardUiProcess(process)) {
      return executeObuiappProcess(process, params);
    }

    String className = resolveProcessClassName(process);
    if (StringUtils.isNotBlank(className)) {
      return executeClassBackedProcess(process, params, className);
    }

    if (StringUtils.isNotBlank(process.getProcedure())) {
      return executeDbProcedure(process, params);
    }

    return NeoResponse.error(400,
        "Process has no executable handler: " + process.getName());
  }

  private static boolean isStandardUiProcess(Process process) {
    return UI_PATTERN_STANDARD.equals(process.getUIPattern())
        && StringUtils.isNotBlank(process.getJavaClassName());
  }

  private static String resolveProcessClassName(Process process) {
    String className = process.getJavaClassName();
    if (StringUtils.isBlank(className)) {
      return resolveModelImplementationClass(process);
    }
    return className;
  }

  private static NeoResponse executeClassBackedProcess(Process process, JSONObject params,
      String className) throws Exception {
    Class<?> cls = loadClass(className);
    if (cls == null) {
      return NeoResponse.error(500,
          "Process class not found: " + className);
    }
    if (DalBaseProcess.class.isAssignableFrom(cls)) {
      return executeClassicProcess(process, cls, params);
    }
    if (org.openbravo.scheduling.Process.class.isAssignableFrom(cls)) {
      return executeSchedulingProcess(process, cls, params);
    }
    return NeoResponse.error(500,
        "Process class is not a supported handler type: " + className);
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
      enrichedParams.put(INP_RECORD_ID, recordId);
      if (tabId != null) {
        enrichedParams.put(INP_TAB_ID, tabId);
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
          collectColumnInfo(column, actions);
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

  private static void collectColumnInfo(Column column, JSONArray actions) throws JSONException {
    if (column != null && column.getReference() != null && "28".equals(column.getReference().getId())) {
      // Check for linked process (classic or OBUIAPP), then apply shared fallback policy
      Process process = column.getProcess();
      org.openbravo.client.application.Process obuiappProcess =
          column.getOBUIAPPProcess();

      if (process == null && obuiappProcess == null) {
        obuiappProcess = com.etendoerp.go.schemaforge.util.NeoAccessHelper
            .resolveFallbackObuiappProcess(column);
      }


      if (obuiappProcess != null || process != null) {
        JSONObject action = new JSONObject();
        action.put("columnName", column.getDBColumnName());

        if (obuiappProcess != null) {
          action.put("processName", obuiappProcess.getName());
          action.put(PROCESS_ID, obuiappProcess.getId());
          action.put(PROCESS_TYPE, "OBUIAPP");
        } else {
          action.put("processName", process.getName());
          action.put(PROCESS_ID, process.getId());
          action.put(PROCESS_TYPE, resolveProcessType(process));
        }

        actions.put(action);
      }
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
        result.put(PROCESS_TYPE, processType);

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
  private static String getTranslatedParamName(ProcessParameter param, Language lang) {
    if (lang == null) return param.getName();
    OBCriteria<ProcessParameterTrl> criteria = OBDal.getInstance()
        .createCriteria(ProcessParameterTrl.class);
    criteria.add(Restrictions.eq(ProcessParameterTrl.PROPERTY_PROCESSPARAMETER, param));
    criteria.add(Restrictions.eq(ProcessParameterTrl.PROPERTY_LANGUAGE, lang));
    criteria.setMaxResults(1);
    ProcessParameterTrl trl = (ProcessParameterTrl) criteria.uniqueResult();
    return (trl != null && trl.getName() != null) ? trl.getName() : param.getName();
  }

  private static JSONArray buildParameterArray(Process process)
      throws JSONException {
    Language lang = OBContext.getOBContext().getLanguage();
    JSONArray parameters = new JSONArray();
    List<ProcessParameter> paramList = process.getADProcessParameterList();

    for (ProcessParameter param : paramList) {
      if (!Boolean.TRUE.equals(param.isActive())) {
        continue;
      }
      JSONObject paramObj = new JSONObject();
      paramObj.put("name", getTranslatedParamName(param, lang));
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

  private static Class<?> loadClass(String className) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  /**
   * Converts a JSONObject of process parameters into the Map format expected
   * by ProcessBundle. Maps NEO internal keys to classic process conventions:
   * inpRecordId → recordID, inpTabId → tabId.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> buildBundleParams(JSONObject params) throws JSONException {
    if (params == null) {
      return new HashMap<>();
    }
    Map<String, Object> bundleParams = new HashMap<>();
    Iterator<String> keys = params.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = params.isNull(key) ? null : params.get(key);
      if (INP_RECORD_ID.equals(key)) {
        bundleParams.put("recordID", value);
      } else if (INP_TAB_ID.equals(key)) {
        bundleParams.put("tabId", value);
      } else {
        bundleParams.put(key, value);
      }
    }
    return bundleParams;
  }

  // ---- Execution strategies ----

  /**
   * Execute an OBUIAPP process (BaseProcessActionHandler subclass).
   *
   * Since BaseProcessActionHandler.execute and doExecute are protected,
   * this method uses reflection to invoke doExecute directly on the
   * concrete handler instance obtained from the CDI container.
   *
   * @param process the AD_Process to execute (must have a valid Java class name)
   * @param params  JSON object with parameter values (e.g. inpRecordId, inpTabId)
   * @return NeoResponse with the execution result
   * @throws Exception if reflection or process execution fails
   */
  public static NeoResponse executeObuiappProcess(Process process,
      JSONObject params) throws Exception {
    if (!NeoAccessHelper.hasProcessAccess(process.getId())) {
      return NeoResponse.error(403, ACCESS_DENIED_FOR_CURRENT_ROLE);
    }
    return executeObuiappHandler(process.getJavaClassName(), process.getId(), params);
  }

  /**
   * Execute an OBUIAPP process directly from an
   * {@link org.openbravo.client.application.Process} entity.
   * Used for button columns that only have an OBUIAPP process linked
   * (no classic AD_Process fallback).
   *
   * @param obuiappProcess the OBUIAPP process definition
   * @param params         JSON object with parameter values.
   *                       Should include inpRecordId and optionally inpTabId.
   * @return NeoResponse with execution result
   */
  public static NeoResponse executeObuiappProcess(
      org.openbravo.client.application.Process obuiappProcess,
      JSONObject params) {
    if (obuiappProcess == null
        || !NeoAccessHelper.hasObuiappProcessAccess(obuiappProcess.getId())) {
      return NeoResponse.error(403, ACCESS_DENIED_FOR_CURRENT_ROLE);
    }
    if (params == null) {
      params = new JSONObject();
    }
    try {
      OBContext.setAdminMode();
      try {
        return executeObuiappHandler(
            obuiappProcess.getJavaClassName(),
            obuiappProcess.getId(),
            params);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error executing OBUIAPP process {}", obuiappProcess.getName(), e);
      return NeoResponse.error(500,
          "Process execution failed: " + e.getMessage());
    }
  }

  /**
   * Execute an OBUIAPP action handler by Java class name.
   *
   * <p>Used when an AD-level OBUIAPP definition points to a client-side hook
   * string (for example {@code OB.AEATSII.send}) but the runtime integration
   * needs to call the underlying server-side action handler class directly.
   */
  public static NeoResponse executeObuiappClass(String className, String processId,
      JSONObject params) {
    if (params == null) {
      params = new JSONObject();
    }
    try {
      OBContext.setAdminMode();
      try {
        return executeObuiappHandler(className, processId, params);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error executing OBUIAPP action handler {}", className, e);
      return NeoResponse.error(500,
          "Process execution failed: " + e.getMessage());
    }
  }

  /**
   * Pushes a VariablesSecureApp into RequestContext and restores the previous one on close.
   */
  private static RequestContextScope pushRequestContextVars() {
    VariablesSecureApp previous = null;
    try {
      previous = RequestContext.get().getVariablesSecureApp();
    } catch (Exception ignored) {
      previous = null;
    }

    try {
      VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(
          OBContext.getOBContext());
      RequestContext.get().setVariableSecureApp(vars);
    } catch (Exception e) {
      log.warn("Could not set VariablesSecureApp on RequestContext: {}",
          e.getMessage());
    }
    return new RequestContextScope(previous);
  }

  /**
   * Shared implementation for OBUIAPP process execution.
   * Builds the content/params JSON and invokes doExecute via reflection.
   */
  private static NeoResponse executeObuiappHandler(String className,
      String processId, JSONObject params) throws Exception {

    Class<?> handlerClass = Class.forName(className);
    Object handlerInstance =
        WeldUtils.getInstanceFromStaticBeanManager(handlerClass);

    if (!(handlerInstance instanceof BaseActionHandler)) {
      return NeoResponse.error(500,
          "Process handler is not a BaseActionHandler: "
              + className);
    }

    // Build the content JSON in the format expected by OBUIAPP handlers:
    // an JSON with _params (object) and _action: "processId"
    JSONObject content = new JSONObject();

    // Build a clean _params object without internal context keys
    JSONObject cleanParams = new JSONObject();
    @SuppressWarnings("unchecked")
    Iterator<String> paramKeys = params.keys();
    while (paramKeys.hasNext()) {
      String pk = paramKeys.next();
      if (!INP_RECORD_ID.equals(pk) && !INP_TAB_ID.equals(pk)) {
        cleanParams.put(pk, params.get(pk));
      }
    }
    content.put("_params", cleanParams);
    content.put("_action", processId);

    // Add record context if present (for button processes / Type B).
    // SMF Jobs (Action/Data framework) expects _entityName, recordIds,
    // and inpKeyName to resolve the input records.
    if (params.has(INP_RECORD_ID)) {
      String recordId = params.getString(INP_RECORD_ID);
      content.put(INP_RECORD_ID, recordId);

      // Provide recordIds array and inpKeyName for SMF Jobs Data constructor.
      // The Data class uses inpKeyName to locate the single-record ID in the JSON,
      // and recordIds as a batch selection list.
      JSONArray recordIds = new JSONArray();
      recordIds.put(recordId);
      content.put("recordIds", recordIds);
      content.put("inpKeyName", INP_RECORD_ID);
    }
    if (params.has(INP_TAB_ID)) {
      String tabId = params.getString(INP_TAB_ID);
      content.put(INP_TAB_ID, tabId);

      // Resolve DAL entity name from tab for SMF Jobs framework
      try {
        Tab tab = OBDal.getInstance().get(Tab.class, tabId);
        if (tab != null && tab.getTable() != null) {
          String dalEntityName = tab.getTable().getName();
          content.put("_entityName", dalEntityName);
        }
      } catch (Exception e) {
        log.debug("Could not resolve entity name from tab {}", tabId, e);
      }
    }

    try (RequestContextScope ignored = pushRequestContextVars()) {
      // Build the parameters map as BaseProcessActionHandler.execute would
      Map<String, Object> handlerParams = new HashMap<>();
      handlerParams.put(PROCESS_ID, processId);
      @SuppressWarnings("unchecked")
      Iterator<String> keys = params.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        handlerParams.put(key, params.get(key));
      }

      JSONObject handlerResult = invokeObuiappHandler(
          handlerInstance, handlerParams, content.toString());

      return translateObuiappResult(handlerResult);
    }
  }

  private static JSONObject invokeObuiappHandler(Object handlerInstance,
      Map<String, Object> handlerParams, String content) throws Exception {
    Class<?> baseClass = handlerInstance instanceof BaseProcessActionHandler
        ? BaseProcessActionHandler.class
        : BaseActionHandler.class;
    Method executeMethod = baseClass.getDeclaredMethod("execute", Map.class, String.class);
    executeMethod.setAccessible(true);
    String handlerContent = content;
    if (!(handlerInstance instanceof BaseProcessActionHandler)) {
      handlerContent = new JSONObject(handlerParams).toString();
    }
    return (JSONObject) executeMethod.invoke(handlerInstance, handlerParams, handlerContent);
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
      Class<?> processClass, JSONObject params) throws Exception {

    Object processInstance =
        WeldUtils.getInstanceFromStaticBeanManager(processClass);

    if (!(processInstance instanceof DalBaseProcess)) {
      return NeoResponse.error(500,
          "Process class is not a DalBaseProcess: " + processClass.getName());
    }

    // Build a ProcessBundle using the Map-based constructor
    Map<String, Object> bundleMap = new HashMap<>();
    bundleMap.put(ProcessBundle.FIELD_PROCESS_ID, process.getId());
    ProcessBundle bundle = new ProcessBundle(bundleMap);

    // Set the process parameters
    bundle.setParams(buildBundleParams(params));

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

  /**
   * Execute a scheduling process (implements {@link Process}).
   *
   * These processes are called via {@link Process#execute(ProcessBundle)}.
   * The bundle params include both generic keys (recordID, inpRecordId) and
   * the table-specific key injected by the caller (e.g. M_Inventory_ID).
   */
  private static NeoResponse executeSchedulingProcess(
      org.openbravo.model.ad.ui.Process adProcess,
      Class<?> processClass,
      JSONObject params) throws Exception {
    Object processInstance = WeldUtils.getInstanceFromStaticBeanManager(processClass);
    if (!(processInstance instanceof org.openbravo.scheduling.Process)) {
      return NeoResponse.error(500,
          "Process class does not implement org.openbravo.scheduling.Process: "
          + processClass.getName());
    }
    // Use the VariablesSecureApp constructor so ProcessBundle.getContext() is non-null.
    // Scheduling processes (e.g. InventoryCountProcess) call getContext().getLanguage()
    // internally; the Map-based constructor leaves context null.
    VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(
        OBContext.getOBContext());
    ProcessBundle bundle = new ProcessBundle(adProcess.getId(), vars);
    bundle.setParams(buildBundleParams(params));
    ((org.openbravo.scheduling.Process) processInstance).execute(bundle);
    Object bundleResult = bundle.getResult();
    return translateClassicResult(bundleResult, adProcess);
  }

  /**
   * Execute a DB procedure process via CallProcess.
   *
   * For DocAction-style processes (C_Order_Post, M_InOut_Post0, etc.),
   * the caller should pass a "docAction" parameter with the action code
   * (e.g., "CO" for Complete, "RE" for Reactivate). This method sets the
   * DocumentAction field on the record before calling the procedure.
   */
  private static NeoResponse executeDbProcedure(Process process,
      JSONObject params) throws Exception {

    try (RequestContextScope ignored = pushRequestContextVars()) {
      String recordId = params.optString("recordId",
          params.optString(INP_RECORD_ID, null));

      if (recordId == null || recordId.isBlank()) {
        return NeoResponse.error(400,
            "DB procedure requires a recordId");
      }

      // For DocAction processes: set the action on the document before calling
      String docAction = params.optString("docAction", null);
      if (docAction != null && !docAction.isBlank()) {
        String tabId = params.optString(INP_TAB_ID, null);
        if (tabId != null) {
          setDocAction(tabId, recordId, docAction);
        }
      }

      // Build parameters map for CallProcess (excludes internal context keys)
      Map<String, String> procParams = null;
      @SuppressWarnings("unchecked")
      Iterator<String> keys = params.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if ("recordId".equals(key) || INP_RECORD_ID.equals(key)
            || INP_TAB_ID.equals(key) || "docAction".equals(key)) {
          continue;
        }
        if (procParams == null) {
          procParams = new HashMap<>();
        }
        procParams.put(key, params.optString(key));
      }

      ProcessInstance pInstance = CallProcess.getInstance()
          .call(process, recordId, procParams);

      // Refresh to get the result written by the procedure
      OBDal.getInstance().getSession().refresh(pInstance);

      return translatePInstanceResult(pInstance, process);
    }
  }

  private static void setDocAction(String tabId, String recordId, String docAction) {
    try {
      Tab tab = OBDal.getInstance().get(Tab.class, tabId);
      if (tab != null && tab.getTable() != null) {
        String dalEntityName = tab.getTable().getName();
        BaseOBObject rec = OBDal.getInstance().get(dalEntityName, recordId);
        if (rec != null && rec.getEntity().hasProperty("documentAction")) {
          rec.set("documentAction", docAction);
          OBDal.getInstance().save(rec);
          OBDal.getInstance().flush();
        }
      }
    } catch (Exception e) {
      log.warn("Could not set docAction on record: {}", e.getMessage());
    }
  }

  /**
   * Translate a ProcessInstance result (from CallProcess/DB procedure) to NeoResponse.
   */
  private static NeoResponse translatePInstanceResult(
      ProcessInstance pInstance, Process process) throws JSONException {

    JSONObject result = new JSONObject();
    long resultCode = pInstance.getResult() != null ? pInstance.getResult() : 0L;
    String errorMsg = pInstance.getErrorMsg();

    if (resultCode == 0L) {
      // Error
      String cleanMsg = errorMsg != null
          ? errorMsg.replaceFirst("@ERROR=", "") : "Process failed";
      result.put(STATUS, ERROR);
      result.put(MESSAGE, cleanMsg);
      return new NeoResponse(400, result);
    }

    // Success
    result.put(STATUS, SUCCESS);
    if (errorMsg != null && !errorMsg.isBlank()) {
      result.put(MESSAGE, errorMsg.replaceFirst("@SUCCESS=", ""));
    } else {
      result.put(MESSAGE,
          "Process " + process.getName() + " executed successfully");
    }
    return NeoResponse.ok(result);
  }

  private static final class RequestContextScope implements AutoCloseable {
    private final VariablesSecureApp previous;

    private RequestContextScope(VariablesSecureApp previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      try {
        RequestContext.get().setVariableSecureApp(previous);
      } catch (Exception e) {
        log.debug("Could not restore VariablesSecureApp on RequestContext: {}", e.getMessage());
      }
    }
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
      String columnName = param.getDBColumnName();
      if (Boolean.TRUE.equals(param.isActive()) && Boolean.TRUE.equals(param.isMandatory()) && columnName != null
          && (!params.has(columnName) || params.isNull(columnName)) && StringUtils.isBlank(param.getDefaultValue())) {
        return NeoResponse.error(400,
            "Missing mandatory parameter: " + columnName
                + " (" + param.getName() + ")");
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
      JSONObject handlerResult) throws JSONException {

    if (handlerResult == null) {
      JSONObject successResult = new JSONObject();
      successResult.put(STATUS, SUCCESS);
      return NeoResponse.ok(successResult);
    }

    JSONObject result = new JSONObject();

    if (handlerResult.has(MESSAGE)) {
      JSONObject message = handlerResult.getJSONObject(MESSAGE);
      String severity = message.optString("severity", SUCCESS);
      String text = message.optString("text", "");

      result.put(STATUS, severity);
      result.put(MESSAGE, text);

      if (ERROR.equals(severity)) {
        return new NeoResponse(400, result);
      }
    } else {
      result.put(STATUS, SUCCESS);
    }

    // Pass through any additional response data
    Iterator<String> keys = handlerResult.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!MESSAGE.equals(key)) {
        result.put(key, handlerResult.get(key));
      }
    }

    return NeoResponse.ok(result);
  }

  /**
   * Resolve the Java class name from the AD_Model_Object table (Process Class subtab).
   * Returns the classname of the default implementation with action = 'P' (Process),
   * or null if none found.
   */
  private static String resolveModelImplementationClass(
      org.openbravo.model.ad.ui.Process process) {
    try {
      List<ModelImplementation> impls = process.getADModelImplementationList();
      for (ModelImplementation impl : impls) {
        if (Boolean.TRUE.equals(impl.isDefault())
            && "P".equals(impl.getAction())
            && StringUtils.isNotBlank(impl.getJavaClassName())) {
          return impl.getJavaClassName();
        }
      }
      // Fallback: return the first non-blank classname with action = 'P'
      for (ModelImplementation impl : impls) {
        if ("P".equals(impl.getAction())
            && StringUtils.isNotBlank(impl.getJavaClassName())) {
          return impl.getJavaClassName();
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve model implementation class for process {}: {}",
          process.getName(), e.getMessage());
    }
    return null;
  }

  /**
   * Translate a classic process result (typically OBError) to a NeoResponse.
   */
  private static NeoResponse translateClassicResult(Object bundleResult,
      Process process) throws JSONException {

    JSONObject result = new JSONObject();

    if (bundleResult instanceof OBError) {
      OBError error = (OBError) bundleResult;
      result.put(STATUS, error.getType().toLowerCase());
      result.put("title", StringUtils.defaultString(error.getTitle()));
      result.put(MESSAGE, StringUtils.defaultString(error.getMessage()));

      if (ERROR.equalsIgnoreCase(error.getType())) {
        return new NeoResponse(400, result);
      }
      return NeoResponse.ok(result);
    }

    if (bundleResult != null) {
      result.put(STATUS, SUCCESS);
      result.put(MESSAGE, bundleResult.toString());
    } else {
      result.put(STATUS, SUCCESS);
      result.put(MESSAGE,
          "Process " + process.getName() + " executed successfully");
    }

    return NeoResponse.ok(result);
  }
}
