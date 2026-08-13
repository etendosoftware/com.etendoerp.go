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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.weld.WeldUtils;

/**
 * Unit tests for {@link NeoServletSupport#parsePath(String)}.
 */
class NeoServletSupportTest {

  @Test
  @DisplayName("Utility class hides its constructor")
  void utilityClassHidesConstructor() throws ReflectiveOperationException {
    Constructor<NeoServletSupport> constructor = NeoServletSupport.class.getDeclaredConstructor();
    assertEquals(Modifier.PRIVATE, constructor.getModifiers() & Modifier.PRIVATE);
    constructor.setAccessible(true);
    constructor.newInstance();
  }


  // -------------------------------------------------------------------------
  // parsePath
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("parsePath")
  class ParsePath {

    @Test
    @DisplayName("Null path returns discovery (all null)")
    void nullPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(null);
      assertNotNull(info);
      assertNull(info.specName);
      assertNull(info.entityName);
      assertNull(info.recordId);
    }

    @Test
    @DisplayName("Empty path returns discovery")
    void emptyPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath("");
      assertNotNull(info);
      assertNull(info.specName);
    }

    @Test
    @DisplayName("Root slash returns discovery")
    void rootSlash() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath("/");
      assertNotNull(info);
      assertNull(info.specName);
    }

    @Test
    @DisplayName("Single segment is spec name only")
    void singleSegment() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath("/sales-order");
      assertNotNull(info);
      assertEquals("sales-order", info.specName);
      assertNull(info.entityName);
      assertNull(info.recordId);
    }

    @Test
    @DisplayName("Two segments: spec + entity")
    void twoSegments() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath("/sales-order/order");
      assertNotNull(info);
      assertEquals("sales-order", info.specName);
      assertEquals("order", info.entityName);
      assertNull(info.recordId);
    }

    @Test
    @DisplayName("Three segments: spec + entity + recordId")
    void threeSegments() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath("/sales-order/order/ABC123");
      assertNotNull(info);
      assertEquals("sales-order", info.specName);
      assertEquals("order", info.entityName);
      assertEquals("ABC123", info.recordId);
      assertFalse(info.isSelector);
      assertFalse(info.isAction);
    }

    @Test
    @DisplayName("Selectors sub-endpoint recognized")
    void selectorsPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/selectors");
      assertNotNull(info);
      assertEquals("sales-order", info.specName);
      assertEquals("order", info.entityName);
      assertTrue(info.isSelector);
      assertNull(info.selectorField);
    }

    @Test
    @DisplayName("Selectors with field name recognized")
    void selectorsWithField() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/selectors/businessPartner");
      assertNotNull(info);
      assertTrue(info.isSelector);
      assertEquals("businessPartner", info.selectorField);
    }

    @Test
    @DisplayName("Evaluate-display sub-endpoint recognized")
    void evaluateDisplayPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/evaluate-display");
      assertNotNull(info);
      assertTrue(info.isEvaluateDisplay);
      assertFalse(info.isSelector);
      assertFalse(info.isAction);
    }

    @Test
    @DisplayName("Callout sub-endpoint recognized")
    void calloutPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/callout");
      assertNotNull(info);
      assertTrue(info.isCallout);
    }

    @Test
    @DisplayName("Defaults sub-endpoint recognized")
    void defaultsPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/defaults");
      assertNotNull(info);
      assertTrue(info.isDefaults);
    }

    @Test
    @DisplayName("Action sub-endpoint recognized")
    void actionPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/REC123/action");
      assertNotNull(info);
      assertTrue(info.isAction);
      assertEquals("REC123", info.recordId);
      assertNull(info.actionName);
    }

    @Test
    @DisplayName("Action with name recognized")
    void actionWithName() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/REC123/action/docAction");
      assertNotNull(info);
      assertTrue(info.isAction);
      assertEquals("REC123", info.recordId);
      assertEquals("docAction", info.actionName);
    }

    @Test
    @DisplayName("Path without leading slash is handled")
    void noLeadingSlash() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath("contacts/businessPartner");
      assertNotNull(info);
      assertEquals("contacts", info.specName);
      assertEquals("businessPartner", info.entityName);
    }
  }

  // -------------------------------------------------------------------------
  // handleWithHooks / lookupHandler
  // -------------------------------------------------------------------------

  /**
   * Mockito mocks of {@link NeoHandler} would NOT carry a real {@code @Named} annotation
   * (a Weld-proxy-style class doesn't inherit it either — the same non-{@code @Inherited}
   * pitfall documented on {@code @Named}-only handler registration) so {@code lookupHandler}
   * is exercised against a real small implementation instead.
   */
  private static class FakeHandler implements NeoHandler {
    NeoResponse preResult;
    NeoResponse postResult;
    NeoContext lastHandleContext;
    NeoContext lastAfterHandleContext;

    @Override
    public NeoResponse handle(NeoContext context) {
      lastHandleContext = context;
      return preResult;
    }

    @Override
    public NeoResponse afterHandle(NeoContext context) {
      lastAfterHandleContext = context;
      return postResult;
    }
  }

  @javax.inject.Named("test-handler-qualifier")
  private static class NamedFakeHandler extends FakeHandler {
  }

  @Nested
  @DisplayName("lookupHandler")
  class LookupHandler {

    @Test
    @DisplayName("finds a handler whose @Named value matches the qualifier")
    void findsMatchingHandler() {
      NamedFakeHandler handler = new NamedFakeHandler();
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        weld.when(() -> WeldUtils.getInstances(NeoHandler.class))
            .thenReturn(List.<NeoHandler>of(handler));
        NeoHandler found = NeoServletSupport.lookupHandler("test-handler-qualifier");
        assertSame(handler, found);
      }
    }

    @Test
    @DisplayName("returns null when no handler matches the qualifier")
    void returnsNullWhenNoMatch() {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        weld.when(() -> WeldUtils.getInstances(NeoHandler.class))
            .thenReturn(Collections.emptyList());
        assertNull(NeoServletSupport.lookupHandler("nonexistent-qualifier"));
      }
    }
  }

  @Nested
  @DisplayName("handleWithHooks")
  class HandleWithHooksTests {

    @Test
    @DisplayName("falls back to handleDefault when no handler is registered for the qualifier")
    void fallsBackToDefaultWhenNoHandler() {
      NeoCrudHandler crudHandler = mock(NeoCrudHandler.class);
      NeoContext context = NeoContext.builder().build();
      NeoResponse defaultResponse = NeoResponse.ok(new JSONObject());
      when(crudHandler.handleDefault(context)).thenReturn(defaultResponse);

      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        weld.when(() -> WeldUtils.getInstances(NeoHandler.class))
            .thenReturn(Collections.emptyList());

        NeoResponse result = NeoServletSupport.handleWithHooks("missing-qualifier", context, crudHandler);

        assertSame(defaultResponse, result);
        verify(crudHandler, times(1)).handleDefault(context);
      }
    }

    @Test
    @DisplayName("uses the handler's pre-hook result directly and never calls handleDefault, when the pre-hook returns non-null")
    void preHookShortCircuitsDefault() {
      NeoCrudHandler crudHandler = mock(NeoCrudHandler.class);
      NeoContext context = NeoContext.builder().build();
      NamedFakeHandler handler = new NamedFakeHandler();
      handler.preResult = NeoResponse.ok(new JSONObject());
      handler.postResult = null;

      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        weld.when(() -> WeldUtils.getInstances(NeoHandler.class))
            .thenReturn(List.<NeoHandler>of(handler));

        NeoResponse result = NeoServletSupport.handleWithHooks("test-handler-qualifier", context, crudHandler);

        assertSame(handler.preResult, result);
        verify(crudHandler, never()).handleDefault(any());
        assertSame(handler.preResult, handler.lastAfterHandleContext.getPreviousResult());
      }
    }

    @Test
    @DisplayName("a non-null post-hook result replaces the pre-hook result")
    void postHookReplacesPreHookResult() {
      NeoCrudHandler crudHandler = mock(NeoCrudHandler.class);
      NeoContext context = NeoContext.builder().build();
      NamedFakeHandler handler = new NamedFakeHandler();
      handler.preResult = NeoResponse.ok(new JSONObject());
      handler.postResult = NeoResponse.error(400, "replaced");

      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        weld.when(() -> WeldUtils.getInstances(NeoHandler.class))
            .thenReturn(List.<NeoHandler>of(handler));

        NeoResponse result = NeoServletSupport.handleWithHooks("test-handler-qualifier", context, crudHandler);

        assertSame(handler.postResult, result);
      }
    }

    @Test
    @DisplayName("runs handleDefault as the default service, then lets the post-hook see and optionally replace it")
    void runsDefaultServiceWhenPreHookDeclines() {
      NeoCrudHandler crudHandler = mock(NeoCrudHandler.class);
      NeoContext context = NeoContext.builder().build();
      NeoResponse defaultResponse = NeoResponse.ok(new JSONObject());
      when(crudHandler.handleDefault(context)).thenReturn(defaultResponse);

      NamedFakeHandler handler = new NamedFakeHandler();
      handler.preResult = null;
      handler.postResult = null;

      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        weld.when(() -> WeldUtils.getInstances(NeoHandler.class))
            .thenReturn(List.<NeoHandler>of(handler));

        NeoResponse result = NeoServletSupport.handleWithHooks("test-handler-qualifier", context, crudHandler);

        assertSame(defaultResponse, result);
        verify(crudHandler, times(1)).handleDefault(context);
        assertSame(defaultResponse, handler.lastAfterHandleContext.getPreviousResult());
      }
    }
  }
}