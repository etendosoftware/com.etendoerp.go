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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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


  private SelectorValidationResolver() {
  }

  /**
   * Replace @param@ placeholders in OBUISEL where/HQL clauses with values from OBContext.
   * Known context params (AD_Org_ID, AD_Client_ID, AD_User_ID, AD_Role_ID) are resolved
   * case-insensitively. Unknown params (e.g. @inpmWarehouseId@) that depend on form context
   * are replaced with NULL since NEO selectors don't have that context yet.
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
        // Unknown context param — replace with NULL so the condition
        // evaluates safely rather than crashing with '@' parse error
        log.debug("Unresolved OBUISEL param @{}@ replaced with NULL", paramName);
        m.appendReplacement(sb, "NULL");
      }
    }
    m.appendTail(sb);
    // Unwrap "(SELECT expr FROM DUAL)" → "(expr)" — Oracle-ism not supported by HQL
    String afterDual = SqlToHqlTranslator.unwrapSelectFromDual(sb.toString());

    // Strip top-level AND clauses that contain SQL-only functions (AD_ISORGINCLUDED, etc.)
    // which have no HQL equivalent and would cause a Hibernate parse error.
    List<String> clauses = SqlToHqlTranslator.splitTopLevelAnd(afterDual);
    List<String> safeClauses = new ArrayList<>();
    for (String clause : clauses) {
      if (SelectorQueryBuilder.SQL_ONLY_FUNCTIONS.matcher(clause).find()) {
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
    String hql = SqlToHqlTranslator.convertSqlToHql(resolvedSql, targetEntityName);
    // Simplify constant CASE WHEN expressions left by FROM DUAL unwrapping after param substitution.
    // Hibernate HQL cannot evaluate string-literal CASE predicates reliably — simplify them away.
    return SqlToHqlTranslator.simplifyConstantCaseExpressions(hql);
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
    List<String> clauses = SqlToHqlTranslator.splitTopLevelAnd(code);

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
    return String.join(SelectorQueryBuilder.SQL_AND, resolvedClauses);
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
    if (SelectorQueryBuilder.SQL_ONLY_FUNCTIONS.matcher(trimmed).find()) {
      log.debug("Skipping validation clause with SQL-only function: {}", trimmed);
      return null;
    }

    // AD validation rules are written in SQL and may use SQL table/column names.
    // HQL requires entity/property names. Translate SQL names → HQL names when possible.
    trimmed = SqlToHqlTranslator.translateSqlToHql(trimmed);

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
    Matcher bare = SelectorQueryBuilder.VALIDATION_PARAM.matcher(intermediate);
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
}
