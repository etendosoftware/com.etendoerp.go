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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-6284 — tells the purchase-invoice SPA whether the invoice carries manual tax lines
 * ({@code C_InvoiceTax} with {@code Recalculate = 'N'}): the way Classic declares an import with
 * DUA. A customs / forwarder invoice (F5) usually has no invoice lines at all, only one or more of
 * these rows, and the core {@code C_Invoice_Post} completes it: its "needs lines" check
 * ({@code @InvoicesNeedLines@}) passes when there is EITHER an invoice line OR a tax line.
 *
 * <p>The SPA's Confirm button is disabled on an invoice without lines; with this signal (and the
 * {@code sii-import-dua} flag) it stays enabled when the invoice has at least one manual tax
 * line. Read-only, per request, single-record GET only — not a list column. Called explicitly by
 * {@link PurchaseInvoiceHeaderHandler} (the purchase-invoice customization), never from shared
 * code.
 *
 * <p>Fail-safe: any DB error leaves {@code hasManualTaxLines = false}, i.e. the SPA keeps the
 * previous behaviour (Confirm disabled without lines).
 */
final class PurchaseInvoiceManualTaxLines {

  static final String FIELD = "hasManualTaxLines";

  private static final Logger log = LogManager.getLogger(PurchaseInvoiceManualTaxLines.class);

  private static final String SQL = "SELECT 1 FROM c_invoicetax it"
      + " WHERE it.c_invoice_id = ? AND it.recalculate = 'N' AND it.isactive = 'Y' LIMIT 1";

  private PurchaseInvoiceManualTaxLines() {
  }

  /**
   * Puts {@code hasManualTaxLines} (boolean) on the record.
   *
   * @param rec       the header record to enrich
   * @param invoiceId the C_Invoice id
   * @throws JSONException if the record cannot be written
   */
  static void enrich(JSONObject rec, String invoiceId) throws JSONException {
    rec.put(FIELD, !StringUtils.isBlank(invoiceId) && hasManualTaxLines(invoiceId));
  }

  @SuppressWarnings("java:S2077")
  static boolean hasManualTaxLines(String invoiceId) {
    try {
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(SQL)) {
        ps.setString(1, invoiceId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next();
        }
      }
    } catch (Exception e) {
      log.warn("Could not check manual tax lines for invoice {}: {}", invoiceId, e.getMessage());
      return false;
    }
  }
}
