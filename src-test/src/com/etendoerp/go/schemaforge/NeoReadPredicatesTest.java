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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

  @Nested
  @DisplayName("resolve")
  class Resolve {

    @Test
    @DisplayName("A null context declares nothing and resolves nothing")
    void nullContextReturnsNull() {
      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               mockStatic(NeoExtensionDispatcher.class)) {
        assertNull(NeoReadPredicates.resolve(null, NeoExtensionChannel.REST_SINGLE));
        dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(any()), never());
      }
    }

    @Test
    @DisplayName("No customization resolved returns null")
    void noCustomizationReturnsNull() {
      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               mockStatic(NeoExtensionDispatcher.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);

        assertNull(NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
      }
    }

    @Test
    @DisplayName("A customization that declares no predicate returns null")
    void emptyPredicatesReturnNull() {
      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               mockStatic(NeoExtensionDispatcher.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
            .thenReturn(declaring(Collections.emptyList()));

        assertNull(NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
      }
    }

    @Test
    @DisplayName("A customization that returns a null list returns null")
    void nullPredicateListReturnsNull() {
      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               mockStatic(NeoExtensionDispatcher.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
            .thenReturn(declaring(null));

        assertNull(NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.MCP));
      }
    }

    @Test
    @DisplayName("The default NeoHandler#readPredicates declares nothing")
    void defaultReadPredicatesIsEmpty() {
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
    @DisplayName("One predicate is returned parenthesised")
    void onePredicateIsParenthesised() {
      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               mockStatic(NeoExtensionDispatcher.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
            .thenReturn(declaring(List.of("e.active = true")));

        assertEquals("(e.active = true)",
            NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
      }
    }

    @Test
    @DisplayName("Several predicates are parenthesised and joined with and")
    void severalPredicatesAreAnded() {
      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               mockStatic(NeoExtensionDispatcher.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
            .thenReturn(declaring(List.of("e.a = 1", "e.b = 2 or e.b is null")));

        assertEquals("(e.a = 1) and (e.b = 2 or e.b is null)",
            NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
      }
    }

    @Test
    @DisplayName("Blank and null entries are skipped")
    void blankEntriesAreSkipped() {
      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               mockStatic(NeoExtensionDispatcher.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
            .thenReturn(declaring(Arrays.asList("", "e.a = 1", "   ", null, "e.b = 2")));

        assertEquals("(e.a = 1) and (e.b = 2)",
            NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
      }
    }

    @Test
    @DisplayName("Only blank entries return null")
    void onlyBlankEntriesReturnNull() {
      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               mockStatic(NeoExtensionDispatcher.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
            .thenReturn(declaring(Arrays.asList("", "  ", null)));

        assertNull(NeoReadPredicates.resolve(context(QUALIFIER), NeoExtensionChannel.REST_SINGLE));
      }
    }

    @Test
    @DisplayName("An exception thrown by the customization propagates")
    void exceptionPropagates() {
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
    @DisplayName("The request carries spec, entity, qualifier, READ surface and channel")
    void requestCarriesTheReadCoordinates() {
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
    @DisplayName("A context with no SFEntity resolves with a null qualifier")
    void noSfEntityMeansNullQualifier() {
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
  }

  /**
   * Through the real {@link NeoExtensionDispatcher}: the qualifier fallback picks the resolver of
   * the channel, the same split every other surface uses.
   */
  @Nested
  @DisplayName("resolve — through the real dispatcher")
  class ResolveThroughDispatcher {

    @Test
    @DisplayName("REST resolves the qualifier through NeoServletSupport.lookupHandler")
    void restUsesServletLookup() {
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
    @DisplayName("MCP resolves the qualifier through NeoHandlerLookup.byQualifier")
    void mcpUsesHandlerLookup() {
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
    @DisplayName("An @NeoExtension customization wins over the qualifier")
    void annotationWinsOverQualifier() {
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
    @DisplayName("No annotation and a blank qualifier resolves nothing")
    void blankQualifierResolvesNothing() {
      try (MockedStatic<NeoExtensionIndex> index = mockStatic(NeoExtensionIndex.class);
           MockedStatic<NeoServletSupport> rest = mockStatic(NeoServletSupport.class)) {
        index.when(() -> NeoExtensionIndex.resolve(any(), any(), any())).thenReturn(null);

        assertNull(NeoReadPredicates.resolve(context(null), NeoExtensionChannel.REST_SINGLE));
        rest.verify(() -> NeoServletSupport.lookupHandler(any()), never());
      }
    }
  }

  @Nested
  @DisplayName("and")
  class And {

    @Test
    @DisplayName("Both present are parenthesised and joined")
    void bothPresent() {
      assertEquals("(e.a = 1) and ((e.b = 2))", NeoReadPredicates.and("e.a = 1", "(e.b = 2)"));
    }

    @Test
    @DisplayName("A blank predicate returns the existing clause unchanged")
    void blankPredicate() {
      assertEquals("e.a = 1", NeoReadPredicates.and("e.a = 1", "  "));
      assertEquals("e.a = 1", NeoReadPredicates.and("e.a = 1", null));
    }

    @Test
    @DisplayName("A blank existing clause returns the predicate alone")
    void blankExisting() {
      assertEquals("(e.b = 2)", NeoReadPredicates.and(null, "(e.b = 2)"));
      assertEquals("(e.b = 2)", NeoReadPredicates.and("", "(e.b = 2)"));
    }

    @Test
    @DisplayName("Both blank returns the existing value")
    void bothBlank() {
      assertNull(NeoReadPredicates.and(null, null));
    }
  }
}
