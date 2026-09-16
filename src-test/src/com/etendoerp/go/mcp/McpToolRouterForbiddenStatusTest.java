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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Status mapping of a role refusal in {@link McpToolRouter#route} (ETP-5335).
 *
 * <p>A refusal is a permanent answer for this role, not a server failure. It used to reach the
 * generic handler and surface as {@code 500 server_error}, whose status class invites a
 * retry-on-5xx client to loop forever on a decision that can never change.</p>
 *
 * <p>The second case is the one that actually escaped: Openbravo's {@link OBSecurityException}
 * extends {@code OBException}, <b>not</b> {@code SecurityException}, so a {@code catch
 * (SecurityException)} alone leaves every platform-raised refusal answering 500. These two are
 * asserted separately for exactly that reason — a single parametrized case over "some security
 * exception" would pass with only one of the two clauses present.</p>
 *
 * <p>The refusal is injected through {@code McpToolRouterSupport}, which {@code route} calls
 * inside its own try block while resolving the spec. That is a real call site (the router's own
 * {@code authorizeSpecAccess} throws {@code SecurityException} from there), so the test drives the
 * public entry point rather than the private envelope builder.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpToolRouterForbiddenStatusTest {

  private static final String SPEC_NAME = "sales-order";
  private static final String ENTITY_NAME = "header";
  private static final Set<String> READ_SCOPES = Set.of("neo:read");

  @Mock private OBDal mockOBDal;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<McpAuthorizationService> authMock;
  private MockedStatic<McpToolRouterSupport> supportMock;

  private McpToolRouter router;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    authMock = mockStatic(McpAuthorizationService.class);
    supportMock = mockStatic(McpToolRouterSupport.class);

    obDalMock.when(OBDal::getInstance).thenReturn(mockOBDal);
    obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
    obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
    supportMock.when(() -> McpToolRouterSupport.validateArgs(any(), any(String[].class)))
        .thenCallRealMethod();

    router = new McpToolRouter();
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
    authMock.close();
    supportMock.close();
  }

  private JSONObject crudArgs() throws Exception {
    JSONObject args = new JSONObject();
    args.put("spec", SPEC_NAME);
    args.put("entity", ENTITY_NAME);
    return args;
  }

  /** The error envelope the router put in the MCP error content, parsed back. */
  private static JSONObject envelopeOf(JSONObject result) throws Exception {
    String text = result.getJSONArray("content").getJSONObject(0).getString("text");
    return new JSONObject(text);
  }

  @Test
  @DisplayName("a role refusal raised as SecurityException answers 403 forbidden, not 500")
  void securityExceptionMapsToForbidden() throws Exception {
    supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString()))
        .thenThrow(new SecurityException("Access denied to spec 'sales-order' for the current role"));

    JSONObject result = router.route("neo_list", crudArgs(), READ_SCOPES);

    assertTrue(result.getBoolean("isError"));
    JSONObject envelope = envelopeOf(result);
    assertEquals(403, envelope.getInt("status"));
    assertEquals("forbidden", envelope.getString("error"));
    assertEquals("neo_list", envelope.getString("tool"));
    assertTrue(envelope.getString("detail").contains("Access denied"),
        "The refusal's own message must survive into the envelope");
  }

  @Test
  @DisplayName("the platform's OBSecurityException answers 403 too — it does not extend SecurityException")
  void obSecurityExceptionMapsToForbidden() throws Exception {
    supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString()))
        .thenThrow(new OBSecurityException("You do not have access to this entity"));

    JSONObject result = router.route("neo_list", crudArgs(), READ_SCOPES);

    assertTrue(result.getBoolean("isError"));
    JSONObject envelope = envelopeOf(result);
    assertEquals(403, envelope.getInt("status"),
        "OBSecurityException extends OBException, not SecurityException: without its own catch "
            + "clause half of the refusals keep answering 500");
    assertEquals("forbidden", envelope.getString("error"));
  }

  @Test
  @DisplayName("the forbidden envelope tells the agent not to retry")
  void forbiddenEnvelopeCarriesTheNoRetryHint() throws Exception {
    supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString()))
        .thenThrow(new SecurityException("Access denied"));

    JSONObject envelope = envelopeOf(router.route("neo_list", crudArgs(), READ_SCOPES));

    String hint = envelope.getString("hint");
    assertTrue(hint.contains("do not retry"),
        "A permanent decision must say so; the status class alone is what made clients loop. "
            + "Hint was: " + hint);
    assertTrue(hint.contains("neo_discover"),
        "The hint must point at the way to find what this role may reach. Hint was: " + hint);
  }

  @Test
  @DisplayName("route() denying access itself produces the forbidden envelope")
  void routeOwnDenialProducesForbidden() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getSpecType()).thenReturn("W");
    supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString())).thenReturn(spec);
    supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString(), anyString()))
        .thenReturn(false);

    JSONObject envelope = envelopeOf(router.route("neo_list", crudArgs(), READ_SCOPES));

    assertEquals(403, envelope.getInt("status"));
    assertEquals("forbidden", envelope.getString("error"));
  }

  @Test
  @DisplayName("any other failure still answers 500 server_error")
  void unrelatedExceptionStillMapsToServerError() throws Exception {
    supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString()))
        .thenThrow(new IllegalStateException("the DAL went away"));

    JSONObject envelope = envelopeOf(router.route("neo_list", crudArgs(), READ_SCOPES));

    assertEquals(500, envelope.getInt("status"),
        "Only a refusal is a 403; a genuine fault must keep its server_error class");
    assertEquals("server_error", envelope.getString("error"));
    assertNotEquals("forbidden", envelope.getString("error"));
  }
}
