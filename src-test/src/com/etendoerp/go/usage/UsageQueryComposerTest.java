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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit specs for {@link UsageQueryComposer} (ETP-5050).
 *
 * <p>The class under test is pure string composition over static methods, so these tests need
 * neither a database nor a mock: they run in the plain (non-isolated) test JVM.
 *
 * <p>The centrepiece is <b>containment</b>. The implementer-authored HQL fragment stored on
 * {@code ETGO_BILLING_RESOURCE.HQL_Restriction} is untrusted with respect to tenant attribution,
 * and the composer's defence is structural: the fragment is confined to a subquery, so even a
 * balanced-but-widening fragment cannot reach the outer {@code and}-chain that clamps the day,
 * nor the {@code group by} that attributes the count to a tenant. The assertions below are
 * therefore positional (where the fragment sits relative to the subquery opener) rather than a
 * full-string equality, which would break on any harmless whitespace change while proving less.
 *
 * <p>Note the two distinct fragment shapes that must NOT be conflated:
 * <ul>
 *   <li>{@code e.posted = 'Y' or 1=1} is <em>balanced</em>. It is accepted, and containment is
 *       what makes accepting it safe — it can only widen the subquery's candidate set.</li>
 *   <li>{@code 1=1) or (1=1} is a <em>paren breakout</em>. It must be rejected outright, because
 *       a fragment that leaves the subquery would put an {@code or} at the top level. Asserting
 *       only on the first shape would pass even with the guardrail removed, which is why both
 *       are covered here and kept distinguishable.</li>
 * </ul>
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
class UsageQueryComposerTest {

  private static final String ENTITY = "Invoice";
  private static final String DATE_PROPERTY = "invoiceDate";

  /** Balanced, widening — accepted, and contained. */
  private static final String WIDENING_FRAGMENT = "e.posted = 'Y' or 1=1";
  /** Unbalanced breakout — must never compose. */
  private static final String BREAKOUT_FRAGMENT = "1=1) or (1=1";
  /**
   * A breakout whose parentheses are perfectly BALANCED overall — two closes, two opens — and
   * which must nevertheless be rejected. See {@link BalancedEscapeRejection} for why counting
   * is not enough.
   */
  private static final String BALANCED_ESCAPE_FRAGMENT = "1=1)) or (1=1 and (1=1";

  private static final String SUBQUERY_OPENER = "in (select";

  @Nested
  class Containment {

    @Test
    void groupedCountConfinesAWideningFragmentToTheSubquery() {
      String hql = UsageQueryComposer.composeGroupedCount(ENTITY, DATE_PROPERTY,
          WIDENING_FRAGMENT);

      int subqueryAt = hql.indexOf(SUBQUERY_OPENER);
      int fragmentAt = hql.indexOf(WIDENING_FRAGMENT);

      assertAll(
          () -> assertTrue(subqueryAt >= 0, "fragment must be composed into a subquery: " + hql),
          () -> assertTrue(fragmentAt > subqueryAt,
              "fragment must appear only after the subquery opener: " + hql),
          () -> assertTrue(hql.contains("(select e.id from " + ENTITY + " e where ("),
              "subquery must select ids of the same entity under alias e: " + hql));
    }

    @Test
    void groupedCountStillCarriesBothDayBoundsOutsideTheSubquery() {
      String hql = UsageQueryComposer.composeGroupedCount(ENTITY, DATE_PROPERTY,
          WIDENING_FRAGMENT);

      int startAt = hql.indexOf(':' + UsageQueryComposer.PARAM_DAY_START);
      int endAt = hql.indexOf(':' + UsageQueryComposer.PARAM_DAY_END);
      int subqueryAt = hql.indexOf(SUBQUERY_OPENER);

      assertAll(
          () -> assertTrue(startAt >= 0, "missing :" + UsageQueryComposer.PARAM_DAY_START),
          () -> assertTrue(endAt >= 0, "missing :" + UsageQueryComposer.PARAM_DAY_END),
          () -> assertTrue(startAt < subqueryAt && endAt < subqueryAt,
              "both day bounds must be clamped in the outer where-clause: " + hql));
    }

    @Test
    void groupedCountStillEndsWithTheTenantGrouping() {
      String hql = UsageQueryComposer.composeGroupedCount(ENTITY, DATE_PROPERTY,
          WIDENING_FRAGMENT);

      assertTrue(hql.endsWith("group by outer_.client.id"),
          "tenant attribution must remain the trailing group by: " + hql);
    }

    @Test
    void perTenantCountConfinesAWideningFragmentToTheSubquery() {
      String hql = UsageQueryComposer.composePerTenantCount(ENTITY, DATE_PROPERTY,
          WIDENING_FRAGMENT);

      int subqueryAt = hql.indexOf(SUBQUERY_OPENER);
      int clientAt = hql.indexOf(':' + UsageQueryComposer.PARAM_CLIENT_ID);

      assertAll(
          () -> assertTrue(subqueryAt >= 0, "fragment must be composed into a subquery: " + hql),
          () -> assertTrue(hql.indexOf(WIDENING_FRAGMENT) > subqueryAt,
              "fragment must appear only after the subquery opener: " + hql),
          () -> assertTrue(clientAt >= 0 && clientAt < subqueryAt,
              "the tenant pin must stay in the outer where-clause: " + hql));
    }
  }

  @Nested
  class BreakoutRejection {

    @Test
    void validateFragmentRejectsAParenBreakout() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.validateFragment(BREAKOUT_FRAGMENT));
      assertTrue(thrown.getMessage().contains("unbalanced parentheses"),
          "unexpected message: " + thrown.getMessage());
    }

    @Test
    void groupedCountRejectsAParenBreakout() {
      assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.composeGroupedCount(ENTITY, DATE_PROPERTY,
              BREAKOUT_FRAGMENT));
    }

    @Test
    void perTenantCountRejectsAParenBreakout() {
      assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.composePerTenantCount(ENTITY, DATE_PROPERTY,
              BREAKOUT_FRAGMENT));
    }

    /**
     * The counterpart of the three tests above: a balanced widening fragment is deliberately
     * ACCEPTED. Keeping both shapes asserted is what makes the breakout tests meaningful — the
     * older acceptance wording used {@code or 1=1}, which passes even with the guardrail removed.
     */
    @Test
    void validateFragmentAcceptsABalancedWideningFragment() {
      assertDoesNotThrow(() -> UsageQueryComposer.validateFragment(WIDENING_FRAGMENT));
    }
  }

  /**
   * The subtlest part of the class, and the reason the guardrail is a running-prefix check rather
   * than a final balance check.
   *
   * <p>{@code 1=1)) or (1=1 and (1=1} has TWO closing parentheses and TWO opening ones, so it is
   * balanced: a validator that merely counted, or that only compared the two totals at the end,
   * would accept it. Compose it into the membership test and read what the database sees:
   *
   * <pre>
   *   ... and outer_.id in (select e.id from Invoice e where ( 1=1)) or (1=1 and (1=1 ))
   *                        ^-- opened by the composer      ^-- opened by the composer
   * </pre>
   *
   * The fragment's first {@code )} closes the composer's {@code where (}, and the second closes
   * the enclosing {@code in (}. The {@code or} that follows therefore sits at the OUTER level,
   * a sibling of the day bounds and of the tenant pin, so it defeats both — exactly the
   * mis-attribution the subquery containment exists to prevent. The fragment then re-opens two
   * parentheses of its own so the totals come out even and the statement still parses, which is
   * what makes this shape more dangerous than the plainly unbalanced {@link #BREAKOUT_FRAGMENT}.
   *
   * <p>What catches it is the {@code depth < 0} early rejection: the guardrail is not "the
   * fragment is balanced", it is "the fragment never closes more parentheses than it has opened
   * AT ANY PREFIX". Deleting that early rejection — leaving only the final {@code depth != 0}
   * check — makes all three tests below fail, which is the mutation these tests exist to catch.
   */
  @Nested
  class BalancedEscapeRejection {

    @Test
    void validateFragmentRejectsABalancedEscape() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.validateFragment(BALANCED_ESCAPE_FRAGMENT));
      assertTrue(thrown.getMessage().contains("closes more than it opens"),
          "a balanced escape must be rejected by the running-prefix rule, not by the final"
              + " balance check; unexpected message: " + thrown.getMessage());
    }

    @Test
    void groupedCountRejectsABalancedEscape() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.composeGroupedCount(ENTITY, DATE_PROPERTY,
              BALANCED_ESCAPE_FRAGMENT));
      assertTrue(thrown.getMessage().contains("closes more than it opens"),
          "unexpected message: " + thrown.getMessage());
    }

    @Test
    void perTenantCountRejectsABalancedEscape() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.composePerTenantCount(ENTITY, DATE_PROPERTY,
              BALANCED_ESCAPE_FRAGMENT));
      assertTrue(thrown.getMessage().contains("closes more than it opens"),
          "unexpected message: " + thrown.getMessage());
    }

    /**
     * Guards the counting reading of the rule from the other side: the same character counts in
     * an order that never goes negative are legitimate and must still be accepted, so the fix
     * cannot be "reject anything with two closes".
     */
    @Test
    void theSameParenCountsInASafeOrderAreStillAccepted() {
      assertDoesNotThrow(
          () -> UsageQueryComposer.validateFragment("(1=1) and (1=1 or 1=1)"));
    }
  }

  /**
   * Characters inside a single-quoted HQL string are DATA, not syntax. A fragment such as
   * {@code e.documentNo = 'A)B'} is an ordinary business restriction — the {@code )} is part of a
   * document number, it can never affect how the composed statement parses — so rejecting it
   * would block legitimate configuration without buying any safety. The validator therefore
   * tracks literal state and applies the paren, terminator and comment checks only outside one.
   */
  @Nested
  class LiteralsAreData {

    @ParameterizedTest
    @ValueSource(strings = {
        "e.documentNo = 'A)B'",
        "e.description like '%--%'",
        "e.name = 'a;b'",
        "e.name = 'a/*b*/c'",
        "e.name = 'O''Brien'",
        "e.name = 'O''B)' and (e.a=1)"
    })
    void syntaxCharactersInsideALiteralAreAccepted(String restriction) {
      assertDoesNotThrow(() -> UsageQueryComposer.validateFragment(restriction));
    }

    @Test
    void aLiteralWithAParenComposesIntoTheSubqueryUnchanged() {
      String fragment = "e.documentNo = 'A)B'";
      String hql = UsageQueryComposer.composeGroupedCount(ENTITY, DATE_PROPERTY, fragment);

      assertTrue(hql.indexOf(fragment) > hql.indexOf(SUBQUERY_OPENER),
          "an accepted literal fragment must still be confined to the subquery: " + hql);
    }

    @Test
    void anUnterminatedLiteralIsRejected() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.validateFragment("e.name = 'abc"));
      assertTrue(thrown.getMessage().contains("unterminated string literal"),
          "unexpected message: " + thrown.getMessage());
    }

    /**
     * Literal-awareness must not become a way to smuggle syntax past the guardrail: here the
     * literal {@code 'x'} is closed, so the {@code )} that follows it is real syntax at depth 0
     * and the fragment is a genuine escape.
     */
    @Test
    void aClosedLiteralDoesNotMaskARealEscape() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.validateFragment("e.name = 'x' ) or (1=1"));
      assertTrue(thrown.getMessage().contains("closes more than it opens"),
          "unexpected message: " + thrown.getMessage());
    }

    @Test
    void anEscapedQuoteDoesNotReopenTheLiteralForTheRestOfTheFragment() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.validateFragment("e.name = 'O''Brien' ; delete from Invoice"));
      assertTrue(thrown.getMessage().contains("statement terminator"),
          "an escaped quote must leave the literal closed at its real end: "
              + thrown.getMessage());
    }
  }

  @Nested
  class ForbiddenTokens {

    @Test
    void statementTerminatorIsRejectedWithItsOwnMessage() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.validateFragment("e.posted = 'Y'; delete from Invoice"));
      assertAll(
          () -> assertTrue(thrown.getMessage().contains("statement terminator"),
              "unexpected message: " + thrown.getMessage()),
          () -> assertFalse(thrown.getMessage().contains("comment marker"),
              "terminator and comment marker must be distinguishable"));
    }

    @Test
    void lineCommentMarkerIsRejectedWithItsOwnMessage() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.validateFragment("e.posted = 'Y' -- ignore the rest"));
      assertTrue(thrown.getMessage().contains("comment marker"),
          "unexpected message: " + thrown.getMessage());
    }

    @Test
    void blockCommentMarkerIsRejectedWithItsOwnMessage() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.validateFragment("e.posted = 'Y' /* ignore the rest"));
      assertTrue(thrown.getMessage().contains("comment marker"),
          "unexpected message: " + thrown.getMessage());
    }

    /**
     * The closing half of a block comment is a marker in its own right: a fragment that starts
     * mid-comment would otherwise let the text preceding it in the composed statement be
     * commented out.
     */
    @Test
    void blockCommentTerminatorOutsideALiteralIsRejected() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.validateFragment("e.posted = 'Y' */ or 1=1"));
      assertTrue(thrown.getMessage().contains("comment marker"),
          "unexpected message: " + thrown.getMessage());
    }
  }

  @Nested
  class Balance {

    @Test
    void unclosedParenthesisIsRejected() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.validateFragment("((e.a=1)"));
      assertTrue(thrown.getMessage().contains("unclosed"),
          "unexpected message: " + thrown.getMessage());
    }

    @Test
    void extraClosingParenthesisIsRejected() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.validateFragment("e.a=1)"));
      assertTrue(thrown.getMessage().contains("closes more than it opens"),
          "unexpected message: " + thrown.getMessage());
    }

    @Test
    void balancedNestedParenthesesAreAccepted() {
      assertDoesNotThrow(() -> UsageQueryComposer.validateFragment("((e.a=1) and (e.b=2))"));
    }
  }

  @Nested
  class BlankRestriction {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void blankRestrictionValidatesSilently(String restriction) {
      assertDoesNotThrow(() -> UsageQueryComposer.validateFragment(restriction));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void groupedCountComposesWithoutAnySubqueryClause(String restriction) {
      String hql = UsageQueryComposer.composeGroupedCount(ENTITY, DATE_PROPERTY, restriction);

      assertAll(
          () -> assertFalse(hql.contains(SUBQUERY_OPENER),
              "a blank restriction must add no membership test: " + hql),
          () -> assertTrue(hql.contains(':' + UsageQueryComposer.PARAM_DAY_START), hql),
          () -> assertTrue(hql.contains(':' + UsageQueryComposer.PARAM_DAY_END), hql),
          () -> assertTrue(hql.endsWith("group by outer_.client.id"), hql));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void perTenantCountComposesWithoutAnySubqueryClause(String restriction) {
      String hql = UsageQueryComposer.composePerTenantCount(ENTITY, DATE_PROPERTY, restriction);

      assertAll(
          () -> assertFalse(hql.contains(SUBQUERY_OPENER),
              "a blank restriction must add no membership test: " + hql),
          () -> assertTrue(hql.contains(':' + UsageQueryComposer.PARAM_CLIENT_ID), hql));
    }
  }

  @Nested
  class PerTenantForm {

    @Test
    void pinsTheTenantWithANamedParameter() {
      String hql = UsageQueryComposer.composePerTenantCount(ENTITY, DATE_PROPERTY,
          WIDENING_FRAGMENT);

      assertAll(
          () -> assertTrue(
              hql.contains("outer_.client.id = :" + UsageQueryComposer.PARAM_CLIENT_ID),
              "tenant must be bound, never interpolated: " + hql),
          () -> assertTrue(hql.startsWith("select count(*)"), hql));
    }

    @Test
    void hasNoGroupByBecauseTheTenantIsAlreadyPinned() {
      String hql = UsageQueryComposer.composePerTenantCount(ENTITY, DATE_PROPERTY,
          WIDENING_FRAGMENT);

      assertFalse(hql.contains("group by"), "per-tenant form must not group: " + hql);
    }

    @Test
    void carriesBothDayBoundsAsNamedParameters() {
      String hql = UsageQueryComposer.composePerTenantCount(ENTITY, DATE_PROPERTY, null);

      assertAll(
          () -> assertTrue(
              hql.contains("outer_." + DATE_PROPERTY + " >= :"
                  + UsageQueryComposer.PARAM_DAY_START), hql),
          () -> assertTrue(
              hql.contains("outer_." + DATE_PROPERTY + " < :"
                  + UsageQueryComposer.PARAM_DAY_END), hql));
    }
  }

  @Nested
  class IdentifierValidation {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void blankEntityNameIsRejected(String entityName) {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.composeGroupedCount(entityName, DATE_PROPERTY, null));
      assertTrue(thrown.getMessage().contains("entityName"),
          "unexpected message: " + thrown.getMessage());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void blankDatePropertyIsRejected(String dateProperty) {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.composeGroupedCount(ENTITY, dateProperty, null));
      assertTrue(thrown.getMessage().contains("dateProperty"),
          "unexpected message: " + thrown.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Invoice outer_",
        "Invoice'",
        "Invoice(1)",
        "Invoice where 1=1",
        "Invoice;"
    })
    void anEntityNameThatIsNotAPlainIdentifierIsRejected(String entityName) {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.composeGroupedCount(entityName, DATE_PROPERTY, null));
      assertTrue(thrown.getMessage().contains("plain HQL identifier"),
          "unexpected message: " + thrown.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = { "invoiceDate desc", "invoice-Date", "invoiceDate)" })
    void aDatePropertyThatIsNotAPlainIdentifierIsRejected(String dateProperty) {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageQueryComposer.composePerTenantCount(ENTITY, dateProperty, null));
      assertTrue(thrown.getMessage().contains("plain HQL identifier"),
          "unexpected message: " + thrown.getMessage());
    }

    @Test
    void aDottedPathIsAValidDateProperty() {
      String hql = assertDoesNotThrow(
          () -> UsageQueryComposer.composeGroupedCount(ENTITY, "order.orderDate", null));
      assertTrue(hql.contains("outer_.order.orderDate >= :"
          + UsageQueryComposer.PARAM_DAY_START), hql);
    }
  }
}
