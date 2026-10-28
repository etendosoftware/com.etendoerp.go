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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;

/**
 * ETP-5351 (L2): the observer must cut an operator's support accesses when they lose the right to
 * hold them, and only then. No DB: the model is mocked and the service is a mock.
 */
class SupportOperatorRevocationObserverTest {

  private static final String SUPPORT_ROLE = SupportAccessGuard.SUPPORT_OPERATOR_ROLE_ID;
  private static final String OTHER_ROLE = "OTHER-ROLE-ID";
  private static final String OPERATOR = "OPERATOR-ID";
  private static final String ACTOR = "ACTOR-ID";

  private static Entity userEntity;
  private static Entity userRolesEntity;
  private static Entity roleEntity;
  private static Property activeProperty;

  private SupportAccessService service;
  private SupportOperatorRevocationObserver observer;
  private MockedStatic<TriggerHandler> triggerStatic;
  private MockedStatic<OBContext> contextStatic;

  /** The observer caches the entities in a static final: initialise it once, with a mocked model. */
  @BeforeAll
  static void initObserverClass() {
    userEntity = mock(Entity.class);
    userRolesEntity = mock(Entity.class);
    roleEntity = mock(Entity.class);
    activeProperty = mock(Property.class);
    for (Entity entity : new Entity[] { userEntity, userRolesEntity, roleEntity }) {
      when(entity.getProperty(User.PROPERTY_ACTIVE)).thenReturn(activeProperty);
    }
    ModelProvider provider = mock(ModelProvider.class);
    when(provider.getEntity(User.ENTITY_NAME)).thenReturn(userEntity);
    when(provider.getEntity(UserRoles.ENTITY_NAME)).thenReturn(userRolesEntity);
    when(provider.getEntity(Role.ENTITY_NAME)).thenReturn(roleEntity);
    try (MockedStatic<ModelProvider> model = mockStatic(ModelProvider.class)) {
      model.when(ModelProvider::getInstance).thenReturn(provider);
      new SupportOperatorRevocationObserver();
    }
  }

  @BeforeEach
  void setUp() {
    service = mock(SupportAccessService.class);
    observer = new SupportOperatorRevocationObserver() {
      @Override
      SupportAccessService supportAccessService() {
        return service;
      }
    };
    TriggerHandler trigger = mock(TriggerHandler.class);
    when(trigger.isDisabled()).thenReturn(false);
    triggerStatic = mockStatic(TriggerHandler.class);
    triggerStatic.when(TriggerHandler::getInstance).thenReturn(trigger);
    contextStatic = mockStatic(OBContext.class);
    withContextUser(ACTOR);
  }

  @AfterEach
  void tearDown() {
    triggerStatic.close();
    contextStatic.close();
  }

  private void withContextUser(String userId) {
    OBContext context = mock(OBContext.class);
    if (userId != null) {
      User user = mock(User.class);
      when(user.getId()).thenReturn(userId);
      when(context.getUser()).thenReturn(user);
    }
    contextStatic.when(OBContext::getOBContext).thenReturn(context);
  }

  // ------------------------------------------------------------ builders

  private static User user(String id) {
    User user = mock(User.class);
    when(user.getEntity()).thenReturn(userEntity);
    when(user.getId()).thenReturn(id);
    return user;
  }

  private static UserRoles userRoles(String roleId, String userId) {
    UserRoles userRoles = mock(UserRoles.class);
    when(userRoles.getEntity()).thenReturn(userRolesEntity);
    Role role = mock(Role.class);
    when(role.getId()).thenReturn(roleId);
    when(userRoles.getRole()).thenReturn(role);
    User contact = user(userId);
    when(userRoles.getUserContact()).thenReturn(contact);
    return userRoles;
  }

  private static Role role(String id) {
    Role role = mock(Role.class);
    when(role.getEntity()).thenReturn(roleEntity);
    when(role.getId()).thenReturn(id);
    return role;
  }

  private static EntityUpdateEvent update(BaseOBObject target, Object before, Object after) {
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    when(event.getTargetInstance()).thenReturn(target);
    when(event.getPreviousState(activeProperty)).thenReturn(before);
    when(event.getCurrentState(activeProperty)).thenReturn(after);
    return event;
  }

  private static EntityDeleteEvent delete(BaseOBObject target) {
    EntityDeleteEvent event = mock(EntityDeleteEvent.class);
    when(event.getTargetInstance()).thenReturn(target);
    return event;
  }

  // ------------------------------------------------------------ AD_User

  @Test
  void userDeactivatedRevokesThatOperator() {
    observer.onUpdate(update(user(OPERATOR), true, false));

    verify(service).revokeAllOf(OPERATOR, ACTOR);
  }

  @Test
  void userStillInactiveDoesNotRevoke() {
    observer.onUpdate(update(user(OPERATOR), false, false));

    verify(service, never()).revokeAllOf(any(), any());
  }

  @Test
  void userReactivatedDoesNotRevoke() {
    observer.onUpdate(update(user(OPERATOR), false, true));

    verify(service, never()).revokeAllOf(any(), any());
  }

  @Test
  void userStillActiveDoesNotRevoke() {
    observer.onUpdate(update(user(OPERATOR), true, true));

    verify(service, never()).revokeAllOf(any(), any());
  }

  @Test
  void userDeletedRevokesThatOperator() {
    observer.onDelete(delete(user(OPERATOR)));

    verify(service).revokeAllOf(OPERATOR, ACTOR);
  }

  // ------------------------------------------------------------ AD_User_Roles

  @Test
  void supportRoleRowDeactivatedRevokesThatOperator() {
    observer.onUpdate(update(userRoles(SUPPORT_ROLE, OPERATOR), true, false));

    verify(service).revokeAllOf(OPERATOR, ACTOR);
  }

  @Test
  void supportRoleRowDeletedRevokesThatOperator() {
    observer.onDelete(delete(userRoles(SUPPORT_ROLE, OPERATOR)));

    verify(service).revokeAllOf(OPERATOR, ACTOR);
  }

  @Test
  void supportRoleRowReactivatedDoesNotRevoke() {
    observer.onUpdate(update(userRoles(SUPPORT_ROLE, OPERATOR), false, true));

    verify(service, never()).revokeAllOf(any(), any());
  }

  @Test
  void otherRoleRowDeactivatedDoesNothing() {
    observer.onUpdate(update(userRoles(OTHER_ROLE, OPERATOR), true, false));

    verify(service, never()).revokeAllOf(any(), any());
  }

  @Test
  void otherRoleRowDeletedDoesNothing() {
    observer.onDelete(delete(userRoles(OTHER_ROLE, OPERATOR)));

    verify(service, never()).revokeAllOf(any(), any());
  }

  // ------------------------------------------------------------ AD_Role

  @Test
  void supportRoleDeactivatedRevokesEveryOperator() {
    observer.onUpdate(update(role(SUPPORT_ROLE), true, false));

    verify(service).revokeAllOf(null, ACTOR);
  }

  @Test
  void otherRoleDeactivatedDoesNothing() {
    observer.onUpdate(update(role(OTHER_ROLE), true, false));

    verify(service, never()).revokeAllOf(any(), any());
  }

  @Test
  void supportRoleDeletedRevokesEveryOperator() {
    observer.onDelete(delete(role(SUPPORT_ROLE)));

    verify(service).revokeAllOf(null, ACTOR);
  }

  @Test
  void otherRoleDeletedDoesNothing() {
    observer.onDelete(delete(role(OTHER_ROLE)));

    verify(service, never()).revokeAllOf(any(), any());
  }

  // ------------------------------------------------------------ guards

  @Test
  void contextWithoutUserRevokesWithANullActor() {
    withContextUser(null);

    observer.onUpdate(update(user(OPERATOR), true, false));

    verify(service).revokeAllOf(OPERATOR, null);
  }

  @Test
  void missingContextRevokesWithANullActor() {
    contextStatic.when(OBContext::getOBContext).thenReturn(null);

    observer.onDelete(delete(user(OPERATOR)));

    verify(service).revokeAllOf(OPERATOR, null);
  }

  @Test
  void eventsAreIgnoredWhileTriggersAreDisabled() {
    TriggerHandler disabled = mock(TriggerHandler.class);
    when(disabled.isDisabled()).thenReturn(true);
    triggerStatic.when(TriggerHandler::getInstance).thenReturn(disabled);

    observer.onUpdate(update(user(OPERATOR), true, false));
    observer.onDelete(delete(user(OPERATOR)));

    verify(service, never()).revokeAllOf(any(), any());
  }

  @Test
  void eventsOnAnUnobservedEntityAreIgnored() {
    User stranger = mock(User.class);
    when(stranger.getEntity()).thenReturn(mock(Entity.class));
    when(stranger.getId()).thenReturn(OPERATOR);

    observer.onDelete(delete(stranger));

    verify(service, never()).revokeAllOf(any(), any());
  }
}
