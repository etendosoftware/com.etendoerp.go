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

import static com.etendoerp.go.schemaforge.NeoPrintableCommons.coalesce;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.isTruthy;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.json;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.number;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.orNull;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.truthy;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.value;

import java.util.List;

import javax.enterprise.context.Dependent;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * The sales quotation's printable data — the Java port of {@code buildQuotationData}
 * ({@code tools/app-shell/src/windows/custom/shared/useQuotationPdf.js}) (ETP-6013), on the print
 * path ({@code currencyData = null}: no exchange-rate row, as {@code documentPdfRegistry.js} prints).
 *
 * <p><b>Which statuses store a PDF.</b> The quotation's preview stores its PDF for every status but
 * draft ({@code QuotationPreview}: {@code documentStatus !== 'DR'}) — its "Complete" leads to
 * <i>under evaluation</i>, not to {@code CO} — so {@link #storesFor} says the same.</p>
 *
 * <p><b>Dual-implementation drift risk — read before changing either side.</b> A key added to,
 * renamed in or recomputed by {@code buildQuotationData} MUST be mirrored here by hand; the parity
 * fixture shared with {@code printableSources.vitest.js} pins it ({@code PrintableDataTest}).</p>
 */
@Dependent
public class QuotationPrintableData implements NeoPrintableDataBuilder {

  static final String DOCUMENT = "sales-quotation";
  /** The line's per-product discount percentage ({@code buildQuotationData} reads the same key). */
  static final String DISCOUNT_FIELD = "discount";
  /** Closed - Rejected: the only status whose printable carries a reject reason. */
  private static final String STATUS_REJECTED = "CJ";
  /**
   * The first words of the line the customer portal appends to a rejected quotation's notes
   * ({@code portal-quotation-decision.note*} in {@code emails_{es_ES,en_US}.properties}). Mirrors
   * {@code PORTAL_REJECT_NOTE_PREFIXES} of {@code useQuotationPdf.js}.
   */
  static final List<String> PORTAL_NOTE_PREFIXES = List.of(
      "Rechazado por el cliente desde el portal.", "Rejected by the customer from the portal.");
  private static final List<String> PORTAL_REASON_MARKERS = List.of("Motivo:", "Reason:");

  @Override
  public String document() {
    return DOCUMENT;
  }

  @Override
  public boolean storesFor(String documentStatus) {
    return documentStatus != null && !NeoDocumentPdf.DRAFT_STATUS.equals(documentStatus);
  }

  @Override
  public JSONObject build(String recordId) throws PrintableDataException {
    JSONObject header = NeoPrintableReads.readRecord(DOCUMENT, "quotation", recordId);
    if (header == null) {
      throw new PrintableDataException("The quotation could not be read");
    }
    JSONArray linesRaw = NeoPrintableReads.children(DOCUMENT, "quotationLine", recordId);
    NeoResponse sessionResponse = NeoSessionService.resolveSession();
    JSONObject session = sessionResponse.getHttpStatus() < 400 ? sessionResponse.getBody() : null;
    try {
      return build(header, linesRaw, session);
    } catch (Exception e) {
      throw new PrintableDataException("The quotation data could not be built", e);
    }
  }

  /**
   * The pure half: {@code buildQuotationData}'s result from its three reads.
   *
   * @return the data
   */
  static JSONObject build(JSONObject header, JSONArray linesRaw, JSONObject session)
      throws Exception {
    // buildQuotationData passes no address fallback to buildCompanyFields.
    JSONObject data = NeoPrintableCommons.companyFields(session, header, null);

    List<JSONObject> sorted = NeoPrintableCommons.sortDocumentLines(linesRaw);
    JSONArray lines = new JSONArray();
    for (int idx = 0; idx < sorted.size(); idx++) {
      JSONObject l = sorted.get(idx);
      JSONObject line = new JSONObject();
      line.put("lineNo", truthy(value(l, "lineNo"), idx + 1));
      line.put("productCode", NeoPrintableCommons.resolveProductCode(l));
      line.put("productName", truthy(value(l, "product$_identifier"), value(l, "description"), "—"));
      line.put("quantity", coalesce(value(l, "orderedQuantity"), 0));
      line.put("unitPrice", coalesce(value(l, "unitPrice"), 0));
      Object discount = value(l, DISCOUNT_FIELD);
      // The data shape names the output after the line field it reads.
      line.put(DISCOUNT_FIELD, isTruthy(discount) ? json(number(discount)) : JSONObject.NULL);
      line.put("taxName", truthy(value(l, "tax$_identifier"), ""));
      line.put("lineTotal", coalesce(value(l, "lineNetAmount"), 0));
      lines.put(line);
    }

    double grandTotal = number(coalesce(value(header, "grandTotalAmount"), 0));
    double netAmount = number(coalesce(value(header, "summedLineAmount"), 0));
    double etgoTotalDiscount = number(coalesce(value(header, "etgoTotalDiscount"), 0));
    double[] breakdown = NeoPrintableCommons.computeDiscountBreakdown(sorted, etgoTotalDiscount,
        QuotationPrintableData::grossLine);
    double grossAmount = breakdown[0];
    double discountPerProduct = breakdown[2];
    double totalDiscountAmt = breakdown[3];

    data.put("documentNo", truthy(value(header, "documentNo"), ""));
    data.put("currencyCode", NeoPrintableCommons.resolveDocumentCurrencyCode(header));
    data.put("invoiceDate", truthy(value(header, "orderDate"), ""));
    data.put("validUntil", orNull(value(header, "validUntil")));
    data.put("customerName", truthy(value(header, "businessPartner$_identifier"),
        value(header, "businessPartner"), "—"));
    data.put("paymentMethod", orNull(value(header, "paymentMethod$_identifier")));
    data.put("paymentTerms", orNull(value(header, "paymentTerms$_identifier")));
    String[] notesAndReason = notesAndRejectReason(header);
    data.put("notes", orNull(notesAndReason[0]));
    // ETP-6209: "Motivo: …" on a rejected quotation, see notesAndRejectReason.
    data.put("rejectReason", orNull(notesAndReason[1]));
    data.put("lines", lines);
    data.put("netAmount", json(netAmount));
    data.put("taxAmount", json(grandTotal - netAmount));
    data.put("grandTotal", json(grandTotal));
    boolean anyProductDiscount = discountPerProduct != 0;
    data.put("grossAmount", anyProductDiscount ? json(grossAmount) : JSONObject.NULL);
    data.put("discountPerProduct", anyProductDiscount ? json(-discountPerProduct)
        : JSONObject.NULL);
    data.put("etgoTotalDiscount", etgoTotalDiscount > 0 ? json(etgoTotalDiscount)
        : JSONObject.NULL);
    data.put("totalDiscountAmt", totalDiscountAmt != 0 ? json(-totalDiscountAmt)
        : JSONObject.NULL);
    data.put("exchangeRate", JSONObject.NULL);
    data.put("orgCurrencyCode", JSONObject.NULL);
    data.put("orgGrandTotal", JSONObject.NULL);
    data.put("rateDecimals", coalesce(value(session, "currencyStandardPrecision"), 4));
    return data;
  }

  /**
   * The quotation's notes and its printed reject reason — {@code quotationNotesAndRejectReason} of
   * {@code useQuotationPdf.js}, line for line (ETP-6209 Part C, Jira comment 149518 point 5).
   *
   * <p>A rejected quotation ({@code CJ}) prints one observation line "Motivo: &lt;reason&gt;" with
   * the customer's own portal reason only (the "Otros" text included), read from the portal's line
   * in the notes, which is taken out of the notes so the observations never say "Motivo" twice. A
   * quotation rejected without a portal reason (e.g. in the app) prints no reason. Other statuses
   * keep their notes and print no reason.</p>
   *
   * <p><b>The tenant's reject reason ({@code rejectReason$_identifier}) is never printed:</b> it can
   * be internal, and this is the PDF the customer downloads from the portal and receives by email
   * (ETP-6209 QA).</p>
   *
   * @return {@code {notes, rejectReason}}, either {@code null}
   */
  static String[] notesAndRejectReason(JSONObject header) {
    Object rawDescription = value(header, "description");
    String description = rawDescription == null ? null : String.valueOf(rawDescription);
    String notes = description == null || description.isEmpty() ? null : description;
    if (!STATUS_REJECTED.equals(value(header, "documentStatus"))) {
      return new String[] { notes, null };
    }
    return splitPortalNote(description);
  }

  /**
   * The notes without the customer portal's decision line(s), for a copy of the quotation
   * ({@link CloneOrderLinePriceHook}) and for the portal list's reference fallback
   * ({@code PortalDocumentsEndpoint}), ETP-6209: the same detection as the printable. Notes with no
   * portal line come back untouched; notes that were only portal lines come back {@code null}.
   *
   * @param description the quotation's notes
   * @return the notes to keep
   */
  public static String withoutPortalNote(String description) {
    if (description == null || java.util.Arrays.stream(description.split("\n", -1))
        .map(String::trim).noneMatch(QuotationPrintableData::isPortalNoteLine)) {
      return description;
    }
    return splitPortalNote(description)[0];
  }

  private static boolean isPortalNoteLine(String trimmedLine) {
    return PORTAL_NOTE_PREFIXES.stream().anyMatch(trimmedLine::startsWith);
  }

  /**
   * Takes the portal's note lines out of the notes.
   *
   * @return {@code {kept notes, portal reason}}, either {@code null}
   */
  private static String[] splitPortalNote(String description) {
    StringBuilder kept = new StringBuilder();
    String portalReason = null;
    for (String line : (description == null ? "" : description).split("\n", -1)) {
      String trimmed = line.trim();
      if (isPortalNoteLine(trimmed)) {
        String reason = reasonOfPortalNote(trimmed);
        portalReason = reason != null ? reason : portalReason;
      } else {
        if (kept.length() > 0) {
          kept.append('\n');
        }
        kept.append(line);
      }
    }
    String keptNotes = kept.toString().trim();
    return new String[] { keptNotes.isEmpty() ? null : keptNotes, portalReason };
  }


  /** The reason inside a portal note line: what follows its marker, without the closing period. */
  private static String reasonOfPortalNote(String line) {
    for (String marker : PORTAL_REASON_MARKERS) {
      int at = line.indexOf(marker);
      if (at >= 0) {
        String reason = line.substring(at + marker.length()).trim();
        if (reason.endsWith(".")) {
          reason = reason.substring(0, reason.length() - 1).trim();
        }
        return reason.isEmpty() ? null : reason;
      }
    }
    return null;
  }

  /** {@code getGrossLine} of {@code buildQuotationData}. */
  static double grossLine(JSONObject l) {
    double qty = number(coalesce(value(l, "orderedQuantity"), 0));
    if (qty == 0) {
      return 0;
    }
    Object listPrice = value(l, "listPrice");
    if (listPrice != null) {
      return qty * number(listPrice);
    }
    double lineNet = number(coalesce(value(l, "lineNetAmount"), 0));
    double disc = number(coalesce(value(l, DISCOUNT_FIELD), 0));
    return disc > 0 ? lineNet / (1 - disc / 100) : lineNet;
  }
}
