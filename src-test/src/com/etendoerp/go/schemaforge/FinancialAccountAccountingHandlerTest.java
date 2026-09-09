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
import static org.mockito.Mockito.never;
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
 * <p>ETP-4872 rewrite: the handler now exposes 9 fields (replacing the old
 * {@code fINAssetAcct}/{@code fINTransitoryAcct} pair) with PATCH-like semantics — a field key
 * omitted from the request body leaves the stored value untouched; a field present with a
 * null/blank value clears it; no field is required.</p>
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
 *   <li>GET returns the existing (account, ledger) accounting row for all 9 fields</li>
 *   <li>GET soft-degrades (200, {@code ledgerConfigured:false}) when the org has no ledger</li>
 *   <li>save hard-fails (400) when the org has no ledger — asymmetric vs. GET's soft-degrade</li>
 *   <li>save find-or-create: creates a new row when none exists, reuses the existing one otherwise</li>
 *   <li>no field is required — an empty-object POST succeeds and creates a fully-null row</li>
 *   <li>a field omitted from the body leaves the stored value untouched (PATCH-like semantics)</li>
 *   <li>a field present-but-blank in the body clears the stored value</li>
 *   <li>cross-ledger {@link AccountingCombination} validation (rejects a combo from another ledger)</li>
*   <li>mixed-ledger request (one valid field + one cross-ledger field in the same body): the
*       earlier-in-call-order valid field's setter still fires before the later field's failure
*       aborts the method — never persisted (obDal.save/flush unreached), but not fully
*       object-level atomic either (ETP-4872 QA)</li>
*   <li>a garbage/non-existent combination id degrades to 400 "not found", never a raw 500</li>
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

  // Field keys — mirrors FinancialAccountAccountingHandler's own constants (ETP-4872, 9 fields).
  private static final String F_BANK_REVAL_GAIN = "fINBankrevaluationgainAcct";
  private static final String F_BANK_REVAL_LOSS = "fINBankrevaluationlossAcct";
  private static final String F_BANK_FEE = "fINBankfeeAcct";
  private static final String F_IN_TRANSIT_IN = "inTransitPaymentAccountIN";
  private static final String F_DEPOSIT = "depositAccount";
  private static final String F_CLEARED_IN = "clearedPaymentAccount";
  private static final String F_IN_TRANSIT_OUT = "fINOutIntransitAcct";
  private static final String F_WITHDRAWAL = "withdrawalAccount";
  private static final String F_CLEARED_OUT = "clearedPaymentAccountOUT";

  private static final List<String> ALL_FIELDS = List.of(
      F_BANK_REVAL_GAIN, F_BANK_REVAL_LOSS, F_BANK_FEE, F_IN_TRANSIT_IN, F_DEPOSIT,
      F_CLEARED_IN, F_IN_TRANSIT_OUT, F_WITHDRAWAL, F_CLEARED_OUT);

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

  /** Wires {@code obDal.get(AccountingCombination.class, id)} to resolve to a same-ledger combo. */
  private AccountingCombination combinationOnLedger(String id, String combo, String description) {
    AccountingCombination combination = combination(id, null, combo, description);
    when(combination.getAccountingSchema()).thenReturn(ledger);
    when(obDal.get(AccountingCombination.class, id)).thenReturn(combination);
    return combination;
  }

  private JSONObject row(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
  }

  /** Body carrying every field with a distinct fake id (fieldName + "-id"). */
  private JSONObject fullBody() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    for (String field : ALL_FIELDS) {
      body.put(field, field + "-id");
    }
    return body;
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
  @DisplayName("GET returns the existing accounting row with all 9 fields + identifiers")
  void getReturnsExistingRowForAllNineFields() throws Exception {
    wireAccountWithLedger();

    ElementValue bankRevalGainEl = mock(ElementValue.class);
    when(bankRevalGainEl.getSearchKey()).thenReturn("76800000");
    when(bankRevalGainEl.getName()).thenReturn("Diferencias positivas de cambio");
    AccountingCombination bankRevalGain = combination("id-1", bankRevalGainEl, "76800000", null);

    AccountingCombination bankRevalLoss = combination("id-2", null, "66800000", "Diferencias negativas");
    AccountingCombination bankFee = combination("id-3", null, "62600000", "Servicios bancarios");
    AccountingCombination inTransitIn = combination("id-4", null, "55500000", "Partidas pendientes");
    AccountingCombination deposit = combination("id-5", null, "57200000", "Bancos");
    AccountingCombination clearedIn = combination("id-6", null, "570001", "Cleared IN");
    AccountingCombination inTransitOut = combination("id-7", null, "55500000", "Partidas pendientes");
    AccountingCombination withdrawal = combination("id-8", null, "57200000", "Bancos");
    AccountingCombination clearedOut = combination("id-9", null, "570002", "Cleared OUT");

    FIN_FinancialAccountAccounting existingRow = mock(FIN_FinancialAccountAccounting.class);
    when(existingRow.getId()).thenReturn("row-1");
    when(existingRow.getFINBankrevaluationgainAcct()).thenReturn(bankRevalGain);
    when(existingRow.getFINBankrevaluationlossAcct()).thenReturn(bankRevalLoss);
    when(existingRow.getFINBankfeeAcct()).thenReturn(bankFee);
    when(existingRow.getInTransitPaymentAccountIN()).thenReturn(inTransitIn);
    when(existingRow.getDepositAccount()).thenReturn(deposit);
    when(existingRow.getClearedPaymentAccount()).thenReturn(clearedIn);
    when(existingRow.getFINOutIntransitAcct()).thenReturn(inTransitOut);
    when(existingRow.getWithdrawalAccount()).thenReturn(withdrawal);
    when(existingRow.getClearedPaymentAccountOUT()).thenReturn(clearedOut);

    wireFindRowCriteria(existingRow);
    wireAccountOptionsCriteria(Collections.emptyList());

    NeoResponse response = handler.handle(getCtx(ACCOUNT_ID));

    assertEquals(200, response.getHttpStatus());
    JSONObject row = row(response);
    assertEquals("row-1", row.getString("id"));
    assertEquals(ACCOUNT_ID, row.getString("financialAccountId"));
    assertEquals("id-1", row.getString(F_BANK_REVAL_GAIN));
    assertTrue(row.getString(F_BANK_REVAL_GAIN + "$_identifier").contains("76800000"));
    assertEquals("id-2", row.getString(F_BANK_REVAL_LOSS));
    assertEquals("id-3", row.getString(F_BANK_FEE));
    assertEquals("id-4", row.getString(F_IN_TRANSIT_IN));
    assertEquals("id-5", row.getString(F_DEPOSIT));
    assertEquals("id-6", row.getString(F_CLEARED_IN));
    assertEquals("id-7", row.getString(F_IN_TRANSIT_OUT));
    assertEquals("id-8", row.getString(F_WITHDRAWAL));
    assertEquals("id-9", row.getString(F_CLEARED_OUT));
    assertTrue(row.getBoolean("ledgerConfigured"));
    // Retired ETP-4530 fields must no longer appear in the response at all.
    assertFalse(row.has("fINAssetAcct"));
    assertFalse(row.has("fINTransitoryAcct"));
  }

  @Test
  @DisplayName("GET soft-degrades (200, ledgerConfigured=false) when the org has no ledger configured")
  void getSoftDegradesWhenOrgHasNoLedgerConfigured() throws Exception {
    wireAccountWithoutLedger();

    NeoResponse response = handler.handle(getCtx(ACCOUNT_ID));

    assertEquals(200, response.getHttpStatus());
    JSONObject row = row(response);
    assertFalse(row.getBoolean("ledgerConfigured"));
    for (String field : ALL_FIELDS) {
      assertTrue(row.isNull(field), field + " should be null when unconfigured");
      assertTrue(row.isNull(field + "$_identifier"), field + "$_identifier should be null");
    }
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
        .put(F_DEPOSIT, "deposit-1");

    NeoResponse response = handler.handle(saveCtx("POST", body));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("no general ledger configured"));
  }

  @Test
  @DisplayName("No field is required — an empty-object POST succeeds and creates a fully-null row")
  void saveEmptyBodySucceedsAndCreatesFullyNullRow() throws Exception {
    wireAccountWithLedger();
    wireFindRowCriteria(null);
    wireAccountOptionsCriteria(Collections.emptyList());

    FIN_FinancialAccountAccounting newRow = mock(FIN_FinancialAccountAccounting.class);
    when(newRow.getId()).thenReturn("row-empty");
    // All 9 getters default to null (unstubbed mock) — a fully-null row.

    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);

    try (MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_FinancialAccountAccounting.class)).thenReturn(newRow);

      NeoResponse response = handler.handle(saveCtx("POST", body));

      assertEquals(200, response.getHttpStatus());
      // No field key was present in the body, so no combination setter must fire at all.
      verify(newRow, never()).setFINBankrevaluationgainAcct(any());
      verify(newRow, never()).setFINBankrevaluationlossAcct(any());
      verify(newRow, never()).setFINBankfeeAcct(any());
      verify(newRow, never()).setInTransitPaymentAccountIN(any());
      verify(newRow, never()).setDepositAccount(any());
      verify(newRow, never()).setClearedPaymentAccount(any());
      verify(newRow, never()).setFINOutIntransitAcct(any());
      verify(newRow, never()).setWithdrawalAccount(any());
      verify(newRow, never()).setClearedPaymentAccountOUT(any());
      // enablebankstatement is still forced true — unconditional side effect of a successful save.
      verify(newRow).setEnablebankstatement(true);
      verify(obDal).save(newRow);
      verify(obDal).flush();

      JSONObject row = row(response);
      for (String field : ALL_FIELDS) {
        assertTrue(row.isNull(field), field + " should be null on a fully-null row");
      }
    }
  }

  @Test
  @DisplayName("A field omitted from the body leaves the stored value untouched (PATCH-like semantics)")
  void saveOmittedFieldLeavesStoredValueUntouched() throws Exception {
    wireAccountWithLedger();

    AccountingCombination depositCombo = combinationOnLedger("deposit-1", "57200000", "Bancos");

    FIN_FinancialAccountAccounting existingRow = mock(FIN_FinancialAccountAccounting.class);
    when(existingRow.getId()).thenReturn("row-existing");

    wireFindRowCriteria(existingRow);
    wireAccountOptionsCriteria(Collections.emptyList());

    // Body only sets depositAccount — every other field key is entirely omitted.
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(F_DEPOSIT, "deposit-1");

    NeoResponse response = handler.handle(saveCtx("PUT", body));

    assertEquals(200, response.getHttpStatus());
    verify(existingRow).setDepositAccount(depositCombo);
    // Every omitted field must never be touched — neither set to a value nor explicitly cleared.
    verify(existingRow, never()).setFINBankrevaluationgainAcct(any());
    verify(existingRow, never()).setFINBankrevaluationlossAcct(any());
    verify(existingRow, never()).setFINBankfeeAcct(any());
    verify(existingRow, never()).setInTransitPaymentAccountIN(any());
    verify(existingRow, never()).setClearedPaymentAccount(any());
    verify(existingRow, never()).setFINOutIntransitAcct(any());
    verify(existingRow, never()).setWithdrawalAccount(any());
    verify(existingRow, never()).setClearedPaymentAccountOUT(any());
  }

  @Test
  @DisplayName("A field present-but-blank in the body explicitly clears the stored value")
  void saveFieldPresentButBlankClearsStoredValue() throws Exception {
    wireAccountWithLedger();

    FIN_FinancialAccountAccounting existingRow = mock(FIN_FinancialAccountAccounting.class);
    when(existingRow.getId()).thenReturn("row-existing");

    wireFindRowCriteria(existingRow);
    wireAccountOptionsCriteria(Collections.emptyList());

    // depositAccount key is present but blank => must be explicitly cleared (setter called with null).
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(F_DEPOSIT, "");

    NeoResponse response = handler.handle(saveCtx("PUT", body));

    assertEquals(200, response.getHttpStatus());
    verify(existingRow).setDepositAccount(null);
  }

  @Test
  @DisplayName("Save throws when the submitted AccountingCombination belongs to a different ledger")
  void saveCrossLedgerCombinationThrows400() throws Exception {
    wireAccountWithLedger();
    // findOrCreateRow runs before any combination is resolved — the cross-ledger validation
    // failure happens while updating an already-existing row.
    FIN_FinancialAccountAccounting existingRow = mock(FIN_FinancialAccountAccounting.class);
    wireFindRowCriteria(existingRow);

    AcctSchema otherLedger = mock(AcctSchema.class);
    when(otherLedger.getId()).thenReturn("other-ledger");

    AccountingCombination badCombo = mock(AccountingCombination.class);
    when(badCombo.getId()).thenReturn("deposit-bad");
    when(badCombo.getAccountingSchema()).thenReturn(otherLedger);
    when(obDal.get(AccountingCombination.class, "deposit-bad")).thenReturn(badCombo);

    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(F_DEPOSIT, "deposit-bad");

    NeoResponse response = handler.handle(saveCtx("POST", body));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("does not belong to the account's ledger"));
  }

  @Test
  @DisplayName("Mixed-ledger request: an earlier valid field's setter fires before a later field's"
      + " cross-ledger failure aborts the save — the row mutates in-memory but is never persisted"
      + " (obDal.save/flush are never reached)")
  void saveMixedLedgerFieldsAppliesEarlierValidFieldButNeverPersists() throws Exception {
    wireAccountWithLedger();
    // findOrCreateRow runs once, before any field is resolved — the row it returns is the one
    // both applyCombination calls below mutate (or attempt to mutate).
    FIN_FinancialAccountAccounting existingRow = mock(FIN_FinancialAccountAccounting.class);
    wireFindRowCriteria(existingRow);

    // depositAccount (5th in handleSave's fixed call order) resolves fine, on the account's ledger.
    AccountingCombination validDeposit = combinationOnLedger("deposit-ok", "57200000", "Bancos");

    // withdrawalAccount (8th, i.e. later) belongs to a DIFFERENT ledger — must be rejected.
    AcctSchema otherLedger = mock(AcctSchema.class);
    when(otherLedger.getId()).thenReturn("other-ledger");
    AccountingCombination badWithdrawal = mock(AccountingCombination.class);
    when(badWithdrawal.getId()).thenReturn("withdrawal-bad");
    when(badWithdrawal.getAccountingSchema()).thenReturn(otherLedger);
    when(obDal.get(AccountingCombination.class, "withdrawal-bad")).thenReturn(badWithdrawal);

    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(F_DEPOSIT, "deposit-ok")
        .put(F_WITHDRAWAL, "withdrawal-bad");

    NeoResponse response = handler.handle(saveCtx("POST", body));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("does not belong to the account's ledger"));
    // The whole request is rejected (400, nothing persisted) — but the earlier field in call
    // order already mutated the in-memory row before the later field's failure aborted the
    // method. This is NOT full object-level atomicity: only "never explicitly persisted".
    verify(existingRow).setDepositAccount(validDeposit);
    verify(existingRow, never()).setWithdrawalAccount(any());
    verify(existingRow, never()).setEnablebankstatement(any(Boolean.class));
    verify(obDal, never()).save(any());
    verify(obDal, never()).flush();
  }

  @Test
  @DisplayName("A garbage/non-existent combination id degrades to 400 'not found' (OBException),"
      + " never a raw 500")
  void saveGarbageNonExistentCombinationIdReturns400NotFound() throws Exception {
    wireAccountWithLedger();
    FIN_FinancialAccountAccounting existingRow = mock(FIN_FinancialAccountAccounting.class);
    wireFindRowCriteria(existingRow);
    // obDal.get(...) for an id that doesn't correspond to any real record returns null —
    // no stubbing needed beyond the default Mockito null return.

    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(F_DEPOSIT, "not-a-real-id-xyz");

    NeoResponse response = handler.handle(saveCtx("POST", body));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("Accounting combination not found: not-a-real-id-xyz"));
    verify(existingRow, never()).setDepositAccount(any());
    verify(obDal, never()).save(any());
    verify(obDal, never()).flush();
  }

  // ── save — find-or-create ────────────────────────────────────────────────────

  @Test
  @DisplayName("Save creates a new row (find-or-create) when none exists, writes all 9 fields,"
      + " and forces enablebankstatement=true")
  void savePostCreatesNewRowWhenNoneExists() throws Exception {
    wireAccountWithLedger();
    wireFindRowCriteria(null);
    wireAccountOptionsCriteria(Collections.emptyList());

    AccountingCombination bankRevalGain = combinationOnLedger(F_BANK_REVAL_GAIN + "-id", "76800000", "x");
    AccountingCombination bankRevalLoss = combinationOnLedger(F_BANK_REVAL_LOSS + "-id", "66800000", "x");
    AccountingCombination bankFee = combinationOnLedger(F_BANK_FEE + "-id", "62600000", "x");
    AccountingCombination inTransitIn = combinationOnLedger(F_IN_TRANSIT_IN + "-id", "55500000", "x");
    AccountingCombination deposit = combinationOnLedger(F_DEPOSIT + "-id", "57200000", "x");
    AccountingCombination clearedIn = combinationOnLedger(F_CLEARED_IN + "-id", "570001", "x");
    AccountingCombination inTransitOut = combinationOnLedger(F_IN_TRANSIT_OUT + "-id", "55500000", "x");
    AccountingCombination withdrawal = combinationOnLedger(F_WITHDRAWAL + "-id", "57200000", "x");
    AccountingCombination clearedOut = combinationOnLedger(F_CLEARED_OUT + "-id", "570002", "x");

    FIN_FinancialAccountAccounting newRow = mock(FIN_FinancialAccountAccounting.class);
    when(newRow.getId()).thenReturn("row-new-1");
    when(newRow.getFINBankrevaluationgainAcct()).thenReturn(bankRevalGain);
    when(newRow.getFINBankrevaluationlossAcct()).thenReturn(bankRevalLoss);
    when(newRow.getFINBankfeeAcct()).thenReturn(bankFee);
    when(newRow.getInTransitPaymentAccountIN()).thenReturn(inTransitIn);
    when(newRow.getDepositAccount()).thenReturn(deposit);
    when(newRow.getClearedPaymentAccount()).thenReturn(clearedIn);
    when(newRow.getFINOutIntransitAcct()).thenReturn(inTransitOut);
    when(newRow.getWithdrawalAccount()).thenReturn(withdrawal);
    when(newRow.getClearedPaymentAccountOUT()).thenReturn(clearedOut);

    JSONObject body = fullBody();

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
      verify(newRow).setFINBankrevaluationgainAcct(bankRevalGain);
      verify(newRow).setFINBankrevaluationlossAcct(bankRevalLoss);
      verify(newRow).setFINBankfeeAcct(bankFee);
      verify(newRow).setInTransitPaymentAccountIN(inTransitIn);
      verify(newRow).setDepositAccount(deposit);
      verify(newRow).setClearedPaymentAccount(clearedIn);
      verify(newRow).setFINOutIntransitAcct(inTransitOut);
      verify(newRow).setWithdrawalAccount(withdrawal);
      verify(newRow).setClearedPaymentAccountOUT(clearedOut);
      // The enablebankstatement side effect must fire on every successful save.
      verify(newRow).setEnablebankstatement(true);
      verify(obDal).save(newRow);
      verify(obDal).flush();

      JSONObject row = row(response);
      for (String field : ALL_FIELDS) {
        assertEquals(field + "-id", row.getString(field));
      }
    }
  }

  @Test
  @DisplayName("Save updates the existing row (find-or-create reuse) without touching OBProvider,"
      + " and still forces enablebankstatement=true")
  void savePutUpdatesExistingRow() throws Exception {
    wireAccountWithLedger();

    AccountingCombination deposit = combinationOnLedger("deposit-2", "57200000", "Bancos 2");

    FIN_FinancialAccountAccounting existingRow = mock(FIN_FinancialAccountAccounting.class);
    when(existingRow.getId()).thenReturn("row-existing");
    when(existingRow.getDepositAccount()).thenReturn(deposit);

    wireFindRowCriteria(existingRow);
    wireAccountOptionsCriteria(Collections.emptyList());

    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(F_DEPOSIT, "deposit-2");

    try (MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);

      NeoResponse response = handler.handle(saveCtx("PUT", body));

      assertEquals(200, response.getHttpStatus());
      // find-or-create must reuse the existing row: OBProvider is never asked for a new one.
      obProviderMock.verifyNoInteractions();
      verify(existingRow).setDepositAccount(deposit);
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
