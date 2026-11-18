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

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;

/**
 * ETP-6051 — the {@code confirm} action of the invoice inbox: {@code POST
 * /sws/neo/invoice-inbox/header/{id}/action/confirm} turns ONE reviewed document into a COMPLETED
 * purchase invoice with the original file attached, creating the vendor and the products the
 * document needs, all or nothing. A batch is the SPA sending one request per document.
 *
 * <p>The request body is optional and only carries {@code {"updated": "<token>"}}, the token the
 * panel read (a stale one is refused 409 {@code stale_document}). The flow, each step a class of its
 * own: {@link InvoiceInboxConfirmClaim} (guards, row lock, idempotency),
 * {@link InvoiceInboxConfirmChecks} (validation, duplicates, address, rate, on the stored document),
 * {@link InvoiceInboxConfirmWork} (the unit of work, one commit: the completion), and on a failure
 * {@link InvoiceInboxConfirmFailure} (the row becomes {@code ERROR} / {@code CONFIRM_FAILED} in a new
 * transaction, the answer is 422 {@code confirm_failed}).</p>
 *
 * <p>The confirm NEVER writes {@code ADJUSTED}: the KPI reads the flag the document carries when it
 * first becomes {@code CONFIRMED}. After the commit, and only on that first transition, it sends
 * {@code backend_invoice_inbox_document_confirmed} ({@link InvoiceInboxTelemetry}); a document that is
 * already confirmed answers {@code alreadyConfirmed: true} and emits nothing.</p>
 *
 * <p>Answers: 200 {@code {id, status, invoice {id, documentNo, identifier}, adjusted, createdVendor,
 * createdProducts, alreadyConfirmed}}; refusals 403 {@code access_denied} / {@code org_not_allowed} /
 * {@code QUOTA_EXCEEDED}, 404 {@code not_found}, 409 {@code not_confirmable} / {@code stale_document},
 * 422 {@code not_valid} / {@code duplicate_invoice} / {@code missing_exchange_rate} /
 * {@code price_list_tax_included} / {@code vendor_address_missing} / {@code confirm_failed}.</p>
 */
final class InvoiceInboxConfirm {

  static final String ACTION = "confirm";
  static final String CODE_NOT_CONFIRMABLE = "not_confirmable";
  static final String CODE_DUPLICATE_INVOICE = "duplicate_invoice";
  static final String CODE_PRICE_LIST_TAX_INCLUDED = "price_list_tax_included";
  static final String CODE_MISSING_EXCHANGE_RATE = "missing_exchange_rate";
  static final String CODE_VENDOR_ADDRESS_MISSING = "vendor_address_missing";
  static final String CODE_CONFIRM_FAILED = "confirm_failed";
  /** The {@code ERROR_CODE} a row carries after a failed confirmation. */
  static final String CONFIRM_FAILED = "CONFIRM_FAILED";

  private static final Logger log = LogManager.getLogger(InvoiceInboxConfirm.class);
  private static final String KEY_UPDATED = "updated";

  private InvoiceInboxConfirm() {
  }

  /**
   * Confirms the document.
   *
   * @param recordId the id of the {@code ETGO_INCOMING_DOCUMENT} row
   * @param body     the request body {@code {updated?}}, may be {@code null}
   * @return the answer described on the class
   */
  static NeoResponse confirm(String recordId, JSONObject body) {
    String token;
    try {
      token = parse(body);
    } catch (IllegalArgumentException refusal) {
      return InvoiceInboxEditGate.refuse(400, InvoiceInboxEditGate.CODE_INVALID_REQUEST,
          InvoiceInboxMessages.INVALID_REQUEST, null, "max", "0");
    }
    InvoiceInboxConfirmClaim.Opened opened = InvoiceInboxConfirmClaim.open(recordId, token);
    if (opened.response() != null) {
      return opened.response();
    }
    try {
      return opened.alreadyConfirmed() ? answerAlready(opened.row()) : confirmRow(opened.row());
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the confirm answer", e);
    }
  }

  private static NeoResponse confirmRow(IncomingDocument row) throws JSONException {
    JSONObject document = document(row);
    Optional<OcrLookups> lookups = InvoiceInboxReview.lookupsFor(row.getTargetType());
    NeoResponse refusal = InvoiceInboxConfirmChecks.preflight(row, document, lookups);
    if (refusal != null) {
      return refusal;
    }
    String rowId = row.getId();
    String clientId = row.getClient().getId();
    InvoiceInboxConfirmWork.Done done;
    try {
      done = InvoiceInboxConfirmWork.run(row, document, lookups);
    } catch (NeoQuotaCreateCheck.Refused e) {
      // toResponse() rolls the transaction back itself (unless a batch owns it): once only.
      return e.toResponse();
    } catch (InvoiceInboxConfirmWork.Stop stop) {
      OBDal.getInstance().rollbackAndClose();
      return stop.refusal() != null ? stop.refusal() : failed(rowId, stop.getMessage());
    } catch (InvoiceSifPreset.Refused e) {
      OBDal.getInstance().rollbackAndClose();
      return failed(rowId, messageOf(e.toResponse()));
    } catch (Exception e) {
      // Classified and sanitized as any NEO failure: no SQL, paths or stack frames reach the row.
      return failed(rowId, messageOf(NeoErrorResponses.fromException(e, "POST",
          "Inbox confirm " + rowId, null)));
    }
    // The invoice is completed and committed: from here nothing may answer a failure.
    InvoiceInboxTelemetry.documentConfirmed(done.adjusted, clientId);
    return answerDone(rowId, done);
  }

  /** The answer of a committed confirmation; a failure building it answers the idempotent shape. */
  private static NeoResponse answerDone(String rowId, InvoiceInboxConfirmWork.Done done) {
    try {
      return success(rowId, done);
    } catch (JSONException | RuntimeException e) {
      log.warn("Inbox confirm {}: the invoice {} is completed but its answer failed: {}", rowId,
          done.invoiceId, e.getMessage());
      return NeoResponse.ok(new JSONObject(Map.of("id", rowId, "status",
          InvoiceInboxConfirmClaim.STATUS_CONFIRMED, "invoice",
          new JSONObject(Map.of("id", done.invoiceId)), "adjusted", done.adjusted,
          "createdVendor", done.createdVendor, "createdProducts", done.createdProducts,
          "alreadyConfirmed", true, "totalMismatch", done.totalMismatch)));
    }
  }

  /** The English message of an error response, empty when it has none. */
  private static String messageOf(NeoResponse response) {
    JSONObject error = response.getBody() == null ? null : response.getBody().optJSONObject("error");
    return error == null ? "" : error.optString("message", "");
  }

  /** The refusal after a failed unit: the failure is recorded in a new transaction first. */
  private static NeoResponse failed(String rowId, String reason) {
    InvoiceInboxConfirmFailure.record(rowId, reason);
    return InvoiceInboxEditGate.refuse(422, CODE_CONFIRM_FAILED,
        InvoiceInboxMessages.CONFIRM_FAILED, InvoiceInboxEditGate.details("detail", reason),
        "reason", reason);
  }

  private static NeoResponse success(String rowId, InvoiceInboxConfirmWork.Done done)
      throws JSONException {
    Invoice invoice = OBDal.getInstance().get(Invoice.class, done.invoiceId);
    return NeoResponse.ok(answer(rowId, invoice, done.adjusted, done.createdVendor,
        done.createdProducts, false, done.totalMismatch));
  }

  private static NeoResponse answerAlready(IncomingDocument row) throws JSONException {
    return NeoResponse.ok(answer(row.getId(), row.getInvoice(),
        Boolean.TRUE.equals(row.isAdjusted()), false, 0, true,
        totalMismatchOf(row)));
  }

  private static boolean totalMismatchOf(IncomingDocument row) throws JSONException {
    JSONObject document = document(row);
    return InvoiceInboxSummary.totalMismatch(document,
        InvoiceInboxLiveChecks.documentCurrency(document, row));
  }

  /** The 200 body; the id is the inbox row's, as the other actions of the inbox answer. */
  private static JSONObject answer(String rowId, Invoice invoice, boolean adjusted,
      boolean createdVendor, int createdProducts, boolean already, boolean totalMismatch)
      throws JSONException {
    return new JSONObject().put("id", rowId)
        .put("status", InvoiceInboxConfirmClaim.STATUS_CONFIRMED)
        .put("invoice", invoice == null ? JSONObject.NULL
            : new JSONObject().put("id", invoice.getId())
                .put("documentNo", StringUtils.defaultString(invoice.getDocumentNo()))
                .put("identifier", invoice.getIdentifier()))
        .put("adjusted", adjusted).put("createdVendor", createdVendor)
        .put("createdProducts", createdProducts).put("alreadyConfirmed", already)
        .put("totalMismatch", totalMismatch);
  }

  /** The token of a body that carries nothing else; empty when it has none or there is no body. */
  private static String parse(JSONObject body) {
    if (body == null) {
      return "";
    }
    Iterator<?> keys = body.keys();
    while (keys.hasNext()) {
      if (!KEY_UPDATED.equals(keys.next())) {
        throw new IllegalArgumentException("unknown key");
      }
    }
    Object updated = body.opt(KEY_UPDATED);
    if (updated != null && !JSONObject.NULL.equals(updated) && !(updated instanceof String)) {
      throw new IllegalArgumentException("updated must be a string");
    }
    return updated instanceof String ? ((String) updated).trim() : "";
  }

  /** The stored document, the empty skeleton when it cannot be read: judged as an empty one. */
  private static JSONObject document(IncomingDocument row) throws JSONException {
    if (!StringUtils.isBlank(row.getCurrentData())) {
      try {
        return new JSONObject(row.getCurrentData());
      } catch (JSONException e) {
        // an unreadable document is judged as an empty one: it is not valid
      }
    }
    return InvoiceInboxReview.skeleton();
  }
}
