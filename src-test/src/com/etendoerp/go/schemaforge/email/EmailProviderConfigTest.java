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

package com.etendoerp.go.schemaforge.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link EmailProviderConfig}.
 */
public class EmailProviderConfigTest {

  @Test
  public void missingBaseUrlDoesNotUseHardcodedDefault() {
    EmailProviderConfig config = new EmailProviderConfig(null, "secret", true, 10000);

    assertFalse(config.isConfigured());
    assertNull(config.getBaseUrl());
  }

  @Test
  public void missingApiKeyIsNotConfigured() {
    EmailProviderConfig config = new EmailProviderConfig("https://provider.example/send", null,
        true, 10000);

    assertFalse(config.isConfigured());
  }

  @Test
  public void disabledProviderIsNotConfigured() {
    EmailProviderConfig config = new EmailProviderConfig("https://provider.example/send", "secret",
        false, 10000);

    assertFalse(config.isConfigured());
    assertFalse(config.isEnabled());
  }

  @Test
  public void positiveConfigIsConfigured() {
    EmailProviderConfig config = new EmailProviderConfig("https://provider.example/send", "secret",
        true, 2500);

    assertTrue(config.isConfigured());
    assertEquals(2500, config.getTimeoutMs());
  }

  @Test
  public void invalidTimeoutFallsBackToDefault() {
    EmailProviderConfig config = new EmailProviderConfig("https://provider.example/send", "secret",
        true, -1);

    assertEquals(EmailProviderConfig.DEFAULT_TIMEOUT_MS, config.getTimeoutMs());
  }

  @Test
  public void runtimeConfigAcceptsEtendoTruthyFlag() {
    System.setProperty(EmailProviderConfig.PROP_BASE_URL, "https://provider.example/send");
    System.setProperty(EmailProviderConfig.PROP_API_KEY, "secret");
    System.setProperty(EmailProviderConfig.PROP_ENABLED, "Y");
    try {
      EmailProviderConfig config = EmailProviderConfig.fromRuntime();

      assertTrue(config.isEnabled());
      assertTrue(config.isConfigured());
    } finally {
      System.clearProperty(EmailProviderConfig.PROP_BASE_URL);
      System.clearProperty(EmailProviderConfig.PROP_API_KEY);
      System.clearProperty(EmailProviderConfig.PROP_ENABLED);
    }
  }
}
