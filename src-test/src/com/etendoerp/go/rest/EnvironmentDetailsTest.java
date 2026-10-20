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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.schemaforge.util.OwnerSupport;

/**
 * @covers com.etendoerp.go.rest.EnvironmentDetails
 *
 * <p>ETP-5734 — the per-company details of {@code GET /environments?details=true}: which fields
 * each relationship gets, and that a failing lookup drops only its own field.
 */
class EnvironmentDetailsTest {

  private static final String CLIENT_ID = "client-1";

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OwnerSupport> ownerSupportStatic;
  private MockedStatic<EtendoGoJwtSupport> jwtSupportStatic;
  private OBDal dal;
  private NativeQuery<Object> countQuery;
  private User environmentUser;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    dal = mock(OBDal.class);
    Session session = mock(Session.class);
    countQuery = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(countQuery);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    ownerSupportStatic = mockStatic(OwnerSupport.class);
    jwtSupportStatic = mockStatic(EtendoGoJwtSupport.class);
    environmentUser = mock(User.class);
    when(environmentUser.getId()).thenReturn("user-1");
  }

  @AfterEach
  void tearDown() {
    jwtSupportStatic.close();
    ownerSupportStatic.close();
    obDalStatic.close();
  }

  @Test
  @DisplayName("an owner gets the user count, counted with the Users window's rule, and no role")
  void ownerGetsUserCountOnly() throws Exception {
    when(countQuery.uniqueResult()).thenReturn(4L);

    JSONObject details = EnvironmentDetails.resolve(CLIENT_ID, environmentUser, true);

    assertEquals(4, details.getInt(EnvironmentDetails.FIELD_USER_COUNT));
    assertFalse(details.has(EnvironmentDetails.FIELD_OWNER_NAME));
    assertFalse(details.has(EnvironmentDetails.FIELD_ROLE_NAME));
    verify(countQuery).setParameter("clientId", CLIENT_ID);
  }

  @Test
  @DisplayName("a failing user count leaves the field out instead of failing the company")
  void ownerUserCountFailureIsDropped() {
    when(countQuery.uniqueResult()).thenThrow(new IllegalStateException("db down"));

    JSONObject details = EnvironmentDetails.resolve(CLIENT_ID, environmentUser, true);

    assertFalse(details.has(EnvironmentDetails.FIELD_USER_COUNT));
  }

  @Test
  @DisplayName("an invitee gets the owner name and its role with the composed template names")
  void invitedGetsOwnerAndRole() throws Exception {
    User owner = mock(User.class);
    when(owner.getName()).thenReturn("Laura Morat");
    ownerSupportStatic.when(() -> OwnerSupport.findOwnerUserId(CLIENT_ID)).thenReturn("owner-1");
    when(dal.get(User.class, "owner-1")).thenReturn(owner);
    Role role = mock(Role.class);
    when(role.getName()).thenReturn("jdoe personal");
    when(role.isClientAdmin()).thenReturn(false);
    when(environmentUser.getDefaultRole()).thenReturn(role);
    jwtSupportStatic.when(() -> EtendoGoJwtSupport.resolveEffectiveRoleNames("user-1"))
        .thenReturn(List.of("Finance", "Sales"));

    JSONObject details = EnvironmentDetails.resolve(CLIENT_ID, environmentUser, false);

    assertEquals("Laura Morat", details.getString(EnvironmentDetails.FIELD_OWNER_NAME));
    assertEquals("jdoe personal", details.getString(EnvironmentDetails.FIELD_ROLE_NAME));
    assertFalse(details.getBoolean(EnvironmentDetails.FIELD_ROLE_IS_CLIENT_ADMIN));
    assertEquals(2, details.getJSONArray(EnvironmentDetails.FIELD_ROLE_TEMPLATE_NAMES).length());
    assertEquals("Sales",
        details.getJSONArray(EnvironmentDetails.FIELD_ROLE_TEMPLATE_NAMES).getString(1));
    assertFalse(details.has(EnvironmentDetails.FIELD_USER_COUNT));
  }

  @Test
  @DisplayName("an invitee with a client-admin role and no owner flagged gets the admin flag only")
  void invitedClientAdminWithoutOwner() throws Exception {
    ownerSupportStatic.when(() -> OwnerSupport.findOwnerUserId(CLIENT_ID)).thenReturn(null);
    Role role = mock(Role.class);
    when(role.getName()).thenReturn("Acme SL Admin");
    when(role.isClientAdmin()).thenReturn(true);
    when(environmentUser.getDefaultRole()).thenReturn(role);
    jwtSupportStatic.when(() -> EtendoGoJwtSupport.resolveEffectiveRoleNames("user-1"))
        .thenReturn(List.of());

    JSONObject details = EnvironmentDetails.resolve(CLIENT_ID, environmentUser, false);

    assertFalse(details.has(EnvironmentDetails.FIELD_OWNER_NAME));
    assertTrue(details.getBoolean(EnvironmentDetails.FIELD_ROLE_IS_CLIENT_ADMIN));
    assertEquals(0, details.getJSONArray(EnvironmentDetails.FIELD_ROLE_TEMPLATE_NAMES).length());
  }

  @Test
  @DisplayName("an invitee with no role yet gets no role fields")
  void invitedWithoutRole() {
    when(environmentUser.getDefaultRole()).thenReturn(null);

    JSONObject details = EnvironmentDetails.resolve(CLIENT_ID, environmentUser, false);

    assertFalse(details.has(EnvironmentDetails.FIELD_ROLE_NAME));
    assertFalse(details.has(EnvironmentDetails.FIELD_ROLE_IS_CLIENT_ADMIN));
  }

  @Test
  @DisplayName("merge copies every detail into the environment entry and tolerates no details")
  void mergeCopiesDetails() throws Exception {
    JSONObject env = new JSONObject();
    env.put("clientId", CLIENT_ID);
    JSONObject details = new JSONObject();
    details.put(EnvironmentDetails.FIELD_USER_COUNT, 3);

    EnvironmentDetails.merge(env, details);
    EnvironmentDetails.merge(env, new JSONObject());

    assertEquals(CLIENT_ID, env.getString("clientId"));
    assertEquals(3, env.getInt(EnvironmentDetails.FIELD_USER_COUNT));
  }
}
