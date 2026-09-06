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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.financialmgmt.gl.GLItem;

import com.etendoerp.go.schemaforge.data.MatchRule;

/**
 * Unit tests for {@link MatchRuleEngine}. The text-matching and evaluation logic is exercised
 * directly (pure, no mocks); {@code loadRules} is covered against a mocked {@link OBCriteria}.
 */
public class MatchRuleEngineTest {

  /**
   * Releases the inline mock-maker references created during the test. Without this they
   * accumulate across the module's single test JVM and push the fork past its heap cap.
   */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

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
  // loadRules — DAL entity mapping
  // ---------------------------------------------------------------------------

  /**
   * loadRules maps each persisted rule onto a Rule, trims text fields, and preserves the query
   * order.
   *
   * <p>Since ETP-4950 this loads through the DAL instead of raw JDBC. That is what makes the rules
   * tenant-scoped: {@code OBCriteria} adds the readable-client / readable-organization predicates by
   * itself, so no caller can forget them — the previous hand-written SQL had no {@code ad_client_id}
   * filter at all and leaked every tenant's global rules into every other tenant's Automatch.
   */
  @Test
  public void testLoadRulesMapsEntitiesOntoRules() {
    // Build the referenced mocks BEFORE the when(...) that returns them: creating a mock inside an
    // unfinished stubbing call is what Mockito reports as UnfinishedStubbingException.
    GLItem concept = glItem("GL-1");
    BusinessPartner partner = bpartner("BP-1");
    MatchRule first = ruleRow("R1", "  Fee Rule  ", 10L, " C ", " commission ", 3L);
    when(first.getAccountingConcept()).thenReturn(concept);
    when(first.getBusinessPartner()).thenReturn(partner);
    MatchRule second = ruleRow("R2", "Transfer Rule", 20L, "S", "transfer", 0L);

    List<MatchRuleEngine.Rule> rules = loadWith(Arrays.asList(first, second), "ACC-1");

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
  }

  /** Every optional FK left unset maps to a null id rather than blowing up. */
  @Test
  public void testLoadRulesLeavesUnsetForeignKeysNull() {
    MatchRule row = ruleRow("R1", "Fee", 10L, "C", "fee", 0L);

    List<MatchRuleEngine.Rule> rules = loadWith(Collections.singletonList(row), "ACC-1");

    assertEquals(1, rules.size());
    assertNull(rules.get(0).glItemId);
    assertNull(rules.get(0).bpartnerId);
    assertNull(rules.get(0).transactionTypeId);
    assertNull(rules.get(0).projectId);
    assertNull(rules.get(0).costCenterId);
    assertNull(rules.get(0).productId);
  }

  /** A null priority or match count degrades to zero instead of throwing on unboxing. */
  @Test
  public void testLoadRulesTreatsNullPriorityAndMatchCountAsZero() {
    MatchRule row = ruleRow("R1", "Fee", null, "C", "fee", null);

    List<MatchRuleEngine.Rule> rules = loadWith(Collections.singletonList(row), "ACC-1");

    assertEquals(0, rules.get(0).priority);
    assertEquals(0L, rules.get(0).matchCount);
  }

  /** An empty result yields an empty list. */
  @Test
  public void testLoadRulesReturnsEmptyListWhenNothingMatches() {
    assertTrue(loadWith(Collections.emptyList(), "  ").isEmpty());
  }

  // ---------------------------------------------------------------------------
  // loadRules helpers
  // ---------------------------------------------------------------------------

  /** Runs loadRules against a criteria stubbed to return {@code rows}. */
  @SuppressWarnings("unchecked")
  private static List<MatchRuleEngine.Rule> loadWith(List<MatchRule> rows, String accountId) {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      OBCriteria<MatchRule> criteria = mock(OBCriteria.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.createCriteria(MatchRule.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(rows);
      return MatchRuleEngine.loadRules(accountId);
    }
  }

  private static MatchRule ruleRow(String id, String name, Long priority, String condition,
      String pattern, Long matchCount) {
    MatchRule row = mock(MatchRule.class);
    when(row.getId()).thenReturn(id);
    when(row.getName()).thenReturn(name);
    when(row.getPriority()).thenReturn(priority);
    when(row.getTextCondition()).thenReturn(condition);
    when(row.getTextPattern()).thenReturn(pattern);
    when(row.getMatchCount()).thenReturn(matchCount);
    return row;
  }

  private static GLItem glItem(String id) {
    GLItem item = mock(GLItem.class);
    when(item.getId()).thenReturn(id);
    return item;
  }

  private static BusinessPartner bpartner(String id) {
    BusinessPartner partner = mock(BusinessPartner.class);
    when(partner.getId()).thenReturn(id);
    return partner;
  }
}
