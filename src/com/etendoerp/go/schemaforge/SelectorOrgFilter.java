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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.selector.meta.SearchableFragment;

/**
 * Organization filter helpers extracted from {@link SelectorQueryBuilder}.
 * Move here: resolveSelectorOrgFilter, buildReadableOrgsPredicate, buildOrganizationPredicate,
 * appendReadableOrgsFilter, appendCustomSearchFilter, resolveSearchableExpression,
 * appendRichSearchFilter, resolveRichSearchableExpression.
 */
public final class SelectorOrgFilter {

  private SelectorOrgFilter() {
  }

  static SelectorQueryBuilder.HqlWithParams resolveSelectorOrgFilter(String entityName, String alias,
      String contextOrganizationId) {
    SelectorQueryBuilder.HqlWithParams orgFilter = buildOrganizationPredicate(entityName, alias,
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
  static SelectorQueryBuilder.HqlWithParams buildReadableOrgsPredicate(String entityName, String alias,
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
    return new SelectorQueryBuilder.HqlWithParams(alias + ".organization.id IN (:selectorReadableOrgIds)", params);
  }

  /**
   * Build an organization filter bound to a single org context.
   * Optionally includes organization "0" (the "*" org) to preserve shared master data visibility.
   */
  static SelectorQueryBuilder.HqlWithParams buildOrganizationPredicate(String entityName, String alias,
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
    return new SelectorQueryBuilder.HqlWithParams(alias + ".organization.id IN (:selectorContextOrgIds)", params);
  }

  /**
   * Append a readable-organizations IN filter to an HQL builder when applicable.
   * Adds a filter only for org-aware entities.
   *
   * @return {@code true} if a WHERE clause is now present (was already present, or was just added)
   */
  static boolean appendReadableOrgsFilter(StringBuilder hql, String alias,
      String entityName, boolean hasWhere, boolean includeOrgZero) {
    SelectorQueryBuilder.HqlWithParams orgFilter = buildReadableOrgsPredicate(entityName, alias, includeOrgZero);
    if (orgFilter == null || orgFilter.isBlank()) {
      return hasWhere;
    }
    hql.append(hasWhere ? SelectorQueryBuilder.SQL_AND : SelectorQueryBuilder.SQL_WHERE);
    hql.append(orgFilter.getHql());
    return true;
  }

  /**
   * Append a full-text search predicate across all searchable properties of a custom-HQL selector.
   * Standard rich selectors use {@link #appendRichSearchFilter} instead.
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
    List<String> expressions = new ArrayList<>();
    for (String fragment : searchableProps) {
      expressions.add(resolveSearchableExpression(alias, fragment));
    }
    appendSearchPredicate(hql, expressions, search, hasWhere);
  }

  /**
   * Append the full-text search predicate of a standard rich (non-custom) selector.
   *
   * <p>Same predicate as {@link #appendCustomSearchFilter}, but each fragment is resolved via
   * {@link #resolveRichSearchableExpression(String, SearchableFragment)}, so a relative DAL path
   * such as {@code product.name} is always qualified with the alias. A bare path would bind to
   * the outer query once the where clause is copied into the de-dup subquery (ETP-5670).
   */
  static void appendRichSearchFilter(StringBuilder hql,
      List<SearchableFragment> searchableProps, String alias, String search, boolean hasWhere) {
    List<String> expressions = new ArrayList<>();
    for (SearchableFragment fragment : searchableProps) {
      expressions.add(resolveRichSearchableExpression(alias, fragment));
    }
    appendSearchPredicate(hql, expressions, search, hasWhere);
  }

  private static void appendSearchPredicate(StringBuilder hql, List<String> expressions,
      String search, boolean hasWhere) {
    if (StringUtils.isBlank(search) || expressions.isEmpty()) {
      return;
    }
    hql.append(hasWhere ? SelectorQueryBuilder.SQL_AND : SelectorQueryBuilder.SQL_WHERE).append("(");
    for (int i = 0; i < expressions.size(); i++) {
      if (i > 0) {
        hql.append(" OR ");
      }
      hql.append("lower(COALESCE(cast(").append(expressions.get(i)).append(" as string), '')) LIKE :search");
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
   * Resolve a searchable fragment of a standard rich (non-custom) selector into an HQL expression.
   *
   * <p>A relative DAL path ({@code SelectorField.property} or the searchable fallback) is always
   * prefixed with {@code alias.}, dotted or not: {@code product.name} becomes
   * {@code e.product.name}. A {@code clause_left_part} fragment keeps the rule of
   * {@link #resolveSearchableExpression(String, String)}.
   *
   * <p>Package-private for unit testing.
   */
  static String resolveRichSearchableExpression(String alias, SearchableFragment fragment) {
    if (fragment.relativePath && StringUtils.isNotBlank(fragment.expression)) {
      return alias + "." + fragment.expression;
    }
    return resolveSearchableExpression(alias, fragment.expression);
  }
}
