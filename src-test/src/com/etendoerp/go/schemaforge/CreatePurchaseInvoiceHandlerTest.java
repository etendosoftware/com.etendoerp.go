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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.common.actionhandler.createlinesfromprocess.CreateInvoiceLinesFromProcess;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * Unit tests for {@link CreatePurchaseInvoiceHandler}.
 *
 * <p>Single test below verifies the link-step that the handler runs after
 * delegating line creation to {@code CreateInvoiceLinesFromProcess}. Without
 * this step, m_inout_post can't create m_matchinv when the receipt is later
 * completed, leaving the delivery status column at 0% on purchase invoices
 * even after a corresponding receipt is completed.
 */
public class CreatePurchaseInvoiceHandlerTest {

  /**
   * Test double that overrides the helper methods that hit the DB or other
   * heavy collaborators, leaving createFromOrder focused on the link-step.
   */
  private static class TestableHandler extends CreatePurchaseInvoiceHandler {
    DocumentType docTypeToReturn;
    JSONArray selectedLinesToReturn;

    @Override
    protected DocumentType resolveAPInvoiceDocType(Order order) {
      return docTypeToReturn;
    }

    @Override
    protected JSONArray buildSelectedLines(Order order) {
      return selectedLinesToReturn;
    }
  }

  /** Stub an Order with the minimum header data the factory expects. */
  private static Order mockOrderWithHeaderData() {
    Order order = mock(Order.class);
    when(order.getClient()).thenReturn(mock(Client.class));
    when(order.getOrganization()).thenReturn(mock(Organization.class));
    when(order.getBusinessPartner()).thenReturn(mock(BusinessPartner.class));
    when(order.getPriceList()).thenReturn(mock(PriceList.class));
    when(order.getCurrency()).thenReturn(mock(Currency.class));
    when(order.getPaymentTerms()).thenReturn(mock(PaymentTerm.class));
    when(order.getPaymentMethod()).thenReturn(mock(FIN_PaymentMethod.class));
    return order;
  }

  /**
   * Verifies that after creating the invoice and delegating line creation,
   * the handler runs the native UPDATE that links each new invoice line to
   * an existing receipt line of the same order line.
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
      when(dal.get(eq(Order.class), eq("po-1"))).thenReturn(mockOrderWithHeaderData());

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(1);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-3");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-PO");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);
      handler.selectedLinesToReturn = new JSONArray().put(new JSONObject()
          .put("id", "ol-1")
          .put("orderedQuantity", "1"));

      Invoice result = handler.createFromOrder("po-1");
      assertSame(invoice, result);

      verify(process).createInvoiceLinesFromDocumentLines(
          eq(handler.selectedLinesToReturn), eq(invoice), eq(OrderLine.class));

      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session).createNativeQuery(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue("SQL should target C_InvoiceLine", sql.contains("UPDATE C_InvoiceLine"));
      assertTrue("SQL should set M_InOutLine_ID", sql.contains("SET M_InOutLine_ID"));
      assertTrue("SQL should subquery MAX over M_InOutLine", sql.contains("MAX(iol.M_InOutLine_ID)"));
      assertTrue("SQL should join via C_OrderLine_ID", sql.contains("iol.C_OrderLine_ID = il.C_OrderLine_ID"));
      assertTrue("SQL should scope to the new invoice", sql.contains(":invoiceId"));
      assertTrue("SQL should only touch unlinked lines", sql.contains("M_InOutLine_ID IS NULL"));

      verify(linkQuery).setParameter(eq("userId"), eq("user-3"));
      verify(linkQuery).setParameter(eq("invoiceId"), eq("invoice-PO"));
      verify(linkQuery).executeUpdate();
    }
  }
}
