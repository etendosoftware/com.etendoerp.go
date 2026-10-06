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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

/**
 * The one resolution of the tenant a credential acts on (ETP-5047), shared by the NEO bind step
 * and MCP. Moved here from {@code McpSessionManager} together with the specs of its role lookup.
 *
 * @covers com.etendoerp.go.auth.EffectiveClientResolver
 */
class EffectiveClientResolverTest {

  private static final String ROLE_ID = "role-1";

  private MockedStatic<OBDal> dalStatic;
  private Session session;

  @BeforeEach
  void setUp() {
    OBDal dal = mock(OBDal.class);
    session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
  }

  @AfterEach
  void tearDown() {
    dalStatic.close();
  }

  @Test
  void aConcreteClientPassesThroughWithoutALookup() {
    assertEquals("client-1", EffectiveClientResolver.effectiveClientId("client-1", ROLE_ID));
    verify(session, never()).doReturningWork(any());
  }

  @Test
  void theWildcardClientResolvesToTheRolesTenant() {
    when(session.doReturningWork(any())).thenReturn("tenant-of-role");

    assertEquals("tenant-of-role", EffectiveClientResolver.effectiveClientId("0", ROLE_ID));
  }

  @Test
  void aSystemOrUnknownRoleKeepsTheWildcard() {
    // The lookup answers null for a role on client 0 (it maps "0" to null) and for no row.
    when(session.doReturningWork(any())).thenReturn(null);

    assertEquals("0", EffectiveClientResolver.effectiveClientId("0", ROLE_ID));
    assertNull(EffectiveClientResolver.clientOfRole(ROLE_ID));
  }

  /** Fails closed: a failed lookup is not a System role, and must never read as "0". */
  @Test
  void aFailedLookupThrowsInsteadOfFallingBackToTheWildcard() {
    when(session.doReturningWork(any())).thenThrow(new IllegalStateException("db error"));

    EffectiveClientResolver.ResolutionException failure = assertThrows(
        EffectiveClientResolver.ResolutionException.class,
        () -> EffectiveClientResolver.effectiveClientId("0", ROLE_ID));
    assertEquals("db error", failure.getCause().getMessage());
  }
}
