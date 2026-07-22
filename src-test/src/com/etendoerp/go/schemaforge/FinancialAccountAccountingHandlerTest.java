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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.FIN_FinancialAccountAccounting;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Unit tests for {@link FinancialAccountAccountingHandler}.
 *
 * <p>Mirrors the Mockito {@code MockedStatic} isolation approach used by
 * {@link GeneralLedgerConfigurationHandlerTest}: {@link OBDal} and {@link OBContext} are stubbed
 * statically so no DB or CDI container is required. {@link OBProvider} is additionally mocked
 * per-test (try-with-resources) only in the find-or-create scenarios, mirroring the pattern used
 * in {@code PriceListHeaderHandlerTest} for the same "reuse or create" shape.</p>
 *
 * <p>Coverage:</p>
 * <ul>
 *   <li>routing guards (spec / entity / endpoint type / unsupported HTTP method)</li>
 *   <li>GET returns the existing (account, ledger) accounting row</li>
 *   <li>GET soft-degrades (200, {@code ledgerConfigured:false}) when the org has no ledger</li>
 *   <li>save hard-fails (400) when the org has no ledger — asymmetric vs. GET's soft-degrade</li>
 *   <li>save find-or-create: creates a new row when none exists, reuses the existing one otherwise</li>
 *   <li>required-field validation (missing {@code fINAssetAcct})</li>
 *   <li>cross-ledger {@link AccountingCombination} validation (rejects a combo from another ledger)</li>
 *   <li>{@code enablebankstatement} is forced to {@code true} on every successful save</li>
 *   <li>catalog-building from active {@link AccountingCombination}s</li>
 *   <li>{@code @Named} qualifier sanity (no competing CDI scope annotation)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FinancialAccountAccountingHandlerTest {

  private static final String SPEC = "financial-account";
  private static final String ENTITY = "accountingConfiguration";
  private static final String PARAM_ACCOUNT_ID = "financialAccountId";
  private static final String ACCOUNT_ID = "acct-001";
  private static final String LEDGER_ID = "ledger-001";

  private FinancialAccountAccountingHandler handler;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private OBDal obDal;

  private FIN_FinancialAccount account;
  private Organization org;
  private AcctSchema ledger;

  @BeforeEach
  void setUp() {
    handler = new FinancialAccountAccountingHandler();
    obDal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    // setAdminMode / restorePreviousMode become no-ops on the mocked static.
    obContextMock = mockStatic(OBContext.class);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private NeoContext getCtx(String accountId) {
    return NeoContext.builder()
        .httpMethod("GET")
        .specName(SPEC)
        .entityName(ENTITY)
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(accountId == null ? Map.of() : Map.of(PARAM_ACCOUNT_ID, accountId))
        .build();
  }

  private NeoContext saveCtx(String method, JSONObject body) {
    return NeoContext.builder()
        .httpMethod(method)
        .specName(SPEC)
        .entityName(ENTITY)
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
  }

  private void wireAccountWithLedger() {
    ledger = mock(AcctSchema.class);
    when(ledger.getId()).thenReturn(LEDGER_ID);

    org = mock(Organization.class);
    when(org.getGeneralLedger()).thenReturn(ledger);

    Client client = mock(Client.class);

    account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACCOUNT_ID);
    when(account.getOrganization()).thenReturn(org);
    when(account.getClient()).thenReturn(client);

    when(obDal.get(FIN_FinancialAccount.class, ACCOUNT_ID)).thenReturn(account);
  }

  private void wireAccountWithoutLedger() {
    org = mock(Organization.class);
    when(org.getGeneralLedger()).thenReturn(null);

    account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACCOUNT_ID);
    when(account.getOrganization()).thenReturn(org);

    when(obDal.get(FIN_FinancialAccount.class, ACCOUNT_ID)).thenReturn(account);
  }

  @SuppressWarnings("unchecked")
  private void wireFindRowCriteria(FIN_FinancialAccountAccounting row) {
    OBCriteria<FIN_FinancialAccountAccounting> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccountAccounting.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrder(any())).thenReturn(criteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(row);
  }

  @SuppressWarnings("unchecked")
  private void wireAccountOptionsCriteria(List<AccountingCombination> combos) {
    OBCriteria<AccountingCombination> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(AccountingCombination.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrder(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(combos);
  }

  private AccountingCombination combination(String id, ElementValue account, String combo,
      String description) {
    AccountingCombination combination = mock(AccountingCombination.class);
    when(combination.getId()).thenReturn(id);
    when(combination.getAccount()).thenReturn(account);
    when(combination.getCombination()).thenReturn(combo);
    when(combination.getDescription()).thenReturn(description);
    return combination;
  }

  private JSONObject row(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
  }

  // ── routing guards ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("Non-CRUD endpoint passes through (null)")
  void nonCrudEndpointReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").specName(SPEC).entityName(ENTITY)
        .endpointType(NeoEndpointType.ACTION).build();
    assertNull(handler.handle(ctx));
  }

  @Test
  @DisplayName("Different spec passes through (null)")
  void otherSpecReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").specName("sales-order").entityName(ENTITY)
        .endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.handle(ctx));
  }

  @Test
  @DisplayName("Different entity passes through (null)")
  void otherEntityReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").specName(SPEC).entityName("Lines")
        .endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.handle(ctx));
  }

  @Test
  @DisplayName("Unsupported HTTP method (DELETE) passes through (null)")
  void deleteMethodReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("DELETE").specName(SPEC).entityName(ENTITY)
        .endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.handle(ctx));
  }

  // ── GET ──────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("GET returns the existing accounting row for the account+ledger combination")
  void getReturnsExistingRowForAccountAndLedger() throws Exception {
    wireAccountWithLedger();

    AccountingCombination assetCombo = combination("asset-1", null, "572000", "Cuenta bancaria");

    FIN_FinancialAccountAccounting existingRow = mock(FIN_FinancialAccountAccounting.class);
    when(existingRow.getId()).thenReturn("row-1");
    when(existingRow.getFINAssetAcct()).thenReturn(assetCombo);
    when(existingRow.getFINTransitoryAcct()).thenReturn(null);

    wireFindRowCriteria(existingRow);
    wireAccountOptionsCriteria(Collections.emptyList());

    NeoResponse response = handler.handle(getCtx(ACCOUNT_ID));

    assertEquals(200, response.getHttpStatus());
    JSONObject row = row(response);
    assertEquals("row-1", row.getString("id"));
    assertEquals(ACCOUNT_ID, row.getString("financialAccountId"));
    assertEquals("asset-1", row.getString("fINAssetAcct"));
    assertTrue(row.getString("fINAssetAcct$_identifier").contains("572000"));
    assertTrue(row.isNull("fINTransitoryAcct"));
    assertTrue(row.getBoolean("ledgerConfigured"));
  }

  @Test
  @DisplayName("GET soft-degrades (200, ledgerConfigured=false) when the org has no ledger configured")
  void getSoftDegradesWhenOrgHasNoLedgerConfigured() throws Exception {
    wireAccountWithoutLedger();

    NeoResponse response = handler.handle(getCtx(ACCOUNT_ID));

    assertEquals(200, response.getHttpStatus());
    JSONObject row = row(response);
    assertFalse(row.getBoolean("ledgerConfigured"));
    assertTrue(row.isNull("fINAssetAcct"));
    assertTrue(row.isNull("fINTransitoryAcct"));
    assertEquals(0, row.getJSONObject("catalogs").getJSONArray("accounts").length());
  }

  // ── save — guard rails ───────────────────────────────────────────────────────

  @Test
  @DisplayName("Save with null body returns 400")
  void saveWithNullBodyReturns400() {
    NeoResponse response = handler.handle(saveCtx("POST", null));
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  @DisplayName("Save hard-fails with 400 when the org has no ledger (asymmetric vs. GET's soft-degrade)")
  void saveThrowsWhenOrgHasNoLedgerConfigured() throws Exception {
    wireAccountWithoutLedger();

    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("fINAssetAcct", "asset-1");

    NeoResponse response = handler.handle(saveCtx("POST", body));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("no general ledger configured"));
  }

  @Test
  @DisplayName("Save rejects a missing required fINAssetAcct with 400")
  void saveRejectsMissingRequiredAssetAccount() throws Exception {
    wireAccountWithLedger();

    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    NeoResponse response = handler.handle(saveCtx("POST", body));

    assertEquals(400, response.getHttpStatus());
    assertEquals("Cuenta bancaria is required",
        response.getBody().getJSONObject("error").getString("message"));
  }

  @Test
  @DisplayName("Save throws when the submitted AccountingCombination belongs to a different ledger")
  void saveCrossLedgerCombinationThrows400() throws Exception {
    wireAccountWithLedger();

    AcctSchema otherLedger = mock(AcctSchema.class);
    when(otherLedger.getId()).thenReturn("other-ledger");

    AccountingCombination badCombo = mock(AccountingCombination.class);
    when(badCombo.getId()).thenReturn("asset-bad");
    when(badCombo.getAccountingSchema()).thenReturn(otherLedger);
    when(obDal.get(AccountingCombination.class, "asset-bad")).thenReturn(badCombo);

    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("fINAssetAcct", "asset-bad");

    NeoResponse response = handler.handle(saveCtx("POST", body));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("does not belong to the account's ledger"));
  }

  // ── save — find-or-create ────────────────────────────────────────────────────

  @Test
  @DisplayName("Save creates a new row (find-or-create) when none exists yet, and forces enablebankstatement=true")
  void savePostCreatesNewRowWhenNoneExists() throws Exception {
    wireAccountWithLedger();
    wireFindRowCriteria(null);
    wireAccountOptionsCriteria(Collections.emptyList());

    AccountingCombination assetCombo = combination("asset-1", null, "572000", "Cuenta bancaria");
    when(assetCombo.getAccountingSchema()).thenReturn(ledger);
    when(obDal.get(AccountingCombination.class, "asset-1")).thenReturn(assetCombo);

    AccountingCombination transitoryCombo = combination("transitory-1", null, "555000", "Transitoria");
    when(transitoryCombo.getAccountingSchema()).thenReturn(ledger);
    when(obDal.get(AccountingCombination.class, "transitory-1")).thenReturn(transitoryCombo);

    FIN_FinancialAccountAccounting newRow = mock(FIN_FinancialAccountAccounting.class);
    when(newRow.getId()).thenReturn("row-new-1");
    when(newRow.getFINAssetAcct()).thenReturn(assetCombo);
    when(newRow.getFINTransitoryAcct()).thenReturn(transitoryCombo);

    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("fINAssetAcct", "asset-1")
        .put("fINTransitoryAcct", "transitory-1");

    try (MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_FinancialAccountAccounting.class)).thenReturn(newRow);

      NeoResponse response = handler.handle(saveCtx("POST", body));

      assertEquals(200, response.getHttpStatus());
      verify(provider).get(FIN_FinancialAccountAccounting.class);
      verify(newRow).setNewOBObject(true);
      verify(newRow).setAccount(account);
      verify(newRow).setAccountingSchema(ledger);
      verify(newRow).setFINAssetAcct(assetCombo);
      verify(newRow).setFINTransitoryAcct(transitoryCombo);
      // The enablebankstatement side effect must fire on every successful save.
      verify(newRow).setEnablebankstatement(true);
      verify(obDal).save(newRow);
      verify(obDal).flush();

      JSONObject row = row(response);
      assertEquals("row-new-1", row.getString("id"));
      assertEquals("asset-1", row.getString("fINAssetAcct"));
      assertEquals("transitory-1", row.getString("fINTransitoryAcct"));
    }
  }

  @Test
  @DisplayName("Save updates the existing row (find-or-create reuse) without touching OBProvider,"
      + " and still forces enablebankstatement=true")
  void savePutUpdatesExistingRow() throws Exception {
    wireAccountWithLedger();

    AccountingCombination assetCombo = combination("asset-2", null, "572100", "Cuenta bancaria 2");
    when(assetCombo.getAccountingSchema()).thenReturn(ledger);
    when(obDal.get(AccountingCombination.class, "asset-2")).thenReturn(assetCombo);

    FIN_FinancialAccountAccounting existingRow = mock(FIN_FinancialAccountAccounting.class);
    when(existingRow.getId()).thenReturn("row-existing");
    when(existingRow.getFINAssetAcct()).thenReturn(assetCombo);
    when(existingRow.getFINTransitoryAcct()).thenReturn(null);

    wireFindRowCriteria(existingRow);
    wireAccountOptionsCriteria(Collections.emptyList());

    // Body omits fINTransitoryAcct — the row's transitory account must be explicitly cleared.
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("fINAssetAcct", "asset-2");

    try (MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);

      NeoResponse response = handler.handle(saveCtx("PUT", body));

      assertEquals(200, response.getHttpStatus());
      // find-or-create must reuse the existing row: OBProvider is never asked for a new one.
      obProviderMock.verifyNoInteractions();
      verify(existingRow).setFINAssetAcct(assetCombo);
      verify(existingRow).setFINTransitoryAcct(null);
      // The enablebankstatement side effect must fire on every successful save.
      verify(existingRow).setEnablebankstatement(true);
      verify(obDal).save(existingRow);
      verify(obDal).flush();

      JSONObject row = row(response);
      assertEquals("row-existing", row.getString("id"));
    }
  }

  // ── catalog building ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("GET builds the accounts catalog from active AccountingCombinations")
  void catalogBuildsFromActiveAccountingCombinations() throws Exception {
    wireAccountWithLedger();
    wireFindRowCriteria(null);

    ElementValue accountEl = mock(ElementValue.class);
    when(accountEl.getSearchKey()).thenReturn("572");
    when(accountEl.getName()).thenReturn("Bancos");

    AccountingCombination comboWithAccount = combination("combo-1", accountEl, "572000", "ignored");
    AccountingCombination comboWithoutAccount = combination("combo-2", null, "430000", "Clientes");

    wireAccountOptionsCriteria(List.of(comboWithAccount, comboWithoutAccount));

    JSONObject row = row(handler.handle(getCtx(ACCOUNT_ID)));
    JSONArray accounts = row.getJSONObject("catalogs").getJSONArray("accounts");

    assertEquals(2, accounts.length());
    JSONObject item0 = accounts.getJSONObject(0);
    assertEquals("combo-1", item0.getString("id"));
    assertEquals("572", item0.getString("code"));
    // resolveCombinationLabel formats "code — name" when both are present.
    assertEquals("572 — Bancos", item0.getString("name"));

    JSONObject item1 = accounts.getJSONObject(1);
    assertEquals("combo-2", item1.getString("id"));
    assertEquals("430000", item1.getString("code"));
    assertEquals("430000 — Clientes", item1.getString("name"));
  }

  // ── @Named qualifier sanity ──────────────────────────────────────────────────

  @Test
  @DisplayName("Handler implements NeoHandler")
  void implementsNeoHandler() {
    assertTrue(NeoHandler.class.isAssignableFrom(FinancialAccountAccountingHandler.class));
  }

  @Test
  @DisplayName("Handler carries only @Named — no competing CDI scope annotation (e.g. @ApplicationScoped)")
  void namedQualifierAnnotationOnlyNoScopeAnnotation() {
    assertTrue(FinancialAccountAccountingHandler.class.isAnnotationPresent(Named.class));
    Named named = FinancialAccountAccountingHandler.class.getAnnotation(Named.class);
    assertEquals("financialAccountAccountingHandler", named.value());
    // Per docs/neo-headless-extensibility.md §2.2: a normal CDI scope (e.g. @ApplicationScoped)
    // resolves to a Weld client proxy whose subclass does not carry the non-@Inherited @Named,
    // so lookupHandler() would silently skip this bean. Only @Named must be present.
    assertEquals(1, FinancialAccountAccountingHandler.class.getAnnotations().length,
        "handler must carry only @Named — any additional scope annotation breaks @Named lookup");
  }

  // ── error handling ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("Unexpected RuntimeException returns 500")
  void unexpectedExceptionReturns500() {
    when(obDal.get(FIN_FinancialAccount.class, ACCOUNT_ID))
        .thenThrow(new RuntimeException("boom"));

    NeoResponse response = handler.handle(getCtx(ACCOUNT_ID));
    assertEquals(500, response.getHttpStatus());
  }
}
