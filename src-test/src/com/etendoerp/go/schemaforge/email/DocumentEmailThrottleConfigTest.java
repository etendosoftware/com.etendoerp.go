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
import static org.mockito.Mockito.mockStatic;

import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.common.ConfigPropertyReader;

/**
 * Unit tests for the per-environment throttle ceilings of the document-send family (ETP-5003).
 */
public class DocumentEmailThrottleConfigTest {

  private static final String PROP = DefaultDocumentSendEmailContract.PROP_MAX_PER_RECORD;
  private static final String ENV = DefaultDocumentSendEmailContract.ENV_MAX_PER_RECORD;

  @After
  public void clearOverride() {
    System.clearProperty(PROP);
  }

  @Test
  public void usesTheProductionCeilingWhenNothingOverridesIt() {
    // The reader is stubbed rather than trusted to come back empty. A developer machine
    // legitimately carries these ceilings in gradle.properties — that is the entire point of the
    // feature — so reading the real configuration would assert against whatever that developer
    // happens to have configured, and the test would fail on exactly the machines that use it.
    try (MockedStatic<ConfigPropertyReader> reader = mockStatic(ConfigPropertyReader.class)) {
      reader.when(() -> ConfigPropertyReader.readConfigValue(PROP, ENV, null)).thenReturn(null);

      assertEquals(DefaultDocumentSendEmailContract.DEFAULT_MAX_PER_RECORD,
          DefaultDocumentSendEmailContract.maxAttempts(PROP, ENV,
              DefaultDocumentSendEmailContract.DEFAULT_MAX_PER_RECORD));
    }
  }

  @Test
  public void honoursAConfiguredCeiling() {
    System.setProperty(PROP, "500");

    assertEquals(500, DefaultDocumentSendEmailContract.maxAttempts(PROP, ENV,
        DefaultDocumentSendEmailContract.DEFAULT_MAX_PER_RECORD));
  }

  @Test
  public void toleratesSurroundingWhitespace() {
    System.setProperty(PROP, "  42  ");

    assertEquals(42, DefaultDocumentSendEmailContract.maxAttempts(PROP, ENV,
        DefaultDocumentSendEmailContract.DEFAULT_MAX_PER_RECORD));
  }

  @Test
  public void ignoresAnOverrideThatWouldTightenTheLimitToNothing() {
    // A typo parsing as 0 or a negative would clamp to one attempt per hour inside
    // EmailThrottleRule and read as the email system being broken, so it is refused.
    System.setProperty(PROP, "0");
    assertEquals(3, DefaultDocumentSendEmailContract.maxAttempts(PROP, ENV, 3));

    System.setProperty(PROP, "-5");
    assertEquals(3, DefaultDocumentSendEmailContract.maxAttempts(PROP, ENV, 3));
  }

  @Test
  public void ignoresANonNumericOverride() {
    System.setProperty(PROP, "muchos");

    assertEquals(3, DefaultDocumentSendEmailContract.maxAttempts(PROP, ENV, 3));
  }
}
