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
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Refuses to reactivate ({@code documentAction=RE}) a Sales/Purchase Invoice that has BOTH been
 * accepted by a fiscal system (SII, TicketBAI or Verifactu) AND is already rectified by at least
 * one completed (DocStatus {@code CO}) rectificativa (ETP-5745).
 *
 * <p><b>Why.</b> Reactivating a fiscally-accepted invoice that a rectificativa already corrects
 * would let the user edit and re-complete a document whose figures the rectificativa was built
 * against — the rectificativa would then silently correct a version of the original invoice that
 * no longer exists. None of today's guards cover {@code RE}: {@link CompletedInvoiceWriteFence}
 * only fences a {@code PATCH}/{@code PUT} field-by-field, and {@link InvoicePostingGate} only
 * covers {@code unpost}.</p>
 *
 * <p><b>Fails OPEN on a read error</b> (mirrors {@link InvoicePostingGate#checkUnpost}): this
 * guard's own failure to read the invoice or its rectificativas must never block a legitimate
 * reactivation — the risk of wrongly blocking a valid user action outweighs the risk of this rare
 * edge case silently passing through once, and the normal document-action flow is unaffected
 * either way (it has no opinion about rectificativas).</p>
 *
 * <p><b>Placement.</b> Invoice-specific, not shared NEO code: wired from each subclass's
 * {@code handle()} ({@link SalesInvoiceHeaderHandler}, {@link PurchaseInvoiceHeaderHandler}),
 * immediately before {@link NeoHeaderActionRouter#dispatch}, mirroring the placement of
 * {@link AbstractInvoiceHeaderHandler#completeInvoiceIfNeeded}.</p>
 */
public final class InvoiceReactivationGuard {

  private static final Logger log = LogManager.getLogger(InvoiceReactivationGuard.class);

  private static final String FIELD_DOCUMENT_ACTION = "documentAction";
  private static final String DOC_ACTION_REACTIVATE = "RE";
  private static final String STATUS_COMPLETED = "CO";

  /** SII ({@code EM_Aeatsii_Estado}): accepted values are Right/Correcto and Accepted-with-errors. */
  private static final String SII_ACCEPTED_CORRECT = "CO";
  private static final String SII_ACCEPTED_WITH_ERRORS = "AE";

  /** Verifactu ({@code EM_Etvfac_Invoice_Status}): accepted values are Aceptada / Aceptada con Errores. */
  private static final String VERIFACTU_ACCEPTED = "AC";
  private static final String VERIFACTU_ACCEPTED_WITH_ERRORS = "AE";

  /** TicketBAI ({@code EM_ETGO_Tbai_Status}, stored computed): only "Recibido" counts as accepted. */
  private static final String TBAI_ACCEPTED_RECEIVED = "Recibido";

  /** Stable identity sent in messageKeys — a plain string, not an AD_MESSAGE (ETP-5692 precedent). */
  static final String MSG_REACTIVATE_FISCAL_RECTIFIED = "ETGO_InvoiceReactivateFiscalRectified";

  private InvoiceReactivationGuard() {
  }

  /**
   * Refuses the reactivation when the invoice was fiscally accepted AND already rectified by a
   * completed rectificativa; otherwise returns {@code null} so the normal {@code RE} document
   * action flow runs unchanged (including the core's own refusal of a non-reactivatable invoice).
   *
   * @param context the current request
   * @return a 422 naming the fiscal system and the rectificativa document number(s), or
   *     {@code null} to let the reactivation proceed
   */
  public static NeoResponse checkReactivation(NeoContext context) {
    if (!isReactivateAction(context)) {
      return null;
    }
    String invoiceId = context.getRecordId();
    if (StringUtils.isBlank(invoiceId)) {
      return null;
    }
    try {
      Block block = computeBlock(invoiceId);
      return block == null ? null : reject(block.fiscalSystems, block.rectificativaDocNos);
    } catch (JSONException e) {
      log.warn("Could not evaluate the reactivation guard for invoice {}: {}", invoiceId,
          e.getMessage());
      return null;
    }
  }

  /**
   * The same refusal condition {@link #checkReactivation} enforces, computed once so it can also
   * be surfaced read-only on GET (ETP-5745 follow-up) — the SPA's "Reactivar" button first
   * un-posts a posted invoice before attempting the document action; without this, a reactivation
   * that is doomed to fail still left the invoice un-posted as a visible side effect. Reading this
   * from the GET response lets the button skip the un-post step entirely when it already knows
   * the action would be refused (PM decision, ETP-5745: "if it can't reactivate, it must not do
   * anything").
   *
   * @return the blocking fiscal system + completed rectificativa document numbers, or
   *     {@code null} when the invoice is not blocked (including: not completed, read failure)
   */
  static Block computeBlock(String invoiceId) {
    if (StringUtils.isBlank(invoiceId)) {
      return null;
    }
    try {
      Invoice invoice = readInvoice(invoiceId);
      if (invoice == null || !Boolean.TRUE.equals(invoice.isProcessed())) {
        return null;
      }
      List<String> fiscalSystems = fiscallyAcceptedSystems(invoice);
      if (fiscalSystems.isEmpty()) {
        return null;
      }
      List<String> rectificativaDocNos = completedRectificativaDocNos(invoiceId);
      if (rectificativaDocNos.isEmpty()) {
        return null;
      }
      return new Block(fiscalSystems, rectificativaDocNos);
    } catch (RuntimeException e) {
      log.warn("Could not evaluate the reactivation guard for invoice {}: {}", invoiceId,
          e.getMessage());
      return null;
    }
  }

  /**
   * The reason a reactivation would be refused: which fiscal system(s) accepted the invoice —
   * every one of them (ETP-5745 CP-9), raw in {@link #fiscalSystems} and also comma-joined in
   * {@link #fiscalSystem} for the GET-only {@code reactivationBlockedFiscalSystem} signal
   * ({@link #enrichReactivationBlock}) — and which rectificativa(s).
   *
   * <p>{@code reject()} uses the raw list, not the joined string: {@code messageParams.fiscalSystem}
   * must stay a real array (like {@code rectificativas} already is) so the frontend can apply a
   * locale-aware conjunction ("SII y TicketBAI") instead of a flat comma join — see
   * {@code translateInvoiceReactivateFiscalRectified} in {@code backendErrors.js}.
   */
  static final class Block {
    final String fiscalSystem;
    final List<String> fiscalSystems;
    final List<String> rectificativaDocNos;

    Block(List<String> fiscalSystems, List<String> rectificativaDocNos) {
      this.fiscalSystems = fiscalSystems;
      this.fiscalSystem = String.join(", ", fiscalSystems);
      this.rectificativaDocNos = rectificativaDocNos;
    }
  }

  /**
   * Injects {@code reactivationBlocked} (boolean) and, when {@code true},
   * {@code reactivationBlockedFiscalSystem} / {@code reactivationBlockedRectificativas} into the
   * GET response — the read-only signal {@link #computeBlock} computes, so the SPA's "Reactivar"
   * button can skip its pre-unpost step entirely instead of un-posting a reactivation it already
   * knows will be refused.
   *
   * <p>{@code reactivationBlockedFiscalSystem} is a real {@code JSONArray} (ETP-5745, matching
   * {@code reject()}'s own {@code messageParams.fiscalSystem}), NOT the comma-joined
   * {@link Block#fiscalSystem} — both of this field's SPA consumers
   * ({@code DetailMoreActionsMenu.jsx}'s pre-unpost check, {@code useInvoiceWindow.js}'s bulk-bar
   * row preview) build the SAME translated sentence the live 422 shows, and need the identical
   * locale-aware conjunction join ({@code joinWithConjunction} in {@code backendErrors.js}) to stay
   * consistent with it — a backend-side comma join can't know the SPA's active locale.
   *
   * @param rec the invoice record JSON object; modified in-place
   * @param invoiceId the primary key of the invoice being enriched
   */
  static void enrichReactivationBlock(JSONObject rec, String invoiceId) throws JSONException {
    Block block = computeBlock(invoiceId);
    rec.put("reactivationBlocked", block != null);
    if (block != null) {
      rec.put("reactivationBlockedFiscalSystem", new JSONArray(block.fiscalSystems));
      rec.put("reactivationBlockedRectificativas", new JSONArray(block.rectificativaDocNos));
    }
  }

  /**
   * {@code true} for a {@code documentAction=RE} request on the ACTION endpoint — same shape as
   * {@link GlJournalHeaderHandler}'s own {@code resolveDocAction}: the body carries the action
   * either at the root ({@code docAction}/{@code documentAction}) or nested under
   * {@code fieldValues} as sent by the draft-mode confirm button.
   */
  private static boolean isReactivateAction(NeoContext context) {
    if (context == null || !NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !FIELD_DOCUMENT_ACTION.equals(context.getFieldName())) {
      return false;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return false;
    }
    JSONObject fieldValues = body.optJSONObject("fieldValues");
    String docAction = fieldValues != null
        ? fieldValues.optString(FIELD_DOCUMENT_ACTION, "")
        : body.optString("docAction", body.optString(FIELD_DOCUMENT_ACTION, ""));
    return DOC_ACTION_REACTIVATE.equals(docAction);
  }

  private static Invoice readInvoice(String invoiceId) {
    OBContext.setAdminMode(true);
    try {
      return OBDal.getInstance().get(Invoice.class, invoiceId);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * EVERY fiscal system that accepted this invoice, in a stable order (SII, Verifactu,
   * TicketBAI), or an empty list when none has an accepted status. Reading all three stored
   * columns is cheap and harmless even for a system the org never configured (the column then
   * simply stays blank).
   *
   * <p><b>ETP-5745 CP-9 fix:</b> an invoice accepted by more than one fiscal system at once (an
   * org migrating between systems, or one simply running two in parallel) used to be reported as
   * accepted by SII alone — the former if/else-if chain stopped at the first match, so a user
   * reactivating an invoice that both SII AND TicketBAI had accepted only ever saw "SII" in the
   * refusal message. Every accepted system is now collected and joined (see {@link #reject}).
   */
  private static List<String> fiscallyAcceptedSystems(Invoice invoice) {
    List<String> systems = new ArrayList<>();
    String sii = invoice.getAeatsiiEstado();
    if (SII_ACCEPTED_CORRECT.equals(sii) || SII_ACCEPTED_WITH_ERRORS.equals(sii)) {
      systems.add("SII");
    }
    String verifactu = invoice.getEtvfacInvoiceStatus();
    if (VERIFACTU_ACCEPTED.equals(verifactu) || VERIFACTU_ACCEPTED_WITH_ERRORS.equals(verifactu)) {
      systems.add("Verifactu");
    }
    String tbai = invoice.getETGOTbaiStatus();
    if (TBAI_ACCEPTED_RECEIVED.equals(tbai)) {
      systems.add("TicketBAI");
    }
    return systems;
  }

  /**
   * The document numbers of every COMPLETED rectificativa linked back to {@code invoiceId} via
   * {@code C_Invoice_Reverse} — the same table {@link InvoiceRectificationLinkSupport#enrichOriginInvoice}
   * reads, in the opposite direction: here {@code invoiceId} is the ORIGIN
   * ({@code reversed_c_invoice_id}), and the rows returned are its rectificativa(s)
   * ({@code c_invoice_id}). A rectificativa still in draft is deliberately excluded by the
   * {@code docstatus = 'CO'} filter — it must not block reactivation (explicit "no regression"
   * case in the ticket).
   */
  @SuppressWarnings("java:S2077")
  private static List<String> completedRectificativaDocNos(String invoiceId) {
    List<String> docNos = new ArrayList<>();
    String sql = "SELECT inv.documentno "
        + "FROM c_invoice_reverse r "
        + "JOIN c_invoice inv ON inv.c_invoice_id = r.c_invoice_id "
        + "WHERE r.reversed_c_invoice_id = ? AND r.isactive = 'Y' AND inv.docstatus = ? "
        + "ORDER BY inv.documentno";
    OBContext.setAdminMode(true);
    try {
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, invoiceId);
        ps.setString(2, STATUS_COMPLETED);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            docNos.add(rs.getString(1));
          }
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve completed rectificativas for invoice {}: {}", invoiceId,
          e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
    return docNos;
  }

  /**
   * The 422 refusal, in the same flat {@code success:false}/{@code message}/{@code messageKeys}/
   * {@code messageParams} shape {@link InvoicePostingGate#checkUnpost} answers with — this guard
   * also protects an ACTION-endpoint document action, not a CRUD field update (which is
   * {@link CompletedInvoiceWriteFence}'s {@code error}-wrapped shape instead).
   */
  private static NeoResponse reject(List<String> fiscalSystems, List<String> rectificativaDocNos)
      throws JSONException {
    // Plain English fallback only (never shown when the SPA's locale-aware translation runs) —
    // a straight comma join is fine here, there is no template/locale concern for this one string.
    String fiscalSystemList = String.join(", ", fiscalSystems);
    String rectificativaList = String.join(", ", rectificativaDocNos);
    String plural = rectificativaDocNos.size() > 1 ? "invoices" : "invoice";
    String message = "This invoice cannot be reactivated because it has already been accepted by "
        + fiscalSystemList + " and is rectified by " + plural + " " + rectificativaList + ".";

    JSONObject body = new JSONObject();
    body.put("success", false);
    body.put("message", message);
    body.put("messageKeys", new JSONArray(List.of(MSG_REACTIVATE_FISCAL_RECTIFIED)));
    JSONObject messageParams = new JSONObject();
    // A real JSONArray, not pre-joined — ETP-5745: lets the frontend apply a locale-aware
    // conjunction ("SII y TicketBAI") instead of the generic comma join every other array-valued
    // messageParam gets (see translateInvoiceReactivateFiscalRectified in backendErrors.js).
    messageParams.put("fiscalSystem", new JSONArray(fiscalSystems));
    messageParams.put("rectificativas", new JSONArray(rectificativaDocNos));
    body.put("messageParams", messageParams);
    return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, body);
  }
}
