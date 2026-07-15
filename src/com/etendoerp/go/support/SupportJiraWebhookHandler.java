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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.schemaforge.data.SupportConversation;
import com.etendoerp.go.schemaforge.data.SupportMessage;

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

  /** Same system properties {@link SupportIntegrationClient} reads for outbound Jira calls —
   * duplicated here (not shared via that class) so this file stays independent of the parallel
   * outbound-attachments work happening on {@link SupportIntegrationClient}. */
  private static final String JIRA_URL =
      System.getProperty("support.jira.url", "https://etendoproject.atlassian.net");
  private static final String JIRA_API_TOKEN =
      System.getProperty("support.jira.token", "");

  private static final String HEADER_WEBHOOK_SECRET = "X-Webhook-Secret";
  private static final String HEADER_AUTHORIZATION = "Authorization";
  private static final String MSG_INVALID_SECRET = "Invalid secret";
  private static final String MSG_INTERNAL_ERROR = "Internal error";
  private static final String RESP_IGNORED = "{\"status\":\"ignored\"}";
  private static final String DEFAULT_AGENT_NAME = "Agente de soporte";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_CONVERSATION_ID = "conversationId";
  private static final String FIELD_JIRA_TICKET_KEY = "jiraTicketKey";
  private static final String FIELD_ID = "id";
  private static final String FIELD_FILENAME = "filename";
  private static final String FIELD_MIME_TYPE = "mimeType";

  /** Shared HTTP client for outbound calls to Jira from this handler (attachment metadata lookup
   * and the authenticated content proxy in {@link SupportConversationsServlet}). Redirects are
   * followed because Jira Cloud's attachment content endpoint commonly 303s to a signed media
   * URL; {@link HttpClient} already strips the Authorization header on cross-host redirects, so
   * the service account credentials are not leaked to that URL. */
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

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

  static JiraWebhookComment parseStandardJiraWebhook(HttpServletResponse response, JSONObject body)
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
    JSONArray attachments = resolveCommentAttachments(jiraKey, comment);
    return new JiraWebhookComment(jiraKey, commentId, authorName, authorEmail, text, attachments);
  }

  /** No "comment" field: either an assignee-change-back-to-bot or a status transition to Done. */
  static void handleJiraNonCommentEvent(HttpServletResponse response, JSONObject issue, JSONObject body,
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

  static boolean isStatusTransitionToDone(JSONObject changelog) {
    if (changelog == null) return false;
    JSONArray items = changelog.optJSONArray("items");
    if (items == null) return false;
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item != null && FIELD_STATUS.equals(item.optString("field", ""))
          && "Done".equalsIgnoreCase(item.optString("toString", ""))) {
        return true;
      }
    }
    return false;
  }

  static void writeIgnored(HttpServletResponse response) throws IOException {
    SupportConversationsServlet.writeRaw(response, 200, RESP_IGNORED);
  }

  // --- Jira Automation webhook (query params) ---

  static JiraWebhookComment parseAutomationJiraWebhook(HttpServletRequest request,
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
    // Jira Automation cannot template a JSON body in this dev's plan (see
    // docs/support-chat-session-2026-06-11.md) — "commentText" arrives as a plain query param,
    // never as ADF, so there is no media node to look for on this path.
    return new JiraWebhookComment(jiraKey, commentId, authorName, authorEmail, text, null);
  }

  // --- Persisting the comment as a support message ---

  private static void storeJiraWebhookComment(HttpServletResponse response, JiraWebhookComment comment)
      throws IOException, JSONException {
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
      SupportConversation conv = findConversationByJiraKey(comment.jiraKey);
      if (conv == null) {
        SupportConversationsServlet.writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "no_conversation"));
        return;
      }
      if (findMessageByExternalId(externalId) != null) {
        // Already stored (Jira retry) — ack without duplicating.
        SupportConversationsServlet.writeJson(response, 200,
            new JSONObject().put(FIELD_STATUS, "ok").put(FIELD_CONVERSATION_ID, conv.getId()));
        return;
      }
      Date ts = new Date();
      insertJiraMessage(conv, comment.authorName, text, ts, externalId, comment.attachments);
      SupportConversationsServlet.updateConvSummary(conv.getId(), text, ts);
      conv.setUnread(true);
      OBDal.getInstance().save(conv);
      log.info("Jira comment {} ({}) stored in conversation {}", comment.commentId, comment.authorName, conv.getId());
      SupportConversationsServlet.writeJson(response, 200,
          new JSONObject().put(FIELD_STATUS, "ok").put(FIELD_CONVERSATION_ID, conv.getId()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Jira webhooks arrive with no per-request tenant to scope to — these lookups must see every
   * client's rows to resolve the ticket key / dedupe the external id. */
  private static SupportConversation findConversationByJiraKey(String jiraKey) {
    OBCriteria<SupportConversation> crit = OBDal.getInstance().createCriteria(SupportConversation.class);
    crit.setFilterOnReadableClients(false);
    crit.setFilterOnReadableOrganization(false);
    crit.add(Restrictions.eq(SupportConversation.PROPERTY_JIRATICKETKEY, jiraKey));
    crit.setMaxResults(1);
    return (SupportConversation) crit.uniqueResult();
  }

  private static SupportMessage findMessageByExternalId(String externalId) {
    OBCriteria<SupportMessage> crit = OBDal.getInstance().createCriteria(SupportMessage.class);
    crit.setFilterOnReadableClients(false);
    crit.setFilterOnReadableOrganization(false);
    crit.add(Restrictions.eq(SupportMessage.PROPERTY_EXTERNALID, externalId));
    crit.setMaxResults(1);
    return (SupportMessage) crit.uniqueResult();
  }

  /** Jira comments have no authenticated Etendo requester — tagged as system-created, same tenant
   * as the owning conversation. {@code attachments} is the resolved {@code [{id, filename,
   * mimeType}]} array (see {@link #resolveCommentAttachments}), or {@code null} when the comment
   * carried none — stored as-is (null column), never as an empty-array string. */
  private static void insertJiraMessage(SupportConversation conv, String authorName, String text, Date ts,
      String externalId, JSONArray attachments) {
    User systemUser = OBDal.getInstance().get(User.class, SupportConversationsServlet.SYSTEM_USER_ID);
    SupportMessage msg = OBProvider.getInstance().get(SupportMessage.class);
    msg.setNewOBObject(true);
    msg.setId(SupportConversationsServlet.newId());
    msg.setClient(conv.getClient());
    msg.setOrganization(conv.getOrganization());
    msg.setCreatedBy(systemUser);
    msg.setUpdatedBy(systemUser);
    msg.setConversation(conv);
    msg.setSender("human");
    msg.setSenderName(authorName);
    msg.setText(text);
    msg.setMessageDate(ts);
    msg.setExternalId(externalId);
    if (attachments != null && attachments.length() > 0) {
      msg.setAttachments(attachments.toString());
    }
    OBDal.getInstance().save(msg);
  }

  // --- Jira automation side-effects triggered without a comment ---

  static void handleAssigneeReset(HttpServletResponse response, String jiraKey) throws IOException {
    try {
      OBContext.setAdminMode(true);
      try {
        OBCriteria<SupportConversation> crit = OBDal.getInstance().createCriteria(SupportConversation.class);
        crit.setFilterOnReadableClients(false);
        crit.setFilterOnReadableOrganization(false);
        crit.add(Restrictions.eq(SupportConversation.PROPERTY_JIRATICKETKEY, jiraKey));
        List<SupportConversation> matches = crit.list();
        for (SupportConversation conv : matches) {
          conv.setHumanTakeover(false);
          OBDal.getInstance().save(conv);
        }
        log.info("Human takeover reset via assignee event for Jira ticket {} ({} row(s))", jiraKey, matches.size());
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
        SupportConversation conv = findConversationByJiraKey(jiraKey);
        if (conv == null) {
          SupportConversationsServlet.writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "no_conversation"));
          return;
        }
        conv.setStatus("closed");
        conv.setUnread(true);
        conv.setLastActivity(new Date());
        OBDal.getInstance().save(conv);
        log.info("Conversation {} closed via Jira ticket {} resolution", conv.getId(), jiraKey);
        SupportConversationsServlet.writeJson(response, 200,
            new JSONObject().put(FIELD_STATUS, "ok").put(FIELD_CONVERSATION_ID, conv.getId()));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error closing conversation for ticket {}", jiraKey, e);
      SupportConversationsServlet.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    }
  }

  static boolean isBotEmail(String email) {
    if (email == null || email.isEmpty()) return false;
    return (!JIRA_BOT_EMAIL.isEmpty() && JIRA_BOT_EMAIL.equalsIgnoreCase(email))
        || JIRA_USERNAME.equalsIgnoreCase(email);
  }

  // --- Jira ADF (Atlassian Document Format) comment body parsing ---

  static String extractAdfText(Object node) {
    if (node == null) return "";
    if (node instanceof String) return extractAdfTextFromString((String) node);
    if (node instanceof JSONObject) return extractAdfTextFromObject((JSONObject) node);
    return "";
  }

  static String extractAdfTextFromString(String raw) {
    String s = raw.trim();
    if (!s.startsWith("{")) return s;
    try {
      return extractAdfText(new JSONObject(s));
    } catch (Exception e) {
      return s;
    }
  }

  static String extractAdfTextFromObject(JSONObject obj) {
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

  static boolean isBlockType(String type) {
    return "paragraph".equals(type) || "heading".equals(type) ||
        "bulletList".equals(type) || "orderedList".equals(type) ||
        "listItem".equals(type) || "codeBlock".equals(type) || "blockquote".equals(type);
  }

  static String nvl(String value, String fallback) {
    return (value != null && !value.isEmpty()) ? value : fallback;
  }

  static final class JiraWebhookComment {
    final String jiraKey;
    final String commentId;
    final String authorName;
    final String authorEmail;
    final String text;
    /** Resolved {@code [{id, filename, mimeType}]} array, or {@code null} when the comment
     * carried no attachment (or none could be resolved). See {@link #resolveCommentAttachments}. */
    final JSONArray attachments;

    JiraWebhookComment(String jiraKey, String commentId, String authorName, String authorEmail, String text,
        JSONArray attachments) {
      this.jiraKey = jiraKey;
      this.commentId = commentId;
      this.authorName = authorName;
      this.authorEmail = authorEmail;
      this.text = text;
      this.attachments = attachments;
    }
  }

  // --- Jira attachment resolution (ADF media nodes → Jira REST attachment metadata) ---

  /**
   * Detects attachments carried by a Jira comment and resolves them to {@code {id, filename,
   * mimeType}} metadata.
   * <p>
   * <b>Approach:</b> Jira's ADF {@code media}/{@code mediaGroup}/{@code mediaSingle} nodes are
   * walked the same way {@link #extractAdfText} already walks the document for text — this
   * handler has been parsing real nested ADF (string-encoded, via {@code extractAdfTextFromString})
   * from the live production webhook since before this change (see
   * {@code docs/support-chat-session-2026-06-11.md}: the Jira Automation "Send web request" body
   * embeds {@code {{comment.body}}} as a JSON string, and this class's existing dual
   * String/JSONObject dispatch already round-trips it as real ADF). Since a {@code media} node's
   * schema is part of the same ADF document, it is present in that same payload whenever the
   * comment has an image/file attached — no separate Jira call is needed to <em>detect</em> an
   * attachment.
   * <p>
   * ADF's {@code media} node attrs only carry an {@code id} (and type/collection) — never a
   * filename or MIME type — so resolving those still requires one Jira REST call per comment:
   * {@code GET /rest/api/3/issue/{key}?fields=attachment}. The ADF media id is a Media
   * Platform file id, which in Jira Cloud is commonly a different value than the classic
   * attachment id used by {@code /rest/api/3/attachment/*} — so this method first tries a direct
   * id match against that list, and falls back to pairing any still-unmatched media node with the
   * closest-by-timestamp unclaimed attachment. <b>This fallback path is unverified against a real
   * Jira comment with an attachment — flagged for QA.</b>
   */
  static JSONArray resolveCommentAttachments(String jiraKey, JSONObject comment) {
    List<String> mediaIds = new ArrayList<>();
    collectAdfMediaIds(comment.opt("body"), mediaIds);
    if (mediaIds.isEmpty()) return null;
    Date commentTime = parseCommentTimestamp(comment);
    JSONArray issueAttachments = fetchIssueAttachments(jiraKey);
    JSONArray resolved = correlateAttachments(issueAttachments, mediaIds, commentTime);
    return resolved.length() > 0 ? resolved : null;
  }

  /** Recursively collects the {@code attrs.id} of every ADF {@code media} node under {@code node}
   * (mirrors {@link #extractAdfText}'s traversal/dual String-or-JSONObject dispatch). */
  static void collectAdfMediaIds(Object node, List<String> ids) {
    if (node instanceof String) {
      String s = ((String) node).trim();
      if (!s.startsWith("{")) return;
      try {
        collectAdfMediaIds(new JSONObject(s), ids);
      } catch (Exception e) {
        // Not JSON after all — nothing to collect.
      }
      return;
    }
    if (!(node instanceof JSONObject)) return;
    JSONObject obj = (JSONObject) node;
    if ("media".equals(obj.optString("type", ""))) {
      JSONObject attrs = obj.optJSONObject("attrs");
      String id = attrs != null ? attrs.optString(FIELD_ID, "") : "";
      if (!id.isEmpty()) ids.add(id);
    }
    JSONArray content = obj.optJSONArray("content");
    if (content != null) {
      for (int i = 0; i < content.length(); i++) {
        Object child = content.opt(i);
        if (child instanceof JSONObject) collectAdfMediaIds(child, ids);
      }
    }
  }

  /** Standard Jira webhooks carry {@code comment.created}; the current production Automation
   * body (see class javadoc) does not, so "now" is the best available proxy — the webhook fires
   * within seconds of the comment being posted. */
  static Date parseCommentTimestamp(JSONObject comment) {
    String created = comment.optString("created", "");
    long millis = parseJiraInstantMillis(created);
    return millis > 0 ? new Date(millis) : new Date();
  }

  /** Jira Cloud REST v3 formats {@code created} timestamps with a numeric offset that has NO
   * colon (e.g. {@code "2021-01-05T10:15:30.000+0000"}), which neither {@link
   * DateTimeFormatter#ISO_OFFSET_DATE_TIME} nor {@link Instant#parse} accepts. The {@code Z}
   * pattern letter does accept that no-colon offset form, so it is tried as a second option;
   * {@code ISO_OFFSET_DATE_TIME} is tried first since it already correctly handles both the
   * colon-offset form ({@code -03:00}) and the {@code Z}/zulu suffix form. */
  private static final DateTimeFormatter NO_COLON_OFFSET_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

  static long parseJiraInstantMillis(String value) {
    if (value == null || value.isEmpty()) return -1;
    try {
      return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli();
    } catch (Exception e) {
      try {
        return OffsetDateTime.parse(value, NO_COLON_OFFSET_FORMAT).toInstant().toEpochMilli();
      } catch (Exception e2) {
        try {
          return Instant.parse(value).toEpochMilli();
        } catch (Exception e3) {
          return -1;
        }
      }
    }
  }

  /** {@code GET /rest/api/3/issue/{key}?fields=attachment} — returns that issue's attachment
   * list ({@code [{id, filename, mimeType, created, ...}]}), or an empty array on any failure
   * (missing token, network error, non-200). */
  static JSONArray fetchIssueAttachments(String jiraKey) {
    if (JIRA_API_TOKEN.isEmpty()) {
      log.warn("Cannot resolve Jira attachments for {}: support.jira.token system property is empty", jiraKey);
      return new JSONArray();
    }
    try {
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(JIRA_URL + "/rest/api/3/issue/" + jiraKey + "?fields=attachment"))
          .header(HEADER_AUTHORIZATION, "Basic " + jiraBasicAuthCredentials())
          .timeout(Duration.ofSeconds(15))
          .GET()
          .build();
      HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        log.warn("Jira attachment lookup FAILED for {} ← {}", jiraKey, resp.statusCode());
        return new JSONArray();
      }
      JSONObject fields = new JSONObject(resp.body()).optJSONObject("fields");
      JSONArray attachments = fields != null ? fields.optJSONArray("attachment") : null;
      return attachments != null ? attachments : new JSONArray();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Failed to fetch Jira attachments for {}: {}", jiraKey, e.getMessage());
      return new JSONArray();
    } catch (Exception e) {
      log.warn("Failed to fetch Jira attachments for {}: {}", jiraKey, e.getMessage());
      return new JSONArray();
    }
  }

  /** Matches each ADF media id against {@code issueAttachments} by direct id equality first,
   * then pairs any leftovers with the closest-by-timestamp unclaimed attachment. Each match is
   * projected down to {@code {id, filename, mimeType}} — {@code id} is always the REST attachment
   * id (never the raw ADF media id), since that is what the content-proxy endpoint needs. */
  static JSONArray correlateAttachments(JSONArray issueAttachments, List<String> mediaIds, Date commentTime) {
    JSONArray result = new JSONArray();
    if (mediaIds.isEmpty() || issueAttachments.length() == 0) return result;
    Set<Integer> claimed = new HashSet<>();
    for (String mediaId : mediaIds) {
      int idx = indexOfAttachmentById(issueAttachments, mediaId, claimed);
      if (idx >= 0) {
        result.put(toAttachmentMeta(issueAttachments.optJSONObject(idx)));
        claimed.add(idx);
      }
    }
    int unmatched = mediaIds.size() - result.length();
    for (int n = 0; n < unmatched; n++) {
      int idx = closestUnclaimedByTime(issueAttachments, commentTime, claimed);
      if (idx < 0) break;
      result.put(toAttachmentMeta(issueAttachments.optJSONObject(idx)));
      claimed.add(idx);
    }
    return result;
  }

  static int indexOfAttachmentById(JSONArray issueAttachments, String id, Set<Integer> claimed) {
    for (int i = 0; i < issueAttachments.length(); i++) {
      if (claimed.contains(i)) continue;
      JSONObject att = issueAttachments.optJSONObject(i);
      if (att != null && id.equals(att.optString(FIELD_ID, ""))) return i;
    }
    return -1;
  }

  /** Maximum gap allowed between a comment and an unclaimed attachment for the fallback,
   * closest-by-timestamp pairing to be trusted. Sized generously above the couple-of-minutes
   * gap typical of an upload-then-comment sequence (and above the widest gap exercised by this
   * class's own fallback test fixtures, ~1 hour) so ordinary support-session delays still
   * correlate, while still rejecting force-pairs against attachments that are actually unrelated
   * (e.g. weeks-old uploads on a long-lived ticket). Beyond this window, "no match" is preferred
   * over a wrong one. */
  private static final long MAX_FALLBACK_CORRELATION_DISTANCE_MILLIS = 24L * 60 * 60 * 1000; // 24 hours

  static int closestUnclaimedByTime(JSONArray issueAttachments, Date commentTime, Set<Integer> claimed) {
    int bestIdx = -1;
    long bestDiff = Long.MAX_VALUE;
    for (int i = 0; i < issueAttachments.length(); i++) {
      if (claimed.contains(i)) continue;
      JSONObject att = issueAttachments.optJSONObject(i);
      if (att == null) continue;
      long attMillis = parseJiraInstantMillis(att.optString("created", ""));
      if (attMillis < 0) continue;
      long diff = Math.abs(attMillis - commentTime.getTime());
      if (diff < bestDiff && diff <= MAX_FALLBACK_CORRELATION_DISTANCE_MILLIS) {
        bestDiff = diff;
        bestIdx = i;
      }
    }
    return bestIdx;
  }

  static JSONObject toAttachmentMeta(JSONObject att) {
    try {
      return new JSONObject()
          .put(FIELD_ID, att.optString(FIELD_ID, ""))
          .put(FIELD_FILENAME, att.optString(FIELD_FILENAME, "attachment"))
          .put(FIELD_MIME_TYPE, att.optString(FIELD_MIME_TYPE, "application/octet-stream"));
    } catch (JSONException e) {
      // JSONObject#put only throws on a null key, which never happens here.
      throw new IllegalStateException(e);
    }
  }

  static String jiraBasicAuthCredentials() {
    return Base64.getEncoder().encodeToString((JIRA_USERNAME + ":" + JIRA_API_TOKEN).getBytes(StandardCharsets.UTF_8));
  }

  // --- Authenticated attachment content proxy (used by SupportConversationsServlet) ---

  /** {@code GET /rest/api/3/attachment/content/{id}} — streams the raw bytes back to the caller.
   * Package-private: invoked by {@link SupportConversationsServlet}'s {@code /attachments/{id}}
   * endpoint after it has verified the requesting user owns the conversation the attachment
   * belongs to. Returns {@code null} on any failure (missing token, network error, non-2xx) —
   * the caller is expected to turn that into a 404/500 rather than leak Jira's error body. */
  static HttpResponse<InputStream> fetchAttachmentContent(String jiraAttachmentId) {
    if (JIRA_API_TOKEN.isEmpty()) {
      log.warn("Cannot proxy Jira attachment {}: support.jira.token system property is empty", jiraAttachmentId);
      return null;
    }
    try {
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(JIRA_URL + "/rest/api/3/attachment/content/" + jiraAttachmentId))
          .header(HEADER_AUTHORIZATION, "Basic " + jiraBasicAuthCredentials())
          .timeout(Duration.ofSeconds(30))
          .GET()
          .build();
      HttpResponse<InputStream> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
      if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
        log.warn("Jira attachment content FAILED for {} ← {}", jiraAttachmentId, resp.statusCode());
        return null;
      }
      return resp;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Failed to fetch Jira attachment content {}: {}", jiraAttachmentId, e.getMessage());
      return null;
    } catch (Exception e) {
      log.warn("Failed to fetch Jira attachment content {}: {}", jiraAttachmentId, e.getMessage());
      return null;
    }
  }
}
