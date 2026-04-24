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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyNotFoundException;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Unit tests for the per-window filter preset service.
 */
public class NeoFiltersServiceTest {

  private static final String PREF_KEY = "ETGO_WindowFilters";

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<Preferences> preferencesMock;
  private OBContext obContext;
  private Client client;
  private Organization organization;
  private User user;
  private Role role;

  @Before
  public void setUp() {
    obContextMock = mockStatic(OBContext.class);
    preferencesMock = mockStatic(Preferences.class);

    obContext = mock(OBContext.class);
    client = mock(Client.class);
    organization = mock(Organization.class);
    user = mock(User.class);
    role = mock(Role.class);

    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getCurrentOrganization()).thenReturn(organization);
    when(obContext.getUser()).thenReturn(user);
    when(obContext.getRole()).thenReturn(role);
    when(user.getId()).thenReturn("USER_ID");
  }

  @After
  public void tearDown() {
    preferencesMock.close();
    obContextMock.close();
  }

  @Test
  public void getWindowPresetsReturnsStoredWindowOnly() throws Exception {
    storedPreference("{\"sales-order\":{\"Open\":{\"status\":\"OP\"}},"
        + "\"payment-in\":{\"Pending\":{\"status\":\"PE\"}}}");

    NeoResponse response = NeoFiltersService.getWindowPresets("sales-order");

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertTrue(body.has("Open"));
    assertEquals("OP", body.getJSONObject("Open").getString("status"));
    assertFalse(body.has("Pending"));
  }

  @Test
  public void getWindowPresetsReturnsEmptyObjectWhenPreferenceDoesNotExist() throws Exception {
    preferencesMock.when(() -> Preferences.getPreferenceValue(PREF_KEY, false,
        client, organization, user, role, null)).thenThrow(new PropertyNotFoundException());

    NeoResponse response = NeoFiltersService.getWindowPresets("sales-order");

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, response.getBody().length());
  }

  @Test
  public void savePresetMergesWithExistingWindowsAndPresets() throws Exception {
    storedPreference("{\"sales-order\":{\"Open\":{\"status\":\"OP\"}},"
        + "\"payment-in\":{\"Pending\":{\"status\":\"PE\"}}}");

    NeoFiltersService.savePreset("sales-order", "Closed", "{\"status\":\"CL\",\"mine\":true}");

    preferencesMock.verify(() -> Preferences.setPreferenceValue(eq(PREF_KEY),
        org.mockito.ArgumentMatchers.argThat(raw -> {
          try {
            JSONObject stored = new JSONObject(raw);
            return "OP".equals(stored.getJSONObject("sales-order")
                .getJSONObject("Open").getString("status"))
                && "CL".equals(stored.getJSONObject("sales-order")
                    .getJSONObject("Closed").getString("status"))
                && stored.getJSONObject("sales-order")
                    .getJSONObject("Closed").getBoolean("mine")
                && "PE".equals(stored.getJSONObject("payment-in")
                    .getJSONObject("Pending").getString("status"));
          } catch (Exception e) {
            return false;
          }
        }), eq(false), same(client), same(organization), same(user),
        isNull(), isNull(), isNull()));
  }

  @Test
  public void savePresetRejectsInvalidJsonBody() throws Exception {
    storedPreference("{}");

    try {
      NeoFiltersService.savePreset("sales-order", "Broken", "{not-json");
      fail("Expected IllegalArgumentException for invalid JSON body");
    } catch (IllegalArgumentException e) {
      assertEquals("Invalid JSON body", e.getMessage());
    }
    verifyNoPreferencePersist();
  }

  @Test
  public void deletePresetRemovesOnlyRequestedPreset() throws Exception {
    storedPreference("{\"sales-order\":{\"Open\":{\"status\":\"OP\"},"
        + "\"Closed\":{\"status\":\"CL\"}}}");

    NeoFiltersService.deletePreset("sales-order", "Open");

    preferencesMock.verify(() -> Preferences.setPreferenceValue(eq(PREF_KEY),
        org.mockito.ArgumentMatchers.argThat(raw -> {
          try {
            JSONObject sales = new JSONObject(raw).getJSONObject("sales-order");
            return !sales.has("Open")
                && "CL".equals(sales.getJSONObject("Closed").getString("status"));
          } catch (Exception e) {
            return false;
          }
        }), eq(false), same(client), same(organization), same(user),
        isNull(), isNull(), isNull()));
  }

  @Test
  public void deletePresetDoesNotPersistWhenWindowDoesNotExist() throws Exception {
    storedPreference("{\"payment-in\":{\"Pending\":{\"status\":\"PE\"}}}");

    NeoFiltersService.deletePreset("sales-order", "Open");

    verifyNoPreferencePersist();
  }

  private void storedPreference(String value) throws Exception {
    preferencesMock.when(() -> Preferences.getPreferenceValue(PREF_KEY, false,
        client, organization, user, role, null)).thenReturn(value);
  }

  private void verifyNoPreferencePersist() {
    preferencesMock.verify(() -> Preferences.setPreferenceValue(anyString(), anyString(),
        anyBoolean(), any(Client.class), any(Organization.class), any(User.class),
        any(Role.class), any(Window.class), any()), never());
  }
}
