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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * Resolves the {@link NeoHandler#readPredicates} an entity's customization declares, as one HQL
 * expression ready to be ANDed into a list read (ETP-5009).
 *
 * <p>The single place every generic list read asks for them — the REST list {@code GET}, the REST
 * {@code ?_distinct=} value fetch and MCP {@code etendo_list} — so the three cannot disagree about
 * which rows exist. Resolution goes through {@link NeoExtensionDispatcher#resolveOnly}, i.e. the
 * same order every other surface uses: the {@link NeoExtension} annotation first, the entity's
 * {@code Java_Qualifier} second, with the resolver the channel's callers already use.</p>
 *
 * <p>Nothing here names an entity: what to restrict is entirely the customization's decision.
 * Nothing is swallowed either: a customization that throws propagates to the caller's own error
 * handling, so a read never silently returns the rows a predicate was meant to exclude.</p>
 */
public final class NeoReadPredicates {

  private static final String HQL_AND = " and ";

  private NeoReadPredicates() {
  }

  /**
   * The combined read predicate the entity's customization declares for this read.
   *
   * @param context the read context; its spec, entity and {@code SFEntity} qualifier select the
   *                customization. May be {@code null}, which declares nothing
   * @param channel the caller family, which selects the qualifier resolver
   * @return the declared predicates, each parenthesised and joined with {@code and}, or
   *         {@code null} when the entity has no customization or it declares none
   */
  public static String resolve(NeoContext context, NeoExtensionChannel channel) {
    if (context == null) {
      return null;
    }
    NeoHandler customization = NeoExtensionDispatcher.resolveOnly(NeoExtensionRequest.builder()
        .qualifier(context.getSfEntity() != null ? context.getSfEntity().getJavaQualifier() : null)
        .specName(context.getSpecName())
        .entityName(context.getEntityName())
        .surface(NeoExtensionSurface.READ)
        .channel(channel)
        .context(context)
        .build());
    if (customization == null) {
      return null;
    }
    return join(customization.readPredicates(context));
  }

  /**
   * ANDs {@code predicate} onto an existing where clause.
   *
   * @param existing  the clause built so far; may be blank
   * @param predicate the predicate to add; may be blank
   * @return the combined clause, or whichever of the two is not blank
   */
  public static String and(String existing, String predicate) {
    if (StringUtils.isBlank(predicate)) {
      return existing;
    }
    if (StringUtils.isBlank(existing)) {
      return predicate;
    }
    return "(" + existing + ")" + HQL_AND + "(" + predicate + ")";
  }

  /**
   * The read predicate for a REST list {@code GET}, or {@code null} for anything else. A read by
   * id ({@link NeoContext#isReadById}: a path id or a query-string {@code id}) is skipped on
   * purpose: core resolves it with its own {@code id = :id} query and never applies the where
   * clause, so a handler that hides a row from it does so in {@code afterHandle}, gated on the
   * very same {@code isReadById}.
   *
   * @param context the REST read context; must not be {@code null}
   * @return the combined predicate, or {@code null} when not a list {@code GET} or none declared
   */
  static String forRestListGet(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod()) || context.isReadById()) {
      return null;
    }
    return resolve(context, NeoExtensionChannel.REST_SINGLE);
  }

  /**
   * Appends the REST read predicate, parenthesised, to a predicate list that will be joined with
   * {@code and} (the REST {@code ?_distinct=} value fetch). A blank predicate adds nothing.
   *
   * @param predicates the predicate list being built
   * @param context    the REST read context
   */
  static void addRestTo(List<String> predicates, NeoContext context) {
    String readPredicate = resolve(context, NeoExtensionChannel.REST_SINGLE);
    if (StringUtils.isNotBlank(readPredicate)) {
      predicates.add("(" + readPredicate + ")");
    }
  }

  /**
   * ANDs a parenthesised {@code predicate} onto {@code where} in place; a blank predicate is a
   * no-op. Unlike {@link #and}, the clause built so far is not re-parenthesised.
   *
   * @param where     the clause being built; may be empty
   * @param predicate the predicate to add; may be blank
   */
  static void appendAnd(StringBuilder where, String predicate) {
    if (StringUtils.isBlank(predicate)) {
      return;
    }
    if (where.length() > 0) {
      where.append(HQL_AND);
    }
    where.append("(").append(predicate).append(")");
  }

  /** Parenthesise and AND the non-blank predicates; {@code null} when there are none. */
  private static String join(List<String> predicates) {
    if (predicates == null || predicates.isEmpty()) {
      return null;
    }
    List<String> parts = new ArrayList<>(predicates.size());
    for (String predicate : predicates) {
      if (StringUtils.isNotBlank(predicate)) {
        parts.add("(" + predicate + ")");
      }
    }
    return parts.isEmpty() ? null : String.join(HQL_AND, parts);
  }
}
