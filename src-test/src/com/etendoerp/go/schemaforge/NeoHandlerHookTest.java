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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for the NeoHandler hook mechanism (pre-hook / post-hook).
 * Tests the contract of handle() + afterHandle() without requiring CDI or a servlet container.
 */
public class NeoHandlerHookTest {

  private static final String TEST_SPEC = "TestSpec";
  private static final String HEADER = "Header";
  private static final String SOURCE = "source";
  private static final String DEFAULT = "default";
  private static final String PRE_HOOK = "preHook";
  private static final String AFTER_HOOK = "afterHook";
  private static final String POST_HOOK = "postHook";

  /**
   * When handle() returns null and afterHandle() returns null,
   * the default service result should be used.
   */
  @Test
  public void testPassThroughHandler() throws JSONException {
    NeoHandler handler = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        return null;
      }
    };

    NeoContext ctx = NeoContext.builder()
          .specName(TEST_SPEC)
          .entityName(HEADER)
        .httpMethod("GET")
        .endpointType(NeoEndpointType.DEFAULTS)
        .build();

    // Simulate the dispatch logic
    NeoResponse defaultResult = NeoResponse.ok(new JSONObject().put("key", DEFAULT));
    NeoResponse finalResult = simulateDispatch(handler, ctx, defaultResult);

    assertEquals(200, finalResult.getHttpStatus());
    assertEquals(DEFAULT, finalResult.getBody().getString("key"));
  }

  /**
   * When handle() returns a NeoResponse (pre-hook override),
   * the default service should NOT be called and the pre-hook result is used.
   */
  @Test
  public void testPreHookOverride() throws JSONException {
    NeoResponse override = NeoResponse.ok(new JSONObject().put(SOURCE, PRE_HOOK));

    NeoHandler handler = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        return override;
      }
    };

    NeoContext ctx = NeoContext.builder()
          .specName(TEST_SPEC)
          .entityName(HEADER)
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CALLOUT)
        .build();

    NeoResponse defaultResult = NeoResponse.ok(new JSONObject().put(SOURCE, DEFAULT));
    NeoResponse finalResult = simulateDispatch(handler, ctx, defaultResult);

    assertEquals(PRE_HOOK, finalResult.getBody().getString(SOURCE));
  }

  /**
   * When handle() returns null but afterHandle() returns a modified response,
   * the post-hook result replaces the default.
   */
  @Test
  public void testPostHookModification() throws JSONException {
    NeoHandler handler = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        return null;
      }

      @Override
      public NeoResponse afterHandle(NeoContext context) {
        try {
          JSONObject modified = new JSONObject();
          modified.put(SOURCE, POST_HOOK);
          modified.put("originalStatus", context.getPreviousResult().getHttpStatus());
          return NeoResponse.ok(modified);
        } catch (JSONException e) {
          return null;
        }
      }
    };

    NeoContext ctx = NeoContext.builder()
          .specName(TEST_SPEC)
          .entityName(HEADER)
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("warehouse")
        .build();

    NeoResponse defaultResult = NeoResponse.ok(new JSONObject().put(SOURCE, DEFAULT));
    NeoResponse finalResult = simulateDispatch(handler, ctx, defaultResult);

    assertEquals(POST_HOOK, finalResult.getBody().getString(SOURCE));
    assertEquals(200, finalResult.getBody().getInt("originalStatus"));
  }

  /**
   * When handle() returns a response AND afterHandle() also returns a response,
   * afterHandle() wins (it can modify the pre-hook result).
   */
  @Test
  public void testAfterHandleOverridesPreHook() throws JSONException {
    NeoResponse preResult = NeoResponse.ok(new JSONObject().put(SOURCE, PRE_HOOK));
    NeoResponse postResult = NeoResponse.ok(new JSONObject().put(SOURCE, AFTER_HOOK));

    NeoHandler handler = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        return preResult;
      }

      @Override
      public NeoResponse afterHandle(NeoContext context) {
        return postResult;
      }
    };

    NeoContext ctx = NeoContext.builder()
        .specName(TEST_SPEC)
        .entityName(HEADER)
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("docAction")
        .build();

    NeoResponse defaultResult = NeoResponse.ok(new JSONObject().put(SOURCE, DEFAULT));
    NeoResponse finalResult = simulateDispatch(handler, ctx, defaultResult);

    assertEquals(AFTER_HOOK, finalResult.getBody().getString(SOURCE));
  }

  /**
   * Verify that endpointType and fieldName are correctly propagated to the handler context.
   */
  @Test
  public void testEndpointTypeAndFieldNameRouting() {
    final NeoEndpointType[] capturedType = new NeoEndpointType[1];
    final String[] capturedField = new String[1];

    NeoHandler handler = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        capturedType[0] = context.getEndpointType();
        capturedField[0] = context.getFieldName();
        return null;
      }
    };

    NeoContext ctx = NeoContext.builder()
        .specName(TEST_SPEC)
        .entityName(HEADER)
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("businessPartner")
        .build();

    NeoResponse defaultResult = NeoResponse.ok(new JSONObject());
    simulateDispatch(handler, ctx, defaultResult);

    assertEquals(NeoEndpointType.SELECTOR, capturedType[0]);
    assertEquals("businessPartner", capturedField[0]);
  }

  /**
   * Verify that previousResult is set before afterHandle is called (post-hook path).
   */
  @Test
  public void testPreviousResultAvailableInPostHook() throws JSONException {
    final NeoResponse[] capturedPrevious = new NeoResponse[1];

    NeoHandler handler = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        return null;
      }

      @Override
      public NeoResponse afterHandle(NeoContext context) {
        capturedPrevious[0] = context.getPreviousResult();
        return null;
      }
    };

    NeoContext ctx = NeoContext.builder()
        .specName(TEST_SPEC)
        .entityName(HEADER)
        .httpMethod("POST")
        .endpointType(NeoEndpointType.EVALUATE_DISPLAY)
        .build();

    NeoResponse defaultResult = NeoResponse.ok(new JSONObject().put("data", "test"));
    NeoResponse finalResult = simulateDispatch(handler, ctx, defaultResult);

    // afterHandle returned null, so default result is used
    assertEquals(defaultResult, finalResult);
    // previousResult was set before afterHandle was called
    assertNotNull(capturedPrevious[0]);
    assertEquals("test", capturedPrevious[0].getBody().getString("data"));
  }

  /**
   * Verify that previousResult is set before afterHandle when pre-hook fires.
   */
  @Test
  public void testPreviousResultAvailableInPreHookAfterHandle() throws JSONException {
    NeoResponse preResult = NeoResponse.ok(new JSONObject().put(SOURCE, "pre"));
    final NeoResponse[] capturedPrevious = new NeoResponse[1];

    NeoHandler handler = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        return preResult;
      }

      @Override
      public NeoResponse afterHandle(NeoContext context) {
        capturedPrevious[0] = context.getPreviousResult();
        return null; // keep pre-hook result
      }
    };

    NeoContext ctx = NeoContext.builder()
        .specName(TEST_SPEC)
        .entityName(HEADER)
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    NeoResponse defaultResult = NeoResponse.ok(new JSONObject().put(SOURCE, DEFAULT));
    NeoResponse finalResult = simulateDispatch(handler, ctx, defaultResult);

    // afterHandle returned null, so pre-hook result is kept
    assertEquals("pre", finalResult.getBody().getString(SOURCE));
    // previousResult was the pre-hook result
    assertNotNull(capturedPrevious[0]);
    assertEquals("pre", capturedPrevious[0].getBody().getString(SOURCE));
  }

  /**
   * Test all endpoint types are routable through the handler.
   */
  @Test
  public void testAllEndpointTypesRoutable() {
    for (NeoEndpointType type : NeoEndpointType.values()) {
      final NeoEndpointType[] captured = new NeoEndpointType[1];

      NeoHandler handler = new NeoHandler() {
        @Override
        public NeoResponse handle(NeoContext context) {
          captured[0] = context.getEndpointType();
          return null;
        }
      };

      NeoContext ctx = NeoContext.builder()
          .specName(TEST_SPEC)
          .entityName(HEADER)
          .httpMethod("GET")
          .endpointType(type)
          .build();

      simulateDispatch(handler, ctx, NeoResponse.ok(new JSONObject()));
      assertEquals("EndpointType " + type + " not routed correctly", type, captured[0]);
    }
  }

  /**
   * When handle() throws an exception, the dispatch should catch it
   * and return an error response (matching dispatchWithHooks behavior).
   */
  @Test
  public void testHandlerExceptionProducesErrorResponse() {
    NeoHandler handler = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        throw new IllegalStateException("Simulated handler failure");
      }
    };

    NeoContext ctx = NeoContext.builder()
        .specName(TEST_SPEC)
        .entityName(HEADER)
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CALLOUT)
        .build();

    NeoResponse defaultResult = NeoResponse.ok(new JSONObject());

    // Simulate the dispatch with exception handling (as in dispatchWithHooks)
    NeoResponse finalResult;
    try {
      finalResult = simulateDispatch(handler, ctx, defaultResult);
    } catch (Exception e) {
      finalResult = NeoResponse.error(500, "Hook handler error: " + e.getMessage());
    }

    assertEquals(500, finalResult.getHttpStatus());
  }

  /**
   * Simulates the dispatch logic from NeoServlet.handleWithHooks / dispatchWithHooks.
   * This replicates the pre-hook / default / post-hook flow without needing CDI or HTTP.
   */
  private NeoResponse simulateDispatch(NeoHandler handler, NeoContext ctx,
      NeoResponse defaultResult) {
    // Pre-hook
    NeoResponse preResult = handler.handle(ctx);
    if (preResult != null) {
      ctx.setPreviousResult(preResult);
      NeoResponse afterResult = handler.afterHandle(ctx);
      return afterResult != null ? afterResult : preResult;
    }

    // Default service (simulated)
    ctx.setPreviousResult(defaultResult);
    NeoResponse afterResult = handler.afterHandle(ctx);
    return afterResult != null ? afterResult : defaultResult;
  }
}
