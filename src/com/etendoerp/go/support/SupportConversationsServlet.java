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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

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
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.common.ConfigPropertyReader;
import com.etendoerp.go.common.EtendoGoCorsServlet;
import com.etendoerp.go.common.ProtocolErrorAdapters;
import com.etendoerp.go.schemaforge.data.SupportConversation;
import com.etendoerp.go.schemaforge.data.SupportMessage;
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
 * Conversations and messages are persisted via OBDal against the AD-registered entities
 * {@link SupportConversation}/{@link SupportMessage} (module com.etendoerp.go, DataAccessLevel
 * Client/Organization). Every new row is tagged with a real ad_client_id/ad_org_id resolved
 * from the JWT's client/organization claims (same claims NeoServletSupport already reads
 * elsewhere in this module), instead of being untenanted.
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
  private static final String FIELD_UNREAD      = "unread";
  private static final String FIELD_RATED       = "rated";
  private static final String FIELD_ATTACHMENTS = "attachments";
  private static final String MSG_INTERNAL_ERROR = "Internal error";
  private static final String MSG_CONVERSATION_NOT_FOUND = "Conversation not found";
  private static final String MSG_ATTACHMENT_NOT_FOUND = "Attachment not found";
  private static final String HEADER_INTERNAL_SECRET = "X-Internal-Secret";
  private static final String MSG_INVALID_SECRET = "Invalid secret";
  /** Package-private: also used by {@link SupportAttachmentHelpers}. */
  static final String FIELD_MIME_TYPE   = "mimeType";
  /** Package-private: also used by {@link SupportAttachmentHelpers}. */
  static final String DEFAULT_MIME_TYPE = "application/octet-stream";
  private static final String FIELD_JIRA_TICKET_KEY = "jiraTicketKey";

  private static final String SENDER_AI    = "ai";
  private static final String SENDER_USER  = "user";
  private static final String STATUS_OPEN  = "open";
  private static final String STATUS_CLOSED = "closed";
  private static final String AI_AGENT_NAME = "ValerIA";
  private static final String AI_STUB_REPLY =
      "Hola, soy ValerIA. En este momento no puedo conectarme con el servicio de IA. Por favor intenta de nuevo en un momento.";

  /** Prepended to the outgoing message text (stripped by the ADK before it ever reaches a model
   * or gets echoed anywhere — never persisted, never shown) on the first turn after a
   * conversation is reopened, so the ADK resets ALL of its turn-scoped ticket/escalation state
   * (pending_escalation, human_takeover, jira_ticket_key, previous_jira_ticket_key,
   * ticket_turn_count, description_updated, ticket_priority) for THIS SAME turn — see the
   * {@code justReopened} block in {@link #handleSendMessage}. {@code stateDelta} sent alongside
   * a {@code /run} call is NOT reliably visible to that same turn's ADK callbacks (verified —
   * see {@link SupportIntegrationClient#createAdkSession}'s docstring: a first attempt at this
   * reset relied on stateDelta for jira_ticket_key alone, and no new ticket ever got created on
   * reopen because of it — same lag). A marker in the message text IS reliably visible
   * immediately, since it travels as real message content rather than through the session-state
   * layer. {@code %s} is the previous (now-superseded) Jira ticket key, so the new ticket's
   * description/link can reference it. Must match {@code _RESET_MARKER_RE} in the ADK's
   * {@code agent/callbacks.py}. */
  static final String RESET_TICKET_CONTEXT_MARKER_FORMAT = " VALERIA_RESET_TICKET_CONTEXT(%s) ";

  private static final String WEBHOOK_SECRET = ConfigPropertyReader.readConfigValue(
      "support.webhook.secret", "ETGO_SUPPORT_WEBHOOK_SECRET", "");

  /** {@code AD_User_ID} used as {@code createdBy}/{@code updatedBy} for system-triggered
   * inserts that have no authenticated requester (inbound Jira webhook).
   * Package-private: also used by {@link SupportJiraWebhookHandler}. */
  static final String SYSTEM_USER_ID = "0";

  private static final DateTimeFormatter ISO_NO_ZONE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

  // --- HTTP dispatchers ---

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    AuthContext ctx = authenticate(request, response);
    if (ctx == null) return;

    String pathInfo = request.getPathInfo();
    if (pathInfo == null) pathInfo = "/";

    if ("/conversations".equals(pathInfo) || "/conversations/".equals(pathInfo)) {
      handleListConversations(response, ctx);
      return;
    }

    String[] parts = pathInfo.split("/");
    if (parts.length == 4 && FIELD_CONVERSATIONS.equals(parts[1]) && FIELD_MESSAGES.equals(parts[3])) {
      handleGetMessages(response, ctx, parts[2]);
      return;
    }
    if (parts.length == 3 && FIELD_ATTACHMENTS.equals(parts[1])) {
      handleGetAttachment(response, ctx, parts[2]);
      return;
    }

    writeError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + pathInfo);
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String pathInfo = request.getPathInfo();
    if (pathInfo == null) pathInfo = "/";

    if (dispatchInternalRoute(pathInfo, request, response)) return;

    AuthContext ctx = authenticate(request, response);
    if (ctx == null) return;

    if ("/conversations".equals(pathInfo) || "/conversations/".equals(pathInfo)) {
      handleCreateConversation(request, response, ctx);
      return;
    }

    if (dispatchConversationAction(pathInfo, request, response, ctx)) return;

    writeError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + pathInfo);
  }

  /** Unauthenticated internal / webhook endpoints. Returns true if the request was handled. */
  private boolean dispatchInternalRoute(String pathInfo, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if ("/jira-webhook".equals(pathInfo)) {
      SupportJiraWebhookHandler.handle(request, response);
      return true;
    }
    if ("/internal/set-ticket".equals(pathInfo)) {
      handleSetTicket(request, response);
      return true;
    }
    if ("/internal/set-human-takeover".equals(pathInfo)) {
      handleSetHumanTakeover(request, response);
      return true;
    }
    if ("/internal/reset-human-takeover".equals(pathInfo)) {
      handleResetHumanTakeover(request, response);
      return true;
    }
    return false;
  }

  /** Routes /conversations/:id/{messages,rating,close,reopen}. Returns true if handled. */
  private boolean dispatchConversationAction(String pathInfo, HttpServletRequest request,
      HttpServletResponse response, AuthContext ctx) throws IOException {
    String[] parts = pathInfo.split("/");
    if (parts.length < 4 || !FIELD_CONVERSATIONS.equals(parts[1])) return false;
    String convId = parts[2];
    String action = parts[3];
    if (FIELD_MESSAGES.equals(action)) {
      handleSendMessage(request, response, ctx, convId);
      return true;
    }
    if ("rating".equals(action)) {
      handleSubmitRating(request, response, ctx, convId);
      return true;
    }
    if ("close".equals(action)) {
      handleCloseConversation(response, ctx, convId);
      return true;
    }
    if ("reopen".equals(action)) {
      handleReopenConversation(response, ctx, convId);
      return true;
    }
    return false;
  }

  // --- Endpoint handlers ---

  private void handleListConversations(HttpServletResponse response, AuthContext ctx)
      throws IOException {
    OBContext previous = enterTenantAdminMode(ctx);
    try {
      User user = OBDal.getInstance().get(User.class, ctx.userId);
      OBCriteria<SupportConversation> crit = OBDal.getInstance().createCriteria(SupportConversation.class);
      crit.add(Restrictions.eq(SupportConversation.PROPERTY_USER, user));
      crit.addOrderBy(SupportConversation.PROPERTY_LASTACTIVITY, false);
      JSONArray arr = new JSONArray();
      for (SupportConversation conv : crit.list()) {
        arr.put(toConvSummaryJson(conv));
      }
      JSONObject result = new JSONObject();
      result.put(FIELD_CONVERSATIONS, arr);
      writeJson(response, HttpServletResponse.SC_OK, result);
    } catch (Exception e) {
      log.error("Error listing conversations for user {}", ctx.userId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    } finally {
      restoreTenantContext(previous);
    }
  }

  private void handleCreateConversation(HttpServletRequest request, HttpServletResponse response,
      AuthContext ctx) throws IOException {
    String userId = ctx.userId;
    JSONObject body = parseBody(request, response);
    if (body == null) return;

    String firstMessage = body.optString(FIELD_MESSAGE, "").trim();
    JSONArray attachments = body.optJSONArray(FIELD_ATTACHMENTS);
    boolean hasAttachments = attachments != null && attachments.length() > 0;
    if (firstMessage.isEmpty() && !hasAttachments) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Message must have text or at least one attachment");
      return;
    }

    OBContext previous = enterTenantAdminMode(ctx);
    try {
      User user = OBDal.getInstance().get(User.class, userId);
      Client client = OBDal.getInstance().get(Client.class, ctx.clientId);
      Organization org = OBDal.getInstance().get(Organization.class, ctx.orgId);
      String subject = firstMessage.length() > 60
          ? firstMessage.substring(0, 60) + "…" : firstMessage;
      Date now = new Date();

      SupportConversation conv = OBProvider.getInstance().get(SupportConversation.class);
      conv.setNewOBObject(true);
      conv.setId(newId());
      conv.setClient(client);
      conv.setOrganization(org);
      conv.setUser(user);
      conv.setSubject(subject);
      conv.setStatus(STATUS_OPEN);
      conv.setLastActivity(now);
      OBDal.getInstance().save(conv);

      // Insert user message
      saveMessage(conv, SENDER_USER, "Tú", firstMessage, now, user, attachments);

      // Commit before calling ADK so background set-ticket can see the new conversation row
      // (the ADK's callback arrives via a separate HTTP request/transaction). Uses
      // SessionHandler directly (not a raw JDBC commit) so the Hibernate Transaction stays in
      // sync and the messages saved afterwards are actually committed at end of request.
      SessionHandler.getInstance().commitAndStart();

      // AI reply
      String locale = body.optString("locale", "es");
      String userEmail = SupportIntegrationClient.getUserEmail(userId);
      SupportIntegrationClient.createAdkSession(userId, conv.getId(), locale, userEmail);
      String aiReplyText = SupportIntegrationClient.sendToAdk(userId, conv.getId(), firstMessage, attachments);
      if (aiReplyText == null) aiReplyText = AI_STUB_REPLY;

      Date aiNow = new Date();
      saveMessage(conv, SENDER_AI, AI_AGENT_NAME, aiReplyText, aiNow, user);
      updateConvSummary(conv.getId(), aiReplyText, aiNow);

      JSONObject result = new JSONObject();
      result.put(FIELD_CONVERSATION, toConvSummaryJson(conv));
      result.put(FIELD_MESSAGES, buildMessageArray(conv.getId()));
      writeJson(response, HttpServletResponse.SC_CREATED, result);
    } catch (Exception e) {
      log.error("Error creating conversation for user {}", userId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    } finally {
      restoreTenantContext(previous);
    }
  }

  private void handleGetMessages(HttpServletResponse response, AuthContext ctx, String convId)
      throws IOException {
    OBContext previous = enterTenantAdminMode(ctx);
    try {
      SupportConversation conv = OBDal.getInstance().get(SupportConversation.class, convId);
      if (!belongsToUser(conv, ctx.userId)) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
        return;
      }
      conv.setUnread(false);
      OBDal.getInstance().save(conv);
      JSONObject result = new JSONObject();
      result.put(FIELD_MESSAGES, buildMessageArray(convId));
      writeJson(response, HttpServletResponse.SC_OK, result);
    } catch (Exception e) {
      log.error("Error loading messages for conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    } finally {
      restoreTenantContext(previous);
    }
  }

  /**
   * Authenticated proxy for a Jira attachment's raw content ({@code GET
   * /sws/support/attachments/:jiraAttachmentId}). Authorization mirrors every other
   * conversation-scoped endpoint in this servlet: the attachment id must appear in some
   * {@link SupportMessage#getAttachments()} row, and that message's conversation must belong to
   * the requesting user ({@link #belongsToUser}) — otherwise this returns 404, same as a
   * not-found conversation, so the endpoint does not leak whether the id exists at all.
   * <p>
   * The lookup itself runs in admin mode across every client (like the Jira webhook's own
   * lookups) because the caller's JWT tenant may not be the same tenant the message was stamped
   * with when the human agent's reply was ingested; ownership is enforced afterwards by the
   * explicit {@code belongsToUser} check, not by client scoping.
   */
  private void handleGetAttachment(HttpServletResponse response, AuthContext ctx, String jiraAttachmentId)
      throws IOException {
    OBContext.setAdminMode(true);
    try {
      SupportMessage msg = SupportAttachmentHelpers.findMessageByAttachmentId(jiraAttachmentId);
      if (msg == null || !belongsToUser(msg.getConversation(), ctx.userId)) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_ATTACHMENT_NOT_FOUND);
        return;
      }
      JSONObject meta = SupportAttachmentHelpers.findAttachmentMeta(msg, jiraAttachmentId);
      String filename = meta != null ? meta.optString("filename", jiraAttachmentId) : jiraAttachmentId;
      String mimeType = meta != null ? meta.optString(FIELD_MIME_TYPE, DEFAULT_MIME_TYPE)
          : DEFAULT_MIME_TYPE;
      SupportAttachmentHelpers.streamJiraAttachment(response, jiraAttachmentId, filename, mimeType);
    } catch (Exception e) {
      log.error("Error proxying Jira attachment {}", jiraAttachmentId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void handleSendMessage(HttpServletRequest request, HttpServletResponse response,
      AuthContext ctx, String convId) throws IOException {
    JSONObject body = parseBody(request, response);
    if (body == null) return;

    String text = body.optString("text", "").trim();
    JSONArray attachments = body.optJSONArray(FIELD_ATTACHMENTS);
    boolean hasAttachments = attachments != null && attachments.length() > 0;
    if (text.isEmpty() && !hasAttachments) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Message must have text or at least one attachment");
      return;
    }

    String userId = ctx.userId;
    OBContext previous = enterTenantAdminMode(ctx);
    try {
      SupportConversation conv = OBDal.getInstance().get(SupportConversation.class, convId);
      if (!belongsToUser(conv, userId)) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
        return;
      }
      if (STATUS_CLOSED.equals(conv.getStatus())) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Conversation is closed");
        return;
      }

      User user = conv.getUser();
      Date now = new Date();
      saveMessage(conv, SENDER_USER, "Tú", text, now, user, attachments);

      // If ticket is assigned to a human agent, block AI response
      if (Boolean.TRUE.equals(conv.isHumanTakeover())) {
        // Human agent is handling this — forward user message to Jira, send no AI reply.
        // An attachment-only message (no text) needs a descriptive fallback: Jira rejects a
        // blank comment body, so without this the message would silently never reach the ticket.
        final String finalKey = conv.getJiraTicketKey();
        final String finalText = text.isEmpty()
            ? SupportIntegrationClient.describeAttachments(attachments)
            : text;
        // Public: the customer will see this reflected in the portal, same as any other reply
        // they post there directly — this is just their own message, forwarded.
        new Thread(() -> SupportIntegrationClient.postJiraComment(finalKey, finalText, false), "jira-comment").start();
        JSONObject result = new JSONObject();
        result.put(FIELD_MESSAGES,     buildMessageArray(convId));
        result.put(FIELD_CONVERSATION, toConvSummaryJson(conv));
        writeJson(response, HttpServletResponse.SC_OK, result);
        return;
      }

      // Sync human_takeover=false into ADK session state; flag tells agent to skip Jira re-check
      JSONObject stateDelta = new JSONObject()
          .put("human_takeover", false)
          .put("human_takeover_synced", true);
      // Conversation was reopened and no new ticket has been created yet (the ADK's own session
      // state still has the OLD, already-resolved ticket key cached — it has no idea the DB-side
      // link was cleared). Reset ALL of the ticket-lifecycle state via the text marker (not
      // stateDelta — see RESET_TICKET_CONTEXT_MARKER_FORMAT's javadoc) so jira_before_agent
      // creates a fresh ticket on THIS SAME turn instead of continuing to comment on the resolved
      // one, same as it would for a brand-new conversation. Self-limiting: once the ADK creates
      // the new ticket it calls back into /internal/set-ticket, which sets conv.jiraTicketKey
      // again — so this branch stops applying after the first turn.
      String textForAdk = text;
      if (conv.getJiraTicketKey() == null && conv.getPreviousJiraTicketKey() != null) {
        textForAdk = String.format(RESET_TICKET_CONTEXT_MARKER_FORMAT, conv.getPreviousJiraTicketKey())
            + textForAdk;
      }
      String aiReplyText = SupportIntegrationClient.sendToAdk(userId, convId, textForAdk, attachments, stateDelta);
      if (aiReplyText == null) aiReplyText = AI_STUB_REPLY;

      Date aiNow = new Date();
      saveMessage(conv, SENDER_AI, AI_AGENT_NAME, aiReplyText, aiNow, user);
      updateConvSummary(convId, aiReplyText, aiNow);

      JSONObject result = new JSONObject();
      result.put(FIELD_MESSAGES,     buildMessageArray(convId));
      result.put(FIELD_CONVERSATION, toConvSummaryJson(conv));
      writeJson(response, HttpServletResponse.SC_OK, result);
    } catch (Exception e) {
      log.error("Error sending message to conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    } finally {
      restoreTenantContext(previous);
    }
  }

  private void handleSubmitRating(HttpServletRequest request, HttpServletResponse response,
      AuthContext ctx, String convId) throws IOException {
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

    OBContext previous = enterTenantAdminMode(ctx);
    try {
      SupportConversation conv = OBDal.getInstance().get(SupportConversation.class, convId);
      if (!belongsToUser(conv, ctx.userId)) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
        return;
      }
      String comment = body.optString("comment", "").trim();
      conv.setRated(true);
      conv.setRatingScore((long) score);
      conv.setRatingComment(comment);
      OBDal.getInstance().save(conv);

      final String finalJiraKey = conv.getJiraTicketKey();
      final int finalScore = score;
      final String finalComment = comment;
      new Thread(() -> {
        // Internal: the CSAT rating/comment is for the support team, never customer-facing.
        SupportIntegrationClient.postJiraComment(finalJiraKey,
            SupportIntegrationClient.buildFeedbackComment(finalScore, finalComment), true);
        SupportIntegrationClient.postJiraCsatLabel(finalJiraKey, finalScore);
      }, "jira-csat-feedback").start();

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, "success");
      writeJson(response, HttpServletResponse.SC_OK, result);
    } catch (Exception e) {
      log.error("Error submitting rating for conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    } finally {
      restoreTenantContext(previous);
    }
  }

  private void handleCloseConversation(HttpServletResponse response, AuthContext ctx, String convId)
      throws IOException {
    OBContext previous = enterTenantAdminMode(ctx);
    try {
      SupportConversation conv = OBDal.getInstance().get(SupportConversation.class, convId);
      if (!belongsToUser(conv, ctx.userId)) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
        return;
      }
      conv.setStatus(STATUS_CLOSED);
      OBDal.getInstance().save(conv);
      JSONObject result = new JSONObject();
      result.put(FIELD_CONVERSATION, toConvSummaryJson(conv));
      writeJson(response, HttpServletResponse.SC_OK, result);
    } catch (Exception e) {
      log.error("Error closing conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    } finally {
      restoreTenantContext(previous);
    }
  }

  private void handleReopenConversation(HttpServletResponse response, AuthContext ctx, String convId)
      throws IOException {
    OBContext previous = enterTenantAdminMode(ctx);
    try {
      SupportConversation conv = OBDal.getInstance().get(SupportConversation.class, convId);
      if (!belongsToUser(conv, ctx.userId)) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
        return;
      }
      Date now = new Date();
      conv.setStatus(STATUS_OPEN);
      conv.setLastActivity(now);
      // Same chat thread and ADK session on purpose — the agent keeps the full conversation
      // history for free (it's the same session state, never reset). But the OLD Jira ticket
      // was already resolved/closed, so we must not keep commenting on it: unlink it here
      // (preserved as previousJiraTicketKey for traceability/context) and let the ADK create a
      // fresh ticket on the next real turn, same as it does for a brand-new conversation (see
      // jira_before_agent in the ADK, which only creates a ticket when state has none).
      String oldJiraKey = conv.getJiraTicketKey();
      if (oldJiraKey != null && !oldJiraKey.isEmpty()) {
        conv.setPreviousJiraTicketKey(oldJiraKey);
        conv.setJiraTicketKey(null);
      }
      conv.setHumanTakeover(false);
      OBDal.getInstance().save(conv);
      // System message to mark reopen in the thread
      saveMessage(conv, SENDER_AI, AI_AGENT_NAME,
          "La conversación ha sido reabierta. ¿En qué más puedo ayudarte?", now, conv.getUser());
      JSONObject result = new JSONObject();
      result.put(FIELD_CONVERSATION, toConvSummaryJson(conv));
      result.put(FIELD_MESSAGES, buildMessageArray(convId));
      writeJson(response, HttpServletResponse.SC_OK, result);
    } catch (Exception e) {
      log.error("Error reopening conversation {}", convId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    } finally {
      restoreTenantContext(previous);
    }
  }

  // --- Auth ---

  /** JWT-derived identity for a request: the AD_User plus the client/org used both to stamp new
   * rows and to switch the ambient {@code OBContext} (same claims
   * {@link com.etendoerp.go.schemaforge.NeoServletSupport} uses to build a real context). */
  private static final class AuthContext {
    final String userId;
    final String roleId;
    final String clientId;
    final String orgId;

    AuthContext(String userId, String roleId, String clientId, String orgId) {
      this.userId = userId;
      this.roleId = roleId;
      this.clientId = clientId;
      this.orgId = orgId;
    }
  }

  /** Switches the ambient {@code OBContext} to the request's user/role/client/org so {@code OBDal}
   * criteria queries see this tenant's rows, then enters admin mode on top to bypass entity-level
   * access checks. The real role (not System Administrator) matters here: {@code OBContext}
   * computes readable clients from {@code role.getUserLevel()} — a System-level role (like role
   * "0") always resolves readable clients to {@code ["0"]} regardless of the clientId passed in,
   * silently hiding every other client's rows even with {@code setAdminMode(true)} active. */
  private static OBContext enterTenantAdminMode(AuthContext ctx) {
    OBContext previous = OBContext.getOBContext();
    OBContext.setOBContext(ctx.userId, ctx.roleId, ctx.clientId, ctx.orgId);
    OBContext.setAdminMode(true);
    return previous;
  }

  private static void restoreTenantContext(OBContext previous) {
    OBContext.restorePreviousMode();
    OBContext.setOBContext(previous);
  }

  private AuthContext authenticate(HttpServletRequest request, HttpServletResponse response)
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
      String roleId = jwt.getClaim("role").asString();
      if (roleId == null || roleId.isEmpty()) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid token: missing role claim");
        return null;
      }
      String clientId = jwt.getClaim("client").asString();
      String orgId = jwt.getClaim("organization").asString();
      return new AuthContext(userId, roleId,
          clientId == null || clientId.isEmpty() ? SYSTEM_USER_ID : clientId,
          orgId == null || orgId.isEmpty() ? SYSTEM_USER_ID : orgId);
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
    String jiraKey     = body.optString(FIELD_JIRA_TICKET_KEY, "");
    // Optional: the reporter's Jira accountId, resolved once at ticket-creation time (see
    // jira_client.get_reporter_account_id on the ADK side). accountId is always present in a
    // webhook payload regardless of the account's profile-privacy settings, unlike emailAddress
    // (which Jira Cloud omits for private-profile accounts — confirmed true for both our own
    // bot account AND real customer accounts in production). This is what lets
    // SupportJiraWebhookHandler#storeJiraWebhookComment recognize "the reporter replied via
    // email/portal to their own ticket" and display it as their own chat message (sender=
    // "user") instead of a different human agent's reply — never filtered/skipped.
    String reporterAccountId = body.optString("reporterAccountId", "");
    if (convId.isEmpty() || jiraKey.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "conversationId and jiraTicketKey required");
      return;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        SupportConversation conv = OBDal.getInstance().get(SupportConversation.class, convId);
        if (conv == null) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
          return;
        }
        conv.setJiraTicketKey(jiraKey);
        if (!reporterAccountId.isEmpty()) {
          conv.setJiraReporterAccountId(reporterAccountId);
        }
        OBDal.getInstance().save(conv);
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
        SupportConversation conv = OBDal.getInstance().get(SupportConversation.class, convId);
        if (conv == null) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
          return;
        }
        conv.setHumanTakeover(true);
        OBDal.getInstance().save(conv);
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
    String jiraKey = body.optString(FIELD_JIRA_TICKET_KEY, "");
    if (convId.isEmpty() && jiraKey.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "conversationId or jiraTicketKey required");
      return;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        List<SupportConversation> matches;
        if (!convId.isEmpty()) {
          SupportConversation conv = OBDal.getInstance().get(SupportConversation.class, convId);
          matches = conv == null ? List.of() : List.of(conv);
        } else {
          // System/webhook call with no per-request tenant to scope to — must see every client's
          // conversations to resolve the Jira ticket key.
          OBCriteria<SupportConversation> crit = OBDal.getInstance().createCriteria(SupportConversation.class);
          crit.setFilterOnReadableClients(false);
          crit.setFilterOnReadableOrganization(false);
          crit.add(Restrictions.eq(SupportConversation.PROPERTY_JIRATICKETKEY, jiraKey));
          matches = crit.list();
        }
        if (matches.isEmpty()) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, MSG_CONVERSATION_NOT_FOUND);
          return;
        }
        for (SupportConversation conv : matches) {
          conv.setHumanTakeover(false);
          OBDal.getInstance().save(conv);
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

  // --- DB helpers ---

  private static boolean belongsToUser(SupportConversation conv, String userId) {
    return conv != null && conv.getUser() != null && userId.equals(conv.getUser().getId());
  }

  private static void saveMessage(SupportConversation conv, String sender, String senderName,
      String text, Date timestamp, User createdBy) {
    saveMessage(conv, sender, senderName, text, timestamp, createdBy, null);
  }

  /** Same as the 5-arg {@link #saveMessage} but also persists the sender's own outgoing
   * attachments (request wire format {@code [{name, mimeType, data}]}) as the {@code [{filename,
   * mimeType}]} shape the frontend's {@code AttachmentItem} already renders for Jira-sourced
   * attachments — no {@code id}, since there is no fetchable Jira attachment id for the user's
   * own just-sent message. Mirrors {@link SupportJiraWebhookHandler#insertJiraMessage}'s
   * null-vs-empty convention: the column is left {@code null} (never an empty-array string) when
   * {@code attachments} is null/empty/all-malformed. */
  private static void saveMessage(SupportConversation conv, String sender, String senderName,
      String text, Date timestamp, User createdBy, JSONArray attachments) {
    SupportMessage msg = OBProvider.getInstance().get(SupportMessage.class);
    msg.setNewOBObject(true);
    msg.setId(newId());
    msg.setClient(conv.getClient());
    msg.setOrganization(conv.getOrganization());
    msg.setCreatedBy(createdBy);
    msg.setUpdatedBy(createdBy);
    msg.setConversation(conv);
    msg.setSender(sender);
    msg.setSenderName(senderName);
    msg.setText(text);
    msg.setMessageDate(timestamp);
    String attachmentsJson = SupportAttachmentHelpers.buildOutgoingAttachmentsJson(attachments);
    if (attachmentsJson != null) {
      msg.setAttachments(attachmentsJson);
    }
    OBDal.getInstance().save(msg);
  }

  /** Package-private: also used by {@link SupportJiraWebhookHandler} when storing an inbound Jira comment. */
  static void updateConvSummary(String convId, String lastMsg, Date ts) {
    SupportConversation conv = OBDal.getInstance().get(SupportConversation.class, convId);
    conv.setLastMessage(lastMsg.length() > 120 ? lastMsg.substring(0, 120) + "…" : lastMsg);
    conv.setLastActivity(ts);
    OBDal.getInstance().save(conv);
  }

  private JSONObject toConvSummaryJson(SupportConversation conv) throws JSONException {
    JSONObject obj = new JSONObject();
    obj.put("id",           conv.getId());
    obj.put(FIELD_SUBJECT,  conv.getSubject());
    obj.put(FIELD_STATUS,   conv.getStatus());
    obj.put("lastActivity", toIso(conv.getLastActivity()));
    obj.put("lastMessage",  conv.getLastMessage() != null ? conv.getLastMessage() : "");
    obj.put(FIELD_UNREAD,   Boolean.TRUE.equals(conv.isUnread()));
    obj.put(FIELD_RATED,    Boolean.TRUE.equals(conv.isRated()));
    // Customers now receive this key via the JSM ticket-notification email, so they may search
    // for a conversation by it — see MensajesTab's search filter in SupportChatWidget.jsx.
    obj.put(FIELD_JIRA_TICKET_KEY, conv.getJiraTicketKey() != null ? conv.getJiraTicketKey() : "");
    return obj;
  }

  private JSONArray buildMessageArray(String convId) throws JSONException {
    // The session flush mode is COMMIT (not AUTO), so a criteria query run in the same
    // transaction as an earlier saveMessage() would not see it without an explicit flush first.
    OBDal.getInstance().flush();
    JSONArray arr = new JSONArray();
    OBCriteria<SupportMessage> crit = OBDal.getInstance().createCriteria(SupportMessage.class);
    crit.add(Restrictions.eq(SupportMessage.PROPERTY_CONVERSATION + ".id", convId));
    crit.addOrderBy(SupportMessage.PROPERTY_MESSAGEDATE, true);
    for (SupportMessage msg : crit.list()) {
      JSONObject json = new JSONObject();
      json.put("id",             msg.getId());
      json.put(FIELD_CONVERSATION_ID, convId);
      json.put("sender",         msg.getSender());
      json.put("senderName",     msg.getSenderName());
      json.put("text",           msg.getText());
      json.put("timestamp",      toIso(msg.getMessageDate()));
      json.put(FIELD_ATTACHMENTS, SupportAttachmentHelpers.parseAttachments(msg.getAttachments()));
      arr.put(json);
    }
    return arr;
  }

  /** Formats a naive local-time {@link Date} (columns are TIMESTAMP WITHOUT TIME ZONE) as
   * millisecond-precision ISO-8601 with no zone suffix, matching the JSON contract the frontend
   * already parses as a wall-clock value. */
  private static String toIso(Date date) {
    if (date == null) return null;
    return date.toInstant().atZone(ZoneId.systemDefault()).format(ISO_NO_ZONE);
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
    // The session flush mode is COMMIT (not AUTO); flushing here — instead of trusting the
    // end-of-request auto-commit — is what makes pending saves in this response durable.
    OBDal.getInstance().flush();
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
