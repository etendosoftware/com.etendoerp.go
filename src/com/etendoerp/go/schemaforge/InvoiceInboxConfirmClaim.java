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

import java.util.Set;

import org.hibernate.Session;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * The guards and the claim of {@code confirm} (ETP-6051), in the order the other actions of the
 * inbox run them: the window access and the organization of the user, judged BEFORE anything runs in
 * admin mode, then the row, locked {@code FOR UPDATE} and judged again (a confirm or a discard may
 * have won while this request waited for the lock), then the optional {@code updated} token.
 *
 * <p>The states a document is confirmed from: {@code PENDING_REVIEW} and {@code READY}, and the
 * {@code ERROR} ones that can be retried, {@code CONFIRM_FAILED} (the retry the user asked for) and
 * {@code MISSING_FIELDS} (a verdict the live data may have healed, such as a rate added since; the
 * verdict is recomputed right after the claim, so a document that is still incomplete is refused
 * with its missing codes and never confirmed). A document already {@code CONFIRMED} is not an
 * error: it answers with its invoice, once more, and writes nothing.</p>
 */
final class InvoiceInboxConfirmClaim {

  static final String STATUS_CONFIRMED = "CONFIRMED";

  private static final Set<String> CONFIRMABLE_STATUS = Set.of("PENDING_REVIEW", "READY");
  private static final Set<String> RETRYABLE_ERRORS = Set.of(InvoiceInboxConfirm.CONFIRM_FAILED,
      InvoiceInboxValidator.MISSING_FIELDS);
  private static final String STATUS_ERROR = "ERROR";
  private static final String P_ID = "id";
  private static final String P_CLIENTS = "clients";
  private static final String SELECT = "SELECT status, ad_org_id, error_code FROM"
      + " etgo_incoming_document WHERE etgo_incoming_document_id = :" + P_ID
      + " AND ad_client_id IN (:" + P_CLIENTS + ")";

  private InvoiceInboxConfirmClaim() {
  }

  /** The outcome: an answer to return, or the locked row (already confirmed or to confirm). */
  static final class Opened {
    private final NeoResponse response;
    private final IncomingDocument row;
    private final boolean alreadyConfirmed;

    private Opened(NeoResponse response, IncomingDocument row, boolean alreadyConfirmed) {
      this.response = response;
      this.row = row;
      this.alreadyConfirmed = alreadyConfirmed;
    }

    /** Returns the refusal to answer, {@code null} when the row was opened. */
    NeoResponse response() {
      return response;
    }

    /** Returns the locked row, {@code null} when refused. */
    IncomingDocument row() {
      return row;
    }

    /** Returns whether the document was already confirmed (nothing to do but answer). */
    boolean alreadyConfirmed() {
      return alreadyConfirmed;
    }
  }

  /**
   * Runs the guards and locks the row.
   *
   * @param recordId the id of the {@code ETGO_INCOMING_DOCUMENT} row
   * @param token    the {@code updated} token the caller read, empty when it sent none
   * @return the answer to return, or the locked row
   */
  static Opened open(String recordId, String token) {
    if (!NeoAccessHelper.hasWindowAccess(InvoiceInboxUploadEndpoint.WINDOW_ID, "POST")) {
      return refused(InvoiceInboxEditGate.refuse(403, InvoiceInboxEditGate.CODE_ACCESS_DENIED,
          InvoiceInboxMessages.ACCESS_DENIED, null));
    }
    Object[] row = read(recordId, false);
    if (row == null) {
      return refused(notFound());
    }
    if (!InvoiceInboxUploadEndpoint.isWritableOrganization(OBContext.getOBContext(),
        (String) row[1])) {
      return refused(InvoiceInboxEditGate.refuse(403, InvoiceInboxUploadEndpoint.CODE_ORG_NOT_ALLOWED,
          InvoiceInboxMessages.DISCARD_ORG_NOT_ALLOWED, null));
    }
    // Lock first, then judge: a confirm, a discard or an edit may have won in between.
    Object[] locked = read(recordId, true);
    if (locked == null) {
      return refused(notFound());
    }
    String status = (String) locked[0];
    if (STATUS_CONFIRMED.equals(status)) {
      return new Opened(null, load(recordId), true);
    }
    if (!isConfirmable(status, (String) locked[2])) {
      return refused(InvoiceInboxEditGate.refuse(409, InvoiceInboxConfirm.CODE_NOT_CONFIRMABLE,
          InvoiceInboxMessages.NOT_CONFIRMABLE,
          InvoiceInboxEditGate.details(InvoiceInboxEditGate.KEY_DOCUMENT_STATUS, status),
          "status", status));
    }
    IncomingDocument loaded = load(recordId);
    if (!token.isEmpty() && InvoiceInboxEditGate.isStale(loaded, token)) {
      return refused(InvoiceInboxEditGate.refuse(409, InvoiceInboxEditGate.CODE_STALE,
          InvoiceInboxMessages.STALE_DOCUMENT, null));
    }
    return new Opened(null, loaded, false);
  }

  /** Whether a row in this state and with this error code can be confirmed. */
  static boolean isConfirmable(String status, String errorCode) {
    // Set.of(...).contains(null) throws: a null status or code (nullable columns) is not confirmable.
    if (status == null) {
      return false;
    }
    return CONFIRMABLE_STATUS.contains(status)
        || (STATUS_ERROR.equals(status) && errorCode != null && RETRYABLE_ERRORS.contains(errorCode));
  }

  private static IncomingDocument load(String recordId) {
    IncomingDocument loaded = OBDal.getInstance().get(IncomingDocument.class, recordId);
    // A guard of the request may have loaded the row before the lock: read what the lock protects.
    OBDal.getInstance().refresh(loaded);
    return loaded;
  }

  private static NeoResponse notFound() {
    return InvoiceInboxEditGate.refuse(404, InvoiceInboxEditGate.CODE_NOT_FOUND,
        InvoiceInboxMessages.NOT_FOUND, null);
  }

  private static Opened refused(NeoResponse response) {
    return new Opened(response, null, false);
  }

  private static Object[] read(String recordId, boolean lock) {
    Session session = OBDal.getInstance().getSession();
    return (Object[]) session.createNativeQuery(SELECT + (lock ? " FOR UPDATE" : ""))
        .setParameter(P_ID, recordId)
        .setParameterList(P_CLIENTS, OBContext.getOBContext().getReadableClients())
        .uniqueResult();
  }
}
