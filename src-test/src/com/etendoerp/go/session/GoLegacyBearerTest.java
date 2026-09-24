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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/** Unit tests for {@link GoLegacyBearer} (ETP-4575, 4b). */
public class GoLegacyBearerTest {

  private static final String PROPERTY = "etgo.legacy.bearer.enabled";

  @After
  public void cleanup() {
    System.clearProperty(PROPERTY);
    GoLegacyBearer.resetUseCount();
  }

  @Test
  public void disabledWhenPropertyIsFalse() {
    System.setProperty(PROPERTY, "false");
    assertFalse(GoLegacyBearer.isEnabled());
  }

  @Test
  public void enabledWhenPropertyIsTrue() {
    System.setProperty(PROPERTY, "true");
    assertTrue(GoLegacyBearer.isEnabled());
  }

  @Test
  public void recordUseIncrementsCounter() {
    GoLegacyBearer.resetUseCount();
    GoLegacyBearer.recordUse();
    GoLegacyBearer.recordUse();
    assertEquals(2L, GoLegacyBearer.useCount());
  }
}
