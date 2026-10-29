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

package com.etendoerp.go.usage.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The verdict rule of the plan quotas: mode {@code off} / {@code warn} / {@code block} against the
 * consumption, the included quantity and the optional warning threshold.
 *
 * <p>The matrix is the contract table: each mode against a consumption under the threshold, at the
 * threshold, at the limit and over it. The quantities are chosen so each position is exact
 * ({@code 100} included, {@code 80}% threshold).
 *
 * @covers com.etendoerp.go.usage.quota.QuotaRules
 */
@SuppressWarnings("java:S2187")
class QuotaRulesTest {

  @Nested
  @DisplayName("mode x consumption matrix, 100 included, 80% threshold")
  class Matrix {

    @ParameterizedTest(name = "{0} at {1} -> {2}")
    @CsvSource(nullValues = "NULL", value = {
        // mode,  consumed, verdict
        "off,     50,  NULL",
        "off,     80,  NULL",
        "off,    100,  NULL",
        "off,    150,  NULL",
        "warn,    50,  ALLOW",
        "warn,    79,  ALLOW",
        "warn,    80,  WARN",
        "warn,   100,  WARN",
        "warn,   150,  WARN",
        "block,   50,  ALLOW",
        "block,   79,  ALLOW",
        "block,   80,  WARN",
        "block,   99,  WARN",
        "block,  100,  DENY",
        "block,  150,  DENY",
    })
    void verdictFollowsTheContract(String mode, long consumed, QuotaVerdict expected) {
      assertEquals(expected, QuotaRules.verdict(mode, 100L, 80L, consumed));
    }
  }

  @Nested
  @DisplayName("no warning threshold")
  class NullThreshold {

    @ParameterizedTest(name = "{0} at {1} -> {2}")
    @CsvSource(nullValues = "NULL", value = {
        "off,     99,  NULL",
        "warn,    99,  ALLOW",
        "warn,   100,  WARN",
        "warn,   150,  WARN",
        "block,   99,  ALLOW",
        "block,  100,  DENY",
        "block,  150,  DENY",
    })
    void onlyTheLimitCounts(String mode, long consumed, QuotaVerdict expected) {
      assertEquals(expected, QuotaRules.verdict(mode, 100L, null, consumed));
    }
  }

  @Nested
  @DisplayName("included quantity zero")
  class IncludedZero {

    @Test
    void blockDeniesTheFirstUnit() {
      assertEquals(QuotaVerdict.DENY, QuotaRules.verdict("block", 0L, 80L, 0L));
    }

    @Test
    void warnWarnsFromTheFirstUnit() {
      assertEquals(QuotaVerdict.WARN, QuotaRules.verdict("warn", 0L, null, 0L));
    }

    @Test
    void offIsStillNotEvaluated() {
      assertNull(QuotaRules.verdict("off", 0L, 80L, 5L));
    }
  }

  @Nested
  @DisplayName("edges")
  class Edges {

    @Test
    void aNullIncludedQuantityIsNotEvaluatedRatherThanReadAsZero() {
      assertNull(QuotaRules.verdict("block", null, 80L, 1_000L));
    }

    @Test
    void anUnknownModeIsNotEvaluated() {
      assertNull(QuotaRules.verdict("strict", 100L, 80L, 1_000L));
    }

    @Test
    void theThresholdIsNotRoundedAway() {
      // 3 included at 50%: the threshold is 1.5 units. 1 is under it, 2 is over it.
      assertEquals(QuotaVerdict.ALLOW, QuotaRules.verdict("block", 3L, 50L, 1L));
      assertEquals(QuotaVerdict.WARN, QuotaRules.verdict("block", 3L, 50L, 2L));
    }

    @Test
    void aHugeIncludedQuantityDoesNotOverflowTheThresholdTest() {
      long included = Long.MAX_VALUE / 2;
      // included * 80 overflows a long; the verdict must still be computed exactly.
      assertEquals(QuotaVerdict.ALLOW, QuotaRules.verdict("block", included, 80L, included / 2));
      assertEquals(QuotaVerdict.WARN, QuotaRules.verdict("block", included, 80L, included - 1));
      assertEquals(QuotaVerdict.DENY, QuotaRules.verdict("block", included, 80L, included));
    }

    @Test
    void aThresholdOfOneHundredWarnsOnlyAtTheLimit() {
      assertEquals(QuotaVerdict.ALLOW, QuotaRules.verdict("warn", 100L, 100L, 99L));
      assertEquals(QuotaVerdict.WARN, QuotaRules.verdict("warn", 100L, 100L, 100L));
    }
  }

  @Nested
  @DisplayName("isEnforced / normaliseMode")
  class Modes {

    @Test
    void onlyWarnAndBlockAreEnforced() {
      assertTrue(QuotaRules.isEnforced("warn"));
      assertTrue(QuotaRules.isEnforced("block"));
      assertFalse(QuotaRules.isEnforced("off"));
      assertFalse(QuotaRules.isEnforced(null));
      assertFalse(QuotaRules.isEnforced("BLOCK"));
    }

    @ParameterizedTest
    @ValueSource(strings = { " Block ", "BLOCK", "block" })
    void aStoredModeIsTrimmedAndLowerCased(String raw) {
      assertEquals("block", QuotaRules.normaliseMode(raw));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void aBlankModeReadsAsOff(String raw) {
      assertEquals("off", QuotaRules.normaliseMode(raw));
    }
  }
}
