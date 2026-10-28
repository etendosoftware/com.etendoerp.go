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

package com.etendoerp.go.schemaforge.webhooks;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * ETP-5952 — the queue the external MCP feedback triage bot ({@code etendo-bots}, {@code
 * bin/bug-triage}) polls: {@code etendo_feedback} reports in {@code ETGO_MCP_USAGE} that the bot
 * has not triaged yet, oldest first, from <b>every</b> client.
 *
 * <pre>GET /webhooks/McpFeedbackPending?limit=20</pre>
 *
 * <p><b>Every client, on purpose.</b> {@code WebhookServiceHandler} binds the {@code OBContext} of
 * the token's user, so a DAL query would only see the bot user's own client. The read is plain
 * SQL on the request's connection, which no client filter reaches. Access is therefore decided
 * entirely by who may call the webhook: it is granted to the bot's token only, per instance (see
 * {@code docs/mcp-usage-telemetry.md}, "Triage bot").
 *
 * <p><b>What is listed:</b> {@code row_type = 'feedback'}, {@code triaged IS NULL} and a stored
 * payload. A rejected or rate-limited {@code etendo_feedback} call is also a feedback row, but with
 * no payload ({@code McpServlet.recordToolCall}); there is nothing to triage in it, and listing it
 * would park it at the head of the queue for good, since the bot reads the oldest rows first.
 *
 * <p>Response, as {@link SFListWindows}: {@code result} is a JSON array serialized as a string and
 * {@code count} its size; {@code error} on a bad {@code limit} or a failed read. The payload is
 * agent-written text that can carry tenant data: it is returned, never logged.
 */
public class McpFeedbackPending extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(McpFeedbackPending.class);

  static final String PARAM_LIMIT = "limit";
  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 100;

  /** Same rendering as {@code mcp-usage-dump.sh}: database server time, millisecond precision. */
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

  private static final String PENDING_SQL = "SELECT u.etgo_mcp_usage_id, u.created, u.ad_client_id,"
      + " c.name AS client, u.tool_name, u.verb, u.target_entity, u.outcome, u.error_code,"
      + " u.client_name, u.client_version, u.payload, u.triage_cell, u.triage_assignee,"
      + " u.triage_issue, u.triage_task, u.triaged"
      + " FROM etgo_mcp_usage u LEFT JOIN ad_client c ON c.ad_client_id = u.ad_client_id"
      + " WHERE u.row_type = 'feedback' AND u.triaged IS NULL AND u.payload IS NOT NULL"
      + " ORDER BY u.created, u.etgo_mcp_usage_id LIMIT ?";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    Integer limit = parseLimit(parameter.get(PARAM_LIMIT));
    if (limit == null) {
      responseVars.put(McpFeedbackTriage.KEY_ERROR,
          "limit must be a whole number between 1 and " + MAX_LIMIT);
      return;
    }
    try {
      JSONArray rows = readPending(OBDal.getInstance().getConnection(), limit);
      responseVars.put("result", rows.toString());
      responseVars.put("count", String.valueOf(rows.length()));
      log.debug("McpFeedbackPending returned {} row(s)", rows.length());
    } catch (Exception e) {
      log.error("Error in McpFeedbackPending", e);
      responseVars.put(McpFeedbackTriage.KEY_ERROR, "Could not read the pending MCP feedback");
    }
  }

  /**
   * @param raw the {@code limit} parameter as sent
   * @return {@value #DEFAULT_LIMIT} when absent, the value capped at {@value #MAX_LIMIT}, or
   *         {@code null} when it is not a whole number of at least 1
   */
  static Integer parseLimit(String raw) {
    if (StringUtils.isBlank(raw)) {
      return DEFAULT_LIMIT;
    }
    try {
      int value = Integer.parseInt(raw.trim());
      return value < 1 ? null : Math.min(value, MAX_LIMIT);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static JSONArray readPending(Connection connection, int limit)
      throws SQLException, JSONException {
    JSONArray rows = new JSONArray();
    try (PreparedStatement statement = connection.prepareStatement(PENDING_SQL)) {
      statement.setInt(1, limit);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          rows.put(toJson(rs));
        }
      }
    }
    return rows;
  }

  private static JSONObject toJson(ResultSet rs) throws SQLException, JSONException {
    JSONObject row = new JSONObject();
    row.put("id", rs.getString("etgo_mcp_usage_id"));
    row.put("created", iso(rs.getTimestamp("created")));
    for (String column : new String[] { "ad_client_id", "client", "tool_name", "verb",
        "target_entity", "outcome", "error_code", "client_name", "client_version", "payload",
        "triage_cell", "triage_assignee", "triage_issue", "triage_task" }) {
      row.put(column, nullable(rs.getString(column)));
    }
    row.put("triaged", iso(rs.getTimestamp("triaged")));
    return row;
  }

  private static Object iso(Timestamp value) {
    return value == null ? JSONObject.NULL : ISO_MILLIS.format(value.toLocalDateTime());
  }

  private static Object nullable(String value) {
    return value == null ? JSONObject.NULL : value;
  }
}
