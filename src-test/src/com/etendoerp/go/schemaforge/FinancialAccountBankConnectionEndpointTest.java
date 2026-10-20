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
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAuditTokenRefresh;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * The bank connection actions reach the SPA's {@code financial-account-bank-connection} endpoint
 * through the dispatcher, as REST does. Sibling of
 * {@link FinancialAccountTransactionsEndpointTest}: that class stubs the transactions spec, so the
 * bank connection spec and message need their own fixture.
 *
 * @covers com.etendoerp.go.schemaforge.FinancialAccountBankConnectionEndpoint
 */
@DisplayName("FinancialAccountBankConnectionEndpoint")
class FinancialAccountBankConnectionEndpointTest {

  private static final String QUALIFIER = "financial-account-bank-connection";

  private MockedStatic<NeoServletSupport> supportMock;
  private MockedStatic<NeoReportCallability> callabilityMock;
  private MockedStatic<NeoExtensionDispatcher> dispatchMock;
  private MockedStatic<NeoAuditTokenRefresh> refreshMock;
  private final List<NeoExtensionRequest> dispatched = new ArrayList<>();
  private SFSpec spec;
  private NeoHandler handler;
  private NeoResponse pre;

  @BeforeEach
  void setUp() {
    supportMock = mockStatic(NeoServletSupport.class, CALLS_REAL_METHODS);
    spec = mock(SFSpec.class);
    supportMock.when(() -> NeoServletSupport.findSpec(FinancialAccountBankConnectionEndpoint.SPEC))
        .thenReturn(spec);
    callabilityMock = mockStatic(NeoReportCallability.class);
    callabilityMock.when(() -> NeoReportCallability.resolveReportHandlerQualifier(spec))
        .thenReturn(QUALIFIER);
    refreshMock = mockStatic(NeoAuditTokenRefresh.class);
    handler = mock(NeoHandler.class);
    dispatchMock = mockStatic(NeoExtensionDispatcher.class);
    dispatchMock.when(() -> NeoExtensionDispatcher.dispatch(any())).thenAnswer(inv -> {
      NeoExtensionRequest request = inv.getArgument(0);
      dispatched.add(request);
      boolean postPhase = request.phase() == NeoExtensionRequest.Phase.POST;
      return new NeoExtensionResult(handler, postPhase ? null : pre, null);
    });
  }

  @AfterEach
  void tearDown() {
    dispatchMock.close();
    refreshMock.close();
    callabilityMock.close();
    supportMock.close();
  }

  private static NeoContext context(boolean mcp) {
    return NeoContext.builder()
        .specName(FinancialAccountBankConnectionEndpoint.SPEC)
        .entityName(FinancialAccountBankConnectionEndpoint.SPEC)
        .httpMethod("POST")
        .queryParams(Map.of("action", "disconnect"))
        .requestBody(new JSONObject())
        .endpointType(NeoEndpointType.CRUD)
        .mcpOrigin(mcp)
        .build();
  }

  @Test
  @DisplayName("an MCP call runs the resolved qualifier's hooks on the MCP channel")
  void mcpOriginUsesTheMcpChannel() throws Exception {
    pre = NeoResponse.ok(new JSONObject().put("ran", true));
    NeoContext ctx = context(true);

    NeoResponse result = FinancialAccountBankConnectionEndpoint.call(ctx);

    assertSame(pre, result);
    assertEquals(2, dispatched.size(), "pre and post phases");
    assertEquals(QUALIFIER, dispatched.get(0).qualifier());
    assertEquals(NeoExtensionChannel.MCP, dispatched.get(0).channel());
    assertSame(ctx, dispatched.get(0).context());
    assertEquals(NeoExtensionRequest.Phase.POST, dispatched.get(1).phase());
  }

  @Test
  @DisplayName("a REST-origin call is traced on the REST channel")
  void restOriginUsesTheRestChannel() throws Exception {
    pre = NeoResponse.ok(new JSONObject());
    FinancialAccountBankConnectionEndpoint.call(context(false));
    assertEquals(NeoExtensionChannel.REST_SINGLE, dispatched.get(0).channel());
  }

  @Test
  @DisplayName("no customization is a 500 'not configured'")
  void noCustomizationIsNotConfigured() {
    handler = null;
    NeoResponse result = FinancialAccountBankConnectionEndpoint.call(context(true));
    assertEquals(500, result.getHttpStatus());
    assertTrue(result.getBody().toString()
        .contains(FinancialAccountBankConnectionEndpoint.MSG_NOT_CONFIGURED),
        result.getBody().toString());
  }

  @Test
  @DisplayName("a missing spec is a 500 'not configured' and resolves no qualifier")
  void missingSpecIsNotConfigured() {
    supportMock.when(() -> NeoServletSupport.findSpec(FinancialAccountBankConnectionEndpoint.SPEC))
        .thenReturn(null);
    handler = null;

    NeoResponse result = FinancialAccountBankConnectionEndpoint.call(context(true));

    assertEquals(500, result.getHttpStatus());
    assertTrue(result.getBody().toString()
        .contains(FinancialAccountBankConnectionEndpoint.MSG_NOT_CONFIGURED));
    callabilityMock.verifyNoInteractions();
  }
}
