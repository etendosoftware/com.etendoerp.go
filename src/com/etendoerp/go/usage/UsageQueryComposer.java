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

package com.etendoerp.go.usage;

import org.apache.commons.lang3.StringUtils;

/**
 * Composes the HQL that counts a declarative usage resource, and contains the
 * implementer-authored restriction fragment carried on
 * {@code ETGO_BILLING_RESOURCE.HQL_Restriction}.
 *
 * <p><b>Why containment is structural rather than textual.</b> The obvious composition,
 * {@code ... and ( <fragment> )}, does <em>not</em> contain the fragment. A fragment of
 * {@code 1=1) or (1=1} composes to {@code ... and (1=1) or (1=1)}, where the {@code or} is
 * top level and the tenant filter no longer applies to it. One such catalog row would
 * mis-attribute every tenant at once, so the fragment is instead confined to a subquery:
 * a paren breakout inside it can only widen that subquery's candidate set, while the outer
 * {@code and}-chain still clamps the day and tenant attribution comes from the
 * {@code group by}, which no fragment can reach. A breakout that escapes the subquery
 * entirely leaves unbalanced parentheses and fails to parse, which
 * {@link #validateFragment(String)} rejects at save time.
 *
 * <p>The fragment is written against alias {@code e}; the outer query uses {@code outer_}.
 * Bounds are always named parameters, never interpolated.
 */
public final class UsageQueryComposer {

  /** Named parameter for the inclusive start of the counted day. */
  public static final String PARAM_DAY_START = "dayStart";
  /** Named parameter for the exclusive end of the counted day. */
  public static final String PARAM_DAY_END = "dayEnd";
  /** Named parameter for the tenant, used only by the per-tenant form. */
  public static final String PARAM_CLIENT_ID = "clientId";

  private static final String ALIAS_OUTER = "outer_";
  private static final String AND = " and ";
  private static final String ALIAS_FRAGMENT = "e";
  private static final char QUOTE = '\'';

  private UsageQueryComposer() {
  }

  /**
   * Counts one day across every tenant, grouped by client.
   *
   * @param entityName the entity to count rows of
   * @param dateProperty the date property that places a row on a day
   * @param restriction optional HQL fragment narrowing the rows; validated before use
   * @return HQL selecting {@code (clientId, count)} rows; bind {@link #PARAM_DAY_START} and
   *     {@link #PARAM_DAY_END}
   */
  public static String composeGroupedCount(String entityName, String dateProperty,
      String restriction) {
    requireIdentifier(entityName, "entityName");
    requireIdentifier(dateProperty, "dateProperty");
    StringBuilder hql = new StringBuilder(256);
    hql.append("select ").append(ALIAS_OUTER).append(".client.id, count(*)")
        .append(" from ").append(entityName).append(' ').append(ALIAS_OUTER)
        .append(" where ").append(dayBounds(dateProperty));
    appendRestriction(hql, entityName, restriction);
    hql.append(" group by ").append(ALIAS_OUTER).append(".client.id");
    return hql.toString();
  }

  /**
   * Counts one day for a single tenant. Used by the single-tenant backfill and by the
   * save-time validation probe.
   *
   * @param entityName the entity to count rows of
   * @param dateProperty the date property that places a row on a day
   * @param restriction optional HQL fragment narrowing the rows; validated before use
   * @return HQL selecting a single count; bind {@link #PARAM_CLIENT_ID},
   *     {@link #PARAM_DAY_START} and {@link #PARAM_DAY_END}
   */
  public static String composePerTenantCount(String entityName, String dateProperty,
      String restriction) {
    requireIdentifier(entityName, "entityName");
    requireIdentifier(dateProperty, "dateProperty");
    StringBuilder hql = new StringBuilder(256);
    hql.append("select count(*)")
        .append(" from ").append(entityName).append(' ').append(ALIAS_OUTER)
        .append(" where ").append(ALIAS_OUTER).append(".client.id = :").append(PARAM_CLIENT_ID)
        .append(AND).append(dayBounds(dateProperty));
    appendRestriction(hql, entityName, restriction);
    return hql.toString();
  }

  private static String dayBounds(String dateProperty) {
    return ALIAS_OUTER + '.' + dateProperty + " >= :" + PARAM_DAY_START
        + AND + ALIAS_OUTER + '.' + dateProperty + " < :" + PARAM_DAY_END;
  }

  /**
   * Appends the restriction as a subquery membership test, which is what keeps a paren
   * breakout from reaching the outer where-clause. A blank restriction appends nothing.
   *
   * <p><b>This is the only place the restriction is ever formatted into a query, and the
   * containment guarantee depends on it staying that way.</b> It always calls
   * {@link #validateFragment(String)} and always wraps the fragment in the subquery. A second
   * composition site that skipped either would silently void the guarantee without any test
   * failing, because every existing test exercises this method.
   */
  private static void appendRestriction(StringBuilder hql, String entityName,
      String restriction) {
    if (StringUtils.isBlank(restriction)) {
      return;
    }
    validateFragment(restriction);
    hql.append(AND).append(ALIAS_OUTER).append(".id in (select ")
        .append(ALIAS_FRAGMENT).append(".id from ").append(entityName).append(' ')
        .append(ALIAS_FRAGMENT).append(" where ( ").append(restriction.trim()).append(" ))");
  }

  /**
   * Rejects a restriction that could not be safely composed. Called when a catalog row is
   * saved, so a bad fragment is a configuration-time error rather than a 02:00 job failure.
   *
   * <p><b>The load-bearing rule is that the fragment never closes more parentheses than it has
   * opened.</b> Overall balance is not enough: {@code 1=1)) or (1=1 and (1=1} balances, yet
   * it would close the fragment's own parentheses <em>and</em> the enclosing {@code in (},
   * putting the {@code or} at the outer level where the tenant and day filters no longer
   * apply. Rejecting any prefix that goes negative is what makes the subquery containment in
   * {@link #composeGroupedCount} hold.
   *
   * <p>The scan is literal-aware: characters inside a single-quoted HQL string are data, not
   * syntax, so {@code e.documentNo = 'A)B'} and {@code e.description like '%--%'} are
   * legitimate and must not be rejected. Inside a literal, {@code ''} is an escaped quote.
   * An unterminated literal is rejected, since a dangling quote is itself a way to change
   * how the rest of the composed query parses.
   *
   * @param restriction the HQL fragment to validate; blank is accepted and means no filter
   * @throws IllegalArgumentException if the fragment is unbalanced, carries a statement
   *     terminator or comment marker outside a literal, or leaves a literal unterminated
   */
  public static void validateFragment(String restriction) {
    if (StringUtils.isBlank(restriction)) {
      return;
    }
    scanFragment(restriction.trim());
  }

  /**
   * Walks the fragment once, tracking literal state and parenthesis depth.
   *
   * @param fragment the trimmed restriction to scan
   * @throws IllegalArgumentException if the fragment is unbalanced, carries a statement
   *     terminator or comment marker outside a literal, or leaves a literal unterminated
   */
  private static void scanFragment(String fragment) {
    int depth = 0;
    boolean inLiteral = false;

    int i = 0;
    while (i < fragment.length()) {
      char c = fragment.charAt(i);

      if (inLiteral) {
        if (c == QUOTE) {
          // A doubled quote is an escaped quote and keeps us inside the literal.
          if (isEscapedQuote(fragment, i)) {
            i++;
          } else {
            inLiteral = false;
          }
        }
      } else if (c == QUOTE) {
        inLiteral = true;
      } else {
        depth = scanOutsideLiteral(fragment, i, depth);
      }
      i++;
    }

    requireClosed(inLiteral, depth);
  }

  /**
   * Rejects a fragment that ends mid-literal or with parentheses left open.
   *
   * @param inLiteral whether the scan ended inside a string literal
   * @param depth parenthesis depth left at the end of the scan
   * @throws IllegalArgumentException when either is left unbalanced
   */
  private static void requireClosed(boolean inLiteral, int depth) {
    if (inLiteral) {
      throw new IllegalArgumentException(
          "HQL restriction has an unterminated string literal (odd number of quotes)");
    }
    if (depth != 0) {
      throw new IllegalArgumentException(
          "HQL restriction has unbalanced parentheses: leaves " + depth + " unclosed");
    }
  }

  /**
   * Tells whether the quote at {@code i} is a doubled (escaped) quote rather than the end of
   * the literal.
   *
   * @param fragment the trimmed restriction being validated
   * @param i index of the quote character
   * @return {@code true} when the next character is also a quote
   */
  private static boolean isEscapedQuote(String fragment, int i) {
    return i + 1 < fragment.length() && fragment.charAt(i + 1) == QUOTE;
  }

  /**
   * Inspects one character that sits outside a string literal, enforcing the structural
   * guardrails: no statement terminator, no comment marker, and never closing more
   * parentheses than were opened.
   *
   * @param fragment the trimmed restriction being validated
   * @param i index of the character to inspect
   * @param depth parenthesis depth before this character
   * @return the parenthesis depth after this character
   * @throws IllegalArgumentException on a statement terminator, a comment marker, or a close
   *     that would escape the subquery the fragment is composed into
   */
  private static int scanOutsideLiteral(String fragment, int i, int depth) {
    char c = fragment.charAt(i);
    if (c == ';') {
      throw new IllegalArgumentException(
          "HQL restriction must not contain a statement terminator (;) outside a literal");
    }
    if (isCommentMarker(fragment, i)) {
      throw new IllegalArgumentException(
          "HQL restriction must not contain a comment marker (-- or /*) outside a literal");
    }
    if (c == '(') {
      return depth + 1;
    }
    if (c == ')') {
      if (depth == 0) {
        throw new IllegalArgumentException("HQL restriction has unbalanced parentheses:"
            + " closes more than it opens, which would escape the subquery it is composed"
            + " into");
      }
      return depth - 1;
    }
    return depth;
  }

  private static boolean isCommentMarker(String fragment, int i) {
    if (i + 1 >= fragment.length()) {
      return false;
    }
    char c = fragment.charAt(i);
    char next = fragment.charAt(i + 1);
    return (c == '-' && next == '-') || (c == '/' && next == '*') || (c == '*' && next == '/');
  }

  private static void requireIdentifier(String value, String what) {
    if (StringUtils.isBlank(value)) {
      throw new IllegalArgumentException(what + " is required for a declarative resource");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') {
        throw new IllegalArgumentException(
            what + " must be a plain HQL identifier, got: " + value);
      }
    }
  }
}
