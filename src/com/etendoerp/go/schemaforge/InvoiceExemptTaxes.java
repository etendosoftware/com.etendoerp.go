/*
 *************************************************************************
 * ETP-4751 — SIF exemption-cause support.
 *************************************************************************
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
 * Detects whether an invoice carries an exempt tax, for the SIF exemption-cause gating
 * introduced in ETP-4751. Extracted out of {@code AbstractInvoiceHeaderHandler} so that
 * shared handler does not grow past its method budget.
 */
final class InvoiceExemptTaxes {

  private static final Logger log = LogManager.getLogger(InvoiceExemptTaxes.class);

  private InvoiceExemptTaxes() {
  }

  /**
   * Injects {@code hasExemptTaxes} (boolean) into the record, replicating the SII module's
   * {@code ExemptTaxes#invoiceHasExemptTaxes}.
   *
   * <p>Detection is done ONLY via active invoice LINES (c_invoiceline -> c_tax). Do NOT add a
   * c_invoicetax UNION branch back in: in Etendo GO draft invoices NEO CRUD does not recompute
   * or remove c_invoicetax rows when lines change or are deleted, so those rows LINGER. A
   * c_invoicetax-based check therefore returns exempt=true even after the exempt line has been
   * removed, leaving hasExemptTaxes stuck at true and the SIF exemption field editable forever.
   * The active lines are the reliable current source of truth. Fail-safe: any DB error leaves
   * {@code hasExemptTaxes = false}.
   *
   * @param rec       the record JSON to enrich
   * @param invoiceId the C_Invoice id
   */
  @SuppressWarnings("java:S2077")
  static void enrich(JSONObject rec, String invoiceId) throws Exception {
    if (StringUtils.isBlank(invoiceId)) {
      rec.put("hasExemptTaxes", false);
      return;
    }
    boolean result = false;
    try {
      String sql =
          "SELECT 1 FROM c_invoiceline il JOIN c_tax t ON t.c_tax_id = il.c_tax_id"
        + " WHERE il.c_invoice_id = ? AND il.isactive = 'Y' AND t.istaxexempt = 'Y'"
        + " LIMIT 1";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, invoiceId);
        try (ResultSet rs = ps.executeQuery()) {
          result = rs.next();
        }
      }
    } catch (Exception e) {
      log.warn("Could not check hasExemptTaxes for invoice {}: {}", invoiceId, e.getMessage());
    }
    rec.put("hasExemptTaxes", result);
  }
}
