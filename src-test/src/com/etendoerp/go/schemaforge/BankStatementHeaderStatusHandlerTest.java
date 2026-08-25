package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;

/**
 * Unit tests for {@link BankStatementHeaderStatusHandler} (ETP-4891 follow-up).
 *
 * <p>Verifies that {@code EM_ETGO_STATUS} is re-derived from the header's OWN in-flight
 * {@code Processed}/{@code EM_ETGO_LINE_COUNT}/{@code EM_ETGO_MATCHED_COUNT} state on both NEW and
 * UPDATE — in particular the case that motivated this handler: a statement whose {@code Processed}
 * flag flips to {@code true} outside this module's own write flows (the PSD2 sync) must stop
 * reading "DRAFT" once that happens, even though no line changed in the same event.
 *
 * <p>{@link ModelProvider} and {@link TriggerHandler} are mocked statically so
 * {@code isValidEvent} passes without a DB or an active import. Modelled on
 * {@code BankStatementLinePendingAmountHandlerTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankStatementHeaderStatusHandlerTest {

  private BankStatementHeaderStatusHandler handler;

  private MockedStatic<ModelProvider> mockedModelProvider;
  private MockedStatic<TriggerHandler> mockedTriggerHandler;

  private Entity statementEntity;
  private Property processedProp;
  private Property statusProp;
  private Property lineCountProp;
  private Property matchedCountProp;

  @BeforeEach
  void setUp() throws Exception {
    handler = new BankStatementHeaderStatusHandler();

    Field entitiesField = BankStatementHeaderStatusHandler.class.getDeclaredField("entities");
    entitiesField.setAccessible(true);
    entitiesField.set(null, null);

    statementEntity = mock(Entity.class);
    processedProp = mock(Property.class);
    statusProp = mock(Property.class);
    lineCountProp = mock(Property.class);
    matchedCountProp = mock(Property.class);
    when(statementEntity.getProperty(FIN_BankStatement.PROPERTY_PROCESSED)).thenReturn(processedProp);
    when(statementEntity.getPropertyByColumnName(BankStatementAggregates.COL_STATUS))
        .thenReturn(statusProp);
    when(statementEntity.getPropertyByColumnName(BankStatementAggregates.COL_LINE_COUNT))
        .thenReturn(lineCountProp);
    when(statementEntity.getPropertyByColumnName(BankStatementAggregates.COL_MATCHED_COUNT))
        .thenReturn(matchedCountProp);

    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(FIN_BankStatement.ENTITY_NAME)).thenReturn(statementEntity);
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

  @Test
  void getObservedEntitiesReturnsStatementEntity() {
    Entity[] observed = handler.getObservedEntities();
    assertEquals(1, observed.length);
    assertEquals(statementEntity, observed[0]);
  }

  /**
   * The exact regression this handler exists for: PSD2 sync creates 24 lines (each already
   * correctly counted by {@link BankStatementLineAggregateHandler}), THEN flips {@code Processed}
   * to {@code true} on the header alone — no line event fires for that. Before this handler
   * existed, {@code EM_ETGO_STATUS} stayed at whatever the line events last computed (DRAFT, since
   * processed was still false then) forever.
   */
  @Test
  void onUpdateProcessedWithUnmatchedLinesDerivesPending() {
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    wireEvent(event, true, 24, 0);

    handler.onUpdate(event);

    verify(event).setCurrentState(statusProp, "PENDING");
  }

  @Test
  void onUpdateProcessedWithAllLinesMatchedDerivesReconciled() {
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    wireEvent(event, true, 24, 24);

    handler.onUpdate(event);

    verify(event).setCurrentState(statusProp, "RECONCILED");
  }

  @Test
  void onUpdateProcessedWithSomeLinesMatchedDerivesPartial() {
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    wireEvent(event, true, 24, 10);

    handler.onUpdate(event);

    verify(event).setCurrentState(statusProp, "PARTIAL");
  }

  @Test
  void onUpdateNotProcessedStaysDraftRegardlessOfLines() {
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    wireEvent(event, false, 24, 24);

    handler.onUpdate(event);

    verify(event).setCurrentState(statusProp, "DRAFT");
  }

  @Test
  void onNewProcessedDerivesFromLineState() {
    EntityNewEvent event = mock(EntityNewEvent.class);
    wireEvent(event, true, 5, 5);

    handler.onNew(event);

    verify(event).setCurrentState(statusProp, "RECONCILED");
  }

  @Test
  void skipsWhenAggregateColumnsAreNotResolved() {
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    wireEvent(event, true, 24, 0);
    when(statementEntity.getPropertyByColumnName(BankStatementAggregates.COL_STATUS))
        .thenReturn(null);

    handler.onUpdate(event);

    verify(event, never()).setCurrentState(any(), any());
  }

  /**
   * Wires the event so {@code isValidEvent} passes (target statement's entity is the observed
   * one, the {@link TriggerHandler} not disabled) and the in-flight state exposes the given
   * processed flag / line count / matched count via {@code getCurrentState}.
   */
  private void wireEvent(EntityPersistenceEvent event, boolean processed, int lineCount,
      int matchedCount) {
    FIN_BankStatement statement = mock(FIN_BankStatement.class);
    when(statement.getEntity()).thenReturn(statementEntity);
    when(event.getTargetInstance()).thenReturn(statement);
    when(event.getCurrentState(processedProp)).thenReturn(processed);
    when(event.getCurrentState(lineCountProp)).thenReturn((long) lineCount);
    when(event.getCurrentState(matchedCountProp)).thenReturn((long) matchedCount);
  }
}
