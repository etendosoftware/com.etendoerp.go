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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * Refuses to complete an invoice that still has a line whose amount was never calculated
 * (ETP-5710), for the invoice header customizations that call it explicitly
 * ({@link PurchaseInvoiceHeaderHandler}, {@link SalesInvoiceHeaderHandler}).
 *
 * <p><b>Why.</b> A line an agent wrote used to keep {@code LineNetAmt 0} next to a quantity and a
 * price. The header totals, the tax rows and the payment plan are sums of the lines, so the invoice
 * completed with a total of 0, no payment plan, and {@code paymentComplete = Y} — shown as paid —
 * with no warning anywhere. Refusing before the document action runs turns a silent corruption into
 * a message that names the lines by product — the line numbers are internal and the UI never
 * shows them.
 *
 * <p><b>The rule is structural, not business.</b> A product line is inconsistent when
 * {@code quantity × unit price} is at least half a cent and yet {@code LineNetAmt} is 0: the net is
 * defined by the other two, so it cannot legitimately be 0. A line with a price of 0 (a free sample)
 * or a quantity of 0 is consistent and passes, and so does a negative (return) line, unless its
 * net is 0 with a non-zero {@code quantity × unit price}: the check uses the ABS of that product,
 * so such a line is refused. The check names no entity and no business property beyond those three columns of {@code C_InvoiceLine}.
 *
 * <p><b>Fails open.</b> If the query itself cannot run the completion proceeds: a guard must not
 * become a new reason a document cannot be completed.
 */
final class InvoiceCompletionLineGuard {

  private static final Logger log = LogManager.getLogger(InvoiceCompletionLineGuard.class);

  static final String ERROR_CODE = "invoiceLinesWithoutAmount";
  private static final int SC_UNPROCESSABLE = 422;

  // A product line whose net is 0 while quantity x price rounds to a non-zero amount.
  private static final String OFFENDING_LINES_SQL =
      "SELECT il.line, p.name, p.value, il.qtyinvoiced FROM c_invoiceline il"
          + " JOIN m_product p ON p.m_product_id = il.m_product_id"
          + " WHERE il.c_invoice_id = ? AND il.isactive = 'Y'"
          + "   AND il.linenetamt = 0"
          + "   AND ABS(COALESCE(il.qtyinvoiced, 0) * COALESCE(il.priceactual, 0)) >= 0.005"
          + " ORDER BY il.line";

  /** An offending line, identified the way the user knows it: by its product. */
  static final class Offender {
    final long line;
    final String product;
    final String quantity;

    Offender(long line, String product, String quantity) {
      this.line = line;
      this.product = product;
      this.quantity = quantity;
    }
  }

  private InvoiceCompletionLineGuard() {
  }

  /**
   * @param context the header request, as {@code handle} received it
   * @return a 422 naming the offending products when {@code context} is a completion
   *     ({@code documentAction = CO}, on the CRUD or the ACTION endpoint) of an invoice with such
   *     lines; {@code null} for anything else, which proceeds
   */
  static NeoResponse refuseIfLinesHaveNoAmount(NeoContext context) {
    if (context == null || !InvoiceCalloutHelper.isInvoiceCompleteAction(context)
        || StringUtils.isBlank(context.getRecordId())) {
      return null;
    }
    List<Offender> lines;
    try {
      lines = offendingLines(context.getRecordId());
    } catch (Exception e) {
      log.warn("[ETP-5710] Could not check the line amounts of invoice {} before completing: {}",
          context.getRecordId(), e.getMessage());
      return null;
    }
    return lines.isEmpty() ? null : refusal(lines);
  }

  @SuppressWarnings("java:S2077")
  private static List<Offender> offendingLines(String invoiceId) throws Exception {
    List<Offender> lines = new ArrayList<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(OFFENDING_LINES_SQL)) {
      ps.setString(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString(2);
          String label = StringUtils.isNotBlank(name) ? name : rs.getString(3);
          BigDecimal qty = rs.getBigDecimal(4);
          lines.add(new Offender(rs.getLong(1), StringUtils.defaultString(label),
              qty == null ? "" : qty.stripTrailingZeros().toPlainString()));
        }
      }
    }
    return lines;
  }

  /**
   * Product labels for the message. When two offending lines share a product the quantity tells
   * them apart: {@code "Agua (×3)"} and {@code "Agua (×5)"} (a locale-neutral
   * marker: the SPA renders this sentence in the user's language).
   */
  static List<String> labels(List<Offender> offenders) {
    Map<String, Integer> uses = new HashMap<>();
    for (Offender o : offenders) {
      uses.merge(o.product, 1, Integer::sum);
    }
    List<String> labels = new ArrayList<>();
    for (Offender o : offenders) {
      boolean shared = uses.get(o.product) > 1 && !o.quantity.isEmpty();
      labels.add(shared ? o.product + " (\u00d7" + o.quantity + ")" : o.product);
    }
    return labels;
  }

  static String message(List<Offender> offenders) {
    return "The invoice cannot be completed: the lines of " + String.join(", ", labels(offenders))
        + " have a quantity and a price but a net amount of 0, so the totals would be wrong."
        + " Save those lines again so the amount is recalculated, then complete.";
  }

  private static NeoResponse refusal(List<Offender> offenders) {
    try {
      JSONArray lines = new JSONArray();
      for (Offender o : offenders) {
        lines.put(new JSONObject().put("line", o.line).put("product", o.product)
            .put("quantity", o.quantity));
      }
      JSONObject error = new JSONObject();
      error.put("status", SC_UNPROCESSABLE);
      error.put("code", ERROR_CODE);
      error.put("message", message(offenders));
      error.put("lines", lines);
      return NeoResponse.error(SC_UNPROCESSABLE, new JSONObject().put("error", error));
    } catch (Exception e) {
      return NeoResponse.error(SC_UNPROCESSABLE,
          "The invoice has lines with a quantity and a price but a net amount of 0");
    }
  }
}
