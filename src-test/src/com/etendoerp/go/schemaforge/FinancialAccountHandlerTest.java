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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.MatchingAlgorithm;

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

  /** DELETE routes to the soft-archive and short-circuits. */
  @Test
  public void testHandleDeleteRoutesToArchive() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(true).when(handler).hasOpenReconciliations(account);

    NeoResponse response = handler.handle(contextFor("DELETE", null, ACC_ID));

    assertEquals(409, response.getHttpStatus());
    verify(handler).archive(ACC_ID);
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
   * When the body carries an IBAN, the country derived from its ISO prefix is
   * injected into the body BEFORE the generic insert — the row-level trigger
   * FIN_FINANCIAL_ACCOUNT_TRG2 rejects a bank account with an IBAN but no country.
   */
  @Test
  public void testCreateWithIbanInjectsCountry() throws Exception {
    JSONObject body = validCreateBody().put("iBAN", ES_IBAN);
    stubValidCreate();
    Country spain = mock(Country.class);
    when(spain.getId()).thenReturn("106");
    doReturn(spain).when(handler).resolveCountryFromIban(ES_IBAN);

    assertNull(handler.validateAndEnrichCreate(body));
    assertEquals("106", body.getString("country"));
  }

  /** An IBAN whose prefix matches no active country injects nothing. */
  @Test
  public void testCreateWithUnknownIbanPrefixInjectsNoCountry() throws Exception {
    JSONObject body = validCreateBody().put("iBAN", "XX0012345678");
    stubValidCreate();
    doReturn(null).when(handler).resolveCountryFromIban("XX0012345678");

    assertNull(handler.validateAndEnrichCreate(body));
    assertFalse(body.has("country"));
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

  /** An IBAN sent on update re-syncs the derived country into the body. */
  @Test
  public void testUpdateWithIbanSyncsCountry() throws Exception {
    JSONObject body = new JSONObject().put("iBAN", ES_IBAN);
    Country spain = mock(Country.class);
    when(spain.getId()).thenReturn("106");
    doReturn(spain).when(handler).resolveCountryFromIban(ES_IBAN);

    assertNull(handler.validateAndEnrichUpdate(ACC_ID, body));
    assertEquals("106", body.getString("country"));
  }

  /** A too-long IBAN on update is rejected with a 400. */
  @Test
  public void testUpdateIbanTooLongReturns400() throws Exception {
    String longIban = new String(new char[35]).replace('\0', '9');
    JSONObject body = new JSONObject().put("iBAN", longIban);
    assertEquals(400, handler.validateAndEnrichUpdate(ACC_ID, body).getHttpStatus());
  }

  // ── delete: soft-archive ─────────────────────────────────────────────────

  /** A blank id is rejected with a 400 before any account lookup. */
  @Test
  public void testArchiveMissingIdReturns400() {
    NeoResponse response = handler.archive("  ");
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).loadAccount(any());
  }

  /** An id that resolves to no account is rejected with a 400. */
  @Test
  public void testArchiveMissingAccountReturns400() {
    doReturn(null).when(handler).loadAccount(ACC_ID);
    assertEquals(400, handler.archive(ACC_ID).getHttpStatus());
  }

  /** An account with open reconciliations cannot be archived → 409. */
  @Test
  public void testArchiveOpenReconciliationsReturns409() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(true).when(handler).hasOpenReconciliations(account);

    assertEquals(409, handler.archive(ACC_ID).getHttpStatus());
    verify(account, never()).setActive(false);
  }

  /** A clean archive soft-deletes (IsActive='N') and returns 204. */
  @Test
  public void testArchiveHappySoftDeletesAndReturns204() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(false).when(handler).hasOpenReconciliations(account);

    try (org.mockito.MockedStatic<org.openbravo.dal.service.OBDal> obDalMock =
        org.mockito.Mockito.mockStatic(org.openbravo.dal.service.OBDal.class)) {
      org.openbravo.dal.service.OBDal dal = mock(org.openbravo.dal.service.OBDal.class);
      obDalMock.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);

      NeoResponse response = handler.archive(ACC_ID);

      assertEquals(204, response.getHttpStatus());
      verify(account).setActive(false);
      verify(dal).save(account);
      verify(dal).flush();
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
}
