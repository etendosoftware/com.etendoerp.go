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
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.util.OwnerSupport;

/**
 * ETP-5577 — who may confirm or regenerate a tenant's accounting calendar: the owner of THAT
 * tenant. Regenerate deletes the calendar, so owning another environment of the same account
 * (what {@code requireBillingOwner} accepts) must not be enough.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
class EtendoGoJwtServletCalendarReviewTest {

  private OBDal dal;
  private MockedStatic<OBDal> obDal;
  private MockedStatic<OwnerSupport> owners;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    owners = mockStatic(OwnerSupport.class);
  }

  @AfterEach
  void tearDown() {
    owners.close();
    obDal.close();
  }

  @Test
  void theOwnerOfThisTenantMayAct() {
    user("OWNER", "TENANT", true);
    assertEquals("OWNER", EtendoGoJwtServlet.ownerOfTenant("OWNER", "TENANT"));
  }

  @Test
  void aMemberOfThisTenantMayNot() {
    user("MEMBER", "TENANT", false);
    assertNull(EtendoGoJwtServlet.ownerOfTenant("MEMBER", "TENANT"));
  }

  @Test
  void theOwnerOfAnotherTenantOfTheAccountMayNot() {
    user("OWNER-ELSEWHERE", "OTHER-TENANT", true);
    assertNull(EtendoGoJwtServlet.ownerOfTenant("OWNER-ELSEWHERE", "TENANT"));
  }

  @Test
  void noUserMeansNoOwner() {
    assertNull(EtendoGoJwtServlet.ownerOfTenant(null, "TENANT"));
    assertNull(EtendoGoJwtServlet.ownerOfTenant("UNKNOWN", "TENANT"));
  }

  private void user(String userId, String clientId, boolean owner) {
    User user = mock(User.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(user.getClient()).thenReturn(client);
    when(dal.get(User.class, userId)).thenReturn(user);
    owners.when(() -> OwnerSupport.isOwner(userId)).thenReturn(owner);
  }
}
