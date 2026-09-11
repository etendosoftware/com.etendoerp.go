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

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

/**
 * Base class for {@link NeoHandler} implementations that perform smart deactivation.
 *
 * <p>Provides the standard pre-hook skeleton: guard on PUT method, guard on explicit
 * {@code active=false}, run {@link #smartDeactivate} under admin mode. Any unexpected exception
 * surfaces as a 500 rather than silently falling through to the default CRUD — falling through
 * would bypass the pending-invoices check and allow deactivation without verification.
 * Concrete handlers supply only {@link #smartDeactivate} and their {@code @Named} qualifier.
 *
 * <p><b>DELETE pre-hook (ETP-5117).</b> A genuine HTTP {@code DELETE} never reaches
 * {@link #smartDeactivate} — it falls straight through to NEO's default hard-delete CRUD. Any
 * cleanup that needs the record's own data (its client/organization, for instance) therefore has
 * to run <em>before</em> the delete, not in {@code afterHandle} where the row is already gone and
 * only the session context is left to guess from — and a GO client-admin session very commonly
 * reports organization {@code '0'} (the {@code '*'} org), which is not the record's own
 * organization. {@link #beforeDelete} is the extension point for that: it defaults to a no-op, so
 * subclasses that need no DELETE cleanup (e.g. {@code VerifactuConfigReadyHandler}) are entirely
 * unaffected, and {@link #handle} always returns {@code null} for a DELETE so the default CRUD
 * proceeds exactly as before. Failures inside {@link #beforeDelete} are logged and swallowed —
 * a cleanup side effect must never block the delete itself.
 *
 * <p>{@link #deletedResponse()} is {@code protected static} for use in subclass
 * {@code smartDeactivate} and {@code afterHandle} implementations. {@link
 * #isExplicitlyDeactivating} is {@code public static} instead — {@code
 * UserRoleAssignmentHandler} (ETP-4830) is not a subclass of this class (it already has its own
 * multi-concern {@code handle()}/{@code afterHandle()} dispatch across GET/POST/PUT/PATCH for
 * the {@code user} entity, so it cannot also extend this class's single-purpose PUT-only
 * skeleton) but still needs the exact same "is this request explicitly setting {@code
 * active=false}" test for its own self/last-admin lockout guard, rather than duplicating it.
 */
public abstract class AbstractSmartDeactivationHandler implements NeoHandler {

  protected static final String METHOD_PUT = "PUT";
  protected static final String METHOD_DELETE = "DELETE";
  private static final String FIELD_ACTIVE = "active";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (METHOD_DELETE.equalsIgnoreCase(context.getHttpMethod())) {
      runBeforeDelete(context);
      return null;
    }
    if (!METHOD_PUT.equalsIgnoreCase(context.getHttpMethod())) {
      return null;
    }
    if (!isExplicitlyDeactivating(context.getRequestBody())) {
      return null;
    }
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        return smartDeactivate(recordId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      LogManager.getLogger(getClass()).error(
          "handle: unexpected error during smart deactivation for {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Error checking deactivation conditions: " + e.getMessage());
    }
  }

  protected abstract NeoResponse smartDeactivate(String recordId) throws JSONException;

  /**
   * Runs {@link #beforeDelete} under admin mode for a genuine {@code DELETE}, swallowing any
   * failure: this is a cleanup side effect, and letting it surface would block a delete the user
   * explicitly asked for.
   */
  private void runBeforeDelete(NeoContext context) {
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        beforeDelete(context, recordId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      LogManager.getLogger(getClass()).warn(
          "beforeDelete: cleanup failed for {}: {}", recordId, e.getMessage(), e);
    }
  }

  /**
   * Hook invoked just before NEO's default CRUD hard-deletes {@code recordId}, while the record
   * still exists and can still answer for its own client/organization. Defaults to a no-op, so
   * subclasses that need no DELETE cleanup keep their previous behavior exactly.
   *
   * <p>Runs under admin mode; the caller swallows and logs any exception. Implementations must
   * not attempt to cancel the delete — the return value of {@link #handle} for a DELETE is always
   * {@code null}.
   *
   * @param context
   *          the current NEO request context
   * @param recordId
   *          the primary key of the record about to be deleted (never blank)
   */
  protected void beforeDelete(NeoContext context, String recordId) {
    // no-op by default
  }

  protected static NeoResponse deletedResponse() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("deleted", true);
    return NeoResponse.ok(body);
  }

  /**
   * Returns {@code true} only when {@code body} explicitly carries an "active" flag (boolean
   * {@code false}, or the string {@code "false"}/{@code "N"}) set to false — a field simply
   * absent from {@code body} is never treated as a deactivation request.
   *
   * @param body
   *          the incoming request body to inspect, or {@code null}
   * @return {@code true} when {@code body} explicitly sets the active flag to false
   */
  public static boolean isExplicitlyDeactivating(JSONObject body) {
    if (body == null || !body.has(FIELD_ACTIVE)) {
      return false;
    }
    Object value = body.opt(FIELD_ACTIVE);
    if (value instanceof Boolean) {
      return !(Boolean) value;
    }
    if (value instanceof String) {
      return "false".equalsIgnoreCase((String) value) || "N".equalsIgnoreCase((String) value);
    }
    return false;
  }
}
