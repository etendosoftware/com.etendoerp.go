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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyException;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

/** Tests for {@link NeoFavoritesService}. */
public class NeoFavoritesServiceTest {

  @Test
  public void getFavoritesJsonReturnsStoredValue() throws Exception {
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    User user = mock(User.class);
    Role role = mock(Role.class);

    OBContext obCtx = mock(OBContext.class);
    when(obCtx.getCurrentClient()).thenReturn(client);
    when(obCtx.getCurrentOrganization()).thenReturn(org);
    when(obCtx.getUser()).thenReturn(user);
    when(obCtx.getRole()).thenReturn(role);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      prefMock.when(() -> Preferences.getPreferenceValue(
          eq("ETGO_NavigatorFavorites"), eq(false),
          any(Client.class), any(Organization.class),
          any(User.class), any(Role.class), (Window) isNull()))
          .thenReturn("[{\"name\":\"sales-order\"}]");

      String result = NeoFavoritesService.getFavoritesJson();
      assertEquals("[{\"name\":\"sales-order\"}]", result);
    }
  }

  @Test
  public void getFavoritesJsonPropertyExceptionReturnsEmptyArray() throws Exception {
    User user = mock(User.class);
    when(user.getId()).thenReturn("user-1");

    OBContext obCtx = mock(OBContext.class);
    when(obCtx.getCurrentClient()).thenReturn(mock(Client.class));
    when(obCtx.getCurrentOrganization()).thenReturn(mock(Organization.class));
    when(obCtx.getUser()).thenReturn(user);
    when(obCtx.getRole()).thenReturn(mock(Role.class));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      prefMock.when(() -> Preferences.getPreferenceValue(
          anyString(), anyBoolean(),
          any(Client.class), any(Organization.class),
          any(User.class), any(Role.class), (Window) isNull()))
          .thenThrow(new PropertyException("conflict", null));

      String result = NeoFavoritesService.getFavoritesJson();
      assertEquals("[]", result);
    }
  }

  @Test
  public void saveFavoritesJsonDelegatesToPreferences() {
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    User user = mock(User.class);

    OBContext obCtx = mock(OBContext.class);
    when(obCtx.getCurrentClient()).thenReturn(client);
    when(obCtx.getCurrentOrganization()).thenReturn(org);
    when(obCtx.getUser()).thenReturn(user);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);

      NeoFavoritesService.saveFavoritesJson("[{\"name\":\"test\"}]");

      prefMock.verify(() -> Preferences.setPreferenceValue(
          eq("ETGO_NavigatorFavorites"), eq("[{\"name\":\"test\"}]"),
          eq(false), eq(client), eq(org), eq(user), isNull(), isNull(), isNull()));
    }
  }
}
