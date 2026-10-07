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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;

/**
 * ETP-5351 (T6) — unit tests for {@link SupportUserExclusion}: pure logic, no database.
 */
class SupportUserExclusionTest {

  private static final String CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String OTHER_CLIENT = "23C59575B9CF467C9620760EB255B389";
  private static final String SUPPORT_USER = SupportAccessGuard.supportUserIdFor(CLIENT);
  private static final String REAL_ADMIN = "A0000000000000000000000000000001";
  private static final String EXPECTED_PREDICATE = "e.id <> '" + SUPPORT_USER + "'";

  private static OBContext contextOf(String clientId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    OBContext context = mock(OBContext.class);
    when(context.getCurrentClient()).thenReturn(client);
    return context;
  }

  @Test
  void removeFromDropsOnlyTheSupportUserOfThatClient() {
    Set<String> admins = new LinkedHashSet<>(List.of(REAL_ADMIN, SUPPORT_USER));

    assertTrue(SupportUserExclusion.removeFrom(admins, CLIENT));
    assertEquals(Set.of(REAL_ADMIN), admins);
  }

  @Test
  void removeFromIgnoresAnotherClientsSupportUser() {
    List<String> users = new ArrayList<>(List.of(REAL_ADMIN, SUPPORT_USER));

    assertFalse(SupportUserExclusion.removeFrom(users, OTHER_CLIENT));
    assertEquals(2, users.size());
  }

  @Test
  void removeFromIsANoOpWithoutClientOrCollection() {
    List<String> users = new ArrayList<>(List.of(SUPPORT_USER));

    assertFalse(SupportUserExclusion.removeFrom(users, null));
    assertFalse(SupportUserExclusion.removeFrom(users, " "));
    assertFalse(SupportUserExclusion.removeFrom(null, CLIENT));
    assertEquals(1, users.size());
  }

  @Test
  void hqlPredicateCarriesTheDerivedIdOnTheGivenAlias() {
    assertEquals(EXPECTED_PREDICATE, SupportUserExclusion.hqlNotSupportUser("e", CLIENT));
  }

  @Test
  void hqlPredicateIsNullWithoutClientOrAlias() {
    assertNull(SupportUserExclusion.hqlNotSupportUser("e", null));
    assertNull(SupportUserExclusion.hqlNotSupportUser("e", ""));
    assertNull(SupportUserExclusion.hqlNotSupportUser(" ", CLIENT));
  }

  @Test
  void userReadPredicatesUseTheReadContextClient() {
    assertEquals(List.of(EXPECTED_PREDICATE),
        SupportUserExclusion.userReadPredicates(contextOf(CLIENT)));
  }

  @Test
  void userReadPredicatesFallBackToTheCurrentContext() {
    OBContext current = contextOf(CLIENT);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(current);

      assertEquals(List.of(EXPECTED_PREDICATE),
          SupportUserExclusion.userReadPredicates(null));
    }
  }

  @Test
  void userReadPredicatesAreEmptyWithoutAnyClient() {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(null);

      assertTrue(SupportUserExclusion.userReadPredicates(null).isEmpty());
      assertNull(SupportUserExclusion.currentClientId());
    }
  }

  @Test
  void supportUserIdOrNullMatchesTheGuard() {
    assertEquals(SUPPORT_USER, SupportUserExclusion.supportUserIdOrNull(CLIENT));
    assertNull(SupportUserExclusion.supportUserIdOrNull(null));
  }
}
