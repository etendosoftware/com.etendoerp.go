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
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.text;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.truthy;
import static com.etendoerp.go.schemaforge.NeoPrintableCommons.value;

import java.util.List;

import javax.enterprise.context.Dependent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * The sales invoice's printable data — the Java port of {@code buildInvoiceData}
 * ({@code tools/app-shell/src/windows/custom/shared/useInvoicePdf.js}) (ETP-6013).
 *
 * <p>Reads exactly what the browser reads — {@code sales-invoice/header/<id>},
 * {@code sales-invoice/lines?parentId=<id>} and the session — through
 * {@link NeoPrintableReads}, so every property name below is the browser's, and builds the same keys
 * with the same JavaScript semantics ({@code ||}, {@code ??}, {@code Number()}).</p>
 *
 * <p><b>Dual-implementation drift risk — read before changing either side.</b> Same shape as
 * {@code BalanceSheetReportHandler}: the template is shared (published by the frontend build), the
 * data is built twice. A key added to, renamed in or recomputed by {@code buildInvoiceData} MUST be
 * mirrored here by hand, and vice versa; otherwise the server-rendered PDF (what the MCP returns and
 * emails) silently differs from the one the user previews. {@code PrintableDataTest} pins the
 * key set the browser builds.</p>
 *
 * <p>The Verifactu QR (ETP-4912) is encoded from {@code etvfacQRURL} verbatim, as the browser does
 * (D1/D2). The browser drops a QR that fails to generate and still prints; a legally required QR
 * missing from the customer's copy is worse than no stored PDF, so here a QR failure fails the
 * render instead — the MCP then answers {@code no_pdf} as before.</p>
 */
@Dependent
public class SalesInvoicePrintableData implements NeoPrintableDataBuilder {

  private static final Logger log = LogManager.getLogger(SalesInvoicePrintableData.class);

  static final String DOCUMENT = "sales-invoice";

  @Override
  public String document() {
    return DOCUMENT;
  }

  @Override
  public JSONObject build(String recordId) throws Exception {
    JSONObject header = NeoPrintableReads.record(DOCUMENT, "header", recordId);
    if (header == null) {
      throw new IllegalStateException("The invoice could not be read");
    }
    JSONArray linesRaw = NeoPrintableReads.children(DOCUMENT, "lines", recordId);
    NeoResponse sessionResponse = NeoSessionService.resolveSession();
    JSONObject session = sessionResponse.getHttpStatus() < 400 ? sessionResponse.getBody() : null;
    return build(header, linesRaw, session);
  }

  /**
   * The pure half of the port: the data from the three reads.
   *
   * @param header   {@code sales-invoice/header/<id>}
   * @param linesRaw {@code sales-invoice/lines?parentId=<id>}
   * @param session  {@code /session}, or {@code null} when it could not be read
   * @return {@code buildInvoiceData}'s result
   */
  static JSONObject build(JSONObject header, JSONArray linesRaw, JSONObject session)
      throws Exception {
    JSONObject data = NeoPrintableCommons.companyFields(session, header, value(header, "bpAddress"));

    List<JSONObject> sorted = NeoPrintableCommons.sortDocumentLines(linesRaw);
    JSONArray lines = new JSONArray();
    for (int idx = 0; idx < sorted.size(); idx++) {
      JSONObject l = sorted.get(idx);
      JSONObject line = new JSONObject();
      line.put("lineNo", truthy(value(l, "lineNo"), idx + 1));
      line.put("productCode", NeoPrintableCommons.resolveProductCode(l));
      line.put("productName", truthy(value(l, "product$_identifier"), value(l, "description"), "—"));
      line.put("quantity", coalesce(value(l, "invoicedQuantity"), value(l, "qtyInvoiced"), 0));
      line.put("unitPrice", coalesce(value(l, "unitPrice"), value(l, "priceActual"), 0));
      Object discount = value(l, "etgoDiscount");
      line.put("discount", isTruthy(discount) ? json(number(discount)) : JSONObject.NULL);
      line.put("taxName", truthy(value(l, "tax$_identifier"), value(l, "taxRate"), ""));
      line.put("lineTotal", coalesce(value(l, "lineNetAmount"), value(l, "lineAmount"), 0));
      lines.put(line);
    }

    double grandTotal = number(coalesce(value(header, "grandTotalAmount"), 0));
    double netAmount = number(coalesce(value(header, "summedLineAmount"),
        value(header, "totalLines"), 0));
    double taxAmount = grandTotal - netAmount;

    double etgoTotalDiscount = number(coalesce(value(header, "etgoTotalDiscount"), 0));
    double[] breakdown = NeoPrintableCommons.computeDiscountBreakdown(sorted, etgoTotalDiscount,
        SalesInvoicePrintableData::grossLine);
    double grossAmount = breakdown[0];
    double discountPerProduct = breakdown[2];
    double totalDiscountAmt = breakdown[3];

    boolean hasLineDiscount = false;
    for (JSONObject l : sorted) {
      hasLineDiscount |= number(coalesce(value(l, "etgoDiscount"), 0)) > 0;
    }

    data.put("documentNo", truthy(value(header, "documentNo"), ""));
    data.put("currencyCode", NeoPrintableCommons.resolveDocumentCurrencyCode(header));
    data.put("invoiceDate", truthy(value(header, "invoiceDate"), value(header, "dateInvoiced"), ""));
    data.put("customerName", truthy(value(header, "businessPartner$_identifier"),
        value(header, "businessPartner"), "—"));
    data.put("paymentMethod", orNull(value(header, "paymentMethod$_identifier")));
    data.put("paymentTerms", orNull(value(header, "paymentTerms$_identifier")));
    data.put("notes", orNull(value(header, "description")));
    data.put("lines", lines);
    data.put("netAmount", json(netAmount));
    data.put("taxAmount", json(taxAmount));
    data.put("grandTotal", json(grandTotal));
    // ETP-5132 — signed breakdown, gated on "!== 0", printed sign-flipped: see buildInvoiceData.
    boolean anyProductDiscount = discountPerProduct != 0;
    data.put("grossAmount", anyProductDiscount ? json(grossAmount) : JSONObject.NULL);
    data.put("grossSubtotal", anyProductDiscount ? json(grossAmount) : JSONObject.NULL);
    data.put("discountPerProduct", anyProductDiscount ? json(-discountPerProduct)
        : JSONObject.NULL);
    data.put("etgoTotalDiscount", etgoTotalDiscount > 0 ? json(etgoTotalDiscount)
        : JSONObject.NULL);
    data.put("totalDiscountPct", etgoTotalDiscount > 0 ? json(etgoTotalDiscount)
        : JSONObject.NULL);
    data.put("totalDiscountAmt", totalDiscountAmt != 0 ? json(-totalDiscountAmt)
        : JSONObject.NULL);
    data.put("hasAnyDiscount", hasLineDiscount || etgoTotalDiscount > 0);
    data.put("hasTotalDiscount", etgoTotalDiscount > 0);
    data.put("verifactuQrDataUrl", verifactuQr(value(header, "etvfacQRURL")));
    return data;
  }

  /** {@code getGrossLine} of {@code buildInvoiceData}. */
  static double grossLine(JSONObject l) {
    double qty = number(coalesce(value(l, "invoicedQuantity"), value(l, "qtyInvoiced"), 0));
    if (qty == 0) {
      return 0;
    }
    Object listPrice = value(l, "listPrice");
    if (listPrice != null) {
      return qty * number(listPrice);
    }
    double lineNet = number(coalesce(value(l, "lineNetAmount"), value(l, "lineAmount"), 0));
    double disc = number(coalesce(value(l, "etgoDiscount"), 0));
    return disc > 0 ? lineNet / (1 - disc / 100) : lineNet;
  }

  /** The Verifactu QR, or JSON null when the invoice carries no AEAT URL yet (D2). */
  private static Object verifactuQr(Object url) throws Exception {
    if (!isTruthy(url)) {
      return JSONObject.NULL;
    }
    String dataUrl = NeoPrintableCommons.verifactuQrDataUrl(text(url));
    if (dataUrl.isEmpty()) {
      return JSONObject.NULL;
    }
    log.debug("Verifactu QR encoded for the server-side invoice printable");
    return dataUrl;
  }
}
