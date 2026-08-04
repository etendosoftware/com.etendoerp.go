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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link NeoActionSurface} — the ETP-4254 probe that tells a tab-less spec with
 * a real {@code /action} route ({@code not-posted-documents}) apart from one with no agentic
 * surface at all (the dashboard's widgets).
 *
 * <p>Every "no evidence" path answers {@code true} on purpose: the catalog predicates that
 * consume this are advisory, so the safe direction is to keep a spec visible rather than
 * silently hide a working window.</p>
 */
class NeoActionSurfaceTest {

  private static final String QUALIFIER = "not-posted-documents";

  private static SFEntity entityWithQualifier(String qualifier) {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn(qualifier);
    return entity;
  }

  /**
   * A handler that declares (or does not declare) an action surface, as
   * {@code NotPostedDocumentsHandler} and the widget handlers respectively do.
   *
   * <p>Always call this BEFORE opening a {@code mockStatic} scope: creating and stubbing a mock
   * inside an unfinished {@code when(...).thenReturn(...)} chain throws
   * {@code UnfinishedStubbingException}.</p>
   */
  private static NeoHandler handlerServingActions(boolean servesActions) {
    NeoHandler handler = mock(NeoHandler.class);
    when(handler.servesActions()).thenReturn(servesActions);
    return handler;
  }

  @Test
  @DisplayName("a handler declaring servesActions() has an action surface")
  void handlerDeclaringActionsHasSurface() {
    NeoHandler handler = handlerServingActions(true);

    try (MockedStatic<NeoServletSupport> supportMock = mockStatic(NeoServletSupport.class)) {
      supportMock.when(() -> NeoServletSupport.lookupHandler(QUALIFIER)).thenReturn(handler);

      assertTrue(NeoActionSurface.hasActionSurface(entityWithQualifier(QUALIFIER)));
    }
  }

  @Test
  @DisplayName("a CRUD-only handler (the widget shape) has no action surface")
  void crudOnlyHandlerHasNoSurface() {
    NeoHandler handler = handlerServingActions(false);

    try (MockedStatic<NeoServletSupport> supportMock = mockStatic(NeoServletSupport.class)) {
      supportMock.when(() -> NeoServletSupport.lookupHandler("widgetKpisHandler"))
          .thenReturn(handler);

      assertFalse(NeoActionSurface.hasActionSurface(entityWithQualifier("widgetKpisHandler")));
    }
  }

  /**
   * No qualifier means no handler, so no handler-served action route. This is the only
   * negative answer reached without consulting a handler.
   */
  @Test
  @DisplayName("an entity with no Java_Qualifier has no handler-served action route")
  void blankQualifierHasNoSurface() {
    assertFalse(NeoActionSurface.hasActionSurface(entityWithQualifier(null)));
    assertFalse(NeoActionSurface.hasActionSurface(entityWithQualifier("   ")));
  }

  @Test
  @DisplayName("an unregistered qualifier fails open")
  void unregisteredHandlerFailsOpen() {
    try (MockedStatic<NeoServletSupport> supportMock = mockStatic(NeoServletSupport.class)) {
      supportMock.when(() -> NeoServletSupport.lookupHandler("ghost")).thenReturn(null);

      assertTrue(NeoActionSurface.hasActionSurface(entityWithQualifier("ghost")));
    }
  }

  @Test
  @DisplayName("a CDI failure fails open")
  void cdiFailureFailsOpen() {
    try (MockedStatic<NeoServletSupport> supportMock = mockStatic(NeoServletSupport.class)) {
      supportMock.when(() -> NeoServletSupport.lookupHandler(QUALIFIER))
          .thenThrow(new IllegalStateException("no CDI container"));

      assertTrue(NeoActionSurface.hasActionSurface(entityWithQualifier(QUALIFIER)));
    }
  }

  @Test
  @DisplayName("a null entity fails open")
  void nullEntityFailsOpen() {
    assertTrue(NeoActionSurface.hasActionSurface((SFEntity) null));
  }

  @Test
  @DisplayName("the list overload is an ANY across entities")
  void listOverloadIsAnyMatch() {
    SFEntity widget = entityWithQualifier("widgetKpisHandler");
    SFEntity action = entityWithQualifier(QUALIFIER);
    NeoHandler widgetHandler = handlerServingActions(false);
    NeoHandler actionHandler = handlerServingActions(true);

    try (MockedStatic<NeoServletSupport> supportMock = mockStatic(NeoServletSupport.class)) {
      supportMock.when(() -> NeoServletSupport.lookupHandler("widgetKpisHandler"))
          .thenReturn(widgetHandler);
      supportMock.when(() -> NeoServletSupport.lookupHandler(QUALIFIER))
          .thenReturn(actionHandler);

      assertTrue(NeoActionSurface.hasActionSurface(List.of(widget, action)));
      assertFalse(NeoActionSurface.hasActionSurface(List.of(widget)));
    }
  }

  /** Empty means "nothing exposes an action", which is what lets the catalog exclude a spec. */
  @Test
  @DisplayName("null and empty lists report no action surface")
  void nullAndEmptyListsReportNoSurface() {
    assertFalse(NeoActionSurface.hasActionSurface((List<SFEntity>) null));
    assertFalse(NeoActionSurface.hasActionSurface(Collections.emptyList()));
  }
}
