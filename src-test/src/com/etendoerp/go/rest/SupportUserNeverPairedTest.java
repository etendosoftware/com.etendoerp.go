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
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.supportaccess.SupportAccessGuard;

/**
 * ETP-5351 (Vigia H1) — the tenant's "Soporte Etendo" user is never paired with a GO account,
 * even when its email or username is changed to the account's. No database: the DAL is mocked.
 */
class SupportUserNeverPairedTest {

  private static final String CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String EMAIL = "operador@example.com";

  private static User user(String id, String email) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    User user = mock(User.class);
    when(user.getId()).thenReturn(id);
    when(user.getClient()).thenReturn(client);
    when(user.isActive()).thenReturn(true);
    when(user.getEmail()).thenReturn(email);
    when(user.getUsername()).thenReturn(email);
    return user;
  }

  @Test
  void ownershipCheckRejectsSupportUserButAcceptsOthers() {
    User support = user(SupportAccessGuard.supportUserIdFor(CLIENT), EMAIL);
    User regular = user("REGULAR0000000000000000000000001", EMAIL);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, support.getId())).thenReturn(support);
    when(obDal.get(User.class, regular.getId())).thenReturn(regular);
    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
      dal.when(OBDal::getInstance).thenReturn(obDal);
      assertFalse(EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(EMAIL, support.getId()));
      assertTrue(EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(EMAIL, regular.getId()));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void environmentUsersLookupDropsSupportUser() {
    User support = user(SupportAccessGuard.supportUserIdFor(CLIENT), EMAIL);
    User regular = user("REGULAR0000000000000000000000001", EMAIL);
    OBQuery<User> query = mock(OBQuery.class);
    when(query.list()).thenReturn(List.of(support, regular));
    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(org.mockito.ArgumentMatchers.eq(User.class),
        org.mockito.ArgumentMatchers.anyString())).thenReturn(query);
    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
      dal.when(OBDal::getInstance).thenReturn(obDal);
      List<User> found = EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail(EMAIL);
      assertEquals(List.of(regular), found);
    }
  }
}
