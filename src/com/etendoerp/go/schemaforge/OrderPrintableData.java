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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * An order's printable data — the Java port of {@code buildOrderData}
 * ({@code tools/app-shell/src/windows/custom/shared/documentPdf.js}) (ETP-6013), shared by the sales
 * and the purchase order exactly as the browser shares it: the builder takes the spec, nothing else
 * differs. The two concrete builders ({@link SalesOrderPrintableData},
 * {@link PurchaseOrderPrintableData}) only name their document.
 *
 * <p><b>The print path, not the preview's.</b> It ports {@code buildOrderData(spec, id, base, token,
 * null)} — the call {@code documentPdfRegistry.js} makes for Print and for the send modal's fallback:
 * no {@code currencyData}, so no exchange-rate row. The preview's own render of a foreign-currency
 * order adds that row; the server PDF follows the printed one. (That preview/print difference is the
 * browser's, documented by D13 in {@code docs/document-printables.md}.)</p>
 *
 * <p><b>Dual-implementation drift risk — read before changing either side.</b> A key added to,
 * renamed in or recomputed by {@code buildOrderData} — or by {@code computeDocumentTotals}, whose
 * discount arithmetic is inlined in {@link #discountTotals} — MUST be mirrored here by hand. The
 * parity fixture shared with {@code printableSources.vitest.js} pins it
 * ({@code PrintableDataTest}).</p>
 */
public abstract class OrderPrintableData implements NeoPrintableDataBuilder {

  /** {@code ORDER_LINE_CONFIG} of {@code useLineGrossAmount.js}. */
  static final String QTY_FIELD = "orderedQuantity";
  static final String PRICE_FIELD = "listPrice";
  static final String DISCOUNT_FIELD = "discount";

  @Override
  public JSONObject build(String recordId) throws PrintableDataException {
    String spec = document();
    JSONObject header = NeoPrintableReads.readRecord(spec, "header", recordId);
    if (header == null) {
      throw new PrintableDataException("The order could not be read");
    }
    JSONArray linesRaw = NeoPrintableReads.children(spec, "lines", recordId);
    NeoResponse sessionResponse = NeoSessionService.resolveSession();
    JSONObject session = sessionResponse.getHttpStatus() < 400 ? sessionResponse.getBody() : null;
    try {
      return build(header, linesRaw, session);
    } catch (Exception e) {
      throw new PrintableDataException("The order data could not be built", e);
    }
  }

  /**
   * The pure half: {@code buildOrderData}'s result from its three reads.
   *
   * @param header   {@code <spec>/header/<id>}
   * @param linesRaw {@code <spec>/lines?parentId=<id>}
   * @param session  {@code /session}, or {@code null}
   * @return the data
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
      line.put("quantity", coalesce(value(l, QTY_FIELD), value(l, "qtyOrdered"), 0));
      line.put("unitPrice", coalesce(value(l, PRICE_FIELD), value(l, "unitPrice"),
          value(l, "priceActual"), 0));
      Object discount = value(l, DISCOUNT_FIELD);
      // The data shape names the output after the line field it reads.
      line.put(DISCOUNT_FIELD, isTruthy(discount) ? json(number(discount)) : JSONObject.NULL);
      line.put("taxName", truthy(value(l, "tax$_identifier"), value(l, "taxRate"), ""));
      line.put("lineTotal", coalesce(value(l, "lineGrossAmount"), value(l, "grossAmount"),
          value(l, "lineNetAmount"), value(l, "lineAmount"), 0));
      lines.put(line);
    }

    double etgoTotalDiscount = number(coalesce(value(header, "etgoTotalDiscount"), 0));
    double[] totals = discountTotals(sorted, etgoTotalDiscount);
    double grossSubtotal = totals[0];
    double discountAmt = totals[1];
    double totalDiscountAmt = totals[2];

    double grandTotal = number(coalesce(value(header, "grandTotalAmount"), 0));
    double netAmount = number(coalesce(value(header, "summedLineAmount"),
        value(header, "totalLines"), 0));

    data.put("documentNo", truthy(value(header, "documentNo"), ""));
    data.put("currencyCode", NeoPrintableCommons.resolveDocumentCurrencyCode(header));
    data.put("invoiceDate", truthy(value(header, "orderDate"), ""));
    data.put("customerName", truthy(value(header, "businessPartner$_identifier"),
        value(header, "businessPartner"), "—"));
    data.put("paymentMethod", orNull(value(header, "paymentMethod$_identifier")));
    data.put("paymentTerms", orNull(value(header, "paymentTerms$_identifier")));
    data.put("notes", orNull(value(header, "description")));
    data.put("lines", lines);
    data.put("netAmount", json(netAmount));
    data.put("taxAmount", json(grandTotal - netAmount));
    data.put("grandTotal", json(grandTotal));
    // ETP-5132 — signed, gated on "!== 0", printed sign-flipped: see buildOrderData.
    data.put("grossAmount", discountAmt != 0 ? json(grossSubtotal) : JSONObject.NULL);
    data.put("discountPerProduct", discountAmt != 0 ? json(-discountAmt) : JSONObject.NULL);
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
   * The part of {@code computeDocumentTotals(linesRaw, null, null, ORDER_LINE_CONFIG, pct)}
   * {@code buildOrderData} reads: the gross subtotal ({@code Σ qty·listPrice}), the per-product
   * discount (gross − {@code Σ qty·listPrice·(1−discount/100)}) and the total-discount amount (net ×
   * pct/100, pct clamped to 0–100). Each field reads as {@code parseFloat(x ?? 0) || 0}.
   *
   * @return {grossSubtotal, discountAmt, totalDiscountAmt}
   */
  static double[] discountTotals(List<JSONObject> lines, double totalDiscountPct) {
    double gross = 0;
    double net = 0;
    for (JSONObject line : lines) {
      double qty = floatOrZero(value(line, QTY_FIELD));
      double price = floatOrZero(value(line, PRICE_FIELD));
      double disc = floatOrZero(value(line, DISCOUNT_FIELD));
      gross += qty * price;
      net += qty * price * (1 - disc / 100);
    }
    double pct = Math.max(0, Math.min(100, Double.isNaN(totalDiscountPct) ? 0 : totalDiscountPct));
    return new double[] { gross, gross - net, net * pct / 100 };
  }

  /** {@code parseFloat(v ?? 0) || 0}. */
  private static double floatOrZero(Object v) {
    double n = number(v);
    return Double.isNaN(n) ? 0 : n;
  }
}
