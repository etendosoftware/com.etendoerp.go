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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.copilot.util.ConversationUtils;
import com.etendoerp.copilot.util.ConversationWriteUtils;
import com.etendoerp.go.common.JwtAuthUtils;

/**
 * Tests for {@link AgentChatConversationsServlet}: routing, HTTP mapping and transaction handling.
 * The persistence and ownership rules live in {@link ConversationUtils} and
 * {@link ConversationWriteUtils} (tested in the Copilot module); here they are mocked.
 */
class AgentChatConversationsServletTest {

  private static final String C1 = "C1";
  private static final String PATH_CONVERSATIONS = "/conversations";
  private static final String KEY_CONVERSATIONS = "conversations";
  private static final String KEY_SUCCESS = "success";
  private static final String KEY_ID = "id";
  private static final String KEY_CONVERSATION_ID = "conversation_id";

  private MockedStatic<JwtAuthUtils> auth;
  private MockedStatic<ConversationUtils> conversations;
  private MockedStatic<ConversationWriteUtils> writes;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDal;
  private OBDal dal;
  private AgentChatConversationsServlet servlet;
  private HttpServletResponse response;
  private StringWriter out;

  @BeforeEach
  void setUp() throws Exception {
    auth = mockStatic(JwtAuthUtils.class);
    auth.when(() -> JwtAuthUtils.authenticateOrFail(any(), any(), any(), anyString())).thenReturn(true);
    conversations = mockStatic(ConversationUtils.class);
    writes = mockStatic(ConversationWriteUtils.class);
    obContext = mockStatic(OBContext.class);
    obDal = mockStatic(OBDal.class);
    dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    servlet = new AgentChatConversationsServlet();
    response = mock(HttpServletResponse.class);
    out = new StringWriter();
    when(response.getWriter()).thenAnswer(i -> new PrintWriter(out)); // the servlet closes the writer it gets
  }

  @AfterEach
  void tearDown() {
    auth.close();
    conversations.close();
    writes.close();
    obContext.close();
    obDal.close();
  }

  private HttpServletRequest request(String path, String body) throws IOException {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(path);
    byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream in = new ByteArrayInputStream(bytes);
    when(request.getInputStream()).thenReturn(new ServletInputStream() {
      @Override public int read() { return in.read(); }
      @Override public boolean isFinished() { return in.available() == 0; }
      @Override public boolean isReady() { return true; }
      @Override public void setReadListener(javax.servlet.ReadListener listener) { }
    });
    return request;
  }

  private void assertStatus(int status) {
    ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
    verify(response, org.mockito.Mockito.atLeastOnce()).setStatus(captor.capture());
    assertEquals(status, captor.getValue().intValue());
  }

  @Test
  void listsActiveAndArchivedConversationsWithNoApp() throws Exception {
    JSONArray active = new JSONArray().put(new JSONObject().put(KEY_ID, C1));
    conversations.when(() -> ConversationUtils.getConversations(null)).thenReturn(active);
    servlet.doGet(request(PATH_CONVERSATIONS, null), response);
    assertStatus(200);
    assertEquals(C1, new JSONObject(out.toString()).getJSONArray(KEY_CONVERSATIONS).getJSONObject(0).getString(KEY_ID));

    JSONArray archived = new JSONArray().put(new JSONObject().put(KEY_ID, "C9"));
    conversations.when(() -> ConversationUtils.getArchivedConversations(null)).thenReturn(archived);
    out.getBuffer().setLength(0);
    servlet.doGet(request("/conversations/archived", null), response);
    assertEquals("C9", new JSONObject(out.toString()).getJSONArray(KEY_CONVERSATIONS).getJSONObject(0).getString(KEY_ID));
    verify(dal, never()).commitAndClose();
  }

  @Test
  void readsMessagesThroughTheOwnerCheckedMethod() throws Exception {
    writes.when(() -> ConversationWriteUtils.getOwnedConversationMessages(C1))
        .thenReturn(new JSONArray().put(new JSONObject().put("content", "hola")));
    servlet.doGet(request(PATH_CONVERSATIONS + "/" + C1 + "/messages", null), response);
    assertStatus(200);
    assertEquals("hola", new JSONObject(out.toString()).getJSONArray("messages").getJSONObject(0).getString("content"));
  }

  @Test
  void refusesUnauthenticatedRequestsBeforeTouchingAnyData() throws Exception {
    auth.when(() -> JwtAuthUtils.authenticateOrFail(any(), any(), any(), anyString())).thenReturn(false);
    servlet.doGet(request(PATH_CONVERSATIONS, null), response);
    servlet.doPost(request(PATH_CONVERSATIONS, "{}"), response);
    conversations.verifyNoInteractions();
    writes.verifyNoInteractions();
    verify(dal, never()).commitAndClose();
  }

  @Test
  void createsAConversationAndCommits() throws Exception {
    writes.when(() -> ConversationWriteUtils.createConversation(any()))
        .thenReturn(new JSONObject().put(KEY_SUCCESS, true).put(KEY_CONVERSATION_ID, "X1"));
    servlet.doPost(request(PATH_CONVERSATIONS, "{\"external_id\":\"X1\",\"title\":\"t\"}"), response);
    assertStatus(200);
    ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
    writes.verify(() -> ConversationWriteUtils.createConversation(body.capture()));
    assertEquals("X1", body.getValue().getString("external_id"));
    verify(dal).commitAndClose();
  }

  @Test
  void appendUsesThePathIdAndIgnoresAConversationIdInTheBody() throws Exception {
    writes.when(() -> ConversationWriteUtils.appendMessages(any())).thenReturn(new JSONObject().put("saved", 1));
    servlet.doPost(request("/conversations/MINE/messages",
        "{\"conversation_id\":\"SOMEONE-ELSES\",\"messages\":[{\"role\":\"user\",\"text\":\"a\"}]}"), response);
    ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
    writes.verify(() -> ConversationWriteUtils.appendMessages(body.capture()));
    assertEquals("MINE", body.getValue().getString(KEY_CONVERSATION_ID));
    verify(dal).commitAndClose();
  }

  @Test
  void routesRenameArchiveRestoreAndPermanentDelete() throws Exception {
    JSONObject ok = new JSONObject().put(KEY_SUCCESS, true);
    writes.when(() -> ConversationWriteUtils.renameOwnedConversation(C1, "New")).thenReturn(ok);
    writes.when(() -> ConversationWriteUtils.setOwnedConversationActive(C1, false)).thenReturn(ok);
    writes.when(() -> ConversationWriteUtils.setOwnedConversationActive(C1, true)).thenReturn(ok);
    writes.when(() -> ConversationWriteUtils.deleteOwnedConversation(C1)).thenReturn(ok);

    servlet.doPost(request(PATH_CONVERSATIONS + "/" + C1 + "/rename", "{\"title\":\"New\"}"), response);
    servlet.doPost(request(PATH_CONVERSATIONS + "/" + C1 + "/archive", null), response);
    servlet.doPost(request(PATH_CONVERSATIONS + "/" + C1 + "/restore", null), response);
    servlet.doPost(request(PATH_CONVERSATIONS + "/" + C1 + "/permanent-delete", null), response);

    writes.verify(() -> ConversationWriteUtils.renameOwnedConversation(C1, "New"));
    writes.verify(() -> ConversationWriteUtils.setOwnedConversationActive(C1, false));
    writes.verify(() -> ConversationWriteUtils.setOwnedConversationActive(C1, true));
    writes.verify(() -> ConversationWriteUtils.deleteOwnedConversation(C1));
  }

  @Test
  void aMissingOrForeignConversationIsA404AndRollsBack() throws Exception {
    writes.when(() -> ConversationWriteUtils.deleteOwnedConversation(C1))
        .thenThrow(new OBException(ConversationUtils.CONVERSATION_NOT_FOUND));
    servlet.doPost(request(PATH_CONVERSATIONS + "/" + C1 + "/permanent-delete", null), response);
    assertStatus(404);
    assertEquals("Conversation not found", new JSONObject(out.toString()).getString("error"));
    verify(dal).rollbackAndClose();
    verify(dal, never()).commitAndClose();
  }

  @Test
  void invalidInputIsA400() throws Exception {
    writes.when(() -> ConversationWriteUtils.appendMessages(any()))
        .thenThrow(new OBException("Invalid role 'system'"));
    servlet.doPost(request(PATH_CONVERSATIONS + "/" + C1 + "/messages", "{\"messages\":[]}"), response);
    assertStatus(400);
    assertTrue(out.toString().contains("Invalid role"));
  }

  @Test
  void aMalformedJsonBodyIsA400() throws Exception {
    servlet.doPost(request(PATH_CONVERSATIONS, "{not json"), response);
    assertStatus(400);
    conversations.verifyNoInteractions();
    writes.verifyNoInteractions();
  }

  @Test
  void unknownPathsAre404() throws Exception {
    servlet.doGet(request("/nope", null), response);
    assertStatus(404);
    servlet.doPost(request(PATH_CONVERSATIONS + "/" + C1 + "/explode", null), response);
    verify(dal, never()).commitAndClose();
  }

  @Test
  void anUnexpectedFailureIsA500WithoutLeakingTheMessage() throws Exception {
    writes.when(() -> ConversationWriteUtils.createConversation(any())).thenThrow(new IllegalStateException("db password"));
    servlet.doPost(request(PATH_CONVERSATIONS, "{}"), response);
    assertStatus(500);
    assertEquals("Internal error", new JSONObject(out.toString()).getString("error"));
    verify(dal).rollbackAndClose();
  }

  @Test
  void routeReturnsNullForShapesItDoesNotKnow() throws Exception {
    assertEquals(null, AgentChatConversationsServlet.route(new String[0], new JSONObject()));
    assertEquals(null, AgentChatConversationsServlet.route(new String[] {"other"}, new JSONObject()));
    assertEquals(null, AgentChatConversationsServlet.route(new String[] {"conversations", C1}, new JSONObject()));
    assertNotNull(new AgentChatConversationsServlet());
  }
}
