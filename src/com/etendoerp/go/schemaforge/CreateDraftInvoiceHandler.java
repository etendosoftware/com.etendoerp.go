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

  /**
   * Entry point for all ACTION requests routed to this handler.
   * Dispatches to {@link #handleCheck}, {@link #handleList}, or {@link #handleCreate}
   * based on {@code fieldName} and HTTP method. Returns {@code null} for any
   * endpoint type other than ACTION so that the default CRUD pipeline continues.
   *
   * @param context
   *     the current NEO request context
   * @return a {@link NeoResponse} short-circuiting the default pipeline, or
   *     {@code null} to continue with default behavior
   */
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

  /**
   * Creates a draft invoice from the record identified by {@code recordId}.
   * Routes to {@link #createFromOrder} for sales-order and sales-quotation specs,
   * or {@link #createFromShipments} for goods-shipment. After creation, triggers
   * document-number generation and gross-amount repair as needed.
   *
   * @param context
   *     the current NEO request context (carries spec name, body, etc.)
   * @param recordId
   *     the primary key of the source document
   * @return HTTP 201 with invoice id/documentNo on success, or an error response
   */
  protected NeoResponse handleCreate(NeoContext context, String recordId) {
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

  /**
   * Checks whether a draft invoice already exists for the given source record(s).
   * Does not create anything. Supports both single-record and multi-shipment modes.
   * Returns a JSON payload with {@code exists}, {@code count}, and — when at least
   * one draft is found — {@code id} and {@code documentNo} of the first draft.
   *
   * @param context
   *     the current NEO request context
   * @return HTTP 200 with the existence payload, or an error response
   */
  protected NeoResponse handleCheck(NeoContext context) {
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
  protected NeoResponse handleList(NeoContext context) {
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

  /**
   * Finds all draft invoices ({@code documentStatus = 'DR'}) linked to the given
   * source record IDs. For order/quotation specs the link is {@code C_Invoice.C_Order_ID};
   * for goods-shipment the lookup goes through the shipment's linked sales order.
   *
   * @param recordIds
   *     list of source document IDs to search against
   * @param specName
   *     the spec name determining the lookup strategy
   * @return list of matching draft invoices, newest first; empty list if none found
   */
  protected List<Invoice> findExistingDrafts(List<String> recordIds, String specName) {
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
  protected void ensureDocumentNo(Invoice invoice) {
    if (StringUtils.isNotBlank(invoice.getDocumentNo())) {
      return;
    }
    String docNo = generateInvoiceDocumentNo(invoice);
    if (StringUtils.isBlank(docNo)) {
      log.warn(
          "Could not generate documentNo for invoice {} (docType={}, client={}). " + "Verify the DocType's DocNoSequence_ID is set, or that the table-level " + "AD_Sequence for C_Invoice exists for the client.",
          invoice.getId(), invoice.getDocumentType() != null ? invoice.getDocumentType().getId() : "null",
          invoice.getClient() != null ? invoice.getClient().getId() : "null");
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
  protected String generateInvoiceDocumentNo(final Invoice invoice) {
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
  protected void markQuotationAsInvoiceCreated(String quotationId) {
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
  protected Map<String, BigDecimal> parseLineOverrides(JSONObject body) {
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

  /**
   * Creates a draft AR invoice from a sales order or sales quotation.
   * Builds the invoice header by copying header fields (business partner, currency,
   * payment terms, price list) from the order, then delegates line creation to
   * the native {@code CreateInvoiceLinesFromProcess} which handles taxes, gross
   * prices and IVA-included price lists. Only lines with pending invoiceable
   * quantity are included; caller-supplied {@code lineOverrides} can further
   * restrict or cap individual line quantities.
   *
   * @param orderId
   *     primary key of the source {@code C_Order} record
   * @param lineOverrides
   *     map of {@code C_OrderLine_ID → quantity} to invoice per
   *     line; empty map means invoice all pending quantity
   * @return the newly persisted draft {@link Invoice}
   * @throws OBException
   *     if the order is not found, has no invoiceable lines, or
   *     no AR Invoice document type can be resolved
   */
  protected Invoice createFromOrder(String orderId, Map<String, BigDecimal> lineOverrides) {
    Order order = OBDal.getInstance().get(Order.class, orderId);
    if (order == null) {
      throw new OBException("Order not found: " + orderId);
    }

    JSONArray selectedLines = buildSelectedLinesForOrder(order, lineOverrides);
    if (selectedLines.length() == 0) {
      throw new OBException("No hay líneas a facturar en este pedido");
    }

    DocumentType invoiceDocType = resolveARInvoiceDocType(order);

    Invoice invoice = NeoCommercialDocumentFactory.createInvoiceFromOrderHeader(
        order,
        invoiceDocType,
        true);
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

  /**
   * Builds the {@code selectedLines} JSON array required by
   * {@code CreateInvoiceLinesFromProcess.createInvoiceLinesFromDocumentLines}.
   * Each entry carries the order line ID and the quantity to invoice (pending
   * quantity, capped by any caller-supplied override).
   *
   * @param order
   *     source sales order
   * @param lineOverrides
   *     caller-supplied quantity caps per order line; empty means all
   * @return JSON array of {@code {id, orderedQuantity}} objects; may be empty if
   *     all lines are already fully invoiced
   */
  protected JSONArray buildSelectedLinesForOrder(Order order, Map<String, BigDecimal> lineOverrides) {
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

  /**
   * Determines the quantity to invoice for a single order line.
   * Returns {@code null} when the line should be skipped (inactive, no product,
   * excluded by overrides, or already fully invoiced). When overrides are present,
   * the result is capped at the override value.
   *
   * @param ol
   *     the order line to evaluate
   * @param hasOverrides
   *     whether {@code lineOverrides} is non-empty
   * @param lineOverrides
   *     caller-supplied quantity caps; consulted only when
   *     {@code hasOverrides} is {@code true}
   * @return quantity to invoice, or {@code null} to skip this line
   */
  protected BigDecimal resolvePendingForLine(OrderLine ol, boolean hasOverrides, Map<String, BigDecimal> lineOverrides) {
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
  protected DocumentType resolveARInvoiceDocType(Order order) {
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

  /**
   * Extracts the list of shipment IDs from the request body.
   * Reads the {@code shipmentIds} JSON array when present; falls back to
   * {@code recordId} so that single-shipment callers need not populate the array.
   *
   * @param body
   *     optional request body (may be {@code null})
   * @param recordId
   *     fallback ID used when {@code shipmentIds} is absent or empty
   * @return non-empty list of shipment IDs to invoice
   */
  protected List<String> parseShipmentIds(JSONObject body, String recordId) {
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

  /**
   * Creates a draft AR invoice from one or more goods shipments.
   * All shipments must belong to the same business partner. The invoice header
   * is derived from the first shipment (and its linked order when available).
   * Lines are built directly from {@link ShipmentInOutLine} records, pulling
   * prices from the linked order line when present.
   *
   * @param shipmentIds
   *     list of {@code M_InOut_ID} values to invoice
   * @param lineOverrides
   *     map of {@code M_InOutLine_ID → quantity} to invoice per
   *     line; empty map means use full movement quantity
   * @return the newly persisted draft {@link Invoice}
   * @throws OBException
   *     if a shipment is not found, shipments span multiple
   *     business partners, or no AR Invoice document type exists
   */
  protected Invoice createFromShipments(List<String> shipmentIds, Map<String, BigDecimal> lineOverrides) {
    List<ShipmentInOut> shipments = loadAndValidateShipments(shipmentIds);

    ShipmentInOut first = shipments.get(0);
    Invoice invoice = createInvoiceHeaderFromShipment(first, shipments);

    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();

    addShipmentLinesToInvoice(invoice, shipments, lineOverrides);

    return invoice;
  }

  /**
   * Loads the {@link ShipmentInOut} records for the given IDs and validates that
   * all belong to the same business partner (a prerequisite for combining them
   * into a single invoice).
   *
   * @param shipmentIds
   *     list of {@code M_InOut_ID} values to load
   * @return validated list of shipments in the same order as the input IDs
   * @throws OBException
   *     if any ID is not found or shipments span multiple BPs
   */
  protected List<ShipmentInOut> loadAndValidateShipments(List<String> shipmentIds) {
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

  /**
   * Constructs the unsaved {@link Invoice} header for a shipment-based invoice.
   * Payment terms, currency, and price list are copied from the linked sales order
   * when available; otherwise they fall back to the business partner's defaults.
   * {@code C_Invoice.C_Order_ID} is set only for single-shipment invoices to avoid
   * an ambiguous order link on combined invoices.
   *
   * @param first
   *     the first (or only) shipment, used to derive organization, BP, and address
   * @param shipments
   *     full list of shipments (used to decide whether to set C_Order_ID)
   * @return a transient {@link Invoice} ready to be saved and populated with lines
   * @throws OBException
   *     if no AR Invoice document type is found or the BP lacks
   *     mandatory payment terms / payment method
   */
  protected Invoice createInvoiceHeaderFromShipment(ShipmentInOut first, List<ShipmentInOut> shipments) {
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

  /**
   * Iterates over all shipment lines across the given shipments and appends an
   * {@link InvoiceLine} to {@code invoice} for each one whose quantity is > 0
   * after applying overrides. Lines are numbered 10, 20, 30, … in iteration order.
   *
   * @param invoice
   *     target draft invoice (header already saved)
   * @param shipments
   *     shipments whose lines should be invoiced
   * @param lineOverrides
   *     map of {@code M_InOutLine_ID → quantity} caps; empty means
   *     use full movement quantity for all lines
   */
  protected void addShipmentLinesToInvoice(Invoice invoice, List<ShipmentInOut> shipments,
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

  /**
   * Determines the quantity to invoice for a single shipment line.
   * Returns {@code null} when the line is inactive, excluded by overrides,
   * or has zero/null movement quantity. When overrides are present the result
   * is capped at the override value.
   *
   * @param sl
   *     the shipment line to evaluate
   * @param hasOverrides
   *     whether {@code lineOverrides} is non-empty
   * @param lineOverrides
   *     caller-supplied quantity caps per shipment line
   * @return quantity to invoice, or {@code null} to skip this line
   */
  protected BigDecimal resolveShipmentLineQty(ShipmentInOutLine sl, boolean hasOverrides,
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

  /**
   * Builds an unsaved {@link InvoiceLine} for the given shipment line.
   * When a linked {@link OrderLine} exists, unit price, list price, price limit, tax,
   * and net amount are copied from it; otherwise all monetary fields default to zero
   * and tax is left unset (caller must patch before completing the invoice).
   *
   * @param invoice
   *     the parent invoice
   * @param sl
   *     the source shipment line
   * @param qty
   *     the quantity to invoice (already validated > 0)
   * @param lineNo
   *     sequential line number to assign (10, 20, …)
   * @return a transient {@link InvoiceLine} ready to be saved
   */
  protected InvoiceLine createShipmentInvoiceLine(Invoice invoice, ShipmentInOutLine sl, BigDecimal qty, long lineNo) {
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
  protected void ensureLineGrossAmounts(Invoice invoice) {
    int precision = invoice.getCurrency().getStandardPrecision().intValue();
    for (InvoiceLine il : invoice.getInvoiceLineList()) {
      BigDecimal current = il.getGrossAmount();
      if (current != null && current.compareTo(BigDecimal.ZERO) > 0) {
        continue;
      }
      il.setGrossAmount(calculateLineGross(il, precision));
      OBDal.getInstance().save(il);
    }
    OBDal.getInstance().flush();
  }

  /**
   * Computes the gross amount for a single invoice line.
   * Uses {@code grossUnitPrice * qty} when {@code grossUnitPrice} is set and
   * positive; otherwise derives it from {@code lineNetAmount * (1 + taxRate/100)}.
   * The result is scaled to {@code precision} decimal places using
   * {@link RoundingMode#HALF_UP}.
   *
   * @param il
   *     the invoice line to compute the gross amount for
   * @param precision
   *     the number of decimal places (from the invoice currency)
   * @return the computed gross amount, never {@code null}
   */
  protected BigDecimal calculateLineGross(InvoiceLine il, int precision) {
    BigDecimal qty = il.getInvoicedQuantity() != null ? il.getInvoicedQuantity() : BigDecimal.ZERO;
    BigDecimal grossPrice = il.getGrossUnitPrice();
    if (grossPrice != null && grossPrice.compareTo(BigDecimal.ZERO) > 0) {
      return qty.multiply(grossPrice).setScale(precision, RoundingMode.HALF_UP);
    }
    BigDecimal net = il.getLineNetAmount() != null ? il.getLineNetAmount() : BigDecimal.ZERO;
    TaxRate tax = il.getTax();
    BigDecimal rate = (tax != null && tax.getRate() != null) ? tax.getRate() : BigDecimal.ZERO;
    BigDecimal taxAmt = net.multiply(rate).divide(new BigDecimal("100"), precision, RoundingMode.HALF_UP);
    return net.add(taxAmt).setScale(precision, RoundingMode.HALF_UP);
  }

  /**
   * Recalculates and persists {@code summedLineAmount}, {@code grandTotalAmount},
   * and {@link InvoiceTax} records for the given invoice.
   * Needed for shipment-based invoices whose lines are created manually (not via
   * {@code CreateInvoiceLinesFromProcess}), so the DB totals reflect the actual
   * line data. Works in two passes:
   * <ol>
   *   <li>Iterates lines to ensure {@code lineNetAmount} is set, and accumulates
   *       the taxable base per tax rate.</li>
   *   <li>Creates one {@link InvoiceTax} row per non-summary tax and sums the
   *       tax amounts into the invoice grand total.</li>
   * </ol>
   *
   * @param invoice
   *     the invoice whose totals should be recalculated
   */
  protected void recalculateTotals(Invoice invoice) {
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

  /**
   * Finds the best AR Invoice {@link DocumentType} for the given organization.
   * Prefers DocTypes that carry a {@code DocNoSequence_ID} (i.e.
   * {@code documentSequence != null}) so that generated document numbers
   * share the same counter the classic Etendo UI uses — picking a sequence-less
   * DocType would fall back to the table-default {@code AD_Sequence} which keeps
   * an independent counter and produces colliding numbers. Tries the given org
   * first, then the system org ({@code '0'}), then any. A second pass without
   * the sequence filter provides a last-resort fallback.
   *
   * @param orgId
   *     the {@code AD_Org_ID} of the target invoice's organization
   * @return the best matching {@link DocumentType}, or {@code null} if none exists
   */
  protected DocumentType findARInvoiceDocType(String orgId) {
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

    // Last-resort: any active ARI sales DocType without a sequence filter.
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

  /**
   * Builds a compact human-readable summary of a {@link DocumentType} list for
   * diagnostic log messages. Format: {@code [{name='...', org=..., default=..., seq=...}, ...]}.
   *
   * @param list
   *     the document types to summarize
   * @return a bracketed string listing each type's key attributes
   */
  protected String summarizeDocTypes(List<DocumentType> list) {
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

  /**
   * Selects the most appropriate {@link DocumentType} from a ranked candidate list
   * for the given organization. Tries an exact org match first, then the system
   * org ({@code '0'}), and finally falls back to the first element in the list
   * (which is sorted by {@code isDefault} descending).
   *
   * @param candidates
   *     pre-filtered and pre-sorted list of candidate document types
   * @param orgId
   *     the target organization ID
   * @return the best match, or {@code null} if the list is empty
   */
  protected DocumentType pickByOrg(List<DocumentType> candidates, String orgId) {
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
