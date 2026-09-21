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

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.ProcessInvoiceUtil;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Runs the real core invoice-completion process ({@link ProcessInvoiceUtil#process}) for an
 * invoice, routing through the {@code ProcessInvoiceHook} CDI chain.
 *
 * <p>Extracted from {@code AbstractInvoiceHeaderHandler} (ETP-5381) so the same completion path can
 * be reused by the handlers that auto-generate invoices from orders, shipments, receipts, returns
 * and quotations — those must now create AND confirm the invoice in a single atomic step, so a
 * draft never sits around un-reserving the source document's pending quantities.
 *
 * <p>For {@code C_Invoice.DocAction} (AD_Process 111, a raw DB procedure with no
 * {@code JavaClassName}), NEO's generic dispatch runs {@code C_Invoice_Post0} directly via
 * {@code CallProcess} and never touches {@link ProcessInvoiceUtil} or the
 * {@code ProcessInvoiceHook} extension point — so hooks such as the Verifactu (and TBAI)
 * billing-registration hooks never fire when an invoice is completed through NEO, even though they
 * fire correctly from the classic UI. This service restores that behavior for NEO.
 *
 * <p><b>Ordering contract for the header handlers.</b> {@code AbstractInvoiceHeaderHandler} must
 * call this AFTER {@code AbstractOrderHeaderHandler#applyTotalDiscountBeforeComplete}, so the
 * total-discount line already reflects the final set of product lines before it is read/posted by
 * {@link ProcessInvoiceUtil#process}. Completing BEFORE the discount recalculation would complete
 * the document with a stale or missing discount line (ETP-4388).
 *
 * <p>This contract used to name {@code validateLineQtyBeforeComplete} as a second predecessor.
 * That guard capped the invoice against the shipment's quantity and was removed in ETP-5381 — the
 * order is the commitment, not the shipment, and over-invoicing the ORDER is still rejected by the
 * core in {@code C_INVOICE_POST}. Nothing else needs to run before completion on that account.
 *
 * <p><b>Ordering contract for the creation handlers.</b> Anything that must be written on the
 * invoice itself — notably the {@code C_Invoice_Reverse} link of a rectificative invoice, whose
 * DB trigger rejects inserts once the invoice is {@code Processed='Y'} — must be persisted BEFORE
 * completing. Everything written on OTHER records (e.g. marking a quotation as invoiced) must come
 * AFTER, so it only becomes durable when a confirmed invoice actually exists.
 *
 * <p><b>Session-lifecycle note (deliberate divergence):</b> unlike every other handler in this
 * module — which only {@code .flush()} the DAL session and leave the final commit to the
 * request-scoped {@code DalThreadCleaner} — {@link ProcessInvoiceUtil#process} internally calls
 * {@code OBDal.getInstance().commitAndClose()} (success) / {@code .rollbackAndClose()} (error),
 * fully closing the current Hibernate session mid-request. This mirrors classic UI behavior (the
 * method is shared with the classic completion path) and is required for its internal
 * {@code ProcessInstance}/{@code CallProcess} bookkeeping. It is safe here because the very next
 * statement performs a DAL read ({@code OBDal.getInstance().get(Process.class, ...)}), which
 * transparently reopens the session — the same characteristic already relied upon by
 * {@code GlJournalHeaderHandler#completeJournal} via {@code FIN_AddPaymentFromJournal}. Do not
 * remove or reorder that read without re-verifying this assumption.
 *
 * <p>Two consequences callers MUST respect:
 * <ul>
 *   <li>Any {@code Invoice} reference held across the call is <b>detached</b> afterwards. Capture
 *       the id before completing and re-read the entity from the reopened session.</li>
 *   <li>The rollback on failure is what makes create-and-confirm all-or-nothing: creation handlers
 *       only {@code flush()}, never commit, so a failed completion reverts the header, its lines,
 *       the discount line, the line links and the document-number sequence advance together.</li>
 * </ul>
 */
final class InvoiceCompletionService {

  private static final Logger log = LogManager.getLogger(InvoiceCompletionService.class);

  /** AD_Process_ID for {@code C_Invoice.DocAction}, used internally by {@link ProcessInvoiceUtil}. */
  private static final String COMPLETE_PROCESS_ID_INVOICE = "111";

  private InvoiceCompletionService() {
  }

  /**
   * Completes the given invoice, returning the translated process result.
   *
   * @param invoiceId the id of the invoice to complete
   * @param obContext the context used to build the classic {@code VariablesSecureApp}
   * @return {@code 200} with the process message on success, {@code 400} with the business message
   *     when the completion is rejected, {@code 500} on infrastructure failure
   */
  static NeoResponse completeInvoice(String invoiceId, OBContext obContext) {
    if (StringUtils.isBlank(invoiceId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Missing invoice record id for completion");
    }
    try {
      VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(obContext);
      // Plain DalConnectionProvider, not wrapped in a RequestContext/pushRequestContextVars scope
      // (the pattern used elsewhere in NeoProcessService.java for classic-code invocations):
      // neither the Verifactu nor the TBAI ProcessInvoiceHook reads RequestContext today, so this
      // is a non-issue in practice. Revisit if a future hook needs request-scoped context.
      ConnectionProvider conn = new DalConnectionProvider(false);
      // ETP-4783: In Go, the Classic ETVFAC_C_INVOICE_SET_VERIFACTU callout is never triggered.
      // Copy DocType Verifactu fields to the invoice before completing so GenerateRFAfterProcessingHook
      // finds em_etvfac_inv_type / em_etvfac_verifac_desc populated (only for AR invoices; AP skipped
      // by the hook anyway). Also ensures em_etsg_date_operation is set when null.
      populateVerifactuFieldsFromDocType(invoiceId);
      // Must be obtained through Weld. ProcessInvoiceUtil is a plain class with an
      // @Inject @Any Instance<ProcessInvoiceHook> hooks field; that field is only populated when
      // the instance itself is CDI-managed. Calling new ProcessInvoiceUtil() would leave hooks
      // empty and silently skip every hook — reproducing the exact bug this class fixes, just
      // moved one layer down.
      ProcessInvoiceUtil processInvoiceUtil =
          WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class);
      // Void-date/supplier-reference params are only consulted for the void action (docAction RC).
      // ProcessInvoiceUtil calls .isEmpty() on the date strings unconditionally, so they must be
      // non-null. Empty strings are the correct null-safe default for a normal "CO" completion.
      OBError result = processInvoiceUtil.process(invoiceId, "CO", "", "", "", vars, conn);
      Process process = OBDal.getInstance().get(Process.class, COMPLETE_PROCESS_ID_INVOICE);
      if (process == null) {
        log.error("[INVOICE-COMPLETE] Process record {} not found", COMPLETE_PROCESS_ID_INVOICE);
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Completion process configuration missing");
      }
      return NeoProcessService.translateClassicResult(result, process);
    } catch (Exception e) {
      log.error("[INVOICE-COMPLETE] Completion failed for invoice {}: {}",
          invoiceId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Invoice completion failed: " + e.getMessage());
    }
  }

  /**
   * Completes the given invoice, throwing instead of returning an error response.
   *
   * <p>This is the variant used by the creation handlers (order, shipment, receipt, return,
   * quotation), which already wrap their work in {@code catch (OBException)} → 400 and
   * {@code catch (Exception)} → 500. Throwing also guarantees the caller cannot accidentally
   * continue to build a success payload for an invoice that was rolled back.
   *
   * @param invoiceId the id of the invoice to complete
   * @param obContext the context used to build the classic {@code VariablesSecureApp}
   * @throws OBException when the completion is rejected for a business reason (the message is the
   *     translated one produced by the process and its hook chain, safe to show to the user)
   * @throws IllegalStateException when the completion fails for an infrastructure reason
   */
  static void completeInvoiceOrThrow(String invoiceId, OBContext obContext) {
    NeoResponse response = completeInvoice(invoiceId, obContext);
    int status = response.getHttpStatus();
    if (status == HttpServletResponse.SC_OK) {
      return;
    }
    String message = extractMessage(response);
    if (status == HttpServletResponse.SC_BAD_REQUEST) {
      throw new OBException(message);
    }
    throw new IllegalStateException(message);
  }

  /**
   * Reads the human-readable message out of a completion response.
   *
   * <p>Has to check both shapes: {@code NeoProcessService.translateClassicResult} puts the
   * business message at the top level, while {@code NeoResponse.error(int, String)} nests it under
   * {@code error.message}. Reading only the first would silently drop the reason on exactly the
   * failures worth diagnosing — the blank-id rejection and every infrastructure error.
   */
  private static String extractMessage(NeoResponse response) {
    JSONObject body = response.getBody();
    if (body != null) {
      String message = body.optString("message", null);
      if (StringUtils.isNotBlank(message)) {
        return message;
      }
      JSONObject error = body.optJSONObject("error");
      if (error != null) {
        String nested = error.optString("message", null);
        if (StringUtils.isNotBlank(nested)) {
          return nested;
        }
      }
    }
    return "Invoice completion failed";
  }

  // ---------------------------------------------------------------------------
  // Pre-completion Verifactu field population
  // ---------------------------------------------------------------------------

  /**
   * ETP-4783: Copies Verifactu fields from the invoice's DocType to the invoice record itself
   * before the completion hook chain runs, replicating the behaviour of the Classic callout
   * {@code ETVFAC_C_INVOICE_SET_VERIFACTU} which Go never fires.
   *
   * <p>Only touches AR sales invoices where {@code em_etvfac_inv_type} is currently null.
   * Uses native SQL so the Go module compiles even when the Verifactu module is absent.
   * After the UPDATE the invoice is evicted from the Hibernate first-level cache so that
   * {@link ProcessInvoiceUtil} (called immediately after) reads the fresh DB values.
   *
   * <p>Fields populated (strictly from DocType — no fallback derivation):
   * <ul>
   *   <li>{@code em_etvfac_inv_type} — invoice type (e.g. F1, R1) — must be set on the DocType</li>
   *   <li>{@code em_etvfac_verifac_desc} — operation description — must be set on the DocType</li>
   *   <li>{@code em_etvfac_reverseinvtype} — rectification method I/S (DocType, for R-types)</li>
   *   <li>{@code em_etsg_date_operation} — defaults to {@code dateinvoiced} when null</li>
   * </ul>
   *
   * <p><b>Developer responsibility:</b> any DocType added to Go for AR invoices MUST have
   * {@code em_etvfac_inv_type} and {@code em_etvfac_verifac_desc} configured in its sampledata
   * (and {@code em_etvfac_reverseinvtype} for R-types). No automatic derivation is performed —
   * if those fields are absent the Verifactu hook will reject the invoice at completion.
   *
   * @param invoiceId the ID of the invoice being completed
   */
  @SuppressWarnings("java:S2077")
  private static void populateVerifactuFieldsFromDocType(String invoiceId) {
    String sql =
        "UPDATE c_invoice i"
        + "   SET em_etvfac_inv_type       = COALESCE(i.em_etvfac_inv_type,       dt.em_etvfac_inv_type),"
        + "       em_etvfac_verifac_desc   = COALESCE(i.em_etvfac_verifac_desc,   dt.em_etvfac_verifac_desc),"
        + "       em_etvfac_reverseinvtype = COALESCE(i.em_etvfac_reverseinvtype, dt.em_etvfac_reverseinvtype),"
        + "       em_etsg_date_operation   = COALESCE(i.em_etsg_date_operation,   i.dateinvoiced)"
        + "  FROM c_doctype dt"
        + " WHERE i.c_invoice_id   = ?"
        + "   AND dt.c_doctype_id  = i.c_doctypetarget_id"
        + "   AND i.issotrx        = 'Y'"
        + "   AND (i.em_etvfac_inv_type IS NULL OR i.em_etsg_date_operation IS NULL)";
    try {
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, invoiceId);
        int rows = ps.executeUpdate();
        if (rows > 0) {
          // Evict from Hibernate first-level cache so ProcessInvoiceUtil sees the updated values
          Invoice inv = OBDal.getInstance().getSession().get(Invoice.class, invoiceId);
          if (inv != null) {
            OBDal.getInstance().getSession().evict(inv);
          }
          log.debug("[INVOICE-COMPLETE] Populated Verifactu DocType fields for invoice {}", invoiceId);
        }
      }
    } catch (Exception e) {
      // Non-fatal: log and continue — if Verifactu is not installed the columns don't exist,
      // and the hook itself will skip processing (shouldSkipSendingToVerifactu returns true).
      log.debug("[INVOICE-COMPLETE] Could not populate Verifactu fields for invoice {}: {}",
          invoiceId, e.getMessage());
    }
  }
}
