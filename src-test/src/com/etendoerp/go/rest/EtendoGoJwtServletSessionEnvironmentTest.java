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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.etendoerp.go.schemaforge.util.OwnerAccountResolver;
import com.etendoerp.go.session.GoSessionRecord;

/**
 * ETP-6228: {@code environment.etgoAccountId} of the session payload is the account that owns the
 * client, and is left out when it cannot be resolved.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
public class EtendoGoJwtServletSessionEnvironmentTest {

  private static GoSessionRecord record(String clientId) {
    GoSessionRecord record = new GoSessionRecord();
    record.setUserId("U1");
    record.setRoleId("R1");
    record.setCtxClientId(clientId);
    record.setCtxOrgId("O1");
    return record;
  }

  @Test
  public void carriesTheOwnerAccountOfTheClient() throws Exception {
    try (MockedStatic<OwnerAccountResolver> owner = Mockito.mockStatic(OwnerAccountResolver.class)) {
      owner.when(() -> OwnerAccountResolver.findOwnerAccountId("C1")).thenReturn("ACCOUNT-OWNER");

      JSONObject env = (JSONObject) EtendoGoJwtServlet.buildSessionEnvironment(record("C1"));

      assertEquals("ACCOUNT-OWNER", env.getString("etgoAccountId"));
      assertEquals("U1", env.getString("userId"));
      assertEquals("C1", env.getString("clientId"));
    }
  }

  @Test
  public void leavesItOutWhenTheOwnerAccountIsUnknown() throws Exception {
    try (MockedStatic<OwnerAccountResolver> owner = Mockito.mockStatic(OwnerAccountResolver.class)) {
      owner.when(() -> OwnerAccountResolver.findOwnerAccountId("C1")).thenReturn(null);

      JSONObject env = (JSONObject) EtendoGoJwtServlet.buildSessionEnvironment(record("C1"));

      assertFalse(env.has("etgoAccountId"));
    }
  }

  @Test
  public void aFailedLookupNeverBreaksTheSession() throws Exception {
    try (MockedStatic<OwnerAccountResolver> owner = Mockito.mockStatic(OwnerAccountResolver.class)) {
      owner.when(() -> OwnerAccountResolver.findOwnerAccountId("C1"))
          .thenThrow(new IllegalStateException("db down"));

      JSONObject env = (JSONObject) EtendoGoJwtServlet.buildSessionEnvironment(record("C1"));

      assertFalse(env.has("etgoAccountId"));
      assertTrue(env.has("clientId"));
    }
  }

  @Test
  public void theSystemClientHasNoOwnerAccount() throws Exception {
    try (MockedStatic<OwnerAccountResolver> owner = Mockito.mockStatic(OwnerAccountResolver.class)) {
      JSONObject env = (JSONObject) EtendoGoJwtServlet.buildSessionEnvironment(record("0"));

      assertFalse(env.has("etgoAccountId"));
      owner.verifyNoInteractions();
    }
  }
}
