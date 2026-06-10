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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * Unit tests for {@link LineCalloutTaxRateHelper#augmentTaxRate}.
 *
 * <p>The class is package-private (same package as the test). All tests mock
 * {@link OBDal} statically so no live database is needed.</p>
 */
public class LineCalloutTaxRateHelperTest {

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<ModelProvider> modelProviderMock;
  private OBDal dal;

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);

    // ModelProvider is a singleton with static state. Mock it so column resolution
    // is deterministic regardless of test ordering: returning a null entity makes
    // matchByDalProperty fall through to matchByDbName (DB-column-name matching),
    // which is what these tests exercise. Without this the real ModelProvider may
    // be initialized by another test in the suite and throw on getEntityByTableId,
    // making the result depend on suite order.
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntityByTableId(any())).thenReturn(null);
    modelProviderMock = mockStatic(ModelProvider.class);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
  }

  @After
  public void tearDown() {
    modelProviderMock.close();
    obDalMock.close();
  }

  // ── null context ──────────────────────────────────────────────────────────

  @Test
  public void augmentTaxRate_nullContext_returnsNull() {
    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(null));
  }

  // ── wrong endpoint type ───────────────────────────────────────────────────

  @Test
  public void augmentTaxRate_crudEndpoint_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(new JSONObject())
        .build();
    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(ctx));
  }

  @Test
  public void augmentTaxRate_selectorEndpoint_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .requestBody(new JSONObject())
        .build();
    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(ctx));
  }

  // ── callout with null body ────────────────────────────────────────────────

  @Test
  public void augmentTaxRate_calloutNullBody_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CALLOUT)
        .requestBody(null)
        .build();
    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(ctx));
  }

  // ── Path A: existing tax update in previousResult ─────────────────────────

  /**
   * When the previous response already contains a {@code tax} key, the helper
   * normalizes the identifier in place and returns null (no separate response).
   */
  @Test
  public void augmentTaxRate_existingTaxUpdate_withResolvableTaxRate_returnsNull()
      throws Exception {
    String taxId = "tax-id-001";
    TaxRate taxRate = mock(TaxRate.class);
    when(taxRate.getName()).thenReturn("Standard VAT 21%");
    when(dal.get(TaxRate.class, taxId)).thenReturn(taxRate);

    JSONObject taxEntry = new JSONObject();
    taxEntry.put("value", taxId);
    JSONObject updates = new JSONObject();
    updates.put("tax", taxEntry);
    JSONObject prevBody = new JSONObject();
    prevBody.put("updates", updates);
    NeoResponse prev = new NeoResponse(200, prevBody);

    JSONObject requestBody = new JSONObject();
    requestBody.put("field", "tax");
    requestBody.put("value", taxId);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CALLOUT)
        .requestBody(requestBody)
        .previousResult(prev)
        .build();

    NeoResponse result = LineCalloutTaxRateHelper.augmentTaxRate(ctx);
    // Path A returns null; normalization happened in-place on the updates object
    assertNull(result);
    // The identifier should have been set in the updates JSON
    assertEquals("Standard VAT 21%",
        updates.optJSONObject("tax$_identifier").optString("value"));
  }

  @Test
  public void augmentTaxRate_existingTaxUpdateButTaxNotResolvable_returnsNull()
      throws Exception {
    when(dal.get(eq(TaxRate.class), any())).thenReturn(null);

    JSONObject taxEntry = new JSONObject();
    taxEntry.put("value", "unknown-tax-id");
    JSONObject updates = new JSONObject();
    updates.put("tax", taxEntry);
    JSONObject prevBody = new JSONObject();
    prevBody.put("updates", updates);
    NeoResponse prev = new NeoResponse(200, prevBody);

    JSONObject requestBody = new JSONObject();
    requestBody.put("field", "tax");
    requestBody.put("value", "unknown-tax-id");

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CALLOUT)
        .requestBody(requestBody)
        .previousResult(prev)
        .build();

    // Should not throw, returns null (no identifier to normalize)
    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(ctx));
  }

  // ── Path B: no existing updates, but previous already has taxRate ─────────

  @Test
  public void augmentTaxRate_existingTaxRateInUpdates_returnsNull() throws Exception {
    JSONObject rateEntry = new JSONObject();
    rateEntry.put("value", 21.0);
    JSONObject updates = new JSONObject();
    updates.put("taxRate", rateEntry);
    JSONObject prevBody = new JSONObject();
    prevBody.put("updates", updates);
    NeoResponse prev = new NeoResponse(200, prevBody);

    JSONObject requestBody = new JSONObject();
    requestBody.put("field", "tax");
    requestBody.put("value", "some-tax-id");

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CALLOUT)
        .requestBody(requestBody)
        .previousResult(prev)
        .build();

    // taxRate already in updates — no enrichment needed
    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(ctx));
  }

  // ── Path B: blank field name ──────────────────────────────────────────────

  @Test
  public void augmentTaxRate_blankFieldName_returnsNull() throws Exception {
    JSONObject requestBody = new JSONObject();
    requestBody.put("field", "  ");
    requestBody.put("value", "some-tax-id");

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CALLOUT)
        .requestBody(requestBody)
        .build();

    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(ctx));
  }

  @Test
  public void augmentTaxRate_missingFieldKey_returnsNull() throws Exception {
    JSONObject requestBody = new JSONObject();
    requestBody.put("value", "some-tax-id");
    // no "field" key

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CALLOUT)
        .requestBody(requestBody)
        .build();

    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(ctx));
  }

  // ── Path B: null adTab / table ────────────────────────────────────────────

  @Test
  public void augmentTaxRate_nullAdTab_returnsNull() throws Exception {
    JSONObject requestBody = new JSONObject();
    requestBody.put("field", "tax");
    requestBody.put("value", "tax-id-x");

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CALLOUT)
        .requestBody(requestBody)
        .adTab(null)
        .build();

    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(ctx));
  }

  @Test
  public void augmentTaxRate_adTabWithNullTable_returnsNull() throws Exception {
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(null);

    JSONObject requestBody = new JSONObject();
    requestBody.put("field", "tax");
    requestBody.put("value", "tax-id-x");

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CALLOUT)
        .requestBody(requestBody)
        .adTab(tab)
        .build();

    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(ctx));
  }

  // ── Path B: trigger is NOT the tax column ─────────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void augmentTaxRate_triggerNotTaxColumn_returnsNull() throws Exception {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("table-id-abc");

    // Column list that does NOT include C_Tax_ID matching fieldName "product"
    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("M_Product_ID");
    when(criteria.list()).thenReturn(Collections.singletonList(col));

    JSONObject requestBody = new JSONObject();
    requestBody.put("field", "product");
    requestBody.put("value", "prod-id");

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CALLOUT)
        .requestBody(requestBody)
        .adTab(tab)
        .build();

    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(ctx));
  }

  // ── Path B: trigger IS the tax column, but tax entity not found ───────────

  @SuppressWarnings("unchecked")
  @Test
  public void augmentTaxRate_taxColumnTrigger_taxNotFound_returnsNull() throws Exception {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("table-id-xyz");

    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("C_Tax_ID");
    when(criteria.list()).thenReturn(Collections.singletonList(col));

    // Tax not resolvable
    when(dal.get(eq(TaxRate.class), any())).thenReturn(null);

    JSONObject requestBody = new JSONObject();
    requestBody.put("field", "C_Tax_ID");
    requestBody.put("value", "missing-tax-id");

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CALLOUT)
        .requestBody(requestBody)
        .adTab(tab)
        .build();

    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(ctx));
  }

  // ── Path B: trigger IS tax column, tax found with null rate ──────────────

  @SuppressWarnings("unchecked")
  @Test
  public void augmentTaxRate_taxColumnTrigger_taxHasNullRate_returnsNull() throws Exception {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("table-id-xyz");

    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("C_Tax_ID");
    when(criteria.list()).thenReturn(Collections.singletonList(col));

    TaxRate taxRate = mock(TaxRate.class);
    when(taxRate.getRate()).thenReturn(null);
    when(dal.get(eq(TaxRate.class), any())).thenReturn(taxRate);

    JSONObject requestBody = new JSONObject();
    requestBody.put("field", "C_Tax_ID");
    requestBody.put("value", "some-tax-id");

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CALLOUT)
        .requestBody(requestBody)
        .adTab(tab)
        .build();

    assertNull(LineCalloutTaxRateHelper.augmentTaxRate(ctx));
  }

  // ── Path B: success — tax rate response built ─────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void augmentTaxRate_taxColumnTrigger_validTax_returnsTaxRateResponse()
      throws Exception {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("table-id-sales-order");

    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("C_Tax_ID");
    when(criteria.list()).thenReturn(Collections.singletonList(col));

    TaxRate taxRate = mock(TaxRate.class);
    when(taxRate.getRate()).thenReturn(new BigDecimal("21.00"));
    when(dal.get(eq(TaxRate.class), any())).thenReturn(taxRate);

    JSONObject requestBody = new JSONObject();
    requestBody.put("field", "C_Tax_ID");
    requestBody.put("value", "valid-tax-id");

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CALLOUT)
        .requestBody(requestBody)
        .adTab(tab)
        .build();

    NeoResponse result = LineCalloutTaxRateHelper.augmentTaxRate(ctx);
    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
    JSONObject updates = result.getBody().optJSONObject("updates");
    assertNotNull(updates);
    JSONObject taxRateEntry = updates.optJSONObject("taxRate");
    assertNotNull(taxRateEntry);
    assertEquals(21.0, taxRateEntry.getDouble("value"), 0.001);
  }
}
