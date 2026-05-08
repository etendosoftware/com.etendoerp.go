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
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.selector.meta.RichFieldMeta;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Row/column mapping helpers extracted from {@link SelectorQueryBuilder}.
 * Move here: buildSelectColumnIndexMap, extractAlias, resolveIdColumnIndex,
 * normalizeEntityId, extractDisplayLabel, mapGridFieldsToItem.
 */
public final class SelectorRowMapper {

  private SelectorRowMapper() {
  }

  /**
   * Build a column-alias to index map from an array of SELECT expression strings.
   * Handles "expr as alias" notation and bare "table.column" expressions.
   */
  static Map<String, Integer> buildSelectColumnIndexMap(String[] selectExprs) {
    Map<String, Integer> colIndexMap = new HashMap<>();
    for (int i = 0; i < selectExprs.length; i++) {
      String expr = selectExprs[i].trim();
      String colAlias = extractAlias(expr);
      colIndexMap.put(colAlias.toLowerCase(), i);
    }
    return colIndexMap;
  }

  /**
   * Extract the column alias from a SELECT expression string.
   * Handles "expr as alias" notation (case-insensitive) and bare "table.column" expressions.
   * Uses string parsing instead of regex to avoid ReDoS risk with find().
   */
  private static String extractAlias(String expr) {
    // Search for " as " (case-insensitive) — last occurrence to handle nested expressions
    String lower = expr.toLowerCase();
    int asIdx = lower.lastIndexOf(" as ");
    if (asIdx >= 0) {
      String afterAs = expr.substring(asIdx + 4).trim();
      if (!afterAs.isEmpty()) {
        return afterAs;
      }
    }
    // Fallback: use the part after the last dot, or the whole expression
    int dotIdx = expr.lastIndexOf('.');
    return dotIdx >= 0 ? expr.substring(dotIdx + 1).trim() : expr.trim();
  }

  /**
   * Resolve the column index that holds the record ID in a custom HQL SELECT result.
   * Checks the value property alias, then "id", then scans for "{alias}.id" prefix.
   */
  static Integer resolveIdColumnIndex(SelectorMeta meta, String alias,
      Map<String, Integer> colIndexMap, String[] selectExprs) {
    String valuePropLower = (meta.valueProperty != null ? meta.valueProperty : "id").toLowerCase();
    Integer idColIdx = colIndexMap.get(valuePropLower);
    if (idColIdx == null) {
      idColIdx = colIndexMap.get("id");
    }
    if (idColIdx == null) {
      String idPrefix = alias.toLowerCase() + ".id";
      for (int i = 0; i < selectExprs.length; i++) {
        if (selectExprs[i].trim().toLowerCase().startsWith(idPrefix)) {
          idColIdx = i;
          break;
        }
      }
    }
    return idColIdx;
  }

  /**
   * Normalize a raw entity ID string from an OBUISEL DB view with a composite 64-char ID.
   * These views concatenate two 32-char UUIDs. For custom HQL selectors the second half is
   * used as a fallback row identifier. Prefer resolving via valueProperty when available.
   */
  static String normalizeEntityId(String rawId) {
    return SelectorResponseSupport.normalizeEntityId(rawId);
  }

  /**
   * Extract the display label from an HQL Object[] row.
   * Falls back to loading the entity by ID if the label column is missing or empty.
   */
  static String extractDisplayLabel(Object[] row, Map<String, Integer> colIndexMap,
      String displayProperty, Entity entityDef, String recordId) {
    String label = "";
    Integer displayIdx = colIndexMap.get(displayProperty.toLowerCase());
    if (displayIdx == null) {
      displayIdx = colIndexMap.get("productname");
    }
    if (displayIdx != null && displayIdx < row.length) {
      Object dispVal = row[displayIdx];
      label = dispVal != null ? dispVal.toString() : "";
    }
    if (label.isEmpty()) {
      try {
        BaseOBObject entity = OBDal.getInstance().get(entityDef.getName(), recordId);
        if (entity != null) label = entity.getIdentifier();
      } catch (Exception ignored) {
        // Ignored: fallback handled below
      }
    }
    return label;
  }

  /**
   * Map rich selector grid fields from an HQL Object[] row into a JSON item.
   * Resolves BaseOBObject values to their identifier strings.
   */
  static void mapGridFieldsToItem(JSONObject item, Object[] row,
      Map<String, Integer> colIndexMap, List<RichFieldMeta> gridFields) throws Exception {
    for (RichFieldMeta fieldMeta : gridFields) {
      Integer colIdx = colIndexMap.get(fieldMeta.propertyKey.toLowerCase());
      if (colIdx != null && colIdx < row.length) {
        Object value = row[colIdx];
        if (value instanceof BaseOBObject) {
          item.put(fieldMeta.propertyKey, ((BaseOBObject) value).getIdentifier());
        } else {
          item.put(fieldMeta.propertyKey, value != null ? value : JSONObject.NULL);
        }
      }
    }
  }
}
