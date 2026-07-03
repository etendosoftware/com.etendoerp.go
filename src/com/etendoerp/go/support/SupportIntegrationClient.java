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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Outbound integrations for the support chat: the ADK (ValerIA) agent runtime and the
 * Jira REST API. Kept separate from {@link SupportConversationsServlet} — that class owns
 * the HTTP request/response contract and conversation persistence, this one owns talking
 * to external services.
 */
final class SupportIntegrationClient {

  private static final Logger log = LogManager.getLogger(SupportIntegrationClient.class);

  private static final String HEADER_CONTENT_TYPE = "Content-Type";
  private static final String HEADER_AUTHORIZATION = "Authorization";
  private static final String CONTENT_TYPE_JSON = "application/json";

  private static final String ADK_BASE_URL =
      System.getProperty("support.adk.url", "http://localhost:8000");
  private static final String ADK_APP_NAME = "agent";

  private static final String JIRA_URL =
      System.getProperty("support.jira.url", "https://etendoproject.atlassian.net");
  private static final String JIRA_USERNAME =
      System.getProperty("support.jira.username", "info@smfconsulting.es");
  private static final String JIRA_API_TOKEN =
      System.getProperty("support.jira.token", "");

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  private SupportIntegrationClient() {
  }

  // --- User lookup ---

  /**
   * ad_user.email is blank for self-service/portal accounts (username IS the email
   * there, e.g. GOuser-style logins) and for generic seed accounts (admin, goadmin).
   * Fall back to username when it looks like an email so those tickets still carry
   * a real reporter instead of silently dropping to the Jira default reporter.
   */
  static String getUserEmail(Connection conn, String userId) {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT email, username FROM ad_user WHERE ad_user_id = ?")) {
      ps.setString(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          String email = rs.getString("email");
          if (email != null && !email.isEmpty()) return email;
          String username = rs.getString("username");
          if (username != null && username.contains("@")) return username;
        }
      }
    } catch (SQLException e) {
      log.warn("Failed to look up email for user {}: {}", userId, e.getMessage());
    }
    return null;
  }

  // --- ADK session / messaging ---

  static void createAdkSession(String userId, String sessionId, String locale, String userEmail) {
    String url = ADK_BASE_URL + "/apps/" + ADK_APP_NAME + "/users/" + userId + "/sessions/" + sessionId;
    try {
      // The body IS the initial state dict directly — NOT wrapped in a "state" key.
      // Seeding it here (once, at session creation) is the only reliable channel:
      // stateDelta on POST /run does not propagate to callback state in this ADK
      // version (verified — human_takeover_synced has the same latent gap). This is
      // also how the Jira ticket's Reporter ends up being the real end user instead
      // of the default reporter fallback (jira_client.py's raiseOnBehalfOf reads
      // state["user_email"]).
      JSONObject state = new JSONObject().put("locale", locale);
      if (userEmail != null && !userEmail.isEmpty()) {
        state.put("user_email", userEmail);
      }
      String body = state.toString();
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
          .timeout(Duration.ofSeconds(10))
          .build();
      HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
      log.debug("ADK session created: {} (locale={}, user_email={}) → {}", sessionId, locale, userEmail,
          resp.statusCode());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Failed to create ADK session {}: {}", sessionId, e.getMessage());
    } catch (Exception e) {
      log.warn("Failed to create ADK session {}: {}", sessionId, e.getMessage());
    }
  }

  static String sendToAdk(String userId, String sessionId, String text, JSONArray attachments) {
    return sendToAdk(userId, sessionId, text, attachments, null);
  }

  static String sendToAdk(String userId, String sessionId, String text, JSONArray attachments,
      JSONObject stateDelta) {
    try {
      JSONArray parts = new JSONArray();
      parts.put(new JSONObject().put("text", text));
      appendAttachmentParts(parts, attachments);

      JSONObject newMessage = new JSONObject().put("role", "user").put("parts", parts);
      JSONObject body = new JSONObject()
          .put("appName", ADK_APP_NAME)
          .put("userId", userId)
          .put("sessionId", sessionId)
          .put("streaming", false)
          .put("newMessage", newMessage);
      if (stateDelta != null) body.put("stateDelta", stateDelta);

      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(ADK_BASE_URL + "/run"))
          .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
          .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
          .timeout(Duration.ofSeconds(120))
          .build();
      HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        log.warn("ADK /run returned {}: {}", resp.statusCode(), resp.body());
        return null;
      }
      return parseAdkResponse(resp.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("ADK /run failed: {}", e.getMessage());
      return null;
    } catch (Exception e) {
      log.warn("ADK /run failed: {}", e.getMessage());
      return null;
    }
  }

  private static void appendAttachmentParts(JSONArray parts, JSONArray attachments) throws JSONException {
    if (attachments == null) return;
    for (int i = 0; i < attachments.length(); i++) {
      JSONObject att = attachments.optJSONObject(i);
      if (att == null) continue;
      appendSingleAttachmentPart(parts, att);
    }
  }

  private static void appendSingleAttachmentPart(JSONArray parts, JSONObject att) throws JSONException {
    String mimeType = att.optString("mimeType", "application/octet-stream");
    String name = att.optString("name", "archivo");
    String textContent = att.optString("text", "");
    if (!textContent.isEmpty()) {
      parts.put(new JSONObject().put("text",
          "--- Archivo adjunto: " + name + " ---\n" + textContent + "\n---"));
      return;
    }
    String data = att.optString("data", "");
    if (data.isEmpty()) return;
    if (mimeType.startsWith("image/") || "application/pdf".equals(mimeType)) {
      parts.put(new JSONObject().put("inlineData",
          new JSONObject().put("mimeType", mimeType).put("data", data)));
    } else {
      parts.put(new JSONObject().put("text", "[Archivo adjunto: " + name + "]"));
    }
  }

  private static String parseAdkResponse(String json) {
    try {
      JSONArray events = new JSONArray(json);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < events.length(); i++) {
        appendEventText(sb, events.optJSONObject(i));
      }
      String result = sb.toString().trim();
      return result.isEmpty() ? null : result;
    } catch (Exception e) {
      log.warn("Failed to parse ADK response: {}", e.getMessage());
      return null;
    }
  }

  private static void appendEventText(StringBuilder sb, JSONObject event) {
    if (event == null || !event.has("content")) return;
    String author = event.optString("author", "");
    if (author.contains("triage")) return;
    JSONObject content = event.optJSONObject("content");
    if (content == null || !"model".equals(content.optString("role"))) return;
    JSONArray parts = content.optJSONArray("parts");
    if (parts == null) return;
    for (int j = 0; j < parts.length(); j++) {
      JSONObject part = parts.optJSONObject(j);
      if (part == null) continue;
      String partText = part.optString("text", "");
      if (!partText.isEmpty()) sb.append(partText);
    }
  }

  // --- Jira comment posting ---

  static void postJiraComment(String jiraKey, String userMessage) {
    if (jiraKey == null || jiraKey.isEmpty() || JIRA_API_TOKEN.isEmpty()) return;
    try {
      String credentials = Base64.getEncoder()
          .encodeToString((JIRA_USERNAME + ":" + JIRA_API_TOKEN).getBytes(StandardCharsets.UTF_8));

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
          .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
          .header(HEADER_AUTHORIZATION, "Basic " + credentials)
          .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
          .timeout(Duration.ofSeconds(10))
          .build();
      HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
        log.info("Jira comment posted to {} ← {}", jiraKey, resp.statusCode());
      } else {
        log.warn("Jira comment FAILED for {} ← {}: {}", jiraKey, resp.statusCode(),
            resp.body().substring(0, Math.min(200, resp.body().length())));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Failed to post Jira comment to {}: {}", jiraKey, e.getMessage());
    } catch (Exception e) {
      log.warn("Failed to post Jira comment to {}: {}", jiraKey, e.getMessage());
    }
  }

  static String buildFeedbackComment(int score, String comment) {
    StringBuilder sb = new StringBuilder("⭐ Valoración de satisfacción: ").append(score).append("/5");
    if (comment != null && !comment.isEmpty()) {
      sb.append("\n\nComentario del cliente: ").append(comment);
    }
    return sb.toString();
  }

  // The native JSM CSAT feedback endpoint (POST .../request/{key}/feedback) requires the
  // caller to be the ticket's reporter — a service account is always rejected with 403.
  // A label is a reliable stand-in: filterable via JQL (labels = "csat-4") and only needs
  // ordinary edit-issue permission, which the service account already has.
  static void postJiraCsatLabel(String jiraKey, int score) {
    if (jiraKey == null || jiraKey.isEmpty() || JIRA_API_TOKEN.isEmpty()) return;
    try {
      String credentials = Base64.getEncoder()
          .encodeToString((JIRA_USERNAME + ":" + JIRA_API_TOKEN).getBytes(StandardCharsets.UTF_8));

      String payload = "{\"update\":{\"labels\":[{\"add\":\"csat-" + score + "\"}]}}";

      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(JIRA_URL + "/rest/api/3/issue/" + jiraKey))
          .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
          .header(HEADER_AUTHORIZATION, "Basic " + credentials)
          .method("PUT", HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
          .timeout(Duration.ofSeconds(10))
          .build();
      HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
        log.info("Jira CSAT label 'csat-{}' added to {} ← {}", score, jiraKey, resp.statusCode());
      } else {
        log.warn("Jira CSAT label FAILED for {} ← {}: {}", jiraKey, resp.statusCode(),
            resp.body().substring(0, Math.min(200, resp.body().length())));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Failed to add Jira CSAT label to {}: {}", jiraKey, e.getMessage());
    } catch (Exception e) {
      log.warn("Failed to add Jira CSAT label to {}: {}", jiraKey, e.getMessage());
    }
  }
}
