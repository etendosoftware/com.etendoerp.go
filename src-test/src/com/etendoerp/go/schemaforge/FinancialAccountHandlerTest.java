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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
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
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;
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

  /** When the defaults endpoint cannot resolve a client currency, it leaves the generic response unchanged. */
  @Test
  public void testAfterHandleDefaultsWithoutClientCurrencyReturnsNull() {
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

      assertNull(handler.afterHandle(ctx));
      verify(handler, never()).extractCreatedId(any());
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
  //   2. The accounts-list derived fields (ETP-4658) — pendingCount, psd2Connected,
  //      psd2Pending, currencyIso/currencyId, isDefault, maskedPan, active and the
  //      lowercase `iban` alias — plus a collection-level `summary` sibling of
  //      `response.data` for the list sidebar. These used to be computed by the bespoke
  //      `financial-accounts-page` R spec; the W spec is now the single source of truth.
  //
  // The three SQL loaders are reached through the package-private `pageLoaders()` seam and
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
   * with its three SQL loaders stubbed. {@code buildSummary} is deliberately left real so the
   * aggregation the sidebar renders is exercised end-to-end.
   */
  private FinancialAccountsPageHandler stubLoaders(List<FinancialAccountsPageHandler.AccountRow> rows,
      Map<String, Integer> pending, Set<String> withTransactions) throws Exception {
    FinancialAccountsPageHandler loaders = spy(new FinancialAccountsPageHandler());
    doReturn(ORGS).when(loaders).accessibleOrgs(ORG_ID);
    doReturn(rows).when(loaders).loadAccounts(eq(CLIENT_ID), eq(ORGS));
    doReturn(pending).when(loaders).loadPendingByAccount(eq(CLIENT_ID), eq(ORGS));
    doReturn(withTransactions).when(loaders).loadAccountsWithTransactions(eq(CLIENT_ID), eq(ORGS));
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
   * the pending counter, the PSD2 flags, the currency pair, the default/archived flags, the
   * masked card number and the lowercase {@code iban} alias of the contract's {@code iBAN}.
   */
  @Test
  public void testAfterHandleGetCrudInjectsDerivedListFieldsPerRow() throws Exception {
    JSONObject row1 = new JSONObject().put("id", ACC_ID).put("iBAN", ES_IBAN);
    JSONObject row2 = new JSONObject().put("id", "acc-2").put("iBAN", "");
    NeoContext ctx = getCrudContext(new JSONArray().put(row1).put(row2));

    FinancialAccountsPageHandler.AccountRow loaded1 = accountRow(ACC_ID, "1500.00", EUR_ID, "EUR", true);
    loaded1.psd2Connected = true;
    FinancialAccountsPageHandler.AccountRow loaded2 = accountRow("acc-2", "0.00", "100", "USD", false);
    loaded2.active = false;
    loaded2.maskedPan = "**** 4321";

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
      assertEquals(4, first.getInt("pendingCount"));
      assertTrue(first.getBoolean("psd2Connected"));
      assertFalse("psd2Pending is never computed server-side yet",
          first.getBoolean("psd2Pending"));
      assertEquals("EUR", first.getString("currencyIso"));
      assertEquals(EUR_ID, first.getString("currencyId"));
      assertTrue(first.getBoolean("isDefault"));
      assertTrue(first.getBoolean("active"));
      assertEquals("lowercase alias of the contract's iBAN", ES_IBAN, first.getString("iban"));

      JSONObject second = outArr.getJSONObject(1);
      assertFalse("account without transactions leaves the Currency field editable",
          second.getBoolean("hasTransactions"));
      assertEquals("no pending statement lines defaults to 0", 0, second.getInt("pendingCount"));
      assertFalse(second.getBoolean("psd2Connected"));
      assertEquals("USD", second.getString("currencyIso"));
      assertFalse("archived accounts are flagged so the Inactivas filter can find them",
          second.getBoolean("active"));
      assertEquals("**** 4321", second.getString("maskedPan"));
    }
  }

  /**
   * The N+1 fix: the three loaders run ONCE for the whole page regardless of how many rows
   * the generic CRUD returned, and the per-row work is a Map/Set lookup.
   */
  @Test
  public void testAfterHandleGetCrudRunsEachLoaderOnceForTheWholePage() throws Exception {
    JSONArray rows = new JSONArray()
        .put(new JSONObject().put("id", ACC_ID))
        .put(new JSONObject().put("id", "acc-2"))
        .put(new JSONObject().put("id", "acc-3"));
    NeoContext ctx = getCrudContext(rows);

    try (MockedStatic<OBContext> obContext = mockSessionContext()) {
      FinancialAccountsPageHandler loaders = stubLoaders(
          Arrays.asList(
              accountRow(ACC_ID, "10.00", EUR_ID, "EUR", false),
              accountRow("acc-2", "20.00", EUR_ID, "EUR", false),
              accountRow("acc-3", "30.00", EUR_ID, "EUR", false)),
          Collections.emptyMap(), Collections.emptySet());

      handler.afterHandle(ctx);

      verify(loaders, times(1)).loadAccounts(CLIENT_ID, ORGS);
      verify(loaders, times(1)).loadPendingByAccount(CLIENT_ID, ORGS);
      verify(loaders, times(1)).loadAccountsWithTransactions(CLIENT_ID, ORGS);
      // The legacy per-row DAL seams must not be reached at all any more.
      verify(handler, never()).loadAccount(any());
      verify(handler, never()).hasTransactions(any());
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
      assertFalse("no id → nothing to correlate a pending counter with", out0.has("pendingCount"));
      assertFalse(out0.has("currencyIso"));
      assertFalse(out0.has("active"));
      verify(handler, never()).loadAccount(any());
    }
  }

  /**
   * A row the loaders do not know about (e.g. visible to the generic CRUD but filtered out
   * of the loader's own query) still gets the id-keyed basics — the transaction flag, the
   * pending counter and the {@code iban} alias — but none of the loader-only columns.
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
      assertEquals(0, out0.getInt("pendingCount"));
      assertEquals(ES_IBAN, out0.getString("iban"));
      assertFalse("loader-only column", out0.has("psd2Connected"));
      assertFalse("loader-only column", out0.has("active"));
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
