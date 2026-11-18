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

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CURRENCY_ID;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.ocr.InvoiceInboxTotals;

/**
 * What the created invoice must be before it is completed (ETP-6051): in the currency of the
 * document, and with the total the inbox MODEL computes from the stored lines
 * ({@link InvoiceInboxTotals}, at the decimals of the currency): the number the review panel shows
 * for the lines. Only a divergence between that model and the Etendo engine refuses, as an internal
 * totals divergence with nothing created. The PRINTED total is never compared: a document whose
 * printed total differs from its lines is created with the lines' total, like any purchase invoice
 * (product decision 4; the mismatch is a warning, see {@code InvoiceInboxSummary#totalMismatch}).
 */
final class InvoiceInboxConfirmVerify {

  private InvoiceInboxConfirmVerify() {
  }

  /** The number of distinct tax records on the lines of the invoice. */
  private static long distinctTaxes(Invoice invoice) {
    return invoice.getInvoiceLineList().stream().map(line -> line.getTax())
        .filter(Objects::nonNull).map(tax -> tax.getId()).distinct().count();
  }

  /**
   * Verifies the invoice against the header of the document.
   *
   * @param invoice the invoice, flushed and refreshed
   * @param header  the header of the stored document
   * @throws InvoiceInboxConfirmWork.Stop when the currency or the total differ
   */
  static void verify(Invoice invoice, JSONObject header, JSONArray lines) {
    String expected = header.optString(K_CURRENCY_ID, "");
    if (!expected.isEmpty() && invoice.getCurrency() != null
        && !expected.equals(invoice.getCurrency().getId())) {
      throw new InvoiceInboxConfirmWork.Stop(
          InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_CURRENCY_MISMATCH));
    }
    if (invoice.getGrandTotalAmount() == null) {
      return;
    }
    // The scale of the currency the ENGINE gave the invoice (the one that rounds its amounts): when the
    // header stores no currency the invoice takes the one its price list implies.
    int scale = invoice.getCurrency() != null && invoice.getCurrency().getStandardPrecision() != null
        ? invoice.getCurrency().getStandardPrecision().intValue()
        : InvoiceInboxTotals.AMOUNT_SCALE;
    InvoiceInboxTotals.Totals model = InvoiceInboxTotals.totals(lines, scale);
    if (model == null) {
      return;
    }
    BigDecimal document = model.total().setScale(scale, RoundingMode.HALF_UP);
    BigDecimal created = invoice.getGrandTotalAmount().setScale(scale, RoundingMode.HALF_UP);
    // The model groups tax by rate and rounding mode; the engine by tax record (and splits summary
    // taxes into children): the two can differ by one minimal unit per distinct tax of the invoice
    // without either being wrong. A larger gap is drift between them, and refuses.
    BigDecimal tolerance = BigDecimal.ONE.movePointLeft(scale)
        .multiply(BigDecimal.valueOf(Math.max(1, distinctTaxes(invoice))));
    if (document.subtract(created).abs().compareTo(tolerance) > 0) {
      throw new InvoiceInboxConfirmWork.Stop(InvoiceInboxMessages.text(
          InvoiceInboxMessages.CONFIRM_TOTAL_MISMATCH, "invoice", created.toPlainString(),
          "document", document.toPlainString()));
    }
  }
}
