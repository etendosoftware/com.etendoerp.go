package com.etendoerp.go.schemaforge;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Filters JSON request/response bodies based on ETGO_SF_FIELD configuration.
 *
 * <p>For GET responses, removes fields where IsIncluded=N.
 * For POST/PUT/PATCH inputs, removes fields where IsIncluded=N or IsReadOnly=Y.</p>
 */
public class NeoFieldFilter {

  private static final Logger log = LogManager.getLogger(NeoFieldFilter.class);

  /** Set of DAL property names that are included (IsIncluded=Y). */
  private final Set<String> includedFields;

  /** Set of DAL property names that are writable (IsIncluded=Y AND IsReadOnly=N). */
  private final Set<String> writableFields;

  /** Whether filtering is active (false if no SF_FIELD config exists). */
  private final boolean active;

  private NeoFieldFilter(Set<String> includedFields, Set<String> writableFields, boolean active) {
    this.includedFields = includedFields;
    this.writableFields = writableFields;
    this.active = active;
  }

  /**
   * Build a field filter for the given SFEntity.
   * Loads all ETGO_SF_FIELD records and resolves their DAL property names.
   *
   * @param sfEntity the schema forge entity configuration
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

      for (SFField sfField : allFields) {
        Column adColumn = sfField.getADColumn();
        if (adColumn == null) {
          continue;
        }

        String dbColumnName = adColumn.getDBColumnName();
        Property prop = dalEntity.getPropertyByColumnName(dbColumnName);
        if (prop == null) {
          log.debug("No DAL property found for column {} in entity {}",
              dbColumnName, dalEntityName);
          continue;
        }

        String propName = prop.getName();

        if (Boolean.TRUE.equals(sfField.isIncluded())) {
          included.add(propName);
          // For FK properties, also include the "_identifier" variant
          // that DefaultJsonDataService adds to the JSON
          if (!prop.isPrimitive() && prop.getTargetEntity() != null) {
            included.add(propName + "$_identifier");
          }

          if (!Boolean.TRUE.equals(sfField.isReadOnly())) {
            writable.add(propName);
          }
        }
      }

      // Always include "id" — it's needed for record identification
      included.add("id");
      writable.add("id");

      log.debug("Field filter for entity {}: {} included, {} writable",
          sfEntity.getName(), included.size(), writable.size());

      return new NeoFieldFilter(included, writable, true);

    } catch (Exception e) {
      log.error("Error building field filter for entity {}: {}",
          sfEntity.getName(), e.getMessage(), e);
      return inactive();
    }
  }

  /**
   * Create an inactive filter that performs no filtering.
   */
  private static NeoFieldFilter inactive() {
    return new NeoFieldFilter(null, null, false);
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
        }
      }
    } catch (Exception e) {
      log.error("Error filtering GET response: {}", e.getMessage(), e);
    }

    return responseJson;
  }

  /**
   * Filter a POST/PUT/PATCH request body.
   * Removes fields that are not included or are read-only.
   * The input is the raw JSON body from the client.
   *
   * @param requestBody the request body JSON
   * @return the filtered JSON (modified in place)
   */
  public JSONObject filterWriteRequest(JSONObject requestBody) {
    if (!active || requestBody == null) {
      return requestBody;
    }

    try {
      filterRecord(requestBody, writableFields);
    } catch (Exception e) {
      log.error("Error filtering write request: {}", e.getMessage(), e);
    }

    return requestBody;
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
