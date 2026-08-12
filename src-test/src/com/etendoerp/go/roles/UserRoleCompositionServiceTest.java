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
package com.etendoerp.go.roles;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;

/**
 * Unit tests for {@link UserRoleCompositionService}'s input-validation guard clauses — the slice
 * that fails before any persistence side effect, so it is safely mockable without a real DB.
 * The full find-or-create/reconciliation mechanism (personal role creation, {@code
 * AD_Role_Inheritance} add/remove, propagation) is covered end-to-end against a real DB by
 * {@link UserRoleCompositionServiceIntegrationTest} — this class is deliberately NOT trying to
 * re-mock that whole call chain, which core's own {@code RoleInheritanceEventHandler} needs to
 * be real DAL events for anyway.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class UserRoleCompositionServiceTest {

  private MockedStatic<OBDal> obDalMock;
  private OBDal mockDal;
  private UserRoleCompositionService service;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    mockDal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(mockDal);
    service = new UserRoleCompositionService();
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  @Test
  void rejectsBlankUserId() {
    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles(" ", Collections.emptyList()));
    assertTrue(e.getMessage().contains("Missing user id"));
  }

  @Test
  void rejectsNullTemplateRoleIdList() {
    User user = mock(User.class);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles("user-1", null));
    assertTrue(e.getMessage().contains("Missing template role id list"));
  }

  @Test
  void rejectsUnknownUser() {
    when(mockDal.get(User.class, "missing-user")).thenReturn(null);

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles("missing-user", Collections.emptyList()));
    assertTrue(e.getMessage().contains("User not found"));
  }

  @Test
  void rejectsUnknownTemplateRoleId() {
    User user = mock(User.class);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);
    when(mockDal.get(Role.class, "missing-role")).thenReturn(null);

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles("user-1", List.of("missing-role")));
    assertTrue(e.getMessage().contains("Template role not found or inactive"));
  }

  @Test
  void rejectsInactiveTemplateRole() {
    User user = mock(User.class);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);
    Role inactive = mock(Role.class);
    when(inactive.isActive()).thenReturn(false);
    when(mockDal.get(Role.class, "inactive-role")).thenReturn(inactive);

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles("user-1", List.of("inactive-role")));
    assertTrue(e.getMessage().contains("Template role not found or inactive"));
  }

  @Test
  void rejectsRoleThatIsNotATemplate() {
    User user = mock(User.class);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);
    Role notTemplate = mock(Role.class);
    when(notTemplate.isActive()).thenReturn(true);
    when(notTemplate.isTemplate()).thenReturn(false);
    when(mockDal.get(Role.class, "plain-role")).thenReturn(notTemplate);

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles("user-1", List.of("plain-role")));
    assertTrue(e.getMessage().contains("is not a template"));
  }

  @Test
  void rejectsTheClientAdminRoleEvenIfSomehowMarkedAsTemplate() {
    User user = mock(User.class);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);
    Role adminLike = mock(Role.class);
    when(adminLike.isActive()).thenReturn(true);
    when(adminLike.isTemplate()).thenReturn(true);
    when(adminLike.isClientAdmin()).thenReturn(true);
    when(mockDal.get(Role.class, "admin-role")).thenReturn(adminLike);

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles("user-1", List.of("admin-role")));
    assertTrue(e.getMessage().contains("Admin role can never be composed"));
  }

  @Test
  void deduplicatesRequestedTemplateIdsBeforeValidating() {
    User user = mock(User.class);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);
    // The single distinct id resolves to an Admin-like role, which fails validation and throws
    // — cleanly stopping BEFORE OBContext.setAdminMode() is ever reached (this plain Mockito
    // unit test never mocks OBContext, so it must never call the real static one). Verifying
    // the lookup ran exactly once, despite the id appearing three times (with whitespace noise)
    // in the input, proves dedup happens before the per-id validation loop.
    Role adminLike = mock(Role.class);
    when(adminLike.isActive()).thenReturn(true);
    when(adminLike.isTemplate()).thenReturn(true);
    when(adminLike.isClientAdmin()).thenReturn(true);
    when(mockDal.get(Role.class, "tpl-1")).thenReturn(adminLike);

    assertThrows(OBException.class,
        () -> service.assignTemplateRoles("user-1", List.of("tpl-1", " tpl-1", "tpl-1 ")));

    org.mockito.Mockito.verify(mockDal, org.mockito.Mockito.times(1)).get(Role.class, "tpl-1");
  }
}
