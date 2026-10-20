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
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.Tax;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.ReversedInvoice;
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
   * @param returnDoc the return document the invoice refunds; {@code null} leaves
   *     {@code linkedReturnId} / {@code returnDocumentNo} out of the answer
   * @param previousReturnInvoices the rectificativas the return had BEFORE this call, as
   *     {@link ReturnShipmentUtils#fetchPreviousReturnInvoices} reports them; {@code null} reads as none
   */
  static NeoResponse finalizeReturnInvoice(Invoice invoice, List<ShipmentInOutLine> lines,
      CreateDraftInvoiceHandler createDraftInvoiceHandler, List<String> originInvoiceIds,
      OBContext obContext, ShipmentInOut returnDoc, JSONArray previousReturnInvoices)
      throws Exception {
    addReturnInvoiceLines(invoice, lines, createDraftInvoiceHandler);
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().refresh(invoice);
    createDraftInvoiceHandler.ensureDocumentNo(invoice);
    createDraftInvoiceHandler.getSupport().ensureLineGrossAmounts(invoice);
    linkRectifiedInvoices(invoice, originInvoiceIds);
    OBDal.getInstance().flush();

    String invoiceId = invoice.getId();
    InvoiceCompletionService.completeInvoiceOrThrow(invoiceId, obContext);
    Invoice completed = OBDal.getInstance().get(Invoice.class, invoiceId);
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
   * invoices to rectify, notes the rectificativas the return already has, builds the header,
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
    // ETP-5381: resolve WHICH invoices are being rectified before creating anything. Without
    // the C_Invoice_Reverse link the rectificative invoice cannot be confirmed, so failing
    // here leaves no stuck draft behind.
    List<String> rectifiedIds = resolveRectifiedInvoiceIds(body, returnId);
    // A second rectificative on the same return is allowed (ETP-5381); it is reported instead.
    JSONArray previous = ReturnShipmentUtils.fetchPreviousReturnInvoices(returnId);

    Invoice sourceInvoice = findSourceInvoice(lines);
    Invoice invoice = ReturnShipmentUtils.buildReturnInvoiceHeader(returnDoc, docType, sourceInvoice, isSales);
    NeoResponse refused = refuseIfUnpriced(lines, invoice, createDraftInvoiceHandler);
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
   * product line, with a quantity, would end at a unit price of 0. The alternative was a COMPLETED
   * document with a total of 0 (nothing to refund, books a zero, cannot be edited once confirmed).
   * It judges per line, not on the document total: one unpriced product among priced ones still
   * refunds the wrong amount, the same stance as {@link InvoiceCompletionLineGuard}.
   *
   * <p>It must run BEFORE anything is saved: it probes each line with the very code that will
   * price it ({@link #resolveLinePricing}, the same resolver {@link #buildAndSaveInvoiceLine}
   * uses) on a transient line that is never persisted.
   *
   * @param invoice the transient header the lines will be built under (its price list matters)
   * @return the 422, or {@code null} when every line has a price
   */
  static NeoResponse refuseIfUnpriced(List<ShipmentInOutLine> lines, Invoice invoice,
      CreateDraftInvoiceHandler createDraftInvoiceHandler) {
    List<String> unpriced = new ArrayList<>();
    for (ShipmentInOutLine retLine : lines) {
      String label = unpricedLabel(retLine, invoice, createDraftInvoiceHandler);
      if (label != null && !unpriced.contains(label)) {
        unpriced.add(label);
      }
    }
    return unpriced.isEmpty() ? null : withoutAmountError(unpriced);
  }

  /**
   * The product label of a return line that would end at a unit price of 0, or {@code null} when
   * the line is skipped (no product, no quantity: {@code addReturnInvoiceLines} skips it too) or
   * has a price.
   */
  private static String unpricedLabel(ShipmentInOutLine retLine, Invoice invoice,
      CreateDraftInvoiceHandler createDraftInvoiceHandler) {
    BigDecimal qty = retLine.getMovementQuantity() != null
        ? retLine.getMovementQuantity().abs().negate() : BigDecimal.ZERO;
    if (retLine.getProduct() == null || qty.signum() == 0) {
      return null;
    }
    if (resolveLinePricing(invoice, retLine, qty, 0L, createDraftInvoiceHandler).unitPrice.signum() != 0) {
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
   * line (completed invoices only), then the price list. It only reads and builds a transient
   * line: it never saves it nor attaches it to the session; applying the fallback price onto the
   * line is the caller's decision ({@link PricedLine#applyFallbackTo}).
   */
  private static PricedLine resolveLinePricing(Invoice invoice, ShipmentInOutLine retLine,
      BigDecimal qty, long lineNo, CreateDraftInvoiceHandler createDraftInvoiceHandler) {
    ShipmentInOutLine origLine = retLine.getCanceledInoutLine();
    ShipmentInOutLine pricingLine = origLine != null ? origLine : retLine;
    InvoiceLine il = createDraftInvoiceHandler.createShipmentInvoiceLine(invoice, pricingLine, qty,
        lineNo);
    BigDecimal unitPrice = il != null && il.getUnitPrice() != null ? il.getUnitPrice()
        : BigDecimal.ZERO;
    BigDecimal fallbackList = null;
    boolean overridden = false;
    if (unitPrice.signum() == 0 && origLine != null) {
      unitPrice = resolvePriceFromSourceInvoiceLine(origLine.getId(), "unitPrice", BigDecimal.ZERO);
      if (unitPrice.signum() != 0) {
        fallbackList = resolvePriceFromSourceInvoiceLine(origLine.getId(), "listPrice", unitPrice);
        overridden = true;
      }
    }
    if (unitPrice.signum() == 0 && retLine.getProduct() != null && invoice.getPriceList() != null) {
      String productId = retLine.getProduct().getId();
      String priceListId = invoice.getPriceList().getId();
      unitPrice = resolvePriceFromPriceList(productId, priceListId, "standardPrice", BigDecimal.ZERO);
      fallbackList = resolvePriceFromPriceList(productId, priceListId, "listPrice", unitPrice);
      overridden = true;
    }
    return new PricedLine(il, unitPrice, fallbackList, overridden);
  }

  /** Outcome of {@link #resolveLinePricing}: the transient line and the price it ends with. */
  private static final class PricedLine {
    final InvoiceLine line;
    final BigDecimal unitPrice;
    private final BigDecimal listPrice;
    private final boolean overridden;

    PricedLine(InvoiceLine line, BigDecimal unitPrice, BigDecimal listPrice, boolean overridden) {
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
      OBCriteria<ReversedInvoice> existing = OBDal.getInstance().createCriteria(ReversedInvoice.class);
      existing.add(Restrictions.eq(ReversedInvoice.PROPERTY_INVOICE, invoice));
      existing.add(Restrictions.eq(ReversedInvoice.PROPERTY_REVERSEDINVOICE, origin));
      if (!existing.list().isEmpty()) {
        continue;
      }
      ReversedInvoice link = OBProvider.getInstance().get(ReversedInvoice.class);
      link.setClient(invoice.getClient());
      link.setOrganization(invoice.getOrganization());
      link.setInvoice(invoice);
      link.setReversedInvoice(origin);
      OBDal.getInstance().save(link);
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

  static void addReturnInvoiceLines(Invoice invoice, List<ShipmentInOutLine> lines,
      CreateDraftInvoiceHandler createDraftInvoiceHandler) {
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
      buildAndSaveInvoiceLine(invoice, retLine, qty, precision, lineNo, createDraftInvoiceHandler);
      lineNo += 10;
    }
  }

  static void buildAndSaveInvoiceLine(Invoice invoice, ShipmentInOutLine retLine,
      BigDecimal qty, int precision, long lineNo, CreateDraftInvoiceHandler createDraftInvoiceHandler) {
    PricedLine priced = resolveLinePricing(invoice, retLine, qty, lineNo, createDraftInvoiceHandler);
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
    BigDecimal unitPrice = priced.unitPrice;

    if (il.getTax() == null && retLine.getProduct() != null) {
      il.setTax(resolveApplicableTax(invoice, retLine));
    }
    if (il.getTax() == null && unitPrice.compareTo(BigDecimal.ZERO) != 0) {
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

  static BigDecimal resolvePriceFromSourceInvoiceLine(String shipmentLineId,
      String priceField, BigDecimal fallback) {
    if (shipmentLineId == null) return fallback;
    try {
      String hql =
          "SELECT il." + priceField + " FROM InvoiceLine il " +
          "WHERE il.goodsShipmentLine.id = :lineId " +
          "AND il.active = true " +
          "AND il.invoice.documentStatus = 'CO' " +
          "ORDER BY il.invoice.invoiceDate DESC";
      List<BigDecimal> rows = OBDal.getInstance().getSession()
          .createQuery(hql, BigDecimal.class)
          .setParameter("lineId", shipmentLineId)
          .setMaxResults(1)
          .list();
      if (!rows.isEmpty()) {
        BigDecimal price = rows.get(0);
        return (price != null && price.compareTo(BigDecimal.ZERO) > 0) ? price : fallback;
      }
      log.warn("No completed invoice line found for shipment line {} ({})", shipmentLineId, priceField);
    } catch (Exception e) {
      log.warn("Could not resolve {} from source invoice line {}: {}",
          priceField, shipmentLineId, e.getMessage());
    }
    return fallback;
  }

  static BigDecimal resolvePriceFromPriceList(String productId, String priceListId,
      String priceProperty, BigDecimal fallback) {
    if (productId == null || priceListId == null) return fallback;
    try {
      String hql =
          "SELECT pp." + priceProperty + " FROM PricingProductPrice pp " +
          "WHERE pp.product.id = :productId " +
          "AND pp.priceListVersion.priceList.id = :priceListId " +
          "AND pp.priceListVersion.active = true " +
          "AND pp.active = true " +
          "AND pp.priceListVersion.validFromDate <= :today " +
          "ORDER BY pp.priceListVersion.validFromDate DESC";
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
    } catch (Exception e) {
      log.warn("Could not resolve {} for product {} from price list {}: {}",
          priceProperty, productId, priceListId, e.getMessage());
    }
    return fallback;
  }
}
