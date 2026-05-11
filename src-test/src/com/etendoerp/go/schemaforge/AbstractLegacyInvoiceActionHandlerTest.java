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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import javax.servlet.http.HttpServletResponse;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;

/**
 * Unit tests for {@link AbstractLegacyInvoiceActionHandler}.
 *
 * <p>Covers the routing guard logic inside {@code handle()}: endpoint type check,
 * HTTP method check, action name matching, blank record-ID guard, success delegation,
 * and exception wrapping.
 */
public class AbstractLegacyInvoiceActionHandlerTest {

  private static final String MATCHING_ACTION = "test-action";

  /** Minimal concrete subclass used across all tests. */
  private static class StubHandler extends AbstractLegacyInvoiceActionHandler {

    private NeoResponse toReturn = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());
    private boolean throwOnExecute = false;

    @Override
    protected boolean matchesActionName(String fieldName) {
      return MATCHING_ACTION.equals(fieldName);
    }

    @Override
    protected NeoResponse executeAction(String recordId) throws Exception {
      if (throwOnExecute) {
        throw new RuntimeException("action failed");
      }
      return toReturn;
    }

    @Override
    protected String buildExecutionErrorMessage(Exception e) {
      return "stub error: " + e.getMessage();
    }
  }

  private static NeoContext actionContext(String fieldName, String recordId) {
    return NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(fieldName)
        .recordId(recordId)
        .build();
  }

  @Test
  public void testReturnsNullForNonActionEndpoint() {
    StubHandler handler = new StubHandler();
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .fieldName(MATCHING_ACTION)
        .recordId("rec-1")
        .build();

    assertNull(handler.handle(ctx));
  }

  @Test
  public void testReturnsNullForNonPostMethod() {
    StubHandler handler = new StubHandler();
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(MATCHING_ACTION)
        .recordId("rec-1")
        .build();

    assertNull(handler.handle(ctx));
  }

  @Test
  public void testReturnsNullWhenActionNameDoesNotMatch() {
    StubHandler handler = new StubHandler();
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("other-action")
        .recordId("rec-1")
        .build();

    assertNull(handler.handle(ctx));
  }

  @Test
  public void testReturns400WhenRecordIdIsBlank() {
    StubHandler handler = new StubHandler();
    NeoContext ctx = actionContext(MATCHING_ACTION, "");

    NeoResponse response = handler.handle(ctx);

    assertNotNull(response);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
  }

  @Test
  public void testReturns400WhenRecordIdIsNull() {
    StubHandler handler = new StubHandler();
    NeoContext ctx = actionContext(MATCHING_ACTION, null);

    NeoResponse response = handler.handle(ctx);

    assertNotNull(response);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
  }

  @Test
  public void testDelegatesExecuteActionAndReturnsResult() {
    StubHandler handler = new StubHandler();
    NeoContext ctx = actionContext(MATCHING_ACTION, "invoice-abc");

    try (MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
      obContext.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      obContext.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      NeoResponse result = handler.handle(ctx);

      assertSame(handler.toReturn, result);
    }
  }

  @Test
  public void testReturns500WhenExecuteActionThrows() {
    StubHandler handler = new StubHandler();
    handler.throwOnExecute = true;
    NeoContext ctx = actionContext(MATCHING_ACTION, "invoice-abc");

    try (MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
      obContext.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      obContext.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }
  }
}
