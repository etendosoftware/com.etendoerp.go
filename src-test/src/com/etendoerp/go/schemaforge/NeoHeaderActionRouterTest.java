/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NeoHeaderActionRouter}.
 */
class NeoHeaderActionRouterTest {

  private static NeoResponse invokeDispatch(NeoContext context, NeoHandler... handlers)
      throws Exception {
    Method method = NeoHeaderActionRouter.class.getDeclaredMethod("dispatch",
        NeoContext.class, NeoHandler[].class);
    method.setAccessible(true);
    return (NeoResponse) method.invoke(null, context, handlers);
  }

  private NeoContext buildContext() {
    return NeoContext.builder()
        .specName("test").entityName("header")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .build();
  }

  @Test
  @DisplayName("null handlers array returns null")
  void nullHandlersReturnsNull() throws Exception {
    assertNull(invokeDispatch(buildContext(), (NeoHandler[]) null));
  }

  @Test
  @DisplayName("empty handlers array returns null")
  void emptyHandlersReturnsNull() throws Exception {
    assertNull(invokeDispatch(buildContext()));
  }

  @Test
  @DisplayName("all handlers returning null returns null")
  void allNullResponsesReturnsNull() throws Exception {
    NeoHandler h1 = mock(NeoHandler.class);
    NeoHandler h2 = mock(NeoHandler.class);
    NeoContext ctx = buildContext();
    when(h1.handle(ctx)).thenReturn(null);
    when(h2.handle(ctx)).thenReturn(null);

    assertNull(invokeDispatch(ctx, h1, h2));
    verify(h1).handle(ctx);
    verify(h2).handle(ctx);
  }

  @Test
  @DisplayName("returns first non-null response")
  void returnsFirstNonNullResponse() throws Exception {
    NeoHandler h1 = mock(NeoHandler.class);
    NeoHandler h2 = mock(NeoHandler.class);
    NeoContext ctx = buildContext();
    NeoResponse expected = NeoResponse.ok(new JSONObject());
    when(h1.handle(ctx)).thenReturn(null);
    when(h2.handle(ctx)).thenReturn(expected);

    NeoResponse result = invokeDispatch(ctx, h1, h2);
    assertEquals(expected, result);
  }

  @Test
  @DisplayName("stops at first non-null response, skips remaining handlers")
  void stopsAtFirstNonNull() throws Exception {
    NeoHandler h1 = mock(NeoHandler.class);
    NeoHandler h2 = mock(NeoHandler.class);
    NeoContext ctx = buildContext();
    NeoResponse expected = NeoResponse.ok(new JSONObject());
    when(h1.handle(ctx)).thenReturn(expected);

    NeoResponse result = invokeDispatch(ctx, h1, h2);
    assertEquals(expected, result);
    verify(h2, never()).handle(ctx);
  }

  @Test
  @DisplayName("null handler in array is skipped")
  void nullHandlerSkipped() throws Exception {
    NeoHandler h1 = mock(NeoHandler.class);
    NeoContext ctx = buildContext();
    NeoResponse expected = NeoResponse.ok(new JSONObject());
    when(h1.handle(ctx)).thenReturn(expected);

    NeoResponse result = invokeDispatch(ctx, null, h1);
    assertEquals(expected, result);
  }
}
