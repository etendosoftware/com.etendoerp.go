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

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * Shared mock builders for the tests of the line-import delegates (ETP-5713). The three delegates
 * read the same handful of entities (a draft target, a completed source, their partner and
 * currency, their lines), so the mocks live here once.
 */
final class LineImportTestSupport {

  static final String PARTNER = "bp1";
  static final String CURRENCY = "eur";
  static final String CLIENT = "c1";

  private LineImportTestSupport() {
  }

  static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  static BusinessPartner partner(String id) {
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getId()).thenReturn(id);
    when(bp.getName()).thenReturn("Partner " + id);
    return bp;
  }

  static Client client(String id) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(id);
    return client;
  }

  static Currency currency(String id) {
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn(id);
    return currency;
  }

  static Product product(String id, String name, boolean stockable) {
    Product product = mock(Product.class);
    when(product.getId()).thenReturn(id);
    when(product.getName()).thenReturn(name);
    when(product.isStocked()).thenReturn(stockable);
    when(product.getProductType()).thenReturn(stockable ? "I" : "S");
    return product;
  }

  static OrderLine orderLine(String id, long lineNo, Product product, String ordered,
      String delivered, String invoiced) {
    OrderLine line = mock(OrderLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getLineNo()).thenReturn(lineNo);
    when(line.isActive()).thenReturn(true);
    when(line.getProduct()).thenReturn(product);
    when(line.getOrderedQuantity()).thenReturn(bd(ordered));
    when(line.getDeliveredQuantity()).thenReturn(bd(delivered));
    when(line.getInvoicedQuantity()).thenReturn(bd(invoiced));
    UOM uom = mock(UOM.class);
    when(uom.getId()).thenReturn("uom1");
    when(line.getUOM()).thenReturn(uom);
    return line;
  }

  /** A completed purchase (or sales) order of {@link #PARTNER} in {@link #CURRENCY}. */
  static Order order(String id, boolean sales, OrderLine... lines) {
    Order order = mock(Order.class);
    when(order.getId()).thenReturn(id);
    when(order.getDocumentNo()).thenReturn("PO-" + id);
    when(order.getDocumentStatus()).thenReturn("CO");
    when(order.isSalesTransaction()).thenReturn(sales);
    doReturn(partner(PARTNER)).when(order).getBusinessPartner();
    doReturn(currency(CURRENCY)).when(order).getCurrency();
    doReturn(new ArrayList<>(Arrays.asList(lines))).when(order).getOrderLineList();
    doReturn(client(CLIENT)).when(order).getClient();
    DocumentType orderType = mock(DocumentType.class);
    doReturn(false).when(orderType).isReturn();
    doReturn(orderType).when(order).getTransactionDocument();
    for (OrderLine line : lines) {
      when(line.getSalesOrder()).thenReturn(order);
    }
    return order;
  }

  /** A draft receipt (or shipment) of {@link #PARTNER} in {@link #CURRENCY}, no order link. */
  static ShipmentInOut draftInOut(String id, boolean sales) {
    ShipmentInOut inOut = mock(ShipmentInOut.class);
    when(inOut.getId()).thenReturn(id);
    doReturn(client(CLIENT)).when(inOut).getClient();
    when(inOut.getDocumentNo()).thenReturn("D-" + id);
    when(inOut.getDocumentStatus()).thenReturn("DR");
    when(inOut.isSalesTransaction()).thenReturn(sales);
    doReturn(partner(PARTNER)).when(inOut).getBusinessPartner();
    doReturn(currency(CURRENCY)).when(inOut).getEtgoCurrency();
    return inOut;
  }

  /** A completed receipt (or shipment) of {@link #PARTNER} in {@link #CURRENCY}. */
  static ShipmentInOut completedInOut(String id, boolean sales, ShipmentInOutLine... lines) {
    ShipmentInOut inOut = draftInOut(id, sales);
    when(inOut.getDocumentStatus()).thenReturn("CO");
    when(inOut.getMaterialMgmtShipmentInOutLineList()).thenReturn(
        new ArrayList<>(Arrays.asList(lines)));
    DocumentType type = mock(DocumentType.class);
    when(type.isReturn()).thenReturn(false);
    when(inOut.getDocumentType()).thenReturn(type);
    return inOut;
  }

  static ShipmentInOutLine inOutLine(String id, long lineNo, Product product, String movement) {
    ShipmentInOutLine line = mock(ShipmentInOutLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getLineNo()).thenReturn(lineNo);
    when(line.isActive()).thenReturn(true);
    when(line.getProduct()).thenReturn(product);
    when(line.getMovementQuantity()).thenReturn(bd(movement));
    UOM uom = mock(UOM.class);
    when(uom.getId()).thenReturn("uom1");
    when(line.getUOM()).thenReturn(uom);
    return line;
  }

  /** A draft invoice of {@link #PARTNER} in {@link #CURRENCY} with a price list. */
  static Invoice draftInvoice(String id, boolean sales) {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getId()).thenReturn(id);
    doReturn(client(CLIENT)).when(invoice).getClient();
    when(invoice.getDocumentNo()).thenReturn("INV-" + id);
    when(invoice.getDocumentStatus()).thenReturn("DR");
    when(invoice.isSalesTransaction()).thenReturn(sales);
    doReturn(partner(PARTNER)).when(invoice).getBusinessPartner();
    doReturn(currency(CURRENCY)).when(invoice).getCurrency();
    doReturn(mock(PriceList.class)).when(invoice).getPriceList();
    doReturn(new ArrayList<>()).when(invoice).getInvoiceLineList();
    return invoice;
  }

  static InvoiceLine invoiceLine(String id, Product product, String quantity) {
    InvoiceLine line = mock(InvoiceLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getProduct()).thenReturn(product);
    when(line.getInvoicedQuantity()).thenReturn(bd(quantity));
    return line;
  }

  static NeoContext action(String action, String recordId, String bodyJson) throws Exception {
    return NeoContext.builder().endpointType(NeoEndpointType.ACTION).httpMethod("POST")
        .fieldName(action).recordId(recordId)
        .requestBody(bodyJson == null ? null : new JSONObject(bodyJson)).build();
  }

  static String errorCode(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("code");
  }

  static JSONObject data(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  static List<String> ids(org.codehaus.jettison.json.JSONArray rows) throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < rows.length(); i++) {
      ids.add(rows.getJSONObject(i).getString("id"));
    }
    return ids;
  }
}
