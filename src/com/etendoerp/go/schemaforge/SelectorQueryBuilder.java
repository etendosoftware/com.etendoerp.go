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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.domain.Validation;

/**
 * HQL/SQL query construction helpers for {@link NeoSelectorService}.
 *
 * Contains all static methods responsible for building HQL fragments,
 * column-index maps, record extraction, and validation-rule conversion.
 * None of these methods perform I/O or session state changes on their own.
 */
class SelectorQueryBuilder {

  private static final Logger log = LogManager.getLogger(SelectorQueryBuilder.class);

  static final String SQL_AND = " AND ";
  static final String SQL_WHERE = " WHERE ";

  private static final String FIELD_LABEL = "label";
  private static final String FIELD_ITEMS = "items";
  private static final String FIELD_COLUMNS = "columns";
  private static final String FIELD_TOTAL_COUNT = "totalCount";
  private static final String FIELD_LIMIT = "limit";
  private static final String FIELD_OFFSET = "offset";
  private static final String FIELD_HAS_MORE = "hasMore";

  /** Pattern matching @param@ placeholders in OBUISEL clauses. */
  static final Pattern PARAM_PATTERN = Pattern.compile("@([A-Za-z_]+)@");

  // Matches @Param@ and @#Param@ (session-level variables in Etendo)
  static final Pattern VALIDATION_PARAM = Pattern.compile("@#?(\\w+)@");

  // SQL functions that have no HQL equivalent and must cause a clause to be skipped
  static final Pattern SQL_ONLY_FUNCTIONS = Pattern.compile(
      "AD_ISORGINCLUDED|AD_ISCHILDORGINCLUDED|AD_ROLE_ORGACCESS", Pattern.CASE_INSENSITIVE);

  private SelectorQueryBuilder() {
  }

  /**
   * Build the standard paginated selector response JSON.
   */
  static NeoResponse buildSelectorResponse(JSONArray items, JSONArray columns,
      int totalCount, int limit, int offset) throws Exception {
    JSONObject result = new JSONObject();
    result.put(FIELD_ITEMS, items);
    result.put(FIELD_COLUMNS, columns);
    result.put(FIELD_TOTAL_COUNT, totalCount);
    result.put(FIELD_LIMIT, limit);
    result.put(FIELD_OFFSET, offset);
    result.put(FIELD_HAS_MORE, (offset + limit) < totalCount);
    return NeoResponse.ok(result);
  }

  /**
   * Build the OBQuery where-string for a standard rich (OBUISEL) query.
   * Incorporates the OBUISEL where clause, validation filter, and search predicate.
   * Returns "as e [where ...]" ready to pass to {@link OBDal#createQuery}.
   */
  static String buildRichQueryWhereClause(SelectorMeta meta,
      String search, String validationFilter, String alias, String contextOrganizationId) {
    StringBuilder hql = new StringBuilder();
    appendResolvedClause(hql, resolveObuiselParams(meta.whereClause));
    appendResolvedClause(hql, validationFilter);
    appendResolvedClause(hql, resolveSelectorOrgFilter(meta.entityName, alias, contextOrganizationId));
    appendSearchClause(hql, meta.searchableProperties, alias, search);

    return hql.length() > 0
        ? "as " + alias + " where " + hql
        : "as " + alias;
  }

  /**
   * Build the column-metadata JSONArray from a list of grid field descriptors.
   */
  static JSONArray buildGridColumnMetadata(List<RichFieldMeta> gridFields)
      throws Exception {
    JSONArray columns = new JSONArray();
    for (RichFieldMeta fieldMeta : gridFields) {
      JSONObject col = new JSONObject();
      col.put("name", fieldMeta.propertyKey);
      col.put(FIELD_LABEL, fieldMeta.label);
      col.put("sortNo", fieldMeta.sortNo);
      columns.put(col);
    }
    return columns;
  }

  /**
   * Build the FROM-onwards HQL fragment for a custom-HQL selector query.
   * Appends the OBUISEL where clause, validation filter, readable-orgs filter,
   * and (if applicable) a search predicate across the selector's searchable properties.
   *
   * @return the complete FROM clause string, ready to use in COUNT and data queries
   */
  static String buildCustomHqlFromClause(String fromOnwards, String alias,
      SelectorMeta meta, String validationFilter, String search, String contextOrganizationId) {
    StringBuilder baseHql = new StringBuilder(fromOnwards);
    boolean hasWhere = Pattern.compile("\\sWHERE\\s", Pattern.CASE_INSENSITIVE)
        .matcher(fromOnwards).find();

    hasWhere = appendClause(baseHql, resolveObuiselParams(meta.whereClause), hasWhere);
    hasWhere = appendClause(baseHql, validationFilter, hasWhere);
    hasWhere = appendClause(baseHql,
        resolveSelectorOrgFilter(meta.entityName, alias, contextOrganizationId), hasWhere);
    appendCustomSearchFilter(baseHql, meta.searchableProperties, alias, search, hasWhere);

    return baseHql.toString();
  }

  private static void appendResolvedClause(StringBuilder hql, String clause) {
    if (StringUtils.isBlank(clause)) {
      return;
    }
    if (hql.length() > 0) {
      hql.append(SQL_AND);
    }
    hql.append(clause);
  }

  private static boolean appendClause(StringBuilder hql, String clause, boolean hasWhere) {
    if (StringUtils.isBlank(clause)) {
      return hasWhere;
    }
    hql.append(hasWhere ? SQL_AND : SQL_WHERE);
    hql.append(clause);
    return true;
  }

  private static void appendSearchClause(StringBuilder hql,
      List<String> searchableProperties, String alias, String search) {
    appendCustomSearchFilter(hql, searchableProperties, alias, search, hql.length() > 0);
  }

  private static String resolveSelectorOrgFilter(String entityName, String alias,
      String contextOrganizationId) {
    String orgFilter = buildOrganizationPredicate(entityName, alias, contextOrganizationId, true);
    if (StringUtils.isNotBlank(orgFilter)) {
      return orgFilter;
    }
    return buildReadableOrgsPredicate(entityName, alias, true);
  }

  /**
   * Build a readable-organizations filter for org-aware entities.
   * Optionally includes organization "0" (the "*" org).
   *
   * @return a predicate string like {@code alias.organization.id IN ('org1','0')}, or {@code null}
   */
  static String buildReadableOrgsPredicate(String entityName, String alias, boolean includeOrgZero) {
    Entity entityDef = ModelProvider.getInstance().getEntity(entityName);
    if (entityDef == null || !entityDef.hasProperty("organization")) {
      return null;
    }
    OBContext ctx = OBContext.getOBContext();
    if (ctx == null) {
      return null;
    }
    String[] readableOrgs = ctx.getReadableOrganizations();
    Set<String> orgIds = new LinkedHashSet<>();
    if (readableOrgs != null) {
      for (String orgId : readableOrgs) {
        if (StringUtils.isNotBlank(orgId)) {
          orgIds.add(orgId);
        }
      }
    }
    if (includeOrgZero) {
      orgIds.add("0");
    }
    if (orgIds.isEmpty()) {
      return null;
    }
    StringBuilder filter = new StringBuilder(alias).append(".organization.id IN (");
    boolean first = true;
    for (String orgId : orgIds) {
      if (!first) {
        filter.append(", ");
      }
      filter.append("'").append(orgId.replace("'", "''")).append("'");
      first = false;
    }
    filter.append(")");
    return filter.toString();
  }

  /**
   * Build an organization filter bound to a single org context.
   * Optionally includes organization "0" (the "*" org) to preserve shared master data visibility.
   */
  static String buildOrganizationPredicate(String entityName, String alias,
      String organizationId, boolean includeOrgZero) {
    if (StringUtils.isBlank(organizationId)) {
      return null;
    }
    Entity entityDef = ModelProvider.getInstance().getEntity(entityName);
    if (entityDef == null || !entityDef.hasProperty("organization")) {
      return null;
    }
    Set<String> orgIds = new LinkedHashSet<>();
    orgIds.add(organizationId.trim());
    if (includeOrgZero) {
      orgIds.add("0");
    }
    StringBuilder filter = new StringBuilder(alias).append(".organization.id IN (");
    boolean first = true;
    for (String orgId : orgIds) {
      if (StringUtils.isBlank(orgId)) {
        continue;
      }
      if (!first) {
        filter.append(", ");
      }
      filter.append("'").append(orgId.replace("'", "''")).append("'");
      first = false;
    }
    if (first) {
      return null;
    }
    filter.append(")");
    return filter.toString();
  }

  /**
   * Append a readable-organizations IN filter to an HQL builder when applicable.
   * Adds a filter only for org-aware entities.
   *
   * @return {@code true} if a WHERE clause is now present (was already present, or was just added)
   */
  static boolean appendReadableOrgsFilter(StringBuilder hql, String alias,
      String entityName, boolean hasWhere, boolean includeOrgZero) {
    String orgFilter = buildReadableOrgsPredicate(entityName, alias, includeOrgZero);
    if (StringUtils.isBlank(orgFilter)) {
      return hasWhere;
    }
    hql.append(hasWhere ? SQL_AND : SQL_WHERE);
    hql.append(orgFilter);
    return true;
  }

  /**
   * Append a full-text search predicate across all searchable properties.
   * Emits an OR clause: {@code (lower(COALESCE(cast(alias.prop as string), '')) LIKE :search)}.
   * No-op when {@code search} is blank or {@code searchableProps} is empty.
   */
  static void appendCustomSearchFilter(StringBuilder hql,
      List<String> searchableProps, String alias, String search, boolean hasWhere) {
    if (StringUtils.isBlank(search) || searchableProps.isEmpty()) {
      return;
    }
    hql.append(hasWhere ? SQL_AND : SQL_WHERE).append("(");
    for (int i = 0; i < searchableProps.size(); i++) {
      if (i > 0) {
        hql.append(" OR ");
      }
      hql.append("lower(COALESCE(cast(").append(alias).append(".")
          .append(searchableProps.get(i)).append(" as string), '')) LIKE :search");
    }
    hql.append(")");
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
   * Extract and normalize the record ID from an HQL Object[] row.
   * Handles BaseOBObject, composite 64-char UUIDs, and plain string values.
   */
  static String extractRecordId(Object[] row, Integer idColIdx) {
    Object idVal = (idColIdx != null && idColIdx < row.length) ? row[idColIdx] : row[0];
    String recordId = idVal instanceof BaseOBObject
        ? ((BaseOBObject) idVal).getId().toString()
        : String.valueOf(idVal);
    // OBUISEL selectors may return composite IDs (e.g., warehouseId + productId = 64 hex chars).
    // Etendo entity UUIDs are always 32 hex chars. Extract the actual entity ID (last 32 chars).
    if (recordId != null && recordId.length() == 64 && recordId.matches("[0-9A-Fa-f]{64}")) {
      recordId = recordId.substring(32);
    }
    return recordId;
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

  /**
   * Replace @param@ placeholders in OBUISEL where/HQL clauses with values from OBContext.
   * Known context params (AD_Org_ID, AD_Client_ID, AD_User_ID, AD_Role_ID) are resolved
   * case-insensitively. Unknown params (e.g. @inpmWarehouseId@) that depend on form context
   * are replaced with NULL since NEO selectors don't have that context yet.
   */
  static String resolveObuiselParams(String whereClause) {
    OBContext ctx = OBContext.getOBContext();
    java.util.Map<String, String> knownParams = new java.util.HashMap<>();
    knownParams.put("ad_org_id", "'" + ctx.getCurrentOrganization().getId() + "'");
    knownParams.put("ad_client_id", "'" + ctx.getCurrentClient().getId() + "'");
    knownParams.put("ad_user_id", "'" + ctx.getUser().getId() + "'");
    knownParams.put("ad_role_id", "'" + ctx.getRole().getId() + "'");
    // Common aliases
    knownParams.put("client", "'" + ctx.getCurrentClient().getId() + "'");

    java.util.regex.Matcher m = PARAM_PATTERN.matcher(whereClause);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String paramName = m.group(1);
      String resolved = knownParams.get(paramName.toLowerCase());
      if (resolved != null) {
        m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(resolved));
      } else {
        // Unknown context param — replace with NULL so the condition
        // evaluates safely rather than crashing with '@' parse error
        log.debug("Unresolved OBUISEL param @{}@ replaced with NULL", paramName);
        m.appendReplacement(sb, "NULL");
      }
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /**
   * Resolve the column's validation rule into an HQL filter using context params.
   *
   * Validation rules are SQL-style clauses like:
   *   C_DocType.DocBaseType IN ('SOO', 'POO') AND C_DocType.IsSOTrx='@IsSOTrx@'
   *   AND (AD_ISORGINCLUDED(@AD_Org_ID@, ...) <> '-1' OR ...)
   *
   * This method applies a best-effort strategy:
   * 1. Splits the validation code into top-level AND clauses
   * 2. For each clause, checks if all @Param@ placeholders can be resolved
   * 3. Includes clauses where all params are available (or that have no params)
   * 4. Skips clauses with unresolvable params or SQL-only functions
   * 5. Converts TABLE.COLUMN references to DAL property paths (e.property)
   *
   * This ensures static filters (e.g., DocBaseType IN ('POO')) are always applied
   * even when context-dependent clauses cannot be resolved.
   */
  static String resolveValidationFilter(Column column, String targetEntityName,
      Map<String, String> contextParams) {
    String resolvedSql = resolveValidationSql(column, contextParams);
    if (StringUtils.isBlank(resolvedSql)) {
      return null;
    }
    // Convert SQL TABLE.COLUMN references to HQL e.property paths
    return convertSqlToHql(resolvedSql, targetEntityName);
  }

  /**
   * Resolve validation rules into a SQL clause with context params substituted.
   * Used by list selectors that execute SQL restrictions directly.
   */
  static String resolveValidationSql(Column column, Map<String, String> contextParams) {
    Validation valRule = column.getValidation();
    if (valRule == null || StringUtils.isBlank(valRule.getValidationCode())) {
      return null;
    }

    String code = valRule.getValidationCode().trim();
    Map<String, String> allParams = buildValidationParamMap(contextParams);
    List<String> clauses = splitTopLevelAnd(code);

    List<String> resolvedClauses = new ArrayList<>();
    for (String clause : clauses) {
      String resolved = resolveValidationClause(clause.trim(), allParams);
      if (resolved != null) {
        resolvedClauses.add(resolved);
      }
    }

    if (resolvedClauses.isEmpty()) {
      return null;
    }
    return String.join(SQL_AND, resolvedClauses);
  }

  /**
   * Build combined param map from OBContext session variables and caller-provided params.
   */
  private static Map<String, String> buildValidationParamMap(Map<String, String> contextParams) {
    Map<String, String> allParams = new java.util.HashMap<>();
    OBContext ctx = OBContext.getOBContext();
    allParams.put("AD_Org_ID", ctx.getCurrentOrganization().getId());
    allParams.put("AD_Client_ID", ctx.getCurrentClient().getId());
    allParams.put("AD_User_ID", ctx.getUser().getId());
    allParams.put("AD_Role_ID", ctx.getRole().getId());
    if (contextParams != null) {
      allParams.putAll(contextParams);
    }
    Map<String, String> normalized = new HashMap<>();
    for (Map.Entry<String, String> entry : allParams.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      normalized.put(entry.getKey().toLowerCase(), entry.getValue());
      normalized.put(entry.getKey().toUpperCase(), entry.getValue());
    }
    allParams.putAll(normalized);
    return allParams;
  }

  /**
   * Attempt to resolve a single validation AND-clause by substituting all @Param@ references.
   * Returns the resolved clause string, or {@code null} if the clause should be skipped
   * (SQL-only function detected, or a param cannot be resolved).
   */
  private static String resolveValidationClause(String trimmed, Map<String, String> allParams) {
    if (StringUtils.isBlank(trimmed)) {
      return null;
    }

    // Skip clauses containing SQL-only functions (no HQL equivalent)
    if (SQL_ONLY_FUNCTIONS.matcher(trimmed).find()) {
      log.debug("Skipping validation clause with SQL-only function: {}", trimmed);
      return null;
    }

    // Check if all @Param@ in this clause can be resolved
    Matcher paramMatcher = VALIDATION_PARAM.matcher(trimmed);
    while (paramMatcher.find()) {
      if (lookupParamValue(allParams, paramMatcher.group(1)) == null) {
        log.debug("Skipping validation clause with unresolvable params: {}", trimmed);
        return null;
      }
    }

    // Replace @Param@ and @#Param@ with sanitized values
    StringBuffer resolved = new StringBuffer();
    paramMatcher = VALIDATION_PARAM.matcher(trimmed);
    while (paramMatcher.find()) {
      String rawValue = lookupParamValue(allParams, paramMatcher.group(1));
      if (rawValue == null) {
        return null;
      }
      String value = rawValue.replace("'", "''");
      paramMatcher.appendReplacement(resolved, Matcher.quoteReplacement("'" + value + "'"));
    }
    paramMatcher.appendTail(resolved);
    return resolved.toString();
  }

  private static String lookupParamValue(Map<String, String> allParams, String key) {
    if (allParams == null || StringUtils.isBlank(key)) {
      return null;
    }
    String exact = allParams.get(key);
    if (exact != null) {
      return exact;
    }
    String lower = allParams.get(key.toLowerCase());
    if (lower != null) {
      return lower;
    }
    return allParams.get(key.toUpperCase());
  }

  /**
   * Split a SQL WHERE clause into top-level AND segments.
   * Respects parentheses nesting so that "A AND (B OR C AND D) AND E"
   * splits into ["A", "(B OR C AND D)", "E"], not into inner parts.
   */
  static List<String> splitTopLevelAnd(String code) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    String upper = code.toUpperCase();

    for (int i = 0; i < code.length(); i++) {
      char c = code.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (depth == 0 && i + 3 < code.length()
          && upper.substring(i, i + 3).equals("AND")
          && (i == 0 || !Character.isLetterOrDigit(code.charAt(i - 1)))
          && !Character.isLetterOrDigit(code.charAt(i + 3))) {
        // Top-level AND (preceded/followed by non-alphanumeric character)
        parts.add(code.substring(start, i).trim());
        start = i + 3;
      }
    }
    // Add the last segment
    String last = code.substring(start).trim();
    if (!last.isEmpty()) {
      parts.add(last);
    }
    return parts;
  }

  /**
   * Convert a SQL-style validation clause to HQL.
   * Replaces TABLE.COLUMN with e.dalProperty, handling FK columns (_ID -> .id).
   * Also converts bare column names (without table prefix) to e.property paths.
   *
   * Example: "C_DocType.DocBaseType IN ('POO')" -> "e.documentType IN ('POO')"
   * Example: "docsubtypeso like 'OB'" -> "e.sOSubType like 'OB'"
   */
  static String convertSqlToHql(String sqlClause, String targetEntityName) {
    try {
      Entity targetEntity = ModelProvider.getInstance().getEntity(targetEntityName);
      if (targetEntity == null) {
        return sqlClause;
      }

      String tableName = targetEntity.getTableName();

      // Step 1: Replace TABLE.COLUMN patterns with e.property
      Pattern tableColPattern = Pattern.compile(
          Pattern.quote(tableName) + "\\.(\\w+)", Pattern.CASE_INSENSITIVE);
      Matcher m = tableColPattern.matcher(sqlClause);

      StringBuffer result = new StringBuffer();
      while (m.find()) {
        String dbColName = m.group(1);
        String replacement = resolveColumnToHql(targetEntity, dbColName);
        m.appendReplacement(result, Matcher.quoteReplacement(replacement));
      }
      m.appendTail(result);
      String afterTableRef = result.toString();

      // Step 2: Replace bare column names that are NOT already prefixed with "e."
      // Match word tokens that could be column names (not inside quotes, not SQL keywords)
      Pattern bareColPattern = Pattern.compile("(?<![.'\"])\\b(\\w+)\\b(?!\\s*\\()");
      m = bareColPattern.matcher(afterTableRef);
      result = new StringBuffer();
      java.util.Set<String> sqlKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
          "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN",
          "COALESCE", "CAST", "AS", "SELECT", "FROM", "WHERE", "ORDER", "BY",
          "ASC", "DESC", "TRUE", "FALSE", "ESCAPE", "e"));
      while (m.find()) {
        String token = m.group(1);
        // Skip SQL keywords, string literals, numbers, and already-resolved "e." references
        if (sqlKeywords.contains(token.toUpperCase())
            || token.matches("\\d+")
            || (m.start() > 0 && afterTableRef.charAt(m.start() - 1) == '.')) {
          continue;
        }
        replaceBareColumnToken(m, result, targetEntity, token);
      }
      m.appendTail(result);
      return result.toString();
    } catch (Exception e) {
      log.warn("Could not convert SQL to HQL for entity {}: {}", targetEntityName, e.getMessage());
      return sqlClause;
    }
  }

  /**
   * Attempt to replace a bare column token in the HQL result buffer.
   * Looks up the token as a DB column name and, if found, appends the HQL replacement.
   * If the token is not a known column or an exception is thrown, no replacement is appended.
   */
  private static void replaceBareColumnToken(Matcher m, StringBuffer result,
      Entity targetEntity, String token) {
    try {
      Property prop = targetEntity.getPropertyByColumnName(token);
      if (prop != null) {
        String replacement = resolveColumnToHql(targetEntity, token);
        m.appendReplacement(result, Matcher.quoteReplacement(replacement));
      }
    } catch (Exception ignored) {
      // Not a column name, leave as-is
    }
  }

  /**
   * Resolve a single DB column name to an HQL "e.property" path.
   */
  static String resolveColumnToHql(Entity targetEntity, String dbColName) {
    Property prop = targetEntity.getPropertyByColumnName(dbColName);
    if (prop != null) {
      if (!prop.isPrimitive() && prop.getTargetEntity() != null) {
        return "e." + prop.getName() + ".id";
      }
      return "e." + prop.getName();
    }
    return "e." + dbColName;
  }
}
