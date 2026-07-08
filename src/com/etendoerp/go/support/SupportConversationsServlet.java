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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 *
 * Outbound ADK/Jira integration lives in {@link SupportIntegrationClient}; inbound Jira
 * webhook parsing lives in {@link SupportJiraWebhookHandler}. This class owns the HTTP
 * request/response contract and conversation persistence.
 */
public class SupportConversationsServlet extends EtendoGoCorsServlet {

  private static final Logger log = LogManager.getLogger(SupportConversationsServlet.class);

  private static final String CONTENT_TYPE_JSON = "application/json";
  private static final String HEADER_AUTHORIZATION = "Authorization";
  private static final String CHARSET_UTF8      = "UTF-8";
  private static final String FIELD_MESSAGE     = "message";
  private static final String FIELD_MESSAGES    = "messages";
  private static final String FIELD_CONVERSATIONS = "conversations";
  private static final String FIELD_CONVERSATION  = "conversation";
  private static final String FIELD_CONVERSATION_ID = "conversationId";
  private static final String FIELD_STATUS      = "status";
  private static final String FIELD_ERROR       = "error";
  private static final String FIELD_SUBJECT     = "subject";
  private static final String FIELD_LAST_MESSAGE_COL = "last_message";
  private static final String FIELD_UNREAD      = "unread";
  private static final String FIELD_RATED       = "rated";
  private static final String MSG_INTERNAL_ERROR = "Internal error";
  private static final String MSG_CONVERSATION_NOT_FOUND = "Conversation not found";
  private static final String HEADER_INTERNAL_SECRET = "X-Internal-Secret";
  private static final String MSG_INVALID_SECRET = "Invalid secret";

  private static final String SENDER_AI    = "ai";
  private static final String SENDER_USER  = "user";
  private static final String STATUS_OPEN  = "open";
  private static final String STATUS_CLOSED = "closed";
  private static final String AI_AGENT_NAME = "ValerIA";
  private static final String AI_STUB_REPLY =
      "Hola, soy ValerIA. En este momento no puedo conectarme con el servicio de IA. Por favor intenta de nuevo en un momento.";

  private static final String WEBHOOK_SECRET =
      System.getProperty("support.webhook.secret", "");

  // DDL — created once on first request
  private static final AtomicBoolean TABLES_READY = new AtomicBoolean(false);

  // Column types are restricted to what export.database (DDLUtils reverse-engineering) can
  // represent: TIMESTAMP (not TIMESTAMPTZ), CHAR(1) 'Y'/'N' (not native BOOLEAN), NUMERIC
  // (not native INTEGER). Constraint names must fit Oracle's 30-char identifier limit, and
  // "timestamp" is reserved, hence "msg_date" below.
  private static final String DDL_CONVERSATION =
      "CREATE TABLE IF NOT EXISTS etgo_support_conversation (" +
      "  id             VARCHAR(32)  PRIMARY KEY," +
      "  user_id        VARCHAR(255) NOT NULL," +
      "  subject        VARCHAR(255)," +
      "  status         VARCHAR(32)  NOT NULL DEFAULT 'open'," +
      "  created_at     TIMESTAMP    NOT NULL DEFAULT now()," +
      "  last_activity  TIMESTAMP    NOT NULL DEFAULT now()," +
      "  last_message   TEXT," +
      "  unread         CHAR(1)      NOT NULL DEFAULT 'N'," +
      "  rated          CHAR(1)      NOT NULL DEFAULT 'N'," +
      "  rating_score   NUMERIC," +
      "  rating_comment TEXT" +
      ")";

  private static final String DDL_MESSAGE =
      "CREATE TABLE IF NOT EXISTS etgo_support_message (" +
      "  id              VARCHAR(32)  PRIMARY KEY," +
      "  conversation_id VARCHAR(32)  NOT NULL," +
      "  sender          VARCHAR(32)  NOT NULL," +
      "  sender_name     VARCHAR(255)," +
      "  text            TEXT," +
      "  msg_date        TIMESTAMP    NOT NULL DEFAULT now()," +
      "  CONSTRAINT etgo_sc_msg_conv_fk FOREIGN KEY (conversation_id)" +
      "    REFERENCES etgo_support_conversation(id)" +
      ")";

  private static final String DDL_IDX_CONV_USER =
      "CREATE INDEX IF NOT EXISTS etgo_sc_conv_user ON etgo_support_conversation(user_id, last_activity DESC)";

  private static final String DDL_IDX_MSG_CONV =
      "CREATE INDEX IF NOT EXISTS etgo_sc_msg_conv ON etgo_support_message(conversation_id, msg_date ASC)";

  private static final String DDL_MIGRATE_JIRA_KEY =
      "ALTER TABLE etgo_support_conversation ADD COLUMN IF NOT EXISTS jira_ticket_key VARCHAR(64)";
  private static final String DDL_MIGRATE_JIRA_IDX =
      "CREATE INDEX IF NOT EXISTS etgo_sc_conv_jira ON etgo_support_conversation(jira_ticket_key)";
  private static final String DDL_MIGRATE_MSG_EXTID =
      "ALTER TABLE etgo_support_message ADD COLUMN IF NOT EXISTS external_id VARCHAR(128)";
  private static final String DDL_MIGRATE_MSG_EXTID_IDX =
      "CREATE UNIQUE INDEX IF NOT EXISTS etgo_sc_msg_extid ON etgo_support_message(external_id) WHERE external_id IS NOT NULL";
  private static final String DDL_MIGRATE_HUMAN_TAKEOVER =
      "ALTER TABLE etgo_support_conversation ADD COLUMN IF NOT EXISTS human_takeover CHAR(1) NOT NULL DEFAULT 'N'";

  // One-time fixups for local/dev databases that already had the table created by an older
  // version of this class (native TIMESTAMPTZ/BOOLEAN/INTEGER, reserved-word "timestamp"
  // column, auto-named FK over 30 chars) — those broke `export.database`. Idempotent: each
  // branch only fires while the old shape is still present.
  private static final String DDL_MIGRATE_FIX_CONVERSATION_TYPES =
      "DO $mig$ BEGIN" +
      "  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'etgo_support_conversation'" +
      "      AND column_name = 'unread' AND data_type = 'boolean') THEN" +
      "    ALTER TABLE etgo_support_conversation ALTER COLUMN unread DROP DEFAULT;" +
      "    ALTER TABLE etgo_support_conversation ALTER COLUMN unread TYPE CHAR(1)" +
      "      USING (CASE WHEN unread THEN 'Y' ELSE 'N' END);" +
      "    ALTER TABLE etgo_support_conversation ALTER COLUMN unread SET DEFAULT 'N';" +
      "  END IF;" +
      "  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'etgo_support_conversation'" +
      "      AND column_name = 'rated' AND data_type = 'boolean') THEN" +
      "    ALTER TABLE etgo_support_conversation ALTER COLUMN rated DROP DEFAULT;" +
      "    ALTER TABLE etgo_support_conversation ALTER COLUMN rated TYPE CHAR(1)" +
      "      USING (CASE WHEN rated THEN 'Y' ELSE 'N' END);" +
      "    ALTER TABLE etgo_support_conversation ALTER COLUMN rated SET DEFAULT 'N';" +
      "  END IF;" +
      "  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'etgo_support_conversation'" +
      "      AND column_name = 'human_takeover' AND data_type = 'boolean') THEN" +
      "    ALTER TABLE etgo_support_conversation ALTER COLUMN human_takeover DROP DEFAULT;" +
      "    ALTER TABLE etgo_support_conversation ALTER COLUMN human_takeover TYPE CHAR(1)" +
      "      USING (CASE WHEN human_takeover THEN 'Y' ELSE 'N' END);" +
      "    ALTER TABLE etgo_support_conversation ALTER COLUMN human_takeover SET DEFAULT 'N';" +
      "  END IF;" +
      "  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'etgo_support_conversation'" +
      "      AND column_name = 'created_at' AND data_type = 'timestamp with time zone') THEN" +
      "    ALTER TABLE etgo_support_conversation ALTER COLUMN created_at TYPE TIMESTAMP;" +
      "  END IF;" +
      "  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'etgo_support_conversation'" +
      "      AND column_name = 'last_activity' AND data_type = 'timestamp with time zone') THEN" +
      "    ALTER TABLE etgo_support_conversation ALTER COLUMN last_activity TYPE TIMESTAMP;" +
      "  END IF;" +
      "  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'etgo_support_conversation'" +
      "      AND column_name = 'rating_score' AND data_type = 'integer') THEN" +
      "    ALTER TABLE etgo_support_conversation ALTER COLUMN rating_score TYPE NUMERIC;" +
      "  END IF;" +
      "END $mig$";

  private static final String DDL_MIGRATE_FIX_MESSAGE_TYPES =
      "DO $mig$ BEGIN" +
      "  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'etgo_support_message'" +
      "      AND column_name = 'timestamp') THEN" +
      "    ALTER TABLE etgo_support_message RENAME COLUMN \"timestamp\" TO msg_date;" +
      "  END IF;" +
      "  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'etgo_support_message'" +
      "      AND column_name = 'msg_date' AND data_type = 'timestamp with time zone') THEN" +
      "    ALTER TABLE etgo_support_message ALTER COLUMN msg_date TYPE TIMESTAMP;" +
      "  END IF;" +
      "  IF EXISTS (SELECT 1 FROM pg_constraint" +
      "      WHERE conname = 'etgo_support_message_conversation_id_fkey') THEN" +
      "    ALTER TABLE etgo_support_message DROP CONSTRAINT etgo_support_message_conversation_id_fkey;" +
      "    ALTER TABLE etgo_support_message ADD CONSTRAINT etgo_sc_msg_conv_fk" +
      "      FOREIGN KEY (conversation_id) REFERENCES etgo_support_conversation(id);" +
      "  END IF;" +
      "END $mig$";

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
    if (parts.length == 4 && FIELD_CONVERSATIONS.equals(parts[1]) && FIELD_MESSAGES.equals(parts[3])) {
      handleGetMessages(response, userId, parts[2]);
      return;
    }

    writeError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + pathInfo);
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String pathInfo = request.getPathInfo();
    if (pathInfo == null) pathInfo = "/";

    if (dispatchInternalRoute(pathInfo, request, response)) return;

    String userId = authenticateAndGetUserId(request, response);
    if (userId == null) return;
    ensureTablesExist();

    if ("/conversations".equals(pathInfo) || "/conversations/".equals(pathInfo)) {
      handleCreateConversation(request, response, userId);
      return;
    }

    if (dispatchConversationAction(pathInfo, request, response, userId)) return;

    writeError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + pathInfo);
  }

  /** Unauthenticated internal / webhook endpoints. Returns true if the request was handled. */
  private boolean dispatchInternalRoute(String pathInfo, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if ("/jira-webhook".equals(pathInfo)) {
      ensureTablesExist();
      SupportJiraWebhookHandler.handle(request, response);
      return true;
    }
    if ("/internal/set-ticket".equals(pathInfo)) {
      ensureTablesExist();
      handleSetTicket(request, response);
      return true;
    }
    if ("/internal/set-human-takeover".equals(pathInfo)) {
      ensureTablesExist();
      handleSetHumanTakeover(request, response);
      return true;
    }
    if ("/internal/reset-human-takeover".equals(pathInfo)) {
      ensureTablesExist();
      handleResetHumanTakeover(request, response);
      return true;
    }
    return false;
  }

  /** Routes /conversations/:id/{messages,rating,close,reopen}. Returns true if handled. */
  private boolean dispatchConversationAction(String pathInfo, HttpServletRequest request,
      HttpServletResponse response, String userId) throws IOException {
    String[] parts = pathInfo.split("/");
    if (parts.length < 4 || !FIELD_CONVERSATIONS.equals(parts[1])) return false;
    String convId = parts[2];
    String action = parts[3];
    if (FIELD_MESSAGES.equals(action)) {
      handleSendMessage(request, response, userId, convId);
      return true;
    }
    if ("rating".equals(action)) {
      handleSubmitRating(request, response, userId, convId);
      return true;
    }
    if ("close".equals(action)) {
      handleCloseConversation(response, userId, convId);
      return true;
    }
    if ("reopen".equals(action)) {
      handleReopenConversation(response, userId, convId);
      return true;
    }
    return false;
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
              conv.put(FIELD_SUBJECT,  rs.getString(FIELD_SUBJECT));
              conv.put(FIELD_STATUS,   rs.getString(FIELD_STATUS));
              conv.put("lastActivity", toIso(rs.getString("last_activity")));
              conv.put("lastMessage",  rs.getString(FIELD_LAST_MESSAGE_COL) != null ? rs.getString(FIELD_LAST_MESSAGE_COL) : "");
              conv.put(FIELD_UNREAD,   isY(rs.getString(FIELD_UNREAD)));
              conv.put(FIELD_RATED,    isY(rs.getString(FIELD_RATED)));
              arr.put(conv);
            }
          }
        }
        JSONObject result = new JSONObject();
        result.put(FIELD_CONVERSATIONS, arr);
        writeJson(response, HttpServletResponse.SC_OK, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error listing conversations for user {}", userId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
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
            " VALUES (?, ?, ?, 'open', ?::timestamp, ?::timestamp, 'N', 'N')")) {
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
        String locale = body.optString("locale", "es");
        String userEmail = SupportIntegrationClient.getUserEmail(conn, userId);
        SupportIntegrationClient.createAdkSession(userId, convId, locale, userEmail);
        String aiReplyText = SupportIntegrationClient.sendToAdk(userId, convId, firstMessage, attachments);
        if (aiReplyText == null) aiReplyText = AI_STUB_REPLY;

        String msgId2    = newId();
        String aiNow     = Instant.now().toString();
        insertMessage(conn, msgId2, convId, SENDER_AI, AI_AGENT_NAME, aiReplyText, aiNow);

        // Update conversation summary
        updateConvSummary(conn, convId, aiReplyText, aiNow);

        JSONObject result = new JSONObject();
        result.put(FIELD_CONVERSATION, buildConvSummary(conn, convId));
        result.put(FIELD_MESSAGES, buildMessageArray(conn, convId));
        writeJson(response, HttpServletResponse.SC_CREATED, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error creating conversation for user {}", userId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    }
  }

  private void handleGetMessages(HttpServletResponse response, String userId, String convId)
      throws IOException {
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        if (!conversationBelongsToUser(conn, convId, userId)) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
          return;
        }
        // Mark as read
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation SET unread = 'N' WHERE id = ?")) {
          ps.setString(1, convId);
          ps.executeUpdate();
        }
        JSONObject result = new JSONObject();
        result.put(FIELD_MESSAGES, buildMessageArray(conn, convId));
        writeJson(response, HttpServletResponse.SC_OK, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error loading messages for conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
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
          writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
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
            if (rs.next()) humanTakeover = isY(rs.getString("human_takeover"));
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
          new Thread(() -> SupportIntegrationClient.postJiraComment(finalKey, finalText), "jira-comment").start();
          JSONObject result = new JSONObject();
          result.put(FIELD_MESSAGES,     buildMessageArray(conn, convId));
          result.put(FIELD_CONVERSATION, buildConvSummary(conn, convId));
          writeJson(response, HttpServletResponse.SC_OK, result);
          return;
        }

        JSONArray attachments = body.optJSONArray("attachments");
        // Sync human_takeover=false into ADK session state; flag tells agent to skip Jira re-check
        JSONObject stateDelta = new JSONObject()
            .put("human_takeover", false)
            .put("human_takeover_synced", true);
        String aiReplyText = SupportIntegrationClient.sendToAdk(userId, convId, text, attachments, stateDelta);
        if (aiReplyText == null) aiReplyText = AI_STUB_REPLY;

        String aiNow = Instant.now().toString();
        insertMessage(conn, newId(), convId, SENDER_AI, AI_AGENT_NAME, aiReplyText, aiNow);
        updateConvSummary(conn, convId, aiReplyText, aiNow);

        JSONObject result = new JSONObject();
        result.put(FIELD_MESSAGES,     buildMessageArray(conn, convId));
        result.put(FIELD_CONVERSATION, buildConvSummary(conn, convId));
        writeJson(response, HttpServletResponse.SC_OK, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error sending message to conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
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
          writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
          return;
        }
        String comment = body.optString("comment", "").trim();
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation" +
            "   SET rated = 'Y', rating_score = ?, rating_comment = ?" +
            " WHERE id = ?")) {
          ps.setInt(1, score);
          ps.setString(2, comment);
          ps.setString(3, convId);
          ps.executeUpdate();
        }

        String jiraKey = null;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT jira_ticket_key FROM etgo_support_conversation WHERE id = ?")) {
          ps.setString(1, convId);
          try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) jiraKey = rs.getString("jira_ticket_key");
          }
        }
        final String finalJiraKey = jiraKey;
        final int finalScore = score;
        final String finalComment = comment;
        new Thread(() -> {
          SupportIntegrationClient.postJiraComment(finalJiraKey,
              SupportIntegrationClient.buildFeedbackComment(finalScore, finalComment));
          SupportIntegrationClient.postJiraCsatLabel(finalJiraKey, finalScore);
        }, "jira-csat-feedback").start();

        JSONObject result = new JSONObject();
        result.put(FIELD_STATUS, "success");
        writeJson(response, HttpServletResponse.SC_OK, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error submitting rating for conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    }
  }

  private void handleCloseConversation(HttpServletResponse response, String userId, String convId)
      throws IOException {
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        if (!conversationBelongsToUser(conn, convId, userId)) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
          return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation SET status = 'closed' WHERE id = ?")) {
          ps.setString(1, convId);
          ps.executeUpdate();
        }
        JSONObject result = new JSONObject();
        result.put(FIELD_CONVERSATION, buildConvSummary(conn, convId));
        writeJson(response, HttpServletResponse.SC_OK, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error closing conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    }
  }

  private void handleReopenConversation(HttpServletResponse response, String userId, String convId)
      throws IOException {
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        if (!conversationBelongsToUser(conn, convId, userId)) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
          return;
        }
        String now = Instant.now().toString();
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation SET status = 'open', last_activity = ?::timestamp WHERE id = ?")) {
          ps.setString(1, now);
          ps.setString(2, convId);
          ps.executeUpdate();
        }
        // System message to mark reopen in the thread
        insertMessage(conn, newId(), convId, SENDER_AI, AI_AGENT_NAME,
            "La conversación ha sido reabierta. ¿En qué más puedo ayudarte?", now);
        JSONObject result = new JSONObject();
        result.put(FIELD_CONVERSATION, buildConvSummary(conn, convId));
        result.put(FIELD_MESSAGES, buildMessageArray(conn, convId));
        writeJson(response, HttpServletResponse.SC_OK, result);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error reopening conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    }
  }

  // --- Auth ---

  private String authenticateAndGetUserId(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String authHeader = request.getHeader(HEADER_AUTHORIZATION);
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

  // --- Internal webhook endpoints (ticket linking / human takeover) ---

  private static boolean isInvalidWebhookSecret(HttpServletRequest request) {
    return !WEBHOOK_SECRET.isEmpty() && !WEBHOOK_SECRET.equals(request.getHeader(HEADER_INTERNAL_SECRET));
  }

  private void handleSetTicket(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (isInvalidWebhookSecret(request)) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, MSG_INVALID_SECRET);
      return;
    }
    JSONObject body = parseBody(request, response);
    if (body == null) return;
    String convId      = body.optString(FIELD_CONVERSATION_ID, "");
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
            writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
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
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    }
  }

  private void handleSetHumanTakeover(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (isInvalidWebhookSecret(request)) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, MSG_INVALID_SECRET);
      return;
    }
    JSONObject body = parseBody(request, response);
    if (body == null) return;
    String convId = body.optString(FIELD_CONVERSATION_ID, "");
    if (convId.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "conversationId required");
      return;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Connection conn = OBDal.getInstance().getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE etgo_support_conversation SET human_takeover = 'Y' WHERE id = ?")) {
          ps.setString(1, convId);
          if (ps.executeUpdate() == 0) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
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
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    }
  }

  private void handleResetHumanTakeover(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (isInvalidWebhookSecret(request)) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, MSG_INVALID_SECRET);
      return;
    }
    JSONObject body = parseBody(request, response);
    if (body == null) return;
    String convId  = body.optString(FIELD_CONVERSATION_ID, "");
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
              "UPDATE etgo_support_conversation SET human_takeover = 'N' WHERE id = ?")) {
            ps.setString(1, convId);
            rows = ps.executeUpdate();
          }
        } else {
          try (PreparedStatement ps = conn.prepareStatement(
              "UPDATE etgo_support_conversation SET human_takeover = 'N' WHERE jira_ticket_key = ?")) {
            ps.setString(1, jiraKey);
            rows = ps.executeUpdate();
          }
        }
        if (rows == 0) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
          return;
        }
        log.info("Human takeover reset for conversation '{}' / jira '{}'", convId, jiraKey);
        writeJson(response, HttpServletResponse.SC_OK, new JSONObject().put(FIELD_STATUS, "ok"));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error in reset-human-takeover", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    }
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
              DDL_MIGRATE_HUMAN_TAKEOVER, DDL_MIGRATE_FIX_CONVERSATION_TYPES, DDL_MIGRATE_FIX_MESSAGE_TYPES }) {
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
        "INSERT INTO etgo_support_message (id, conversation_id, sender, sender_name, text, msg_date)" +
        " VALUES (?, ?, ?, ?, ?, ?::timestamp)")) {
      ps.setString(1, id);
      ps.setString(2, convId);
      ps.setString(3, sender);
      ps.setString(4, senderName);
      ps.setString(5, text);
      ps.setString(6, timestamp);
      ps.executeUpdate();
    }
  }

  /** Package-private: also used by {@link SupportJiraWebhookHandler} when storing an inbound Jira comment. */
  static void updateConvSummary(Connection conn, String convId, String lastMsg, String ts)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "UPDATE etgo_support_conversation" +
        "   SET last_message = ?, last_activity = ?::timestamp" +
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
        return rs.next() ? rs.getString(FIELD_STATUS) : STATUS_OPEN;
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
        obj.put(FIELD_SUBJECT,  rs.getString(FIELD_SUBJECT));
        obj.put(FIELD_STATUS,   rs.getString(FIELD_STATUS));
        obj.put("lastActivity", toIso(rs.getString("last_activity")));
        obj.put("lastMessage",  rs.getString(FIELD_LAST_MESSAGE_COL) != null ? rs.getString(FIELD_LAST_MESSAGE_COL) : "");
        obj.put(FIELD_UNREAD,   isY(rs.getString(FIELD_UNREAD)));
        obj.put(FIELD_RATED,    isY(rs.getString(FIELD_RATED)));
        return obj;
      }
    }
  }

  private JSONArray buildMessageArray(Connection conn, String convId)
      throws SQLException, JSONException {
    JSONArray arr = new JSONArray();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT id, conversation_id, sender, sender_name, text, msg_date" +
        "  FROM etgo_support_message WHERE conversation_id = ? ORDER BY msg_date ASC")) {
      ps.setString(1, convId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject msg = new JSONObject();
          msg.put("id",             rs.getString("id"));
          msg.put(FIELD_CONVERSATION_ID, rs.getString("conversation_id"));
          msg.put("sender",         rs.getString("sender"));
          msg.put("senderName",     rs.getString("sender_name"));
          msg.put("text",           rs.getString("text"));
          msg.put("timestamp",      toIso(rs.getString("msg_date")));
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

  /** Reads an Etendo-style CHAR(1) 'Y'/'N' boolean column. */
  private static boolean isY(String flag) {
    return "Y".equals(flag);
  }

  /** Package-private: also used by {@link SupportJiraWebhookHandler} to mint message/comment ids. */
  static String newId() {
    return java.util.UUID.randomUUID().toString().replace("-", "");
  }

  // --- HTTP utilities ---

  /** Parse request body without writing an error response on failure (returns null on parse error). */
  static JSONObject parseBodySilent(HttpServletRequest request) {
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

  /** Package-private: also used by {@link SupportJiraWebhookHandler} to write raw webhook acks. */
  static void writeRaw(HttpServletResponse response, int status, String json) throws IOException {
    response.setStatus(status);
    response.setContentType(CONTENT_TYPE_JSON);
    response.setCharacterEncoding(CHARSET_UTF8);
    response.getWriter().write(json);
  }

  /** Package-private: also used by {@link SupportJiraWebhookHandler}. */
  static void writeJson(HttpServletResponse response, int status, JSONObject body)
      throws IOException {
    response.setStatus(status);
    response.setContentType(CONTENT_TYPE_JSON);
    response.setCharacterEncoding(CHARSET_UTF8);
    try (PrintWriter writer = response.getWriter()) {
      writer.write(body.toString());
    }
  }

  /** Package-private: also used by {@link SupportJiraWebhookHandler}. */
  static void writeError(HttpServletResponse response, int status, String message)
      throws IOException {
    ProtocolErrorAdapters.writeRestError(response, status, message,
        FIELD_MESSAGE, FIELD_STATUS, FIELD_ERROR);
  }
}
