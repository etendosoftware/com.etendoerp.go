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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SelectorQueryBuilder} search-filter construction.
 *
 * Focused on the blank-property / custom-HQL selector support: when the searchable
 * fragment already carries its own alias (e.g. {@code bp.name} from
 * {@code clause_left_part}), it must not be double-prefixed.
 */
class SelectorQueryBuilderTest {

  private static final String ALIAS = "bp";

  // --------------------------------------------------------------------
  // resolveSearchableExpression
  // --------------------------------------------------------------------

  /** Bare property: alias is prefixed. */
  @Test
  @DisplayName("resolveSearchableExpression prefixes alias for bare property")
  void testResolveExprBareProperty() {
    assertEquals("e.name",
        SelectorOrgFilter.resolveSearchableExpression("e", "name"));
  }

  /** Dotted fragment from clause_left_part: used as-is (already aliased). */
  @Test
  @DisplayName("resolveSearchableExpression leaves dotted fragment untouched")
  void testResolveExprDottedFragment() {
    assertEquals("bp.name",
        SelectorOrgFilter.resolveSearchableExpression("e", "bp.name"));
    assertEquals("bp.searchKey",
        SelectorOrgFilter.resolveSearchableExpression("bp", "bp.searchKey"));
    assertEquals("contact.businessPartner.name",
        SelectorOrgFilter.resolveSearchableExpression("e", "contact.businessPartner.name"));
  }

  /** Blank fragment is returned as-is (caller is responsible for filtering). */
  @Test
  @DisplayName("resolveSearchableExpression passes blank fragment through")
  void testResolveExprBlank() {
    assertEquals("", SelectorOrgFilter.resolveSearchableExpression("e", ""));
  }

  // --------------------------------------------------------------------
  // appendCustomSearchFilter
  // --------------------------------------------------------------------

  /** Standard selector (bare properties) — alias is prefixed. */
  @Test
  @DisplayName("appendCustomSearchFilter prefixes alias for bare properties")
  void testAppendFilterBareProperty() {
    StringBuilder hql = new StringBuilder();
    SelectorOrgFilter.appendCustomSearchFilter(
        hql, Collections.singletonList("name"), "e", "mi", false);
    String result = hql.toString();
    assertTrue(result.contains("cast(e.name as string)"),
        "Should prefix bare property with alias: " + result);
    assertFalse(result.contains("cast(e.e."),
        "Should NOT double-prefix: " + result);
  }

  /** Custom-HQL selector (clause_left_part values like 'bp.name') — no double prefix. */
  @Test
  @DisplayName("appendCustomSearchFilter does not double-prefix dotted fragments")
  void testAppendFilterDottedFragment() {
    StringBuilder hql = new StringBuilder();
    SelectorOrgFilter.appendCustomSearchFilter(
        hql, Arrays.asList("bp.name", "bp.searchKey"), ALIAS, "mi", false);
    String result = hql.toString();

    // The emitted HQL uses the fragment verbatim
    assertTrue(result.contains("cast(bp.name as string)"), "missing bp.name: " + result);
    assertTrue(result.contains("cast(bp.searchKey as string)"),
        "missing bp.searchKey: " + result);

    // No double prefix like bp.bp.name
    assertFalse(result.contains("bp.bp."), "double-prefix detected: " + result);

    // Uses OR between terms and a WHERE prefix when hasWhere=false
    assertTrue(result.startsWith(" WHERE ("),
        "should start with WHERE when no prior clause: " + result);
    assertTrue(result.contains(" OR "), "multiple terms should be OR'd: " + result);
    assertTrue(result.endsWith(")"), "should be parenthesized: " + result);
  }

  /** Mixed: one bare + one dotted — each handled independently. */
  @Test
  @DisplayName("appendCustomSearchFilter handles mixed bare + dotted fragments")
  void testAppendFilterMixedFragments() {
    StringBuilder hql = new StringBuilder();
    SelectorOrgFilter.appendCustomSearchFilter(
        hql, Arrays.asList("name", "bp.searchKey"), "e", "mi", false);
    String result = hql.toString();
    assertTrue(result.contains("cast(e.name as string)"), "bare prop missing alias: " + result);
    assertTrue(result.contains("cast(bp.searchKey as string)"),
        "dotted fragment should stay as-is: " + result);
  }

  /** No-op when search is blank. */
  @Test
  @DisplayName("appendCustomSearchFilter no-op when search is blank")
  void testAppendFilterBlankSearch() {
    StringBuilder hql = new StringBuilder();
    SelectorOrgFilter.appendCustomSearchFilter(
        hql, Arrays.asList("bp.name"), ALIAS, "", false);
    assertEquals("", hql.toString());
  }

  /** No-op when searchable list is empty. */
  @Test
  @DisplayName("appendCustomSearchFilter no-op when searchable list is empty")
  void testAppendFilterEmptyList() {
    StringBuilder hql = new StringBuilder();
    SelectorOrgFilter.appendCustomSearchFilter(
        hql, Collections.emptyList(), ALIAS, "mi", false);
    assertEquals("", hql.toString());
  }

  /** hasWhere=true uses AND instead of WHERE. */
  @Test
  @DisplayName("appendCustomSearchFilter uses AND when hasWhere=true")
  void testAppendFilterAndPrefix() {
    StringBuilder hql = new StringBuilder("FROM Foo f WHERE 1=1");
    SelectorOrgFilter.appendCustomSearchFilter(
        hql, Collections.singletonList("bp.name"), ALIAS, "mi", true);
    String result = hql.toString();
    assertTrue(result.contains(" AND ("), "should append with AND: " + result);
    assertFalse(result.contains(" WHERE (lower"),
        "should not introduce a second WHERE: " + result);
  }

  // --------------------------------------------------------------------
  // simplifyConstantCaseExpressions
  // --------------------------------------------------------------------

  /**
   * When both literals match ('N'='N'), the CASE is replaced by a direct equality.
   * This covers the vendor payment-method filter after @FIN_ISRECEIPT@ substitution.
   */
  @Test
  @DisplayName("simplifyConstantCaseExpressions rewrites always-true CASE to direct equality")
  void testSimplifyAlwaysTrueCase() {
    String input = "EXISTS (SELECT 1 FROM FinancialMgmtFinAccPaymentMethod fapm "
        + "WHERE e.id=fapm.paymentMethod.id AND fapm.active='Y' "
        + "AND fapm.payoutAllow = (CASE WHEN 'N'='N' THEN 'Y' ELSE fapm.payoutAllow END))";

    String result = SqlToHqlTranslator.simplifyConstantCaseExpressions(input);

    assertTrue(result.contains("fapm.payoutAllow = 'Y'"),
        "Always-true CASE should collapse to direct equality: " + result);
    assertFalse(result.contains("CASE WHEN"),
        "No CASE WHEN should remain: " + result);
  }

  /**
   * When literals differ ('N'='Y') and LHS == ELSE_EXPR, the CASE is a tautology
   * (x = x is always true) — the AND clause should be dropped entirely.
   */
  @Test
  @DisplayName("simplifyConstantCaseExpressions drops always-false CASE when LHS=ELSE (tautology)")
  void testSimplifyAlwaysFalseCaseTautology() {
    String input = "EXISTS (SELECT 1 FROM FinancialMgmtFinAccPaymentMethod fapm "
        + "WHERE e.id=fapm.paymentMethod.id AND fapm.active='Y' "
        + "AND fapm.payinAllow = (CASE WHEN 'N'='Y' THEN 'Y' ELSE fapm.payinAllow END))";

    String result = SqlToHqlTranslator.simplifyConstantCaseExpressions(input);

    assertFalse(result.contains("CASE WHEN"),
        "No CASE WHEN should remain: " + result);
    assertFalse(result.contains("AND 1=1"),
        "AND 1=1 tautology should be stripped: " + result);
  }

  /**
   * When literals differ ('A'='B') and LHS != ELSE_EXPR, the CASE evaluates to ELSE.
   * The replacement must be a real comparison 'LHS = ELSE_EXPR', not a dropped tautology.
   */
  @Test
  @DisplayName("simplifyConstantCaseExpressions emits real comparison when always-false CASE has different LHS and ELSE")
  void testSimplifyAlwaysFalseCaseDifferentElse() {
    String input = "WHERE e.org = (CASE WHEN 'A'='B' THEN 'Y' ELSE e.client END)";

    String result = SqlToHqlTranslator.simplifyConstantCaseExpressions(input);

    assertFalse(result.contains("CASE WHEN"),
        "No CASE WHEN should remain: " + result);
    assertTrue(result.contains("e.org = e.client"),
        "Real comparison must be emitted when LHS != ELSE_EXPR: " + result);
    assertFalse(result.contains("1=1"),
        "Must not collapse to tautology when LHS and ELSE differ: " + result);
  }

  /**
   * Customer payment-method filter: @FIN_ISRECEIPT@='Y' → always-true CASE on payinAllow.
   */
  @Test
  @DisplayName("simplifyConstantCaseExpressions handles payinAllow with FIN_ISRECEIPT=Y")
  void testSimplifyCustomerPaymentMethod() {
    String input = "EXISTS (SELECT 1 FROM FinancialMgmtFinAccPaymentMethod fapm "
        + "WHERE e.id=fapm.paymentMethod.id AND fapm.active='Y' "
        + "AND fapm.payinAllow = (CASE WHEN 'Y'='Y' THEN 'Y' ELSE fapm.payinAllow END))";

    String result = SqlToHqlTranslator.simplifyConstantCaseExpressions(input);

    assertTrue(result.contains("fapm.payinAllow = 'Y'"),
        "Customer always-true CASE should collapse to direct equality: " + result);
    assertFalse(result.contains("CASE WHEN"), "No CASE WHEN should remain: " + result);
  }

  /** HQL without CASE WHEN is returned unchanged. */
  @Test
  @DisplayName("simplifyConstantCaseExpressions returns HQL unchanged when no CASE WHEN present")
  void testSimplifyNoCaseWhen() {
    String input = "EXISTS (SELECT 1 FROM FinancialMgmtFinAccPaymentMethod fapm "
        + "WHERE e.id=fapm.paymentMethod.id AND fapm.active='Y')";

    String result = SqlToHqlTranslator.simplifyConstantCaseExpressions(input);

    assertEquals(input, result, "HQL without CASE WHEN should be returned unchanged");
  }

  /** Null input is returned as null without throwing. */
  @Test
  @DisplayName("simplifyConstantCaseExpressions handles null input gracefully")
  void testSimplifyNull() {
    assertEquals(null, SqlToHqlTranslator.simplifyConstantCaseExpressions(null));
  }

  // --------------------------------------------------------------------
  // unwrapSelectFromDual — SELECT FROM DUAL tempered-greedy fix
  // --------------------------------------------------------------------

  /**
   * The FIN_PaymentMethodsWithAccountIsReceiptControl rule embeds two
   * (SELECT … FROM DUAL) subexpressions inside an outer EXISTS (SELECT … FROM …).
   * The tempered-greedy pattern must unwrap only the inner FROM DUAL clauses
   * without consuming the outer EXISTS subquery.
   */
  @Test
  @DisplayName("unwrapSelectFromDual removes nested FROM DUAL clauses without consuming outer EXISTS")
  void testSelectFromDualNestedInExists() {
    String sql = "EXISTS (SELECT 1 FROM FIN_FinAcc_PaymentMethod fapm "
        + "WHERE FIN_PaymentMethod.FIN_PaymentMethod_ID=fapm.FIN_PaymentMethod_ID "
        + "AND fapm.isActive='Y' "
        + "AND fapm.Payin_Allow = (SELECT CASE WHEN '@FIN_ISRECEIPT@'='Y' THEN 'Y' ELSE fapm.Payin_Allow END FROM DUAL) "
        + "AND fapm.Payout_Allow = (SELECT CASE WHEN '@FIN_ISRECEIPT@'='N' THEN 'Y' ELSE fapm.Payout_Allow END FROM DUAL))";

    String result = SqlToHqlTranslator.unwrapSelectFromDual(sql);

    assertTrue(result.contains("EXISTS (SELECT 1"),
        "Outer EXISTS SELECT must be preserved: " + result);
    assertFalse(result.contains("FROM DUAL"),
        "All FROM DUAL clauses must be unwrapped: " + result);
    assertTrue(result.contains("CASE WHEN '@FIN_ISRECEIPT@'='Y' THEN 'Y' ELSE fapm.Payin_Allow END"),
        "Inner CASE expression must be preserved: " + result);
  }

  /**
   * Same nested EXISTS scenario but the inner subqueries are formatted with whitespace
   * between "(" and "SELECT" (e.g. "(\n  SELECT … FROM DUAL )"). The negative lookahead
   * must tolerate that whitespace; otherwise the tempered token crosses the inner
   * boundary and consumes the outer EXISTS, producing invalid HQL like
   * "EXISTS (1 FROM …".
   */
  @Test
  @DisplayName("unwrapSelectFromDual tolerates whitespace between '(' and 'SELECT' in nested subqueries")
  void testSelectFromDualNestedInExistsWithWhitespace() {
    String sql = "EXISTS (SELECT 1 FROM FIN_FinAcc_PaymentMethod fapm "
        + "WHERE FIN_PaymentMethod.FIN_PaymentMethod_ID=fapm.FIN_PaymentMethod_ID "
        + "AND fapm.isActive='Y' "
        + "AND fapm.Payin_Allow = (\n  SELECT CASE WHEN '@FIN_ISRECEIPT@'='Y' THEN 'Y' ELSE fapm.Payin_Allow END FROM DUAL\n) "
        + "AND fapm.Payout_Allow = ( SELECT CASE WHEN '@FIN_ISRECEIPT@'='N' THEN 'Y' ELSE fapm.Payout_Allow END FROM DUAL ))";

    String result = SqlToHqlTranslator.unwrapSelectFromDual(sql);

    assertTrue(result.contains("EXISTS (SELECT 1"),
        "Outer EXISTS SELECT must be preserved when inner subqueries have whitespace after '(': " + result);
    assertFalse(result.contains("FROM DUAL"),
        "All FROM DUAL clauses must be unwrapped: " + result);
    assertTrue(result.contains("CASE WHEN '@FIN_ISRECEIPT@'='Y' THEN 'Y' ELSE fapm.Payin_Allow END"),
        "Inner CASE expression (with leading whitespace before SELECT) must be preserved: " + result);
    assertTrue(result.contains("CASE WHEN '@FIN_ISRECEIPT@'='N' THEN 'Y' ELSE fapm.Payout_Allow END"),
        "Second inner CASE expression must be preserved: " + result);
  }

  /**
   * A simple (SELECT expr FROM DUAL) is unwrapped to (expr).
   */
  @Test
  @DisplayName("unwrapSelectFromDual unwraps simple top-level SELECT FROM DUAL")
  void testSelectFromDualSimple() {
    String result = SqlToHqlTranslator.unwrapSelectFromDual("(SELECT 'Y' FROM DUAL)");

    assertFalse(result.contains("FROM DUAL"), "FROM DUAL must be removed: " + result);
    assertTrue(result.contains("'Y'"), "Expression must be preserved: " + result);
  }

  /** Null input is returned as null without throwing. */
  @Test
  @DisplayName("unwrapSelectFromDual handles null input gracefully")
  void testSelectFromDualNull() {
    assertEquals(null, SqlToHqlTranslator.unwrapSelectFromDual(null));
  }

  /** SQL without FROM DUAL is returned unchanged. */
  @Test
  @DisplayName("unwrapSelectFromDual returns SQL unchanged when no FROM DUAL present")
  void testSelectFromDualNoOp() {
    String sql = "EXISTS (SELECT 1 FROM FIN_FinAcc_PaymentMethod fapm WHERE fapm.active='Y')";
    assertEquals(sql, SqlToHqlTranslator.unwrapSelectFromDual(sql));
  }
}
