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

package com.etendoerp.go.featureflags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FeatureFlagContextTest {

  /**
   * Updated: {@code forAccount} now carries the email as BOTH the targeting key and the
   * {@code Email} attribute — see {@code FeatureFlagContext#ATTRIBUTE_EMAIL} for why both. The
   * assertion that the attribute map was empty pinned the earlier shape, so it is rewritten to
   * guard the current one rather than dropped: an email that stopped reaching the attribute would
   * silently stop matching every attribute-based targeting rule, with the targeting key still
   * looking right.
   */
  @Test
  void targetsOnTheAccountEmail() {
    FeatureFlagContext context = FeatureFlagContext.forAccount("user@example.com");
    assertEquals("user@example.com", context.getTargetingKey());
    // forAccount() publishes the email as BOTH the targeting key and the Email attribute — see
    // FeatureFlagContext.ATTRIBUTE_EMAIL for why the duplication is load-bearing (ETP-4966).
    assertEquals(Map.of(FeatureFlagContext.ATTRIBUTE_EMAIL, "user@example.com"),
        context.getAttributes());
  }

  /**
   * The blank case is the one that matters: a context with neither the key nor the attribute must
   * never read as an empty-string identity a rule could match.
   *
   * <p>Kept through the develop merge that rewrote this class to whole-map assertions:
   * {@code aBlankAccountLeavesNoTargetingKey} covers the targeting key for the same inputs, and
   * nothing else covers the attribute map, which is the half a rule would match on.</p>
   */
  @Test
  void aBlankAccountCarriesNoEmailAttributeEither() {
    assertTrue(FeatureFlagContext.forAccount("   ").getAttributes().isEmpty());
    assertTrue(FeatureFlagContext.forAccount(null).getAttributes().isEmpty());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "   " })
  void aBlankAccountLeavesNoTargetingKey(String email) {
    assertNull(FeatureFlagContext.forAccount(email).getTargetingKey());
  }

  @Test
  void addsAttributesWithoutMutatingTheOriginal() {
    FeatureFlagContext base = FeatureFlagContext.forAccount("user@example.com");
    FeatureFlagContext scoped = base.with(FeatureFlagContext.ATTRIBUTE_CLIENT_ID, "CLIENT1");

    // base still carries exactly what forAccount() gave it: it never gained clientId.
    assertEquals(Map.of(FeatureFlagContext.ATTRIBUTE_EMAIL, "user@example.com"),
        base.getAttributes());
    assertEquals("CLIENT1", scoped.getAttributes().get(FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    assertEquals("user@example.com",
        scoped.getAttributes().get(FeatureFlagContext.ATTRIBUTE_EMAIL),
        "with() must carry the attributes it inherited, not replace them");
    assertEquals("user@example.com", scoped.getTargetingKey());
  }

  @Test
  void ignoresBlankAttributeKeysAndValues() {
    FeatureFlagContext context = FeatureFlagContext.forAccount("user@example.com")
        .with(FeatureFlagContext.ATTRIBUTE_CLIENT_ID, null)
        .with(FeatureFlagContext.ATTRIBUTE_CLIENT_ID, "  ")
        .with(null, "CLIENT1")
        .with("   ", "CLIENT1");
    // Only the Email attribute survives: none of the four blank with() calls added anything.
    assertEquals(Map.of(FeatureFlagContext.ATTRIBUTE_EMAIL, "user@example.com"),
        context.getAttributes());
  }

  @Test
  void attributesAreNotModifiableByCallers() {
    FeatureFlagContext context = FeatureFlagContext.forAccount("user@example.com")
        .with(FeatureFlagContext.ATTRIBUTE_CLIENT_ID, "CLIENT1");
    assertThrows(UnsupportedOperationException.class,
        () -> context.getAttributes().put("other", "value"));
  }
}
