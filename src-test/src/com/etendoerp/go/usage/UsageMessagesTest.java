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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit specs for {@link UsageMessages} (ETP-5050).
 *
 * <p><b>Why a whole class for one two-line method.</b> Because the failure it prevents is
 * silence. Openbravo treats {@code @} as its message-parameter delimiter, so a message carrying a
 * stray at-sign can reach the user <b>blank</b> — the record does not save and the screen says
 * nothing, which is worse than no validation at all. That already shipped once, in the
 * undeployed-qualifier message which named {@code @Named} and {@code @ApplicationScoped}, and the
 * fixed wording was only half the exposure: the other half is every value a message interpolates
 * that we do not control (a catalog search key an administrator typed, an HQL fragment, an
 * exception message from Hibernate or CDI).
 *
 * <p>The behaviour is therefore pinned here at the unit level, where it can be stated exactly,
 * and again at each call site in {@code UsageResourceValidatorTest} and
 * {@code UsageAggregationProcessTest} with values that genuinely carry at-signs — a guard nobody
 * calls is not a guard.
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
class UsageMessagesTest {

  @Nested
  @DisplayName("text that carries at-signs")
  class TextWithAtSigns {

    /**
     * REPLACED, not stripped, and this is the whole design decision. Dropping the character turns
     * {@code a@b.com} into {@code ab.com}, which reads as a real value and is quietly wrong;
     * {@code a(at)b.com} is visibly a substitution, so a reader can see what happened and act on
     * it. Asserting the exact output is what holds that distinction — a test that only checked
     * "no at-sign remains" would accept the stripping version.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({ "BILLING@ACME, BILLING(at)ACME", "a@b.com, a(at)b.com",
        "'@Named(\"x\")', '(at)Named(\"x\")'", "'@', '(at)'" })
    void oneAtSignIsReplacedInPlace(String raw, String expected) {
      assertEquals(expected, UsageMessages.atSafe(raw));
    }

    /** Every occurrence, not just the first: one survivor is enough to blank the message. */
    @Test
    void everyAtSignIsReplaced() {
      String result = UsageMessages.atSafe("@Named(\"a@b\") and @ApplicationScoped");

      assertAll(
          () -> assertEquals("(at)Named(\"a(at)b\") and (at)ApplicationScoped", result),
          () -> assertFalse(result.contains("@"), result));
    }

    /** An at-sign at either end is as dangerous as one in the middle, and as easy to miss. */
    @ParameterizedTest(name = "raw = {0}")
    @ValueSource(strings = { "@leading", "trailing@", "@both@", "@@adjacent@@" })
    void atSignsAtTheEdgesAreReplacedToo(String raw) {
      assertFalse(UsageMessages.atSafe(raw).contains("@"), raw);
    }
  }

  @Nested
  @DisplayName("text that needs no change")
  class TextWithoutAtSigns {

    /**
     * Text with nothing to replace comes back as it was. The guard is applied indiscriminately to
     * every interpolated value, so the overwhelmingly common case must be a no-op — a guard that
     * mangled ordinary search keys would be removed again within a week.
     */
    @ParameterizedTest(name = "raw = {0}")
    @ValueSource(strings = { "BILLING_ACME", "no counter is deployed for that qualifier",
        "select count(*) from Invoice e where e.invoiceDate >= :dayStart", "   ", "(at)" })
    void textWithNoAtSignIsUnchanged(String raw) {
      assertEquals(raw, UsageMessages.atSafe(raw));
    }

    /**
     * Null passes through rather than becoming "null" or throwing. Several call sites interpolate
     * values that can legitimately be absent — a resource with no counted entity, an exception
     * with no message — and a guard that threw would convert a message about a failure into a
     * second, more confusing failure inside the error handler.
     */
    @Test
    void nullPassesThrough() {
      assertNull(UsageMessages.atSafe(null));
    }

    @Test
    void emptyPassesThroughUnchanged() {
      assertEquals("", UsageMessages.atSafe(""));
      assertSame("", UsageMessages.atSafe(""));
    }
  }
}
