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
package com.etendoerp.go.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5395 — the SQL lookups behind {@link GoSessionRoleReconciler}, and the environment-entry
 * context derivation it reuses.
 */
public class DalRoleDirectoryTest {

  private MockedStatic<OBDal> obDalStatic;
  private OBDal obDal;
  @SuppressWarnings("rawtypes")
  private NativeQuery query;
  private final DalRoleDirectory directory = new DalRoleDirectory();

  @Before
  public void setUp() {
    obDalStatic = mockStatic(OBDal.class);
    obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    query = mock(NativeQuery.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(query);
  }

  @After
  public void tearDown() {
    obDalStatic.close();
  }

  @Test
  public void eligibleWhenTheUserHoldsAnActiveRoleOfTheClient() {
    when(query.uniqueResult()).thenReturn(BigInteger.ONE);

    assertTrue(directory.isEligible("U", "R", "C"));

    verify(query).setParameter("userId", "U");
    verify(query).setParameter("roleId", "R");
    verify(query).setParameter("clientId", "C");
  }

  @Test
  public void notEligibleWhenNoMatchingAssignment() {
    when(query.uniqueResult()).thenReturn(BigInteger.ZERO);

    assertFalse(directory.isEligible("U", "R", "C"));
  }

  @Test
  public void eligibilityRequiresActiveAssignmentActiveRoleAndSameClient() {
    Session session = obDal.getSession();
    when(query.uniqueResult()).thenReturn(BigInteger.ONE);

    directory.isEligible("U", "R", "C");

    verify(session).createNativeQuery(contains("ur.isactive = 'Y'"));
    verify(session).createNativeQuery(contains("r.isactive = 'Y'"));
    verify(session).createNativeQuery(contains("r.ad_client_id = :clientId"));
  }

  @Test
  public void readsTheDefaultRole() {
    when(query.list()).thenReturn(Collections.singletonList("DEFAULT"));
    assertEquals("DEFAULT", directory.findDefaultRoleId("U"));

    when(query.list()).thenReturn(Collections.singletonList(null));
    assertNull(directory.findDefaultRoleId("U"));

    when(query.list()).thenReturn(Collections.emptyList());
    assertNull(directory.findDefaultRoleId("U"));
  }

  @Test
  public void listsActiveRolesInOrder() {
    when(query.list()).thenReturn(Arrays.asList("A", "B"));

    assertEquals(List.of("A", "B"), directory.findActiveRoleIds("U"));
  }

  @Test
  public void orgAccess() {
    assertFalse("no org means no access", directory.hasOrgAccess("R", null));

    when(query.uniqueResult()).thenReturn(BigInteger.ONE);
    assertTrue(directory.hasOrgAccess("R", "O"));

    when(query.uniqueResult()).thenReturn(BigInteger.ZERO);
    assertFalse(directory.hasOrgAccess("R", "O"));
  }

  @Test
  public void derivesOrgAndWarehouseFromTheEnvironmentEntryToken() throws Exception {
    User user = mock(User.class);
    Role role = mock(Role.class);
    when(obDal.get(User.class, "U")).thenReturn(user);
    when(obDal.get(Role.class, "R")).thenReturn(role);
    DecodedJWT claims = mock(DecodedJWT.class);
    Claim org = mock(Claim.class);
    Claim warehouse = mock(Claim.class);
    when(org.asString()).thenReturn("ORG");
    when(warehouse.asString()).thenReturn("WH");
    when(claims.getClaim("organization")).thenReturn(org);
    when(claims.getClaim("warehouse")).thenReturn(warehouse);

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      OBContext previous = mock(OBContext.class);
      ctx.when(OBContext::getOBContext).thenReturn(previous);
      sws.when(() -> SecureWebServicesUtils.generateToken(user, role)).thenReturn("TOKEN");
      sws.when(() -> SecureWebServicesUtils.decodeToken("TOKEN")).thenReturn(claims);

      GoSessionRoleReconciler.RoleContext context = directory.deriveContext("U", "R");

      assertEquals("ORG", context.orgId());
      assertEquals("WH", context.warehouseId());
      ctx.verify(() -> OBContext.setOBContext("0", "0", "0", "0"));
      ctx.verify(() -> OBContext.setOBContext(previous));
    }
  }

  @Test
  public void derivationFailureIsReportedAndTheCallerContextRestored() throws Exception {
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      OBContext previous = mock(OBContext.class);
      ctx.when(OBContext::getOBContext).thenReturn(previous);
      sws.when(() -> SecureWebServicesUtils.generateToken(any(), any()))
          .thenThrow(new IllegalStateException("no signing key"));

      assertThrows(OBException.class, () -> directory.deriveContext("U", "R"));

      ctx.verify(() -> OBContext.setOBContext(previous));
    }
  }
}
