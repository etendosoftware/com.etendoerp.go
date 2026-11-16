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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;

/**
 * ETP-6050 — the {@code mark-reviewed} action of the invoice inbox: {@code POST
 * /sws/neo/invoice-inbox/header/{id}/action/mark-reviewed} with {@code {updated}} says "I reviewed
 * this document": {@code PENDING_REVIEW} becomes {@code READY} and {@code REVIEWED} becomes
 * {@code Y}. Nothing else moves: not {@code ADJUSTED}, not the summary columns, not the document.
 *
 * <p>The verdict is recomputed here (the stored one is not trusted) and the document must be
 * valid, else 422 {@code not_valid} with the {@code missing} codes. The cases that can actually
 * arrive: a valid {@code PENDING_REVIEW} row is marked; an invalid {@code PENDING_REVIEW} row (a
 * row read before a rule changed, or whose stored status lags its document: the normal invalid
 * row is already {@code ERROR}) is 422 and is moved to {@code ERROR}; an {@code ERROR} row is 409
 * {@code not_reviewable} (it is invalid by definition: edit it first); {@code READY} is 200;
 * {@code CONFIRMED}, {@code DISCARDED} and a foreign receiver are 409 {@code not_editable} from the
 * guards. A document already
 * {@code READY} answers 200 unchanged and writes nothing, but only for a caller that holds the
 * current token: a retry that still carries the OLD token (the first call did save) is refused
 * first, 409 {@code stale_document}, because the token check runs before the status is looked at,
 * and the caller re-reads the document and finds it {@code READY}. One that is not
 * {@code PENDING_REVIEW} (an {@code ERROR}) is 409 {@code not_reviewable}. Guards (access, tenant,
 * organization, processing, editable, lock, token): {@link InvoiceInboxEditGate}. Stateless; called
 * by {@link InvoiceInboxHeaderHandler}.</p>
 */
final class InvoiceInboxMarkReviewed {

  static final String ACTION = "mark-reviewed";
  static final String CODE_NOT_REVIEWABLE = "not_reviewable";
  static final String CODE_NOT_VALID = "not_valid";

  private static final String KEY_UPDATED = "updated";
  private static final String KEY_MISSING = "missing";
  private static final String PENDING_REVIEW = "PENDING_REVIEW";
  private static final String READY = "READY";

  private InvoiceInboxMarkReviewed() {
  }

  /**
   * Marks the document as reviewed.
   *
   * @param recordId the id of the {@code ETGO_INCOMING_DOCUMENT} row
   * @param body     the request body {@code {updated}}, may be {@code null}
   * @return 200 with the review body of the row; 409 {@code not_reviewable}; 422 {@code not_valid}
   *         and the guards' refusals
   */
  static NeoResponse markReviewed(String recordId, JSONObject body) {
    try {
      String token = parse(body);
      if (token.isEmpty()) {
        return InvoiceInboxEditGate.refuse(400, InvoiceInboxApply.CODE_MISSING_UPDATED,
            InvoiceInboxMessages.MISSING_UPDATED, null);
      }
      InvoiceInboxEditGate.Opened opened = InvoiceInboxEditGate.open(recordId, token);
      if (opened.refusal() != null) {
        return opened.refusal();
      }
      return mark(opened.row());
    } catch (IllegalArgumentException refusal) {
      return InvoiceInboxEditGate.refuse(400, InvoiceInboxEditGate.CODE_INVALID_REQUEST,
          InvoiceInboxMessages.INVALID_REQUEST, null, "max", "0");
    } catch (JSONException e) {
      throw new IllegalStateException("Could not mark the document as reviewed", e);
    }
  }

  /** The token of a body that carries nothing else; empty when it has none. */
  private static String parse(JSONObject body) {
    if (body == null) {
      throw new IllegalArgumentException("no body");
    }
    Iterator<?> keys = body.keys();
    while (keys.hasNext()) {
      if (!KEY_UPDATED.equals(keys.next())) {
        throw new IllegalArgumentException("unknown key");
      }
    }
    Object updated = body.opt(KEY_UPDATED);
    return updated instanceof String ? ((String) updated).trim() : "";
  }

  private static NeoResponse mark(IncomingDocument row) throws JSONException {
    String status = row.getStatus();
    if (READY.equals(status)) {
      return InvoiceInboxReview.respond(row);
    }
    if (!PENDING_REVIEW.equals(status)) {
      return InvoiceInboxEditGate.refuse(409, CODE_NOT_REVIEWABLE,
          InvoiceInboxMessages.NOT_REVIEWABLE,
          InvoiceInboxEditGate.details(InvoiceInboxEditGate.KEY_DOCUMENT_STATUS, status),
          "status", status);
    }
    InvoiceInboxValidator.Result verdict = InvoiceInboxValidator.validate(document(row));
    if (!verdict.isValid()) {
      return notValid(row, verdict);
    }
    row.setStatus(READY);
    row.setReviewed(true);
    OBDal.getInstance().save(row);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(row);
    return InvoiceInboxReview.respond(row);
  }

  /**
   * A {@code PENDING_REVIEW} row that does not validate when judged again (a row read before a rule
   * changed, or one whose stored status lags its document): 422 {@code not_valid} with the
   * recomputed {@code missing}. The stored status is brought in line with the verdict
   * ({@code ERROR} with its code, as an edit would) so the list says what the panel says. That
   * write is committed with the request (an error {@code NeoResponse} is a returned value, not an
   * exception), so the answer carries the NEW {@code updated} token and the new status for the
   * caller to adopt without a reload.
   */
  private static NeoResponse notValid(IncomingDocument row, InvoiceInboxValidator.Result verdict)
      throws JSONException {
    InvoiceInboxStatusPolicy.applyAfterEdit(row, verdict);
    OBDal.getInstance().save(row);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(row);
    JSONObject details = new JSONObject()
        .put(KEY_MISSING, new JSONArray(verdict.getMissing()))
        .put(KEY_UPDATED, NeoDateFormat.toAuditToken(row.getUpdated()))
        .put(InvoiceInboxEditGate.KEY_DOCUMENT_STATUS, row.getStatus());
    return InvoiceInboxEditGate.refuse(422, CODE_NOT_VALID, InvoiceInboxMessages.NOT_VALID,
        details, KEY_MISSING, StringUtils.join(verdict.getMissing(), ", "));
  }

  /** The stored document, the empty skeleton when it cannot be read. */
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
