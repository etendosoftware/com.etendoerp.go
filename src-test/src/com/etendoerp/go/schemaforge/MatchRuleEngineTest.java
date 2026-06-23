/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link MatchRuleEngine}. All tests are pure (no DB, no mocks) — they exercise
 * the text-matching and evaluation logic directly.
 */
public class MatchRuleEngineTest {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static MatchRuleEngine.Rule rule(String id, String condition, String pattern) {
    return rule(id, condition, pattern, 10);
  }

  private static MatchRuleEngine.Rule rule(String id, String condition, String pattern,
      int priority) {
    return new MatchRuleEngine.Rule(id, "Rule " + id, priority, condition, pattern,
        new MatchRuleEngine.RuleOptions("GL-001", "BP-001", null, null, null, null), 0L);
  }

  // ---------------------------------------------------------------------------
  // buildSearchText
  // ---------------------------------------------------------------------------

  /** An all-blank input builds an empty search text. */
  @Test
  public void testBuildSearchTextAllBlankReturnsEmpty() {
    assertEquals("", MatchRuleEngine.buildSearchText("", "", ""));
  }

  /** A description-only input is trimmed and lower-cased. */
  @Test
  public void testBuildSearchTextDescOnlyReturnsTrimmedLower() {
    assertEquals("commission fee", MatchRuleEngine.buildSearchText("COMMISSION FEE", "", ""));
  }

  /** All three fields concatenate with single spaces and lower-case. */
  @Test
  public void testBuildSearchTextAllFieldsConcatenatedWithSpaces() {
    String result = MatchRuleEngine.buildSearchText("COMMISSION FEE", "REF-001", "ACME");
    assertEquals("commission fee ref-001 acme", result);
  }

  // ---------------------------------------------------------------------------
  // matches — Contains
  // ---------------------------------------------------------------------------

  /** A Contains rule matches text that includes the pattern. */
  @Test
  public void testMatchesContainsHit() {
    MatchRuleEngine.Rule r = rule("1", MatchRuleEngine.COND_CONTAINS, "commission");
    assertTrue(MatchRuleEngine.matches(r, "bank commission fee may"));
  }

  /** A Contains rule does not match text without the pattern. */
  @Test
  public void testMatchesContainsMiss() {
    MatchRuleEngine.Rule r = rule("1", MatchRuleEngine.COND_CONTAINS, "commission");
    assertFalse(MatchRuleEngine.matches(r, "bank transfer fee may"));
  }

  /** A Contains rule is case-insensitive. */
  @Test
  public void testMatchesContainsCaseInsensitive() {
    MatchRuleEngine.Rule r = rule("1", MatchRuleEngine.COND_CONTAINS, "COMMISSION");
    assertTrue(MatchRuleEngine.matches(r, "bank commission fee"));
  }

  // ---------------------------------------------------------------------------
  // matches — Starts with
  // ---------------------------------------------------------------------------

  /** A Starts-with rule matches text that begins with the pattern. */
  @Test
  public void testMatchesStartsHit() {
    MatchRuleEngine.Rule r = rule("2", MatchRuleEngine.COND_STARTS, "bank fee");
    assertTrue(MatchRuleEngine.matches(r, "bank fee may 2026"));
  }

  /** A Starts-with rule does not match when the pattern is mid-string. */
  @Test
  public void testMatchesStartsMiss() {
    MatchRuleEngine.Rule r = rule("2", MatchRuleEngine.COND_STARTS, "bank fee");
    assertFalse(MatchRuleEngine.matches(r, "monthly bank fee"));
  }

  // ---------------------------------------------------------------------------
  // matches — Regex
  // ---------------------------------------------------------------------------

  /** A Regex rule matches text that satisfies the anchored pattern. */
  @Test
  public void testMatchesRegexHit() {
    MatchRuleEngine.Rule r = rule("3", MatchRuleEngine.COND_REGEX, "^commission.*fee$");
    assertTrue(MatchRuleEngine.matches(r, "commission maintenance fee"));
  }

  /** A Regex rule does not match text that fails the anchored pattern. */
  @Test
  public void testMatchesRegexMiss() {
    MatchRuleEngine.Rule r = rule("3", MatchRuleEngine.COND_REGEX, "^commission.*fee$");
    assertFalse(MatchRuleEngine.matches(r, "bank transfer 2026"));
  }

  /**
   * A catastrophic-backtracking pattern (end-anchored nested quantifier against a trailing
   * non-matching char) must time out under the 200ms cap and return false.
   */
  @Test
  public void testMatchesRegexCatastrophicBacktrackingReturnsFalse() {
    // The end anchor plus the trailing '!' force the engine to explore every
    // partition while searching, so the 200ms guard times out and the result is false.
    MatchRuleEngine.Rule r = rule("4", MatchRuleEngine.COND_REGEX, "(a+)+$");
    assertFalse(MatchRuleEngine.matches(r,
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!"));
  }

  /** An invalid regex pattern returns false (never throws). */
  @Test
  public void testMatchesRegexInvalidPatternReturnsFalse() {
    MatchRuleEngine.Rule r = rule("5", MatchRuleEngine.COND_REGEX, "[invalid(");
    assertFalse(MatchRuleEngine.matches(r, "anything"));
  }

  // ---------------------------------------------------------------------------
  // matches — edge cases
  // ---------------------------------------------------------------------------

  /** Blank text never matches. */
  @Test
  public void testMatchesBlankTextReturnsFalse() {
    MatchRuleEngine.Rule r = rule("6", MatchRuleEngine.COND_CONTAINS, "fee");
    assertFalse(MatchRuleEngine.matches(r, ""));
  }

  /** A blank pattern never matches. */
  @Test
  public void testMatchesBlankPatternReturnsFalse() {
    MatchRuleEngine.Rule r = rule("7", MatchRuleEngine.COND_CONTAINS, "");
    assertFalse(MatchRuleEngine.matches(r, "bank fee"));
  }

  /** An unknown text condition never matches. */
  @Test
  public void testMatchesUnknownConditionReturnsFalse() {
    MatchRuleEngine.Rule r = rule("8", "X", "fee");
    assertFalse(MatchRuleEngine.matches(r, "bank fee"));
  }

  // ---------------------------------------------------------------------------
  // evaluate — priority ordering
  // ---------------------------------------------------------------------------

  /** Evaluating against an empty rule set yields no match. */
  @Test
  public void testEvaluateNoRulesNoMatch() {
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate(
        "commission fee", "", "", Collections.emptyList());
    assertFalse(result.isMatched());
    assertNull(result.primary);
    assertTrue(result.alternatives.isEmpty());
  }

  /** A single matching rule becomes the primary with no alternatives. */
  @Test
  public void testEvaluateSingleMatchPrimarySet() {
    List<MatchRuleEngine.Rule> rules = Arrays.asList(
        rule("R1", MatchRuleEngine.COND_CONTAINS, "commission"),
        rule("R2", MatchRuleEngine.COND_CONTAINS, "transfer"));
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("commission fee", "", "", rules);
    assertTrue(result.isMatched());
    assertEquals("R1", result.primary.id);
    assertTrue(result.alternatives.isEmpty());
  }

  /** When several rules match, the lowest priority value wins and the rest are alternatives. */
  @Test
  public void testEvaluateMultipleMatchesLowestPriorityWinsAlternativesListed() {
    // Both rules match; R1 has lower priority value (= higher priority) → primary.
    List<MatchRuleEngine.Rule> rules = Arrays.asList(
        rule("R1", MatchRuleEngine.COND_CONTAINS, "fee", 10),
        rule("R2", MatchRuleEngine.COND_CONTAINS, "bank", 20));
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("bank fee", "", "", rules);
    assertTrue(result.isMatched());
    assertEquals("R1", result.primary.id);
    assertEquals(1, result.alternatives.size());
    assertEquals("R2", result.alternatives.get(0).id);
  }

  /** An inactive rule (simulated by exclusion from the list) yields no match. */
  @Test
  public void testEvaluateInactiveRuleNotEvaluatedNoMatch() {
    // An inactive rule should not even be loaded; we simulate by not including it.
    List<MatchRuleEngine.Rule> rules = Collections.emptyList();
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("commission fee", "", "", rules);
    assertFalse(result.isMatched());
  }

  /** The description field is used in the search text. */
  @Test
  public void testEvaluateDescriptionUsed() {
    List<MatchRuleEngine.Rule> rules = Arrays.asList(
        rule("R1", MatchRuleEngine.COND_CONTAINS, "impuestos"));
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("PAGO IMPUESTOS MAY", "", "", rules);
    assertTrue(result.isMatched());
    assertEquals("R1", result.primary.id);
  }

  /** The reference field is used in the search text. */
  @Test
  public void testEvaluateReferenceUsed() {
    List<MatchRuleEngine.Rule> rules = Arrays.asList(
        rule("R1", MatchRuleEngine.COND_CONTAINS, "ref-999"));
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("", "REF-999", "", rules);
    assertTrue(result.isMatched());
  }

  /** The partner name field is used in the search text. */
  @Test
  public void testEvaluatePartnerNameUsed() {
    List<MatchRuleEngine.Rule> rules = Arrays.asList(
        rule("R1", MatchRuleEngine.COND_CONTAINS, "acme"));
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("", "", "ACME CORP", rules);
    assertTrue(result.isMatched());
  }

  /** When no rule matches, the result reports no match and a null primary. */
  @Test
  public void testEvaluateNoMatchReturnsNoMatchResult() {
    List<MatchRuleEngine.Rule> rules = Arrays.asList(
        rule("R1", MatchRuleEngine.COND_CONTAINS, "commission"));
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("bank transfer", "", "", rules);
    assertFalse(result.isMatched());
    assertNull(result.primary);
  }

  /** The alternatives list returned by evaluate is unmodifiable. */
  @Test
  public void testEvaluateAlternativesListIsUnmodifiable() {
    List<MatchRuleEngine.Rule> rules = Arrays.asList(
        rule("R1", MatchRuleEngine.COND_CONTAINS, "fee", 10),
        rule("R2", MatchRuleEngine.COND_CONTAINS, "bank", 20));
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("bank fee", "", "", rules);
    assertNotNull(result.alternatives);
    // Verify the list is unmodifiable.
    try {
      result.alternatives.add(rule("R3", MatchRuleEngine.COND_CONTAINS, "x"));
      assertFalse("Expected UnsupportedOperationException", true);
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  // ---------------------------------------------------------------------------
  // matchesRegex — direct calls (timeout, syntax-error, plain hit/miss)
  // ---------------------------------------------------------------------------

  /** A valid regex that matches the text returns true through the executor path. */
  @Test
  public void testMatchesRegexDirectHit() {
    assertTrue(MatchRuleEngine.matchesRegex("comm.*fee", "commission fee"));
  }

  /** A valid regex that does not match the text returns false. */
  @Test
  public void testMatchesRegexDirectMiss() {
    assertFalse(MatchRuleEngine.matchesRegex("^xyz$", "commission fee"));
  }

  /** An invalid pattern raises a PatternSyntaxException inside the worker and returns false. */
  @Test
  public void testMatchesRegexDirectInvalidPatternReturnsFalse() {
    assertFalse(MatchRuleEngine.matchesRegex("[unterminated(", "anything"));
  }

  /**
   * A catastrophic-backtracking pattern evaluated directly must trip the 200ms timeout guard and
   * return false instead of hanging.
   */
  @Test
  public void testMatchesRegexDirectCatastrophicTimeoutReturnsFalse() {
    assertFalse(MatchRuleEngine.matchesRegex("(a+)+$",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!"));
  }

  // ---------------------------------------------------------------------------
  // loadRules — JDBC ResultSet mapping
  // ---------------------------------------------------------------------------

  /**
   * loadRules maps each ResultSet row into a Rule, trims text fields, binds the account id, and
   * preserves the DB fetch order.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testLoadRulesMapsRowsAndBindsAccount() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString("etgo_match_rule_id")).thenReturn("R1", "R2");
    when(rs.getString("name")).thenReturn("  Fee Rule  ", "Transfer Rule");
    when(rs.getInt("priority")).thenReturn(10, 20);
    when(rs.getString("textcondition")).thenReturn(" C ", "S");
    when(rs.getString("textpattern")).thenReturn(" commission ", "transfer");
    when(rs.getString("c_glitem_id")).thenReturn("GL-1", null);
    when(rs.getString("c_bpartner_id")).thenReturn("BP-1", null);
    when(rs.getString("etgo_transaction_type_id")).thenReturn(null, null);
    when(rs.getString("c_project_id")).thenReturn(null, null);
    when(rs.getString("c_costcenter_id")).thenReturn(null, null);
    when(rs.getString("m_product_id")).thenReturn(null, null);
    when(rs.getLong("matchcount")).thenReturn(3L, 0L);

    List<MatchRuleEngine.Rule> rules = MatchRuleEngine.loadRules(conn, "ACC-1");

    assertEquals(2, rules.size());
    assertEquals("R1", rules.get(0).id);
    assertEquals("Fee Rule", rules.get(0).name);
    assertEquals("C", rules.get(0).textCondition);
    assertEquals("commission", rules.get(0).textPattern);
    assertEquals("GL-1", rules.get(0).glItemId);
    assertEquals("BP-1", rules.get(0).bpartnerId);
    assertEquals(3L, rules.get(0).matchCount);
    assertEquals("R2", rules.get(1).id);
    assertEquals(20, rules.get(1).priority);
    verify(ps).setString(1, "ACC-1");
  }

  /**
   * A blank account id is bound as an empty string (global-only rule load) and an empty ResultSet
   * yields an empty list.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testLoadRulesBlankAccountBindsEmptyStringAndEmptyResultSet() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    List<MatchRuleEngine.Rule> rules = MatchRuleEngine.loadRules(conn, "  ");

    assertTrue(rules.isEmpty());
    verify(ps).setString(1, "");
  }
}
