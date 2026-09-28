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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
 * {@link MultiDocumentInvoiceSupport#resolveProductPrices}.
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
}
