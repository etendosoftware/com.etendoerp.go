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

package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.Traceable;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Re-stamps the {@code updated} concurrency token of a write response after the entity's
 * {@code afterHandle} post-hook has run (ETP-5262).
 *
 * <h2>The defect this closes</h2>
 *
 * The CRUD write serialises its response body <b>before</b> {@code afterHandle} is called
 * ({@code NeoServletSupport.handleWithHooks} sets it as {@code previousResult}, then invokes the
 * hook). A post-hook that writes to the record it just saved therefore bumps
 * {@code updated} on the row while the body already in flight still carries the value from
 * <i>before</i> that write — and the contract of {@code afterHandle} returning {@code null} is
 * "keep the default result", so that pre-write body is exactly what the client receives.
 *
 * <p>Harmless until ETP-5122, which made the React client harvest {@code updated} from a create
 * response and cache it as the record's version. From then on the cached token is stale <b>on
 * arrival</b>, and the user's very first edit of the record they just created is refused by
 * {@link NeoRecordVersion#isStale} with a {@code stale_record} 409 — a conflict with nobody.
 *
 * <p>Reported on the Users window, where {@code UserRoleAssignmentHandler}'s POST branch creates a
 * personal role and writes it back to {@code AD_User.defaultRole} (with a {@code flush}) after the
 * create response was built. It is not confined to that handler:
 * {@code AbstractInvoiceHeaderHandler.persistSiiAuthorizationno} does the same thing to
 * {@code C_Invoice} on every sales/purchase invoice write, and any future post-hook that saves its
 * own record joins the list. Hence the fix lives here, at the single dispatch point, rather than in
 * each handler.
 *
 * <p>The bug is <b>intermittent</b>, which is how it was reported and why it survived: the
 * comparison zeroes milliseconds ({@code NeoRecordVersion.equalToTheSecond}), so a stale token only
 * differs from the stored one when the post-hook's flush happens to cross a wall-clock second.
 *
 * <h2>Why the dispatcher and not the handler</h2>
 *
 * A per-handler fix — each post-hook patching the body it just invalidated — is narrower, but it is
 * a rule that has to be remembered by every handler ever written, on a failure mode that shows up
 * as an unreproducible 409 in production rather than as a broken test. The same forgetting already
 * happened twice for the sibling problem of a post-hook's own field values (ETP-4783's
 * {@code injectAuthorizationnoIntoSaveResponse} exists solely because a post-hook write was
 * invisible in the response), and once for the token's format (ETP-5073, then ETP-5255 in two more
 * handlers). Doing it centrally is the only version nobody can forget.
 *
 * <p>The cost is one {@code OBDal.get} per write response, which is a Hibernate session-cache hit
 * for the record the request just wrote — the same read {@link NeoRecordVersion} already performs
 * on every update — and a no-op {@code put} when nothing changed. It runs only for CRUD writes
 * (POST/PUT/PATCH), never for a GET or a list.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <ul>
 *   <li><b>It does not flush.</b> It reads the token the record holds now; a post-hook that saves
 *       without flushing still ships the pre-write value. Flushing here would give the shared
 *       dispatcher the power to fail a whole transaction on behalf of an entity it knows nothing
 *       about, and a flush failure cannot be swallowed — the session is unusable afterwards.
 *       Flushing its own writes is the post-hook's own responsibility, and is already the
 *       convention in this module (both known offenders do it).</li>
 *   <li><b>It never adds an {@code updated} key that was not already there.</b> Its job is to
 *       correct a value the response already publishes, not to expose a field
 *       {@code NeoFieldFilter} chose to withhold.</li>
 *   <li><b>It does not touch {@link NeoRecordVersion}'s comparison.</b> The token is made correct;
 *       the check stays exactly as strict as core's.</li>
 *   <li><b>It skips non-CRUD endpoint types.</b> An ACTION response is a process result, not a
 *       record, and has no {@code updated} of its own to correct.</li>
 * </ul>
 *
 * <p>Nothing here logs a business field value: only the entity name, the record id and
 * {@code updated} timestamps — the same boundary {@code NeoWriteRefusalLog.staleRecord} draws for
 * the same value.
 */
public final class NeoAuditTokenRefresh {

  private static final Logger log = LogManager.getLogger(NeoAuditTokenRefresh.class);

  /** The audit/concurrency token's key, in both the request and the response body. */
  private static final String FIELD_UPDATED = "updated";

  /** Core's {@code JsonDataService} envelope: {@code {"response": {"data": [ ... ]}}}. */
  private static final String KEY_RESPONSE = "response";
  private static final String KEY_DATA = "data";
  private static final String KEY_ID = "id";

  private NeoAuditTokenRefresh() {
    // utility class — no instances
  }

  /**
   * Re-stamps the token in {@code result}'s body, when {@code result} is a successful CRUD write.
   *
   * <p>An error response is left alone: a refused write did not change the row, and its body is an
   * IMP-5 error envelope with no record in it.
   *
   * @param context the request context the post-hook just ran against
   * @param result  the response about to be written to the client; may be {@code null}
   */
  public static void refreshInResponse(NeoContext context, NeoResponse result) {
    if (result == null || result.getHttpStatus() >= 400) {
      return;
    }
    refreshInBody(context, result.getBody());
  }

  /**
   * Re-stamps the token in a raw response body — the MCP write paths' entry point, which hold their
   * response as a bare {@link JSONObject} and mutate it in place rather than wrapping it in a
   * {@link NeoResponse}.
   *
   * <p>Mutates {@code body} in place: it is the object about to be serialised, and every caller
   * either returns it or flattens it afterwards, so an in-place patch reaches the client without
   * the callers having to thread a replacement response back out. It is also what lets one
   * implementation serve the REST and MCP paths, whose response plumbing differs.
   *
   * <p>Best-effort by construction. Every failure — an unreadable row, an unrecognised body shape,
   * a token this module cannot render — leaves the body byte-identical and logs. Getting the token
   * wrong costs the user one spurious 409 they can retry past; throwing here would lose a write
   * that already succeeded and is about to be committed.
   *
   * @param context the request context the post-hook just ran against
   * @param body    the response body to patch; may be {@code null}
   */
  public static void refreshInBody(NeoContext context, JSONObject body) {
    if (context == null || body == null
        || !NeoEndpointType.CRUD.equals(context.getEndpointType())
        || !isWriteMethod(context.getHttpMethod())) {
      return;
    }
    String dalEntityName = dalEntityName(context);
    if (dalEntityName == null) {
      return;
    }
    try {
      for (JSONObject record : records(body)) {
        refreshRecord(dalEntityName, record, context.getRecordId());
      }
    } catch (Exception e) {
      log.warn("Could not refresh the `updated` token of a {} write response: {}. The response is"
          + " returned unchanged, so a post-hook write may leave the client holding a stale token"
          + " (ETP-5262)", dalEntityName, e.getMessage());
    }
  }

  /**
   * POST/PUT/PATCH — the methods whose response carries a record the client will cache a token
   * from. Duplicated from {@code NeoHandlerUtils.isWriteMethod} rather than shared because that one
   * is package-private in {@code com.etendoerp.go.schemaforge} and this class is deliberately in
   * {@code util}, callable from the MCP package too.
   *
   * <p>DELETE is absent on purpose: there is no row left to read a token from.
   */
  private static boolean isWriteMethod(String method) {
    return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
  }

  /**
   * The DAL entity name for the request's tab — the same derivation
   * {@code NeoCrudHandler} uses ({@code adTab.getTable().getName()}), so this class and the
   * concurrency check it is fixing agree on which entity they are talking about.
   *
   * @return the entity name, or {@code null} when the context carries no resolved tab (a sub-hook
   *         context, which cannot be a CRUD write anyway)
   */
  private static String dalEntityName(NeoContext context) {
    if (context.getAdTab() == null || context.getAdTab().getTable() == null) {
      return null;
    }
    return context.getAdTab().getTable().getName();
  }

  /**
   * The record objects inside a write response, in either shape this module emits.
   *
   * <p>Core's {@code JsonDataService} wraps a write result as {@code {"response": {"data": [ … ]}}}
   * — a one-element array even for a single record (confirmed in ETP-4830). A handler that fully
   * overrode the write in its pre-hook may instead have returned the record flat, which
   * {@code AbstractInvoiceHeaderHandler.injectAuthorizationnoIntoSaveResponse} already handles the
   * same way; the flat body is only treated as a record when it actually carries a token, so an
   * envelope with an empty {@code data} array is not mistaken for one.
   *
   * @return the records to consider, possibly empty — never {@code null}
   */
  private static List<JSONObject> records(JSONObject body) {
    List<JSONObject> found = new ArrayList<>();
    JSONObject envelope = body.optJSONObject(KEY_RESPONSE);
    JSONArray data = envelope == null ? null : envelope.optJSONArray(KEY_DATA);
    if (data != null) {
      for (int i = 0; i < data.length(); i++) {
        JSONObject record = data.optJSONObject(i);
        if (record != null) {
          found.add(record);
        }
      }
      return found;
    }
    if (body.has(FIELD_UPDATED)) {
      found.add(body);
    }
    return found;
  }

  /**
   * Overwrites one record's token with the value its row holds now.
   *
   * <p>The id is read from the record rather than from the context because a POST has no id in the
   * URL — the created record's id only exists in the response, which is precisely the case this
   * whole class is about. The context's id is the fallback for a PUT/PATCH whose response shape
   * omits it.
   *
   * <p>{@link NeoDateFormat#toAuditToken} is the only accepted renderer here: the token has to be
   * accepted back by {@code JsonUtils.createDateTimeFormat()}, whose offset is mandatory, and every
   * hand-rolled or offsetless alternative has already shipped this same bug three times (see that
   * method's javadoc). A value it cannot render leaves the original in place — a record returned
   * with no {@code updated} at all would 400 the next write with {@code missing_updated}, which is
   * worse than the stale token this is trying to fix.
   */
  private static void refreshRecord(String dalEntityName, JSONObject record, String fallbackId)
      throws JSONException {
    String previous = record.optString(FIELD_UPDATED, null);
    if (StringUtils.isBlank(previous)) {
      return;
    }
    String recordId = record.optString(KEY_ID, null);
    if (StringUtils.isBlank(recordId)) {
      recordId = fallbackId;
    }
    if (StringUtils.isBlank(recordId)) {
      return;
    }
    String current = storedToken(dalEntityName, recordId);
    if (current == null || current.equals(previous)) {
      return;
    }
    record.put(FIELD_UPDATED, current);
    log.debug("Refreshed the `updated` token of {} {} after the post-hook: '{}' -> '{}' (ETP-5262)",
        dalEntityName, recordId, previous, current);
  }

  /**
   * The token the row holds now, or {@code null} when it cannot be established.
   *
   * <p>Reads through {@link OBDal} rather than SQL so the value seen is the one the Hibernate
   * session holds — which, after a post-hook's {@code flush}, is the audit timestamp core's
   * interceptor stamped on the very object the hook saved. That is the same read
   * {@link NeoRecordVersion#readStoredUpdated} performs, and it is by construction the value the
   * next write's concurrency check will compare against: reading it any other way could
   * re-introduce the mismatch instead of removing it.
   *
   * <p>No admin mode is taken, matching {@link NeoRecordVersion}: a record the request just wrote
   * is one the caller can read, and silently widening access to stamp a timestamp is not a trade
   * worth making.
   */
  private static String storedToken(String dalEntityName, String recordId) {
    try {
      BaseOBObject stored = OBDal.getInstance().get(dalEntityName, recordId);
      if (!(stored instanceof Traceable)) {
        return null;
      }
      return NeoDateFormat.toAuditToken(((Traceable) stored).getUpdated());
    } catch (Exception e) {
      log.warn("Could not read the stored `updated` of {} {}: {}. The response keeps the token the"
          + " write produced (ETP-5262)", dalEntityName, recordId, e.getMessage());
      return null;
    }
  }
}
