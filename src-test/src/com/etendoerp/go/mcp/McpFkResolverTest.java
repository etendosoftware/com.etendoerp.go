/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link McpFkResolver}'s DAL-free static logic (IMP-4): id-vs-search-string
 * detection and the match-count-to-outcome decision. The DAL-bound {@code resolveFkNames} path
 * (selector lookup, body mutation) needs a live instance and is exercised manually/via the MCP
 * validation bot instead.
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpFkResolver")
class McpFkResolverTest {

  @Nested
  @DisplayName("looksLikeId")
  class LooksLikeId {

    @Test
    @DisplayName("a 32-char hex string (upper, lower, or mixed case) is treated as an id")
    void hexIdsAreIds() {
      assertTrue(McpFkResolver.looksLikeId("95E2A8B50A254B2AAE6774B8C2F28120"));
      assertTrue(McpFkResolver.looksLikeId("95e2a8b50a254b2aae6774b8c2f28120"));
      assertTrue(McpFkResolver.looksLikeId("95E2a8b50A254b2AAe6774b8C2f28120"));
    }

    @Test
    @DisplayName("a human search string, a short id, a non-hex string, or null is not an id")
    void nonIdsAreNotIds() {
      assertFalse(McpFkResolver.looksLikeId("Acme Corp"));
      assertFalse(McpFkResolver.looksLikeId("95E2A8B50A254B2AAE6774B8C2F281")); // 31 chars
      assertFalse(McpFkResolver.looksLikeId("95E2A8B50A254B2AAE6774B8C2F2812Z")); // non-hex char
      assertFalse(McpFkResolver.looksLikeId(""));
      assertFalse(McpFkResolver.looksLikeId(null));
    }
  }

  @Nested
  @DisplayName("decideOutcome")
  class DecideOutcome {

    @Test
    @DisplayName("zero matches is NOT_FOUND")
    void zeroIsNotFound() {
      assertEquals(McpFkResolver.Outcome.NOT_FOUND, McpFkResolver.decideOutcome(0));
    }

    @Test
    @DisplayName("exactly one match is RESOLVED")
    void oneIsResolved() {
      assertEquals(McpFkResolver.Outcome.RESOLVED, McpFkResolver.decideOutcome(1));
    }

    @Test
    @DisplayName("more than one match is AMBIGUOUS")
    void manyIsAmbiguous() {
      assertEquals(McpFkResolver.Outcome.AMBIGUOUS, McpFkResolver.decideOutcome(2));
      assertEquals(McpFkResolver.Outcome.AMBIGUOUS, McpFkResolver.decideOutcome(10));
    }
  }
}
