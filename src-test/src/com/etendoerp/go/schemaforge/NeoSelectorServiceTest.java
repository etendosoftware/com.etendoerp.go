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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * Unit tests for {@link NeoSelectorService} utility methods.
 */
class NeoSelectorServiceTest {

  private static final String FILTER_A = "a.id='X'";
  private static final String FILTER_B = "b.org='Y'";

  /** Combining two null filters returns null. */
  @Test
  void testCombineFiltersBothNullReturnsNull() {
    assertNull(NeoSelectorService.combineFilters(null, null));
  }

  /** Combining two blank filters returns null. */
  @Test
  void testCombineFiltersBothBlankReturnsNull() {
    assertNull(NeoSelectorService.combineFilters("", "  "));
  }

  /** A single non-blank filter is returned as-is. */
  @Test
  void testCombineFiltersSingleNonBlankReturnsThat() {
    assertEquals(FILTER_A, NeoSelectorService.combineFilters(null, FILTER_A));
  }

  /** Two non-blank filters are joined with AND. */
  @Test
  void testCombineFiltersTwoNonBlankJoinsWithAnd() {
    String result = NeoSelectorService.combineFilters(FILTER_A, FILTER_B);
    assertEquals(FILTER_A + " AND " + FILTER_B, result);
  }

  /** Blank entries in the middle are skipped. */
  @Test
  void testCombineFiltersSkipsBlanksInMiddle() {
    String result = NeoSelectorService.combineFilters("x=1", "", "y=2");
    assertEquals("x=1 AND y=2", result);
  }

  /** Calling with no arguments returns null. */
  @Test
  void testCombineFiltersNoArgsReturnsNull() {
    assertNull(NeoSelectorService.combineFilters());
  }

  // --------------------------------------------------------------------
  // resolveSearchableFragment — custom-HQL selectors with blank property
  // --------------------------------------------------------------------

  /** Standard selector: non-blank property is returned verbatim. */
  @Test
  @DisplayName("resolveSearchableFragment prefers non-blank property")
  void testResolveFragmentPrefersProperty() {
    assertEquals("name",
        NeoSelectorService.resolveSearchableFragment("name", "bp.name"));
  }

  /** Custom HQL selector: blank property + safe dotted clause_left_part. */
  @Test
  @DisplayName("resolveSearchableFragment accepts dotted clause_left_part when property is blank")
  void testResolveFragmentFallsBackToClauseLeftPart() {
    assertEquals("bp.name",
        NeoSelectorService.resolveSearchableFragment("", "bp.name"));
    assertEquals("bp.searchKey",
        NeoSelectorService.resolveSearchableFragment(null, "bp.searchKey"));
  }

  /** Deep dotted path (contact.businessPartner.name) is safe. */
  @Test
  @DisplayName("resolveSearchableFragment accepts multi-segment dotted paths")
  void testResolveFragmentDeepPath() {
    assertEquals("contact.businessPartner.name",
        NeoSelectorService.resolveSearchableFragment("", "contact.businessPartner.name"));
  }

  /** Whitespace around a safe clause is stripped. */
  @Test
  @DisplayName("resolveSearchableFragment trims safe clause_left_part")
  void testResolveFragmentTrimsClauseLeftPart() {
    assertEquals("bp.name",
        NeoSelectorService.resolveSearchableFragment("", "  bp.name  "));
  }

  /** Complex expressions (COALESCE, arithmetic, quotes, etc.) are rejected. */
  @Test
  @DisplayName("resolveSearchableFragment rejects complex HQL expressions")
  void testResolveFragmentRejectsComplexExpressions() {
    assertNull(NeoSelectorService.resolveSearchableFragment(
        "", "COALESCE(contact.name, usercontact.name)"));
    assertNull(NeoSelectorService.resolveSearchableFragment(
        "", "bp.creditLimit - bp.creditUsed"));
    assertNull(NeoSelectorService.resolveSearchableFragment(
        "", "bp.name || ' ' || bp.searchKey"));
    assertNull(NeoSelectorService.resolveSearchableFragment(
        "", "(select x from Foo f)"));
    assertNull(NeoSelectorService.resolveSearchableFragment(
        "", "bp.name = 'x'"));
  }

  /** Both blank returns null — no search applied. */
  @Test
  @DisplayName("resolveSearchableFragment returns null when both property and clause are blank")
  void testResolveFragmentBothBlank() {
    assertNull(NeoSelectorService.resolveSearchableFragment(null, null));
    assertNull(NeoSelectorService.resolveSearchableFragment("", ""));
    assertNull(NeoSelectorService.resolveSearchableFragment("  ", "  "));
  }
}
