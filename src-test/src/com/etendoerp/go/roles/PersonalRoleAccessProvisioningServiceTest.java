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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

/**
 * ETP-5502 — {@link PersonalRoleAccessProvisioningService}'s personal role naming. The collision
 * suffix used to be appended BEFORE truncating to {@code AD_Role.Name}'s 60 characters, so for a
 * user name of 47+ characters every suffix was cut off to the same string and {@code
 * buildPersonalRoleName} looped forever on the first collision.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class PersonalRoleAccessProvisioningServiceTest {

  private static final String PREFIX = "Personal – ";
  private static final String NAME_EQ = Role.PROPERTY_NAME + "=";

  private MockedStatic<OBDal> obDalMock;
  private OBCriteria<Role> criteria;
  private final List<String> queriedNames = new ArrayList<>();
  private final PersonalRoleAccessProvisioningService service =
      new PersonalRoleAccessProvisioningService();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    OBDal dal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Role.class)).thenReturn(criteria);
    when(criteria.add(any())).thenAnswer(inv -> {
      String criterion = inv.getArgument(0, Criterion.class).toString();
      if (criterion.startsWith(NAME_EQ)) {
        queriedNames.add(criterion.substring(NAME_EQ.length()));
      }
      return criteria;
    });
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  private static User userNamed(String name) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    User user = mock(User.class);
    when(user.getId()).thenReturn("user-1");
    when(user.getClient()).thenReturn(client);
    when(user.getName()).thenReturn(name);
    return user;
  }

  /**
   * The first {@code takenCount} names probed are taken, every later one is free. Stubbed, not
   * timed: {@code mockStatic} is thread-local, so a preemptive timeout would run the builder on a
   * thread that sees the real {@link OBDal}.
   */
  private void takeFirstNames(int takenCount) {
    int[] calls = { 0 };
    when(criteria.uniqueResult()).thenAnswer(inv -> calls[0]++ < takenCount ? mock(Role.class) : null);
  }

  @Test
  void keepsTodaysNameWhenThereIsNoCollision() {
    takeFirstNames(0);
    assertEquals(PREFIX + "Jane Doe", service.buildPersonalRoleName(userNamed("Jane Doe")));
  }

  @Test
  void keepsTodaysTruncatedNameWhenThereIsNoCollision() {
    takeFirstNames(0);
    String longName = StringUtils.repeat('x', 80);
    String expected = (PREFIX + longName).substring(0, 60);
    assertEquals(expected, service.buildPersonalRoleName(userNamed(longName)));
  }

  @ParameterizedTest
  @ValueSource(ints = { 46, 47, 50, 80 })
  void suffixAlwaysFitsWhenTheBaseNameIsTaken(int nameLength) {
    takeFirstNames(1);
    String name = service.buildPersonalRoleName(userNamed(StringUtils.repeat('x', nameLength)));
    assertTrue(name.length() <= 60, name);
    assertTrue(name.endsWith(" (2)"), name);
    assertTrue(name.startsWith(PREFIX), name);
  }

  @Test
  void manyCollisionsOnALongNameStayDistinctAndWithinTheColumnLength() {
    takeFirstNames(10);
    String name = service.buildPersonalRoleName(userNamed(StringUtils.repeat('x', 80)));
    assertTrue(name.endsWith(" (11)"), name);
    assertEquals(11, queriedNames.size(), queriedNames.toString());
    assertEquals(11, new HashSet<>(queriedNames).size(), "every probed name must be distinct");
    for (String probed : queriedNames) {
      assertTrue(probed.length() <= 60, probed);
    }
  }

  @Test
  void throwsInsteadOfLoopingWhenEveryNameIsTaken() {
    // Every name is taken. A builder with no attempt cap would spin forever, so the stub breaks
    // out itself (with a non-OBException) well past the cap, turning a hang into a failure.
    int[] calls = { 0 };
    when(criteria.uniqueResult()).thenAnswer(inv -> {
      if (++calls[0] > PersonalRoleAccessProvisioningService.MAX_NAME_ATTEMPTS + 10) {
        throw new IllegalStateException("name builder never gave up");
      }
      return mock(Role.class);
    });
    OBException e = assertThrows(OBException.class,
        () -> service.buildPersonalRoleName(userNamed("Jane Doe")));
    assertTrue(e.getMessage().contains("user-1"), e.getMessage());
    assertEquals(PersonalRoleAccessProvisioningService.MAX_NAME_ATTEMPTS, queriedNames.size());
  }

  @Test
  void personalRoleNameMatchesWhatTheBuilderProduces() {
    String base = StringUtils.repeat('y', 70);
    for (int n = 1; n <= 12; n++) {
      String built = PersonalRoleAccessProvisioningService.personalRoleName(base, n);
      assertTrue(PersonalRoleAccessProvisioningService.isPersonalRoleNameFor(base, built), built);
    }
    assertTrue(PersonalRoleAccessProvisioningService.isPersonalRoleNameFor("Juan Pérez",
        PREFIX + "Juan Pérez (2)"));
  }

  @Test
  void personalRoleNameDoesNotMatchAnotherBase() {
    assertFalse(PersonalRoleAccessProvisioningService.isPersonalRoleNameFor("Juan Pérez",
        PREFIX + "Juan Pérez Gómez"));
    assertFalse(PersonalRoleAccessProvisioningService.isPersonalRoleNameFor("Juan Pérez",
        PREFIX + "Juan Pérez (1)"));
    assertFalse(PersonalRoleAccessProvisioningService.isPersonalRoleNameFor("Juan Pérez",
        PREFIX + "Juan Pérez (x)"));
    assertFalse(PersonalRoleAccessProvisioningService.isPersonalRoleNameFor("Juan Pérez",
        "Juan Pérez (2)"));
  }
}
