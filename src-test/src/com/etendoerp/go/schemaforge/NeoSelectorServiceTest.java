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
}
