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
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-4888 — Resolves the most recent fiscal sub-record id for an invoice, so the frontend
 * SIF tab's "Adjuntos" sections can point the generic attachments endpoint
 * ({@code GET /sws/neo/attachments/{tableName}/{recordId}}) at the record that actually
 * carries the fiscal XML.
 *
 * <p>Confirmed via live DB inspection on a real Verifactu send: SII and TBAI attach their
 * outbound/response XML to a fiscal sub-record — {@code aeatsii_facturas} and
 * {@code tbai_syncinvoice} respectively — not to {@code C_Invoice} itself. Verifactu's AEAT
 * response leg does the same, via {@code etvfac_c_invoice_verifactu}; Verifactu's outbound-send
 * leg already attaches directly to {@code C_Invoice} (via {@code GenerateRF#saveInvoiceXML}) and
 * needs no support here.
 *
 * <p>{@code aeatsii_payment} (keyed on {@code fin_payment_id}) and
 * {@code aeatsii_cash_receipt_data} (keyed on {@code aeatsii_cash_receipt_id}) were investigated
 * and confirmed NOT to carry a {@code c_invoice_id} column — they back different windows
 * (Payment / Cash Receipt SII sends) and are out of scope for the sales-invoice window.
 *
 * <p>Extracted as a standalone helper (mirroring {@link InvoiceExemptTaxes}) so the shared
 * invoice header handlers do not grow past their method budget. Each of the 3 lookups is
 * independent and fails safely: if a fiscal module is not installed the underlying table does
 * not exist, the resulting exception is caught and logged, and the corresponding key is simply
 * omitted from the record.
 */
final class SifSubRecordAttachments {

  private static final Logger log = LogManager.getLogger(SifSubRecordAttachments.class);

  private static final String FIELD_AEATSII_FACTURA_ID = "aeatsiiFacturaId";
  private static final String FIELD_TBAI_SYNC_INVOICE_ID = "tbaiSyncInvoiceId";
  private static final String FIELD_INVOICE_VERIFACTU_ID = "invoiceVerifactuId";

  private SifSubRecordAttachments() {
  }

  /**
   * Injects {@code aeatsiiFacturaId}, {@code tbaiSyncInvoiceId}, and {@code invoiceVerifactuId}
   * into the record — the id of the most recent ({@code created DESC}) active row in
   * {@code aeatsii_facturas}, {@code tbai_syncinvoice}, and {@code etvfac_c_invoice_verifactu}
   * respectively, for the given invoice. Only meaningful in detail view (single record); do not
   * call for list responses.
   *
   * @param rec       the invoice record JSON object; modified in-place
   * @param invoiceId the C_Invoice id
   */
  static void enrich(JSONObject rec, String invoiceId) {
    if (StringUtils.isBlank(invoiceId)) {
      return;
    }
    injectLatestId(rec, FIELD_AEATSII_FACTURA_ID, "aeatsii_facturas", "aeatsii_facturas_id", invoiceId);
    injectLatestId(rec, FIELD_TBAI_SYNC_INVOICE_ID, "tbai_syncinvoice", "tbai_syncinvoice_id", invoiceId);
    injectLatestId(rec, FIELD_INVOICE_VERIFACTU_ID, "etvfac_c_invoice_verifactu",
        "etvfac_c_invoice_verifactu_id", invoiceId);
  }

  /**
   * Looks up the most recent active row of {@code tableName} for {@code invoiceId} and, if
   * found, writes its primary key under {@code jsonKey} in {@code rec}. Table/column names are
   * fixed internal constants (never user input), so plain string concatenation into the SQL is
   * safe here.
   */
  @SuppressWarnings("java:S2077")
  private static void injectLatestId(JSONObject rec, String jsonKey, String tableName,
      String pkColumn, String invoiceId) {
    String sql = "SELECT " + pkColumn + " FROM " + tableName
        + " WHERE c_invoice_id = ? AND isactive = 'Y' ORDER BY created DESC LIMIT 1";
    try {
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, invoiceId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            rec.put(jsonKey, rs.getString(1));
          }
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve {} for invoice {} (table may not be installed): {}",
          jsonKey, invoiceId, e.getMessage());
    }
  }
}
