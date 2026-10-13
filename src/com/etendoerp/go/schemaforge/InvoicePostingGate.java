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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The invoice side of {@code post} / {@code unpost} (ETP-5692), shared by the sales and purchase
 * invoice header customizations: the contracts that make both actions discoverable to agents, and
 * the status gate an unpost must pass before {@link DocumentPostingService} runs it.
 *
 * <p><b>Why the unpost gate lives here and not in {@link DocumentPostingService}.</b> The service
 * is shared by every posted document, and its unpost deliberately answers a handled 200 for a
 * document that is not posted (ETP-5445, Internal Consumption: "0 entries removed"). What an
 * invoice needs is narrower and is invoice semantics: only a <em>Completed</em> invoice that is
 * <em>posted</em> may be unposted — the SPA offers the action only there. Without the gate,
 * {@code ResetAccounting} ran on drafts (a silent no-op reported as success) and on voided
 * invoices, where it flipped {@code Posted} from {@code 'D'} to {@code 'N'} and so re-enabled
 * background posting of a voided document.</p>
 *
 * <p>The {@code post} side needs no invoice gate: a post of a draft or of an already posted
 * document is now named precisely by the service itself (structural: it reads the
 * {@code Processed}/{@code Posted} columns {@code AcctServer} locks on), for every table.</p>
 */
public final class InvoicePostingGate {

  private static final Logger log = LogManager.getLogger(InvoicePostingGate.class);

  static final String ACTION_POST = "post";
  static final String ACTION_UNPOST = "unpost";

  /*
   * Stable identities sent in messageKeys — plain strings, NOT AD_MESSAGE records (ETP-5692
   * decision); the text is plain English, like DocumentPostingService's own "Unposted (N entries
   * removed)". Core's NotCompletedInvoice ("The invoice must be completed") was considered and not
   * used: it cannot carry the current status an agent needs.
   */
  static final String MSG_UNPOST_NOT_COMPLETED = "ETGO_InvoiceUnpostNotCompleted";
  static final String MSG_UNPOST_NOT_POSTED = "ETGO_InvoiceUnpostNotPosted";

  private static final String STATUS_COMPLETED = "CO";
  private static final String POSTED_YES = "Y";
  private static final String ID_DESCRIPTION = "the invoice id";

  /** The two posting actions both invoice headers serve and declare, in this order. */
  static final Map<String, NeoActionContract> CONTRACTS;

  static {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(ACTION_POST, NeoActionContract.write(ACTION_POST,
        "Books the invoice in the general ledger: creates its accounting entries and sets posted "
            + "to 'Y'. Only for a processed (completed) invoice whose posted field is not 'Y': a "
            + "draft answers 422 with messageKeys ['ETGO_PostingDocumentNotProcessed'] and an "
            + "already posted invoice 422 with ['PostedDocument']; an incomplete accounting setup "
            + "or a closed period also answers 422 with the reason. Nothing changes on a refusal. "
            + "Takes no parameters; read documentStatus and posted with etendo_get first.")
        .withIdDescription(ID_DESCRIPTION));
    contracts.put(ACTION_UNPOST, NeoActionContract.write(ACTION_UNPOST,
        "Removes the invoice's accounting entries (posted becomes 'N') and leaves it Completed, "
            + "so its accounting date, accounting dimensions (project, costcenter) and exchange "
            + "rates can be corrected with etendo_update; then post it again with the post "
            + "action. Only for documentStatus 'CO' with posted 'Y': any other status answers 422 "
            + "with messageKeys ['ETGO_InvoiceUnpostNotCompleted'], a completed invoice that is "
            + "not posted 422 with ['ETGO_InvoiceUnpostNotPosted'], and a period closed for "
            + "unposting 422 — nothing changes on a refusal. To reopen the invoice for any other "
            + "change use documentAction RE instead. Takes no parameters.")
        .withIdDescription(ID_DESCRIPTION));
    CONTRACTS = Collections.unmodifiableMap(contracts);
  }

  private InvoicePostingGate() {
  }

  /**
   * The ETP-5692 guards every invoice header request passes before anything else runs: the
   * completed-invoice write fence on a CRUD update ({@link CompletedInvoiceWriteFence}) and the
   * status gate on an {@code unpost} action ({@link #checkUnpost}). Both are the invoice
   * customization, so they hold on REST and on MCP alike.
   *
   * @param context               the current request
   * @param editableWhenCompleted the calling window's header allowlist
   *                              ({@code AbstractInvoiceHeaderHandler#completedEditableHeaderFields})
   * @return the refusal, or {@code null} to continue
   */
  public static NeoResponse checkHeaderRequest(NeoContext context,
      Set<String> editableWhenCompleted) {
    NeoResponse fence = CompletedInvoiceWriteFence.checkHeader(context, editableWhenCompleted);
    return fence != null ? fence : checkUnpost(context);
  }

  /**
   * Refuses an {@code unpost} of an invoice that is not Completed and posted.
   *
   * @param context the request
   * @return a 422 in the same flat shape {@link DocumentPostingService#handleAction} answers
   *     ({@code success:false, message, messageKeys[, messageParams]}), or {@code null} to let the
   *     service run — always {@code null} for anything but an {@code unpost} action, and when the
   *     invoice cannot be read (the service then answers as before)
   */
  public static NeoResponse checkUnpost(NeoContext context) {
    if (context == null || !NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !ACTION_UNPOST.equals(context.getFieldName())
        || StringUtils.isBlank(context.getRecordId())) {
      return null;
    }
    Invoice invoice;
    OBContext.setAdminMode(true);
    try {
      invoice = OBDal.getInstance().get(Invoice.class, context.getRecordId());
    } catch (RuntimeException e) {
      log.warn("Could not read invoice {} to gate its unpost: {}", context.getRecordId(),
          e.getMessage());
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
    if (invoice == null) {
      return null;
    }
    try {
      String docStatus = invoice.getDocumentStatus();
      if (!STATUS_COMPLETED.equals(docStatus)) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(CompletedInvoiceWriteFence.PARAM_DOC_STATUS, docStatus);
        String message = "Only a completed invoice can be unposted. This invoice's status is "
            + docStatus + ".";
        return refusal(message, MSG_UNPOST_NOT_COMPLETED, params);
      }
      if (!POSTED_YES.equals(invoice.getPosted())) {
        String message = "This invoice is not posted, so it has no accounting entries to remove.";
        return refusal(message, MSG_UNPOST_NOT_POSTED, Collections.emptyMap());
      }
      return null;
    } catch (JSONException e) {
      log.error("Could not build the unpost refusal for invoice {}", context.getRecordId(), e);
      return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, "Unpost refused");
    }
  }

  private static NeoResponse refusal(String message, String messageKey,
      Map<String, Object> params) throws JSONException {
    JSONObject body = new JSONObject();
    body.put("success", false);
    body.put("message", message);
    body.put("messageKeys", new JSONArray(List.of(messageKey)));
    if (!params.isEmpty()) {
      body.put("messageParams", new JSONObject(params));
    }
    // The JSONObject overload, never body.toString(): the String one nests the text under
    // error.message, which every flat-`message` client of post/unpost would miss (ETP-4706).
    return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, body);
  }
}
