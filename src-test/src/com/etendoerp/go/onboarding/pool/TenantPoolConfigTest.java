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
package com.etendoerp.go.onboarding.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ETP-5389 — which requests the pool can serve, and the size bounds. */
public class TenantPoolConfigTest {

  @Test
  public void onlyTheEurSpainSpanishCombinationIsSupported() {
    assertTrue(TenantPoolConfig.supports("EUR", "ES", "es_ES"));
    assertTrue(TenantPoolConfig.supports(" eur ", "es", "es_ES"));
    assertFalse(TenantPoolConfig.supports("USD", "ES", "es_ES"));
    assertFalse(TenantPoolConfig.supports("EUR", "PT", "es_ES"));
    assertFalse(TenantPoolConfig.supports("EUR", "ES", "en_US"));
    assertFalse(TenantPoolConfig.supports(null, null, null));
  }

  @Test
  public void placeholderNamesAreRecognised() {
    assertTrue(TenantPoolConfig.isPlaceholderName("POOL-ABC"));
    assertFalse(TenantPoolConfig.isPlaceholderName("Acme"));
    assertFalse(TenantPoolConfig.isPlaceholderName(null));
  }

  @Test
  public void sizeIsClamped() {
    assertEquals(0, TenantPoolConfig.clamp(-4, 0, TenantPoolConfig.MAX_SIZE));
    assertEquals(TenantPoolConfig.MAX_SIZE,
        TenantPoolConfig.clamp(500, 0, TenantPoolConfig.MAX_SIZE));
    assertEquals(3, TenantPoolConfig.clamp(3, 0, TenantPoolConfig.MAX_SIZE));
  }

  @Test
  public void defaultsWhenNothingIsConfigured() {
    assertEquals(TenantPoolConfig.DEFAULT_SIZE, TenantPoolConfig.poolSize());
    String property = "etendo.go.flags.onboarding-tenant-pool";
    String previous = System.getProperty(property);
    try {
      System.setProperty(property, "false");
      assertFalse(TenantPoolConfig.isEnabled(null));
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }
}
