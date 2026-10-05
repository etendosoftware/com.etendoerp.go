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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 * ETP-5558 — the account's movement and transfer actions reach the SPA's
 * {@code financial-account-transactions} endpoint through the dispatcher, as REST does.
 *
 * <p>They used to call {@code new FinancialAccountTransactionsHandler().handle(...)} directly: no
 * resolution (an {@code @NeoExtension} or a replaced bean was bypassed), no trace, no
 * {@code afterHandle}.</p>
 */
@DisplayName("ETP-5558 — FinancialAccountTransactionsEndpoint")
class FinancialAccountTransactionsEndpointTest {

  private static final String QUALIFIER = "financial-account-transactions";

  private MockedStatic<NeoServletSupport> supportMock;
  private MockedStatic<NeoReportCallability> callabilityMock;
  private MockedStatic<NeoExtensionDispatcher> dispatchMock;
  private MockedStatic<NeoAuditTokenRefresh> refreshMock;
  private final List<NeoExtensionRequest> dispatched = new ArrayList<>();
  private NeoHandler handler;
  private NeoResponse pre;
  private NeoResponse post;

  @BeforeEach
  void setUp() {
    supportMock = mockStatic(NeoServletSupport.class, CALLS_REAL_METHODS);
    SFSpec spec = mock(SFSpec.class);
    supportMock.when(() -> NeoServletSupport.findSpec(FinancialAccountTransactionsEndpoint.SPEC))
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
      return new NeoExtensionResult(handler, postPhase ? post : pre, null);
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
        .specName(FinancialAccountTransactionsEndpoint.SPEC)
        .entityName(FinancialAccountTransactionsEndpoint.SPEC)
        .httpMethod("POST")
        .queryParams(Map.of("action", "transfer"))
        .requestBody(new JSONObject())
        .endpointType(NeoEndpointType.CRUD)
        .mcpOrigin(mcp)
        .build();
  }

  @Test
  @DisplayName("resolves the spec's qualifier and runs handle then afterHandle, on the MCP channel")
  void runsTheHooksAsRestDoes() throws Exception {
    pre = NeoResponse.ok(new JSONObject().put("ran", true));
    NeoContext ctx = context(true);

    NeoResponse result = FinancialAccountTransactionsEndpoint.call(ctx);

    assertSame(pre, result, "afterHandle returned null: the pre result stands");
    assertEquals(2, dispatched.size(), "pre and post phases");
    NeoExtensionRequest first = dispatched.get(0);
    assertEquals(QUALIFIER, first.qualifier());
    assertEquals(NeoExtensionChannel.MCP, first.channel());
    assertSame(ctx, first.context(), "the action's own context, unchanged");
    assertEquals(NeoExtensionRequest.Phase.POST, dispatched.get(1).phase());
  }

  @Test
  @DisplayName("an afterHandle answer replaces the endpoint's, as on REST")
  void afterHandleIsHonoured() throws Exception {
    pre = NeoResponse.ok(new JSONObject());
    post = NeoResponse.ok(new JSONObject().put("decorated", true));

    assertSame(post, FinancialAccountTransactionsEndpoint.call(context(true)));
  }

  @Test
  @DisplayName("a REST-origin call is traced on the REST channel")
  void restOriginKeepsTheRestChannel() throws Exception {
    pre = NeoResponse.ok(new JSONObject());
    FinancialAccountTransactionsEndpoint.call(context(false));
    assertEquals(NeoExtensionChannel.REST_SINGLE, dispatched.get(0).channel());
  }

  @Test
  @DisplayName("a refusal short-circuits: no afterHandle")
  void refusalSkipsAfterHandle() throws Exception {
    pre = NeoResponse.error(422, "bad amount");
    NeoResponse result = FinancialAccountTransactionsEndpoint.call(context(true));
    assertSame(pre, result);
    assertEquals(1, dispatched.size());
  }

  @Test
  @DisplayName("no customization is a 500 'not configured', never the generic CRUD")
  void noCustomizationIsNotConfigured() throws Exception {
    handler = null;
    NeoResponse result = FinancialAccountTransactionsEndpoint.call(context(true));
    assertEquals(500, result.getHttpStatus());
    assertTrue(result.getBody().toString()
        .contains(FinancialAccountTransactionsEndpoint.MSG_NOT_CONFIGURED), result.getBody().toString());
  }

  @Test
  @DisplayName("neither action class calls the handler directly any more")
  void actionsDoNotBypassTheDispatcher() throws Exception {
    for (String file : List.of("FinancialAccountMovementActions.java",
        "FinancialAccountTransferActions.java")) {
      String src = source(file);
      assertFalse(src.contains("new FinancialAccountTransactionsHandler().handle("), file);
      assertTrue(src.contains("FinancialAccountTransactionsEndpoint::call"), file);
    }
    supportMock.verify(() -> NeoServletSupport.findSpec(anyString()),
        org.mockito.Mockito.never());
  }

  /** A source of this package, from the Etendo root or from the module directory. */
  private static String source(String file) throws java.io.IOException {
    String rel = "src/com/etendoerp/go/schemaforge/" + file;
    java.nio.file.Path fromRoot = java.nio.file.Path.of("modules/com.etendoerp.go", rel);
    return java.nio.file.Files.readString(java.nio.file.Files.exists(fromRoot) ? fromRoot
        : java.nio.file.Path.of(rel));
  }
}
