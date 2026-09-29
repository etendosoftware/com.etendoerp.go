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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Unit tests for the ETP-5473 country rule of {@link FinancialAccountHandler}: the backend now
 * enforces exactly what the SPA's account forms enforce.
 *
 * <ul>
 *   <li>Create, every type (Bank, Cash, Card): {@code country} missing, {@code null} or blank is
 *       "Country is required"; an id that does not resolve is "Invalid country". The country is
 *       never derived from the IBAN.</li>
 *   <li>Update: a body touching neither {@code iBAN} nor {@code country} skips the check (no
 *       account load). A non-blank country must resolve. Clearing the country is allowed on Cash,
 *       Card and on a Bank account whose effective IBAN is blank — the body then carries JSON
 *       {@code null} — and rejected with the IBAN-specific message on a Bank account that keeps or
 *       gets an IBAN.</li>
 * </ul>
 *
 * <p>Split out of {@link FinancialAccountHandlerTest} to keep that class manageable. Each matrix
 * case runs against a fresh spy (see {@link #newHandler()}) so {@code verify(never())} assertions
 * are scoped to a single case. Same spy-and-stub strategy as the sibling classes: the DAL-bound
 * seams ({@code loadCurrency}, {@code nameExists}, {@code loadCountry}, {@code loadAccount},
 * {@code listMatchingAlgorithms}) are stubbed so no database or live OBContext is needed.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountHandlerCountryTest {

  private static final String ACC_ID = "acc-1";
  private static final String EUR_ID = "102";
  private static final String ES_COUNTRY_ID = "106";
  private static final String UNKNOWN_COUNTRY_ID = "no-such-country";
  private static final String ES_IBAN = "ES9121000418450200051332";
  private static final String FIELD_COUNTRY = "country";
  private static final String FIELD_IBAN = "iBAN";
  private static final String FIELD_TYPE = "type";

  private static final String MSG_COUNTRY_REQUIRED = "Country is required";
  private static final String MSG_INVALID_COUNTRY = "Invalid country";
  private static final String MSG_IBAN_REQUIRES_COUNTRY = "A bank account with an IBAN must have a country.";

  private static final List<String> ALL_TYPES = Arrays.asList("B", "C", "CA");

  /** Ways a caller can leave the country out of a body: absent key, JSON null, blank strings. */
  private enum MissingCountry {
    ABSENT_KEY, JSON_NULL, EMPTY_STRING, BLANK_STRING;

    void applyTo(JSONObject body) throws Exception {
      switch (this) {
        case ABSENT_KEY:
          body.remove(FIELD_COUNTRY);
          break;
        case JSON_NULL:
          body.put(FIELD_COUNTRY, JSONObject.NULL);
          break;
        case EMPTY_STRING:
          body.put(FIELD_COUNTRY, "");
          break;
        default:
          body.put(FIELD_COUNTRY, "   ");
          break;
      }
    }
  }

  /** The explicit-clear subset of {@link MissingCountry} (the key IS present on update). */
  private static final List<MissingCountry> CLEARS =
      Arrays.asList(MissingCountry.JSON_NULL, MissingCountry.EMPTY_STRING, MissingCountry.BLANK_STRING);

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  private FinancialAccountHandler newHandler() {
    FinancialAccountHandler handler = spy(new FinancialAccountHandler());
    doNothing().when(handler).enterAdminMode();
    doNothing().when(handler).exitAdminMode();
    doNothing().when(handler).doRollbackAndClose();
    return handler;
  }

  /** A create-ready spy: currency resolves, name is free, ES_COUNTRY_ID resolves to Spain. */
  private FinancialAccountHandler newCreateHandler() {
    FinancialAccountHandler handler = newHandler();
    doReturn(mock(Currency.class)).when(handler).loadCurrency(EUR_ID);
    doReturn(false).when(handler).nameExists("BBVA", null);
    doReturn(Collections.emptyList()).when(handler).listMatchingAlgorithms();
    doReturn(spain()).when(handler).loadCountry(ES_COUNTRY_ID);
    doReturn(null).when(handler).loadCountry(UNKNOWN_COUNTRY_ID);
    return handler;
  }

  private static JSONObject createBody(String type) throws Exception {
    return new JSONObject().put("name", "BBVA").put("currency", EUR_ID).put(FIELD_TYPE, type)
        .put(FIELD_COUNTRY, ES_COUNTRY_ID);
  }

  /** Spain with the IBAN metadata that {@link #ES_IBAN} needs to pass the pair check. */
  private static Country spain() {
    Country spain = mock(Country.class);
    when(spain.getId()).thenReturn(ES_COUNTRY_ID);
    when(spain.getName()).thenReturn("Spain");
    when(spain.getIBANCode()).thenReturn("ES");
    when(spain.getIBANLength()).thenReturn(24L);
    return spain;
  }

  private static Country france() {
    Country france = mock(Country.class);
    when(france.getName()).thenReturn("France");
    when(france.getIBANCode()).thenReturn("FR");
    when(france.getIBANLength()).thenReturn(27L);
    return france;
  }

  private static FIN_FinancialAccount storedAccount(FinancialAccountHandler handler, String type,
      String iban, Country country) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getType()).thenReturn(type);
    when(account.getIBAN()).thenReturn(iban);
    when(account.getCountry()).thenReturn(country);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    return account;
  }

  private static String errorMessage(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("message");
  }

  private static void assertBadRequest(String label, String expectedMessage, NeoResponse response)
      throws Exception {
    assertNotNull(label + ": expected a 400, got pass-through", response);
    assertEquals(label, 400, response.getHttpStatus());
    assertEquals(label, expectedMessage, errorMessage(response));
  }

  // ── create ────────────────────────────────────────────────────────────────

  /** Country is mandatory on create for every type, however it is left out. */
  @Test
  public void createWithoutCountryIsRejectedForEveryType() throws Exception {
    for (String type : ALL_TYPES) {
      for (MissingCountry missing : MissingCountry.values()) {
        String label = "type=" + type + ", country=" + missing;
        FinancialAccountHandler handler = newCreateHandler();
        JSONObject body = createBody(type);
        missing.applyTo(body);

        assertBadRequest(label, MSG_COUNTRY_REQUIRED, handler.validateAndEnrichCreate(body));
        verify(handler, never()).loadCountry(any());
        verify(handler, never()).listMatchingAlgorithms();
      }
    }
  }

  /** A country id that resolves to nothing is "Invalid country" for every type. */
  @Test
  public void createWithUnresolvableCountryReturnsInvalidCountryForEveryType() throws Exception {
    for (String type : ALL_TYPES) {
      FinancialAccountHandler handler = newCreateHandler();
      JSONObject body = createBody(type).put(FIELD_COUNTRY, UNKNOWN_COUNTRY_ID);

      assertBadRequest("type=" + type, MSG_INVALID_COUNTRY, handler.validateAndEnrichCreate(body));
    }
  }

  /** A valid country is accepted for every type and written back trimmed. */
  @Test
  public void createWithValidCountryIsAcceptedForEveryTypeAndTrimsTheId() throws Exception {
    for (String type : ALL_TYPES) {
      FinancialAccountHandler handler = newCreateHandler();
      JSONObject body = createBody(type).put(FIELD_COUNTRY, "  " + ES_COUNTRY_ID + "  ");

      assertNull("type=" + type, handler.validateAndEnrichCreate(body));
      assertEquals("type=" + type, ES_COUNTRY_ID, body.getString(FIELD_COUNTRY));
    }
  }

  /**
   * A Bank create with a valid IBAN and no country is "Country is required" — and no country
   * lookup of any kind happens: not through the handler's seam and not straight against the DAL
   * (the removed IBAN-prefix derivation used to query Country via OBDal).
   */
  @Test
  public void createBankWithIbanAndNoCountryDoesNotDeriveCountry() throws Exception {
    FinancialAccountHandler handler = newCreateHandler();
    JSONObject body = createBody("B").put(FIELD_IBAN, ES_IBAN);
    body.remove(FIELD_COUNTRY);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertBadRequest("bank + IBAN, no country", MSG_COUNTRY_REQUIRED,
          handler.validateAndEnrichCreate(body));
      obDal.verifyNoInteractions();
    }
    verify(handler, never()).loadCountry(any());
    assertFalse(body.has(FIELD_COUNTRY));
  }

  /** A Bank create whose IBAN prefix does not match the chosen country gets the pair error. */
  @Test
  public void createBankWithIbanPrefixMismatchingCountryReturnsPairError() throws Exception {
    FinancialAccountHandler handler = newCreateHandler();
    doReturn(france()).when(handler).loadCountry("fr");
    JSONObject body = createBody("B").put(FIELD_COUNTRY, "fr").put(FIELD_IBAN, ES_IBAN);

    NeoResponse response = handler.validateAndEnrichCreate(body);

    assertEquals(400, response.getHttpStatus());
    String message = errorMessage(response);
    assertTrue(message, message.contains("France"));
    assertFalse("the pair error, not the missing-country one",
        MSG_IBAN_REQUIRES_COUNTRY.equals(message) || MSG_COUNTRY_REQUIRED.equals(message));
  }

  // ── update ────────────────────────────────────────────────────────────────

  /** A body touching neither iBAN nor country is not validated and never loads the account. */
  @Test
  public void updateTouchingNeitherIbanNorCountrySkipsValidationAndAccountLoad() throws Exception {
    FinancialAccountHandler handler = newHandler();
    JSONObject body = new JSONObject().put("swiftCode", "BBVAESMM")
        .put("eTGOAmountTolerance", "2.5");

    assertNull(handler.validateAndEnrichUpdate(ACC_ID, body));
    verify(handler, never()).loadAccount(any());
    verify(handler, never()).loadCountry(any());
    assertFalse(body.has(FIELD_COUNTRY));
  }

  /**
   * Clearing the country is allowed on Cash and Card — even when they carry a stale IBAN — and on
   * a Bank account with no IBAN stored (null or blank). The body ends with JSON null.
   */
  @Test
  public void updateClearingCountryIsAllowedWhereTheUiAllowsIt() throws Exception {
    Object[][] accounts = {
        { "C", null }, { "C", ES_IBAN }, { "CA", null }, { "CA", ES_IBAN }, { "B", null }, { "B", "  " },
    };
    for (Object[] account : accounts) {
      for (MissingCountry clear : CLEARS) {
        String type = (String) account[0];
        String storedIban = (String) account[1];
        String label = "type=" + type + ", storedIban=" + storedIban + ", clear=" + clear;
        FinancialAccountHandler handler = newHandler();
        storedAccount(handler, type, storedIban, spain());
        JSONObject body = new JSONObject();
        clear.applyTo(body);

        assertNull(label, handler.validateAndEnrichUpdate(ACC_ID, body));
        assertTrue(label + ": country must be a JSON null", body.isNull(FIELD_COUNTRY));
        verify(handler, never()).loadCountry(any());
      }
    }
  }

  /** Clearing the country on a Bank account that keeps its stored IBAN is the IBAN-specific 400. */
  @Test
  public void updateClearingCountryOnBankWithStoredIbanReturnsIbanError() throws Exception {
    for (MissingCountry clear : CLEARS) {
      FinancialAccountHandler handler = newHandler();
      storedAccount(handler, "B", ES_IBAN, spain());
      JSONObject body = new JSONObject();
      clear.applyTo(body);

      assertBadRequest("clear=" + clear, MSG_IBAN_REQUIRES_COUNTRY,
          handler.validateAndEnrichUpdate(ACC_ID, body));
    }
  }

  /** Clearing the country AND the IBAN of a Bank account in the same body is allowed. */
  @Test
  public void updateClearingCountryAndIbanTogetherOnBankIsAllowed() throws Exception {
    FinancialAccountHandler handler = newHandler();
    storedAccount(handler, "B", ES_IBAN, spain());
    JSONObject body = new JSONObject().put(FIELD_COUNTRY, JSONObject.NULL).put(FIELD_IBAN, JSONObject.NULL);

    assertNull(handler.validateAndEnrichUpdate(ACC_ID, body));
    assertTrue(body.isNull(FIELD_COUNTRY));
  }

  /**
   * Adding an IBAN to a Bank account with no stored country is the IBAN-specific 400 — whether the
   * body omits the country or explicitly clears it. No country is derived from the IBAN.
   */
  @Test
  public void updateAddingIbanToBankWithoutCountryReturnsIbanError() throws Exception {
    List<MissingCountry> variants = Arrays.asList(MissingCountry.ABSENT_KEY, MissingCountry.JSON_NULL,
        MissingCountry.BLANK_STRING);
    for (MissingCountry missing : variants) {
      FinancialAccountHandler handler = newHandler();
      storedAccount(handler, "B", null, null);
      JSONObject body = new JSONObject().put(FIELD_IBAN, ES_IBAN);
      missing.applyTo(body);

      assertBadRequest("country=" + missing, MSG_IBAN_REQUIRES_COUNTRY,
          handler.validateAndEnrichUpdate(ACC_ID, body));
      verify(handler, never()).loadCountry(any());
    }
  }

  /** Adding an IBAN together with a matching country to a country-less Bank account passes. */
  @Test
  public void updateAddingIbanWithMatchingCountryIsAccepted() throws Exception {
    FinancialAccountHandler handler = newHandler();
    storedAccount(handler, "B", null, null);
    doReturn(spain()).when(handler).loadCountry(ES_COUNTRY_ID);
    JSONObject body = new JSONObject().put(FIELD_IBAN, "es91 2100 0418 4502 0005 1332")
        .put(FIELD_COUNTRY, ES_COUNTRY_ID);

    assertNull(handler.validateAndEnrichUpdate(ACC_ID, body));
    assertEquals(ES_COUNTRY_ID, body.getString(FIELD_COUNTRY));
    assertEquals(ES_IBAN, body.getString(FIELD_IBAN));
  }

  /** A non-blank country that does not resolve is "Invalid country" on update, for every type. */
  @Test
  public void updateWithUnresolvableCountryReturnsInvalidCountryForEveryType() throws Exception {
    for (String type : ALL_TYPES) {
      FinancialAccountHandler handler = newHandler();
      storedAccount(handler, type, null, spain());
      doReturn(null).when(handler).loadCountry(UNKNOWN_COUNTRY_ID);
      JSONObject body = new JSONObject().put(FIELD_COUNTRY, UNKNOWN_COUNTRY_ID);

      assertBadRequest("type=" + type, MSG_INVALID_COUNTRY,
          handler.validateAndEnrichUpdate(ACC_ID, body));
    }
  }
}
