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
package com.etendoerp.go.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.util.OwnerSupport;
import com.etendoerp.go.supportaccess.SupportAccessGuard;

/**
 * ETP-5351 (T6) — {@link UserRoleCompositionService} never lists, composes, creates or restores a
 * role for the tenant's "Soporte Etendo" user. No database: the DAL is mocked, and every refusal
 * happens before any write.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class UserRoleCompositionServiceSupportTest {

  private static final String CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String SUPPORT_USER = SupportAccessGuard.supportUserIdFor(CLIENT);
  private static final String REAL_USER = "B0000000000000000000000000000001";
  private static final String CALLER = "C0000000000000000000000000000001";
  private static final String SUPPORT_MARKER = "support";

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private OBDal mockDal;
  private Client client;
  private UserRoleCompositionService service;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    mockDal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(mockDal);
    client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    service = new UserRoleCompositionService(UserRoleWriteLock.NO_OP);
  }

  @AfterEach
  void tearDown() {
    obContextMock.close();
    obDalMock.close();
  }

  private User user(String id, Role defaultRole) {
    User user = mock(User.class);
    when(user.getId()).thenReturn(id);
    when(user.getClient()).thenReturn(client);
    when(user.getDefaultRole()).thenReturn(defaultRole);
    when(mockDal.get(User.class, id)).thenReturn(user);
    return user;
  }

  private Role adminRole() {
    Role role = mock(Role.class);
    when(role.isClientAdmin()).thenReturn(true);
    when(role.getClient()).thenReturn(client);
    return role;
  }

  @Test
  @SuppressWarnings("unchecked")
  void roleGridHasNoEntryForTheSupportUser() {
    User real = user(REAL_USER, null);
    User support = user(SUPPORT_USER, adminRole());
    OBCriteria<User> criteria = mock(OBCriteria.class);
    when(criteria.list()).thenReturn(Arrays.asList(real, support));
    when(mockDal.createCriteria(User.class)).thenReturn(criteria);

    Map<String, List<String>> applied = service.getAppliedTemplateRoleIdsForClient(CLIENT);

    assertEquals(Set.of(REAL_USER), applied.keySet());
  }

  @Test
  void templateRolesAreNeverComposedForTheSupportUser() {
    user(SUPPORT_USER, adminRole());

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles(SUPPORT_USER, Collections.emptyList(), null, CALLER));

    assertTrue(e.getMessage().contains(SUPPORT_MARKER));
    verify(mockDal, never()).save(any());
  }

  @Test
  void noPersonalRoleIsEverCreatedForTheSupportUser() {
    User support = user(SUPPORT_USER, adminRole());

    assertThrows(OBException.class, () -> service.ensurePersonalRole(support));
    assertThrows(OBException.class, () -> service.createFreshPersonalRole(support));
    verify(mockDal, never()).save(any());
  }

  @Test
  void theSupportUserIsNeverDemoted() {
    user(SUPPORT_USER, adminRole());
    Role callerRole = adminRole();
    try (MockedStatic<OwnerSupport> ownerSupport = mockStatic(OwnerSupport.class)) {
      ownerSupport.when(() -> OwnerSupport.isOwner(CALLER)).thenReturn(true);

      OBException e = assertThrows(OBException.class,
          () -> service.demoteFromAdmin(CALLER, callerRole, SUPPORT_USER));

      assertTrue(e.getMessage().contains(SUPPORT_MARKER));
      verify(mockDal, never()).save(any());
    }
  }
}
