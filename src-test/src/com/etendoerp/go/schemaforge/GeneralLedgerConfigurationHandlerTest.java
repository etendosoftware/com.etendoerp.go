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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchemaDefault;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchemaElement;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchemaGL;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.model.financialmgmt.calendar.Calendar;

/**
 * Unit tests for {@link GeneralLedgerConfigurationHandler}.
 *
 * <p>The handler is an org-scoped aggregate over one accounting schema (general / defaults /
 * dimensions / orgInfo / catalogs / meta). These tests isolate the static
 * {@link OBDal} and {@link OBContext} dependencies with Mockito {@code MockedStatic}, so no DB
 * or CDI container is required. The model graph (organization → ledger → defaults/dimensions)
 * is built from plain mocks.</p>
 *
 * <p>Coverage:</p>
 * <ul>
 *   <li>routing guards (spec / entity / endpoint type)</li>
 *   <li>GET aggregate shape (all top-level keys, orgInfo calendar + name)</li>
 *   <li>POST happy path (a backed `general` field change is written and the refreshed row reflects it)</li>
 *   <li>org resolution failures (no concrete current org, org not found, org without general ledger)</li>
 *   <li>currency not found, accounting combination not found</li>
 *   <li>a mandatory dimension cannot be deactivated</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GeneralLedgerConfigurationHandlerTest {

  private static final String SPEC = "general-ledger-configuration";
  private static final String ENTITY = "General";
  private static final String ORG_ID = "org-es-001";
  private static final String LEDGER_ID = "ledger-001";

  private GeneralLedgerConfigurationHandler handler;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private OBDal obDal;

  // Model graph, rebuilt per test via the wire* helpers.
  private Organization org;
  private AcctSchema schema;
  private AcctSchemaDefault defaults;
  private AcctSchemaGL generalAccounts;

  @BeforeEach
  void setUp() {
    handler = new GeneralLedgerConfigurationHandler();
    obDal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    // setAdminMode / restorePreviousMode become no-ops; getOBContext is stubbed per-test when needed.
    obContextMock = mockStatic(OBContext.class);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private NeoContext getCtx(String orgId) {
    return NeoContext.builder()
        .httpMethod("GET")
        .specName(SPEC)
        .entityName(ENTITY)
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(orgId == null ? Map.of() : Map.of("selectedOrgId", orgId))
        .build();
  }

  private NeoContext postCtx(String orgId, JSONObject body) {
    return NeoContext.builder()
        .httpMethod("POST")
        .specName(SPEC)
        .entityName(ENTITY)
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(orgId == null ? Map.of() : Map.of("selectedOrgId", orgId))
        .requestBody(body)
        .build();
  }

  private void wireOrgWithLedger() {
    schema = mock(AcctSchema.class);
    when(schema.getId()).thenReturn(LEDGER_ID);
    when(schema.getName()).thenReturn("Spain GAAP");
    when(schema.getGAAP()).thenReturn("ESP");
    when(schema.isAccrual()).thenReturn(true);
    when(schema.isAllowNegative()).thenReturn(false);
    when(schema.getDescription()).thenReturn("Default ledger");
    when(schema.getCurrency()).thenReturn(null);

    Calendar calendar = mock(Calendar.class);
    when(calendar.getName()).thenReturn("Calendario 2026");

    org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(org.getName()).thenReturn("Espana S.A.");
    when(org.getCalendar()).thenReturn(calendar);
    when(org.getGeneralLedger()).thenReturn(schema);

    when(obDal.get(Organization.class, ORG_ID)).thenReturn(org);
  }

  @SuppressWarnings("unchecked")
  private void wireLoadCriteria(List<AcctSchemaElement> dimensions) {
    defaults = mock(AcctSchemaDefault.class);

    OBCriteria<AcctSchemaDefault> defCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchemaDefault.class)).thenReturn(defCrit);
    when(defCrit.add(any())).thenReturn(defCrit);
    when(defCrit.addOrder(any())).thenReturn(defCrit);
    when(defCrit.setMaxResults(anyInt())).thenReturn(defCrit);
    when(defCrit.uniqueResult()).thenReturn(defaults);

    OBCriteria<AcctSchemaElement> elemCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchemaElement.class)).thenReturn(elemCrit);
    when(elemCrit.setFilterOnActive(anyBoolean())).thenReturn(elemCrit);
    when(elemCrit.add(any())).thenReturn(elemCrit);
    when(elemCrit.addOrder(any())).thenReturn(elemCrit);
    when(elemCrit.list()).thenReturn(dimensions);

    OBCriteria<AccountingCombination> accCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(AccountingCombination.class)).thenReturn(accCrit);
    when(accCrit.add(any())).thenReturn(accCrit);
    when(accCrit.addOrder(any())).thenReturn(accCrit);
    when(accCrit.list()).thenReturn(Collections.emptyList());

    OBCriteria<Currency> curCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(Currency.class)).thenReturn(curCrit);
    when(curCrit.add(any())).thenReturn(curCrit);
    when(curCrit.addOrder(any())).thenReturn(curCrit);
    when(curCrit.setMaxResults(anyInt())).thenReturn(curCrit);
    when(curCrit.list()).thenReturn(Collections.emptyList());

    wireGeneralAccountsCriteria();
  }

  /**
   * Stubs the {@link AcctSchemaGL} unique-result lookup used by {@code loadState}. Called by
   * every {@code wireLoadCriteria*} variant so tests that don't care about the general-accounts
   * row still get a non-null one (mirrors the mandatory {@code AcctSchemaDefault} row).
   */
  @SuppressWarnings("unchecked")
  private void wireGeneralAccountsCriteria() {
    generalAccounts = mock(AcctSchemaGL.class);
    when(generalAccounts.getId()).thenReturn("gl-acct-001");

    OBCriteria<AcctSchemaGL> glCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchemaGL.class)).thenReturn(glCrit);
    when(glCrit.add(any())).thenReturn(glCrit);
    when(glCrit.addOrder(any())).thenReturn(glCrit);
    when(glCrit.setMaxResults(anyInt())).thenReturn(glCrit);
    when(glCrit.uniqueResult()).thenReturn(generalAccounts);
  }

  /**
   * Variant of wireLoadCriteria that returns the provided combos from the AccountingCombination
   * criteria and the provided currencies from the Currency criteria.
   */
  @SuppressWarnings("unchecked")
  private void wireLoadCriteriaWithCatalogsAndDimensions(
      List<AcctSchemaElement> dimensions,
      List<AccountingCombination> combos,
      List<Currency> currencies) {

    defaults = mock(AcctSchemaDefault.class);

    OBCriteria<AcctSchemaDefault> defCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchemaDefault.class)).thenReturn(defCrit);
    when(defCrit.add(any())).thenReturn(defCrit);
    when(defCrit.addOrder(any())).thenReturn(defCrit);
    when(defCrit.setMaxResults(anyInt())).thenReturn(defCrit);
    when(defCrit.uniqueResult()).thenReturn(defaults);

    OBCriteria<AcctSchemaElement> elemCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchemaElement.class)).thenReturn(elemCrit);
    when(elemCrit.setFilterOnActive(anyBoolean())).thenReturn(elemCrit);
    when(elemCrit.add(any())).thenReturn(elemCrit);
    when(elemCrit.addOrder(any())).thenReturn(elemCrit);
    when(elemCrit.list()).thenReturn(dimensions);

    OBCriteria<AccountingCombination> accCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(AccountingCombination.class)).thenReturn(accCrit);
    when(accCrit.add(any())).thenReturn(accCrit);
    when(accCrit.addOrder(any())).thenReturn(accCrit);
    when(accCrit.list()).thenReturn(combos);

    OBCriteria<Currency> curCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(Currency.class)).thenReturn(curCrit);
    when(curCrit.add(any())).thenReturn(curCrit);
    when(curCrit.addOrder(any())).thenReturn(curCrit);
    when(curCrit.setMaxResults(anyInt())).thenReturn(curCrit);
    when(curCrit.list()).thenReturn(currencies);

    wireGeneralAccountsCriteria();
  }

  private AcctSchemaElement dimension(String id, String label, boolean active, boolean mandatory,
      String type, long seq) {
    AcctSchemaElement element = mock(AcctSchemaElement.class);
    when(element.getId()).thenReturn(id);
    when(element.getName()).thenReturn(label);
    when(element.isActive()).thenReturn(active);
    when(element.isMandatory()).thenReturn(mandatory);
    when(element.getType()).thenReturn(type);
    when(element.getSequenceNumber()).thenReturn(seq);
    return element;
  }

  private JSONObject aggregateRow(NeoResponse response) throws Exception {
    return response.getBody()
        .getJSONObject("response")
        .getJSONArray("data")
        .getJSONObject(0);
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

  // ── GET aggregate shape ────────────────────────────────────────────────────────

  @Test
  @DisplayName("GET returns the full aggregate shape with all top-level sections")
  void getReturnsFullAggregateShape() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(List.of(
        dimension("dim-org", "Organizacion", true, true, "OO", 10L),
        dimension("dim-prod", "Producto", true, false, "PR", 20L)));

    NeoResponse response = handler.handle(getCtx(ORG_ID));

    assertEquals(200, response.getHttpStatus());
    JSONObject envelope = response.getBody().getJSONObject("response");
    assertEquals(1, envelope.getInt("count"));
    assertEquals(1, envelope.getJSONArray("data").length());

    JSONObject row = aggregateRow(response);
    for (String key : new String[] { "general", "defaults", "dimensions",
        "orgInfo", "catalogs", "generalAccounts", "meta" }) {
      assertTrue(row.has(key), "aggregate row must carry the '" + key + "' section");
    }

    JSONObject general = row.getJSONObject("general");
    assertEquals(LEDGER_ID, general.getString("id"));
    assertEquals("Spain GAAP", general.getString("name"));
    assertEquals(true, general.getBoolean("accrual"));

    JSONArray dimensions = row.getJSONArray("dimensions");
    assertEquals(2, dimensions.length());
    assertEquals("dim-org", dimensions.getJSONObject(0).getString("id"));
    assertEquals(true, dimensions.getJSONObject(0).getBoolean("mandatory"));
  }

  @Test
  @DisplayName("GET orgInfo carries calendar + organization name")
  void getOrgInfoAndMeta() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());

    JSONObject row = aggregateRow(handler.handle(getCtx(ORG_ID)));

    JSONObject orgInfo = row.getJSONObject("orgInfo");
    assertEquals("Espana S.A.", orgInfo.getString("organization"));
    assertEquals("Calendario 2026", orgInfo.getString("fiscalCalendar"));

    JSONObject meta = row.getJSONObject("meta");
    assertEquals("neo", meta.getString("source"));
  }

  // ── POST happy path ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("POST writes a backed 'general' field and the refreshed row reflects it")
  void postPersistsGeneralChange() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());
    // Simulate persistence: after the setter is applied the refreshed read returns the new name.
    when(schema.getName()).thenReturn("New GL Name");

    JSONObject body = new JSONObject()
        .put("general", new JSONObject().put("name", "New GL Name"));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    // The write path executed against the schema and committed in one transaction.
    verify(schema).setName("New GL Name");
    verify(obDal).save(schema);
    verify(obDal).save(org);
    verify(obDal).save(defaults);
    verify(obDal).flush();

    // The refreshed aggregate reflects the new value.
    assertEquals("New GL Name", aggregateRow(response).getJSONObject("general").getString("name"));
  }

  @Test
  @DisplayName("POST with null body returns 400")
  void postWithNullBodyReturns400() {
    NeoResponse response = handler.handle(postCtx(ORG_ID, null));
    assertEquals(400, response.getHttpStatus());
  }

  // ── org resolution failures ──────────────────────────────────────────────────

  @Test
  @DisplayName("No selectedOrgId and no concrete current org returns 400")
  void noConcreteOrgReturns400() {
    OBContext ctx = mock(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(ctx);
    when(ctx.getCurrentOrganization()).thenReturn(null);

    NeoResponse response = handler.handle(getCtx(null));
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  @DisplayName("Org not found returns 400")
  void orgNotFoundReturns400() {
    when(obDal.get(Organization.class, ORG_ID)).thenReturn(null);

    NeoResponse response = handler.handle(getCtx(ORG_ID));
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  @DisplayName("Org without a general ledger returns 400")
  void orgWithoutLedgerReturns400() {
    Organization noLedger = mock(Organization.class);
    when(noLedger.getId()).thenReturn(ORG_ID);
    when(noLedger.getGeneralLedger()).thenReturn(null);
    when(obDal.get(Organization.class, ORG_ID)).thenReturn(noLedger);

    NeoResponse response = handler.handle(getCtx(ORG_ID));
    assertEquals(400, response.getHttpStatus());
  }

  // ── value resolution failures (POST) ─────────────────────────────────────────

  @Test
  @DisplayName("Currency not found on save returns 400")
  void currencyNotFoundReturns400() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());
    when(obDal.get(Currency.class, "missing-currency")).thenReturn(null);

    JSONObject body = new JSONObject()
        .put("general", new JSONObject().put("currency", "missing-currency"));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  @DisplayName("Accounting combination not found on save returns 400")
  void combinationNotFoundReturns400() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());
    when(obDal.get(AccountingCombination.class, "missing-combo")).thenReturn(null);

    JSONObject body = new JSONObject()
        .put("defaults", new JSONObject().put("bankAsset", "missing-combo"));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));
    assertEquals(400, response.getHttpStatus());
  }

  // ── dimension rule (POST) ────────────────────────────────────────────────────

  @Test
  @DisplayName("Deactivating a mandatory dimension returns 400")
  void deactivatingMandatoryDimensionReturns400() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(List.of(dimension("dim-org", "Organizacion", true, true, "OO", 10L)));

    JSONObject body = new JSONObject().put("dimensions",
        new JSONArray().put(new JSONObject().put("id", "dim-org").put("active", false)));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));
    assertEquals(400, response.getHttpStatus());
    // The mandatory guard must fire before any write occurs.
    verify(obDal, never()).flush();
  }

  // ── Group A — applyGeneralChanges all fields ─────────────────────────────────

  @Test
  @DisplayName("POST updates description field via applyGeneralChanges")
  void postUpdatesDescription() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());

    JSONObject body = new JSONObject()
        .put("general", new JSONObject().put("description", "New desc"));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    verify(schema).setDescription("New desc");
  }

  @Test
  @DisplayName("POST updates accrual boolean field via applyGeneralChanges")
  void postUpdatesAccrual() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());

    JSONObject body = new JSONObject()
        .put("general", new JSONObject().put("accrual", false));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    verify(schema).setAccrual(false);
  }

  @Test
  @DisplayName("POST updates allowNegative boolean field via applyGeneralChanges")
  void postUpdatesAllowNegative() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());

    JSONObject body = new JSONObject()
        .put("general", new JSONObject().put("allowNegative", true));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    verify(schema).setAllowNegative(true);
  }

  @Test
  @DisplayName("POST updates gAAP field via applyGeneralChanges")
  void postUpdatesGaap() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());

    JSONObject body = new JSONObject()
        .put("general", new JSONObject().put("gAAP", "IFRS"));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    verify(schema).setGAAP("IFRS");
  }

  @Test
  @DisplayName("POST updates name and description together via applyGeneralChanges")
  void postUpdatesNameAndMultipleFields() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());
    when(schema.getName()).thenReturn("X");

    JSONObject body = new JSONObject()
        .put("general", new JSONObject().put("name", "X").put("description", "Y"));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    verify(schema).setName("X");
    verify(schema).setDescription("Y");
  }

  // ── Group B — applyDefaultChanges success ────────────────────────────────────

  @Test
  @DisplayName("POST updates a default account field when a valid combination id is provided")
  void postUpdatesDefaultAccount() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());

    AccountingCombination combo = mock(AccountingCombination.class);
    when(combo.getId()).thenReturn("combo-1");
    when(obDal.get(AccountingCombination.class, "combo-1")).thenReturn(combo);

    JSONObject body = new JSONObject()
        .put("defaults", new JSONObject().put("bankAsset", "combo-1"));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    verify(defaults).set(AcctSchemaDefault.PROPERTY_BANKASSET, combo);
  }

  @Test
  @DisplayName("POST sets a default account field to null when JSONObject.NULL is provided")
  void postNullDefaultAccount() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());

    JSONObject body = new JSONObject()
        .put("defaults", new JSONObject().put("bankAsset", JSONObject.NULL));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    verify(defaults).set(AcctSchemaDefault.PROPERTY_BANKASSET, null);
  }

  // ── Group C — applyDimensionChanges success path ─────────────────────────────

  @Test
  @DisplayName("POST deactivating a non-mandatory dimension succeeds and saves the row")
  void postDimensionNonMandatoryDeactivateSucceeds() throws Exception {
    wireOrgWithLedger();
    AcctSchemaElement dim = dimension("dim-prod", "Producto", true, false, "PR", 20L);
    wireLoadCriteria(List.of(dim));

    JSONObject body = new JSONObject().put("dimensions",
        new JSONArray().put(new JSONObject().put("id", "dim-prod").put("active", false)));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    verify(dim).setActive(false);
    verify(obDal).save(dim);
    verify(obDal).flush();
  }

  @Test
  @DisplayName("POST with unknown dimension id is silently skipped — response 200 and flush called")
  void postDimensionUnknownIdSkipped() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(List.of(dimension("dim-prod", "Producto", true, false, "PR", 20L)));

    JSONObject body = new JSONObject().put("dimensions",
        new JSONArray().put(new JSONObject().put("id", "does-not-exist").put("active", false)));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    verify(obDal).flush();
  }

  // ── Group D — buildAccountOptions / resolveCombinationCode / resolveCombinationName ──

  @Test
  @DisplayName("GET returns account options in catalogs using combination fallback for code and description fallback for name")
  void getAccountOptionsReturnedInCatalogs() throws Exception {
    wireOrgWithLedger();

    AccountingCombination combo = mock(AccountingCombination.class);
    when(combo.getId()).thenReturn("combo-x");
    when(combo.getAccount()).thenReturn(null);
    when(combo.getCombination()).thenReturn("1000");
    when(combo.getDescription()).thenReturn("Bank");

    wireLoadCriteriaWithCatalogsAndDimensions(
        Collections.emptyList(),
        List.of(combo),
        Collections.emptyList());

    JSONObject row = aggregateRow(handler.handle(getCtx(ORG_ID)));

    JSONArray accounts = row.getJSONObject("catalogs").getJSONArray("accounts");
    assertEquals(1, accounts.length());
    JSONObject item = accounts.getJSONObject(0);
    assertEquals("combo-x", item.getString("id"));
    assertEquals("1000", item.getString("code"));
    assertTrue(item.getString("name").contains("Bank"));
  }

  @Test
  @DisplayName("GET returns account options using ElementValue searchKey as code and name as label")
  void getAccountOptionsWithAccount() throws Exception {
    wireOrgWithLedger();

    ElementValue account = mock(ElementValue.class);
    when(account.getSearchKey()).thenReturn("572");
    when(account.getName()).thenReturn("Bancos");

    AccountingCombination combo = mock(AccountingCombination.class);
    when(combo.getId()).thenReturn("combo-y");
    when(combo.getAccount()).thenReturn(account);
    when(combo.getCombination()).thenReturn("572");
    when(combo.getDescription()).thenReturn("Cuentas bancarias");

    wireLoadCriteriaWithCatalogsAndDimensions(
        Collections.emptyList(),
        List.of(combo),
        Collections.emptyList());

    JSONObject row = aggregateRow(handler.handle(getCtx(ORG_ID)));

    JSONArray accounts = row.getJSONObject("catalogs").getJSONArray("accounts");
    assertEquals(1, accounts.length());
    JSONObject item = accounts.getJSONObject(0);
    assertEquals("572", item.getString("code"));
    assertEquals("Bancos", item.getString("name"));
  }

  // ── Group E — buildCurrencyOptions ──────────────────────────────────────────

  @Test
  @DisplayName("GET returns currency options in catalogs with ISO code as value label")
  void getCurrencyOptionsReturnedInCatalogs() throws Exception {
    wireOrgWithLedger();

    Currency eur = mock(Currency.class);
    when(eur.getId()).thenReturn("EUR-ID");
    when(eur.getISOCode()).thenReturn("EUR");

    wireLoadCriteriaWithCatalogsAndDimensions(
        Collections.emptyList(),
        Collections.emptyList(),
        List.of(eur));

    JSONObject row = aggregateRow(handler.handle(getCtx(ORG_ID)));

    JSONArray currencies = row.getJSONObject("catalogs").getJSONArray("currencies");
    assertEquals(1, currencies.length());
    JSONObject item = currencies.getJSONObject(0);
    assertEquals("EUR-ID", item.getString("value"));
    assertTrue(item.getString("name").contains("EUR"));
  }

  @Test
  @DisplayName("GET currency option with null ISO code falls back to currency id as name")
  void getCurrencyOptionsNullIsoCode() throws Exception {
    wireOrgWithLedger();

    Currency noIso = mock(Currency.class);
    when(noIso.getId()).thenReturn("X-ID");
    when(noIso.getISOCode()).thenReturn(null);

    wireLoadCriteriaWithCatalogsAndDimensions(
        Collections.emptyList(),
        Collections.emptyList(),
        List.of(noIso));

    JSONObject row = aggregateRow(handler.handle(getCtx(ORG_ID)));

    JSONArray currencies = row.getJSONObject("catalogs").getJSONArray("currencies");
    assertEquals(1, currencies.length());
    assertEquals("X-ID", currencies.getJSONObject(0).getString("name"));
  }

  // ── Group F — inferDimensionScope and buildDimensionCaption ──────────────────

  @Test
  @DisplayName("Dimension caption for mandatory OO type contains Obligatorio and Facturas y asientos")
  void dimensionCaptionMandatoryOO() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(List.of(dimension("dim-oo", "Org", true, true, "OO", 10L)));

    JSONObject row = aggregateRow(handler.handle(getCtx(ORG_ID)));
    String caption = row.getJSONArray("dimensions").getJSONObject(0).getString("caption");

    assertTrue(caption.contains("Obligatorio"), "expected Obligatorio in: " + caption);
    assertTrue(caption.contains("Facturas y asientos"), "expected scope in: " + caption);
  }

  @Test
  @DisplayName("Dimension caption for optional PR type contains Opcional and Ventas y compras")
  void dimensionCaptionOptionalPR() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(List.of(dimension("dim-pr", "Product", true, false, "PR", 10L)));

    JSONObject row = aggregateRow(handler.handle(getCtx(ORG_ID)));
    String caption = row.getJSONArray("dimensions").getJSONObject(0).getString("caption");

    assertTrue(caption.contains("Opcional"), "expected Opcional in: " + caption);
    assertTrue(caption.contains("Ventas y compras"), "expected scope in: " + caption);
  }

  @Test
  @DisplayName("Dimension caption for PJ type contains Todos los documentos")
  void dimensionCaptionPJ() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(List.of(dimension("dim-pj", "Project", true, false, "PJ", 10L)));

    JSONObject row = aggregateRow(handler.handle(getCtx(ORG_ID)));
    String caption = row.getJSONArray("dimensions").getJSONObject(0).getString("caption");

    assertTrue(caption.contains("Todos los documentos"), "expected scope in: " + caption);
  }

  @Test
  @DisplayName("Dimension caption for unknown type falls back to Todos los documentos")
  void dimensionCaptionUnknownType() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(List.of(dimension("dim-zz", "Other", true, false, "ZZ", 10L)));

    JSONObject row = aggregateRow(handler.handle(getCtx(ORG_ID)));
    String caption = row.getJSONArray("dimensions").getJSONObject(0).getString("caption");

    assertTrue(caption.contains("Todos los documentos"), "expected fallback scope in: " + caption);
  }

  @Test
  @DisplayName("Dimension caption for null type contains only Opcional with no scope appended")
  void dimensionCaptionNullType() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(List.of(dimension("dim-null", "NoType", true, false, null, 10L)));

    JSONObject row = aggregateRow(handler.handle(getCtx(ORG_ID)));
    String caption = row.getJSONArray("dimensions").getJSONObject(0).getString("caption");

    assertTrue(caption.contains("Opcional"), "expected Opcional in: " + caption);
    assertFalse(caption.contains("·"), "null type should produce no scope separator in: " + caption);
  }

  // ── Group G — mergeOrgQuery body override ────────────────────────────────────

  @Test
  @DisplayName("POST with selectedOrgId in body resolves that org instead of the query-param org")
  void postWithOrgIdInBody() throws Exception {
    wireOrgWithLedger();

    AcctSchema schema2 = mock(AcctSchema.class);
    when(schema2.getId()).thenReturn("ledger-002");
    when(schema2.getName()).thenReturn("Body Ledger");
    when(schema2.getGAAP()).thenReturn("IFRS");
    when(schema2.isAccrual()).thenReturn(false);
    when(schema2.isAllowNegative()).thenReturn(false);
    when(schema2.getDescription()).thenReturn(null);
    when(schema2.getCurrency()).thenReturn(null);

    Organization org2 = mock(Organization.class);
    when(org2.getId()).thenReturn("body-org");
    when(org2.getName()).thenReturn("Body Org");
    when(org2.getCalendar()).thenReturn(null);
    when(org2.getGeneralLedger()).thenReturn(schema2);
    when(obDal.get(Organization.class, "body-org")).thenReturn(org2);

    // Wire criteria for org2's schema
    AcctSchemaDefault defaults2 = mock(AcctSchemaDefault.class);
    OBCriteria<AcctSchemaDefault> defCrit2 = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchemaDefault.class)).thenReturn(defCrit2);
    when(defCrit2.add(any())).thenReturn(defCrit2);
    when(defCrit2.addOrder(any())).thenReturn(defCrit2);
    when(defCrit2.setMaxResults(anyInt())).thenReturn(defCrit2);
    when(defCrit2.uniqueResult()).thenReturn(defaults2);

    OBCriteria<AcctSchemaElement> elemCrit2 = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchemaElement.class)).thenReturn(elemCrit2);
    when(elemCrit2.setFilterOnActive(anyBoolean())).thenReturn(elemCrit2);
    when(elemCrit2.add(any())).thenReturn(elemCrit2);
    when(elemCrit2.addOrder(any())).thenReturn(elemCrit2);
    when(elemCrit2.list()).thenReturn(Collections.emptyList());

    OBCriteria<AccountingCombination> accCrit2 = mock(OBCriteria.class);
    when(obDal.createCriteria(AccountingCombination.class)).thenReturn(accCrit2);
    when(accCrit2.add(any())).thenReturn(accCrit2);
    when(accCrit2.addOrder(any())).thenReturn(accCrit2);
    when(accCrit2.list()).thenReturn(Collections.emptyList());

    OBCriteria<Currency> curCrit2 = mock(OBCriteria.class);
    when(obDal.createCriteria(Currency.class)).thenReturn(curCrit2);
    when(curCrit2.add(any())).thenReturn(curCrit2);
    when(curCrit2.addOrder(any())).thenReturn(curCrit2);
    when(curCrit2.setMaxResults(anyInt())).thenReturn(curCrit2);
    when(curCrit2.list()).thenReturn(Collections.emptyList());

    wireGeneralAccountsCriteria();

    JSONObject body = new JSONObject()
        .put("selectedOrgId", "body-org")
        .put("general", new JSONObject().put("name", "N"));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    JSONObject orgInfo = aggregateRow(response).getJSONObject("orgInfo");
    assertEquals("Body Org", orgInfo.getString("organization"));
  }

  // ── Group H — loadState null defaults branch ─────────────────────────────────

  @Test
  @DisplayName("GET returns 400 when no AcctSchemaDefault row exists for the ledger")
  @SuppressWarnings("unchecked")
  void getNoDefaultsReturns400() {
    wireOrgWithLedger();

    OBCriteria<AcctSchemaDefault> defCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchemaDefault.class)).thenReturn(defCrit);
    when(defCrit.add(any())).thenReturn(defCrit);
    when(defCrit.addOrder(any())).thenReturn(defCrit);
    when(defCrit.setMaxResults(anyInt())).thenReturn(defCrit);
    when(defCrit.uniqueResult()).thenReturn(null);

    NeoResponse response = handler.handle(getCtx(ORG_ID));
    assertEquals(400, response.getHttpStatus());
  }

  // ── Group I — resolveTargetOrganization "0" org path ─────────────────────────

  @Test
  @DisplayName("Current org with id '0' (system org) returns 400 — no concrete org available")
  void zeroOrgIdCurrentOrgReturns400() {
    Organization zeroOrg = mock(Organization.class);
    when(zeroOrg.getId()).thenReturn("0");

    OBContext ctx = mock(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(ctx);
    when(ctx.getCurrentOrganization()).thenReturn(zeroOrg);

    NeoResponse response = handler.handle(getCtx(null));
    assertEquals(400, response.getHttpStatus());
  }

  // ── Group J — handle() generic Exception → 500 ───────────────────────────────

  @Test
  @DisplayName("Unexpected RuntimeException in the aggregate pipeline returns 500")
  void unexpectedExceptionReturns500() {
    wireOrgWithLedger();
    when(obDal.get(Organization.class, ORG_ID))
        .thenThrow(new RuntimeException("boom"));

    NeoResponse response = handler.handle(getCtx(ORG_ID));
    assertEquals(500, response.getHttpStatus());
  }

  // ── Group K — dimension deactivate-then-reload regression (item 7) ──────────

  @Test
  @DisplayName("Deactivating a non-mandatory dimension does not drop it from the very next reload")
  void deactivatedDimensionSurvivesReload() throws Exception {
    wireOrgWithLedger();
    AcctSchemaElement dim = dimension("dim-prod", "Producto", true, false, "PR", 20L);
    wireLoadCriteria(List.of(dim));

    JSONObject body = new JSONObject().put("dimensions",
        new JSONArray().put(new JSONObject().put("id", "dim-prod").put("active", false)));

    // Simulate the deactivation actually taking effect for the post-save reload.
    when(dim.isActive()).thenReturn(false);

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    verify(dim).setActive(false);
    // The dimensions criteria must not filter on IsActive — the deactivated row is still present
    // in the refreshed aggregate row, not silently dropped.
    JSONArray dimensions = aggregateRow(response).getJSONArray("dimensions");
    assertEquals(1, dimensions.length());
    assertEquals("dim-prod", dimensions.getJSONObject(0).getString("id"));
    assertFalse(dimensions.getJSONObject(0).getBoolean("active"));
  }

  @Test
  @DisplayName("The dimensions criteria explicitly disables the default active-only filter")
  void dimensionsCriteriaDisablesFilterOnActive() throws Exception {
    wireOrgWithLedger();
    AcctSchemaElement dim = dimension("dim-prod", "Producto", false, false, "PR", 20L);
    wireLoadCriteria(List.of(dim));

    handler.handle(getCtx(ORG_ID));

    // Captured via the mocked OBCriteria chain: setFilterOnActive(false) must be invoked so
    // inactive dimensions are still returned.
    OBCriteria<AcctSchemaElement> elemCrit = obDal.createCriteria(AcctSchemaElement.class);
    verify(elemCrit).setFilterOnActive(false);
  }

  // ── Group L — general accounts (item 2) ──────────────────────────────────────

  @Test
  @DisplayName("GET generalAccounts reflects the AcctSchemaGL row's boolean and account fields")
  void getGeneralAccountsShape() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());

    when(generalAccounts.isSuspenseBalancingUse()).thenReturn(true);
    when(generalAccounts.isSuspenseErrorUse()).thenReturn(false);
    when(generalAccounts.isCurrencyBalancingUse()).thenReturn(true);
    when(generalAccounts.isActive()).thenReturn(true);
    when(generalAccounts.isCreateClosing()).thenReturn(true);

    AccountingCombination retainedEarning = mock(AccountingCombination.class);
    when(retainedEarning.getId()).thenReturn("combo-retained");
    when(generalAccounts.getRetainedEarning()).thenReturn(retainedEarning);

    JSONObject row = aggregateRow(handler.handle(getCtx(ORG_ID)));
    JSONObject ga = row.getJSONObject("generalAccounts");

    assertEquals("gl-acct-001", ga.getString("id"));
    assertTrue(ga.getBoolean("suspenseBalancingUse"));
    assertFalse(ga.getBoolean("suspenseErrorUse"));
    assertTrue(ga.getBoolean("currencyBalancingUse"));
    assertTrue(ga.getBoolean("active"));
    assertTrue(ga.getBoolean("createClosing"));
    assertEquals("combo-retained", ga.getString("retainedEarning"));
  }

  @Test
  @DisplayName("POST writes generalAccounts boolean and account fields and saves the row")
  void postUpdatesGeneralAccounts() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());

    AccountingCombination combo = mock(AccountingCombination.class);
    when(combo.getId()).thenReturn("combo-income");
    when(obDal.get(AccountingCombination.class, "combo-income")).thenReturn(combo);

    JSONObject body = new JSONObject().put("generalAccounts", new JSONObject()
        .put("suspenseBalancingUse", true)
        .put("incomeSummary", "combo-income")
        .put("createClosing", false));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));

    assertEquals(200, response.getHttpStatus());
    verify(generalAccounts).setSuspenseBalancingUse(true);
    verify(generalAccounts).setIncomeSummary(combo);
    verify(generalAccounts).setCreateClosing(false);
    verify(obDal).save(generalAccounts);
  }

  @Test
  @DisplayName("POST with an unknown generalAccounts combination id returns 400")
  void postGeneralAccountsCombinationNotFoundReturns400() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());
    when(obDal.get(AccountingCombination.class, "missing-combo")).thenReturn(null);

    JSONObject body = new JSONObject().put("generalAccounts",
        new JSONObject().put("retainedEarning", "missing-combo"));

    NeoResponse response = handler.handle(postCtx(ORG_ID, body));
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  @DisplayName("GET returns 400 when no AcctSchemaGL row exists for the ledger")
  @SuppressWarnings("unchecked")
  void getNoGeneralAccountsReturns400() {
    wireOrgWithLedger();

    defaults = mock(AcctSchemaDefault.class);
    OBCriteria<AcctSchemaDefault> defCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchemaDefault.class)).thenReturn(defCrit);
    when(defCrit.add(any())).thenReturn(defCrit);
    when(defCrit.addOrder(any())).thenReturn(defCrit);
    when(defCrit.setMaxResults(anyInt())).thenReturn(defCrit);
    when(defCrit.uniqueResult()).thenReturn(defaults);

    OBCriteria<AcctSchemaElement> elemCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchemaElement.class)).thenReturn(elemCrit);
    when(elemCrit.setFilterOnActive(anyBoolean())).thenReturn(elemCrit);
    when(elemCrit.add(any())).thenReturn(elemCrit);
    when(elemCrit.addOrder(any())).thenReturn(elemCrit);
    when(elemCrit.list()).thenReturn(Collections.emptyList());

    OBCriteria<AcctSchemaGL> glCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchemaGL.class)).thenReturn(glCrit);
    when(glCrit.add(any())).thenReturn(glCrit);
    when(glCrit.addOrder(any())).thenReturn(glCrit);
    when(glCrit.setMaxResults(anyInt())).thenReturn(glCrit);
    when(glCrit.uniqueResult()).thenReturn(null);

    NeoResponse response = handler.handle(getCtx(ORG_ID));
    assertEquals(400, response.getHttpStatus());
  }
}
