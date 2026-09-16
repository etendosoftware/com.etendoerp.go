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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    assertEquals("user@example.com",
        context.getAttributes().get(FeatureFlagContext.ATTRIBUTE_EMAIL));
    assertEquals(1, context.getAttributes().size(),
        "forAccount contributes the email attribute and nothing else");
  }

  /**
   * The blank case is the one that matters: a context with neither the key nor the attribute must
   * never read as an empty-string identity a rule could match.
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

    // The original keeps exactly what forAccount gave it — the email attribute, and no clientId.
    assertFalse(base.getAttributes().containsKey(FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    assertEquals(1, base.getAttributes().size());
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

    // Every blank call was a no-op, so what remains is exactly what forAccount contributed.
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
