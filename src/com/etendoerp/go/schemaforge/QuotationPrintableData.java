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
      line.put("discount", isTruthy(discount) ? json(number(discount)) : JSONObject.NULL);
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
    data.put("notes", orNull(value(header, "description")));
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
