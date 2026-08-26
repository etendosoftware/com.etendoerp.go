package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import org.openbravo.model.ad.ui.Field;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Cascade and selector-aux helpers extracted from {@link NeoDefaultsService}.
 */
public class NeoDefaultsCascadeHelper {

  private static final Logger log = LogManager.getLogger(NeoDefaultsCascadeHelper.class);
  private static final int MAX_CALLOUT_CHAIN_DEPTH = 5;
  private static final String FIELD_VALUE = "value";
  private static final String FIELD_IDENTIFIER = "_identifier";
  private static final String IDENTIFIER_SUFFIX = "$_identifier";
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
    // No explicit snapshot supplied: fall back to the CURRENT body keys. Used by callers
    // (and legacy tests) that invoke the cascade directly against an already-assembled body
    // with no separate "as submitted by the client" snapshot available. The create path in
    // NeoMandatoryDefaultsService.injectMandatoryDefaults MUST use the overload below with an
    // explicit pre-defaults snapshot instead (ETP-4784 defaults-cascade-order fix) — otherwise
    // values written by the generic mandatory-column defaults pass (e.g. the plain AD_Column
    // default, computed with no knowledge of the Business Partner) get frozen as "protected"
    // before the callout cascade ever gets a chance to recompute them from the real BP.
    Set<String> protectedFields = new HashSet<>();
    Iterator<String> bodyKeys = body.keys();
    while (bodyKeys.hasNext()) {
      protectedFields.add(bodyKeys.next());
    }
    executeCalloutCascadeForCreate(ctx, adTab, body, protectedFields);
  }

  /**
   * Runs the create-path callout cascade, protecting only the fields listed in
   * {@code protectedFields} from being overwritten by a re-cascaded callout.
   *
   * <p>ETP-4784: {@code protectedFields} MUST be a snapshot of the field names present in the
   * request body <em>as submitted by the client</em>, taken BEFORE any generic mandatory-column
   * default injection ran. Passing a snapshot taken from the body AFTER that injection (the
   * previous behaviour, when this method computed the snapshot internally from the live body)
   * incorrectly protects values the backend itself filled in with a generic default — e.g. a
   * plain column-level default for a document-type-key field that ignores the Business Partner
   * — from being corrected by a subsequent callout that knows the real, BP-aware value. A value
   * the user genuinely submitted in the original POST must still end up in {@code
   * protectedFields} and stay protected; only backend-injected generic defaults must not.</p>
   *
   * @param ctx             the NEO request context
   * @param adTab           the tab whose columns may trigger dependent callouts
   * @param body            the in-progress create payload, mutated in place by the cascade
   * @param protectedFields snapshot of field names to protect from cascade overwrite — must
   *                        reflect the client-submitted body, not the body after generic
   *                        default injection
   */
  static void executeCalloutCascadeForCreate(NeoContext ctx, Tab adTab, JSONObject body,
      Set<String> protectedFields) {
    try {
      Set<String> emptySeqFields = new HashSet<>();
      Set<String> effectiveProtected = protectedFields != null
          ? protectedFields : java.util.Collections.emptySet();
      NeoDefaultsService.CalloutCascadeResult cascadeResult =
          executeCalloutCascade(ctx, adTab, body, emptySeqFields, effectiveProtected);
      if (cascadeResult != null && cascadeResult.hasResults()) {
        log.info("[NEO-CREATE] Callout cascade derived {} field updates",
            cascadeResult.updatedFieldCount());
      }
    } catch (Exception e) {
      log.warn("[NEO-CREATE] Callout cascade failed (non-fatal): {}", e.getMessage());
    }
  }

  /**
   * Execute the create/defaults callout cascade until no more dependent fields remain
   * or the configured maximum depth is reached.
   *
   * @param ctx the NEO request context used to resolve callouts
   * @param adTab the tab whose columns may trigger dependent callouts
   * @param defaults the current defaults payload, updated in place during the cascade
   * @param seqFields fields that should be skipped because they are sequence previews
   * @return the aggregated cascade result with merged updates, combos, and messages
   */
  public static NeoDefaultsService.CalloutCascadeResult executeCalloutCascade(NeoContext ctx, Tab adTab,
      JSONObject defaults, Set<String> seqFields) {
    return executeCalloutCascade(ctx, adTab, defaults, seqFields, java.util.Collections.emptySet());
  }

  public static NeoDefaultsService.CalloutCascadeResult executeCalloutCascade(NeoContext ctx, Tab adTab,
      JSONObject defaults, Set<String> seqFields, Set<String> protectedFields) {
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
            result, nextPending, protectedFields);
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

      long cascadeStart = System.nanoTime();
      int depth = 0;
      int totalProcessed = 0;
      while (!pendingFields.isEmpty() && depth < MAX_CALLOUT_CHAIN_DEPTH) {
        depth++;
        long iterStart = System.nanoTime();
        int iterSize = pendingFields.size();
        totalProcessed += iterSize;
        pendingFields = executeCascadeIteration(
            pendingFields, ctx, adTab, cascadeFormState, cascadeFormState, skipFields, result);
        log.debug("[NEO-PERF]   cascadeInteractive iter={} fields={} duration={}ms trigger={}",
            depth, iterSize, (System.nanoTime() - iterStart) / 1_000_000L, triggerField);
      }
      log.debug("[NEO-PERF] cascadeInteractive trigger={} totalIterations={} totalCalloutsRun={} duration={}ms",
          triggerField, depth, totalProcessed,
          (System.nanoTime() - cascadeStart) / 1_000_000L);
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
    // skipFields carries the original triggerField of this interactive callout invocation.
    // Reuse it as protectedFields so a re-cascaded callout from a different field cannot
    // overwrite the value the user is actively editing (mirrors the create-path fix where
    // protectedFields comes from the submitted body's keys).
    CalloutFieldContext cCtx = new CalloutFieldContext(formState, defaults,
        java.util.Collections.emptySet(), result, nextPending, skipFields);

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
    return orderByAdFieldSequence(fieldsWithCallouts, adTab);
  }

  /**
   * Orders the cascade's trigger fields by their AD_Field sequence number, i.e. the order the
   * fields appear in the Classic form, from the most generic (Organization) to the most
   * specific (Business Partner, its address, ...).
   *
   * <p><b>Why this matters (ETP-4784).</b> Unlike Classic — where only the callout of the field
   * the user just edited runs — the create/defaults cascade fires the callout of EVERY field
   * present in the payload. When two callouts write the same column, the winner is simply
   * whichever runs last, and the previous iteration order was {@code JSONObject.keys()}, i.e.
   * hash order: non-deterministic and unrelated to any business rule.
   *
   * <p>Concrete case this fixes: on a sales invoice both
   * {@code SiiInvoiceOrganizationCallout} (on {@code AD_Org_ID}, seqNo 10) and
   * {@code SiiAutoSetSIIKEYByDefault} (on {@code C_BPartner_ID}, seqNo 50) write
   * {@code EM_Aeatsii_Clave_Tipo}. The organization callout writes a blanket {@code "F1"},
   * while the business-partner one resolves the key actually configured on that partner. Under
   * hash order the blanket value could land last and silently overwrite the specific one.
   * Following the form's own sequence reproduces what Classic does when a user fills the form
   * top-to-bottom: the more specific callout runs later and therefore wins.
   *
   * <p>Fields with no matching AD_Field (a payload key that is not on this tab) keep their
   * original relative order and are appended last, so behaviour for them is unchanged.
   */
  private static List<String> orderByAdFieldSequence(List<String> fieldsWithCallouts, Tab adTab) {
    if (fieldsWithCallouts.size() < 2 || adTab == null || adTab.getTable() == null) {
      return fieldsWithCallouts;
    }
    try {
      Entity dalEntity = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
      if (dalEntity == null) {
        return fieldsWithCallouts;
      }
      // property name -> smallest AD_Field seqNo declaring it on this tab
      Map<String, Long> seqByProperty = new HashMap<>();
      for (Field adField : adTab.getADFieldList()) {
        Column column = adField.getColumn();
        if (column == null || adField.getSequenceNumber() == null) {
          continue;
        }
        // resolvePropertyName never returns null: it falls back to toCleanFieldName().
        String propertyName = resolvePropertyName(dalEntity, column.getDBColumnName());
        seqByProperty.merge(propertyName, adField.getSequenceNumber(), Math::min);
      }
      if (seqByProperty.isEmpty()) {
        return fieldsWithCallouts;
      }
      List<String> ordered = new ArrayList<>(fieldsWithCallouts);
      // Stable sort: unknown fields (no AD_Field on this tab) sort last, keeping their order.
      ordered.sort(Comparator.comparing(
          field -> seqByProperty.getOrDefault(field, Long.MAX_VALUE)));
      return ordered;
    } catch (Exception e) {
      log.debug("[NEO-DEFAULTS] Could not order cascade fields by AD sequence: {}", e.getMessage());
      return fieldsWithCallouts;
    }
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
          adTab, cCtx.result, cCtx.nextPending, cCtx.protectedFields);
      mergeCalloutCombos(calloutBody, cCtx.formState, cCtx.defaults, cCtx.result, cCtx.protectedFields);

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
      NeoDefaultsService.CalloutCascadeResult result, Set<String> nextPending,
      Set<String> protectedFields) throws Exception {
    JSONObject updates = calloutBody.optJSONObject(KEY_UPDATES);
    if (updates == null) {
      return;
    }
    // Only merge unprotected fields into `result` — for the interactive callout path,
    // `result` is serialized back to the browser (NeoCalloutEndpoint#applyCascade) and
    // applied to the form as-is, so a protected field's stale/overwritten value must never
    // reach it, even though the create path never surfaces `result` to a client.
    result.mergeUpdates(filterProtectedFields(updates, defaults, protectedFields));
    Iterator<String> updateKeys = updates.keys();
    while (updateKeys.hasNext()) {
      String updatedField = updateKeys.next();
      JSONObject updateObj = updates.optJSONObject(updatedField);
      if (updateObj == null || !updateObj.has(FIELD_VALUE)) {
        continue;
      }
      if (shouldKeepExistingValue(defaults, updatedField, protectedFields)) {
        continue;
      }
      Object newValue = updateObj.get(FIELD_VALUE);
      Object oldValue = formState.opt(updatedField);
      if (wouldClearExistingValue(newValue, oldValue)) {
        log.debug("[NEO-DEFAULTS] Skipping callout update that would clear '{}' "
            + "(old='{}', new='')", updatedField, oldValue);
        continue;
      }
      formState.put(updatedField, newValue);
      defaults.put(updatedField, newValue);
      propagateIdentifier(updateObj, defaults, updatedField);
      if (shouldQueueForCascade(oldValue, newValue, updatedField, seqFields, adTab)) {
        nextPending.add(updatedField);
      }
    }
  }

  // Legacy callouts (e.g., SL_TaxCategory_Org) sometimes return empty strings as a
  // "don't know / clear this" signal. In the defaults/create flow we don't want to
  // overwrite a valid existing value with "" — that destroys defaults we already
  // resolved and causes NOT-NULL violations on save for mandatory FKs.
  private static boolean wouldClearExistingValue(Object newValue, Object oldValue) {
    boolean newIsEmpty = newValue == null
        || JSONObject.NULL.equals(newValue)
        || "".equals(String.valueOf(newValue));
    boolean oldIsPresent = oldValue != null
        && !JSONObject.NULL.equals(oldValue)
        && !"".equals(String.valueOf(oldValue));
    return newIsEmpty && oldIsPresent;
  }

  // When the callout returned a fresh _identifier alongside the value, propagate it so the
  // {field}$_identifier companion stays consistent with the new value. Without this, a prior
  // identifier resolved from the original (now-overwritten) value lingers in the response.
  private static void propagateIdentifier(JSONObject updateObj, JSONObject defaults,
      String updatedField) throws JSONException {
    if (!updateObj.has(FIELD_IDENTIFIER)) {
      return;
    }
    Object newIdentifier = updateObj.opt(FIELD_IDENTIFIER);
    if (newIdentifier != null && !JSONObject.NULL.equals(newIdentifier)) {
      defaults.put(updatedField + IDENTIFIER_SUFFIX, newIdentifier);
    }
  }

  private static boolean shouldQueueForCascade(Object oldValue, Object newValue,
      String updatedField, Set<String> seqFields, Tab adTab) {
    return valueChanged(oldValue, newValue)
        && !seqFields.contains(updatedField)
        && NeoCalloutService.resolveCallout(adTab, updatedField) != null;
  }

  private static void mergeCalloutCombos(JSONObject calloutBody, JSONObject formState,
      JSONObject defaults, NeoDefaultsService.CalloutCascadeResult result,
      Set<String> protectedFields) throws Exception {
    JSONObject combos = calloutBody.optJSONObject(KEY_COMBOS);
    if (combos == null) {
      return;
    }
    result.mergeCombos(filterProtectedFields(combos, defaults, protectedFields));
    Iterator<String> comboKeys = combos.keys();
    while (comboKeys.hasNext()) {
      String comboField = comboKeys.next();
      JSONObject comboObj = combos.optJSONObject(comboField);
      boolean hasSelected = comboObj != null && comboObj.has(KEY_SELECTED);
      boolean isProtected = hasSelected && shouldKeepExistingValue(defaults, comboField, protectedFields);
      if (isProtected) {
        log.debug("[NEO-DEFAULTS] Skipping combo update for protected field '{}'", comboField);
      }
      if (!hasSelected || isProtected) {
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
    final Set<String> protectedFields;

    CalloutFieldContext(JSONObject formState, JSONObject defaults, Set<String> seqFields,
        NeoDefaultsService.CalloutCascadeResult result, Set<String> nextPending,
        Set<String> protectedFields) {
      this.formState = formState;
      this.defaults = defaults;
      this.seqFields = seqFields;
      this.result = result;
      this.nextPending = nextPending;
      this.protectedFields = protectedFields != null ? protectedFields
          : java.util.Collections.emptySet();
    }
  }

  /**
   * Returns a copy of {@code source} (an "updates" or "combos" callout section) with any
   * entry for a protected field removed, based on the SAME snapshot of {@code defaults}
   * that {@link #shouldKeepExistingValue} will use for the per-field state-mutation check
   * right after this call. Keeps the raw cascade result handed back to the caller
   * (frontend, in the interactive callout path) consistent with what actually gets applied
   * to {@code formState}/{@code defaults}.
   */
  private static JSONObject filterProtectedFields(JSONObject source, JSONObject defaults,
      Set<String> protectedFields) throws JSONException {
    if (protectedFields == null || protectedFields.isEmpty()) {
      return source;
    }
    JSONObject filtered = new JSONObject();
    Iterator<String> keys = source.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (shouldKeepExistingValue(defaults, key, protectedFields)) {
        log.debug("[NEO-DEFAULTS] Excluding protected field '{}' from cascade result", key);
        continue;
      }
      filtered.put(key, source.get(key));
    }
    return filtered;
  }

  private static boolean shouldKeepExistingValue(JSONObject defaults, String fieldName,
      Set<String> protectedFields) {
    if (protectedFields == null || !protectedFields.contains(fieldName)) {
      return false;
    }
    Object current = defaults.opt(fieldName);
    if (current == null || JSONObject.NULL.equals(current)) {
      return false;
    }
    if (current instanceof String) {
      return !((String) current).trim().isEmpty();
    }
    return true;
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

  /**
   * Remove mandatory FK properties that still carry empty-string placeholders
   * so DAL can resolve them as null/absent instead of invalid IDs.
   *
   * @param body the request payload to sanitize
   * @param adTab the tab whose FK columns should be checked
   */
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
