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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Collection;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * A contact's {@code searchKey} ({@code C_BPartner.Value}) on create, for
 * {@link BusinessPartnerHandler}. The app's form does not show it: the save needs a temporary
 * value, and once the row exists the key becomes the contact's number
 * ({@code em_etgo_identifier}), in the table and in the answer.
 */
final class ContactSearchKey {

  private static final Logger log = LogManager.getLogger(ContactSearchKey.class);

  static final String FIELD_SEARCH_KEY = "searchKey";
  /** ETP-5852 (M-107): the answer key that reports a searchKey the caller sent and lost. */
  static final String KEY_SEARCH_KEY_NOTE = "searchKeyNote";
  private static final String FIELD_NAME = "name";
  private static final String RESPONSE_KEY = "response";
  private static final String KEY_DATA = "data";
  /** {@code C_BPartner.Value} is {@code VARCHAR(40)}. */
  private static final int SEARCH_KEY_MAX_LENGTH = 40;
  private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

  private ContactSearchKey() {
  }

  /**
   * Puts the name as the temporary searchKey of a create body that has none. Never overwrites a
   * value the caller sent, and abstains without a name.
   */
  static void putPlaceholder(JSONObject body) throws Exception {
    String name = body.optString(FIELD_NAME, null);
    if (StringUtils.isNotBlank(name) && !body.has(FIELD_SEARCH_KEY)) {
      // C_BPartner.Value is VARCHAR(40) while Name has 60, so a long commercial name
      // cannot be used verbatim as the placeholder ("Value too long. Length 48,
      // maximum allowed 40"). The number replaces it after the save anyway, so truncating
      // here is lossless. ETP-4156: the app-shell used to pre-truncate this client-side,
      // which masked the missing guard.
      body.put(FIELD_SEARCH_KEY, StringUtils.substring(name, 0, SEARCH_KEY_MAX_LENGTH));
    }
  }

  /**
   * POST only: replaces the placeholder searchKey with the identifier the database computed, both
   * in C_BPartner and in the response being returned. Never runs the update for a blank
   * identifier.
   *
   * @param recordId the created contact; {@code null} answers {@code false}
   * @param body     the create's response, patched in place
   * @return whether {@code body} was patched
   */
  static boolean applyNumber(String recordId, JSONObject body) throws Exception {
    if (recordId == null) {
      return false;
    }
    String identifier = queryIdentifier(recordId);
    if (StringUtils.isBlank(identifier) || !applyUpdate(recordId, identifier)) {
      return false;
    }
    patchResponse(body, identifier);
    return true;
  }

  /**
   * ETP-5852 (M-107): on MCP, when the caller sent its own searchKey and the contact got its number
   * instead, says so in the answer ({@code searchKeyNote}), so the replacement is not silent. The
   * app never sends one: its form does not show the field.
   *
   * @param created the created record as answered; may be {@code null}
   * @param body    the create's response, patched in place
   * @return whether {@code body} was patched
   */
  static boolean noteReplaced(NeoContext ctx, JSONObject created, JSONObject body)
      throws Exception {
    Object provided = ctx == null ? null : ctx.getAttribute(NeoContext.ATTR_CLIENT_PROVIDED_FIELDS);
    if (ctx == null || !ctx.isMcpOrigin() || ctx.getRequestBody() == null
        || !(provided instanceof Collection)
        || !((Collection<?>) provided).contains(FIELD_SEARCH_KEY)) {
      return false;
    }
    String sent = ctx.getRequestBody().optString(FIELD_SEARCH_KEY, null);
    String kept = created == null ? null : created.optString(FIELD_SEARCH_KEY, null);
    JSONObject response = body.optJSONObject(RESPONSE_KEY);
    if (StringUtils.isBlank(sent) || kept == null || sent.equals(kept) || response == null) {
      return false;
    }
    response.put(KEY_SEARCH_KEY_NOTE, "The searchKey you sent ('" + sent + "') was not kept: a "
        + "contact's searchKey is its number, assigned automatically as in the app (" + kept
        + "). Do not send it when creating a contact.");
    return true;
  }

  private static String queryIdentifier(String recordId) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT em_etgo_identifier FROM c_bpartner WHERE c_bpartner_id = ?")) {
      ps.setString(1, recordId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getString(1);
        }
      }
    }
    return null;
  }

  /**
   * Updates the searchKey under a savepoint so a duplicate-key hit (two concurrent rows racing on
   * the same not-yet-committed sequence value) only rolls back the failed UPDATE instead of
   * poisoning the whole shared {@code /batch} transaction for every other operation that follows
   * on the same connection.
   *
   * @return {@code true} when the identifier was applied, {@code false} when it was skipped due to
   *         a duplicate-key race (the placeholder searchKey is kept in that case)
   */
  private static boolean applyUpdate(String recordId, String identifier) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    Savepoint savepoint = conn.setSavepoint();
    try (PreparedStatement ps = conn.prepareStatement(
        "UPDATE c_bpartner SET value = ? WHERE c_bpartner_id = ?")) {
      ps.setString(1, identifier);
      ps.setString(2, recordId);
      ps.executeUpdate();
      return true;
    } catch (SQLException e) {
      if (!SQLSTATE_UNIQUE_VIOLATION.equals(e.getSQLState())) {
        throw e;
      }
      log.warn("BusinessPartnerHandler: searchKey race on bp={} (duplicate identifier {}), "
          + "keeping placeholder searchKey", recordId, identifier, e);
      conn.rollback(savepoint);
      return false;
    }
  }

  private static void patchResponse(JSONObject body, String identifier) {
    try {
      JSONObject response = body.optJSONObject(RESPONSE_KEY);
      JSONArray data = response == null ? null : response.optJSONArray(KEY_DATA);
      if (data != null && data.length() > 0) {
        data.getJSONObject(0).put(FIELD_SEARCH_KEY, identifier);
      }
    } catch (Exception e) {
      log.warn("BusinessPartnerHandler: could not patch searchKey in response", e);
    }
  }
}
