package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Cascade and selector-aux helpers extracted from {@link NeoDefaultsService}.
 */
public class NeoDefaultsCascadeHelper {

  private static final Logger log = LogManager.getLogger(NeoDefaultsCascadeHelper.class);
  private static final int MAX_CALLOUT_CHAIN_DEPTH = 5;
  private static final String FIELD_VALUE = "value";
  private static final String KEY_UPDATES = "updates";
  private static final String KEY_COMBOS = "combos";
  private static final String KEY_SELECTED = "selected";

  private NeoDefaultsCascadeHelper() {
  }

  static Entity resolveDalEntity(SFEntity sfEntity) {
    try {
      Tab adTab = sfEntity.getADTab();
      if (adTab != null && adTab.getTable() != null) {
        return ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
      }
    } catch (Exception e) {
      log.debug("Could not resolve DAL entity: {}", e.getMessage());
    }
    return null;
  }

  static String resolvePropertyName(Entity dalEntity, String dbColumnName) {
    if (dalEntity != null) {
      try {
        Property prop = dalEntity.getPropertyByColumnName(dbColumnName);
        if (prop != null) {
          return prop.getName();
        }
      } catch (Exception e) {
        log.debug("Could not resolve property name for column {}: {}",
            dbColumnName, e.getMessage());
      }
    }
    return NeoCalloutService.toCleanFieldName(dbColumnName);
  }

  static void executeCalloutCascadeForCreate(NeoContext ctx, Tab adTab, JSONObject body) {
    try {
      Set<String> emptySeqFields = new HashSet<>();
      NeoDefaultsService.CalloutCascadeResult cascadeResult =
          executeCalloutCascade(ctx, adTab, body, emptySeqFields);
      if (cascadeResult != null && cascadeResult.hasResults()) {
        log.info("[NEO-CREATE] Callout cascade derived {} field updates",
            cascadeResult.updatedFieldCount());
      }
    } catch (Exception e) {
      log.warn("[NEO-CREATE] Callout cascade failed (non-fatal): {}", e.getMessage());
    }
  }

  public static NeoDefaultsService.CalloutCascadeResult executeCalloutCascade(NeoContext ctx, Tab adTab,
      JSONObject defaults, Set<String> seqFields) {
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();
    try {
      List<String> fieldsWithCallouts = collectFieldsWithCallouts(defaults, seqFields, adTab);
      if (fieldsWithCallouts.isEmpty()) {
        return result;
      }

      log.info("[NEO-DEFAULTS] Callout cascade: {} fields have callouts: {}",
          fieldsWithCallouts.size(), fieldsWithCallouts);

      JSONObject formState = new JSONObject(defaults.toString());
      Set<String> pendingFields = new LinkedHashSet<>(fieldsWithCallouts);
      int depth = 0;

      while (!pendingFields.isEmpty() && depth < MAX_CALLOUT_CHAIN_DEPTH) {
        depth++;
        Set<String> nextPending = new LinkedHashSet<>();
        CalloutFieldContext cCtx = new CalloutFieldContext(formState, defaults, seqFields,
            result, nextPending);
        for (String fieldName : pendingFields) {
          Object value = formState.opt(fieldName);
          if (value != null && !JSONObject.NULL.equals(value)) {
            processCalloutForField(ctx, adTab, fieldName, value, cCtx);
          }
        }
        pendingFields = nextPending;
      }

      result.chainDepth = depth;
      result.truncated = depth >= MAX_CALLOUT_CHAIN_DEPTH && !pendingFields.isEmpty();
      if (result.truncated) {
        log.warn("[NEO-DEFAULTS] Callout cascade reached max depth {} with pending fields: {}",
            MAX_CALLOUT_CHAIN_DEPTH, pendingFields);
      }
    } catch (Exception e) {
      log.error("[NEO-DEFAULTS] Error in callout cascade: {}", e.getMessage(), e);
    }
    return result;
  }

  static NeoDefaultsService.CalloutCascadeResult cascadeInteractiveCallout(
      NeoContext ctx, Tab adTab, String triggerField,
      JSONObject originalFormState, JSONObject calloutResponse) {
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();
    if (ctx == null || adTab == null || calloutResponse == null) {
      return result;
    }
    try {
      JSONObject cascadeFormState = new JSONObject(
          originalFormState != null ? originalFormState.toString() : "{}");
      Set<String> skipFields = new HashSet<>();
      skipFields.add(triggerField);

      Set<String> pendingFields = collectCalloutPendingFields(
          calloutResponse, cascadeFormState, skipFields, adTab);
      if (pendingFields.isEmpty()) {
        return result;
      }

      log.debug("[NEO-CALLOUT] Interactive cascade: {} field(s) queued after '{}': {}",
          pendingFields.size(), triggerField, pendingFields);

      int depth = 0;
      while (!pendingFields.isEmpty() && depth < MAX_CALLOUT_CHAIN_DEPTH) {
        depth++;
        pendingFields = executeCascadeIteration(
            pendingFields, ctx, adTab, cascadeFormState, cascadeFormState, skipFields, result);
      }
    } catch (Exception e) {
      log.warn("[NEO-CALLOUT] Interactive cascade failed for trigger '{}': {}",
          triggerField, e.getMessage(), e);
    }
    return result;
  }

  private static JSONObject resolveSelectorAuxValues(Tab adTab, String fieldName,
      String value) {
    if (adTab == null || fieldName == null || value == null || value.isEmpty()) {
      return null;
    }
    try {
      Entity entity = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
      if (entity == null) {
        return null;
      }
      Property prop = entity.getProperty(fieldName, false);
      if (prop == null || prop.getColumnId() == null || prop.isPrimitive()) {
        return null;
      }

      Column adColumn = OBDal.getInstance().get(Column.class, prop.getColumnId());
      if (adColumn == null) {
        return null;
      }

      JSONObject aux = NeoSelectorService.resolveSelectorAuxForId(adColumn, fieldName, value);
      if (aux != null && aux.length() > 0) {
        log.info("[NEO-DEFAULTS] Selector aux for '{}': {}", fieldName, aux);
      }
      return aux;
    } catch (Exception e) {
      log.warn("[NEO-DEFAULTS] Failed to resolve selector aux for field '{}': {}",
          fieldName, e.getMessage());
      return null;
    }
  }

  private static Set<String> collectCalloutPendingFields(JSONObject calloutResponse,
      JSONObject cascadeFormState, Set<String> skipFields, Tab adTab) throws JSONException {
    Set<String> pendingFields = new LinkedHashSet<>();
    JSONObject updates = calloutResponse.optJSONObject(KEY_UPDATES);
    if (updates == null) {
      return pendingFields;
    }
    @SuppressWarnings("unchecked")
    Iterator<String> keys = updates.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      JSONObject entry = updates.optJSONObject(key);
      Object value = entry != null ? entry.opt(FIELD_VALUE) : null;
      if (value == null || JSONObject.NULL.equals(value) || "".equals(String.valueOf(value))) {
        continue;
      }
      cascadeFormState.put(key, value);
      if (!skipFields.contains(key) && NeoCalloutService.resolveCallout(adTab, key) != null) {
        pendingFields.add(key);
      }
    }
    return pendingFields;
  }

  private static Set<String> executeCascadeIteration(Set<String> pendingFields,
      NeoContext ctx, Tab adTab, JSONObject formState, JSONObject defaults,
      Set<String> skipFields, NeoDefaultsService.CalloutCascadeResult result) {
    Set<String> nextPending = new LinkedHashSet<>();
    CalloutFieldContext cCtx = new CalloutFieldContext(formState, defaults,
        java.util.Collections.emptySet(), result, nextPending);

    for (String fieldName : pendingFields) {
      boolean shouldSkip = skipFields != null && skipFields.contains(fieldName);
      Object value = formState.opt(fieldName);
      boolean hasValue = value != null && !JSONObject.NULL.equals(value);
      if (!shouldSkip && hasValue) {
        processCalloutForField(ctx, adTab, fieldName, value, cCtx);
      }
    }
    return nextPending;
  }

  private static List<String> collectFieldsWithCallouts(JSONObject defaults,
      Set<String> seqFields, Tab adTab) {
    List<String> fieldsWithCallouts = new ArrayList<>();
    Iterator<String> keys = defaults.keys();
    while (keys.hasNext()) {
      String fieldName = keys.next();
      Object value = defaults.opt(fieldName);
      if (!seqFields.contains(fieldName)
          && value != null
          && !JSONObject.NULL.equals(value)
          && NeoCalloutService.resolveCallout(adTab, fieldName) != null) {
        fieldsWithCallouts.add(fieldName);
      }
    }
    return fieldsWithCallouts;
  }

  private static void processCalloutForField(NeoContext ctx, Tab adTab, String fieldName,
      Object value, CalloutFieldContext cCtx) {
    try {
      JSONObject calloutRequest = buildCalloutRequest(adTab, fieldName, value, cCtx.formState);
      NeoResponse calloutResponse = NeoCalloutService.executeCallout(ctx, calloutRequest);
      if (calloutResponse == null || calloutResponse.getHttpStatus() != 200) {
        log.debug("[NEO-DEFAULTS] Callout for '{}' failed or returned non-200", fieldName);
        return;
      }

      JSONObject calloutBody = calloutResponse.getBody();
      if (calloutBody == null) {
        return;
      }

      mergeCalloutUpdates(calloutBody, cCtx.formState, cCtx.defaults, cCtx.seqFields,
          adTab, cCtx.result, cCtx.nextPending);
      mergeCalloutCombos(calloutBody, cCtx.formState, cCtx.defaults, cCtx.result);

      JSONArray messages = calloutBody.optJSONArray("messages");
      if (messages != null) {
        cCtx.result.mergeMessages(messages);
      }
    } catch (Exception e) {
      log.warn("[NEO-DEFAULTS] Callout cascade error for field '{}': {}", fieldName, e.getMessage());
    }
  }

  private static JSONObject buildCalloutRequest(Tab adTab, String fieldName, Object value,
      JSONObject formState) throws Exception {
    JSONObject calloutRequest = new JSONObject();
    calloutRequest.put("field", fieldName);
    calloutRequest.put(FIELD_VALUE, value);
    calloutRequest.put("formState", formState);

    JSONObject auxValues = resolveSelectorAuxValues(adTab, fieldName, value.toString());
    if (auxValues != null && auxValues.length() > 0) {
      calloutRequest.put("auxiliaryValues", auxValues);
      log.info("[NEO-DEFAULTS] Resolved {} aux values for field '{}'",
          auxValues.length(), fieldName);
    }
    return calloutRequest;
  }

  private static void mergeCalloutUpdates(JSONObject calloutBody, JSONObject formState,
      JSONObject defaults, Set<String> seqFields, Tab adTab,
      NeoDefaultsService.CalloutCascadeResult result, Set<String> nextPending) throws Exception {
    JSONObject updates = calloutBody.optJSONObject(KEY_UPDATES);
    if (updates == null) {
      return;
    }
    result.mergeUpdates(updates);
    Iterator<String> updateKeys = updates.keys();
    while (updateKeys.hasNext()) {
      String updatedField = updateKeys.next();
      JSONObject updateObj = updates.optJSONObject(updatedField);
      if (updateObj == null || !updateObj.has(FIELD_VALUE)) {
        continue;
      }
      Object newValue = updateObj.get(FIELD_VALUE);
      Object oldValue = formState.opt(updatedField);
      formState.put(updatedField, newValue);
      defaults.put(updatedField, newValue);
      if (valueChanged(oldValue, newValue)
          && !seqFields.contains(updatedField)
          && NeoCalloutService.resolveCallout(adTab, updatedField) != null) {
        nextPending.add(updatedField);
      }
    }
  }

  private static void mergeCalloutCombos(JSONObject calloutBody, JSONObject formState,
      JSONObject defaults, NeoDefaultsService.CalloutCascadeResult result) throws Exception {
    JSONObject combos = calloutBody.optJSONObject(KEY_COMBOS);
    if (combos == null) {
      return;
    }
    result.mergeCombos(combos);
    Iterator<String> comboKeys = combos.keys();
    while (comboKeys.hasNext()) {
      String comboField = comboKeys.next();
      JSONObject comboObj = combos.optJSONObject(comboField);
      if (comboObj == null || !comboObj.has(KEY_SELECTED)) {
        continue;
      }
      Object selectedValue = comboObj.get(KEY_SELECTED);
      if (selectedValue != null && !JSONObject.NULL.equals(selectedValue)) {
        formState.put(comboField, selectedValue);
        defaults.put(comboField, selectedValue);
        log.debug("[NEO-DEFAULTS] Applied combo selected value: {} = {}", comboField, selectedValue);
      }
    }
  }

  private static boolean valueChanged(Object oldValue, Object newValue) {
    if (oldValue == null && newValue == null) {
      return false;
    }
    if (oldValue == null || newValue == null) {
      return true;
    }
    return !oldValue.toString().equals(newValue.toString());
  }

  private static class CalloutFieldContext {
    final JSONObject formState;
    final JSONObject defaults;
    final Set<String> seqFields;
    final NeoDefaultsService.CalloutCascadeResult result;
    final Set<String> nextPending;

    CalloutFieldContext(JSONObject formState, JSONObject defaults, Set<String> seqFields,
        NeoDefaultsService.CalloutCascadeResult result, Set<String> nextPending) {
      this.formState = formState;
      this.defaults = defaults;
      this.seqFields = seqFields;
      this.result = result;
      this.nextPending = nextPending;
    }
  }

  static void injectSafeTypeDefault(JSONObject body, String propName, Column col) {
    try {
      String refId = col.getReference() != null ? col.getReference().getId() : null;
      if ("22".equals(refId) || "29".equals(refId) || "12".equals(refId) || "11".equals(refId)) {
        body.put(propName, 0);
      } else if ("20".equals(refId)) {
        body.put(propName, false);
      }
    } catch (Exception e) {
      log.debug("Could not inject safe type default for {}: {}", propName, e.getMessage());
    }
  }

  public static void removeEmptyFkValues(JSONObject body, Tab adTab) {
    if (body == null || adTab == null || adTab.getTable() == null) {
      return;
    }
    try {
      Entity dalEntity = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
      if (dalEntity == null) {
        return;
      }
      for (Column col : adTab.getTable().getADColumnList()) {
        removeEmptyFkValueForColumn(body, col, dalEntity);
      }
    } catch (Exception e) {
      log.debug("Error removing empty FK values: {}", e.getMessage());
    }
  }

  private static void removeEmptyFkValueForColumn(JSONObject body, Column col, Entity dalEntity) {
    if (!col.isActive() || !col.isMandatory()) {
      return;
    }
    String dbColName = col.getDBColumnName();
    if (!dbColName.toUpperCase().endsWith("_ID")) {
      return;
    }
    Property prop = dalEntity.getPropertyByColumnName(dbColName);
    if (prop == null || !body.has(prop.getName())) {
      return;
    }
    Object value = body.opt(prop.getName());
    if (value instanceof String && ((String) value).trim().isEmpty()) {
      body.remove(prop.getName());
      log.debug("Removed empty FK value for mandatory field: {}", prop.getName());
    }
  }
}
