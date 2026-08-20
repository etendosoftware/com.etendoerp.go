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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Filters JSON request/response bodies based on ETGO_SF_FIELD configuration.
 *
 * <p>
 * For GET responses, removes fields where IsIncluded=N.
 * For POST/PUT/PATCH inputs, removes fields where IsIncluded=N or IsReadOnly=Y.
 * </p>
 */
public class NeoFieldFilter {

  private static final Logger log = LogManager.getLogger(NeoFieldFilter.class);
  private static final String IDENTIFIER_SUFFIX = "$_identifier";

  /**
   * Set of DAL property names that are included (IsIncluded=Y).
   */
  private final Set<String> includedFields;

  /**
   * Set of DAL property names that are writable (IsIncluded=Y AND IsReadOnly=N).
   */
  private final Set<String> writableFields;

  /**
   * Set of DAL property names that {@link #filterCreateRequest} must reject rather than
   * silently drop when present in a POST body: IsIncluded=Y, IsReadOnly=Y, the AD column has
   * no configured default value, AND the owning entity has no {@code Java_Qualifier} (no
   * {@code NeoHandler} that could legitimately be the one supplying the value via a pre-hook,
   * as {@code InventoryLineHandler} does for {@code bookQuantity}). IMP-28 clause 2.
   *
   * <p>Entities with a Java_Qualifier are exempt in full — a handler for that entity might
   * inject the value before this filter runs (see {@code NeoServletSupport.handleWithHooks}),
   * so a per-field default-value check alone cannot tell "genuinely unwritable" from
   * "written by the entity's own handler". This is coarser than a per-field signal would be:
   * an entity with a handler that does NOT touch a given read-only field (e.g.
   * {@code ProductStockWarehouseHandler}, GET-only) is still exempted here. See IMP-28 report.
   *
   * <p><b>Membership is additionally reconciled against {@link #writableFields}</b> at the end of
   * {@link #forEntity}: anything explicitly granted write permission there — {@code id},
   * {@code active}, and every link-to-parent column — is removed from this set even when it
   * satisfies the rule above. Without that step a read-only parent FK was both writable and
   * rejectable and the rejection won, so no child row could be created at all (IMP-37).
   */
  private final Set<String> rejectableOnCreateFields;

  /**
   * Maps API keys (javaQualifier, e.g. "unitPrice") to DAL property names
   * (e.g. "priceActual"). Used to rename request body keys before filtering,
   * bridging the gap between the frontend field name and DefaultJsonDataService.
   */
  private final Map<String, String> apiKeyToPropName;

  /**
   * Reverse of apiKeyToPropName: DAL property name → API key
   * (e.g. "priceActual" → "unitPrice"). Used to rename GET response fields
   * so the frontend receives the field names it declared in decisions.json.
   */
  private final Map<String, String> propNameToApiKey;

  /**
   * Whether filtering is active (false if no SF_FIELD config exists).
   */
  private final boolean active;

  private NeoFieldFilter(Set<String> includedFields, Set<String> writableFields,
      Set<String> rejectableOnCreateFields,
      Map<String, String> apiKeyToPropName, Map<String, String> propNameToApiKey, boolean active) {
    this.includedFields = includedFields;
    this.writableFields = writableFields;
    this.rejectableOnCreateFields = rejectableOnCreateFields;
    this.apiKeyToPropName = apiKeyToPropName;
    this.propNameToApiKey = propNameToApiKey;
    this.active = active;
  }

  /**
   * Build a field filter for the given SFEntity.
   * Loads all ETGO_SF_FIELD records and resolves their DAL property names.
   *
   * @param sfEntity
   *     the schema forge entity configuration
   * @param dalEntityName
   *     the DAL entity name (from adTab.getTable().getName())
   * @return a filter instance, which may be inactive if no fields are configured
   */
  @SuppressWarnings("unchecked")
  public static NeoFieldFilter forEntity(SFEntity sfEntity, String dalEntityName) {
    if (sfEntity == null) {
      return inactive();
    }

    try {
      Entity dalEntity = ModelProvider.getInstance().getEntity(dalEntityName);
      if (dalEntity == null) {
        log.warn("Could not find DAL entity: {}", dalEntityName);
        return inactive();
      }

      // Load all active SF_FIELD records for this entity
      OBCriteria<SFField> fieldCrit = OBDal.getInstance().createCriteria(SFField.class);
      fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
      fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
      List<SFField> allFields = fieldCrit.list();

      if (allFields.isEmpty()) {
        // No field configuration means no filtering
        return inactive();
      }

      Set<String> included = new HashSet<>();
      Set<String> writable = new HashSet<>();
      Set<String> rejectableOnCreate = new HashSet<>();
      Map<String, String> apiKeyMap = new HashMap<>();
      Map<String, String> propToApiMap = new HashMap<>();

      // An entity with a Java_Qualifier has a NeoHandler that runs as a pre-hook before this
      // filter (NeoServletSupport.handleWithHooks) and may legitimately inject a read-only
      // field's value itself — e.g. InventoryLineHandler sets bookQuantity. Such entities are
      // exempt from clause-2 rejection entirely (see rejectableOnCreateFields javadoc).
      boolean entityHasHandler = sfEntity.getJavaQualifier() != null
          && !sfEntity.getJavaQualifier().trim().isEmpty();

      processFieldMappings(allFields, dalEntity, included, writable, rejectableOnCreate,
          apiKeyMap, propToApiMap, dalEntityName, entityHasHandler);

      // Always include "id" — it's needed for record identification
      included.add("id");
      writable.add("id");

      // Always expose the standard "active" flag. It is a base AD column, never an
      // ETGO_SF_FIELD row, so it would otherwise be stripped from GET responses —
      // breaking YESNO toggle columns (e.g. match-rule "Activa") — and from writes,
      // breaking inline activate/deactivate. Kept writable so toggles persist.
      included.add("active");
      writable.add("active");

      addParentColumnMappings(sfEntity, dalEntity, included, writable);

      // IMP-37: the three blocks above ("id", "active", link-to-parent columns) grant write
      // permission AFTER processFieldMappings has already classified every field, and clause 2
      // never revisited its own set. A link-to-parent FK curated read-only therefore ended up in
      // BOTH writable and rejectableOnCreate, and the rejection won because it runs before
      // filterBody -- making child-row creation impossible on 58 entities (a POST cannot omit the
      // parent link, and could not send it either). Inside processFieldMappings the two sets are
      // disjoint by construction (an if/else on isReadOnly), so this subtraction can only remove
      // what those three blocks added: an explicit grant must always beat an inferred rejection.
      // Keep this AFTER every writable.add above -- a grant added below it would not be honoured.
      rejectableOnCreate.removeAll(writable);

      log.debug("Field filter for entity {}: {} included, {} writable, {} rejectable on create",
          sfEntity.getName(), included.size(), writable.size(), rejectableOnCreate.size());

      return new NeoFieldFilter(included, writable, rejectableOnCreate, apiKeyMap, propToApiMap, true);

    } catch (Exception e) {
      log.error("Error building field filter for entity {}: {}",
          sfEntity.getName(), e.getMessage(), e);
      return inactive();
    }
  }

  /**
   * Processes field mappings for all SF fields, populating the included, writable,
   * apiKeyMap, and propToApiMap sets/maps.
   */
  private static void processFieldMappings(List<SFField> fields, Entity dalEntity,
      Set<String> included, Set<String> writable, Set<String> rejectableOnCreate,
      Map<String, String> apiKeyMap, Map<String, String> propToApiMap,
      String dalEntityName, boolean entityHasHandler) {
    for (SFField sfField : fields) {
      Property prop = resolveProperty(sfField, dalEntity, dalEntityName);
      if (prop == null) {
        continue;
      }

      String propName = prop.getName();

      // push-to-neo.js stores the frontend field name (e.g. "unitPrice") in
      // javaQualifier. DefaultJsonDataService expects the DAL property name
      // (e.g. "priceActual"). Build both maps:
      //   POST: apiKeyMap  (qualifier → propName) for remapApiKeys()
      //   GET:  propToApiMap (propName → qualifier) for renameToApiKeys()
      String qualifier = sfField.getJavaQualifier();
      if (qualifier != null && !qualifier.equals(propName)) {
        apiKeyMap.put(qualifier, propName);
        propToApiMap.put(propName, qualifier);
      }

      if (Boolean.TRUE.equals(sfField.isIncluded())) {
        included.add(propName);
        // For FK properties, also include the "_identifier" variant
        // that DefaultJsonDataService adds to the JSON
        includeFkIdentifierVariant(included, apiKeyMap, propToApiMap, prop, propName, qualifier);

        if (!Boolean.TRUE.equals(sfField.isReadOnly())) {
          writable.add(propName);
        } else if (!entityHasHandler && !hasConfiguredDefault(sfField.getADColumn())) {
          // IMP-28 clause 2: included + read-only + no AD default + no handler that could be
          // supplying it -> a client-sent value here can only be a mistake (or leftover from a
          // stale reading of a previous readOnly:false response). Reject on POST instead of
          // silently dropping it.
          rejectableOnCreate.add(propName);
        }
      }
    }
  }

  /**
   * Whether the given AD column has a non-blank default value configured
   * ({@code AD_Column.DefaultValue}). Mirrors the check
   * {@code McpSchemaFieldBuilder.hasSuppliedDefault} uses to decide whether a schema field
   * descriptor carries {@code defaultExpression}/{@code defaultSource} — kept as a separate,
   * literal re-implementation here since {@code McpSchemaFieldBuilder} lives in a different
   * package ({@code com.etendoerp.go.mcp}) and its helper is not accessible from here.
   */
  private static boolean hasConfiguredDefault(Column adColumn) {
    if (adColumn == null) {
      return false;
    }
    String defaultValue = adColumn.getDefaultValue();
    return defaultValue != null && !defaultValue.trim().isEmpty();
  }

  /**
   * For an included FK property, also registers its {@code $_identifier} variant
   * (added by DefaultJsonDataService) in the included set, and renames that variant
   * to the javaQualifier alias when one is configured.
   */
  private static void includeFkIdentifierVariant(Set<String> included, Map<String, String> apiKeyMap,
      Map<String, String> propToApiMap, Property prop, String propName, String qualifier) {
    if (!prop.isPrimitive() && prop.getTargetEntity() != null) {
      included.add(propName + IDENTIFIER_SUFFIX);
      // When a javaQualifier alias exists, the $_identifier must also be renamed
      // so the frontend receives "account$_identifier" instead of "finFinancialAccount$_identifier"
      if (qualifier != null && !qualifier.equals(propName)) {
        propToApiMap.put(propName + IDENTIFIER_SUFFIX, qualifier + IDENTIFIER_SUFFIX);
        apiKeyMap.put(qualifier + IDENTIFIER_SUFFIX, propName + IDENTIFIER_SUFFIX);
      }
    }
  }

  /**
   * Resolves the DAL {@link Property} for the given SF field, or returns {@code null}
   * if the field has no AD column or the column has no matching DAL property.
   */
  private static Property resolveProperty(SFField sfField, Entity dalEntity, String dalEntityName) {
    Column adColumn = sfField.getADColumn();
    if (adColumn == null) {
      return null;
    }
    String dbColumnName = adColumn.getDBColumnName();
    Property prop = dalEntity.getPropertyByColumnName(dbColumnName);
    if (prop == null) {
      log.debug("No DAL property found for column {} in entity {}", dbColumnName, dalEntityName);
    }
    return prop;
  }

  /**
   * Adds link-to-parent column properties to included and writable sets.
   * These are always allowed — they're needed for child record creation
   * (e.g., salesOrder on C_OrderLine, invoice on C_InvoiceLine).
   *
   * <p>"Always allowed" holds only because {@link #forEntity} subtracts {@code writable} from
   * the clause-2 rejection set after calling this method (IMP-37). Curation routinely marks a
   * parent FK read-only — correctly, since the user does not choose it — which used to land it
   * in {@link #rejectableOnCreateFields} and defeat this grant entirely.
   */
  private static void addParentColumnMappings(SFEntity sfEntity, Entity dalEntity,
      Set<String> included, Set<String> writable) {
    Tab adTab = sfEntity.getADTab();
    if (adTab != null && adTab.getTable() != null) {
      for (Column col : adTab.getTable().getADColumnList()) {
        if (col.isActive() && col.isLinkToParentColumn()) {
          Property parentProp = dalEntity.getPropertyByColumnName(col.getDBColumnName());
          if (parentProp != null) {
            writable.add(parentProp.getName());
            included.add(parentProp.getName());
          }
        }
      }
    }
  }

  /**
   * Create an inactive filter that performs no filtering.
   */
  private static NeoFieldFilter inactive() {
    return new NeoFieldFilter(null, null, null,
        java.util.Collections.emptyMap(), java.util.Collections.emptyMap(), false);
  }

  /**
   * Filter a GET response JSON from DefaultJsonDataService.
   * The response has structure: { "response": { "data": [...], ... } }
   * Removes properties not in the included set from each data record.
   *
   * @param responseJson
   *     the full JSON response from jsonService.fetch()
   * @return the filtered JSON (modified in place)
   */
  public JSONObject filterGetResponse(JSONObject responseJson) {
    if (!active || responseJson == null) {
      return responseJson;
    }

    try {
      JSONObject response = responseJson.optJSONObject("response");
      if (response == null) {
        return responseJson;
      }

      JSONArray data = response.optJSONArray("data");
      if (data != null) {
        for (int i = 0; i < data.length(); i++) {
          JSONObject item = data.getJSONObject(i);
          filterRecord(item, includedFields);
          renameToApiKeys(item);
        }
      }
    } catch (Exception e) {
      log.error("Error filtering GET response: {}", e.getMessage(), e);
    }

    return responseJson;
  }

  /**
   * Filter a PUT/PATCH request body.
   * Removes fields that are not included or are read-only.
   * The input is the raw JSON body from the client.
   *
   * @param requestBody
   *     the request body JSON
   * @return the filtered JSON (modified in place)
   */
  public JSONObject filterWriteRequest(JSONObject requestBody) {
    return filterBody(requestBody, writableFields);
  }

  /**
   * Resolves an API-level field key (e.g. {@code accountingDate}, the {@code java_qualifier}
   * declared in {@code ETGO_SF_FIELD}) to the DAL property name {@code filterWriteRequest}
   * actually persists (e.g. {@code dateAcct}) — the same rename {@code remapApiKeys} applies to
   * every OTHER field in the body. Callers that re-inject a value into an already-filtered body
   * (e.g. a server-side mirror for a read-only field that {@code filterWriteRequest} legitimately
   * stripped from the client's own input) must use the RESOLVED name, or the DAL layer silently
   * ignores the injected key as an unrecognized property (ETP-4531).
   *
   * @param apiKey the API/contract field key
   * @return the DAL property name, or {@code apiKey} unchanged if no remapping is configured
   */
  public String resolveWritablePropName(String apiKey) {
    return apiKeyToPropName.getOrDefault(apiKey, apiKey);
  }

  /**
   * The field keys a GET response can actually contain, expressed as the API keys the caller sees —
   * i.e. every included DAL property already passed through the {@code propNameToApiKey} rename that
   * {@link #filterGetResponse} applies. Companion keys such as {@code $_identifier} are included as
   * they appear.
   *
   * <p>Exists so a caller can tell "this name is not available here" from "this name happens to be
   * absent from the rows I got back" **without inspecting the rows** — the row-inspection answer is
   * undefined on an empty result set, which is exactly when a typo is most expensive to miss
   * (IMP-18). Read-only: the returned set is a copy.
   *
   * @return {@link Optional#of} the emittable response keys, or {@link Optional#empty()} when this
   *     filter is inactive (no {@code ETGO_SF_FIELD} config), in which case the response is
   *     unfiltered and the caller must fall back to the DAL entity's own property list rather than
   *     assume nothing is valid
   */
  public Optional<Set<String>> emittableResponseKeys() {
    if (!active || includedFields == null) {
      return Optional.empty();
    }
    Set<String> keys = new HashSet<>();
    for (String propName : includedFields) {
      keys.add(propNameToApiKey.getOrDefault(propName, propName));
    }
    return Optional.of(keys);
  }

  /**
   * Filter a POST (create) request body.
   * Allows read-only fields through when their entity's own NeoHandler pre-hook may have
   * legitimately supplied them (e.g., {@code transactionDocument}, {@code bookQuantity} — see
   * {@code InventoryLineHandler}), or when the AD column has a configured default value.
   * Removes fields that are not included at all, and REJECTS (does not silently drop) a
   * client-supplied value for a field that is read-only with no such excuse — see
   * {@link #rejectableOnCreateFields} and IMP-28.
   *
   * @param requestBody
   *     the request body JSON
   * @return the filtered JSON (modified in place)
   * @throws ReadOnlyFieldRejectedException
   *     if the body writes a field that is read-only, has no configured default, and belongs
   *     to an entity with no NeoHandler that could have supplied it
   */
  public JSONObject filterCreateRequest(JSONObject requestBody) {
    if (active && requestBody != null) {
      JSONObject dataNode = requestBody.optJSONObject("data");
      rejectDisallowedReadOnlyFields(dataNode != null ? dataNode : requestBody);
    }
    return filterBody(requestBody, includedFields);
  }

  /**
   * Throws {@link ReadOnlyFieldRejectedException} if the body contains a key (in either its
   * API-facing form or its resolved DAL property name) that is a member of
   * {@link #rejectableOnCreateFields}. Called before {@link #filterBody} would otherwise strip
   * the same key silently.
   */
  private void rejectDisallowedReadOnlyFields(JSONObject body) {
    if (body == null || rejectableOnCreateFields == null || rejectableOnCreateFields.isEmpty()) {
      return;
    }
    Iterator<String> keys = body.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      String propName = apiKeyToPropName.getOrDefault(key, key);
      if (rejectableOnCreateFields.contains(propName)) {
        throw new ReadOnlyFieldRejectedException(key);
      }
    }
  }

  private JSONObject filterBody(JSONObject requestBody, Set<String> allowedFields) {
    if (!active || requestBody == null) {
      return requestBody;
    }

    try {
      // Defensive: if client sends {"data": {...}}, unwrap before filtering.
      // NeoServlet will re-wrap with the proper SmartClient envelope.
      JSONObject bodyToFilter = requestBody;
      if (requestBody.has("data") && requestBody.optJSONObject("data") != null) {
        bodyToFilter = requestBody.getJSONObject("data");
      }
      remapApiKeys(bodyToFilter);
      filterRecord(bodyToFilter, allowedFields);
      return bodyToFilter;
    } catch (Exception e) {
      log.error("Error filtering write request: {}", e.getMessage(), e);
    }

    return requestBody;
  }

  /**
   * Renames DAL property names (e.g. "priceActual") in a GET response record to
   * their API keys (e.g. "unitPrice") so the frontend receives the field names it
   * declared in decisions.json / the contract. Called after filterRecord() so only
   * included fields are present and eligible for renaming.
   */
  @SuppressWarnings("unchecked")
  private void renameToApiKeys(JSONObject jsonObj) {
    if (propNameToApiKey.isEmpty() || jsonObj == null) {
      return;
    }
    Map<String, String> toRename = new HashMap<>();
    Iterator<String> keys = jsonObj.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      String apiKey = propNameToApiKey.get(key);
      if (apiKey != null) {
        toRename.put(key, apiKey);
      }
    }
    for (Map.Entry<String, String> entry : toRename.entrySet()) {
      String propName = entry.getKey();
      String apiKey = entry.getValue();
      Object value = jsonObj.opt(propName);
      jsonObj.remove(propName);
      if (!jsonObj.has(apiKey) && value != null) {
        try {
          jsonObj.put(apiKey, value);
          log.debug("[NEO] renameToApiKeys: '{}' → '{}' (value={})", propName, apiKey, value);
        } catch (Exception e) {
          log.warn("[NEO] renameToApiKeys: failed to rename '{}' → '{}': {}",
              propName, apiKey, e.getMessage());
        }
      }
    }
  }

  /**
   * Renames API keys (javaQualifier, e.g. "unitPrice") in the request body to
   * their DAL property names (e.g. "priceActual") before filtering and coercion.
   * If the DAL property name is already present in the body, the API key is
   * dropped and the existing DAL-name value is preserved.
   */
  @SuppressWarnings("unchecked")
  private void remapApiKeys(JSONObject body) {
    log.info("[REMAP] apiKeyToPropName map: {}", apiKeyToPropName);
    log.info("[REMAP] body recibido: {}", body);
    if (apiKeyToPropName.isEmpty() || body == null) {
      return;
    }
    Map<String, String> toRename = new HashMap<>();
    Iterator<String> keys = body.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      String propName = apiKeyToPropName.get(key);
      if (propName != null) {
        toRename.put(key, propName);
      }
    }
    for (Map.Entry<String, String> entry : toRename.entrySet()) {
      String apiKey = entry.getKey();
      String propName = entry.getValue();
      Object value = body.opt(apiKey);
      body.remove(apiKey);
      // Preserve an existing DAL-name value if already present in the body.
      if (!body.has(propName) && value != null) {
        try {
          body.put(propName, value);
          log.debug("[NEO] remapApiKeys: '{}' → '{}' (value={})", apiKey, propName, value);
        } catch (Exception e) {
          log.warn("[NEO] remapApiKeys: failed to rename '{}' → '{}': {}",
              apiKey, propName, e.getMessage());
        }
      }
    }
  }

  /**
   * Remove all keys from a JSON item that are NOT in the allowed set.
   * Preserves standard metadata keys added by DefaultJsonDataService
   * (e.g., _identifier, _entityName, recordTime).
   */
  @SuppressWarnings("unchecked")
  private void filterRecord(JSONObject item, Set<String> allowedFields) {
    Iterator<String> keys = item.keys();
    Set<String> toRemove = new HashSet<>();

    while (keys.hasNext()) {
      String key = keys.next();
      // Preserve standard metadata keys from DefaultJsonDataService
      if (isMetadataKey(key)) {
        continue;
      }
      if (!allowedFields.contains(key)) {
        toRemove.add(key);
      }
    }

    for (String key : toRemove) {
      item.remove(key);
    }
  }

  /**
   * Check if a JSON key is a standard metadata key from DefaultJsonDataService.
   * These are always preserved regardless of field configuration.
   */
  private boolean isMetadataKey(String key) {
    return key.startsWith("_") || key.startsWith("$")
        || "recordTime".equals(key) || "entityName".equals(key);
  }
}
