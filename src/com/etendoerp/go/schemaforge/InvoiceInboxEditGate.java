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
import java.util.regex.Pattern;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;
import com.etendoerp.go.schemaforge.util.NeoRecordVersion;

/**
 * The guards both writing actions of the review panel share (ETP-6050): {@code apply} and
 * {@code mark-reviewed}. Same order for both, so a refusal means the same thing in either: write
 * access to the window, the row exists for the tenant, its organization is writable, it is not
 * being read ({@code processing}), it is editable ({@code not_editable}); then the row is locked
 * ({@code SELECT ... FOR UPDATE}, the pattern of {@link InvoiceInboxWorkStore#lockIfProcessing}),
 * the state is judged again under the lock, the row is loaded fresh and the optimistic token is
 * compared.
 *
 * <p>The token is the {@code updated} the panel read, as {@link NeoDateFormat#toAuditToken} emits
 * it (it carries milliseconds). It is accepted when it is the very string of the row's current
 * token; otherwise {@link NeoRecordVersion#isStale} decides, which compares to the millisecond when
 * the token has a fraction of a second and only to the second when it has none (core's tolerance).
 * A token that is not even shaped like an audit token is refused as stale (fail closed), where
 * {@link NeoRecordVersion} alone would let it pass.</p>
 */
final class InvoiceInboxEditGate {

  static final String CODE_NOT_FOUND = "not_found";
  static final String CODE_PROCESSING = "processing";
  static final String CODE_NOT_EDITABLE = "not_editable";
  static final String CODE_STALE = "stale_document";
  static final String CODE_ACCESS_DENIED = "access_denied";
  static final String CODE_INVALID_REQUEST = "invalid_request";

  private static final String STATUS_PROCESSING = "PROCESSING";
  private static final String P_ID = "id";
  private static final String P_CLIENTS = "clients";
  private static final String KEY_STATUS = "status";
  /** Detail key carrying the row status: {@code error.status} already holds the HTTP code. */
  static final String KEY_DOCUMENT_STATUS = "documentStatus";
  private static final String KEY_ERROR = "error";
  private static final String SELECT = "SELECT status, ad_org_id, error_code FROM"
      + " etgo_incoming_document WHERE etgo_incoming_document_id = :" + P_ID
      + " AND ad_client_id IN (:" + P_CLIENTS + ")";
  private static final Pattern TOKEN_SHAPE = Pattern.compile(
      "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})");

  private InvoiceInboxEditGate() {
  }

  /** The outcome of the guards: a refusal to answer, or the locked, fresh row. */
  static final class Opened {
    private final NeoResponse refusal;
    private final IncomingDocument row;

    private Opened(NeoResponse refusal, IncomingDocument row) {
      this.refusal = refusal;
      this.row = row;
    }

    /** Returns the refusal to answer, {@code null} when the row was opened. */
    NeoResponse refusal() {
      return refusal;
    }

    /** Returns the locked row, {@code null} when refused. */
    IncomingDocument row() {
      return row;
    }
  }

  /**
   * Runs the guards.
   *
   * @param recordId the id of the {@code ETGO_INCOMING_DOCUMENT} row
   * @param token    the {@code updated} token the caller read
   * @return the locked row, or the refusal
   */
  static Opened open(String recordId, String token) {
    if (!NeoAccessHelper.hasWindowAccess(InvoiceInboxUploadEndpoint.WINDOW_ID, "POST")) {
      return refused(refuse(403, CODE_ACCESS_DENIED, InvoiceInboxMessages.ACCESS_DENIED, null));
    }
    Object[] row = read(recordId, false);
    if (row == null) {
      return refused(refuse(404, CODE_NOT_FOUND, InvoiceInboxMessages.NOT_FOUND, null));
    }
    if (!InvoiceInboxUploadEndpoint.isWritableOrganization(OBContext.getOBContext(),
        (String) row[1])) {
      return refused(refuse(403, InvoiceInboxUploadEndpoint.CODE_ORG_NOT_ALLOWED,
          InvoiceInboxMessages.DISCARD_ORG_NOT_ALLOWED, null));
    }
    NeoResponse state = judge(row);
    if (state != null) {
      return refused(state);
    }
    // Lock first, then judge the state again: a discard or a confirm may have won in between.
    Object[] locked = read(recordId, true);
    state = locked == null ? refuse(404, CODE_NOT_FOUND, InvoiceInboxMessages.NOT_FOUND, null)
        : judge(locked);
    if (state != null) {
      return refused(state);
    }
    IncomingDocument loaded = OBDal.getInstance().get(IncomingDocument.class, recordId);
    // A guard of the request may have loaded the row before the lock: read what the lock protects.
    OBDal.getInstance().refresh(loaded);
    if (isStale(loaded, token)) {
      return refused(refuse(409, CODE_STALE, InvoiceInboxMessages.STALE_DOCUMENT, null));
    }
    return new Opened(null, loaded);
  }

  private static NeoResponse judge(Object[] row) {
    String status = (String) row[0];
    if (STATUS_PROCESSING.equals(status)) {
      return refuse(409, CODE_PROCESSING, InvoiceInboxMessages.PROCESSING, null);
    }
    if (!InvoiceInboxReview.isEditable(status, (String) row[2])) {
      return refuse(409, CODE_NOT_EDITABLE, InvoiceInboxMessages.NOT_EDITABLE,
          details(KEY_DOCUMENT_STATUS, status), KEY_STATUS, status);
    }
    return null;
  }

  private static boolean isStale(IncomingDocument row, String token) {
    String current = NeoDateFormat.toAuditToken(row.getUpdated());
    if (token.equals(current)) {
      return false;
    }
    return !TOKEN_SHAPE.matcher(token).matches()
        || NeoRecordVersion.isStale(IncomingDocument.ENTITY_NAME, row.getId(), token);
  }

  private static Object[] read(String recordId, boolean lock) {
    return (Object[]) session().createNativeQuery(SELECT + (lock ? " FOR UPDATE" : ""))
        .setParameter(P_ID, recordId)
        .setParameterList(P_CLIENTS, OBContext.getOBContext().getReadableClients())
        .uniqueResult();
  }

  private static Opened refused(NeoResponse response) {
    return new Opened(response, null);
  }

  /** One extra key of the {@code error} object. */
  static JSONObject details(String key, Object value) {
    try {
      return new JSONObject().put(key, value);
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the error details", e);
    }
  }

  /**
   * A refusal in the NEO envelope: {@code error.code}, the English text of the message, its key,
   * and the given details merged into the {@code error} object (the SPA reads them by name).
   *
   * @param status  the HTTP status
   * @param code    the {@code error.code}
   * @param key     the {@link InvoiceInboxMessages} key
   * @param details extra keys of the {@code error} object, may be {@code null}
   * @param params  placeholder name and value pairs of the message
   * @return the response
   */
  static NeoResponse refuse(int status, String code, String key, JSONObject details,
      String... params) {
    NeoResponse response = NeoErrorResponses.toResponse(status, code,
        InvoiceInboxMessages.text(key, params), List.of(key));
    JSONObject error = response.getBody() == null ? null : response.getBody().optJSONObject(KEY_ERROR);
    if (error != null && details != null) {
      java.util.Iterator<?> names = details.keys();
      while (names.hasNext()) {
        String name = (String) names.next();
        try {
          error.put(name, details.get(name));
        } catch (JSONException e) {
          throw new IllegalStateException("Could not add the error details", e);
        }
      }
    }
    return response;
  }

  private static Session session() {
    return OBDal.getInstance().getSession();
  }
}
