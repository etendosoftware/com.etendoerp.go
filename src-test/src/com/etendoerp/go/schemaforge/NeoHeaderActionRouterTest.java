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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

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

  // ── declaredActions (ETP-5447) ─────────────────────────────────────────

  private static final String SPEC = "financial-account";
  private static final String ENTITY = "account";
  private static final String ACTION_CREATE = "createStatement";
  private static final String ACTION_LIST = "listStatements";

  /** A delegate whose only behaviour is its declaration; records the spec/entity it was asked. */
  private static final class DeclaringHandler implements NeoHandler {
    private final List<NeoActionContract> declared;
    private String askedSpec;
    private String askedEntity;

    DeclaringHandler(List<NeoActionContract> declared) {
      this.declared = declared;
    }

    @Override
    public NeoResponse handle(NeoContext context) {
      return null;
    }

    @Override
    public List<NeoActionContract> declaredActions(String specName, String entityName) {
      askedSpec = specName;
      askedEntity = entityName;
      return declared;
    }
  }

  private static NeoActionContract contract(String name, String method) {
    return NeoActionContract.builder(name).method(method).build();
  }

  @Test
  void testDeclaredActionsConcatenatesDelegatesInOrder() {
    NeoActionContract create = contract(ACTION_CREATE, NeoActionContract.METHOD_POST);
    NeoActionContract list = contract(ACTION_LIST, NeoActionContract.METHOD_GET);
    NeoActionContract lines = contract("lines", NeoActionContract.METHOD_GET);

    List<NeoActionContract> combined = NeoHeaderActionRouter.declaredActions(SPEC, ENTITY,
        new DeclaringHandler(List.of(create, list)), new DeclaringHandler(List.of(lines)));

    assertEquals(Arrays.asList(create, list, lines), combined);
  }

  @Test
  void testDeclaredActionsDeduplicatesByNameAndFirstDelegateWins() {
    NeoActionContract firstCreate = contract(ACTION_CREATE, NeoActionContract.METHOD_POST);
    NeoActionContract secondCreate = contract(ACTION_CREATE, NeoActionContract.METHOD_GET);
    NeoActionContract list = contract(ACTION_LIST, NeoActionContract.METHOD_GET);

    List<NeoActionContract> combined = NeoHeaderActionRouter.declaredActions(SPEC, ENTITY,
        new DeclaringHandler(List.of(firstCreate)),
        new DeclaringHandler(List.of(secondCreate, list)));

    assertEquals(2, combined.size());
    assertSame(firstCreate, combined.get(0));
    assertEquals(NeoActionContract.METHOD_POST, combined.get(0).getMethod());
    assertSame(list, combined.get(1));
  }

  @Test
  void testDeclaredActionsDeduplicatesWithinASingleDelegate() {
    NeoActionContract first = contract(ACTION_CREATE, NeoActionContract.METHOD_POST);
    NeoActionContract duplicate = contract(ACTION_CREATE, NeoActionContract.METHOD_GET);

    List<NeoActionContract> combined = NeoHeaderActionRouter.declaredActions(SPEC, ENTITY,
        new DeclaringHandler(List.of(first, duplicate)));

    assertEquals(List.of(first), combined);
  }

  @Test
  void testDeclaredActionsSkipsNullDelegatesNullListsAndNullEntries() {
    NeoActionContract create = contract(ACTION_CREATE, NeoActionContract.METHOD_POST);
    List<NeoActionContract> withNullEntry = new ArrayList<>();
    withNullEntry.add(null);
    withNullEntry.add(create);

    List<NeoActionContract> combined = NeoHeaderActionRouter.declaredActions(SPEC, ENTITY,
        null, new DeclaringHandler(null), new DeclaringHandler(withNullEntry));

    assertEquals(List.of(create), combined);
  }

  @Test
  void testDeclaredActionsReturnsEmptyForNullDelegatesArray() {
    List<NeoActionContract> combined = NeoHeaderActionRouter.declaredActions(SPEC, ENTITY,
        (NeoHandler[]) null);

    assertNotNull(combined);
    assertTrue(combined.isEmpty());
  }

  @Test
  void testDeclaredActionsReturnsEmptyForNoDelegates() {
    List<NeoActionContract> combined = NeoHeaderActionRouter.declaredActions(SPEC, ENTITY);

    assertNotNull(combined);
    assertTrue(combined.isEmpty());
  }

  @Test
  void testDeclaredActionsReturnsEmptyForDefaultDeclarations() {
    NeoHandler plain = context -> null;

    List<NeoActionContract> combined = NeoHeaderActionRouter.declaredActions(SPEC, ENTITY,
        plain);

    assertTrue(combined.isEmpty());
  }

  @Test
  void testDeclaredActionsPassesSpecAndEntityToEachDelegate() {
    DeclaringHandler first = new DeclaringHandler(List.of());
    DeclaringHandler second = new DeclaringHandler(List.of());

    NeoHeaderActionRouter.declaredActions(SPEC, ENTITY, first, second);

    assertEquals(SPEC, first.askedSpec);
    assertEquals(ENTITY, first.askedEntity);
    assertEquals(SPEC, second.askedSpec);
    assertEquals(ENTITY, second.askedEntity);
  }
}
