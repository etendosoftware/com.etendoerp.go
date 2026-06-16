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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.common.EtendoGoCorsServlet;
import com.etendoerp.go.common.ProtocolErrorAdapters;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Support Chat REST API servlet.
 *
 * Mapped to /sws/support/* via AD_MODEL_OBJECT_MAPPING.
 *
 * Endpoints (all require Bearer JWT from Etendo's standard /sws/login):
 *   GET  /sws/support/conversations                          — List conversations for the user
 *   POST /sws/support/conversations                          — Start a new conversation
 *   GET  /sws/support/conversations/:id/messages             — Load message history
 *   POST /sws/support/conversations/:id/messages             — Send a message
 *   POST /sws/support/conversations/:id/rating               — Submit satisfaction rating
 *
 * Conversations and messages are persisted in PostgreSQL tables
 * (etgo_support_conversation, etgo_support_message) created on first use.
 */
public class SupportConversationsServlet extends EtendoGoCorsServlet {

  private static final Logger log = LogManager.getLogger(SupportConversationsServlet.class);

  private static final String CONTENT_TYPE_JSON = "application/json";
  private static final String CHARSET_UTF8      = "UTF-8";
  private static final String FIELD_MESSAGE     = "message";
  private static final String FIELD_STATUS      = "status";
  private static final String FIELD_ERROR       = "error";

  private static final String SENDER_AI    = "ai";
  private static final String SENDER_USER  = "user";
  private static final String STATUS_OPEN  = "open";
  private static final String STATUS_CLOSED = "closed";
  private static final String AI_AGENT_NAME = "ValerIA";
  private static final String AI_STUB_REPLY =
      "Hola, soy ValerIA. En este momento no puedo conectarme con el servicio de IA. Por favor intenta de nuevo en un momento.";

  private static final String ADK_BASE_URL =
      System.getProperty("support.adk.url", "http://localhost:8000");
  private static final String ADK_APP_NAME = "agent";
  private static final String WEBHOOK_SECRET =
      System.getProperty("support.webhook.secret", "");
  private static final String JIRA_BOT_EMAIL =
      System.getProperty("support.jira.bot.email", "");
  private static final String JIRA_URL =
      System.getProperty("support.jira.url", "https://etendoproject.atlassian.net");
  private static final String JIRA_USERNAME =
      System.getProperty("support.jira.username", "info@smfconsulting.es");
  private static final String JIRA_API_TOKEN =
      System.getProperty("support.jira.token", "");
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  // DDL — created once on first request
  private static final AtomicBoolean TABLES_READY = new AtomicBoolean(false);

  private static final String DDL_CONVERSATION =
      "CREATE TABLE IF NOT EXISTS etgo_support_conversation (" +
      "  id             VARCHAR(32)  PRIMARY KEY," +
      "  user_id        VARCHAR(255) NOT NULL," +
      "  subject        VARCHAR(255)," +
      "  status         VARCHAR(32)  NOT NULL DEFAULT 'open'," +
      "  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()," +
      "  last_activity  TIMESTAMPTZ  NOT NULL DEFAULT now()," +
      "  last_message   TEXT," +
      "  unread         BOOLEAN      NOT NULL DEFAULT FALSE," +
      "  rated          BOOLEAN      NOT NULL DEFAULT FALSE," +
      "  rating_score   INTEGER," +
      "  rating_comment TEXT" +
      ")";

  private static final String DDL_MESSAGE =
      "CREATE TABLE IF NOT EXISTS etgo_support_message (" +
      "  id              VARCHAR(32)  PRIMARY KEY," +
      "  conversation_id VARCHAR(32)  NOT NULL REFERENCES etgo_support_conversation(id)," +
      "  sender          VARCHAR(32)  NOT NULL," +
      "  sender_name     VARCHAR(255)," +
      "  text            TEXT," +
      "  timestamp       TIMESTAMPTZ  NOT NULL DEFAULT now()" +
      ")";

  private static final String DDL_IDX_CONV_USER =
      "CREATE INDEX IF NOT EXISTS etgo_sc_conv_user ON etgo_support_conversation(user_id, last_activity DESC)";

  private static final String DDL_IDX_MSG_CONV =
      "CREATE INDEX IF NOT EXISTS etgo_sc_msg_conv ON etgo_support_message(conversation_id, timestamp ASC)";

  private static final String DDL_MIGRATE_JIRA_KEY =
      "ALTER TABLE etgo_support_conversation ADD COLUMN IF NOT EXISTS jira_ticket_key VARCHAR(64)";
  private static final String DDL_MIGRATE_JIRA_IDX =
      "CREATE INDEX IF NOT EXISTS etgo_sc_conv_jira ON etgo_support_conversation(jira_ticket_key)";
  private static final String DDL_MIGRATE_MSG_EXTID =
      "ALTER TABLE etgo_support_message ADD COLUMN IF NOT EXISTS external_id VARCHAR(128)";
  private static final String DDL_MIGRATE_MSG_EXTID_IDX =
      "CREATE UNIQUE INDEX IF NOT EXISTS etgo_sc_msg_extid ON etgo_support_message(external_id) WHERE external_id IS NOT NULL";
  private static final String DDL_MIGRATE_HUMAN_TAKEOVER =
      "ALTER TABLE etgo_support_conversation ADD COLUMN IF NOT EXISTS human_takeover BOOLEAN NOT NULL DEFAULT FALSE";

  // --- HTTP dispatchers ---

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String userId = authenticateAndGetUserId(request, response);
    if (userId == null) return;
    ensureTablesExist();

    String pathInfo = request.getPathInfo();
    if (pathInfo == null) pathInfo = "/";

    if ("/conversations".equals(pathInfo) || "/conversations/".equals(pathInfo)) {
      handleListConversations(response, userId);
      return;
    }

    String[] parts = pathInfo.split("/");
    if (parts.length == 4 && "conversations".equals(parts[1]) && "messages".equals(parts[3])) {
      handleGetMessages(response, userId, parts[2]);
      return;
    }

    writeError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + pathInfo);
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String pathInfo = request.getPathInfo();
    if (pathInfo == null) pathInfo = "/";

    // Unauthenticated internal / webhook endpoints
    if ("/jira-webhook".equals(pathInfo)) {
      ensureTablesExist();
      handleJiraWebhook(request, response);
      return;
    }
    if ("/internal/set-ticket".equals(pathInfo)) {
      ensureTablesExist();
      handleSetTicket(request, response);
      return;
    }
    if ("/internal/set-human-takeover".equals(pathInfo)) {
      ensureTablesExist();
      handleSetHumanTakeover(request, response);
      return;
    }
    if ("/internal/reset-human-takeover".equals(pathInfo)) {
      ensureTablesExist();
      handleResetHumanTakeover(request, response);
      return;
    }

    String userId = authenticateAndGetUserId(request, response);
    if (userId == null) return;
    ensureTablesExist();

    if ("/conversations".equals(pathInfo) || "/conversations/".equals(pathInfo)) {
      handleCreateConversation(request, response, userId);
      return;
    }

    String[] parts = pathInfo.split("/");
    if (parts.length >= 4 && "conversations".equals(parts[1])) {
      String convId = parts[2];
      String action = parts[3];
      if ("messages".equals(action)) {
        handleSendMessage(request, response, userId, convId);
        return;
      }
      if ("rating".equals(action)) {
        handleSubmitRating(request, response, userId, convId);
        return;
      }
      if ("close".equals(action)) {
        handleCloseConversation(response, userId, convId);
        return;
      }
      if ("reopen".equals(action)) {
        handleReopenConversation(response, userId, convId);
        return;
      }
    }

    writeError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + pathInfo);
  }

  // --- Endpoint handlers ---

  private void handleListConversations(HttpServletResponse response, String userId)
      throws IOException {
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        JSONArray arr = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id, subject, status, last_activity, last_message, unread, rated" +
            "  FROM etgo_support_conversation" +
            " WHERE user_id = ? ORDER BY last_activity DESC")) {
          ps.setString(1, userId);
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              JSONObject conv = new JSONObject();
              conv.put("id",           rs.getString("id"));
              conv.put("subject",      rs.getString("subject"));
              conv.put("status",       rs.getString("status"));
              conv.put("lastActivity", toIso(rs.getString("last_activity")));
              conv.put("lastMessage",  rs.getString("last_message") != null ? rs.getString("last_message") : "");
              conv.put("unread",       rs.getBoolean("unread"));
              conv.put("rated",        rs.getBoolean("rated"));
              arr.put(conv);
            }
          }
        }
        JSONObject result = new JSONObject();
        result.put("conversations", arr);
        writeJson(response, HttpServletResponse.SC_OK, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error listing conversations for user {}", userId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  private void handleCreateConversation(HttpServletRequest request, HttpServletResponse response,
      String userId) throws IOException {
    JSONObject body = parseBody(request, response);
    if (body == null) return;

    String firstMessage;
    try {
      firstMessage = body.getString(FIELD_MESSAGE).trim();
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing required field: message");
      return;
    }
    if (firstMessage.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Field message must not be empty");
      return;
    }

    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        String convId  = newId();
        String msgId1  = newId();
        String now     = Instant.now().toString();
        String subject = firstMessage.length() > 60
            ? firstMessage.substring(0, 60) + "…" : firstMessage;

        // Insert conversation
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO etgo_support_conversation" +
            "  (id, user_id, subject, status, created_at, last_activity, unread, rated)" +
            " VALUES (?, ?, ?, 'open', ?::timestamptz, ?::timestamptz, false, false)")) {
          ps.setString(1, convId);
          ps.setString(2, userId);
          ps.setString(3, subject);
          ps.setString(4, now);
          ps.setString(5, now);
          ps.executeUpdate();
        }

        // Insert user message
        insertMessage(conn, msgId1, convId, SENDER_USER, "Tú", firstMessage, now);

        // Commit before calling ADK so background set-ticket can see the new conversation row
        conn.commit();

        // AI reply
        JSONArray attachments = body.optJSONArray("attachments");
        createAdkSession(userId, convId);
        String aiReplyText = sendToAdk(userId, convId, firstMessage, attachments);
        if (aiReplyText == null) aiReplyText = AI_STUB_REPLY;

        String msgId2    = newId();
        String aiNow     = Instant.now().toString();
        insertMessage(conn, msgId2, convId, SENDER_AI, AI_AGENT_NAME, aiReplyText, aiNow);

        // Update conversation summary
        updateConvSummary(conn, convId, aiReplyText, aiNow);

        JSONObject result = new JSONObject();
        result.put("conversation", buildConvSummary(conn, convId));
        result.put("messages", buildMessageArray(conn, convId));
        writeJson(response, HttpServletResponse.SC_CREATED, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error creating conversation for user {}", userId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  private void handleGetMessages(HttpServletResponse response, String userId, String convId)
      throws IOException {
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        if (!conversationBelongsToUser(conn, convId, userId)) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, "Conversation not found");
          return;
        }
        // Mark as read
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation SET unread = false WHERE id = ?")) {
          ps.setString(1, convId);
          ps.executeUpdate();
        }
        JSONObject result = new JSONObject();
        result.put("messages", buildMessageArray(conn, convId));
        writeJson(response, HttpServletResponse.SC_OK, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error loading messages for conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  private void handleSendMessage(HttpServletRequest request, HttpServletResponse response,
      String userId, String convId) throws IOException {
    JSONObject body = parseBody(request, response);
    if (body == null) return;

    String text;
    try {
      text = body.getString("text").trim();
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing required field: text");
      return;
    }
    if (text.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Field text must not be empty");
      return;
    }

    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        if (!conversationBelongsToUser(conn, convId, userId)) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, "Conversation not found");
          return;
        }
        String convStatus = getConvStatus(conn, convId);
        if (STATUS_CLOSED.equals(convStatus)) {
          writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Conversation is closed");
          return;
        }

        String now = Instant.now().toString();
        insertMessage(conn, newId(), convId, SENDER_USER, "Tú", text, now);

        // If ticket is assigned to a human agent, block AI response
        boolean humanTakeover = false;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT human_takeover FROM etgo_support_conversation WHERE id = ?")) {
          ps.setString(1, convId);
          try (var rs = ps.executeQuery()) {
            if (rs.next()) humanTakeover = rs.getBoolean("human_takeover");
          }
        }

        if (humanTakeover) {
          // Human agent is handling this — forward user message to Jira, send no AI reply
          String jiraKey = null;
          try (PreparedStatement ps2 = conn.prepareStatement(
              "SELECT jira_ticket_key FROM etgo_support_conversation WHERE id = ?")) {
            ps2.setString(1, convId);
            try (var rs2 = ps2.executeQuery()) {
              if (rs2.next()) jiraKey = rs2.getString("jira_ticket_key");
            }
          }
          final String finalKey = jiraKey;
          final String finalText = text;
          new Thread(() -> postJiraComment(finalKey, finalText), "jira-comment").start();
          JSONObject result = new JSONObject();
          result.put("messages",     buildMessageArray(conn, convId));
          result.put("conversation", buildConvSummary(conn, convId));
          writeJson(response, HttpServletResponse.SC_OK, result);
          return;
        }

        JSONArray attachments = body.optJSONArray("attachments");
        // Sync human_takeover=false into ADK session state; flag tells agent to skip Jira re-check
        JSONObject stateDelta = new JSONObject()
            .put("human_takeover", false)
            .put("human_takeover_synced", true);
        String aiReplyText = sendToAdk(userId, convId, text, attachments, stateDelta);
        if (aiReplyText == null) aiReplyText = AI_STUB_REPLY;

        String aiNow = Instant.now().toString();
        insertMessage(conn, newId(), convId, SENDER_AI, AI_AGENT_NAME, aiReplyText, aiNow);
        updateConvSummary(conn, convId, aiReplyText, aiNow);

        JSONObject result = new JSONObject();
        result.put("messages",     buildMessageArray(conn, convId));
        result.put("conversation", buildConvSummary(conn, convId));
        writeJson(response, HttpServletResponse.SC_OK, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error sending message to conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  private void handleSubmitRating(HttpServletRequest request, HttpServletResponse response,
      String userId, String convId) throws IOException {
    JSONObject body = parseBody(request, response);
    if (body == null) return;

    int score;
    try {
      score = body.getInt("score");
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing required field: score");
      return;
    }
    if (score < 1 || score > 5) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Field score must be between 1 and 5");
      return;
    }

    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        if (!conversationBelongsToUser(conn, convId, userId)) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, "Conversation not found");
          return;
        }
        String comment = body.optString("comment", "").trim();
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation" +
            "   SET rated = true, rating_score = ?, rating_comment = ?" +
            " WHERE id = ?")) {
          ps.setInt(1, score);
          ps.setString(2, comment);
          ps.setString(3, convId);
          ps.executeUpdate();
        }
        JSONObject result = new JSONObject();
        result.put(FIELD_STATUS, "success");
        writeJson(response, HttpServletResponse.SC_OK, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error submitting rating for conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  private void handleCloseConversation(HttpServletResponse response, String userId, String convId)
      throws IOException {
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        if (!conversationBelongsToUser(conn, convId, userId)) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, "Conversation not found");
          return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation SET status = 'closed' WHERE id = ?")) {
          ps.setString(1, convId);
          ps.executeUpdate();
        }
        JSONObject result = new JSONObject();
        result.put("conversation", buildConvSummary(conn, convId));
        writeJson(response, HttpServletResponse.SC_OK, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error closing conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  private void handleReopenConversation(HttpServletResponse response, String userId, String convId)
      throws IOException {
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        if (!conversationBelongsToUser(conn, convId, userId)) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, "Conversation not found");
          return;
        }
        String now = Instant.now().toString();
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation SET status = 'open', last_activity = ?::timestamptz WHERE id = ?")) {
          ps.setString(1, now);
          ps.setString(2, convId);
          ps.executeUpdate();
        }
        // System message to mark reopen in the thread
        insertMessage(conn, newId(), convId, SENDER_AI, AI_AGENT_NAME,
            "La conversación ha sido reabierta. ¿En qué más puedo ayudarte?", now);
        JSONObject result = new JSONObject();
        result.put("conversation", buildConvSummary(conn, convId));
        result.put("messages", buildMessageArray(conn, convId));
        writeJson(response, HttpServletResponse.SC_OK, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error reopening conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  // --- Auth ---

  private String authenticateAndGetUserId(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
          "Missing or invalid Authorization header");
      return null;
    }
    String token = authHeader.substring(7).trim();
    try {
      DecodedJWT jwt = SecureWebServicesUtils.decodeToken(token);
      String userId = jwt.getClaim("user").asString();
      if (userId == null || userId.isEmpty()) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid token: missing user claim");
        return null;
      }
      return userId;
    } catch (Exception e) {
      log.warn("Support chat: invalid JWT token", e);
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
      return null;
    }
  }

  // --- Jira webhook & internal set-ticket ---

  private void handleSetTicket(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!WEBHOOK_SECRET.isEmpty()) {
      String s = request.getHeader("X-Internal-Secret");
      if (!WEBHOOK_SECRET.equals(s)) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid secret");
        return;
      }
    }
    JSONObject body = parseBody(request, response);
    if (body == null) return;
    String convId      = body.optString("conversationId", "");
    String jiraKey     = body.optString("jiraTicketKey", "");
    if (convId.isEmpty() || jiraKey.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "conversationId and jiraTicketKey required");
      return;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation SET jira_ticket_key = ? WHERE id = ?")) {
          ps.setString(1, jiraKey);
          ps.setString(2, convId);
          if (ps.executeUpdate() == 0) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, "Conversation not found");
            return;
          }
        }
        log.info("Linked conversation {} to Jira ticket {}", convId, jiraKey);
        writeJson(response, HttpServletResponse.SC_OK, new JSONObject().put(FIELD_STATUS, "ok"));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error in set-ticket", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  private void handleSetHumanTakeover(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!WEBHOOK_SECRET.isEmpty()) {
      String s = request.getHeader("X-Internal-Secret");
      if (!WEBHOOK_SECRET.equals(s)) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid secret");
        return;
      }
    }
    JSONObject body = parseBody(request, response);
    if (body == null) return;
    String convId = body.optString("conversationId", "");
    if (convId.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "conversationId required");
      return;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation SET human_takeover = true WHERE id = ?")) {
          ps.setString(1, convId);
          if (ps.executeUpdate() == 0) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, "Conversation not found");
            return;
          }
        }
        log.info("Human takeover set for conversation {}", convId);
        writeJson(response, HttpServletResponse.SC_OK, new JSONObject().put(FIELD_STATUS, "ok"));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error in set-human-takeover", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  private void handleResetHumanTakeover(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!WEBHOOK_SECRET.isEmpty()) {
      String s = request.getHeader("X-Internal-Secret");
      if (!WEBHOOK_SECRET.equals(s)) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid secret");
        return;
      }
    }
    JSONObject body = parseBody(request, response);
    if (body == null) return;
    String convId  = body.optString("conversationId", "");
    String jiraKey = body.optString("jiraTicketKey", "");
    if (convId.isEmpty() && jiraKey.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "conversationId or jiraTicketKey required");
      return;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        int rows;
        if (!convId.isEmpty()) {
          try (PreparedStatement ps = conn.prepareStatement(
              "UPDATE etgo_support_conversation SET human_takeover = false WHERE id = ?")) {
            ps.setString(1, convId);
            rows = ps.executeUpdate();
          }
        } else {
          try (PreparedStatement ps = conn.prepareStatement(
              "UPDATE etgo_support_conversation SET human_takeover = false WHERE jira_ticket_key = ?")) {
            ps.setString(1, jiraKey);
            rows = ps.executeUpdate();
          }
        }
        if (rows == 0) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, "Conversation not found");
          return;
        }
        log.info("Human takeover reset for conversation '{}' / jira '{}'", convId, jiraKey);
        writeJson(response, HttpServletResponse.SC_OK, new JSONObject().put(FIELD_STATUS, "ok"));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error in reset-human-takeover", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  private void handleJiraWebhook(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!WEBHOOK_SECRET.isEmpty()) {
      String s = request.getHeader("X-Webhook-Secret");
      if (!WEBHOOK_SECRET.equals(s)) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid secret");
        return;
      }
    }

    // Try to parse JSON body; if empty (e.g. Jira Automation sends no body), fall back to query params
    String jiraKey   = null;
    String commentId = null;
    String authorEmail = "";
    String authorName  = "Agente de soporte";
    String text        = null;

    JSONObject body = parseBodySilent(request);
    if (body != null && body.length() > 0) {
      // Standard Jira system webhook or manually configured automation body
      JSONObject issue = body.optJSONObject("issue");
      if (issue == null) { writeRaw(response, 200, "{\"status\":\"ignored\"}"); return; }
      jiraKey = issue.optString("key", "");
      if (jiraKey.isEmpty()) { writeRaw(response, 200, "{\"status\":\"ignored\"}"); return; }

      JSONObject comment = body.optJSONObject("comment");
      if (comment == null) {
        // Check for assignee-change-back-to-bot (jira:issue_updated with no comment)
        JSONObject fields = issue.optJSONObject("fields");
        if (fields != null) {
          JSONObject assignee = fields.optJSONObject("assignee");
          String assigneeEmail = assignee != null ? assignee.optString("emailAddress", "") : "";
          if (isBotEmail(assigneeEmail)) {
            handleAssigneeReset(response, jiraKey);
            return;
          }
        }
        // Check for ticket resolved/closed (status transition to Done)
        JSONObject changelog = body.optJSONObject("changelog");
        if (changelog != null) {
          JSONArray items = changelog.optJSONArray("items");
          if (items != null) {
            for (int i = 0; i < items.length(); i++) {
              JSONObject item = items.optJSONObject(i);
              if (item != null && "status".equals(item.optString("field", ""))
                  && "Done".equalsIgnoreCase(item.optString("toString", ""))) {
                handleTicketClosed(response, jiraKey);
                return;
              }
            }
          }
        }
        writeRaw(response, 200, "{\"status\":\"ignored\"}");
        return;
      }

      commentId = comment.optString("id", newId());
      if (!comment.optBoolean("jsdPublic", true)) {
        writeRaw(response, 200, "{\"status\":\"skipped_internal\"}");
        return;
      }
      JSONObject author = comment.optJSONObject("author");
      authorEmail = author != null ? author.optString("emailAddress", "") : "";
      authorName  = author != null ? author.optString("displayName", "Agente de soporte") : "Agente de soporte";
      text = extractAdfText(comment.opt("body")).trim();
    } else {
      // Jira Automation webhook: data comes from query params.
      // Configure automation URL: ...?issueKey={{issue.key}}&commentId={{comment.id}}&authorName={{comment.author.displayName}}&authorEmail={{comment.author.emailAddress}}&commentText={{comment.body}}
      jiraKey     = request.getParameter("issueKey");
      commentId   = request.getParameter("commentId");
      authorName  = nvl(request.getParameter("authorName"), "Agente de soporte");
      authorEmail = nvl(request.getParameter("authorEmail"), "");
      text        = nvl(request.getParameter("commentText"), "");
      if (jiraKey == null || jiraKey.isEmpty()) {
        writeRaw(response, 200, "{\"status\":\"ignored_no_key\"}");
        return;
      }
      // Jira Automation assignee-reset trigger:
      // URL: .../jira-webhook?action=assignee_reset&issueKey={{issue.key}}&authorEmail={{issue.assignee.emailAddress}}
      if ("assignee_reset".equals(nvl(request.getParameter("action"), ""))) {
        if (isBotEmail(authorEmail)) {
          handleAssigneeReset(response, jiraKey);
        } else {
          writeRaw(response, 200, "{\"status\":\"ignored_not_bot\"}");
        }
        return;
      }
      // Jira Automation ticket-closed trigger:
      // URL: .../jira-webhook?action=ticket_closed&issueKey={{issue.key}}
      if ("ticket_closed".equals(nvl(request.getParameter("action"), ""))) {
        handleTicketClosed(response, jiraKey);
        return;
      }
      if (commentId == null) commentId = newId();
    }

    try {

      if (!JIRA_BOT_EMAIL.isEmpty() && JIRA_BOT_EMAIL.equalsIgnoreCase(authorEmail)) {
        writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "skipped_bot"));
        return;
      }

      if (text == null || text.trim().isEmpty()) {
        writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "empty_body"));
        return;
      }
      text = text.trim();

      String externalId = "jira:" + jiraKey + ":" + commentId;

      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        String convId = null;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id FROM etgo_support_conversation WHERE jira_ticket_key = ? LIMIT 1")) {
          ps.setString(1, jiraKey);
          try (ResultSet rs = ps.executeQuery()) { if (rs.next()) convId = rs.getString("id"); }
        }
        if (convId == null) { writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "no_conversation")); return; }

        String ts = Instant.now().toString();
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO etgo_support_message (id, conversation_id, sender, sender_name, text, timestamp, external_id)" +
            " VALUES (?, ?, 'human', ?, ?, ?::timestamptz, ?)" +
            " ON CONFLICT (external_id) WHERE external_id IS NOT NULL DO NOTHING")) {
          ps.setString(1, newId());
          ps.setString(2, convId);
          ps.setString(3, authorName);
          ps.setString(4, text);
          ps.setString(5, ts);
          ps.setString(6, externalId);
          ps.executeUpdate();
        }
        updateConvSummary(conn, convId, text, ts);
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation SET unread = true WHERE id = ?")) {
          ps.setString(1, convId);
          ps.executeUpdate();
        }
        log.info("Jira comment {} ({}) stored in conversation {}", commentId, authorName, convId);
        writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "ok").put("conversationId", convId));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error processing Jira webhook", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  private void handleAssigneeReset(HttpServletResponse response, String jiraKey) throws IOException {
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
        writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "ok").put("jiraTicketKey", jiraKey));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error resetting human takeover for ticket {}", jiraKey, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  private void handleTicketClosed(HttpServletResponse response, String jiraKey) throws IOException {
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        String convId = null;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id FROM etgo_support_conversation WHERE jira_ticket_key = ? LIMIT 1")) {
          ps.setString(1, jiraKey);
          try (ResultSet rs = ps.executeQuery()) { if (rs.next()) convId = rs.getString("id"); }
        }
        if (convId == null) {
          writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "no_conversation"));
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
        writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "ok").put("conversationId", convId));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error closing conversation for ticket {}", jiraKey, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    }
  }

  private boolean isBotEmail(String email) {
    if (email == null || email.isEmpty()) return false;
    return (!JIRA_BOT_EMAIL.isEmpty() && JIRA_BOT_EMAIL.equalsIgnoreCase(email))
        || JIRA_USERNAME.equalsIgnoreCase(email);
  }

  private static String extractAdfText(Object node) {
    if (node == null) return "";
    if (node instanceof String) {
      String s = ((String) node).trim();
      if (s.startsWith("{")) {
        try { return extractAdfText(new JSONObject(s)); } catch (Exception e) { return s; }
      }
      return s;
    }
    if (node instanceof JSONObject) {
      JSONObject obj = (JSONObject) node;
      String type = obj.optString("type", "");
      if ("text".equals(type)) return obj.optString("text", "");
      if ("hardBreak".equals(type)) return "\n";
      StringBuilder sb = new StringBuilder();
      JSONArray content = obj.optJSONArray("content");
      if (content != null) {
        for (int i = 0; i < content.length(); i++) {
          Object child = content.opt(i);
          if (child instanceof JSONObject) sb.append(extractAdfText((JSONObject) child));
        }
      }
      boolean isBlock = "paragraph".equals(type) || "heading".equals(type) ||
          "bulletList".equals(type) || "orderedList".equals(type) ||
          "listItem".equals(type) || "codeBlock".equals(type) || "blockquote".equals(type);
      if (isBlock && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
      return sb.toString();
    }
    return "";
  }

  // --- Schema bootstrap ---

  private void ensureTablesExist() {
    if (TABLES_READY.get()) return;
    synchronized (TABLES_READY) {
      if (TABLES_READY.get()) return;
      try {
        OBContext.setAdminMode(true);
        try {
          Connection conn = OBDal.getInstance().getConnection();
          for (String ddl : new String[]{ DDL_CONVERSATION, DDL_MESSAGE, DDL_IDX_CONV_USER, DDL_IDX_MSG_CONV,
              DDL_MIGRATE_JIRA_KEY, DDL_MIGRATE_JIRA_IDX, DDL_MIGRATE_MSG_EXTID, DDL_MIGRATE_MSG_EXTID_IDX,
              DDL_MIGRATE_HUMAN_TAKEOVER }) {
            try (PreparedStatement ps = conn.prepareStatement(ddl)) {
              ps.execute();
            }
          }
          TABLES_READY.set(true);
          log.info("Support chat tables ready");
        } finally {
          OBContext.restorePreviousMode();
        }
      } catch (Exception e) {
        log.error("Failed to initialize support chat tables — will retry on next request", e);
      }
    }
  }

  // --- DB helpers ---

  private void insertMessage(Connection conn, String id, String convId, String sender,
      String senderName, String text, String timestamp) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO etgo_support_message (id, conversation_id, sender, sender_name, text, timestamp)" +
        " VALUES (?, ?, ?, ?, ?, ?::timestamptz)")) {
      ps.setString(1, id);
      ps.setString(2, convId);
      ps.setString(3, sender);
      ps.setString(4, senderName);
      ps.setString(5, text);
      ps.setString(6, timestamp);
      ps.executeUpdate();
    }
  }

  private void updateConvSummary(Connection conn, String convId, String lastMsg, String ts)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "UPDATE etgo_support_conversation" +
        "   SET last_message = ?, last_activity = ?::timestamptz" +
        " WHERE id = ?")) {
      ps.setString(1, lastMsg.length() > 120 ? lastMsg.substring(0, 120) + "…" : lastMsg);
      ps.setString(2, ts);
      ps.setString(3, convId);
      ps.executeUpdate();
    }
  }

  private boolean conversationBelongsToUser(Connection conn, String convId, String userId)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT 1 FROM etgo_support_conversation WHERE id = ? AND user_id = ?")) {
      ps.setString(1, convId);
      ps.setString(2, userId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private String getConvStatus(Connection conn, String convId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT status FROM etgo_support_conversation WHERE id = ?")) {
      ps.setString(1, convId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString("status") : STATUS_OPEN;
      }
    }
  }

  private JSONObject buildConvSummary(Connection conn, String convId)
      throws SQLException, JSONException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT id, subject, status, last_activity, last_message, unread, rated" +
        "  FROM etgo_support_conversation WHERE id = ?")) {
      ps.setString(1, convId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return new JSONObject();
        JSONObject obj = new JSONObject();
        obj.put("id",           rs.getString("id"));
        obj.put("subject",      rs.getString("subject"));
        obj.put("status",       rs.getString("status"));
        obj.put("lastActivity", toIso(rs.getString("last_activity")));
        obj.put("lastMessage",  rs.getString("last_message") != null ? rs.getString("last_message") : "");
        obj.put("unread",       rs.getBoolean("unread"));
        obj.put("rated",        rs.getBoolean("rated"));
        return obj;
      }
    }
  }

  private JSONArray buildMessageArray(Connection conn, String convId)
      throws SQLException, JSONException {
    JSONArray arr = new JSONArray();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT id, conversation_id, sender, sender_name, text, timestamp" +
        "  FROM etgo_support_message WHERE conversation_id = ? ORDER BY timestamp ASC")) {
      ps.setString(1, convId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject msg = new JSONObject();
          msg.put("id",             rs.getString("id"));
          msg.put("conversationId", rs.getString("conversation_id"));
          msg.put("sender",         rs.getString("sender"));
          msg.put("senderName",     rs.getString("sender_name"));
          msg.put("text",           rs.getString("text"));
          msg.put("timestamp",      toIso(rs.getString("timestamp")));
          arr.put(msg);
        }
      }
    }
    return arr;
  }

  /** Strip timezone offset info that JDBC adds to prevent double-parsing on the frontend. */
  private static String toIso(String ts) {
    if (ts == null) return null;
    // JDBC returns e.g. "2026-06-10 16:00:00.123456-03" — normalise to ISO-8601
    String s = ts.replace(' ', 'T');
    // Truncate microseconds to milliseconds (ECMAScript Date only supports 3 decimal places)
    s = s.replaceAll("(\\.\\d{3})\\d+", "$1");
    // Short offsets like +02 or -03 are missing :MM — add :00
    s = s.replaceAll("([+-]\\d{2})$", "$1:00");
    // Normalise UTC offset to Z
    s = s.replace("+00:00", "Z");
    return s;
  }

  private static String newId() {
    return java.util.UUID.randomUUID().toString().replace("-", "");
  }

  // --- ADK integration ---

  private void createAdkSession(String userId, String sessionId) {
    String url = ADK_BASE_URL + "/apps/" + ADK_APP_NAME + "/users/" + userId + "/sessions/" + sessionId;
    try {
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{}"))
          .timeout(Duration.ofSeconds(10))
          .build();
      HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
      log.debug("ADK session created: {} → {}", sessionId, resp.statusCode());
    } catch (Exception e) {
      log.warn("Failed to create ADK session {}: {}", sessionId, e.getMessage());
    }
  }

  private String sendToAdk(String userId, String sessionId, String text, JSONArray attachments) {
    return sendToAdk(userId, sessionId, text, attachments, null);
  }

  private String sendToAdk(String userId, String sessionId, String text, JSONArray attachments, JSONObject stateDelta) {
    try {
      JSONArray parts = new JSONArray();
      parts.put(new JSONObject().put("text", text));

      if (attachments != null) {
        for (int i = 0; i < attachments.length(); i++) {
          JSONObject att = attachments.optJSONObject(i);
          if (att == null) continue;
          String mimeType = att.optString("mimeType", "application/octet-stream");
          String name = att.optString("name", "archivo");
          String textContent = att.optString("text", "");
          if (!textContent.isEmpty()) {
            parts.put(new JSONObject().put("text",
                "--- Archivo adjunto: " + name + " ---\n" + textContent + "\n---"));
            continue;
          }
          String data = att.optString("data", "");
          if (data.isEmpty()) continue;
          if (mimeType.startsWith("image/") || "application/pdf".equals(mimeType)) {
            parts.put(new JSONObject().put("inlineData",
                new JSONObject().put("mimeType", mimeType).put("data", data)));
          } else {
            parts.put(new JSONObject().put("text", "[Archivo adjunto: " + name + "]"));
          }
        }
      }

      JSONObject newMessage = new JSONObject().put("role", "user").put("parts", parts);
      JSONObject body = new JSONObject()
          .put("appName", ADK_APP_NAME)
          .put("userId",  userId)
          .put("sessionId", sessionId)
          .put("streaming", false)
          .put("newMessage", newMessage);
      if (stateDelta != null) body.put("stateDelta", stateDelta);

      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(ADK_BASE_URL + "/run"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
          .timeout(Duration.ofSeconds(120))
          .build();
      HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        log.warn("ADK /run returned {}: {}", resp.statusCode(), resp.body());
        return null;
      }
      return parseAdkResponse(resp.body());
    } catch (Exception e) {
      log.warn("ADK /run failed: {}", e.getMessage());
      return null;
    }
  }

  private String parseAdkResponse(String json) {
    try {
      JSONArray events = new JSONArray(json);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < events.length(); i++) {
        JSONObject event = events.optJSONObject(i);
        if (event == null || !event.has("content")) continue;
        String author = event.optString("author", "");
        if (author.contains("triage")) continue;
        JSONObject content = event.optJSONObject("content");
        if (content == null || !"model".equals(content.optString("role"))) continue;
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) continue;
        for (int j = 0; j < parts.length(); j++) {
          JSONObject part = parts.optJSONObject(j);
          if (part == null) continue;
          String partText = part.optString("text", "");
          if (!partText.isEmpty()) sb.append(partText);
        }
      }
      String result = sb.toString().trim();
      return result.isEmpty() ? null : result;
    } catch (Exception e) {
      log.warn("Failed to parse ADK response: {}", e.getMessage());
      return null;
    }
  }

  // --- HTTP utilities ---

  /** Parse request body without writing an error response on failure (returns null on parse error). */
  private JSONObject parseBodySilent(HttpServletRequest request) {
    try {
      StringBuilder sb = new StringBuilder();
      try (BufferedReader reader = request.getReader()) {
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
      }
      String s = sb.toString().trim();
      if (s.isEmpty()) return new JSONObject();
      return new JSONObject(s);
    } catch (Exception e) {
      return null;
    }
  }

  private void postJiraComment(String jiraKey, String userMessage) {
    if (jiraKey == null || jiraKey.isEmpty() || JIRA_API_TOKEN.isEmpty()) return;
    try {
      String credentials = java.util.Base64.getEncoder()
          .encodeToString((JIRA_USERNAME + ":" + JIRA_API_TOKEN).getBytes(java.nio.charset.StandardCharsets.UTF_8));

      // Escape message for JSON
      String escaped = userMessage
          .replace("\\", "\\\\")
          .replace("\"", "\\\"")
          .replace("\n", "\\n")
          .replace("\r", "\\r")
          .replace("\t", "\\t");

      // Jira API v3 with ADF body — built as literal string to avoid JSONObject serialization issues
      String payload = "{" +
          "\"body\":{\"type\":\"doc\",\"version\":1,\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + escaped + "\"}]}]}," +
          "\"properties\":[{\"key\":\"sd.public.comment\",\"value\":{\"internal\":true}}]" +
          "}";

      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(JIRA_URL + "/rest/api/3/issue/" + jiraKey + "/comment"))
          .header("Content-Type", "application/json")
          .header("Authorization", "Basic " + credentials)
          .POST(HttpRequest.BodyPublishers.ofString(payload, java.nio.charset.StandardCharsets.UTF_8))
          .timeout(Duration.ofSeconds(10))
          .build();
      HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
        log.info("Jira comment posted to {} ← {}", jiraKey, resp.statusCode());
      } else {
        log.warn("Jira comment FAILED for {} ← {}: {}", jiraKey, resp.statusCode(), resp.body().substring(0, Math.min(200, resp.body().length())));
      }
    } catch (Exception e) {
      log.warn("Failed to post Jira comment to {}: {}", jiraKey, e.getMessage());
    }
  }

  private static String nvl(String value, String fallback) {
    return (value != null && !value.isEmpty()) ? value : fallback;
  }

  private void writeRaw(HttpServletResponse response, int status, String json) throws IOException {
    response.setStatus(status);
    response.setContentType(CONTENT_TYPE_JSON);
    response.setCharacterEncoding(CHARSET_UTF8);
    response.getWriter().write(json);
  }

  private JSONObject parseBody(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) sb.append(line);
    }
    try {
      return new JSONObject(sb.toString());
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
      return null;
    }
  }

  private void writeJson(HttpServletResponse response, int status, JSONObject body)
      throws IOException {
    response.setStatus(status);
    response.setContentType(CONTENT_TYPE_JSON);
    response.setCharacterEncoding(CHARSET_UTF8);
    try (PrintWriter writer = response.getWriter()) {
      writer.write(body.toString());
    }
  }

  private void writeError(HttpServletResponse response, int status, String message)
      throws IOException {
    ProtocolErrorAdapters.writeRestError(response, status, message,
        FIELD_MESSAGE, FIELD_STATUS, FIELD_ERROR);
  }
}
