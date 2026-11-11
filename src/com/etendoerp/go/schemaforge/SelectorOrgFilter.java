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
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.DalUtil;
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
   * <p>Emits an OR clause: {@code ((upper(x) LIKE :searchLoose AND lower(x) LIKE :search))} with {@code x = COALESCE(cast(<expr> as string), '')}.
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
    appendCustomSearchFilter(hql, searchableProps, alias, search, hasWhere, null);
  }

  /**
   * Same as above; with the queried {@code entityName}, a fragment that is a plain string property
   * of that entity is searched as {@code upper(col)} (usable by a trigram index on
   * {@code upper(col)}) instead of through a cast.
   */
  static void appendCustomSearchFilter(StringBuilder hql,
      List<String> searchableProps, String alias, String search, boolean hasWhere,
      String entityName) {
    List<String> expressions = new ArrayList<>();
    for (String fragment : searchableProps) {
      expressions.add(resolveSearchableExpression(alias, fragment));
    }
    appendSearchPredicate(hql, expressions, search, hasWhere, entityName, alias);
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
    appendRichSearchFilter(hql, searchableProps, alias, search, hasWhere, null);
  }

  /** Same as above, typing the fragments against {@code entityName} (see appendCustomSearchFilter). */
  static void appendRichSearchFilter(StringBuilder hql,
      List<SearchableFragment> searchableProps, String alias, String search, boolean hasWhere,
      String entityName) {
    List<String> expressions = new ArrayList<>();
    for (SearchableFragment fragment : searchableProps) {
      expressions.add(resolveRichSearchableExpression(alias, fragment));
    }
    appendSearchPredicate(hql, expressions, search, hasWhere, entityName, alias);
  }

  /**
   * The two-part search predicate (ETP-6103): {@code upper(x) LIKE :searchLoose} is the prefilter a
   * trigram index on {@code upper(x)} can serve; {@code lower(x) LIKE :search} keeps the exact
   * pre-ETP-6103 results, because on a C-collation database upper()/lower() only fold ASCII, so a
   * plain {@code upper(x) LIKE upper(:search)} would miss accented text. The caller binds both
   * params (see {@code SelectorQueryExecutor.searchPattern / searchLoosePattern}).
   */
  static String searchLike(String expression) {
    return "(upper(" + expression + ") LIKE :searchLoose AND lower(" + expression + ") LIKE :search)";
  }

  /**
   * One OR-ed {@link #searchLike} per expression. A string column stays a
   * bare {@code upper(col)} that a trigram index on {@code upper(col)} can serve (the lower() half
   * keeps the exact pre-ETP-6103 results on a C-collation database). Other types (and fragments
   * whose type is unknown) keep the cast.
   * A NULL column never matches (before it was '' and matched only a term of wildcards).
   */
  private static void appendSearchPredicate(StringBuilder hql, List<String> expressions,
      String search, boolean hasWhere, String entityName, String alias) {
    if (StringUtils.isBlank(search) || expressions.isEmpty()) {
      return;
    }
    hql.append(hasWhere ? SelectorQueryBuilder.SQL_AND : SelectorQueryBuilder.SQL_WHERE).append("(");
    for (int i = 0; i < expressions.size(); i++) {
      if (i > 0) {
        hql.append(" OR ");
      }
      String expression = expressions.get(i);
      if (isStringProperty(entityName, alias, expression)) {
        hql.append(searchLike(expression));
      } else {
        hql.append(searchLike(
            "COALESCE(cast(" + expression + " as string), '')"));
      }
    }
    hql.append(")");
  }

  /**
   * Whether {@code expression} is {@code alias.<path>} resolving, on {@code entityName}, to a plain
   * String property. False when unknown (another alias, an HQL function, no entity given).
   */
  static boolean isStringProperty(String entityName, String alias, String expression) {
    if (entityName == null || alias == null || expression == null
        || !expression.startsWith(alias + ".")) {
      return false;
    }
    try {
      Entity entity = ModelProvider.getInstance().getEntity(entityName);
      Property property = DalUtil.getPropertyFromPath(entity, expression.substring(alias.length() + 1));
      return property != null && property.isPrimitive() && !property.isOneToMany()
          && String.class.equals(property.getPrimitiveObjectType());
    } catch (RuntimeException e) {
      return false;
    }
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
