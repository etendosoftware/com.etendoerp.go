/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;

/**
 * Auxiliary field resolution helpers for {@link NeoSelectorService}.
 *
 * Contains all static methods responsible for resolving auxiliary (_aux) field values
 * on selector results — both via DAL property navigation and via re-executing the
 * original OBUISEL custom HQL SELECT for already-fetched entity IDs.
 */
class SelectorAuxResolver {

  private static final Logger log = LogManager.getLogger(SelectorAuxResolver.class);

  private SelectorAuxResolver() {
  }

  /**
   * Resolve auxiliary field values for a given entity object.
   * Uses the DAL property path from the selector field to navigate the entity graph.
   * For list properties (e.g., businessPartnerLocationList), returns the first entry's ID.
   */
  static void appendAuxFields(JSONObject item, BaseOBObject bob,
      List<AuxFieldMeta> auxFields) {
    if (auxFields == null || auxFields.isEmpty()) {
      return;
    }
    try {
      JSONObject aux = new JSONObject();
      for (AuxFieldMeta af : auxFields) {
        Object auxVal = resolveAuxFieldValue(bob, af);
        if (auxVal != null) {
          aux.put(af.suffix, auxVal.toString());
        }
      }
      if (aux.length() > 0) {
        item.put("_aux", aux);
      }
    } catch (Exception e) {
      log.debug("Error resolving aux fields for {}: {}", bob.getId(), e.getMessage());
    }
  }

  /**
   * Resolve a single auxiliary field value from a BaseOBObject.
   * Navigates the DAL property path. For FK/list properties, returns the ID.
   */
  static Object resolveAuxFieldValue(BaseOBObject bob, AuxFieldMeta af) {
    // Try DAL property path if available
    if (StringUtils.isNotBlank(af.property)) {
      try {
        Object leaf = navigatePropertyPath(bob, af.property.split("\\."));
        return extractLeafValue(leaf);
      } catch (Exception e) {
        log.debug("Could not resolve aux property {} on {}: {}",
            af.property, bob.getId(), e.getMessage());
      }
    }
    // No property path — aux value must be resolved via HQL (see resolveAuxFieldsViaHql)
    return null;
  }

  /**
   * Navigate a dot-split property path on a BaseOBObject, returning the final value.
   * Returns {@code null} as soon as a segment resolves to null.
   * Returns the current object early if it is no longer a BaseOBObject mid-path.
   */
  private static Object navigatePropertyPath(BaseOBObject bob, String[] parts) {
    Object current = bob;
    for (String part : parts) {
      if (current == null) {
        return null;
      }
      if (current instanceof BaseOBObject) {
        current = ((BaseOBObject) current).get(part);
      } else {
        return current;
      }
    }
    return current;
  }

  /**
   * Extract the final value from a leaf node after property-path navigation.
   * BaseOBObject → its ID; List (of BaseOBObject) → ID of the first element; otherwise as-is.
   */
  private static Object extractLeafValue(Object leaf) {
    if (leaf instanceof BaseOBObject) {
      return ((BaseOBObject) leaf).getId();
    }
    if (leaf instanceof List) {
      List<?> list = (List<?>) leaf;
      if (!list.isEmpty() && list.get(0) instanceof BaseOBObject) {
        return ((BaseOBObject) list.get(0)).getId();
      }
    }
    return leaf;
  }

  /**
   * Resolve auxiliary field values that lack a DAL property by re-executing
   * the original OBUISEL custom HQL SELECT for the already-fetched entity IDs.
   *
   * The original HQL includes computed/joined columns (e.g., "bploc.id as locationid")
   * that are not accessible via DAL. This method:
   * 1. Parses the SELECT clause to build an alias→position map
   * 2. Finds the position of the entity ID column (by valueFieldAlias or entity alias + ".id")
   * 3. Executes the original SELECT with an IN(:ids) filter as Object[]
   * 4. Merges the aux values back into the item JSONArray by matching IDs
   */
  @SuppressWarnings("unchecked")
  static void resolveAuxFieldsViaHql(JSONArray items, List<String> entityIds,
      String rawHql, int fromIdx, String entityAlias, SelectorMeta meta) {
    try {
      String selectClause = rawHql.substring(0, fromIdx);
      String fromOnwards = rawHql.substring(fromIdx);

      // Parse ordered alias list from SELECT: "expr as aliasname"
      List<String> aliases = parseSelectAliases(selectClause);
      if (aliases.isEmpty()) {
        log.debug("Could not parse SELECT aliases from custom HQL");
        return;
      }

      int idPos = findIdPositionInAliases(aliases, meta.valueProperty);
      if (idPos < 0) {
        log.debug("Could not find ID column in custom HQL SELECT aliases: {}", aliases);
        return;
      }

      Map<String, Integer> auxAliasPos = buildHqlAuxAliasPositionMap(meta.auxFields, aliases);
      if (auxAliasPos.isEmpty()) {
        return;
      }

      // Build and execute the aux query filtered by the already-fetched IDs
      String auxHql = buildAuxIdListQuery(selectClause + fromOnwards, entityAlias);
      auxHql = SelectorQueryBuilder.resolveObuiselParams(auxHql);

      org.hibernate.query.Query<Object[]> auxQuery = OBDal.getInstance()
          .getSession().createQuery(auxHql, Object[].class);
      auxQuery.setParameterList("auxIds", entityIds);

      Map<String, JSONObject> auxMap = buildAuxResultMap(auxQuery.list(), idPos, auxAliasPos);
      mergeAuxIntoItems(items, auxMap);

    } catch (Exception e) {
      log.warn("Could not resolve aux fields via HQL: {}", e.getMessage());
    }
  }

  /**
   * Parse the ordered list of lowercase alias names from a SELECT clause.
   * Handles optional DISTINCT and "expr as alias" notation.
   */
  static List<String> parseSelectAliases(String selectClause) {
    // Use a simple pattern that only captures the alias after "as" keyword.
    // All quantifiers are possessive to guarantee linear runtime with find().
    java.util.regex.Matcher aliasMatcher = Pattern.compile(
        "\\b[Aa][Ss]\\s++(\\w++)")
        .matcher(selectClause);
    List<String> aliases = new ArrayList<>();
    while (aliasMatcher.find()) {
      aliases.add(aliasMatcher.group(1).toLowerCase());
    }
    return aliases;
  }

  /**
   * Find the position of the ID column in a SELECT alias list.
   * Checks the value-property alias first, then falls back to short aliases ending in "id".
   * Returns -1 if not found.
   */
  static int findIdPositionInAliases(List<String> aliases, String valueProperty) {
    String valueAlias = valueProperty != null ? valueProperty.toLowerCase() : "id";
    int idPos = aliases.indexOf(valueAlias);
    if (idPos >= 0) {
      return idPos;
    }
    for (int i = 0; i < aliases.size(); i++) {
      if (aliases.get(i).endsWith("id") && aliases.get(i).length() <= 6) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Build a suffix→column-position map for auxiliary fields that have an HQL alias
   * but no DAL property path.
   */
  static Map<String, Integer> buildHqlAuxAliasPositionMap(
      List<AuxFieldMeta> auxFields, List<String> aliases) {
    Map<String, Integer> auxAliasPos = new java.util.HashMap<>();
    for (AuxFieldMeta af : auxFields) {
      if (StringUtils.isBlank(af.property) && StringUtils.isNotBlank(af.hqlAlias)) {
        int pos = aliases.indexOf(af.hqlAlias.toLowerCase());
        if (pos >= 0) {
          auxAliasPos.put(af.suffix, pos);
        }
      }
    }
    return auxAliasPos;
  }

  /**
   * Prepare an HQL query string from a raw HQL by stripping ORDER BY and appending
   * an IN(:auxIds) entity ID filter, ready for aux-field resolution.
   */
  static String buildAuxIdListQuery(String rawHql, String entityAlias) {
    // Remove ORDER BY (not needed for aux lookup)
    int orderByIdx = rawHql.toUpperCase().lastIndexOf("ORDER BY");
    String hql = orderByIdx > 0 ? rawHql.substring(0, orderByIdx) : rawHql;

    boolean hasWhere = Pattern.compile("\\sWHERE\\s", Pattern.CASE_INSENSITIVE)
        .matcher(hql).find();
    return hql + (hasWhere ? SelectorQueryBuilder.SQL_AND : SelectorQueryBuilder.SQL_WHERE)
        + entityAlias + ".id IN (:auxIds)";
  }

  /**
   * Build an ID-to-aux-values map from HQL Object[] query results.
   */
  static Map<String, JSONObject> buildAuxResultMap(List<Object[]> rows,
      int idPos, Map<String, Integer> auxAliasPos) throws Exception {
    Map<String, JSONObject> auxMap = new java.util.HashMap<>();
    for (Object[] row : rows) {
      if (row.length <= idPos || row[idPos] == null) {
        continue;
      }
      String rowId = row[idPos].toString();
      JSONObject aux = new JSONObject();
      for (Map.Entry<String, Integer> entry : auxAliasPos.entrySet()) {
        int pos = entry.getValue();
        if (pos < row.length && row[pos] != null) {
          aux.put(entry.getKey(), row[pos].toString());
        }
      }
      if (aux.length() > 0) {
        auxMap.put(rowId, aux);
      }
    }
    return auxMap;
  }

  /**
   * Merge auxiliary field values from auxMap into the corresponding items in the JSONArray.
   * Merges with any existing {@code _aux} object already present on the item.
   */
  @SuppressWarnings("unchecked")
  static void mergeAuxIntoItems(JSONArray items, Map<String, JSONObject> auxMap)
      throws Exception {
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      String itemId = item.optString("id");
      JSONObject aux = auxMap.get(itemId);
      if (aux == null) {
        continue;
      }
      JSONObject existing = item.optJSONObject("_aux");
      if (existing != null) {
        java.util.Iterator<String> auxKeysIter = aux.keys();
        while (auxKeysIter.hasNext()) {
          String key = auxKeysIter.next();
          existing.put(key, aux.get(key));
        }
      } else {
        item.put("_aux", aux);
      }
    }
  }

  /**
   * Load the entity object needed to resolve DAL aux field values.
   * If valueProperty is "id", loads directly. Otherwise, queries by property value.
   */
  static BaseOBObject loadEntityForAux(SelectorMeta meta, String recordId) {
    if ("id".equals(meta.valueProperty)) {
      return (BaseOBObject) OBDal.getInstance().get(meta.entityName, recordId);
    }
    // Query by the value property path (e.g., "product.id" for ProductByPriceAndWarehouse)
    try {
      String hql = "from " + meta.entityName + " where " + meta.valueProperty + " = :val";
      org.hibernate.query.Query<?> q = OBDal.getInstance().getSession().createQuery(hql);
      q.setParameter("val", recordId);
      q.setMaxResults(1);
      List<?> results = q.list();
      if (!results.isEmpty()) {
        return (BaseOBObject) results.get(0);
      }
    } catch (Exception e) {
      log.debug("Could not query {} by {}: {}", meta.entityName, meta.valueProperty, e.getMessage());
    }
    return null;
  }

  /**
   * Resolve aux values via the original OBUISEL custom HQL for a single record.
   */
  @SuppressWarnings("unchecked")
  static JSONObject resolveAuxViaHql(SelectorMeta meta, String fieldName,
      String recordId) {
    try {
      String hql = meta.customHql;
      String selectClause = hql.substring(0, hql.toUpperCase().indexOf(" FROM "));
      String[] selectParts = selectClause.replaceFirst("(?i)^\\s*select\\s+", "").split(",");
      Map<String, Integer> aliasPos = buildAuxAliasPositionMap(selectParts);

      String fullHql = buildAuxHqlWithIdFilter(hql, selectClause, meta.entityAlias);
      return executeAuxHqlQuery(fullHql, recordId, aliasPos, meta.auxFields, fieldName);

    } catch (Exception e) {
      log.debug("Could not resolve aux via HQL for {}: {}", fieldName, e.getMessage());
      return null;
    }
  }

  /**
   * Build an alias-to-index map from the SELECT parts of a custom HQL clause.
   */
  static Map<String, Integer> buildAuxAliasPositionMap(String[] selectParts) {
    Map<String, Integer> aliasPos = new java.util.HashMap<>();
    for (int i = 0; i < selectParts.length; i++) {
      String part = selectParts[i].trim();
      int asIdx = part.toLowerCase().lastIndexOf(" as ");
      if (asIdx >= 0) {
        String alias = part.substring(asIdx + 4).trim().replace("\"", "");
        aliasPos.put(alias.toLowerCase(), i);
      }
    }
    return aliasPos;
  }

  /**
   * Append an entity ID filter to the HQL and return the full query string.
   */
  static String buildAuxHqlWithIdFilter(String hql, String selectClause,
      String entityAlias) {
    String fromClause = hql.substring(hql.toUpperCase().indexOf(" FROM "));
    String fullHql = selectClause + fromClause;
    if (fullHql.toUpperCase().contains(SelectorQueryBuilder.SQL_WHERE)) {
      fullHql += SelectorQueryBuilder.SQL_AND + entityAlias + ".id = :recordId";
    } else {
      fullHql += SelectorQueryBuilder.SQL_WHERE + entityAlias + ".id = :recordId";
    }
    return fullHql;
  }

  /**
   * Execute an aux HQL query and map results to a JSON object keyed by fieldName + suffix.
   */
  static JSONObject executeAuxHqlQuery(String fullHql, String recordId,
      Map<String, Integer> aliasPos, List<AuxFieldMeta> auxFields,
      String fieldName) throws Exception {
    org.hibernate.query.Query<?> query = OBDal.getInstance().getSession().createQuery(fullHql);
    query.setParameter("recordId", recordId);
    query.setMaxResults(1);

    List<?> results = query.list();
    if (results.isEmpty()) {
      return null;
    }

    Object row = results.get(0);
    Object[] cols = row instanceof Object[] ? (Object[]) row : new Object[]{ row };

    JSONObject result = new JSONObject();
    for (AuxFieldMeta af : auxFields) {
      Integer pos = aliasPos.get(af.hqlAlias.toLowerCase());
      if (pos != null && pos < cols.length && cols[pos] != null) {
        Object val = cols[pos];
        if (val instanceof BaseOBObject) {
          val = ((BaseOBObject) val).getId();
        }
        result.put(fieldName + af.suffix, val.toString());
      }
    }
    return result.length() > 0 ? result : null;
  }
}
