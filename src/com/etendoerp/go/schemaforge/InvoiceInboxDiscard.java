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

import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-6049 — the {@code discard} action of the invoice inbox: moves a document that is waiting for
 * the user ({@code PENDING_REVIEW}, {@code ERROR} or {@code READY}) to {@code DISCARDED}. Nothing
 * else is touched: the error code, the review flags, the invoice link, the attachment and the
 * extraction stay as they were.
 *
 * <p>The state change is a single conditional {@code UPDATE ... WHERE status IN (...)}: whoever
 * changes the row first wins, so a concurrent confirm (ETP-6051) or a second discard is never
 * overwritten. The worker (ETP-6048) writes only {@code WHERE status = 'PROCESSING'}, so the two
 * can not race either. Discarding a row that is already {@code DISCARDED} answers 200, so a retry
 * after a lost response is not a failure.</p>
 *
 * <p>Native SQL on purpose, like {@link InvoiceInboxWorkStore}: the guard is the {@code WHERE}
 * clause, and the tenant is part of it. Stateless; called by {@link InvoiceInboxHeaderHandler}.</p>
 */
final class InvoiceInboxDiscard {

  static final String ACTION = "discard";
  static final String CODE_NOT_FOUND = "not_found";
  static final String CODE_NOT_DISCARDABLE = "not_discardable";
  static final String STATUS_DISCARDED = "DISCARDED";

  /** The states a document can be discarded from. */
  static final Set<String> DISCARDABLE = Set.of("PENDING_REVIEW", "ERROR", "READY");

  private static final String P_ID = "id";
  private static final String P_CLIENTS = "clients";
  private static final String P_ALLOWED = "allowed";
  private static final String KEY_STATUS = "status";
  private static final String TENANT = " AND ad_client_id IN (:" + P_CLIENTS + ")";

  private InvoiceInboxDiscard() {
  }

  /**
   * Discards the document.
   *
   * @param recordId the id of the {@code ETGO_INCOMING_DOCUMENT} row
   * @return 200 with {@code id}, {@code status}, {@code discardedAt} and {@code alreadyDiscarded};
   *         404 {@code not_found}; 403 {@code org_not_allowed}; 409 {@code not_discardable}
   */
  static NeoResponse discard(String recordId) {
    Object[] row = read(recordId);
    if (row == null) {
      return refuse(404, CODE_NOT_FOUND, InvoiceInboxMessages.NOT_FOUND);
    }
    if (!InvoiceInboxUploadEndpoint.isWritableOrganization(OBContext.getOBContext(),
        (String) row[1])) {
      return refuse(403, InvoiceInboxUploadEndpoint.CODE_ORG_NOT_ALLOWED,
          InvoiceInboxMessages.DISCARD_ORG_NOT_ALLOWED);
    }
    if (claim(recordId) == 1) {
      OBDal.getInstance().flush();
      return success(recordId, false);
    }
    // Lost the race, or it was never discardable: judge by the state the row has now.
    Object[] now = read(recordId);
    if (now == null) {
      return refuse(404, CODE_NOT_FOUND, InvoiceInboxMessages.NOT_FOUND);
    }
    String status = (String) now[0];
    if (STATUS_DISCARDED.equals(status)) {
      return success(recordId, true);
    }
    return NeoErrorResponses.toResponse(409, CODE_NOT_DISCARDABLE,
        InvoiceInboxMessages.text(InvoiceInboxMessages.NOT_DISCARDABLE, KEY_STATUS, status),
        List.of(InvoiceInboxMessages.NOT_DISCARDABLE));
  }

  /** {status, org} of the row when it belongs to a client the caller can read, else null. */
  private static Object[] read(String recordId) {
    return (Object[]) session().createNativeQuery("SELECT status, ad_org_id FROM"
        + " etgo_incoming_document WHERE etgo_incoming_document_id = :" + P_ID + TENANT)
        .setParameter(P_ID, recordId)
        .setParameterList(P_CLIENTS, OBContext.getOBContext().getReadableClients())
        .uniqueResult();
  }

  /** The atomic state change; the number of rows moved (1, or 0 when the state does not allow). */
  private static int claim(String recordId) {
    return session().createNativeQuery("UPDATE etgo_incoming_document SET status = '"
        + STATUS_DISCARDED + "', discarded_at = now(), updated = now(), updatedby = :user"
        + " WHERE etgo_incoming_document_id = :" + P_ID + " AND status IN (:" + P_ALLOWED + ")"
        + TENANT)
        .setParameter("user", OBContext.getOBContext().getUser().getId())
        .setParameter(P_ID, recordId)
        .setParameterList(P_ALLOWED, DISCARDABLE)
        .setParameterList(P_CLIENTS, OBContext.getOBContext().getReadableClients())
        .executeUpdate();
  }

  private static NeoResponse success(String recordId, boolean already) {
    Object discardedAt = session().createNativeQuery("SELECT CAST(discarded_at AS text) FROM"
        + " etgo_incoming_document WHERE etgo_incoming_document_id = :" + P_ID)
        .setParameter(P_ID, recordId).uniqueResult();
    try {
      return NeoResponse.ok(new JSONObject().put(P_ID, recordId).put(KEY_STATUS, STATUS_DISCARDED)
          .put("discardedAt", discardedAt).put("alreadyDiscarded", already));
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the discard answer", e);
    }
  }

  private static NeoResponse refuse(int status, String code, String messageKey) {
    return NeoErrorResponses.toResponse(status, code, InvoiceInboxMessages.text(messageKey),
        List.of(messageKey));
  }

  private static Session session() {
    return OBDal.getInstance().getSession();
  }
}
