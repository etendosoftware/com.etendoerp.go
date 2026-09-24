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
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;

/**
 * ETP-5443 — the gate itself: flag {@code demo-data-transfer} is OFF unless configured, and a
 * local {@code etendo.go.flags.demo-data-transfer=true} switches it on.
 *
 * <p>{@code Openbravo.properties} is mocked empty so a developer's local override cannot leak into
 * the "unconfigured" assertion (same isolation as {@code PropertiesFeatureProviderTest}).
 */
class DemoDataTransferFlagTest {

  private static final String FLAG_PROPERTY = "etendo.go.flags.demo-data-transfer";

  private MockedStatic<OBPropertiesProvider> propertiesMock;

  @BeforeEach
  void isolateFromOpenbravoProperties() {
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    propertiesMock = mockStatic(OBPropertiesProvider.class);
    propertiesMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
  }

  @AfterEach
  void clearOverride() {
    System.clearProperty(FLAG_PROPERTY);
    propertiesMock.close();
  }

  @Test
  void isOffByDefault() {
    assertFalse(DemoDataTransferFlag.isEnabled());
  }

  @Test
  void isOnlyOnWhenConfiguredTrue() {
    System.setProperty(FLAG_PROPERTY, "false");
    assertFalse(DemoDataTransferFlag.isEnabled());
    System.setProperty(FLAG_PROPERTY, "true");
    assertTrue(DemoDataTransferFlag.isEnabled());
  }
}
