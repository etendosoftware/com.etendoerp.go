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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * Unit tests for {@link NeoReadPredicates} (ETP-5009): the single place every generic list read
 * asks an entity's customization for the predicates its rows must satisfy.
 */
@DisplayName("NeoReadPredicates — customization read predicates (ETP-5009)")
class NeoReadPredicatesTest {

  private static final String SPEC = "product";
  private static final String ENTITY = "product";
  private static final String QUALIFIER = "productDefaultsHandler";

  private static NeoContext context(String qualifier) {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getJavaQualifier()).thenReturn(qualifier);
    return NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .sfEntity(sfEntity)
        .build();
  }

  private static NeoHandler declaring(List<String> predicates) {
    NeoHandler customization = mock(NeoHandler.class);
    when(customization.readPredicates(any())).thenReturn(predicates);
    return customization;
  }

  // ---- resolve ----

  @Test
  @DisplayName("resolve — A null context declares nothing and resolves nothing")
  void resolveNullContextReturnsNull() {
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      assertNull(NeoReadPredicates.resolve(null, NeoExtensionChannel.REST_SINGLE));
      dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(any()), never());
    }
  }

  @Test
  @DisplayName("resolve — No customization resolved returns null")
  void resolveNoCustomizationReturnsNull() {
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);

      assertNull(NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
    }
  }

  @Test
  @DisplayName("resolve — A customization that declares no predicate returns null")
  void resolveEmptyPredicatesReturnNull() {
    NeoHandler customization = declaring(Collections.emptyList());
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
          .thenReturn(customization);

      assertNull(NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
    }
  }

  @Test
  @DisplayName("resolve — A customization that returns a null list returns null")
  void resolveNullPredicateListReturnsNull() {
    NeoHandler customization = declaring(null);
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
          .thenReturn(customization);

      assertNull(NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.MCP));
    }
  }

  @Test
  @DisplayName("resolve — The default NeoHandler#readPredicates declares nothing")
  void resolveDefaultReadPredicatesIsEmpty() {
    NeoHandler plain = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        return null;
      }

      @Override
      public NeoResponse afterHandle(NeoContext context) {
        return null;
      }
    };
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(plain);

      assertEquals(Collections.emptyList(), plain.readPredicates(context(QUALIFIER)));
      assertNull(NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
    }
  }

  @Test
  @DisplayName("resolve — One predicate is returned parenthesised")
  void resolveOnePredicateIsParenthesised() {
    NeoHandler customization = declaring(List.of("e.active = true"));
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
          .thenReturn(customization);

      assertEquals("(e.active = true)",
          NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
    }
  }

  @Test
  @DisplayName("resolve — Several predicates are parenthesised and joined with and")
  void resolveSeveralPredicatesAreAnded() {
    NeoHandler customization = declaring(List.of("e.a = 1", "e.b = 2 or e.b is null"));
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
          .thenReturn(customization);

      assertEquals("(e.a = 1) and (e.b = 2 or e.b is null)",
          NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
    }
  }

  @Test
  @DisplayName("resolve — Blank and null entries are skipped")
  void resolveBlankEntriesAreSkipped() {
    NeoHandler customization = declaring(Arrays.asList("", "e.a = 1", "   ", null, "e.b = 2"));
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
          .thenReturn(customization);

      assertEquals("(e.a = 1) and (e.b = 2)",
          NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
    }
  }

  @Test
  @DisplayName("resolve — Only blank entries return null")
  void resolveOnlyBlankEntriesReturnNull() {
    NeoHandler customization = declaring(Arrays.asList("", "  ", null));
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
          .thenReturn(customization);

      assertNull(NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
    }
  }

  @Test
  @DisplayName("resolve — An exception thrown by the customization propagates")
  void resolveExceptionPropagates() {
    NeoHandler customization = mock(NeoHandler.class);
    IllegalStateException failure = new IllegalStateException("predicate failure");
    when(customization.readPredicates(any())).thenThrow(failure);
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
          .thenReturn(customization);

      NeoContext ctx = context(QUALIFIER);
      IllegalStateException thrown = assertThrows(IllegalStateException.class,
          () -> NeoReadPredicates.resolve(ctx, NeoExtensionChannel.REST_SINGLE));
      assertSame(failure, thrown);
    }
  }

  @Test
  @DisplayName("resolve — The request carries spec, entity, qualifier, READ surface and channel")
  void resolveRequestCarriesTheReadCoordinates() {
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
      NeoContext ctx = context(QUALIFIER);

      NeoReadPredicates.resolve(ctx, NeoExtensionChannel.MCP);

      ArgumentCaptor<NeoExtensionRequest> captor =
          ArgumentCaptor.forClass(NeoExtensionRequest.class);
      dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(captor.capture()));
      NeoExtensionRequest request = captor.getValue();
      assertEquals(QUALIFIER, request.qualifier());
      assertEquals(SPEC, request.specName());
      assertEquals(ENTITY, request.entityName());
      assertEquals(NeoExtensionSurface.READ, request.surface());
      assertEquals(NeoExtensionChannel.MCP, request.channel());
      assertSame(ctx, request.context());
    }
  }

  @Test
  @DisplayName("resolve — A context with no SFEntity resolves with a null qualifier")
  void resolveNoSfEntityMeansNullQualifier() {
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
      NeoContext ctx = NeoContext.builder()
          .specName(SPEC).entityName(ENTITY).httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD).build();

      assertNull(NeoReadPredicates.resolve(ctx, NeoExtensionChannel.REST_SINGLE));

      ArgumentCaptor<NeoExtensionRequest> captor =
          ArgumentCaptor.forClass(NeoExtensionRequest.class);
      dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(captor.capture()));
      assertNull(captor.getValue().qualifier());
    }
  }

  // ---- resolve through the real NeoExtensionDispatcher: the qualifier fallback picks the
  // resolver of the channel, the same split every other surface uses ----

  @Test
  @DisplayName("resolve via dispatcher — REST resolves the qualifier through NeoServletSupport.lookupHandler")
  void dispatcherRestUsesServletLookup() {
    NeoHandler customization = declaring(List.of("e.rest = true"));
    try (MockedStatic<NeoExtensionIndex> index = mockStatic(NeoExtensionIndex.class);
         MockedStatic<NeoServletSupport> rest = mockStatic(NeoServletSupport.class);
         MockedStatic<NeoHandlerLookup> mcp = mockStatic(NeoHandlerLookup.class)) {
      index.when(() -> NeoExtensionIndex.resolve(any(), any(), any())).thenReturn(null);
      rest.when(() -> NeoServletSupport.lookupHandler(QUALIFIER)).thenReturn(customization);

      assertEquals("(e.rest = true)",
          NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
      mcp.verify(() -> NeoHandlerLookup.byQualifier(any()), never());
    }
  }

  @Test
  @DisplayName("resolve via dispatcher — MCP resolves the qualifier through NeoHandlerLookup.byQualifier")
  void dispatcherMcpUsesHandlerLookup() {
    NeoHandler customization = declaring(List.of("e.mcp = true"));
    try (MockedStatic<NeoExtensionIndex> index = mockStatic(NeoExtensionIndex.class);
         MockedStatic<NeoServletSupport> rest = mockStatic(NeoServletSupport.class);
         MockedStatic<NeoHandlerLookup> mcp = mockStatic(NeoHandlerLookup.class)) {
      index.when(() -> NeoExtensionIndex.resolve(any(), any(), any())).thenReturn(null);
      mcp.when(() -> NeoHandlerLookup.byQualifier(QUALIFIER)).thenReturn(customization);

      assertEquals("(e.mcp = true)",
          NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.MCP));
      rest.verify(() -> NeoServletSupport.lookupHandler(any()), never());
    }
  }

  @Test
  @DisplayName("resolve via dispatcher — An @NeoExtension customization wins over the qualifier")
  void dispatcherAnnotationWinsOverQualifier() {
    NeoHandler annotated = declaring(List.of("e.annotated = true"));
    try (MockedStatic<NeoExtensionIndex> index = mockStatic(NeoExtensionIndex.class);
         MockedStatic<NeoServletSupport> rest = mockStatic(NeoServletSupport.class)) {
      index.when(() -> NeoExtensionIndex.resolve(SPEC, ENTITY, QUALIFIER)).thenReturn(annotated);

      assertEquals("(e.annotated = true)",
          NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
      rest.verify(() -> NeoServletSupport.lookupHandler(any()), never());
    }
  }

  @Test
  @DisplayName("resolve via dispatcher — No annotation and a blank qualifier resolves nothing")
  void dispatcherBlankQualifierResolvesNothing() {
    try (MockedStatic<NeoExtensionIndex> index = mockStatic(NeoExtensionIndex.class);
         MockedStatic<NeoServletSupport> rest = mockStatic(NeoServletSupport.class)) {
      index.when(() -> NeoExtensionIndex.resolve(any(), any(), any())).thenReturn(null);

      assertNull(NeoReadPredicates.resolve(context(null), NeoExtensionChannel.REST_SINGLE));
      rest.verify(() -> NeoServletSupport.lookupHandler(any()), never());
    }
  }

  // ---- and ----

  @Test
  @DisplayName("and — Both present are parenthesised and joined")
  void andBothPresent() {
    assertEquals("(e.a = 1) and ((e.b = 2))", NeoReadPredicates.and("e.a = 1", "(e.b = 2)"));
  }

  @Test
  @DisplayName("and — A blank predicate returns the existing clause unchanged")
  void andBlankPredicate() {
    assertEquals("e.a = 1", NeoReadPredicates.and("e.a = 1", "  "));
    assertEquals("e.a = 1", NeoReadPredicates.and("e.a = 1", null));
  }

  @Test
  @DisplayName("and — A blank existing clause returns the predicate alone")
  void andBlankExisting() {
    assertEquals("(e.b = 2)", NeoReadPredicates.and(null, "(e.b = 2)"));
    assertEquals("(e.b = 2)", NeoReadPredicates.and("", "(e.b = 2)"));
  }

  @Test
  @DisplayName("and — Both blank returns the existing value")
  void andBothBlank() {
    assertNull(NeoReadPredicates.and(null, null));
  }

  // ---- forRestListGet ----

  @Test
  @DisplayName("forRestListGet — A non-GET request resolves nothing")
  void forRestListGetNonGetReturnsNull() {
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      SFEntity sfEntity = mock(SFEntity.class);
      when(sfEntity.getJavaQualifier()).thenReturn(QUALIFIER);
      NeoContext ctx = NeoContext.builder()
          .specName(SPEC).entityName(ENTITY).httpMethod("POST")
          .endpointType(NeoEndpointType.CRUD).sfEntity(sfEntity).build();

      assertNull(NeoReadPredicates.forRestListGet(ctx));
      dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(any()), never());
    }
  }

  @Test
  @DisplayName("forRestListGet — A GET by path id resolves nothing")
  void forRestListGetReadByPathIdReturnsNull() {
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      NeoContext ctx = NeoContext.builder()
          .specName(SPEC).entityName(ENTITY).httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD).recordId("ABC").build();

      assertNull(NeoReadPredicates.forRestListGet(ctx));
      dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(any()), never());
    }
  }

  @Test
  @DisplayName("forRestListGet — A GET by query-string id resolves nothing")
  void forRestListGetReadByQueryIdReturnsNull() {
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      NeoContext ctx = NeoContext.builder()
          .specName(SPEC).entityName(ENTITY).httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD).queryParams(Map.of("id", "ABC")).build();

      assertNull(NeoReadPredicates.forRestListGet(ctx));
      dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(any()), never());
    }
  }

  @Test
  @DisplayName("forRestListGet — A list GET resolves on the REST_SINGLE channel")
  void forRestListGetListGetResolves() {
    NeoHandler customization = declaring(List.of("e.active = true"));
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
          .thenReturn(customization);

      assertEquals("(e.active = true)", NeoReadPredicates.forRestListGet(context(QUALIFIER)));

      ArgumentCaptor<NeoExtensionRequest> captor =
          ArgumentCaptor.forClass(NeoExtensionRequest.class);
      dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(captor.capture()));
      assertEquals(NeoExtensionChannel.REST_SINGLE, captor.getValue().channel());
    }
  }

  @Test
  @DisplayName("forRestListGet — A list GET with no customization returns null")
  void forRestListGetNoCustomizationReturnsNull() {
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);

      assertNull(NeoReadPredicates.forRestListGet(context(QUALIFIER)));
    }
  }

  // ---- addRestTo ----

  @Test
  @DisplayName("addRestTo — The resolved predicate is added parenthesised")
  void addRestToAddsWrappedPredicate() {
    NeoHandler customization = declaring(List.of("e.a = 1", "e.b = 2"));
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
          .thenReturn(customization);
      List<String> predicates = new ArrayList<>(List.of("e.existing = true"));

      NeoReadPredicates.addRestTo(predicates, context(QUALIFIER));

      assertEquals(List.of("e.existing = true", "((e.a = 1) and (e.b = 2))"), predicates);
    }
  }

  @Test
  @DisplayName("addRestTo — Nothing is added when no predicate is declared")
  void addRestToAddsNothingWhenNone() {
    NeoHandler customization = declaring(Collections.emptyList());
    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
             mockStatic(NeoExtensionDispatcher.class)) {
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
          .thenReturn(customization);
      List<String> predicates = new ArrayList<>(List.of("e.existing = true"));

      NeoReadPredicates.addRestTo(predicates, context(QUALIFIER));

      assertEquals(List.of("e.existing = true"), predicates);
    }
  }

  // ---- appendAnd ----

  @Test
  @DisplayName("appendAnd — On an empty builder the predicate is appended parenthesised")
  void appendAndEmptyBuilder() {
    StringBuilder where = new StringBuilder();

    NeoReadPredicates.appendAnd(where, "e.a = 1");

    assertEquals("(e.a = 1)", where.toString());
  }

  @Test
  @DisplayName("appendAnd — On a non-empty builder the predicate is ANDed without re-wrapping")
  void appendAndNonEmptyBuilder() {
    StringBuilder where = new StringBuilder("e.x = 1 or e.y = 2");

    NeoReadPredicates.appendAnd(where, "e.a = 1");

    assertEquals("e.x = 1 or e.y = 2 and (e.a = 1)", where.toString());
  }

  @Test
  @DisplayName("appendAnd — A blank or null predicate leaves the builder untouched")
  void appendAndBlankPredicateIsNoOp() {
    StringBuilder where = new StringBuilder("e.x = 1");

    NeoReadPredicates.appendAnd(where, "  ");
    NeoReadPredicates.appendAnd(where, null);
    NeoReadPredicates.appendAnd(where, "");

    assertEquals("e.x = 1", where.toString());
  }
}
