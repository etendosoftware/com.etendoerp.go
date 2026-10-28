package com.etendoerp.go.schemaforge;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Links a sales rectificative invoice to the invoice(s) it rectifies — the official
 * {@code C_Invoice_Reverse} row — when a line imported from one of them is created (ETP-6002).
 *
 * <p>The link used to depend on the SPA: the "Import from source invoice" popup PATCHed the header
 * with {@code originInvoices} after the lines were posted, carrying the {@code updated} token read
 * before the import. Creating the lines recalculates the header totals, so that PATCH always came
 * back {@code 409 stale_record}, the popup ignored it, and the rectificative stayed unlinked. The
 * "Import from return shipment" popup never sent the PATCH at all. Deriving the link from the line
 * itself makes it independent of whichever client created the line.
 *
 * <p>Two sources, both read from the line being created:
 * <ul>
 *   <li>{@code sourceInvoiceLineId} — the line was copied from an invoice line: link to that
 *   line's invoice;</li>
 *   <li>{@code goodsShipmentLine} pointing at a return line ({@code Canceled_Inoutline_ID} set) —
 *   link to every <b>completed</b> invoice of the same business partner that billed the original
 *   shipment line, the per-line form of {@link RectifiableInvoiceUtils#fetchAutoDetectedInvoices}.
 *   A return with no invoiced chain (hand-made return) links nothing: there is nothing to derive,
 *   and confirming the rectificative still asks for the link as before.</li>
 * </ul>
 *
 * <p><b>Channels.</b> The pre-hook runs on REST single, {@code /batch} and MCP. On MCP,
 * {@code sourceInvoiceLineId} reaches it (declared as a create directive by
 * {@link SalesInvoiceLineHandler#createDirectives()}), but {@code goodsShipmentLine} does not: the
 * field is curated read-only on {@code sales-invoice/lines}, so the MCP write gate refuses it with
 * {@code read_only_field} before the pre-hook. The return-line source is therefore REST-only
 * (declared in {@code neo-headless.md} §4.12.9).
 *
 * <p><b>Runs in the pre-hook, on purpose.</b> Outside a batch the default create commits the line
 * on its own, so a link written in {@code afterHandle} could only fail after the line was already
 * saved — the half-done state this fix exists to remove. Written and flushed before the create,
 * the link shares the line's transaction: if {@code C_INVOICE_REVERSE_TRG} refuses it (processed
 * invoice, different business partner) the exception reaches the dispatcher, which answers it as a
 * classified refusal and rolls back — no line is created.
 *
 * <p><b>Known gap — a create refused by a returned 4xx on REST single.</b> When the create fails
 * by throwing, or inside a {@code /batch}, the session is rolled back and the link goes with the
 * line. But {@code NeoCrudHandler#handleDefault} also refuses some creates by RETURNING a 4xx
 * without rolling back (missing required fields, a read-only field sent, the
 * {@code validatePostRequest} early error), and {@code NeoServletSupport#handleWithDefaultStep}
 * passes that response through without running {@code afterHandle}. In that case the flushed link
 * is committed with the request and no line exists. The link itself is still the one the caller
 * asked for (a retry deduplicates it), so the effect is an origin listed in "Rectificaciones" with
 * no imported line. Closing it needs a generic rollback of a returned 4xx in the shared dispatch
 * — not something this customization can do — and is left open.
 */
final class SalesRectificativeLineLinkSupport {

  static final String FIELD_SOURCE_INVOICE_LINE_ID = "sourceInvoiceLineId";
  static final String FIELD_GOODS_SHIPMENT_LINE = "goodsShipmentLine";
  static final String ERROR_SOURCE_LINE_NOT_FOUND = "sourceInvoiceLineNotFound";
  private static final int SC_UNPROCESSABLE = 422;

  private SalesRectificativeLineLinkSupport() {
  }

  /**
   * Reads {@code sourceInvoiceLineId} off a create body before {@link InvoiceLineHandler#handle}
   * removes it from the body (it is not a contract field, so the field filter would drop it).
   *
   * @param context the pre-hook context
   * @return the id, or {@code null} when absent or not a CRUD POST
   */
  static String peekSourceInvoiceLineId(NeoContext context) {
    JSONObject body = createBody(context);
    return body != null ? idOrNull(body, FIELD_SOURCE_INVOICE_LINE_ID) : null;
  }

  /**
   * Ensures the {@code C_Invoice_Reverse} rows a created line implies, when the line's invoice is
   * a sales rectificative. Abstains (returns {@code null}, writes nothing) for any other request,
   * a body with neither source, or an invoice that is not a rectificative.
   *
   * @param context             the pre-hook context of the line create
   * @param sourceInvoiceLineId the id {@link #peekSourceInvoiceLineId} read, or {@code null}
   * @return a 422 when {@code sourceInvoiceLineId} names no invoice line; {@code null} to let the
   *     create continue
   */
  static NeoResponse linkOnCreate(NeoContext context, String sourceInvoiceLineId) {
    JSONObject body = createBody(context);
    if (body == null) {
      return null;
    }
    String shipmentLineId = idOrNull(body, FIELD_GOODS_SHIPMENT_LINE);
    if (StringUtils.isBlank(sourceInvoiceLineId) && StringUtils.isBlank(shipmentLineId)) {
      return null;
    }
    String invoiceId = idOrNull(body, InvoiceLineAmountSupport.FIELD_PARENT_ID);
    if (StringUtils.isBlank(invoiceId)) {
      invoiceId = idOrNull(body, InvoiceLineAmountSupport.FIELD_INVOICE);
    }
    if (StringUtils.isBlank(invoiceId)) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
      if (invoice == null
          || !SalesInvoiceHeaderHandler.isRectificativeDocType(invoice.getTransactionDocument())) {
        return null;
      }
      Map<String, Invoice> origins = new LinkedHashMap<>();
      if (StringUtils.isNotBlank(sourceInvoiceLineId)) {
        InvoiceLine sourceLine = OBDal.getInstance().get(InvoiceLine.class, sourceInvoiceLineId);
        if (sourceLine == null) {
          return sourceLineNotFound(sourceInvoiceLineId);
        }
        addOrigin(origins, sourceLine.getInvoice());
      }
      if (StringUtils.isNotBlank(shipmentLineId)) {
        addReturnedLineInvoices(origins, shipmentLineId, invoice);
      }
      origins.remove(invoice.getId());
      boolean created = false;
      for (Invoice origin : origins.values()) {
        created |= InvoiceRectificationLinkSupport.ensureReverseLink(invoice, origin);
      }
      if (created) {
        // Here, not at commit: C_INVOICE_REVERSE_TRG's refusal must surface before the line is
        // created (see the class javadoc).
        OBDal.getInstance().flush();
      }
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Adds every completed invoice of the rectificative's own business partner that billed the
   * shipment line the given return line cancels. Nothing for a line that is not a return line,
   * whose original line was never invoiced, or for a rectificative with no partner yet.
   *
   * <p>The partner filter is the reason {@link RectifiableInvoiceUtils#fetchSelectableInvoices}
   * filters too: {@code C_INVOICE_REVERSE_TRG} unconditionally refuses a cross-partner link, so
   * one such invoice in the chain (anomalous data) would otherwise refuse the whole line import.
   */
  private static void addReturnedLineInvoices(Map<String, Invoice> origins, String shipmentLineId,
      Invoice invoice) {
    if (invoice.getBusinessPartner() == null) {
      return;
    }
    ShipmentInOutLine returnLine = OBDal.getInstance().get(ShipmentInOutLine.class, shipmentLineId);
    ShipmentInOutLine original = returnLine != null ? returnLine.getCanceledInoutLine() : null;
    if (original == null) {
      return;
    }
    for (InvoiceLine billed : OBDal.getInstance()
        .createQuery(InvoiceLine.class,
            "as il where il.goodsShipmentLine.id = :lineId and il.invoice.documentStatus = 'CO'"
                + " and il.invoice.businessPartner.id = :bpId")
        .setNamedParameter("lineId", original.getId())
        .setNamedParameter("bpId", invoice.getBusinessPartner().getId())
        .list()) {
      addOrigin(origins, billed.getInvoice());
    }
  }

  private static void addOrigin(Map<String, Invoice> origins, Invoice origin) {
    if (origin != null) {
      origins.putIfAbsent(origin.getId(), origin);
    }
  }

  private static JSONObject createBody(NeoContext context) {
    if (context == null || !NeoEndpointType.CRUD.equals(context.getEndpointType())
        || !"POST".equals(context.getHttpMethod())) {
      return null;
    }
    return context.getRequestBody();
  }

  private static String idOrNull(JSONObject body, String key) {
    String value = body.optString(key, null);
    return StringUtils.isBlank(value) || "null".equals(value) ? null : value;
  }

  private static NeoResponse sourceLineNotFound(String sourceInvoiceLineId) {
    String message = "The source invoice line " + sourceInvoiceLineId + " does not exist.";
    try {
      JSONObject error = new JSONObject();
      error.put("status", SC_UNPROCESSABLE);
      error.put("code", ERROR_SOURCE_LINE_NOT_FOUND);
      error.put("message", message);
      return NeoResponse.error(SC_UNPROCESSABLE, new JSONObject().put("error", error));
    } catch (JSONException e) {
      return NeoResponse.error(SC_UNPROCESSABLE, message);
    }
  }
}
