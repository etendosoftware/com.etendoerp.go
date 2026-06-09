/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

/**
 * Unit tests for {@link BankStatementLineAggregateHandler}.
 *
 * <p>Covers the observed-entity wiring, the per-thread suppression flag, and the
 * dispatch of each persistence event to
 * {@link BankStatementAggregates#recomputeWithDelta} — including the suppression
 * and null-parent guards. {@link BankStatementAggregates} is mocked statically so
 * the dispatch is verified without touching a DB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankStatementLineAggregateHandlerTest {

  private BankStatementLineAggregateHandler handler;

  private MockedStatic<ModelProvider> mockedModelProvider;
  private MockedStatic<TriggerHandler> mockedTriggerHandler;

  private Entity lineEntity;

  @BeforeEach
  void setUp() throws Exception {
    handler = new BankStatementLineAggregateHandler();
    BankStatementLineAggregateHandler.resume();

    // Reset the static observed-entities cache so each test re-resolves it.
    Field entitiesField = BankStatementLineAggregateHandler.class.getDeclaredField("entities");
    entitiesField.setAccessible(true);
    entitiesField.set(null, null);

    lineEntity = mock(Entity.class);

    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(FIN_BankStatementLine.ENTITY_NAME)).thenReturn(lineEntity);
    mockedModelProvider = mockStatic(ModelProvider.class);
    mockedModelProvider.when(ModelProvider::getInstance).thenReturn(modelProvider);

    TriggerHandler triggerHandler = mock(TriggerHandler.class);
    when(triggerHandler.isDisabled()).thenReturn(false);
    mockedTriggerHandler = mockStatic(TriggerHandler.class);
    mockedTriggerHandler.when(TriggerHandler::getInstance).thenReturn(triggerHandler);
  }

  @AfterEach
  void tearDown() {
    BankStatementLineAggregateHandler.resume();
    mockedModelProvider.close();
    mockedTriggerHandler.close();
    Mockito.framework().clearInlineMocks();
  }

  // ── suppression flag ─────────────────────────────────────────────────────────

  @Test
  void suppressAndResumeToggleTheFlag() {
    assertFalse(BankStatementLineAggregateHandler.isSuppressed());
    BankStatementLineAggregateHandler.suppress();
    assertTrue(BankStatementLineAggregateHandler.isSuppressed());
    BankStatementLineAggregateHandler.resume();
    assertFalse(BankStatementLineAggregateHandler.isSuppressed());
  }

  // ── observed entities ────────────────────────────────────────────────────────

  @Test
  void getObservedEntitiesReturnsLineEntity() {
    Entity[] observed = handler.getObservedEntities();
    assertNotNull(observed);
    assertEquals(1, observed.length);
    assertEquals(lineEntity, observed[0]);
  }

  // ── dispatch ─────────────────────────────────────────────────────────────────

  @Test
  void onNewRecomputesWithNewOp() {
    FIN_BankStatement parent = mock(FIN_BankStatement.class);
    FIN_BankStatementLine line = lineWithParent(parent);
    EntityNewEvent event = mock(EntityNewEvent.class);
    wireEvent(event, line);

    try (MockedStatic<BankStatementAggregates> agg = mockStatic(BankStatementAggregates.class)) {
      handler.onNew(event);
      agg.verify(() -> BankStatementAggregates.recomputeWithDelta(
          eq(parent), eq(line), eq(BankStatementAggregates.Op.NEW), eq(event)));
    }
  }

  @Test
  void onUpdateRecomputesWithUpdateOp() {
    FIN_BankStatement parent = mock(FIN_BankStatement.class);
    FIN_BankStatementLine line = lineWithParent(parent);
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    wireEvent(event, line);

    try (MockedStatic<BankStatementAggregates> agg = mockStatic(BankStatementAggregates.class)) {
      handler.onUpdate(event);
      agg.verify(() -> BankStatementAggregates.recomputeWithDelta(
          eq(parent), eq(line), eq(BankStatementAggregates.Op.UPDATE), eq(event)));
    }
  }

  @Test
  void onDeleteRecomputesWithDeleteOp() {
    FIN_BankStatement parent = mock(FIN_BankStatement.class);
    FIN_BankStatementLine line = lineWithParent(parent);
    EntityDeleteEvent event = mock(EntityDeleteEvent.class);
    wireEvent(event, line);

    try (MockedStatic<BankStatementAggregates> agg = mockStatic(BankStatementAggregates.class)) {
      handler.onDelete(event);
      agg.verify(() -> BankStatementAggregates.recomputeWithDelta(
          eq(parent), eq(line), eq(BankStatementAggregates.Op.DELETE), eq(event)));
    }
  }

  @Test
  void suppressedObserverDoesNotRecompute() {
    FIN_BankStatement parent = mock(FIN_BankStatement.class);
    FIN_BankStatementLine line = lineWithParent(parent);
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    wireEvent(event, line);

    BankStatementLineAggregateHandler.suppress();
    try (MockedStatic<BankStatementAggregates> agg = mockStatic(BankStatementAggregates.class)) {
      handler.onUpdate(event);
      agg.verify(() -> BankStatementAggregates.recomputeWithDelta(any(), any(), any(), any()), never());
    }
  }

  @Test
  void lineWithoutParentDoesNotRecompute() {
    FIN_BankStatementLine line = lineWithParent(null);
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    wireEvent(event, line);

    try (MockedStatic<BankStatementAggregates> agg = mockStatic(BankStatementAggregates.class)) {
      handler.onUpdate(event);
      agg.verify(() -> BankStatementAggregates.recomputeWithDelta(any(), any(), any(), any()), never());
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private FIN_BankStatementLine lineWithParent(FIN_BankStatement parent) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getEntity()).thenReturn(lineEntity);
    when(line.getBankStatement()).thenReturn(parent);
    return line;
  }

  /**
   * Wires the event so its target instance is the line and its entity is the
   * observed one — which makes {@code isValidEvent} pass given the mocked,
   * not-disabled {@link TriggerHandler}.
   */
  private void wireEvent(Object event, FIN_BankStatementLine line) {
    BaseOBObject target = line;
    if (event instanceof EntityNewEvent) {
      when(((EntityNewEvent) event).getTargetInstance()).thenReturn(target);
    } else if (event instanceof EntityUpdateEvent) {
      when(((EntityUpdateEvent) event).getTargetInstance()).thenReturn(target);
    } else if (event instanceof EntityDeleteEvent) {
      when(((EntityDeleteEvent) event).getTargetInstance()).thenReturn(target);
    }
  }
}
