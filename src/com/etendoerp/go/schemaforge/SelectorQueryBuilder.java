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
import com.etendoerp.go.schemaforge.selector.meta.AuxFieldMeta;
import com.etendoerp.go.schemaforge.selector.meta.RichFieldMeta;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;
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

  /** Pattern matching @param@ and @#param@ (session-level) placeholders in OBUISEL clauses. */
  static final Pattern PARAM_PATTERN = Pattern.compile("@#?([A-Za-z_]+)@");

  // Matches @Param@ and @#Param@ (session-level variables in Etendo)
  static final Pattern VALIDATION_PARAM = Pattern.compile("@#?(\\w+)@");

  // SQL functions that have no HQL equivalent and must cause a clause to be skipped.
  static final Pattern SQL_ONLY_FUNCTIONS = Pattern.compile(
      "AD_ISORGINCLUDED|AD_ISCHILDORGINCLUDED|AD_ROLE_ORGACCESS", Pattern.CASE_INSENSITIVE);

  // Matches "(SELECT expr FROM DUAL)" — Oracle-ism for inline expressions.
  // HQL doesn't support FROM DUAL; the fix is to unwrap the subquery into just "(expr)".
  private static final Pattern SELECT_FROM_DUAL = Pattern.compile(
      "\\(\\s*SELECT\\s+(.+?)\\s+FROM\\s+DUAL\\s*\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  // Matches "FROM <name> <alias>" in subqueries to detect SQL table names that need HQL translation
  private static final Pattern FROM_WITH_ALIAS = Pattern.compile(
      "\\bFROM\\s+(\\w+)\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

  private SelectorQueryBuilder() {
  }

  static final class HqlWithParams {
    private final String hql;
    private final Map<String, Object> params;

    private HqlWithParams(String hql, Map<String, Object> params) {
      this.hql = hql;
      this.params = params;
    }

    static HqlWithParams empty() {
      return new HqlWithParams("", new HashMap<>());
    }

    static HqlWithParams of(String hql) {
      return new HqlWithParams(StringUtils.defaultString(hql), new HashMap<>());
    }

    String getHql() {
      return hql;
    }

    Map<String, Object> getParams() {
      return params;
    }

    boolean isBlank() {
      return StringUtils.isBlank(hql);
    }
  }


  /**
   * Build the OBQuery where-string for a standard rich (OBUISEL) query.
   * Incorporates the OBUISEL where clause, validation filter, and search predicate.
   * Returns "as e [where ...]" ready to pass to {@link OBDal#createQuery}.
   */
  static HqlWithParams buildRichQueryWhereClause(SelectorMeta meta,
      String search, String validationFilter, String alias, String contextOrganizationId) {
    StringBuilder hql = new StringBuilder();
    Map<String, Object> queryParams = new HashMap<>();
    appendResolvedClause(hql, queryParams, resolveObuiselParams(meta.whereClause));
    appendResolvedClause(hql, queryParams, HqlWithParams.of(validationFilter));
    appendResolvedClause(hql, queryParams,
        resolveSelectorOrgFilter(meta.entityName, alias, contextOrganizationId));
    appendSearchClause(hql, meta.searchableProperties, alias, search);

    String whereClause = hql.length() > 0
        ? "as " + alias + " where " + hql
        : "as " + alias;
    return new HqlWithParams(whereClause, queryParams);
  }


  /**
   * Build the FROM-onwards HQL fragment for a custom-HQL selector query.
   * Appends the OBUISEL where clause, validation filter, readable-orgs filter,
   * and (if applicable) a search predicate across the selector's searchable properties.
   *
   * @return the complete FROM clause string, ready to use in COUNT and data queries
   */
  static HqlWithParams buildCustomHqlFromClause(String fromOnwards, String alias,
      SelectorMeta meta, String validationFilter, String search, String contextOrganizationId) {
    StringBuilder baseHql = new StringBuilder(fromOnwards);
    Map<String, Object> queryParams = new HashMap<>();
    boolean hasWhere = Pattern.compile("\\sWHERE\\s", Pattern.CASE_INSENSITIVE)
        .matcher(fromOnwards).find();

    hasWhere = appendClause(baseHql, queryParams, resolveObuiselParams(meta.whereClause), hasWhere);
    hasWhere = appendClause(baseHql, queryParams, HqlWithParams.of(validationFilter), hasWhere);
    hasWhere = appendClause(baseHql, queryParams,
        resolveSelectorOrgFilter(meta.entityName, alias, contextOrganizationId), hasWhere);

    // Apply client filter — custom HQL uses Session.createQuery() which bypasses
    // Hibernate's automatic AD_Client filter (enabled by OBDal). Added explicitly
    // so results are always scoped to the current session client.
    OBContext ctx = OBContext.getOBContext();
    if (ctx != null) {
      String currentClientId = ctx.getCurrentClient().getId();
      if (StringUtils.isNotBlank(currentClientId) && !"0".equals(currentClientId)) {
        hasWhere = appendClause(baseHql, queryParams,
            HqlWithParams.of(alias + ".client.id = '" + currentClientId + "'"), hasWhere);
      }
    }

    appendCustomSearchFilter(baseHql, meta.searchableProperties, alias, search, hasWhere);

    return new HqlWithParams(baseHql.toString(), queryParams);
  }

  private static void appendResolvedClause(StringBuilder hql, Map<String, Object> queryParams,
      HqlWithParams clause) {
    if (clause == null || clause.isBlank()) {
      return;
    }
    if (hql.length() > 0) {
      hql.append(SQL_AND);
    }
    hql.append(clause.getHql());
    queryParams.putAll(clause.getParams());
  }

  private static boolean appendClause(StringBuilder hql, Map<String, Object> queryParams,
      HqlWithParams clause, boolean hasWhere) {
    if (clause == null || clause.isBlank()) {
      return hasWhere;
    }
    hql.append(hasWhere ? SQL_AND : SQL_WHERE);
    hql.append(clause.getHql());
    queryParams.putAll(clause.getParams());
    return true;
  }

  private static void appendSearchClause(StringBuilder hql,
      List<String> searchableProperties, String alias, String search) {
    appendCustomSearchFilter(hql, searchableProperties, alias, search, hql.length() > 0);
  }

  private static HqlWithParams resolveSelectorOrgFilter(String entityName, String alias,
      String contextOrganizationId) {
    HqlWithParams orgFilter = buildOrganizationPredicate(entityName, alias,
        contextOrganizationId, true);
    if (orgFilter != null && !orgFilter.isBlank()) {
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
  static HqlWithParams buildReadableOrgsPredicate(String entityName, String alias,
      boolean includeOrgZero) {
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
    Map<String, Object> params = new HashMap<>();
    params.put("selectorReadableOrgIds", new ArrayList<>(orgIds));
    return new HqlWithParams(alias + ".organization.id IN (:selectorReadableOrgIds)", params);
  }

  /**
   * Build an organization filter bound to a single org context.
   * Optionally includes organization "0" (the "*" org) to preserve shared master data visibility.
   */
  static HqlWithParams buildOrganizationPredicate(String entityName, String alias,
      String organizationId, boolean includeOrgZero) {
    if (StringUtils.isBlank(organizationId)) {
      return null;
    }
    Entity entityDef = ModelProvider.getInstance().getEntity(entityName);
    if (entityDef == null || !entityDef.hasProperty("organization")) {
      return null;
    }
    OBContext ctx = OBContext.getOBContext();
    if (ctx == null) {
      return null;
    }
    Set<String> orgIds = new LinkedHashSet<>(
        ctx.getOrganizationStructureProvider().getNaturalTree(organizationId.trim()));
    if (includeOrgZero) {
      orgIds.add("0");
    }
    orgIds.removeIf(StringUtils::isBlank);
    if (orgIds.isEmpty()) {
      return null;
    }
    Map<String, Object> params = new HashMap<>();
    params.put("selectorContextOrgIds", new ArrayList<>(orgIds));
    return new HqlWithParams(alias + ".organization.id IN (:selectorContextOrgIds)", params);
  }

  /**
   * Append a readable-organizations IN filter to an HQL builder when applicable.
   * Adds a filter only for org-aware entities.
   *
   * @return {@code true} if a WHERE clause is now present (was already present, or was just added)
   */
  static boolean appendReadableOrgsFilter(StringBuilder hql, String alias,
      String entityName, boolean hasWhere, boolean includeOrgZero) {
    HqlWithParams orgFilter = buildReadableOrgsPredicate(entityName, alias, includeOrgZero);
    if (orgFilter == null || orgFilter.isBlank()) {
      return hasWhere;
    }
    hql.append(hasWhere ? SQL_AND : SQL_WHERE);
    hql.append(orgFilter.getHql());
    return true;
  }

  /**
   * Append a full-text search predicate across all searchable properties.
   *
   * <p>Emits an OR clause: {@code (lower(COALESCE(cast(<expr> as string), '')) LIKE :search)}.
   * No-op when {@code search} is blank or {@code searchableProps} is empty.
   *
   * <p>Each fragment is resolved via {@link #resolveSearchableExpression(String, String)}:
   * bare property names are prefixed with the alias (standard selectors with
   * {@code SelectorField.property}), while dotted fragments are used as-is
   * (custom-HQL selectors whose {@code clause_left_part} already contains the alias,
   * e.g. {@code bp.name}).
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
      String expr = resolveSearchableExpression(alias, searchableProps.get(i));
      hql.append("lower(COALESCE(cast(").append(expr).append(" as string), '')) LIKE :search");
    }
    hql.append(")");
  }

  /**
   * Resolve a searchable fragment into a fully-qualified HQL expression.
   *
   * <p>If the fragment already contains a dot (e.g. {@code bp.name}), it is returned
   * as-is — OBUISEL custom selectors store the full HQL path including the alias in
   * {@code clause_left_part}. Otherwise the fragment is a bare property name
   * (standard {@code SelectorField.property}) and is prefixed with {@code alias.}.
   *
   * <p>Package-private for unit testing.
   */
  static String resolveSearchableExpression(String alias, String fragment) {
    if (StringUtils.isBlank(fragment)) {
      return fragment;
    }
    return fragment.contains(".") ? fragment : alias + "." + fragment;
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
   * Extract and normalize the record ID from an HQL Object[] row.
   * Handles BaseOBObject, composite 64-char UUIDs, and plain string values.
   */
  static String extractRecordId(Object[] row, Integer idColIdx) {
    Object idVal = (idColIdx != null && idColIdx < row.length) ? row[idColIdx] : row[0];
    String recordId = idVal instanceof BaseOBObject
        ? ((BaseOBObject) idVal).getId().toString()
        : String.valueOf(idVal);
    return SelectorResponseSupport.normalizeEntityId(recordId);
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
  static HqlWithParams resolveObuiselParams(String whereClause) {
    if (StringUtils.isBlank(whereClause)) {
      return HqlWithParams.empty();
    }
    OBContext ctx = OBContext.getOBContext();
    Map<String, String> knownParams = new HashMap<>();
    if (ctx != null) {
      knownParams.put("ad_org_id", ctx.getCurrentOrganization().getId());
      knownParams.put("ad_client_id", ctx.getCurrentClient().getId());
      knownParams.put("ad_user_id", ctx.getUser().getId());
      knownParams.put("ad_role_id", ctx.getRole().getId());
      knownParams.put("client", ctx.getCurrentClient().getId());
    }

    Map<String, Object> queryParams = new HashMap<>();
    java.util.regex.Matcher m = PARAM_PATTERN.matcher(whereClause);
    StringBuffer sb = new StringBuffer();
    int paramIndex = 0;
    while (m.find()) {
      String paramName = m.group(1);
      String resolved = knownParams.get(paramName.toLowerCase());
      if (resolved != null) {
        String hqlParamName = "obuiselParam" + paramIndex++;
        queryParams.put(hqlParamName, resolved);
        m.appendReplacement(sb,
            java.util.regex.Matcher.quoteReplacement(":" + hqlParamName));
      } else {
        // Unknown context param — replace with NULL so the condition
        // evaluates safely rather than crashing with '@' parse error
        log.debug("Unresolved OBUISEL param @{}@ replaced with NULL", paramName);
        m.appendReplacement(sb, "NULL");
      }
    }
    m.appendTail(sb);
    // Unwrap "(SELECT expr FROM DUAL)" → "(expr)" — Oracle-ism not supported by HQL
    String afterDual = SELECT_FROM_DUAL.matcher(sb.toString()).replaceAll("($1)");

    // Strip top-level AND clauses that contain SQL-only functions (AD_ISORGINCLUDED, etc.)
    // which have no HQL equivalent and would cause a Hibernate parse error.
    List<String> clauses = splitTopLevelAnd(afterDual);
    List<String> safeClauses = new ArrayList<>();
    for (String clause : clauses) {
      if (SQL_ONLY_FUNCTIONS.matcher(clause).find()) {
        log.debug("Dropping OBUISEL clause with SQL-only function: {}", clause);
      } else {
        safeClauses.add(clause);
      }
    }
    String resolved = safeClauses.isEmpty() ? "" : String.join(SQL_AND, safeClauses);
    return new HqlWithParams(resolved, queryParams);
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
   *
   * <p>Mirrors the session variables Etendo Classic exposes to validation rules so that
   * filters like {@code @#User_Client@}, {@code @Default_AD_Org_ID@} or
   * {@code @Parent_AD_Org@} resolve the same way in FIC and in {@code /defaults}.</p>
   */
  private static Map<String, String> buildValidationParamMap(Map<String, String> contextParams) {
    Map<String, String> allParams = new java.util.HashMap<>();
    OBContext ctx = OBContext.getOBContext();
    String currentOrgId = ctx.getCurrentOrganization().getId();
    String currentClientId = ctx.getCurrentClient().getId();
    allParams.put("AD_Org_ID", currentOrgId);
    allParams.put("AD_Client_ID", currentClientId);
    allParams.put("AD_User_ID", ctx.getUser().getId());
    allParams.put("AD_Role_ID", ctx.getRole().getId());
    allParams.put("Default_AD_Org_ID", currentOrgId);
    allParams.put("Default_AD_Client_ID", currentClientId);
    allParams.put("Default_AD_Role_ID", ctx.getRole().getId());
    allParams.put("Default_AD_User_ID", ctx.getUser().getId());
    allParams.put("Product_Org", currentOrgId);
    String readableClients = quotedCsv(ctx.getReadableClients());
    if (readableClients != null) {
      allParams.put("#User_Client", readableClients);
    }
    String readableOrgs = quotedCsv(ctx.getReadableOrganizations());
    if (readableOrgs != null) {
      allParams.put("#User_Org", readableOrgs);
    }
    try {
      String parentOrg = ctx.getOrganizationStructureProvider().getParentOrg(currentOrgId);
      if (StringUtils.isNotBlank(parentOrg)) {
        allParams.put("Parent_AD_Org", parentOrg);
      }
    } catch (Exception e) {
      log.debug("Could not resolve Parent_AD_Org for {}: {}", currentOrgId, e.getMessage());
    }
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

    // AD validation rules are written in SQL and may use SQL table/column names.
    // HQL requires entity/property names. Translate SQL names → HQL names when possible.
    trimmed = translateSqlToHql(trimmed);

    // Substitute @Param@ / @#Param@ with their value, or with literal NULL when the
    // variable can't be resolved from the current context. This mirrors Etendo Classic's
    // ComboTableData, which injects NULL for missing session vars: filters relying on a
    // missing variable naturally return no rows instead of being silently dropped (which
    // would relax the filter and surface arbitrary records).
    return substituteValidationParams(trimmed, allParams);
  }

  /**
   * Replace every {@code @Param@} / {@code @#Param@} placeholder in {@code clause} by its
   * value from {@code allParams}. Unresolved placeholders become the SQL literal
   * {@code NULL}.
   *
   * <p>Placeholders wrapped in single quotes (e.g. {@code ='@Foo@'}) get special handling:
   * the surrounding quotes are stripped when substituting {@code NULL}, so the output is
   * {@code =NULL} instead of the string {@code ='NULL'}. Resolved values in that position
   * keep the surrounding quotes and only have inner quotes escaped.</p>
   */
  private static String substituteValidationParams(String clause, Map<String, String> allParams) {
    // First pass: placeholders wrapped in single quotes (e.g. ='@Foo@') — for unresolved
    // vars strip the quotes so the output is =NULL rather than ='NULL'.
    Pattern quotedParam = Pattern.compile("'@([^@]+)@'");
    Matcher m = quotedParam.matcher(clause);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String value = lookupParamValue(allParams, m.group(1));
      String rep = value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
      m.appendReplacement(sb, Matcher.quoteReplacement(rep));
    }
    m.appendTail(sb);

    // Second pass: bare placeholders.
    // Pre-formatted CSV session vars like #User_Client ("'0','1'") already contain quotes
    // — those are emitted raw to preserve the SQL list syntax. Simple scalar values are
    // wrapped in quotes and have their inner quotes escaped.
    String intermediate = sb.toString();
    Matcher bare = VALIDATION_PARAM.matcher(intermediate);
    StringBuffer out = new StringBuffer();
    while (bare.find()) {
      String value = lookupParamValue(allParams, bare.group(1));
      String rep;
      if (value == null) {
        log.debug("Validation param @{}@ not resolvable, substituting NULL in clause: {}",
            bare.group(1), clause);
        rep = "NULL";
      } else if (value.indexOf('\'') >= 0) {
        rep = value;
      } else {
        rep = "'" + value + "'";
      }
      bare.appendReplacement(out, Matcher.quoteReplacement(rep));
    }
    bare.appendTail(out);
    return out.toString();
  }

  /**
   * Build a comma-separated list of SQL-quoted ids (e.g. {@code '0','1'}).
   * Returns {@code null} when the array is empty or fully blank, so callers
   * can skip injecting the session variable when there are no readable ids.
   */
  private static String quotedCsv(String[] ids) {
    if (ids == null || ids.length == 0) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (String id : ids) {
      if (StringUtils.isBlank(id)) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append('\'').append(id.replace("'", "''")).append('\'');
    }
    return sb.length() == 0 ? null : sb.toString();
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
   * Translates SQL table names and column names to their HQL equivalents in a validation clause.
   * AD validation rules are written in SQL and may use:
   * - SQL table names (e.g. {@code FIN_FinAcc_PaymentMethod}) instead of HQL entity names
   *   (e.g. {@code FinancialMgmtFinAccPaymentMethod})
   * - SQL column names (e.g. {@code Payin_Allow}) instead of HQL property names
   *   (e.g. {@code payinAllow})
   * - FK column names (e.g. {@code FIN_PaymentMethod_ID}) which need {@code .id} appended
   *   (e.g. {@code paymentMethod.id})
   *
   * {@code FROM DUAL} is removed — HQL scalar subqueries work without a FROM clause.
   */
  private static String translateSqlToHql(String clause) {
    clause = SELECT_FROM_DUAL.matcher(clause).replaceAll("($1)");
    Map<String, Entity> aliasEntityMap = new HashMap<>();
    String translatedFrom = translateFromAliases(clause, aliasEntityMap);
    return translateColumnReferences(translatedFrom, aliasEntityMap);
  }

  private static String translateFromAliases(String clause, Map<String, Entity> aliasEntityMap) {
    Matcher fromMatcher = FROM_WITH_ALIAS.matcher(clause);
    StringBuffer translated = new StringBuffer();
    while (fromMatcher.find()) {
      Entity entity = resolveFromEntity(fromMatcher.group(1));
      String alias = fromMatcher.group(2);
      if (entity != null) {
        aliasEntityMap.put(alias, entity);
      }
      if (entity != null && !entity.getName().equals(fromMatcher.group(1))) {
        log.debug("Translating SQL table name [{}] → HQL entity [{}]", fromMatcher.group(1), entity.getName());
        fromMatcher.appendReplacement(translated,
            Matcher.quoteReplacement("FROM " + entity.getName() + " " + alias));
      }
    }
    fromMatcher.appendTail(translated);
    return translated.toString();
  }

  private static Entity resolveFromEntity(String tableName) {
    try {
      Entity hqlEntity = ModelProvider.getInstance().getEntity(tableName);
      if (hqlEntity != null) {
        return hqlEntity;
      }
    } catch (Exception e) {
      // Not a valid HQL entity name — fall through to SQL table lookup.
    }
    return ModelProvider.getInstance().getEntityByTableName(tableName);
  }

  private static String translateColumnReferences(String clause, Map<String, Entity> aliasEntityMap) {
    String result = clause;
    for (Map.Entry<String, Entity> entry : aliasEntityMap.entrySet()) {
      result = translateAliasColumns(result, entry.getKey(), entry.getValue());
    }
    return result;
  }

  private static String translateAliasColumns(String clause, String alias, Entity entity) {
    Pattern colRef = Pattern.compile("\\b" + Pattern.quote(alias) + "\\.(\\w+)");
    Matcher colMatcher = colRef.matcher(clause);
    StringBuffer translated = new StringBuffer();
    while (colMatcher.find()) {
      Property prop = entity.getPropertyByColumnName(colMatcher.group(1), false);
      if (prop != null) {
        String hqlRef = prop.isPrimitive()
            ? alias + "." + prop.getName()
            : alias + "." + prop.getName() + ".id";
        log.debug("Translating column [{}] → HQL property [{}]", colMatcher.group(1), hqlRef);
        colMatcher.appendReplacement(translated, Matcher.quoteReplacement(hqlRef));
      }
    }
    colMatcher.appendTail(translated);
    return translated.toString();
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
