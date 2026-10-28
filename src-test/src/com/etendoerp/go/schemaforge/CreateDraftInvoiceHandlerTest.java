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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static com.etendoerp.go.schemaforge.InvoiceCompletionTestSupport.completionError;
import static com.etendoerp.go.schemaforge.InvoiceCompletionTestSupport.completionSuccess;
import static com.etendoerp.go.schemaforge.InvoiceCompletionTestSupport.mockCompletedInvoice;

import com.etendoerp.go.schemaforge.InvoiceCompletionTestSupport.CompletionMocks;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.enterprise.inject.Vetoed;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;
import org.mockito.ArgumentCaptor;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.openbravo.advpaymentmngt.ProcessInvoiceUtil;
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
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.model.pricing.pricelist.PriceList;

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
 *
 * @covers com.etendoerp.go.schemaforge.CreateDraftInvoiceHandler
 * @covers com.etendoerp.go.schemaforge.OrderInvoiceListSupport
 * @covers com.etendoerp.go.schemaforge.OrderInvoiceDuplicateGuard
 * @covers com.etendoerp.go.schemaforge.DuplicateCreationRefusal
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
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
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

  @Test
  public void testResolvePendingLineDiscountProductReturnsNull() {
    OrderLine ol = mock(OrderLine.class);
    Product product = mock(Product.class);
    when(ol.isActive()).thenReturn(true);
    when(ol.getProduct()).thenReturn(product);
    when(product.getId()).thenReturn(TotalDiscountService.DISCOUNT_PRODUCT_ID);

    assertNull(new CreateDraftInvoiceHandler().resolvePendingForLine(ol, false, new HashMap<>()));
  }

  private static OrderLine pendingOrderLine(String id, String ordered, String invoiced) {
    OrderLine ol = mock(OrderLine.class);
    when(ol.isActive()).thenReturn(true);
    when(ol.getProduct()).thenReturn(mock(Product.class));
    when(ol.getId()).thenReturn(id);
    when(ol.getLineNo()).thenReturn(10L);
    when(ol.getOrderedQuantity()).thenReturn(new BigDecimal(ordered));
    when(ol.getInvoicedQuantity()).thenReturn(new BigDecimal(invoiced));
    return ol;
  }

  /**
   * ETP-5709 (item 4): ordered 2 / invoiced 4 is pending -2, an OVER-invoiced line. It used to be
   * selected and would have produced a -2 invoice line.
   */
  @Test
  public void testResolvePendingLineOverInvoicedReturnsNull() {
    OrderLine ol = pendingOrderLine("ol-1", "2", "4");

    assertNull(new CreateDraftInvoiceHandler().resolvePendingForLine(ol, false, new HashMap<>()));
  }

  /** ETP-5709 (item 4): with an override, an over-invoiced line is still not invoiceable (was -2). */
  @Test
  public void testResolvePendingLineOverInvoicedWithOverrideReturnsNull() {
    OrderLine ol = pendingOrderLine("ol-1", "2", "4");

    assertNull(new CreateDraftInvoiceHandler().resolvePendingForLine(ol, true,
        Collections.singletonMap("ol-1", BigDecimal.ONE)));
  }

  /** ETP-4722/ETP-4567 keep working: ordered -3 / invoiced 0 is a legit -3 pending line. */
  @Test
  public void testResolvePendingLineLegitNegativeLineStillInvoiced() {
    OrderLine ol = pendingOrderLine("ol-1", "-3", "0");

    assertEquals(new BigDecimal("-3"),
        new CreateDraftInvoiceHandler().resolvePendingForLine(ol, false, new HashMap<>()));
  }

  /** A negative line over-invoiced in its own direction (ordered -3, invoiced -5) is not pending. */
  @Test
  public void testResolvePendingLineOverInvoicedNegativeLineReturnsNull() {
    OrderLine ol = pendingOrderLine("ol-1", "-3", "-5");

    assertNull(new CreateDraftInvoiceHandler().resolvePendingForLine(ol, false, new HashMap<>()));
  }

  /**
   * Pending is ORDERED-based (ordered - invoiced), not delivered-based: a partially shipped line
   * (ordered 5, delivered 3, nothing invoiced) is still pending 5 here. What gets invoiced for
   * its shipped part is decided later by core (see toOrderLineOverrides), so the sign rule must
   * not look at delivered quantities.
   */
  @Test
  public void testResolvePendingLineIsOrderedBasedNotDeliveredBased() {
    OrderLine ol = pendingOrderLine("ol-1", "5", "0");
    when(ol.getDeliveredQuantity()).thenReturn(new BigDecimal("3"));

    assertEquals(new BigDecimal("5"),
        new CreateDraftInvoiceHandler().resolvePendingForLine(ol, false, new HashMap<>()));
  }

  /** An override against the line's sign is never turned into an invoice quantity. */
  @Test
  public void testResolvePendingLineOverrideAgainstNegativeSignReturnsNull() {
    OrderLine ol = pendingOrderLine("ol-1", "-3", "0");

    assertNull(new CreateDraftInvoiceHandler().resolvePendingForLine(ol, true,
        Collections.singletonMap("ol-1", new BigDecimal("2"))));
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

  @Test
  public void testEnsureDocNoFallbackToGetDocumentNoConnectionWhenClientPresent() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<Utility> utilityMock = Mockito.mockStatic(Utility.class)) {
      OBDal dal = mock(OBDal.class);
      Connection connection = mock(Connection.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection(false)).thenReturn(connection);

      Client client = mock(Client.class);
      when(client.getId()).thenReturn("CLIENT-001");
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentNo()).thenReturn(null);
      when(invoice.getClient()).thenReturn(client);

      utilityMock.when(() -> Utility.getDocumentNoConnection(
          eq(connection), any(), eq("CLIENT-001"), eq("C_Invoice"), eq(true)))
          .thenReturn("FALLBACK-001");

      TestableHandler h = new TestableHandler();
      h.generatedDocNo = ""; // blank primary → triggers fallback
      h.ensureDocumentNo(invoice);

      verify(invoice).setDocumentNo("FALLBACK-001");
      verify(dal).save(invoice);
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
      when(s.getDocumentStatus()).thenReturn("CO");
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

  @Test
  public void testLoadClosedShipmentIsRefusedNamingNumberAndClBeforeAnything() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      org.openbravo.model.common.businesspartner.BusinessPartner bp =
          mock(org.openbravo.model.common.businesspartner.BusinessPartner.class);
      when(bp.getId()).thenReturn("bp-1");
      ShipmentInOut s = mock(ShipmentInOut.class);
      when(s.getBusinessPartner()).thenReturn(bp);
      when(s.getDocumentStatus()).thenReturn("CL");
      when(s.getDocumentNo()).thenReturn("SH-CL-2");
      when(dal.get(eq(ShipmentInOut.class), eq("s-cl"))).thenReturn(s);

      try {
        new CreateDraftInvoiceHandler().loadAndValidateShipments(Collections.singletonList("s-cl"));
        org.junit.Assert.fail("a closed shipment must be refused");
      } catch (OBException e) {
        assertTrue(e.getMessage(),
            e.getMessage().contains("SH-CL-2") && e.getMessage().contains("estado CL"));
      }
      Mockito.verify(dal, Mockito.never()).save(Mockito.any());
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
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class DispatchHandler extends CreateDraftInvoiceHandler {
    Invoice orderInvoice;
    Invoice shipmentInvoice;
    RuntimeException createFailure;
    Map<String, BigDecimal> receivedLineOverrides;
    List<String> parsedShipmentIds = Collections.emptyList();
    List<String> receivedShipmentIds;
    String markedQuotationId;
    String sourceOrderStatus = "CO";
    boolean ensuredDocumentNo;
    boolean ensuredGrossAmounts;
    Invoice alignedListPricesInvoice;
    List<Invoice> draftsToReturn = Collections.emptyList();
    RuntimeException checkFailure;
    List<String> checkedIds;
    String checkedSpecName;

    @Override
    protected Invoice createFromOrder(String orderId, Map<String, BigDecimal> lineOverrides,
        String priceListId) {
      if (createFailure != null) {
        throw createFailure;
      }
      receivedLineOverrides = lineOverrides;
      return orderInvoice;
    }

    @Override
    protected Invoice createFromShipments(List<String> shipmentIds, Map<String, BigDecimal> lineOverrides,
        String priceListId) {
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
    protected void requireOrderCompleted(String orderId) {
      com.etendoerp.go.schemaforge.util.DocumentStatusGuard.requireCompleted(orderId,
          sourceOrderStatus, "order %s is %s");
    }

    @Override
    protected void requireQuotationInvoiceable(String quotationId) {
      com.etendoerp.go.schemaforge.util.DocumentStatusGuard.requireAnyStatus(quotationId,
          sourceOrderStatus, "quotation %s is %s", "UE", "CO");
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
    InvoiceFromOrderSupport getSupport() {
      return new InvoiceFromOrderSupport() {
        @Override
        public void ensureLineGrossAmounts(Invoice invoice) {
          ensuredGrossAmounts = true;
        }

        @Override
        public void alignLineListPrices(Invoice invoice, java.util.Set<String> onlyLineIds) {
          alignedListPricesInvoice = onlyLineIds == null ? invoice : null;
        }
      };
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
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class CreateFromOrderHandler extends CreateDraftInvoiceHandler {
    JSONArray selectedLines = new JSONArray();
    DocumentType resolvedDocType;
    Map<String, BigDecimal> receivedOverrides;
    Invoice ensuredGrossInvoice;
    Invoice copiedDiscountsInvoice;
    /** ETP-5714: the copy-side support calls in the order they happened (copy, align, gross). */
    final List<String> supportCalls = new java.util.ArrayList<>();
    Invoice alignedInvoice;
    java.util.Set<String> alignedScope = Collections.singleton("unset");

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
    InvoiceFromOrderSupport getSupport() {
      return new InvoiceFromOrderSupport() {
        @Override
        public void ensureLineGrossAmounts(Invoice invoice) {
          ensuredGrossInvoice = invoice;
          supportCalls.add("gross");
        }

        @Override
        public void copyLineDiscountsFromOrder(Invoice invoice) {
          copiedDiscountsInvoice = invoice;
          supportCalls.add("copy");
        }

        @Override
        public void alignLineListPrices(Invoice invoice, java.util.Set<String> onlyLineIds) {
          supportCalls.add("align");
          alignedInvoice = invoice;
          alignedScope = onlyLineIds;
        }
      };
    }
  }

  /**
   * Test double for shipment-based creation scenarios where collaborators are stubbed.
   */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class CreateFromShipmentsHandler extends CreateDraftInvoiceHandler {
    List<ShipmentInOut> shipmentsToReturn = Collections.emptyList();
    Invoice invoiceHeader;
    Invoice receivedInvoice;
    List<ShipmentInOut> receivedShipments;
    Map<String, BigDecimal> receivedOverrides;
    /** Pending quantities the multi-shipment pre-validation sees (ETP-5709); non-empty by default. */
    Map<String, BigDecimal> pendingToReturn = Collections.singletonMap("sl-x", BigDecimal.ONE);

    @Override
    protected Map<String, BigDecimal> computePendingQtyPerLineOrThrow(String shipmentId,
        boolean includeDrafts) {
      return pendingToReturn;
    }

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
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
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
   * Test double that captures generated line numbers while bypassing invoice line creation
   * and DB access (the throwing pending-qty computation is stubbed to return an empty map).
   */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class AddShipmentLinesHandler extends CreateDraftInvoiceHandler {
    final Map<String, BigDecimal> qtyByShipmentLineId = new HashMap<>();
    JSONArray capturedLines;

    @Override
    protected Map<String, BigDecimal> computePendingQtyPerLineOrThrow(String shipmentId,
        boolean includeDrafts) {
      return Collections.emptyMap();
    }

    @Override
    protected BigDecimal resolveShipmentLineQty(ShipmentInOutLine sl, boolean hasOverrides,
        Map<String, BigDecimal> lineOverrides, Map<String, BigDecimal> pendingQtyMap) {
      return qtyByShipmentLineId.get(sl.getId());
    }

    @Override
    protected void createInvoiceLinesFromShipmentLines(JSONArray selectedLines, Invoice invoice) {
      capturedLines = selectedLines;
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
   *
   * <p>ETP-5381: the payload is now read from the invoice re-loaded AFTER confirmation, so the
   * completion has to be stubbed for this path to reach 201 at all.
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

      DispatchHandler handler = new DispatchHandler();
      handler.orderInvoice = invoice;

      JSONObject body = new JSONObject()
          .put("lines", new JSONArray().put(new JSONObject()
              .put("orderLineId", "ol-1")
              .put("quantity", "2")));

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        mockCompletedInvoice(dal, "inv-1", "INV-001", "CO");

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
  }

  /**
   * ETP-5714: an order that already has a DRAFT invoice is refused with 409 {@code draftInvoiceExists},
   * naming the draft numbers, and nothing is created; {@code allowDuplicate:true} creates the invoice
   * and lists the drafts the order had; anything but a JSON boolean is a 422. Same rule as the screen,
   * which hides the button while a draft exists.
   */
  @Test
  public void handle_orderWithADraftInvoice_isRefusedUnlessAllowDuplicateTrue() throws JSONException {
    Object[][] cases = {
        // name, allowDuplicate body value (null = absent), expected status
        { "absent", null, 409 },
        { "false", Boolean.FALSE, 409 },
        { "true", Boolean.TRUE, 201 },
        { "string true", "true", 422 },
        { "number one", 1, 422 },
    };
    for (Object[] c : cases) {
      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
        mockAdminMode(obContextMock);
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        when(dal.getSession()).thenReturn(mock(Session.class));
        Invoice invoice = mock(Invoice.class);
        when(invoice.getId()).thenReturn("inv-1");
        DispatchHandler handler = new DispatchHandler();
        handler.orderInvoice = invoice;
        JSONObject body = new JSONObject();
        if (c[1] != null) {
          body.put("allowDuplicate", c[1]);
        }

        try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
          completion.existingDrafts = List.of("DRAFT-1", "DRAFT-2");
          mockCompletedInvoice(dal, "inv-1", "INV-001", "CO");

          NeoResponse response = handler.handle(NeoContext.builder()
              .specName(SPEC_SALES_ORDER).entityName(ENTITY_HEADER).httpMethod("POST")
              .endpointType(NeoEndpointType.ACTION).fieldName(ACTION_CREATE)
              .recordId("order-1").requestBody(body).build());

          assertEquals((String) c[0], (int) (Integer) c[2], response.getHttpStatus());
          if (response.getHttpStatus() == 409) {
            JSONObject error = response.getBody().getJSONObject("error");
            assertEquals("draftInvoiceExists", error.getString("code"));
            assertTrue(error.getString("message").contains("DRAFT-1, DRAFT-2"));
            assertEquals(2, error.getJSONArray("existingDraftInvoices").length());
            assertNull("nothing is created", handler.receivedLineOverrides);
          } else if (response.getHttpStatus() == 201) {
            JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
            assertEquals("DRAFT-1", data.getJSONArray("existingDraftInvoices").getString(0));
          }
        }
      }
    }
  }

  /** ETP-5714: the status error comes before the draft refusal: an uncompleted order is a 400. */
  @Test
  public void handle_uncompletedOrderWithADraftInvoice_answersTheStatusErrorFirst()
      throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));
      DispatchHandler handler = new DispatchHandler();
      handler.sourceOrderStatus = "DR";

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        completion.existingDrafts = List.of("DRAFT-1");

        NeoResponse response = handler.handle(NeoContext.builder()
            .specName(SPEC_SALES_ORDER).entityName(ENTITY_HEADER).httpMethod("POST")
            .endpointType(NeoEndpointType.ACTION).fieldName(ACTION_CREATE)
            .recordId("order-1").requestBody(new JSONObject()).build());

        assertEquals(400, response.getHttpStatus());
        assertNull("nothing is created", handler.receivedLineOverrides);
      }
    }
  }

  /** ETP-5714: with no draft invoice the answer carries no {@code existingDraftInvoices} key. */
  @Test
  public void handle_orderWithoutADraftInvoice_answersAsBefore() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-1");
      DispatchHandler handler = new DispatchHandler();
      handler.orderInvoice = invoice;

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        mockCompletedInvoice(dal, "inv-1", "INV-001", "CO");

        NeoResponse response = handler.handle(NeoContext.builder()
            .specName(SPEC_SALES_ORDER).entityName(ENTITY_HEADER).httpMethod("POST")
            .endpointType(NeoEndpointType.ACTION).fieldName(ACTION_CREATE)
            .recordId("order-1").requestBody(new JSONObject()).build());

        assertEquals(201, response.getHttpStatus());
        assertFalse(response.getBody().getJSONObject("response").getJSONObject("data")
            .has("existingDraftInvoices"));
      }
    }
  }

  /**
   * ETP-5381 — the sales-order branch confirms the invoice it just created: the response reports
   * the CONFIRMED status (not {@code DR}) and the completion process runs exactly once. Creating
   * a draft and leaving it behind is what allowed the same order to be invoiced twice, because
   * {@code c_orderline.qtyinvoiced} is only written by {@code C_Invoice_Post}.
   */
  @Test
  public void handleCreate_salesOrder_confirmsInvoiceOnceAndReportsCompletedStatus()
      throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));

      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-co-1");

      DispatchHandler handler = new DispatchHandler();
      handler.orderInvoice = invoice;

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        mockCompletedInvoice(dal, "inv-co-1", "INV-CO-001", "CO");

        NeoResponse response = handler.handle(NeoContext.builder()
            .specName(SPEC_SALES_ORDER)
            .entityName(ENTITY_HEADER)
            .httpMethod("POST")
            .endpointType(NeoEndpointType.ACTION)
            .fieldName(ACTION_CREATE)
            .recordId("order-co-1")
            .build());

        assertNotNull(response);
        assertEquals(201, response.getHttpStatus());
        JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
        assertEquals("The response must report the confirmed status, not DR", "CO",
            data.getString("documentStatus"));
        verify(completion.processInvoiceUtil, Mockito.times(1)).process(
            eq("inv-co-1"), eq("CO"), eq(""), eq(""), eq(""), any(), any());
      }
    }
  }

  /**
   * ETP-5381 — when the confirmation is rejected for a business reason the whole request fails
   * with a 400 carrying that message, and no success payload is emitted. The invoice was rolled
   * back by {@code ProcessInvoiceUtil}, so reporting a {@code documentStatus} would be a lie.
   */
  @Test
  public void handleCreate_completionRejected_returns400WithBusinessMessageAndNoStatus()
      throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));

      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-ko-1");

      DispatchHandler handler = new DispatchHandler();
      handler.orderInvoice = invoice;

      try (CompletionMocks completion =
               new CompletionMocks(dal, completionError("Period is closed"))) {

        NeoResponse response = handler.handle(NeoContext.builder()
            .specName(SPEC_SALES_ORDER)
            .entityName(ENTITY_HEADER)
            .httpMethod("POST")
            .endpointType(NeoEndpointType.ACTION)
            .fieldName(ACTION_CREATE)
            .recordId("order-ko-1")
            .build());

        assertNotNull(response);
        assertEquals(400, response.getHttpStatus());
        assertEquals("Period is closed", response.getBody().getString("message"));
        assertFalse("A rolled-back creation must not report a document status",
            response.getBody().toString().contains("documentStatus"));
      }
    }
  }

  /**
   * A generated invoice whose org→document currency differs, in currency {@code currencyId} with
   * header rate {@code rate}, for org {@code org-1} (organization currency {@code eur}).
   */
  private static Invoice generatedInvoice(String id, String currencyId, BigDecimal rate) {
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn(currencyId);
    Invoice invoice = mock(Invoice.class);
    when(invoice.getId()).thenReturn(id);
    when(invoice.get(Invoice.PROPERTY_ORGANIZATION)).thenReturn(org);
    when(invoice.get(Invoice.PROPERTY_CURRENCY)).thenReturn(currency);
    when(invoice.get(Invoice.PROPERTY_ETGOCURRENCYRATE)).thenReturn(rate);
    return invoice;
  }

  /**
   * ETP-5840, table-driven: an invoice generated from a sales order or quotation in a foreign
   * currency WITHOUT a rate (none configured for its accounting date) is refused BEFORE completing —
   * 422 {@code exchange_rate_missing_on_complete} with its message keys and params, the generation
   * rolled back ({@code rollbackAndClose}), the completion never run and the quotation not marked
   * invoiced. With a rate, or in the organization currency, it completes as before; the shipment
   * route is not checked.
   */
  @Test
  public void handleCreate_foreignInvoiceWithoutRate_isRefusedAndRolledBack() throws Exception {
    Object[][] cases = {
        // name, spec, currency, rate, expected status
        { "order, foreign, no rate", SPEC_SALES_ORDER, "usd", null, 422 },
        { "quotation, foreign, no rate", SPEC_SALES_QUOTATION, "usd", null, 422 },
        { "order, foreign, with rate", SPEC_SALES_ORDER, "usd", new BigDecimal("1.1"), 201 },
        { "order, org currency", SPEC_SALES_ORDER, "eur", null, 201 },
        { "shipment route is not checked", SPEC_GOODS_SHIPMENT, "usd", null, 201 },
    };
    for (Object[] c : cases) {
      String name = (String) c[0];
      String spec = (String) c[1];
      try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
          MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
          MockedStatic<org.openbravo.erpCommon.utility.OBCurrencyUtils> currencies =
              Mockito.mockStatic(org.openbravo.erpCommon.utility.OBCurrencyUtils.class);
          MockedStatic<NeoInvoiceSupport> supportMock =
              Mockito.mockStatic(NeoInvoiceSupport.class)) {
        mockAdminMode(obContextMock);
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        when(dal.getSession()).thenReturn(mock(Session.class));
        currencies.when(() -> org.openbravo.erpCommon.utility.OBCurrencyUtils.getOrgCurrency("org-1"))
            .thenReturn("eur");
        supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(eq("ship-1"),
            eq(true))).thenReturn(Collections.singletonMap("sl-1", BigDecimal.ONE));

        Invoice invoice = generatedInvoice("inv-fx", (String) c[2], (BigDecimal) c[3]);
        DispatchHandler handler = new DispatchHandler();
        handler.orderInvoice = invoice;
        handler.shipmentInvoice = invoice;
        handler.parsedShipmentIds = Collections.singletonList("ship-1");
        handler.sourceOrderStatus = SPEC_SALES_QUOTATION.equals(spec) ? "UE" : "CO";

        try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
          mockCompletedInvoice(dal, "inv-fx", "INV-FX-001", "CO");

          NeoResponse response = handler.handle(NeoContext.builder()
              .specName(spec).entityName(ENTITY_HEADER).httpMethod("POST")
              .endpointType(NeoEndpointType.ACTION).fieldName(ACTION_CREATE)
              .recordId(SPEC_GOODS_SHIPMENT.equals(spec) ? "ship-1" : "src-1")
              .requestBody(new JSONObject()).build());

          assertEquals(name, c[4], response.getHttpStatus());
          if ((Integer) c[4] == 422) {
            JSONObject error = response.getBody().getJSONObject("error");
            assertEquals(name, DocumentRateMessages.CODE_MISSING_ON_COMPLETE,
                error.getString("code"));
            assertEquals(name, DocumentRateMessages.MSG_MISSING_ON_COMPLETE,
                error.getJSONArray(NeoProcessService.MESSAGE_KEYS).getString(0));
            JSONObject params = error.getJSONObject(NeoProcessService.MESSAGE_PARAMS);
            assertEquals(name, "eur", params.getString("orgCurrency"));
            assertEquals(name, "usd", params.getString("documentCurrency"));
            verify(dal).rollbackAndClose();
            verify(completion.processInvoiceUtil, never()).process(any(), any(), any(), any(),
                any(), any(), any());
            assertNull(name, handler.markedQuotationId);
          } else {
            verify(dal, never()).rollbackAndClose();
            verify(completion.processInvoiceUtil, Mockito.times(1)).process(
                eq("inv-fx"), eq("CO"), eq(""), eq(""), eq(""), any(), any());
          }
        }
      }
    }
  }

  /**
   * ETP-5381 ordering regression — {@code markQuotationAsInvoiceCreated} must run AFTER the
   * confirmation, never before. Marking first would leave the quotation in {@code ETGO_CI} even
   * though the invoice was rolled back, and {@code assertQuotationNotInvoiced} would then reject
   * every legitimate retry with a 409.
   */
  @Test
  public void handleCreate_quotationCompletionFails_doesNotMarkQuotationAsInvoiced()
      throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));

      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-q-ko");

      DispatchHandler handler = new DispatchHandler();
      handler.orderInvoice = invoice;

      try (CompletionMocks completion =
               new CompletionMocks(dal, completionError("Tax rate is not configured"))) {

        NeoResponse response = handler.handle(NeoContext.builder()
            .specName(SPEC_SALES_QUOTATION)
            .entityName(ENTITY_HEADER)
            .httpMethod("POST")
            .endpointType(NeoEndpointType.ACTION)
            .fieldName(ACTION_CREATE)
            .recordId("quotation-ko")
            .build());

        assertNotNull(response);
        assertEquals(400, response.getHttpStatus());
        assertNull("The quotation must not be closed as invoiced when the invoice was rolled back",
            handler.markedQuotationId);
      }
    }
  }

  /**
   * Verifies that quotation-based creation marks the source quotation as invoiced.
   *
   * <p>ETP-5381: the marking now happens only once the confirmation has succeeded, so the
   * completion has to be stubbed for the quotation to be marked at all.
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

      DispatchHandler handler = new DispatchHandler();
      handler.orderInvoice = invoice;
      handler.sourceOrderStatus = "UE"; // a quotation is invoiced from Under Evaluation

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        mockCompletedInvoice(dal, "inv-q-1", "INV-Q-001", "CO");

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
  }

  /**
   * Verifies that shipment-based creation triggers gross amount repair.
   *
   * <p>ETP-5381: the branch now runs {@code assertShipmentsHavePending} before creating and
   * confirms the invoice afterwards, so both the pending-quantity query and the completion have
   * to be stubbed.
   */
  @Test
  public void testHandleCreateGoodsShipmentRecalculatesShipmentInvoice() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock =
            Mockito.mockStatic(NeoInvoiceSupport.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(anyString(), eq(true)))
          .thenReturn(Collections.singletonMap("sl-1", new BigDecimal("3")));

      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-s-1");

      DispatchHandler handler = new DispatchHandler();
      handler.shipmentInvoice = invoice;
      handler.parsedShipmentIds = Arrays.asList("ship-1", "ship-2");

      JSONObject body = new JSONObject()
          .put("shipmentIds", new JSONArray().put("ship-1").put("ship-2"))
          .put("lines", new JSONArray().put(new JSONObject()
              .put("shipmentLineId", "sl-1")
              .put("quantity", "3")));

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        mockCompletedInvoice(dal, "inv-s-1", "INV-S-001", "CO");

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
      assertNotNull("ETP-5714: the shipment path aligns the list prices of its whole invoice",
          handler.alignedListPricesInvoice);
        verify(dal, Mockito.times(2)).flush();
      }
    }
  }

  /**
   * ETP-5381 — the bulk case: the guard is "at least one shipment still has something pending",
   * not "every shipment does". Rejecting a batch because its first shipment is already invoiced
   * would block a perfectly valid multi-shipment invoice.
   */
  @Test
  public void handleCreate_goodsShipmentBulkOnePending_isNotRejected() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock =
            Mockito.mockStatic(NeoInvoiceSupport.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));

      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(eq("ship-done"), eq(true)))
          .thenReturn(Collections.emptyMap());
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(eq("ship-open"), eq(true)))
          .thenReturn(Collections.singletonMap("sl-9", new BigDecimal("4")));

      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-bulk-1");

      DispatchHandler handler = new DispatchHandler();
      handler.shipmentInvoice = invoice;
      handler.parsedShipmentIds = Arrays.asList("ship-done", "ship-open");

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        mockCompletedInvoice(dal, "inv-bulk-1", "INV-BULK-001", "CO");

        NeoResponse response = handler.handle(NeoContext.builder()
            .specName(SPEC_GOODS_SHIPMENT)
            .entityName(ENTITY_HEADER)
            .httpMethod("POST")
            .endpointType(NeoEndpointType.ACTION)
            .fieldName(ACTION_CREATE)
            .recordId("ship-done")
            .requestBody(new JSONObject())
            .build());

        assertNotNull(response);
        assertEquals("One already-invoiced shipment must not veto the whole batch",
            201, response.getHttpStatus());
        assertEquals(Arrays.asList("ship-done", "ship-open"), handler.receivedShipmentIds);
      }
    }
  }

  /**
   * Verifies that unexpected creation failures are translated into HTTP 500 responses.
   */
  @Test
  public void testHandleCreateUnexpectedErrorReturns500() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OrderInvoiceDuplicateGuard> guard =
            Mockito.mockStatic(OrderInvoiceDuplicateGuard.class)) {
      mockAdminMode(obContextMock);
      // ETP-5714: the draft lookup is not what this test is about
      guard.when(() -> OrderInvoiceDuplicateGuard.draftsOf(anyString(), anyBoolean()))
          .thenReturn(Collections.emptyList());

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
   * Verifies that invoice listing merges both query sources, deduplicates rows, and formats output fields,
   * including {@code currency$_identifier} (ETP-5527): the ISO code, or JSON null when the invoice has no
   * currency.
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
      Currency euro = mock(Currency.class);
      when(euro.getISOCode()).thenReturn("EUR");
      when(invoiceFromLines.getCurrency()).thenReturn(euro);

      Invoice directOnlyInvoice = mock(Invoice.class);
      when(directOnlyInvoice.getId()).thenReturn("inv-2");
      when(directOnlyInvoice.getDocumentNo()).thenReturn("INV-002");
      when(directOnlyInvoice.getDocumentStatus()).thenReturn("DR");
      when(directOnlyInvoice.getGrandTotalAmount()).thenReturn(null);
      when(directOnlyInvoice.getInvoiceDate()).thenReturn(null);
      when(directOnlyInvoice.getCurrency()).thenReturn(null);

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
      // The sales handler delegates with the sales flag: both queries bind salesTrx=true.
      verify(lineQuery).setParameter("salesTrx", true);
      verify(directQuery).setParameter("salesTrx", true);
      verify(lineQuery, never()).setParameter("salesTrx", false);
      verify(directQuery, never()).setParameter("salesTrx", false);
      JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(2, data.length());
      assertEquals("inv-1", data.getJSONObject(0).getString("id"));
      assertEquals("2026-04-29", data.getJSONObject(0).getString("invoiceDate"));
      assertEquals("EUR", data.getJSONObject(0).getString("currency$_identifier"));
      assertEquals("inv-2", data.getJSONObject(1).getString("id"));
      assertEquals(0, data.getJSONObject(1).getInt("grandTotalAmount"));
      assertFalse(data.getJSONObject(1).has("invoiceDate"));
      assertTrue(data.getJSONObject(1).has("currency$_identifier"));
      assertTrue(data.getJSONObject(1).isNull("currency$_identifier"));
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
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("order-10"))).thenReturn(order);
      when(dal.getSession()).thenReturn(session);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-10");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      // The post-flush UPDATE that links new invoice lines to existing
      // shipment/receipt lines uses the current OBContext user id.
      OBContext obCtx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-1");
      when(obCtx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      // Stub the NativeQuery chain so the UPDATE doesn't NPE.
      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(0);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      CreateFromOrderHandler handler = new CreateFromOrderHandler();
      handler.resolvedDocType = mock(DocumentType.class);
      handler.selectedLines = new JSONArray().put(new JSONObject()
          .put("id", "ol-1")
          .put("orderedQuantity", "2"));

      Map<String, BigDecimal> overrides = Collections.singletonMap("ol-1", new BigDecimal("2"));
      Invoice result = handler.createFromOrder("order-10", overrides, null);

      assertSame(invoice, result);
      assertEquals(overrides, handler.receivedOverrides);
      assertSame(invoice, handler.ensuredGrossInvoice);
      assertSame(invoice, handler.copiedDiscountsInvoice);
      // ETP-5714: the list price the native copy left at 0 is aligned for the whole invoice, after
      // the discount copy (it reads the discount) and before the gross amounts
      assertSame(invoice, handler.alignedInvoice);
      assertNull(handler.alignedScope);
      assertTrue(handler.supportCalls.toString(),
          handler.supportCalls.indexOf("copy") < handler.supportCalls.indexOf("align")
              && handler.supportCalls.indexOf("align") < handler.supportCalls.indexOf("gross"));
      verify(invoice).setDocumentType(handler.resolvedDocType);
      verify(invoice).setTransactionDocument(handler.resolvedDocType);
      // Factory delegates document numbering to DocumentNoHandlerLegacy
      // (event listener replaces "<*>" with the next sequence value on save).
      verify(invoice).setDocumentNo("<*>");
      verify(invoice).setSalesTransaction(true);
      verify(dal).save(invoice);
      verify(process).createInvoiceLinesFromDocumentLines(eq(handler.selectedLines), eq(invoice), eq(OrderLine.class));
      verify(session).refresh(invoice);
      verify(dal, Mockito.atLeastOnce()).flush();
    }
  }

  /**
   * The shipment link of the new lines is chosen by direction ({@code IsSOTrx}), never by window:
   * a sales order plans its shipment lines with the shared rule BEFORE the native process creates
   * the lines (afterwards the invoice's own draft lines would hold every shipment line), then links
   * the lines to that plan; a purchase order keeps the {@code MAX(M_InOutLine_ID)} backfill.
   */
  @Test
  public void testCreateFromOrderLinksShipmentLinesByPlanOnSalesAndByBackfillOnPurchase()
      throws Exception {
    for (boolean sales : new boolean[] { true, false }) {
      String label = sales ? "sales order" : "purchase order";
      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
          MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
          MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class);
          MockedStatic<InvoiceLineLinker> linker = Mockito.mockStatic(InvoiceLineLinker.class)) {
        OBDal dal = mock(OBDal.class);
        Session session = mock(Session.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        Order order = mockOrderWithHeaderData();
        when(order.isSalesTransaction()).thenReturn(sales);
        when(dal.get(eq(Order.class), eq("order-dir"))).thenReturn(order);
        when(dal.getSession()).thenReturn(session);

        OBProvider provider = mock(OBProvider.class);
        Invoice invoice = mock(Invoice.class);
        when(invoice.getId()).thenReturn("invoice-dir");
        obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
        when(provider.get(Invoice.class)).thenReturn(invoice);
        OBContext obCtx = mock(OBContext.class);
        obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

        List<String> calls = new java.util.ArrayList<>();
        CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
        Mockito.doAnswer(inv -> {
          calls.add("native");
          return 0;
        }).when(process).createInvoiceLinesFromDocumentLines(any(), any(), any());
        weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
            .thenReturn(process);
        Map<String, List<OrderLineMovementAllocation.Allocation>> plan = new HashMap<>();
        linker.when(() -> InvoiceLineLinker.planSalesShipmentLines(any())).thenAnswer(inv -> {
          calls.add("plan");
          return plan;
        });

        CreateFromOrderHandler handler = new CreateFromOrderHandler();
        handler.resolvedDocType = mock(DocumentType.class);
        handler.selectedLines = new JSONArray().put(new JSONObject()
            .put("id", "ol-1")
            .put("orderedQuantity", "2"));
        JSONArray selection = handler.selectedLines;

        handler.createFromOrder("order-dir", Collections.emptyMap(), null);

        if (sales) {
          assertEquals(label, Arrays.asList("plan", "native"), calls);
          linker.verify(() -> InvoiceLineLinker.planSalesShipmentLines(selection));
          linker.verify(() -> InvoiceLineLinker.linkSalesInvoiceLinesToPlannedShipments(invoice, plan));
          linker.verify(() -> InvoiceLineLinker.linkInvoiceLinesToExistingInouts(anyString()),
              Mockito.never());
        } else {
          assertEquals(label, Collections.singletonList("native"), calls);
          linker.verify(() -> InvoiceLineLinker.linkInvoiceLinesToExistingInouts("invoice-dir"));
          linker.verify(() -> InvoiceLineLinker.planSalesShipmentLines(any()), Mockito.never());
          linker.verify(() -> InvoiceLineLinker.linkSalesInvoiceLinesToPlannedShipments(any(), any()),
              Mockito.never());
        }
      }
    }
  }

  // ─── applyPriceListOverride (ETP-4028) — exercised via createFromOrder ─────
  //
  // applyPriceListOverride() itself is private, so it is exercised through the
  // createFromOrder() entry point using the same CreateFromOrderHandler test
  // double as testCreateFromOrderPersistsHeaderAndDelegatesLines.

  /**
   * Verifies that when {@code priceListId} is blank/null, {@code invoice.setPriceList(...)}
   * is never called with anything other than the order's own price list — i.e. the popup
   * override is a no-op and the invoice keeps whatever the order/header defaulted to.
   */
  @Test
  public void testCreateFromOrderNullPriceListIdDoesNotOverridePriceList() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("order-pl-null"))).thenReturn(order);
      when(dal.getSession()).thenReturn(session);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-pl-null");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      OBContext obCtx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-pl-null");
      when(obCtx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(0);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      CreateFromOrderHandler handler = new CreateFromOrderHandler();
      handler.resolvedDocType = mock(DocumentType.class);
      handler.selectedLines = new JSONArray().put(new JSONObject()
          .put("id", "ol-1").put("orderedQuantity", "1"));

      handler.createFromOrder("order-pl-null", Collections.emptyMap(), null);

      // Header creation always sets the order's own price list; no override call is made.
      verify(invoice, Mockito.times(1)).setPriceList(any(PriceList.class));
      verify(invoice).setPriceList(order.getPriceList());
      verify(dal, never()).get(eq(PriceList.class), anyString());
    }
  }

  /**
   * Verifies that when {@code priceListId} resolves to an active {@link PriceList},
   * {@code invoice.setPriceList(...)} is called with that resolved price list — overriding
   * whatever the header derivation set from the order.
   */
  @Test
  public void testCreateFromOrderValidPriceListIdOverridesInvoicePriceList() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("order-pl-valid"))).thenReturn(order);
      when(dal.getSession()).thenReturn(session);

      PriceList overridePriceList = mock(PriceList.class);
      when(dal.get(eq(PriceList.class), eq("pl-tarifa-1"))).thenReturn(overridePriceList);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-pl-valid");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      OBContext obCtx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-pl-valid");
      when(obCtx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(0);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      CreateFromOrderHandler handler = new CreateFromOrderHandler();
      handler.resolvedDocType = mock(DocumentType.class);
      handler.selectedLines = new JSONArray().put(new JSONObject()
          .put("id", "ol-1").put("orderedQuantity", "1"));

      handler.createFromOrder("order-pl-valid", Collections.emptyMap(), "pl-tarifa-1");

      verify(invoice).setPriceList(overridePriceList);
    }
  }

  /**
   * Verifies that when {@code priceListId} does not resolve to any {@link PriceList} record
   * (OBDal returns null), no exception is thrown and {@code invoice.setPriceList(...)} is
   * never called with anything other than the order's own price list.
   */
  @Test
  public void testCreateFromOrderUnresolvedPriceListIdDoesNotThrowOrOverride() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("order-pl-missing"))).thenReturn(order);
      when(dal.getSession()).thenReturn(session);
      when(dal.get(eq(PriceList.class), eq("pl-does-not-exist"))).thenReturn(null);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-pl-missing");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      OBContext obCtx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-pl-missing");
      when(obCtx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(0);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      CreateFromOrderHandler handler = new CreateFromOrderHandler();
      handler.resolvedDocType = mock(DocumentType.class);
      handler.selectedLines = new JSONArray().put(new JSONObject()
          .put("id", "ol-1").put("orderedQuantity", "1"));

      Invoice result = handler.createFromOrder("order-pl-missing", Collections.emptyMap(), "pl-does-not-exist");

      assertSame(invoice, result);
      verify(invoice, Mockito.times(1)).setPriceList(any(PriceList.class));
      verify(invoice).setPriceList(order.getPriceList());
    }
  }

  // ─── applyCurrencyOverride (ETP-4314) — exercised via createFromOrder(4-arg) ──
  //
  // applyCurrencyOverride() itself is private, so it is exercised through the
  // 4-arg createFromOrder() entry point using the same CreateFromOrderHandler test
  // double as the price-list override tests above.

  /**
   * Verifies that when {@code currencyId} is blank/null, {@code invoice.setCurrency(...)}
   * is never called with anything other than the order's own currency — i.e. the
   * shipment-currency override is a no-op and the invoice keeps whatever the order's
   * header derivation set.
   */
  @Test
  public void testCreateFromOrderNullCurrencyIdDoesNotOverrideCurrency() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("order-cur-null"))).thenReturn(order);
      when(dal.getSession()).thenReturn(session);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-cur-null");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      OBContext obCtx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-cur-null");
      when(obCtx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(0);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      CreateFromOrderHandler handler = new CreateFromOrderHandler();
      handler.resolvedDocType = mock(DocumentType.class);
      handler.selectedLines = new JSONArray().put(new JSONObject()
          .put("id", "ol-1").put("orderedQuantity", "1"));

      handler.createFromOrder("order-cur-null", Collections.emptyMap(), null, null);

      // Header creation always sets the order's own currency; no override call is made.
      verify(invoice, Mockito.times(1)).setCurrency(any(Currency.class));
      verify(invoice).setCurrency(order.getCurrency());
      verify(dal, never()).get(eq(Currency.class), anyString());
    }
  }

  /**
   * Verifies that when {@code currencyId} resolves to an active {@link Currency},
   * {@code invoice.setCurrency(...)} is called with that resolved currency —
   * overriding whatever the header derivation set from the order.
   */
  @Test
  public void testCreateFromOrderValidCurrencyIdOverridesInvoiceCurrency() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("order-cur-valid"))).thenReturn(order);
      when(dal.getSession()).thenReturn(session);

      Currency overrideCurrency = mock(Currency.class);
      when(dal.get(eq(Currency.class), eq("cur-eur"))).thenReturn(overrideCurrency);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-cur-valid");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      OBContext obCtx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-cur-valid");
      when(obCtx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(0);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      CreateFromOrderHandler handler = new CreateFromOrderHandler();
      handler.resolvedDocType = mock(DocumentType.class);
      handler.selectedLines = new JSONArray().put(new JSONObject()
          .put("id", "ol-1").put("orderedQuantity", "1"));

      handler.createFromOrder("order-cur-valid", Collections.emptyMap(), null, "cur-eur");

      verify(invoice).setCurrency(overrideCurrency);
    }
  }

  /**
   * Verifies that when {@code currencyId} does not resolve to any {@link Currency} record
   * (OBDal returns null), no exception is thrown and {@code invoice.setCurrency(...)} is
   * never called with anything other than the order's own currency.
   */
  @Test
  public void testCreateFromOrderUnresolvedCurrencyIdDoesNotThrowOrOverride() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("order-cur-missing"))).thenReturn(order);
      when(dal.getSession()).thenReturn(session);
      when(dal.get(eq(Currency.class), eq("cur-does-not-exist"))).thenReturn(null);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-cur-missing");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      OBContext obCtx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-cur-missing");
      when(obCtx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(0);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      CreateFromOrderHandler handler = new CreateFromOrderHandler();
      handler.resolvedDocType = mock(DocumentType.class);
      handler.selectedLines = new JSONArray().put(new JSONObject()
          .put("id", "ol-1").put("orderedQuantity", "1"));

      Invoice result = handler.createFromOrder("order-cur-missing", Collections.emptyMap(), null,
          "cur-does-not-exist");

      assertSame(invoice, result);
      verify(invoice, Mockito.times(1)).setCurrency(any(Currency.class));
      verify(invoice).setCurrency(order.getCurrency());
    }
  }

  // ETP-4780: copyLineDiscountsFromOrder() moved to InvoiceFromOrderSupport so
  // CreatePurchaseInvoiceHandler can reuse it — the logic-level regression test now
  // lives in InvoiceFromOrderSupportTest. CreateFromOrderHandler above still verifies
  // that createFromOrder() delegates to getSupport().copyLineDiscountsFromOrder(...).

  @Test
  public void testCreateInvoiceFromOrderHeaderCarriesTotalDiscountToInvoiceHeader() {
    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      Order order = mock(Order.class);
      when(order.getEtgoTotalDiscount()).thenReturn(new BigDecimal("12.5"));

      NeoCommercialDocumentFactory.createInvoiceFromOrderHeader(order, mock(DocumentType.class), true);

      verify(invoice).setEtgoTotalDiscount(new BigDecimal("12.5"));
    }
  }

  /**
   * Verifies that after the line-creation process, createFromOrder runs the
   * native UPDATE that links each new invoice line to an existing shipment/
   * receipt line of the same order line. This is what populates
   * c_invoiceline.M_InOutLine_ID so that m_inout_post can later create
   * m_matchsi/m_matchinv when the inout is completed. Without this step,
   * invoices created after a shipment (e.g. when the order-confirm modal
   * asks for both at once and creates the shipment first) stay unlinked
   * and the matching tables never populate.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testCreateFromOrderLinksInvoiceLinesToExistingInoutLines() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      Order orderHeader = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("order-X"))).thenReturn(orderHeader);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-X");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      OBContext obCtx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-Y");
      when(obCtx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(1);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      CreateFromOrderHandler handler = new CreateFromOrderHandler();
      handler.resolvedDocType = mock(DocumentType.class);
      handler.selectedLines = new JSONArray().put(new JSONObject()
          .put("id", "ol-X")
          .put("orderedQuantity", "1"));

      handler.createFromOrder("order-X", Collections.emptyMap(), null);

      // Capture the SQL and assert it has the right shape.
      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session).createNativeQuery(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue("SQL should target C_InvoiceLine", sql.contains("UPDATE C_InvoiceLine"));
      assertTrue("SQL should set M_InOutLine_ID", sql.contains("SET M_InOutLine_ID"));
      assertTrue("SQL should subquery MAX over M_InOutLine", sql.contains("MAX(iol.M_InOutLine_ID)"));
      assertTrue("SQL should join via C_OrderLine_ID", sql.contains("iol.C_OrderLine_ID = il.C_OrderLine_ID"));
      assertTrue("SQL should scope to the new invoice", sql.contains(":invoiceId"));
      assertTrue("SQL should only touch unlinked lines", sql.contains("M_InOutLine_ID IS NULL"));
      assertTrue("SQL should skip orderline-less rows", sql.contains("C_OrderLine_ID IS NOT NULL"));

      verify(linkQuery).setParameter(eq("userId"), eq("user-Y"));
      verify(linkQuery).setParameter(eq("invoiceId"), eq("invoice-X"));
      verify(linkQuery).executeUpdate();
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

      handler.createFromOrder("order-11", Collections.emptyMap(), null);
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
      // ETP-4942: createFromShipments now fails fast when the resulting invoice has no
      // price list at all (see ensurePriceListResolved) — stub a non-null one so this
      // test keeps exercising header/lines plumbing rather than that unrelated guard.
      when(invoice.getPriceList()).thenReturn(mock(PriceList.class));

      CreateFromShipmentsHandler handler = new CreateFromShipmentsHandler();
      handler.shipmentsToReturn = Arrays.asList(first, second);
      handler.invoiceHeader = invoice;
      Map<String, BigDecimal> overrides = Collections.singletonMap("sl-1", new BigDecimal("4"));

      Invoice result = handler.createFromShipments(Arrays.asList("ship-10", "ship-20"), overrides, null);

      assertSame(invoice, result);
      assertEquals(Arrays.asList(first, second), handler.receivedShipments);
      assertEquals(overrides, handler.receivedOverrides);
      assertSame(invoice, handler.receivedInvoice);
      verify(dal).save(invoice);
      verify(dal).flush();
    }
  }

  /**
   * Verifies that the multi-shipment path of {@code createFromShipments} also applies the
   * price-list override (ETP-4028) — same {@code applyPriceListOverride} helper used by
   * {@code createFromOrder}, called before {@code OBDal.save(invoice)}.
   */
  @Test
  public void testCreateFromShipmentsMultiShipmentAppliesPriceListOverride() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList overridePriceList = mock(PriceList.class);
      when(dal.get(eq(PriceList.class), eq("pl-shipments-1"))).thenReturn(overridePriceList);

      ShipmentInOut first = mock(ShipmentInOut.class);
      ShipmentInOut second = mock(ShipmentInOut.class);
      Invoice invoice = mock(Invoice.class);
      // ETP-4942: stub the getter too — a bare mock's setPriceList(...) call (verified
      // below) does not make getPriceList() reflect it, and ensurePriceListResolved
      // would otherwise see a null price list and throw.
      when(invoice.getPriceList()).thenReturn(overridePriceList);

      CreateFromShipmentsHandler handler = new CreateFromShipmentsHandler();
      handler.shipmentsToReturn = Arrays.asList(first, second);
      handler.invoiceHeader = invoice;

      handler.createFromShipments(Arrays.asList("ship-30", "ship-40"),
          Collections.emptyMap(), "pl-shipments-1");

      verify(invoice).setPriceList(overridePriceList);
    }
  }

  // ─── ensurePriceListResolved (ETP-4942) — exercised via createFromShipments ─
  //
  // ensurePriceListResolved() itself is private and takes only the already-built
  // Invoice, so it is exercised through the multi-shipment createFromShipments()
  // entry point (the single-shipment-with-linked-order path delegates to
  // createFromOrder() before this guard ever runs) using the same
  // CreateFromShipmentsHandler test double as the price-list-override tests above.
  // The "does not throw" side is already covered by
  // testCreateFromShipmentsSavesHeaderAndAddsLines (header already resolved a
  // tariff — the linked-order/Business-Partner-default case) and
  // testCreateFromShipmentsMultiShipmentAppliesPriceListOverride (the explicit
  // popup override resolves it); this section adds the missing throwing case plus
  // one explicit non-throwing case naming the guard directly.

  /**
   * Verifies that {@code createFromShipments} fails fast with the ETP-4942
   * validation message when none of the three price-list sources (linked order,
   * Business Partner default, explicit popup override) resolved a tariff — i.e.
   * the header-derived invoice still has a {@code null} price list and no
   * {@code priceListId} override was supplied.
   */
  @Test
  public void testCreateFromShipmentsNoPriceListResolvedThrows() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      ShipmentInOut first = mock(ShipmentInOut.class);
      Invoice invoice = mock(Invoice.class);
      // No linked order, no Business Partner default, no override — the header
      // derivation left the invoice's price list null.
      when(invoice.getPriceList()).thenReturn(null);

      CreateFromShipmentsHandler handler = new CreateFromShipmentsHandler();
      handler.shipmentsToReturn = Collections.singletonList(first);
      handler.invoiceHeader = invoice;

      try {
        handler.createFromShipments(Collections.singletonList("ship-50"), Collections.emptyMap(), null);
        org.junit.Assert.fail("Expected an OBException when no Price List could be resolved");
      } catch (OBException e) {
        assertEquals(
            "No Price List could be resolved for this invoice: select a tariff or configure "
                + "a default Price List for the Business Partner",
            e.getMessage());
      }

      // The guard must fail BEFORE the header/lines are persisted, so a bad
      // draft invoice is never left half-built in the database.
      verify(dal, never()).save(invoice);
      verify(dal, never()).flush();
    }
  }

  /**
   * Verifies that {@code createFromShipments} does NOT throw when the popup
   * override resolves a {@link PriceList} even though the header derivation
   * itself left the invoice's price list null (no linked order, no Business
   * Partner default) — the explicit override is the last chance to fill it in,
   * exercised before {@code ensurePriceListResolved} runs.
   */
  @Test
  public void testCreateFromShipmentsOverrideAloneSatisfiesPriceListGuard() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList overridePriceList = mock(PriceList.class);
      when(dal.get(eq(PriceList.class), eq("pl-guard-1"))).thenReturn(overridePriceList);

      ShipmentInOut first = mock(ShipmentInOut.class);
      Invoice invoice = mock(Invoice.class);
      // Header derivation alone left it null; only the override call (stubbed via
      // getPriceList() below, since a bare mock's setPriceList() doesn't make
      // getPriceList() reflect it) resolves a tariff.
      when(invoice.getPriceList()).thenReturn(overridePriceList);

      CreateFromShipmentsHandler handler = new CreateFromShipmentsHandler();
      handler.shipmentsToReturn = Collections.singletonList(first);
      handler.invoiceHeader = invoice;

      Invoice result = handler.createFromShipments(
          Collections.singletonList("ship-51"), Collections.emptyMap(), "pl-guard-1");

      assertSame(invoice, result);
      verify(invoice).setPriceList(overridePriceList);
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

      Currency shipmentCurrency = mock(Currency.class);
      ShipmentInOut shipment = mock(ShipmentInOut.class);
      when(shipment.getClient()).thenReturn(client);
      when(shipment.getOrganization()).thenReturn(organization);
      when(shipment.getBusinessPartner()).thenReturn(businessPartner);
      when(shipment.getPartnerAddress()).thenReturn(null);
      when(shipment.getSalesOrder()).thenReturn(linkedOrder);
      when(shipment.getEtgoCurrency()).thenReturn(shipmentCurrency);

      InvoiceHeaderHandler handler = new InvoiceHeaderHandler();
      Invoice result = handler.createInvoiceHeaderFromShipment(shipment, Collections.singletonList(shipment));

      assertSame(invoice, result);
      verify(invoice).setDocumentType(invoiceDocType);
      verify(invoice).setTransactionDocument(invoiceDocType);
      verify(invoice).setPriceList(priceList);
      verify(invoice).setPaymentTerms(paymentTerm);
      verify(invoice).setPaymentMethod(paymentMethod);
      verify(invoice).setSalesOrder(linkedOrder);
      verify(invoice).setBusinessPartner(businessPartner);
      // ETP-4028/ETP-4314: the invoice's currency is unconditionally overwritten
      // with the shipment's OWN currency at the end of the method — it may diverge
      // from linkedOrder.getCurrency() (set earlier) once the user edits the
      // shipment's currency in draft, so the shipment's currency must win.
      verify(invoice).setCurrency(shipmentCurrency);
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

      Currency shipmentCurrency = mock(Currency.class);
      ShipmentInOut shipment = mock(ShipmentInOut.class);
      when(shipment.getClient()).thenReturn(mock(Client.class));
      when(shipment.getOrganization()).thenReturn(organization);
      when(shipment.getBusinessPartner()).thenReturn(businessPartner);
      when(shipment.getPartnerAddress()).thenReturn(null);
      when(shipment.getSalesOrder()).thenReturn(null);
      when(shipment.getEtgoCurrency()).thenReturn(shipmentCurrency);

      InvoiceHeaderHandler handler = new InvoiceHeaderHandler();
      handler.docTypeToReturn = mock(DocumentType.class);

      Invoice result = handler.createInvoiceHeaderFromShipment(shipment, Collections.singletonList(shipment));

      assertSame(invoice, result);
      assertEquals("org-20", handler.requestedOrgId);
      verify(invoice).setPriceList(priceList);
      verify(invoice).setPaymentTerms(paymentTerm);
      verify(invoice).setPaymentMethod(paymentMethod);
      verify(invoice, never()).setSalesOrder(any(Order.class));
      // ETP-4028/ETP-4314: even in the no-order (BP-default) branch, the invoice's
      // currency must end up being the shipment's OWN currency, not the BP's
      // price-list currency set earlier in this same branch.
      verify(invoice).setCurrency(shipmentCurrency);
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
   * Verifies that addShipmentLinesToInvoice includes qualifying lines and skips null-qty lines
   * when delegating to CreateInvoiceLinesFromProcess.
   */
  @Test
  public void testAddShipmentLinesToInvoiceDelegatesQualifyingLinesToProcess() throws Exception {
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
    handler.qtyByShipmentLineId.put("sl-2", null);  // excluded
    handler.qtyByShipmentLineId.put("sl-3", new BigDecimal("5"));

    handler.addShipmentLinesToInvoice(mock(Invoice.class), Arrays.asList(shipment1, shipment2),
        Collections.emptyMap());

    assertNotNull(handler.capturedLines);
    assertEquals(2, handler.capturedLines.length());
    assertEquals("sl-1", handler.capturedLines.getJSONObject(0).getString("id"));
    assertEquals("2", handler.capturedLines.getJSONObject(0).getString("orderedQuantity"));
    assertEquals("sl-3", handler.capturedLines.getJSONObject(1).getString("id"));
    assertEquals("5", handler.capturedLines.getJSONObject(1).getString("orderedQuantity"));
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

  // ── handlePendingLines ────────────────────────────────────────────────────

  /** Blank recordId returns 400. */
  @Test
  public void testHandlePendingLinesBlankRecordIdReturns400() {
    NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_GOODS_SHIPMENT).entityName(ENTITY_HEADER)
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName("pendingInvoiceLines").build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  /** Whitespace recordId returns 400. */
  @Test
  public void testHandlePendingLinesWhitespaceRecordIdReturns400() {
    NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_GOODS_SHIPMENT).entityName(ENTITY_HEADER)
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName("pendingInvoiceLines").recordId("   ").build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  /** POST method for pendingInvoiceLines is not routed (only GET is handled). */
  @Test
  public void testHandlePendingLinesPostMethodReturnsNull() {
    NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_GOODS_SHIPMENT).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("pendingInvoiceLines").recordId("ship-1").build());
    assertNull(r);
  }

  /**
   * Success path: subclass overrides computePendingQtyPerLine to return a known map;
   * verifies JSON structure of the response.
   */
  @Test
  public void testHandlePendingLinesSuccessReturns200WithLineArray() throws Exception {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      mockAdminMode(ctxMock);
      // ETP-5410 follow-up: handlePendingLines now also loads the shipment itself (for the
      // product/salesOrderLine enrichment and the resolved price list) — a null doc here keeps
      // this test focused on the pre-existing lineId/pendingQty assertions below.
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(org.openbravo.model.materialmgmt.transaction.ShipmentInOut.class), anyString()))
          .thenReturn(null);

      CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler() {
        @Override
        protected Map<String, BigDecimal> computePendingQtyPerLine(String shipmentId, boolean includeDrafts) {
          Map<String, BigDecimal> m = new java.util.LinkedHashMap<>();
          m.put("line-A", new BigDecimal("3"));
          m.put("line-B", new BigDecimal("0"));
          return m;
        }
      };

      NeoResponse r = handler.handle(NeoContext.builder()
          .specName(SPEC_GOODS_SHIPMENT).entityName(ENTITY_HEADER)
          .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
          .fieldName("pendingInvoiceLines").recordId("ship-99").build());

      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
      JSONArray data = r.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(2, data.length());
      assertEquals("line-A", data.getJSONObject(0).getString("lineId"));
      assertEquals(3.0, data.getJSONObject(0).getDouble("pendingQty"), 0.0);
      assertEquals("line-B", data.getJSONObject(1).getString("lineId"));
    }
  }

  /** Exception inside computePendingQtyPerLine is caught and returns 500. */
  @Test
  public void testHandlePendingLinesExceptionReturns500() throws Exception {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      mockAdminMode(ctxMock);

      CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler() {
        @Override
        protected Map<String, BigDecimal> computePendingQtyPerLine(String shipmentId, boolean includeDrafts) {
          throw new RuntimeException("db burst");
        }
      };

      NeoResponse r = handler.handle(NeoContext.builder()
          .specName(SPEC_GOODS_SHIPMENT).entityName(ENTITY_HEADER)
          .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
          .fieldName("pendingInvoiceLines").recordId("ship-1").build());

      assertNotNull(r);
      assertEquals(500, r.getHttpStatus());
    }
  }

  // ── handleProductPrices ───────────────────────────────────────────────────

  /** GET method for productPrices is not routed (only POST is handled). */
  @Test
  public void testHandleProductPricesGetMethodReturnsNull() {
    NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
        .specName(SPEC_GOODS_SHIPMENT).entityName(ENTITY_HEADER)
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName("productPrices").build());
    assertNull(r);
  }

  /** No productIds in the body: 200 with an empty data array, never a 500/NPE. */
  @Test
  public void testHandleProductPricesEmptyBodyReturns200WithEmptyArray() throws Exception {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      mockAdminMode(ctxMock);

      NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
          .specName(SPEC_GOODS_SHIPMENT).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("productPrices").requestBody(new JSONObject()).build());

      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
      JSONArray data = r.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(0, data.length());
    }
  }

  /**
   * Happy path: delegates to {@link MultiDocumentInvoiceSupport#buildProductPricesResponse},
   * which in turn calls {@link MultiDocumentInvoiceSupport#resolveProductPrices} and
   * serialises the result as {@code [{productId, price}, ...]}. {@code CALLS_REAL_METHODS}
   * keeps {@code buildProductPricesResponse} itself real (it's the delegation this test
   * verifies) while only {@code resolveProductPrices} — the DB-touching part — is stubbed.
   */
  @Test
  public void testHandleProductPricesSuccessReturns200WithPricedProducts() throws Exception {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<MultiDocumentInvoiceSupport> supportMock =
            Mockito.mockStatic(MultiDocumentInvoiceSupport.class, Answers.CALLS_REAL_METHODS)) {
      mockAdminMode(ctxMock);
      Map<String, BigDecimal> prices = new java.util.LinkedHashMap<>();
      prices.put("prod-1", new BigDecimal("9.99"));
      supportMock.when(() -> MultiDocumentInvoiceSupport.resolveProductPrices(
          eq("PL-1"), any())).thenReturn(prices);

      JSONObject body = new JSONObject()
          .put("priceListId", "PL-1")
          .put("productIds", new JSONArray().put("prod-1"));

      NeoResponse r = new CreateDraftInvoiceHandler().handle(NeoContext.builder()
          .specName(SPEC_GOODS_SHIPMENT).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("productPrices").requestBody(body).build());

      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
      JSONArray data = r.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(1, data.length());
      assertEquals("prod-1", data.getJSONObject(0).getString("productId"));
      assertEquals(9.99, data.getJSONObject(0).getDouble("price"), 0.0);
    }
  }

  // ── computePendingQtyPerLine ──────────────────────────────────────────────

  /** DB connection throws: method catches it and returns empty map. */
  @Test
  public void testComputePendingQtyPerLineDbErrorReturnsEmptyMap() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      // getConnection() throws — simulates a lost connection
      when(dal.getConnection()).thenThrow(new RuntimeException("connection lost"));

      Map<String, BigDecimal> result = new CreateDraftInvoiceHandler()
          .computePendingQtyPerLine("ship-X");
      assertNotNull(result);
      assertTrue(result.isEmpty());
    }
  }

  /**
   * PreparedStatement throws during executeQuery: method catches and returns empty map.
   */
  @Test
  public void testComputePendingQtyPerLinePrepareStmtExceptionReturnsEmpty() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      java.sql.Connection conn = mock(java.sql.Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenThrow(new java.sql.SQLException("query failed"));

      Map<String, BigDecimal> result = new CreateDraftInvoiceHandler()
          .computePendingQtyPerLine("ship-Y");
      assertNotNull(result);
      assertTrue(result.isEmpty());
    }
  }

  /**
   * ResultSet returns one row: movQty=5, invQty=2 → pending=3.
   * Also verifies clamping: a row with movQty=1, invQty=3 → pending=0 (not negative).
   */
  @Test
  public void testComputePendingQtyPerLineSubtractsAndClampsAtZero() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      java.sql.Connection conn = mock(java.sql.Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);

      java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
      // First row: pending = 5 - 2 = 3
      // Second row: pending = max(1 - 3, 0) = 0
      when(rs.next()).thenReturn(true, true, false);
      when(rs.getString(1)).thenReturn("line-1", "line-2");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("5"), new BigDecimal("1"));
      when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("2"), new BigDecimal("3"));
      when(ps.executeQuery()).thenReturn(rs);

      Map<String, BigDecimal> result = new CreateDraftInvoiceHandler()
          .computePendingQtyPerLine("ship-Z");
      assertEquals(1, result.size());
      assertEquals(0, new BigDecimal("3").compareTo(result.get("line-1")));
      assertFalse("line-2 with zero pending (clamped from negative) must not be in map",
          result.containsKey("line-2"));
    }
  }

  /** Null movQty is treated as zero: 0 - invQty clamped at 0. */
  @Test
  public void testComputePendingQtyPerLineNullMovQtyTreatedAsZero() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      java.sql.Connection conn = mock(java.sql.Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-null");
      when(rs.getBigDecimal(2)).thenReturn(null);
      when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("2"));
      when(ps.executeQuery()).thenReturn(rs);

      Map<String, BigDecimal> result = new CreateDraftInvoiceHandler()
          .computePendingQtyPerLine("ship-null");
      assertFalse("null movQty treated as zero: pending = max(0-2,0) = 0, line must not be in map",
          result.containsKey("line-null"));
    }
  }

  // ── resolveShipmentLineQty (4-arg with pendingQtyMap) ────────────────────

  /** pendingQtyMap entry < movementQty → capped at pendingQty. */
  @Test
  public void testResolveShipmentLineQty4ArgPendingQtyCapsMovement() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.isActive()).thenReturn(true);
    when(sl.getId()).thenReturn("sl-pending-1");
    when(sl.getMovementQuantity()).thenReturn(new BigDecimal("10"));

    Map<String, BigDecimal> pendingMap = new HashMap<>();
    pendingMap.put("sl-pending-1", new BigDecimal("4"));

    BigDecimal result = new CreateDraftInvoiceHandler()
        .resolveShipmentLineQty(sl, false, new HashMap<>(), pendingMap);
    assertEquals(0, new BigDecimal("4").compareTo(result));
  }

  /** pendingQtyMap entry = 0 → line is already fully invoiced, returns null. */
  @Test
  public void testResolveShipmentLineQty4ArgZeroPendingReturnsNull() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.isActive()).thenReturn(true);
    when(sl.getId()).thenReturn("sl-zero");
    when(sl.getMovementQuantity()).thenReturn(new BigDecimal("5"));

    Map<String, BigDecimal> pendingMap = new HashMap<>();
    pendingMap.put("sl-zero", BigDecimal.ZERO);

    assertNull(new CreateDraftInvoiceHandler()
        .resolveShipmentLineQty(sl, false, new HashMap<>(), pendingMap));
  }

  /**
   * ETP-5709: a line absent from a NON-NULL pendingQtyMap has nothing pending (the pending query
   * omits fully-invoiced lines) and is skipped. It used to fall back to the movement quantity,
   * which re-invoiced every already-invoiced line.
   */
  @Test
  public void testResolveShipmentLineQty4ArgMissingFromMapIsSkipped() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.isActive()).thenReturn(true);
    when(sl.getId()).thenReturn("sl-not-in-map");
    when(sl.getMovementQuantity()).thenReturn(new BigDecimal("7"));

    assertNull(new CreateDraftInvoiceHandler()
        .resolveShipmentLineQty(sl, false, new HashMap<>(), new HashMap<>()));
  }

  /** No pending information at all ({@code null} map) → movement quantity (backward-compat). */
  @Test
  public void testResolveShipmentLineQty4ArgNullMapFallsBackToMovement() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.isActive()).thenReturn(true);
    when(sl.getId()).thenReturn("sl-no-info");
    when(sl.getMovementQuantity()).thenReturn(new BigDecimal("7"));

    BigDecimal result = new CreateDraftInvoiceHandler()
        .resolveShipmentLineQty(sl, false, new HashMap<>(), null);
    assertEquals(0, new BigDecimal("7").compareTo(result));
  }

  /** Override present + pendingQty: result = min(override, pendingQty). */
  @Test
  public void testResolveShipmentLineQty4ArgOverrideCappedByPendingQty() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.isActive()).thenReturn(true);
    when(sl.getId()).thenReturn("sl-cap");
    when(sl.getMovementQuantity()).thenReturn(new BigDecimal("10"));

    Map<String, BigDecimal> overrides = new HashMap<>();
    overrides.put("sl-cap", new BigDecimal("8"));
    Map<String, BigDecimal> pendingMap = new HashMap<>();
    pendingMap.put("sl-cap", new BigDecimal("5"));

    // min(override=8, pendingQty=5) = 5
    BigDecimal result = new CreateDraftInvoiceHandler()
        .resolveShipmentLineQty(sl, true, overrides, pendingMap);
    assertEquals(0, new BigDecimal("5").compareTo(result));
  }

  // ── handleCreate — OBException produces structured JSON body ────────────────

  /**
   * When createFromOrder throws an OBException the catch block wraps the message
   * in {"status":"error","message":"..."} and returns HTTP 400.
   */
  @Test
  public void testHandleCreateOBExceptionReturns400WithStructuredBody() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OrderInvoiceDuplicateGuard> guard =
            Mockito.mockStatic(OrderInvoiceDuplicateGuard.class)) {
      mockAdminMode(obContextMock);
      // ETP-5714: the draft lookup is not what this test is about
      guard.when(() -> OrderInvoiceDuplicateGuard.draftsOf(anyString(), anyBoolean()))
          .thenReturn(Collections.emptyList());

      DispatchHandler handler = new DispatchHandler();
      handler.createFailure = new OBException("not enough stock");

      NeoResponse response = handler.handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER)
          .entityName(ENTITY_HEADER)
          .httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE)
          .recordId("order-err")
          .build());

      assertNotNull(response);
      assertEquals(400, response.getHttpStatus());
      String body = response.getBody().toString();
      assertTrue("Response body must contain the OBException message",
          body.contains("not enough stock"));
    }
  }

  // ── handleCheck — single draft returns id + documentNo ──────────────────────

  /**
   * When exactly one draft exists the check response contains its id and documentNo,
   * and the "drafts" array is absent (only added when count > 1).
   */
  @Test
  public void testHandleCheckSingleDraftReturnsIdAndDocNo() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      mockAdminMode(obContextMock);

      Invoice draft = mock(Invoice.class);
      when(draft.getId()).thenReturn("inv-single");
      when(draft.getDocumentNo()).thenReturn("INV-SINGLE-001");

      DispatchHandler handler = new DispatchHandler();
      handler.draftsToReturn = Collections.singletonList(draft);

      NeoResponse response = handler.handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER)
          .entityName(ENTITY_HEADER)
          .httpMethod("GET")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CHECK)
          .recordId("order-single")
          .build());

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertTrue(data.getBoolean("exists"));
      assertEquals(1, data.getInt("count"));
      assertEquals("inv-single", data.getString("id"));
      assertEquals("INV-SINGLE-001", data.getString("documentNo"));
      assertFalse("drafts array must not be present when count == 1", data.has("drafts"));
    }
  }

  // ── createFromShipments — single shipment with linked order delegates ────────

  /**
   * When there is exactly one shipment and it has a linked sales order, createFromShipments
   * delegates to the order-based creation (header/pricing) instead of the multi-shipment path,
   * handing it a selection scoped to the shipment's own lines (ETP-5709).
   */
  @Test
  public void testCreateFromShipmentsSingleShipmentWithOrderDelegatesToCreateFromOrder() {
    Order linkedOrder = mock(Order.class);
    when(linkedOrder.getId()).thenReturn("order-linked");

    ShipmentInOut single = mock(ShipmentInOut.class);
    when(single.getSalesOrder()).thenReturn(linkedOrder);
    List<ShipmentInOutLine> singleLines =
        Collections.singletonList(shipmentLine("sl-1", "2", "ol-1"));
    when(single.getMaterialMgmtShipmentInOutLineList()).thenReturn(singleLines);
    when(single.getId()).thenReturn("ship-single");

    Invoice expectedInvoice = mock(Invoice.class);
    final boolean[] orderPathCalled = {false};
    final boolean[] multiPathCalled = {false};

    CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler() {
      @Override
      protected List<ShipmentInOut> loadAndValidateShipments(List<String> ids) {
        return Collections.singletonList(single);
      }

      @Override
      protected void evictShipmentFromSession(ShipmentInOut shipment) {
        // the real one needs a Hibernate session
      }

      @Override
      protected Invoice createFromOrderLines(String orderId, JSONArray selectedLines,
          String priceListId, String currencyId) {
        orderPathCalled[0] = true;
        assertEquals("order-linked", orderId);
        assertEquals(1, selectedLines.length());
        return expectedInvoice;
      }

      @Override
      protected Invoice createInvoiceHeaderFromShipment(ShipmentInOut first, List<ShipmentInOut> shipments) {
        multiPathCalled[0] = true;
        return null;
      }

      @Override
      protected Map<String, BigDecimal> computePendingQtyPerLineOrThrow(String shipmentId,
          boolean includeDrafts) {
        return Collections.singletonMap("sl-1", new BigDecimal("2"));
      }
    };

    Invoice result = handler.createFromShipments(
        Collections.singletonList("ship-single"), Collections.emptyMap(), null);
    assertSame(expectedInvoice, result);
    assertTrue("Single-shipment-with-order path must delegate to the order-based creation",
        orderPathCalled[0]);
    assertFalse("Multi-shipment path must NOT be entered", multiPathCalled[0]);
  }

  // ── single shipment with order, NEGATIVE (return-style) line (ETP-4722 / ETP-5709) ──

  /** Handler whose single-shipment-with-order route captures the selection handed to core. */
  private static final class SelectionCapturingHandler extends CreateDraftInvoiceHandler {
    private final ShipmentInOut shipment;
    private final Map<String, BigDecimal> pending;
    JSONArray capturedSelection;

    SelectionCapturingHandler(ShipmentInOut shipment, Map<String, BigDecimal> pending) {
      this.shipment = shipment;
      this.pending = pending;
    }

    @Override
    protected List<ShipmentInOut> loadAndValidateShipments(List<String> ids) {
      return Collections.singletonList(shipment);
    }

    @Override
    protected void evictShipmentFromSession(ShipmentInOut s) {
      // the real one needs a Hibernate session
    }

    @Override
    protected Invoice createFromOrderLines(String orderId, JSONArray selectedLines,
        String priceListId, String currencyId) {
      capturedSelection = selectedLines;
      return mock(Invoice.class);
    }

    @Override
    protected Map<String, BigDecimal> computePendingQtyPerLineOrThrow(String shipmentId,
        boolean includeDrafts) {
      return pending;
    }
  }

  private static SelectionCapturingHandler negativeShipmentHandler() {
    ShipmentInOut shipment = shipmentOf("neg", true, shipmentLine("sl-neg", "-3", "ol-neg"));
    return new SelectionCapturingHandler(shipment,
        Collections.singletonMap("sl-neg", new BigDecimal("3")));
  }

  /** A -3 return-style shipment line with no explicit lines is selected at -3, bound to its line. */
  @Test
  public void testCreateFromShipmentsSingleNegativeLine_noOverrides_emitsSignedPendingBoundToLine()
      throws JSONException {
    SelectionCapturingHandler handler = negativeShipmentHandler();

    handler.createFromShipments(Collections.singletonList("neg"), Collections.emptyMap(), null);

    assertEquals(1, handler.capturedSelection.length());
    JSONObject entry = handler.capturedSelection.getJSONObject(0);
    assertEquals("ol-neg", entry.getString("id"));
    assertEquals("-3", entry.getString("orderedQuantity"));
    assertEquals("sl-neg", entry.getString("shipmentInOutLine"));
  }

  /**
   * The sales parser only accepts quantity &gt; 0, so a NEGATIVE partial cannot be expressed on
   * this path: {@code -1} is dropped and the request, whose {@code lines} carries an entry, is
   * refused by {@code requireParsedLines} instead of invoicing the whole line.
   */
  @Test
  public void testSalesPathCannotExpressANegativePartial_negativeQuantityIsDroppedAndRefused()
      throws JSONException {
    JSONObject body = new JSONObject().put("lines", new JSONArray()
        .put(new JSONObject().put("shipmentLineId", "sl-neg").put("quantity", "-1")));

    Map<String, BigDecimal> parsed = new CreateDraftInvoiceHandler().parseLineOverrides(body);

    assertTrue("a negative quantity is dropped by the sales parser", parsed.isEmpty());
    try {
      MultiDocumentInvoiceSupport.requireParsedLines(body, "lines", parsed, "lines unusable");
      org.junit.Assert.fail("lines that parse to nothing must be refused, not invoice everything");
    } catch (OBException expected) {
      assertEquals("lines unusable", expected.getMessage());
    }
  }

  /** The only expressible quantity (+1) is the opposite sign of a -3 line: 400 naming line and -3. */
  @Test
  public void testCreateFromShipmentsSingleNegativeLine_positiveOverride_refusedNamingLineAndPending() {
    SelectionCapturingHandler handler = negativeShipmentHandler();

    try {
      handler.createFromShipments(Collections.singletonList("neg"),
          Collections.singletonMap("sl-neg", BigDecimal.ONE), null);
      org.junit.Assert.fail("+1 on a -3 line must be refused");
    } catch (OBException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("SH-neg"));
      assertTrue(e.getMessage(), e.getMessage().contains("sl-neg"));
      assertTrue(e.getMessage(), e.getMessage().contains("cantidad solicitada 1"));
      assertTrue(e.getMessage(), e.getMessage().contains("pendiente de facturar es -3"));
    }
    assertNull("nothing may be handed to core for a refused request", handler.capturedSelection);
  }

  // ── capShipmentLineOverrides — all lines capped to zero throws ──────────────

  /**
   * When every line override is fully consumed by an existing draft the cap method
   * throws OBException instead of returning an empty map.
   */
  @Test(expected = OBException.class)
  public void testCreateFromShipmentsAllLinesCappedToZeroThrows() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.getId()).thenReturn("sl-used");

    Order linkedOrder = mock(Order.class);
    when(linkedOrder.getId()).thenReturn("order-cap");

    ShipmentInOut shipment = mock(ShipmentInOut.class);
    when(shipment.getSalesOrder()).thenReturn(linkedOrder); // single-shipment-with-order path → cap is applied
    when(shipment.getMaterialMgmtShipmentInOutLineList())
        .thenReturn(Collections.singletonList(sl));
    when(shipment.getId()).thenReturn("ship-cap");

    // pendingQty for all overridden lines is zero → cap leaves empty map → throw
    Map<String, BigDecimal> overrides = new HashMap<>();
    overrides.put("sl-used", new BigDecimal("3"));

    CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler() {
      @Override
      protected List<ShipmentInOut> loadAndValidateShipments(List<String> ids) {
        return Collections.singletonList(shipment);
      }

      @Override
      protected Map<String, BigDecimal> computePendingQtyPerLineOrThrow(String shipmentId,
          boolean includeDrafts) {
        // The pending query omits fully-invoiced lines: nothing pending → OBException
        return Collections.emptyMap();
      }
    };

    handler.createFromShipments(Collections.singletonList("ship-cap"), overrides, null);
  }

  // ── computePendingQtyPerLine (1-arg) delegates to 2-arg ─────────────────────

  /**
   * The 1-arg overload (no includeDrafts param) must call the 2-arg variant
   * with includeDrafts=false. Verified via a subclass that captures the flag.
   */
  @Test
  public void testComputePendingQtyPerLineOneArgDelegatesToTwoArgWithFalse() {
    // Capture the includeDrafts flag passed by the 1-arg overload
    boolean[] capturedFlag = {true}; // init to true so a false write is detectable
    CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler() {
      @Override
      protected Map<String, BigDecimal> computePendingQtyPerLine(String shipmentId, boolean includeDrafts) {
        capturedFlag[0] = includeDrafts;
        return Collections.emptyMap();
      }
    };
    handler.computePendingQtyPerLine("ship-1arg");
    assertFalse("1-arg overload must pass includeDrafts=false to the 2-arg variant",
        capturedFlag[0]);
  }

  // ── computePendingQtyPerLine (2-arg, includeDrafts=true) — draft SQL branch ──

  /**
   * When includeDrafts=true the method uses a different SQL branch. The DB mock
   * yields one line with movQty=5 and invoiced=2 → pending=3.
   */
  @Test
  public void testComputePendingQtyPerLineIncludeDraftsReturnsPositivePending() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      java.sql.Connection conn = mock(java.sql.Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);

      java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-draft");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("5"));
      when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("2"));
      when(ps.executeQuery()).thenReturn(rs);

      Map<String, BigDecimal> result = new CreateDraftInvoiceHandler()
          .computePendingQtyPerLine("ship-draft", true);

      assertEquals(1, result.size());
      assertEquals(0, new BigDecimal("3").compareTo(result.get("line-draft")));
    }
  }

  /**
   * includeDrafts=true with a line fully consumed by drafts → pending=0 → omitted.
   */
  @Test
  public void testComputePendingQtyPerLineIncludeDraftsFullyConsumedLineOmitted() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      java.sql.Connection conn = mock(java.sql.Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);

      java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-consumed");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("5"));
      when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("5")); // fully invoiced
      when(ps.executeQuery()).thenReturn(rs);

      Map<String, BigDecimal> result = new CreateDraftInvoiceHandler()
          .computePendingQtyPerLine("ship-full", true);

      assertTrue("Fully consumed line must be omitted even with includeDrafts=true",
          result.isEmpty());
    }
  }

  /**
   * includeDrafts=true, DB throws → empty map returned without propagation.
   */
  @Test
  public void testComputePendingQtyPerLineIncludeDraftsDbErrorReturnsEmptyMap() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("DB down includeDrafts=true"));

      Map<String, BigDecimal> result = new CreateDraftInvoiceHandler()
          .computePendingQtyPerLine("ship-err", true);

      assertNotNull(result);
      assertTrue(result.isEmpty());
    }
  }

  // ── findLinkedInvoiceLine ─────────────────────────────────────────────────────

  /**
   * When the HQL query returns a matching invoice line, findLinkedInvoiceLine returns it.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testFindLinkedInvoiceLineReturnsMatchingLine() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      InvoiceLine il = mock(InvoiceLine.class);
      Query<InvoiceLine> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(InvoiceLine.class))).thenReturn(q);
      when(q.setParameter(anyString(), any())).thenReturn(q);
      when(q.setMaxResults(anyInt())).thenReturn(q);
      when(q.list()).thenReturn(Collections.singletonList(il));

      InvoiceLine result = new CreateDraftInvoiceHandler().findLinkedInvoiceLine("sl-linked");
      assertSame(il, result);
    }
  }

  /**
   * When no matching invoice line exists, findLinkedInvoiceLine returns null.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testFindLinkedInvoiceLineReturnsNullWhenNotFound() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<InvoiceLine> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(InvoiceLine.class))).thenReturn(q);
      when(q.setParameter(anyString(), any())).thenReturn(q);
      when(q.setMaxResults(anyInt())).thenReturn(q);
      when(q.list()).thenReturn(Collections.emptyList());

      assertNull(new CreateDraftInvoiceHandler().findLinkedInvoiceLine("sl-missing"));
    }
  }

  /**
   * When the query throws, findLinkedInvoiceLine swallows the exception and returns null.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testFindLinkedInvoiceLineReturnsNullOnException() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      when(session.createQuery(anyString(), eq(InvoiceLine.class)))
          .thenThrow(new RuntimeException("query error"));

      assertNull(new CreateDraftInvoiceHandler().findLinkedInvoiceLine("sl-err"));
    }
  }

  // ── createShipmentInvoiceLine ─────────────────────────────────────────────

  @Test
  public void testCreateShipmentInvoiceLineNoOrderLineNoLinkedInvoiceZeroPrices() {
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
      CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler() {
        @Override
        protected InvoiceLine findLinkedInvoiceLine(String id) { return null; }
      };
      InvoiceLine result = handler.createShipmentInvoiceLine(invoice, sl, new BigDecimal("3"), 10L);

      assertEquals(il, result);
      verify(il).setUnitPrice(BigDecimal.ZERO);
      verify(il).setListPrice(BigDecimal.ZERO);
      verify(il).setLineNetAmount(BigDecimal.ZERO);
    }
  }

  @Test
  public void testCreateShipmentInvoiceLineNoOrderLineFallsBackToLinkedInvoicePrices() {
    try (MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      InvoiceLine il = mock(InvoiceLine.class);
      when(provider.get(InvoiceLine.class)).thenReturn(il);

      InvoiceLine sourceIL = mock(InvoiceLine.class);
      when(sourceIL.getUnitPrice()).thenReturn(new BigDecimal("15"));
      when(sourceIL.getListPrice()).thenReturn(new BigDecimal("18"));
      when(sourceIL.getPriceLimit()).thenReturn(new BigDecimal("12"));
      when(sourceIL.getTax()).thenReturn(mock(TaxRate.class));
      when(sourceIL.getSalesOrderLine()).thenReturn(null);

      ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
      when(sl.getOrganization()).thenReturn(mock(Organization.class));
      when(sl.getProduct()).thenReturn(mock(Product.class));
      when(sl.getUOM()).thenReturn(null);
      when(sl.getSalesOrderLine()).thenReturn(null);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);

      CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler() {
        @Override
        protected InvoiceLine findLinkedInvoiceLine(String id) { return sourceIL; }
      };
      handler.createShipmentInvoiceLine(invoice, sl, new BigDecimal("4"), 10L);

      verify(il).setUnitPrice(new BigDecimal("15"));
      verify(il).setListPrice(new BigDecimal("18"));
      verify(il).setLineNetAmount(new BigDecimal("60.00"));
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

      new CreateDraftInvoiceHandler().createShipmentInvoiceLine(invoice, sl, new BigDecimal("2"), 10L);

      verify(il).setUnitPrice(new BigDecimal("10"));
      verify(il).setListPrice(new BigDecimal("12"));
      verify(il).setLineNetAmount(new BigDecimal("20.00"));
    }
  }

  // ── applyPricesFromOrderLine (via createShipmentInvoiceLine) ─────────────

  @Test
  public void testApplyPricesFromOrderLineUsesOrderListPriceWhenPositive() {
    try (MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      InvoiceLine il = mock(InvoiceLine.class);
      when(provider.get(InvoiceLine.class)).thenReturn(il);

      OrderLine ol = mock(OrderLine.class);
      when(ol.getUnitPrice()).thenReturn(new BigDecimal("20"));
      when(ol.getListPrice()).thenReturn(new BigDecimal("25"));
      when(ol.getPriceLimit()).thenReturn(new BigDecimal("18"));
      TaxRate tax = mock(TaxRate.class);
      when(ol.getTax()).thenReturn(tax);

      ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
      when(sl.getOrganization()).thenReturn(mock(Organization.class));
      when(sl.getProduct()).thenReturn(mock(Product.class));
      when(sl.getUOM()).thenReturn(null);
      when(sl.getSalesOrderLine()).thenReturn(ol);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getPriceList()).thenReturn(null);

      new CreateDraftInvoiceHandler().createShipmentInvoiceLine(invoice, sl, new BigDecimal("2"), 10L);

      verify(il).setUnitPrice(new BigDecimal("20"));
      verify(il).setListPrice(new BigDecimal("25"));
      verify(il).setLineNetAmount(new BigDecimal("40.00"));
      verify(il).setTax(tax);
      verify(il).setSalesOrderLine(ol);
    }
  }

  @Test
  public void testApplyPricesFromOrderLineNullListPriceFallsBackToUnitPrice() {
    try (MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      InvoiceLine il = mock(InvoiceLine.class);
      when(provider.get(InvoiceLine.class)).thenReturn(il);

      Product product = mock(Product.class);
      when(product.getId()).thenReturn("prod-1");

      OrderLine ol = mock(OrderLine.class);
      when(ol.getUnitPrice()).thenReturn(new BigDecimal("15"));
      when(ol.getListPrice()).thenReturn(null);
      when(ol.getPriceLimit()).thenReturn(null);
      when(ol.getTax()).thenReturn(null);
      when(ol.getProduct()).thenReturn(product);

      ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
      when(sl.getOrganization()).thenReturn(mock(Organization.class));
      when(sl.getProduct()).thenReturn(product);
      when(sl.getUOM()).thenReturn(null);
      when(sl.getSalesOrderLine()).thenReturn(ol);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getPriceList()).thenReturn(null);

      new CreateDraftInvoiceHandler().createShipmentInvoiceLine(invoice, sl, new BigDecimal("3"), 10L);

      verify(il).setListPrice(new BigDecimal("15"));
    }
  }

  // ── applyPricesFromInvoiceLine (via createShipmentInvoiceLine) ────────────

  @Test
  public void testApplyPricesFromInvoiceLineCopiesTaxAndSalesOrderLine() {
    try (MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      InvoiceLine il = mock(InvoiceLine.class);
      when(provider.get(InvoiceLine.class)).thenReturn(il);

      TaxRate tax = mock(TaxRate.class);
      OrderLine orderLine = mock(OrderLine.class);

      InvoiceLine sourceIL = mock(InvoiceLine.class);
      when(sourceIL.getUnitPrice()).thenReturn(new BigDecimal("8"));
      when(sourceIL.getListPrice()).thenReturn(new BigDecimal("10"));
      when(sourceIL.getPriceLimit()).thenReturn(new BigDecimal("6"));
      when(sourceIL.getTax()).thenReturn(tax);
      when(sourceIL.getSalesOrderLine()).thenReturn(orderLine);
      when(sourceIL.getProduct()).thenReturn(null);

      ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
      when(sl.getOrganization()).thenReturn(mock(Organization.class));
      when(sl.getProduct()).thenReturn(mock(Product.class));
      when(sl.getUOM()).thenReturn(null);
      when(sl.getSalesOrderLine()).thenReturn(null);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getPriceList()).thenReturn(null);

      CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler() {
        @Override
        protected InvoiceLine findLinkedInvoiceLine(String id) { return sourceIL; }
      };
      handler.createShipmentInvoiceLine(invoice, sl, new BigDecimal("2"), 10L);

      verify(il).setUnitPrice(new BigDecimal("8"));
      verify(il).setListPrice(new BigDecimal("10"));
      verify(il).setLineNetAmount(new BigDecimal("16.00"));
      verify(il).setTax(tax);
      verify(il).setSalesOrderLine(orderLine);
    }
  }

  @Test
  public void testApplyPricesFromInvoiceLineNullTaxAndNullOrderLineSkipsBothSetters() {
    try (MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      InvoiceLine il = mock(InvoiceLine.class);
      when(provider.get(InvoiceLine.class)).thenReturn(il);

      InvoiceLine sourceIL = mock(InvoiceLine.class);
      when(sourceIL.getUnitPrice()).thenReturn(new BigDecimal("5"));
      when(sourceIL.getListPrice()).thenReturn(new BigDecimal("7"));
      when(sourceIL.getPriceLimit()).thenReturn(null);
      when(sourceIL.getTax()).thenReturn(null);
      when(sourceIL.getSalesOrderLine()).thenReturn(null);
      when(sourceIL.getProduct()).thenReturn(null);

      ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
      when(sl.getOrganization()).thenReturn(mock(Organization.class));
      when(sl.getProduct()).thenReturn(mock(Product.class));
      when(sl.getUOM()).thenReturn(null);
      when(sl.getSalesOrderLine()).thenReturn(null);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getPriceList()).thenReturn(null);

      CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler() {
        @Override
        protected InvoiceLine findLinkedInvoiceLine(String id) { return sourceIL; }
      };
      handler.createShipmentInvoiceLine(invoice, sl, new BigDecimal("1"), 10L);

      verify(il, never()).setTax(any());
      verify(il, never()).setSalesOrderLine(any());
    }
  }

  // ── resolveListPriceFromPriceList (via createShipmentInvoiceLine) ─────────

  @Test
  public void testResolveListPriceNullProductIdReturnsFallback() {
    try (MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      InvoiceLine il = mock(InvoiceLine.class);
      when(provider.get(InvoiceLine.class)).thenReturn(il);

      OrderLine ol = mock(OrderLine.class);
      when(ol.getUnitPrice()).thenReturn(new BigDecimal("9"));
      when(ol.getListPrice()).thenReturn(BigDecimal.ZERO);
      when(ol.getPriceLimit()).thenReturn(null);
      when(ol.getTax()).thenReturn(null);
      when(ol.getProduct()).thenReturn(null);

      PriceList priceList = mock(PriceList.class);
      when(priceList.getId()).thenReturn("pl-1");
      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getPriceList()).thenReturn(priceList);

      ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
      when(sl.getOrganization()).thenReturn(mock(Organization.class));
      when(sl.getProduct()).thenReturn(mock(Product.class));
      when(sl.getUOM()).thenReturn(null);
      when(sl.getSalesOrderLine()).thenReturn(ol);

      new CreateDraftInvoiceHandler().createShipmentInvoiceLine(invoice, sl, new BigDecimal("1"), 10L);

      verify(il).setListPrice(new BigDecimal("9"));
    }
  }

  @Test
  public void testResolveListPriceNullPriceListIdReturnsFallback() {
    try (MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      InvoiceLine il = mock(InvoiceLine.class);
      when(provider.get(InvoiceLine.class)).thenReturn(il);

      Product product = mock(Product.class);
      when(product.getId()).thenReturn("prod-X");

      OrderLine ol = mock(OrderLine.class);
      when(ol.getUnitPrice()).thenReturn(new BigDecimal("12"));
      when(ol.getListPrice()).thenReturn(BigDecimal.ZERO);
      when(ol.getPriceLimit()).thenReturn(null);
      when(ol.getTax()).thenReturn(null);
      when(ol.getProduct()).thenReturn(product);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getPriceList()).thenReturn(null);

      ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
      when(sl.getOrganization()).thenReturn(mock(Organization.class));
      when(sl.getProduct()).thenReturn(product);
      when(sl.getUOM()).thenReturn(null);
      when(sl.getSalesOrderLine()).thenReturn(ol);

      new CreateDraftInvoiceHandler().createShipmentInvoiceLine(invoice, sl, new BigDecimal("1"), 10L);

      verify(il).setListPrice(new BigDecimal("12"));
    }
  }

  @Test
  public void testResolveListPriceDbReturnsZeroPriceFallsBackToUnitPrice() throws Exception {
    try (MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      InvoiceLine il = mock(InvoiceLine.class);
      when(provider.get(InvoiceLine.class)).thenReturn(il);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      java.sql.Connection conn = mock(java.sql.Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
      when(rs.next()).thenReturn(true);
      when(rs.getBigDecimal(1)).thenReturn(BigDecimal.ZERO);
      when(ps.executeQuery()).thenReturn(rs);

      Product product = mock(Product.class);
      when(product.getId()).thenReturn("prod-2");
      PriceList priceList = mock(PriceList.class);
      when(priceList.getId()).thenReturn("pl-2");

      OrderLine ol = mock(OrderLine.class);
      when(ol.getUnitPrice()).thenReturn(new BigDecimal("11"));
      when(ol.getListPrice()).thenReturn(BigDecimal.ZERO);
      when(ol.getPriceLimit()).thenReturn(null);
      when(ol.getTax()).thenReturn(null);
      when(ol.getProduct()).thenReturn(product);

      ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
      when(sl.getOrganization()).thenReturn(mock(Organization.class));
      when(sl.getProduct()).thenReturn(product);
      when(sl.getUOM()).thenReturn(null);
      when(sl.getSalesOrderLine()).thenReturn(ol);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getPriceList()).thenReturn(priceList);

      new CreateDraftInvoiceHandler().createShipmentInvoiceLine(invoice, sl, new BigDecimal("1"), 10L);

      verify(il).setListPrice(new BigDecimal("11"));
    }
  }

  @Test
  public void testResolveListPriceDbReturnsPositivePriceUsesIt() throws Exception {
    try (MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      InvoiceLine il = mock(InvoiceLine.class);
      when(provider.get(InvoiceLine.class)).thenReturn(il);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      java.sql.Connection conn = mock(java.sql.Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
      when(rs.next()).thenReturn(true);
      when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("30"));
      when(ps.executeQuery()).thenReturn(rs);

      Product product = mock(Product.class);
      when(product.getId()).thenReturn("prod-3");
      PriceList priceList = mock(PriceList.class);
      when(priceList.getId()).thenReturn("pl-3");

      OrderLine ol = mock(OrderLine.class);
      when(ol.getUnitPrice()).thenReturn(new BigDecimal("20"));
      when(ol.getListPrice()).thenReturn(BigDecimal.ZERO);
      when(ol.getPriceLimit()).thenReturn(null);
      when(ol.getTax()).thenReturn(null);
      when(ol.getProduct()).thenReturn(product);

      ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
      when(sl.getOrganization()).thenReturn(mock(Organization.class));
      when(sl.getProduct()).thenReturn(product);
      when(sl.getUOM()).thenReturn(null);
      when(sl.getSalesOrderLine()).thenReturn(ol);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getPriceList()).thenReturn(priceList);

      new CreateDraftInvoiceHandler().createShipmentInvoiceLine(invoice, sl, new BigDecimal("1"), 10L);

      verify(il).setListPrice(new BigDecimal("30"));
    }
  }

  @Test
  public void testResolveListPriceDbExceptionFallsBackToUnitPrice() throws Exception {
    try (MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      InvoiceLine il = mock(InvoiceLine.class);
      when(provider.get(InvoiceLine.class)).thenReturn(il);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      java.sql.Connection conn = mock(java.sql.Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenThrow(new java.sql.SQLException("db down"));

      Product product = mock(Product.class);
      when(product.getId()).thenReturn("prod-4");
      PriceList priceList = mock(PriceList.class);
      when(priceList.getId()).thenReturn("pl-4");

      OrderLine ol = mock(OrderLine.class);
      when(ol.getUnitPrice()).thenReturn(new BigDecimal("18"));
      when(ol.getListPrice()).thenReturn(BigDecimal.ZERO);
      when(ol.getPriceLimit()).thenReturn(null);
      when(ol.getTax()).thenReturn(null);
      when(ol.getProduct()).thenReturn(product);

      ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
      when(sl.getOrganization()).thenReturn(mock(Organization.class));
      when(sl.getProduct()).thenReturn(product);
      when(sl.getUOM()).thenReturn(null);
      when(sl.getSalesOrderLine()).thenReturn(ol);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getPriceList()).thenReturn(priceList);

      new CreateDraftInvoiceHandler().createShipmentInvoiceLine(invoice, sl, new BigDecimal("1"), 10L);

      verify(il).setListPrice(new BigDecimal("18"));
    }
  }

  // ── addShipmentLinesToInvoice — throws when all lines produce null qty ────────

  /**
   * When no shipment line produces a qualifying quantity (all return null from
   * resolveShipmentLineQty) addShipmentLinesToInvoice throws OBException.
   */
  @Test(expected = OBException.class)
  public void testAddShipmentLinesToInvoiceThrowsWhenNoQualifyingLines() {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.getId()).thenReturn("sl-none");

    ShipmentInOut shipment = mock(ShipmentInOut.class);
    when(shipment.getMaterialMgmtShipmentInOutLineList())
        .thenReturn(Collections.singletonList(sl));
    when(shipment.getId()).thenReturn("ship-none");

    AddShipmentLinesHandler handler = new AddShipmentLinesHandler();
    // qtyByShipmentLineId intentionally empty → resolveShipmentLineQty returns null for sl-none

    handler.addShipmentLinesToInvoice(
        mock(Invoice.class),
        Collections.singletonList(shipment),
        Collections.emptyMap());
  }

  // ── source order must be completed (ETP-5709) ──────────────────────────────

  @Test
  public void handleCreate_salesOrderNotCompleted_returns400AndCreatesNothing() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      mockAdminMode(obContextMock);
      DispatchHandler handler = new DispatchHandler();
      handler.sourceOrderStatus = "DR";
      handler.orderInvoice = mock(Invoice.class);

      NeoResponse response = handler.handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER).entityName(ENTITY_HEADER).httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION).fieldName(ACTION_CREATE)
          .recordId("order-draft").build());

      assertEquals(400, response.getHttpStatus());
      assertTrue(response.getBody().toString().contains("order-draft is DR"));
      assertNull("nothing may be created", handler.receivedLineOverrides);
    }
  }

  @Test
  public void requireOrderCompleted_realCheck_namesNumberAndStatus() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mock(Order.class);
      when(order.getDocumentNo()).thenReturn("SO-5");
      when(order.getDocumentStatus()).thenReturn("DR");
      when(dal.get(Order.class, "o-1")).thenReturn(order);

      try {
        new TestableHandler().requireOrderCompleted("o-1");
        org.junit.Assert.fail("a draft order must be refused");
      } catch (OBException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("SO-5") && e.getMessage().contains("DR"));
      }
      when(order.getDocumentStatus()).thenReturn("CO");
      new TestableHandler().requireOrderCompleted("o-1");
    }
  }

  @Test
  public void handleCreate_realCheck_closedSalesOrderReturns400NamingNumberAndClAndCreatesNothing()
      throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mock(Order.class);
      when(order.getDocumentNo()).thenReturn("SO-CL-12");
      when(order.getDocumentStatus()).thenReturn("CL");
      when(dal.get(Order.class, "order-closed")).thenReturn(order);

      NeoResponse response = new TestableHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER).entityName(ENTITY_HEADER).httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION).fieldName(ACTION_CREATE)
          .recordId("order-closed").build());

      assertEquals(400, response.getHttpStatus());
      String body = response.getBody().toString();
      assertTrue(body, body.contains("SO-CL-12") && body.contains("estado CL"));
      Mockito.verify(dal, Mockito.never()).save(Mockito.any());
      Mockito.verify(order, Mockito.never()).getOrderLineList();
    }
  }

  @Test
  public void handleCreate_salesQuotation_isNotSubjectToTheCompletedCheck() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-q");
      DispatchHandler handler = new DispatchHandler();
      handler.sourceOrderStatus = "UE"; // a quotation is never CO; the order check must not run
      handler.orderInvoice = invoice;

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        mockCompletedInvoice(dal, "inv-q", "INV-Q", "CO");
        NeoResponse response = handler.handle(NeoContext.builder()
            .specName("sales-quotation").entityName("quotation").httpMethod("POST")
            .endpointType(NeoEndpointType.ACTION).fieldName(ACTION_CREATE)
            .recordId("quote-1").build());

        assertEquals(201, response.getHttpStatus());
        assertEquals("quote-1", handler.markedQuotationId);
      }
    }
  }

  /**
   * ETP-5709 review W4: the REAL check, end to end through {@code handle -> handleCreate} (no seam
   * stub): a DRAFT sales order answers 400 naming number and status, and nothing is created.
   */
  @Test
  public void handleCreate_realCheck_draftSalesOrderReturns400AndCreatesNothing() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      mockAdminMode(obContextMock);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mock(Order.class);
      when(order.getDocumentNo()).thenReturn("SO-12");
      when(order.getDocumentStatus()).thenReturn("DR");
      when(dal.get(Order.class, "order-real")).thenReturn(order);

      NeoResponse response = new TestableHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER).entityName(ENTITY_HEADER).httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION).fieldName(ACTION_CREATE)
          .recordId("order-real").build());

      assertEquals(400, response.getHttpStatus());
      String body = response.getBody().toString();
      assertTrue(body, body.contains("SO-12") && body.contains("DR"));
      Mockito.verify(dal, Mockito.never()).save(Mockito.any());
      Mockito.verify(order, Mockito.never()).getOrderLineList();
    }
  }

  /**
   * ETP-5709: a quotation is invoiced only from "Under Evaluation" (UE), the one status the SPA
   * offers the action from. Draft, rejected, already-invoiced and order-converted quotations answer
   * 400 naming number and status, and nothing is created.
   */
  @Test
  public void handleCreate_realCheck_quotationNotUnderEvaluationReturns400AndCreatesNothing()
      throws Exception {
    for (String status : new String[] {"DR", "RJ", "CJ", "ETGO_CI", "CA", "VO"}) {
      try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
          MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
        mockAdminMode(obContextMock);
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        Order quotation = mock(Order.class);
        when(quotation.getDocumentNo()).thenReturn("QT-7");
        when(quotation.getDocumentStatus()).thenReturn(status);
        when(dal.get(Order.class, "quote-real")).thenReturn(quotation);

        NeoResponse response = new TestableHandler().handle(NeoContext.builder()
            .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER).httpMethod("POST")
            .endpointType(NeoEndpointType.ACTION).fieldName(ACTION_CREATE)
            .recordId("quote-real").build());

        assertEquals(status, 400, response.getHttpStatus());
        String body = response.getBody().toString();
        assertTrue(body, body.contains("QT-7") && body.contains(status));
        Mockito.verify(dal, Mockito.never()).save(Mockito.any());
        Mockito.verify(quotation, Mockito.never()).getOrderLineList();
      }
    }
  }

  /**
   * ETP-5709: a quotation is invoiced in full. A body with {@code lines} is refused with 400 on
   * every channel (REST reaches the handler without the MCP contract check) and nothing is created.
   */
  @Test
  public void handleCreate_quotationWithLines_returns400AndCreatesNothing() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      mockAdminMode(obContextMock);
      DispatchHandler handler = new DispatchHandler();
      handler.sourceOrderStatus = "UE";
      handler.orderInvoice = mock(Invoice.class);

      NeoResponse response = handler.handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER).httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION).fieldName(ACTION_CREATE)
          .requestBody(new JSONObject().put("lines", new JSONArray()
              .put(new JSONObject().put("orderLineId", "L1").put("quantity", "1"))))
          .recordId("quote-lines").build());

      assertEquals(400, response.getHttpStatus());
      assertTrue(response.getBody().toString(), response.getBody().toString().contains("lines"));
      assertNull("nothing may be created", handler.receivedLineOverrides);
      assertNull("the quotation must not be closed", handler.markedQuotationId);
    }
  }

  /**
   * ETP-5709: the quotation status guard mirrors where the SPA offers the action
   * (QuotationTopbarActions opens QuotationConfirmModal for CO or UE): both are accepted; draft,
   * rejected, order-converted, already-invoiced and voided quotations are refused.
   */
  @Test
  public void requireQuotationInvoiceable_realCheck_acceptsUeAndCoRefusesTheRest() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order quotation = mock(Order.class);
      when(quotation.getDocumentNo()).thenReturn("QT-9");
      when(dal.get(Order.class, "q-1")).thenReturn(quotation);

      for (String ok : new String[] {"UE", "CO"}) {
        when(quotation.getDocumentStatus()).thenReturn(ok);
        new TestableHandler().requireQuotationInvoiceable("q-1");
      }
      for (String refused : new String[] {"DR", "RJ", "CJ", "CA", "ETGO_CI", "VO", null}) {
        when(quotation.getDocumentStatus()).thenReturn(refused);
        try {
          new TestableHandler().requireQuotationInvoiceable("q-1");
          org.junit.Assert.fail("status " + refused + " must be refused");
        } catch (OBException e) {
          assertTrue(e.getMessage(), e.getMessage().contains("QT-9")
              && e.getMessage().contains(String.valueOf(refused)));
        }
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ETP-5709 — explicit `lines` are validated, never capped (order / quotation routes)
  // ═══════════════════════════════════════════════════════════════════════════

  /** Records the 4-arg createFromOrder the 3-arg entry delegates to, without building anything. */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class DelegationHandler extends CreateDraftInvoiceHandler {
    String receivedOrderId;
    Map<String, BigDecimal> receivedOrderOverrides;
    int createFromOrderCalls;
    /** Order line ids that have completed, not-yet-invoiced shipment lines (the DB seam). */
    java.util.Set<String> orderLinesWithUninvoicedShipments = new java.util.HashSet<>();

    @Override
    protected boolean hasUninvoicedShipmentLines(OrderLine orderLine) {
      return orderLinesWithUninvoicedShipments.contains(orderLine.getId());
    }

    @Override
    protected Invoice createFromOrder(String orderId, Map<String, BigDecimal> lineOverrides,
        String priceListId, String currencyId) {
      createFromOrderCalls++;
      receivedOrderId = orderId;
      receivedOrderOverrides = lineOverrides;
      return mock(Invoice.class);
    }
  }

  private static Order orderWith(OrderLine... lines) {
    Order order = mock(Order.class);
    when(order.getOrderLineList()).thenReturn(Arrays.asList(lines));
    return order;
  }

  @Test
  public void testCreateFromOrderExplicitQuantityAbovePendingAnswers400NamingLineAndPending() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = orderWith(pendingOrderLine("ol-1", "10", "3"));
      when(dal.get(eq(Order.class), eq("so-1"))).thenReturn(order);
      DelegationHandler handler = new DelegationHandler();

      try {
        handler.createFromOrder("so-1", Collections.singletonMap("ol-1", new BigDecimal("8")), null);
        fail("8 requested but only 7 pending must be refused, not capped");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("ol-1"));
        assertTrue(expected.getMessage(), expected.getMessage().contains("solicitada 8"));
        assertTrue(expected.getMessage(), expected.getMessage().contains("pendiente de facturar es 7"));
      }
      assertEquals("nothing may be built for a refused request", 0, handler.createFromOrderCalls);
    }
  }

  @Test
  public void testCreateFromOrderExplicitPartialQuantityWithinPendingIsForwardedAsSent() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = orderWith(pendingOrderLine("ol-1", "10", "3"));
      when(dal.get(eq(Order.class), eq("so-1"))).thenReturn(order);
      DelegationHandler handler = new DelegationHandler();
      Map<String, BigDecimal> overrides = Collections.singletonMap("ol-1", new BigDecimal("4"));

      handler.createFromOrder("so-1", overrides, null);

      assertEquals(overrides, handler.receivedOrderOverrides);
    }
  }

  @Test
  public void testCreateFromOrderExplicitQuantityOnOverInvoicedLineAnswers400() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = orderWith(pendingOrderLine("ol-1", "2", "4"));
      when(dal.get(eq(Order.class), eq("so-1"))).thenReturn(order);
      DelegationHandler handler = new DelegationHandler();

      try {
        handler.createFromOrder("so-1", Collections.singletonMap("ol-1", BigDecimal.ONE), null);
        fail("a line with nothing pending must be refused");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("pendiente de facturar es 0"));
      }
      assertEquals(0, handler.createFromOrderCalls);
    }
  }

  /** A positive quantity against a negative (return-style) pending line has the wrong sign. */
  @Test
  public void testCreateFromOrderExplicitQuantityAgainstNegativeLineSignAnswers400() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = orderWith(pendingOrderLine("ol-1", "-3", "0"));
      when(dal.get(eq(Order.class), eq("so-1"))).thenReturn(order);
      DelegationHandler handler = new DelegationHandler();

      try {
        handler.createFromOrder("so-1", Collections.singletonMap("ol-1", new BigDecimal("2")), null);
        fail("a positive quantity on a -3 pending line must be refused");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("pendiente de facturar es -3"));
      }
    }
  }

  /**
   * ETP-5709: core replaces an order-line entry that has completed, not-yet-invoiced shipment lines
   * by ALL of those shipment lines at their full quantity, ignoring the quantity asked for. We do
   * not allocate over shipments: the request is refused, naming the line, before anything is built.
   */
  @Test
  public void testCreateFromOrderExplicitLinesOnLineWithUninvoicedShipmentsAnswers400() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = orderWith(pendingOrderLine("ol-1", "10", "0"));
      when(dal.get(eq(Order.class), eq("so-1"))).thenReturn(order);
      DelegationHandler handler = new DelegationHandler();
      handler.orderLinesWithUninvoicedShipments.add("ol-1");

      try {
        handler.createFromOrder("so-1", Collections.singletonMap("ol-1", new BigDecimal("4")), null);
        fail("core would ignore the 4 and invoice the shipped quantity: must be refused");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("ol-1"));
        assertTrue(expected.getMessage(), expected.getMessage().contains("albaranes sin facturar"));
        assertTrue(expected.getMessage(), expected.getMessage().contains("facture desde el albarán"));
      }
      assertEquals("nothing may be built for a refused request", 0, handler.createFromOrderCalls);
    }
  }

  /** Order lines with NO shipment lines keep honouring a partial quantity (core uses the entry as is). */
  @Test
  public void testCreateFromOrderExplicitPartialOnLineWithoutShipmentsIsStillForwarded() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = orderWith(pendingOrderLine("ol-1", "10", "0"), pendingOrderLine("ol-2", "5", "0"));
      when(dal.get(eq(Order.class), eq("so-1"))).thenReturn(order);
      DelegationHandler handler = new DelegationHandler();
      handler.orderLinesWithUninvoicedShipments.add("ol-2"); // shipped, but not requested

      Map<String, BigDecimal> overrides = Collections.singletonMap("ol-1", new BigDecimal("4"));
      handler.createFromOrder("so-1", overrides, null);

      assertEquals(overrides, handler.receivedOrderOverrides);
    }
  }

  @Test
  public void testCreateFromOrderWithoutExplicitLinesDelegatesWithoutTouchingTheOrderTwice() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      DelegationHandler handler = new DelegationHandler();

      handler.createFromOrder("so-1", Collections.emptyMap(), null);

      assertEquals(1, handler.createFromOrderCalls);
      verify(dal, never()).get(eq(Order.class), anyString());
    }
  }

  /**
   * ETP-5709 — a {@code lines} array whose entries all fail to parse (zero quantity) used to leave
   * an empty override map, which means "no lines requested": the whole order was invoiced.
   */
  @Test
  public void testHandleCreateLinesThatParseToNothingAnswers400InsteadOfInvoicingEverything()
      throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      mockAdminMode(obContextMock);
      DispatchHandler handler = new DispatchHandler();
      handler.orderInvoice = mock(Invoice.class);
      JSONObject body = new JSONObject().put("lines", new JSONArray()
          .put(new JSONObject().put("orderLineId", "ol-1").put("quantity", "0")));

      NeoResponse response = handler.handle(NeoContext.builder()
          .specName(SPEC_SALES_ORDER).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE).recordId("so-1").requestBody(body).build());

      assertNotNull(response);
      assertEquals(400, response.getHttpStatus());
      assertNull("createFromOrder must not run", handler.receivedLineOverrides);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ETP-5709 — invoice from shipment(s): pending-scoped, DB errors surface
  // ═══════════════════════════════════════════════════════════════════════════

  private static ShipmentInOutLine shipmentLine(String id, String movementQty, String orderLineId) {
    ShipmentInOutLine sl = mock(ShipmentInOutLine.class);
    when(sl.getId()).thenReturn(id);
    when(sl.isActive()).thenReturn(true);
    when(sl.getLineNo()).thenReturn(10L);
    when(sl.getMovementQuantity()).thenReturn(new BigDecimal(movementQty));
    if (orderLineId != null) {
      OrderLine ol = mock(OrderLine.class);
      when(ol.getId()).thenReturn(orderLineId);
      when(sl.getSalesOrderLine()).thenReturn(ol);
    }
    return sl;
  }

  private static ShipmentInOut shipmentOf(String id, boolean withOrder, ShipmentInOutLine... lines) {
    ShipmentInOut ship = mock(ShipmentInOut.class);
    when(ship.getId()).thenReturn(id);
    when(ship.getDocumentNo()).thenReturn("SH-" + id);
    when(ship.getMaterialMgmtShipmentInOutLineList()).thenReturn(Arrays.asList(lines));
    if (withOrder) {
      Order order = mock(Order.class);
      when(order.getId()).thenReturn("so-1");
      when(ship.getSalesOrder()).thenReturn(order);
    }
    return ship;
  }

  private static void pendingFor(MockedStatic<NeoInvoiceSupport> supportMock, String shipmentId,
      Map<String, BigDecimal> pending) {
    supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(eq(shipmentId), eq(true)))
        .thenReturn(pending);
  }

  /** Single-shipment route: records the selectedLines the order-based creation is handed. */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class SingleShipmentHandler extends CreateDraftInvoiceHandler {
    List<ShipmentInOut> shipments;
    JSONArray receivedSelectedLines;
    String receivedOrderId;
    String receivedPriceListId;
    int createFromOrderCalls;
    int evictions;

    @Override
    protected List<ShipmentInOut> loadAndValidateShipments(List<String> shipmentIds) {
      return shipments;
    }

    @Override
    protected void evictShipmentFromSession(ShipmentInOut shipment) {
      evictions++; // the real one needs a Hibernate session
    }

    @Override
    protected Invoice createFromOrderLines(String orderId, JSONArray selectedLines,
        String priceListId, String currencyId) {
      createFromOrderCalls++;
      receivedOrderId = orderId;
      receivedSelectedLines = selectedLines;
      receivedPriceListId = priceListId;
      return mock(Invoice.class);
    }
  }

  @Test
  public void testSingleShipmentExplicitQuantityAbovePendingAnswers400NamingLine() {
    try (MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      pendingFor(supportMock, "ship-1", Collections.singletonMap("sl-1", new BigDecimal("4")));
      SingleShipmentHandler handler = new SingleShipmentHandler();
      handler.shipments = Collections.singletonList(shipmentOf("ship-1", true,
          shipmentLine("sl-1", "10", "ol-1")));

      try {
        handler.createFromShipments(Collections.singletonList("ship-1"),
            Collections.singletonMap("sl-1", new BigDecimal("5")), null);
        fail("5 requested but only 4 pending must be refused, not capped");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("SH-ship-1"));
        assertTrue(expected.getMessage(), expected.getMessage().contains("solicitada 5"));
        assertTrue(expected.getMessage(), expected.getMessage().contains("pendiente de facturar es 4"));
      }
      assertEquals(0, handler.createFromOrderCalls);
    }
  }

  @Test
  public void testSingleShipmentExplicitPartialQuantityEmitsEntryBoundToTheShipmentLine()
      throws Exception {
    try (MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      pendingFor(supportMock, "ship-1", Collections.singletonMap("sl-1", new BigDecimal("4")));
      SingleShipmentHandler handler = new SingleShipmentHandler();
      handler.shipments = Collections.singletonList(shipmentOf("ship-1", true,
          shipmentLine("sl-1", "4", "ol-1")));

      handler.createFromShipments(Collections.singletonList("ship-1"),
          Collections.singletonMap("sl-1", new BigDecimal("2")), null);

      assertEquals(1, handler.receivedSelectedLines.length());
      JSONObject entry = handler.receivedSelectedLines.getJSONObject(0);
      assertEquals("ol-1", entry.getString("id"));
      assertEquals("2", entry.getString("orderedQuantity"));
      assertEquals("without shipmentInOutLine core ignores the quantity and invoices the shipment",
          "sl-1", entry.getString("shipmentInOutLine"));
    }
  }

  /** An explicit selection of a line that has no order line used to widen into the whole order. */
  @Test
  public void testSingleShipmentExplicitLineWithoutOrderLineAnswers400InsteadOfWholeOrder() {
    try (MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      pendingFor(supportMock, "ship-1", Collections.singletonMap("sl-1", new BigDecimal("4")));
      SingleShipmentHandler handler = new SingleShipmentHandler();
      handler.shipments = Collections.singletonList(shipmentOf("ship-1", true,
          shipmentLine("sl-1", "4", null)));

      try {
        handler.createFromShipments(Collections.singletonList("ship-1"),
            Collections.singletonMap("sl-1", new BigDecimal("2")), null);
        fail("nothing selectable must be a 400, not 'invoice the order'");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("No hay líneas pendientes"));
      }
      assertEquals(0, handler.createFromOrderCalls);
    }
  }

  /**
   * Explicit lines + a failed pending query: the error surfaces. The swallowing variant returned
   * an empty map, which every line then "capped" to zero, answering a misleading
   * "no pending lines" 400 for what is an infrastructure failure.
   */
  @Test
  public void testSingleShipmentExplicitLinesDbErrorSurfacesAsTheRealError() {
    try (MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(anyString(), eq(true)))
          .thenThrow(new OBException("Could not determine pending quantities to invoice"));
      SingleShipmentHandler handler = new SingleShipmentHandler();
      handler.shipments = Collections.singletonList(shipmentOf("ship-1", true,
          shipmentLine("sl-1", "4", "ol-1")));

      try {
        handler.createFromShipments(Collections.singletonList("ship-1"),
            Collections.singletonMap("sl-1", new BigDecimal("2")), null);
        fail("a failed pending query must surface as such");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("Could not determine"));
      }
      assertEquals(0, handler.createFromOrderCalls);
    }
  }

  /**
   * Pins the live-verified behaviour (sales order of 5, shipment lowered to 3 and completed,
   * invoice from the shipment with body {@code {priceListId}} gave 3 units) on what is emitted now:
   * ONE entry of 3 bound to the shipment line, built from the shipment's own pending quantity,
   * with the price list still forwarded to the order-based creation.
   */
  @Test
  public void testSingleShipmentWithoutLinesEmitsThePendingQuantityBoundToTheShipmentLine()
      throws Exception {
    try (MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      pendingFor(supportMock, "ship-1", Collections.singletonMap("sl-1", new BigDecimal("3")));
      SingleShipmentHandler handler = new SingleShipmentHandler();
      handler.shipments = Collections.singletonList(shipmentOf("ship-1", true,
          shipmentLine("sl-1", "3", "ol-1")));

      handler.createFromShipments(Collections.singletonList("ship-1"), Collections.emptyMap(),
          "pl-1");

      assertEquals(1, handler.createFromOrderCalls);
      assertEquals("the shipment is detached before the process links lines to it", 1,
          handler.evictions);
      assertEquals("so-1", handler.receivedOrderId);
      assertEquals("pl-1", handler.receivedPriceListId);
      assertEquals(1, handler.receivedSelectedLines.length());
      JSONObject entry = handler.receivedSelectedLines.getJSONObject(0);
      assertEquals("ol-1", entry.getString("id"));
      assertEquals("3", entry.getString("orderedQuantity"));
      assertEquals("sl-1", entry.getString("shipmentInOutLine"));
    }
  }

  /**
   * Reviewer B1: a second call on an already-invoiced shipment used to reach the order with an
   * empty selection, which invoiced the order line's remaining (never shipped) quantity. Nothing
   * pending on the shipment is now a 400 before anything is created.
   */
  @Test
  public void testSingleShipmentAlreadyInvoicedAnswers400AndNeverReachesTheOrder() {
    try (MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      pendingFor(supportMock, "ship-1", Collections.emptyMap());
      SingleShipmentHandler handler = new SingleShipmentHandler();
      handler.shipments = Collections.singletonList(shipmentOf("ship-1", true,
          shipmentLine("sl-1", "3", "ol-1")));

      try {
        handler.createFromShipments(Collections.singletonList("ship-1"), Collections.emptyMap(), null);
        fail("an already-invoiced shipment must not invoice the order's remaining quantity");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("No hay líneas pendientes"));
      }
      assertEquals(0, handler.createFromOrderCalls);
    }
  }

  /** Order shipped in two deliveries: invoicing A must carry A's lines only, never B's. */
  @Test
  public void testSingleShipmentOfAnOrderWithTwoShipmentsOnlyInvoicesItsOwnLines() throws Exception {
    try (MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      pendingFor(supportMock, "ship-a", Collections.singletonMap("sl-a", new BigDecimal("2")));
      SingleShipmentHandler handler = new SingleShipmentHandler();
      handler.shipments = Collections.singletonList(shipmentOf("ship-a", true,
          shipmentLine("sl-a", "2", "ol-1")));

      handler.createFromShipments(Collections.singletonList("ship-a"), Collections.emptyMap(), null);

      assertEquals(1, handler.receivedSelectedLines.length());
      assertEquals("sl-a",
          handler.receivedSelectedLines.getJSONObject(0).getString("shipmentInOutLine"));
      assertEquals("2", handler.receivedSelectedLines.getJSONObject(0).getString("orderedQuantity"));
    }
  }

  /** ETP-4722 regression: a negative shipment line keeps its sign (the pending map is magnitude-only). */
  @Test
  public void testSingleShipmentNegativeLineKeepsItsSign() throws Exception {
    try (MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      pendingFor(supportMock, "ship-1", Collections.singletonMap("sl-1", new BigDecimal("3")));
      SingleShipmentHandler handler = new SingleShipmentHandler();
      handler.shipments = Collections.singletonList(shipmentOf("ship-1", true,
          shipmentLine("sl-1", "-3", "ol-1")));

      handler.createFromShipments(Collections.singletonList("ship-1"), Collections.emptyMap(), null);
      assertEquals("-3", handler.receivedSelectedLines.getJSONObject(0).getString("orderedQuantity"));

      handler.createFromShipments(Collections.singletonList("ship-1"),
          Collections.singletonMap("sl-1", new BigDecimal("-1")), null);
      assertEquals("-1", handler.receivedSelectedLines.getJSONObject(0).getString("orderedQuantity"));
    }
  }

  /**
   * ETP-5709: the classic Invoicefromshipment button (redirected here for agents) checked the
   * status; this path did not, so a DRAFT shipment could be invoiced. Refused before anything is
   * created, naming the document and its status.
   */
  @Test
  public void testLoadAndValidateShipmentsRefusesAShipmentThatIsNotCompleted() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut draft = mock(ShipmentInOut.class);
      when(draft.getDocumentNo()).thenReturn("SH-9");
      when(draft.getDocumentStatus()).thenReturn("DR");
      when(dal.get(eq(ShipmentInOut.class), eq("ship-9"))).thenReturn(draft);

      try {
        new TestableHandler().loadAndValidateShipments(Collections.singletonList("ship-9"));
        fail("a draft shipment must not be invoiced");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("SH-9")
            && expected.getMessage().contains("DR"));
      }
      when(draft.getDocumentStatus()).thenReturn("CO");
      assertEquals(1, new TestableHandler()
          .loadAndValidateShipments(Collections.singletonList("ship-9")).size());
    }
  }

  /** Multi-shipment route (two shipments): records the header and the lines handed to the process. */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class MultiShipmentHandler extends CreateDraftInvoiceHandler {
    List<ShipmentInOut> shipments;
    Invoice header;
    int headerCalls;
    JSONArray capturedLines;

    @Override
    protected List<ShipmentInOut> loadAndValidateShipments(List<String> shipmentIds) {
      return shipments;
    }

    @Override
    protected Invoice createInvoiceHeaderFromShipment(ShipmentInOut first, List<ShipmentInOut> all) {
      headerCalls++;
      return header;
    }

    @Override
    protected void createInvoiceLinesFromShipmentLines(JSONArray selectedLines, Invoice invoice) {
      capturedLines = selectedLines;
    }
  }

  /** Two shipments (no linked order): SH-ship-1 has sl-1 (10 units), SH-ship-2 has sl-2 (3 units). */
  private static MultiShipmentHandler multiShipmentHandler() {
    MultiShipmentHandler handler = new MultiShipmentHandler();
    handler.shipments = Arrays.asList(
        shipmentOf("ship-1", false, shipmentLine("sl-1", "10", null)),
        shipmentOf("ship-2", false, shipmentLine("sl-2", "3", null)));
    handler.header = mock(Invoice.class);
    when(handler.header.getPriceList()).thenReturn(mock(PriceList.class));
    return handler;
  }

  private static List<String> twoShipmentIds() {
    return Arrays.asList("ship-1", "ship-2");
  }

  /**
   * ETP-5709 (item 2): the pending map omits fully-invoiced lines and an absent line used to mean
   * "fully pending", so a multi-shipment invoice re-invoiced the full quantity of every line that
   * was already invoiced. Only the still-pending line may reach the invoice, at its pending qty.
   */
  @Test
  public void testMultiShipmentSkipsAlreadyInvoicedLineAndInvoicesPendingOneAtPendingQuantity()
      throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      pendingFor(supportMock, "ship-1", Collections.emptyMap());
      pendingFor(supportMock, "ship-2", Collections.singletonMap("sl-2", new BigDecimal("2")));
      MultiShipmentHandler handler = multiShipmentHandler();

      handler.createFromShipments(twoShipmentIds(), Collections.emptyMap(), null);

      assertEquals("the already-invoiced sl-1 must not come back", 1, handler.capturedLines.length());
      assertEquals("sl-2", handler.capturedLines.getJSONObject(0).getString("id"));
      assertEquals("2", handler.capturedLines.getJSONObject(0).getString("orderedQuantity"));
    }
  }

  /** ETP-5709 (item 2): every line invoiced -> 400 and nothing is saved. */
  @Test
  public void testMultiShipmentWithNothingPendingAnswers400BeforeSavingAnything() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      pendingFor(supportMock, "ship-1", Collections.emptyMap());
      pendingFor(supportMock, "ship-2", Collections.emptyMap());
      MultiShipmentHandler handler = multiShipmentHandler();

      try {
        handler.createFromShipments(twoShipmentIds(), Collections.emptyMap(), null);
        fail("fully-invoiced shipments must not produce a duplicate invoice");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("No hay líneas pendientes"));
      }
      verify(dal, never()).save(any(Invoice.class));
      assertNull(handler.capturedLines);
    }
  }

  /**
   * ETP-5709 (item 2): the swallowing variant returned an empty map on a DB error, which read as
   * "no pending info" and invoiced everything in full. A failed query must now surface.
   */
  @Test
  public void testMultiShipmentDbErrorSurfacesInsteadOfInvoicingEverything() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(anyString(), eq(true)))
          .thenThrow(new OBException("Could not determine pending quantities to invoice"));
      MultiShipmentHandler handler = multiShipmentHandler();

      try {
        handler.createFromShipments(twoShipmentIds(), Collections.emptyMap(), null);
        fail("a failed pending query must not read as 'invoice everything'");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("Could not determine"));
      }
      verify(dal, never()).save(any(Invoice.class));
      assertNull(handler.capturedLines);
    }
  }

  /** ETP-5709 (item 5): explicit quantity above pending on the multi route: 400, nothing saved. */
  @Test
  public void testMultiShipmentExplicitQuantityAbovePendingAnswers400BeforeSavingAnything() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      pendingFor(supportMock, "ship-1", Collections.singletonMap("sl-1", new BigDecimal("7")));
      pendingFor(supportMock, "ship-2", Collections.singletonMap("sl-2", new BigDecimal("3")));
      MultiShipmentHandler handler = multiShipmentHandler();

      try {
        handler.createFromShipments(twoShipmentIds(),
            Collections.singletonMap("sl-1", new BigDecimal("8")), null);
        fail("8 requested but only 7 pending must be refused, not capped");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("solicitada 8"));
        assertTrue(expected.getMessage(), expected.getMessage().contains("pendiente de facturar es 7"));
      }
      verify(dal, never()).save(any(Invoice.class));
    }
  }

  /** ETP-5709 (item 5): a partial explicit quantity within pending is invoiced exactly as sent. */
  @Test
  public void testMultiShipmentExplicitPartialQuantityWithinPendingIsInvoicedAsSent()
      throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      pendingFor(supportMock, "ship-1", Collections.singletonMap("sl-1", new BigDecimal("7")));
      pendingFor(supportMock, "ship-2", Collections.singletonMap("sl-2", new BigDecimal("3")));
      MultiShipmentHandler handler = multiShipmentHandler();

      handler.createFromShipments(twoShipmentIds(),
          Collections.singletonMap("sl-1", new BigDecimal("4")), null);

      assertEquals(1, handler.capturedLines.length());
      assertEquals("4", handler.capturedLines.getJSONObject(0).getString("orderedQuantity"));
    }
  }
}
