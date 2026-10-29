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

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyException;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.common.BoundedJsonParserTest;

/**
 * Tests for {@link NeoFiscalModelsCatalogService}.
 *
 * @covers com.etendoerp.go.schemaforge.NeoFiscalModelsCatalogService
 * @covers com.etendoerp.go.schemaforge.FiscalModel390Flag
 */
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
      assertEquals(0, modelCount(result.getBody()));
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
      assertEquals(0, modelCount(result.getBody()));
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

  /**
   * A body that breaks the JSON parser is the client's error: the IllegalArgumentException the
   * endpoint turns into a 400, never an {@code Error}, and nothing is stored.
   */
  @Test
  public void saveActiveModelsParserBreakingBodyThrowsIllegalArgumentException() {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class)) {
      BoundedJsonParserTest.stackBreakingBodies().filter(body -> body.length() < 100)
          .forEach(body -> {
            try {
              NeoFiscalModelsCatalogService.saveActiveModels(body);
              throw new AssertionError("expected IllegalArgumentException for " + body);
            } catch (IllegalArgumentException expected) {
              assertEquals("Invalid JSON body", expected.getMessage());
            }
          });

      ctxMock.verifyNoInteractions();
      prefMock.verifyNoInteractions();
    }
  }

  /**
   * Model entries of a catalog answer: every key but the ETP-5716 {@code _unavailable} marker,
   * which the answer carries while the {@code fiscal-model-390} flag is off (its default).
   */
  private static int modelCount(org.codehaus.jettison.json.JSONObject body) {
    return body.length() - (body.has(FiscalModel390Flag.UNAVAILABLE_KEY) ? 1 : 0);
  }

  // ---------------------------------------------------------------- fiscal-model-390 flag

  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";

  private static OBContext contextFor(Client client) {
    when(client.getId()).thenReturn(CLIENT_ID);
    OBContext obCtx = mock(OBContext.class);
    when(obCtx.getCurrentClient()).thenReturn(client);
    return obCtx;
  }

  /** The flag gate with only its evaluation stubbed; the read/write shaping stays real. */
  private static MockedStatic<FiscalModel390Flag> flag(boolean enabled) {
    MockedStatic<FiscalModel390Flag> flag =
        mockStatic(FiscalModel390Flag.class, Mockito.CALLS_REAL_METHODS);
    flag.when(() -> FiscalModel390Flag.isEnabled(Mockito.any())).thenReturn(enabled);
    return flag;
  }

  private static void storeCatalog(MockedStatic<Preferences> prefMock, Client client, String raw) {
    prefMock.when(() -> Preferences.getPreferenceValue(
        eq("ETGO_FiscalModelsCatalog"), eq(false),
        eq(client), (Organization) isNull(), (User) isNull(), (Role) isNull(), (Window) isNull()))
        .thenReturn(raw);
  }

  private static JSONObject savedCatalog(MockedStatic<Preferences> prefMock, Client client)
      throws Exception {
    ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
    prefMock.verify(() -> Preferences.setPreferenceValue(
        eq("ETGO_FiscalModelsCatalog"), saved.capture(),
        eq(false), eq(client),
        (Organization) isNull(), (User) isNull(), (Role) isNull(), (Window) isNull(),
        (VariablesSecureApp) isNull()));
    return new JSONObject(saved.getValue());
  }

  @Test
  public void getActiveModelsWithTheFlagOnReturnsTheStoredCatalogUnchanged() throws Exception {
    Client client = mock(Client.class);
    OBContext obCtx = contextFor(client);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class);
         MockedStatic<FiscalModel390Flag> flag = flag(true)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      storeCatalog(prefMock, client, "{\"303\":true,\"390\":true}");

      NeoResponse result = NeoFiscalModelsCatalogService.getActiveModels();

      assertEquals(200, result.getHttpStatus());
      assertEquals(new JSONObject("{\"303\":true,\"390\":true}").toString(),
          result.getBody().toString());
      assertFalse(result.getBody().has(FiscalModel390Flag.UNAVAILABLE_KEY));
      flag.verify(() -> FiscalModel390Flag.isEnabled(CLIENT_ID));
    }
  }

  @Test
  public void getActiveModelsWithTheFlagOffHidesAStored390AndMarksItUnavailable()
      throws Exception {
    Client client = mock(Client.class);
    OBContext obCtx = contextFor(client);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class);
         MockedStatic<FiscalModel390Flag> flag = flag(false)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      storeCatalog(prefMock, client, "{\"303\":true,\"390\":true}");

      NeoResponse result = NeoFiscalModelsCatalogService.getActiveModels();

      assertEquals(200, result.getHttpStatus());
      assertTrue(result.getBody().getBoolean("303"));
      assertFalse(result.getBody().has("390"));
      assertEquals("[\"390\"]",
          result.getBody().getJSONArray(FiscalModel390Flag.UNAVAILABLE_KEY).toString());
    }
  }

  @Test
  public void saveActiveModelsWithTheFlagOffKeepsTheStored390() throws Exception {
    Client client = mock(Client.class);
    OBContext obCtx = contextFor(client);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class);
         MockedStatic<FiscalModel390Flag> flag = flag(false)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      storeCatalog(prefMock, client, "{\"303\":false,\"390\":true}");

      NeoFiscalModelsCatalogService.saveActiveModels("{\"303\":true,\"390\":false}");

      JSONObject saved = savedCatalog(prefMock, client);
      assertTrue(saved.getBoolean("303"));
      assertTrue("the tenant's stored 390 survives a save while the flag is off",
          saved.getBoolean("390"));
      flag.verify(() -> FiscalModel390Flag.isEnabled(CLIENT_ID));
    }
  }

  @Test
  public void saveActiveModelsNeverStoresTheUnavailableMarker() throws Exception {
    for (boolean enabled : new boolean[] { true, false }) {
      Client client = mock(Client.class);
      OBContext obCtx = contextFor(client);

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<Preferences> prefMock = mockStatic(Preferences.class);
           MockedStatic<FiscalModel390Flag> flag = flag(enabled)) {
        ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
        storeCatalog(prefMock, client, "{}");

        NeoFiscalModelsCatalogService.saveActiveModels(
            "{\"303\":true,\"_unavailable\":[\"390\"]}");

        JSONObject saved = savedCatalog(prefMock, client);
        assertFalse("flag " + enabled, saved.has(FiscalModel390Flag.UNAVAILABLE_KEY));
        assertTrue(saved.getBoolean("303"));
      }
    }
  }

  /** A failure while building the answer must fail hidden, never answer a bare {}. */
  @Test
  public void getActiveModelsThatCannotBuildTheAnswerFailsHidden() throws Exception {
    Client client = mock(Client.class);
    OBContext obCtx = contextFor(client);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class);
         MockedStatic<FiscalModel390Flag> flag = flag(true)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      storeCatalog(prefMock, client, "{\"303\":true,\"390\":true}");
      flag.when(() -> FiscalModel390Flag.applyToRead(Mockito.any(), Mockito.anyBoolean()))
          .thenThrow(new IllegalStateException("boom"));

      NeoResponse result = NeoFiscalModelsCatalogService.getActiveModels();

      assertEquals(200, result.getHttpStatus());
      assertEquals(0, modelCount(result.getBody()));
      assertEquals("[\"390\"]",
          result.getBody().getJSONArray(FiscalModel390Flag.UNAVAILABLE_KEY).toString());
    }
  }

  /**
   * With the flag off, a stored catalog that cannot be read refuses the save instead of merging
   * over {} and silently dropping the stored 390.
   */
  @Test
  public void saveActiveModelsWithTheFlagOffRefusesWhenTheStoredCatalogCannotBeRead() {
    Client client = mock(Client.class);
    OBContext obCtx = contextFor(client);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class);
         MockedStatic<FiscalModel390Flag> flag = flag(false)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      prefMock.when(() -> Preferences.getPreferenceValue(
          anyString(), anyBoolean(),
          eq(client), (Organization) isNull(), (User) isNull(), (Role) isNull(), (Window) isNull()))
          .thenThrow(new PropertyException("conflict", null));

      try {
        NeoFiscalModelsCatalogService.saveActiveModels("{\"303\":true}");
        throw new AssertionError("expected the save to be refused");
      } catch (IllegalStateException expected) {
        // refused
      }
      prefMock.verify(() -> Preferences.setPreferenceValue(
          anyString(), anyString(), anyBoolean(), Mockito.any(Client.class),
          Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()),
          Mockito.never());
    }
  }

  /** With the flag on the stored 390 is not kept, so an unreadable stored catalog does not block. */
  @Test
  public void saveActiveModelsWithTheFlagOnDoesNotNeedTheStoredCatalog() throws Exception {
    Client client = mock(Client.class);
    OBContext obCtx = contextFor(client);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<Preferences> prefMock = mockStatic(Preferences.class);
         MockedStatic<FiscalModel390Flag> flag = flag(true)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      prefMock.when(() -> Preferences.getPreferenceValue(
          anyString(), anyBoolean(),
          eq(client), (Organization) isNull(), (User) isNull(), (Role) isNull(), (Window) isNull()))
          .thenThrow(new PropertyException("conflict", null));

      NeoFiscalModelsCatalogService.saveActiveModels("{\"303\":true,\"390\":true}");

      JSONObject saved = savedCatalog(prefMock, client);
      assertTrue(saved.getBoolean("390"));
    }
  }
}
