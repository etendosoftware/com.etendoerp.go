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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

import com.etendoerp.psd2.bank.integration.data.PisPayment;

/**
 * Unit tests for {@link PisRejectedPaymentHandler} (ETP-4895).
 *
 * <p>The observer is what makes a rejection arriving after the payment modal closed visible at all:
 * every writer that can record one — the PSD2 scheduled refresh, its manual button, the Salt Edge
 * webhook — lives in PSD2 and knows nothing about Etendo Go's payment. Reacting to the row PSD2
 * saves inverts that dependency.
 *
 * <p>{@link ModelProvider} and {@link TriggerHandler} are mocked statically so {@code isValidEvent}
 * passes without a DB, following {@code BankStatementLinePendingAmountHandlerTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PisRejectedPaymentHandlerTest {

  private PisRejectedPaymentHandler handler;
  private MockedStatic<ModelProvider> mockedModelProvider;
  private MockedStatic<TriggerHandler> mockedTriggerHandler;

  private Entity pisEntity;
  private Property statusProp;
  private Property paymentProp;

  @BeforeEach
  void setUp() throws Exception {
    handler = new PisRejectedPaymentHandler();

    Field entitiesField = PisRejectedPaymentHandler.class.getDeclaredField("entities");
    entitiesField.setAccessible(true);
    entitiesField.set(null, null);

    pisEntity = mock(Entity.class);
    statusProp = mock(Property.class);
    paymentProp = mock(Property.class);
    when(pisEntity.getProperty(PisPayment.PROPERTY_STATUS)).thenReturn(statusProp);
    when(pisEntity.getProperty(PisPayment.PROPERTY_PAYMENT)).thenReturn(paymentProp);

    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(PisPayment.ENTITY_NAME)).thenReturn(pisEntity);
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

  private EntityUpdateEvent event(String status, FIN_Payment payment) {
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    PisPayment row = mock(PisPayment.class);
    when(row.getEntity()).thenReturn(pisEntity);
    when(event.getTargetInstance()).thenReturn(row);
    when(event.getCurrentState(statusProp)).thenReturn(status);
    when(event.getCurrentState(paymentProp)).thenReturn(payment);
    return event;
  }

  @Test
  @DisplayName("observes PSD2's bank-transfer row, which is the one every writer saves")
  void observesThePisPaymentEntity() {
    Entity[] observed = handler.getObservedEntities();
    assertEquals(1, observed.length);
    assertEquals(pisEntity, observed[0]);
  }

  @Test
  @DisplayName("flags the payment the moment a transfer is recorded as rejected")
  void flagsThePaymentOnRejection() {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getStatus()).thenReturn("PPM");

    handler.onUpdate(event("failed", payment));

    verify(payment).setStatus("ETGOERR");
  }

  @Test
  @DisplayName("leaves the payment alone while the transfer is still in flight")
  void ignoresNonRejectedStatuses() {
    FIN_Payment payment = mock(FIN_Payment.class);

    handler.onUpdate(event("authorized", payment));
    handler.onUpdate(event("executed", payment));

    verify(payment, never()).setStatus(anyString());
  }

  @Test
  @DisplayName("a rejection before the bank committed has no payment to flag")
  void ignoresRejectionsWithNoPayment() {
    // Nothing is created until 'authorized', so this is the ordinary early rejection — reported in
    // the payment modal instead. Reaching here with no payment must not blow up the PSD2 write.
    handler.onUpdate(event("failed", null));
  }

  @Test
  @DisplayName("does not rewrite a payment another writer already flagged")
  void isIdempotent() {
    // The observer, the SPA poll and reconcileAttemptsFor can all reach the same row.
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getStatus()).thenReturn("ETGOERR");

    handler.onUpdate(event("failed", payment));

    verify(payment, never()).setStatus(anyString());
  }

  @Test
  @DisplayName("never breaks the PSD2 write that triggered it")
  void swallowsFailures() {
    // PSD2 must still record the Salt Edge status even if the Etendo Go side cannot be flagged;
    // reconcileAttemptsFor picks it up on the next read.
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getStatus()).thenThrow(new IllegalStateException("detached"));

    handler.onUpdate(event("failed", payment));
  }
}
