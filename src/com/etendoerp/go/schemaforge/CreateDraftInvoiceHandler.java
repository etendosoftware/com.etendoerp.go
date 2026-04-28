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
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.service.db.DalConnectionProvider;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.common.actionhandler.createlinesfromprocess.CreateInvoiceLinesFromProcess;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.service.OBCriteria;

/**
 * NeoHandler that creates a Sales Invoice in Draft status from a Sales Order,
 * Sales Quotation, or Goods Shipment record. Invoked as an ACTION endpoint via:
 * POST /sws/neo/sales-order/{entity}/{recordId}/action/createDraftInvoice
 * POST /sws/neo/sales-quotation/{entity}/{recordId}/action/createDraftInvoice
 * POST /sws/neo/goods-shipment/{entity}/{recordId}/action/createDraftInvoice
 * <p>
 * For orders and quotations: copies header data (business partner, currency, terms)
 * and lines (product, quantity, prices) into a new draft invoice via the native
 * {@code CreateInvoiceLinesFromProcess}.
 * For quotations: after invoice creation the source quotation's DocStatus is set
 * to {@code ETGO_CI} (Closed - Invoice Created), mirroring the standard
 * "CA" transition used when a quotation is converted into a sales order.
 * For shipments: invoices the full movement quantity, pulling prices from linked order lines.
 */
@Named("createDraftInvoiceHandler")
public class CreateDraftInvoiceHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreateDraftInvoiceHandler.class);
  private static final String ACTION_NAME = "createDraftInvoice";
  private static final String CHECK_ACTION = "checkDraftInvoice";
  private static final String LIST_ACTION = "listInvoices";

  private static final String SPEC_GOODS_SHIPMENT = "goods-shipment";
  private static final String SPEC_SALES_QUOTATION = "sales-quotation";
  private static final String SPEC_SALES_ORDER = "sales-order";
  private static final String FIELD_DOCUMENT_NO = "documentNo";
  private static final String PARAM_SHIPMENT_IDS = "shipmentIds";
  private static final String ERR_RECORD_ID_REQUIRED = "Record ID is required";
  private static final String KEY_RESPONSE = "response";

  /**
   * Custom DocStatus key marking a quotation as "Closed - Invoice Created".
   * Mirrors the standard "CA" (Closed - Order Created) used by
   * ConvertQuotationIntoOrder. The reference list entry is registered in AD.
   */
  private static final String STATUS_INVOICE_CREATED = "ETGO_CI";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }

    String fieldName = context.getFieldName();

    // GET checkDraftInvoice — returns existing draft info without creating
    if (CHECK_ACTION.equals(fieldName) && ("GET".equals(context.getHttpMethod()) || "POST".equals(
        context.getHttpMethod()))) {
      return handleCheck(context);
    }

    // GET listInvoices — returns ALL invoices linked to the order via invoice lines
    if (LIST_ACTION.equals(fieldName) && "GET".equals(context.getHttpMethod())) {
      return handleList(context);
    }

    if (!ACTION_NAME.equals(fieldName) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }

    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
    }

    return handleCreate(context, recordId);
  }

  private NeoResponse handleCreate(NeoContext context, String recordId) {
    String specName = context.getSpecName();
    try {
      OBContext.setAdminMode(true);
      try {
        JSONObject body = context.getRequestBody();
        Map<String, BigDecimal> lineOverrides = parseLineOverrides(body);

        Invoice invoice;
        if (SPEC_SALES_ORDER.equals(specName)) {
          invoice = createFromOrder(recordId, lineOverrides);
        } else if (SPEC_SALES_QUOTATION.equals(specName)) {
          invoice = createFromOrder(recordId, lineOverrides);
          markQuotationAsInvoiceCreated(recordId);
        } else if (SPEC_GOODS_SHIPMENT.equals(specName)) {
          List<String> shipmentIds = parseShipmentIds(body, recordId);
          invoice = createFromShipments(shipmentIds, lineOverrides);
        } else {
          return null;
        }

        OBDal.getInstance().flush();
        // Refresh to pick up trigger-generated documentNo and the totals
        // set by CreateInvoiceLinesFromProcess (order path) or recalculateTotals (shipment path).
        OBDal.getInstance().getSession().refresh(invoice);
        ensureDocumentNo(invoice);
        if (SPEC_GOODS_SHIPMENT.equals(specName)) {
          ensureLineGrossAmounts(invoice);
          recalculateTotals(invoice);
          OBDal.getInstance().flush();
        }

        JSONObject data = new JSONObject();
        data.put("id", invoice.getId());
        data.put(FIELD_DOCUMENT_NO, invoice.getDocumentNo());
        data.put("documentStatus", invoice.getDocumentStatus());

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);

        JSONObject wrapper = new JSONObject();
        wrapper.put(KEY_RESPONSE, responseData);

        return NeoResponse.created(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Error creating draft invoice from {}: {}", specName, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating draft invoice from {}: {}", specName, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the invoice");
    }
  }

  private NeoResponse handleCheck(NeoContext context) {
    String recordId = context.getRecordId();
    String specName = context.getSpecName();
    try {
      OBContext.setAdminMode(true);
      try {
        List<String> ids = new java.util.ArrayList<>();
        if (SPEC_GOODS_SHIPMENT.equals(specName) && context.getRequestBody() != null && context.getRequestBody().has(
            PARAM_SHIPMENT_IDS)) {
          ids = parseShipmentIds(context.getRequestBody(), recordId);
        } else if (StringUtils.isNotBlank(recordId)) {
          ids.add(recordId);
        }
        if (ids.isEmpty()) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
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
        wrapper.put(KEY_RESPONSE, responseData);
        return new NeoResponse(200, wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Returns ALL invoices linked to the given sales order via invoice lines.
   * This works even when C_Invoice.C_Order_ID is not set (e.g. invoices created
   * from classic Etendo UI that link via C_InvoiceLine.C_OrderLine_ID).
   */
  private NeoResponse handleList(NeoContext context) {
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
    }
    try {
      OBContext.setAdminMode(true);
      try {
        // Find invoices via order lines (covers all creation flows)
        String hql = "SELECT DISTINCT i FROM Invoice i JOIN i.invoiceLineList il " + "WHERE il.salesOrderLine.salesOrder.id = :orderId " + "AND i.salesTransaction = true " + "ORDER BY i.invoiceDate DESC";
        List<Invoice> invoices = OBDal.getInstance().getSession().createQuery(hql, Invoice.class).setParameter(
            "orderId", recordId).setMaxResults(100).list();

        // Also include invoices with C_Order_ID set directly (created via our action)
        // that may not have lines (edge case: empty invoice)
        String hqlDirect = "FROM Invoice i WHERE i.salesOrder.id = :orderId " + "AND i.salesTransaction = true ORDER BY i.invoiceDate DESC";
        List<Invoice> directInvoices = OBDal.getInstance().getSession().createQuery(hqlDirect,
            Invoice.class).setParameter("orderId", recordId).setMaxResults(100).list();

        // Merge both lists, deduplicate by ID
        java.util.Map<String, Invoice> merged = new java.util.LinkedHashMap<>();
        for (Invoice i : invoices) merged.put(i.getId(), i);
        for (Invoice i : directInvoices) merged.putIfAbsent(i.getId(), i);

        JSONArray arr = new JSONArray();
        for (Invoice inv : merged.values()) {
          JSONObject item = new JSONObject();
          item.put("id", inv.getId());
          item.put(FIELD_DOCUMENT_NO, inv.getDocumentNo());
          item.put("documentStatus", inv.getDocumentStatus());
          item.put("grandTotalAmount", inv.getGrandTotalAmount() != null ? inv.getGrandTotalAmount() : 0);
          if (inv.getInvoiceDate() != null) {
            item.put("invoiceDate", new SimpleDateFormat("yyyy-MM-dd").format(inv.getInvoiceDate()));
          }
          arr.put(item);
        }

        JSONObject responseData = new JSONObject();
        responseData.put("data", arr);
        JSONObject wrapper = new JSONObject();
        wrapper.put(KEY_RESPONSE, responseData);
        return new NeoResponse(200, wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error listing invoices for order {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  private List<Invoice> findExistingDrafts(List<String> recordIds, String specName) {
    if (recordIds.isEmpty()) return java.util.Collections.emptyList();
    String hql;
    if (SPEC_SALES_ORDER.equals(specName) || SPEC_SALES_QUOTATION.equals(specName)) {
      hql = "from Invoice i where i.salesOrder.id in :ids and i.documentStatus = 'DR' and i.salesTransaction = true order by i.creationDate desc";
    } else if (SPEC_GOODS_SHIPMENT.equals(specName)) {
      hql = "from Invoice i where i.salesOrder.id in (select s.salesOrder.id from MaterialMgmtShipmentInOut s where s.id in :ids) and i.documentStatus = 'DR' and i.salesTransaction = true order by i.creationDate desc";
    } else {
      return java.util.Collections.emptyList();
    }
    return OBDal.getInstance().getSession().createQuery(hql, Invoice.class).setParameterList("ids",
        recordIds).setMaxResults(10).list();
  }

  /**
   * Ensures the invoice has a {@code documentNo}. The DB trigger on
   * {@code C_Invoice} only validates the column — it never generates a value.
   * In standard Etendo, the docNo is produced by the {@code SL_Invoice}
   * callout when the invoice is created through the UI; for programmatically
   * created invoices we must replicate that step.
   *
   * <p>Delegates to the canonical
   * {@link Utility#getDocumentNo(java.sql.Connection, org.openbravo.database.ConnectionProvider,
   * org.openbravo.base.secureApp.VariablesSecureApp, String, String, String, String, boolean, boolean)
   * Utility.getDocumentNo} overload — the same one used by Etendo Core's
   * {@code InvoiceGeneratorFromGoodsShipment.generateInvoiceDocumentNo}. That
   * implementation:
   * <ul>
   *   <li>Uses the DocType-specific sequence when {@code DocNoSequence_ID}
   *       is set on the DocType (so we share the counter the classic UI uses
   *       — no collisions, no parallel sequences).</li>
   *   <li>Falls back to the table-level {@code AD_Sequence} row otherwise.</li>
   *   <li>Atomically increments {@code CurrentNext} in the DB, race-free.</li>
   *   <li>Honours all the standard filters ({@code IsActive='Y'},
   *       {@code IsAutoSequence='Y'}, per-client) without us having to repeat
   *       them.</li>
   * </ul>
   * Because we route through Etendo's own utility, the next number produced
   * here is exactly the next number the classic UI would assign — no
   * sequence names are hard-coded anywhere in this class.
   */
  private void ensureDocumentNo(Invoice invoice) {
    if (StringUtils.isNotBlank(invoice.getDocumentNo())) {
      return;
    }
    String docNo = generateInvoiceDocumentNo(invoice);
    if (StringUtils.isBlank(docNo)) {
      log.warn(
          "Could not generate documentNo for invoice {} (docType={}, client={}). " + "Verify the DocType's DocNoSequence_ID is set, or that the table-level " + "AD_Sequence for C_Invoice exists for the client.",
          invoice.getId(), invoice.getDocumentType() != null ? invoice.getDocumentType().getId() : "null",
          invoice.getClient().getId());
      return;
    }
    log.info("Generated documentNo='{}' for invoice {}", docNo, invoice.getId());
    invoice.setDocumentNo(docNo);
    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();
  }

  /**
   * Mirrors {@code InvoiceGeneratorFromGoodsShipment.generateInvoiceDocumentNo}
   * exactly — same arguments, same overload — so the document number stays
   * consistent with whatever the classic UI / native invoice processes would
   * produce.
   */
  private String generateInvoiceDocumentNo(final Invoice invoice) {
    final Entity invoiceEntity = ModelProvider.getInstance().getEntity(Invoice.class);
    return Utility.getDocumentNo(OBDal.getInstance().getConnection(false), new DalConnectionProvider(false),
        RequestContext.get().getVariablesSecureApp(), "", invoiceEntity.getTableName(),
        invoice.getTransactionDocument() == null ? "" : invoice.getTransactionDocument().getId(),
        invoice.getDocumentType() == null ? "" : invoice.getDocumentType().getId(), false, true);
  }

  /**
   * Marks a sales quotation as "Closed - Invoice Created" by setting
   * its DocStatus to {@link #STATUS_INVOICE_CREATED}. Mirrors the standard
   * Etendo pattern in {@code ConvertQuotationIntoOrder} which sets DocStatus
   * to "CA" after generating an order — direct write via OBDal, no
   * {@code C_Order_Post} invocation.
   */
  private void markQuotationAsInvoiceCreated(String quotationId) {
    Order quotation = OBDal.getInstance().get(Order.class, quotationId);
    if (quotation == null) {
      return;
    }
    quotation.setDocumentStatus(STATUS_INVOICE_CREATED);
    OBDal.getInstance().save(quotation);
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
      throw new OBException("Order not found: " + orderId);
    }

    JSONArray selectedLines = buildSelectedLinesForOrder(order, lineOverrides);
    if (selectedLines.length() == 0) {
      throw new OBException("No hay líneas a facturar en este pedido");
    }

    DocumentType invoiceDocType = resolveARInvoiceDocType(order);

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
    invoice.setBusinessPartner(order.getBusinessPartner());
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

    // Delegate line creation to native Etendo process — handles taxes, gross prices, IVA-included, etc.
    CreateInvoiceLinesFromProcess proc = WeldUtils.getInstanceFromStaticBeanManager(
        CreateInvoiceLinesFromProcess.class);
    proc.createInvoiceLinesFromDocumentLines(selectedLines, invoice, OrderLine.class);

    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().refresh(invoice);
    ensureLineGrossAmounts(invoice);

    return invoice;
  }

  private JSONArray buildSelectedLinesForOrder(Order order, Map<String, BigDecimal> lineOverrides) {
    boolean hasOverrides = !lineOverrides.isEmpty();
    JSONArray selectedLines = new JSONArray();
    for (OrderLine ol : order.getOrderLineList()) {
      BigDecimal pending = resolvePendingForLine(ol, hasOverrides, lineOverrides);
      if (pending == null) continue;
      try {
        JSONObject entry = new JSONObject();
        entry.put("id", ol.getId());
        entry.put("orderedQuantity", pending.toPlainString());
        selectedLines.put(entry);
      } catch (Exception e) {
        log.warn("Failed to add line {}: {}", ol.getId(), e.getMessage());
      }
    }
    return selectedLines;
  }

  private BigDecimal resolvePendingForLine(OrderLine ol, boolean hasOverrides, Map<String, BigDecimal> lineOverrides) {
    if (!ol.isActive() || ol.getProduct() == null) return null;
    if (hasOverrides && !lineOverrides.containsKey(ol.getId())) return null;
    BigDecimal ordered = ol.getOrderedQuantity() != null ? ol.getOrderedQuantity() : BigDecimal.ZERO;
    BigDecimal invoiced = ol.getInvoicedQuantity() != null ? ol.getInvoicedQuantity() : BigDecimal.ZERO;
    BigDecimal pending = ordered.subtract(invoiced);
    if (pending.compareTo(BigDecimal.ZERO) <= 0) return null;
    if (hasOverrides) {
      BigDecimal override = lineOverrides.get(ol.getId());
      return override != null ? override.min(pending) : pending;
    }
    return pending;
  }

  /**
   * Resolves the AR Invoice DocumentType to use for the new invoice.
   *
   * <p>Preference order:
   * <ol>
   *   <li>The DocType linked from the source order's DocType
   *       ({@code orderDocType.getDocumentTypeForInvoice()}) <strong>if</strong>
   *       it has a {@code DocumentSequence} assigned. We accept the link only
   *       when it points to a sequence-bearing DocType — otherwise we'd skip
   *       the dynamic search below and the caller would be forced to use
   *       a generic table-default sequence whose counter is unrelated to
   *       the one classic UI uses, producing colliding document numbers.</li>
   *   <li>{@link #findARInvoiceDocType(String)} which queries for an active
   *       sales ARI DocType <em>with</em> a DocumentSequence (preferring the
   *       order's organization, then the system org, then any).</li>
   *   <li>The linked DocType even if it lacks a sequence — last-resort, only
   *       when no DocType in the database has one configured.</li>
   * </ol>
   * No sequence names are hard-coded; the choice is driven entirely by what
   * the AD has configured in {@code C_DocType.DocNoSequence_ID}.
   */
  private DocumentType resolveARInvoiceDocType(Order order) {
    DocumentType orderDocType = order.getTransactionDocument();
    DocumentType linkedFromOrder = orderDocType != null ? orderDocType.getDocumentTypeForInvoice() : null;

    if (linkedFromOrder != null && linkedFromOrder.getDocumentSequence() != null) {
      return linkedFromOrder;
    }

    DocumentType discovered = findARInvoiceDocType(order.getOrganization().getId());
    if (discovered != null) {
      return discovered;
    }

    if (linkedFromOrder != null) {
      return linkedFromOrder;
    }
    throw new OBException("No AR Invoice document type found");
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
        throw new OBException("Shipment not found: " + id);
      }
      shipments.add(s);
    }
    if (shipments.isEmpty()) {
      throw new OBException("No shipments provided");
    }

    BusinessPartner bp = shipments.get(0).getBusinessPartner();
    for (ShipmentInOut s : shipments) {
      if (!s.getBusinessPartner().getId().equals(bp.getId())) {
        throw new OBException("All shipments must belong to the same Business Partner");
      }
    }
    return shipments;
  }

  private Invoice createInvoiceHeaderFromShipment(ShipmentInOut first, List<ShipmentInOut> shipments) {
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
      throw new OBException("No AR Invoice document type found");
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
      if (bp.getPaymentTerms() == null || bp.getPaymentMethod() == null) {
        throw new OBException("Business Partner is missing mandatory Payment Terms or Payment Method");
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
        BigDecimal qty = resolveShipmentLineQty(sl, hasOverrides, lineOverrides);
        if (qty == null) {
          continue;
        }
        InvoiceLine il = createShipmentInvoiceLine(invoice, sl, qty, lineNo);
        OBDal.getInstance().save(il);
        lineNo += 10;
      }
    }
  }

  private BigDecimal resolveShipmentLineQty(ShipmentInOutLine sl, boolean hasOverrides,
      Map<String, BigDecimal> lineOverrides) {
    if (!sl.isActive() || (hasOverrides && !lineOverrides.containsKey(sl.getId()))) {
      return null;
    }
    BigDecimal maxQty = sl.getMovementQuantity();
    if (maxQty == null || maxQty.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    BigDecimal qty = hasOverrides ? lineOverrides.get(sl.getId()).min(maxQty) : maxQty;
    return qty.compareTo(BigDecimal.ZERO) > 0 ? qty : null;
  }

  private InvoiceLine createShipmentInvoiceLine(Invoice invoice, ShipmentInOutLine sl, BigDecimal qty, long lineNo) {
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
      int precision = invoice.getCurrency().getStandardPrecision().intValue();
      il.setLineNetAmount(qty.multiply(ol.getUnitPrice()).setScale(precision, RoundingMode.HALF_UP));
      il.setTax(ol.getTax());
      il.setSalesOrderLine(ol);
    } else {
      il.setUnitPrice(BigDecimal.ZERO);
      il.setListPrice(BigDecimal.ZERO);
      il.setLineNetAmount(BigDecimal.ZERO);
    }
    return il;
  }

  /**
   * Ensures every invoice line has its {@code lineGrossAmount} populated.
   * {@code CreateInvoiceLinesFromProcess} sets {@code lineNetAmount} from the
   * source order/quotation line, but for tax-not-included price lists the
   * gross amount is left at zero — which leaves the grid column blank.
   * This helper fills it in using either {@code grossUnitPrice * qty} when
   * available, or {@code lineNetAmount * (1 + taxRate/100)} as a fallback.
   */
  private void ensureLineGrossAmounts(Invoice invoice) {
    int precision = invoice.getCurrency().getStandardPrecision().intValue();
    for (InvoiceLine il : invoice.getInvoiceLineList()) {
      BigDecimal current = il.getGrossAmount();
      if (current != null && current.compareTo(BigDecimal.ZERO) > 0) {
        continue;
      }
      BigDecimal qty = il.getInvoicedQuantity() != null ? il.getInvoicedQuantity() : BigDecimal.ZERO;
      BigDecimal grossPrice = il.getGrossUnitPrice();
      BigDecimal gross;
      if (grossPrice != null && grossPrice.compareTo(BigDecimal.ZERO) > 0) {
        gross = qty.multiply(grossPrice);
      } else {
        BigDecimal net = il.getLineNetAmount() != null ? il.getLineNetAmount() : BigDecimal.ZERO;
        TaxRate tax = il.getTax();
        BigDecimal rate = (tax != null && tax.getRate() != null) ? tax.getRate() : BigDecimal.ZERO;
        BigDecimal taxAmt = net.multiply(rate).divide(new BigDecimal("100"), precision, RoundingMode.HALF_UP);
        gross = net.add(taxAmt);
      }
      il.setGrossAmount(gross.setScale(precision, RoundingMode.HALF_UP));
      OBDal.getInstance().save(il);
    }
    OBDal.getInstance().flush();
  }

  private void recalculateTotals(Invoice invoice) {
    int precision = invoice.getCurrency().getStandardPrecision().intValue();

    // Pass 1: ensure lineNetAmount is set; accumulate taxable base per tax
    Map<String, BigDecimal> taxBaseMap = new LinkedHashMap<>();
    Map<String, TaxRate> taxRateMap = new LinkedHashMap<>();
    BigDecimal totalLines = BigDecimal.ZERO;

    for (InvoiceLine il : invoice.getInvoiceLineList()) {
      BigDecimal qty = il.getInvoicedQuantity() != null ? il.getInvoicedQuantity() : BigDecimal.ZERO;
      BigDecimal price = il.getUnitPrice() != null ? il.getUnitPrice() : BigDecimal.ZERO;
      BigDecimal lineNet;
      if (il.getLineNetAmount() == null || il.getLineNetAmount().compareTo(BigDecimal.ZERO) == 0) {
        lineNet = qty.multiply(price).setScale(precision, RoundingMode.HALF_UP);
        il.setLineNetAmount(lineNet);
        OBDal.getInstance().save(il);
      } else {
        lineNet = il.getLineNetAmount();
      }
      totalLines = totalLines.add(lineNet);

      TaxRate tax = il.getTax();
      if (tax != null && Boolean.FALSE.equals(tax.isSummaryLevel())) {
        taxBaseMap.merge(tax.getId(), lineNet, BigDecimal::add);
        taxRateMap.putIfAbsent(tax.getId(), tax);
      }
    }

    // Pass 2: create InvoiceTax records and accumulate total tax
    BigDecimal totalTax = BigDecimal.ZERO;
    long lineNo = 10;
    for (Map.Entry<String, TaxRate> entry : taxRateMap.entrySet()) {
      TaxRate tax = entry.getValue();
      BigDecimal taxBase = taxBaseMap.get(entry.getKey());
      BigDecimal rate = tax.getRate() != null ? tax.getRate() : BigDecimal.ZERO;
      BigDecimal taxAmt = taxBase.multiply(rate).divide(new BigDecimal("100"), precision, RoundingMode.HALF_UP);

      InvoiceTax it = OBProvider.getInstance().get(InvoiceTax.class);
      it.setClient(invoice.getClient());
      it.setOrganization(invoice.getOrganization());
      it.setInvoice(invoice);
      it.setTax(tax);
      it.setLineNo(lineNo);
      it.setTaxableAmount(taxBase.setScale(precision, RoundingMode.HALF_UP));
      it.setTaxAmount(taxAmt);
      it.setRecalculate(false);
      OBDal.getInstance().save(it);

      totalTax = totalTax.add(taxAmt);
      lineNo += 10;
    }

    invoice.setSummedLineAmount(totalLines.setScale(precision, RoundingMode.HALF_UP));
    invoice.setGrandTotalAmount(totalLines.add(totalTax).setScale(precision, RoundingMode.HALF_UP));
    OBDal.getInstance().save(invoice);
  }

  private DocumentType findARInvoiceDocType(String orgId) {
    // First pass: prefer DocTypes that have a DocumentSequence assigned —
    // those generate document numbers from the same counter the classic UI
    // uses. Picking a DocType without a sequence would force a fallback to
    // the table-default AD_Sequence, which keeps an independent counter and
    // produces numbers that collide with the ones in use.
    List<DocumentType> withSeq = OBDal.getInstance().createCriteria(DocumentType.class).add(
        Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, "ARI")).add(
        Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, true)).add(
        Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true)).add(
        Restrictions.isNotNull(DocumentType.PROPERTY_DOCUMENTSEQUENCE)).addOrderBy(DocumentType.PROPERTY_DEFAULT,
        false).list();
    log.info("findARInvoiceDocType orgId={} ARI-with-sequence candidates={}", orgId, summarizeDocTypes(withSeq));

    DocumentType picked = pickByOrg(withSeq, orgId);
    if (picked != null) {
      log.info("findARInvoiceDocType picked={} (name={}, sequence={})", picked.getId(), picked.getName(),
          picked.getDocumentSequence() != null ? picked.getDocumentSequence().getName() : "null");
      return picked;
    }

    // Last-resort fallback: any active ARI sales DocType, even without a
    // sequence. The caller will use the table-default sequence — which is
    // not ideal but at least produces *some* docNo.
    List<DocumentType> any = OBDal.getInstance().createCriteria(DocumentType.class).add(
        Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, "ARI")).add(
        Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, true)).add(
        Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true)).addOrderBy(DocumentType.PROPERTY_DEFAULT, false).list();
    log.info("findARInvoiceDocType orgId={} fallback ARI candidates={}", orgId, summarizeDocTypes(any));
    DocumentType fallback = pickByOrg(any, orgId);
    if (fallback != null) {
      log.info("findARInvoiceDocType fallback picked={} (name={}, sequence={})", fallback.getId(), fallback.getName(),
          fallback.getDocumentSequence() != null ? fallback.getDocumentSequence().getName() : "null");
    }
    return fallback;
  }

  private String summarizeDocTypes(List<DocumentType> list) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < list.size(); i++) {
      DocumentType d = list.get(i);
      if (i > 0) sb.append(", ");
      sb.append("{name='").append(d.getName()).append("', org=").append(
          d.getOrganization() != null ? d.getOrganization().getId() : "null").append(", default=").append(
          Boolean.TRUE.equals(d.isDefault())).append(", seq=").append(
          d.getDocumentSequence() != null ? d.getDocumentSequence().getName() : "null").append("}");
    }
    return sb.append("]").toString();
  }

  private DocumentType pickByOrg(List<DocumentType> candidates, String orgId) {
    for (DocumentType dt : candidates) {
      if (orgId.equals(dt.getOrganization().getId())) {
        return dt;
      }
    }
    for (DocumentType dt : candidates) {
      if ("0".equals(dt.getOrganization().getId())) {
        return dt;
      }
    }
    return candidates.isEmpty() ? null : candidates.get(0);
  }
}
