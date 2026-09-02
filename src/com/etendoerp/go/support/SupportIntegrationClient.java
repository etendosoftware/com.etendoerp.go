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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.common.ConfigPropertyReader;

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
  private static final String JIRA_ISSUE_PATH = "/rest/api/3/issue/";
  private static final String AUTH_BASIC_PREFIX = "Basic ";

  // Attachment mime types eligible to be forwarded to the ADK model as real inlineData
  // (as opposed to a text placeholder). Scope is images and documents only — no
  // audio/video — matching the frontend file-picker's accept list.
  private static final String MIME_TYPE_IMAGE_PREFIX = "image/";
  private static final String MIME_TYPE_PDF = "application/pdf";
  private static final String MIME_TYPE_DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  private static final String MIME_TYPE_XLSX =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final String FIELD_MIME_TYPE = "mimeType";
  private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

  private static final String ADK_BASE_URL = ConfigPropertyReader.readConfigValue(
      "support.adk.url", "ETGO_SUPPORT_ADK_URL", "");
  private static final String ADK_APP_NAME = "agent";

  /** Zero-width-prefixed marker appended to a reply's text when the ADK's response for that
   * turn set {@code pending_escalation=confirm} — i.e. ValerIA just offered to escalate to a
   * human. Persisted as part of the message text; the frontend strips it before rendering and
   * shows a one-click "talk to a human" button on that message instead. */
  static final String SUGGESTS_ESCALATION_MARKER = "\u200B##SUGGESTS_ESCALATION##";

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
  static String getUserEmail(String userId) {
    User user = OBDal.getInstance().get(User.class, userId);
    if (user == null) return null;
    String email = user.getEmail();
    if (email != null && !email.isEmpty()) return email;
    String username = user.getUsername();
    if (username != null && username.contains("@")) return username;
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
      String replyText = parseAdkResponse(resp.body());
      if (replyText != null && responseSuggestsEscalation(resp.body())) {
        replyText += SUGGESTS_ESCALATION_MARKER;
      }
      return replyText;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("ADK /run failed: {}", e.getMessage());
      return null;
    } catch (Exception e) {
      log.warn("ADK /run failed: {}", e.getMessage());
      return null;
    }
  }

  static void appendAttachmentParts(JSONArray parts, JSONArray attachments) throws JSONException {
    if (attachments == null) return;
    for (int i = 0; i < attachments.length(); i++) {
      JSONObject att = attachments.optJSONObject(i);
      if (att == null) continue;
      appendSingleAttachmentPart(parts, att);
    }
  }

  static void appendSingleAttachmentPart(JSONArray parts, JSONObject att) throws JSONException {
    String mimeType = att.optString(FIELD_MIME_TYPE, DEFAULT_MIME_TYPE);
    String name = att.optString("name", "archivo");
    String textContent = att.optString("text", "");
    String data = att.optString("data", "");

    if (!textContent.isEmpty()) {
      parts.put(new JSONObject().put("text",
          "--- Archivo adjunto: " + name + " ---\n" + textContent + "\n---"));
      // Text-file attachments (CSV/TXT) may also carry the raw bytes alongside the
      // inlined text so the ADK side can extract and upload the real file to Jira.
      // Older/cached frontend builds may still send text-only — guard on data presence.
      if (!data.isEmpty()) {
        parts.put(new JSONObject().put("inlineData",
            new JSONObject().put(FIELD_MIME_TYPE, mimeType).put("data", data)));
      }
      return;
    }

    if (data.isEmpty()) return;
    if (isInlineableMimeType(mimeType)) {
      parts.put(new JSONObject().put("inlineData",
          new JSONObject().put(FIELD_MIME_TYPE, mimeType).put("data", data)));
    } else {
      // Defensive/system-boundary fallback: the payload is client-controlled, so a
      // stale client or a direct API call could still send audio/video/anything else.
      parts.put(new JSONObject().put("text", "[Archivo adjunto: " + name + "]"));
    }
  }

  /**
   * Attachments and documents allow-listed for forwarding as real inlineData to the
   * ADK model: images and documents (PDF, DOCX, XLSX) — no audio, no video. Matches
   * the frontend file-picker's {@code accept="image/*,.pdf,.csv,.txt,.xlsx,.docx"}.
   * CSV/TXT are handled separately in {@link #appendSingleAttachmentPart} since they
   * always carry a {@code text} field and are inlined regardless of this allow-list.
   */
  private static boolean isInlineableMimeType(String mimeType) {
    return mimeType.startsWith(MIME_TYPE_IMAGE_PREFIX)
        || MIME_TYPE_PDF.equals(mimeType)
        || MIME_TYPE_DOCX.equals(mimeType)
        || MIME_TYPE_XLSX.equals(mimeType);
  }

  static String parseAdkResponse(String json) {
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

  /** True if any event in the turn's raw ADK response set {@code pending_escalation=confirm}
   * in its {@code actions.stateDelta} — the ADK's signal that this reply just offered to
   * escalate to a human and is waiting on the user's confirmation. */
  static boolean responseSuggestsEscalation(String json) {
    try {
      JSONArray events = new JSONArray(json);
      for (int i = 0; i < events.length(); i++) {
        JSONObject event = events.optJSONObject(i);
        if (event == null) continue;
        JSONObject actions = event.optJSONObject("actions");
        JSONObject stateDelta = actions != null ? actions.optJSONObject("stateDelta") : null;
        if (stateDelta != null && "confirm".equals(stateDelta.optString("pending_escalation", null))) {
          return true;
        }
      }
    } catch (Exception e) {
      log.warn("Failed to scan ADK response for escalation signal: {}", e.getMessage());
    }
    return false;
  }

  static void appendEventText(StringBuilder sb, JSONObject event) {
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

  static void postJiraComment(String jiraKey, String userMessage, boolean internal) {
    if (jiraKey == null || jiraKey.isEmpty()) return;
    JiraConfig config = JiraConfig.fromRuntime();
    if (!config.isConfigured()) {
      log.warn("Jira comment for {} NOT sent: Jira integration is not configured "
          + "(support.jira.url/username/token)", jiraKey);
      return;
    }
    // Jira rejects a blank comment body with 400 ("Comment body can not be empty!") — an
    // attachment-only message (no text, e.g. the user just drops a file while the ticket is
    // already human-escalated) would otherwise silently vanish: never reaching Jira and never
    // getting any reply back to the chat. The caller should already pass a descriptive fallback
    // (see describeAttachments) when there is no text, but this is the last line of defense.
    if (userMessage == null || userMessage.trim().isEmpty()) {
      userMessage = "[Mensaje sin texto]";
    }
    try {
      String credentials = config.basicAuthCredentials();

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
          "\"properties\":[{\"key\":\"sd.public.comment\",\"value\":{\"internal\":" + internal + "}}]" +
          "}";

      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(config.getUrl() + JIRA_ISSUE_PATH + jiraKey + "/comment"))
          .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
          .header(HEADER_AUTHORIZATION, AUTH_BASIC_PREFIX + credentials)
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

  /** Builds a fallback comment body for an attachment-only message (no text) sent while the
   * ticket is human-escalated — {@link #postJiraComment} needs a non-empty body, so this
   * describes what the user attached instead of leaving it blank. {@code attachments} is the
   * request wire format ({@code [{name, mimeType, data}]}, per {@code buildOutgoingAttachmentsJson}
   * in {@link SupportAttachmentHelpers}). */
  static String describeAttachments(JSONArray attachments) {
    if (attachments == null || attachments.length() == 0) return "[Adjuntó un archivo]";
    List<String> names = new ArrayList<>();
    for (int i = 0; i < attachments.length(); i++) {
      JSONObject att = attachments.optJSONObject(i);
      if (att != null) names.add(att.optString("name", "archivo"));
    }
    return names.isEmpty() ? "[Adjuntó un archivo]" : "📎 Adjuntó: " + String.join(", ", names);
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
    if (jiraKey == null || jiraKey.isEmpty()) return;
    JiraConfig config = JiraConfig.fromRuntime();
    if (!config.isConfigured()) {
      log.warn("Jira CSAT label for {} NOT sent: Jira integration is not configured "
          + "(support.jira.url/username/token)", jiraKey);
      return;
    }
    try {
      String credentials = config.basicAuthCredentials();

      String payload = "{\"update\":{\"labels\":[{\"add\":\"csat-" + score + "\"}]}}";

      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(config.getUrl() + JIRA_ISSUE_PATH + jiraKey))
          .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
          .header(HEADER_AUTHORIZATION, AUTH_BASIC_PREFIX + credentials)
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

  /** Returns {@code {emailAddress, displayName}} of the ticket's current assignee — either or
   * both may be {@code null} if unassigned, the request fails, or (for accounts with private
   * email visibility, like "Information Etendo") {@code emailAddress} is omitted by Jira
   * entirely.
   *
   * Used by {@code handleSendMessage}'s live re-check: a conversation escalated to a human
   * can only learn it was reassigned back to the bot from this poll if the assignee-reset
   * webhook never fires — and that webhook depends on a Jira Automation rule that isn't
   * always configured or reachable (confirmed: the shared rule pointed at the wrong
   * environment for weeks). Synchronous/blocking by design, unlike the fire-and-forget writes
   * elsewhere in this class — the caller needs the answer before deciding whether to forward
   * the message silently or call the AI. */
  static String[] getTicketAssignee(String jiraKey) {
    String[] result = new String[]{null, null};
    if (jiraKey == null || jiraKey.isEmpty()) return result;
    JiraConfig config = JiraConfig.fromRuntime();
    if (!config.isConfigured()) return result;
    try {
      String credentials = config.basicAuthCredentials();
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(config.getUrl() + JIRA_ISSUE_PATH + jiraKey + "?fields=assignee"))
          .header(HEADER_AUTHORIZATION, AUTH_BASIC_PREFIX + credentials)
          .timeout(Duration.ofSeconds(5))
          .GET()
          .build();
      HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
        JSONObject fields = new JSONObject(resp.body()).optJSONObject("fields");
        JSONObject assignee = fields != null ? fields.optJSONObject("assignee") : null;
        if (assignee != null) {
          result[0] = assignee.optString("emailAddress", null);
          result[1] = assignee.optString("displayName", null);
        }
      } else {
        log.warn("getTicketAssignee FAILED for {} ← {}", jiraKey, resp.statusCode());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Failed to get Jira assignee for {}: {}", jiraKey, e.getMessage());
    } catch (Exception e) {
      log.warn("Failed to get Jira assignee for {}: {}", jiraKey, e.getMessage());
    }
    return result;
  }
}
