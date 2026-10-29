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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;

import javax.inject.Inject;
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
import org.openbravo.model.common.currency.Currency;
import org.openbravo.service.db.DalConnectionProvider;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.common.actionhandler.createlinesfromprocess.CreateInvoiceLinesFromProcess;
import org.openbravo.base.weld.WeldUtils;

import com.etendoerp.go.schemaforge.util.DocumentStatusGuard;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.usage.quota.QuotaWriteGuard;

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
@SuppressWarnings("java:S1448")
public class CreateDraftInvoiceHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreateDraftInvoiceHandler.class);
  private static final String ACTION_NAME = "createDraftInvoice";
  private static final String PARAM_LINES = "lines";
  private static final String PARAM_SHIPMENT_IDS = "shipmentIds";
  private static final String PARAM_PRICE_LIST_ID = "priceListId";
  private static final String ERR_ORDER_NOT_FOUND = "Order not found: ";

  /**
   * What an agent reads about {@code createDraftInvoice} on a sales order (ETP-5709). Despite the
   * name the invoice is COMPLETED in the same call (ETP-5381), which the description says
   * explicitly. {@code lines} is the one body key the order path reads
   * ({@link #parseLineOverrides}); the SPA sends {@code {}}. The MCP publishes it and judges the
   * call against it; REST and the SPA do not validate against it.
   */
  static final NeoActionContract ORDER_CONTRACT = NeoActionContract.write(ACTION_NAME,
      "Creates a sales invoice from the lines of this sales order that are still pending "
          + "invoicing and COMPLETES it in the same call (created AND completed, there is no "
          + "draft step despite the name), returning 201 with {id, documentNo, documentStatus}. "
          + "Precondition: the order is completed (status CO), otherwise 400 naming its status. "
          + "It is independent of createShipment: an order can be invoiced without being "
          + "shipped, shipped without being invoiced, or both. Typical sequence for an order: "
          + "documentAction (confirm) -> createShipment -> createDraftInvoice. A line is invoiced "
          + "only for what is still pending (ordered minus invoiced, same sign as the ordered "
          + "quantity): an already-invoiced or over-invoiced line is never invoiced again, so a "
          + "second call does not duplicate the invoice and, once nothing is pending, answers "
          + "400 'No hay líneas a facturar en este pedido'. Without lines, a line with completed, "
          + "not-yet-invoiced shipments is invoiced for the quantity of those shipments. Only "
          + "COMPLETED invoices count as invoiced, but an order that already has a DRAFT invoice "
          + "is refused with 409 draftInvoiceExists, creating nothing (one draft per order, as on "
          + "the screen, which hides the button): the refusal lists the draft numbers in "
          + "existingDraftInvoices, and allowDuplicate:true creates the invoice anyway and lists "
          + "the drafts the order had.",
      NeoActionContract.Param.array(PARAM_LINES, NeoActionContract.TYPE_OBJECT, false,
          "Partial invoicing: [{orderLineId, quantity}] with the quantity to invoice per order "
              + "line. Honoured only for order lines that have NO completed shipment not yet "
              + "invoiced; if a requested line has one, the call is refused with 400 naming the "
              + "line and telling you to invoice from the shipment instead (createDraftInvoice "
              + "on the goods shipment, where quantities per shipment line are honoured). An "
              + "invoice generated from a shipment cannot have its quantities edited afterwards "
              + "(reactivating it does not allow it), so give any partial quantity at creation. "
              + "A quantity above what is still pending for that line, or for a line with "
              + "nothing pending, is refused with 400 naming the line and its pending quantity "
              + "(it is never capped silently), and so is a list none of whose entries has an "
              + "orderLineId and a quantity above zero. Omit to invoice the pending quantity of "
              + "every line."),
      OrderInvoiceDuplicateGuard.allowDuplicateParam())
      .withIdDescription("the id of the sales order to invoice")
      .availableWhen(DocumentAvailability.WHEN_COMPLETED);

  /**
   * What an agent reads about {@code createDraftInvoice} on a sales quotation (ETP-5709). No
   * parameters: a quotation is invoiced in full (the SPA's QuotationConfirmModal posts {@code {}}),
   * because a partial invoice would leave the quotation closed ({@code ETGO_CI}) with the remainder
   * impossible to invoice. A {@code lines} key is a 422 on MCP and a 400 from the handler on REST
   * ({@link #ERR_QUOTATION_LINES}). The MCP publishes it and judges the call against it.
   */
  static final NeoActionContract QUOTATION_CONTRACT = NeoActionContract.write(ACTION_NAME,
      "Invoices the WHOLE sales quotation: creates a sales invoice with every line of this "
          + "quotation and COMPLETES it in the same call (created AND completed, there is no "
          + "draft step despite the name), returning 201 with {id, documentNo, documentStatus}. "
          + "No parameters: a quotation cannot be invoiced partially (any body key is refused). "
          + "Precondition: the quotation is Under Evaluation (status UE, reached with "
          + "documentAction CO) or completed (status CO), otherwise 400 naming its number and "
          + "status (a draft, rejected, already-invoiced or order-converted quotation is "
          + "refused). Once the invoice is "
          + "completed the quotation moves to status ETGO_CI (\"Closed - Invoice Created\") and "
          + "can no longer be invoiced: a second call answers 400 naming the status. This action "
          + "never creates an order from the quotation.")
      .withIdDescription("the id of the sales quotation to invoice");

  /**
   * What an agent reads about {@code createDraftInvoice} on a goods shipment (ETP-5709). The three
   * keys are the ones {@link #handleCreate} reads on the shipment route: {@code shipmentIds}
   * ({@link #parseShipmentIds}), {@code priceListId} and {@code lines} ({@link #parseLineOverrides},
   * which reads {@code shipmentLineId}). Declared because the classic {@code Invoicefromshipment}
   * button bypasses every guard of this path and is redirected here through MCP_CONFIG. The SPA
   * posts {@code {priceListId}} and {@code {shipmentIds, priceListId}}. The MCP publishes it and
   * judges the call against it; REST and the SPA do not validate against it.
   */
  static final NeoActionContract SHIPMENT_CONTRACT = NeoActionContract.write(ACTION_NAME,
      "Creates a sales invoice from this goods shipment (or from several shipments together, see "
          + "shipmentIds) and COMPLETES it in the same call (created AND completed, there is no "
          + "draft step despite the name), returning 201 with {id, documentNo, documentStatus}. "
          + "Precondition: the shipment is completed (status CO; every shipment of shipmentIds), "
          + "otherwise 400 naming the shipment and its status. It is independent of the order's "
          + "own createDraftInvoice. It invoices only what is still pending on each shipment line "
          + "(shipped minus invoiced, draft invoices included) and only the lines of the "
          + "shipment(s) sent, so a second call does not duplicate the invoice: once nothing is "
          + "pending it answers 400 'No hay líneas pendientes de facturar en este albarán'. By "
          + "default it invoices the pending quantity of every line. An invoice generated from a "
          + "shipment cannot have its quantities edited afterwards (reactivating it does not "
          + "allow it), so give any partial quantity at creation.",
      NeoActionContract.Param.array(PARAM_SHIPMENT_IDS, NeoActionContract.TYPE_STRING, false,
          "Ids of the goods shipments to invoice together in ONE invoice (all of the same "
              + "business partner). When sent, exactly these shipments are invoiced, so include "
              + "this one. Omit to invoice only this shipment."),
      NeoActionContract.Param.optional(PARAM_PRICE_LIST_ID, NeoActionContract.TYPE_STRING,
          "Id of the sales price list to price the invoice with. Only required when none can be "
              + "resolved (shipments without a linked order and a business partner without a "
              + "default price list); otherwise the order's or partner's list is used."),
      NeoActionContract.Param.array(PARAM_LINES, NeoActionContract.TYPE_OBJECT, false,
          "Partial invoicing, honoured exactly: [{shipmentLineId, quantity}] with the quantity to "
              + "invoice per shipment line (a quantity above zero). A quantity above what is "
              + "still pending for that line, with the opposite sign to the line, or for a line "
              + "with nothing pending is refused with 400 naming the line and its pending "
              + "quantity (it is never capped silently), and so is a list none of whose entries "
              + "has a shipmentLineId and a quantity above zero. Omit to invoice the pending "
              + "quantity of every line."))
      .withIdDescription("the id of the goods shipment to invoice")
      .availableWhen(DocumentAvailability.WHEN_COMPLETED);

  @Inject
  InvoiceFromOrderSupport invoiceFromOrderSupport;

  @Inject
  TotalDiscountService totalDiscountService;

  private static final String CHECK_ACTION = "checkDraftInvoice";
  private static final String LIST_ACTION = "listInvoices";
  private static final String PENDING_LINES_ACTION = "pendingInvoiceLines";
  private static final String PRODUCT_PRICES_ACTION = "productPrices";

  private static final String SPEC_GOODS_SHIPMENT = "goods-shipment";
  private static final String SPEC_SALES_QUOTATION = "sales-quotation";
  private static final String SPEC_SALES_ORDER = "sales-order";
  private static final String FIELD_DOCUMENT_NO = "documentNo";
  private static final String FIELD_ORDERED_QUANTITY = "orderedQuantity";
  private static final String ERR_QUOTATION_LINES =
      "Una cotización se factura completa: no admite 'lines' (facturación parcial)";
  private static final String ERR_RECORD_ID_REQUIRED = "Record ID is required";
  // ETP-5709 — explicit `lines` quantities are validated against what is pending, never capped.
  private static final String ERR_ORDER_LINE_EXCEEDS_PENDING =
      "No se puede facturar la línea %s del pedido: cantidad solicitada %s, pero lo pendiente "
          + "de facturar es %s";
  private static final String ERR_ORDER_LINE_HAS_SHIPMENTS =
      "La línea %s del pedido ya tiene albaranes sin facturar: facture desde el albarán para "
          + "indicar cantidades parciales";
  private static final String ERR_SHIPMENT_LINE_EXCEEDS_PENDING =
      "No se puede facturar la línea %s: cantidad solicitada %s, pero lo pendiente de "
          + "facturar es %s (ya facturado o cubierto por una factura en borrador)";
  private static final String ERR_LINES_UNUSABLE =
      "Se enviaron 'lines' pero ninguna entrada tiene un orderLineId/shipmentLineId y una "
          + "cantidad mayor que cero";
  private static final String ERR_SHIPMENT_NOTHING_PENDING =
      "No hay líneas pendientes de facturar en este albarán";
  // ETP-4942 — surfaced as a 400 validation error instead of letting a null
  // Invoice.getPriceList() reach UpdatePricesAndAmounts and blow up as an
  // unguarded 500 further down the native invoice-line-creation pipeline.
  private static final String ERR_PRICE_LIST_REQUIRED =
      "No Price List could be resolved for this invoice: select a tariff or configure "
          + "a default Price List for the Business Partner";
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

    // GET pendingInvoiceLines — returns uninvoiced qty per shipment line (excludes drafts)
    if (PENDING_LINES_ACTION.equals(fieldName) && "GET".equals(context.getHttpMethod())) {
      return handlePendingLines(context);
    }

    // POST productPrices — prices a caller-supplied list of products against a price list
    if (PRODUCT_PRICES_ACTION.equals(fieldName) && "POST".equals(context.getHttpMethod())) {
      return handleProductPrices(context);
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
        MultiDocumentInvoiceSupport.requireParsedLines(body, PARAM_LINES, lineOverrides,
            ERR_LINES_UNUSABLE);

        List<String> existingDrafts = new ArrayList<>();
        Invoice invoice =
            createFromSource(specName, recordId, body, lineOverrides, existingDrafts);
        if (invoice == null) {
          return null;
        }

        OBDal.getInstance().flush();
        OBDal.getInstance().getSession().refresh(invoice);
        ensureDocumentNo(invoice);
        if (SPEC_GOODS_SHIPMENT.equals(specName)) {
          getSupport().ensureLineGrossAmounts(invoice);
          // ETP-5714: the native copy of a shipment line keeps a source list price of 0
          getSupport().alignLineListPrices(invoice, null);
          OBDal.getInstance().flush();
        }
        // ETP-5051: the plan-quota check of the generated invoice and its lines, BEFORE the
        // completion below commits (ProcessInvoiceUtil): a refusal rolls the whole generation back.
        requireQuotaAllowed(invoice);

        // ETP-5381: create and confirm in a single atomic step. A draft invoice reserves nothing
        // — c_orderline.qtyinvoiced and m_inoutline.isinvoiced are only written by C_Invoice_Post
        // — so leaving one behind lets the same order or shipment be invoiced a second time.
        // The id is captured BEFORE the call because ProcessInvoiceUtil commits and closes the
        // Hibernate session, leaving `invoice` detached; `completed` is read from the reopened
        // session, which also picks up the DocumentNo the completion may have reassigned from the
        // document type's sequence. On failure the internal rollback reverts the whole creation.
        String invoiceId = invoice.getId();
        // ETP-5840: an invoice generated from an order or quotation got its rate recomputed for its
        // own accounting date; a foreign-currency one without a rate is refused before completing,
        // and the generation rolled back (catch below) — nothing is left behind.
        if (!SPEC_GOODS_SHIPMENT.equals(specName)) {
          DocumentCurrencyRate.requireRateForCompletion(invoice, Invoice.PROPERTY_ETGOCURRENCYRATE);
        }
        InvoiceCompletionService.completeInvoiceOrThrow(invoiceId, context.getObContext());
        Invoice completed = OBDal.getInstance().get(Invoice.class, invoiceId);

        // After completion, not before: ETGO_CI must mean "a CONFIRMED invoice exists for this
        // quotation". Writing it first would mark the quotation even when the completion is
        // rolled back, leaving it closed with nothing to show for it.
        if (SPEC_SALES_QUOTATION.equals(specName)) {
          markQuotationAsInvoiceCreated(recordId);
        }

        JSONObject data = new JSONObject();
        data.put("id", invoiceId);
        data.put(FIELD_DOCUMENT_NO, completed.getDocumentNo());
        data.put("documentStatus", completed.getDocumentStatus());
        OrderInvoiceDuplicateGuard.listExisting(data, existingDrafts);

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);

        JSONObject wrapper = new JSONObject();
        wrapper.put(KEY_RESPONSE, responseData);

        return NeoResponse.created(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (NeoQuotaCreateCheck.Refused e) {
      return e.toResponse();
    } catch (DocumentCurrencyRate.MissingRateException e) {
      OBDal.getInstance().rollbackAndClose();
      log.warn("Refused to generate invoice from {}: {}", recordId, e.getMessage());
      return e.toResponse();
    } catch (OrderInvoiceDuplicateGuard.Refusal e) {
      // ETP-5714: a draft invoice already covers the order; nothing was created
      return e.getResponse();
    } catch (OBException e) {
      log.warn("Error creating draft invoice from {}: {}", specName, e.getMessage());
      return errorResponse(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating draft invoice from {}: {}", specName, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the invoice");
    }
  }

  /**
   * Creates the invoice from the source the spec names (order, quotation or goods shipment),
   * after that source's own precondition. Split out of {@link #handleCreate}, which was past the
   * cognitive-complexity limit.
   *
   * @param specName       the spec of the source document
   * @param recordId       the source document id
   * @param body           the request body
   * @param lineOverrides  the parsed {@code lines} quantities
   * @param existingDrafts out: the draft invoice numbers a sales order already had (ETP-5714)
   * @return the created (not yet completed) invoice, or {@code null} for a spec this handler does
   *     not serve
   */
  private Invoice createFromSource(String specName, String recordId, JSONObject body,
      Map<String, BigDecimal> lineOverrides, List<String> existingDrafts) {
    if (SPEC_SALES_ORDER.equals(specName)) {
      // ETP-5709: only the direct-from-order route. createFromOrder is also what the
      // quotation route calls, so the check cannot live there; a quotation never reaches CO
      // and keeps its own flow unchanged.
      requireOrderCompleted(recordId);
      // ETP-5714: one draft invoice per order, as on the screen (it hides the button then)
      existingDrafts.addAll(OrderInvoiceDuplicateGuard.draftsOf(recordId, true));
      OrderInvoiceDuplicateGuard.requireNoDraft(body, existingDrafts);
      return createFromOrder(recordId, lineOverrides, null);
    }
    if (SPEC_SALES_QUOTATION.equals(specName)) {
      // ETP-5709: the SPA offers this only from UE or CO; the route used to accept any
      // status (a draft or rejected quotation could be invoiced by guessing the action).
      requireQuotationInvoiceable(recordId);
      if (body != null && body.has(PARAM_LINES)) {
        throw new OBException(ERR_QUOTATION_LINES);
      }
      return createFromOrder(recordId, lineOverrides, null);
    }
    if (SPEC_GOODS_SHIPMENT.equals(specName)) {
      List<String> shipmentIds = parseShipmentIds(body, recordId);
      String priceListId = body != null ? body.optString(PARAM_PRICE_LIST_ID, null) : null;
      return createFromShipments(shipmentIds, lineOverrides, priceListId);
    }
    return null;
  }

  /**
   * Seam for tests: the plan-quota check of a generated invoice and its lines
   * ({@link NeoQuotaCreateCheck}).
   */
  protected void requireQuotaAllowed(Invoice invoice) {
    NeoQuotaCreateCheck.requireAllowedDocument(invoice, quotaGuard());
  }

  /** Seam for tests: the plan-quota guard of {@link #requireQuotaAllowed}. */
  protected QuotaWriteGuard quotaGuard() {
    return QuotaWriteGuard.getDefault();
  }

  /**
   * Seam for tests: refuses a quotation that is neither Under Evaluation ({@code UE}) nor
   * {@code CO}: the two statuses the SPA offers the action from (QuotationTopbarActions opens
   * QuotationConfirmModal for {@code CO} or {@code UE}; {@code CO} can only be a legacy quotation).
   */
  protected void requireQuotationInvoiceable(String quotationId) {
    Order quotation = OBDal.getInstance().get(Order.class, quotationId);
    if (quotation == null) {
      throw new OBException(ERR_ORDER_NOT_FOUND + quotationId);
    }
    DocumentStatusGuard.requireAnyStatus(quotation.getDocumentNo(), quotation.getDocumentStatus(),
        "La cotización %s no se puede facturar en su estado actual (estado %s): solo se factura "
            + "una cotización en evaluación (UE) o completada (CO)",
        DocumentStatusGuard.STATUS_UNDER_EVALUATION, DocumentStatusGuard.STATUS_COMPLETED);
  }

  /** Seam for tests: refuses a source order that is not completed (see {@link #handleCreate}). */
  protected void requireOrderCompleted(String orderId) {
    Order order = OBDal.getInstance().get(Order.class, orderId);
    if (order == null) {
      throw new OBException(ERR_ORDER_NOT_FOUND + orderId);
    }
    DocumentStatusGuard.requireCompleted(order.getDocumentNo(), order.getDocumentStatus(),
        "El pedido %s no está completado (estado %s): complétalo antes de crear la factura");
  }

  /**
   * Builds the {@code {status, message}} error body every frontend caller of this action reads as
   * {@code err.response.message}, falling back to a plain-text response if the JSON cannot be
   * assembled.
   */
  private NeoResponse errorResponse(int status, String message) {
    try {
      JSONObject body = new JSONObject();
      body.put("status", "error");
      body.put("message", message);
      return NeoResponse.error(status, body);
    } catch (Exception jsonEx) {
      return NeoResponse.error(status, message);
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
    return OrderInvoiceListSupport.listInvoices(context, true);
  }

  /**
   * Returns the uninvoiced (pending) quantity per shipment line for the given shipment, plus
   * each line's {@code product} and {@code salesOrderLine} — ETP-5410 follow-up: the quote
   * feature used to fetch these from a SEPARATE {@code goodsShipmentLine?parentId=} request,
   * adding one more round trip to every "Crear factura" modal open. They come from the same
   * already-loaded {@link ShipmentInOut}, so returning them here costs nothing extra over the
   * wire and lets the frontend drop that request entirely.
   * Mirrors the GREATEST(msi, direct) logic from {@code buildInvoiceStatusSql} in
   * {@link GoodsShipmentHeaderHandler} but operates at line granularity so the frontend
   * preview can show — and cap at — the remaining quantity, and so that
   * {@link #createFromShipments} never creates invoice lines for already-invoiced quantities.
   * Draft invoices are deliberately excluded (same as the status badge), allowing users
   * to invoice remaining units even when a draft exists for other lines.
   */
  protected NeoResponse handlePendingLines(NeoContext context) {
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Map<String, BigDecimal> pendingMap = computePendingQtyPerLine(recordId, true);
        ShipmentInOut doc = OBDal.getInstance().get(ShipmentInOut.class, recordId);
        Map<String, String[]> lineDetails = MultiDocumentInvoiceSupport.loadLineProductAndOrderLine(doc);
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, BigDecimal> entry : pendingMap.entrySet()) {
          JSONObject item = new JSONObject();
          item.put("lineId", entry.getKey());
          item.put("pendingQty", entry.getValue());
          String[] details = lineDetails.get(entry.getKey());
          item.put("product", details != null && details[0] != null ? details[0] : JSONObject.NULL);
          item.put("salesOrderLine", details != null && details[1] != null ? details[1] : JSONObject.NULL);
          arr.put(item);
        }
        JSONObject responseData = new JSONObject();
        responseData.put("data", arr);
        // ETP-5410 follow-up: also resolve the price list HERE (order → BP → client default),
        // so the caller's N=1 auto-select-Tarifa feature no longer needs a separate full
        // single-record GET just for this one field — that GET was the single heaviest request
        // in the "Crear factura" modal's opening waterfall.
        if (doc != null) {
          String resolvedPriceListId = MultiDocumentInvoiceSupport.resolveEffectivePriceListId(doc, true);
          responseData.put("resolvedPriceListId", resolvedPriceListId != null ? resolvedPriceListId : JSONObject.NULL);
        }
        JSONObject wrapper = new JSONObject();
        wrapper.put(KEY_RESPONSE, responseData);
        return new NeoResponse(200, wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error computing pending invoice lines for shipment {}", recordId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Prices a caller-supplied list of products against a price list — POST {@code
   * /goods-shipment/goodsShipment/{id}/action/productPrices}, body {@code {productIds: [...],
   * priceListId: "..."}}. {@code recordId} is not used: this exists only so the "Crear factura"
   * quote feature can price the handful of products its own pending lines actually reference,
   * instead of running the generic product-browse selector (up to 500 rows) and discarding all
   * but a few of them.
   *
   * <p>POST, not GET, because ACTION-endpoint dispatch never threads query-string parameters into
   * {@link NeoContext} (only the request body reaches it) — the same reason {@link #handleCheck}
   * already accepts POST for what is otherwise a pure read.
   *
   * <p>Delegates to {@link MultiDocumentInvoiceSupport#buildProductPricesResponse}, shared
   * byte-for-byte with {@code CreatePurchaseInvoiceHandler#handleProductPrices}.
   */
  protected NeoResponse handleProductPrices(NeoContext context) {
    return MultiDocumentInvoiceSupport.buildProductPricesResponse(context.getRequestBody(), log);
  }

  /**
   * Computes the uninvoiced quantity for every active line of the given shipment.
   * Uses the same GREATEST(msi_qty, direct_qty) pattern as the header-level invoice
   * status badge to avoid double-counting when both m_matchsi and c_invoiceline.m_inoutline_id
   * are populated (e.g. after m_inout_post). Draft, voided and closed invoices are excluded
   * so that lines in draft invoices can still be fully invoiced in a new document.
   *
   * @param shipmentId {@code M_InOut_ID} of the shipment to inspect
   * @return map of {@code M_InOutLine_ID → pending qty} (clamped to ≥ 0); empty on DB error
   */
  @SuppressWarnings("java:S2077")
  protected Map<String, BigDecimal> computePendingQtyPerLine(String shipmentId) {
    return computePendingQtyPerLine(shipmentId, false);
  }

  @SuppressWarnings("java:S2077")
  protected Map<String, BigDecimal> computePendingQtyPerLine(String shipmentId, boolean includeDrafts) {
    return NeoInvoiceSupport.computePendingQtyPerLine(shipmentId, includeDrafts);
  }

  /**
   * Seam for tests. Same computation as {@link #computePendingQtyPerLine(String, boolean)} but a
   * DB failure propagates (ETP-5709): every path that DECIDES what to invoice must use this one,
   * because the swallowing variant returns an empty map both for "nothing pending" and for "the
   * query failed".
   */
  protected Map<String, BigDecimal> computePendingQtyPerLineOrThrow(String shipmentId,
      boolean includeDrafts) {
    return NeoInvoiceSupport.computePendingQtyPerLineOrThrow(shipmentId, includeDrafts);
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
      // Cover both flows:
      // 1. Shipment linked to an order: find draft via salesOrder.id
      // 2. Standalone shipment (no order): find draft via invoice line → shipment line → shipment
      hql = "FROM Invoice i WHERE i.salesTransaction = true AND i.documentStatus = 'DR' AND ("
          + "  i.salesOrder.id IN ("
          + "    SELECT s.salesOrder.id FROM MaterialMgmtShipmentInOut s"
          + "    WHERE s.id IN :ids AND s.salesOrder IS NOT NULL"
          + "  ) OR EXISTS ("
          + "    SELECT 1 FROM InvoiceLine il"
          + "    WHERE il.invoice.id = i.id"
          + "    AND il.goodsShipmentLine IS NOT NULL"
          + "    AND il.goodsShipmentLine.shipmentReceipt.id IN :ids"
          + "  )"
          + ") ORDER BY i.creationDate DESC";
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
    String current = invoice.getDocumentNo();
    if (StringUtils.isNotBlank(current) && !current.startsWith("<")) {
      return;
    }
    String docNo = generateInvoiceDocumentNo(invoice);
    if (StringUtils.isBlank(docNo) && invoice.getClient() != null) {
      // generateInvoiceDocumentNo relies on RequestContext which may be absent in
      // backend action contexts (e.g. createReturnInvoice from NeoServlet). Fall back
      // to the same table-level sequence approach that CreatePurchaseInvoiceHandler uses.
      docNo = Utility.getDocumentNoConnection(
          OBDal.getInstance().getConnection(false),
          new DalConnectionProvider(false),
          invoice.getClient().getId(),
          "C_Invoice",
          true);
    }
    if (StringUtils.isBlank(docNo)) {
      log.warn(
          "Could not generate documentNo for invoice {} (docType={}, client={}). "
              + "Configure DocNoSequence_ID on the document type or activate "
              + "AD_Sequence 'DocumentNo_C_Invoice' for the client.",
          invoice.getId(),
          invoice.getDocumentType() != null ? invoice.getDocumentType().getId() : "null",
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
    if (body == null || !body.has(PARAM_LINES)) {
      return overrides;
    }
    try {
      JSONArray lines = body.getJSONArray(PARAM_LINES);
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
  InvoiceFromOrderSupport getSupport() {
    return invoiceFromOrderSupport != null ? invoiceFromOrderSupport : new InvoiceFromOrderSupport();
  }

  protected Invoice createFromOrder(String orderId, Map<String, BigDecimal> lineOverrides,
      String priceListId) {
    // ETP-5709: this is the entry for a caller's own `lines` (order and quotation routes), whose
    // explicit quantities are refused when not invoiceable, never capped silently. The single-
    // shipment route no longer comes through here: it builds its own selection (see
    // buildShipmentScopedOrderSelection).
    if (!lineOverrides.isEmpty()) {
      Order order = OBDal.getInstance().get(Order.class, orderId);
      if (order == null) {
        throw new OBException(ERR_ORDER_NOT_FOUND + orderId);
      }
      requireOverridesWithinPending(order, lineOverrides);
      requireNoUninvoicedShipments(order, lineOverrides);
    }
    return createFromOrder(orderId, lineOverrides, priceListId, null);
  }

  /**
   * Seam (DB): whether the order line has completed shipment lines not yet invoiced — the same
   * population the native {@code CreateLinesFromUtil#getRelatedNotInvoicedInOutLines} reads for a
   * sales order line (processed, completed or closed shipment, not completely invoiced, line not
   * yet invoiced), which is the one that replaces an order-line entry by those shipment lines.
   */
  protected boolean hasUninvoicedShipmentLines(OrderLine orderLine) {
    Long count = OBDal.getInstance()
        .getSession()
        .createQuery("select count(il.id) from MaterialMgmtShipmentInOutLine as il "
            + "join il.shipmentReceipt sh where il.salesOrderLine.id = :orderLineId "
            + "and sh.processed = 'Y' and sh.documentStatus in ('CO', 'CL') "
            + "and sh.completelyInvoiced = 'N' and il.reinvoice = 'N'", Long.class)
        .setParameter("orderLineId", orderLine.getId())
        .uniqueResult();
    return count != null && count > 0;
  }

  /**
   * Refuses explicit quantities for an order line that has completed shipment lines not yet
   * invoiced (ETP-5709). The native process would replace the entry by ALL of those shipment lines
   * at their full quantity and ignore the quantity asked for; allocating a partial quantity over
   * shipments is deliberately not implemented, so the caller is told to invoice from the shipment,
   * where quantities per line are honoured. Order lines without shipments keep working: the
   * native process uses their entry as is.
   */
  private void requireNoUninvoicedShipments(Order order, Map<String, BigDecimal> lineOverrides) {
    for (OrderLine ol : order.getOrderLineList()) {
      if (lineOverrides.containsKey(ol.getId()) && hasUninvoicedShipmentLines(ol)) {
        throw new OBException(String.format(ERR_ORDER_LINE_HAS_SHIPMENTS,
            ol.getLineNo() + " (" + ol.getId() + ")"));
      }
    }
  }

  /**
   * Refuses an explicit per-line quantity that is not invoiceable (ETP-5709): the line has
   * nothing pending, or the quantity has the wrong sign or exceeds the pending magnitude. An
   * agent must learn that its request cannot be honoured, so nothing is capped silently.
   */
  private void requireOverridesWithinPending(Order order, Map<String, BigDecimal> lineOverrides) {
    for (OrderLine ol : order.getOrderLineList()) {
      BigDecimal requested = lineOverrides.get(ol.getId());
      if (requested == null) {
        continue;
      }
      BigDecimal pending = resolvePendingForLine(ol, false, Collections.emptyMap());
      if (pending == null || requested.signum() != pending.signum()
          || requested.abs().compareTo(pending.abs()) > 0) {
        throw new OBException(String.format(ERR_ORDER_LINE_EXCEEDS_PENDING,
            ol.getLineNo() + " (" + ol.getId() + ")", requested.toPlainString(),
            pending == null ? "0" : pending.toPlainString()));
      }
    }
  }

  /**
   * @param currencyId
   *     when invoicing from a shipment (ETP-4028), the shipment's own currency —
   *     overrides the order's currency, which can diverge once the user edits the
   *     shipment's currency in draft. {@code null} for direct order/quotation invoicing,
   *     where the order's own currency is correct as-is.
   */
  protected Invoice createFromOrder(String orderId, Map<String, BigDecimal> lineOverrides,
      String priceListId, String currencyId) {
    Order order = OBDal.getInstance().get(Order.class, orderId);
    if (order == null) {
      throw new OBException(ERR_ORDER_NOT_FOUND + orderId);
    }

    JSONArray selectedLines = buildSelectedLinesForOrder(order, lineOverrides);
    if (selectedLines.length() == 0) {
      throw new OBException("No hay líneas a facturar en este pedido");
    }
    return createFromOrderLines(orderId, selectedLines, priceListId, currencyId);
  }

  /**
   * Creates the invoice from an already-built {@code selectedLines} selection of the order's
   * lines (see {@link #buildSelectedLinesForOrder}): header from the order, lines by the native
   * {@code CreateInvoiceLinesFromProcess}. Split out of {@link #createFromOrder} so the
   * single-shipment route (ETP-5709) can hand it entries scoped to ITS shipment lines.
   */
  protected Invoice createFromOrderLines(String orderId, JSONArray selectedLines, String priceListId,
      String currencyId) {
    Order order = OBDal.getInstance().get(Order.class, orderId);
    if (order == null) {
      throw new OBException(ERR_ORDER_NOT_FOUND + orderId);
    }

    DocumentType invoiceDocType = resolveARInvoiceDocType(order);

    Invoice invoice = NeoCommercialDocumentFactory.createInvoiceFromOrderHeader(
        order,
        invoiceDocType,
        true);
    applyPriceListOverride(invoice, priceListId);
    applyCurrencyOverride(invoice, currencyId);
    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();

    // Delegate line creation to native Etendo process — handles taxes, gross prices, IVA-included, etc.
    // UpdatePricesAndAmounts (a CreateLinesFromProcessHook) reads getInvoice().getPriceList() and
    // getInvoice().getCurrency() off this already-saved invoice, so the override above is picked
    // up automatically — no changes needed to the native process itself.
    CreateInvoiceLinesFromProcess proc = WeldUtils.getInstanceFromStaticBeanManager(
        CreateInvoiceLinesFromProcess.class);
    proc.createInvoiceLinesFromDocumentLines(selectedLines, invoice, OrderLine.class);

    OBDal.getInstance().flush();

    // Carry over the per-line discount percentage (OrderLine.discount, standard
    // Etendo column) into the EM_Etgo_Discount field on each invoice line so the
    // frontend can display the discount % and compute the totals breakdown.
    // The native process already copies unitPrice/listPrice/lineNetAmount, but
    // c_invoiceline has no standard discount column — it lives in the EM_ extension.
    // ETP-4780: moved into InvoiceFromOrderSupport so CreatePurchaseInvoiceHandler
    // can reuse the same behaviour without duplication.
    getSupport().copyLineDiscountsFromOrder(invoice);
    // ETP-5714: a source line without a list price gives a copied line the form would not store
    getSupport().alignLineListPrices(invoice, null);

    InvoiceLineLinker.linkInvoiceLinesToExistingInouts(invoice.getId());

    OBDal.getInstance().getSession().refresh(invoice);
    invoice = getSupport().applyOrderDiscountToInvoice(invoice, orderId, totalDiscountService);
    getSupport().ensureLineGrossAmounts(invoice);

    getSupport().propagateOrderRateToInvoice(order, invoice);

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
        entry.put(FIELD_ORDERED_QUANTITY, pending.toPlainString());
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
    // Skip ETGO_DTO total-discount lines on the source order: the invoice carries
    // em_etgo_total_discount on its header and the matching discount line is
    // materialized at completion time by AbstractOrderHeaderHandler. Copying the
    // source line here would leave a stale duplicate after that recalculation.
    if (TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(ol.getProduct().getId())) return null;
    if (hasOverrides && !lineOverrides.containsKey(ol.getId())) return null;
    BigDecimal ordered = ol.getOrderedQuantity() != null ? ol.getOrderedQuantity() : BigDecimal.ZERO;
    BigDecimal invoiced = ol.getInvoicedQuantity() != null ? ol.getInvoicedQuantity() : BigDecimal.ZERO;
    BigDecimal pending = ordered.subtract(invoiced);
    // ETP-4722: quantities can be NEGATIVE since ETP-4567 removed the old
    // min: 0 constraint (e.g. a return-style order line), so "nothing left" is not
    // "pending <= 0". (Used by the order and quotation routes; "Crear factura" on a Goods
    // Shipment linked to an order builds its own shipment-scoped selection since ETP-5709.)
    // ETP-5709: a pending quantity only counts when it has the SAME sign as the ordered
    // quantity: ordered 2 / invoiced 4 is pending -2, an OVER-invoiced line, and invoicing it
    // would create a -2 invoice line. A legit negative line (ordered -3, invoiced 0 -> -3)
    // keeps the ordered sign and is still invoiced.
    if (pending.signum() == 0 || pending.signum() != ordered.signum()) return null;
    if (hasOverrides) {
      BigDecimal override = lineOverrides.get(ol.getId());
      if (override == null) return pending;
      // Never above the pending magnitude, never against the line's sign.
      if (override.signum() != pending.signum()) return null;
      return override.abs().min(pending.abs()).multiply(BigDecimal.valueOf(pending.signum()));
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
    return MultiDocumentInvoiceSupport.parseDocumentIds(body, PARAM_SHIPMENT_IDS, recordId, log);
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
  protected Invoice createFromShipments(List<String> shipmentIds, Map<String, BigDecimal> lineOverrides,
      String priceListId) {
    List<ShipmentInOut> shipments = loadAndValidateShipments(shipmentIds);
    ShipmentInOut first = shipments.get(0);

    // Single shipment with a linked order: delegate to the order-based creation so that
    // CreateInvoiceLinesFromProcess handles price-list lookups and taxes as Etendo core does —
    // avoiding the manual pricelist=0 issue. The selection is scoped to THIS shipment's lines
    // (ETP-5709), see buildShipmentScopedOrderSelection.
    if (shipments.size() == 1 && first.getSalesOrder() != null) {
      JSONArray selectedLines = buildShipmentScopedOrderSelection(first, lineOverrides);
      String currencyId = first.getEtgoCurrency() != null ? first.getEtgoCurrency().getId() : null;
      String orderId = first.getSalesOrder().getId();
      evictShipmentFromSession(first);
      return createFromOrderLines(orderId, selectedLines, priceListId, currencyId);
    }

    Invoice invoice = createInvoiceHeaderFromShipment(first, shipments);
    applyPriceListOverride(invoice, priceListId);
    ensurePriceListResolved(invoice);

    // ETP-5709: validate BEFORE the header is saved — the invoice is still transient here, so
    // nothing has been written on a path that is about to answer 400. After the price-list
    // guard so ETP-4942's more specific message keeps winning. The throwing variant: a DB
    // failure must surface as an error, not as an empty map that used to mean "invoice
    // everything".
    Map<String, BigDecimal> pendingQtyMap = computePendingForShipments(shipments);
    if (pendingQtyMap.isEmpty()) {
      throw new OBException(ERR_SHIPMENT_NOTHING_PENDING);
    }
    MultiDocumentInvoiceSupport.requireOverridesWithinPending(shipments, lineOverrides,
        pendingQtyMap, ERR_SHIPMENT_LINE_EXCEEDS_PENDING);

    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();
    addShipmentLinesToInvoice(invoice, shipments, lineOverrides);
    return invoice;
  }

  /** Merged draft-aware pending quantities of {@code shipments}; propagates DB failures. */
  private Map<String, BigDecimal> computePendingForShipments(List<ShipmentInOut> shipments) {
    Map<String, BigDecimal> merged = new HashMap<>();
    for (ShipmentInOut shipment : shipments) {
      merged.putAll(computePendingQtyPerLineOrThrow(shipment.getId(), true));
    }
    return merged;
  }

  /**
   * Overrides the invoice's price list — used by the "invoice from shipment" flow (ETP-4028),
   * where the tariff is chosen explicitly by the user in a confirmation popup rather than
   * inherited from the source order/business partner. A no-op when {@code priceListId} is
   * blank or does not resolve to an active {@link PriceList}.
   *
   * <p>Must be called BEFORE {@code CreateInvoiceLinesFromProcess} runs: the native
   * {@code UpdatePricesAndAmounts} hook prices every line off {@code getInvoice().getPriceList()}
   * read from this already-saved invoice, so overriding it here is sufficient — no changes
   * needed to the native pricing process.
   */
  private void applyPriceListOverride(Invoice invoice, String priceListId) {
    if (StringUtils.isBlank(priceListId)) {
      return;
    }
    PriceList priceList = OBDal.getInstance().get(PriceList.class, priceListId);
    if (priceList != null) {
      invoice.setPriceList(priceList);
    }
  }

  /**
   * Fails fast with a clear 400 when neither the shipment's linked order, the
   * Business Partner's default tariff, nor an explicit {@code priceListId} override
   * resolved a price list (ETP-4942 — a shipment with no linked sales order and a
   * Business Partner with no default Price List used to reach {@code
   * UpdatePricesAndAmounts} with a null {@code Invoice.getPriceList()}, which threw an
   * unguarded NPE surfaced to the caller as a 500). Must run AFTER {@link
   * #applyPriceListOverride}, which is the last chance to fill it in.
   */
  private void ensurePriceListResolved(Invoice invoice) {
    if (invoice.getPriceList() == null) {
      throw new OBException(ERR_PRICE_LIST_REQUIRED);
    }
  }

  /**
   * Overrides the invoice's currency with the shipment's own (editable-until-confirmed)
   * currency — used by the "invoice from shipment" flow (ETP-4028), where
   * {@code createInvoiceFromOrderHeader} otherwise defaults it to the linked order's
   * currency, which can diverge once the user edits the shipment's currency in draft.
   * A no-op when {@code currencyId} is blank or does not resolve to an active
   * {@link Currency}. Must be called before {@code OBDal.save} for the same reason as
   * {@link #applyPriceListOverride}.
   */
  private void applyCurrencyOverride(Invoice invoice, String currencyId) {
    if (StringUtils.isBlank(currencyId)) {
      return;
    }
    Currency currency = OBDal.getInstance().get(Currency.class, currencyId);
    if (currency != null) {
      invoice.setCurrency(currency);
    }
  }

  /**
   * Detaches the shipment and its lines before the native process links invoice lines to them by
   * id — the same precaution {@code CreatePurchaseInvoiceHandler#createFromReceipt} takes (the
   * process saves/loads those movement lines itself and fails with an EntityExistsException when
   * the handler already holds them in the session). Everything needed from the shipment is read
   * before this call.
   */
  protected void evictShipmentFromSession(ShipmentInOut shipment) {
    for (ShipmentInOutLine sl : shipment.getMaterialMgmtShipmentInOutLineList()) {
      OBDal.getInstance().getSession().evict(sl);
    }
    OBDal.getInstance().getSession().evict(shipment);
  }

  /**
   * The {@code selectedLines} of the single-shipment-with-order route (ETP-5709): ONE entry per
   * line of THIS shipment, {@code {id: orderLineId, orderedQuantity, shipmentInOutLine: lineId}},
   * at that line's draft-aware pending quantity or the caller's explicit {@code lines} quantity.
   *
   * <p>It used to hand the order to the native process with an EMPTY selection, which decides the
   * quantities from the order line: invoiced shipped quantities were safe (an order line with
   * completed, not-yet-invoiced shipment lines is replaced by those lines), but a second call on an
   * already-invoiced shipment invoiced the order line's remaining, never-shipped quantity, and
   * invoicing shipment A of an order shipped in A and B also invoiced B. Naming the shipment line
   * makes the native process use the entry as is. Invoiced from the shipment, a shipment of 3 from
   * an order of 5 yields 3 (live-verified), and an explicit partial quantity is honoured.
   *
   * <p>Everything is validated BEFORE anything is created: the pending query is the throwing
   * variant (a DB failure surfaces as such), a quantity above pending, against the line's sign or
   * for a line with nothing pending is a 400 naming the line (never a silent cap), and nothing
   * pending is a 400. Shipment lines without an order line cannot be invoiced through the order
   * and are skipped.
   */
  private JSONArray buildShipmentScopedOrderSelection(ShipmentInOut shipment,
      Map<String, BigDecimal> lineOverrides) {
    Map<String, BigDecimal> pending = computePendingQtyPerLineOrThrow(shipment.getId(), true);
    // Explicit lines first: against an empty pending map they are refused naming the line.
    MultiDocumentInvoiceSupport.requireOverridesWithinPending(List.of(shipment), lineOverrides,
        pending, ERR_SHIPMENT_LINE_EXCEEDS_PENDING);
    if (pending.isEmpty()) {
      throw new OBException(ERR_SHIPMENT_NOTHING_PENDING);
    }
    boolean hasOverrides = !lineOverrides.isEmpty();
    JSONArray selectedLines = new JSONArray();
    for (ShipmentInOutLine sl : shipment.getMaterialMgmtShipmentInOutLineList()) {
      BigDecimal qty = MultiDocumentInvoiceSupport.resolveInOutLineQty(sl, hasOverrides,
          lineOverrides, pending);
      if (qty == null || sl.getSalesOrderLine() == null) {
        continue;
      }
      try {
        selectedLines.put(MultiDocumentInvoiceSupport.buildOrderLineEntryForInOutLine(
            sl.getSalesOrderLine().getId(), sl, qty));
      } catch (Exception e) {
        log.warn("Failed to build invoice line entry for shipment line {}: {}", sl.getId(),
            e.getMessage());
      }
    }
    if (selectedLines.length() == 0) {
      throw new OBException(ERR_SHIPMENT_NOTHING_PENDING);
    }
    return selectedLines;
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
    List<ShipmentInOut> shipments = MultiDocumentInvoiceSupport.loadAndValidateSameBusinessPartner(
        shipmentIds, "Shipment not found: ", "No shipments provided",
        "All shipments must belong to the same Business Partner");
    // ETP-5709: every shipment that feeds an invoice must be completed, checked before anything
    // is created (the classic Invoicefromshipment button, redirected here, checked it).
    for (ShipmentInOut shipment : shipments) {
      DocumentStatusGuard.requireCompleted(shipment.getDocumentNo(), shipment.getDocumentStatus(),
          "El albarán %s no está completado (estado %s): complétalo antes de crear la factura");
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
    // ETP-4028: the invoice's currency is always inherited from the shipment's own
    // (editable-until-confirmed) currency, never from the linked order or BP default —
    // those can diverge from the shipment once the user changes it in draft.
    invoice.setCurrency(first.getEtgoCurrency());
    invoice.setSummedLineAmount(BigDecimal.ZERO);
    invoice.setGrandTotalAmount(BigDecimal.ZERO);
    invoice.setWithholdingamount(BigDecimal.ZERO);

    // ETP-4888: this header is built directly via OBProvider, bypassing the normal NEO CRUD
    // "new record" HTTP path that would otherwise resolve every declared contract.json
    // derivation (e.g. SII/SIF fields like etsgDateOperation). Fields already set above are
    // never overwritten — only properties still blank are filled in.
    NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity("sales-invoice", "header",
        invoice, first.getId());

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
    // ETP-5709: the throwing variant — the swallowing one returned an empty map on a DB error,
    // which read as "no pending information" and invoiced every line in full.
    Map<String, BigDecimal> pendingQtyMap = computePendingForShipments(shipments);

    boolean hasOverrides = !lineOverrides.isEmpty();
    JSONArray selectedLines = new JSONArray();
    for (ShipmentInOut shipment : shipments) {
      for (ShipmentInOutLine sl : shipment.getMaterialMgmtShipmentInOutLineList()) {
        BigDecimal qty = resolveShipmentLineQty(sl, hasOverrides, lineOverrides, pendingQtyMap);
        if (qty == null) {
          continue;
        }
        try {
          JSONObject entry = new JSONObject();
          entry.put("id", sl.getId());
          entry.put(FIELD_ORDERED_QUANTITY, qty.toPlainString());
          selectedLines.put(entry);
        } catch (Exception e) {
          log.warn("Failed to build invoice line entry for shipment line {}: {}", sl.getId(), e.getMessage());
        }
      }
    }
    if (selectedLines.length() == 0) {
      throw new OBException(ERR_SHIPMENT_NOTHING_PENDING);
    }
    createInvoiceLinesFromShipmentLines(selectedLines, invoice);
  }

  protected void createInvoiceLinesFromShipmentLines(JSONArray selectedLines, Invoice invoice) {
    CreateInvoiceLinesFromProcess proc =
        WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class);
    proc.createInvoiceLinesFromDocumentLines(selectedLines, invoice, ShipmentInOutLine.class);
  }

  /**
   * Backward-compatible 3-arg overload. Delegates to the 4-arg variant with a {@code null}
   * pending-qty map ("no pending information"), which falls back to movement quantity for
   * every line (same behaviour as before pending-qty awareness was added). It is deliberately
   * {@code null}, not an empty map: a non-null map is authoritative and an empty one means
   * "nothing pending" (ETP-5709).
   */
  protected BigDecimal resolveShipmentLineQty(ShipmentInOutLine sl, boolean hasOverrides,
      Map<String, BigDecimal> lineOverrides) {
    return resolveShipmentLineQty(sl, hasOverrides, lineOverrides, null);
  }

  /**
   * Determines the quantity to invoice for a single shipment line, capped at
   * the uninvoiced (pending) quantity so that already-invoiced lines are skipped.
   * Returns {@code null} when the line is inactive, excluded by overrides,
   * has zero/null movement quantity, or is already fully invoiced.
   * When overrides are present the result is capped at min(|override|, |pendingQty|),
   * with the shipment line's own sign reapplied to the result.
   *
   * <p><b>ETP-4567:</b> movement quantity can be NEGATIVE (return-style shipment
   * line, since ETP-4567 removed the {@code min: 0} constraint on order/shipment
   * lines). {@code pendingQtyMap} (see {@link #computePendingQtyPerLine}) is itself
   * magnitude-only — its underlying query {@code ABS()}s both movement and invoiced
   * quantities at the SQL level so that a negative return-style line and a positive
   * shipment line are tracked on the same sign-agnostic "remaining magnitude" scale.
   * This method therefore does all its pending-quantity arithmetic in magnitude
   * (absolute-value) space and reapplies the line's own sign only at the very end —
   * comparing/clamping a negative {@code movementQty} directly against that
   * magnitude-only map (or a {@code max(ZERO)} clamp) would silently destroy the
   * sign or drop the line outright, exactly as the sibling {@code
   * CreatePurchaseInvoiceHandler#getPendingQuantity} bug did for fully-negative
   * purchase orders (QA "Hallazgo 1").
   *
   * @param sl
   *     the shipment line to evaluate
   * @param hasOverrides
   *     whether {@code lineOverrides} is non-empty
   * @param lineOverrides
   *     caller-supplied quantity caps per shipment line
   * @param pendingQtyMap
   *     pre-computed uninvoiced qty MAGNITUDE per {@code M_InOutLine_ID}; a line absent from
   *     the map has nothing pending (ETP-5709); {@code null} means no pending information
   *     and falls back to the movement quantity (backward-compat behaviour)
   * @return quantity to invoice (sign matches the shipment line), or {@code null} to skip this line
   */
  protected BigDecimal resolveShipmentLineQty(ShipmentInOutLine sl, boolean hasOverrides,
      Map<String, BigDecimal> lineOverrides, Map<String, BigDecimal> pendingQtyMap) {
    return MultiDocumentInvoiceSupport.resolveInOutLineQty(sl, hasOverrides, lineOverrides, pendingQtyMap);
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

  // ---------------------------------------------------------------------------
  // Legacy shipment-line invoice creation — used by ReturnShipmentUtils
  // ---------------------------------------------------------------------------

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
      applyPricesFromOrderLine(il, ol, qty, invoice);
    } else {
      InvoiceLine sourceIL = findLinkedInvoiceLine(sl.getId());
      if (sourceIL != null) {
        applyPricesFromInvoiceLine(il, sourceIL, qty, invoice);
      } else {
        il.setUnitPrice(BigDecimal.ZERO);
        il.setListPrice(BigDecimal.ZERO);
        il.setLineNetAmount(BigDecimal.ZERO);
      }
    }
    return il;
  }

  private void applyPricesFromOrderLine(InvoiceLine il, OrderLine ol, BigDecimal qty, Invoice invoice) {
    BigDecimal unitPrice = ol.getUnitPrice() != null ? ol.getUnitPrice() : BigDecimal.ZERO;
    BigDecimal rawList  = ol.getListPrice();
    String productId = ol.getProduct() != null ? ol.getProduct().getId() : null;
    String priceListId = invoice.getPriceList() != null ? invoice.getPriceList().getId() : null;
    BigDecimal listPrice = (rawList != null && rawList.compareTo(BigDecimal.ZERO) > 0)
        ? rawList
        : resolveListPriceFromPriceList(productId, priceListId, unitPrice);
    il.setUnitPrice(unitPrice);
    il.setListPrice(listPrice);
    il.setPriceLimit(ol.getPriceLimit());
    int precision = invoice.getCurrency().getStandardPrecision().intValue();
    il.setLineNetAmount(qty.multiply(unitPrice).setScale(precision, RoundingMode.HALF_UP));
    if (ol.getGrossUnitPrice() != null) il.setGrossUnitPrice(ol.getGrossUnitPrice());
    if (ol.getGrossListPrice() != null) il.setGrossListPrice(ol.getGrossListPrice());
    if (ol.getBaseGrossUnitPrice() != null) il.setBaseGrossUnitPrice(ol.getBaseGrossUnitPrice());
    il.setTax(ol.getTax());
    il.setSalesOrderLine(ol);
  }

  private void applyPricesFromInvoiceLine(InvoiceLine il, InvoiceLine sourceIL, BigDecimal qty, Invoice invoice) {
    BigDecimal unitPrice = sourceIL.getUnitPrice() != null ? sourceIL.getUnitPrice() : BigDecimal.ZERO;
    BigDecimal rawList  = sourceIL.getListPrice();
    String productId = sourceIL.getProduct() != null ? sourceIL.getProduct().getId() : null;
    String priceListId = invoice.getPriceList() != null ? invoice.getPriceList().getId() : null;
    BigDecimal listPrice = (rawList != null && rawList.compareTo(BigDecimal.ZERO) > 0)
        ? rawList
        : resolveListPriceFromPriceList(productId, priceListId, unitPrice);
    il.setUnitPrice(unitPrice);
    il.setListPrice(listPrice);
    if (sourceIL.getPriceLimit() != null) il.setPriceLimit(sourceIL.getPriceLimit());
    int precision = invoice.getCurrency().getStandardPrecision().intValue();
    il.setLineNetAmount(qty.multiply(unitPrice).setScale(precision, RoundingMode.HALF_UP));
    if (sourceIL.getGrossUnitPrice() != null) il.setGrossUnitPrice(sourceIL.getGrossUnitPrice());
    if (sourceIL.getGrossListPrice() != null) il.setGrossListPrice(sourceIL.getGrossListPrice());
    if (sourceIL.getBaseGrossUnitPrice() != null) il.setBaseGrossUnitPrice(sourceIL.getBaseGrossUnitPrice());
    if (sourceIL.getTax() != null) il.setTax(sourceIL.getTax());
    if (sourceIL.getSalesOrderLine() != null) il.setSalesOrderLine(sourceIL.getSalesOrderLine());
  }

  @SuppressWarnings("java:S2077")
  private BigDecimal resolveListPriceFromPriceList(String productId, String priceListId, BigDecimal fallback) {
    if (productId == null || priceListId == null) return fallback;
    String sql =
        "SELECT pp.pricelist FROM m_productprice pp " +
        "JOIN m_pricelist_version plv ON plv.m_pricelist_version_id = pp.m_pricelist_version_id " +
        "WHERE pp.m_product_id = ? AND plv.m_pricelist_id = ? " +
        "AND plv.isactive = 'Y' AND pp.isactive = 'Y' AND plv.validfrom <= CURRENT_DATE " +
        "ORDER BY plv.validfrom DESC LIMIT 1";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, productId);
      ps.setString(2, priceListId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          BigDecimal listed = rs.getBigDecimal(1);
          return (listed != null && listed.compareTo(BigDecimal.ZERO) > 0) ? listed : fallback;
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve list price for product {} from price list {}: {}", productId, priceListId, e.getMessage());
    }
    return fallback;
  }

  protected InvoiceLine findLinkedInvoiceLine(String shipmentLineId) {
    try {
      List<InvoiceLine> lines = OBDal.getInstance().getSession()
          .createQuery(
              "FROM InvoiceLine il WHERE il.goodsShipmentLine.id = :slId " +
              "AND il.invoice.documentStatus NOT IN ('VO', 'CL') " +
              "ORDER BY il.invoice.invoiceDate DESC",
              InvoiceLine.class)
          .setParameter("slId", shipmentLineId)
          .setMaxResults(1)
          .list();
      return lines.isEmpty() ? null : lines.get(0);
    } catch (Exception e) {
      log.warn("Could not find linked invoice line for shipment line {}: {}", shipmentLineId, e.getMessage());
      return null;
    }
  }
}
