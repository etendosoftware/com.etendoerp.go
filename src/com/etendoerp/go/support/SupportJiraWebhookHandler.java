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

package com.etendoerp.go.support;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Handles the two inbound Jira webhook shapes support chat relies on to mirror human agent
 * replies back into the conversation: the standard Jira system webhook (JSON body) and the
 * Jira Automation webhook (query params, used when Automation can't send a custom body).
 * Kept separate from {@link SupportConversationsServlet} to isolate the webhook payload
 * parsing/normalization from the servlet's own request routing and conversation persistence.
 */
final class SupportJiraWebhookHandler {

  private static final Logger log = LogManager.getLogger(SupportJiraWebhookHandler.class);

  private static final String WEBHOOK_SECRET = System.getProperty("support.webhook.secret", "");
  private static final String JIRA_BOT_EMAIL = System.getProperty("support.jira.bot.email", "");
  private static final String JIRA_USERNAME =
      System.getProperty("support.jira.username", "info@smfconsulting.es");

  private static final String HEADER_WEBHOOK_SECRET = "X-Webhook-Secret";
  private static final String MSG_INVALID_SECRET = "Invalid secret";
  private static final String MSG_INTERNAL_ERROR = "Internal error";
  private static final String RESP_IGNORED = "{\"status\":\"ignored\"}";
  private static final String DEFAULT_AGENT_NAME = "Agente de soporte";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_CONVERSATION_ID = "conversationId";
  private static final String FIELD_JIRA_TICKET_KEY = "jiraTicketKey";

  private SupportJiraWebhookHandler() {
  }

  static void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!WEBHOOK_SECRET.isEmpty() && !WEBHOOK_SECRET.equals(request.getHeader(HEADER_WEBHOOK_SECRET))) {
      SupportConversationsServlet.writeError(response, HttpServletResponse.SC_UNAUTHORIZED, MSG_INVALID_SECRET);
      return;
    }

    JSONObject body = SupportConversationsServlet.parseBodySilent(request);
    JiraWebhookComment comment = (body != null && body.length() > 0)
        ? parseStandardJiraWebhook(response, body)
        : parseAutomationJiraWebhook(request, response);
    if (comment == null) return;

    try {
      storeJiraWebhookComment(response, comment);
    } catch (Exception e) {
      log.error("Error processing Jira webhook", e);
      SupportConversationsServlet.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    }
  }

  // --- Standard Jira system webhook (JSON body) ---

  private static JiraWebhookComment parseStandardJiraWebhook(HttpServletResponse response, JSONObject body)
      throws IOException {
    JSONObject issue = body.optJSONObject("issue");
    if (issue == null) { writeIgnored(response); return null; }
    String jiraKey = issue.optString("key", "");
    if (jiraKey.isEmpty()) { writeIgnored(response); return null; }

    JSONObject comment = body.optJSONObject("comment");
    if (comment == null) {
      handleJiraNonCommentEvent(response, issue, body, jiraKey);
      return null;
    }

    String commentId = comment.optString("id", SupportConversationsServlet.newId());
    if (!comment.optBoolean("jsdPublic", true)) {
      SupportConversationsServlet.writeRaw(response, 200, "{\"status\":\"skipped_internal\"}");
      return null;
    }
    JSONObject author = comment.optJSONObject("author");
    String authorEmail = author != null ? author.optString("emailAddress", "") : "";
    String authorName = author != null ? author.optString("displayName", DEFAULT_AGENT_NAME) : DEFAULT_AGENT_NAME;
    String text = extractAdfText(comment.opt("body")).trim();
    return new JiraWebhookComment(jiraKey, commentId, authorName, authorEmail, text);
  }

  /** No "comment" field: either an assignee-change-back-to-bot or a status transition to Done. */
  private static void handleJiraNonCommentEvent(HttpServletResponse response, JSONObject issue, JSONObject body,
      String jiraKey) throws IOException {
    JSONObject fields = issue.optJSONObject("fields");
    if (fields != null) {
      JSONObject assignee = fields.optJSONObject("assignee");
      String assigneeEmail = assignee != null ? assignee.optString("emailAddress", "") : "";
      if (isBotEmail(assigneeEmail)) {
        handleAssigneeReset(response, jiraKey);
        return;
      }
    }
    if (isStatusTransitionToDone(body.optJSONObject("changelog"))) {
      handleTicketClosed(response, jiraKey);
      return;
    }
    writeIgnored(response);
  }

  private static boolean isStatusTransitionToDone(JSONObject changelog) {
    if (changelog == null) return false;
    JSONArray items = changelog.optJSONArray("items");
    if (items == null) return false;
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item != null && "status".equals(item.optString("field", ""))
          && "Done".equalsIgnoreCase(item.optString("toString", ""))) {
        return true;
      }
    }
    return false;
  }

  private static void writeIgnored(HttpServletResponse response) throws IOException {
    SupportConversationsServlet.writeRaw(response, 200, RESP_IGNORED);
  }

  // --- Jira Automation webhook (query params) ---

  private static JiraWebhookComment parseAutomationJiraWebhook(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    String jiraKey     = request.getParameter("issueKey");
    String commentId   = request.getParameter("commentId");
    String authorName  = nvl(request.getParameter("authorName"), DEFAULT_AGENT_NAME);
    String authorEmail = nvl(request.getParameter("authorEmail"), "");
    String text        = nvl(request.getParameter("commentText"), "");
    if (jiraKey == null || jiraKey.isEmpty()) {
      SupportConversationsServlet.writeRaw(response, 200, "{\"status\":\"ignored_no_key\"}");
      return null;
    }
    String action = nvl(request.getParameter("action"), "");
    if ("assignee_reset".equals(action)) {
      if (isBotEmail(authorEmail)) {
        handleAssigneeReset(response, jiraKey);
      } else {
        SupportConversationsServlet.writeRaw(response, 200, "{\"status\":\"ignored_not_bot\"}");
      }
      return null;
    }
    if ("ticket_closed".equals(action)) {
      handleTicketClosed(response, jiraKey);
      return null;
    }
    if (commentId == null) commentId = SupportConversationsServlet.newId();
    return new JiraWebhookComment(jiraKey, commentId, authorName, authorEmail, text);
  }

  // --- Persisting the comment as a support message ---

  private static void storeJiraWebhookComment(HttpServletResponse response, JiraWebhookComment comment)
      throws IOException, SQLException, JSONException {
    if (!JIRA_BOT_EMAIL.isEmpty() && JIRA_BOT_EMAIL.equalsIgnoreCase(comment.authorEmail)) {
      SupportConversationsServlet.writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "skipped_bot"));
      return;
    }
    String text = comment.text == null ? "" : comment.text.trim();
    if (text.isEmpty()) {
      SupportConversationsServlet.writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "empty_body"));
      return;
    }

    String externalId = "jira:" + comment.jiraKey + ":" + comment.commentId;
    OBContext.setAdminMode(true);
    try {
      Connection conn = OBDal.getInstance().getConnection();
      String convId = findConversationByJiraKey(conn, comment.jiraKey);
      if (convId == null) {
        SupportConversationsServlet.writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "no_conversation"));
        return;
      }
      String ts = Instant.now().toString();
      insertJiraMessage(conn, convId, comment.authorName, text, ts, externalId);
      SupportConversationsServlet.updateConvSummary(conn, convId, text, ts);
      markConversationUnread(conn, convId);
      log.info("Jira comment {} ({}) stored in conversation {}", comment.commentId, comment.authorName, convId);
      SupportConversationsServlet.writeJson(response, 200,
          new JSONObject().put(FIELD_STATUS, "ok").put(FIELD_CONVERSATION_ID, convId));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String findConversationByJiraKey(Connection conn, String jiraKey) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT id FROM etgo_support_conversation WHERE jira_ticket_key = ? LIMIT 1")) {
      ps.setString(1, jiraKey);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString("id") : null;
      }
    }
  }

  private static void insertJiraMessage(Connection conn, String convId, String authorName, String text, String ts,
      String externalId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO etgo_support_message (id, conversation_id, sender, sender_name, text, timestamp, external_id)" +
        " VALUES (?, ?, 'human', ?, ?, ?::timestamptz, ?)" +
        " ON CONFLICT (external_id) WHERE external_id IS NOT NULL DO NOTHING")) {
      ps.setString(1, SupportConversationsServlet.newId());
      ps.setString(2, convId);
      ps.setString(3, authorName);
      ps.setString(4, text);
      ps.setString(5, ts);
      ps.setString(6, externalId);
      ps.executeUpdate();
    }
  }

  private static void markConversationUnread(Connection conn, String convId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "UPDATE etgo_support_conversation SET unread = true WHERE id = ?")) {
      ps.setString(1, convId);
      ps.executeUpdate();
    }
  }

  // --- Jira automation side-effects triggered without a comment ---

  static void handleAssigneeReset(HttpServletResponse response, String jiraKey) throws IOException {
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation SET human_takeover = false WHERE jira_ticket_key = ?")) {
          ps.setString(1, jiraKey);
          int rows = ps.executeUpdate();
          log.info("Human takeover reset via assignee event for Jira ticket {} ({} row(s))", jiraKey, rows);
        }
        SupportConversationsServlet.writeJson(response, 200,
            new JSONObject().put(FIELD_STATUS, "ok").put(FIELD_JIRA_TICKET_KEY, jiraKey));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error resetting human takeover for ticket {}", jiraKey, e);
      SupportConversationsServlet.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    }
  }

  static void handleTicketClosed(HttpServletResponse response, String jiraKey) throws IOException {
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        String convId = findConversationByJiraKey(conn, jiraKey);
        if (convId == null) {
          SupportConversationsServlet.writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "no_conversation"));
          return;
        }
        String ts = Instant.now().toString();
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation SET status = 'closed', unread = true, last_activity = ?::timestamptz WHERE id = ?")) {
          ps.setString(1, ts);
          ps.setString(2, convId);
          ps.executeUpdate();
        }
        log.info("Conversation {} closed via Jira ticket {} resolution", convId, jiraKey);
        SupportConversationsServlet.writeJson(response, 200,
            new JSONObject().put(FIELD_STATUS, "ok").put(FIELD_CONVERSATION_ID, convId));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error closing conversation for ticket {}", jiraKey, e);
      SupportConversationsServlet.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    }
  }

  private static boolean isBotEmail(String email) {
    if (email == null || email.isEmpty()) return false;
    return (!JIRA_BOT_EMAIL.isEmpty() && JIRA_BOT_EMAIL.equalsIgnoreCase(email))
        || JIRA_USERNAME.equalsIgnoreCase(email);
  }

  // --- Jira ADF (Atlassian Document Format) comment body parsing ---

  private static String extractAdfText(Object node) {
    if (node == null) return "";
    if (node instanceof String) return extractAdfTextFromString((String) node);
    if (node instanceof JSONObject) return extractAdfTextFromObject((JSONObject) node);
    return "";
  }

  private static String extractAdfTextFromString(String raw) {
    String s = raw.trim();
    if (!s.startsWith("{")) return s;
    try {
      return extractAdfText(new JSONObject(s));
    } catch (Exception e) {
      return s;
    }
  }

  private static String extractAdfTextFromObject(JSONObject obj) {
    String type = obj.optString("type", "");
    if ("text".equals(type)) return obj.optString("text", "");
    if ("hardBreak".equals(type)) return "\n";
    StringBuilder sb = new StringBuilder();
    JSONArray content = obj.optJSONArray("content");
    if (content != null) {
      for (int i = 0; i < content.length(); i++) {
        Object child = content.opt(i);
        if (child instanceof JSONObject) sb.append(extractAdfText(child));
      }
    }
    if (isBlockType(type) && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
      sb.append('\n');
    }
    return sb.toString();
  }

  private static boolean isBlockType(String type) {
    return "paragraph".equals(type) || "heading".equals(type) ||
        "bulletList".equals(type) || "orderedList".equals(type) ||
        "listItem".equals(type) || "codeBlock".equals(type) || "blockquote".equals(type);
  }

  private static String nvl(String value, String fallback) {
    return (value != null && !value.isEmpty()) ? value : fallback;
  }

  private static final class JiraWebhookComment {
    final String jiraKey;
    final String commentId;
    final String authorName;
    final String authorEmail;
    final String text;

    JiraWebhookComment(String jiraKey, String commentId, String authorName, String authorEmail, String text) {
      this.jiraKey = jiraKey;
      this.commentId = commentId;
      this.authorName = authorName;
      this.authorEmail = authorEmail;
      this.text = text;
    }
  }
}
