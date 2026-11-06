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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;

/**
 * Unit tests for {@link RemittanceMethodLinkObserver}: an active {@code FIN_FinAcc_PaymentMethod}
 * that joins a remittance payment method to an account without «Admite remesas» is refused with
 * {@link MessageKeyedException} {@code ETGO_RemittanceMethodAccountNotAllowed}; everything else
 * passes.
 *
 * <p>{@link ModelProvider} and {@link TriggerHandler} are mocked statically so
 * {@code isValidEvent} passes without a database; {@link RemittanceSepaProvisioning} is mocked to
 * answer whether the method is a remittance method; {@link OBContext} is mocked so the admin-mode
 * toggle is a recorded no-op. Modelled on {@code BankStatementLinePendingAmountHandlerTest}.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceMethodLinkObserver
 */
class RemittanceMethodLinkObserverTest {

  private RemittanceMethodLinkObserver observer;

  private MockedStatic<ModelProvider> modelStatic;
  private MockedStatic<TriggerHandler> triggerStatic;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<OBMessageUtils> messagesStatic;
  private MockedStatic<RemittanceSepaProvisioning> provisioningStatic;

  private TriggerHandler triggerHandler;
  private Entity linkEntity;
  private Property activeProp;
  private Property accountProp;
  private Property methodProp;

  @BeforeEach
  void setUp() throws Exception {
    observer = new RemittanceMethodLinkObserver();
    Field cache = RemittanceMethodLinkObserver.class.getDeclaredField("entities");
    cache.setAccessible(true);
    cache.set(null, null);

    linkEntity = mock(Entity.class);
    activeProp = mock(Property.class);
    accountProp = mock(Property.class);
    methodProp = mock(Property.class);
    when(linkEntity.getProperty(FinAccPaymentMethod.PROPERTY_ACTIVE)).thenReturn(activeProp);
    when(linkEntity.getProperty(FinAccPaymentMethod.PROPERTY_ACCOUNT)).thenReturn(accountProp);
    when(linkEntity.getProperty(FinAccPaymentMethod.PROPERTY_PAYMENTMETHOD))
        .thenReturn(methodProp);
    ModelProvider model = mock(ModelProvider.class);
    when(model.getEntity(FinAccPaymentMethod.ENTITY_NAME)).thenReturn(linkEntity);
    triggerHandler = mock(TriggerHandler.class);

    modelStatic = mockStatic(ModelProvider.class);
    modelStatic.when(ModelProvider::getInstance).thenReturn(model);
    triggerStatic = mockStatic(TriggerHandler.class);
    triggerStatic.when(TriggerHandler::getInstance).thenReturn(triggerHandler);
    contextStatic = mockStatic(OBContext.class);
    messagesStatic = mockStatic(OBMessageUtils.class);
    messagesStatic.when(() -> OBMessageUtils.messageBD(anyString()))
        .thenAnswer(inv -> inv.getArgument(0));
    provisioningStatic = mockStatic(RemittanceSepaProvisioning.class);
  }

  @AfterEach
  void tearDown() {
    provisioningStatic.close();
    messagesStatic.close();
    contextStatic.close();
    triggerStatic.close();
    modelStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  @Test
  void observesTheAccountPaymentMethodEntity() {
    assertArrayEquals(new Entity[] { linkEntity }, observer.getObservedEntities());
  }

  @Test
  void anActiveRemittanceMethodOnAnAccountWithoutTheSettingIsRefused() {
    FIN_PaymentMethod method = method("PM-REM");
    remittanceMethod(method, true);
    EntityNewEvent event = event(EntityNewEvent.class, true, account("ACC", false), method);

    MessageKeyedException refusal = assertThrows(MessageKeyedException.class,
        () -> observer.onNew(event));

    assertEquals(RemittanceMethodLinkObserver.MSG_NOT_ALLOWED, refusal.getMessageKey());
    assertEquals("ETGO_RemittanceMethodAccountNotAllowed", refusal.getMessageKey());
    contextStatic.verify(() -> OBContext.setAdminMode(true));
    contextStatic.verify(OBContext::restorePreviousMode);
  }

  @Test
  void aMethodThatIsNotARemittanceMethodPasses() {
    FIN_PaymentMethod method = method("PM-TRANSFER");
    remittanceMethod(method, false);
    EntityNewEvent event = event(EntityNewEvent.class, true, account("ACC", false), method);

    assertDoesNotThrow(() -> observer.onNew(event));

    provisioningStatic.verify(() -> RemittanceSepaProvisioning.isRemittanceMethod(method));
  }

  @Test
  void anAccountThatAllowsRemittancesPassesWithoutAskingAboutTheMethod() {
    FIN_PaymentMethod method = method("PM-REM");
    remittanceMethod(method, true);
    EntityNewEvent event = event(EntityNewEvent.class, true, account("ACC", true), method);

    assertDoesNotThrow(() -> observer.onNew(event));

    provisioningStatic.verifyNoInteractions();
  }

  @Test
  void anInactiveLinkIsAlwaysAllowed() {
    FIN_PaymentMethod method = method("PM-REM");
    remittanceMethod(method, true);
    EntityNewEvent event = event(EntityNewEvent.class, false, account("ACC", false), method);

    assertDoesNotThrow(() -> observer.onNew(event));

    provisioningStatic.verifyNoInteractions();
  }

  @Test
  void anUpdateThatChangesNoneOfTheCheckedColumnsIsNeverRefused() {
    FIN_PaymentMethod method = method("PM-REM");
    remittanceMethod(method, true);
    FIN_FinancialAccount account = account("ACC", false);
    EntityUpdateEvent event = event(EntityUpdateEvent.class, true, account, method);
    // Previous state: the same account and method (other instances, same ids), already active.
    FIN_FinancialAccount sameAccount = account("ACC", false);
    FIN_PaymentMethod sameMethod = method("PM-REM");
    when(event.getPreviousState(activeProp)).thenReturn(true);
    when(event.getPreviousState(accountProp)).thenReturn(sameAccount);
    when(event.getPreviousState(methodProp)).thenReturn(sameMethod);

    assertDoesNotThrow(() -> observer.onUpdate(event));

    provisioningStatic.verifyNoInteractions();
  }

  @Test
  void anUpdateThatReactivatesTheLinkIsChecked() {
    FIN_PaymentMethod method = method("PM-REM");
    remittanceMethod(method, true);
    FIN_FinancialAccount account = account("ACC", false);
    EntityUpdateEvent event = event(EntityUpdateEvent.class, true, account, method);
    when(event.getPreviousState(activeProp)).thenReturn(false);
    when(event.getPreviousState(accountProp)).thenReturn(account);
    when(event.getPreviousState(methodProp)).thenReturn(method);

    assertThrows(MessageKeyedException.class, () -> observer.onUpdate(event));
  }

  @Test
  void anUpdateThatSwapsInARemittanceMethodIsChecked() {
    FIN_PaymentMethod method = method("PM-REM");
    remittanceMethod(method, true);
    FIN_FinancialAccount account = account("ACC", false);
    EntityUpdateEvent event = event(EntityUpdateEvent.class, true, account, method);
    when(event.getPreviousState(activeProp)).thenReturn(true);
    when(event.getPreviousState(accountProp)).thenReturn(account);
    FIN_PaymentMethod previous = method("PM-OTHER");
    when(event.getPreviousState(methodProp)).thenReturn(previous);

    assertThrows(MessageKeyedException.class, () -> observer.onUpdate(event));
  }

  @Test
  void aLinkWithoutAccountOrMethodIsLeftToTheMandatoryChecks() {
    EntityNewEvent noMethod = event(EntityNewEvent.class, true, account("ACC", false), null);
    EntityNewEvent noAccount = event(EntityNewEvent.class, true, null, method("PM-REM"));

    assertDoesNotThrow(() -> observer.onNew(noMethod));
    assertDoesNotThrow(() -> observer.onNew(noAccount));

    provisioningStatic.verifyNoInteractions();
  }

  @Test
  void nothingIsCheckedWhileTriggersAreDisabled() {
    when(triggerHandler.isDisabled()).thenReturn(true);
    FIN_PaymentMethod method = method("PM-REM");
    remittanceMethod(method, true);
    EntityNewEvent event = event(EntityNewEvent.class, true, account("ACC", false), method);

    assertDoesNotThrow(() -> observer.onNew(event));

    provisioningStatic.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- helpers

  private void remittanceMethod(FIN_PaymentMethod method, boolean remittance) {
    provisioningStatic.when(() -> RemittanceSepaProvisioning.isRemittanceMethod(method))
        .thenReturn(remittance);
  }

  private <E extends EntityPersistenceEvent> E event(Class<E> type, boolean active,
      FIN_FinancialAccount account, FIN_PaymentMethod method) {
    FinAccPaymentMethod link = mock(FinAccPaymentMethod.class);
    when(link.getEntity()).thenReturn(linkEntity);
    E event = mock(type);
    when(event.getTargetInstance()).thenReturn(link);
    when(event.getCurrentState(activeProp)).thenReturn(active);
    when(event.getCurrentState(accountProp)).thenReturn(account);
    when(event.getCurrentState(methodProp)).thenReturn(method);
    return event;
  }

  private static FIN_FinancialAccount account(String id, boolean allowRemittance) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(id);
    when(account.isETGOAllowRemittance()).thenReturn(allowRemittance);
    return account;
  }

  private static FIN_PaymentMethod method(String id) {
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.getId()).thenReturn(id);
    return method;
  }
}
