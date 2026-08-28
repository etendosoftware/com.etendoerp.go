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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import com.etendoerp.go.common.ConfigPropertyReader;
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

  private static final String WEBHOOK_SECRET = ConfigPropertyReader.readConfigValue(
      "support.webhook.secret", "ETGO_SUPPORT_WEBHOOK_SECRET", "");

  // Jira URL/username/token/bot-identity now live in JiraConfig, shared with
  // SupportIntegrationClient — see that class for the resolution order (system property >
  // Openbravo.properties > env var, no hardcoded real credential as a default) and
  // JiraConfig#isConfigured().
  //
  // Fallback for the bot-email check: Jira Cloud omits `emailAddress` from a comment's `author`
  // object entirely for accounts whose Atlassian profile has email visibility set to private —
  // true of our integration account ("Information Etendo"), confirmed against a real captured
  // webhook payload. Bot email alone therefore NEVER matches our own comments; the bot-name
  // check is what actually catches them and prevents an echo loop once comments are public
  // (a comment WE post gets picked up by the same webhook and re-inserted as if a human replied).

  private static final String HEADER_WEBHOOK_SECRET = "X-Webhook-Secret";
  private static final String HEADER_AUTHORIZATION = "Authorization";
  private static final String MSG_INVALID_SECRET = "Invalid secret";
  private static final String MSG_INTERNAL_ERROR = "Internal error";
  private static final String RESP_IGNORED = "{\"status\":\"ignored\"}";
  private static final String DEFAULT_AGENT_NAME = "Agente de soporte";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_CONVERSATION_ID = "conversationId";
  private static final String FIELD_JIRA_TICKET_KEY = "jiraTicketKey";
  /** Package-private (not private): also read by {@link SupportAdfAttachmentCorrelator#toAttachmentMeta}. */
  static final String FIELD_FILENAME = "filename";

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
    String authorAccountId = author != null ? author.optString("accountId", "") : "";
    String authorName = author != null ? author.optString("displayName", DEFAULT_AGENT_NAME) : DEFAULT_AGENT_NAME;
    String text = extractAdfText(comment.opt("body")).trim();
    ResolvedAttachments resolvedAttachments = resolveCommentAttachments(jiraKey, comment);
    text = stripResolvedWikiMarkupTokens(text, resolvedAttachments.resolvedWikiMarkupTokens);
    return new JiraWebhookComment(jiraKey, commentId, authorName, authorEmail, authorAccountId, text,
        resolvedAttachments.attachments);
  }

  /** No "comment" field: either an assignee-change-back-to-bot or a status transition to Done. */
  static void handleJiraNonCommentEvent(HttpServletResponse response, JSONObject issue, JSONObject body,
      String jiraKey) throws IOException {
    JSONObject fields = issue.optJSONObject("fields");
    if (fields != null) {
      JSONObject assignee = fields.optJSONObject("assignee");
      String assigneeEmail = assignee != null ? assignee.optString("emailAddress", "") : "";
      String assigneeName = assignee != null ? assignee.optString("displayName", "") : "";
      if (isBotIdentity(assigneeEmail, assigneeName)) {
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
      if (isBotIdentity(authorEmail, authorName)) {
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
    // never as ADF, so there is no media node to look for on this path. Automation's smart
    // values on this rule don't expose an accountId either — the reporter-echo check just won't
    // fire for events delivered this way (the standard webhook path is what production actually
    // uses; this path is a documented fallback, not the primary trigger).
    return new JiraWebhookComment(jiraKey, commentId, authorName, authorEmail, "", text, null);
  }

  // --- Persisting the comment as a support message ---

  static void storeJiraWebhookComment(HttpServletResponse response, JiraWebhookComment comment)
      throws IOException, JSONException {
    if (isBotIdentity(comment.authorEmail, comment.authorName)) {
      SupportConversationsServlet.writeJson(response, 200, new JSONObject().put(FIELD_STATUS, "skipped_bot"));
      return;
    }
    String text = comment.text == null ? "" : comment.text.trim();
    boolean hasAttachments = comment.attachments != null && comment.attachments.length() > 0;
    if (text.isEmpty() && !hasAttachments) {
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
      // NOTE: there is deliberately NO "is this the reporter replying to their own ticket"
      // FILTER here. Replying via email or the JSM portal is a legitimate, intended second
      // channel into the SAME conversation (that's the whole point of enabling portal access) —
      // an earlier version of this check filtered those out entirely, which broke that feature.
      // The bot-identity check above is what actually solves the real echo-loop bug (OUR OWN
      // posted comments, from add_two_comments_sync, looping back in as if a human replied).
      //
      // What the reporter-identity signal IS used for: deciding how the message displays. A
      // genuinely different support agent's reply should show as "human" (left-aligned, agent
      // avatar) — but the reporter replying to their own ticket via another channel is THEM, so
      // it should look exactly like their own chat messages (right-aligned, no avatar), not like
      // someone else answering them. accountId is the reliable signal (email is commonly absent
      // from Jira Cloud webhook payloads — see storeJiraWebhookComment's earlier bot check).
      boolean isReporterReply = !comment.authorAccountId.isEmpty()
          && comment.authorAccountId.equals(conv.getJiraReporterAccountId());
      String sender = isReporterReply ? "user" : "human";
      if (findMessageByExternalId(externalId) != null) {
        // Already stored (Jira retry) — ack without duplicating.
        SupportConversationsServlet.writeJson(response, 200,
            new JSONObject().put(FIELD_STATUS, "ok").put(FIELD_CONVERSATION_ID, conv.getId()));
        return;
      }
      Date ts = new Date();
      insertJiraMessage(conv, sender, comment.authorName, text, ts, externalId, comment.attachments);
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
   * client's rows to resolve the ticket key / dedupe the external id. Package-private (not
   * private): also called by {@link SupportAdfAttachmentCorrelator#findAlreadyLinkedAttachmentIds}. */
  static SupportConversation findConversationByJiraKey(String jiraKey) {
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
   * as the owning conversation. {@code sender} is {@code "user"} when the comment is the
   * reporter replying to their own ticket via email/portal (see the isReporterReply check in
   * {@link #storeJiraWebhookComment}), {@code "human"} for a genuinely different support agent.
   * {@code attachments} is the resolved {@code [{id, filename, mimeType}]} array (see
   * {@link #resolveCommentAttachments}), or {@code null} when the comment carried none — stored
   * as-is (null column), never as an empty-array string. */
  private static void insertJiraMessage(SupportConversation conv, String sender, String authorName, String text,
      Date ts, String externalId, JSONArray attachments) {
    User systemUser = OBDal.getInstance().get(User.class, SupportConversationsServlet.SYSTEM_USER_ID);
    SupportMessage msg = OBProvider.getInstance().get(SupportMessage.class);
    msg.setNewOBObject(true);
    msg.setId(SupportConversationsServlet.newId());
    msg.setClient(conv.getClient());
    msg.setOrganization(conv.getOrganization());
    msg.setCreatedBy(systemUser);
    msg.setUpdatedBy(systemUser);
    msg.setConversation(conv);
    msg.setSender(sender);
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
    JiraConfig config = JiraConfig.fromRuntime();
    return (!config.getBotEmail().isEmpty() && config.getBotEmail().equalsIgnoreCase(email))
        || email.equalsIgnoreCase(config.getUsername());
  }

  /** True if {@code email} OR {@code displayName} identifies our own Jira integration account
   * ("Information Etendo"). Needed because that account's Atlassian profile has email
   * visibility set to private, so Jira Cloud omits {@code emailAddress} from BOTH a comment's
   * {@code author} object AND an issue's {@code assignee} object — {@link #isBotEmail} alone
   * never matches it. {@code displayName} isn't subject to that privacy setting, so it's the
   * only reliable signal for the assignee-reset case (a human reassigning the ticket back to
   * the bot); for comments this mirrors the existing bot-name fallback in
   * {@link #storeJiraWebhookComment}. */
  static boolean isBotIdentity(String email, String displayName) {
    return isBotEmail(email) || JiraConfig.fromRuntime().getBotName().equalsIgnoreCase(displayName);
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

  // --- Jira wiki-markup (non-ADF) embedded image detection ---

  /** Matches a Jira wiki-markup image/file reference: {@code !filename!} or
   * {@code !filename|param1=val1,param2=val2!} (e.g.
   * {@code !Captura desde 2026-07-15 13-21-04.png|width=989,alt="..."!} — real Jira filenames
   * routinely contain spaces, so the filename group only excludes {@code |} and {@code !}
   * themselves, not whitespace). Group 1 is the filename portion (before the first {@code |}, if
   * any; lazily matched so it stops at the first {@code |} or {@code !} it hits, whichever comes
   * first). The dot check in {@link #extractWikiMarkupAttachmentRefs} — not whitespace exclusion —
   * is what keeps a bare {@code !} used as normal sentence punctuation from turning into a
   * spurious match: two nearby {@code !}/{@code !} with no file extension in between is discarded
   * there. This is the syntax Jira renders for an EMBEDDED image/file (inline media); a plain,
   * non-embedded attachment link uses the different {@link #WIKI_MARKUP_ATTACHMENT_LINK_PATTERN}
   * syntax below. */
  private static final Pattern WIKI_MARKUP_IMAGE_PATTERN = Pattern.compile("!([^|!]+?)(?:\\|[^!]*)?!");

  /** Matches a Jira wiki-markup plain attachment link: {@code [^filename.ext]} (e.g.
   * {@code [^Hoja de cálculo sin título.xlsx]}) — the syntax Jira Automation's
   * {@code {{comment.body}}} smart value renders when a human agent attaches a file WITHOUT
   * embedding it as inline media (a regular, non-embedded attachment), as opposed to the embedded
   * image/file syntax matched by {@link #WIKI_MARKUP_IMAGE_PATTERN}. Group 1 is everything between
   * {@code [^} and the closing {@code ]}, so spaces in the filename are preserved. The
   * {@code [^...]} shape is distinctive enough on its own that plain prose essentially never
   * produces it by accident, but {@link #extractWikiMarkupAttachmentRefs} still applies the same
   * "must contain a dot" heuristic as the image pattern, as cheap extra insurance. */
  private static final Pattern WIKI_MARKUP_ATTACHMENT_LINK_PATTERN = Pattern.compile("\\[\\^([^\\]]+)\\]");

  /** One {@code !...!} or {@code [^...]} token found in a Jira wiki-markup comment body:
   * {@code filename} is the extracted filename to correlate against the Jira REST attachment
   * list, {@code token} is the exact original matched substring (including the surrounding
   * markup) so it can be stripped verbatim from the displayed text once correlated. */
  static final class WikiMarkupImageRef {
    final String filename;
    final String token;

    WikiMarkupImageRef(String filename, String token) {
      this.filename = filename;
      this.token = token;
    }
  }

  /** Scans {@code text} for BOTH Jira wiki-markup attachment reference shapes and returns them
   * merged into a single list: embedded image/file references ({@code !filename.ext!} or
   * {@code !filename.ext|params!}, via {@link #WIKI_MARKUP_IMAGE_PATTERN}) and plain, non-embedded
   * attachment links ({@code [^filename.ext]}, via {@link #WIKI_MARKUP_ATTACHMENT_LINK_PATTERN}) —
   * the two shapes Jira Automation's {@code {{comment.body}}} smart value actually renders as
   * (instead of ADF JSON) depending on whether the human agent embedded the file as inline media
   * or attached it plainly (see {@link #resolveCommentAttachments}). For both patterns, a matched
   * "filename" is only kept when it contains a {@code .} (a file extension): a bare {@code !} used
   * as normal punctuation never has a dot immediately before the next {@code !}/{@code |}, so this
   * simple heuristic is enough to avoid false positives without a more elaborate parser. */
  static List<WikiMarkupImageRef> extractWikiMarkupAttachmentRefs(String text) {
    List<WikiMarkupImageRef> refs = new ArrayList<>();
    if (text == null || text.isEmpty()) return refs;
    collectWikiMarkupRefs(WIKI_MARKUP_IMAGE_PATTERN, text, refs);
    collectWikiMarkupRefs(WIKI_MARKUP_ATTACHMENT_LINK_PATTERN, text, refs);
    return refs;
  }

  /** Runs {@code pattern} against {@code text} and appends every match whose captured filename
   * (group 1) contains a {@code .} to {@code refs} — shared by both wiki-markup patterns in
   * {@link #extractWikiMarkupAttachmentRefs}. */
  private static void collectWikiMarkupRefs(Pattern pattern, String text, List<WikiMarkupImageRef> refs) {
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      String filename = matcher.group(1);
      if (filename.indexOf('.') < 0) continue; // not shaped like a real filename — likely punctuation
      refs.add(new WikiMarkupImageRef(filename, matcher.group(0)));
    }
  }

  /** Removes every token in {@code tokensToStrip} (exact substrings, as produced by {@link
   * #extractWikiMarkupAttachmentRefs}) from {@code text}, then does minimal whitespace cleanup —
   * collapsing runs of spaces/tabs left behind and trimming the ends. Tokens that could not be
   * correlated to a real attachment are never passed in here, so they are left in the text as-is
   * (safer than silently swallowing unresolved content). */
  static String stripResolvedWikiMarkupTokens(String text, List<String> tokensToStrip) {
    if (text == null || tokensToStrip == null || tokensToStrip.isEmpty()) return text;
    String result = text;
    for (String token : tokensToStrip) {
      result = result.replace(token, "");
    }
    return result.replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n").trim();
  }

  static final class JiraWebhookComment {
    final String jiraKey;
    final String commentId;
    final String authorName;
    final String authorEmail;
    /** The author's Jira accountId — unlike {@code authorEmail}, always present regardless of
     * the account's profile-privacy settings. Empty string when unavailable (e.g. the
     * Automation query-param path, which never carries it). See
     * {@link #storeJiraWebhookComment}'s reporter-echo check. */
    final String authorAccountId;
    final String text;
    /** Resolved {@code [{id, filename, mimeType}]} array, or {@code null} when the comment
     * carried no attachment (or none could be resolved). See {@link #resolveCommentAttachments}. */
    final JSONArray attachments;

    JiraWebhookComment(String jiraKey, String commentId, String authorName, String authorEmail,
        String authorAccountId, String text, JSONArray attachments) {
      this.jiraKey = jiraKey;
      this.commentId = commentId;
      this.authorName = authorName;
      this.authorEmail = authorEmail;
      this.authorAccountId = authorAccountId;
      this.text = text;
      this.attachments = attachments;
    }
  }

  // --- Jira attachment resolution (ADF media nodes → Jira REST attachment metadata) ---

  /**
   * Result of {@link #resolveCommentAttachments}: the resolved {@code {id, filename, mimeType}}
   * attachments (or {@code null} when none were found) plus the exact wiki-markup {@code !...!}
   * substrings that were successfully correlated to one of them — the caller ({@link
   * #parseStandardJiraWebhook}) strips those substrings out of the displayed comment text via
   * {@link #stripResolvedWikiMarkupTokens}. Unmatched wiki-markup tokens are intentionally NOT
   * included here, so they are left as-is in the text (see {@link #stripResolvedWikiMarkupTokens}).
   */
  static final class ResolvedAttachments {
    final JSONArray attachments;
    final List<String> resolvedWikiMarkupTokens;

    ResolvedAttachments(JSONArray attachments, List<String> resolvedWikiMarkupTokens) {
      this.attachments = attachments;
      this.resolvedWikiMarkupTokens = resolvedWikiMarkupTokens;
    }
  }

  /**
   * Detects attachments carried by a Jira comment and resolves them to {@code {id, filename,
   * mimeType}} metadata. Supports BOTH shapes Jira actually sends {@code comment.body} as:
   * <ul>
   * <li><b>ADF (structured JSON):</b> {@code media}/{@code mediaGroup}/{@code mediaSingle} nodes
   * are walked the same way {@link #extractAdfText} already walks the document for text — this
   * handler has been parsing real nested ADF (string-encoded, via {@code extractAdfTextFromString})
   * from the live production webhook since before this change (see
   * {@code docs/support-chat-session-2026-06-11.md}). Since a {@code media} node's schema is part
   * of the same ADF document, it is present in that same payload whenever the comment has an
   * image/file attached — no separate Jira call is needed to <em>detect</em> an attachment.</li>
   * <li><b>Jira wiki markup (plain string):</b> confirmed against real Jira comments with an
   * embedded image, and separately with a plain (non-embedded) file attachment — Jira Automation's
   * {@code {{comment.body}}} smart value renders as wiki markup rather than ADF JSON for both
   * cases: {@code !filename.png|width=989,alt="filename.png"!} for an embedded image/file, or
   * {@code [^filename.xlsx]} for a plain attachment link. Either way {@code comment.opt("body")}
   * is a plain string that never reaches the ADF walk above with anything to find.
   * {@link #extractWikiMarkupAttachmentRefs} extracts the filename out of every token of both
   * shapes and this method correlates each against the Jira REST attachment list by EXACT filename
   * match — unambiguous, unlike the ADF id correlation below.</li>
   * </ul>
   * ADF's {@code media} node attrs only carry an {@code id} (and type/collection) — never a
   * filename or MIME type — so resolving those (and the wiki-markup filenames) still requires one
   * Jira REST call per comment: {@code GET /rest/api/3/issue/{key}?fields=attachment}. The ADF
   * media id is a Media Platform file id, which in Jira Cloud is commonly a different value than
   * the classic attachment id used by {@code /rest/api/3/attachment/*} — so the ADF side first
   * tries a direct id match against that list, and falls back to pairing any still-unmatched media
   * node with the closest-by-timestamp unclaimed attachment (see {@link
   * SupportAdfAttachmentCorrelator#correlateAttachments(JSONArray, List, Date, Set)}). The
   * wiki-markup side needs none of that: an exact filename match is either found or it isn't.
   */
  static ResolvedAttachments resolveCommentAttachments(String jiraKey, JSONObject comment) {
    Object rawBody = comment.opt("body");
    List<String> mediaIds = new ArrayList<>();
    SupportAdfAttachmentCorrelator.collectAdfMediaIds(rawBody, mediaIds);
    List<WikiMarkupImageRef> wikiRefs = (rawBody instanceof String)
        ? extractWikiMarkupAttachmentRefs((String) rawBody)
        : Collections.emptyList();
    if (mediaIds.isEmpty() && wikiRefs.isEmpty()) {
      return new ResolvedAttachments(null, Collections.emptyList());
    }

    Date commentTime = parseCommentTimestamp(comment);
    JSONArray issueAttachments = fetchIssueAttachments(jiraKey);
    // Nothing to correlate against — skip the (avoidable) DB round-trip below.
    if (issueAttachments.length() == 0) return new ResolvedAttachments(null, Collections.emptyList());

    JSONArray resolved = new JSONArray();
    if (!mediaIds.isEmpty()) {
      Set<String> alreadyLinkedIds = SupportAdfAttachmentCorrelator.findAlreadyLinkedAttachmentIds(jiraKey);
      JSONArray adfResolved =
          SupportAdfAttachmentCorrelator.correlateAttachments(issueAttachments, mediaIds, commentTime, alreadyLinkedIds);
      for (int i = 0; i < adfResolved.length(); i++) {
        resolved.put(adfResolved.opt(i));
      }
    }

    List<String> resolvedWikiMarkupTokens = new ArrayList<>();
    for (WikiMarkupImageRef ref : wikiRefs) {
      JSONObject match = findAttachmentByFilename(issueAttachments, ref.filename);
      if (match != null) {
        resolved.put(SupportAdfAttachmentCorrelator.toAttachmentMeta(match));
        resolvedWikiMarkupTokens.add(ref.token);
      }
    }

    return new ResolvedAttachments(resolved.length() > 0 ? resolved : null, resolvedWikiMarkupTokens);
  }

  /** Exact {@code filename} match against {@code issueAttachments} — used for the wiki-markup
   * correlation, which (unlike the ADF media-id path) has no ambiguous id mapping to resolve, so a
   * direct filename match is sufficient and unambiguous. Returns the first match, or {@code null}
   * when none of the issue's attachments has that filename. */
  static JSONObject findAttachmentByFilename(JSONArray issueAttachments, String filename) {
    for (int i = 0; i < issueAttachments.length(); i++) {
      JSONObject att = issueAttachments.optJSONObject(i);
      if (att != null && filename.equals(att.optString(FIELD_FILENAME, ""))) return att;
    }
    return null;
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
    JiraConfig config = JiraConfig.fromRuntime();
    if (!config.isConfigured()) {
      log.warn("Cannot resolve Jira attachments for {}: Jira integration is not configured", jiraKey);
      return new JSONArray();
    }
    try {
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(config.getUrl() + "/rest/api/3/issue/" + jiraKey + "?fields=attachment"))
          .header(HEADER_AUTHORIZATION, "Basic " + config.basicAuthCredentials())
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

  // --- Authenticated attachment content proxy (used by SupportConversationsServlet) ---

  /** {@code GET /rest/api/3/attachment/content/{id}} — streams the raw bytes back to the caller.
   * Package-private: invoked by {@link SupportConversationsServlet}'s {@code /attachments/{id}}
   * endpoint after it has verified the requesting user owns the conversation the attachment
   * belongs to. Returns {@code null} on any failure (missing token, network error, non-2xx) —
   * the caller is expected to turn that into a 404/500 rather than leak Jira's error body. */
  static HttpResponse<InputStream> fetchAttachmentContent(String jiraAttachmentId) {
    JiraConfig config = JiraConfig.fromRuntime();
    if (!config.isConfigured()) {
      log.warn("Cannot proxy Jira attachment {}: Jira integration is not configured", jiraAttachmentId);
      return null;
    }
    try {
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(config.getUrl() + "/rest/api/3/attachment/content/" + jiraAttachmentId))
          .header(HEADER_AUTHORIZATION, "Basic " + config.basicAuthCredentials())
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
