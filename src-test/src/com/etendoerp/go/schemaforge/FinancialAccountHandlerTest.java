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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.financialmgmt.accounting.FIN_FinancialAccountAccounting;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.model.financialmgmt.payment.MatchingAlgorithm;

import com.etendoerp.go.schemaforge.data.MatchRule;
import com.etendoerp.psd2.bank.integration.data.FinaccConnection;
import com.etendoerp.psd2.bank.integration.data.PSD2FinaccLog;

/**
 * Mockito-driven unit tests for {@link FinancialAccountHandler} (ETP-4239).
 *
 * <p>The handler is a W-spec pre/post hook: on POST/PUT/PATCH it validates and
 * <b>mutates the request body</b> (normalized {@code type}, {@code country}
 * derived from the IBAN, default {@code matchingAlgorithm}) and returns
 * {@code null} so the generic CRUD persists; on DELETE it short-circuits with a
 * soft-archive. Strategy: spy the handler and stub the package-private DAL seams
 * ({@code loadCurrency}, {@code loadAccount}, {@code nameExists},
 * {@code hasOpenReconciliations}, {@code resolveCountryFromIban},
 * {@code listMatchingAlgorithms}) so every path runs without a database or a
 * live OBContext. The {@code handle()} routing path is exercised through a
 * mocked {@link NeoContext}.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Routing: foreign spec / GET → null passthrough; POST/PUT/DELETE dispatch.</li>
 *   <li>create: happy → null + body enriched (type, country-from-IBAN,
 *       matchingAlgorithm); blank/too-long name, blank/invalid currency,
 *       too-long IBAN/BIC → 400; duplicate name → 409.</li>
 *   <li>update: name uniqueness (excluding self) → 409; IBAN→country sync;
 *       missing-name body passes through.</li>
 *   <li>delete: soft-archive → 204 + setActive(false); open reconciliations →
 *       409; missing id / unknown account → 400.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountHandlerTest {

  private static final String SPEC = "financial-account";
  private static final String CLIENT_ID = "23C59575B9CF467C9620760EB255B389";
  private static final String EUR_ID = "102";
  private static final String ACC_ID = "acc-1";
  private static final String ES_IBAN = "ES9121000418450200051332";

  private FinancialAccountHandler handler;

  /**
   * Initializes a Mockito spy of the handler before each test so individual
   * methods can stub the DB-bound seams without touching the real DAL layer.
   */
  @Before
  public void setUp() {
    handler = spy(new FinancialAccountHandler());
    // Stub the OBContext/OBDal seams so tests run without a live Etendo
    // session (CI has no initialized OBContext on the thread).
    org.mockito.Mockito.doNothing().when(handler).enterAdminMode();
    org.mockito.Mockito.doNothing().when(handler).exitAdminMode();
    org.mockito.Mockito.doNothing().when(handler).doRollbackAndClose();
  }

  /**
   * Clears Mockito's inline mock cache after each test. The seam tests below use
   * {@code MockedStatic}, whose inline mocks otherwise accumulate across the
   * whole module suite (a single test JVM) and push the fork past its heap
   * limit. Clearing keeps the heap flat without touching the build config.
   */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  private NeoContext contextFor(String method, JSONObject body, String recordId) {
    NeoContext context = mock(NeoContext.class);
    when(context.getSpecName()).thenReturn(SPEC);
    when(context.getHttpMethod()).thenReturn(method);
    when(context.getRequestBody()).thenReturn(body);
    when(context.getRecordId()).thenReturn(recordId);
    return context;
  }

  private JSONObject validCreateBody() throws Exception {
    return new JSONObject().put("name", "BBVA").put("currency", EUR_ID);
  }

  private void stubValidCreate() {
    doReturn(mock(Currency.class)).when(handler).loadCurrency(EUR_ID);
    doReturn(false).when(handler).nameExists("BBVA", null);
    doReturn(Collections.emptyList()).when(handler).listMatchingAlgorithms();
  }

  /**
   * Spain, fully configured for IBAN validation (ETP-4896): {@code getIBANCode()="ES"},
   * {@code getIBANLength()=24}, matching the real {@link #ES_IBAN} fixture. A bare
   * {@code mock(Country.class)} with only {@code getId()} stubbed fails
   * {@code FinancialAccountCountrySupport#validateIbanCountryPair} at the "no IBAN
   * configuration" branch, since every other getter defaults to {@code null} — this is the
   * fixture every IBAN-success test needs.
   */
  private Country stubSpainWithIbanMeta() {
    Country spain = mock(Country.class);
    when(spain.getId()).thenReturn("106");
    when(spain.getName()).thenReturn("Spain");
    when(spain.getIBANCode()).thenReturn("ES");
    when(spain.getIBANLength()).thenReturn(24L);
    return spain;
  }

  /**
   * Stubs {@code loadAccount(ACC_ID)} to return a bare Bank-type account fixture (ETP-4896): the
   * country/IBAN validation on update loads the persisted record lazily, only when the body
   * touches {@code iBAN} or {@code country} — this fixture is what those tests need instead of a
   * live DAL call.
   */
  private FIN_FinancialAccount stubStoredBankAccount(String iban, Country country) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getType()).thenReturn("B");
    when(account.getIBAN()).thenReturn(iban);
    when(account.getCountry()).thenReturn(country);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    return account;
  }

  // ── handle() routing ─────────────────────────────────────────────────────

  /** A context for another spec must pass through untouched (null). */
  @Test
  public void testHandleForeignSpecReturnsNull() {
    NeoContext context = mock(NeoContext.class);
    when(context.getSpecName()).thenReturn("sales-order");

    assertNull(handler.handle(context));
    verify(handler, never()).enterAdminMode();
  }

  /** GET (list / getById) flows straight through to the generic service. */
  @Test
  public void testHandleGetReturnsNull() {
    assertNull(handler.handle(contextFor("GET", null, null)));
  }

  /** POST routes to the create validation/enrichment path. */
  @Test
  public void testHandlePostRoutesToCreateValidation() throws Exception {
    JSONObject body = validCreateBody();
    stubValidCreate();

    assertNull(handler.handle(contextFor("POST", body, null)));
    verify(handler).validateAndEnrichCreate(body);
  }

  /** PUT routes to the update validation path with the record id. */
  @Test
  public void testHandlePutRoutesToUpdateValidation() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA");
    doReturn(false).when(handler).nameExists("BBVA", ACC_ID);

    assertNull(handler.handle(contextFor("PUT", body, ACC_ID)));
    verify(handler).validateAndEnrichUpdate(ACC_ID, body);
  }

  /** DELETE routes to the hard-delete path and short-circuits (ETP-4871). */
  @Test
  public void testHandleDeleteRoutesToDeleteAccount() {
    NeoResponse expected = NeoResponse.noContent();
    doReturn(expected).when(handler).deleteAccount(ACC_ID);

    NeoResponse response = handler.handle(contextFor("DELETE", null, ACC_ID));

    assertSame(expected, response);
    verify(handler).deleteAccount(ACC_ID);
  }

  /** An unexpected runtime failure is translated to a 500 with rollback. */
  @Test
  public void testHandleTranslatesRuntimeExceptionTo500() throws Exception {
    JSONObject body = validCreateBody();
    doReturn(mock(Currency.class)).when(handler).loadCurrency(EUR_ID);
    // doThrow(...).when(spy) — NOT when(spy.method()).thenThrow — so the real nameExists
    // (which hits OBDal/OBContext) is never invoked during stubbing.
    doThrow(new RuntimeException("boom")).when(handler).nameExists("BBVA", null);

    NeoResponse response = handler.handle(contextFor("POST", body, null));

    assertEquals(500, response.getHttpStatus());
    verify(handler).doRollbackAndClose();
  }

  // ── create: validation ───────────────────────────────────────────────────

  /** A missing body is rejected with a 400. */
  @Test
  public void testCreateNullBodyReturns400() throws Exception {
    NeoResponse response = handler.validateAndEnrichCreate(null);
    assertEquals(400, response.getHttpStatus());
  }

  /** A blank name is rejected with a 400. */
  @Test
  public void testCreateBlankNameReturns400() throws Exception {
    JSONObject body = new JSONObject().put("name", "  ").put("currency", EUR_ID);
    assertEquals(400, handler.validateAndEnrichCreate(body).getHttpStatus());
  }

  /** A name longer than 60 chars is rejected with a 400. */
  @Test
  public void testCreateNameTooLongReturns400() throws Exception {
    String longName = new String(new char[61]).replace('\0', 'x');
    JSONObject body = new JSONObject().put("name", longName).put("currency", EUR_ID);
    assertEquals(400, handler.validateAndEnrichCreate(body).getHttpStatus());
  }

  /** A missing currency is rejected with a 400. */
  @Test
  public void testCreateBlankCurrencyReturns400() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA");
    assertEquals(400, handler.validateAndEnrichCreate(body).getHttpStatus());
  }

  /** A currency id that resolves to no Currency is rejected with a 400. */
  @Test
  public void testCreateInvalidCurrencyReturns400() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA").put("currency", "bad");
    doReturn(null).when(handler).loadCurrency("bad");
    assertEquals(400, handler.validateAndEnrichCreate(body).getHttpStatus());
  }

  /** An IBAN longer than 34 chars is rejected with a 400. */
  @Test
  public void testCreateIbanTooLongReturns400() throws Exception {
    String longIban = new String(new char[35]).replace('\0', '9');
    JSONObject body = validCreateBody().put("iBAN", longIban);
    assertEquals(400, handler.validateAndEnrichCreate(body).getHttpStatus());
  }

  /** A BIC/SWIFT longer than 20 chars is rejected with a 400. */
  @Test
  public void testCreateSwiftTooLongReturns400() throws Exception {
    String longSwift = new String(new char[21]).replace('\0', 'B');
    JSONObject body = validCreateBody().put("swiftCode", longSwift);
    assertEquals(400, handler.validateAndEnrichCreate(body).getHttpStatus());
  }

  /** A duplicate active name within the organization is rejected with a 409. */
  @Test
  public void testCreateDuplicateNameReturns409() throws Exception {
    JSONObject body = validCreateBody();
    doReturn(mock(Currency.class)).when(handler).loadCurrency(EUR_ID);
    doReturn(true).when(handler).nameExists("BBVA", null);
    assertEquals(409, handler.validateAndEnrichCreate(body).getHttpStatus());
  }

  // ── create: enrichment (the hook mutates the body, persist is generic) ────

  /** A valid create returns null (pass through) and normalizes the type. */
  @Test
  public void testCreateHappyReturnsNullAndDefaultsTypeToBank() throws Exception {
    JSONObject body = validCreateBody();
    stubValidCreate();

    assertNull(handler.validateAndEnrichCreate(body));
    assertEquals("B", body.getString("type"));
  }

  /** A cash account keeps its explicit type after normalization. */
  @Test
  public void testCreateCashAccountKeepsTypeC() throws Exception {
    JSONObject body = validCreateBody().put("type", "C");
    stubValidCreate();

    assertNull(handler.validateAndEnrichCreate(body));
    assertEquals("C", body.getString("type"));
  }

  /** An unknown type code falls back to Bank. */
  @Test
  public void testCreateUnknownTypeFallsBackToBank() throws Exception {
    JSONObject body = validCreateBody().put("type", "ZZ");
    stubValidCreate();

    assertNull(handler.validateAndEnrichCreate(body));
    assertEquals("B", body.getString("type"));
  }

  /**
   * When the body carries an IBAN but no country, the country derived from the IBAN's ISO prefix
   * is injected into the body BEFORE the generic insert — the row-level trigger
   * FIN_FINANCIAL_ACCOUNT_TRG2 rejects a bank account with an IBAN but no country (ETP-4896).
   */
  @Test
  public void testCreateWithIbanInjectsCountry() throws Exception {
    JSONObject body = validCreateBody().put("iBAN", ES_IBAN);
    stubValidCreate();
    Country spain = stubSpainWithIbanMeta();
    doReturn(spain).when(handler).resolveCountryFromIban(ES_IBAN);

    assertNull(handler.validateAndEnrichCreate(body));
    assertEquals("106", body.getString("country"));
  }

  /**
   * An IBAN whose prefix matches no active country is now rejected with a 400 (ETP-4896) instead
   * of silently creating an inconsistent record — which used to reach the generic insert and let
   * FIN_FINANCIAL_ACCOUNT_TRG2 raise @COUNTRY_IBAN@, flattened by NeoErrorSanitizer into a raw 500.
   */
  @Test
  public void testCreateWithUnknownIbanPrefixReturns400() throws Exception {
    String unknownPrefixIban = "XX0012345678901";
    JSONObject body = validCreateBody().put("iBAN", unknownPrefixIban);
    stubValidCreate();
    doReturn(null).when(handler).resolveCountryFromIban(unknownPrefixIban);

    NeoResponse response = handler.validateAndEnrichCreate(body);

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("must have a country"));
    assertFalse(body.has("country"));
  }

  /**
   * A country present in the body wins over IBAN-derivation (ETP-4896 requirement 2): the SPA's
   * picker is authoritative, so resolveCountryFromIban must not even be consulted.
   */
  @Test
  public void testCreateWithBodyCountryWinsOverIbanDerivation() throws Exception {
    JSONObject body = validCreateBody().put("iBAN", ES_IBAN).put("country", "106");
    stubValidCreate();
    doReturn(stubSpainWithIbanMeta()).when(handler).loadCountry("106");

    assertNull(handler.validateAndEnrichCreate(body));
    assertEquals("106", body.getString("country"));
    verify(handler, never()).resolveCountryFromIban(any());
  }

  /** An inconsistent (IBAN, country) pair is rejected with the country-specific message, not a
   *  generic checksum failure — see FinancialAccountCountrySupport#validateIbanCountryPair. */
  @Test
  public void testCreateWithMismatchedCountryReturns400() throws Exception {
    JSONObject body = validCreateBody().put("iBAN", ES_IBAN).put("country", "italy-id");
    stubValidCreate();
    Country italy = mock(Country.class);
    when(italy.getName()).thenReturn("Italy");
    when(italy.getIBANCode()).thenReturn("IT");
    when(italy.getIBANLength()).thenReturn(27L);
    doReturn(italy).when(handler).loadCountry("italy-id");

    NeoResponse response = handler.validateAndEnrichCreate(body);

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("Italy"));
    // The rejected body is left exactly as the caller sent it. Unlike the sibling
    // testCreateWithUnknownIbanPrefixReturns400 — where the body carries no country at all, so
    // "not injected" is the property to assert — here the caller DID send one, so the property is
    // that the 400 path writes nothing: validateCountryAndIban's body.put calls all sit after the
    // pair check, so neither the country nor the IBAN is normalized on the way out.
    assertEquals("italy-id", body.getString("country"));
    assertEquals(ES_IBAN, body.getString("iBAN"));
  }

  /** A Cash account carrying a stale/irrelevant IBAN skips country validation entirely. */
  @Test
  public void testCreateCashAccountWithIbanSkipsCountryValidation() throws Exception {
    JSONObject body = validCreateBody().put("type", "C").put("iBAN", "not-a-real-iban");
    stubValidCreate();

    assertNull(handler.validateAndEnrichCreate(body));
    assertFalse(body.has("country"));
    verify(handler, never()).resolveCountryFromIban(any());
  }

  /** The IBAN persisted is the normalized form, regardless of how it was typed. */
  @Test
  public void testCreateNormalizesIbanBeforePersisting() throws Exception {
    JSONObject body = validCreateBody().put("iBAN", "es91 2100 0418 4502 0005 1332");
    stubValidCreate();
    doReturn(stubSpainWithIbanMeta()).when(handler).resolveCountryFromIban(ES_IBAN);

    assertNull(handler.validateAndEnrichCreate(body));
    assertEquals(ES_IBAN, body.getString("iBAN"));
  }

  /** When no algorithm is provided, the first active one is injected. */
  @Test
  public void testCreateInjectsDefaultMatchingAlgorithm() throws Exception {
    JSONObject body = validCreateBody();
    doReturn(mock(Currency.class)).when(handler).loadCurrency(EUR_ID);
    doReturn(false).when(handler).nameExists("BBVA", null);
    MatchingAlgorithm algorithm = mock(MatchingAlgorithm.class);
    when(algorithm.getId()).thenReturn("alg-1");
    doReturn(Arrays.asList(algorithm)).when(handler).listMatchingAlgorithms();

    assertNull(handler.validateAndEnrichCreate(body));
    assertEquals("alg-1", body.getString("matchingAlgorithm"));
  }

  /** A caller-provided algorithm is never overridden by the default. */
  @Test
  public void testCreateKeepsCallerMatchingAlgorithm() throws Exception {
    JSONObject body = validCreateBody().put("matchingAlgorithm", "alg-mine");
    doReturn(mock(Currency.class)).when(handler).loadCurrency(EUR_ID);
    doReturn(false).when(handler).nameExists("BBVA", null);

    assertNull(handler.validateAndEnrichCreate(body));
    assertEquals("alg-mine", body.getString("matchingAlgorithm"));
    verify(handler, never()).listMatchingAlgorithms();
  }

  // ── update: validation + IBAN→country sync ───────────────────────────────

  /** A null body passes through (nothing to validate or enrich). */
  @Test
  public void testUpdateNullBodyReturnsNull() throws Exception {
    assertNull(handler.validateAndEnrichUpdate(ACC_ID, null));
  }

  /** A body without a name key skips the name validation entirely. */
  @Test
  public void testUpdateWithoutNameKeySkipsNameChecks() throws Exception {
    JSONObject body = new JSONObject().put("swiftCode", "BBVAESMM");

    assertNull(handler.validateAndEnrichUpdate(ACC_ID, body));
    verify(handler, never()).nameExists(any(), any());
  }

  /** A blank name on update is rejected with a 400. */
  @Test
  public void testUpdateBlankNameReturns400() throws Exception {
    JSONObject body = new JSONObject().put("name", "  ");
    assertEquals(400, handler.validateAndEnrichUpdate(ACC_ID, body).getHttpStatus());
  }

  /** A duplicate name (excluding the record itself) is rejected with a 409. */
  @Test
  public void testUpdateDuplicateNameReturns409() throws Exception {
    JSONObject body = new JSONObject().put("name", "Taken");
    doReturn(true).when(handler).nameExists("Taken", ACC_ID);
    assertEquals(409, handler.validateAndEnrichUpdate(ACC_ID, body).getHttpStatus());
  }

  /**
   * An IBAN sent on update, with no stored country and no country in the body, re-syncs the
   * derived country into the body (ETP-4896: fallback behavior, preserved for old API/MCP
   * callers that only ever send an IBAN).
   */
  @Test
  public void testUpdateWithIbanSyncsCountry() throws Exception {
    JSONObject body = new JSONObject().put("iBAN", ES_IBAN);
    stubStoredBankAccount(null, null);
    Country spain = stubSpainWithIbanMeta();
    doReturn(spain).when(handler).resolveCountryFromIban(ES_IBAN);

    assertNull(handler.validateAndEnrichUpdate(ACC_ID, body));
    assertEquals("106", body.getString("country"));
  }

  /** A too-long IBAN on update is rejected with a 400 before the account is ever loaded. */
  @Test
  public void testUpdateIbanTooLongReturns400() throws Exception {
    String longIban = new String(new char[35]).replace('\0', '9');
    JSONObject body = new JSONObject().put("iBAN", longIban);
    assertEquals(400, handler.validateAndEnrichUpdate(ACC_ID, body).getHttpStatus());
    verify(handler, never()).loadAccount(any());
  }

  /**
   * A stored IBAN already exists; changing ONLY the country must validate the pair against that
   * stored IBAN — this is the scenario a user hits directly: pick a different country for an
   * account that already has an IBAN, and the pair must still make sense (ETP-4896).
   */
  @Test
  public void testUpdateCountryOnlyValidatesAgainstStoredIban() throws Exception {
    JSONObject body = new JSONObject().put("country", "106");
    stubStoredBankAccount(ES_IBAN, null);
    doReturn(stubSpainWithIbanMeta()).when(handler).loadCountry("106");

    assertNull(handler.validateAndEnrichUpdate(ACC_ID, body));
    assertEquals("106", body.getString("country"));
  }

  /**
   * Changing the country to one inconsistent with the ALREADY-STORED IBAN is rejected — the exact
   * case a user needs surfaced with a real message instead of the raw 500 NeoErrorSanitizer would
   * otherwise produce from FIN_FINANCIAL_ACCOUNT_TRG2's @20259@.
   */
  @Test
  public void testUpdateCountryMismatchedWithStoredIbanReturns400() throws Exception {
    JSONObject body = new JSONObject().put("country", "france-id");
    stubStoredBankAccount(ES_IBAN, null);
    Country france = mock(Country.class);
    when(france.getName()).thenReturn("France");
    when(france.getIBANCode()).thenReturn("FR");
    when(france.getIBANLength()).thenReturn(27L);
    doReturn(france).when(handler).loadCountry("france-id");

    NeoResponse response = handler.validateAndEnrichUpdate(ACC_ID, body);

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("France"));
  }

  /** Neither IBAN nor country touched: a no-op, mirroring the trigger's own re-validate guard —
   *  the account must not even be loaded for an unrelated edit (e.g. a rename). */
  @Test
  public void testUpdateNeitherIbanNorCountryTouchedIsNoOp() throws Exception {
    JSONObject body = new JSONObject().put("name", "New Name");
    doReturn(false).when(handler).nameExists("New Name", ACC_ID);

    assertNull(handler.validateAndEnrichUpdate(ACC_ID, body));
    verify(handler, never()).loadAccount(any());
  }

  /**
   * PATCH {"iBAN": null} (clearing the IBAN) must not be treated as a real, non-blank IBAN —
   * regression guard for the bug where optString() on a JSON null yielded the literal "null"
   * string (ETP-4896).
   */
  @Test
  public void testUpdateClearingIbanIsNotTreatedAsNonBlank() throws Exception {
    JSONObject body = new JSONObject("{\"iBAN\": null}");
    stubStoredBankAccount(ES_IBAN, stubSpainWithIbanMeta());

    assertNull(handler.validateAndEnrichUpdate(ACC_ID, body));
    assertFalse(body.has("country"));
  }

  /** Clearing the country while a non-blank IBAN remains in play is rejected, not silently
   *  re-derived — that would hide the user's own action of clearing the field. */
  @Test
  public void testUpdateClearingCountryWithStoredIbanReturns400() throws Exception {
    JSONObject body = new JSONObject("{\"country\": null}");
    stubStoredBankAccount(ES_IBAN, stubSpainWithIbanMeta());

    NeoResponse response = handler.validateAndEnrichUpdate(ACC_ID, body);

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("must have a country"));
    verify(handler, never()).resolveCountryFromIban(any());
  }

  // ── amount tolerance: 0…100 percentage bound ─────────────────────────────
  //
  // Server-side counterpart of the modal's clamp. The value is read as a PERCENTAGE of the
  // statement line by both the automatch engine and the difference posting, so at 100 % or more the
  // latter's gate would authorise posting an entire statement line of any size to a G/L item. The
  // UI clamp is a convenience; this is the boundary.

  private static final String FIELD_AMOUNT_TOLERANCE = "eTGOAmountTolerance";

  /** The human-readable message of an error response. */
  private static String errorMessage(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("message");
  }

  /** A tolerance above 100 % is rejected on update, naming the accepted range. */
  @Test
  public void testUpdateAmountToleranceAboveMaxReturns400() throws Exception {
    JSONObject body = new JSONObject().put(FIELD_AMOUNT_TOLERANCE, "500");

    NeoResponse response = handler.validateAndEnrichUpdate(ACC_ID, body);

    assertEquals(400, response.getHttpStatus());
    String message = errorMessage(response);
    assertTrue(message.contains("between 0 and 100"));
    assertTrue("the message should echo the offending value", message.contains("500"));
  }

  /** A negative tolerance is rejected on update. */
  @Test
  public void testUpdateAmountToleranceNegativeReturns400() throws Exception {
    JSONObject body = new JSONObject().put(FIELD_AMOUNT_TOLERANCE, "-5");

    NeoResponse response = handler.validateAndEnrichUpdate(ACC_ID, body);

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("between 0 and 100"));
  }

  /** Both bounds are INCLUSIVE: 0 and 100 are legitimate configurations. */
  @Test
  public void testUpdateAmountToleranceBoundsAreInclusive() throws Exception {
    assertNull(handler.validateAndEnrichUpdate(ACC_ID,
        new JSONObject().put(FIELD_AMOUNT_TOLERANCE, "0")));
    assertNull(handler.validateAndEnrichUpdate(ACC_ID,
        new JSONObject().put(FIELD_AMOUNT_TOLERANCE, "100")));
  }

  /** A fractional percentage inside the range is accepted (the field is a decimal). */
  @Test
  public void testUpdateAmountToleranceAcceptsDecimal() throws Exception {
    assertNull(handler.validateAndEnrichUpdate(ACC_ID,
        new JSONObject().put(FIELD_AMOUNT_TOLERANCE, "2.5")));
    assertNull(handler.validateAndEnrichUpdate(ACC_ID,
        new JSONObject().put(FIELD_AMOUNT_TOLERANCE, "99.99")));
  }

  /** Non-numeric text is rejected with its own message rather than escaping as a 500. */
  @Test
  public void testUpdateAmountToleranceNonNumericReturns400() throws Exception {
    JSONObject body = new JSONObject().put(FIELD_AMOUNT_TOLERANCE, "abc");

    NeoResponse response = handler.validateAndEnrichUpdate(ACC_ID, body);

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("must be a number"));
  }

  /**
   * The guard is inert on a partial update. The Edit-account modal sends ONLY the fields it
   * considers dirty, so a body without the key — or with an explicit null / empty string — must
   * validate exactly as before, and the rest of the enrichment must still run.
   */
  @Test
  public void testUpdateWithoutAmountToleranceKeyIsUnaffected() throws Exception {
    stubStoredBankAccount(null, null);
    Country spain = stubSpainWithIbanMeta();
    doReturn(spain).when(handler).resolveCountryFromIban(ES_IBAN);
    JSONObject body = new JSONObject().put("iBAN", ES_IBAN);

    assertNull(handler.validateAndEnrichUpdate(ACC_ID, body));
    // The IBAN→country sync still happened: the guard did not swallow the enrichment.
    assertEquals("106", body.getString("country"));
  }

  /** An explicit JSON null on the field is treated as "not sent", not as invalid. */
  @Test
  public void testUpdateAmountToleranceNullOrBlankIsSkipped() throws Exception {
    JSONObject withNull = new JSONObject();
    withNull.put(FIELD_AMOUNT_TOLERANCE, JSONObject.NULL);
    assertNull(handler.validateAndEnrichUpdate(ACC_ID, withNull));

    assertNull(handler.validateAndEnrichUpdate(ACC_ID,
        new JSONObject().put(FIELD_AMOUNT_TOLERANCE, "")));
    assertNull(handler.validateAndEnrichUpdate(ACC_ID,
        new JSONObject().put(FIELD_AMOUNT_TOLERANCE, "   ")));
  }

  /**
   * An out-of-range value SHORT-CIRCUITS the update: the enrichment that follows never runs, so
   * nothing derived from the rejected body can reach the record.
   */
  @Test
  public void testUpdateOutOfRangeToleranceStopsBeforeEnrichment() throws Exception {
    JSONObject body = new JSONObject()
        .put(FIELD_AMOUNT_TOLERANCE, "500")
        .put("iBAN", ES_IBAN);

    NeoResponse response = handler.validateAndEnrichUpdate(ACC_ID, body);

    assertEquals(400, response.getHttpStatus());
    // The IBAN→country sync is downstream of the guard and must not have run.
    verify(handler, never()).resolveCountryFromIban(any());
    assertFalse(body.has("country"));
  }

  /** The same bound applies on create — an account must not be born out of range. */
  @Test
  public void testCreateAmountToleranceAboveMaxReturns400() throws Exception {
    stubValidCreate();
    JSONObject body = validCreateBody().put(FIELD_AMOUNT_TOLERANCE, "500");

    NeoResponse response = handler.validateAndEnrichCreate(body);

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("between 0 and 100"));
  }

  /** A negative tolerance is rejected on create too. */
  @Test
  public void testCreateAmountToleranceNegativeReturns400() throws Exception {
    stubValidCreate();
    JSONObject body = validCreateBody().put(FIELD_AMOUNT_TOLERANCE, "-1");

    assertEquals(400, handler.validateAndEnrichCreate(body).getHttpStatus());
  }

  /**
   * The create guard runs BEFORE the currency and duplicate-name lookups, so a rejected body never
   * reaches the DB-bound seams.
   */
  @Test
  public void testCreateOutOfRangeToleranceStopsBeforeLookups() throws Exception {
    stubValidCreate();
    JSONObject body = validCreateBody().put(FIELD_AMOUNT_TOLERANCE, "101");

    assertEquals(400, handler.validateAndEnrichCreate(body).getHttpStatus());
    verify(handler, never()).loadCurrency(any());
    verify(handler, never()).nameExists(any(), any());
  }

  /** A valid tolerance leaves the create path untouched. */
  @Test
  public void testCreateAmountToleranceInRangeIsAccepted() throws Exception {
    stubValidCreate();

    assertNull(handler.validateAndEnrichCreate(
        validCreateBody().put(FIELD_AMOUNT_TOLERANCE, "2.5")));
    assertNull(handler.validateAndEnrichCreate(
        validCreateBody().put(FIELD_AMOUNT_TOLERANCE, "100")));
  }

  /** Create without the field behaves exactly as before the guard existed. */
  @Test
  public void testCreateWithoutAmountToleranceKeyIsUnaffected() throws Exception {
    stubValidCreate();

    assertNull(handler.validateAndEnrichCreate(validCreateBody()));
  }

  // ── archive guard, moved onto the update path (ETP-4871) ─────────────────
  //
  // The old DELETE-based archive() no longer exists: DELETE now hard-deletes (see the
  // "delete: hard delete" section below). The former archive semantics — soft-delete via
  // IsActive='N', gated by the open-reconciliations guard — moved onto the PUT/PATCH path.
  // The frontend now archives by sending {"active": false}; validateAndEnrichUpdate detects
  // that via the private isArchivingRequest(body) and runs the guard via the private
  // guardArchive(id) — neither is package-private any more, so these are exercised only
  // through validateAndEnrichUpdate, never called directly. Note this pre-hook does NOT persist
  // the flip itself (unlike the old archive()) — a passing guard returns null so the generic
  // CRUD persists {"active": false} in its own transaction.

  private JSONObject archiveBody() throws JSONException {
    return new JSONObject().put("active", false);
  }

  /**
   * An archiving PATCH for an id that resolves to no account is rejected with a 400. Unlike the
   * old archive(), guardArchive has no separate "blank id" branch — it always calls loadAccount(id)
   * and treats a null result as "not found" — so a blank id takes the exact same 400 path as an
   * id that simply does not resolve to any account.
   */
  @Test
  public void testUpdateArchiveMissingIdReturns400() throws Exception {
    doReturn(null).when(handler).loadAccount("  ");

    NeoResponse response = handler.validateAndEnrichUpdate("  ", archiveBody());

    assertEquals(400, response.getHttpStatus());
  }

  /** An id that resolves to no account is rejected with a 400. */
  @Test
  public void testUpdateArchiveMissingAccountReturns400() throws Exception {
    doReturn(null).when(handler).loadAccount(ACC_ID);

    assertEquals(400, handler.validateAndEnrichUpdate(ACC_ID, archiveBody()).getHttpStatus());
  }

  /** An account with open reconciliations cannot be archived → 409, and nothing is persisted. */
  @Test
  public void testUpdateArchiveOpenReconciliationsReturns409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(true).when(handler).hasOpenReconciliations(account);

    NeoResponse response = handler.validateAndEnrichUpdate(ACC_ID, archiveBody());

    assertEquals(409, response.getHttpStatus());
    verify(account, never()).setActive(false);
  }

  /**
   * A clean archive request (no open reconciliations) passes the guard and returns {@code null},
   * falling through to the rest of the enrichment so the generic CRUD persists
   * {@code active=false} in its own transaction — this pre-hook no longer flips the flag or saves
   * anything itself, unlike the old DELETE-based archive().
   */
  @Test
  public void testUpdateArchiveHappyPassesGuardAndReturnsNull() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(false).when(handler).hasOpenReconciliations(account);

    NeoResponse response = handler.validateAndEnrichUpdate(ACC_ID, archiveBody());

    assertNull(response);
    verify(account, never()).setActive(false);
  }

  /**
   * The archive guard only triggers when the body explicitly sets {@code active} to
   * {@code false}: a plain rename, an explicit {@code active: true}, and an explicit JSON
   * {@code null} on {@code active} must all skip {@code loadAccount}/{@code hasOpenReconciliations}
   * entirely — none of those are "archiving requests".
   */
  @Test
  public void testUpdateArchiveGuardSkippedWhenActiveNotExplicitlyFalse() throws Exception {
    doReturn(false).when(handler).nameExists("BBVA Renamed", ACC_ID);
    assertNull(handler.validateAndEnrichUpdate(ACC_ID,
        new JSONObject().put("name", "BBVA Renamed")));
    verify(handler, never()).loadAccount(any());

    assertNull(handler.validateAndEnrichUpdate(ACC_ID, new JSONObject().put("active", true)));
    verify(handler, never()).loadAccount(any());

    JSONObject nullActive = new JSONObject();
    nullActive.put("active", JSONObject.NULL);
    assertNull(handler.validateAndEnrichUpdate(ACC_ID, nullActive));
    verify(handler, never()).loadAccount(any());
  }

  // ── delete: hard delete (ETP-4871) ────────────────────────────────────────

  /** A blank id is rejected with a 400 before any account lookup. */
  @Test
  public void testDeleteAccountMissingIdReturns400() {
    NeoResponse response = handler.deleteAccount("  ");
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).loadAccount(any());
  }

  /** An id that resolves to no account is rejected with a 400. */
  @Test
  public void testDeleteAccountMissingAccountReturns400() {
    doReturn(null).when(handler).loadAccount(ACC_ID);
    assertEquals(400, handler.deleteAccount(ACC_ID).getHttpStatus());
  }

  /**
   * Stubs every individual blocker check to {@code false} so a test can flip exactly one back to
   * {@code true} and prove that one alone is enough to block the delete. {@code hasTransactions}
   * stays an instance method on the {@code handler} spy; the other eight moved to static methods
   * on {@link FinancialAccountDeleteSupport} (ETP-4871's Sonar method-count extraction), so they
   * are stubbed on the {@code deleteSupport} static mock instead. {@code deleteSupport} must be
   * created with {@code Mockito.CALLS_REAL_METHODS} as its default answer so
   * {@code findDeleteBlockers}'s own real body still runs and calls back into these (stubbed)
   * sibling static methods.
   */
  private void stubNoBlockers(MockedStatic<FinancialAccountDeleteSupport> deleteSupport,
      FIN_FinancialAccount account) {
    doReturn(false).when(handler).hasTransactions(account);
    deleteSupport.when(() -> FinancialAccountDeleteSupport.hasAnyReconciliation(account)).thenReturn(false);
    deleteSupport.when(() -> FinancialAccountDeleteSupport.hasBankStatements(account)).thenReturn(false);
    deleteSupport.when(() -> FinancialAccountDeleteSupport.hasPayments(account)).thenReturn(false);
    deleteSupport.when(() -> FinancialAccountDeleteSupport.hasPaymentProposals(account)).thenReturn(false);
    deleteSupport.when(() -> FinancialAccountDeleteSupport.hasJournalLines(account)).thenReturn(false);
    deleteSupport.when(() -> FinancialAccountDeleteSupport.hasBankFileExceptions(account)).thenReturn(false);
    deleteSupport.when(() -> FinancialAccountDeleteSupport.isDefaultBpartnerAccount(account)).thenReturn(false);
    deleteSupport.when(() -> FinancialAccountDeleteSupport.hasBankConnection(account)).thenReturn(false);
  }

  /**
   * The happy path: nothing blocks the delete, so the account's own auto-created configuration
   * rows are swept first (via {@link FinancialAccountDeleteSupport#sweepOwnConfig}), then the
   * account itself is removed, flushed, and a 204 returned.
   */
  @Test
  public void testDeleteAccountHappyPathSweepsConfigRemovesAccountAndReturns204() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<FinancialAccountDeleteSupport> deleteSupport =
            mockStatic(FinancialAccountDeleteSupport.class, Mockito.CALLS_REAL_METHODS)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      stubNoBlockers(deleteSupport, account);
      deleteSupport.when(() -> FinancialAccountDeleteSupport.sweepOwnConfig(account))
          .thenAnswer(invocation -> null);
      // The .when(...) registration above is itself recorded as an invocation on the static
      // mock (unlike a regular instance mock, MockedStatic does not auto-exclude it), so clear
      // that bookkeeping noise before exercising the real code path — otherwise the verify()
      // below double-counts and fails with "Wanted 1 time... But was 2 times". This only
      // discards recorded invocations; the stub set up above is preserved.
      deleteSupport.clearInvocations();

      NeoResponse response = handler.deleteAccount(ACC_ID);

      assertEquals(204, response.getHttpStatus());
      deleteSupport.verify(() -> FinancialAccountDeleteSupport.sweepOwnConfig(account));
      verify(dal).remove(account);
      verify(dal).flush();
    }
  }

  /** Each of the nine blocker checks, in isolation, is sufficient to block a hard delete with a
   *  409 that names its own reason and never sweeps/removes anything. */
  @Test
  public void testDeleteAccountWithTransactionsReturns409NamingReason() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<FinancialAccountDeleteSupport> deleteSupport =
        mockStatic(FinancialAccountDeleteSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubNoBlockers(deleteSupport, account);
      doReturn(true).when(handler).hasTransactions(account);

      NeoResponse response = handler.deleteAccount(ACC_ID);

      assertEquals(409, response.getHttpStatus());
      assertTrue(errorMessage(response).contains(FinancialAccountDeleteSupport.REASON_TRANSACTIONS));
      deleteSupport.verify(() -> FinancialAccountDeleteSupport.sweepOwnConfig(any()), never());
    }
  }

  @Test
  public void testDeleteAccountWithReconciliationReturns409NamingReason() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<FinancialAccountDeleteSupport> deleteSupport =
        mockStatic(FinancialAccountDeleteSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubNoBlockers(deleteSupport, account);
      deleteSupport.when(() -> FinancialAccountDeleteSupport.hasAnyReconciliation(account)).thenReturn(true);

      NeoResponse response = handler.deleteAccount(ACC_ID);

      assertEquals(409, response.getHttpStatus());
      assertTrue(errorMessage(response).contains(FinancialAccountDeleteSupport.REASON_RECONCILIATIONS));
      deleteSupport.verify(() -> FinancialAccountDeleteSupport.sweepOwnConfig(any()), never());
    }
  }

  @Test
  public void testDeleteAccountWithBankStatementsReturns409NamingReason() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<FinancialAccountDeleteSupport> deleteSupport =
        mockStatic(FinancialAccountDeleteSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubNoBlockers(deleteSupport, account);
      deleteSupport.when(() -> FinancialAccountDeleteSupport.hasBankStatements(account)).thenReturn(true);

      NeoResponse response = handler.deleteAccount(ACC_ID);

      assertEquals(409, response.getHttpStatus());
      assertTrue(errorMessage(response).contains(FinancialAccountDeleteSupport.REASON_BANK_STATEMENTS));
      deleteSupport.verify(() -> FinancialAccountDeleteSupport.sweepOwnConfig(any()), never());
    }
  }

  @Test
  public void testDeleteAccountWithPaymentsReturns409NamingReason() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<FinancialAccountDeleteSupport> deleteSupport =
        mockStatic(FinancialAccountDeleteSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubNoBlockers(deleteSupport, account);
      deleteSupport.when(() -> FinancialAccountDeleteSupport.hasPayments(account)).thenReturn(true);

      NeoResponse response = handler.deleteAccount(ACC_ID);

      assertEquals(409, response.getHttpStatus());
      assertTrue(errorMessage(response).contains(FinancialAccountDeleteSupport.REASON_PAYMENTS));
      deleteSupport.verify(() -> FinancialAccountDeleteSupport.sweepOwnConfig(any()), never());
    }
  }

  @Test
  public void testDeleteAccountWithPaymentProposalsReturns409NamingReason() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<FinancialAccountDeleteSupport> deleteSupport =
        mockStatic(FinancialAccountDeleteSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubNoBlockers(deleteSupport, account);
      deleteSupport.when(() -> FinancialAccountDeleteSupport.hasPaymentProposals(account)).thenReturn(true);

      NeoResponse response = handler.deleteAccount(ACC_ID);

      assertEquals(409, response.getHttpStatus());
      assertTrue(errorMessage(response).contains(FinancialAccountDeleteSupport.REASON_PAYMENT_PROPOSALS));
      deleteSupport.verify(() -> FinancialAccountDeleteSupport.sweepOwnConfig(any()), never());
    }
  }

  @Test
  public void testDeleteAccountWithJournalLinesReturns409NamingReason() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<FinancialAccountDeleteSupport> deleteSupport =
        mockStatic(FinancialAccountDeleteSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubNoBlockers(deleteSupport, account);
      deleteSupport.when(() -> FinancialAccountDeleteSupport.hasJournalLines(account)).thenReturn(true);

      NeoResponse response = handler.deleteAccount(ACC_ID);

      assertEquals(409, response.getHttpStatus());
      assertTrue(errorMessage(response).contains(FinancialAccountDeleteSupport.REASON_JOURNAL_LINES));
      deleteSupport.verify(() -> FinancialAccountDeleteSupport.sweepOwnConfig(any()), never());
    }
  }

  @Test
  public void testDeleteAccountWithBankFileExceptionsReturns409NamingReason() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<FinancialAccountDeleteSupport> deleteSupport =
        mockStatic(FinancialAccountDeleteSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubNoBlockers(deleteSupport, account);
      deleteSupport.when(() -> FinancialAccountDeleteSupport.hasBankFileExceptions(account)).thenReturn(true);

      NeoResponse response = handler.deleteAccount(ACC_ID);

      assertEquals(409, response.getHttpStatus());
      assertTrue(errorMessage(response).contains(FinancialAccountDeleteSupport.REASON_BANK_FILE_EXCEPTIONS));
      deleteSupport.verify(() -> FinancialAccountDeleteSupport.sweepOwnConfig(any()), never());
    }
  }

  @Test
  public void testDeleteAccountAsBpartnerDefaultReturns409NamingReason() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<FinancialAccountDeleteSupport> deleteSupport =
        mockStatic(FinancialAccountDeleteSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubNoBlockers(deleteSupport, account);
      deleteSupport.when(() -> FinancialAccountDeleteSupport.isDefaultBpartnerAccount(account)).thenReturn(true);

      NeoResponse response = handler.deleteAccount(ACC_ID);

      assertEquals(409, response.getHttpStatus());
      assertTrue(errorMessage(response).contains(FinancialAccountDeleteSupport.REASON_BPARTNER_DEFAULT));
      deleteSupport.verify(() -> FinancialAccountDeleteSupport.sweepOwnConfig(any()), never());
    }
  }

  @Test
  public void testDeleteAccountWithBankConnectionReturns409NamingReason() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<FinancialAccountDeleteSupport> deleteSupport =
        mockStatic(FinancialAccountDeleteSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubNoBlockers(deleteSupport, account);
      deleteSupport.when(() -> FinancialAccountDeleteSupport.hasBankConnection(account)).thenReturn(true);

      NeoResponse response = handler.deleteAccount(ACC_ID);

      assertEquals(409, response.getHttpStatus());
      assertTrue(errorMessage(response).contains(FinancialAccountDeleteSupport.REASON_BANK_CONNECTION));
      deleteSupport.verify(() -> FinancialAccountDeleteSupport.sweepOwnConfig(any()), never());
    }
  }

  /** Multiple simultaneous blockers are all named in the same 409 message. */
  @Test
  public void testDeleteAccountWithMultipleBlockersListsAllReasonsIn409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<FinancialAccountDeleteSupport> deleteSupport =
        mockStatic(FinancialAccountDeleteSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubNoBlockers(deleteSupport, account);
      doReturn(true).when(handler).hasTransactions(account);
      deleteSupport.when(() -> FinancialAccountDeleteSupport.hasBankConnection(account)).thenReturn(true);

      NeoResponse response = handler.deleteAccount(ACC_ID);

      assertEquals(409, response.getHttpStatus());
      String message = errorMessage(response);
      assertTrue(message.contains(FinancialAccountDeleteSupport.REASON_TRANSACTIONS));
      assertTrue(message.contains(FinancialAccountDeleteSupport.REASON_BANK_CONNECTION));
      deleteSupport.verify(() -> FinancialAccountDeleteSupport.sweepOwnConfig(any()), never());
    }
  }

  /**
   * {@code sweepOwnConfig} real body: removes every row of the four account-owned config tables
   * (accounting setup, default payment methods, matching rules, PSD2 sync log) via
   * {@code OBDal.remove}, none of which are themselves delete blockers.
   */
  @Test
  public void testSweepOwnConfigRemovesAllFourConfigTables() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      FIN_FinancialAccountAccounting acctRow = mock(FIN_FinancialAccountAccounting.class);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_FinancialAccountAccounting> acctCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_FinancialAccountAccounting.class)).thenReturn(acctCriteria);
      when(acctCriteria.list()).thenReturn(Arrays.asList(acctRow));

      FinAccPaymentMethod pmRow = mock(FinAccPaymentMethod.class);
      @SuppressWarnings("unchecked")
      OBCriteria<FinAccPaymentMethod> pmCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(pmCriteria);
      when(pmCriteria.list()).thenReturn(Arrays.asList(pmRow));

      MatchRule matchRuleRow = mock(MatchRule.class);
      @SuppressWarnings("unchecked")
      OBCriteria<MatchRule> matchRuleCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(MatchRule.class)).thenReturn(matchRuleCriteria);
      when(matchRuleCriteria.list()).thenReturn(Arrays.asList(matchRuleRow));

      PSD2FinaccLog logRow = mock(PSD2FinaccLog.class);
      @SuppressWarnings("unchecked")
      OBCriteria<PSD2FinaccLog> logCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(PSD2FinaccLog.class)).thenReturn(logCriteria);
      when(logCriteria.list()).thenReturn(Arrays.asList(logRow));

      FinancialAccountDeleteSupport.sweepOwnConfig(account);

      verify(dal).remove(acctRow);
      verify(dal).remove(pmRow);
      verify(dal).remove(matchRuleRow);
      verify(dal).remove(logRow);
    }
  }

  /**
   * {@code isDefaultBpartnerAccount} real body: checks BOTH FK columns a business partner can
   * default this account through ({@code account} and {@code pOFinancialAccount}) — a partner
   * defaulting to it only as its PO account must still count as a blocker.
   */
  @Test
  public void testIsDefaultBpartnerAccountChecksBothRegularAndPoFkColumns() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<BusinessPartner> regularCriteria = mock(OBCriteria.class);
      @SuppressWarnings("unchecked")
      OBCriteria<BusinessPartner> poCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(BusinessPartner.class)).thenReturn(regularCriteria, poCriteria);
      when(regularCriteria.uniqueResult()).thenReturn(null);
      when(poCriteria.uniqueResult()).thenReturn(mock(BusinessPartner.class));

      assertTrue("a BP defaulting to this account only as its PO account still blocks the delete",
          FinancialAccountDeleteSupport.isDefaultBpartnerAccount(account));
    }
  }

  /**
   * {@code hasAnyReconciliation} real body: deliberately adds NO active-status restriction, unlike
   * {@code hasOpenReconciliations} (which adds three restrictions, including "active" and
   * "not-closed"). Proven here by asserting the criteria receives exactly ONE restriction (the FK
   * match) — an inactive/closed reconciliation is therefore still found and still blocks a hard
   * delete, which is the asymmetry ETP-4871 depends on: a RESTRICT FK blocks a delete regardless
   * of whether the referencing row is itself soft-deleted.
   */
  @Test
  public void testHasAnyReconciliationCountsInactiveOrClosedRowAsBlocker() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_Reconciliation> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_Reconciliation.class)).thenReturn(criteria);
      // Only one restriction is ever added (the FK match), so this simulated criteria would still
      // match a CLOSED/inactive reconciliation row exactly as it matches an open/active one.
      when(criteria.uniqueResult()).thenReturn(mock(FIN_Reconciliation.class));

      assertTrue("an inactive/closed reconciliation still blocks the hard delete",
          FinancialAccountDeleteSupport.hasAnyReconciliation(account));
      verify(criteria, times(1)).add(any());
      verify(criteria).setMaxResults(1);
    }
  }

  /**
   * {@code hasBankConnection} real body: same asymmetry as
   * {@link #testHasAnyReconciliationCountsInactiveOrClosedRowAsBlocker} — no active/status
   * restriction is added, so a soft-disconnected {@code FinaccConnection} row still counts as a
   * blocker (disconnecting the app-side sync never removes the FK row itself).
   */
  @Test
  public void testHasBankConnectionCountsSoftDisconnectedRowAsBlocker() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<FinaccConnection> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(FinaccConnection.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(mock(FinaccConnection.class));

      assertTrue("a soft-disconnected bank connection still blocks the hard delete",
          FinancialAccountDeleteSupport.hasBankConnection(account));
      verify(criteria, times(1)).add(any());
      verify(criteria).setMaxResults(1);
    }
  }

  // ── normalizeType ────────────────────────────────────────────────────────

  /** Type normalization accepts the three valid codes and defaults to Bank. */
  @Test
  public void testNormalizeType() {
    assertEquals("B", handler.normalizeType("B"));
    assertEquals("C", handler.normalizeType("C"));
    assertEquals("CA", handler.normalizeType("CA"));
    assertEquals("B", handler.normalizeType(""));
    assertEquals("B", handler.normalizeType("junk"));
    assertTrue(handler.normalizeType(null) != null);
  }

  // ── seam real-body coverage ───────────────────────────────────────────────
  //
  // The tests above stub the DAL-bound seams on the spy, so those methods' real
  // bodies never run. The tests below invoke the real bodies on a fresh, NON-spy
  // handler, mocking the static OBDal/OBContext entry points so the DAL layer is
  // never actually hit.

  /** normalizeType keeps a cash code unchanged (real body, no spy). */
  @Test
  public void testNormalizeTypeRealBodyCash() {
    FinancialAccountHandler h = new FinancialAccountHandler();
    assertEquals("C", h.normalizeType("C"));
    assertEquals("CA", h.normalizeType("CA"));
    assertEquals("B", h.normalizeType("B"));
    assertEquals("B", h.normalizeType(null));
  }

  /** loadCurrency delegates to OBDal.get(Currency.class, id) and returns it. */
  @Test
  public void testLoadCurrencyReturnsDalResult() {
    FinancialAccountHandler h = new FinancialAccountHandler();
    Currency currency = mock(Currency.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Currency.class, EUR_ID)).thenReturn(currency);

      assertSame(currency, h.loadCurrency(EUR_ID));
      verify(dal).get(Currency.class, EUR_ID);
    }
  }

  /** loadAccount delegates to OBDal.get(FIN_FinancialAccount.class, id). */
  @Test
  public void testLoadAccountReturnsDalResult() {
    FinancialAccountHandler h = new FinancialAccountHandler();
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinancialAccount.class, ACC_ID)).thenReturn(account);

      assertSame(account, h.loadAccount(ACC_ID));
      verify(dal).get(FIN_FinancialAccount.class, ACC_ID);
    }
  }

  /** A null IBAN resolves to no country without ever touching the DAL. */
  @Test
  public void testResolveCountryFromIbanNullReturnsNullWithoutDal() {
    FinancialAccountHandler h = new FinancialAccountHandler();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(h.resolveCountryFromIban(null));
      obDal.verifyNoInteractions();
    }
  }

  /** An IBAN shorter than two chars resolves to no country without the DAL. */
  @Test
  public void testResolveCountryFromIbanTooShortReturnsNullWithoutDal() {
    FinancialAccountHandler h = new FinancialAccountHandler();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(h.resolveCountryFromIban("E"));
      obDal.verifyNoInteractions();
    }
  }

  /**
   * A valid IBAN uppercases its ISO prefix, disables the readable client/org
   * filters and returns the criteria's unique result.
   */
  @Test
  public void testResolveCountryFromIbanUppercasesPrefixAndReturnsMatch() {
    FinancialAccountHandler h = new FinancialAccountHandler();
    Country spain = mock(Country.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Country.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(spain);

      // Lower-case prefix must still match (the body uppercases to "ES").
      assertSame(spain, h.resolveCountryFromIban("es9121000418450200051332"));

      verify(criteria).setFilterOnReadableClients(false);
      verify(criteria).setFilterOnReadableOrganization(false);
      verify(criteria).setMaxResults(1);
      verify(criteria).uniqueResult();
    }
  }

  /** nameExists with no excludeId adds three restrictions and returns false on an empty list. */
  @Test
  public void testNameExistsEmptyListReturnsFalse() {
    FinancialAccountHandler h = new FinancialAccountHandler();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      OBContext ctx = mock(OBContext.class);
      Organization org = mock(Organization.class);
      when(ctx.getCurrentOrganization()).thenReturn(org);
      obContext.when(OBContext::getOBContext).thenReturn(ctx);

      @SuppressWarnings("unchecked")
      OBCriteria<FIN_FinancialAccount> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_FinancialAccount.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      assertFalse(h.nameExists("BBVA", null));
      // name + organization + active (no excludeId branch).
      verify(criteria, times(3)).add(any());
      verify(criteria).setMaxResults(1);
    }
  }

  /**
   * nameExists with a non-blank excludeId adds the extra {@code ne id}
   * restriction and returns true on a non-empty list.
   */
  @Test
  public void testNameExistsWithExcludeIdAddsExtraRestrictionAndReturnsTrue() {
    FinancialAccountHandler h = new FinancialAccountHandler();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      OBContext ctx = mock(OBContext.class);
      Organization org = mock(Organization.class);
      when(ctx.getCurrentOrganization()).thenReturn(org);
      obContext.when(OBContext::getOBContext).thenReturn(ctx);

      @SuppressWarnings("unchecked")
      OBCriteria<FIN_FinancialAccount> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_FinancialAccount.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Arrays.asList(mock(FIN_FinancialAccount.class)));

      assertTrue(h.nameExists("BBVA", ACC_ID));
      // name + organization + active + ne id (excludeId branch).
      verify(criteria, times(4)).add(any());
    }
  }

  /** hasOpenReconciliations returns false when the criteria yields no row. */
  @Test
  public void testHasOpenReconciliationsNullReturnsFalse() {
    FinancialAccountHandler h = new FinancialAccountHandler();
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_Reconciliation> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_Reconciliation.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      assertFalse(h.hasOpenReconciliations(account));
      verify(criteria).setMaxResults(1);
    }
  }

  /** hasOpenReconciliations returns true when the criteria yields a row. */
  @Test
  public void testHasOpenReconciliationsNonNullReturnsTrue() {
    FinancialAccountHandler h = new FinancialAccountHandler();
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_Reconciliation> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_Reconciliation.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(mock(FIN_Reconciliation.class));

      assertTrue(h.hasOpenReconciliations(account));
    }
  }

  /** listMatchingAlgorithms returns the criteria list and orders by name ascending. */
  @Test
  public void testListMatchingAlgorithmsReturnsListAndOrders() {
    FinancialAccountHandler h = new FinancialAccountHandler();
    List<MatchingAlgorithm> expected = Arrays.asList(mock(MatchingAlgorithm.class));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<MatchingAlgorithm> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(MatchingAlgorithm.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(expected);

      assertSame(expected, h.listMatchingAlgorithms());
      verify(criteria).addOrderBy(MatchingAlgorithm.PROPERTY_NAME, true);
    }
  }

  /** doRollbackAndClose delegates to OBDal.getInstance().rollbackAndClose(). */
  @Test
  public void testDoRollbackAndCloseCallsDal() {
    FinancialAccountHandler h = new FinancialAccountHandler();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      h.doRollbackAndClose();

      verify(dal).rollbackAndClose();
    }
  }

  /** enterAdminMode sets the admin mode flag on OBContext. */
  @Test
  public void testEnterAdminModeSetsAdminMode() {
    FinancialAccountHandler h = new FinancialAccountHandler();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      h.enterAdminMode();
      obContext.verify(() -> OBContext.setAdminMode(true));
    }
  }

  /** exitAdminMode restores the previous OBContext mode. */
  @Test
  public void testExitAdminModeRestoresPreviousMode() {
    FinancialAccountHandler h = new FinancialAccountHandler();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      h.exitAdminMode();
      obContext.verify(OBContext::restorePreviousMode);
    }
  }

  // ── afterHandle: delegates default payment-method assignment to
  // FinancialAccountSupport ──────────────────────────────────────────────────
  //
  // The assignment logic itself (findPaymentMethodByName/linkExists/createLink,
  // one method per account type, default flag, idempotency) now lives on
  // FinancialAccountSupport.assignDefaultPaymentMethods (static) and is covered
  // end-to-end in FinancialAccountSupportTest. Here we only verify the hook
  // orchestration: routing and that the static call is delegated with the
  // loaded account.

  /** A foreign spec is ignored by the post-hook (no account lookup). */
  @Test
  public void testAfterHandleForeignSpecReturnsNull() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("sales-order");

    assertNull(handler.afterHandle(ctx));
    verify(handler, never()).loadAccount(any());
  }

  /** Non-POST writes (e.g. PUT) do not trigger payment-method assignment. */
  @Test
  public void testAfterHandleNonPostReturnsNull() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getHttpMethod()).thenReturn("PUT");

    assertNull(handler.afterHandle(ctx));
    verify(handler, never()).extractCreatedId(any());
  }

  /**
   * The defaults endpoint overwrites the generic currency default with the client's accounting
   * schema currency, so the new-account wizard starts with the real client currency instead of the
   * first alphabetic active currency.
   */
  @Test
  public void testAfterHandleDefaultsInjectsClientCurrency() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.DEFAULTS);

    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn(EUR_ID);
    when(currency.getISOCode()).thenReturn("EUR");

    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getCurrency()).thenReturn(currency);

    JSONObject defaults = new JSONObject().put("currency", "114").put("currency$_identifier", "AED");
    JSONObject responseBody = new JSONObject().put("defaults", defaults);
    when(ctx.getPreviousResult()).thenReturn(new NeoResponse(200, responseBody));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBContext obCtx = mock(OBContext.class);
      Client client = mock(Client.class);
      when(obCtx.getCurrentClient()).thenReturn(client);
      obContext.when(OBContext::getOBContext).thenReturn(obCtx);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<AcctSchema> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(AcctSchema.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(schema);

      NeoResponse out = handler.afterHandle(ctx);

      assertEquals(200, out.getHttpStatus());
      JSONObject outDefaults = out.getBody().getJSONObject("defaults");
      assertEquals(EUR_ID, outDefaults.getString("currency"));
      assertEquals("EUR", outDefaults.getString("currency$_identifier"));
      verify(handler, never()).extractCreatedId(any());
    }
  }

  /**
   * When the defaults endpoint cannot resolve a client currency, the response is still 200 (the
   * body is mutated in place regardless — ETP-4896 folded the old early-return-null behavior into
   * a single always-ok response, since {@code context.getEntityName()} is unstubbed here (not the
   * {@code account} entity), so country/catalog injection is skipped either way).
   */
  @Test
  public void testAfterHandleDefaultsWithoutClientCurrencyLeavesDefaultsEmpty() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.DEFAULTS);
    when(ctx.getPreviousResult()).thenReturn(new NeoResponse(200, new JSONObject()));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBContext obCtx = mock(OBContext.class);
      when(obCtx.getCurrentClient()).thenReturn(mock(Client.class));
      obContext.when(OBContext::getOBContext).thenReturn(obCtx);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<AcctSchema> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(AcctSchema.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      NeoResponse out = handler.afterHandle(ctx);

      assertEquals(200, out.getHttpStatus());
      assertFalse(out.getBody().getJSONObject("defaults").has("currency"));
      assertFalse("not the account entity, so no country key either",
          out.getBody().getJSONObject("defaults").has("country"));
      verify(handler, never()).extractCreatedId(any());
    }
  }

  /**
   * On the {@code account} entity, the defaults response also gets {@code country} (ETP-4896
   * requirement 1) and the {@code countryIbanRules} catalog as a sibling of {@code defaults}.
   */
  @Test
  public void testAfterHandleDefaultsForAccountEntityInjectsCountryAndCatalog() throws Exception {
    FinancialAccountCountrySupport.clearIbanRulesCacheForTests();
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.DEFAULTS);
    when(ctx.getEntityName()).thenReturn("account");
    when(ctx.getPreviousResult()).thenReturn(new NeoResponse(200, new JSONObject()));

    Country orgCountry = mock(Country.class);
    when(orgCountry.getId()).thenReturn("106");
    when(orgCountry.getName()).thenReturn("Spain");
    doReturn(orgCountry).when(handler).resolveOrgCountry();

    Country spain = stubSpainWithIbanMeta();
    when(spain.getISOCountryCode()).thenReturn("ES");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBContext obCtx = mock(OBContext.class);
      when(obCtx.getCurrentClient()).thenReturn(mock(Client.class));
      obContext.when(OBContext::getOBContext).thenReturn(obCtx);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<AcctSchema> acctCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(AcctSchema.class)).thenReturn(acctCriteria);
      when(acctCriteria.uniqueResult()).thenReturn(null);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> countryCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Country.class)).thenReturn(countryCriteria);
      when(countryCriteria.list()).thenReturn(Collections.singletonList(spain));

      NeoResponse out = handler.afterHandle(ctx);

      assertEquals(200, out.getHttpStatus());
      JSONObject outDefaults = out.getBody().getJSONObject("defaults");
      assertEquals("106", outDefaults.getString("country"));
      assertEquals("Spain", outDefaults.getString("country$_identifier"));
      JSONArray rules = out.getBody().getJSONArray("countryIbanRules");
      assertEquals(1, rules.length());
      assertEquals("ES", rules.getJSONObject(0).getString("iso"));
    }
  }

  /**
   * A non-{@code account} entity (e.g. {@code transaction}) keeps getting the pre-existing
   * currency default (a leak that predates ETP-4896, left alone) but must NOT get the new
   * country/catalog keys.
   */
  @Test
  public void testAfterHandleDefaultsForOtherEntitySkipsCountryAndCatalog() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.DEFAULTS);
    when(ctx.getEntityName()).thenReturn("transaction");
    when(ctx.getPreviousResult()).thenReturn(new NeoResponse(200, new JSONObject()));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBContext obCtx = mock(OBContext.class);
      when(obCtx.getCurrentClient()).thenReturn(mock(Client.class));
      obContext.when(OBContext::getOBContext).thenReturn(obCtx);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<AcctSchema> acctCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(AcctSchema.class)).thenReturn(acctCriteria);
      when(acctCriteria.uniqueResult()).thenReturn(null);

      NeoResponse out = handler.afterHandle(ctx);

      assertEquals(200, out.getHttpStatus());
      assertFalse(out.getBody().getJSONObject("defaults").has("country"));
      assertFalse(out.getBody().has("countryIbanRules"));
      verify(handler, never()).resolveOrgCountry();
      verify(dal, never()).createCriteria(Country.class);
    }
  }

  /**
   * When the organization's country cannot be resolved at all, the key is simply omitted — never
   * the AD-seeded {@code ISDEFAULT='Y'} country (United States, no IBAN metadata).
   */
  @Test
  public void testAfterHandleDefaultsOrgCountryUnresolvedOmitsKeyWithoutError() throws Exception {
    FinancialAccountCountrySupport.clearIbanRulesCacheForTests();
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.DEFAULTS);
    when(ctx.getEntityName()).thenReturn("account");
    when(ctx.getPreviousResult()).thenReturn(new NeoResponse(200, new JSONObject()));
    doReturn(null).when(handler).resolveOrgCountry();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBContext obCtx = mock(OBContext.class);
      when(obCtx.getCurrentClient()).thenReturn(mock(Client.class));
      obContext.when(OBContext::getOBContext).thenReturn(obCtx);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<AcctSchema> acctCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(AcctSchema.class)).thenReturn(acctCriteria);
      when(acctCriteria.uniqueResult()).thenReturn(null);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> countryCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Country.class)).thenReturn(countryCriteria);
      when(countryCriteria.list()).thenReturn(Collections.emptyList());

      NeoResponse out = handler.afterHandle(ctx);

      assertEquals(200, out.getHttpStatus());
      assertFalse("never the AD-seeded United States default",
          out.getBody().getJSONObject("defaults").has("country"));
      assertTrue(out.getBody().has("countryIbanRules"));
    }
  }

  /** A POST whose response carries no id assigns nothing. */
  @Test
  public void testAfterHandleNoCreatedIdSkipsAssignment() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getHttpMethod()).thenReturn("POST");
    doReturn(null).when(handler).extractCreatedId(ctx);

    assertNull(handler.afterHandle(ctx));
    verify(handler, never()).loadAccount(any());
  }

  /**
   * A POST with a created id loads the account and delegates the assignment to
   * {@link FinancialAccountSupport#assignDefaultPaymentMethods}, verified via a static mock
   * since the method is now static on that helper (moved out of this handler).
   */
  @Test
  public void testAfterHandlePostAssignsForCreatedAccount() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getHttpMethod()).thenReturn("POST");
    doReturn(ACC_ID).when(handler).extractCreatedId(ctx);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<FinancialAccountSupport> support =
        mockStatic(FinancialAccountSupport.class)) {
      assertNull(handler.afterHandle(ctx));
      support.verify(() -> FinancialAccountSupport.assignDefaultPaymentMethods(account));
    }
  }

  /** A failure during assignment is swallowed so account creation is not broken. */
  @Test
  public void testAfterHandleSwallowsAssignmentFailure() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getHttpMethod()).thenReturn("POST");
    doReturn(ACC_ID).when(handler).extractCreatedId(ctx);
    doThrow(new RuntimeException("boom")).when(handler).loadAccount(ACC_ID);

    assertNull(handler.afterHandle(ctx));
  }

  // ── afterHandle: GET+CRUD injects the derived list fields (ETP-4530 / ETP-4658) ──
  //
  // Two things are injected post-hook over every row of a GET (list/getById) response for
  // this spec, because NeoFieldFilter strips anything that is not a declared field and runs
  // BEFORE afterHandle:
  //
  //   1. `hasTransactions` (ETP-4530) — the frontend locks the Currency field once the
  //      account has real movement history.
  //   2. The accounts-list derived fields (ETP-4658) — bankConnected,
  //      bankConnectionPending, currencyIso/currencyId, isDefault, maskedPan, active and the
  //      lowercase `iban` alias — plus a collection-level `summary` sibling of
  //      `response.data` for the list sidebar. These used to be computed by the bespoke
  //      `financial-accounts-page` R spec; the W spec is now the single source of truth.
  //
  // `pendingCount` used to be on that list. It is NOT injected any more: it became the
  // EM_ETGO_Pending_Count stored computed column, so the generic CRUD already serves it as
  // `eTGOPendingCount` and afterHandle must not touch it. What the handler still reads from
  // the AccountRow is the count that feeds `summary.pending.accountsWithPending`.
  //
  // The SQL loaders are reached through the package-private `pageLoaders()` seam and
  // run ONCE for the whole page (Map/Set lookup per row) — the previous implementation
  // issued two queries PER ROW just for `hasTransactions`. Tests therefore stub
  // `pageLoaders()` with a spy whose loader seams are stubbed, letting the real
  // `buildSummary` aggregate the fixture rows.

  private static final String ORG_ID = "root-org";
  private static final Set<String> ORGS = new HashSet<>(Arrays.asList("0", ORG_ID));

  /** Builds an AccountRow fixture with the loader-set flags the list needs. */
  private static FinancialAccountsPageHandler.AccountRow accountRow(String id, String balance,
      String currencyId, String currencyIso, boolean isDefault) {
    return new FinancialAccountsPageHandler.AccountRow(id, "name-" + id, "B",
        new BigDecimal(balance),
        new FinancialAccountsPageHandler.Currency(currencyId, currencyIso),
        "ES1200000000000000000001", isDefault);
  }

  /** A GET/CRUD context whose previous result is the given generic-CRUD envelope. */
  private NeoContext getCrudContext(JSONArray rows) throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", rows));
    when(ctx.getPreviousResult()).thenReturn(new NeoResponse(200, body));
    return ctx;
  }

  /**
   * Installs a spied {@link FinancialAccountsPageHandler} as the {@code pageLoaders()} seam
   * with its three SQL loaders stubbed ({@code loadDeleteBlockersByAccount} — ETP-4871 — defaults
   * to an empty map, i.e. every account deletable, unless a test needs otherwise; see the overload
   * below). {@code buildSummary} is deliberately left real so the aggregation the sidebar renders
   * is exercised end-to-end.
   */
  private FinancialAccountsPageHandler stubLoaders(List<FinancialAccountsPageHandler.AccountRow> rows,
      Map<String, Integer> pending, Set<String> withTransactions) throws Exception {
    return stubLoaders(rows, pending, withTransactions, Collections.emptyMap());
  }

  /**
   * Same as {@link #stubLoaders(List, Map, Set)} but lets the caller control
   * {@code loadDeleteBlockersByAccount}'s result directly, for tests asserting the
   * {@code deletable} / {@code deleteBlockedReason} injection (ETP-4871).
   */
  private FinancialAccountsPageHandler stubLoaders(List<FinancialAccountsPageHandler.AccountRow> rows,
      Map<String, Integer> pending, Set<String> withTransactions,
      Map<String, List<String>> deleteBlockersByAccount) throws Exception {
    FinancialAccountsPageHandler loaders = spy(new FinancialAccountsPageHandler());
    doReturn(ORGS).when(loaders).accessibleOrgs(ORG_ID);
    // `pending` is applied ONTO the fixture rows rather than stubbed as a loader seam:
    // loadPendingByAccount is gone, and loadAccounts now reads the count out of the
    // EM_ETGO_Pending_Count column into AccountRow.pendingCount. Keeping the parameter lets
    // every call site stay as it was while the real buildSummary still aggregates it.
    for (FinancialAccountsPageHandler.AccountRow row : rows) {
      row.pendingCount = pending.getOrDefault(row.id, 0);
    }
    doReturn(rows).when(loaders).loadAccounts(eq(CLIENT_ID), eq(ORGS));
    doReturn(withTransactions).when(loaders).loadAccountsWithTransactions(eq(CLIENT_ID), eq(ORGS));
    doReturn(deleteBlockersByAccount).when(loaders).loadDeleteBlockersByAccount(eq(CLIENT_ID), eq(ORGS));
    doReturn(loaders).when(handler).pageLoaders();
    return loaders;
  }

  /** Stubs the OBContext statics the injection reads for the client/org filter. */
  private MockedStatic<OBContext> mockSessionContext() {
    MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
    OBContext obCtx = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(obCtx.getCurrentClient()).thenReturn(client);
    when(obCtx.getCurrentOrganization()).thenReturn(org);
    obContext.when(OBContext::getOBContext).thenReturn(obCtx);
    return obContext;
  }

  /**
   * Every derived field the accounts list needs is injected per row: the transaction flag,
   * the pending counter, the bank-connection flags, the provider's logo URL, the currency pair,
   * the default/archived flags, the masked card number and the lowercase {@code iban} alias of
   * the contract's {@code iBAN}.
   */
  @Test
  public void testAfterHandleGetCrudInjectsDerivedListFieldsPerRow() throws Exception {
    JSONObject row1 = new JSONObject().put("id", ACC_ID).put("iBAN", ES_IBAN);
    JSONObject row2 = new JSONObject().put("id", "acc-2").put("iBAN", "");
    NeoContext ctx = getCrudContext(new JSONArray().put(row1).put(row2));

    FinancialAccountsPageHandler.AccountRow loaded1 = accountRow(ACC_ID, "1500.00", EUR_ID, "EUR", true);
    loaded1.bankConnected = true;
    loaded1.providerLogoUrl = "https://cdn.saltedge.com/bank_icons/bbva.png";
    loaded1.country = new FinancialAccountsPageHandler.CountryRef("106", "ES", "Spain");
    FinancialAccountsPageHandler.AccountRow loaded2 = accountRow("acc-2", "0.00", "100", "USD", false);
    loaded2.active = false;
    loaded2.maskedPan = "**** 4321";
    // loaded2.country stays null — a Cash/never-set-up account, exercising the "" fallback below.

    Map<String, Integer> pending = new LinkedHashMap<>();
    pending.put(ACC_ID, 4);

    try (MockedStatic<OBContext> obContext = mockSessionContext()) {
      stubLoaders(Arrays.asList(loaded1, loaded2), pending, Collections.singleton(ACC_ID));

      NeoResponse out = handler.afterHandle(ctx);

      assertEquals(200, out.getHttpStatus());
      JSONArray outArr = out.getBody().getJSONObject("response").getJSONArray("data");
      JSONObject first = outArr.getJSONObject(0);
      assertTrue("account with a registered transaction locks the Currency field",
          first.getBoolean("hasTransactions"));
      assertFalse("pendingCount is the EM_ETGO_Pending_Count stored column now — the generic "
          + "CRUD serves it as eTGOPendingCount and afterHandle must not re-inject it",
          first.has("pendingCount"));
      assertTrue(first.getBoolean("bankConnected"));
      assertFalse("bankConnectionPending is never computed server-side yet",
          first.getBoolean("bankConnectionPending"));
      assertEquals("EUR", first.getString("currencyIso"));
      assertEquals(EUR_ID, first.getString("currencyId"));
      assertTrue(first.getBoolean("isDefault"));
      assertTrue(first.getBoolean("active"));
      assertEquals("lowercase alias of the contract's iBAN", ES_IBAN, first.getString("iban"));
      assertEquals("https://cdn.saltedge.com/bank_icons/bbva.png", first.getString("providerLogoUrl"));
      assertEquals("106", first.getString("countryId"));
      assertEquals("ES", first.getString("countryIso"));
      assertEquals("Spain", first.getString("countryName"));

      JSONObject second = outArr.getJSONObject(1);
      assertFalse("account without transactions leaves the Currency field editable",
          second.getBoolean("hasTransactions"));
      assertFalse("not injected for any row, pending or not", second.has("pendingCount"));
      assertFalse(second.getBoolean("bankConnected"));
      assertEquals("USD", second.getString("currencyIso"));
      assertFalse("archived accounts are flagged so the Inactivas filter can find them",
          second.getBoolean("active"));
      assertEquals("**** 4321", second.getString("maskedPan"));
      assertEquals("no bank provider serialises providerLogoUrl as an empty string, not null",
          "", second.getString("providerLogoUrl"));
      assertEquals("a null row.country serialises as \"\", never the literal \"null\"",
          "", second.getString("countryId"));
      assertEquals("", second.getString("countryIso"));
      assertEquals("", second.getString("countryName"));
    }
  }

  /**
   * The N+1 fix: the four loaders run ONCE for the whole page regardless of how many rows
   * the generic CRUD returned, and the per-row work is a Map/Set lookup. {@code
   * loadDeleteBlockersByAccount} (ETP-4871) follows the same rule as the other three.
   */
  @Test
  public void testAfterHandleGetCrudRunsEachLoaderOnceForTheWholePage() throws Exception {
    JSONArray rows = new JSONArray()
        .put(new JSONObject().put("id", ACC_ID))
        .put(new JSONObject().put("id", "acc-2"))
        .put(new JSONObject().put("id", "acc-3"));
    NeoContext ctx = getCrudContext(rows);

    try (MockedStatic<OBContext> obContext = mockSessionContext();
        MockedStatic<FinancialAccountDeleteSupport> deleteSupport =
            mockStatic(FinancialAccountDeleteSupport.class)) {
      FinancialAccountsPageHandler loaders = stubLoaders(
          Arrays.asList(
              accountRow(ACC_ID, "10.00", EUR_ID, "EUR", false),
              accountRow("acc-2", "20.00", EUR_ID, "EUR", false),
              accountRow("acc-3", "30.00", EUR_ID, "EUR", false)),
          Collections.emptyMap(), Collections.emptySet());

      handler.afterHandle(ctx);

      verify(loaders, times(1)).loadAccounts(CLIENT_ID, ORGS);
      verify(loaders, times(1)).loadAccountsWithTransactions(CLIENT_ID, ORGS);
      verify(loaders, times(1)).loadDeleteBlockersByAccount(CLIENT_ID, ORGS);
      // The legacy per-row DAL seams must not be reached at all any more; findDeleteBlockers
      // (now static on FinancialAccountDeleteSupport, ETP-4871's Sonar method-count extraction)
      // is never called per row either — the batched loader above is the only ETP-4871 entry
      // point this injection path is allowed to reach.
      verify(handler, never()).loadAccount(any());
      verify(handler, never()).hasTransactions(any());
      deleteSupport.verify(
          () -> FinancialAccountDeleteSupport.findDeleteBlockers(any(), anyBoolean()), never());
    }
  }

  /**
   * The row-level counterpart of {@code deletable=true}: an account with no rows in the batched
   * {@code loadDeleteBlockersByAccount} map is serialised as deletable, with no
   * {@code deleteBlockedReason} key at all (not even blank).
   */
  @Test
  public void testAfterHandleGetCrudInjectsDeletableTrueWhenNoBlockers() throws Exception {
    JSONObject row = new JSONObject().put("id", ACC_ID);
    NeoContext ctx = getCrudContext(new JSONArray().put(row));

    try (MockedStatic<OBContext> obContext = mockSessionContext()) {
      stubLoaders(Collections.singletonList(accountRow(ACC_ID, "10.00", EUR_ID, "EUR", false)),
          Collections.emptyMap(), Collections.emptySet());

      NeoResponse out = handler.afterHandle(ctx);

      JSONObject outRow = out.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertTrue(outRow.getBoolean("deletable"));
      assertFalse("no reason field at all when the account is deletable",
          outRow.has("deleteBlockedReason"));
    }
  }

  /**
   * An account with one or more batched blocker reasons is serialised as NOT deletable, with
   * {@code deleteBlockedReason} joining every reason into one string (mirrors the DELETE 409
   * message's own join).
   */
  @Test
  public void testAfterHandleGetCrudInjectsDeletableFalseWithJoinedReasons() throws Exception {
    JSONObject row = new JSONObject().put("id", ACC_ID);
    NeoContext ctx = getCrudContext(new JSONArray().put(row));

    Map<String, List<String>> blockers = new LinkedHashMap<>();
    blockers.put(ACC_ID, Arrays.asList(
        FinancialAccountDeleteSupport.REASON_TRANSACTIONS, FinancialAccountDeleteSupport.REASON_BANK_CONNECTION));

    try (MockedStatic<OBContext> obContext = mockSessionContext()) {
      stubLoaders(Collections.singletonList(accountRow(ACC_ID, "10.00", EUR_ID, "EUR", false)),
          Collections.emptyMap(), Collections.emptySet(), blockers);

      NeoResponse out = handler.afterHandle(ctx);

      JSONObject outRow = out.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertFalse(outRow.getBoolean("deletable"));
      String reason = outRow.getString("deleteBlockedReason");
      assertTrue(reason.contains(FinancialAccountDeleteSupport.REASON_TRANSACTIONS));
      assertTrue(reason.contains(FinancialAccountDeleteSupport.REASON_BANK_CONNECTION));
    }
  }

  /**
   * The collection-level {@code summary} is attached as a SIBLING of {@code response.data}
   * (NEO serialises the handler body verbatim, and the frontend reads it through
   * {@code useEntity}'s {@code meta}). It aggregates only the ACTIVE rows present in this
   * response, so archived accounts never skew the sidebar totals.
   */
  @Test
  public void testAfterHandleGetCrudAttachesSummarySiblingOverVisibleActiveRows() throws Exception {
    JSONArray rows = new JSONArray()
        .put(new JSONObject().put("id", ACC_ID))
        .put(new JSONObject().put("id", "acc-2"))
        .put(new JSONObject().put("id", "acc-archived"));
    NeoContext ctx = getCrudContext(rows);

    FinancialAccountsPageHandler.AccountRow eur = accountRow(ACC_ID, "1000.00", EUR_ID, "EUR", true);
    FinancialAccountsPageHandler.AccountRow usd = accountRow("acc-2", "250.00", "100", "USD", false);
    FinancialAccountsPageHandler.AccountRow archived = accountRow("acc-archived", "999.00", EUR_ID, "EUR", false);
    archived.active = false;

    Map<String, Integer> pending = new LinkedHashMap<>();
    pending.put(ACC_ID, 3);

    try (MockedStatic<OBContext> obContext = mockSessionContext()) {
      stubLoaders(Arrays.asList(eur, usd, archived), pending, Collections.emptySet());

      NeoResponse out = handler.afterHandle(ctx);

      JSONObject envelope = out.getBody().getJSONObject("response");
      JSONObject summary = envelope.optJSONObject("summary");
      assertNotNull("summary must sit next to response.data", summary);
      // 1000 + 250; the archived 999 is excluded.
      assertEquals(new BigDecimal("1250.00"), new BigDecimal(summary.getString("totalBalance")));
      assertEquals(2, summary.getJSONArray("byCurrency").length());
      assertEquals(1, summary.getJSONObject("pending").getInt("accountsWithPending"));
      // The rows themselves are still there — the summary is additive.
      assertEquals(3, envelope.getJSONArray("data").length());
    }
  }

  /**
   * A row with no {@code id} cannot be correlated with the loaders, so it keeps the
   * historical contract — {@code hasTransactions} is always present, defaulting to
   * {@code false} — and gets none of the id-keyed derived fields.
   */
  @Test
  public void testAfterHandleGetCrudRowWithoutIdGetsHasTransactionsFalseOnly() throws Exception {
    JSONObject rowNoId = new JSONObject().put("name", "row without id");
    NeoContext ctx = getCrudContext(new JSONArray().put(rowNoId));

    try (MockedStatic<OBContext> obContext = mockSessionContext()) {
      stubLoaders(Collections.emptyList(), Collections.emptyMap(), Collections.emptySet());

      NeoResponse out = handler.afterHandle(ctx);

      JSONObject out0 = out.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertFalse(out0.getBoolean("hasTransactions"));
      assertFalse("pendingCount is never injected — it is a real column on the row",
          out0.has("pendingCount"));
      assertFalse(out0.has("currencyIso"));
      assertFalse(out0.has("countryId"));
      assertFalse(out0.has("countryIso"));
      assertFalse(out0.has("active"));
      assertFalse("no id → the deletable check (ETP-4871) never even runs", out0.has("deletable"));
      verify(handler, never()).loadAccount(any());
    }
  }

  /**
   * A row the loaders do not know about (e.g. visible to the generic CRUD but filtered out
   * of the loader's own query) still gets the id-keyed basics — the transaction flag and the
   * {@code iban} alias — but none of the loader-only columns.
   */
  @Test
  public void testAfterHandleGetCrudRowUnknownToLoadersKeepsIdKeyedFieldsOnly() throws Exception {
    JSONObject row = new JSONObject().put("id", "acc-orphan").put("iBAN", ES_IBAN);
    NeoContext ctx = getCrudContext(new JSONArray().put(row));

    try (MockedStatic<OBContext> obContext = mockSessionContext()) {
      stubLoaders(Collections.emptyList(), Collections.emptyMap(),
          Collections.singleton("acc-orphan"));

      NeoResponse out = handler.afterHandle(ctx);

      JSONObject out0 = out.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertTrue(out0.getBoolean("hasTransactions"));
      assertFalse("pendingCount is a real column, not an injected field", out0.has("pendingCount"));
      assertEquals(ES_IBAN, out0.getString("iban"));
      assertFalse("loader-only column", out0.has("bankConnected"));
      assertFalse("loader-only column", out0.has("active"));
      assertFalse("loader-only column", out0.has("providerLogoUrl"));
      assertFalse("loader-only column", out0.has("countryId"));
      assertFalse("loader-only column", out0.has("countryIso"));
      // deletable (ETP-4871) is id-keyed, not loader-row-keyed: it is set from the batched
      // deleteBlockersByAccount map BEFORE the byId correlation check, so it is still present
      // (and true, since the stub's map is empty) even though every loader-only column is absent.
      assertTrue("id-keyed field, set independently of the byId loader row",
          out0.getBoolean("deletable"));
    }
  }

  /** A GET/CRUD response with no data array (empty list) is left untouched (returns null). */
  @Test
  public void testAfterHandleGetCrudWithNoDataReturnsNull() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getPreviousResult()).thenReturn(null);

    assertNull(handler.afterHandle(ctx));
    verify(handler, never()).pageLoaders();
  }

  /** A non-CRUD GET (e.g. SELECTOR) is not touched by the derived-field injection. */
  @Test
  public void testAfterHandleGetNonCrudEndpointSkipsInjection() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.SELECTOR);

    assertNull(handler.afterHandle(ctx));
    verify(handler, never()).pageLoaders();
    verify(handler, never()).loadAccount(any());
  }

  /**
   * A loader failure must never break the GET: the generic CRUD response is already valid,
   * so the hook logs and returns null (leaving the un-enriched rows) instead of a 500.
   */
  @Test
  public void testAfterHandleGetCrudSwallowsLoaderFailure() throws Exception {
    NeoContext ctx = getCrudContext(new JSONArray().put(new JSONObject().put("id", ACC_ID)));

    try (MockedStatic<OBContext> obContext = mockSessionContext()) {
      doThrow(new RuntimeException("boom")).when(handler).pageLoaders();

      assertNull(handler.afterHandle(ctx));
    }
  }

  /** pageLoaders() real body: hands back a usable loader instance. */
  @Test
  public void testPageLoadersReturnsAnAccountsPageHandler() {
    assertNotNull(new FinancialAccountHandler().pageLoaders());
  }

  /** hasTransactions(FIN_FinancialAccount) real body: true when the criteria finds a row. */
  @Test
  public void testHasTransactionsReturnsTrueWhenCriteriaFindsActiveTransaction() {
    FinancialAccountHandler h = new FinancialAccountHandler();
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_FinaccTransaction> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_FinaccTransaction.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(mock(FIN_FinaccTransaction.class));

      assertTrue(h.hasTransactions(account));
      verify(criteria).setMaxResults(1);
    }
  }

  /** hasTransactions(FIN_FinancialAccount) real body: false when the criteria finds no row. */
  @Test
  public void testHasTransactionsReturnsFalseWhenCriteriaFindsNoActiveTransaction() {
    FinancialAccountHandler h = new FinancialAccountHandler();
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_FinaccTransaction> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_FinaccTransaction.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      assertFalse(h.hasTransactions(account));
    }
  }

  // ── extractCreatedId (real body) ──────────────────────────────────────────

  /** A null previous result yields no id. */
  @Test
  public void testExtractCreatedIdNullPreviousReturnsNull() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getPreviousResult()).thenReturn(null);
    assertNull(new FinancialAccountHandler().extractCreatedId(ctx));
  }

  /** The id is read from a {@code response.data[0].id} array envelope. */
  @Test
  public void testExtractCreatedIdFromDataArray() throws Exception {
    JSONObject dataRow = new JSONObject().put("id", "acc-9");
    JSONObject response = new JSONObject().put("data", new JSONArray().put(dataRow));
    JSONObject body = new JSONObject().put("response", response);
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getPreviousResult()).thenReturn(new NeoResponse(201, body));

    assertEquals("acc-9", new FinancialAccountHandler().extractCreatedId(ctx));
  }

  /** The id is read from a {@code response.data.id} object envelope. */
  @Test
  public void testExtractCreatedIdFromDataObject() throws Exception {
    JSONObject dataRow = new JSONObject().put("id", "acc-7");
    JSONObject response = new JSONObject().put("data", dataRow);
    JSONObject body = new JSONObject().put("response", response);
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getPreviousResult()).thenReturn(new NeoResponse(201, body));

    assertEquals("acc-7", new FinancialAccountHandler().extractCreatedId(ctx));
  }

  /** A body without a {@code response} wrapper yields no id. */
  @Test
  public void testExtractCreatedIdMissingResponseReturnsNull() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getPreviousResult()).thenReturn(new NeoResponse(201, new JSONObject()));
    assertNull(new FinancialAccountHandler().extractCreatedId(ctx));
  }

}
