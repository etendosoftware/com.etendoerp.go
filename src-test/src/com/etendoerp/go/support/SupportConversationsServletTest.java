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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Tests for {@link SupportConversationsServlet}.
 *
 * Exercises the servlet exclusively through its public {@code doGet}/{@code doPost}
 * contract (no visibility changes to production code). DB access is mocked via
 * {@code OBDal}/{@code OBContext} static mocks, following the same pattern as
 * {@link SupportJiraWebhookHandlerTest}. Outbound ADK/Jira calls are mocked via
 * {@link SupportIntegrationClient} static mocks so tests never touch the network.
 */
class SupportConversationsServletTest {

  private static final String VALID_TOKEN = "Bearer valid-token";
  private static final String USER_ID = "100";
  private static final String CLIENT_ID = "CLIENT1";
  private static final String ORG_ID = "ORG1";

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
   * (plus {@link #CLIENT_ID}/{@link #ORG_ID}, which {@code authenticate()} also reads to stamp
   * new conversation rows). */
  private static MockedStatic<SecureWebServicesUtils> mockValidAuth() {
    DecodedJWT jwt = mock(DecodedJWT.class);
    Claim userClaim = mock(Claim.class);
    when(userClaim.asString()).thenReturn(USER_ID);
    when(jwt.getClaim("user")).thenReturn(userClaim);
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

  /**
   * Mocks OBDal with a single Connection/PreparedStatement pair that answers every
   * {@code prepareStatement(...)} call the same way (matches the DDL loop in
   * {@code ensureTablesExist} harmlessly, and drives whatever query the handler under
   * test issues). Returns the PreparedStatement mock so individual tests can further
   * stub {@code executeQuery()}/{@code executeUpdate()} results.
   *
   * <p>Callers must still keep an open {@code MockedStatic<OBContext>} in their
   * try-with-resources for the duration of the call under test, so that the real
   * {@code OBContext.setAdminMode}/{@code restorePreviousMode} calls become safe no-ops
   * instead of touching a real Openbravo context — this helper doesn't need to
   * reference it directly to rely on that.
   */
  private static PreparedStatement mockDb(MockedStatic<OBDal> dalMock) throws Exception {
    OBDal obDal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    dalMock.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    return ps;
  }

  private static ResultSet emptyResultSet() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);
    return rs;
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

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

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

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

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
        PreparedStatement ps = mockDb(dalMock);
        ResultSet emptyRs = emptyResultSet();
        when(ps.executeQuery()).thenReturn(emptyRs);

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
        PreparedStatement ps = mockDb(dalMock);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("id")).thenReturn("conv-1");
        when(rs.getString("subject")).thenReturn("Need help");
        when(rs.getString("status")).thenReturn("open");
        when(rs.getString("last_activity")).thenReturn("2026-07-01 10:00:00.000000-03");
        when(rs.getString("last_message")).thenReturn("Hello");
        when(rs.getString("unread")).thenReturn("Y");
        when(rs.getString("rated")).thenReturn("N");
        when(ps.executeQuery()).thenReturn(rs);

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
        PreparedStatement ps = mockDb(dalMock);
        ResultSet emptyRs = emptyResultSet();
        when(ps.executeQuery()).thenReturn(emptyRs);

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
        PreparedStatement ps = mockDb(dalMock);
        ResultSet belongsRs = mock(ResultSet.class);
        when(belongsRs.next()).thenReturn(true);
        ResultSet msgRs = mock(ResultSet.class);
        when(msgRs.next()).thenReturn(true, false);
        when(msgRs.getString("id")).thenReturn("msg-1");
        when(msgRs.getString("conversation_id")).thenReturn("conv-1");
        when(msgRs.getString("sender")).thenReturn("user");
        when(msgRs.getString("sender_name")).thenReturn("Tú");
        when(msgRs.getString("text")).thenReturn("Hola");
        when(msgRs.getString("timestamp")).thenReturn("2026-07-01 10:00:00.000000-03");
        when(ps.executeQuery()).thenReturn(belongsRs, msgRs);

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

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

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

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

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

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

        new SupportConversationsServlet().doPost(request, response);
      }

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
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);
        when(ps.executeUpdate()).thenReturn(1);

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

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

        new SupportConversationsServlet().doPost(request, response);
      }

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
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);
        when(ps.executeUpdate()).thenReturn(0);

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
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);
        when(ps.executeUpdate()).thenReturn(1);

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

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

        new SupportConversationsServlet().doPost(request, response);
      }

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
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);
        when(ps.executeUpdate()).thenReturn(1);

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
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);
        when(ps.executeUpdate()).thenReturn(1);

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

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

        new SupportConversationsServlet().doPost(request, response);
      }

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

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

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

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

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

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

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
           MockedStatic<SupportIntegrationClient> sicMock = mockStatic(SupportIntegrationClient.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);
        when(ps.executeUpdate()).thenReturn(1);
        ResultSet summaryRs = mock(ResultSet.class);
        when(summaryRs.next()).thenReturn(true);
        when(summaryRs.getString("id")).thenReturn("conv-new");
        when(summaryRs.getString("subject")).thenReturn("Necesito ayuda");
        when(summaryRs.getString("status")).thenReturn("open");
        when(summaryRs.getString("last_activity")).thenReturn("2026-07-01 10:00:00.000000-03");
        when(summaryRs.getString("last_message")).thenReturn("Hola! ¿En qué puedo ayudarte?");
        when(summaryRs.getString("unread")).thenReturn("N");
        when(summaryRs.getString("rated")).thenReturn("N");
        ResultSet messagesRs = emptyResultSet();
        ResultSet clientOrgRs1 = emptyResultSet();
        ResultSet clientOrgRs2 = emptyResultSet();
        // Extra leading empty results: getConvClientOrg() runs once per insertMessage() call
        // (user message + AI reply) before buildConvSummary()/buildMessageArray() run.
        when(ps.executeQuery()).thenReturn(clientOrgRs1, clientOrgRs2, summaryRs, messagesRs);

        sicMock.when(() -> SupportIntegrationClient.getUserEmail(org.mockito.ArgumentMatchers.any(), anyString()))
            .thenReturn("user@example.com");
        sicMock.when(() -> SupportIntegrationClient.sendToAdk(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any()))
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
           MockedStatic<SupportIntegrationClient> sicMock = mockStatic(SupportIntegrationClient.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);
        when(ps.executeUpdate()).thenReturn(1);
        ResultSet summaryRs = mock(ResultSet.class);
        when(summaryRs.next()).thenReturn(true);
        when(summaryRs.getString(anyString())).thenReturn("value");
        ResultSet emptyRs = emptyResultSet();
        ResultSet clientOrgRs1 = emptyResultSet();
        ResultSet clientOrgRs2 = emptyResultSet();
        when(ps.executeQuery()).thenReturn(clientOrgRs1, clientOrgRs2, summaryRs, emptyRs);

        sicMock.when(() -> SupportIntegrationClient.getUserEmail(org.mockito.ArgumentMatchers.any(), anyString()))
            .thenReturn(null);
        sicMock.when(() -> SupportIntegrationClient.sendToAdk(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(null);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("value"));
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

  private static final String FIELD_MESSAGES_LITERAL = "messages";

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

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

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
        PreparedStatement ps = mockDb(dalMock);
        ResultSet emptyRs = emptyResultSet();
        when(ps.executeQuery()).thenReturn(emptyRs);

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
        PreparedStatement ps = mockDb(dalMock);
        ResultSet belongsRs = mock(ResultSet.class);
        when(belongsRs.next()).thenReturn(true);
        ResultSet statusRs = mock(ResultSet.class);
        when(statusRs.next()).thenReturn(true);
        when(statusRs.getString("status")).thenReturn("closed");
        when(ps.executeQuery()).thenReturn(belongsRs, statusRs);

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
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);
        when(ps.executeUpdate()).thenReturn(1);

        ResultSet belongsRs = mock(ResultSet.class);
        when(belongsRs.next()).thenReturn(true);
        ResultSet statusRs = mock(ResultSet.class);
        when(statusRs.next()).thenReturn(true);
        when(statusRs.getString("status")).thenReturn("open");
        ResultSet takeoverRs = mock(ResultSet.class);
        when(takeoverRs.next()).thenReturn(true);
        when(takeoverRs.getString("human_takeover")).thenReturn("Y");
        ResultSet jiraKeyRs = mock(ResultSet.class);
        when(jiraKeyRs.next()).thenReturn(true);
        when(jiraKeyRs.getString("jira_ticket_key")).thenReturn("SUP-5");
        ResultSet summaryRs = mock(ResultSet.class);
        when(summaryRs.next()).thenReturn(true);
        when(summaryRs.getString(anyString())).thenReturn("value");

        ResultSet emptyRs = emptyResultSet();
        ResultSet clientOrgRs = emptyResultSet();
        // Extra result between statusRs and takeoverRs: getConvClientOrg() inside the
        // insertMessage() call for the user's own text (this path sends no AI reply message).
        when(ps.executeQuery()).thenReturn(belongsRs, statusRs, clientOrgRs, takeoverRs, jiraKeyRs, emptyRs, summaryRs);

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
           MockedStatic<SupportIntegrationClient> sicMock = mockStatic(SupportIntegrationClient.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);
        when(ps.executeUpdate()).thenReturn(1);

        ResultSet belongsRs = mock(ResultSet.class);
        when(belongsRs.next()).thenReturn(true);
        ResultSet statusRs = mock(ResultSet.class);
        when(statusRs.next()).thenReturn(true);
        when(statusRs.getString("status")).thenReturn("open");
        ResultSet takeoverRs = mock(ResultSet.class);
        when(takeoverRs.next()).thenReturn(true);
        when(takeoverRs.getString("human_takeover")).thenReturn("N");
        ResultSet summaryRs = mock(ResultSet.class);
        when(summaryRs.next()).thenReturn(true);
        when(summaryRs.getString(anyString())).thenReturn("value");

        ResultSet emptyRs = emptyResultSet();
        ResultSet clientOrgRs1 = emptyResultSet();
        ResultSet clientOrgRs2 = emptyResultSet();
        // Extra results: getConvClientOrg() inside each insertMessage() call (user text, then
        // the AI reply).
        when(ps.executeQuery()).thenReturn(belongsRs, statusRs, clientOrgRs1, takeoverRs, clientOrgRs2, emptyRs, summaryRs);

        sicMock.when(() -> SupportIntegrationClient.sendToAdk(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
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

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

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

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

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
        PreparedStatement ps = mockDb(dalMock);
        ResultSet emptyRs = emptyResultSet();
        when(ps.executeQuery()).thenReturn(emptyRs);

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
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);
        when(ps.executeUpdate()).thenReturn(1);

        ResultSet belongsRs = mock(ResultSet.class);
        when(belongsRs.next()).thenReturn(true);
        ResultSet jiraKeyRs = mock(ResultSet.class);
        when(jiraKeyRs.next()).thenReturn(true);
        when(jiraKeyRs.getString("jira_ticket_key")).thenReturn("SUP-5");
        when(ps.executeQuery()).thenReturn(belongsRs, jiraKeyRs);

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
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);
        when(ps.executeUpdate()).thenReturn(1);
        ResultSet belongsRs = mock(ResultSet.class);
        when(belongsRs.next()).thenReturn(true);
        ResultSet summaryRs = mock(ResultSet.class);
        when(summaryRs.next()).thenReturn(true);
        when(summaryRs.getString(anyString())).thenReturn("value");
        when(ps.executeQuery()).thenReturn(belongsRs, summaryRs);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains(FIELD_MESSAGES_LITERAL) || capture.toString().contains("value"));
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
        PreparedStatement ps = mockDb(dalMock);
        ResultSet emptyRs = emptyResultSet();
        when(ps.executeQuery()).thenReturn(emptyRs);

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
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);
        when(ps.executeUpdate()).thenReturn(1);
        ResultSet belongsRs = mock(ResultSet.class);
        when(belongsRs.next()).thenReturn(true);
        ResultSet summaryRs = mock(ResultSet.class);
        when(summaryRs.next()).thenReturn(true);
        when(summaryRs.getString(anyString())).thenReturn("value");
        ResultSet emptyRs = emptyResultSet();
        ResultSet clientOrgRs = emptyResultSet();
        // Extra result: getConvClientOrg() inside insertMessage() for the reopen system message.
        when(ps.executeQuery()).thenReturn(belongsRs, clientOrgRs, summaryRs, emptyRs);

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

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockValidAuth();
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        PreparedStatement ps = mockDb(dalMock);
        when(ps.execute()).thenReturn(true);

        new SupportConversationsServlet().doPost(request, response);
      }

      assertTrue(capture.toString().contains("Unknown endpoint"));
    }
  }

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
