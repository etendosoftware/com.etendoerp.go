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

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * NeoHandler that creates a Sales Invoice in Draft status from a Sales Order
 * or Goods Shipment record. Invoked as an ACTION endpoint via:
 *   POST /sws/neo/sales-order/{entity}/{recordId}/action/createDraftInvoice
 *   POST /sws/neo/goods-shipment/{entity}/{recordId}/action/createDraftInvoice
 *
 * For orders: invoices only the delivered-but-not-yet-invoiced quantity per line.
 * For shipments: invoices the full movement quantity, pulling prices from linked order lines.
 */
@Named("createDraftInvoiceHandler")
public class CreateDraftInvoiceHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreateDraftInvoiceHandler.class);
  private static final String ACTION_NAME = "createDraftInvoice";
  private static final String CHECK_ACTION = "checkDraftInvoice";

  private static final String SPEC_GOODS_SHIPMENT = "goods-shipment";
  private static final String FIELD_DOCUMENT_NO = "documentNo";
  private static final String PARAM_SHIPMENT_IDS = "shipmentIds";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }

    String fieldName = context.getFieldName();

    // GET/POST checkDraftInvoice — returns existing draft info without creating
    if (CHECK_ACTION.equals(fieldName) && ("GET".equals(context.getHttpMethod()) || "POST".equals(context.getHttpMethod()))) {
      return handleCheck(context);
    }

    if (!ACTION_NAME.equals(fieldName) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }

    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }

    String specName = context.getSpecName();

    try {
      OBContext.setAdminMode(true);
      try {
        JSONObject body = context.getRequestBody();
        Map<String, BigDecimal> lineOverrides = parseLineOverrides(body);

        Invoice invoice;
        if ("sales-order".equals(specName)) {
          invoice = createFromOrder(recordId, lineOverrides);
        } else if (SPEC_GOODS_SHIPMENT.equals(specName)) {
          List<String> shipmentIds = parseShipmentIds(body, recordId);
          invoice = createFromShipments(shipmentIds, lineOverrides);
        } else {
          return null;
        }

        OBDal.getInstance().flush();
        recalculateTotals(invoice);
        OBDal.getInstance().flush();

        JSONObject data = new JSONObject();
        data.put("id", invoice.getId());
        data.put(FIELD_DOCUMENT_NO, invoice.getDocumentNo());
        data.put("documentStatus", invoice.getDocumentStatus());

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.created(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (IllegalArgumentException e) {
      log.warn("Bad request creating draft invoice from {}: {}", specName, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      log.warn("State error creating draft invoice from {}: {}", specName, e.getMessage());
      return NeoResponse.error(422, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating draft invoice from {}: {}", specName, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to create invoice: " + e.getMessage());
    }
  }

  private NeoResponse handleCheck(NeoContext context) {
    String recordId = context.getRecordId();
    String specName = context.getSpecName();
    try {
      OBContext.setAdminMode(true);
      try {
        List<String> ids = new java.util.ArrayList<>();
        if (SPEC_GOODS_SHIPMENT.equals(specName) && context.getRequestBody() != null
            && context.getRequestBody().has(PARAM_SHIPMENT_IDS)) {
          ids = parseShipmentIds(context.getRequestBody(), recordId);
        } else if (StringUtils.isNotBlank(recordId)) {
          ids.add(recordId);
        }
        if (ids.isEmpty()) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
        }

        List<Invoice> drafts = findExistingDrafts(ids, specName);
        JSONObject data = new JSONObject();
        data.put("exists", !drafts.isEmpty());
        data.put("count", drafts.size());
        if (!drafts.isEmpty()) {
          data.put("id", drafts.get(0).getId());
          data.put(FIELD_DOCUMENT_NO, drafts.get(0).getDocumentNo());
        }
        if (drafts.size() > 1) {
          JSONArray arr = new JSONArray();
          for (Invoice inv : drafts) {
            JSONObject item = new JSONObject();
            item.put("id", inv.getId());
            item.put(FIELD_DOCUMENT_NO, inv.getDocumentNo());
            arr.put(item);
          }
          data.put("drafts", arr);
        }

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);
        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);
        return new NeoResponse(200, wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  private List<Invoice> findExistingDrafts(List<String> recordIds, String specName) {
    if (recordIds.isEmpty()) return java.util.Collections.emptyList();
    String hql;
    if ("sales-order".equals(specName)) {
      hql = "from Invoice i where i.salesOrder.id in :ids and i.documentStatus = 'DR' and i.salesTransaction = true order by i.creationDate desc";
    } else if (SPEC_GOODS_SHIPMENT.equals(specName)) {
      hql = "from Invoice i where i.salesOrder.id in (select s.salesOrder.id from MaterialMgmtShipmentInOut s where s.id in :ids) and i.documentStatus = 'DR' and i.salesTransaction = true order by i.creationDate desc";
    } else {
      return java.util.Collections.emptyList();
    }
    return OBDal.getInstance().getSession()
        .createQuery(hql, Invoice.class)
        .setParameterList("ids", recordIds)
        .setMaxResults(10)
        .list();
  }

  /**
   * Parse optional line overrides from request body.
   * Format: { "lines": [ { "orderLineId": "ABC", "quantity": 5 }, ... ] }
   * Returns a map of orderLineId/shipmentLineId -> quantity. Empty map means invoice all.
   */
  private Map<String, BigDecimal> parseLineOverrides(JSONObject body) {
    Map<String, BigDecimal> overrides = new HashMap<>();
    if (body == null || !body.has("lines")) {
      return overrides;
    }
    try {
      JSONArray lines = body.getJSONArray("lines");
      for (int i = 0; i < lines.length(); i++) {
        JSONObject line = lines.getJSONObject(i);
        String lineId = line.optString("orderLineId", line.optString("shipmentLineId", null));
        BigDecimal qty = line.has("quantity") ? new BigDecimal(line.getString("quantity")) : null;
        if (lineId != null && qty != null && qty.compareTo(BigDecimal.ZERO) > 0) {
          overrides.put(lineId, qty);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to parse line overrides: {}", e.getMessage());
    }
    return overrides;
  }

  private Invoice createFromOrder(String orderId, Map<String, BigDecimal> lineOverrides) {
    Order order = OBDal.getInstance().get(Order.class, orderId);
    if (order == null) {
      throw new IllegalArgumentException("Order not found: " + orderId);
    }

    BusinessPartner bp = order.getBusinessPartner();

    DocumentType orderDocType = order.getTransactionDocument();
    DocumentType invoiceDocType = orderDocType != null
        ? orderDocType.getDocumentTypeForInvoice()
        : null;
    if (invoiceDocType == null) {
      invoiceDocType = findARInvoiceDocType(order.getOrganization().getId());
    }
    if (invoiceDocType == null) {
      throw new IllegalStateException("No AR Invoice document type found");
    }

    Invoice invoice = OBProvider.getInstance().get(Invoice.class);
    invoice.setClient(order.getClient());
    invoice.setOrganization(order.getOrganization());
    invoice.setDocumentType(invoiceDocType);
    invoice.setTransactionDocument(invoiceDocType);
    invoice.setDocumentStatus("DR");
    invoice.setDocumentAction("CO");
    invoice.setSalesTransaction(true);
    invoice.setInvoiceDate(new Date());
    invoice.setAccountingDate(new Date());
    invoice.setBusinessPartner(bp);
    invoice.setPartnerAddress(order.getPartnerAddress());
    invoice.setPriceList(order.getPriceList());
    invoice.setCurrency(order.getCurrency());
    invoice.setPaymentTerms(order.getPaymentTerms());
    invoice.setPaymentMethod(order.getPaymentMethod());
    invoice.setSummedLineAmount(BigDecimal.ZERO);
    invoice.setGrandTotalAmount(BigDecimal.ZERO);
    invoice.setWithholdingamount(BigDecimal.ZERO);
    invoice.setSalesOrder(order);

    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();

    addOrderLinesToInvoice(invoice, order, lineOverrides);

    return invoice;
  }

  private void addOrderLinesToInvoice(Invoice invoice, Order order,
      Map<String, BigDecimal> lineOverrides) {
    boolean hasOverrides = !lineOverrides.isEmpty();
    long lineNo = 10;
    for (OrderLine ol : order.getOrderLineList()) {
      if (shouldSkipOrderLine(ol, hasOverrides, lineOverrides)) {
        continue;
      }

      BigDecimal delivered = ol.getDeliveredQuantity() != null
          ? ol.getDeliveredQuantity() : BigDecimal.ZERO;
      BigDecimal invoiced = ol.getInvoicedQuantity() != null
          ? ol.getInvoicedQuantity() : BigDecimal.ZERO;
      BigDecimal maxQty = delivered.subtract(invoiced);
      if (maxQty.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }

      BigDecimal qtyToInvoice = hasOverrides
          ? lineOverrides.get(ol.getId()).min(maxQty)
          : maxQty;
      if (qtyToInvoice.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }

      InvoiceLine il = OBProvider.getInstance().get(InvoiceLine.class);
      il.setOrganization(ol.getOrganization());
      il.setInvoice(invoice);
      il.setLineNo(lineNo);
      il.setProduct(ol.getProduct());
      il.setInvoicedQuantity(qtyToInvoice);
      il.setUOM(ol.getUOM());
      il.setUnitPrice(ol.getUnitPrice());
      il.setListPrice(ol.getListPrice());
      il.setPriceLimit(ol.getPriceLimit());
      il.setLineNetAmount(qtyToInvoice.multiply(ol.getUnitPrice()));
      il.setTax(ol.getTax());
      il.setSalesOrderLine(ol);

      OBDal.getInstance().save(il);
      lineNo += 10;
    }
  }

  private boolean shouldSkipOrderLine(OrderLine ol, boolean hasOverrides,
      Map<String, BigDecimal> lineOverrides) {
    return !ol.isActive() || (hasOverrides && !lineOverrides.containsKey(ol.getId()));
  }

  private List<String> parseShipmentIds(JSONObject body, String recordId) {
    List<String> ids = new java.util.ArrayList<>();
    if (body != null && body.has(PARAM_SHIPMENT_IDS)) {
      try {
        JSONArray arr = body.getJSONArray(PARAM_SHIPMENT_IDS);
        for (int i = 0; i < arr.length(); i++) {
          ids.add(arr.getString(i));
        }
      } catch (Exception e) {
        log.warn("Failed to parse shipmentIds: {}", e.getMessage());
      }
    }
    if (ids.isEmpty()) {
      ids.add(recordId);
    }
    return ids;
  }

  private Invoice createFromShipments(List<String> shipmentIds, Map<String, BigDecimal> lineOverrides) {
    List<ShipmentInOut> shipments = loadAndValidateShipments(shipmentIds);

    ShipmentInOut first = shipments.get(0);
    Invoice invoice = createInvoiceHeaderFromShipment(first, shipments);

    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();

    addShipmentLinesToInvoice(invoice, shipments, lineOverrides);

    return invoice;
  }

  private List<ShipmentInOut> loadAndValidateShipments(List<String> shipmentIds) {
    List<ShipmentInOut> shipments = new java.util.ArrayList<>();
    for (String id : shipmentIds) {
      ShipmentInOut s = OBDal.getInstance().get(ShipmentInOut.class, id);
      if (s == null) {
        throw new IllegalArgumentException("Shipment not found: " + id);
      }
      shipments.add(s);
    }
    if (shipments.isEmpty()) {
      throw new IllegalArgumentException("No shipments provided");
    }

    BusinessPartner bp = shipments.get(0).getBusinessPartner();
    for (ShipmentInOut s : shipments) {
      if (!s.getBusinessPartner().getId().equals(bp.getId())) {
        throw new IllegalArgumentException("All shipments must belong to the same Business Partner");
      }
    }
    return shipments;
  }

  private Invoice createInvoiceHeaderFromShipment(ShipmentInOut first,
      List<ShipmentInOut> shipments) {
    BusinessPartner bp = first.getBusinessPartner();
    Order linkedOrder = first.getSalesOrder();

    DocumentType invoiceDocType = null;
    if (linkedOrder != null && linkedOrder.getTransactionDocument() != null) {
      invoiceDocType = linkedOrder.getTransactionDocument().getDocumentTypeForInvoice();
    }
    if (invoiceDocType == null) {
      invoiceDocType = findARInvoiceDocType(first.getOrganization().getId());
    }
    if (invoiceDocType == null) {
      throw new IllegalStateException("No AR Invoice document type found");
    }

    Invoice invoice = OBProvider.getInstance().get(Invoice.class);
    invoice.setClient(first.getClient());
    invoice.setOrganization(first.getOrganization());
    invoice.setDocumentType(invoiceDocType);
    invoice.setTransactionDocument(invoiceDocType);
    invoice.setDocumentStatus("DR");
    invoice.setDocumentAction("CO");
    invoice.setSalesTransaction(true);
    invoice.setInvoiceDate(new Date());
    invoice.setAccountingDate(new Date());
    invoice.setBusinessPartner(bp);
    invoice.setPartnerAddress(first.getPartnerAddress());
    if (linkedOrder != null) {
      invoice.setPriceList(linkedOrder.getPriceList());
      invoice.setCurrency(linkedOrder.getCurrency());
      invoice.setPaymentTerms(linkedOrder.getPaymentTerms());
      invoice.setPaymentMethod(linkedOrder.getPaymentMethod());
      if (shipments.size() == 1) {
        invoice.setSalesOrder(linkedOrder);
      }
    } else {
      invoice.setPriceList(bp.getPriceList());
      if (bp.getPriceList() != null) {
        invoice.setCurrency(bp.getPriceList().getCurrency());
      }
      invoice.setPaymentTerms(bp.getPaymentTerms());
      invoice.setPaymentMethod(bp.getPaymentMethod());
    }
    invoice.setSummedLineAmount(BigDecimal.ZERO);
    invoice.setGrandTotalAmount(BigDecimal.ZERO);
    invoice.setWithholdingamount(BigDecimal.ZERO);

    return invoice;
  }

  private void addShipmentLinesToInvoice(Invoice invoice, List<ShipmentInOut> shipments,
      Map<String, BigDecimal> lineOverrides) {
    boolean hasOverrides = !lineOverrides.isEmpty();
    long lineNo = 10;
    for (ShipmentInOut shipment : shipments) {
      for (ShipmentInOutLine sl : shipment.getMaterialMgmtShipmentInOutLineList()) {
        if (shouldSkipShipmentLine(sl, hasOverrides, lineOverrides)) {
          continue;
        }

        BigDecimal maxQty = sl.getMovementQuantity();
        if (maxQty == null || maxQty.compareTo(BigDecimal.ZERO) <= 0) {
          continue;
        }

        BigDecimal qty = hasOverrides
            ? lineOverrides.get(sl.getId()).min(maxQty)
            : maxQty;
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
          continue;
        }

        InvoiceLine il = createShipmentInvoiceLine(invoice, sl, qty, lineNo);
        OBDal.getInstance().save(il);
        lineNo += 10;
      }
    }
  }

  private boolean shouldSkipShipmentLine(ShipmentInOutLine sl, boolean hasOverrides,
      Map<String, BigDecimal> lineOverrides) {
    return !sl.isActive() || (hasOverrides && !lineOverrides.containsKey(sl.getId()));
  }

  private InvoiceLine createShipmentInvoiceLine(Invoice invoice, ShipmentInOutLine sl,
      BigDecimal qty, long lineNo) {
    InvoiceLine il = OBProvider.getInstance().get(InvoiceLine.class);
    il.setOrganization(sl.getOrganization());
    il.setInvoice(invoice);
    il.setLineNo(lineNo);
    il.setProduct(sl.getProduct());
    il.setInvoicedQuantity(qty);
    il.setUOM(sl.getUOM());
    il.setGoodsShipmentLine(sl);

    OrderLine ol = sl.getSalesOrderLine();
    if (ol != null) {
      il.setUnitPrice(ol.getUnitPrice());
      il.setListPrice(ol.getListPrice());
      il.setPriceLimit(ol.getPriceLimit());
      il.setLineNetAmount(qty.multiply(ol.getUnitPrice()));
      il.setTax(ol.getTax());
      il.setSalesOrderLine(ol);
    } else {
      il.setUnitPrice(BigDecimal.ZERO);
      il.setListPrice(BigDecimal.ZERO);
      il.setLineNetAmount(BigDecimal.ZERO);
    }
    return il;
  }

  @SuppressWarnings("unchecked")
  private void recalculateTotals(Invoice invoice) {
    BigDecimal totalLines = BigDecimal.ZERO;
    for (InvoiceLine il : invoice.getInvoiceLineList()) {
      BigDecimal qty = il.getInvoicedQuantity() != null ? il.getInvoicedQuantity() : BigDecimal.ZERO;
      BigDecimal price = il.getUnitPrice() != null ? il.getUnitPrice() : BigDecimal.ZERO;
      BigDecimal lineNet = qty.multiply(price);
      if (il.getLineNetAmount() == null || il.getLineNetAmount().compareTo(BigDecimal.ZERO) == 0) {
        il.setLineNetAmount(lineNet);
        OBDal.getInstance().save(il);
      } else {
        lineNet = il.getLineNetAmount();
      }
      totalLines = totalLines.add(lineNet);
    }
    invoice.setSummedLineAmount(totalLines);
    invoice.setGrandTotalAmount(totalLines);
    OBDal.getInstance().save(invoice);
  }

  private DocumentType findARInvoiceDocType(String orgId) {
    List<DocumentType> results = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, "ARI"))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, true))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true))
        .addOrderBy(DocumentType.PROPERTY_DEFAULT, false)
        .list();

    for (DocumentType dt : results) {
      if (orgId.equals(dt.getOrganization().getId())) {
        return dt;
      }
    }
    for (DocumentType dt : results) {
      if ("0".equals(dt.getOrganization().getId())) {
        return dt;
      }
    }
    return results.isEmpty() ? null : results.get(0);
  }
}
