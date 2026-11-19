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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.domain.Validation;

/**
 * Validation filter resolution helpers extracted from {@link SelectorQueryBuilder}.
 * Move here: resolveObuiselParams, resolveValidationFilter, resolveValidationSql,
 * buildValidationParamMap, resolveValidationClause, substituteValidationParams,
 * quotedCsv, lookupParamValue. Also move the VALIDATION_PARAM pattern if it is only
 * used here.
 */
public final class SelectorValidationResolver {
  private static final Logger log = LogManager.getLogger(SelectorValidationResolver.class);
  private static final String UNRESOLVED_MARKER = "__NEO_UNRESOLVED__";

  private static final Set<String> RESERVED_SESSION_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      "ad_org_id",
      "ad_client_id",
      "ad_user_id",
      "ad_role_id",
      "default_ad_org_id",
      "default_ad_client_id",
      "default_ad_role_id",
      "default_ad_user_id",
      "product_org",
      "parent_ad_org",
      "user_client",
      "userclient",
      "user_org",
      "userorg",
      "client"
  )));

  private SelectorValidationResolver() {
  }

  /**
   * Check whether the given key corresponds to a reserved server-side session parameter.
   * Client-supplied context parameters matching these keys (case-insensitively, with or
   * without leading '#') cannot override session values.
   */
  static boolean isReservedSessionKey(String key) {
    if (StringUtils.isBlank(key)) {
      return false;
    }
    String normalized = key.trim().toLowerCase();
    if (normalized.startsWith("#")) {
      normalized = normalized.substring(1);
    }
    if (RESERVED_SESSION_KEYS.contains(normalized)) {
      return true;
    }
    String noUnderscore = normalized.replace("_", "");
    return RESERVED_SESSION_KEYS.contains(noUnderscore);
  }

  private static boolean isSessionCsv(String key, String value) {
    if (StringUtils.isBlank(key) || value == null) {
      return false;
    }
    String stripped = key.startsWith("#") ? key.substring(1) : key;
    String lower = stripped.toLowerCase().replace("_", "");
    return "userclient".equals(lower) || "userorg".equals(lower);
  }

  /**
   * Replace @param@ placeholders in OBUISEL where/HQL clauses with values from OBContext.
   * Known context params (AD_Org_ID, AD_Client_ID, AD_User_ID, AD_Role_ID) are resolved
   * case-insensitively. Clauses containing unknown params (e.g. @inpcAcctschemaId@) are
   * dropped entirely so they do not evaluate to a permanently-false NULL comparison.
   */
  static SelectorQueryBuilder.HqlWithParams resolveObuiselParams(String whereClause) {
    if (StringUtils.isBlank(whereClause)) {
      return SelectorQueryBuilder.HqlWithParams.empty();
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
    java.util.regex.Matcher m = SelectorQueryBuilder.PARAM_PATTERN.matcher(whereClause);
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
        // Unknown context param (e.g. @inpcAcctschemaId@): mark the clause for removal
        // so it does not produce a permanently-false "= NULL" comparison.
        log.debug("Unresolved OBUISEL param @{}@, clause will be dropped", paramName);
        m.appendReplacement(sb, UNRESOLVED_MARKER);
      }
    }
    m.appendTail(sb);
    // Unwrap "(SELECT expr FROM DUAL)" → "(expr)" — Oracle-ism not supported by HQL
    String afterDual = SqlToHqlTranslator.unwrapSelectFromDual(sb.toString());

    // Strip top-level AND clauses that contain SQL-only functions (AD_ISORGINCLUDED, etc.)
    // or an unresolved-param marker — both would break the HQL query.
    List<String> clauses = SqlToHqlTranslator.splitTopLevelAnd(afterDual);
    List<String> safeClauses = new ArrayList<>();
    for (String clause : clauses) {
      if (clause.contains(UNRESOLVED_MARKER)) {
        log.debug("Dropping OBUISEL clause with unresolved context param: {}", clause);
      } else if (SelectorQueryBuilder.SQL_ONLY_FUNCTIONS.matcher(clause).find()) {
        log.debug("Dropping OBUISEL clause with SQL-only function: {}", clause);
      } else {
        safeClauses.add(clause);
      }
    }
    String resolved = safeClauses.isEmpty() ? "" : String.join(SelectorQueryBuilder.SQL_AND, safeClauses);
    return new SelectorQueryBuilder.HqlWithParams(resolved, queryParams);
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
   * 3. Binds dynamic context parameters as named HQL parameters (:valParamN)
   * 4. Skips clauses with unresolvable params or SQL-only functions
   * 5. Converts TABLE.COLUMN references to DAL property paths (e.property)
   */
  static SelectorQueryBuilder.HqlWithParams resolveValidationFilter(Column column, String targetEntityName,
      Map<String, String> contextParams) {
    if (column == null) {
      return null;
    }
    Validation valRule = column.getValidation();
    if (valRule == null || StringUtils.isBlank(valRule.getValidationCode())) {
      return null;
    }

    String code = valRule.getValidationCode().trim();
    Map<String, String> allParams = buildValidationParamMap(contextParams);
    List<String> clauses = SqlToHqlTranslator.splitTopLevelAnd(code);

    List<String> resolvedClauses = new ArrayList<>();
    Map<String, Object> queryParams = new HashMap<>();
    AtomicInteger paramIndex = new AtomicInteger(0);

    for (String clause : clauses) {
      SelectorQueryBuilder.HqlWithParams resolved = resolveValidationClause(clause.trim(), allParams, paramIndex);
      if (resolved != null && !resolved.isBlank()) {
        resolvedClauses.add(resolved.getHql());
        queryParams.putAll(resolved.getParams());
      }
    }

    if (resolvedClauses.isEmpty()) {
      return null;
    }
    // One group, as Classic's ComboTableData does: a top-level OR in the rule must not escape
    // the AND that joins it with the selector's other filters and the search text.
    String joined = "(" + String.join(SelectorQueryBuilder.SQL_AND, resolvedClauses) + ")";
    // Convert SQL TABLE.COLUMN references to HQL e.property paths
    String hql = SqlToHqlTranslator.convertSqlToHql(joined, targetEntityName);
    // Simplify constant CASE WHEN expressions left by FROM DUAL unwrapping after param substitution.
    // Hibernate HQL cannot evaluate string-literal CASE predicates reliably — simplify them away.
    String simplified = SqlToHqlTranslator.simplifyConstantCaseExpressions(hql);
    return new SelectorQueryBuilder.HqlWithParams(simplified, queryParams);
  }

  /**
   * Resolve validation rules into a SQL clause with context params substituted.
   * Used by list selectors that execute SQL restrictions directly.
   */
  static String resolveValidationSql(Column column, Map<String, String> contextParams) {
    if (column == null) {
      return null;
    }
    Validation valRule = column.getValidation();
    if (valRule == null || StringUtils.isBlank(valRule.getValidationCode())) {
      return null;
    }

    String code = valRule.getValidationCode().trim();
    Map<String, String> allParams = buildValidationParamMap(contextParams);
    List<String> clauses = SqlToHqlTranslator.splitTopLevelAnd(code);

    List<String> resolvedClauses = new ArrayList<>();
    for (String clause : clauses) {
      String resolved = resolveValidationClauseToSql(clause.trim(), allParams);
      if (resolved != null) {
        resolvedClauses.add(resolved);
      }
    }

    if (resolvedClauses.isEmpty()) {
      return null;
    }
    return "(" + String.join(SelectorQueryBuilder.SQL_AND, resolvedClauses) + ")";
  }

  private static String resolveValidationClauseToSql(String trimmed, Map<String, String> allParams) {
    if (StringUtils.isBlank(trimmed)) {
      return null;
    }
    if (SelectorQueryBuilder.SQL_ONLY_FUNCTIONS.matcher(trimmed).find()) {
      log.debug("Skipping validation clause with SQL-only function: {}", trimmed);
      return null;
    }
    if (SqlToHqlTranslator.NESTED_SUBQUERY.matcher(trimmed).find()) {
      log.debug("Skipping validation clause with nested subquery: {}", trimmed);
      return null;
    }
    return substituteValidationParamsSql(trimmed, allParams);
  }

  private static String substituteValidationParamsSql(String clause, Map<String, String> allParams) {
    Pattern quotedParam = Pattern.compile("'@#?([^@]+)@'");
    Matcher m = quotedParam.matcher(clause);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String key = m.group(1);
      String value = lookupParamValue(allParams, key);
      String rep;
      if (value == null) {
        rep = "NULL";
      } else if (isSessionCsv(key, value)) {
        rep = value;
      } else {
        rep = "'" + value.replace("'", "''") + "'";
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(rep));
    }
    m.appendTail(sb);

    String intermediate = sb.toString();
    Matcher bare = SelectorQueryBuilder.VALIDATION_PARAM.matcher(intermediate);
    StringBuffer out = new StringBuffer();
    while (bare.find()) {
      String key = bare.group(1);
      String value = lookupParamValue(allParams, key);
      String rep;
      if (value == null) {
        log.debug("Validation param @{}@ not resolvable, substituting NULL in clause: {}",
            key, clause);
        rep = "NULL";
      } else if (isSessionCsv(key, value)) {
        rep = value;
      } else {
        rep = "'" + value.replace("'", "''") + "'";
      }
      bare.appendReplacement(out, Matcher.quoteReplacement(rep));
    }
    bare.appendTail(out);
    return out.toString();
  }

  /**
   * Build combined param map from OBContext session variables and caller-provided params.
   *
   * <p>Mirrors the session variables Etendo Classic exposes to validation rules so that
   * filters like {@code @#User_Client@}, {@code @Default_AD_Org_ID@} or
   * {@code @Parent_AD_Org@} resolve the same way in FIC and in {@code /defaults}.</p>
   */
  private static Map<String, String> buildValidationParamMap(Map<String, String> contextParams) {
    Map<String, String> allParams = new HashMap<>();
    OBContext ctx = OBContext.getOBContext();
    if (ctx != null) {
      String currentOrgId = ctx.getCurrentOrganization() != null ? ctx.getCurrentOrganization().getId() : null;
      String currentClientId = ctx.getCurrentClient() != null ? ctx.getCurrentClient().getId() : null;
      if (currentOrgId != null) {
        allParams.put("AD_Org_ID", currentOrgId);
        allParams.put("Default_AD_Org_ID", currentOrgId);
        allParams.put("Product_Org", currentOrgId);
      }
      if (currentClientId != null) {
        allParams.put("AD_Client_ID", currentClientId);
        allParams.put("Default_AD_Client_ID", currentClientId);
      }
      if (ctx.getUser() != null) {
        allParams.put("AD_User_ID", ctx.getUser().getId());
        allParams.put("Default_AD_User_ID", ctx.getUser().getId());
      }
      if (ctx.getRole() != null) {
        allParams.put("AD_Role_ID", ctx.getRole().getId());
        allParams.put("Default_AD_Role_ID", ctx.getRole().getId());
      }
      String readableClients = quotedCsv(ctx.getReadableClients());
      if (readableClients != null) {
        allParams.put("#User_Client", readableClients);
        allParams.put("User_Client", readableClients);
      }
      String readableOrgs = quotedCsv(ctx.getReadableOrganizations());
      if (readableOrgs != null) {
        allParams.put("#User_Org", readableOrgs);
        allParams.put("User_Org", readableOrgs);
      }
      try {
        if (currentOrgId != null && ctx.getOrganizationStructureProvider() != null) {
          String parentOrg = ctx.getOrganizationStructureProvider().getParentOrg(currentOrgId);
          if (StringUtils.isNotBlank(parentOrg)) {
            allParams.put("Parent_AD_Org", parentOrg);
          }
        }
      } catch (Exception e) {
        log.debug("Could not resolve Parent_AD_Org for {}: {}", currentOrgId, e.getMessage());
      }
    }
    if (contextParams != null) {
      for (Map.Entry<String, String> entry : contextParams.entrySet()) {
        String key = entry.getKey();
        if (key != null && !isReservedSessionKey(key)) {
          allParams.put(key, entry.getValue());
        } else if (key != null && isReservedSessionKey(key)) {
          log.warn("Ignored attempt to override reserved session key in contextParams: {}", key);
        }
      }
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
   * Returns the resolved HqlWithParams, or {@code null} if the clause should be skipped
   * (SQL-only function detected, or a nested subquery detected).
   */
  static SelectorQueryBuilder.HqlWithParams resolveValidationClause(String trimmed,
      Map<String, String> allParams) {
    return resolveValidationClause(trimmed, allParams, new AtomicInteger(0));
  }

  static SelectorQueryBuilder.HqlWithParams resolveValidationClause(String trimmed,
      Map<String, String> allParams, AtomicInteger paramIndex) {
    if (StringUtils.isBlank(trimmed)) {
      return null;
    }

    // Skip clauses containing SQL-only functions (no HQL equivalent)
    if (SelectorQueryBuilder.SQL_ONLY_FUNCTIONS.matcher(trimmed).find()) {
      log.debug("Skipping validation clause with SQL-only function: {}", trimmed);
      return null;
    }

    // Skip clauses with a nested subquery: the generic translator cannot map raw SQL table names
    // to HQL entities. Shared with SqlToHqlTranslator#convertSqlToHql's own nested-subquery guard
    // — see SqlToHqlTranslator.NESTED_SUBQUERY for why this is a single, package-shared pattern.
    if (SqlToHqlTranslator.NESTED_SUBQUERY.matcher(trimmed).find()) {
      log.debug("Skipping validation clause with nested subquery: {}", trimmed);
      return null;
    }

    // AD validation rules are written in SQL and may use SQL table/column names.
    // HQL requires entity/property names. Translate SQL names → HQL names when possible.
    trimmed = SqlToHqlTranslator.translateSqlToHql(trimmed);

    // Substitute @Param@ / @#Param@ with their value, binding context params as named :valParamN
    return substituteValidationParams(trimmed, allParams, paramIndex);
  }

  /**
   * Replace every {@code @Param@} / {@code @#Param@} placeholder in {@code clause} by binding its
   * value from {@code allParams} as a named parameter (:valParamN). Unresolved placeholders
   * become the SQL literal {@code NULL}.
   *
   * <p>Placeholders wrapped in single quotes (e.g. {@code ='@Foo@'}) get special handling:
   * the surrounding quotes are stripped when substituting {@code NULL} or binding :valParamN.</p>
   */
  static SelectorQueryBuilder.HqlWithParams substituteValidationParams(String clause,
      Map<String, String> allParams) {
    return substituteValidationParams(clause, allParams, new AtomicInteger(0));
  }

  static SelectorQueryBuilder.HqlWithParams substituteValidationParams(String clause,
      Map<String, String> allParams, AtomicInteger paramIndex) {
    if (clause == null) {
      return SelectorQueryBuilder.HqlWithParams.empty();
    }
    Map<String, Object> queryParams = new HashMap<>();

    // First pass: placeholders wrapped in single quotes (e.g. ='@Foo@') — for unresolved
    // vars strip the quotes so the output is =NULL rather than ='NULL'.
    // For resolved vars, bind as :valParamN (without surrounding single quotes).
    Pattern quotedParam = Pattern.compile("'@#?([^@]+)@'");
    Matcher m = quotedParam.matcher(clause);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String key = m.group(1);
      String value = lookupParamValue(allParams, key);
      if (value == null) {
        m.appendReplacement(sb, "NULL");
      } else if (isSessionCsv(key, value)) {
        m.appendReplacement(sb, Matcher.quoteReplacement(value));
      } else {
        String hqlParamName = "valParam" + paramIndex.getAndIncrement();
        queryParams.put(hqlParamName, value);
        m.appendReplacement(sb, Matcher.quoteReplacement(":" + hqlParamName));
      }
    }
    m.appendTail(sb);

    // Second pass: bare placeholders.
    // Pre-formatted CSV session vars like #User_Client ("'0','1'") already contain quotes
    // — those are emitted raw to preserve the SQL list syntax. Normal parameters are bound
    // as :valParamN.
    String intermediate = sb.toString();
    Matcher bare = SelectorQueryBuilder.VALIDATION_PARAM.matcher(intermediate);
    StringBuffer out = new StringBuffer();
    while (bare.find()) {
      String key = bare.group(1);
      String value = lookupParamValue(allParams, key);
      if (value == null) {
        log.debug("Validation param @{}@ not resolvable, substituting NULL in clause: {}",
            key, clause);
        bare.appendReplacement(out, "NULL");
      } else if (isSessionCsv(key, value)) {
        bare.appendReplacement(out, Matcher.quoteReplacement(value));
      } else {
        String hqlParamName = "valParam" + paramIndex.getAndIncrement();
        queryParams.put(hqlParamName, value);
        bare.appendReplacement(out, Matcher.quoteReplacement(":" + hqlParamName));
      }
    }
    bare.appendTail(out);
    return new SelectorQueryBuilder.HqlWithParams(out.toString(), queryParams);
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
    String stripped = key.startsWith("#") ? key.substring(1) : key;
    String exact = allParams.get(key);
    if (exact != null) {
      return exact;
    }
    exact = allParams.get(stripped);
    if (exact != null) {
      return exact;
    }
    exact = allParams.get("#" + stripped);
    if (exact != null) {
      return exact;
    }
    String lower = allParams.get(stripped.toLowerCase());
    if (lower != null) {
      return lower;
    }
    lower = allParams.get(("#" + stripped).toLowerCase());
    if (lower != null) {
      return lower;
    }
    String upper = allParams.get(stripped.toUpperCase());
    if (upper != null) {
      return upper;
    }
    return allParams.get(("#" + stripped).toUpperCase());
  }
}
