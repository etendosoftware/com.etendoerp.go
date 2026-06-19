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
    return MatchRuleEngine.Rule.of(id, "Rule " + id, priority, condition, pattern,
        "GL-001", "BP-001", null, null, null, null, 0L);
  }

  // ---------------------------------------------------------------------------
  // buildSearchText
  // ---------------------------------------------------------------------------

  @Test
  public void buildSearchText_allBlank_returnsEmpty() {
    assertEquals("", MatchRuleEngine.buildSearchText("", "", ""));
  }

  @Test
  public void buildSearchText_descOnly_returnsTrimmedLower() {
    assertEquals("commission fee", MatchRuleEngine.buildSearchText("COMMISSION FEE", "", ""));
  }

  @Test
  public void buildSearchText_allFields_concatenatedWithSpaces() {
    String result = MatchRuleEngine.buildSearchText("COMMISSION FEE", "REF-001", "ACME");
    assertEquals("commission fee ref-001 acme", result);
  }

  // ---------------------------------------------------------------------------
  // matches — Contains
  // ---------------------------------------------------------------------------

  @Test
  public void matches_contains_hit() {
    MatchRuleEngine.Rule r = rule("1", MatchRuleEngine.COND_CONTAINS, "commission");
    assertTrue(MatchRuleEngine.matches(r, "bank commission fee may"));
  }

  @Test
  public void matches_contains_miss() {
    MatchRuleEngine.Rule r = rule("1", MatchRuleEngine.COND_CONTAINS, "commission");
    assertFalse(MatchRuleEngine.matches(r, "bank transfer fee may"));
  }

  @Test
  public void matches_contains_caseInsensitive() {
    MatchRuleEngine.Rule r = rule("1", MatchRuleEngine.COND_CONTAINS, "COMMISSION");
    assertTrue(MatchRuleEngine.matches(r, "bank commission fee"));
  }

  // ---------------------------------------------------------------------------
  // matches — Starts with
  // ---------------------------------------------------------------------------

  @Test
  public void matches_starts_hit() {
    MatchRuleEngine.Rule r = rule("2", MatchRuleEngine.COND_STARTS, "bank fee");
    assertTrue(MatchRuleEngine.matches(r, "bank fee may 2026"));
  }

  @Test
  public void matches_starts_miss() {
    MatchRuleEngine.Rule r = rule("2", MatchRuleEngine.COND_STARTS, "bank fee");
    assertFalse(MatchRuleEngine.matches(r, "monthly bank fee"));
  }

  // ---------------------------------------------------------------------------
  // matches — Regex
  // ---------------------------------------------------------------------------

  @Test
  public void matches_regex_hit() {
    MatchRuleEngine.Rule r = rule("3", MatchRuleEngine.COND_REGEX, "^commission.*fee$");
    assertTrue(MatchRuleEngine.matches(r, "commission maintenance fee"));
  }

  @Test
  public void matches_regex_miss() {
    MatchRuleEngine.Rule r = rule("3", MatchRuleEngine.COND_REGEX, "^commission.*fee$");
    assertFalse(MatchRuleEngine.matches(r, "bank transfer 2026"));
  }

  @Test
  public void matches_regex_catastrophicBacktracking_returnsFalse() {
    // Pattern known to cause catastrophic backtracking — engine should time out and return false.
    MatchRuleEngine.Rule r = rule("4", MatchRuleEngine.COND_REGEX, "(a+)+");
    assertFalse(MatchRuleEngine.matches(r,
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!"));
  }

  @Test
  public void matches_regex_invalidPattern_returnsFalse() {
    MatchRuleEngine.Rule r = rule("5", MatchRuleEngine.COND_REGEX, "[invalid(");
    assertFalse(MatchRuleEngine.matches(r, "anything"));
  }

  // ---------------------------------------------------------------------------
  // matches — edge cases
  // ---------------------------------------------------------------------------

  @Test
  public void matches_blankText_returnsFalse() {
    MatchRuleEngine.Rule r = rule("6", MatchRuleEngine.COND_CONTAINS, "fee");
    assertFalse(MatchRuleEngine.matches(r, ""));
  }

  @Test
  public void matches_blankPattern_returnsFalse() {
    MatchRuleEngine.Rule r = rule("7", MatchRuleEngine.COND_CONTAINS, "");
    assertFalse(MatchRuleEngine.matches(r, "bank fee"));
  }

  @Test
  public void matches_unknownCondition_returnsFalse() {
    MatchRuleEngine.Rule r = rule("8", "X", "fee");
    assertFalse(MatchRuleEngine.matches(r, "bank fee"));
  }

  // ---------------------------------------------------------------------------
  // evaluate — priority ordering
  // ---------------------------------------------------------------------------

  @Test
  public void evaluate_noRules_noMatch() {
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate(
        "commission fee", "", "", Collections.emptyList());
    assertFalse(result.isMatched());
    assertNull(result.primary);
    assertTrue(result.alternatives.isEmpty());
  }

  @Test
  public void evaluate_singleMatch_primarySet() {
    List<MatchRuleEngine.Rule> rules = Arrays.asList(
        rule("R1", MatchRuleEngine.COND_CONTAINS, "commission"),
        rule("R2", MatchRuleEngine.COND_CONTAINS, "transfer"));
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("commission fee", "", "", rules);
    assertTrue(result.isMatched());
    assertEquals("R1", result.primary.id);
    assertTrue(result.alternatives.isEmpty());
  }

  @Test
  public void evaluate_multipleMatches_lowestPriorityWinsAlternativesListed() {
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

  @Test
  public void evaluate_inactiveRuleNotEvaluated_noMatch() {
    // An inactive rule should not even be loaded; we simulate by not including it.
    List<MatchRuleEngine.Rule> rules = Collections.emptyList();
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("commission fee", "", "", rules);
    assertFalse(result.isMatched());
  }

  @Test
  public void evaluate_descriptionUsed() {
    List<MatchRuleEngine.Rule> rules = Arrays.asList(
        rule("R1", MatchRuleEngine.COND_CONTAINS, "impuestos"));
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("PAGO IMPUESTOS MAY", "", "", rules);
    assertTrue(result.isMatched());
    assertEquals("R1", result.primary.id);
  }

  @Test
  public void evaluate_referenceUsed() {
    List<MatchRuleEngine.Rule> rules = Arrays.asList(
        rule("R1", MatchRuleEngine.COND_CONTAINS, "ref-999"));
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("", "REF-999", "", rules);
    assertTrue(result.isMatched());
  }

  @Test
  public void evaluate_partnerNameUsed() {
    List<MatchRuleEngine.Rule> rules = Arrays.asList(
        rule("R1", MatchRuleEngine.COND_CONTAINS, "acme"));
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("", "", "ACME CORP", rules);
    assertTrue(result.isMatched());
  }

  @Test
  public void evaluate_noMatch_returnsNoMatchResult() {
    List<MatchRuleEngine.Rule> rules = Arrays.asList(
        rule("R1", MatchRuleEngine.COND_CONTAINS, "commission"));
    MatchRuleEngine.MatchResult result = MatchRuleEngine.evaluate("bank transfer", "", "", rules);
    assertFalse(result.isMatched());
    assertNull(result.primary);
  }

  @Test
  public void evaluate_alternativesListIsUnmodifiable() {
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
}
