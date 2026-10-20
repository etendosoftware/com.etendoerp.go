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
package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.TreeMap;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;

import com.etendoerp.go.schemaforge.NeoActionRecordGuard;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoFieldFilter;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * @covers com.etendoerp.go.schemaforge.util.PostingAccountGuard
 */
class PostingAccountGuardTest {

  private static final String DAL_ENTITY = "WarehouseAccounts";
  private static final String LEDGER_PROP = "accountingSchema";
  private static final String COMBO_PROP = "warehouseDifferences";
  private static final String MAPPED_PROP = "dueTransAcct";
  private static final String MAPPED_API_KEY = "tDueTransAcct";
  private static final String RECORD_ID = "row-1";

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBMessageUtils> messagesMock;
  private MockedStatic<ModelProvider> modelProviderMock;
  private MockedStatic<NeoFieldFilter> fieldFilterMock;
  private MockedStatic<NeoActionRecordGuard> guardMock;

  private Entity entity;
  private Tab adTab;
  private AcctSchema ledger;
  private AcctSchema otherLedger;
  private BaseOBObject stored;

  @BeforeEach
  void setUp() {
    obContextMock = mockStatic(OBContext.class);
    messagesMock = mockStatic(OBMessageUtils.class);
    messagesMock.when(() -> OBMessageUtils.messageBD(anyString())).thenAnswer(i -> i.getArgument(0));
    // The text is "<key> <params sorted>", so a test can read both the field and the account.
    messagesMock.when(() -> OBMessageUtils.parseTranslation(anyString(), anyMap()))
        .thenAnswer(i -> i.getArgument(0) + " " + new TreeMap<>((Map<?, ?>) i.getArgument(1)));

    entity = mock(Entity.class);
    doReturn(BaseOBObject.class).when(entity).getMappingClass();
    Property ledgerProperty = property(AcctSchema.ENTITY_NAME);
    Property comboProperty = property(AccountingCombination.ENTITY_NAME);
    Property mappedProperty = property(AccountingCombination.ENTITY_NAME);
    when(entity.getProperty(LEDGER_PROP, false)).thenReturn(ledgerProperty);
    when(entity.getProperty(COMBO_PROP, false)).thenReturn(comboProperty);
    when(entity.getProperty(MAPPED_PROP, false)).thenReturn(mappedProperty);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(DAL_ENTITY)).thenReturn(entity);
    modelProviderMock = mockStatic(ModelProvider.class);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);

    NeoFieldFilter filter = mock(NeoFieldFilter.class);
    when(filter.resolveWritablePropName(anyString())).thenAnswer(i -> i.getArgument(0));
    when(filter.resolveWritablePropName(MAPPED_API_KEY)).thenReturn(MAPPED_PROP);
    fieldFilterMock = mockStatic(NeoFieldFilter.class);
    fieldFilterMock.when(() -> NeoFieldFilter.forEntity(any(), eq(DAL_ENTITY))).thenReturn(filter);

    Table table = mock(Table.class);
    when(table.getName()).thenReturn(DAL_ENTITY);
    adTab = mock(Tab.class);
    when(adTab.getTable()).thenReturn(table);

    ledger = ledger("ledger-1");
    otherLedger = ledger("ledger-2");
    stored = mock(BaseOBObject.class);
    when(stored.get(LEDGER_PROP)).thenReturn(ledger);

    guardMock = mockStatic(NeoActionRecordGuard.class);
    guardMock.when(() -> NeoActionRecordGuard.loadOwned(BaseOBObject.class, RECORD_ID)).thenReturn(stored);
    guardMock.when(() -> NeoActionRecordGuard.loadOwned(AcctSchema.class, "ledger-1")).thenReturn(ledger);
  }

  @AfterEach
  void tearDown() {
    guardMock.close();
    fieldFilterMock.close();
    modelProviderMock.close();
    messagesMock.close();
    obContextMock.close();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static Property property(String targetEntityName) {
    Entity target = mock(Entity.class);
    when(target.getName()).thenReturn(targetEntityName);
    Property property = mock(Property.class);
    when(property.getTargetEntity()).thenReturn(target);
    return property;
  }

  private static AcctSchema ledger(String id) {
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getId()).thenReturn(id);
    return schema;
  }

  /** A loadable combination; summary / active flags and ledger set by the caller. */
  private AccountingCombination combo(String id, AcctSchema of, boolean summary, boolean accountActive,
      boolean comboActive) {
    ElementValue account = mock(ElementValue.class);
    when(account.getSearchKey()).thenReturn("4300");
    when(account.getName()).thenReturn("Customers");
    when(account.isSummaryLevel()).thenReturn(summary);
    when(account.isActive()).thenReturn(accountActive);
    AccountingCombination combo = mock(AccountingCombination.class);
    when(combo.getId()).thenReturn(id);
    when(combo.getAccount()).thenReturn(account);
    when(combo.isActive()).thenReturn(comboActive);
    when(combo.getAccountingSchema()).thenReturn(of);
    guardMock.when(() -> NeoActionRecordGuard.loadOwned(AccountingCombination.class, id)).thenReturn(combo);
    return combo;
  }

  private AccountingCombination posting(String id) {
    return combo(id, ledger, false, true, true);
  }

  private NeoContext ctx(String method, String recordId, JSONObject body) {
    return NeoContext.builder()
        .specName("warehouse")
        .entityName("accounting")
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(method)
        .recordId(recordId)
        .requestBody(body)
        .adTab(adTab)
        .sfEntity(mock(SFEntity.class))
        .build();
  }

  private NeoResponse patch(JSONObject body) {
    return PostingAccountGuard.checkWrite(ctx("PATCH", RECORD_ID, body),
        PostingAccountGuard.ledgerOfRow(LEDGER_PROP));
  }

  private NeoResponse create(JSONObject body) {
    return PostingAccountGuard.checkWrite(ctx("POST", null, body),
        PostingAccountGuard.ledgerOfRow(LEDGER_PROP));
  }

  private static void assertRefused(NeoResponse response, String key) throws Exception {
    assertNotNull(response, "expected a refusal");
    assertEquals(400, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(key, error.getJSONArray("messageKeys").getString(0));
  }

  private static String message(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("message");
  }

  // ── R1 posting rule ────────────────────────────────────────────────────────

  @Test
  @DisplayName("A posting account of the row's ledger passes")
  void postingAccountPasses() throws Exception {
    posting("c-ok");
    assertNull(patch(new JSONObject().put(COMBO_PROP, "c-ok")));
  }

  @Test
  @DisplayName("A summary account is refused with its key, the field and the account in the text")
  void summaryAccountRefused() throws Exception {
    combo("c-sum", ledger, true, true, true);
    NeoResponse response = patch(new JSONObject().put(COMBO_PROP, "c-sum"));
    assertRefused(response, PostingAccountGuard.MSG_SUMMARY);
    assertTrue(message(response).contains("field=" + COMBO_PROP), message(response));
    assertTrue(message(response).contains("account=4300 - Customers"), message(response));
  }

  @Test
  @DisplayName("An inactive account is refused")
  void inactiveAccountRefused() throws Exception {
    combo("c-ia", ledger, false, false, true);
    assertRefused(patch(new JSONObject().put(COMBO_PROP, "c-ia")), PostingAccountGuard.MSG_INACTIVE);
  }

  @Test
  @DisplayName("An inactive combination on an active account is refused too (same rule as the selectors)")
  void inactiveCombinationRefused() throws Exception {
    combo("c-ic", ledger, false, true, false);
    assertRefused(patch(new JSONObject().put(COMBO_PROP, "c-ic")), PostingAccountGuard.MSG_INACTIVE);
  }

  // ── R5 ledger ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Create: a combination of another ledger than the body's accountingSchema is refused")
  void createOtherLedgerRefused() throws Exception {
    combo("c-other", otherLedger, false, true, true);
    assertRefused(create(new JSONObject().put(LEDGER_PROP, "ledger-1").put(COMBO_PROP, "c-other")),
        PostingAccountGuard.MSG_WRONG_LEDGER);
  }

  @Test
  @DisplayName("Update: the combination is checked against the STORED ledger, even when the body repeats it")
  void updateChecksStoredLedger() throws Exception {
    combo("c-other", otherLedger, false, true, true);
    assertRefused(patch(new JSONObject().put(LEDGER_PROP, "ledger-1").put(COMBO_PROP, "c-other")),
        PostingAccountGuard.MSG_WRONG_LEDGER);
  }

  // ── R6 ledger not updatable ────────────────────────────────────────────────

  @Test
  @DisplayName("Update: changing accountingSchema alone is refused")
  void ledgerChangeAloneRefused() throws Exception {
    assertRefused(patch(new JSONObject().put(LEDGER_PROP, "ledger-2")),
        PostingAccountGuard.MSG_LEDGER_NOT_UPDATABLE);
  }

  @Test
  @DisplayName("Update: changing accountingSchema with a combination of the stored ledger is still refused")
  void ledgerChangeWithValidComboRefused() throws Exception {
    posting("c-ok");
    assertRefused(patch(new JSONObject().put(LEDGER_PROP, "ledger-2").put(COMBO_PROP, "c-ok")),
        PostingAccountGuard.MSG_LEDGER_NOT_UPDATABLE);
  }

  @Test
  @DisplayName("Create: accountingSchema is free (no stored row)")
  void createLedgerIsFree() throws Exception {
    posting("c-ok");
    assertNull(create(new JSONObject().put(LEDGER_PROP, "ledger-1").put(COMBO_PROP, "c-ok")));
  }

  // ── R2 / R3 / R4 ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("R2: an unchanged stored value is not re-checked, so a pre-existing bad row stays saveable")
  void unchangedStoredValuePasses() throws Exception {
    AccountingCombination bad = combo("c-sum", ledger, true, true, true);
    when(stored.get(COMBO_PROP)).thenReturn(bad);
    assertNull(patch(new JSONObject().put(COMBO_PROP, "c-sum")));
  }

  @Test
  @DisplayName("R4: clearing a field passes")
  void nullPasses() throws Exception {
    assertNull(patch(new JSONObject().put(COMBO_PROP, JSONObject.NULL)));
  }

  @Test
  @DisplayName("R3: an id that does not load for the caller is left to the default path")
  void unknownIdPasses() throws Exception {
    assertNull(patch(new JSONObject().put(COMBO_PROP, "nope")));
  }

  // ── body shapes ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("An API key that differs from its DAL property is mapped and checked; the message names the API key")
  void mappedApiKeyChecked() throws Exception {
    combo("c-sum", ledger, true, true, true);
    NeoResponse response = patch(new JSONObject().put(MAPPED_API_KEY, "c-sum"));
    assertRefused(response, PostingAccountGuard.MSG_SUMMARY);
    assertTrue(message(response).contains("field=" + MAPPED_API_KEY), message(response));
  }

  @Test
  @DisplayName("A {\"data\":{...}} envelope is unwrapped and checked")
  void dataEnvelopeChecked() throws Exception {
    combo("c-sum", ledger, true, true, true);
    JSONObject body = new JSONObject().put("data", new JSONObject().put(COMBO_PROP, "c-sum"));
    assertRefused(patch(body), PostingAccountGuard.MSG_SUMMARY);
  }

  @Test
  @DisplayName("An FK sent as {\"id\": ...} (which core also accepts) is checked like a plain id")
  void objectShapedIdChecked() throws Exception {
    combo("c-sum", ledger, true, true, true);
    JSONObject body = new JSONObject().put(COMBO_PROP, new JSONObject().put("id", "c-sum"));
    assertRefused(patch(body), PostingAccountGuard.MSG_SUMMARY);
  }

  @Test
  @DisplayName("Update: repeating the stored ledger as {\"id\": ...} is not a ledger change")
  void objectShapedSameLedgerPasses() throws Exception {
    assertNull(patch(new JSONObject().put(LEDGER_PROP, new JSONObject().put("id", "ledger-1"))));
  }

  @Test
  @DisplayName("Keys that are not properties (parentId, updated, $_identifier) are ignored without error")
  void unknownKeysIgnored() throws Exception {
    posting("c-ok");
    JSONObject body = new JSONObject().put("parentId", "p").put("updated", "2026-10-20")
        .put(COMBO_PROP + "$_identifier", "x").put(COMBO_PROP, "c-ok");
    assertNull(patch(body));
  }

  // ── scope / totality ───────────────────────────────────────────────────────

  @Test
  @DisplayName("GET, non-CRUD endpoints, a null body and a missing tab are not checked")
  void outOfScopeReturnsNull() throws Exception {
    PostingAccountGuard.LedgerResolver resolver = PostingAccountGuard.ledgerOfRow(LEDGER_PROP);
    assertNull(PostingAccountGuard.checkWrite(ctx("GET", RECORD_ID, new JSONObject()), resolver));
    assertNull(PostingAccountGuard.checkWrite(ctx("PATCH", RECORD_ID, null), resolver));
    assertNull(PostingAccountGuard.checkWrite(NeoContext.builder().endpointType(NeoEndpointType.DEFAULTS)
        .httpMethod("POST").requestBody(new JSONObject()).adTab(adTab).build(), resolver));
    assertNull(PostingAccountGuard.checkWrite(NeoContext.builder().endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST").requestBody(new JSONObject()).build(), resolver));
  }

  @Test
  @DisplayName("Totality: a load that throws is skipped, never turned into a 500")
  void loadFailureIsSkipped() throws Exception {
    guardMock.when(() -> NeoActionRecordGuard.loadOwned(AccountingCombination.class, "boom"))
        .thenThrow(new IllegalStateException("db down"));
    assertNull(patch(new JSONObject().put(COMBO_PROP, "boom")));
  }

  @Test
  @DisplayName("requirePosting with no ledger skips the ledger check only")
  void requirePostingWithoutLedger() {
    AccountingCombination other = combo("c-other", otherLedger, false, true, true);
    PostingAccountGuard.requirePosting(other, null, COMBO_PROP);
  }
}
