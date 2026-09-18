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
package com.etendoerp.go.common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

/**
 * Unit tests for {@link FiscalTestModeResolver} — the effective {@code ETSG_ForceTestMode}
 * lookup backing {@code GET /sws/neo/fiscal-test-mode}.
 */
public class FiscalTestModeResolverTest {

  private static final String CLIENT_ID = "9F8E7D6C5B4A39281706E5D4C3B2A190";

  @SuppressWarnings("unchecked")
  private OBCriteria<Preference> mockCriteria() {
    OBCriteria<Preference> crit = mock(OBCriteria.class);
    when(crit.setFilterOnReadableClients(false)).thenReturn(crit);
    when(crit.setFilterOnReadableOrganization(false)).thenReturn(crit);
    when(crit.add(any())).thenReturn(crit);
    when(crit.addOrder(any())).thenReturn(crit);
    when(crit.setMaxResults(1)).thenReturn(crit);
    return crit;
  }

  @Test
  public void returnsTrueWhenClientOwnRowIsYes() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);

    Preference ownRow = mock(Preference.class);
    when(ownRow.getSearchKey()).thenReturn("Y");

    OBCriteria<Preference> crit = mockCriteria();
    when(crit.uniqueResult()).thenReturn(ownRow);

    OBDal obDal = mock(OBDal.class);
    doReturn(crit).when(obDal).createCriteria(Preference.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      assertTrue(FiscalTestModeResolver.isForceTestModeActive(client));
    }
  }

  @Test
  public void returnsFalseWhenClientOwnRowIsNo() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);

    Preference ownRow = mock(Preference.class);
    when(ownRow.getSearchKey()).thenReturn("N");

    OBCriteria<Preference> crit = mockCriteria();
    when(crit.uniqueResult()).thenReturn(ownRow);

    OBDal obDal = mock(OBDal.class);
    doReturn(crit).when(obDal).createCriteria(Preference.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      assertFalse(FiscalTestModeResolver.isForceTestModeActive(client));
    }
  }

  @Test
  public void fallsBackToSystemRowWhenClientHasNoOwnRow() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);

    Preference systemRow = mock(Preference.class);
    when(systemRow.getSearchKey()).thenReturn("Y");

    OBCriteria<Preference> ownAttempt = mockCriteria();
    when(ownAttempt.uniqueResult()).thenReturn(null);
    OBCriteria<Preference> systemAttempt = mockCriteria();
    when(systemAttempt.uniqueResult()).thenReturn(systemRow);

    OBDal obDal = mock(OBDal.class);
    doReturn(ownAttempt).doReturn(systemAttempt).when(obDal).createCriteria(Preference.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      assertTrue(FiscalTestModeResolver.isForceTestModeActive(client));
    }
  }

  @Test
  public void returnsFalseWhenNeitherOwnNorSystemRowExists() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);

    OBCriteria<Preference> ownAttempt = mockCriteria();
    when(ownAttempt.uniqueResult()).thenReturn(null);
    OBCriteria<Preference> systemAttempt = mockCriteria();
    when(systemAttempt.uniqueResult()).thenReturn(null);

    OBDal obDal = mock(OBDal.class);
    doReturn(ownAttempt).doReturn(systemAttempt).when(obDal).createCriteria(Preference.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      assertFalse(FiscalTestModeResolver.isForceTestModeActive(client));
    }
  }

  @Test
  public void systemClientWithNoRowNeverDoubleQueries() {
    // Client id "0" IS the System client already — must not attempt a second (fallback) lookup.
    Client systemClient = mock(Client.class);
    when(systemClient.getId()).thenReturn("0");

    OBCriteria<Preference> crit = mockCriteria();
    when(crit.uniqueResult()).thenReturn(null);

    OBDal obDal = mock(OBDal.class);
    doReturn(crit).when(obDal).createCriteria(Preference.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      assertFalse(FiscalTestModeResolver.isForceTestModeActive(systemClient));
    }
  }

  @Test
  public void nullClientResolvesDirectlyToSystemRow() {
    Preference systemRow = mock(Preference.class);
    when(systemRow.getSearchKey()).thenReturn("Y");

    OBCriteria<Preference> crit = mockCriteria();
    when(crit.uniqueResult()).thenReturn(systemRow);

    OBDal obDal = mock(OBDal.class);
    doReturn(crit).when(obDal).createCriteria(Preference.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      assertTrue(FiscalTestModeResolver.isForceTestModeActive(null));
    }
  }
}
