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

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link CreateGoodsReceiptHandler}.
 *
 * <p>The single test below locks down the link-step that the handler runs after
 * persisting each receipt line. Without that step, m_inout_post can't create
 * m_matchinv when the receipt is later completed, which leaves the delivery
 * status column at 0% on purchase invoices.
 */
public class CreateGoodsReceiptHandlerTest {

  /**
   * Test double that overrides {@link CreateGoodsReceiptHandler#findDefaultLocator}
   * to bypass the warehouse-locator lookup, leaving createReceiptLines focused on
   * the line-creation + link-update behavior.
   */
  private static class TestableHandler extends CreateGoodsReceiptHandler {
    Locator locatorToReturn;

    @Override
    protected Locator findDefaultLocator(Order order) {
      return locatorToReturn;
    }
  }

  /**
   * Verifies that after persisting each receipt line, the handler runs the
   * native UPDATE that links draft invoice lines (of the same order line and
   * still unlinked) to the freshly created shipment line. This is what
   * c_invoiceline.M_InOutLine_ID gets set to so that m_inout_post can create
   * m_matchinv when the receipt is completed.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testCreateReceiptLinesLinksDraftInvoiceLines() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      // ── OBDal + Session + NativeQuery chain ─────────────────────────────
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(1);

      // ── OBContext for the user-id parameter ─────────────────────────────
      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-1");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      // ── Order with one valid order line ─────────────────────────────────
      Order order = mock(Order.class);
      OrderLine orderLine = mock(OrderLine.class);
      when(orderLine.getId()).thenReturn("ol-1");
      when(orderLine.isActive()).thenReturn(true);
      when(orderLine.getProduct()).thenReturn(mock(Product.class));
      when(orderLine.getUOM()).thenReturn(mock(UOM.class));
      when(orderLine.getOrderedQuantity()).thenReturn(new BigDecimal("3"));
      when(orderLine.getDeliveredQuantity()).thenReturn(BigDecimal.ZERO);
      when(orderLine.getClient()).thenReturn(null);
      when(orderLine.getOrganization()).thenReturn(null);
      when(order.getOrderLineList()).thenReturn(Collections.singletonList(orderLine));

      // ── ShipmentInOutLine produced by OBProvider ────────────────────────
      OBProvider provider = mock(OBProvider.class);
      ShipmentInOutLine receiptLine = mock(ShipmentInOutLine.class);
      when(receiptLine.getId()).thenReturn("iol-new");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(receiptLine);

      // ── Run the method under test ───────────────────────────────────────
      TestableHandler handler = new TestableHandler();
      handler.locatorToReturn = mock(Locator.class);

      ShipmentInOut receipt = mock(ShipmentInOut.class);
      handler.createReceiptLines(receipt, order);

      // ── Capture the SQL and verify shape ────────────────────────────────
      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session).createNativeQuery(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue("SQL should target C_InvoiceLine", sql.contains("UPDATE C_InvoiceLine"));
      assertTrue("SQL should set M_InOutLine_ID", sql.contains("SET M_InOutLine_ID"));
      assertTrue("SQL should filter by order line", sql.contains("C_OrderLine_ID = :orderLineId"));
      assertTrue("SQL should only touch unlinked lines", sql.contains("M_InOutLine_ID IS NULL"));

      verify(linkQuery).setParameter(eq("inoutLineId"), eq("iol-new"));
      verify(linkQuery).setParameter(eq("orderLineId"), eq("ol-1"));
      verify(linkQuery).setParameter(eq("userId"), eq("user-1"));
      verify(linkQuery).executeUpdate();
    }
  }
}
