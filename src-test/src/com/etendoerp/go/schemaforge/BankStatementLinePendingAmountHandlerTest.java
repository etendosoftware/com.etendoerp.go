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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;

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
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;

/**
 * Unit tests for {@link BankStatementLinePendingAmountHandler} (ETP-4502 iteration 5).
 *
 * <p>Verifies the {@code EM_ETGO_Pending_Amount} maintenance rule on both NEW and UPDATE events:
 * an unmatched (sub-)line (no {@code financialAccountTransaction}) gets {@code |cramount −
 * dramount|}; a matched one gets {@code 0}. The value is written onto the in-flight event state via
 * {@code setCurrentState} — the same row, no extra flush.
 *
 * <p>{@link ModelProvider} and {@link TriggerHandler} are mocked statically so
 * {@code isValidEvent} passes without a DB or an active import; {@link ReactivationSupport} is
 * mocked to resolve the runtime EM property. Modelled on {@code BankStatementLineAggregateHandlerTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankStatementLinePendingAmountHandlerTest {

  private BankStatementLinePendingAmountHandler handler;

  private MockedStatic<ModelProvider> mockedModelProvider;
  private MockedStatic<TriggerHandler> mockedTriggerHandler;

  private Entity lineEntity;
  private Property cramountProp;
  private Property dramountProp;
  private Property txnProp;
  private Property pendingProp;

  @BeforeEach
  void setUp() throws Exception {
    handler = new BankStatementLinePendingAmountHandler();

    // Reset the static observed-entities cache so each test re-resolves it.
    Field entitiesField = BankStatementLinePendingAmountHandler.class.getDeclaredField("entities");
    entitiesField.setAccessible(true);
    entitiesField.set(null, null);

    lineEntity = mock(Entity.class);
    cramountProp = mock(Property.class);
    dramountProp = mock(Property.class);
    txnProp = mock(Property.class);
    pendingProp = mock(Property.class);
    when(lineEntity.getProperty(FIN_BankStatementLine.PROPERTY_CRAMOUNT)).thenReturn(cramountProp);
    when(lineEntity.getProperty(FIN_BankStatementLine.PROPERTY_DRAMOUNT)).thenReturn(dramountProp);
    when(lineEntity.getProperty(FIN_BankStatementLine.PROPERTY_FINANCIALACCOUNTTRANSACTION))
        .thenReturn(txnProp);

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
    mockedModelProvider.close();
    mockedTriggerHandler.close();
    Mockito.framework().clearInlineMocks();
  }

  // ── observed entities ────────────────────────────────────────────────────────

  @Test
  void getObservedEntitiesReturnsLineEntity() {
    Entity[] observed = handler.getObservedEntities();
    assertEquals(1, observed.length);
    assertEquals(lineEntity, observed[0]);
  }

  // ── onNew ────────────────────────────────────────────────────────────────────

  @Test
  void onNewUnmatchedLineSetsAbsoluteAmount() {
    EntityNewEvent event = mock(EntityNewEvent.class);
    // Credit 100, no debit, no linked transaction → pending = |100 − 0| = 100.
    wireEvent(event, new BigDecimal("100.00"), BigDecimal.ZERO, null);

    try (MockedStatic<ReactivationSupport> rs = mockPendingProperty()) {
      handler.onNew(event);
    }

    verify(event).setCurrentState(eq(pendingProp),
        argThat(v -> ((BigDecimal) v).compareTo(new BigDecimal("100.00")) == 0));
  }

  @Test
  void onNewMatchedLineSetsZero() {
    EntityNewEvent event = mock(EntityNewEvent.class);
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    // A linked transaction → the (sub-)line is reconciled → pending = 0.
    wireEvent(event, new BigDecimal("53.24"), BigDecimal.ZERO, trx);

    try (MockedStatic<ReactivationSupport> rs = mockPendingProperty()) {
      handler.onNew(event);
    }

    verify(event).setCurrentState(eq(pendingProp), argThat(v -> ((BigDecimal) v).signum() == 0));
  }

  // ── onUpdate ─────────────────────────────────────────────────────────────────

  @Test
  void onUpdateUnmatchedLineSetsAbsoluteAmount() {
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    // A pure debit (outflow) line, no transaction → pending = |0 − 46.76| = 46.76.
    wireEvent(event, BigDecimal.ZERO, new BigDecimal("46.76"), null);

    try (MockedStatic<ReactivationSupport> rs = mockPendingProperty()) {
      handler.onUpdate(event);
    }

    verify(event).setCurrentState(eq(pendingProp),
        argThat(v -> ((BigDecimal) v).compareTo(new BigDecimal("46.76")) == 0));
  }

  @Test
  void onUpdateMatchedLineSetsZero() {
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    wireEvent(event, new BigDecimal("46.76"), BigDecimal.ZERO, trx);

    try (MockedStatic<ReactivationSupport> rs = mockPendingProperty()) {
      handler.onUpdate(event);
    }

    verify(event).setCurrentState(eq(pendingProp), argThat(v -> ((BigDecimal) v).signum() == 0));
  }

  // ── guard: property not yet in the model ──────────────────────────────────────

  @Test
  void skipsWhenPendingPropertyIsNotResolved() {
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    wireEvent(event, new BigDecimal("100.00"), BigDecimal.ZERO, null);

    try (MockedStatic<ReactivationSupport> rs = mockStatic(ReactivationSupport.class)) {
      rs.when(() -> ReactivationSupport.extensionProperty(
              eq(FIN_BankStatementLine.ENTITY_NAME), eq(ReactivationSupport.COL_PENDING_AMOUNT)))
          .thenReturn(null);
      handler.onUpdate(event);
    }

    // No property to write to → the in-flight state is left untouched.
    verify(event, never()).setCurrentState(any(), any());
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /** Mocks {@link ReactivationSupport#extensionProperty} to resolve the pending-amount column. */
  private MockedStatic<ReactivationSupport> mockPendingProperty() {
    MockedStatic<ReactivationSupport> rs = mockStatic(ReactivationSupport.class);
    rs.when(() -> ReactivationSupport.extensionProperty(
            eq(FIN_BankStatementLine.ENTITY_NAME), eq(ReactivationSupport.COL_PENDING_AMOUNT)))
        .thenReturn(pendingProp);
    return rs;
  }

  /**
   * Wires the event so {@code isValidEvent} passes (target line's entity is the observed one, the
   * {@link TriggerHandler} not disabled) and the in-flight state exposes the given
   * credit/debit/transaction via {@code getCurrentState}.
   */
  private void wireEvent(EntityPersistenceEvent event, BigDecimal credit, BigDecimal debit,
      Object transaction) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getEntity()).thenReturn(lineEntity);
    when(event.getTargetInstance()).thenReturn(line);
    when(event.getCurrentState(cramountProp)).thenReturn(credit);
    when(event.getCurrentState(dramountProp)).thenReturn(debit);
    when(event.getCurrentState(txnProp)).thenReturn(transaction);
  }
}
