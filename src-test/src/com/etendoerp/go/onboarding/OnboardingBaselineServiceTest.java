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
package com.etendoerp.go.onboarding;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

public class OnboardingBaselineServiceTest {

  @Test
  public void testRegisterBaselineInsertsSystemOwnedBaselineRow() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(dal.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(contains("INSERT INTO etgo_data_fix_history")))
        .thenReturn(statement);
    when(statement.executeUpdate()).thenReturn(1);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      new OnboardingBaselineService().registerBaseline("CLIENT-1");
    }

    verify(dal).flush();
    verify(statement).setString(1, "0");
    verify(statement).setString(2, "0");
    verify(statement).setString(3, "0");
    verify(statement).setString(4, "0");
    verify(statement).setString(5, "CLIENT-1");
    verify(statement).setString(6, "__baseline__");
    verify(statement).setString(7, "BASELINE");
    verify(statement).executeUpdate();
    verify(statement).close();
  }

  @Test
  public void testRegisterBaselineAcceptsExistingBaselineConflict() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(dal.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(contains("ON CONFLICT ON CONSTRAINT etgo_dfh_tenant_fix_un")))
        .thenReturn(statement);
    when(statement.executeUpdate()).thenReturn(0);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      new OnboardingBaselineService().registerBaseline("CLIENT-1");
    }

    verify(dal).flush();
    verify(statement).executeUpdate();
    verify(statement).close();
  }

  @Test
  public void testRegisterBaselineFailsWhenClientIdIsMissing() {
    try {
      new OnboardingBaselineService().registerBaseline("");
      fail("Expected missing client id to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("onboarding context has no client id"));
    }
  }

  @Test
  public void testRegisterBaselineRethrowsSqlErrorAsObException() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection connection = mock(Connection.class);
    when(dal.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(contains("INSERT INTO etgo_data_fix_history")))
        .thenThrow(new SQLException("sql-boom"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      try {
        new OnboardingBaselineService().registerBaseline("CLIENT-1");
        fail("Expected SQL error to fail");
      } catch (OBException e) {
        assertTrue(e.getMessage().contains("Failed to register data-fix baseline"));
        assertTrue(e.getMessage().contains("CLIENT-1"));
        assertTrue(e.getCause() instanceof SQLException);
      }
    }

    verify(dal).flush();
  }
}
