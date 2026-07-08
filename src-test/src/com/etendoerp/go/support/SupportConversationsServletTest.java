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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.schemaforge.data.SupportConversation;
import com.etendoerp.go.schemaforge.data.SupportMessage;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Tests for {@link SupportConversationsServlet}.
 *
 * Exercises the servlet exclusively through its public {@code doGet}/{@code doPost}
 * contract (no visibility changes to production code). Persistence is mocked via
 * {@code OBDal}/{@code OBProvider}/{@code OBContext} static mocks — {@link SupportConversation}
 * and {@link SupportMessage} are AD-generated {@code BaseOBObject} entities that require a live
 * Openbravo model to back real getters/setters, so tests use plain Mockito mocks of the entity
 * classes (stubbing only the getters each handler actually reads) rather than instantiating them.
 * Outbound ADK/Jira calls are mocked via {@link SupportIntegrationClient} static mocks so tests
 * never touch the network.
 */
class SupportConversationsServletTest {

  private static final String VALID_TOKEN = "Bearer valid-token";
  private static final String USER_ID = "100";
  private static final String ROLE_ID = "ROLE1";
  private static final String CLIENT_ID = "CLIENT1";
  private static final String ORG_ID = "ORG1";
  private static final String FIELD_MESSAGES_LITERAL = "messages";

  private static HttpServletResponse mockResponse(StringWriter capture) throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getWriter()).thenReturn(new PrintWriter(capture));
    return response;
  }

  private static HttpServletRequest mockRequestWithBody(String body) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body == null ? "" : body)));
    return request;
  }

  private static HttpServletRequest authenticatedRequest(String pathInfo, String body) throws Exception {
    HttpServletRequest request = mockRequestWithBody(body);
    when(request.getHeader("Authorization")).thenReturn(VALID_TOKEN);
    when(request.getPathInfo()).thenReturn(pathInfo);
    return request;
  }

  /** Stubs {@link SecureWebServicesUtils#decodeToken(String)} to resolve to {@link #USER_ID}
   * (plus {@link #ROLE_ID}/{@link #CLIENT_ID}/{@link #ORG_ID}, which {@code authenticate()} also
   * reads to switch {@code OBContext} and stamp new conversation rows). */
  private static MockedStatic<SecureWebServicesUtils> mockValidAuth() {
    DecodedJWT jwt = mock(DecodedJWT.class);
    Claim userClaim = mock(Claim.class);
    when(userClaim.asString()).thenReturn(USER_ID);
    when(jwt.getClaim("user")).thenReturn(userClaim);
    Claim roleClaim = mock(Claim.class);
    when(roleClaim.asString()).thenReturn(ROLE_ID);
    when(jwt.getClaim("role")).thenReturn(roleClaim);
    Claim clientClaim = mock(Claim.class);
    when(clientClaim.asString()).thenReturn(CLIENT_ID);
    when(jwt.getClaim("client")).thenReturn(clientClaim);
    Claim orgClaim = mock(Claim.class);
    when(orgClaim.asString()).thenReturn(ORG_ID);
    when(jwt.getClaim("organization")).thenReturn(orgClaim);
    MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
    swsMock.when(() -> SecureWebServicesUtils.decodeToken(anyString())).thenReturn(jwt);
    return swsMock;
  }

  /** Wires {@code OBDal.getInstance()} to a mock so {@code OBContext.setAdminMode}-wrapped
   * handlers can run; individual tests stub {@code get}/{@code createCriteria} as needed.
   * Callers must keep an open {@code MockedStatic<OBContext>} for the duration of the call
   * under test so the real admin-mode calls become safe no-ops. */
  private static OBDal mockObDal(MockedStatic<OBDal> dalMock) throws Exception {
    OBDal obDal = mock(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(obDal);
    return obDal;
  }

  /** Wires an already-open {@code MockedStatic<SessionHandler>} so the mid-request
   * {@code SessionHandler.getInstance().commitAndStart()} call in {@code handleCreateConversation}
   * becomes a safe no-op. */
  private static MockedStatic<SessionHandler> mockSessionHandler() {
    SessionHandler sessionHandler = mock(SessionHandler.class);
    MockedStatic<SessionHandler> shMock = mockStatic(SessionHandler.class);
    shMock.when(SessionHandler::getInstance).thenReturn(sessionHandler);
    return shMock;
  }

  /** Wires an already-open {@code MockedStatic<OBProvider>} so {@code OBProvider.getInstance().get(...)}
   * hands back the given mock entities — production code calls this to mint new rows before saving
   * them. Callers must open {@code mockStatic(OBProvider.class)} themselves (in the same
   * try-with-resources as the other statics) so it's reliably closed — a helper that opens and
   * returns it is easy to leak across tests since the caller has no compile-time nudge to close it. */
  private static void stubProvider(MockedStatic<OBProvider> providerMock, SupportConversation conv, SupportMessage msg) {
    OBProvider provider = mock(OBProvider.class);
    providerMock.when(OBProvider::getInstance).thenReturn(provider);
    if (conv != null) when(provider.get(SupportConversation.class)).thenReturn(conv);
    if (msg != null) when(provider.get(SupportMessage.class)).thenReturn(msg);
  }

  @SuppressWarnings("unchecked")
  private static <T extends org.openbravo.base.structure.BaseOBObject> void mockCriteria(
      OBDal obDal, Class<T> clazz, List<T> results) {
    OBCriteria<T> crit = mock(OBCriteria.class);
    when(obDal.createCriteria(clazz)).thenReturn(crit);
    when(crit.add(any())).thenReturn(crit);
    when(crit.addOrderBy(anyString(), anyBoolean())).thenReturn(crit);
    when(crit.setMaxResults(anyInt())).thenReturn(crit);
    when(crit.list()).thenReturn(results);
    when(crit.uniqueResult()).thenReturn(results.isEmpty() ? null : results.get(0));
  }

  private static User mockUser(String id) {
    User user = mock(User.class);
    when(user.getId()).thenReturn(id);
    return user;
  }

  /** A conversation mock stubbed the way {@code belongsToUser}/{@code toConvSummaryJson} read it:
   * owned by {@link #USER_ID}, open, unread/rated false. Override specific getters per test. */
  private static SupportConversation mockConversation(String id) {
    SupportConversation conv = mock(SupportConversation.class);
    User user = mockUser(USER_ID);
    when(conv.getId()).thenReturn(id);
    when(conv.getUser()).thenReturn(user);
    when(conv.getStatus()).thenReturn("open");
    when(conv.getSubject()).thenReturn("Need help");
    when(conv.isUnread()).thenReturn(false);
    when(conv.isRated()).thenReturn(false);
    when(conv.getClient()).thenReturn(mock(Client.class));
    when(conv.getOrganization()).thenReturn(mock(Organization.class));
    return conv;
  }

  // -------------------------------------------------------------------------
  // doGet — authentication
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("doGet — authentication")
  class DoGetAuth {

    @Test
    @DisplayName("Missing Authorization header returns 401")
    void missingAuthHeader() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mock(HttpServletRequest.class);

      new SupportConversationsServlet().doGet(request, response);

      assertTrue(capture.toString().contains("Missing or invalid Authorization header"));
    }

    @Test
    @DisplayName("Authorization header without Bearer prefix returns 401")
    void nonBearerAuthHeader() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getHeader("Authorization")).thenReturn("Basic abc123");

      new SupportConversationsServlet().doGet(request, response);

      assertTrue(capture.toString().contains("Missing or invalid Authorization header"));
    }

    @Test
    @DisplayName("Token that fails to decode returns 401")
    void invalidToken() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getHeader("Authorization")).thenReturn(VALID_TOKEN);

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
        swsMock.when(() -> SecureWebServicesUtils.decodeToken(anyString()))
            .thenThrow(new RuntimeException("bad token"));

        new SupportConversationsServlet().doGet(request, response);
      }

      assertTrue(capture.toString().contains("Invalid or expired token"));
    }

    @Test
    @DisplayName("Token decoding to a missing user claim returns 401")
    void missingUserClaim() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getHeader("Authorization")).thenReturn(VALID_TOKEN);

      DecodedJWT jwt = mock(DecodedJWT.class);
      Claim claim = mock(Claim.class);
      when(claim.asString()).thenReturn(null);
      when(jwt.getClaim("user")).thenReturn(claim);

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
        swsMock.when(() -> SecureWebServicesUtils.decodeToken(anyString())).thenReturn(jwt);

        new SupportConversationsServlet().doGet(request, response);
      }

      assertTrue(capture.toString().contains("missing user claim"));
    }

    @Test
    @DisplayName("Authenticated request to an unknown GET endpoint returns 404")
    void unknownEndpoint() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/something-else", null);

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth()) {
        new SupportConversationsServlet().doGet(request, response);
      }

      assertTrue(capture.toString().contains("Unknown endpoint"));
    }

    @Test
    @DisplayName("Null pathInfo is treated as the root path and returns 404")
    void nullPathInfo() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest(null, null);

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth()) {
        new SupportConversationsServlet().doGet(request, response);
      }

      assertTrue(capture.toString().contains("Unknown endpoint"));
    }
  }

  // -------------------------------------------------------------------------
  // doGet — /conversations (list)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("doGet — list conversations")
  class DoGetListConversations {

    @Test
    @DisplayName("Returns an empty array when the user has no conversations")
    void emptyList() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations", null);

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, Collections.emptyList());

        new SupportConversationsServlet().doGet(request, response);
      }

      assertEquals("{\"conversations\":[]}", capture.toString());
    }

    @Test
    @DisplayName("Returns conversations mapped from the result set")
    void withRows() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/", null);

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-1");
        when(conv.getSubject()).thenReturn("Need help");
        when(conv.getStatus()).thenReturn("open");
        when(conv.isUnread()).thenReturn(true);
        when(conv.isRated()).thenReturn(false);
        mockCriteria(obDal, SupportConversation.class, List.of(conv));

        new SupportConversationsServlet().doGet(request, response);
      }

      String out = capture.toString();
      assertTrue(out.contains("conv-1"));
      assertTrue(out.contains("Need help"));
      assertTrue(out.contains("\"unread\":true"));
    }

    @Test
    @DisplayName("A DB failure while listing returns 500")
    void dbFailure() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations", null);

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        dalMock.when(OBDal::getInstance).thenThrow(new RuntimeException("db down"));

        new SupportConversationsServlet().doGet(request, response);
      }

      assertTrue(capture.toString().contains("Internal error"));
    }
  }

  // -------------------------------------------------------------------------
  // doGet — /conversations/:id/messages
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("doGet — get messages")
  class DoGetMessages {

    @Test
    @DisplayName("Conversation not belonging to the user returns 404")
    void conversationNotFound() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/messages", null);

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(null);

        new SupportConversationsServlet().doGet(request, response);
      }

      assertTrue(capture.toString().contains("Conversation not found"));
    }

    @Test
    @DisplayName("Loads messages, marks conversation as read")
    void loadsMessages() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/messages", null);

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-1");
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(conv);

        SupportMessage msg = mock(SupportMessage.class);
        when(msg.getId()).thenReturn("msg-1");
        when(msg.getSender()).thenReturn("user");
        when(msg.getSenderName()).thenReturn("Tú");
        when(msg.getText()).thenReturn("Hola");
        mockCriteria(obDal, SupportMessage.class, List.of(msg));

        new SupportConversationsServlet().doGet(request, response);
      }

      String out = capture.toString();
      assertTrue(out.contains("msg-1"));
      assertTrue(out.contains("Hola"));
    }

    @Test
    @DisplayName("Malformed path with wrong segment count is not routed to get-messages (404)")
    void malformedPath() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1", null);

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth()) {
        new SupportConversationsServlet().doGet(request, response);
      }

      assertTrue(capture.toString().contains("Unknown endpoint"));
    }
  }

  // -------------------------------------------------------------------------
  // doPost — authentication (non-internal routes)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("doPost — authentication")
  class DoPostAuth {

    @Test
    @DisplayName("Missing Authorization header on /conversations returns 401")
    void missingAuthHeader() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mockRequestWithBody("{}");
      when(request.getPathInfo()).thenReturn("/conversations");

      new SupportConversationsServlet().doPost(request, response);

      assertTrue(capture.toString().contains("Missing or invalid Authorization header"));
    }

    @Test
    @DisplayName("Null pathInfo on POST falls through to unknown endpoint once authenticated")
    void nullPathInfo() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest(null, "{}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth()) {
        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Unknown endpoint"));
    }
  }

  // -------------------------------------------------------------------------
  // doPost — internal / webhook routes (unauthenticated)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("doPost — internal routes")
  class DoPostInternalRoutes {

    @Test
    @DisplayName("/jira-webhook is dispatched without requiring Authorization")
    void jiraWebhookDispatched() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mockRequestWithBody("");
      when(request.getPathInfo()).thenReturn("/jira-webhook");

      new SupportConversationsServlet().doPost(request, response);

      // No issueKey / empty body -> SupportJiraWebhookHandler ignores it, no auth error surfaces.
      assertTrue(capture.toString().contains("ignored"));
    }

    @Test
    @DisplayName("/internal/set-ticket links a conversation to a Jira key")
    void setTicketSuccess() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      String body = "{\"conversationId\":\"conv-1\",\"jiraTicketKey\":\"SUP-1\"}";
      HttpServletRequest request = mockRequestWithBody(body);
      when(request.getPathInfo()).thenReturn("/internal/set-ticket");

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-1");
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(conv);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("/internal/set-ticket with missing fields returns 400")
    void setTicketMissingFields() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mockRequestWithBody("{}");
      when(request.getPathInfo()).thenReturn("/internal/set-ticket");

      new SupportConversationsServlet().doPost(request, response);

      assertTrue(capture.toString().contains("conversationId and jiraTicketKey required"));
    }

    @Test
    @DisplayName("/internal/set-ticket for an unknown conversation returns 404")
    void setTicketNotFound() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      String body = "{\"conversationId\":\"missing\",\"jiraTicketKey\":\"SUP-1\"}";
      HttpServletRequest request = mockRequestWithBody(body);
      when(request.getPathInfo()).thenReturn("/internal/set-ticket");

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        when(obDal.get(SupportConversation.class, "missing")).thenReturn(null);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Conversation not found"));
    }

    @Test
    @DisplayName("/internal/set-human-takeover marks the conversation")
    void setHumanTakeoverSuccess() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      String body = "{\"conversationId\":\"conv-1\"}";
      HttpServletRequest request = mockRequestWithBody(body);
      when(request.getPathInfo()).thenReturn("/internal/set-human-takeover");

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-1");
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(conv);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("/internal/set-human-takeover without conversationId returns 400")
    void setHumanTakeoverMissingId() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mockRequestWithBody("{}");
      when(request.getPathInfo()).thenReturn("/internal/set-human-takeover");

      new SupportConversationsServlet().doPost(request, response);

      assertTrue(capture.toString().contains("conversationId required"));
    }

    @Test
    @DisplayName("/internal/reset-human-takeover by conversationId resets the flag")
    void resetHumanTakeoverByConvId() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      String body = "{\"conversationId\":\"conv-1\"}";
      HttpServletRequest request = mockRequestWithBody(body);
      when(request.getPathInfo()).thenReturn("/internal/reset-human-takeover");

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-1");
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(conv);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("/internal/reset-human-takeover by jiraTicketKey resets the flag")
    void resetHumanTakeoverByJiraKey() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      String body = "{\"jiraTicketKey\":\"SUP-9\"}";
      HttpServletRequest request = mockRequestWithBody(body);
      when(request.getPathInfo()).thenReturn("/internal/reset-human-takeover");

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-9");
        mockCriteria(obDal, SupportConversation.class, List.of(conv));

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("/internal/reset-human-takeover with no identifiers returns 400")
    void resetHumanTakeoverMissingBoth() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mockRequestWithBody("{}");
      when(request.getPathInfo()).thenReturn("/internal/reset-human-takeover");

      new SupportConversationsServlet().doPost(request, response);

      assertTrue(capture.toString().contains("conversationId or jiraTicketKey required"));
    }

    @Test
    @DisplayName("Internal routes proceed normally when no webhook secret is configured "
        + "(support.webhook.secret unset — the field is read once at class-load time, "
        + "so it cannot be toggled per-test)")
    void noSecretConfiguredSkipsCheck() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mockRequestWithBody("{}");
      when(request.getPathInfo()).thenReturn("/internal/set-ticket");
      when(request.getHeader("X-Internal-Secret")).thenReturn("anything-or-nothing");

      new SupportConversationsServlet().doPost(request, response);

      assertTrue(capture.toString().contains("conversationId and jiraTicketKey required"));
    }
  }

  // -------------------------------------------------------------------------
  // doPost — /conversations (create)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("doPost — create conversation")
  class DoPostCreateConversation {

    @Test
    @DisplayName("Missing message field returns 400")
    void missingMessage() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations", "{}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth()) {
        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Missing required field: message"));
    }

    @Test
    @DisplayName("Blank message returns 400")
    void blankMessage() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations", "{\"message\":\"   \"}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth()) {
        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("must not be empty"));
    }

    @Test
    @DisplayName("Invalid JSON body returns 400")
    void invalidJsonBody() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations", "not json");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth()) {
        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Invalid JSON body"));
    }

    @Test
    @DisplayName("Valid message creates a conversation and stores the AI reply")
    void createsConversation() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations", "{\"message\":\"Necesito ayuda\"}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
           MockedStatic<SessionHandler> shMock = mockSessionHandler();
           MockedStatic<SupportIntegrationClient> sicMock = mockStatic(SupportIntegrationClient.class)) {
        OBDal obDal = mockObDal(dalMock);
        User authUser = mockUser(USER_ID);
        when(obDal.get(User.class, USER_ID)).thenReturn(authUser);
        when(obDal.get(Client.class, CLIENT_ID)).thenReturn(mock(Client.class));
        when(obDal.get(Organization.class, ORG_ID)).thenReturn(mock(Organization.class));

        SupportConversation conv = mockConversation("conv-new");
        when(conv.getSubject()).thenReturn("Necesito ayuda");
        when(conv.getLastMessage()).thenReturn("Hola! ¿En qué puedo ayudarte?");
        when(obDal.get(SupportConversation.class, "conv-new")).thenReturn(conv);
        stubProvider(providerMock, conv, mock(SupportMessage.class));
        mockCriteria(obDal, SupportMessage.class, Collections.emptyList());

        sicMock.when(() -> SupportIntegrationClient.getUserEmail(anyString()))
            .thenReturn("user@example.com");
        sicMock.when(() -> SupportIntegrationClient.sendToAdk(anyString(), anyString(), anyString(), any()))
            .thenReturn("Hola! ¿En qué puedo ayudarte?");

        new SupportConversationsServlet().doPost(request, response);
      }

      String out = capture.toString();
      assertTrue(out.contains("conv-new"));
      assertTrue(out.contains(FIELD_MESSAGES_LITERAL));
    }

    @Test
    @DisplayName("A null ADK reply falls back to the stub message")
    void adkFailureFallsBackToStub() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations", "{\"message\":\"Hola\"}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
           MockedStatic<SessionHandler> shMock = mockSessionHandler();
           MockedStatic<SupportIntegrationClient> sicMock = mockStatic(SupportIntegrationClient.class)) {
        OBDal obDal = mockObDal(dalMock);
        User authUser = mockUser(USER_ID);
        when(obDal.get(User.class, USER_ID)).thenReturn(authUser);
        when(obDal.get(Client.class, CLIENT_ID)).thenReturn(mock(Client.class));
        when(obDal.get(Organization.class, ORG_ID)).thenReturn(mock(Organization.class));

        SupportConversation conv = mockConversation("conv-stub");
        when(conv.getLastMessage()).thenReturn(
            "Hola, soy ValerIA. En este momento no puedo conectarme con el servicio de IA. "
                + "Por favor intenta de nuevo en un momento.");
        when(obDal.get(SupportConversation.class, "conv-stub")).thenReturn(conv);
        stubProvider(providerMock, conv, mock(SupportMessage.class));
        mockCriteria(obDal, SupportMessage.class, Collections.emptyList());

        sicMock.when(() -> SupportIntegrationClient.getUserEmail(anyString())).thenReturn(null);
        sicMock.when(() -> SupportIntegrationClient.sendToAdk(anyString(), anyString(), anyString(), any()))
            .thenReturn(null);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("no puedo conectarme"));
    }

    @Test
    @DisplayName("A DB failure while creating a conversation returns 500")
    void dbFailure() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations", "{\"message\":\"Hola\"}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        dalMock.when(OBDal::getInstance).thenThrow(new RuntimeException("db down"));

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Internal error"));
    }
  }

  // -------------------------------------------------------------------------
  // doPost — /conversations/:id/messages (send)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("doPost — send message")
  class DoPostSendMessage {

    @Test
    @DisplayName("Missing text field returns 400")
    void missingText() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/messages", "{}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth()) {
        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Missing required field: text"));
    }

    @Test
    @DisplayName("Conversation not belonging to user returns 404")
    void conversationNotFound() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/messages", "{\"text\":\"hola\"}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(null);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Conversation not found"));
    }

    @Test
    @DisplayName("Sending to a closed conversation returns 400")
    void closedConversation() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/messages", "{\"text\":\"hola\"}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-1");
        when(conv.getStatus()).thenReturn("closed");
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(conv);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Conversation is closed"));
    }

    @Test
    @DisplayName("Human takeover forwards the message to Jira without an AI reply")
    void humanTakeoverForwardsToJira() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/messages", "{\"text\":\"hola\"}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-1");
        when(conv.isHumanTakeover()).thenReturn(true);
        when(conv.getJiraTicketKey()).thenReturn("SUP-5");
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(conv);
        stubProvider(providerMock, null, mock(SupportMessage.class));
        mockCriteria(obDal, SupportMessage.class, Collections.emptyList());

        // postJiraComment() runs on a fire-and-forget background thread the test can't
        // observe (Mockito static mocks are thread-local); it safely no-ops in this
        // environment since JIRA_API_TOKEN is unset. We assert on the HTTP response only.
        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains(FIELD_MESSAGES_LITERAL));
    }

    @Test
    @DisplayName("Normal (non-takeover) message gets an AI reply")
    void normalMessageGetsAiReply() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/messages", "{\"text\":\"hola\"}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
           MockedStatic<SupportIntegrationClient> sicMock = mockStatic(SupportIntegrationClient.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-1");
        when(conv.isHumanTakeover()).thenReturn(false);
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(conv);
        stubProvider(providerMock, null, mock(SupportMessage.class));
        mockCriteria(obDal, SupportMessage.class, Collections.emptyList());

        sicMock.when(() -> SupportIntegrationClient.sendToAdk(
                anyString(), anyString(), anyString(), any(), any()))
            .thenReturn("Claro, contame más");

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains(FIELD_MESSAGES_LITERAL));
    }
  }

  // -------------------------------------------------------------------------
  // doPost — /conversations/:id/rating
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("doPost — submit rating")
  class DoPostSubmitRating {

    @Test
    @DisplayName("Missing score field returns 400")
    void missingScore() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/rating", "{}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth()) {
        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Missing required field: score"));
    }

    @Test
    @DisplayName("Score out of range returns 400")
    void scoreOutOfRange() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/rating", "{\"score\":9}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth()) {
        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("must be between 1 and 5"));
    }

    @Test
    @DisplayName("Conversation not found returns 404")
    void conversationNotFound() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/rating", "{\"score\":4}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(null);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Conversation not found"));
    }

    @Test
    @DisplayName("Valid rating is stored and forwarded to Jira as feedback")
    void validRating() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest(
          "/conversations/conv-1/rating", "{\"score\":5,\"comment\":\"Genial!\"}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-1");
        when(conv.getJiraTicketKey()).thenReturn("SUP-5");
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(conv);

        // buildFeedbackComment/postJiraCsatLabel run on a fire-and-forget background
        // thread the test can't observe (Mockito static mocks are thread-local); they
        // safely run for real here (no network — JIRA_API_TOKEN is unset in this env).
        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("\"status\":\"success\""));
    }
  }

  // -------------------------------------------------------------------------
  // doPost — /conversations/:id/close and /reopen
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("doPost — close / reopen conversation")
  class DoPostCloseReopen {

    @Test
    @DisplayName("Closing a conversation the user owns succeeds")
    void closeSuccess() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/close", "{}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-1");
        when(conv.getStatus()).thenReturn("closed");
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(conv);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains(FIELD_CONVERSATION_LITERAL));
    }

    @Test
    @DisplayName("Closing a conversation that doesn't belong to the user returns 404")
    void closeNotFound() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/close", "{}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(null);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Conversation not found"));
    }

    @Test
    @DisplayName("Reopening a conversation succeeds and adds a system message")
    void reopenSuccess() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/reopen", "{}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-1");
        when(conv.getStatus()).thenReturn("open");
        when(obDal.get(SupportConversation.class, "conv-1")).thenReturn(conv);
        stubProvider(providerMock, null, mock(SupportMessage.class));
        mockCriteria(obDal, SupportMessage.class, Collections.emptyList());

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains(FIELD_MESSAGES_LITERAL));
    }

    @Test
    @DisplayName("Unknown action on a conversation returns 404")
    void unknownAction() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = authenticatedRequest("/conversations/conv-1/archive", "{}");

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth()) {
        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Unknown endpoint"));
    }
  }

  private static final String FIELD_CONVERSATION_LITERAL = "conversation";

  // -------------------------------------------------------------------------
  // newId
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("newId returns a 32-character lowercase hex string with no dashes")
  void newIdReturnsHexUuid() {
    String id = SupportConversationsServlet.newId();

    assertTrue(id.matches("[0-9a-f]{32}"));
  }

  @Test
  @DisplayName("newId returns a different value on each call")
  void newIdIsUnique() {
    assertTrue(!SupportConversationsServlet.newId().equals(SupportConversationsServlet.newId()));
  }
}
