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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.etendoerp.go.common.GoRuntimeProperties;

/**
 * ETP-5389 — which requests the pool can serve, and the size bounds.
 *
 * @covers com.etendoerp.go.onboarding.pool.TenantPoolConfig
 */
public class TenantPoolConfigTest {

  /**
   * Makes the pool size read as set nowhere (no JVM property, no {@code Openbravo.properties} entry,
   * no environment variable) while every other setting is still read for real. Without it a test
   * read this machine's own configuration and failed wherever a developer sizes the local pool.
   *
   * @return the static mock to close when the test is done
   */
  public static MockedStatic<GoRuntimeProperties> poolSizeUnset() {
    MockedStatic<GoRuntimeProperties> props =
        Mockito.mockStatic(GoRuntimeProperties.class, Mockito.CALLS_REAL_METHODS);
    props.when(() -> GoRuntimeProperties.readValue(eq(TenantPoolConfig.PROP_SIZE),
        eq(TenantPoolConfig.ENV_SIZE), anyString())).thenAnswer(call -> call.getArgument(2));
    return props;
  }

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
    try (MockedStatic<GoRuntimeProperties> props = poolSizeUnset()) {
      assertEquals(TenantPoolConfig.DEFAULT_SIZE, TenantPoolConfig.poolSize());
    }
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
