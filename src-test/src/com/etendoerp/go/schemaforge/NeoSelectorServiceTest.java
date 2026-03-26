package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NeoSelectorService} utility methods.
 */
class NeoSelectorServiceTest {

  // ── combineFilters ────────────────────────────────────────────────────

  @Test
  void testCombineFilters_bothNull_returnsNull() {
    assertNull(NeoSelectorService.combineFilters(null, null));
  }

  @Test
  void testCombineFilters_bothBlank_returnsNull() {
    assertNull(NeoSelectorService.combineFilters("", "  "));
  }

  @Test
  void testCombineFilters_singleNonBlank_returnsThat() {
    assertEquals("a.id='X'", NeoSelectorService.combineFilters(null, "a.id='X'"));
  }

  @Test
  void testCombineFilters_twoNonBlank_joinsWithAnd() {
    String result = NeoSelectorService.combineFilters("a.id='X'", "b.org='Y'");
    assertEquals("a.id='X' AND b.org='Y'", result);
  }

  @Test
  void testCombineFilters_skipsBlanksInMiddle() {
    String result = NeoSelectorService.combineFilters("x=1", "", "y=2");
    assertEquals("x=1 AND y=2", result);
  }

  @Test
  void testCombineFilters_noArgs_returnsNull() {
    assertNull(NeoSelectorService.combineFilters());
  }
}
