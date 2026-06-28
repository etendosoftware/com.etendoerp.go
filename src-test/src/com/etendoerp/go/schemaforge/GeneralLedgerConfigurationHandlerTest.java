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
import org.openbravo.model.financialmgmt.calendar.Calendar;

/**
 * Unit tests for {@link GeneralLedgerConfigurationHandler}.
 *
 * <p>The handler is an org-scoped aggregate over one accounting schema (general / defaults /
 * dimensions / documents / orgInfo / catalogs / meta). These tests isolate the static
 * {@link OBDal} and {@link OBContext} dependencies with Mockito {@code MockedStatic}, so no DB
 * or CDI container is required. The model graph (organization → ledger → defaults/dimensions)
 * is built from plain mocks.</p>
 *
 * <p>Coverage:</p>
 * <ul>
 *   <li>routing guards (spec / entity / endpoint type)</li>
 *   <li>GET aggregate shape (all top-level keys, orgInfo calendar + name, meta.documentsBacked=false)</li>
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
    when(schema.isAutomaticPeriodControl()).thenReturn(false);
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
    for (String key : new String[] { "general", "defaults", "dimensions", "documents",
        "orgInfo", "catalogs", "meta" }) {
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
  @DisplayName("GET orgInfo carries calendar + organization name; meta.documentsBacked is false")
  void getOrgInfoAndMeta() throws Exception {
    wireOrgWithLedger();
    wireLoadCriteria(Collections.emptyList());

    JSONObject row = aggregateRow(handler.handle(getCtx(ORG_ID)));

    JSONObject orgInfo = row.getJSONObject("orgInfo");
    assertEquals("Espana S.A.", orgInfo.getString("organization"));
    assertEquals("Calendario 2026", orgInfo.getString("fiscalCalendar"));

    JSONObject meta = row.getJSONObject("meta");
    assertFalse(meta.getBoolean("documentsBacked"));

    // Documents are seed rows and read-only — present but not DB-backed.
    assertTrue(row.getJSONArray("documents").length() > 0);
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
}
