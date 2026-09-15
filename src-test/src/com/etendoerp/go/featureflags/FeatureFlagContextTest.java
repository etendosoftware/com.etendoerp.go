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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FeatureFlagContextTest {

  @Test
  void targetsOnTheAccountEmail() {
    FeatureFlagContext context = FeatureFlagContext.forAccount("user@example.com");
    assertEquals("user@example.com", context.getTargetingKey());
    // forAccount() publishes the email as BOTH the targeting key and the Email attribute — see
    // FeatureFlagContext.ATTRIBUTE_EMAIL for why the duplication is load-bearing (ETP-4966).
    assertEquals(Map.of(FeatureFlagContext.ATTRIBUTE_EMAIL, "user@example.com"),
        context.getAttributes());
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
