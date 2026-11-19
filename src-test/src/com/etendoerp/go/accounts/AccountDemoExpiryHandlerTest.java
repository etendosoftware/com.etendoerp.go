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
package com.etendoerp.go.accounts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * Classic edits of {@code ETGO_ACCOUNT.DEMO_EXPIRES_AT}: setting a date on an account without a
 * running demo is refused, as the signup API refuses it; clearing it, or saving it unchanged, is
 * not checked.
 *
 * @covers com.etendoerp.go.accounts.AccountDemoExpiryHandler
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountDemoExpiryHandlerTest {

  private static final Date DECEMBER_FIRST = new Date(1_796_083_200_000L);

  private final DemoExpiryService demoExpiry = mock(DemoExpiryService.class);
  private final AccountDemoExpiryHandler handler = new AccountDemoExpiryHandler(demoExpiry);

  private MockedStatic<ModelProvider> mockedModelProvider;
  private MockedStatic<TriggerHandler> mockedTriggerHandler;
  private MockedStatic<OBMessageUtils> mockedMessageUtils;

  private Entity accountEntity;
  private Property expiryProperty;

  @BeforeEach
  void setUp() throws Exception {
    Field entities = AccountDemoExpiryHandler.class.getDeclaredField("entities");
    entities.setAccessible(true);
    entities.set(null, null);

    accountEntity = mock(Entity.class);
    expiryProperty = mock(Property.class);
    when(accountEntity.getProperty(Account.PROPERTY_DEMOEXPIRESAT)).thenReturn(expiryProperty);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(Account.ENTITY_NAME)).thenReturn(accountEntity);
    mockedModelProvider = mockStatic(ModelProvider.class);
    mockedModelProvider.when(ModelProvider::getInstance).thenReturn(modelProvider);

    TriggerHandler triggerHandler = mock(TriggerHandler.class);
    when(triggerHandler.isDisabled()).thenReturn(false);
    mockedTriggerHandler = mockStatic(TriggerHandler.class);
    mockedTriggerHandler.when(TriggerHandler::getInstance).thenReturn(triggerHandler);

    mockedMessageUtils = mockStatic(OBMessageUtils.class);
    mockedMessageUtils.when(() -> OBMessageUtils.messageBD(AccountDemoExpiryHandler.MSG_NO_DEMO))
        .thenReturn(AccountDemoExpiryHandler.MSG_NO_DEMO);
  }

  @AfterEach
  void tearDown() {
    mockedModelProvider.close();
    mockedTriggerHandler.close();
    mockedMessageUtils.close();
  }

  @Test
  void aDateOnAnAccountWithADemoIsAccepted() {
    when(demoExpiry.ownsDemo(any())).thenReturn(true);

    assertDoesNotThrow(() -> handler.onUpdate(updateEvent(null, DECEMBER_FIRST)));
  }

  @Test
  void aDateOnAnAccountWithoutADemoIsRefused() {
    when(demoExpiry.ownsDemo(any())).thenReturn(false);

    OBException error = assertThrows(OBException.class,
        () -> handler.onUpdate(updateEvent(null, DECEMBER_FIRST)));
    assertEquals(AccountDemoExpiryHandler.MSG_NO_DEMO, error.getMessage());
  }

  @Test
  void clearingTheDateIsNotChecked() {
    handler.onUpdate(updateEvent(DECEMBER_FIRST, null));

    verify(demoExpiry, never()).ownsDemo(any());
  }

  @Test
  void anUnchangedDateIsNotCheckedEvenAsATimestamp() {
    handler.onUpdate(updateEvent(new Timestamp(DECEMBER_FIRST.getTime()), DECEMBER_FIRST));

    verify(demoExpiry, never()).ownsDemo(any());
  }

  @Test
  void nothingIsCheckedWhileTriggersAreDisabled() {
    TriggerHandler disabled = mock(TriggerHandler.class);
    when(disabled.isDisabled()).thenReturn(true);
    mockedTriggerHandler.when(TriggerHandler::getInstance).thenReturn(disabled);

    handler.onUpdate(updateEvent(null, DECEMBER_FIRST));

    verify(demoExpiry, never()).ownsDemo(any());
  }

  @Test
  void datesAreComparedByInstant() {
    Date day = new Date(DECEMBER_FIRST.getTime());
    assertTrue(AccountDemoExpiryHandler.sameInstant(new Timestamp(day.getTime()), day));
    assertFalse(AccountDemoExpiryHandler.sameInstant(day, new Date(day.getTime() + 1)));
    assertTrue(AccountDemoExpiryHandler.sameInstant(null, null));
    assertFalse(AccountDemoExpiryHandler.sameInstant(null, day));
  }

  private EntityUpdateEvent updateEvent(Date previous, Date current) {
    Account account = mock(Account.class);
    when(account.getEntity()).thenReturn(accountEntity);
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    when(event.getTargetInstance()).thenReturn(account);
    when(event.getPreviousState(expiryProperty)).thenReturn(previous);
    when(event.getCurrentState(expiryProperty)).thenReturn(current);
    return event;
  }
}
