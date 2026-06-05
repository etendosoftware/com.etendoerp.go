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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Mockito-driven unit tests for {@link FinancialAccountHandler} (ETP-4096).
 *
 * <p>Strategy: spy the handler and stub the package-private DAL seams
 * ({@code loadCurrency}, {@code loadAccount}, {@code nameExists},
 * {@code hasOpenReconciliations}, {@code persist}, {@code resolveDefaultCurrency},
 * {@code listCurrencies}) so every {@code create} / {@code update} /
 * {@code archive} / {@code buildDefaults} path runs without a database or a live
 * OBContext. The {@code handle()} routing path is exercised through a mocked
 * {@link NeoContext} that only overrides the HTTP method + query params, with the
 * business methods stubbed so no static OBContext machinery is required.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Wrong HTTP method → 405.</li>
 *   <li>create happy → 201 with id/name; blank name / blank currency / name too
 *       long → 400; duplicate name → 409; invalid currency → 400.</li>
 *   <li>update happy → 200; preserves swiftCode when the key is omitted;
 *       duplicate name → 409; missing account → 400.</li>
 *   <li>archive happy → 204; open reconciliations → 409; missing id → 400.</li>
 *   <li>defaults envelope shape (default currency + currencies array).</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountHandlerTest {

  private static final String SPEC = "financial-account";
  private static final String EUR_ID = "102";
  private static final String ACC_ID = "acc-1";

  private FinancialAccountHandler handler;

  /**
   * Initializes a Mockito spy of the handler before each test so individual
   * methods can stub the DB-bound seams without touching the real DAL layer.
   */
  @Before
  public void setUp() {
    handler = spy(new FinancialAccountHandler());
    // Stub the OBContext/OBDal seams so routing tests run without a live
    // Etendo session (CI has no initialized OBContext on the thread).
    org.mockito.Mockito.doNothing().when(handler).enterAdminMode();
    org.mockito.Mockito.doNothing().when(handler).exitAdminMode();
    org.mockito.Mockito.doNothing().when(handler).doRollbackAndClose();
  }

  // ── handle() routing ─────────────────────────────────────────────────────

  /**
   * A method that is neither GET (defaults) nor POST (create/update/archive)
   * must be rejected with a 405 and none of the business methods invoked.
   */
  @Test
  public void testHandleRejectsUnsupportedMethodWith405() throws Exception {
    NeoContext ctx = ctx("DELETE", null, null, null);

    NeoResponse response = handler.handle(ctx);

    assertNotNull(response);
    assertEquals(405, response.getHttpStatus());
    verify(handler, never()).create(any());
    verify(handler, never()).update(any(), any());
    verify(handler, never()).archive(any());
    verify(handler, never()).buildDefaults();
  }

  /**
   * A plain POST with no {@code action} routes to {@code create} and returns
   * whatever that produces, untouched.
   */
  @Test
  public void testHandlePostRoutesToCreate() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA").put("currencyId", EUR_ID);
    NeoContext ctx = ctx("POST", null, null, body);
    NeoResponse expected = NeoResponse.ok(new JSONObject());
    doReturn(expected).when(handler).create(body);

    assertSame(expected, handler.handle(ctx));
    verify(handler).create(body);
  }

  /**
   * {@code POST ?action=update&id=...} routes to {@code update} with the id from
   * the query string and the request body.
   */
  @Test
  public void testHandlePostUpdateRoutesToUpdate() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA");
    NeoContext ctx = ctx("POST", "update", ACC_ID, body);
    NeoResponse expected = NeoResponse.ok(new JSONObject());
    doReturn(expected).when(handler).update(ACC_ID, body);

    assertSame(expected, handler.handle(ctx));
    verify(handler).update(ACC_ID, body);
  }

  /**
   * {@code POST ?action=archive&id=...} routes to {@code archive} with the id.
   */
  @Test
  public void testHandlePostArchiveRoutesToArchive() throws Exception {
    NeoContext ctx = ctx("POST", "archive", ACC_ID, null);
    NeoResponse expected = NeoResponse.noContent();
    doReturn(expected).when(handler).archive(ACC_ID);

    assertSame(expected, handler.handle(ctx));
    verify(handler).archive(ACC_ID);
  }

  /**
   * {@code GET ?action=defaults} routes to {@code buildDefaults}.
   */
  @Test
  public void testHandleGetDefaultsRoutesToBuildDefaults() throws Exception {
    NeoContext ctx = ctx("GET", "defaults", null, null);
    NeoResponse expected = NeoResponse.ok(new JSONObject());
    doReturn(expected).when(handler).buildDefaults();

    assertSame(expected, handler.handle(ctx));
    verify(handler).buildDefaults();
  }

  /**
   * The handler only claims its own spec — any other spec name falls through to
   * {@code null} so the dispatcher keeps probing other handlers.
   */
  @Test
  public void testHandleForeignSpecReturnsNull() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("sales-order");

    assertEquals(null, handler.handle(ctx));
  }

  /**
   * A business {@link org.openbravo.base.exception.OBException} thrown by a
   * delegate must be translated into a 400 rather than propagating.
   */
  @Test
  public void testHandleTranslatesObExceptionTo400() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA");
    NeoContext ctx = ctx("POST", null, null, body);
    doThrowOb(body);

    NeoResponse response = handler.handle(ctx);

    assertEquals(400, response.getHttpStatus());
  }

  private void doThrowOb(JSONObject body) throws Exception {
    org.mockito.Mockito.doThrow(new org.openbravo.base.exception.OBException("boom"))
        .when(handler).create(body);
  }

  // ── create() ─────────────────────────────────────────────────────────────

  /**
   * Full create happy path: a valid currency, a unique name and an in-memory
   * persisted account produce a 201 carrying the new id + name.
   */
  @Test
  public void testCreateHappyReturns201WithIdAndName() throws Exception {
    JSONObject body = new JSONObject()
        .put("name", "BBVA")
        .put("currencyId", EUR_ID)
        .put("iban", "ES9121000418450200051332")
        .put("swiftCode", "BBVAESMM");

    Currency currency = mock(Currency.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn("new-acc");
    when(account.getName()).thenReturn("BBVA");
    doReturn(currency).when(handler).loadCurrency(EUR_ID);
    doReturn(false).when(handler).nameExists(eq("BBVA"), isNull());
    doReturn(Collections.emptyList()).when(handler).listMatchingAlgorithms();
    doReturn(account).when(handler).persist(eq("BBVA"), eq("B"), eq(currency),
        eq("ES9121000418450200051332"), eq("BBVAESMM"), isNull());

    NeoResponse response = handler.create(body);

    assertEquals(201, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals("new-acc", data.getString("id"));
    assertEquals("BBVA", data.getString("name"));
  }

  /**
   * Cash accounts default to type {@code B} only for the bank flow; an explicit
   * {@code type=C} must be normalised and forwarded to persist as {@code C}.
   */
  @Test
  public void testCreateCashAccountPersistsTypeC() throws Exception {
    JSONObject body = new JSONObject()
        .put("name", "Caja Central")
        .put("currencyId", EUR_ID)
        .put("type", "C");

    Currency currency = mock(Currency.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn("cash-1");
    when(account.getName()).thenReturn("Caja Central");
    doReturn(currency).when(handler).loadCurrency(EUR_ID);
    doReturn(false).when(handler).nameExists(eq("Caja Central"), isNull());
    doReturn(Collections.emptyList()).when(handler).listMatchingAlgorithms();
    doReturn(account).when(handler).persist(eq("Caja Central"), eq("C"), eq(currency),
        eq(""), eq(""), isNull());

    NeoResponse response = handler.create(body);

    assertEquals(201, response.getHttpStatus());
    verify(handler).persist(eq("Caja Central"), eq("C"), eq(currency), eq(""), eq(""), isNull());
  }

  /**
   * A {@code null} body is rejected at the boundary with a 400.
   */
  @Test
  public void testCreateNullBodyReturns400() throws Exception {
    NeoResponse response = handler.create(null);
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).persist(any(), any(), any(), any(), any(), any());
  }

  /**
   * A blank (whitespace-only) name is rejected with a 400 — the trim collapses
   * it to empty.
   */
  @Test
  public void testCreateBlankNameReturns400() throws Exception {
    JSONObject body = new JSONObject().put("name", "   ").put("currencyId", EUR_ID);
    NeoResponse response = handler.create(body);
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).persist(any(), any(), any(), any(), any(), any());
  }

  /**
   * A blank currency id is rejected with a 400 before any currency lookup.
   */
  @Test
  public void testCreateBlankCurrencyReturns400() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA").put("currencyId", "");
    NeoResponse response = handler.create(body);
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).loadCurrency(any());
  }

  /**
   * {@code FIN_Financial_Account.Name} caps at 60 chars; longer names fail fast
   * with a 400.
   */
  @Test
  public void testCreateNameTooLongReturns400() throws Exception {
    JSONObject body = new JSONObject()
        .put("name", repeat("A", 61))
        .put("currencyId", EUR_ID);
    NeoResponse response = handler.create(body);
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).persist(any(), any(), any(), any(), any(), any());
  }

  /**
   * An IBAN longer than 34 chars is rejected with a 400.
   */
  @Test
  public void testCreateIbanTooLongReturns400() throws Exception {
    JSONObject body = new JSONObject()
        .put("name", "BBVA")
        .put("currencyId", EUR_ID)
        .put("iban", repeat("E", 35));
    NeoResponse response = handler.create(body);
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).persist(any(), any(), any(), any(), any(), any());
  }

  /**
   * A SWIFT/BIC longer than 20 chars is rejected with a 400.
   */
  @Test
  public void testCreateSwiftTooLongReturns400() throws Exception {
    JSONObject body = new JSONObject()
        .put("name", "BBVA")
        .put("currencyId", EUR_ID)
        .put("swiftCode", repeat("S", 21));
    NeoResponse response = handler.create(body);
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).persist(any(), any(), any(), any(), any(), any());
  }

  /**
   * When the supplied currency id resolves to {@code null}, the create is
   * rejected with a 400 (invalid currency) before any name-uniqueness check.
   */
  @Test
  public void testCreateInvalidCurrencyReturns400() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA").put("currencyId", "bad");
    doReturn(null).when(handler).loadCurrency("bad");

    NeoResponse response = handler.create(body);

    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).nameExists(any(), any());
    verify(handler, never()).persist(any(), any(), any(), any(), any(), any());
  }

  /**
   * A name already in use (for a new account, so {@code excludeId == null})
   * returns a 409 conflict and never persists.
   */
  @Test
  public void testCreateDuplicateNameReturns409() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA").put("currencyId", EUR_ID);
    Currency currency = mock(Currency.class);
    doReturn(currency).when(handler).loadCurrency(EUR_ID);
    doReturn(true).when(handler).nameExists(eq("BBVA"), isNull());

    NeoResponse response = handler.create(body);

    assertEquals(409, response.getHttpStatus());
    verify(handler, never()).persist(any(), any(), any(), any(), any(), any());
  }

  // ── update() ─────────────────────────────────────────────────────────────

  /**
   * Update happy path: a found account, a unique new name and an unchanged
   * currency produce a 200 with the {@code response.data} envelope carrying the
   * updated id + name.
   */
  @Test
  public void testUpdateHappyReturns200WithEnvelope() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA Renamed");
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    when(account.getName()).thenReturn("BBVA Renamed");
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(false).when(handler).nameExists("BBVA Renamed", ACC_ID);

    try (org.mockito.MockedStatic<org.openbravo.dal.service.OBDal> obDalMock =
        org.mockito.Mockito.mockStatic(org.openbravo.dal.service.OBDal.class)) {
      org.openbravo.dal.service.OBDal dal = mock(org.openbravo.dal.service.OBDal.class);
      obDalMock.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);

      NeoResponse response = handler.update(ACC_ID, body);

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(ACC_ID, data.getString("id"));
      assertEquals("BBVA Renamed", data.getString("name"));
      verify(account).setName("BBVA Renamed");
      verify(dal).save(account);
      verify(dal).flush();
    }
  }

  /**
   * When the body omits the {@code swiftCode} (and {@code iban}) keys — as the
   * Edit modal does — the handler must NOT call {@code setSwiftCode} /
   * {@code setIBAN}, so the stored values are preserved.
   */
  @Test
  public void testUpdatePreservesSwiftCodeWhenKeyOmitted() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA");
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    when(account.getName()).thenReturn("BBVA");
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(false).when(handler).nameExists("BBVA", ACC_ID);

    try (org.mockito.MockedStatic<org.openbravo.dal.service.OBDal> obDalMock =
        org.mockito.Mockito.mockStatic(org.openbravo.dal.service.OBDal.class)) {
      org.openbravo.dal.service.OBDal dal = mock(org.openbravo.dal.service.OBDal.class);
      obDalMock.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);

      NeoResponse response = handler.update(ACC_ID, body);

      assertEquals(200, response.getHttpStatus());
      verify(account, never()).setSwiftCode(any());
      verify(account, never()).setIBAN(any());
    }
  }

  /**
   * When the body explicitly carries an {@code iban} key, the IBAN is updated
   * (trimmed-to-null) so the edit modal can clear or replace it.
   */
  @Test
  public void testUpdateSetsIbanWhenKeyPresent() throws Exception {
    JSONObject body = new JSONObject()
        .put("name", "BBVA")
        .put("iban", "ES9121000418450200051332");
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    Country spain = mock(Country.class);
    when(account.getId()).thenReturn(ACC_ID);
    when(account.getName()).thenReturn("BBVA");
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(false).when(handler).nameExists("BBVA", ACC_ID);
    doReturn(spain).when(handler).resolveCountryFromIban("ES9121000418450200051332");

    try (org.mockito.MockedStatic<org.openbravo.dal.service.OBDal> obDalMock =
        org.mockito.Mockito.mockStatic(org.openbravo.dal.service.OBDal.class)) {
      org.openbravo.dal.service.OBDal dal = mock(org.openbravo.dal.service.OBDal.class);
      obDalMock.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);

      handler.update(ACC_ID, body);

      verify(account).setIBAN("ES9121000418450200051332");
      verify(account).setCountry(spain);
    }
  }

  /**
   * Updating the currency only happens when a non-blank currency id is sent; a
   * valid one resolves and is applied to the account.
   */
  @Test
  public void testUpdateSetsCurrencyWhenProvided() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA").put("currencyId", EUR_ID);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    Currency currency = mock(Currency.class);
    when(account.getId()).thenReturn(ACC_ID);
    when(account.getName()).thenReturn("BBVA");
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(false).when(handler).nameExists("BBVA", ACC_ID);
    doReturn(currency).when(handler).loadCurrency(EUR_ID);

    try (org.mockito.MockedStatic<org.openbravo.dal.service.OBDal> obDalMock =
        org.mockito.Mockito.mockStatic(org.openbravo.dal.service.OBDal.class)) {
      org.openbravo.dal.service.OBDal dal = mock(org.openbravo.dal.service.OBDal.class);
      obDalMock.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);

      handler.update(ACC_ID, body);

      verify(account).setCurrency(currency);
    }
  }

  /**
   * A non-blank but invalid currency id on update is rejected with a 400.
   */
  @Test
  public void testUpdateInvalidCurrencyReturns400() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA").put("currencyId", "bad");
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(false).when(handler).nameExists("BBVA", ACC_ID);
    doReturn(null).when(handler).loadCurrency("bad");

    NeoResponse response = handler.update(ACC_ID, body);

    assertEquals(400, response.getHttpStatus());
  }

  /**
   * A missing/blank id is rejected with a 400 before any account lookup.
   */
  @Test
  public void testUpdateMissingIdReturns400() throws Exception {
    NeoResponse response = handler.update("  ", new JSONObject().put("name", "BBVA"));
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).loadAccount(any());
  }

  /**
   * A {@code null} body is rejected with a 400.
   */
  @Test
  public void testUpdateNullBodyReturns400() throws Exception {
    NeoResponse response = handler.update(ACC_ID, null);
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).loadAccount(any());
  }

  /**
   * An id that resolves to no account is rejected with a 400 (account not
   * found).
   */
  @Test
  public void testUpdateMissingAccountReturns400() throws Exception {
    JSONObject body = new JSONObject().put("name", "BBVA");
    doReturn(null).when(handler).loadAccount(ACC_ID);

    NeoResponse response = handler.update(ACC_ID, body);

    assertEquals(400, response.getHttpStatus());
  }

  /**
   * A blank name on update is rejected with a 400.
   */
  @Test
  public void testUpdateBlankNameReturns400() throws Exception {
    JSONObject body = new JSONObject().put("name", "   ");
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    NeoResponse response = handler.update(ACC_ID, body);

    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).nameExists(any(), any());
  }

  /**
   * A name colliding with a DIFFERENT account (excluding self) returns 409.
   */
  @Test
  public void testUpdateDuplicateNameReturns409() throws Exception {
    JSONObject body = new JSONObject().put("name", "Taken");
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(true).when(handler).nameExists("Taken", ACC_ID);

    NeoResponse response = handler.update(ACC_ID, body);

    assertEquals(409, response.getHttpStatus());
  }

  // ── archive() ────────────────────────────────────────────────────────────

  /**
   * Archive happy path: a found account with no open reconciliations is soft
   * deleted ({@code setActive(false)}) and the handler returns 204 No Content.
   */
  @Test
  public void testArchiveHappyReturns204() throws Exception {
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

  /**
   * A missing/blank id is rejected with a 400 before any account lookup.
   */
  @Test
  public void testArchiveMissingIdReturns400() throws Exception {
    NeoResponse response = handler.archive("   ");
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).loadAccount(any());
  }

  /**
   * An id that resolves to no account is rejected with a 400.
   */
  @Test
  public void testArchiveMissingAccountReturns400() throws Exception {
    doReturn(null).when(handler).loadAccount(ACC_ID);

    NeoResponse response = handler.archive(ACC_ID);

    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).hasOpenReconciliations(any());
  }

  /**
   * Archiving an account that still has open reconciliations is forbidden and
   * returns a 409 — the account stays active.
   */
  @Test
  public void testArchiveOpenReconciliationsReturns409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(true).when(handler).hasOpenReconciliations(account);

    NeoResponse response = handler.archive(ACC_ID);

    assertEquals(409, response.getHttpStatus());
    verify(account, never()).setActive(false);
  }

  // ── buildDefaults() ──────────────────────────────────────────────────────

  /**
   * The defaults endpoint surfaces the resolved session currency plus the full
   * active-currency list, wrapped in the {@code response.data} envelope the New
   * Account form consumes.
   */
  @Test
  public void testBuildDefaultsShapeWithDefaultCurrencyAndList() throws Exception {
    Currency eur = mock(Currency.class);
    when(eur.getId()).thenReturn(EUR_ID);
    when(eur.getISOCode()).thenReturn("EUR");
    when(eur.getSymbol()).thenReturn("€");
    Currency usd = mock(Currency.class);
    when(usd.getId()).thenReturn("100");
    when(usd.getISOCode()).thenReturn("USD");
    when(usd.getSymbol()).thenReturn("$");

    doReturn(eur).when(handler).resolveDefaultCurrency(any());
    doReturn(Arrays.asList(eur, usd)).when(handler).listCurrencies();

    try (org.mockito.MockedStatic<org.openbravo.dal.core.OBContext> obContextMock =
        org.mockito.Mockito.mockStatic(org.openbravo.dal.core.OBContext.class)) {
      org.openbravo.dal.core.OBContext obCtx = mock(org.openbravo.dal.core.OBContext.class);
      org.openbravo.model.common.enterprise.Organization org =
          mock(org.openbravo.model.common.enterprise.Organization.class);
      when(org.getId()).thenReturn("org-1");
      when(obCtx.getCurrentOrganization()).thenReturn(org);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      NeoResponse response = handler.buildDefaults();

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(EUR_ID, data.getString("defaultCurrencyId"));
      assertEquals("EUR", data.getString("defaultCurrencyIso"));
      assertEquals(2, data.getJSONArray("currencies").length());
      JSONObject first = data.getJSONArray("currencies").getJSONObject(0);
      assertEquals(EUR_ID, first.getString("id"));
      assertEquals("EUR", first.getString("iso"));
      assertEquals("€", first.getString("symbol"));
    }
  }

  /**
   * When the org has no resolvable default currency, the defaults payload omits
   * the {@code defaultCurrencyId} keys but still emits the currency list.
   */
  @Test
  public void testBuildDefaultsOmitsDefaultWhenNull() throws Exception {
    doReturn(null).when(handler).resolveDefaultCurrency(any());
    doReturn(Collections.<Currency>emptyList()).when(handler).listCurrencies();

    try (org.mockito.MockedStatic<org.openbravo.dal.core.OBContext> obContextMock =
        org.mockito.Mockito.mockStatic(org.openbravo.dal.core.OBContext.class)) {
      org.openbravo.dal.core.OBContext obCtx = mock(org.openbravo.dal.core.OBContext.class);
      org.openbravo.model.common.enterprise.Organization org =
          mock(org.openbravo.model.common.enterprise.Organization.class);
      when(org.getId()).thenReturn("org-1");
      when(obCtx.getCurrentOrganization()).thenReturn(org);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      NeoResponse response = handler.buildDefaults();

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertTrue("no default currency id when unresolved", !data.has("defaultCurrencyId"));
      assertEquals(0, data.getJSONArray("currencies").length());
    }
  }

  // ── normalizeType() ──────────────────────────────────────────────────────

  /**
   * {@code normalizeType} keeps {@code C} as Cash and coerces everything else
   * (including {@code T}, unknown, and the default) to {@code B} Bank.
   */
  @Test
  public void testNormalizeType() {
    assertEquals("C", handler.normalizeType("C"));
    assertEquals("B", handler.normalizeType("B"));
    assertEquals("B", handler.normalizeType("T"));
    assertEquals("B", handler.normalizeType("anything"));
    assertEquals("B", handler.normalizeType(""));
  }

  // ── afterHandle hooks ────────────────────────────────────────────────────

  /**
   * The handler does not override the default {@link NeoHandler} post-hooks, so
   * both return {@code null} and leave the upstream response untouched.
   */
  @Test
  public void testAfterHandleHooksReturnNullByDefault() {
    NeoContext ctx = mock(NeoContext.class);
    assertEquals(null, handler.afterHandle(ctx));
    assertEquals(null, handler.afterCallout(ctx));
  }

  // ── Fixtures ─────────────────────────────────────────────────────────────

  /**
   * Builds a {@link NeoContext} mock that overrides only the request shape
   * fields the handler routes on: spec name, HTTP method, action + id query
   * params and the request body.
   *
   * @param method the HTTP method
   * @param action the {@code action} query parameter, or {@code null}
   * @param id the {@code id} query parameter, or {@code null}
   * @param body the request body, or {@code null}
   * @return a stubbed NeoContext
   */
  private NeoContext ctx(String method, String action, String id, JSONObject body) {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getHttpMethod()).thenReturn(method);
    Map<String, String> params = new HashMap<>();
    if (action != null) {
      params.put("action", action);
    }
    if (id != null) {
      params.put("id", id);
    }
    when(ctx.getQueryParams()).thenReturn(params);
    when(ctx.getRequestBody()).thenReturn(body);
    return ctx;
  }

  /**
   * Repeats a string {@code count} times — local helper to avoid depending on
   * the Java 11 {@code String.repeat} (kept for clarity of intent).
   *
   * @param token the token to repeat
   * @param count how many times
   * @return the repeated string
   */
  private static String repeat(String token, int count) {
    StringBuilder sb = new StringBuilder(token.length() * count);
    for (int i = 0; i < count; i++) {
      sb.append(token);
    }
    return sb.toString();
  }
}
