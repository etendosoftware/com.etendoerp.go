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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.model.common.businesspartner.BusinessPartner;

/**
 * Unit tests for {@link ContactNameSyncHandler}.
 *
 * <p>Covers the full decision tree of {@code onUpdate}: the {@code isValidEvent} guard
 * (disabled trigger handler, wrong entity), the {@code isPerson} guard, the no-changes guard,
 * the blank-name guard, and the happy-path name derivation including whitespace trimming.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactNameSyncHandlerTest {

  private ContactNameSyncHandler handler;

  private MockedStatic<ModelProvider> mockedModelProvider;
  private MockedStatic<TriggerHandler> mockedTriggerHandler;

  private Entity bpEntity;
  private Property isPersonProp;
  private Property firstnameProp;
  private Property lastnameProp;
  private Property nameProp;

  @BeforeEach
  void setUp() throws Exception {
    handler = new ContactNameSyncHandler();

    // Reset static cache so each test gets a fresh entity initialization.
    Field entitiesField = ContactNameSyncHandler.class.getDeclaredField("entities");
    entitiesField.setAccessible(true);
    entitiesField.set(null, null);

    bpEntity = mock(Entity.class);
    isPersonProp = mock(Property.class);
    firstnameProp = mock(Property.class);
    lastnameProp = mock(Property.class);
    nameProp = mock(Property.class);

    when(bpEntity.getPropertyByColumnName("EM_ETGO_ISPERSON")).thenReturn(isPersonProp);
    when(bpEntity.getPropertyByColumnName("EM_ETGO_FIRSTNAME")).thenReturn(firstnameProp);
    when(bpEntity.getPropertyByColumnName("EM_ETGO_LASTNAME")).thenReturn(lastnameProp);
    when(bpEntity.getPropertyByColumnName("NAME")).thenReturn(nameProp);

    ModelProvider modelProviderInstance = mock(ModelProvider.class);
    when(modelProviderInstance.getEntity(BusinessPartner.ENTITY_NAME)).thenReturn(bpEntity);
    mockedModelProvider = mockStatic(ModelProvider.class);
    mockedModelProvider.when(ModelProvider::getInstance).thenReturn(modelProviderInstance);

    TriggerHandler triggerHandlerInstance = mock(TriggerHandler.class);
    when(triggerHandlerInstance.isDisabled()).thenReturn(false);
    mockedTriggerHandler = mockStatic(TriggerHandler.class);
    mockedTriggerHandler.when(TriggerHandler::getInstance).thenReturn(triggerHandlerInstance);
  }

  @AfterEach
  void tearDown() {
    mockedModelProvider.close();
    mockedTriggerHandler.close();
  }

  // ── Happy-path tests ─────────────────────────────────────────────────────────

  /**
   * When only the first name changes, Name must be rebuilt as "newFirst lastName".
   */
  @Test
  void testOnUpdateFirstNameChangedUpdatesName() {
    EntityUpdateEvent event = buildEvent(Boolean.TRUE, "Juan", "Pedro", "García", "García");
    handler.onUpdate(event);
    verify(event).setCurrentState(nameProp, "Pedro García");
  }

  /**
   * When only the last name changes, Name must be rebuilt as "firstName newLast".
   */
  @Test
  void testOnUpdateLastNameChangedUpdatesName() {
    EntityUpdateEvent event = buildEvent(Boolean.TRUE, "Juan", "Juan", "Pérez", "García");
    handler.onUpdate(event);
    verify(event).setCurrentState(nameProp, "Juan García");
  }

  /**
   * When both names change, Name must combine the new values.
   */
  @Test
  void testOnUpdateBothNamesChangedCombinesCorrectly() {
    EntityUpdateEvent event = buildEvent(Boolean.TRUE, "OldFirst", "María", "OldLast", "López");
    handler.onUpdate(event);
    verify(event).setCurrentState(nameProp, "María López");
  }

  /**
   * Names with surrounding spaces must be trimmed before combining.
   */
  @Test
  void testOnUpdateNamesWithExtraSpacesTrimmed() {
    EntityUpdateEvent event = buildEvent(Boolean.TRUE, "OldFirst", "  Ana  ", "OldLast", "  García  ");
    handler.onUpdate(event);
    verify(event).setCurrentState(nameProp, "Ana García");
  }

  /**
   * When only the first name is present (last name null), Name must equal just the first name.
   */
  @Test
  void testOnUpdateOnlyFirstNamePresentUsesFirstNameOnly() {
    EntityUpdateEvent event = buildEvent(Boolean.TRUE, "OldFirst", "Carlos", "García", null);
    handler.onUpdate(event);
    verify(event).setCurrentState(nameProp, "Carlos");
  }

  /**
   * When only the last name is present (first name null), Name must equal just the last name.
   */
  @Test
  void testOnUpdateOnlyLastNamePresentUsesLastNameOnly() {
    EntityUpdateEvent event = buildEvent(Boolean.TRUE, "Juan", null, "OldLast", "Martínez");
    handler.onUpdate(event);
    verify(event).setCurrentState(nameProp, "Martínez");
  }

  // ── isPerson guard ────────────────────────────────────────────────────────────

  /**
   * When isPerson is Boolean.FALSE, Name must not be touched.
   */
  @Test
  void testOnUpdateIsPersonFalseDoesNotUpdateName() {
    EntityUpdateEvent event = buildEvent(Boolean.FALSE, "Juan", "Pedro", "García", "García");
    handler.onUpdate(event);
    verify(event, never()).setCurrentState(any(Property.class), any());
  }

  /**
   * When isPerson is null (unset), Name must not be touched.
   */
  @Test
  void testOnUpdateIsPersonNullDoesNotUpdateName() {
    EntityUpdateEvent event = buildEvent(null, "Juan", "Pedro", "García", "García");
    handler.onUpdate(event);
    verify(event, never()).setCurrentState(any(Property.class), any());
  }

  // ── No-changes guard ─────────────────────────────────────────────────────────

  /**
   * When isPerson is true but neither name changed, no update must occur.
   */
  @Test
  void testOnUpdateNoChangesDoesNotUpdateName() {
    EntityUpdateEvent event = buildEvent(Boolean.TRUE, "Juan", "Juan", "García", "García");
    handler.onUpdate(event);
    verify(event, never()).setCurrentState(any(Property.class), any());
  }

  // ── Blank-name guard ─────────────────────────────────────────────────────────

  /**
   * When both resulting name parts are blank/null, Name must not be set to an empty string.
   */
  @Test
  void testOnUpdateBothNamesBlankDoesNotUpdateName() {
    EntityUpdateEvent event = buildEvent(Boolean.TRUE, "OldFirst", "  ", "OldLast", null);
    handler.onUpdate(event);
    verify(event, never()).setCurrentState(any(Property.class), any());
  }

  // ── isValidEvent guards ───────────────────────────────────────────────────────

  /**
   * When the TriggerHandler is disabled (import mode), the handler must not process the event.
   */
  @Test
  void testOnUpdateTriggerHandlerDisabledDoesNotProcess() {
    TriggerHandler disabledTH = mock(TriggerHandler.class);
    when(disabledTH.isDisabled()).thenReturn(true);
    mockedTriggerHandler.when(TriggerHandler::getInstance).thenReturn(disabledTH);

    EntityUpdateEvent event = buildEvent(Boolean.TRUE, "OldFirst", "NewFirst", "García", "García");
    handler.onUpdate(event);
    verify(event, never()).setCurrentState(any(Property.class), any());
  }

  /**
   * When the event targets a different entity (not C_BPartner), the handler must skip processing.
   */
  @Test
  void testOnUpdateWrongEntityDoesNotProcess() {
    Entity otherEntity = mock(Entity.class);
    BaseOBObject targetInstance = mock(BaseOBObject.class);
    when(targetInstance.getEntity()).thenReturn(otherEntity);

    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    when(event.getTargetInstance()).thenReturn(targetInstance);

    handler.onUpdate(event);
    verify(event, never()).setCurrentState(any(Property.class), any());
  }

  // ── getObservedEntities ───────────────────────────────────────────────────────

  /**
   * {@code getObservedEntities} must return a non-null single-element array containing the
   * BusinessPartner entity.
   */
  @Test
  void testGetObservedEntitiesReturnsBusinessPartnerEntity() {
    Entity[] observed = handler.getObservedEntities();
    assertNotNull(observed);
    assertEquals(1, observed.length);
    assertEquals(bpEntity, observed[0]);
  }

  /**
   * Subsequent calls to {@code getObservedEntities} must return the cached array (same reference).
   */
  @Test
  void testGetObservedEntitiesCacheIsReused() {
    Entity[] first = handler.getObservedEntities();
    Entity[] second = handler.getObservedEntities();
    assertNotNull(first);
    assertEquals(first, second);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  /**
   * Builds a fully-wired {@link EntityUpdateEvent} mock where:
   * <ul>
   *   <li>{@code getTargetInstance().getEntity()} returns the shared {@code bpEntity}</li>
   *   <li>isPerson current state = {@code isPerson}</li>
   *   <li>firstname previous / current state = {@code prevFirst} / {@code currFirst}</li>
   *   <li>lastname previous / current state = {@code prevLast} / {@code currLast}</li>
   * </ul>
   */
  private EntityUpdateEvent buildEvent(Object isPerson, String prevFirst, String currFirst, String prevLast,
      String currLast) {

    BaseOBObject targetInstance = mock(BaseOBObject.class);
    when(targetInstance.getEntity()).thenReturn(bpEntity);

    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    when(event.getTargetInstance()).thenReturn(targetInstance);

    when(event.getCurrentState(isPersonProp)).thenReturn(isPerson);
    when(event.getPreviousState(firstnameProp)).thenReturn(prevFirst);
    when(event.getCurrentState(firstnameProp)).thenReturn(currFirst);
    when(event.getPreviousState(lastnameProp)).thenReturn(prevLast);
    when(event.getCurrentState(lastnameProp)).thenReturn(currLast);

    return event;
  }
}
