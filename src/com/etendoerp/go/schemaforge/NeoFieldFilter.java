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

  /** Set of DAL property names that are included (IsIncluded=Y). */
  private final Set<String> includedFields;

  /**
   * Set of DAL property names that are writable (IsIncluded=Y AND IsReadOnly=N).
   */
  private final Set<String> writableFields;

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

  /** Whether filtering is active (false if no SF_FIELD config exists). */
  private final boolean active;

  private NeoFieldFilter(Set<String> includedFields, Set<String> writableFields,
      Map<String, String> apiKeyToPropName, Map<String, String> propNameToApiKey, boolean active) {
    this.includedFields = includedFields;
    this.writableFields = writableFields;
    this.apiKeyToPropName = apiKeyToPropName;
    this.propNameToApiKey = propNameToApiKey;
    this.active = active;
  }

  /**
   * Build a field filter for the given SFEntity.
   * Loads all ETGO_SF_FIELD records and resolves their DAL property names.
   *
   * @param sfEntity      the schema forge entity configuration
   * @param dalEntityName the DAL entity name (from adTab.getTable().getName())
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
      Map<String, String> apiKeyMap = new HashMap<>();
      Map<String, String> propToApiMap = new HashMap<>();

      processFieldMappings(allFields, dalEntity, included, writable, apiKeyMap, propToApiMap,
          dalEntityName);

      // Always include "id" — it's needed for record identification
      included.add("id");
      writable.add("id");

      addParentColumnMappings(sfEntity, dalEntity, included, writable);

      log.debug("Field filter for entity {}: {} included, {} writable",
          sfEntity.getName(), included.size(), writable.size());

      return new NeoFieldFilter(included, writable, apiKeyMap, propToApiMap, true);

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
      Set<String> included, Set<String> writable,
      Map<String, String> apiKeyMap, Map<String, String> propToApiMap,
      String dalEntityName) {
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
        if (!prop.isPrimitive() && prop.getTargetEntity() != null) {
          included.add(propName + "$_identifier");
          // When a javaQualifier alias exists, the $_identifier must also be renamed
          // so the frontend receives "account$_identifier" instead of "finFinancialAccount$_identifier"
          if (qualifier != null && !qualifier.equals(propName)) {
            propToApiMap.put(propName + "$_identifier", qualifier + "$_identifier");
            apiKeyMap.put(qualifier + "$_identifier", propName + "$_identifier");
          }
        }

        if (!Boolean.TRUE.equals(sfField.isReadOnly())) {
          writable.add(propName);
        }
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
    return new NeoFieldFilter(null, null,
        java.util.Collections.emptyMap(), java.util.Collections.emptyMap(), false);
  }

  /**
   * Filter a GET response JSON from DefaultJsonDataService.
   * The response has structure: { "response": { "data": [...], ... } }
   * Removes properties not in the included set from each data record.
   *
   * @param responseJson the full JSON response from jsonService.fetch()
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
          JSONObject record = data.getJSONObject(i);
          filterRecord(record, includedFields);
          renameToApiKeys(record);
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
   * @param requestBody the request body JSON
   * @return the filtered JSON (modified in place)
   */
  public JSONObject filterWriteRequest(JSONObject requestBody) {
    return filterBody(requestBody, writableFields);
  }

  /**
   * Filter a POST (create) request body.
   * Allows read-only fields through because they may carry values from callouts
   * or defaults that are required for record creation (e.g., transactionDocument).
   * Only removes fields that are not included at all.
   *
   * @param requestBody the request body JSON
   * @return the filtered JSON (modified in place)
   */
  public JSONObject filterCreateRequest(JSONObject requestBody) {
    return filterBody(requestBody, includedFields);
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
   * Remove all keys from a JSON record that are NOT in the allowed set.
   * Preserves standard metadata keys added by DefaultJsonDataService
   * (e.g., _identifier, _entityName, recordTime).
   */
  @SuppressWarnings("unchecked")
  private void filterRecord(JSONObject record, Set<String> allowedFields) {
    Iterator<String> keys = record.keys();
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
      record.remove(key);
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
