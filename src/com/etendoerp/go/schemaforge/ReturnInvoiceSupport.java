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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.QueryException;
import org.hibernate.query.Query;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.Tax;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Rectificative-invoice flow of the return documents (ETP-5381, ETP-5712): resolves the invoices
 * to rectify, prices and builds the invoice lines, refuses a zero-amount document, links, completes
 * and describes the created invoice. Shared by {@link ReturnToVendorShipmentHeaderHandler} and
 * {@link ReturnMaterialReceiptHeaderHandler}. Split out of {@link ReturnShipmentUtils} (java:S1448);
 * the constants and the small response/JSON helpers both classes need stay there.
 */
final class ReturnInvoiceSupport {

  private static final Logger log = LogManager.getLogger(ReturnInvoiceSupport.class);

  private ReturnInvoiceSupport() {}

  // ---------------------------------------------------------------------------
  // Invoice finalization – shared between both return header handlers
  // ---------------------------------------------------------------------------

  /**
   * Builds the rectificative invoice's lines, links it to the invoice(s) it rectifies, and
   * completes it — in that order (ETP-5381).
   *
   * <p><b>The order is not stylistic.</b> The {@code C_INVOICE_REVERSE_TRG} database trigger
   * rejects any insert into {@code C_Invoice_Reverse} once the invoice is {@code Processed='Y'},
   * so the link can only be created while the invoice is still a draft. And the link must exist
   * before completing, because {@code ETSG_CHECK_RECTIF_INV_DOC} rejects a rectificative document
   * type with no rectified invoices attached ("El tipo de documento es rectificativo, pero no se
   * han asociado facturas a rectificar"). Complete first and the invoice can never be confirmed
   * nor linked — a permanently stuck document.
   *
   * <p><b>Taxes and totals belong to the database, not to this method.</b> Flushing the lines is
   * what computes them: {@code C_INVOICELINE_TRG2} inserts each line's {@code C_InvoiceLineTax},
   * {@code C_INVOICELINETAX_TRG} folds those into ONE {@code C_InvoiceTax} row per tax rate
   * ({@code Recalculate='Y'}, incremented in place by every further line of that rate), and the
   * same chain keeps {@code C_Invoice.TotalLines} / {@code GrandTotal} current — exactly as for
   * any other invoice. Never insert an {@code InvoiceTax} from Java here: a hand-made row sits
   * NEXT TO the trigger-made one, and the completion then sums both, doubling the tax and the
   * amount to pay (base -300, tax -126 instead of -63).
   *
   * <p>The {@code refresh(invoice)} below is safe with respect to the duplicate-row hazard
   * described in {@code InvoiceFromOrderSupport#applyOrderDiscountToInvoice}: at that point the
   * session holds no {@code InvoiceTax} instance at all (this flow never loads or creates one),
   * so there is no stale tax entity for a cascade refresh to resurrect as an INSERT. Keep it that
   * way — do not read {@code invoice.getInvoiceTaxList()} before the refresh.
   *
   * @param originInvoiceIds ids of the invoices being rectified; must not be empty
   */
  static NeoResponse finalizeReturnInvoice(Invoice invoice, List<ShipmentInOutLine> lines,
      CreateDraftInvoiceHandler createDraftInvoiceHandler, List<String> originInvoiceIds,
      OBContext obContext) throws Exception {
    return finalizeReturnInvoice(invoice, lines, createDraftInvoiceHandler, originInvoiceIds,
        obContext, null, null);
  }

  /**
   * Same as above, plus what the answer reports about the linkage (ETP-5712).
   *
   * <p>{@code originInvoiceIds} is also threaded to the pricing: each line's source price is read
   * from those invoices.
   *
   * @param invoice the draft header
   * @param lines the return shipment lines
   * @param createDraftInvoiceHandler builds the invoice lines
   * @param originInvoiceIds the invoices being rectified (linked, and used to price)
   * @param obContext the context the completion runs under
   * @param returnDoc the return document the invoice refunds; {@code null} leaves
   *     {@code linkedReturnId} / {@code returnDocumentNo} out of the answer
   * @param previousReturnInvoices the rectificativas the return had BEFORE this call, as
   *     {@link ReturnShipmentUtils#fetchPreviousReturnInvoices} reports them; {@code null} reads as none
   * @return the 200 with the completed invoice
   * @throws Exception when a line cannot be built or the completion is refused; an
   *     {@link IllegalStateException} when the invoice cannot be read back afterwards
   */
  static NeoResponse finalizeReturnInvoice(Invoice invoice, List<ShipmentInOutLine> lines,
      CreateDraftInvoiceHandler createDraftInvoiceHandler, List<String> originInvoiceIds,
      OBContext obContext, ShipmentInOut returnDoc, JSONArray previousReturnInvoices)
      throws Exception {
    addReturnInvoiceLines(invoice, lines, createDraftInvoiceHandler, originInvoiceIds);
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().refresh(invoice);
    createDraftInvoiceHandler.ensureDocumentNo(invoice);
    createDraftInvoiceHandler.getSupport().ensureLineGrossAmounts(invoice);
    linkRectifiedInvoices(invoice, originInvoiceIds);
    OBDal.getInstance().flush();

    String invoiceId = invoice.getId();
    InvoiceCompletionService.completeInvoiceOrThrow(invoiceId, obContext);
    Invoice completed = OBDal.getInstance().get(Invoice.class, invoiceId);
    if (completed == null) {
      // The completion rolls the draft back when the transaction was marked rollback-only: say so
      // instead of letting refresh(null) fail with an IllegalArgumentException.
      // A server fault, not a user error: IllegalStateException, so the handler answers 500
      // (generic message, details only in the log) instead of the OBException 400.
      throw new IllegalStateException("The return invoice " + invoiceId + " could not be read "
          + "back after the completion; the transaction was rolled back.");
    }
    // The totals are computed by the DB triggers during the completion: read them from a fresh
    // copy, never from the entity as it was loaded before (ETP-5712).
    OBDal.getInstance().refresh(completed);

    JSONObject data = new JSONObject();
    data.put("id", invoiceId);
    data.put(ReturnShipmentUtils.FIELD_DOCUMENT_NO, completed.getDocumentNo());
    data.put(ReturnShipmentUtils.FIELD_DOCUMENT_STATUS, completed.getDocumentStatus());
    describeCreatedInvoice(data, completed, returnDoc, originInvoiceIds, previousReturnInvoices);
    return ReturnShipmentUtils.wrapOkData(data);
  }

  /**
   * ETP-5712: what the answer says about the factura rectificativa it just created, so an agent
   * can report it without a second read: its document type and direction, its amounts (negative:
   * it refunds), the return it is linked to, the invoices it rectifies and the rectificativas the
   * return already had before this call (a duplicate is allowed, not silent).
   */
  private static void describeCreatedInvoice(JSONObject data, Invoice completed,
      ShipmentInOut returnDoc, List<String> originInvoiceIds, JSONArray previousReturnInvoices)
      throws Exception {
    DocumentType type = completed.getDocumentType();
    data.put("documentType", type != null && type.getName() != null ? type.getName()
        : JSONObject.NULL);
    data.put("isSalesTransaction", Boolean.TRUE.equals(completed.isSalesTransaction()));
    data.put("grandTotalAmount", completed.getGrandTotalAmount() != null
        ? completed.getGrandTotalAmount() : JSONObject.NULL);
    data.put("totalLines", completed.getSummedLineAmount() != null
        ? completed.getSummedLineAmount() : JSONObject.NULL);
    if (returnDoc != null) {
      ReturnShipmentUtils.putQuietly(data, "linkedReturnId", returnDoc.getId());
      ReturnShipmentUtils.putQuietly(data, "returnDocumentNo", returnDoc.getDocumentNo());
    }
    JSONArray rectified = new JSONArray();
    for (String originId : originInvoiceIds) {
      Invoice origin = OBDal.getInstance().get(Invoice.class, originId);
      JSONObject row = new JSONObject().put("id", originId);
      ReturnShipmentUtils.putQuietly(row, ReturnShipmentUtils.FIELD_DOCUMENT_NO, origin != null ? origin.getDocumentNo() : null);
      rectified.put(row);
    }
    data.put("rectifiedInvoices", rectified);
    data.put("previousReturnInvoices",
        previousReturnInvoices != null ? previousReturnInvoices : new JSONArray());
  }

  /**
   * The shared body of {@code createReturnInvoice} of both return headers (ETP-5712): resolves the
   * invoices to rectify, refuses a return that already has a rectificativa (unless
   * {@code allowDuplicate:true}), builds the header,
   * refuses a document that would end at a zero amount, and only then persists, links and
   * confirms.
   *
   * <p>Order matters: everything that can refuse runs BEFORE the first {@code save}, so a refusal
   * leaves nothing behind (no half-created invoice, nothing to roll back). A failure after it rolls
   * the transaction back explicitly before propagating.
   *
   * @param isSales {@code true} for the customer return (sales invoice), {@code false} for the
   *     return to a vendor
   * @param context the request context: it supplies the request body (which invoices to rectify)
   *     and the {@link OBContext} the completion runs under
   * @return the 200 with the created invoice, or the refusal
   */
  static NeoResponse createReturnInvoice(ShipmentInOut returnDoc, String returnId,
      List<ShipmentInOutLine> lines, DocumentType docType, boolean isSales, NeoContext context,
      CreateDraftInvoiceHandler createDraftInvoiceHandler) throws Exception {
    JSONObject body = context.getRequestBody();
    OBContext obContext = context.getObContext();
    // ETP-5712: one rectificativa per return, like the screen (it hides the button once the
    // return has one). The check reads only, so it runs before anything else.
    JSONArray previous = ReturnShipmentUtils.fetchPreviousReturnInvoices(returnId);
    NeoResponse duplicate = refuseIfAlreadyInvoiced(body, previous);
    if (duplicate != null) {
      return duplicate;
    }
    // ETP-5381: resolve WHICH invoices are being rectified before creating anything. Without
    // the C_Invoice_Reverse link the rectificative invoice cannot be confirmed, so failing
    // here leaves no stuck draft behind.
    List<String> rectifiedIds = resolveRectifiedInvoiceIds(body, returnId);

    Invoice sourceInvoice = findSourceInvoice(lines);
    Invoice invoice = ReturnShipmentUtils.buildReturnInvoiceHeader(returnDoc, docType, sourceInvoice, isSales);
    NeoResponse refused = refuseIfUnpriced(lines, invoice, createDraftInvoiceHandler, rectifiedIds);
    if (refused != null) {
      return refused;
    }
    try {
      OBDal.getInstance().save(invoice);
      OBDal.getInstance().flush();
      return finalizeReturnInvoice(invoice, lines, createDraftInvoiceHandler, rectifiedIds,
          obContext, returnDoc, previous);
    } catch (Exception e) {
      // From the first save on, ANY failure must discard the draft: the request filter commits
      // unless an exception escapes, and the handlers turn this one into an error answer. The
      // core completion rolls back on most refusals, but not on all (a ServletException, a hook
      // throwing at runtime), so do it here too. The return itself is already CO from a previous
      // request, so nothing this request needs persisted is lost.
      rollbackQuietly();
      throw e;
    }
  }

  /**
   * ETP-5712: the one-rectificativa-per-return rule of the screen, for every channel. Refuses with
   * 409 {@code returnInvoiceAlreadyExists} (the message is the exact sentence the SPA localizes)
   * when the return already has a non-voided rectificativa, unless the request carries the JSON
   * boolean {@code allowDuplicate:true}. The check is strict: a string, a number or {@code null}
   * is never read as true and never fails open, it is a 422.
   *
   * @param body the request body; {@code null} reads as empty
   * @param previous the non-voided rectificativas the return already has, as
   *     {@link ReturnShipmentUtils#fetchPreviousReturnInvoices} reports them
   * @return the 409 or 422, or {@code null} when the creation may go on
   */
  static NeoResponse refuseIfAlreadyInvoiced(JSONObject body, JSONArray previous) {
    return DuplicateCreationRefusal.refuseUnlessAllowed(body, previous,
        ReturnShipmentUtils.ERROR_RETURN_INVOICE_ALREADY_EXISTS,
        ReturnShipmentUtils.MSG_RETURN_INVOICE_ALREADY_EXISTS,
        ReturnShipmentUtils.HINT_RETURN_INVOICE_ALREADY_EXISTS, "previousReturnInvoices");
  }

  /**
   * Rolls the current transaction back, never throwing: it may run right after the core already
   * rolled back and closed the session, and a failure here must not hide the error being
   * propagated.
   */
  private static void rollbackQuietly() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (RuntimeException rollbackFailure) {
      log.warn("Rollback after a failed return invoice creation did not complete: {}",
          rollbackFailure.getMessage());
    }
  }

  /**
   * ETP-5712: refuses (422 {@code returnInvoiceWithoutAmount}) a rectificativa in which ANY
   * product line, with a quantity, has NO price source at all. The alternative was a COMPLETED
   * document with a total of 0 (nothing to refund, books a zero, cannot be edited once confirmed).
   * A 0 that comes from the completed invoice line being rectified is a source (a deliberately
   * free product is refunded at 0); a 0 that only the order line or the price list yields is not.
   * It judges per line, not on the document total: one unpriced product among priced ones still
   * refunds the wrong amount, the same stance as {@link InvoiceCompletionLineGuard}.
   *
   * <p>It must run BEFORE anything is saved: it probes each line with the very code that will
   * price it ({@link #resolveLinePricing}, the same resolver {@link #buildAndSaveInvoiceLine}
   * uses) on a transient line that is never persisted.
   *
   * @param lines the return shipment lines
   * @param invoice the transient header the lines will be built under (its price list matters)
   * @param createDraftInvoiceHandler builds the handler's line from the shipment line
   * @return the 422, or {@code null} when every line has a price source
   */
  static NeoResponse refuseIfUnpriced(List<ShipmentInOutLine> lines, Invoice invoice,
      CreateDraftInvoiceHandler createDraftInvoiceHandler) {
    return refuseIfUnpriced(lines, invoice, createDraftInvoiceHandler, Collections.emptyList());
  }

  /**
   * Same as above, with the invoices being rectified: the price a line is judged and built with is
   * then the one on THOSE invoices' lines (see {@link #resolveLinePricing}).
   *
   * @param lines the return shipment lines
   * @param invoice the (transient) header the lines would be built under
   * @param createDraftInvoiceHandler builds the handler's line from the shipment line
   * @param rectifiedIds the invoices the corrective invoice rectifies; empty looks the source
   *     invoice line up across every completed invoice of the shipment line
   * @return the 422, or {@code null} when every line has a price source
   */
  static NeoResponse refuseIfUnpriced(List<ShipmentInOutLine> lines, Invoice invoice,
      CreateDraftInvoiceHandler createDraftInvoiceHandler, List<String> rectifiedIds) {
    List<String> unpriced = new ArrayList<>();
    for (ShipmentInOutLine retLine : lines) {
      String label = unpricedLabel(retLine, invoice, createDraftInvoiceHandler, rectifiedIds);
      if (label != null && !unpriced.contains(label)) {
        unpriced.add(label);
      }
    }
    return unpriced.isEmpty() ? null : withoutAmountError(unpriced);
  }

  /**
   * The product label of a return line with no price source, or {@code null} when the line is
   * skipped (no product, no quantity: {@code addReturnInvoiceLines} skips it too) or has one.
   *
   * @param retLine the return shipment line to judge
   * @param invoice the (transient) header the line would be built under
   * @param createDraftInvoiceHandler builds the handler's line from the shipment line
   * @param rectifiedIds the invoices being rectified; empty means any completed invoice
   * @return the product label, or {@code null} when the line is skipped or priced
   */
  private static String unpricedLabel(ShipmentInOutLine retLine, Invoice invoice,
      CreateDraftInvoiceHandler createDraftInvoiceHandler, List<String> rectifiedIds) {
    BigDecimal qty = retLine.getMovementQuantity() != null
        ? retLine.getMovementQuantity().abs().negate() : BigDecimal.ZERO;
    if (retLine.getProduct() == null || qty.signum() == 0) {
      return null;
    }
    if (resolveLinePricing(invoice, retLine, qty, 0L, createDraftInvoiceHandler,
        rectifiedIds).priceFound) {
      return null;
    }
    String name = retLine.getProduct().getName();
    return name != null && !name.isBlank() ? name : retLine.getProduct().getId();
  }

  /** The 422 {@code returnInvoiceWithoutAmount} naming the unpriced products. */
  private static NeoResponse withoutAmountError(List<String> unpriced) {
    try {
      JSONObject error = new JSONObject();
      error.put("status", ReturnableQuantity.SC_UNPROCESSABLE);
      error.put("code", ReturnShipmentUtils.ERROR_RETURN_INVOICE_WITHOUT_AMOUNT);
      error.put("message", ReturnShipmentUtils.MSG_WITHOUT_AMOUNT_PREFIX + String.join(", ", unpriced)
          + ReturnShipmentUtils.MSG_WITHOUT_AMOUNT_SUFFIX);
      error.put("products", new JSONArray(unpriced));
      return NeoResponse.error(ReturnableQuantity.SC_UNPROCESSABLE,
          new JSONObject().put("error", error));
    } catch (Exception e) {
      return NeoResponse.error(ReturnableQuantity.SC_UNPROCESSABLE,
          ReturnShipmentUtils.MSG_WITHOUT_AMOUNT_PREFIX + String.join(", ", unpriced) + ReturnShipmentUtils.MSG_WITHOUT_AMOUNT_SUFFIX);
    }
  }

  /**
   * The single pricing chain of a return invoice line, shared by the zero-amount probe
   * ({@link #refuseIfUnpriced}) and the real build ({@link #buildAndSaveInvoiceLine}) so the two
   * cannot drift: the line the handler builds from the shipment line, then the source invoice
   * line (completed invoices only, those being rectified when known), then the price list.
   *
   * <p>A line is {@linkplain PricedLine#priceFound found} when it ends at a non-zero price, or at
   * the price of the source invoice line even if that is 0: a deliberately free product was
   * invoiced at 0 and is refunded at 0. A 0 coming only from the handler (order line) or the
   * price list is not found, so a missing price is never mistaken for a free one.
   *
   * <p>It only reads and builds a transient line: it never saves it nor attaches it to the
   * session; applying the fallback price onto the line is the caller's decision
   * ({@link PricedLine#applyFallbackTo}).
   *
   * @param invoice the (transient) header the line is built under; its price list matters
   * @param retLine the return shipment line being priced
   * @param qty the (negative) quantity of the invoice line
   * @param lineNo the line number given to the transient line
   * @param createDraftInvoiceHandler builds the handler's line from the shipment line
   * @param rectifiedIds the invoices being rectified; empty means any completed invoice
   * @return the transient line, its price and whether a price source exists
   */
  private static PricedLine resolveLinePricing(Invoice invoice, ShipmentInOutLine retLine,
      BigDecimal qty, long lineNo, CreateDraftInvoiceHandler createDraftInvoiceHandler,
      List<String> rectifiedIds) {
    ShipmentInOutLine origLine = retLine.getCanceledInoutLine();
    ShipmentInOutLine pricingLine = origLine != null ? origLine : retLine;
    InvoiceLine il = createDraftInvoiceHandler.createShipmentInvoiceLine(invoice, pricingLine, qty,
        lineNo);
    BigDecimal unitPrice = il != null && il.getUnitPrice() != null ? il.getUnitPrice()
        : BigDecimal.ZERO;
    BigDecimal fallbackList = null;
    boolean overridden = false;
    boolean priceFound = unitPrice.signum() != 0;
    if (!priceFound && origLine != null) {
      Optional<BigDecimal> sourcePrice = resolvePriceFromSourceInvoiceLine(origLine.getId(),
          "unitPrice", rectifiedIds);
      if (sourcePrice.isPresent()) {
        unitPrice = sourcePrice.get();
        fallbackList = resolvePriceFromSourceInvoiceLine(origLine.getId(), "listPrice",
            rectifiedIds).orElse(unitPrice);
        overridden = true;
        priceFound = true;
      }
    }
    if (!priceFound && retLine.getProduct() != null && invoice.getPriceList() != null) {
      String productId = retLine.getProduct().getId();
      String priceListId = invoice.getPriceList().getId();
      unitPrice = resolvePriceFromPriceList(productId, priceListId, "standardPrice", BigDecimal.ZERO);
      fallbackList = resolvePriceFromPriceList(productId, priceListId, "listPrice", unitPrice);
      overridden = true;
      priceFound = unitPrice.signum() != 0;
    }
    return new PricedLine(il, unitPrice, fallbackList, overridden, priceFound);
  }

  /** Outcome of {@link #resolveLinePricing}: the transient line and the price it ends with. */
  private static final class PricedLine {
    final InvoiceLine line;
    final BigDecimal unitPrice;
    private final BigDecimal listPrice;
    private final boolean overridden;
    /** Whether a price source exists: non-zero, or the real price (even 0) of the source invoice line. */
    final boolean priceFound;

    PricedLine(InvoiceLine line, BigDecimal unitPrice, BigDecimal listPrice, boolean overridden,
        boolean priceFound) {
      this.priceFound = priceFound;
      this.line = line;
      this.unitPrice = unitPrice;
      this.listPrice = listPrice;
      this.overridden = overridden;
    }

    /** Writes the fallback price onto the line when a fallback (not the handler) priced it. */
    void applyFallbackTo(BigDecimal qty, int precision) {
      if (!overridden) {
        return;
      }
      line.setUnitPrice(unitPrice);
      line.setListPrice(listPrice);
      line.setLineNetAmount(qty.multiply(unitPrice).setScale(precision, RoundingMode.HALF_UP));
    }
  }

  /**
   * Creates the {@code C_Invoice_Reverse} rows tying a rectificative invoice to the invoices it
   * rectifies, skipping any link that already exists.
   *
   * <p>Note the DB trigger also requires both invoices to share a business partner; candidates are
   * sourced from the return document's own origin invoices, so that holds by construction.
   */
  static void linkRectifiedInvoices(Invoice invoice, List<String> originInvoiceIds) {
    if (originInvoiceIds == null || originInvoiceIds.isEmpty()) {
      throw new OBException(ReturnShipmentUtils.ERR_RECTIFIED_INVOICE_REQUIRED);
    }
    for (String originId : originInvoiceIds) {
      Invoice origin = OBDal.getInstance().get(Invoice.class, originId);
      if (origin == null) {
        throw new OBException("Invoice to rectify not found: " + originId);
      }
      InvoiceRectificationLinkSupport.ensureReverseLink(invoice, origin);
    }
  }

  /**
   * Resolves which invoices a rectificative invoice will rectify: the ones the caller explicitly
   * picked, or — when the caller picked none — the newest invoice auto-detected from the return
   * lines.
   *
   * <p>The fallback reads from {@link RectifiableInvoiceUtils#fetchAutoDetectedInvoices}, NOT from
   * the broader selectable
   * list: that list is every confirmed invoice of the flow, so falling back to its first entry
   * would silently rectify an arbitrary unrelated invoice. Only the document chain
   * (return line → original line → its invoice) is a safe automatic answer.
   *
   * <p>Never returns empty: a rectificative invoice with no rectified invoice cannot be confirmed,
   * so failing here — before anything is written — is strictly better than creating a document
   * that is stuck in draft forever.
   */
  static List<String> resolveRectifiedInvoiceIds(JSONObject body, String inOutId) {
    List<String> ids = new ArrayList<>();
    JSONArray requested = body != null ? body.optJSONArray(ReturnShipmentUtils.PARAM_ORIGIN_INVOICES) : null;
    if (requested != null) {
      for (int i = 0; i < requested.length(); i++) {
        String id = requested.optString(i, null);
        if (id != null && !id.isBlank() && !ids.contains(id)) {
          ids.add(id);
        }
      }
    }
    if (ids.isEmpty()) {
      List<JSONObject> detected = RectifiableInvoiceUtils.fetchAutoDetectedInvoices(inOutId);
      if (!detected.isEmpty()) {
        ids.add(detected.get(0).optString("id"));
      }
    }
    if (ids.isEmpty()) {
      throw new OBException(ReturnShipmentUtils.ERR_RECTIFIED_INVOICE_REQUIRED);
    }
    return ids;
  }

  // ---------------------------------------------------------------------------
  // Invoice building helpers
  // ---------------------------------------------------------------------------

  static Invoice findSourceInvoice(List<ShipmentInOutLine> lines) {
    for (ShipmentInOutLine retLine : lines) {
      ShipmentInOutLine origLine = retLine.getCanceledInoutLine();
      if (origLine == null) continue;
      String hql = "SELECT il.invoice FROM InvoiceLine il " +
          "WHERE il.goodsShipmentLine.id = :lineId AND il.invoice.documentStatus != 'VO'";
      List<Invoice> results = OBDal.getInstance().getSession()
          .createQuery(hql, Invoice.class)
          .setParameter("lineId", origLine.getId())
          .setMaxResults(1)
          .list();
      if (!results.isEmpty()) return results.get(0);
    }
    return null;
  }

  /**
   * Builds and saves the invoice lines of a return, pricing them from any completed invoice.
   *
   * @param invoice the header the lines are added to
   * @param lines the return shipment lines
   * @param createDraftInvoiceHandler builds the handler's line from the shipment line
   */
  static void addReturnInvoiceLines(Invoice invoice, List<ShipmentInOutLine> lines,
      CreateDraftInvoiceHandler createDraftInvoiceHandler) {
    addReturnInvoiceLines(invoice, lines, createDraftInvoiceHandler, Collections.emptyList());
  }

  /**
   * Builds and saves the invoice lines of a return; the source price of each line is read from the
   * invoices being rectified.
   *
   * @param invoice the header the lines are added to
   * @param lines the return shipment lines
   * @param createDraftInvoiceHandler builds the handler's line from the shipment line
   * @param rectifiedIds the invoices being rectified; empty means any completed invoice
   */
  static void addReturnInvoiceLines(Invoice invoice, List<ShipmentInOutLine> lines,
      CreateDraftInvoiceHandler createDraftInvoiceHandler, List<String> rectifiedIds) {
    int precision = invoice.getCurrency() != null
        ? invoice.getCurrency().getStandardPrecision().intValue() : 2;
    long lineNo = 10;
    for (ShipmentInOutLine retLine : lines) {
      // ETP-4737: the rectificative invoice line must always come out negative regardless of
      // which sign convention the SOURCE return document uses for movementQuantity. When this
      // was written Sales (RFC Receipt) stored it positive while Purchases (RTV Shipment)
      // already stored it negative, so a blind .negate() flipped Purchase-side rectificativas
      // back to positive (e.g. REC-1000007). abs().negate() forces the correct sign either way
      // without touching the return document's own movementQuantity.
      // ETP-5313 aligned Sales onto the same stored-negative convention (see
      // ReturnLineQuantityPolicy), so both sides now arrive negative — this stays sign-agnostic
      // on purpose: it must keep working for documents created before that change.
      BigDecimal qty = retLine.getMovementQuantity() != null
          ? retLine.getMovementQuantity().abs().negate() : BigDecimal.ZERO;
      if (retLine.getProduct() == null || qty.compareTo(BigDecimal.ZERO) == 0) continue;
      buildAndSaveInvoiceLine(invoice, retLine, qty, precision, lineNo, createDraftInvoiceHandler,
          rectifiedIds);
      lineNo += 10;
    }
  }

  /**
   * Prices, completes (tax) and saves one invoice line of a return.
   *
   * @param invoice the header the line is added to
   * @param retLine the return shipment line
   * @param qty the (negative) quantity of the invoice line
   * @param precision the currency precision of the line net amount
   * @param lineNo the line number
   * @param createDraftInvoiceHandler builds the handler's line from the shipment line
   * @param rectifiedIds the invoices being rectified; empty means any completed invoice
   * @throws OBException when no tax rate can be determined for the product
   */
  static void buildAndSaveInvoiceLine(Invoice invoice, ShipmentInOutLine retLine,
      BigDecimal qty, int precision, long lineNo, CreateDraftInvoiceHandler createDraftInvoiceHandler,
      List<String> rectifiedIds) {
    PricedLine priced = resolveLinePricing(invoice, retLine, qty, lineNo, createDraftInvoiceHandler,
        rectifiedIds);
    InvoiceLine il = priced.line;
    il.setGoodsShipmentLine(retLine);
    // The original document's order line is only the SOURCE of price and tax. Keeping it as the
    // line's C_OrderLine_ID would make the core treat this negative line as an invoice OF that
    // order: C_INVOICE_POST adds the (negative) quantity to C_OrderLine.QtyInvoiced, so a fully
    // invoiced order reads as pending again and its linked-invoice total drops below the order
    // total. The rectificative invoice is tied to the return document through
    // M_InOutLine_ID (above) and to the invoice it rectifies through C_Invoice_Reverse — never
    // to the order.
    il.setSalesOrderLine(null);
    priced.applyFallbackTo(qty, precision);

    if (il.getTax() == null && retLine.getProduct() != null) {
      il.setTax(resolveApplicableTax(invoice, retLine));
    }
    if (il.getTax() == null) {
      throw new OBException("Cannot determine tax rate for product '"
          + retLine.getProduct().getName() + "'.");
    }

    OBDal.getInstance().save(il);
  }

  static TaxRate resolveApplicableTax(Invoice invoice, ShipmentInOutLine retLine) {
    try {
      String productId = retLine.getProduct().getId();
      String orgId = invoice.getOrganization().getId();
      String warehouseId = retLine.getShipmentReceipt() != null
          && retLine.getShipmentReceipt().getWarehouse() != null
          ? retLine.getShipmentReceipt().getWarehouse().getId() : "";
      String bpLocId = invoice.getPartnerAddress() != null
          ? invoice.getPartnerAddress().getId() : "";
      Date invoiceDate = invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : new Date();
      String strDate = new SimpleDateFormat("dd-MM-yyyy").format(invoiceDate);
      boolean isSOTrx = Boolean.TRUE.equals(invoice.isSalesTransaction());
      String taxId = Tax.get(new DalConnectionProvider(), productId, strDate, orgId,
          warehouseId, bpLocId, bpLocId, "", isSOTrx);
      if (taxId != null && !taxId.isEmpty()) {
        return OBDal.getInstance().get(TaxRate.class, taxId);
      }
    } catch (Exception e) {
      log.warn("Tax.get() fallback failed for product {}: {}", retLine.getProduct().getId(),
          e.getMessage());
    }
    return null;
  }

  /**
   * The HQL of {@link #resolvePriceFromSourceInvoiceLine}, exposed so a test can parse it against
   * the real model (a bad property path is otherwise swallowed and poisons the transaction).
   *
   * @param priceField the {@code InvoiceLine} property to select ({@code unitPrice}, {@code listPrice})
   * @param constrained whether the lookup is restricted to the {@code :invoiceIds} being rectified
   * @return the HQL string
   */
  static String sourceInvoiceLinePriceHql(String priceField, boolean constrained) {
    return "SELECT il." + priceField + " FROM InvoiceLine il " +
        "WHERE il.goodsShipmentLine.id = :lineId " +
        "AND il.active = true " +
        "AND il.invoice.documentStatus = 'CO' " +
        (constrained ? "AND il.invoice.id IN (:invoiceIds) " : "") +
        "ORDER BY il.invoice.invoiceDate DESC, il.invoice.creationDate DESC, "
        + "il.lineNo DESC, il.id DESC";
  }

  /**
   * The price field of the completed invoice line that invoiced a shipment line, or empty when
   * there is none. A 0 IS an answer (a deliberately free product); only a missing row or a null
   * price is empty. A {@link QueryException} (a bad HQL) is rethrown, not swallowed.
   *
   * @param shipmentLineId the shipment line the invoice line invoiced; {@code null} gives empty
   * @param priceField the {@code InvoiceLine} property to read ({@code unitPrice}, {@code listPrice})
   * @param rectifiedIds when not empty, only lines of THOSE invoices count, so what is credited is
   *     literally what was invoiced; empty keeps the latest completed invoice line of the
   *     shipment line
   * @return the price, possibly 0, or empty when there is no completed invoice line or no price
   */
  static Optional<BigDecimal> resolvePriceFromSourceInvoiceLine(String shipmentLineId,
      String priceField, List<String> rectifiedIds) {
    if (shipmentLineId == null) return Optional.empty();
    boolean constrained = rectifiedIds != null && !rectifiedIds.isEmpty();
    try {
      String hql = sourceInvoiceLinePriceHql(priceField, constrained);
      Query<BigDecimal> query = OBDal.getInstance().getSession()
          .createQuery(hql, BigDecimal.class)
          .setParameter("lineId", shipmentLineId);
      if (constrained) {
        query = query.setParameterList("invoiceIds", rectifiedIds);
      }
      List<BigDecimal> rows = query.setMaxResults(1).list();
      if (!rows.isEmpty()) {
        return Optional.ofNullable(rows.get(0));
      }
      log.warn("No completed invoice line found for shipment line {} ({})", shipmentLineId, priceField);
    } catch (QueryException e) {
      throw badHql(e);
    } catch (Exception e) {
      // Everything else (a lookup that fails or finds nothing) falls through to the next price
      // source. A QueryException is different, see badHql.
      log.warn("Could not resolve {} from source invoice line {}: {}",
          priceField, shipmentLineId, e.getMessage());
    }
    return Optional.empty();
  }

  /**
   * A QueryException is a programming error (a bad property path), not a missing price: it leaves
   * the Hibernate transaction rollback-only, so the completion that follows would silently roll
   * the whole draft back. Rethrown so it fails loudly (500, logged with its stack trace); the
   * property paths are checked against the real model by
   * {@code ReturnLineQuantitySignIntegrationTest}.
   */
  private static QueryException badHql(QueryException e) {
    log.error("Invalid HQL in a return invoice price lookup: {}", e.getMessage(), e);
    return e;
  }

  /**
   * The HQL of {@link #resolvePriceFromPriceList}, exposed so a test can parse it against the real
   * model.
   *
   * @param priceProperty the {@code PricingProductPrice} property to select
   * @return the HQL string
   */
  static String priceListPriceHql(String priceProperty) {
    return "SELECT pp." + priceProperty + " FROM PricingProductPrice pp " +
        "WHERE pp.product.id = :productId " +
        "AND pp.priceListVersion.priceList.id = :priceListId " +
        "AND pp.priceListVersion.active = true " +
        "AND pp.active = true " +
        "AND pp.priceListVersion.validFromDate <= :today " +
        "ORDER BY pp.priceListVersion.validFromDate DESC";
  }

  static BigDecimal resolvePriceFromPriceList(String productId, String priceListId,
      String priceProperty, BigDecimal fallback) {
    if (productId == null || priceListId == null) return fallback;
    try {
      String hql = priceListPriceHql(priceProperty);
      List<BigDecimal> rows = OBDal.getInstance().getSession()
          .createQuery(hql, BigDecimal.class)
          .setParameter("productId", productId)
          .setParameter("priceListId", priceListId)
          .setParameter("today", new Date())
          .setMaxResults(1)
          .list();
      if (!rows.isEmpty()) {
        BigDecimal price = rows.get(0);
        return (price != null && price.compareTo(BigDecimal.ZERO) > 0) ? price : fallback;
      }
      log.warn("No price list entry for product {} in price list {} ({})",
          productId, priceListId, priceProperty);
    } catch (QueryException e) {
      throw badHql(e);
    } catch (Exception e) {
      log.warn("Could not resolve {} for product {} from price list {}: {}",
          priceProperty, productId, priceListId, e.getMessage());
    }
    return fallback;
  }
}
