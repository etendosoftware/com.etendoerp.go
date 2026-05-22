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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

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


  /** Pattern matching @param@ and @#param@ (session-level) placeholders in OBUISEL clauses. */
  static final Pattern PARAM_PATTERN = Pattern.compile("@#?([A-Za-z_]+)@");

  // Matches @Param@ and @#Param@ (session-level variables in Etendo)
  static final Pattern VALIDATION_PARAM = Pattern.compile("@#?(\\w+)@");

  // SQL functions that have no HQL equivalent and must cause a clause to be skipped.
  static final Pattern SQL_ONLY_FUNCTIONS = Pattern.compile(
      "AD_ISORGINCLUDED|AD_ISCHILDORGINCLUDED|AD_ROLE_ORGACCESS", Pattern.CASE_INSENSITIVE);

  // Matches "(SELECT expr FROM DUAL)" — Oracle-ism for inline expressions.
  // HQL doesn't support FROM DUAL; the fix is to unwrap the subquery into just "(expr)".
  // Uses a tempered greedy token so the pattern does NOT cross inner "(SELECT" subquery boundaries,
  // which prevents accidentally consuming an outer EXISTS(SELECT 1 FROM ...) when it contains
  // multiple nested (SELECT ... FROM DUAL) clauses. The lookahead allows whitespace between
  // "(" and "SELECT" so subqueries formatted as "(\n  SELECT ..." are also detected.
  static final Pattern SELECT_FROM_DUAL = Pattern.compile(
      "\\(\\s*SELECT\\s+((?:(?!\\(\\s*SELECT).)+?)\\s+FROM\\s+DUAL\\s*\\)",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  // Matches "FROM <name> <alias>" in subqueries to detect SQL table names that need HQL translation
  static final Pattern FROM_WITH_ALIAS = Pattern.compile(
      "\\bFROM\\s+(\\w+)\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

  // Matches: LHS_EXPR = (CASE WHEN 'LIT1' = 'LIT2' THEN 'THEN_LIT' ELSE ELSE_EXPR END)
  // Used to simplify constant CASE expressions that arise from FROM DUAL unwrapping.
  static final Pattern LITERAL_CASE_WHEN = Pattern.compile(
      "(\\w++(?:\\.\\w++)*+)\\s*+=\\s*+" +
      "\\(CASE\\s++WHEN\\s++'([^']*+)'\\s*+=\\s*+'([^']*+)'\\s++THEN\\s++'([^']*+)'\\s++ELSE\\s++(\\w++(?:\\.\\w++)*+)\\s++END\\)",
      Pattern.CASE_INSENSITIVE);

  private SelectorQueryBuilder() {
  }

  static final class HqlWithParams {
    private final String hql;
    private final Map<String, Object> params;

    public HqlWithParams(String hql, Map<String, Object> params) {
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
    appendResolvedClause(hql, queryParams, SelectorValidationResolver.resolveObuiselParams(meta.whereClause));
    appendResolvedClause(hql, queryParams, HqlWithParams.of(validationFilter));
    appendResolvedClause(hql, queryParams,
        SelectorOrgFilter.resolveSelectorOrgFilter(meta.entityName, alias, contextOrganizationId));
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

    hasWhere = appendClause(baseHql, queryParams, SelectorValidationResolver.resolveObuiselParams(meta.whereClause), hasWhere);
    hasWhere = appendClause(baseHql, queryParams, HqlWithParams.of(validationFilter), hasWhere);
    hasWhere = appendClause(baseHql, queryParams,
        SelectorOrgFilter.resolveSelectorOrgFilter(meta.entityName, alias, contextOrganizationId), hasWhere);

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

    SelectorOrgFilter.appendCustomSearchFilter(baseHql, meta.searchableProperties, alias, search, hasWhere);

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
    SelectorOrgFilter.appendCustomSearchFilter(hql, searchableProperties, alias, search, hql.length() > 0);
  }


}
