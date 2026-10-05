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
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/** QA edge cases for ETP-5594 (effective tenant on ETGO_MCP_USAGE rows). */
public class McpUsageTenantQaTest {

  private McpServlet servlet;

  @Before
  public void setUp() {
    servlet = new McpServlet();
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

  private static WorkStub resolves(String org, String client) {
    return s -> when(s.doReturningWork(any())).thenReturn(org, client);
  }

  private static JSONObject neoListArgs() throws Exception {
    return new JSONObject().put("spec", "sales-order");
  }

  @Test
  public void wildcardTokenWhoseResolutionFindsNothingKeepsZero() throws Exception {
    McpUsageRow row = doPostToolsCall("neo_list", neoListArgs(), "0", "0", resolves(null, null),
        new JSONObject());
    assertEquals("0", row.clientId());
    assertEquals("0", row.orgId());
  }

  @Test
  public void wildcardTokenWithOnlyClientResolvedKeepsOrgZero() throws Exception {
    McpUsageRow row = doPostToolsCall("neo_list", neoListArgs(), "0", "0",
        resolves(null, "realClient"), new JSONObject());
    assertEquals("realClient", row.clientId());
    assertEquals("0", row.orgId());
  }

  @Test
  public void nullTokenOrgIsRecordedAsTheResolvedOrg() throws Exception {
    McpUsageRow row = doPostToolsCall("neo_list", neoListArgs(), "client1", null,
        resolves("realOrg", null), new JSONObject());
    assertEquals("client1", row.clientId());
    assertEquals("realOrg", row.orgId());
  }

  @Test
  public void resolutionFailureIsSwallowedAndTheRowKeepsZero() throws Exception {
    // resolveDefaultOrg/resolveClientFromRole catch and log: the call proceeds on "0".
    McpUsageRow row = doPostToolsCall("neo_list", neoListArgs(), "0", "0",
        s -> when(s.doReturningWork(any())).thenThrow(new RuntimeException("db down")),
        new JSONObject());
    assertEquals(McpUsageRow.OUTCOME_OK, row.outcome());
    assertEquals("0", row.clientId());
    assertEquals("0", row.orgId());
  }

  @Test
  public void pooledThreadDoesNotCarryThePreviousRequestTenant() throws Exception {
    McpUsageRow first = doPostToolsCall("neo_list", neoListArgs(), "0", "0",
        resolves("orgA", "clientA"), new JSONObject());
    assertEquals("clientA", first.clientId());
    assertNull(McpUsageTelemetry.currentTenant());

    // Same thread, next request never reaches setCurrentTenant (resolution blows up).
    McpUsageRow second = doPostToolsCall("neo_list", neoListArgs(), "0", "0",
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
    McpUsageRow row = doPostToolsCall("neo_discover", new JSONObject(), "0", "0",
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
    when(session.doReturningWork(any())).thenReturn("orgA", "clientA");
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
}
