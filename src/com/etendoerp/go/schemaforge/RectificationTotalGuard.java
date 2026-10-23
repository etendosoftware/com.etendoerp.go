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
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Refuses to COMPLETE (documentAction=CO) a rectificativa invoice whose completion would push
 * the total of ALL completed rectificativas against the same origin invoice past that origin's
 * own total (ETP-5745 CP-9).
 *
 * <p><b>Why.</b> Nothing enforced this before: FV1000167 (363 €) could be — and was, in the CP-9
 * repro — rectified by two separately completed rectificativas totaling -726 €, double the
 * original. A rectificativa corrects a SUBSET (up to the whole) of its origin's total; nothing
 * about the document type stops the user from creating and completing as many as they like.
 *
 * <p><b>Comparison is on absolute value</b>: a rectificativa total is conventionally negative
 * (a credit against the original), but {@code ETGO_SF_ENTITY}/{@code ad_callouts} do not enforce
 * the sign, and the ticket's own repro invoice is itself a positive "under-invoiced correction"
 * case (see {@code AbstractInvoiceHeaderHandler#enrichInvoiceSubtype} javadoc) — so the guard
 * must not assume a sign and instead compares magnitudes.
 *
 * <p><b>Fails OPEN on a read error</b>, mirroring {@link InvoiceReactivationGuard}: this guard's
 * own failure to read the invoice totals must never block a legitimate completion.
 *
 * <p><b>Placement.</b> Invoice-specific, not shared NEO code: wired from
 * {@link AbstractInvoiceHeaderHandler#completeInvoiceIfNeeded}, immediately before
 * {@link InvoiceCompletionService#completeInvoice} — the same spot
 * {@code validateLineQtyBeforeComplete} used to occupy. Applies identically to sales and
 * purchase invoices: {@code C_Invoice}/{@code C_Invoice_Reverse} are shared tables and nothing
 * here branches on a spec or table name.
 */
final class RectificationTotalGuard {

  private static final Logger log = LogManager.getLogger(RectificationTotalGuard.class);

  private static final String STATUS_COMPLETED = "CO";

  /** Stable identity sent in messageKeys — a plain string, not an AD_MESSAGE (ETP-5692 precedent). */
  static final String MSG_RECTIFICATION_EXCEEDS_ORIGINAL = "ETGO_InvoiceRectificationExceedsOriginal";

  private RectificationTotalGuard() {
  }

  /**
   * Refuses the completion when it would push the rectified total past the origin's total for
   * ANY origin this invoice rectifies; otherwise returns {@code null} so the normal completion
   * flow runs unchanged.
   *
   * @param context the current request
   * @return a 422 naming the origin invoice and the two totals, or {@code null} to let the
   *     completion proceed
   */
  static NeoResponse checkCompletion(NeoContext context) {
    if (!InvoiceCalloutHelper.isInvoiceCompleteAction(context)) {
      return null;
    }
    String invoiceId = InvoiceCalloutHelper.resolveInvoiceIdFromContext(context);
    if (StringUtils.isBlank(invoiceId)) {
      return null;
    }
    try {
      for (String originId : originInvoiceIds(invoiceId)) {
        BigDecimal originTotal = absGrandTotal(originId);
        if (originTotal == null) {
          continue;
        }
        BigDecimal rectifiedTotal = sumCompletedRectifications(originId, invoiceId);
        if (rectifiedTotal.compareTo(originTotal) > 0) {
          return reject(originId, originTotal, rectifiedTotal);
        }
      }
      return null;
    } catch (RuntimeException | JSONException e) {
      log.warn("Could not evaluate the rectification total guard for invoice {}: {}", invoiceId,
          e.getMessage());
      return null;
    }
  }

  /** Every ORIGIN invoice this invoice rectifies (active {@code C_Invoice_Reverse} links). */
  @SuppressWarnings("java:S2077")
  private static List<String> originInvoiceIds(String invoiceId) {
    List<String> origins = new ArrayList<>();
    String sql = "SELECT DISTINCT reversed_c_invoice_id FROM c_invoice_reverse "
        + "WHERE c_invoice_id = ? AND isactive = 'Y'";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          origins.add(rs.getString(1));
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve origin invoices for {}: {}", invoiceId, e.getMessage());
    }
    return origins;
  }

  /** The absolute grand total of {@code invoiceId}, or {@code null} when it cannot be read. */
  @SuppressWarnings("java:S2077")
  private static BigDecimal absGrandTotal(String invoiceId) {
    String sql = "SELECT grandtotal FROM c_invoice WHERE c_invoice_id = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          BigDecimal total = rs.getBigDecimal(1);
          return total != null ? total.abs() : null;
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve grand total for invoice {}: {}", invoiceId, e.getMessage());
    }
    return null;
  }

  /**
   * The sum of {@code ABS(GrandTotal)} of every rectificativa linked to {@code originId} that IS
   * ALREADY completed, OR is {@code excludeOrIncludeInvoiceId} itself (the one about to become
   * completed by this very request, still in whatever status it currently holds).
   */
  @SuppressWarnings("java:S2077")
  private static BigDecimal sumCompletedRectifications(String originId, String excludeOrIncludeInvoiceId) {
    String sql = "SELECT COALESCE(SUM(ABS(inv.grandtotal)), 0) "
        + "FROM c_invoice_reverse r "
        + "JOIN c_invoice inv ON inv.c_invoice_id = r.c_invoice_id "
        + "WHERE r.reversed_c_invoice_id = ? AND r.isactive = 'Y' "
        + "AND (inv.docstatus = ? OR inv.c_invoice_id = ?)";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, originId);
      ps.setString(2, STATUS_COMPLETED);
      ps.setString(3, excludeOrIncludeInvoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          BigDecimal sum = rs.getBigDecimal(1);
          return sum != null ? sum : BigDecimal.ZERO;
        }
      }
    } catch (Exception e) {
      log.warn("Could not sum completed rectifications for origin {}: {}", originId, e.getMessage());
    }
    return BigDecimal.ZERO;
  }

  /**
   * The 422 refusal, in the same flat {@code success:false}/{@code message}/{@code messageKeys}/
   * {@code messageParams} shape {@link InvoiceReactivationGuard} answers with.
   */
  private static NeoResponse reject(String originId, BigDecimal originTotal, BigDecimal rectifiedTotal)
      throws JSONException {
    String message = "This rectification cannot be completed: the total already rectified against "
        + "the original invoice (" + rectifiedTotal + ") would exceed its own total (" + originTotal
        + ").";

    JSONObject body = new JSONObject();
    body.put("success", false);
    body.put("message", message);
    body.put("messageKeys", new JSONArray(List.of(MSG_RECTIFICATION_EXCEEDS_ORIGINAL)));
    JSONObject messageParams = new JSONObject();
    messageParams.put("originInvoiceId", originId);
    messageParams.put("originTotal", originTotal);
    messageParams.put("rectifiedTotal", rectifiedTotal);
    body.put("messageParams", messageParams);
    return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, body);
  }
}
