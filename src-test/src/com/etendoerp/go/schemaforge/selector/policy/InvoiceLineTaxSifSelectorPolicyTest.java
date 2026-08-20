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
package com.etendoerp.go.schemaforge.selector.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.tax.TaxRate;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Unit tests for {@link InvoiceLineTaxSifSelectorPolicy} (ETP-4888).
 *
 * <p>Covers: window/entity/target-entity scoping ({@code supports}, pure — no DB), the
 * short-circuit branches of {@code enrich} (null/empty response — no DB), and the
 * DB-backed enrichment path (mocked JDBC via {@code OBDal.getReadOnlyInstance()}, same
 * workaround already used by {@code InvoiceLineHandlerTest#shouldAutoFillExemptionCause}):
 * correct SQL projection columns, a single BATCHED query regardless of row count (not
 * N+1), correct column-to-JSON-key mapping, and safe handling of unmatched ids / null DB
 * values / a thrown SQLException.
 */
public class InvoiceLineTaxSifSelectorPolicyTest {

  private static final String ENTITY_LINES = "lines";
  private static final String ENTITY_HEADER = "header";
  private static final String WINDOW_SALES_INVOICE = "167";
  private static final String WINDOW_PURCHASE_INVOICE = "183";
  // ETP-4888 follow-up round: sales-order/purchase-order joined the in-scope set, so
  // WINDOW_OTHER must be a window id genuinely outside all four — not 143 any more.
  private static final String WINDOW_SALES_ORDER = "143";
  private static final String WINDOW_PURCHASE_ORDER = "181";
  private static final String WINDOW_OTHER = "999";
  // Bound to the GENERATED DAL model's own entity name, NEVER a hardcoded literal.
  // A literal here would silently mirror whatever the production constant says, making
  // production and test mutually confirming instead of one verifying the other: that is
  // exactly how ETP-4888 shipped with TAX_TARGET_ENTITY = "TaxRate" (the Java SIMPLE CLASS
  // name) while the real DAL entity name is "FinancialMgmtTaxRate", leaving supports()
  // permanently false and the whole policy dead in production with a fully green suite.
  // org.openbravo.model.financialmgmt.tax.TaxRate is on the unit-test classpath, so this
  // stays a pure unit test — no DB, no OBBaseTest, no ModelProvider bootstrap needed.
  private static final String TARGET_TAX_RATE = TaxRate.ENTITY_NAME;
  private static final String TARGET_PRODUCT = "Product";

  private final InvoiceLineTaxSifSelectorPolicy policy = new InvoiceLineTaxSifSelectorPolicy();

  private static Map<String, String> ctx(String sourceEntity, String windowId) {
    Map<String, String> params = new HashMap<>();
    if (sourceEntity != null) {
      params.put(NeoSelectorService.SOURCE_ENTITY_NAME_PARAM, sourceEntity);
    }
    if (windowId != null) {
      params.put(NeoSelectorService.SOURCE_WINDOW_ID_PARAM, windowId);
    }
    return params;
  }

  private static SelectorMeta metaFor(String entityName) {
    return new SelectorMeta(entityName, "name", null);
  }

  // ── The production constant itself vs. the generated DAL model ──────────

  /**
   * Pins {@code InvoiceLineTaxSifSelectorPolicy.TAX_TARGET_ENTITY} to the DAL entity name
   * that {@code SelectorDescriptorResolver} actually puts in {@link SelectorMeta#entityName}
   * at runtime (i.e. {@code ModelProvider.getEntityByTableName("C_Tax").getName()}, which the
   * generated model exposes as {@link TaxRate#ENTITY_NAME}).
   *
   * <p>Every other test in this class exercises {@code supports()} through {@code metaFor()},
   * so a wrong constant shows up only INDIRECTLY, as "supports() returned false" — which reads
   * like an ordinary assertion failure and says nothing about why. This test fails LOUDLY and
   * specifically instead, naming both values and what the drift costs.
   *
   * <p>ETP-4888 shipped with {@code TAX_TARGET_ENTITY = "TaxRate"} — the Java SIMPLE CLASS
   * name, not the DAL entity name — so {@code supports()} was permanently false and this
   * policy never ran once in production, for its entire life, with a fully green test suite.
   */
  @Test
  public void productionTargetEntityConstantMatchesTheGeneratedDalModelEntityName() throws Exception {
    Field field = InvoiceLineTaxSifSelectorPolicy.class.getDeclaredField("TAX_TARGET_ENTITY");
    field.setAccessible(true);
    String productionValue = (String) field.get(null);

    assertEquals(
        "InvoiceLineTaxSifSelectorPolicy.TAX_TARGET_ENTITY is \"" + productionValue
            + "\" but the DAL entity name for C_Tax is \"" + TaxRate.ENTITY_NAME
            + "\" (org.openbravo.model.financialmgmt.tax.TaxRate.ENTITY_NAME). "
            + "SelectorDescriptorResolver fills SelectorMeta.entityName from "
            + "ModelProvider.getEntityByTableName(\"C_Tax\").getName(), so any other value "
            + "makes supports() permanently false and silently kills the whole policy in "
            + "production — the invoice-lines Tax selector stops being enriched and the "
            + "frontend's TBAI/Verifactu SIF-missing badges never light up. Beware the Java "
            + "SIMPLE CLASS name \"TaxRate\": that was the original ETP-4888 bug.",
        TaxRate.ENTITY_NAME, productionValue);
  }

  // ── supports() — window/entity/target-entity scoping (pure, no DB) ────────

  @Test
  public void supportsSalesInvoiceLinesTaxSelector() {
    assertTrue(policy.supports(metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_SALES_INVOICE)));
  }

  @Test
  public void supportsPurchaseInvoiceLinesTaxSelector() {
    assertTrue(policy.supports(metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_PURCHASE_INVOICE)));
  }

  @Test
  public void supportsSalesOrderLinesTaxSelector() {
    assertTrue(policy.supports(metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_SALES_ORDER)));
  }

  @Test
  public void supportsPurchaseOrderLinesTaxSelector() {
    assertTrue(policy.supports(metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_PURCHASE_ORDER)));
  }

  @Test
  public void doesNotSupportOtherWindows() {
    assertFalse(policy.supports(metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_OTHER)));
  }

  @Test
  public void doesNotSupportOtherEntityNamesEvenForInScopeWindows() {
    // "lines" alone is shared across unrelated windows' detail tabs; the SAME entity
    // name under a DIFFERENT window id must not match, and neither should an
    // unrelated entity name even under an in-scope window id.
    assertFalse(policy.supports(metaFor(TARGET_TAX_RATE), ctx(ENTITY_HEADER, WINDOW_SALES_INVOICE)));
  }

  @Test
  public void doesNotSupportOtherTargetEntities() {
    // Guards against misfiring against the SAME "lines" entity's Product selector.
    assertFalse(policy.supports(metaFor(TARGET_PRODUCT), ctx(ENTITY_LINES, WINDOW_SALES_INVOICE)));
  }

  @Test
  public void doesNotSupportWhenMetaIsNull() {
    assertFalse(policy.supports(null, ctx(ENTITY_LINES, WINDOW_SALES_INVOICE)));
  }

  @Test
  public void doesNotSupportWhenContextParamsIsNull() {
    assertFalse(policy.supports(metaFor(TARGET_TAX_RATE), null));
  }

  // FIXED SOURCE BUG (ETP-4888, found by this test, fixed in a follow-up commit):
  // `supports()` used to call `IN_SCOPE_WINDOW_IDS.contains(sourceWindowId)` where
  // IN_SCOPE_WINDOW_IDS is `Set.of("167", "183")`. `Set.of(...)`'s immutable-set
  // `.contains(null)` THROWS NullPointerException instead of returning false (verified:
  // java.util.ImmutableCollections$Set12.contains rejects null outright). `sourceWindowId`
  // is legitimately null whenever a spec's SFEntity -> SFSpec -> AD_Window chain can't be
  // resolved (NeoSelectorService#resolveSourceWindowId's own documented null-return
  // contract) — which happens for every OTHER window's "lines"-named entity too, not just
  // this policy's two in-scope windows. Since NeoSelectorPolicy.enrichSelectorResult calls
  // supports() unconditionally for every registered enrichment policy on every selector
  // request, this would have thrown a real NPE on ordinary selector calls for unrelated
  // windows whenever _sourceWindowId is absent — a production crash risk, not just a
  // cosmetic gap. Fixed by adding a `sourceWindowId != null &&` short-circuit before the
  // .contains() call (IN_SCOPE_WINDOW_IDS deliberately kept as Set.of(...)).
  @Test
  public void doesNotSupportWhenSourceWindowIdIsMissing() {
    // _sourceEntityName alone, with no _sourceWindowId at all (e.g. a spec whose
    // window link could not be resolved), must not match — and must NOT throw.
    assertFalse(policy.supports(metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, null)));
  }

  @Test
  public void doesNotSupportWhenSourceEntityIsMissing() {
    assertFalse(policy.supports(metaFor(TARGET_TAX_RATE), ctx(null, WINDOW_SALES_INVOICE)));
  }

  // ── enrich() — short-circuit branches (no DB access) ───────────────────────

  @Test
  public void enrichReturnsResponseUnchangedWhenResponseIsNull() {
    assertNull(policy.enrich(null, metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_SALES_INVOICE)));
  }

  @Test
  public void enrichReturnsResponseUnchangedWhenBodyIsNull() {
    NeoResponse response = new NeoResponse(200, null);
    assertSame(response, policy.enrich(response, metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_SALES_INVOICE)));
  }

  @Test
  public void enrichReturnsResponseUnchangedWhenItemsKeyIsMissing() throws Exception {
    NeoResponse response = new NeoResponse(200, new JSONObject().put("total", 0));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      NeoResponse result = policy.enrich(response, metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_SALES_INVOICE));
      assertSame(response, result);
      dalMock.verifyNoInteractions();
    }
  }

  @Test
  public void enrichReturnsResponseUnchangedWhenItemsArrayIsEmpty() throws Exception {
    NeoResponse response = new NeoResponse(200, new JSONObject().put("items", new JSONArray()));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      NeoResponse result = policy.enrich(response, metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_SALES_INVOICE));
      assertSame(response, result);
      dalMock.verifyNoInteractions();
    }
  }

  @Test
  public void enrichSkipsTheDbEntirelyWhenEveryItemIdIsBlank() throws Exception {
    JSONArray items = new JSONArray()
        .put(new JSONObject().put("id", ""))
        .put(new JSONObject());
    NeoResponse response = new NeoResponse(200, new JSONObject().put("items", items));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      NeoResponse result = policy.enrich(response, metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_SALES_INVOICE));
      assertSame(response, result);
      dalMock.verifyNoInteractions();
    }
  }

  // ── enrich() — DB-backed enrichment (mocked JDBC) ──────────────────────────

  /** Wires OBDal.getReadOnlyInstance().getConnection() -> a mocked Connection. */
  private static Connection wireConnection(MockedStatic<OBDal> dalMock) throws SQLException {
    OBDal dal = mock(OBDal.class);
    dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    return conn;
  }

  /** Stubs a single-row ResultSet on {@code ps}, serving {@code row}'s values via rs.getString(column). */
  private static void stubResultSetForTax(PreparedStatement ps, Map<String, String> row) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    when(ps.executeQuery()).thenReturn(rs);
    // One row, then exhausted.
    when(rs.next()).thenReturn(true, false);
    for (Map.Entry<String, String> e : row.entrySet()) {
      when(rs.getString(e.getKey())).thenReturn(e.getValue());
    }
  }

  @Test
  public void enrichProjectsAllTenColumnsWithTheirExactJsonKeysAndBatchesOneQueryForMultipleIds() throws Exception {
    JSONArray items = new JSONArray()
        .put(new JSONObject().put("id", "tax-1"))
        .put(new JSONObject().put("id", "tax-2"))
        .put(new JSONObject().put("id", "tax-3"));
    NeoResponse response = new NeoResponse(200, new JSONObject().put("items", items));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Connection conn = wireConnection(dalMock);
      PreparedStatement ps = mock(PreparedStatement.class);
      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      when(conn.prepareStatement(sqlCaptor.capture())).thenReturn(ps);

      Map<String, String> row1 = new HashMap<>();
      row1.put("c_tax_id", "tax-1");
      row1.put("istaxexempt", "Y");
      row1.put("isnotaxable", "N");
      row1.put("em_tbai_claveregimeniva", "05");
      row1.put("em_tbai_exemptioncause", "E1");
      row1.put("em_tbai_nonsubjectcause", null);
      row1.put("em_etvfac_vat_regime", "01");
      row1.put("em_etvfac_igic_regime", null);
      row1.put("em_etvfac_ipsi_regime", null);
      row1.put("em_etvfac_exemption_cause", null);
      row1.put("em_etvfac_cause_not_taxable", null);
      stubResultSetForTax(ps, row1);

      policy.enrich(response, metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_SALES_INVOICE));

      // Batched: exactly ONE prepareStatement call regardless of the 3 distinct ids
      // in the page — proves this is not N+1 (one query per tax id).
      verify(conn, times(1)).prepareStatement(anyString());

      String sql = sqlCaptor.getValue().toLowerCase();
      assertTrue("must select from c_tax", sql.contains("from c_tax"));
      assertTrue("must project c_tax_id", sql.contains("c_tax_id"));
      // All 8 TBAI/Verifactu key columns + the two completeness flags.
      for (String column : new String[] {
          "istaxexempt", "isnotaxable",
          "em_tbai_claveregimeniva", "em_tbai_exemptioncause", "em_tbai_nonsubjectcause",
          "em_etvfac_vat_regime", "em_etvfac_igic_regime", "em_etvfac_ipsi_regime",
          "em_etvfac_exemption_cause", "em_etvfac_cause_not_taxable",
      }) {
        assertTrue("SQL must project " + column, sql.contains(column));
      }
      // 3 distinct ids in the page -> 3 placeholders in the IN clause.
      long placeholderCount = sql.chars().filter(c -> c == '?').count();
      assertEquals(3, placeholderCount);

      // The first item (tax-1) is enriched with the EXACT raw-column JSON keys
      // (casing matters — matches selectSifFields()'s buildField() `column` values).
      JSONObject enrichedItem1 = items.getJSONObject(0);
      assertEquals("Y", enrichedItem1.getString("taxExempt"));
      assertEquals("N", enrichedItem1.getString("notTaxable"));
      assertEquals("05", enrichedItem1.getString("EM_Tbai_Claveregimeniva"));
      assertEquals("E1", enrichedItem1.getString("EM_Tbai_Exemptioncause"));
      assertEquals("01", enrichedItem1.getString("EM_Etvfac_Vat_Regime"));
      // Null DB values are never written onto the item at all (extractRow skips them).
      assertFalse(enrichedItem1.has("EM_Tbai_Nonsubjectcause"));
      assertFalse(enrichedItem1.has("em_etvfac_igic_regime"));
    }
  }

  @Test
  public void enrichLeavesItemsWithNoMatchingDbRowUntouched() throws Exception {
    JSONArray items = new JSONArray().put(new JSONObject().put("id", "tax-unmatched"));
    NeoResponse response = new NeoResponse(200, new JSONObject().put("items", items));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Connection conn = wireConnection(dalMock);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      // No rows returned at all (tax id not found — e.g. race with a delete).
      when(rs.next()).thenReturn(false);

      policy.enrich(response, metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_SALES_INVOICE));

      JSONObject item = items.getJSONObject(0);
      assertFalse(item.has("taxExempt"));
      assertFalse(item.has("EM_Tbai_Claveregimeniva"));
    }
  }

  @Test
  public void enrichBindsEveryDistinctIdAsAPreparedStatementParameter() throws Exception {
    JSONArray items = new JSONArray()
        .put(new JSONObject().put("id", "tax-a"))
        .put(new JSONObject().put("id", "tax-b"));
    NeoResponse response = new NeoResponse(200, new JSONObject().put("items", items));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Connection conn = wireConnection(dalMock);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      policy.enrich(response, metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_SALES_INVOICE));

      verify(ps).setString(1, "tax-a");
      verify(ps).setString(2, "tax-b");
    }
  }

  @Test
  public void enrichSwallowsAnySqlExceptionAndReturnsTheResponseUnchanged() throws Exception {
    JSONArray items = new JSONArray().put(new JSONObject().put("id", "tax-1"));
    NeoResponse response = new NeoResponse(200, new JSONObject().put("items", items));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Connection conn = wireConnection(dalMock);
      when(conn.prepareStatement(anyString())).thenThrow(new SQLException("connection reset"));

      NeoResponse result = policy.enrich(response, metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_SALES_INVOICE));

      // Same response instance returned (unmodified body), no exception propagated.
      assertSame(response, result);
      assertFalse(items.getJSONObject(0).has("taxExempt"));
    }
  }

  // ── Dispatch through the registry facade (NeoSelectorPolicy) — proves the
  //    real end-to-end wiring registered in NeoSelectorPolicy.java, not just
  //    this class's own supports()/enrich() in isolation. ──────────────────

  @Test
  public void neoSelectorPolicyDispatchesToThisPolicyForAnInScopeWindow() throws Exception {
    JSONArray items = new JSONArray().put(new JSONObject().put("id", "tax-1"));
    NeoResponse response = new NeoResponse(200, new JSONObject().put("items", items));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Connection conn = wireConnection(dalMock);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true, false);
      when(rs.getString("c_tax_id")).thenReturn("tax-1");
      when(rs.getString("em_tbai_claveregimeniva")).thenReturn("05");

      NeoResponse result = NeoSelectorPolicy.enrichSelectorResult(
          response, metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_PURCHASE_INVOICE));

      assertEquals("05", result.getBody().getJSONArray("items").getJSONObject(0)
          .getString("EM_Tbai_Claveregimeniva"));
    }
  }

  @Test
  public void neoSelectorPolicyDispatchesToThisPolicyForSalesOrder() throws Exception {
    JSONArray items = new JSONArray().put(new JSONObject().put("id", "tax-1"));
    NeoResponse response = new NeoResponse(200, new JSONObject().put("items", items));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Connection conn = wireConnection(dalMock);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true, false);
      when(rs.getString("c_tax_id")).thenReturn("tax-1");
      when(rs.getString("em_tbai_claveregimeniva")).thenReturn("05");

      NeoResponse result = NeoSelectorPolicy.enrichSelectorResult(
          response, metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_SALES_ORDER));

      assertEquals("05", result.getBody().getJSONArray("items").getJSONObject(0)
          .getString("EM_Tbai_Claveregimeniva"));
    }
  }

  @Test
  public void neoSelectorPolicyDoesNotDispatchForAnOutOfScopeWindow() throws Exception {
    JSONArray items = new JSONArray().put(new JSONObject().put("id", "tax-1"));
    NeoResponse response = new NeoResponse(200, new JSONObject().put("items", items));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      NeoResponse result = NeoSelectorPolicy.enrichSelectorResult(
          response, metaFor(TARGET_TAX_RATE), ctx(ENTITY_LINES, WINDOW_OTHER));

      assertFalse(result.getBody().getJSONArray("items").getJSONObject(0).has("EM_Tbai_Claveregimeniva"));
      dalMock.verifyNoInteractions();
    }
  }
}
