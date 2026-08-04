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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link NeoActionSurface} — the ETP-4254 probe that tells a tab-less spec with
 * a real {@code /action} route ({@code not-posted-documents}) apart from one with no agentic
 * surface at all (the dashboard's widgets).
 *
 * <p>Every "no evidence" path answers {@code true} on purpose: the catalog predicates that
 * consume this are advisory, so the safe direction is to keep a spec visible rather than
 * silently hide a working window.</p>
 *
 * <p><b>No {@code mockStatic} here, deliberately.</b> These tests inject a fake
 * {@link NeoActionSurface.HandlerResolver} instead of statically mocking
 * {@code NeoServletSupport}. The module's whole suite shares one test JVM (no
 * {@code maxParallelForks}/{@code forkEvery} configured), and statically instrumenting a class
 * that sits next to the DAL bootstrap is a cross-class pollution risk — it showed up as
 * {@code ReactivatePaymentHandlerRemoveIntegrationTest} failing its {@code classSetUp} with a
 * null datasource in the full run while passing in isolation.</p>
 */
class NeoActionSurfaceTest {

  private static final String QUALIFIER = "not-posted-documents";
  private static final String WIDGET_QUALIFIER = "widgetKpisHandler";

  private static SFEntity entityWithQualifier(String qualifier) {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn(qualifier);
    return entity;
  }

  /** A handler that declares (or does not declare) that it answers ACTION requests. */
  private static NeoHandler handlerServingActions(boolean servesActions) {
    NeoHandler handler = mock(NeoHandler.class);
    when(handler.servesActions()).thenReturn(servesActions);
    return handler;
  }

  /** A resolver backed by a fixed qualifier → handler map; anything else resolves to null. */
  private static NeoActionSurface.HandlerResolver resolverOf(Map<String, NeoHandler> handlers) {
    return handlers::get;
  }

  /** The resolver every "action-serving handler" case shares. */
  private static NeoActionSurface.HandlerResolver bothHandlers() {
    return resolverOf(Map.of(
        QUALIFIER, handlerServingActions(true),
        WIDGET_QUALIFIER, handlerServingActions(false)));
  }

  @Test
  @DisplayName("a handler declaring servesActions() has an action surface")
  void handlerDeclaringActionsHasSurface() {
    assertTrue(NeoActionSurface.hasActionSurface(entityWithQualifier(QUALIFIER), bothHandlers()));
  }

  @Test
  @DisplayName("a CRUD-only handler (the widget shape) has no action surface")
  void crudOnlyHandlerHasNoSurface() {
    assertFalse(
        NeoActionSurface.hasActionSurface(entityWithQualifier(WIDGET_QUALIFIER), bothHandlers()));
  }

  /**
   * No qualifier means no handler, so no handler-served action route. This is the only negative
   * answer reached without consulting a handler.
   */
  @Test
  @DisplayName("an entity with no Java_Qualifier has no handler-served action route")
  void blankQualifierHasNoSurface() {
    assertFalse(NeoActionSurface.hasActionSurface(entityWithQualifier(null), bothHandlers()));
    assertFalse(NeoActionSurface.hasActionSurface(entityWithQualifier("   "), bothHandlers()));
  }

  @Test
  @DisplayName("an unregistered qualifier fails open")
  void unregisteredHandlerFailsOpen() {
    assertTrue(NeoActionSurface.hasActionSurface(entityWithQualifier("ghost"), bothHandlers()));
  }

  @Test
  @DisplayName("a resolver failure fails open")
  void resolverFailureFailsOpen() {
    NeoActionSurface.HandlerResolver boom = qualifier -> {
      throw new IllegalStateException("no CDI container");
    };

    assertTrue(NeoActionSurface.hasActionSurface(entityWithQualifier(QUALIFIER), boom));
  }

  @Test
  @DisplayName("a null entity fails open")
  void nullEntityFailsOpen() {
    assertTrue(NeoActionSurface.hasActionSurface((SFEntity) null, bothHandlers()));
  }

  @Test
  @DisplayName("the list overload is an ANY across entities")
  void listOverloadIsAnyMatch() {
    SFEntity widget = entityWithQualifier(WIDGET_QUALIFIER);
    SFEntity action = entityWithQualifier(QUALIFIER);
    NeoActionSurface.HandlerResolver resolver = bothHandlers();

    assertTrue(NeoActionSurface.hasActionSurface(List.of(widget, action), resolver));
    assertFalse(NeoActionSurface.hasActionSurface(List.of(widget), resolver));
  }

  /** Empty means "nothing exposes an action", which is what lets the catalog exclude a spec. */
  @Test
  @DisplayName("null and empty lists report no action surface")
  void nullAndEmptyListsReportNoSurface() {
    assertFalse(NeoActionSurface.hasActionSurface((List<SFEntity>) null, bothHandlers()));
    assertFalse(NeoActionSurface.hasActionSurface(Collections.emptyList(), bothHandlers()));
  }

  /**
   * The public overloads must delegate to the CDI resolver. Called outside a container, the
   * lookup fails or finds nothing, so both answer the fail-open {@code true} — which is enough
   * to prove the delegation happens without statically mocking anything.
   */
  @Test
  @DisplayName("the public overloads use the CDI resolver and fail open outside a container")
  void publicOverloadsDelegateToCdiAndFailOpen() {
    SFEntity entity = entityWithQualifier(QUALIFIER);

    assertTrue(NeoActionSurface.hasActionSurface(entity));
    assertTrue(NeoActionSurface.hasActionSurface(List.of(entity)));
  }
}
