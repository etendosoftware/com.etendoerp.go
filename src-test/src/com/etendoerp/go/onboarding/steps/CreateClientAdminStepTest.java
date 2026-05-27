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
package com.etendoerp.go.onboarding.steps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.authentication.hashing.PasswordHash;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStepException;

/** Tests for {@link CreateClientAdminStep}. */
public class CreateClientAdminStepTest {

  @Test
  public void nameReturnsCorrectValue() {
    assertEquals("createClientAdmin", new CreateClientAdminStep().name());
  }

  @Test
  public void executeCreatesUserSuccessfully() throws Exception {
    CreateClientAdminStep step = new CreateClientAdminStep();
    OnboardingContext ctx = mock(OnboardingContext.class);
    when(ctx.getClientId()).thenReturn("client-1");
    when(ctx.getAdminUser()).thenReturn("admin@test.com");
    when(ctx.getAdminPassword()).thenReturn("pass123");
    when(ctx.getClientName()).thenReturn("TestCo");

    Client client = mock(Client.class);
    Organization orgZero = mock(Organization.class);
    User user = mock(User.class);
    when(user.getId()).thenReturn("new-user-id");

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(Client.class, "client-1")).thenReturn(client);
    when(obDal.get(Organization.class, "0")).thenReturn(orgZero);

    OBProvider obProvider = mock(OBProvider.class);
    when(obProvider.get(User.class)).thenReturn(user);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
         MockedStatic<PasswordHash> hashMock = mockStatic(PasswordHash.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      providerMock.when(OBProvider::getInstance).thenReturn(obProvider);
      hashMock.when(() -> PasswordHash.generateHash("pass123")).thenReturn("hashed");

      step.execute(ctx);

      verify(user).setClient(client);
      verify(user).setOrganization(orgZero);
      verify(user).setUsername("admin@test.com");
      verify(user).setPassword("hashed");
      verify(obDal).save(user);
      verify(ctx).setClientAdminUserId("new-user-id");
    }
  }

  @Test(expected = OnboardingStepException.class)
  public void executeClientNotFoundThrows() throws Exception {
    CreateClientAdminStep step = new CreateClientAdminStep();
    OnboardingContext ctx = mock(OnboardingContext.class);
    when(ctx.getClientId()).thenReturn("bad-client");

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(Client.class, "bad-client")).thenReturn(null);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      step.execute(ctx);
    }
  }

  @Test(expected = OnboardingStepException.class)
  public void executeOrgZeroNotFoundThrows() throws Exception {
    CreateClientAdminStep step = new CreateClientAdminStep();
    OnboardingContext ctx = mock(OnboardingContext.class);
    when(ctx.getClientId()).thenReturn("client-1");

    Client client = mock(Client.class);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(Client.class, "client-1")).thenReturn(client);
    when(obDal.get(Organization.class, "0")).thenReturn(null);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      step.execute(ctx);
    }
  }
}
