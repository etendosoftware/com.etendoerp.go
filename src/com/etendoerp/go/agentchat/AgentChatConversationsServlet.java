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

package com.etendoerp.go.agentchat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.copilot.util.ConversationUtils;
import com.etendoerp.go.common.CorsUtils;
import com.etendoerp.go.common.JwtAuthUtils;
import com.etendoerp.go.common.ServletResponseUtils;

/**
 * History of the Etendo agent chat, stored in the Copilot conversation tables
 * ({@code ETCOP_CONVERSATION} / {@code ETCOP_MESSAGE}) so it is the same history the legacy
 * Copilot panel shows.
 *
 * <p>Mapped to {@code /sws/agent-chat/*} via AD_MODEL_OBJECT_MAPPING. It exists because
 * {@code /sws/copilot/*} authenticates with a bearer JWT only and answers 401 to the Etendo Go
 * cookie session, while every servlet of this module authenticates through
 * {@link JwtAuthUtils#authenticateOrFail} (cookie session plus CSRF proof for unsafe methods, or
 * the legacy Bearer). The persistence itself is NOT reimplemented here: it is
 * {@link ConversationUtils}, the same code the Copilot module uses.
 *
 * <p>Every operation acts on the session user's own conversations. Unlike the legacy by-id
 * Copilot endpoints, reading, renaming, archiving, restoring and deleting a conversation that
 * belongs to somebody else is refused (reported as 404, exactly like a missing one).
 *
 * <pre>
 * GET  /sws/agent-chat/conversations                       active conversations with no app
 * GET  /sws/agent-chat/conversations/archived              archived ones
 * GET  /sws/agent-chat/conversations/{id}/messages         messages, oldest first
 * POST /sws/agent-chat/conversations                       {title?, external_id?, app_id?}
 * POST /sws/agent-chat/conversations/{id}/messages         {messages:[{role,text,external_id?,metadata?}]}
 * POST /sws/agent-chat/conversations/{id}/rename           {title}
 * POST /sws/agent-chat/conversations/{id}/archive
 * POST /sws/agent-chat/conversations/{id}/restore
 * POST /sws/agent-chat/conversations/{id}/permanent-delete
 * </pre>
 *
 * <p>Contract details: {@code docs/conversation-write-api.md} in the Copilot module and
 * {@code docs/agent-chat-api.md} here.
 */
public class AgentChatConversationsServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(AgentChatConversationsServlet.class);
  private static final String ALLOWED_METHODS = "GET, POST, OPTIONS";
  private static final String ALLOWED_HEADERS = "Authorization, Content-Type, X-Go-CSRF";
  private static final String CONVERSATIONS = "conversations";
  private static final String ARCHIVED = "archived";
  private static final String MESSAGES = "messages";
  private static final String CONVERSATION_ID = "conversation_id";
  private static final String MSG_INTERNAL_ERROR = "Internal error";

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, ALLOWED_METHODS, ALLOWED_HEADERS, null, false);
    if (!JwtAuthUtils.authenticateOrFail(request, response, log, "agent-chat GET")) return;
    String[] parts = pathParts(request);
    execute(response, false, () -> {
      if (parts.length == 1 && CONVERSATIONS.equals(parts[0])) {
        return new JSONObject().put(CONVERSATIONS, ConversationUtils.getConversations(null));
      }
      if (parts.length == 2 && CONVERSATIONS.equals(parts[0]) && ARCHIVED.equals(parts[1])) {
        return new JSONObject().put(CONVERSATIONS, ConversationUtils.getArchivedConversations(null));
      }
      if (parts.length == 3 && CONVERSATIONS.equals(parts[0]) && MESSAGES.equals(parts[2])) {
        JSONArray messages = ConversationUtils.getOwnedConversationMessages(parts[1]);
        return new JSONObject().put(MESSAGES, messages);
      }
      return null;
    });
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, ALLOWED_METHODS, ALLOWED_HEADERS, null, false);
    if (!JwtAuthUtils.authenticateOrFail(request, response, log, "agent-chat POST")) return;
    String[] parts = pathParts(request);
    execute(response, true, () -> route(parts, readBody(request)));
  }

  @Override
  public void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, ALLOWED_METHODS, ALLOWED_HEADERS, null, false);
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  /** Routes a POST; returns null for an unknown path. Package-private for tests. */
  static JSONObject route(String[] parts, JSONObject body) throws JSONException {
    if (parts.length == 0 || !CONVERSATIONS.equals(parts[0])) {
      return null;
    }
    if (parts.length == 1) {
      return ConversationUtils.createConversation(body);
    }
    if (parts.length != 3) {
      return null;
    }
    String id = parts[1];
    switch (parts[2]) {
      case MESSAGES:
        // The path id wins over anything the body says: a body cannot redirect the write.
        return ConversationUtils.appendMessages(body.put(CONVERSATION_ID, id));
      case "rename":
        return ConversationUtils.renameOwnedConversation(id, body.optString("title", null));
      case "archive":
        return ConversationUtils.setOwnedConversationActive(id, false);
      case "restore":
        return ConversationUtils.setOwnedConversationActive(id, true);
      case "permanent-delete":
        return ConversationUtils.deleteOwnedConversation(id);
      default:
        return null;
    }
  }

  @FunctionalInterface
  interface Action {
    /** Returns the JSON to send, or null when the path is not an endpoint. */
    JSONObject run() throws JSONException, IOException;
  }

  /**
   * Runs an action in admin mode as the session user (already the {@code OBContext} user after
   * authentication) and maps the outcome to HTTP: 200 with the JSON, 404 for an unknown path or a
   * missing/foreign conversation, 400 for invalid input, 500 for anything else. Writes are
   * committed only on success.
   */
  private void execute(HttpServletResponse response, boolean write, Action action) throws IOException {
    try {
      OBContext.setAdminMode(true);
      JSONObject result = action.run();
      if (result == null) {
        OBDal.getInstance().rollbackAndClose();
        ServletResponseUtils.sendError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint");
        return;
      }
      if (write) {
        OBDal.getInstance().commitAndClose();
      }
      ServletResponseUtils.writeJson(response, HttpServletResponse.SC_OK, result);
    } catch (OBException e) {
      OBDal.getInstance().rollbackAndClose();
      int status = ConversationUtils.CONVERSATION_NOT_FOUND.equals(e.getMessage())
          ? HttpServletResponse.SC_NOT_FOUND : HttpServletResponse.SC_BAD_REQUEST;
      ServletResponseUtils.sendError(response, status, e.getMessage());
    } catch (JSONException e) {
      OBDal.getInstance().rollbackAndClose();
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
    } catch (Exception e) {
      log.error("agent-chat request failed: {}", e.getMessage(), e);
      OBDal.getInstance().rollbackAndClose();
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String[] pathParts(HttpServletRequest request) {
    String pathInfo = StringUtils.defaultString(request.getPathInfo());
    return StringUtils.split(pathInfo, '/');
  }

  private static JSONObject readBody(HttpServletRequest request) throws IOException, JSONException {
    String raw = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return StringUtils.isBlank(raw) ? new JSONObject() : new JSONObject(raw);
  }
}
