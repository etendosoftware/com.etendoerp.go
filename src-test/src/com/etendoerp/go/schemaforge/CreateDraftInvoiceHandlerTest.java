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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.common.actionhandler.createlinesfromprocess.CreateInvoiceLinesFromProcess;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Unit tests for {@link CreateDraftInvoiceHandler}.
 *
 * <p>Organized by method:
 * <ul>
 *   <li><strong>Dispatch</strong> – routing via {@code handle()}, no CDI needed.</li>
 *   <li><strong>Pure-logic</strong> – methods with no OBDal/OBProvider calls, tested directly
 *       via protected access.</li>
 *   <li><strong>Mocked-CDI</strong> – methods that call OBDal/OBProvider/OBContext, tested
 *       via {@code mockStatic} + a {@link TestableHandler} subclass that overrides the
 *       costly static chains ({@code generateInvoiceDocumentNo}, {@code findARInvoiceDocType})
 *       so each test exercises only the method under test.</li>
 * </ul>
 */
public class CreateDraftInvoiceHandlerTest {

  // ── Constants used across tests ──────────────────────────────────────────

  private static final String SPEC_SALES_QUOTATION = "sales-quotation";
  private static final String SPEC_SALES_ORDER = "sales-order";
  private static final String SPEC_GOODS_SHIPMENT = "goods-shipment";
  private static final String ENTITY_HEADER = "header";
  private static final String ACTION_CREATE = "createDraftInvoice";
  private static final String ACTION_CHECK = "checkDraftInvoice";
  private static final String ACTION_LIST = "listInvoices";
  private static final int PRECISION = 2;

  // ── TestableHandler — overrides costly static chains ─────────────────────

  /**
   * Subclass used to isolate methods that internally call
   * {@code generateInvoiceDocumentNo} or {@code findARInvoiceDocType},
   * letting each test control those return values without static mocking.
   */
  private static class TestableHandler extends CreateDraftInvoiceHandler {
    String generatedDocNo = "DOC-TEST";
    DocumentType arDocTypeToReturn = null;

    @Override
    protected String generateInvoiceDocumentNo(Invoice invoice) {
      return generatedDocNo;
    }

    @Override
    protected DocumentType findARInvoiceDocType(String orgId) {
      return arDocTypeToReturn;
    }
  }

  // ── handle() — dispatch ──────────────────────────────────────────────────

  @Test
  public void testNonActionEndpointReturnsNull() {
    assertNull(new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build()));
  }

  @Test
  public void testActionWithUnknownFieldReturnsNull() {
    assertNull(new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("unknownAction").recordId("abc").build()));
  }

  @Test
  public void testCreateDraftInvoiceRejectsNonPostMethod() {
    assertNull(new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE).recordId("abc").build()));
  }

  @Test
  public void testCheckActionWithUnsupportedMethodReturnsNull() {
    assertNull(new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("DELETE").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CHECK).recordId("abc").build()));
  }

  @Test
  public void testListActionWithPostReturnsNull() {
    assertNull(new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_LIST).recordId("abc").build()));
  }

  @Test
  public void testMissingRecordIdReturns400() throws JSONException {
    NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE).build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message").contains("Record ID"));
  }

  @Test
  public void testBlankRecordIdReturns400() {
    NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE).recordId("   ").build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  @Test
  public void testListGetWithBlankRecordIdReturns400() {
    NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_LIST).build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  @Test
  public void testListGetWithWhitespaceRecordIdReturns400() {
    NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_LIST).recordId("   ").build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  @Test
  public void testCheckGetRoutesToHandleCheck() {
    assertNotNull(new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CHECK).recordId("abc").build()));
  }

  @Test
  public void testCheckPostRoutesToHandleCheck() {
    assertNotNull(new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CHECK).recordId("abc").build()));
  }

  @Test
  public void testAfterHandleReturnsNull() {
    assertNull(new CreateDraftInvoiceHandler().afterHandle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE).recordId("abc").build()));
  }

  // ── parseLineOverrides ────────────────────────────────────────────────────

  @Test
  public void testParseLineOverridesNullBodyReturnsEmpty() {
    assertTrue(new CreateDraftInvoiceHandler().parseLineOverrides(null).isEmpty());
  }

  @Test
  public void testParseLineOverridesNoLinesKeyReturnsEmpty() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("other", "value");
    assertTrue(new CreateDraftInvoiceHandler().parseLineOverrides(body).isEmpty());
  }

  @Test
  public void testParseLineOverridesValidOrderLineId() throws JSONException {
    JSONObject line = new JSONObject();
    line.put("orderLineId", "line-1");
    line.put("quantity", "3.5");
    JSONObject body = new JSONObject();
    body.put("lines", new JSONArray().put(line));

    Map<String, BigDecimal> result = new CreateDraftInvoiceHandler().parseLineOverrides(body);
    assertEquals(1, result.size());
    assertEquals(new BigDecimal("3.5"), result.get("line-1"));
  }

  @Test
  public void testParseLineOverridesZeroQtySkipped() throws JSONException {
    JSONObject line = new JSONObject();
    line.put("orderLineId", "line-2");
    line.put("quantity", "0");
    JSONObject body = new JSONObject();
    body.put("lines", new JSONArray().put(line));
    assertTrue(new CreateDraftInvoiceHandler().parseLineOverrides(body).isEmpty());
  }

  @Test
  public void testParseLineOverridesNegativeQtySkipped() throws JSONException {
    JSONObject line = new JSONObject();
    line.put("orderLineId", "line-3");
    line.put("quantity", "-1");
    JSONObject body = new JSONObject();
    body.put("lines", new JSONArray().put(line));
    assertTrue(new CreateDraftInvoiceHandler().parseLineOverrides(body).isEmpty());
  }

  @Test
  public void testParseLineOverridesShipmentLineIdFallback() throws JSONException {
    JSONObject line = new JSONObject();
    line.put("shipmentLineId", "sl-99");
    line.put("quantity", "2");
    JSONObject body = new JSONObject();
    body.put("lines", new JSONArray().put(line));

    Map<String, BigDecimal> result = new CreateDraftInvoiceHandler().parseLineOverrides(body);
    assertEquals(1, result.size());
    assertEquals(new BigDecimal("2"), result.get("sl-99"));
  }

  // ── parseShipmentIds ──────────────────────────────────────────────────────

  @Test
  public void testParseShipmentIdsNullBodyReturnsFallback() {
    List<String> result = new CreateDraftInvoiceHandler().parseShipmentIds(null, "rec-1");
    assertEquals(1, result.size());
    assertEquals("rec-1", result.get(0));
  }

  @Test
  public void testParseShipmentIdsNoKeyReturnsFallback() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("other", "value");
    List<String> result = new CreateDraftInvoiceHandler().parseShipmentIds(body, "rec-2");
    assertEquals(1, result.size());
    assertEquals("rec-2", result.get(0));
  }

  @Test
  public void testParseShipmentIdsWithArray() throws JSONException {
    JSONArray arr = new JSONArray().put("s-1").put("s-2").put("s-3");
    JSONObject body = new JSONObject();
    body.put("shipmentIds", arr);
    List<String> result = new CreateDraftInvoiceHandler().parseShipmentIds(body, "fallback");
    assertEquals(Arrays.asList("s-1", "s-2", "s-3"), result);
  }

  @Test
  public void testParseShipmentIdsEmptyArrayReturnsFallback() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("shipmentIds", new JSONArray());
    List<String> result = new CreateDraftInvoiceHandler().parseShipmentIds(body, "fb");
    assertEquals(1, result.size());
    assertEquals("fb", result.get(0));
  }

  // ── calculateLineGross ────────────────────────────────────────────────────

  @Test
  public void testCalculateLineGrossWithPositiveGrossPrice() {
    InvoiceLine il = mock(InvoiceLine.class);
    when(il.getInvoicedQuantity()).thenReturn(new BigDecimal("2"));
    when(il.getGrossUnitPrice()).thenReturn(new BigDecimal("10"));
    assertEquals(new BigDecimal("20.00"),
        new CreateDraftInvoiceHandler().calculateLineGross(il, PRECISION));
  }

  @Test
  public void testCalculateLineGrossWithZeroGrossPriceUsesNetPlusTax() {
    InvoiceLine il = mock(InvoiceLine.class);
    when(il.getGrossUnitPrice()).thenReturn(BigDecimal.ZERO);
    when(il.getLineNetAmount()).thenReturn(new BigDecimal("100"));
    TaxRate tax = mock(TaxRate.class);
    when(tax.getRate()).thenReturn(new BigDecimal("21"));
    when(il.getTax()).thenReturn(tax);
    // 100 + 100*21/100 = 121.00
    assertEquals(new BigDecimal("121.00"),
        new CreateDraftInvoiceHandler().calculateLineGross(il, PRECISION));
  }

  @Test
  public void testCalculateLineGrossNullGrossPriceAndNoTax() {
    InvoiceLine il = mock(InvoiceLine.class);
    when(il.getGrossUnitPrice()).thenReturn(null);
    when(il.getLineNetAmount()).thenReturn(new BigDecimal("50"));
    when(il.getTax()).thenReturn(null);
    assertEquals(new BigDecimal("50.00"),
        new CreateDraftInvoiceHandler().calculateLineGross(il, PRECISION));
  }

  @Test
  public void testCalculateLineGrossNullTaxRateReturnsNet() {
    InvoiceLine il = mock(InvoiceLine.class);
    when(il.getGrossUnitPrice()).thenReturn(null);
    when(il.getLineNetAmount()).thenReturn(new BigDecimal("80"));
    TaxRate tax = mock(TaxRate.class);
    when(tax.getRate()).thenReturn(null);
    when(il.getTax()).thenReturn(tax);
    assertEquals(new BigDecimal("80.00"),
        new CreateDraftInvoiceHandler().calculateLineGross(il, PRECISION));
  }

  @Test
  public void testCalculateLineGrossAllNullsReturnZero() {
    InvoiceLine il = mock(InvoiceLine.class);
    when(il.getInvoicedQuantity()).thenReturn(null);
    when(il.getGrossUnitPrice()).thenReturn(null);
    when(il.getLineNetAmount()).thenReturn(null);
    when(il.getTax()).thenReturn(null);
    assertEquals(new BigDecimal("0.00"),
        new CreateDraftInvoiceHandler().calculateLineGross(il, PRECISION));
  }

  // ── resolvePendingForLine ─────────────────────────────────────────────────

  @Test
  public void testResolvePendingLineInactiveReturnsNull() {
    OrderLine ol = mock(OrderLine.class);
    when(ol.isActive()).thenReturn(false);
    assertNull(new CreateDraftInvoiceHandler().resolvePendingForLine(ol, false, new HashMap<>()));
  }

  @Test
  public void testResolvePendingLineNoProductReturnsNull() {
    OrderLine ol = mock(OrderLine.class);
    when(ol.isActive()).thenReturn(true);
    when(ol.getProduct()).thenReturn(null);
    assertNull(new CreateDraftInvoiceHandler().resolvePendingForLine(ol, false, new HashMap<>()));
  }

  @Test
  public void testResolvePendingLineNotInOverridesReturnsNull() {
    OrderLine ol = mock(OrderLine.class);
    when(ol.isActive()).thenReturn(true);
    when(ol.getProduct()).thenReturn(mock(Product.class));
    when(ol.getId()).thenReturn("line-A");
    Map<String, BigDecimal> overrides = new HashMap<>();
    overrides.put("line-B", BigDecimal.ONE);
    assertNull(new CreateDraftInvoiceHandler().resolvePendingForLine(ol, true, overrides));
  }

  @Test
  public void testResolvePendingLineFullyInvoicedReturnsNull() {
    OrderLine ol = mock(OrderLine.class);
    when(ol.isActive()).thenReturn(true);
    when(ol.getProduct()).thenReturn(mock(Product.class));
    when(ol.getOrderedQuantity()).thenReturn(new BigDecimal("5"));
    when(ol.getInvoicedQuantity()).thenReturn(new BigDecimal("5"));
    assertNull(new CreateDraftInvoiceHandler().resolvePendingForLine(ol, false, new HashMap<>()));
  }

  @Test
  public void testResolvePendingLineNoOverridesReturnsPending() {
    OrderLine ol = mock(OrderLine.class);
    when(ol.isActive()).thenReturn(true);
    when(ol.getProduct()).thenReturn(mock(Product.class));
    when(ol.getOrderedQuantity()).thenReturn(new BigDecimal("10"));
    when(ol.getInvoicedQuantity()).thenReturn(new BigDecimal("3"));
    assertEquals(new BigDecimal("7"),
        new CreateDraftInvoiceHandler().resolvePendingForLine(ol, false, new HashMap<>()));
  }

  @Test
  public void testResolvePendingLineWithOverrideCapsResult() {
    OrderLine ol = mock(OrderLine.class);
    when(ol.isActive()).thenReturn(true);
    when(ol.getProduct()).thenReturn(mock(Product.class));
    when(ol.getId()).thenReturn("line-X");
    when(ol.getOrderedQuantity()).thenReturn(new BigDecimal("10"));
    when(ol.getInvoicedQuantity()).thenReturn(new BigDecimal("3"));
    Map<String, BigDecimal> overrides = new HashMap<>();
    overrides.put("line-X", new BigDecimal("4"));
    // pending=7, override=4 → min is 4
    assertEquals(new BigDecimal("4"),
        new CreateDraftInvoiceHandler().resolvePendingForLine(ol, true, overrides));
  }

  // ── resolveShipmentLineQty ────────────────────────────────────────────────

  @Test
  public void testResolveShipmentQtyInactiveReturnsNull() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.isActive()).thenReturn(false);
    assertNull(new CreateDraftInvoiceHandler()
        .resolveShipmentLineQty(sl, false, new HashMap<>()));
  }

  @Test
  public void testResolveShipmentQtyNotInOverridesReturnsNull() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.isActive()).thenReturn(true);
    when(sl.getId()).thenReturn("sl-A");
    Map<String, BigDecimal> overrides = new HashMap<>();
    overrides.put("sl-B", BigDecimal.ONE);
    assertNull(new CreateDraftInvoiceHandler()
        .resolveShipmentLineQty(sl, true, overrides));
  }

  @Test
  public void testResolveShipmentQtyNullMovementReturnsNull() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.isActive()).thenReturn(true);
    when(sl.getMovementQuantity()).thenReturn(null);
    assertNull(new CreateDraftInvoiceHandler()
        .resolveShipmentLineQty(sl, false, new HashMap<>()));
  }

  @Test
  public void testResolveShipmentQtyZeroMovementReturnsNull() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.isActive()).thenReturn(true);
    when(sl.getMovementQuantity()).thenReturn(BigDecimal.ZERO);
    assertNull(new CreateDraftInvoiceHandler()
        .resolveShipmentLineQty(sl, false, new HashMap<>()));
  }

  @Test
  public void testResolveShipmentQtyNoOverridesReturnsMovement() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.isActive()).thenReturn(true);
    when(sl.getMovementQuantity()).thenReturn(new BigDecimal("5"));
    assertEquals(new BigDecimal("5"),
        new CreateDraftInvoiceHandler().resolveShipmentLineQty(sl, false, new HashMap<>()));
  }

  @Test
  public void testResolveShipmentQtyOverrideCapsMovement() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.isActive()).thenReturn(true);
    when(sl.getId()).thenReturn("sl-1");
    when(sl.getMovementQuantity()).thenReturn(new BigDecimal("8"));
    Map<String, BigDecimal> overrides = new HashMap<>();
    overrides.put("sl-1", new BigDecimal("3"));
    assertEquals(new BigDecimal("3"),
        new CreateDraftInvoiceHandler().resolveShipmentLineQty(sl, true, overrides));
  }

  // ── pickByOrg ─────────────────────────────────────────────────────────────

  private DocumentType mockDocType(String orgId) {
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    DocumentType dt = mock(DocumentType.class);
    when(dt.getOrganization()).thenReturn(org);
    return dt;
  }

  @Test
  public void testPickByOrgEmptyListReturnsNull() {
    assertNull(new CreateDraftInvoiceHandler().pickByOrg(Collections.emptyList(), "org-1"));
  }

  @Test
  public void testPickByOrgExactMatchReturned() {
    DocumentType dtOther = mockDocType("org-2");
    DocumentType dtTarget = mockDocType("org-1");
    DocumentType result = new CreateDraftInvoiceHandler()
        .pickByOrg(Arrays.asList(dtOther, dtTarget), "org-1");
    assertEquals(dtTarget, result);
  }

  @Test
  public void testPickByOrgSystemOrgFallback() {
    DocumentType dtSystem = mockDocType("0");
    DocumentType dtOther = mockDocType("org-99");
    DocumentType result = new CreateDraftInvoiceHandler()
        .pickByOrg(Arrays.asList(dtOther, dtSystem), "org-1");
    assertEquals(dtSystem, result);
  }

  @Test
  public void testPickByOrgFirstFallbackWhenNoMatch() {
    DocumentType dtA = mockDocType("org-A");
    DocumentType dtB = mockDocType("org-B");
    DocumentType result = new CreateDraftInvoiceHandler()
        .pickByOrg(Arrays.asList(dtA, dtB), "org-X");
    assertEquals(dtA, result);
  }

  // ── summarizeDocTypes ─────────────────────────────────────────────────────

  @Test
  public void testSummarizeDocTypesEmptyList() {
    assertEquals("[]",
        new CreateDraftInvoiceHandler().summarizeDocTypes(Collections.emptyList()));
  }

  @Test
  public void testSummarizeDocTypesSingleEntryNullSequence() {
    DocumentType dt = mockDocType("org-1");
    when(dt.getName()).thenReturn("AR Invoice");
    when(dt.isDefault()).thenReturn(true);
    when(dt.getDocumentSequence()).thenReturn(null);
    String result = new CreateDraftInvoiceHandler().summarizeDocTypes(Collections.singletonList(dt));
    assertTrue(result.contains("AR Invoice"));
    assertTrue(result.contains("null"));
  }

  @Test
  public void testSummarizeDocTypesMultipleEntriesHasComma() {
    DocumentType dt1 = mockDocType("org-1");
    when(dt1.getName()).thenReturn("Type1");
    when(dt1.isDefault()).thenReturn(false);
    when(dt1.getDocumentSequence()).thenReturn(null);
    DocumentType dt2 = mockDocType("org-2");
    when(dt2.getName()).thenReturn("Type2");
    when(dt2.isDefault()).thenReturn(false);
    when(dt2.getDocumentSequence()).thenReturn(null);
    String result = new CreateDraftInvoiceHandler()
        .summarizeDocTypes(Arrays.asList(dt1, dt2));
    assertTrue(result.contains("Type1"));
    assertTrue(result.contains("Type2"));
    assertTrue(result.contains(", "));
  }

  // ── buildSelectedLinesForOrder ────────────────────────────────────────────

  @Test
  public void testBuildSelectedLinesEmptyOrderReturnsEmptyArray() {
    Order order = mock(Order.class);
    when(order.getOrderLineList()).thenReturn(Collections.emptyList());
    JSONArray result = new CreateDraftInvoiceHandler()
        .buildSelectedLinesForOrder(order, new HashMap<>());
    assertEquals(0, result.length());
  }

  @Test
  public void testBuildSelectedLinesSkipsFullyInvoicedLines() {
    OrderLine ol = mock(OrderLine.class);
    when(ol.isActive()).thenReturn(true);
    when(ol.getProduct()).thenReturn(mock(Product.class));
    when(ol.getOrderedQuantity()).thenReturn(new BigDecimal("5"));
    when(ol.getInvoicedQuantity()).thenReturn(new BigDecimal("5"));
    Order order = mock(Order.class);
    when(order.getOrderLineList()).thenReturn(Collections.singletonList(ol));
    JSONArray result = new CreateDraftInvoiceHandler()
        .buildSelectedLinesForOrder(order, new HashMap<>());
    assertEquals(0, result.length());
  }

  @Test
  public void testBuildSelectedLinesPendingLineIncluded() throws JSONException {
    OrderLine ol = mock(OrderLine.class);
    when(ol.isActive()).thenReturn(true);
    when(ol.getProduct()).thenReturn(mock(Product.class));
    when(ol.getId()).thenReturn("line-1");
    when(ol.getOrderedQuantity()).thenReturn(new BigDecimal("10"));
    when(ol.getInvoicedQuantity()).thenReturn(new BigDecimal("3"));
    Order order = mock(Order.class);
    when(order.getOrderLineList()).thenReturn(Collections.singletonList(ol));
    JSONArray result = new CreateDraftInvoiceHandler()
        .buildSelectedLinesForOrder(order, new HashMap<>());
    assertEquals(1, result.length());
    assertEquals("line-1", result.getJSONObject(0).getString("id"));
    assertEquals("7", result.getJSONObject(0).getString("orderedQuantity"));
  }

  // ── markQuotationAsInvoiceCreated ─────────────────────────────────────────

  @Test
  public void testMarkQuotationNotFoundIsNoop() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Order.class), anyString())).thenReturn(null);
      // must not throw
      new CreateDraftInvoiceHandler().markQuotationAsInvoiceCreated("missing-id");
      verify(dal, never()).save(any());
    }
  }

  @Test
  public void testMarkQuotationSetsStatusToEtgoCI() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order quotation = mock(Order.class);
      when(dal.get(eq(Order.class), eq("q-1"))).thenReturn(quotation);
      new CreateDraftInvoiceHandler().markQuotationAsInvoiceCreated("q-1");
      verify(quotation).setDocumentStatus("ETGO_CI");
      verify(dal).save(quotation);
    }
  }

  // ── ensureDocumentNo ──────────────────────────────────────────────────────

  @Test
  public void testEnsureDocNoAlreadySetSkips() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentNo()).thenReturn("EXISTING-001");
      TestableHandler h = new TestableHandler();
      h.ensureDocumentNo(invoice);
      verify(invoice, never()).setDocumentNo(anyString());
      verify(dal, never()).save(any());
    }
  }

  @Test
  public void testEnsureDocNoBlankAssignsGenerated() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentNo()).thenReturn("");
      when(invoice.getDocumentType()).thenReturn(null);
      when(invoice.getClient()).thenReturn(null);
      TestableHandler h = new TestableHandler();
      h.generatedDocNo = "GEN-001";
      h.ensureDocumentNo(invoice);
      verify(invoice).setDocumentNo("GEN-001");
      verify(dal).save(invoice);
    }
  }

  @Test
  public void testEnsureDocNoGeneratedBlankSkips() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentNo()).thenReturn(null);
      TestableHandler h = new TestableHandler();
      h.generatedDocNo = "   ";
      h.ensureDocumentNo(invoice);
      verify(invoice, never()).setDocumentNo(anyString());
      verify(dal, never()).save(any());
    }
  }

  // ── loadAndValidateShipments ──────────────────────────────────────────────

  @Test(expected = OBException.class)
  public void testLoadShipmentEmptyListThrows() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      new CreateDraftInvoiceHandler().loadAndValidateShipments(Collections.emptyList());
    }
  }

  @Test(expected = OBException.class)
  public void testLoadShipmentIdNotFoundThrows() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), anyString())).thenReturn(null);
      new CreateDraftInvoiceHandler()
          .loadAndValidateShipments(Collections.singletonList("bad-id"));
    }
  }

  @Test
  public void testLoadSingleShipmentReturnsIt() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut s = mock(ShipmentInOut.class);
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("bp-1");
      org.getClass(); // just to touch mock
      // BusinessPartner mock
      org.openbravo.model.common.businesspartner.BusinessPartner bp =
          mock(org.openbravo.model.common.businesspartner.BusinessPartner.class);
      when(bp.getId()).thenReturn("bp-1");
      when(s.getBusinessPartner()).thenReturn(bp);
      when(dal.get(eq(ShipmentInOut.class), eq("s-1"))).thenReturn(s);
      List<ShipmentInOut> result = new CreateDraftInvoiceHandler()
          .loadAndValidateShipments(Collections.singletonList("s-1"));
      assertEquals(1, result.size());
      assertEquals(s, result.get(0));
    }
  }

  @Test(expected = OBException.class)
  public void testLoadShipmentsDifferentBPsThrows() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      org.openbravo.model.common.businesspartner.BusinessPartner bp1 =
          mock(org.openbravo.model.common.businesspartner.BusinessPartner.class);
      when(bp1.getId()).thenReturn("bp-1");
      org.openbravo.model.common.businesspartner.BusinessPartner bp2 =
          mock(org.openbravo.model.common.businesspartner.BusinessPartner.class);
      when(bp2.getId()).thenReturn("bp-2");
      ShipmentInOut s1 = mock(ShipmentInOut.class);
      when(s1.getBusinessPartner()).thenReturn(bp1);
      ShipmentInOut s2 = mock(ShipmentInOut.class);
      when(s2.getBusinessPartner()).thenReturn(bp2);
      when(dal.get(eq(ShipmentInOut.class), eq("s-1"))).thenReturn(s1);
      when(dal.get(eq(ShipmentInOut.class), eq("s-2"))).thenReturn(s2);
      new CreateDraftInvoiceHandler()
          .loadAndValidateShipments(Arrays.asList("s-1", "s-2"));
    }
  }

  // ── findExistingDrafts ────────────────────────────────────────────────────

  @Test
  public void testFindDraftsEmptyIdsReturnsEmpty() {
    List<Invoice> result = new CreateDraftInvoiceHandler()
        .findExistingDrafts(Collections.emptyList(), SPEC_SALES_ORDER);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testFindDraftsUnknownSpecReturnsEmpty() {
    List<Invoice> result = new CreateDraftInvoiceHandler()
        .findExistingDrafts(Collections.singletonList("id-1"), "purchase-order");
    assertTrue(result.isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFindDraftsSalesOrderSpecQueriesDB() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      Query<Invoice> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(Invoice.class))).thenReturn(q);
      when(q.setParameterList(anyString(), any(java.util.Collection.class))).thenReturn(q);
      when(q.setMaxResults(anyInt())).thenReturn(q);
      when(q.list()).thenReturn(Collections.emptyList());

      List<Invoice> result = new CreateDraftInvoiceHandler()
          .findExistingDrafts(Collections.singletonList("order-1"), SPEC_SALES_ORDER);
      assertTrue(result.isEmpty());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFindDraftsGoodsShipmentSpecQueriesDB() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      Query<Invoice> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(Invoice.class))).thenReturn(q);
      when(q.setParameterList(anyString(), any(java.util.Collection.class))).thenReturn(q);
      when(q.setMaxResults(anyInt())).thenReturn(q);
      when(q.list()).thenReturn(Collections.emptyList());

      List<Invoice> result = new CreateDraftInvoiceHandler()
          .findExistingDrafts(Collections.singletonList("ship-1"), SPEC_GOODS_SHIPMENT);
      assertTrue(result.isEmpty());
    }
  }

  // ── resolveARInvoiceDocType ───────────────────────────────────────────────

  @Test
  public void testResolveDocTypeLinkedWithSequenceReturnedDirectly() {
    DocumentType linkedDocType = mock(DocumentType.class);
    when(linkedDocType.getDocumentSequence()).thenReturn(mock(org.openbravo.model.ad.utility.Sequence.class));
    DocumentType orderDocType = mock(DocumentType.class);
    when(orderDocType.getDocumentTypeForInvoice()).thenReturn(linkedDocType);
    Order order = mock(Order.class);
    when(order.getTransactionDocument()).thenReturn(orderDocType);

    TestableHandler h = new TestableHandler();
    assertEquals(linkedDocType, h.resolveARInvoiceDocType(order));
  }

  @Test
  public void testResolveDocTypeLinkedNoSequenceUsesDiscovered() {
    DocumentType discovered = mock(DocumentType.class);
    DocumentType linkedDocType = mock(DocumentType.class);
    when(linkedDocType.getDocumentSequence()).thenReturn(null);
    DocumentType orderDocType = mock(DocumentType.class);
    when(orderDocType.getDocumentTypeForInvoice()).thenReturn(linkedDocType);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");
    Order order = mock(Order.class);
    when(order.getTransactionDocument()).thenReturn(orderDocType);
    when(order.getOrganization()).thenReturn(org);

    TestableHandler h = new TestableHandler();
    h.arDocTypeToReturn = discovered;
    assertEquals(discovered, h.resolveARInvoiceDocType(order));
  }

  @Test
  public void testResolveDocTypeNoTransactionDocUsesDiscovered() {
    DocumentType discovered = mock(DocumentType.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");
    Order order = mock(Order.class);
    when(order.getTransactionDocument()).thenReturn(null);
    when(order.getOrganization()).thenReturn(org);

    TestableHandler h = new TestableHandler();
    h.arDocTypeToReturn = discovered;
    assertEquals(discovered, h.resolveARInvoiceDocType(order));
  }

  @Test(expected = OBException.class)
  public void testResolveDocTypeNothingFoundThrows() {
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");
    Order order = mock(Order.class);
    when(order.getTransactionDocument()).thenReturn(null);
    when(order.getOrganization()).thenReturn(org);

    TestableHandler h = new TestableHandler();
    h.arDocTypeToReturn = null;
    h.resolveARInvoiceDocType(order);
  }

  // ── ensureLineGrossAmounts ────────────────────────────────────────────────

  @Test
  public void testEnsureGrossAlreadyPositiveSkipsLine() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      InvoiceLine il = mock(InvoiceLine.class);
      when(il.getGrossAmount()).thenReturn(new BigDecimal("50.00"));
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(il));

      new CreateDraftInvoiceHandler().ensureLineGrossAmounts(invoice);
      verify(il, never()).setGrossAmount(any());
    }
  }

  @Test
  public void testEnsureGrossNullComputesAndSaves() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      InvoiceLine il = mock(InvoiceLine.class);
      when(il.getGrossAmount()).thenReturn(null);
      when(il.getGrossUnitPrice()).thenReturn(new BigDecimal("10"));
      when(il.getInvoicedQuantity()).thenReturn(new BigDecimal("3"));
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(il));

      new CreateDraftInvoiceHandler().ensureLineGrossAmounts(invoice);
      verify(il).setGrossAmount(new BigDecimal("30.00"));
      verify(dal).save(il);
    }
  }

  @Test
  public void testEnsureGrossZeroComputesAndSaves() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      InvoiceLine il = mock(InvoiceLine.class);
      when(il.getGrossAmount()).thenReturn(BigDecimal.ZERO);
      when(il.getGrossUnitPrice()).thenReturn(null);
      when(il.getLineNetAmount()).thenReturn(new BigDecimal("100"));
      when(il.getTax()).thenReturn(null);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(il));

      new CreateDraftInvoiceHandler().ensureLineGrossAmounts(invoice);
      verify(il).setGrossAmount(new BigDecimal("100.00"));
    }
  }

  // ── recalculateTotals ─────────────────────────────────────────────────────

  @Test
  public void testRecalculateTotalsNoLinesSetsTotalsToZero() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.emptyList());

      new CreateDraftInvoiceHandler().recalculateTotals(invoice);
      verify(invoice).setSummedLineAmount(new BigDecimal("0.00"));
      verify(invoice).setGrandTotalAmount(new BigDecimal("0.00"));
      verify(dal).save(invoice);
    }
  }

  @Test
  public void testRecalculateTotalsLineWithoutTaxAccumulatesNet() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      InvoiceLine il = mock(InvoiceLine.class);
      when(il.getInvoicedQuantity()).thenReturn(new BigDecimal("2"));
      when(il.getUnitPrice()).thenReturn(new BigDecimal("50"));
      when(il.getLineNetAmount()).thenReturn(new BigDecimal("100"));
      when(il.getTax()).thenReturn(null);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(il));

      new CreateDraftInvoiceHandler().recalculateTotals(invoice);
      verify(invoice).setSummedLineAmount(new BigDecimal("100.00"));
      verify(invoice).setGrandTotalAmount(new BigDecimal("100.00"));
    }
  }

  // ── createShipmentInvoiceLine ─────────────────────────────────────────────

  @Test
  public void testCreateShipmentInvoiceLineNoOrderLineZeroPrices() {
    try (MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      InvoiceLine il = mock(InvoiceLine.class);
      when(provider.get(InvoiceLine.class)).thenReturn(il);

      ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
      when(sl.getOrganization()).thenReturn(mock(Organization.class));
      when(sl.getProduct()).thenReturn(mock(Product.class));
      when(sl.getUOM()).thenReturn(null);
      when(sl.getSalesOrderLine()).thenReturn(null);

      Invoice invoice = mock(Invoice.class);
      InvoiceLine result = new CreateDraftInvoiceHandler()
          .createShipmentInvoiceLine(invoice, sl, new BigDecimal("3"), 10L);

      assertEquals(il, result);
      verify(il).setUnitPrice(BigDecimal.ZERO);
      verify(il).setListPrice(BigDecimal.ZERO);
      verify(il).setLineNetAmount(BigDecimal.ZERO);
    }
  }

  @Test
  public void testCreateShipmentInvoiceLineWithOrderLineCopiesPrices() {
    try (MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      InvoiceLine il = mock(InvoiceLine.class);
      when(provider.get(InvoiceLine.class)).thenReturn(il);

      OrderLine ol = mock(OrderLine.class);
      when(ol.getUnitPrice()).thenReturn(new BigDecimal("10"));
      when(ol.getListPrice()).thenReturn(new BigDecimal("12"));
      when(ol.getPriceLimit()).thenReturn(new BigDecimal("8"));
      when(ol.getTax()).thenReturn(mock(TaxRate.class));

      ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
      when(sl.getOrganization()).thenReturn(mock(Organization.class));
      when(sl.getProduct()).thenReturn(mock(Product.class));
      when(sl.getUOM()).thenReturn(null);
      when(sl.getSalesOrderLine()).thenReturn(ol);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);

      new CreateDraftInvoiceHandler()
          .createShipmentInvoiceLine(invoice, sl, new BigDecimal("2"), 10L);

      verify(il).setUnitPrice(new BigDecimal("10"));
      verify(il).setListPrice(new BigDecimal("12"));
      // lineNetAmount = 2 * 10 = 20.00
      verify(il).setLineNetAmount(new BigDecimal("20.00"));
    }
  }

  // ── handleCreate (mockStatic) ─────────────────────────────────────────────

  @Test
  public void testHandleCreateUnknownSpecReturnsNull() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
          .specName("purchase-order").entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE).recordId("some-id").build());
      assertNull(r);
    }
  }

  @Test
  public void testHandleCreateQuotationOrderNotFoundReturns400() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Order.class), anyString())).thenReturn(null);
      NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE).recordId("not-found").build());
      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
    }
  }

  @Test
  public void testHandleCreateOrderNotFoundReturns400() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Order.class), anyString())).thenReturn(null);
      NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE).recordId("bad-id").build());
      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
    }
  }

  // ── handleCheck (mockStatic) ──────────────────────────────────────────────

  @Test
  public void testHandleCheckBlankRecordIdReturns400() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CHECK).build());
      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testHandleCheckNoDraftsReturns200WithExistsFalse() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      Query<Invoice> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(Invoice.class))).thenReturn(q);
      when(q.setParameterList(anyString(), any(java.util.Collection.class))).thenReturn(q);
      when(q.setMaxResults(anyInt())).thenReturn(q);
      when(q.list()).thenReturn(Collections.emptyList());

      NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CHECK).recordId("order-42").build());
      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertFalse(data.getBoolean("exists"));
      assertEquals(0, data.getInt("count"));
    }
  }

  // ── handleList (mockStatic) ───────────────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void testHandleListEmptyInvoicesReturns200() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      Query<Invoice> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(Invoice.class))).thenReturn(q);
      when(q.setParameter(anyString(), any())).thenReturn(q);
      when(q.setMaxResults(anyInt())).thenReturn(q);
      when(q.list()).thenReturn(Collections.emptyList());

      NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER).entityName(ENTITY_HEADER)
          .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_LIST).recordId("order-99").build());
      assertNotNull(r);
      JSONArray data = r.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(0, data.length());
    }
  }

  /**
   * Test double for dispatch-oriented scenarios where individual handler branches
   * need to be observed without executing the full persistence workflow.
   */
  private static class DispatchHandler extends CreateDraftInvoiceHandler {
    Invoice orderInvoice;
    Invoice shipmentInvoice;
    RuntimeException createFailure;
    Map<String, BigDecimal> receivedLineOverrides;
    List<String> parsedShipmentIds = Collections.emptyList();
    List<String> receivedShipmentIds;
    String markedQuotationId;
    boolean ensuredDocumentNo;
    boolean ensuredGrossAmounts;
    boolean recalculatedTotals;
    List<Invoice> draftsToReturn = Collections.emptyList();
    RuntimeException checkFailure;
    List<String> checkedIds;
    String checkedSpecName;

    @Override
    protected Invoice createFromOrder(String orderId, Map<String, BigDecimal> lineOverrides) {
      if (createFailure != null) {
        throw createFailure;
      }
      receivedLineOverrides = lineOverrides;
      return orderInvoice;
    }

    @Override
    protected Invoice createFromShipments(List<String> shipmentIds, Map<String, BigDecimal> lineOverrides) {
      if (createFailure != null) {
        throw createFailure;
      }
      receivedShipmentIds = shipmentIds;
      receivedLineOverrides = lineOverrides;
      return shipmentInvoice;
    }

    @Override
    protected List<String> parseShipmentIds(JSONObject body, String recordId) {
      return parsedShipmentIds;
    }

    @Override
    protected void markQuotationAsInvoiceCreated(String quotationId) {
      markedQuotationId = quotationId;
    }

    @Override
    protected void ensureDocumentNo(Invoice invoice) {
      ensuredDocumentNo = true;
    }

    @Override
    protected void ensureLineGrossAmounts(Invoice invoice) {
      ensuredGrossAmounts = true;
    }

    @Override
    protected void recalculateTotals(Invoice invoice) {
      recalculatedTotals = true;
    }

    @Override
    protected List<Invoice> findExistingDrafts(List<String> recordIds, String specName) {
      if (checkFailure != null) {
        throw checkFailure;
      }
      checkedIds = recordIds;
      checkedSpecName = specName;
      return draftsToReturn;
    }
  }

  /**
   * Test double for isolating {@code createFromOrder()} from downstream helpers.
   */
  private static class CreateFromOrderHandler extends CreateDraftInvoiceHandler {
    JSONArray selectedLines = new JSONArray();
    DocumentType resolvedDocType;
    Map<String, BigDecimal> receivedOverrides;
    Invoice ensuredGrossInvoice;

    @Override
    protected JSONArray buildSelectedLinesForOrder(Order order, Map<String, BigDecimal> lineOverrides) {
      receivedOverrides = lineOverrides;
      return selectedLines;
    }

    @Override
    protected DocumentType resolveARInvoiceDocType(Order order) {
      return resolvedDocType;
    }

    @Override
    protected void ensureLineGrossAmounts(Invoice invoice) {
      ensuredGrossInvoice = invoice;
    }
  }

  /**
   * Test double for shipment-based creation scenarios where collaborators are stubbed.
   */
  private static class CreateFromShipmentsHandler extends CreateDraftInvoiceHandler {
    List<ShipmentInOut> shipmentsToReturn = Collections.emptyList();
    Invoice invoiceHeader;
    Invoice receivedInvoice;
    List<ShipmentInOut> receivedShipments;
    Map<String, BigDecimal> receivedOverrides;

    @Override
    protected List<ShipmentInOut> loadAndValidateShipments(List<String> shipmentIds) {
      return shipmentsToReturn;
    }

    @Override
    protected Invoice createInvoiceHeaderFromShipment(ShipmentInOut first, List<ShipmentInOut> shipments) {
      receivedShipments = shipments;
      return invoiceHeader;
    }

    @Override
    protected void addShipmentLinesToInvoice(Invoice invoice, List<ShipmentInOut> shipments,
        Map<String, BigDecimal> lineOverrides) {
      receivedInvoice = invoice;
      receivedShipments = shipments;
      receivedOverrides = lineOverrides;
    }
  }

  /**
   * Test double used to control AR invoice document type lookup for shipment headers.
   */
  private static class InvoiceHeaderHandler extends CreateDraftInvoiceHandler {
    DocumentType docTypeToReturn;
    String requestedOrgId;

    @Override
    protected DocumentType findARInvoiceDocType(String orgId) {
      requestedOrgId = orgId;
      return docTypeToReturn;
    }
  }

  /**
   * Test double that captures generated line numbers while bypassing invoice line creation.
   */
  private static class AddShipmentLinesHandler extends CreateDraftInvoiceHandler {
    final Map<String, BigDecimal> qtyByShipmentLineId = new HashMap<>();
    final Map<String, Long> lineNoByShipmentLineId = new HashMap<>();

    @Override
    protected BigDecimal resolveShipmentLineQty(ShipmentInOutLine sl, boolean hasOverrides,
        Map<String, BigDecimal> lineOverrides) {
      return qtyByShipmentLineId.get(sl.getId());
    }

    @Override
    protected InvoiceLine createShipmentInvoiceLine(Invoice invoice, ShipmentInOutLine sl,
        BigDecimal qty, long lineNo) {
      lineNoByShipmentLineId.put(sl.getId(), lineNo);
      return mock(InvoiceLine.class);
    }
  }

  /**
   * Stubs admin mode lifecycle calls used by handler entry points.
   */
  private void mockAdminMode(MockedStatic<OBContext> obContextMock) {
    obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(invocation -> null);
    obContextMock.when(OBContext::restorePreviousMode).thenAnswer(invocation -> null);
  }

  /**
   * Creates a minimal document type candidate for organization-based selection tests.
   */
  private DocumentType mockCandidate(String id, String orgId, boolean isDefault, String sequenceName) {
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);

    DocumentType docType = mock(DocumentType.class);
    when(docType.getId()).thenReturn(id);
    when(docType.getName()).thenReturn(id + "-name");
    when(docType.getOrganization()).thenReturn(org);
    when(docType.isDefault()).thenReturn(isDefault);
    if (sequenceName != null) {
      Sequence sequence = mock(Sequence.class);
      when(sequence.getName()).thenReturn(sequenceName);
      when(docType.getDocumentSequence()).thenReturn(sequence);
    } else {
      when(docType.getDocumentSequence()).thenReturn(null);
    }
    return docType;
  }

  /**
   * Mocks a reusable {@link OBCriteria} chain returning the provided document types.
   */
  @SuppressWarnings("unchecked")
  private OBCriteria<DocumentType> mockDocumentTypeCriteria(OBDal dal, List<DocumentType> result) {
    OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(result);
    return criteria;
  }

  /**
   * Creates an order mock with the mandatory header data required by invoice creation tests.
   */
  private Order mockOrderWithHeaderData() {
    Order order = mock(Order.class);
    when(order.getClient()).thenReturn(mock(Client.class));
    when(order.getOrganization()).thenReturn(mock(Organization.class));
    when(order.getBusinessPartner()).thenReturn(mock(BusinessPartner.class));
    when(order.getPartnerAddress()).thenReturn(null);
    when(order.getPriceList()).thenReturn(mock(PriceList.class));
    when(order.getCurrency()).thenReturn(mock(Currency.class));
    when(order.getPaymentTerms()).thenReturn(mock(PaymentTerm.class));
    when(order.getPaymentMethod()).thenReturn(mock(FIN_PaymentMethod.class));
    return order;
  }

  /**
   * Verifies that sales-order creation returns HTTP 201 and exposes the created invoice payload.
   */
  @Test
  public void testHandleCreateSalesOrderReturnsCreatedResponse() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-1");
      when(invoice.getDocumentNo()).thenReturn("INV-001");
      when(invoice.getDocumentStatus()).thenReturn("DR");

      DispatchHandler handler = new DispatchHandler();
      handler.orderInvoice = invoice;

      JSONObject body = new JSONObject()
          .put("lines", new JSONArray().put(new JSONObject()
              .put("orderLineId", "ol-1")
              .put("quantity", "2")));

      NeoResponse response = handler.handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER)
          .entityName(ENTITY_HEADER)
          .httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE)
          .recordId("order-1")
          .requestBody(body)
          .build());

      assertNotNull(response);
      assertEquals(201, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals("inv-1", data.getString("id"));
      assertEquals("INV-001", data.getString("documentNo"));
      assertEquals(new BigDecimal("2"), handler.receivedLineOverrides.get("ol-1"));
      assertTrue(handler.ensuredDocumentNo);
      verify(session).refresh(invoice);
      verify(dal, Mockito.atLeastOnce()).flush();
    }
  }

  /**
   * Verifies that quotation-based creation marks the source quotation as invoiced.
   */
  @Test
  public void testHandleCreateSalesQuotationMarksSourceQuotation() {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-q-1");
      when(invoice.getDocumentNo()).thenReturn("INV-Q-001");
      when(invoice.getDocumentStatus()).thenReturn("DR");

      DispatchHandler handler = new DispatchHandler();
      handler.orderInvoice = invoice;

      NeoResponse response = handler.handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION)
          .entityName(ENTITY_HEADER)
          .httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE)
          .recordId("quotation-1")
          .build());

      assertNotNull(response);
      assertEquals(201, response.getHttpStatus());
      assertEquals("quotation-1", handler.markedQuotationId);
      assertTrue(handler.ensuredDocumentNo);
    }
  }

  /**
   * Verifies that shipment-based creation triggers gross repair and total recalculation.
   */
  @Test
  public void testHandleCreateGoodsShipmentRecalculatesShipmentInvoice() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-s-1");
      when(invoice.getDocumentNo()).thenReturn("INV-S-001");
      when(invoice.getDocumentStatus()).thenReturn("DR");

      DispatchHandler handler = new DispatchHandler();
      handler.shipmentInvoice = invoice;
      handler.parsedShipmentIds = Arrays.asList("ship-1", "ship-2");

      JSONObject body = new JSONObject()
          .put("shipmentIds", new JSONArray().put("ship-1").put("ship-2"))
          .put("lines", new JSONArray().put(new JSONObject()
              .put("shipmentLineId", "sl-1")
              .put("quantity", "3")));

      NeoResponse response = handler.handle(NeoContext.builder()
          .specName(SPEC_GOODS_SHIPMENT)
          .entityName(ENTITY_HEADER)
          .httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE)
          .recordId("ship-fallback")
          .requestBody(body)
          .build());

      assertNotNull(response);
      assertEquals(201, response.getHttpStatus());
      assertEquals(Arrays.asList("ship-1", "ship-2"), handler.receivedShipmentIds);
      assertEquals(new BigDecimal("3"), handler.receivedLineOverrides.get("sl-1"));
      assertTrue(handler.ensuredGrossAmounts);
      assertTrue(handler.recalculatedTotals);
      verify(dal, Mockito.times(2)).flush();
    }
  }

  /**
   * Verifies that unexpected creation failures are translated into HTTP 500 responses.
   */
  @Test
  public void testHandleCreateUnexpectedErrorReturns500() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      mockAdminMode(obContextMock);

      DispatchHandler handler = new DispatchHandler();
      handler.createFailure = new RuntimeException("boom");

      NeoResponse response = handler.handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER)
          .entityName(ENTITY_HEADER)
          .httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE)
          .recordId("order-2")
          .build());

      assertNotNull(response);
      assertEquals(500, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject("error").getString("message")
          .contains("internal error"));
    }
  }

  /**
   * Verifies that shipment draft checks include aggregate draft metadata when multiple drafts exist.
   */
  @Test
  public void testHandleCheckGoodsShipmentIncludesAllDrafts() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      mockAdminMode(obContextMock);

      Invoice draft1 = mock(Invoice.class);
      when(draft1.getId()).thenReturn("inv-1");
      when(draft1.getDocumentNo()).thenReturn("INV-001");
      Invoice draft2 = mock(Invoice.class);
      when(draft2.getId()).thenReturn("inv-2");
      when(draft2.getDocumentNo()).thenReturn("INV-002");

      DispatchHandler handler = new DispatchHandler();
      handler.parsedShipmentIds = Arrays.asList("ship-1", "ship-2");
      handler.draftsToReturn = Arrays.asList(draft1, draft2);

      NeoResponse response = handler.handle(NeoContext.builder()
          .specName(SPEC_GOODS_SHIPMENT)
          .entityName(ENTITY_HEADER)
          .httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CHECK)
          .recordId("fallback-id")
          .requestBody(new JSONObject().put("shipmentIds", new JSONArray().put("ship-1")))
          .build());

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertTrue(data.getBoolean("exists"));
      assertEquals(2, data.getInt("count"));
      assertEquals("inv-1", data.getString("id"));
      JSONArray drafts = data.getJSONArray("drafts");
      assertEquals(2, drafts.length());
      assertEquals(Arrays.asList("ship-1", "ship-2"), handler.checkedIds);
      assertEquals(SPEC_GOODS_SHIPMENT, handler.checkedSpecName);
    }
  }

  /**
   * Verifies that unexpected failures during draft checks are reported as HTTP 500.
   */
  @Test
  public void testHandleCheckUnexpectedErrorReturns500() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      mockAdminMode(obContextMock);

      DispatchHandler handler = new DispatchHandler();
      handler.checkFailure = new RuntimeException("check failed");

      NeoResponse response = handler.handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER)
          .entityName(ENTITY_HEADER)
          .httpMethod("GET")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CHECK)
          .recordId("order-3")
          .build());

      assertNotNull(response);
      assertEquals(500, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject("error").getString("message")
          .contains("check failed"));
    }
  }

  /**
   * Verifies that invoice listing merges both query sources, deduplicates rows, and formats output fields.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleListMergesDeduplicatesAndFormatsRows() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      Query<Invoice> lineQuery = mock(Query.class);
      Query<Invoice> directQuery = mock(Query.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      when(session.createQuery(anyString(), eq(Invoice.class))).thenReturn(lineQuery, directQuery);
      when(lineQuery.setParameter(anyString(), any())).thenReturn(lineQuery);
      when(lineQuery.setMaxResults(anyInt())).thenReturn(lineQuery);
      when(directQuery.setParameter(anyString(), any())).thenReturn(directQuery);
      when(directQuery.setMaxResults(anyInt())).thenReturn(directQuery);

      Invoice invoiceFromLines = mock(Invoice.class);
      when(invoiceFromLines.getId()).thenReturn("inv-1");
      when(invoiceFromLines.getDocumentNo()).thenReturn("INV-001");
      when(invoiceFromLines.getDocumentStatus()).thenReturn("CO");
      when(invoiceFromLines.getGrandTotalAmount()).thenReturn(new BigDecimal("42.50"));
      when(invoiceFromLines.getInvoiceDate()).thenReturn(new GregorianCalendar(2026, 3, 29).getTime());

      Invoice directOnlyInvoice = mock(Invoice.class);
      when(directOnlyInvoice.getId()).thenReturn("inv-2");
      when(directOnlyInvoice.getDocumentNo()).thenReturn("INV-002");
      when(directOnlyInvoice.getDocumentStatus()).thenReturn("DR");
      when(directOnlyInvoice.getGrandTotalAmount()).thenReturn(null);
      when(directOnlyInvoice.getInvoiceDate()).thenReturn(null);

      when(lineQuery.list()).thenReturn(Collections.singletonList(invoiceFromLines));
      when(directQuery.list()).thenReturn(Arrays.asList(invoiceFromLines, directOnlyInvoice));

      NeoResponse response = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER)
          .entityName(ENTITY_HEADER)
          .httpMethod("GET")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_LIST)
          .recordId("order-4")
          .build());

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(2, data.length());
      assertEquals("inv-1", data.getJSONObject(0).getString("id"));
      assertEquals("2026-04-29", data.getJSONObject(0).getString("invoiceDate"));
      assertEquals("inv-2", data.getJSONObject(1).getString("id"));
      assertEquals(0, data.getJSONObject(1).getInt("grandTotalAmount"));
      assertFalse(data.getJSONObject(1).has("invoiceDate"));
    }
  }

  /**
   * Verifies that unexpected list failures are mapped to HTTP 500 responses.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleListUnexpectedErrorReturns500() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      when(session.createQuery(anyString(), eq(Invoice.class))).thenThrow(new RuntimeException("list failed"));

      NeoResponse response = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER)
          .entityName(ENTITY_HEADER)
          .httpMethod("GET")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_LIST)
          .recordId("order-5")
          .build());

      assertNotNull(response);
      assertEquals(500, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject("error").getString("message")
          .contains("list failed"));
    }
  }

  /**
   * Verifies that malformed line override payloads are ignored instead of failing the request.
   */
  @Test
  public void testParseLineOverridesInvalidPayloadReturnsEmpty() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("lines", "invalid-array");

    assertTrue(new CreateDraftInvoiceHandler().parseLineOverrides(body).isEmpty());
  }

  /**
   * Verifies that malformed shipment ID payloads fall back to the record ID.
   */
  @Test
  public void testParseShipmentIdsInvalidPayloadFallsBackToRecordId() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("shipmentIds", "invalid-array");

    List<String> ids = new CreateDraftInvoiceHandler().parseShipmentIds(body, "fallback-shipment");
    assertEquals(Collections.singletonList("fallback-shipment"), ids);
  }

  /**
   * Verifies that document type resolution falls back to the linked order document type when discovery fails.
   */
  @Test
  public void testResolveDocTypeFallsBackToLinkedDocTypeWhenDiscoveryFails() {
    DocumentType linkedDocType = mock(DocumentType.class);
    when(linkedDocType.getDocumentSequence()).thenReturn(null);

    DocumentType orderDocType = mock(DocumentType.class);
    when(orderDocType.getDocumentTypeForInvoice()).thenReturn(linkedDocType);

    Organization organization = mock(Organization.class);
    when(organization.getId()).thenReturn("org-1");

    Order order = mock(Order.class);
    when(order.getTransactionDocument()).thenReturn(orderDocType);
    when(order.getOrganization()).thenReturn(organization);

    TestableHandler handler = new TestableHandler();
    handler.arDocTypeToReturn = null;

    assertSame(linkedDocType, handler.resolveARInvoiceDocType(order));
  }

  /**
   * Verifies that document number generation forwards both transaction and target document type IDs.
   */
  @Test
  public void testGenerateInvoiceDocumentNoUsesTransactionAndDocumentTypeIds() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelProviderMock = Mockito.mockStatic(ModelProvider.class);
        MockedStatic<RequestContext> requestContextMock = Mockito.mockStatic(RequestContext.class);
        MockedStatic<Utility> utilityMock = Mockito.mockStatic(Utility.class)) {
      OBDal dal = mock(OBDal.class);
      Connection connection = mock(Connection.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection(false)).thenReturn(connection);

      ModelProvider modelProvider = mock(ModelProvider.class);
      Entity entity = mock(Entity.class);
      modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
      when(modelProvider.getEntity(Invoice.class)).thenReturn(entity);
      when(entity.getTableName()).thenReturn("C_Invoice");

      RequestContext requestContext = mock(RequestContext.class);
      VariablesSecureApp vars = mock(VariablesSecureApp.class);
      requestContextMock.when(RequestContext::get).thenReturn(requestContext);
      when(requestContext.getVariablesSecureApp()).thenReturn(vars);

      DocumentType transactionDocument = mock(DocumentType.class);
      when(transactionDocument.getId()).thenReturn("trx-1");
      DocumentType documentType = mock(DocumentType.class);
      when(documentType.getId()).thenReturn("doc-1");
      Invoice invoice = mock(Invoice.class);
      when(invoice.getTransactionDocument()).thenReturn(transactionDocument);
      when(invoice.getDocumentType()).thenReturn(documentType);

      utilityMock.when(() -> Utility.getDocumentNo(eq(connection), any(), eq(vars), eq(""), eq("C_Invoice"),
          eq("trx-1"), eq("doc-1"), eq(false), eq(true))).thenReturn("INV-100");

      assertEquals("INV-100", new CreateDraftInvoiceHandler().generateInvoiceDocumentNo(invoice));
    }
  }

  /**
   * Verifies that missing transaction and document types are forwarded as empty IDs to Etendo's utility.
   */
  @Test
  public void testGenerateInvoiceDocumentNoUsesEmptyIdsWhenDocsAreMissing() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelProviderMock = Mockito.mockStatic(ModelProvider.class);
        MockedStatic<RequestContext> requestContextMock = Mockito.mockStatic(RequestContext.class);
        MockedStatic<Utility> utilityMock = Mockito.mockStatic(Utility.class)) {
      OBDal dal = mock(OBDal.class);
      Connection connection = mock(Connection.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection(false)).thenReturn(connection);

      ModelProvider modelProvider = mock(ModelProvider.class);
      Entity entity = mock(Entity.class);
      modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
      when(modelProvider.getEntity(Invoice.class)).thenReturn(entity);
      when(entity.getTableName()).thenReturn("C_Invoice");

      RequestContext requestContext = mock(RequestContext.class);
      VariablesSecureApp vars = mock(VariablesSecureApp.class);
      requestContextMock.when(RequestContext::get).thenReturn(requestContext);
      when(requestContext.getVariablesSecureApp()).thenReturn(vars);

      Invoice invoice = mock(Invoice.class);
      when(invoice.getTransactionDocument()).thenReturn(null);
      when(invoice.getDocumentType()).thenReturn(null);

      utilityMock.when(() -> Utility.getDocumentNo(eq(connection), any(), eq(vars), eq(""), eq("C_Invoice"),
          eq(""), eq(""), eq(false), eq(true))).thenReturn("INV-101");

      assertEquals("INV-101", new CreateDraftInvoiceHandler().generateInvoiceDocumentNo(invoice));
    }
  }

  /**
   * Verifies that order-based invoice creation persists the header and delegates line generation to the native process.
   */
  @Test
  public void testCreateFromOrderPersistsHeaderAndDelegatesLines() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class);
        MockedStatic<Utility> utilityMock = Mockito.mockStatic(Utility.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("order-10"))).thenReturn(order);
      when(dal.getSession()).thenReturn(session);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);
      utilityMock.when(() -> Utility.getDocumentNo(any(DalConnectionProvider.class),
          Mockito.nullable(String.class), eq("C_Invoice"), eq(true))).thenReturn("INV-200");

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      CreateFromOrderHandler handler = new CreateFromOrderHandler();
      handler.resolvedDocType = mock(DocumentType.class);
      handler.selectedLines = new JSONArray().put(new JSONObject()
          .put("id", "ol-1")
          .put("orderedQuantity", "2"));

      Map<String, BigDecimal> overrides = Collections.singletonMap("ol-1", new BigDecimal("2"));
      Invoice result = handler.createFromOrder("order-10", overrides);

      assertSame(invoice, result);
      assertEquals(overrides, handler.receivedOverrides);
      assertSame(invoice, handler.ensuredGrossInvoice);
      verify(invoice).setDocumentType(handler.resolvedDocType);
      verify(invoice).setTransactionDocument(handler.resolvedDocType);
      verify(invoice).setDocumentNo("INV-200");
      verify(invoice).setSalesTransaction(true);
      verify(dal).save(invoice);
      verify(process).createInvoiceLinesFromDocumentLines(eq(handler.selectedLines), eq(invoice), eq(OrderLine.class));
      verify(session).refresh(invoice);
      verify(dal, Mockito.atLeastOnce()).flush();
    }
  }

  /**
   * Verifies that order-based invoice creation rejects requests without invoiceable lines.
   */
  @Test(expected = OBException.class)
  public void testCreateFromOrderWithoutInvoiceableLinesThrows() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Order.class), eq("order-11"))).thenReturn(mock(Order.class));

      CreateFromOrderHandler handler = new CreateFromOrderHandler();
      handler.selectedLines = new JSONArray();

      handler.createFromOrder("order-11", Collections.emptyMap());
    }
  }

  /**
   * Verifies that shipment-based invoice creation saves the header and delegates line appending.
   */
  @Test
  public void testCreateFromShipmentsSavesHeaderAndAddsLines() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      ShipmentInOut first = mock(ShipmentInOut.class);
      ShipmentInOut second = mock(ShipmentInOut.class);
      Invoice invoice = mock(Invoice.class);

      CreateFromShipmentsHandler handler = new CreateFromShipmentsHandler();
      handler.shipmentsToReturn = Arrays.asList(first, second);
      handler.invoiceHeader = invoice;
      Map<String, BigDecimal> overrides = Collections.singletonMap("sl-1", new BigDecimal("4"));

      Invoice result = handler.createFromShipments(Arrays.asList("ship-10", "ship-20"), overrides);

      assertSame(invoice, result);
      assertEquals(Arrays.asList(first, second), handler.receivedShipments);
      assertEquals(overrides, handler.receivedOverrides);
      assertSame(invoice, handler.receivedInvoice);
      verify(dal).save(invoice);
      verify(dal).flush();
    }
  }

  /**
   * Verifies that shipment invoice headers reuse linked order commercial data when an order is available.
   */
  @Test
  public void testCreateInvoiceHeaderFromShipmentUsesLinkedOrderData() {
    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      Client client = mock(Client.class);
      Organization organization = mock(Organization.class);
      BusinessPartner businessPartner = mock(BusinessPartner.class);
      PriceList priceList = mock(PriceList.class);
      Currency currency = mock(Currency.class);
      PaymentTerm paymentTerm = mock(PaymentTerm.class);
      FIN_PaymentMethod paymentMethod = mock(FIN_PaymentMethod.class);
      DocumentType invoiceDocType = mock(DocumentType.class);
      DocumentType orderDocType = mock(DocumentType.class);
      when(orderDocType.getDocumentTypeForInvoice()).thenReturn(invoiceDocType);

      Order linkedOrder = mock(Order.class);
      when(linkedOrder.getTransactionDocument()).thenReturn(orderDocType);
      when(linkedOrder.getPriceList()).thenReturn(priceList);
      when(linkedOrder.getCurrency()).thenReturn(currency);
      when(linkedOrder.getPaymentTerms()).thenReturn(paymentTerm);
      when(linkedOrder.getPaymentMethod()).thenReturn(paymentMethod);

      ShipmentInOut shipment = mock(ShipmentInOut.class);
      when(shipment.getClient()).thenReturn(client);
      when(shipment.getOrganization()).thenReturn(organization);
      when(shipment.getBusinessPartner()).thenReturn(businessPartner);
      when(shipment.getPartnerAddress()).thenReturn(null);
      when(shipment.getSalesOrder()).thenReturn(linkedOrder);

      InvoiceHeaderHandler handler = new InvoiceHeaderHandler();
      Invoice result = handler.createInvoiceHeaderFromShipment(shipment, Collections.singletonList(shipment));

      assertSame(invoice, result);
      verify(invoice).setDocumentType(invoiceDocType);
      verify(invoice).setTransactionDocument(invoiceDocType);
      verify(invoice).setPriceList(priceList);
      verify(invoice).setCurrency(currency);
      verify(invoice).setPaymentTerms(paymentTerm);
      verify(invoice).setPaymentMethod(paymentMethod);
      verify(invoice).setSalesOrder(linkedOrder);
      verify(invoice).setBusinessPartner(businessPartner);
    }
  }

  /**
   * Verifies that shipment invoice headers fall back to business partner defaults when no linked order exists.
   */
  @Test
  public void testCreateInvoiceHeaderFromShipmentUsesBusinessPartnerDefaults() {
    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      Organization organization = mock(Organization.class);
      when(organization.getId()).thenReturn("org-20");
      PriceList priceList = mock(PriceList.class);
      Currency currency = mock(Currency.class);
      when(priceList.getCurrency()).thenReturn(currency);
      PaymentTerm paymentTerm = mock(PaymentTerm.class);
      FIN_PaymentMethod paymentMethod = mock(FIN_PaymentMethod.class);
      BusinessPartner businessPartner = mock(BusinessPartner.class);
      when(businessPartner.getPriceList()).thenReturn(priceList);
      when(businessPartner.getPaymentTerms()).thenReturn(paymentTerm);
      when(businessPartner.getPaymentMethod()).thenReturn(paymentMethod);

      ShipmentInOut shipment = mock(ShipmentInOut.class);
      when(shipment.getClient()).thenReturn(mock(Client.class));
      when(shipment.getOrganization()).thenReturn(organization);
      when(shipment.getBusinessPartner()).thenReturn(businessPartner);
      when(shipment.getPartnerAddress()).thenReturn(null);
      when(shipment.getSalesOrder()).thenReturn(null);

      InvoiceHeaderHandler handler = new InvoiceHeaderHandler();
      handler.docTypeToReturn = mock(DocumentType.class);

      Invoice result = handler.createInvoiceHeaderFromShipment(shipment, Collections.singletonList(shipment));

      assertSame(invoice, result);
      assertEquals("org-20", handler.requestedOrgId);
      verify(invoice).setPriceList(priceList);
      verify(invoice).setCurrency(currency);
      verify(invoice).setPaymentTerms(paymentTerm);
      verify(invoice).setPaymentMethod(paymentMethod);
      verify(invoice, never()).setSalesOrder(any(Order.class));
    }
  }

  /**
   * Verifies that shipment invoice header creation fails when no AR invoice document type can be resolved.
   */
  @Test(expected = OBException.class)
  public void testCreateInvoiceHeaderFromShipmentWithoutDocTypeThrows() {
    Organization organization = mock(Organization.class);
    when(organization.getId()).thenReturn("org-21");

    ShipmentInOut shipment = mock(ShipmentInOut.class);
    when(shipment.getOrganization()).thenReturn(organization);
    when(shipment.getBusinessPartner()).thenReturn(mock(BusinessPartner.class));
    when(shipment.getSalesOrder()).thenReturn(null);

    InvoiceHeaderHandler handler = new InvoiceHeaderHandler();
    handler.docTypeToReturn = null;

    handler.createInvoiceHeaderFromShipment(shipment, Collections.singletonList(shipment));
  }

  /**
   * Verifies that shipment invoice header creation fails when business partner defaults are incomplete.
   */
  @Test(expected = OBException.class)
  public void testCreateInvoiceHeaderFromShipmentMissingBusinessPartnerDefaultsThrows() {
    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(mock(Invoice.class));

      Organization organization = mock(Organization.class);
      when(organization.getId()).thenReturn("org-22");
      BusinessPartner businessPartner = mock(BusinessPartner.class);
      when(businessPartner.getPriceList()).thenReturn(null);
      when(businessPartner.getPaymentTerms()).thenReturn(null);
      when(businessPartner.getPaymentMethod()).thenReturn(mock(FIN_PaymentMethod.class));

      ShipmentInOut shipment = mock(ShipmentInOut.class);
      when(shipment.getClient()).thenReturn(mock(Client.class));
      when(shipment.getOrganization()).thenReturn(organization);
      when(shipment.getBusinessPartner()).thenReturn(businessPartner);
      when(shipment.getPartnerAddress()).thenReturn(null);
      when(shipment.getSalesOrder()).thenReturn(null);

      InvoiceHeaderHandler handler = new InvoiceHeaderHandler();
      handler.docTypeToReturn = mock(DocumentType.class);

      handler.createInvoiceHeaderFromShipment(shipment, Collections.singletonList(shipment));
    }
  }

  /**
   * Verifies that shipment line appending skips null quantities and preserves sequential line numbering.
   */
  @Test
  public void testAddShipmentLinesToInvoiceSkipsNullQtyAndIncrementsLineNo() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      ShipmentInOutLine line1 = mock(ShipmentInOutLine.class);
      when(line1.getId()).thenReturn("sl-1");
      ShipmentInOutLine line2 = mock(ShipmentInOutLine.class);
      when(line2.getId()).thenReturn("sl-2");
      ShipmentInOutLine line3 = mock(ShipmentInOutLine.class);
      when(line3.getId()).thenReturn("sl-3");

      ShipmentInOut shipment1 = mock(ShipmentInOut.class);
      when(shipment1.getMaterialMgmtShipmentInOutLineList()).thenReturn(Arrays.asList(line1, line2));
      ShipmentInOut shipment2 = mock(ShipmentInOut.class);
      when(shipment2.getMaterialMgmtShipmentInOutLineList()).thenReturn(Collections.singletonList(line3));

      AddShipmentLinesHandler handler = new AddShipmentLinesHandler();
      handler.qtyByShipmentLineId.put("sl-1", new BigDecimal("2"));
      handler.qtyByShipmentLineId.put("sl-2", null);
      handler.qtyByShipmentLineId.put("sl-3", new BigDecimal("5"));

      handler.addShipmentLinesToInvoice(mock(Invoice.class), Arrays.asList(shipment1, shipment2),
          Collections.emptyMap());

      assertEquals(Long.valueOf(10), handler.lineNoByShipmentLineId.get("sl-1"));
      assertEquals(Long.valueOf(20), handler.lineNoByShipmentLineId.get("sl-3"));
      assertFalse(handler.lineNoByShipmentLineId.containsKey("sl-2"));
      verify(dal, Mockito.times(2)).save(any(InvoiceLine.class));
    }
  }

  /**
   * Verifies that total recalculation backfills missing net amounts and creates invoice tax rows.
   */
  @Test
  public void testRecalculateTotalsCreatesInvoiceTaxAndBackfillsLineNetAmount() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      OBProvider provider = mock(OBProvider.class);
      InvoiceTax invoiceTax = mock(InvoiceTax.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(InvoiceTax.class)).thenReturn(invoiceTax);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);

      TaxRate taxRate = mock(TaxRate.class);
      when(taxRate.getId()).thenReturn("tax-1");
      when(taxRate.getRate()).thenReturn(new BigDecimal("21"));
      when(taxRate.isSummaryLevel()).thenReturn(false);

      TaxRate summaryTax = mock(TaxRate.class);
      when(summaryTax.isSummaryLevel()).thenReturn(true);

      InvoiceLine lineWithoutNet = mock(InvoiceLine.class);
      when(lineWithoutNet.getInvoicedQuantity()).thenReturn(new BigDecimal("2"));
      when(lineWithoutNet.getUnitPrice()).thenReturn(new BigDecimal("10"));
      when(lineWithoutNet.getLineNetAmount()).thenReturn(null);
      when(lineWithoutNet.getTax()).thenReturn(taxRate);

      InvoiceLine summaryLine = mock(InvoiceLine.class);
      when(summaryLine.getInvoicedQuantity()).thenReturn(BigDecimal.ONE);
      when(summaryLine.getUnitPrice()).thenReturn(new BigDecimal("50"));
      when(summaryLine.getLineNetAmount()).thenReturn(new BigDecimal("50"));
      when(summaryLine.getTax()).thenReturn(summaryTax);

      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getInvoiceLineList()).thenReturn(Arrays.asList(lineWithoutNet, summaryLine));
      when(invoice.getClient()).thenReturn(mock(Client.class));
      when(invoice.getOrganization()).thenReturn(mock(Organization.class));

      new CreateDraftInvoiceHandler().recalculateTotals(invoice);

      verify(lineWithoutNet).setLineNetAmount(new BigDecimal("20.00"));
      verify(invoiceTax).setTaxableAmount(new BigDecimal("20.00"));
      verify(invoiceTax).setTaxAmount(new BigDecimal("4.20"));
      verify(invoice).setSummedLineAmount(new BigDecimal("70.00"));
      verify(invoice).setGrandTotalAmount(new BigDecimal("74.20"));
      verify(dal).save(invoiceTax);
      verify(dal).save(invoice);
    }
  }

  /**
   * Verifies that AR invoice document type lookup prefers an exact organization match with sequence.
   */
  @Test
  public void testFindArInvoiceDocTypePrefersExactOrgWithSequence() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType other = mockCandidate("dt-other", "org-2", false, "SEQ-2");
      DocumentType target = mockCandidate("dt-target", "org-1", true, "SEQ-1");
      mockDocumentTypeCriteria(dal, Arrays.asList(other, target));

      DocumentType result = new CreateDraftInvoiceHandler().findARInvoiceDocType("org-1");

      assertSame(target, result);
    }
  }

  /**
   * Verifies that AR invoice document type lookup falls back to any active candidate when none has a sequence.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testFindArInvoiceDocTypeFallsBackToAnyActiveCandidate() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      OBCriteria<DocumentType> withSequenceCriteria = mock(OBCriteria.class);
      OBCriteria<DocumentType> fallbackCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(withSequenceCriteria, fallbackCriteria);

      when(withSequenceCriteria.add(any())).thenReturn(withSequenceCriteria);
      when(withSequenceCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(withSequenceCriteria);
      when(withSequenceCriteria.list()).thenReturn(Collections.emptyList());

      DocumentType systemCandidate = mockCandidate("dt-system", "0", false, null);
      when(fallbackCriteria.add(any())).thenReturn(fallbackCriteria);
      when(fallbackCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(fallbackCriteria);
      when(fallbackCriteria.list()).thenReturn(Collections.singletonList(systemCandidate));

      DocumentType result = new CreateDraftInvoiceHandler().findARInvoiceDocType("org-9");

      assertSame(systemCandidate, result);
    }
  }
}
