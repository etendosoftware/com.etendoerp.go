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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.module.Module;

import com.etendoerp.go.schemaforge.data.Group;

/**
 * Classic edits of {@code ETGO_GROUP}: the key follows the API rule, never changes, and a group a
 * module ships cannot be deleted.
 *
 * @covers com.etendoerp.go.accounts.AccountGroupKeyHandler
 * @covers com.etendoerp.go.accounts.AccountGroupService
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountGroupKeyHandlerTest {

  private final AccountGroupKeyHandler handler = new AccountGroupKeyHandler();

  private MockedStatic<ModelProvider> mockedModelProvider;
  private MockedStatic<TriggerHandler> mockedTriggerHandler;
  private MockedStatic<OBMessageUtils> mockedMessageUtils;

  private Entity groupEntity;
  private Property keyProperty;

  @BeforeEach
  void setUp() throws Exception {
    Field entities = AccountGroupKeyHandler.class.getDeclaredField("entities");
    entities.setAccessible(true);
    entities.set(null, null);

    groupEntity = mock(Entity.class);
    keyProperty = mock(Property.class);
    when(groupEntity.getProperty(Group.PROPERTY_SEARCHKEY)).thenReturn(keyProperty);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(Group.ENTITY_NAME)).thenReturn(groupEntity);
    mockedModelProvider = mockStatic(ModelProvider.class);
    mockedModelProvider.when(ModelProvider::getInstance).thenReturn(modelProvider);

    TriggerHandler triggerHandler = mock(TriggerHandler.class);
    when(triggerHandler.isDisabled()).thenReturn(false);
    mockedTriggerHandler = mockStatic(TriggerHandler.class);
    mockedTriggerHandler.when(TriggerHandler::getInstance).thenReturn(triggerHandler);

    mockedMessageUtils = mockStatic(OBMessageUtils.class);
    mockedMessageUtils.when(() -> OBMessageUtils.messageBD(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @AfterEach
  void tearDown() {
    mockedModelProvider.close();
    mockedTriggerHandler.close();
    mockedMessageUtils.close();
  }

  @ParameterizedTest
  @ValueSource(strings = { "beta", "early-access", "wave_2", "9lives" })
  void aNewGroupWithAValidKeyIsAccepted(String key) {
    assertDoesNotThrow(() -> handler.onNew(newEvent(group(key, null))));
  }

  @ParameterizedTest
  @ValueSource(strings = { "Beta", "beta testers", "-beta", "_beta", "beta!", "" })
  void aNewGroupWithAnInvalidKeyIsRefused(String key) {
    OBException error = assertThrows(OBException.class,
        () -> handler.onNew(newEvent(group(key, null))));
    assertEquals(AccountGroupKeyHandler.MSG_INVALID_KEY, error.getMessage());
  }

  @Test
  void aNewGroupWithoutAKeyIsRefused() {
    assertThrows(OBException.class, () -> handler.onNew(newEvent(group(null, null))));
  }

  @Test
  void aKeyLongerThanSixtyCharactersIsRefused() {
    assertTrue(AccountGroupService.isValidKey("a".repeat(60)));
    assertFalse(AccountGroupService.isValidKey("a".repeat(61)));
  }

  @Test
  void anUpdateThatKeepsTheKeyIsAccepted() {
    assertDoesNotThrow(() -> handler.onUpdate(updateEvent("beta", "beta")));
  }

  @Test
  void anUpdateThatChangesTheKeyIsRefused() {
    OBException error = assertThrows(OBException.class,
        () -> handler.onUpdate(updateEvent("beta", "beta-2")));
    assertEquals(AccountGroupKeyHandler.MSG_KEY_IMMUTABLE, error.getMessage());
  }

  @Test
  void aGroupOfNoModuleCanBeDeleted() {
    assertDoesNotThrow(() -> handler.onDelete(deleteEvent(group("qa", null))));
  }

  @Test
  void aGroupShippedByAModuleCannotBeDeleted() {
    OBException error = assertThrows(OBException.class,
        () -> handler.onDelete(deleteEvent(group("beta", mock(Module.class)))));
    assertEquals(AccountGroupKeyHandler.MSG_SHIPPED_NOT_DELETABLE, error.getMessage());
  }

  @Test
  void nothingIsCheckedWhileTriggersAreDisabled() {
    TriggerHandler disabled = mock(TriggerHandler.class);
    when(disabled.isDisabled()).thenReturn(true);
    mockedTriggerHandler.when(TriggerHandler::getInstance).thenReturn(disabled);

    assertDoesNotThrow(() -> handler.onNew(newEvent(group("Not Valid", null))));
    assertDoesNotThrow(() -> handler.onDelete(deleteEvent(group("beta", mock(Module.class)))));
  }

  private Group group(String key, Module module) {
    Group group = mock(Group.class);
    when(group.getEntity()).thenReturn(groupEntity);
    when(group.getSearchKey()).thenReturn(key);
    when(group.getModule()).thenReturn(module);
    return group;
  }

  private static EntityNewEvent newEvent(Group group) {
    EntityNewEvent event = mock(EntityNewEvent.class);
    when(event.getTargetInstance()).thenReturn(group);
    return event;
  }

  private static EntityDeleteEvent deleteEvent(Group group) {
    EntityDeleteEvent event = mock(EntityDeleteEvent.class);
    when(event.getTargetInstance()).thenReturn(group);
    return event;
  }

  private EntityUpdateEvent updateEvent(String previousKey, String currentKey) {
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    when(event.getTargetInstance()).thenReturn(group(currentKey, null));
    when(event.getPreviousState(keyProperty)).thenReturn(previousKey);
    when(event.getCurrentState(keyProperty)).thenReturn(currentKey);
    return event;
  }
}
