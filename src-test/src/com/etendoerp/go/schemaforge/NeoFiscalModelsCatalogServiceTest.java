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
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyException;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

/** Tests for {@link NeoFiscalModelsCatalogService}. */
public class NeoFiscalModelsCatalogServiceTest {

  @Test
  public void getActiveModelsReturnsStoredValueScopedToClientOnly() throws Exception {
    Client client = mock(Client.class);

    OBContext obCtx = mock(OBContext.class);
    when(obCtx.getCurrentClient()).thenReturn(client);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      prefMock.when(() -> Preferences.getPreferenceValue(
          eq("ETGO_FiscalModelsCatalog"), eq(false),
          eq(client), (Organization) isNull(), (User) isNull(), (Role) isNull(), (Window) isNull()))
          .thenReturn("{\"303\":true,\"349\":false}");

      NeoResponse result = NeoFiscalModelsCatalogService.getActiveModels();

      assertEquals(200, result.getHttpStatus());
      assertTrue(result.getBody().getBoolean("303"));
      assertFalse(result.getBody().getBoolean("349"));
    }
  }

  @Test
  public void getActiveModelsReturnsEmptyObjectWhenNothingStoredYet() throws Exception {
    Client client = mock(Client.class);

    OBContext obCtx = mock(OBContext.class);
    when(obCtx.getCurrentClient()).thenReturn(client);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      prefMock.when(() -> Preferences.getPreferenceValue(
          anyString(), anyBoolean(),
          eq(client), (Organization) isNull(), (User) isNull(), (Role) isNull(), (Window) isNull()))
          .thenThrow(new org.openbravo.erpCommon.utility.PropertyNotFoundException());

      NeoResponse result = NeoFiscalModelsCatalogService.getActiveModels();

      assertEquals(200, result.getHttpStatus());
      assertEquals(0, result.getBody().length());
    }
  }

  @Test
  public void getActiveModelsPropertyExceptionReturnsEmptyObject() throws Exception {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");

    OBContext obCtx = mock(OBContext.class);
    when(obCtx.getCurrentClient()).thenReturn(client);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      prefMock.when(() -> Preferences.getPreferenceValue(
          anyString(), anyBoolean(),
          eq(client), (Organization) isNull(), (User) isNull(), (Role) isNull(), (Window) isNull()))
          .thenThrow(new PropertyException("conflict", null));

      NeoResponse result = NeoFiscalModelsCatalogService.getActiveModels();

      assertEquals(200, result.getHttpStatus());
      assertEquals(0, result.getBody().length());
    }
  }

  @Test
  public void saveActiveModelsDelegatesToPreferencesWithClientOnlyScope() {
    Client client = mock(Client.class);

    OBContext obCtx = mock(OBContext.class);
    when(obCtx.getCurrentClient()).thenReturn(client);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);

      NeoFiscalModelsCatalogService.saveActiveModels("{\"303\":true,\"349\":false}");

      prefMock.verify(() -> Preferences.setPreferenceValue(
          eq("ETGO_FiscalModelsCatalog"), eq("{\"303\":true,\"349\":false}"),
          eq(false), eq(client),
          (Organization) isNull(), (User) isNull(), (Role) isNull(), (Window) isNull(),
          (VariablesSecureApp) isNull()));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void saveActiveModelsInvalidJsonThrowsIllegalArgumentException() {
    NeoFiscalModelsCatalogService.saveActiveModels("not-json");
  }
}
