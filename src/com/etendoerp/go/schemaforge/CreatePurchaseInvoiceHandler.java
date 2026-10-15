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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.common.actionhandler.createlinesfromprocess.CreateInvoiceLinesFromProcess;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.util.DocumentStatusGuard;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * NeoHandler that creates a draft Purchase Invoice from a Purchase Order or Goods Receipt.
 * Invoked as an ACTION endpoint via:
 *   POST /sws/neo/purchase-order/header/{recordId}/action/createPurchaseInvoice
 *   POST /sws/neo/goods-receipt/{entity}/{recordId}/action/createPurchaseInvoice
 */
@Named("createPurchaseInvoiceHandler")
public class CreatePurchaseInvoiceHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreatePurchaseInvoiceHandler.class);
  private static final String ACTION_NAME = "createPurchaseInvoice";
  private static final String LIST_ACTION = "listInvoices";
  private static final String PENDING_LINES_ACTION = "pendingInvoiceLines";
  private static final String PRODUCT_PRICES_ACTION = "productPrices";
  private static final String SPEC_PURCHASE_ORDER = "purchase-order";
  private static final String SPEC_GOODS_RECEIPT = "goods-receipt";
  private static final String FIELD_ORDERED_QUANTITY = "orderedQuantity";
  private static final String PARAM_RECEIPT_IDS = "receiptIds";
  private static final String PARAM_PRICE_LIST_ID = "priceListId";
  private static final String PARAM_LINES = "lines";
  private static final String ERR_RECORD_ID_REQUIRED = "Record ID is required";
  private static final String KEY_RESPONSE = "response";
  private static final String ERR_NO_AP_INVOICE_DOC_TYPE = "No AP Invoice document type found";
  // ETP-4942 — same guard as CreateDraftInvoiceHandler#ensurePriceListResolved, surfaced as a
  // 400 instead of letting a null Invoice.getPriceList() reach UpdatePricesAndAmounts and blow
  // up as an unguarded 500 further down the native invoice-line-creation pipeline. The single-
  // receipt path (createFromReceipt / createFromReceiptNoPo) never needed this because it
  // always has either the linked order's price list or the BP's own purchase tariff to fall
  // back on; the multi-receipt path can legitimately have neither (different POs, no common
  // order, BP with no default tariff) until the user picks one explicitly.
  private static final String ERR_PRICE_LIST_REQUIRED =
      "No Price List could be resolved for this invoice: select a tariff or configure "
          + "a default Price List for the Business Partner";
  /**
   * What an agent reads about {@code createPurchaseInvoice} on a purchase order (ETP-5709): no
   * parameters. The MCP publishes it and judges the call against it; REST and the SPA do not
   * validate against it.
   */
  static final NeoActionContract ORDER_CONTRACT = NeoActionContract.write(ACTION_NAME,
      "Creates a purchase invoice from the lines of this purchase order that are still pending "
          + "invoicing and COMPLETES it in the same call (created AND completed, there is no "
          + "draft step), returning 201 with {id, documentNo, documentStatus}. Precondition: the "
          + "order is completed (status CO), otherwise 400 naming its status. It is independent of createGoodsReceipt: an order can be "
          + "invoiced without being received, received without being invoiced, or both. Typical "
          + "sequence for an order: documentAction (confirm) -> createGoodsReceipt -> "
          + "createPurchaseInvoice. A line is invoiced only for what is still pending (ordered "
          + "minus invoiced, same sign as the ordered quantity): an already-invoiced or "
          + "over-invoiced line is never invoiced again, so a second call does not duplicate "
          + "the invoice and, once nothing is pending, answers 400 'No pending lines to invoice "
          + "in this purchase order'. Only COMPLETED invoices count as invoiced: an invoice "
          + "left in draft by other means does not reduce what is pending.")
      .withIdDescription("the id of the purchase order to invoice");

  /**
   * The goods-receipt variant of {@code createPurchaseInvoice} (ETP-5709): the optional body keys
   * {@link #createFromReceipt} / {@link #createFromReceipts} read — {@code receiptIds},
   * {@code priceListId} and {@code lines}. The SPA posts {@code priceListId} (and
   * {@code receiptIds} from the bulk action), so all three are declared.
   */
  static final NeoActionContract RECEIPT_CONTRACT = NeoActionContract.write(ACTION_NAME,
      "Creates a purchase invoice from this goods receipt (or from several receipts together, "
          + "see receiptIds) and COMPLETES it in the same call (created AND completed, there is "
          + "no draft step), returning 201 with {id, documentNo, documentStatus}. Precondition: "
          + "the receipt is completed (status CO; every receipt of receiptIds), otherwise 400 naming "
          + "the receipt and its status. It is independent of the order's own createPurchaseInvoice: "
          + "only the receipt, only the invoice, or both can be generated for an order. It "
          + "invoices only what is still pending on each receipt line (received minus invoiced, "
          + "draft invoices included), so a second call does not duplicate the invoice: once "
          + "nothing is pending it answers 400 'Goods receipt <no> has nothing left to invoice' "
          + "(several receipts: 'Goods receipts <no>, <no> have nothing left to invoice'). By "
          + "default it invoices the pending quantity of every line. The invoice is scoped to the "
          + "lines of the receipt(s) sent: other receipts of the same order line are not "
          + "invoiced. An invoice generated from a receipt cannot have its quantities edited "
          + "afterwards (reactivating it does not allow it), so give any partial quantity here, "
          + "at creation.",
      NeoActionContract.Param.array(PARAM_RECEIPT_IDS, NeoActionContract.TYPE_STRING, false,
          "Ids of the goods receipts to invoice together in ONE invoice (all of the same business "
              + "partner). When sent, exactly these receipts are invoiced, so include this one. "
              + "Omit to invoice only this receipt."),
      NeoActionContract.Param.optional(PARAM_PRICE_LIST_ID, NeoActionContract.TYPE_STRING,
          "Id of the purchase price list to price the invoice with. Only required when none can "
              + "be resolved (receipts without a common order and a business partner without a "
              + "default purchase tariff); otherwise the order's or partner's list is used."),
      NeoActionContract.Param.array(PARAM_LINES, NeoActionContract.TYPE_OBJECT, false,
          "Partial invoicing, honoured exactly (a receipt of 3 with quantity 1 gives an invoice "
              + "of 1): [{receiptLineId, quantity}] with the quantity to invoice per "
              + "receipt line, for a single receipt, several (receiptIds) or one without a "
              + "purchase order alike. A quantity above what is still pending for that line, with the "
              + "opposite sign to the line, or for a line with nothing pending is refused with "
              + "400 naming the line and its pending quantity (it is never capped silently), and "
              + "so is a list none of whose entries has a receiptLineId and a non-zero quantity. "
              + "Omit to invoice the pending quantity of every line."))
      .withIdDescription("the id of the goods receipt to invoice");

  private static final String ERR_ORDER_NOT_COMPLETED =
      "Purchase order %s is not completed (status %s): complete it before creating the invoice";
  private static final String ERR_RECEIPT_NOTHING_TO_INVOICE =
      "Goods receipt %s has nothing left to invoice: every line is already invoiced or covered "
          + "by a draft invoice";
  private static final String ERR_RECEIPTS_NOTHING_TO_INVOICE =
      "Goods receipts %s have nothing left to invoice: every line is already invoiced or "
          + "covered by a draft invoice";
  private static final String ERR_LINE_EXCEEDS_PENDING =
      "Cannot invoice %s: requested quantity %s, but the quantity still pending to invoice is %s "
          + "(already invoiced or covered by a draft invoice)";
  private static final String ERR_LINES_UNUSABLE =
      "'lines' was sent but none of its entries has a receiptLineId and a non-zero quantity";
  private static final String ERR_RECEIPT_NOT_COMPLETED =
      "Goods receipt %s is not completed (status %s): complete it before creating the invoice";

  @Inject
  InvoiceFromOrderSupport invoiceFromOrderSupport;

  @Inject
  TotalDiscountService totalDiscountService;

  /**
   * Routes the two goods-receipt-ONLY actions ({@code pendingInvoiceLines} GET,
   * {@code productPrices} POST) that must never fire on {@code purchase-order} — this handler
   * is also injected into {@code PurchaseOrderHeaderHandler}'s dispatch chain (for
   * {@code createFromOrder}), and {@code NeoHeaderActionRouter.dispatch} takes the first
   * non-null response. An ungated GET here would treat a {@code C_Order_ID} as an
   * {@code M_InOut_ID} and would also shadow {@code currencyOptionsHandler}, which sits later
   * in that same chain. Extracted out of {@link #handle} to keep its cognitive complexity down.
   *
   * @return the dispatched response, or {@code null} when neither action matches (caller falls
   *     through to the {@code createPurchaseInvoice} handling below).
   */
  private NeoResponse dispatchGoodsReceiptOnlyAction(NeoContext context, String specName,
      String fieldName, String method) {
    if (!SPEC_GOODS_RECEIPT.equals(specName)) {
      return null;
    }
    if (PENDING_LINES_ACTION.equals(fieldName) && "GET".equals(method)) {
      return handlePendingLines(context);
    }
    if (PRODUCT_PRICES_ACTION.equals(fieldName) && "POST".equals(method)) {
      return handleProductPrices(context);
    }
    return null;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    String specName = context.getSpecName();
    if (!SPEC_PURCHASE_ORDER.equals(specName) && !SPEC_GOODS_RECEIPT.equals(specName)) {
      return null;
    }

    String fieldName = context.getFieldName();
    String method = context.getHttpMethod();

    NeoResponse readOnlyOrPricingResponse =
        dispatchGoodsReceiptOnlyAction(context, specName, fieldName, method);
    if (readOnlyOrPricingResponse != null) {
      return readOnlyOrPricingResponse;
    }

    // GET listInvoices — purchase invoices linked to the order by header or only by lines
    if (SPEC_PURCHASE_ORDER.equals(specName) && LIST_ACTION.equals(fieldName) && "GET".equals(method)) {
      return OrderInvoiceListSupport.listInvoices(context, false);
    }

    if (!ACTION_NAME.equals(fieldName) || !"POST".equals(method)) {
      return null;
    }

    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
    }

    try {
      OBContext.setAdminMode(true);
      try {
        Invoice invoice;
        if (SPEC_GOODS_RECEIPT.equals(specName)) {
          JSONObject body = context.getRequestBody();
          List<String> receiptIds = parseReceiptIds(body, recordId);
          // A caller that sends no receiptIds (every existing single-receipt caller) falls
          // back to [recordId] via parseReceiptIds, so this always resolves to the ORIGINAL,
          // untouched createFromReceipt path — zero behaviour change for it.
          invoice = receiptIds.size() == 1
              ? createFromReceipt(receiptIds.get(0), body)
              : createFromReceipts(receiptIds, body);
        } else {
          invoice = createFromOrder(recordId);
        }
        OBDal.getInstance().flush();
        // Refresh to pick up trigger-generated documentNo and totals set by CreateInvoiceLinesFromProcess.
        OBDal.getInstance().getSession().refresh(invoice);
        ensureDocumentNo(invoice);

        // ETP-5381: create and confirm atomically — see CreateDraftInvoiceHandler.handleCreate for
        // the full rationale. The id is captured before the call because ProcessInvoiceUtil commits
        // and closes the session, leaving `invoice` detached.
        String invoiceId = invoice.getId();
        InvoiceCompletionService.completeInvoiceOrThrow(invoiceId, context.getObContext());
        Invoice completed = OBDal.getInstance().get(Invoice.class, invoiceId);

        JSONObject data = new JSONObject();
        data.put("id", invoiceId);
        data.put("documentNo", completed.getDocumentNo());
        // Not sent before this ticket; the frontend needs it to render the resulting status.
        data.put("documentStatus", completed.getDocumentStatus());

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);

        JSONObject wrapper = new JSONObject();
        wrapper.put(KEY_RESPONSE, responseData);

        return NeoResponse.created(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Error creating purchase invoice from order {}: {}", recordId, e.getMessage());
      return errorResponse(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating purchase invoice from order {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the purchase invoice");
    }
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
   * Defensive fallback when DocumentNoHandlerLegacy fails to resolve the
   * sequence. The stock {@code AP Invoice} document type ships with
   * {@code IsDocNoControlled='N'} and no {@code DocNoSequence_ID}, so the
   * listener returns an empty string. Resolves the next number directly from
   * the table-level {@code DocumentNo_C_Invoice} sequence using the invoice's
   * own client (not {@code vars.getClient()}, which can differ under
   * {@code OBContext.setAdminMode(true)} + NEO Headless), and reuses the OBDal
   * JDBC connection so the sequence advance stays in the same transaction.
   */
  protected void ensureDocumentNo(Invoice invoice) {
    String current = invoice.getDocumentNo();
    if (StringUtils.isNotBlank(current) && !current.startsWith("<")) {
      return;
    }
    String docNo = Utility.getDocumentNoConnection(
        OBDal.getInstance().getConnection(false),
        new DalConnectionProvider(false),
        invoice.getClient().getId(),
        "C_Invoice",
        true);
    if (StringUtils.isBlank(docNo)) {
      log.warn(
          "Could not generate documentNo for purchase invoice {} (docType={}, client={}). "
              + "Configure DocNoSequence_ID on the document type or activate "
              + "AD_Sequence 'DocumentNo_C_Invoice' for the client.",
          invoice.getId(),
          invoice.getDocumentType() != null ? invoice.getDocumentType().getName() : "null",
          invoice.getClient().getId());
      return;
    }
    log.info("Generated documentNo='{}' for purchase invoice {}", docNo, invoice.getId());
    invoice.setDocumentNo(docNo);
    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();
  }

  /**
   * Returns the draft-invoice state for a goods receipt:
   * <ul>
   *   <li>{@code draftExists} — at least one draft AP invoice exists for this receipt's lines</li>
   *   <li>{@code pendingExists} — there are still lines not yet covered by any draft/completed invoice</li>
   *   <li>{@code draftId} / {@code draftDocNo} — first draft's ID and document number (when {@code draftExists})</li>
   * </ul>
   * The frontend uses this to decide whether to show "create invoice" (pendingExists) or
   * navigate to the existing draft ({@code !pendingExists && draftExists}).
   */
  protected NeoResponse handleCheck(NeoContext context) {
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut receipt = OBDal.getInstance().get(ShipmentInOut.class, recordId);
        if (receipt == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Receipt not found: " + recordId);
        }
        // Collect order line IDs from this receipt's lines — used to find linked draft invoices.
        List<String> orderLineIds = new ArrayList<>();
        for (ShipmentInOutLine rl : receipt.getMaterialMgmtShipmentInOutLineList()) {
          if (rl.isActive() && rl.getSalesOrderLine() != null) {
            orderLineIds.add(rl.getSalesOrderLine().getId());
          }
        }
        List<Invoice> drafts = Collections.emptyList();
        if (!orderLineIds.isEmpty()) {
          drafts = OBDal.getInstance().getSession()
              .createQuery(
                  "SELECT DISTINCT i FROM Invoice i JOIN i.invoiceLineList il "
                  + "WHERE il.salesOrderLine.id IN :olIds "
                  + "AND i.documentStatus = 'DR' AND i.salesTransaction = false "
                  + "ORDER BY i.creationDate DESC",
                  Invoice.class)
              .setParameterList("olIds", orderLineIds)
              .setMaxResults(5)
              .list();
        }
        boolean pendingExists = !NeoInvoiceSupport.computePendingQtyPerLine(recordId, true).isEmpty();
        JSONObject data = new JSONObject();
        data.put("draftExists", !drafts.isEmpty());
        data.put("pendingExists", pendingExists);
        if (!drafts.isEmpty()) {
          Invoice first = drafts.get(0);
          data.put("draftId", first.getId());
          data.put("draftDocNo", first.getDocumentNo());
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
      log.error("Error checking purchase invoice for receipt {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  InvoiceFromOrderSupport getSupport() {
    return invoiceFromOrderSupport != null ? invoiceFromOrderSupport : new InvoiceFromOrderSupport();
  }

  /**
   * Overrides the invoice's price list with the one explicitly chosen by the user in the
   * "invoice from goods receipt" confirmation popup (ETP-4028) — reads {@code priceListId}
   * from the request body. A no-op when absent or blank.
   *
   * <p>Must be called BEFORE {@code CreateInvoiceLinesFromProcess} runs: the native
   * {@code UpdatePricesAndAmounts} hook prices every line off {@code getInvoice().getPriceList()}
   * read from this already-saved invoice, so overriding it here is sufficient. Currency is
   * intentionally left untouched: it is always inherited from the receipt (read-only in the
   * popup), never user-selected here.
   */
  private void applyPriceListOverride(Invoice invoice, JSONObject body) {
    PriceList priceList = resolvePriceListOverride(body);
    if (priceList != null) {
      invoice.setPriceList(priceList);
    }
  }

  private PriceList resolvePriceListOverride(JSONObject body) {
    String priceListId = body != null ? body.optString(PARAM_PRICE_LIST_ID, null) : null;
    if (StringUtils.isBlank(priceListId)) {
      return null;
    }
    return OBDal.getInstance().get(PriceList.class, priceListId);
  }

  protected Invoice createFromOrder(String orderId) {
    Order order = OBDal.getInstance().get(Order.class, orderId);
    if (order == null) {
      throw new OBException("Purchase order not found: " + orderId);
    }
    DocumentStatusGuard.requireCompleted(order.getDocumentNo(), order.getDocumentStatus(),
        ERR_ORDER_NOT_COMPLETED);

    JSONArray selectedLines = buildSelectedLines(order);
    if (selectedLines.length() == 0) {
      throw new OBException("No pending lines to invoice in this purchase order");
    }

    DocumentType invoiceDocType = resolveAPInvoiceDocType(order);

    Invoice invoice = NeoCommercialDocumentFactory.createInvoiceFromOrderHeader(
        order,
        invoiceDocType,
        false);

    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();

    // Delegate line creation to native Etendo process — handles taxes, gross prices, IVA-included, etc.
    CreateInvoiceLinesFromProcess proc =
        WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class);
    proc.createInvoiceLinesFromDocumentLines(selectedLines, invoice, OrderLine.class);

    OBDal.getInstance().flush();

    // ETP-4780: carry the per-line % Descuento (OrderLine.discount) into the invoice
    // line's EM_Etgo_Discount field — the native process copies prices/taxes but not
    // this EM_ extension column, so the generated invoice total drifted from the
    // source purchase order. Shared with CreateDraftInvoiceHandler (ETP-4006).
    getSupport().copyLineDiscountsFromOrder(invoice);

    InvoiceLineLinker.linkInvoiceLinesToExistingInouts(invoice.getId());

    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().refresh(invoice);
    invoice = getSupport().applyOrderDiscountToInvoice(invoice, orderId, totalDiscountService);
    getSupport().ensureLineGrossAmounts(invoice);
    getSupport().propagateOrderRateToInvoice(order, invoice);

    return invoice;
  }

  protected JSONArray buildSelectedLines(Order order) {
    JSONArray selectedLines = new JSONArray();
    for (OrderLine ol : order.getOrderLineList()) {
      BigDecimal pending = getPendingQuantity(ol);
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

  private BigDecimal getPendingQuantity(OrderLine ol) {
    if (!ol.isActive() || ol.getProduct() == null) return null;
    BigDecimal ordered  = ol.getOrderedQuantity()  != null ? ol.getOrderedQuantity()  : BigDecimal.ZERO;
    BigDecimal invoiced = ol.getInvoicedQuantity() != null ? ol.getInvoicedQuantity() : BigDecimal.ZERO;
    BigDecimal pending  = ordered.subtract(invoiced);
    // ETP-4567: ordered/invoiced quantities can be NEGATIVE (return-style lines,
    // since ETP-4567 removed the min:0 constraint on order lines), so "nothing left" is not
    // "pending <= 0".
    // ETP-5709: but a pending quantity only counts when it has the SAME sign as the ordered
    // quantity. A line ordered 2 and invoiced 4 has pending -2: it is over-invoiced, not
    // pending, and selecting it would create an invoice line of -2. Same sign rule as
    // InOutLineFromOrderFactory uses for receipts; a legit negative line (ordered -3, invoiced
    // 0 -> pending -3) keeps the ordered sign and is still invoiced.
    return pending.signum() != 0 && pending.signum() == ordered.signum() ? pending : null;
  }

  // Discard the placeholder doc type ('0' = "** New **", docBasetype="---")
  // which is stored when no invoice doc type is linked to the PO doc type.
  protected DocumentType resolveAPInvoiceDocType(Order order) {
    DocumentType orderDocType = order.getTransactionDocument();
    DocumentType invoiceDocType = orderDocType != null
        ? orderDocType.getDocumentTypeForInvoice()
        : null;
    if (invoiceDocType != null && !"API".equals(invoiceDocType.getDocumentCategory())) {
      invoiceDocType = null;
    }
    if (invoiceDocType == null) {
      invoiceDocType = findAPInvoiceDocType(order.getClient().getId());
    }
    if (invoiceDocType == null) {
      throw new OBException(ERR_NO_AP_INVOICE_DOC_TYPE);
    }
    return invoiceDocType;
  }

  private DocumentType findAPInvoiceDocType(String clientId) {
    org.openbravo.model.ad.system.Client client =
        OBDal.getInstance().get(org.openbravo.model.ad.system.Client.class, clientId);
    List<DocumentType> results = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_CLIENT, client))
        .add(Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, "API"))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, false))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true))
        .addOrderBy(DocumentType.PROPERTY_DEFAULT, false)
        .setMaxResults(1)
        .list();
    return results.isEmpty() ? null : results.get(0);
  }

  // ── Multi-receipt (bulk) invoice creation ──────────────────────────────────────────
  //
  // Mirrors CreateDraftInvoiceHandler's goods-shipment bulk path (createFromShipments):
  // N selected receipts are combined into ONE invoice. Unlike that sales path, this one
  // has no single-receipt-with-order short-circuit into createFromOrder — the existing
  // createFromReceipt (untouched, below) already IS that single-receipt path, reached
  // directly from handle() when receiptIds.size() == 1.

  /**
   * Extracts the list of receipt IDs from the request body. Reads the {@code receiptIds}
   * JSON array when present; falls back to {@code recordId} so that every existing
   * single-receipt caller (which sends no array at all) keeps invoicing exactly the record
   * the URL already names.
   */
  protected List<String> parseReceiptIds(JSONObject body, String recordId) {
    return MultiDocumentInvoiceSupport.parseDocumentIds(body, PARAM_RECEIPT_IDS, recordId, log);
  }

  /**
   * Loads the {@link ShipmentInOut} receipts for the given IDs and validates that all belong
   * to the same Business Partner — the one cross-document invariant a combined invoice
   * cannot relax, since {@code C_Invoice.C_BPartner_ID} is a single column. This is the only
   * server-side cross-document validation on this path: {@code docStatus == 'CO'} and "same
   * currency" stay client-side only, matching the sales twin.
   *
   * @param receiptIds
   *     list of {@code M_InOut_ID} values to load
   * @return validated list of receipts in the same order as the input IDs
   * @throws OBException
   *     if any ID is not found, the list is empty, or the receipts span multiple
   *     Business Partners
   */
  protected List<ShipmentInOut> loadAndValidateReceipts(List<String> receiptIds) {
    List<ShipmentInOut> receipts = MultiDocumentInvoiceSupport.loadAndValidateSameBusinessPartner(
        receiptIds, "Goods receipt not found: ", "No goods receipts provided",
        "All goods receipts must belong to the same Business Partner");
    requireReceiptsCompleted(receipts);
    return receipts;
  }

  /**
   * ETP-5709: every receipt that feeds an invoice must be completed — checked for the single and
   * the multi-receipt path alike, before anything is created.
   */
  private static void requireReceiptsCompleted(List<ShipmentInOut> receipts) {
    for (ShipmentInOut receipt : receipts) {
      DocumentStatusGuard.requireCompleted(receipt.getDocumentNo(),
          receipt.getDocumentStatus(), ERR_RECEIPT_NOT_COMPLETED);
    }
  }

  /**
   * Creates a draft (immediately confirmed — see {@link #handle}) AP Invoice from N goods
   * receipts, combined into a single invoice header. Reached only when {@link #handle}
   * resolves more than one receipt id; a single id is still routed to the untouched {@link
   * #createFromReceipt}.
   *
   * <p>Every validation here runs BEFORE the first setter on the invoice header: the line
   * selection is built and checked first (step order below), and only once it is known to be
   * non-empty is the transient {@link Invoice} constructed. A handler that returns an error
   * after a write still leaves that write for Hibernate to flush regardless of the HTTP
   * status, so nothing may be created on a path that is about to throw.
   *
   * <p>Uses {@code ShipmentInOutLine.class} for the native line-creation process, never {@code
   * OrderLine.class}: Core's order-line path re-derives quantities from EVERY completed
   * receipt of that order line, not just the ones the user selected here — see {@link
   * MultiDocumentInvoiceSupport} for the full rationale. This also means N=1 (via {@link
   * #createFromReceipt}) and N&gt;=2 take genuinely different Core paths and can price/derive
   * quantities differently; that is intentional, and the multi-receipt path is the more
   * faithful one to "the invoice is built from the receipt(s), not the order".
   *
   * @param receiptIds
   *     list of {@code M_InOut_ID} values to invoice (size &gt;= 2)
   * @param body
   *     optional request body — read for {@code lines} overrides and {@code priceListId}
   * @return the newly persisted (pre-completion) {@link Invoice}
   * @throws OBException
   *     if the receipts are invalid (see {@link #loadAndValidateReceipts}), nothing is
   *     pending to invoice, or no price list can be resolved
   */
  protected Invoice createFromReceipts(List<String> receiptIds, JSONObject body) {
    List<ShipmentInOut> receipts = loadAndValidateReceipts(receiptIds);
    ShipmentInOut first = receipts.get(0);

    Map<String, BigDecimal> qtyOverrides = parseLineOverrides(body);
    MultiDocumentInvoiceSupport.requireParsedLines(body, PARAM_LINES, qtyOverrides,
        ERR_LINES_UNUSABLE);

    // Seed per-receipt pending quantities — the multi-receipt twin of the ETP-5381 seeding in
    // #createFromReceipt (the {@code qtyOverrides.isEmpty()} branch): a QUANTITY SOURCE, not a duplicate-invoice guard.
    // Core's own pending-quantity logic (raised qtyinvoiced once the first invoice is
    // confirmed) is what actually blocks a genuine re-invoice. Uses the throwing variant
    // (computePendingQtyPerLineOrThrow) deliberately: the swallowing variant returns an empty
    // map on a transient DB error exactly as it would for "nothing pending", and an empty map
    // here makes resolveInOutLineQty fall back to the FULL movement quantity — silently
    // producing a duplicate invoice instead of a visible failure.
    Map<String, BigDecimal> pendingQtyMap = new HashMap<>();
    for (ShipmentInOut r : receipts) {
      pendingQtyMap.putAll(NeoInvoiceSupport.computePendingQtyPerLineOrThrow(r.getId(), true));
    }
    // ETP-5709: explicit quantities are validated against what is pending, never silently
    // capped; and the map is authoritative, so a line absent from it (nothing pending) is
    // skipped by buildInOutLineSelection instead of being invoiced in full.
    MultiDocumentInvoiceSupport.requireOverridesWithinPending(receipts, qtyOverrides, pendingQtyMap,
        ERR_LINE_EXCEEDS_PENDING);

    JSONArray selectedLines = MultiDocumentInvoiceSupport.buildInOutLineSelection(receipts, qtyOverrides,
        pendingQtyMap, log);
    if (selectedLines.length() == 0) {
      List<String> docNos = new ArrayList<>();
      for (ShipmentInOut r : receipts) {
        docNos.add(r.getDocumentNo());
      }
      throw new OBException(String.format(ERR_RECEIPTS_NOTHING_TO_INVOICE, String.join(", ", docNos)));
    }

    Invoice invoice = createInvoiceHeaderFromReceipts(first, receipts);
    applyPriceListOverride(invoice, body);
    ensurePriceListResolved(invoice);

    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();

    CreateInvoiceLinesFromProcess proc =
        WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class);
    proc.createInvoiceLinesFromDocumentLines(selectedLines, invoice, ShipmentInOutLine.class);

    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().refresh(invoice);
    // ETP-4726 pattern (see createFromReceipt/createFromReceiptNoPo): CreateInvoiceLinesFromProcess
    // never populates Line_Gross_Amount / grossUnitPrice; only this call does.
    getSupport().ensureLineGrossAmounts(invoice);

    // Deliberately NOT called on this path, unlike createFromReceipt/createFromOrder:
    // copyLineDiscountsFromOrder / applyOrderDiscountToInvoice / propagateOrderRateToInvoice
    // are all scoped to ONE source order and would be meaningless (or wrong) when receipts
    // span several orders or none. A bulk invoice therefore does not carry per-line
    // % Descuento nor a header total-discount from the source order(s) — a known, accepted
    // gap relative to the single-receipt flow, not an oversight.

    return invoice;
  }

  /**
   * Builds the transient invoice header for a multi-receipt invoice. Never sets {@code
   * C_Invoice.C_Order_ID}: {@link NeoCommercialDocumentFactory#createInvoiceFromReceiptHeader}
   * simply never sets it, which is the correct state for N&gt;1 (an ambiguous order link on a
   * combined invoice). Core's own {@code UpdateInvoiceLineInformation} later sets it from the
   * lines if — and only if — every line the native process created turns out to share one
   * order; this method never has to decide that itself.
   *
   * <p>Financial fields follow a PO-preferred, Business-Partner-default fallback: when every
   * receipt resolves to the SAME purchase order, that order's tariff/terms/payment method are
   * used (mirroring the single-receipt-with-PO flow); otherwise (different POs, a mix of
   * with/without PO, or no PO at all) the Business Partner's own purchase defaults are used,
   * exactly like {@link #createFromReceiptNoPo}. A selection spanning different purchase
   * orders is never rejected — same-Business-Partner is the only hard cross-document rule
   * (see {@link #loadAndValidateReceipts}).
   *
   * @param first
   *     the first receipt, used to derive client/org/BP/address/currency
   * @param receipts
   *     the full validated list, used to look for a common linked order
   * @return a transient {@link Invoice} ready to be saved and populated with lines
   */
  protected Invoice createInvoiceHeaderFromReceipts(ShipmentInOut first, List<ShipmentInOut> receipts) {
    BusinessPartner bp = first.getBusinessPartner();
    Order commonOrder = resolveCommonOrder(receipts);

    PriceList priceList;
    PaymentTerm paymentTerms;
    FIN_PaymentMethod paymentMethod;
    DocumentType docType;
    if (commonOrder != null) {
      priceList = commonOrder.getPriceList();
      paymentTerms = commonOrder.getPaymentTerms();
      paymentMethod = commonOrder.getPaymentMethod();
      docType = resolveAPInvoiceDocType(commonOrder);
    } else {
      priceList = bp != null ? bp.getPurchasePricelist() : null;
      paymentTerms = bp != null ? bp.getPOPaymentTerms() : null;
      paymentMethod = bp != null ? bp.getPOPaymentMethod() : null;
      docType = findAPInvoiceDocType(first.getClient().getId());
      if (docType == null) {
        throw new OBException(ERR_NO_AP_INVOICE_DOC_TYPE);
      }
    }

    // ETP-4028: the invoice's currency is always inherited from the receipt's own
    // (editable-until-confirmed) currency, never from the linked order or price list.
    Currency currency = first.getEtgoCurrency();

    return NeoCommercialDocumentFactory.createInvoiceFromReceiptHeader(
        first, docType, priceList, paymentTerms, paymentMethod, currency);
  }

  /**
   * Returns the purchase order every one of {@code receipts} resolves to (directly via {@code
   * C_Order_ID}, or via {@link #deriveOrderFromLines} for a header-less NEO-imported receipt),
   * or {@code null} when they do not all resolve to the SAME one (different orders, a mix of
   * with/without order, or none at all).
   */
  private Order resolveCommonOrder(List<ShipmentInOut> receipts) {
    Order common = null;
    for (ShipmentInOut r : receipts) {
      Order o = r.getSalesOrder();
      if (o == null) {
        o = deriveOrderFromLines(r);
      }
      if (o == null) {
        return null;
      }
      if (common == null) {
        common = o;
      } else if (!Objects.equals(common.getId(), o.getId())) {
        return null;
      }
    }
    return common;
  }

  /**
   * Fails fast with a clear 400 when neither a common linked order, the Business Partner's
   * default purchase tariff, nor an explicit {@code priceListId} override resolved a price
   * list (ETP-4942 pattern — see {@link CreateDraftInvoiceHandler#ensurePriceListResolved}).
   * Must run AFTER {@link #applyPriceListOverride}, which is the last chance to fill it in.
   */
  private void ensurePriceListResolved(Invoice invoice) {
    if (invoice.getPriceList() == null) {
      throw new OBException(ERR_PRICE_LIST_REQUIRED);
    }
  }

  /**
   * Returns the pending-to-invoice quantity per line for a single goods receipt — GET
   * {@code /goods-receipt/goodsReceipt/{id}/action/pendingInvoiceLines}. Mirrors {@code
   * CreateDraftInvoiceHandler#handlePendingLines}; used by the bulk toolbar action to preview
   * the total pending units across a selection (one call per selected receipt, summed
   * client-side) and, on the single-receipt form-view flow, to show the same subtitle the
   * goods-shipment "Crear factura" confirm popup already shows.
   *
   * <p>Also returns each line's {@code product}/{@code salesOrderLine} — ETP-5410 follow-up:
   * these used to require a SEPARATE {@code goodsReceiptLine?parentId=} request; they come from
   * the same already-loaded {@link ShipmentInOut}, so returning them here removes one whole
   * round trip from every "Crear factura" modal open.
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
          String resolvedPriceListId = MultiDocumentInvoiceSupport.resolveEffectivePriceListId(doc, false);
          responseData.put("resolvedPriceListId", resolvedPriceListId != null ? resolvedPriceListId : JSONObject.NULL);
        }
        JSONObject wrapper = new JSONObject();
        wrapper.put(KEY_RESPONSE, responseData);
        return new NeoResponse(200, wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error computing pending invoice lines for receipt {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Prices a caller-supplied list of products against a price list — POST {@code
   * /goods-receipt/goodsReceipt/{id}/action/productPrices}, body {@code {productIds: [...],
   * priceListId: "..."}}. {@code recordId} is not used: this exists only so the "Crear factura"
   * quote feature can price the handful of products its own pending lines actually reference,
   * instead of running the generic product-browse selector (up to 500 rows) and discarding all
   * but a few of them. Mirrors {@code CreateDraftInvoiceHandler#handleProductPrices}, including
   * the POST-not-GET reasoning (ACTION-endpoint dispatch never threads query-string parameters
   * into {@link NeoContext}).
   *
   * <p>Delegates to {@link MultiDocumentInvoiceSupport#buildProductPricesResponse}, shared
   * byte-for-byte with the sales handler.
   */
  protected NeoResponse handleProductPrices(NeoContext context) {
    return MultiDocumentInvoiceSupport.buildProductPricesResponse(context.getRequestBody(), log);
  }

  /**
   * Overridable seam for {@link #handlePendingLines} (and usable by a future caller of {@link
   * #createFromReceipts}), so a test can stub the pending-quantity computation without a DB.
   */
  protected Map<String, BigDecimal> computePendingQtyPerLine(String inOutId, boolean includeDrafts) {
    return NeoInvoiceSupport.computePendingQtyPerLine(inOutId, includeDrafts);
  }

  // ── Single-receipt invoice creation (unchanged) ────────────────────────────────────

  /**
   * Creates a draft AP Invoice from a Goods Receipt. Quantities come from the
   * receipt's movement quantities, or from per-line overrides supplied in the
   * request body ({@code { "lines": [{ "receiptLineId": "...", "quantity": "2" }] }}).
   * Prices and taxes are resolved via the linked purchase order lines.
   * Only lines that have a linked {@code C_OrderLine} are included.
   *
   * @param receiptId primary key of the source {@code M_InOut} record (issotrx=false)
   * @param body      optional request JSON; may be null
   * @return the newly persisted draft {@link Invoice}
   * @throws OBException if the receipt is not found, has no linked PO, or no invoiceable lines
   */
  protected Invoice createFromReceipt(String receiptId, JSONObject body) {
    ShipmentInOut receipt = OBDal.getInstance().get(ShipmentInOut.class, receiptId);
    if (receipt == null) {
      throw new OBException("Goods receipt not found: " + receiptId);
    }
    requireReceiptsCompleted(List.of(receipt));

    Map<String, BigDecimal> qtyOverrides = parseLineOverrides(body);
    MultiDocumentInvoiceSupport.requireParsedLines(body, PARAM_LINES, qtyOverrides,
        ERR_LINES_UNUSABLE);
    // ETP-5381: when the caller sends no explicit line quantities — which is what the UI always
    // does, it posts only priceListId — seed them from the receipt's pending quantities. This is
    // not a duplicate-invoice guard but a correctness fix: resolveReceiptLineQty otherwise falls
    // back to the FULL movementQuantity, so unlike the order path this one never consulted what
    // was already invoiced. Its javadoc already promised the map came from
    // computePendingQtyPerLine; it was simply never wired up.
    // Nothing pending leaves the map EMPTY, and an empty map downstream means "no overrides,
    // invoice the full movement quantity" — so "nothing left to invoice" must be refused HERE,
    // before any line is built: no later check can tell the two apart (the "no lines" checks
    // below only fire on an empty selection, which a full-quantity fallback never produces; a
    // second call used to create a second identical invoice, ETP-5709). The throwing variant is
    // used so a DB failure surfaces as such instead of being mistaken for "nothing left".
    // Drafts count as covered (includeDrafts=true), hence the wording of the message.
    // ETP-5709: the pending map is computed for explicit lines too, to refuse (400, not a
    // silent cap) a quantity above what is pending.
    Map<String, BigDecimal> pending = NeoInvoiceSupport.computePendingQtyPerLineOrThrow(receiptId,
        true);
    if (qtyOverrides.isEmpty()) {
      qtyOverrides = pending;
      if (qtyOverrides.isEmpty()) {
        throw new OBException(String.format(ERR_RECEIPT_NOTHING_TO_INVOICE,
            receipt.getDocumentNo()));
      }
    } else {
      MultiDocumentInvoiceSupport.requireOverridesWithinPending(List.of(receipt), qtyOverrides,
          pending, ERR_LINE_EXCEEDS_PENDING);
    }

    Order linkedOrder = receipt.getSalesOrder();
    if (linkedOrder == null) {
      // When the receipt was created via NEO import-from-PO, C_Order_ID may not be
      // set at the header level — fall back to deriving the order from the line links.
      linkedOrder = deriveOrderFromLines(receipt);
    }
    if (linkedOrder == null) {
      return createFromReceiptNoPo(receipt, qtyOverrides, body);
    }
    JSONArray selectedLines = buildSelectedLinesFromReceipt(receipt, qtyOverrides, linkedOrder);
    if (selectedLines.length() == 0) {
      throw new OBException("No lines with a linked purchase order to invoice in this goods receipt");
    }

    DocumentType invoiceDocType = resolveAPInvoiceDocType(linkedOrder);
    // Read before evicting receipt below — a lazy FK access on a detached entity
    // would throw LazyInitializationException.
    Currency receiptCurrency = receipt.getEtgoCurrency();

    // Evict receipt and its lines from the Hibernate session before the first flush.
    // CreateInvoiceLinesFromProcess internally does saveOrUpdate on M_InOutLine objects
    // and throws EntityExistsException when those objects are already in the session.
    for (ShipmentInOutLine rl : receipt.getMaterialMgmtShipmentInOutLineList()) {
      OBDal.getInstance().getSession().evict(rl);
    }
    OBDal.getInstance().getSession().evict(receipt);

    Invoice invoice = NeoCommercialDocumentFactory.createInvoiceFromOrderHeader(
        linkedOrder, invoiceDocType, false);
    // ETP-4028: the invoice's currency is always inherited from the receipt's own
    // (editable-until-confirmed) currency, never from the linked order — that can
    // diverge from the receipt once the user changes it in draft.
    invoice.setCurrency(receiptCurrency);
    applyPriceListOverride(invoice, body);

    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();

    CreateInvoiceLinesFromProcess proc =
        WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class);
    proc.createInvoiceLinesFromDocumentLines(selectedLines, invoice, OrderLine.class);

    OBDal.getInstance().flush();

    // ETP-4780: same discount copy as createFromOrder above — a receipt-originated
    // invoice must not lose the % Descuento carried by the linked purchase order line.
    getSupport().copyLineDiscountsFromOrder(invoice);

    InvoiceLineLinker.linkInvoiceLinesToExistingInouts(invoice.getId());

    OBDal.getInstance().getSession().refresh(invoice);
    // ETP-4844: mirrors createFromOrder() above — without these two calls the invoice's
    // etgoTotalDiscount header % was carried over but never materialized into a real
    // ETGO_DTO line/c_invoicetax update (correct-looking Draft totals came only from the
    // GET-time compensation formula, not persisted data — Classic's own Complete process
    // never runs our header handlers and would post the wrong totals), and every line's
    // lineGrossAmount/grossUnitPrice was left at 0 (CreateInvoiceLinesFromProcess never
    // populates them; only ensureLineGrossAmounts does).
    invoice = getSupport().applyOrderDiscountToInvoice(invoice, linkedOrder.getId(), totalDiscountService);
    getSupport().ensureLineGrossAmounts(invoice);
    ensureDocumentNo(invoice);
    // ETP-4726: createFromOrder() already does this; createFromReceipt() never did,
    // since inception (ETP-4032, commit 89d610e7) — ensureLineGrossAmounts() didn't
    // exist yet at that point and was simply never wired in afterwards. Without it,
    // Line_Gross_Amount stays at its stub value (0) for every receipt-generated line.
    getSupport().ensureLineGrossAmounts(invoice);

    return invoice;
  }

  /**
   * Builds the selectedLines JSON array for a goods receipt.
   *
   * <p>For each active receipt line the order line is resolved as follows:
   * <ol>
   *   <li>Direct link via {@code C_OrderLine_ID} on the receipt line (normal receipts).</li>
   *   <li>Product-based match against {@code linkedOrder} (cloned receipts, where
   *       {@code m_inoutline_trg} forces {@code C_OrderLine_ID} to be null on INSERT
   *       but the header still carries {@code C_Order_ID}).</li>
   * </ol>
   *
   * <p>ETP-5709: ONE entry PER receipt line, each bound to its receipt line
   * ({@code shipmentInOutLine}) — see {@link MultiDocumentInvoiceSupport#buildOrderLineEntryForInOutLine}
   * for why. They used to be accumulated per order line without that binding, which made the
   * native process discard the requested quantity and invoice every receipt of the order line in
   * full. Receipt lines absent from {@code qtyOverrides} are skipped when the map is non-empty: a
   * missing entry means the line has zero pending qty.
   *
   * @param linkedOrder the purchase order linked to the receipt header; may be null
   */
  protected JSONArray buildSelectedLinesFromReceipt(ShipmentInOut receipt,
      Map<String, BigDecimal> qtyOverrides, Order linkedOrder) {
    Map<String, OrderLine> orderLineByProduct = buildOrderLineByProduct(linkedOrder);
    JSONArray selectedLines = new JSONArray();
    for (ShipmentInOutLine rl : receipt.getMaterialMgmtShipmentInOutLineList()) {
      JSONObject entry = buildEntryForReceiptLine(rl, qtyOverrides, orderLineByProduct);
      if (entry != null) {
        selectedLines.put(entry);
      }
    }
    return selectedLines;
  }

  /**
   * Builds the {@code selectedLines} entry of one receipt line, or {@code null} when the line is
   * skipped: inactive or without product, no order line to bind to, or nothing left to invoice.
   */
  private JSONObject buildEntryForReceiptLine(ShipmentInOutLine rl,
      Map<String, BigDecimal> qtyOverrides, Map<String, OrderLine> orderLineByProduct) {
    if (!rl.isActive() || rl.getProduct() == null) {
      return null;
    }
    OrderLine ol = rl.getSalesOrderLine();
    if (ol == null) {
      ol = orderLineByProduct.get(rl.getProduct().getId());
    }
    if (ol == null) {
      return null;
    }
    BigDecimal qty = resolveReceiptLineQty(rl, qtyOverrides);
    // ETP-4722: quantities can be NEGATIVE since ETP-4567 removed the old
    // min: 0 constraint (e.g. a return-style receipt line). Only an exact
    // zero means "nothing left to invoice" — a strictly-positive check
    // silently dropped every negative-quantity receipt line here.
    if (qty == null || qty.compareTo(BigDecimal.ZERO) == 0) {
      return null;
    }
    try {
      return MultiDocumentInvoiceSupport.buildOrderLineEntryForInOutLine(ol.getId(), rl,
          MultiDocumentInvoiceSupport.signedLikeLine(rl, qty));
    } catch (Exception ex) {
      log.warn("Failed to build selectedLine entry for receipt line {}: {}", rl.getId(),
          ex.getMessage());
      return null;
    }
  }

  /**
   * Resolves the quantity to invoice for a single receipt line.
   * When {@code qtyOverrides} is non-empty (populated from {@code computePendingQtyPerLine}
   * or from explicit request overrides) only lines present in the map are invoiced —
   * an absent entry means pending qty is zero and the line must be skipped.
   * When {@code qtyOverrides} is empty the full movement quantity is used.
   */
  private BigDecimal resolveReceiptLineQty(ShipmentInOutLine rl, Map<String, BigDecimal> qtyOverrides) {
    if (!qtyOverrides.isEmpty()) {
      return qtyOverrides.get(rl.getId()); // null → skip (pending = 0)
    }
    return rl.getMovementQuantity();
  }

  private Map<String, OrderLine> buildOrderLineByProduct(Order linkedOrder) {
    Map<String, OrderLine> result = new HashMap<>();
    if (linkedOrder != null) {
      for (OrderLine ol : linkedOrder.getOrderLineList()) {
        if (ol.isActive() && ol.getProduct() != null) {
          result.putIfAbsent(ol.getProduct().getId(), ol);
        }
      }
    }
    return result;
  }

  /**
   * Creates a draft AP Invoice from a goods receipt with no linked purchase order.
   * Follows the same pattern as {@code InvoiceGeneratorFromGoodsShipment} but for AP:
   * doc type API, salesTransaction=false, and purchase defaults from the business partner.
   * Prices are resolved by {@link CreateInvoiceLinesFromProcess} from the product's
   * entry in the invoice price list.
   */
  protected Invoice createFromReceiptNoPo(ShipmentInOut receipt,
      Map<String, BigDecimal> qtyOverrides) {
    return createFromReceiptNoPo(receipt, qtyOverrides, null);
  }

  protected Invoice createFromReceiptNoPo(ShipmentInOut receipt,
      Map<String, BigDecimal> qtyOverrides, JSONObject body) {
    BusinessPartner bp = receipt.getBusinessPartner();
    if (bp == null) {
      throw new OBException("Goods receipt has no business partner");
    }
    PriceList priceList = resolvePriceListOverride(body);
    if (priceList == null) {
      priceList = bp.getPurchasePricelist();
    }
    if (priceList == null) {
      throw new OBException(
          "Business partner '" + bp.getName() + "' has no purchase price list configured");
    }
    PaymentTerm paymentTerms = bp.getPOPaymentTerms();
    FIN_PaymentMethod paymentMethod = bp.getPOPaymentMethod();

    DocumentType docType = findAPInvoiceDocType(receipt.getClient().getId());
    if (docType == null) {
      throw new OBException(ERR_NO_AP_INVOICE_DOC_TYPE);
    }
    // Read before evicting receipt below — a lazy FK access on a detached entity
    // would throw LazyInitializationException.
    Currency receiptCurrency = receipt.getEtgoCurrency();

    JSONArray selectedLines = new JSONArray();
    for (ShipmentInOutLine rl : receipt.getMaterialMgmtShipmentInOutLineList()) {
      JSONObject entry = buildNoPoLineEntry(rl, qtyOverrides);
      if (entry != null) {
        selectedLines.put(entry);
      }
    }
    if (selectedLines.length() == 0) {
      throw new OBException("No invoiceable lines found in this goods receipt");
    }

    for (ShipmentInOutLine rl : receipt.getMaterialMgmtShipmentInOutLineList()) {
      OBDal.getInstance().getSession().evict(rl);
    }
    OBDal.getInstance().getSession().evict(receipt);

    Invoice invoice = NeoCommercialDocumentFactory.createInvoiceFromReceiptHeader(
        receipt, docType, priceList, paymentTerms, paymentMethod, receiptCurrency);
    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();

    CreateInvoiceLinesFromProcess proc =
        WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class);
    proc.createInvoiceLinesFromDocumentLines(selectedLines, invoice, ShipmentInOutLine.class);

    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().refresh(invoice);
    ensureDocumentNo(invoice);
    // ETP-4726: same gap as createFromReceipt() above — never wired in this branch either.
    getSupport().ensureLineGrossAmounts(invoice);

    return invoice;
  }

  private JSONObject buildNoPoLineEntry(ShipmentInOutLine rl, Map<String, BigDecimal> qtyOverrides) {
    if (!rl.isActive() || rl.getProduct() == null || rl.getUOM() == null) {
      return null;
    }
    // A non-empty map is an explicit list of what to invoice (explicit lines, or the pending
    // quantities): a line absent from it is skipped, as in the with-PO path. Only an EMPTY map
    // (a direct caller with no quantities) means the full movement quantity.
    BigDecimal qty = qtyOverrides.isEmpty()
        ? rl.getMovementQuantity()
        : qtyOverrides.get(rl.getId());
    // ETP-4722: same sign-unaware bug as buildSelectedLinesFromReceipt above —
    // only an exact zero means "nothing to invoice", negative qty is valid.
    if (qty == null || qty.compareTo(BigDecimal.ZERO) == 0) {
      return null;
    }
    try {
      JSONObject entry = new JSONObject();
      entry.put("id", rl.getId());
      entry.put(FIELD_ORDERED_QUANTITY, qty.toPlainString());
      return entry;
    } catch (Exception e) {
      log.warn("Failed to add receipt line {} to invoice lines: {}", rl.getId(), e.getMessage());
      return null;
    }
  }

  private Order deriveOrderFromLines(ShipmentInOut receipt) {
    for (ShipmentInOutLine rl : receipt.getMaterialMgmtShipmentInOutLineList()) {
      if (rl.getSalesOrderLine() != null && rl.getSalesOrderLine().getSalesOrder() != null) {
        return rl.getSalesOrderLine().getSalesOrder();
      }
    }
    return null;
  }

  protected Map<String, BigDecimal> parseLineOverrides(JSONObject body) {
    Map<String, BigDecimal> overrides = new HashMap<>();
    if (body == null) {
      return overrides;
    }
    JSONArray linesArr = body.optJSONArray(PARAM_LINES);
    if (linesArr == null) {
      return overrides;
    }
    for (int i = 0; i < linesArr.length(); i++) {
      try {
        JSONObject entry = linesArr.getJSONObject(i);
        String lineId = entry.optString("receiptLineId", null);
        String qtyStr = entry.optString("quantity", null);
        if (StringUtils.isNotBlank(lineId) && StringUtils.isNotBlank(qtyStr)) {
          BigDecimal qty = new BigDecimal(qtyStr);
          // ETP-4722: an explicit override can legitimately be negative
          // (partial invoicing of a negative-quantity receipt/shipment line).
          if (qty.compareTo(BigDecimal.ZERO) != 0) {
            overrides.put(lineId, qty);
          }
        }
      } catch (Exception e) {
        log.warn("Failed to parse line override at index {}: {}", i, e.getMessage());
      }
    }
    return overrides;
  }
}
