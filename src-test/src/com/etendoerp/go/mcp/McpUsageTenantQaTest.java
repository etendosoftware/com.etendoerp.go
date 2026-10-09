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

package com.etendoerp.go.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.oauth2.OAuth2Filter;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * QA edge cases for ETP-5594 (effective tenant on ETGO_MCP_USAGE rows).
 *
 * @covers com.etendoerp.go.mcp.McpUsageRow
 * @covers com.etendoerp.go.mcp.McpUsageTelemetry
 * @covers com.etendoerp.go.mcp.McpCallObservation
 */
public class McpUsageTenantQaTest {

  private McpServlet servlet;

  @Before
  public void setUp() {
    // A mocked lifecycle answers null (no lifecycle metadata): the commercial gate lets the call
    // through without touching the DAL these tests stub call by call.
    servlet = new McpServlet(mock(TenantEnvironmentLifecycleService.class));
    System.setProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY, "https://example.com/mcp");
    McpUsageTelemetry.clearCurrentTenant();
  }

  @After
  public void tearDown() {
    System.clearProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY);
    McpUsageTelemetry.clearCurrentTenant();
  }

  private interface WorkStub {
    void apply(Session session);
  }

  private McpUsageRow doPostToolsCall(String tool, JSONObject arguments, String tokenClient,
      String tokenOrg, WorkStub work, JSONObject routerResult) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    when(request.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/etendo/sws/mcp"));
    when(request.getAttribute(OAuth2Filter.ATTR_USER_ID)).thenReturn("user1");
    when(request.getAttribute(OAuth2Filter.ATTR_ROLE_ID)).thenReturn("role1");
    when(request.getAttribute(OAuth2Filter.ATTR_CLIENT_ID)).thenReturn(tokenClient);
    when(request.getAttribute(OAuth2Filter.ATTR_ORG_ID)).thenReturn(tokenOrg);
    when(request.getAttribute(OAuth2Filter.ATTR_SCOPES)).thenReturn("neo:read neo:write");
    when(request.getHeader(McpUsageTelemetry.HEADER_SESSION_ID)).thenReturn("sess-qa-" + tool);
    String body = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
        .put("params", new JSONObject().put("name", tool).put("arguments", arguments)).toString();
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));

    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    work.apply(session);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<McpUsageLogger> loggerMock = mockStatic(McpUsageLogger.class);
         MockedConstruction<McpToolRouter> routerMock = mockConstruction(McpToolRouter.class,
             (router, ctx) -> when(router.route(anyString(), any(), any()))
                 .thenReturn(routerResult))) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      swsMock.when(() -> SecureWebServicesUtils.createContext(
          anyString(), anyString(), anyString(), any(), anyString()))
          .thenReturn(mock(OBContext.class));
      loggerMock.when(McpUsageLogger::isEnabled).thenReturn(true);

      servlet.doPost(request, response);

      ArgumentCaptor<McpUsageRow> row = ArgumentCaptor.forClass(McpUsageRow.class);
      loggerMock.verify(() -> McpUsageLogger.enqueue(row.capture()));
      return row.getValue();
    }
  }

  /**
   * Stubs the lookups of a wildcard ({@code "0"}) token in call order: the commercial-access gate
   * resolves the role's client first (ETP-5642), then executeInContext resolves org and client.
   */
  private static WorkStub resolves(String org, String client) {
    return s -> when(s.doReturningWork(any())).thenReturn(client, org, client);
  }

  private static JSONObject neoListArgs() throws Exception {
    return new JSONObject().put("spec", "sales-order");
  }

  @Test
  public void wildcardTokenWhoseResolutionFindsNothingKeepsZero() throws Exception {
    McpUsageRow row = doPostToolsCall("etendo_list", neoListArgs(), "0", "0", resolves(null, null),
        new JSONObject());
    assertEquals("0", row.clientId());
    assertEquals("0", row.orgId());
  }

  @Test
  public void wildcardTokenWithOnlyClientResolvedKeepsOrgZero() throws Exception {
    McpUsageRow row = doPostToolsCall("etendo_list", neoListArgs(), "0", "0",
        resolves(null, "realClient"), new JSONObject());
    assertEquals("realClient", row.clientId());
    assertEquals("0", row.orgId());
  }

  @Test
  public void nullTokenOrgIsRecordedAsTheResolvedOrg() throws Exception {
    // A concrete token client needs no role lookup: the only query is the org resolution.
    McpUsageRow row = doPostToolsCall("etendo_list", neoListArgs(), "client1", null,
        s -> when(s.doReturningWork(any())).thenReturn("realOrg"), new JSONObject());
    assertEquals("client1", row.clientId());
    assertEquals("realOrg", row.orgId());
  }

  @Test
  public void resolutionFailureIsSwallowedAndTheRowKeepsZero() throws Exception {
    // resolveDefaultOrg/resolveClientFromRole catch and log: the call proceeds on "0".
    McpUsageRow row = doPostToolsCall("etendo_list", neoListArgs(), "0", "0",
        s -> when(s.doReturningWork(any())).thenThrow(new RuntimeException("db down")),
        new JSONObject());
    assertEquals(McpUsageRow.OUTCOME_OK, row.outcome());
    assertEquals("0", row.clientId());
    assertEquals("0", row.orgId());
  }

  @Test
  public void pooledThreadDoesNotCarryThePreviousRequestTenant() throws Exception {
    McpUsageRow first = doPostToolsCall("etendo_list", neoListArgs(), "0", "0",
        resolves("orgA", "clientA"), new JSONObject());
    assertEquals("clientA", first.clientId());
    assertNull(McpUsageTelemetry.currentTenant());

    // Same thread, next request never reaches setCurrentTenant (resolution blows up).
    McpUsageRow second = doPostToolsCall("etendo_list", neoListArgs(), "0", "0",
        s -> when(s.doReturningWork(any())).thenThrow(new RuntimeException("db down")),
        new JSONObject());
    assertEquals("0", second.clientId());
    assertEquals("0", second.orgId());
  }

  @Test
  public void neoFeedbackRowIsAttributedToTheEffectiveTenant() throws Exception {
    JSONObject args = new JSONObject().put("verdict", "positive").put("comment", "works");
    McpUsageRow row = doPostToolsCall(McpConstants.TOOL_NEO_FEEDBACK, args, "0", "0",
        resolves("realOrg", "realClient"), new JSONObject());
    assertEquals(McpUsageRow.ROW_TYPE_FEEDBACK, row.rowType());
    assertEquals("realClient", row.clientId());
    assertEquals("realOrg", row.orgId());
  }

  @Test
  public void neoDiscoverRowIsAttributedToTheEffectiveTenant() throws Exception {
    McpUsageRow row = doPostToolsCall("etendo_discover", new JSONObject(), "0", "0",
        resolves("realOrg", "realClient"), new JSONObject());
    assertNotNull(row);
    assertEquals("realClient", row.clientId());
    assertEquals("realOrg", row.orgId());
  }

  @Test
  public void nonToolMethodLeavesNoTenantBound() throws Exception {
    // tools/list enters executeInContext too; doPost must still unbind on the way out.
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    when(request.getAttribute(OAuth2Filter.ATTR_USER_ID)).thenReturn("user1");
    when(request.getAttribute(OAuth2Filter.ATTR_ROLE_ID)).thenReturn("role1");
    when(request.getAttribute(OAuth2Filter.ATTR_CLIENT_ID)).thenReturn("0");
    when(request.getAttribute(OAuth2Filter.ATTR_ORG_ID)).thenReturn("0");
    when(request.getAttribute(OAuth2Filter.ATTR_SCOPES)).thenReturn("neo:read");
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")));
    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    // Gate role lookup (ETP-5642), then executeInContext's org and client resolution.
    when(session.doReturningWork(any())).thenReturn("clientA", "orgA", "clientA");
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      swsMock.when(() -> SecureWebServicesUtils.createContext(
          anyString(), anyString(), anyString(), any(), anyString()))
          .thenReturn(mock(OBContext.class));
      servlet.doPost(request, response);
    }
    assertNull(McpUsageTelemetry.currentTenant());
  }

  // ── Derived modern sessions (ETP-5640) ──────────────────────────────────

  private static final long T0 = 1_000_000L;

  @Test
  public void derivedSessionIsStableWithinTheIdleGapAndRenewedAfterIt() {
    long gap = McpUsageTelemetry.MODERN_SESSION_IDLE_MS;
    McpUsageTelemetry.ModernSession first =
        McpUsageTelemetry.modernSession("u-gap", "c1", "r1", T0);
    McpUsageTelemetry.ModernSession again =
        McpUsageTelemetry.modernSession("u-gap", "c1", "r1", T0 + gap);
    McpUsageTelemetry.ModernSession later =
        McpUsageTelemetry.modernSession("u-gap", "c1", "r1", T0 + 2 * gap + 1);

    org.junit.Assert.assertTrue(first.started());
    org.junit.Assert.assertFalse(again.started());
    assertEquals(first.key(), again.key());
    org.junit.Assert.assertTrue("renewed after the gap", later.started());
    org.junit.Assert.assertNotEquals(first.key(), later.key());
    org.junit.Assert.assertTrue(first.key().startsWith(McpUsageTelemetry.MODERN_SESSION_PREFIX));
  }

  @Test
  public void derivedSessionsAreDistinctPerAuthenticatedCaller() {
    String base = McpUsageTelemetry.modernSession("u-per", "c1", "r1", T0).key();
    org.junit.Assert.assertNotEquals(base,
        McpUsageTelemetry.modernSession("u-per2", "c1", "r1", T0).key());
    org.junit.Assert.assertNotEquals(base,
        McpUsageTelemetry.modernSession("u-per", "c2", "r1", T0).key());
    org.junit.Assert.assertNotEquals(base,
        McpUsageTelemetry.modernSession("u-per", "c1", "r2", T0).key());
  }

  /**
   * W3: the self-reported client name is not part of the key, so rotating it neither opens new
   * sessions nor flushes other callers' sessions out of the bounded map.
   */
  @Test
  public void rotatingTheClientNameKeepsOneSession() throws Exception {
    String first = boundSessionFor("u-rot", "name-1");
    String second = boundSessionFor("u-rot", "name-2");

    assertEquals(first, second);
  }

  private static String boundSessionFor(String user, String clientName) throws Exception {
    JSONObject params = new JSONObject().put("_meta", new JSONObject()
        .put(McpRequestEra.META_PROTOCOL_VERSION, McpProtocolVersion.MODERN_LATEST)
        .put(McpServlet.META_CLIENT_INFO, new JSONObject().put("name", clientName)));
    try {
      McpUsageTelemetry.bindModernCaller(user, "c1", "r1", params,
          McpRequestEra.classify("tools/list", params, new McpRequestEra.Headers(
              McpProtocolVersion.MODERN_LATEST, "tools/list", null), false));
      assertEquals(clientName, McpUsageTelemetry.currentClient().getName());
      return McpUsageTelemetry.currentSessionKey();
    } finally {
      McpUsageTelemetry.clearCurrentSessionKey();
      McpUsageTelemetry.clearCurrentClient();
    }
  }

  /**
   * Kill switch on: a request declaring a modern version is legacy. It keeps its _meta client name
   * but derives no m- session, so a rollback leaves no unused modern sessions behind.
   */
  @Test
  public void aLegacyRequestDeclaringAModernVersionNamesItsClientWithoutASession()
      throws Exception {
    JSONObject params = new JSONObject().put("_meta", new JSONObject()
        .put(McpRequestEra.META_PROTOCOL_VERSION, McpProtocolVersion.MODERN_LATEST)
        .put(McpServlet.META_CLIENT_INFO, new JSONObject().put("name", "claude-code")));
    try {
      McpUsageTelemetry.bindModernCaller("u-legacy", "c1", "r1", params,
          McpRequestEra.Classification.legacy());

      assertEquals("claude-code", McpUsageTelemetry.currentClient().getName());
      org.junit.Assert.assertNull(McpUsageTelemetry.currentSessionKey());
      // The feedback rate limit still has the caller to key on, session or not.
      org.junit.Assert.assertNotNull(McpUsageTelemetry.rateLimitKey());
    } finally {
      McpUsageTelemetry.clearCurrentSessionKey();
      McpUsageTelemetry.clearCurrentClient();
    }
  }

  /** W3: the map evicts the least recently ACTIVE session, never a busy one. */
  @Test
  public void anActiveSessionSurvivesEvictionPressure() {
    long now = T0;
    String active = McpUsageTelemetry.modernSession("u-active", "c1", "r1", now).key();
    for (int i = 0; i < 3_000; i++) {
      McpUsageTelemetry.modernSession("u-filler-" + i, "c1", "r1", now);
      if (i % 500 == 0) {
        // The active caller keeps calling while other callers come and go.
        McpUsageTelemetry.modernSession("u-active", "c1", "r1", now);
      }
    }

    McpUsageTelemetry.ModernSession again =
        McpUsageTelemetry.modernSession("u-active", "c1", "r1", now);
    org.junit.Assert.assertFalse("still tracked", again.started());
    assertEquals(active, again.key());
  }

  @Test
  public void clientInfoFromMetaReadsNameAndVersionOnly() throws Exception {
    JSONObject params = new JSONObject().put("_meta", new JSONObject().put(
        McpServlet.META_CLIENT_INFO, new JSONObject().put("name", "  claude-code ")
            .put("version", "2.1.0").put("secret", "x")));

    McpUsageTelemetry.ClientInfo client = McpUsageTelemetry.clientInfoFromMeta(params);

    assertEquals("claude-code", client.getName());
    assertEquals("2.1.0", client.getVersion());
    org.junit.Assert.assertSame(McpUsageTelemetry.ClientInfo.UNKNOWN,
        McpUsageTelemetry.clientInfoFromMeta(null));
    org.junit.Assert.assertSame(McpUsageTelemetry.ClientInfo.UNKNOWN,
        McpUsageTelemetry.clientInfoFromMeta(new JSONObject().put("_meta", new JSONObject())));
  }

  @Test
  public void clientInfoFromMetaBoundsASelfReportedName() throws Exception {
    String longName = org.apache.commons.lang3.StringUtils.repeat('x', 500);
    JSONObject params = new JSONObject().put("_meta", new JSONObject().put(
        McpServlet.META_CLIENT_INFO, new JSONObject().put("name", longName)));

    // Bounded at the stored width (100), not at the shorter bound of an echoed log value.
    assertEquals(100, McpUsageTelemetry.clientInfoFromMeta(params).getName().length());
  }

  @Test
  public void observationCarriesTheResolvedSessionKey() {
    assertEquals("m-abc", new McpCallObservation("m-abc", "{}", "{}", System.nanoTime())
        .sessionKey());
    assertNull(new McpCallObservation(null, "{}", "{}", System.nanoTime()).sessionKey());
  }
}
