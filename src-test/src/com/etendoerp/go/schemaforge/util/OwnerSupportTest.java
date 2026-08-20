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

package com.etendoerp.go.schemaforge.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link OwnerSupport} — {@code AD_User.EM_ETGO_Is_Owner} (ETP-4830).
 *
 * <p>Mirrors {@code SFWindowAccessMapTest}'s native-query mocking convention for {@code
 * AD_Role.EM_ETGO_Show_Acct_Fields}, the precedent extension column this class's read path is
 * modeled on: Hibernate returns {@link Character} elements for a plain scalar native query
 * against a PostgreSQL {@code char(1)} column, never {@link String}, so every "column reads Y"
 * stub below uses a real {@code Character} to catch a regression to a naive {@code String} cast.
 */
public class OwnerSupportTest {

  private MockedStatic<OBDal> obDalMock;
  private OBDal mockDal;
  private Session mockSession;

  @Before
  public void setUp() {
    obDalMock = mockStatic(OBDal.class);
    mockDal = mock(OBDal.class);
    mockSession = mock(Session.class);
    obDalMock.when(OBDal::getInstance).thenReturn(mockDal);
    when(mockDal.getSession()).thenReturn(mockSession);
  }

  @After
  public void tearDown() {
    obDalMock.close();
  }

  @SuppressWarnings("unchecked")
  private NativeQuery<Object> stubQuery(List<?> resultRows) {
    NativeQuery<Object> mockQuery = mock(NativeQuery.class);
    when(mockSession.createNativeQuery(anyString())).thenReturn(mockQuery);
    when(mockQuery.getResultList()).thenReturn((List<Object>) resultRows);
    return mockQuery;
  }

  // ── isOwner ───────────────────────────────────────────────────────────────

  @Test
  public void isOwnerReturnsTrueWhenColumnReadsY() {
    stubQuery(Collections.singletonList(Character.valueOf('Y')));
    assertTrue(OwnerSupport.isOwner("user-1"));
  }

  @Test
  public void isOwnerReturnsFalseWhenColumnReadsN() {
    stubQuery(Collections.singletonList(Character.valueOf('N')));
    assertFalse(OwnerSupport.isOwner("user-1"));
  }

  @Test
  public void isOwnerReturnsFalseWhenColumnIsNull() {
    // The physical column is NOT NULL DEFAULT 'N', but a legacy/unexpected null row must still
    // resolve to "not owner" rather than throw.
    stubQuery(Collections.singletonList(null));
    assertFalse(OwnerSupport.isOwner("user-1"));
  }

  @Test
  public void isOwnerReturnsFalseWhenUserDoesNotExist() {
    stubQuery(Collections.emptyList());
    assertFalse(OwnerSupport.isOwner("missing-user"));
  }

  @Test
  public void isOwnerReturnsFalseForBlankOrNullIdWithoutQueryingTheDb() {
    assertFalse(OwnerSupport.isOwner(null));
    assertFalse(OwnerSupport.isOwner("  "));
    obDalMock.verify(OBDal::getInstance, never());
  }

  // ── clientHasOwner ────────────────────────────────────────────────────────

  @Test
  public void clientHasOwnerReturnsTrueWhenAnyRowMatches() {
    stubQuery(Collections.singletonList(1));
    assertTrue(OwnerSupport.clientHasOwner("client-1"));
  }

  @Test
  public void clientHasOwnerReturnsFalseWhenNoRowMatches() {
    stubQuery(Collections.emptyList());
    assertFalse(OwnerSupport.clientHasOwner("client-1"));
  }

  @Test
  public void clientHasOwnerReturnsFalseForBlankOrNullIdWithoutQueryingTheDb() {
    assertFalse(OwnerSupport.clientHasOwner(null));
    assertFalse(OwnerSupport.clientHasOwner(""));
    obDalMock.verify(OBDal::getInstance, never());
  }

  // ── markAsOwnerIfNoneExists ───────────────────────────────────────────────

  @Test
  public void markAsOwnerIfNoneExistsUpdatesTheRowWhenClientHasNoOwnerYet() {
    // First native query: clientHasOwner() -> empty (no owner yet). Second: the UPDATE itself.
    NativeQuery<Object> checkQuery = mock(NativeQuery.class);
    NativeQuery<Object> updateQuery = mock(NativeQuery.class);
    when(mockSession.createNativeQuery(anyString())).thenReturn(checkQuery, updateQuery);
    when(checkQuery.getResultList()).thenReturn(Collections.emptyList());
    when(updateQuery.executeUpdate()).thenReturn(1);

    OwnerSupport.markAsOwnerIfNoneExists("client-1", "user-1");

    verify(updateQuery, times(1)).executeUpdate();
  }

  @Test
  public void markAsOwnerIfNoneExistsIsNoOpWhenClientAlreadyHasAnOwner() {
    stubQuery(Collections.singletonList(1));

    OwnerSupport.markAsOwnerIfNoneExists("client-1", "user-2");

    // Only the clientHasOwner() check ran — no second (UPDATE) native query was ever created.
    verify(mockSession, times(1)).createNativeQuery(anyString());
  }

  @Test
  public void markAsOwnerIfNoneExistsIsNoOpForMissingClientOrUserWithoutQueryingTheDb() {
    OwnerSupport.markAsOwnerIfNoneExists(null, "user-1");
    OwnerSupport.markAsOwnerIfNoneExists("client-1", null);
    OwnerSupport.markAsOwnerIfNoneExists("  ", "  ");

    obDalMock.verify(OBDal::getInstance, never());
  }
}
