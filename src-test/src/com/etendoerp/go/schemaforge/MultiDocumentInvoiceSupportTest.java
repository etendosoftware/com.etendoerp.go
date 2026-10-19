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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * Unit tests for the ETP-5410 additions to {@link MultiDocumentInvoiceSupport}:
 * {@link MultiDocumentInvoiceSupport#resolveEffectivePriceListId}'s three-tier resolution
 * (order &gt; Business Partner &gt; client default) and the guard clauses of
 * {@link MultiDocumentInvoiceSupport#resolveProductPrices}, plus the ETP-5709 pending-quantity
 * rules: a non-null pending map is authoritative, explicit quantities are validated (never capped)
 * and a {@code lines} array that parses to nothing is refused.
 *
 * @covers com.etendoerp.go.schemaforge.MultiDocumentInvoiceSupport
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiDocumentInvoiceSupportTest {

  @Mock
  private ShipmentInOut doc;
  @Mock
  private Order order;
  @Mock
  private BusinessPartner businessPartner;
  @Mock
  private PriceList orderPriceList;
  @Mock
  private PriceList bpPriceList;
  @Mock
  private PriceList defaultPriceList;
  @Mock
  private OBDal dal;
  @Mock
  private OBCriteria<PriceList> criteria;

  // ── resolveProductPrices guard clauses ──────────────────────────────────

  @Test
  void testResolveProductPricesReturnsEmptyMapWhenPriceListBlank() {
    Map<String, BigDecimal> prices = MultiDocumentInvoiceSupport.resolveProductPrices(" ",
        List.of("prod-1"));
    assertTrue(prices.isEmpty());
  }

  @Test
  void testResolveProductPricesReturnsEmptyMapWhenProductIdsEmpty() {
    Map<String, BigDecimal> prices = MultiDocumentInvoiceSupport.resolveProductPrices("PL-1",
        Collections.emptyList());
    assertTrue(prices.isEmpty());
  }

  // ── resolveEffectivePriceListId tier priority ───────────────────────────

  @Test
  void testResolveEffectivePriceListIdPrefersOrderPriceList() {
    when(doc.getSalesOrder()).thenReturn(order);
    when(order.getPriceList()).thenReturn(orderPriceList);
    when(orderPriceList.getId()).thenReturn("PL-ORDER");

    String result = MultiDocumentInvoiceSupport.resolveEffectivePriceListId(doc, true);

    assertEquals("PL-ORDER", result);
  }

  @Test
  void testResolveEffectivePriceListIdUsesSalesBusinessPartnerPriceListWhenNoOrder() {
    when(doc.getSalesOrder()).thenReturn(null);
    when(doc.getMaterialMgmtShipmentInOutLineList()).thenReturn(Collections.emptyList());
    when(doc.getBusinessPartner()).thenReturn(businessPartner);
    when(businessPartner.getPriceList()).thenReturn(bpPriceList);
    when(bpPriceList.getId()).thenReturn("PL-BP-SALES");

    String result = MultiDocumentInvoiceSupport.resolveEffectivePriceListId(doc, true);

    assertEquals("PL-BP-SALES", result);
    verify(businessPartner, never()).getPurchasePricelist();
  }

  @Test
  void testResolveEffectivePriceListIdUsesPurchaseBusinessPartnerPriceListWhenNoOrder() {
    when(doc.getSalesOrder()).thenReturn(null);
    when(doc.getMaterialMgmtShipmentInOutLineList()).thenReturn(Collections.emptyList());
    when(doc.getBusinessPartner()).thenReturn(businessPartner);
    when(businessPartner.getPurchasePricelist()).thenReturn(bpPriceList);
    when(bpPriceList.getId()).thenReturn("PL-BP-PURCHASE");

    String result = MultiDocumentInvoiceSupport.resolveEffectivePriceListId(doc, false);

    assertEquals("PL-BP-PURCHASE", result);
    verify(businessPartner, never()).getPriceList();
  }

  @Test
  void testResolveEffectivePriceListIdFallsBackToClientDefaultPriceList() {
    when(doc.getSalesOrder()).thenReturn(null);
    when(doc.getMaterialMgmtShipmentInOutLineList()).thenReturn(Collections.emptyList());
    when(doc.getBusinessPartner()).thenReturn(null);
    when(defaultPriceList.getId()).thenReturn("PL-DEFAULT");

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.createCriteria(PriceList.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
      when(criteria.list()).thenReturn(List.of(defaultPriceList));

      String result = MultiDocumentInvoiceSupport.resolveEffectivePriceListId(doc, true);

      assertEquals("PL-DEFAULT", result);
    }
  }

  @Test
  void testResolveEffectivePriceListIdReturnsNullWhenNoTierMatches() {
    when(doc.getSalesOrder()).thenReturn(null);
    when(doc.getMaterialMgmtShipmentInOutLineList()).thenReturn(Collections.emptyList());
    when(doc.getBusinessPartner()).thenReturn(null);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.createCriteria(PriceList.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      String result = MultiDocumentInvoiceSupport.resolveEffectivePriceListId(doc, true);

      assertNull(result);
    }
  }

  // ── ETP-5709: pending-quantity rules ────────────────────────────────────

  private static ShipmentInOutLine movementLine(String id, String qty) {
    ShipmentInOutLine line = org.mockito.Mockito.mock(ShipmentInOutLine.class);
    when(line.getId()).thenReturn(id);
    when(line.isActive()).thenReturn(true);
    when(line.getMovementQuantity()).thenReturn(new BigDecimal(qty));
    return line;
  }

  /** The bug: the pending query omits fully-invoiced lines, and absence meant "invoice it all". */
  @Test
  void testResolveInOutLineQtyLineAbsentFromNonNullMapHasNothingPending() {
    ShipmentInOutLine line = movementLine("l-1", "5");

    assertNull(MultiDocumentInvoiceSupport.resolveInOutLineQty(line, false, Collections.emptyMap(),
        Collections.emptyMap()));
    assertNull(MultiDocumentInvoiceSupport.resolveInOutLineQty(line, false, Collections.emptyMap(),
        Collections.singletonMap("other", BigDecimal.ONE)));
  }

  @Test
  void testResolveInOutLineQtyNullMapFallsBackToMovementQuantity() {
    ShipmentInOutLine line = movementLine("l-1", "5");

    assertEquals(new BigDecimal("5"), MultiDocumentInvoiceSupport.resolveInOutLineQty(line, false,
        Collections.emptyMap(), null));
  }

  @Test
  void testResolveInOutLineQtyPendingInMapIsUsedAndKeepsNegativeSign() {
    ShipmentInOutLine positive = movementLine("l-1", "5");
    ShipmentInOutLine negative = movementLine("l-2", "-5");
    Map<String, BigDecimal> pending = new HashMap<>();
    pending.put("l-1", new BigDecimal("2"));
    pending.put("l-2", new BigDecimal("3"));

    assertEquals(new BigDecimal("2"), MultiDocumentInvoiceSupport.resolveInOutLineQty(positive,
        false, Collections.emptyMap(), pending));
    assertEquals(new BigDecimal("-3"), MultiDocumentInvoiceSupport.resolveInOutLineQty(negative,
        false, Collections.emptyMap(), pending));
  }

  @Test
  void testBuildInOutLineSelectionSkipsInvoicedLineAndKeepsPendingOne() throws JSONException {
    ShipmentInOutLine invoiced = movementLine("l-1", "10");
    ShipmentInOutLine pending = movementLine("l-2", "6");
    when(doc.getMaterialMgmtShipmentInOutLineList()).thenReturn(Arrays.asList(invoiced, pending));

    JSONArray selection = MultiDocumentInvoiceSupport.buildInOutLineSelection(List.of(doc),
        Collections.emptyMap(), Collections.singletonMap("l-2", new BigDecimal("4")),
        LogManager.getLogger(MultiDocumentInvoiceSupportTest.class));

    assertEquals(1, selection.length());
    JSONObject entry = selection.getJSONObject(0);
    assertEquals("l-2", entry.getString("id"));
    assertEquals("4", entry.getString("orderedQuantity"));
  }

  private static final String EXCEEDS = "line %s asked %s pending %s";

  private void docWithLines(ShipmentInOutLine... lines) {
    when(doc.getDocumentNo()).thenReturn("DOC-1");
    when(doc.getMaterialMgmtShipmentInOutLineList()).thenReturn(Arrays.asList(lines));
  }

  @Test
  void testRequireOverridesWithinPendingAcceptsPartialAndExactQuantities() {
    docWithLines(movementLine("l-1", "10"), movementLine("l-2", "-4"));
    Map<String, BigDecimal> overrides = new HashMap<>();
    overrides.put("l-1", new BigDecimal("3"));
    overrides.put("l-2", new BigDecimal("-4"));
    Map<String, BigDecimal> pending = new HashMap<>();
    pending.put("l-1", new BigDecimal("7"));
    pending.put("l-2", new BigDecimal("4"));

    assertDoesNotThrow(() -> MultiDocumentInvoiceSupport.requireOverridesWithinPending(List.of(doc),
        overrides, pending, EXCEEDS));
  }

  @Test
  void testRequireOverridesWithinPendingRefusesQuantityAbovePendingNamingLineAndPending() {
    docWithLines(movementLine("l-1", "10"));

    OBException ex = assertThrows(OBException.class,
        () -> MultiDocumentInvoiceSupport.requireOverridesWithinPending(List.of(doc),
            Collections.singletonMap("l-1", new BigDecimal("8")),
            Collections.singletonMap("l-1", new BigDecimal("7")), EXCEEDS));

    assertTrue(ex.getMessage().contains("DOC-1"), ex.getMessage());
    assertTrue(ex.getMessage().contains("l-1"), ex.getMessage());
    assertTrue(ex.getMessage().contains("asked 8 pending 7"), ex.getMessage());
  }

  @Test
  void testRequireOverridesWithinPendingRefusesLineWithNothingPending() {
    docWithLines(movementLine("l-1", "10"));

    OBException ex = assertThrows(OBException.class,
        () -> MultiDocumentInvoiceSupport.requireOverridesWithinPending(List.of(doc),
            Collections.singletonMap("l-1", BigDecimal.ONE), Collections.emptyMap(), EXCEEDS));

    assertTrue(ex.getMessage().contains("asked 1 pending 0"), ex.getMessage());
  }

  @Test
  void testRequireOverridesWithinPendingRefusesWrongSign() {
    docWithLines(movementLine("l-1", "-4"));

    OBException ex = assertThrows(OBException.class,
        () -> MultiDocumentInvoiceSupport.requireOverridesWithinPending(List.of(doc),
            Collections.singletonMap("l-1", new BigDecimal("2")),
            Collections.singletonMap("l-1", new BigDecimal("4")), EXCEEDS));

    assertTrue(ex.getMessage().contains("asked 2 pending -4"), ex.getMessage());
  }

  @Test
  void testRequireOverridesWithinPendingRefusesNegativeMagnitudeAbovePendingNamingSignedPending() {
    docWithLines(movementLine("l-1", "-3"));

    OBException ex = assertThrows(OBException.class,
        () -> MultiDocumentInvoiceSupport.requireOverridesWithinPending(List.of(doc),
            Collections.singletonMap("l-1", new BigDecimal("-4")),
            Collections.singletonMap("l-1", new BigDecimal("3")), EXCEEDS));

    assertTrue(ex.getMessage().contains("DOC-1") && ex.getMessage().contains("l-1"), ex.getMessage());
    assertTrue(ex.getMessage().contains("asked -4 pending -3"), ex.getMessage());
  }

  @Test
  void testSignedLikeLineGivesQtyTheSignOfTheMovement() {
    assertEquals(new BigDecimal("-3"),
        MultiDocumentInvoiceSupport.signedLikeLine(movementLine("n", "-5"), new BigDecimal("3")));
    assertEquals(new BigDecimal("-3"),
        MultiDocumentInvoiceSupport.signedLikeLine(movementLine("n", "-5"), new BigDecimal("-3")));
    assertEquals(new BigDecimal("3"),
        MultiDocumentInvoiceSupport.signedLikeLine(movementLine("p", "5"), new BigDecimal("-3")));
    assertNull(MultiDocumentInvoiceSupport.signedLikeLine(movementLine("p", "5"), null));
  }

  @Test
  void testBuildOrderLineEntryForInOutLineKeepsNegativeQtyAndBindsTheMovementLine()
      throws JSONException {
    ShipmentInOutLine line = movementLine("l-neg", "-4");
    when(line.getOrderQuantity()).thenReturn(new BigDecimal("-8"));

    JSONObject entry = MultiDocumentInvoiceSupport.buildOrderLineEntryForInOutLine("ol-9", line,
        new BigDecimal("-1"));

    assertEquals("ol-9", entry.getString("id"));
    assertEquals("-1", entry.getString("orderedQuantity"));
    assertEquals("l-neg", entry.getString("shipmentInOutLine"));
    // -1 of -4 is a quarter of the line: the scaled order quantity keeps the sign (-8 * 1/4)
    assertEquals(0, new BigDecimal(entry.getString("orderQuantity")).compareTo(new BigDecimal("-2")));
  }

  @Test
  void testRequireOverridesWithinPendingIgnoresEmptyOverridesAndUnknownLineIds() {
    docWithLines(movementLine("l-1", "10"));

    assertDoesNotThrow(() -> MultiDocumentInvoiceSupport.requireOverridesWithinPending(List.of(doc),
        Collections.emptyMap(), Collections.emptyMap(), EXCEEDS));
    assertDoesNotThrow(() -> MultiDocumentInvoiceSupport.requireOverridesWithinPending(List.of(doc),
        Collections.singletonMap("unknown", BigDecimal.TEN), Collections.emptyMap(), EXCEEDS));
  }

  @Test
  void testRequireParsedLinesRefusesNonEmptyArrayThatParsedToNothing() throws JSONException {
    JSONObject body = new JSONObject().put("lines", new JSONArray()
        .put(new JSONObject().put("receiptLineId", "l-1").put("quantity", "0")));

    OBException ex = assertThrows(OBException.class,
        () -> MultiDocumentInvoiceSupport.requireParsedLines(body, "lines", Collections.emptyMap(),
            "unusable lines"));

    assertEquals("unusable lines", ex.getMessage());
  }

  @Test
  void testRequireParsedLinesAcceptsAbsentEmptyOrParsedLines() throws JSONException {
    assertDoesNotThrow(() -> MultiDocumentInvoiceSupport.requireParsedLines(null, "lines",
        Collections.emptyMap(), "x"));
    assertDoesNotThrow(() -> MultiDocumentInvoiceSupport.requireParsedLines(new JSONObject(),
        "lines", Collections.emptyMap(), "x"));
    assertDoesNotThrow(() -> MultiDocumentInvoiceSupport.requireParsedLines(
        new JSONObject().put("lines", new JSONArray()), "lines", Collections.emptyMap(), "x"));
    JSONObject body = new JSONObject().put("lines", new JSONArray().put(new JSONObject()));
    assertDoesNotThrow(() -> MultiDocumentInvoiceSupport.requireParsedLines(body, "lines",
        Collections.singletonMap("l-1", BigDecimal.ONE), "x"));
  }
}
