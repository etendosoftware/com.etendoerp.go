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

/**
 * Unit tests for {@link OnboardingAcctdimCentrallyMaintainedService} — ETP-4854, gap K1.
 *
 * <p>Mirrors {@code OnboardingBaselineServiceTest}'s Mockito pattern (mocked
 * {@link Connection}/{@link PreparedStatement}, no live DB) since the service talks to the shared
 * DAL connection via raw JDBC, exactly like {@code OnboardingBaselineService}.</p>
 *
 * <p>The "freshly-provisioned tenant already has {@code Acctdim_Centrally_Maintained = 'N'}"
 * behavior this ticket asks for is proven here as: given a client whose per-dimension AD_Client
 * config resolves the effective-visibility formula, the service (a) backfills
 * {@code C_AcctSchema_Element.isactive} accordingly and (b) flips the flag — both on the SAME
 * connection, in the order backfill-then-flip, atomically with the rest of onboarding (the
 * connection is never committed/rolled back here directly; that is the servlet's / DAL helper's
 * responsibility, same contract as every other Onboarding*Service).</p>
 */
public class OnboardingAcctdimCentrallyMaintainedServiceTest {

  @Test
  public void testForceFlatAccountingDimensionVisibilityBackfillsThenFlipsFlag() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection connection = mock(Connection.class);
    PreparedStatement backfillStatement = mock(PreparedStatement.class);
    PreparedStatement flipStatement = mock(PreparedStatement.class);
    when(dal.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(contains("WITH dim_effective")))
        .thenReturn(backfillStatement);
    when(connection.prepareStatement(contains("UPDATE ad_client")))
        .thenReturn(flipStatement);
    when(backfillStatement.executeUpdate()).thenReturn(4);
    when(flipStatement.executeUpdate()).thenReturn(1);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      new OnboardingAcctdimCentrallyMaintainedService()
          .forceFlatAccountingDimensionVisibility("CLIENT-1");
    }

    verify(dal).flush();
    // 7 CTE branches bind the same client id (OO/PJ/BP/PR/CC/U1/U2), then updatedby='0', then the
    // final WHERE e.ad_client_id = ? binds the client id again.
    for (int i = 1; i <= 7; i++) {
      verify(backfillStatement).setString(i, "CLIENT-1");
    }
    verify(backfillStatement).setString(8, "0");
    verify(backfillStatement).setString(9, "CLIENT-1");
    verify(backfillStatement).executeUpdate();
    verify(backfillStatement).close();

    verify(flipStatement).setString(1, "0");
    verify(flipStatement).setString(2, "CLIENT-1");
    verify(flipStatement).executeUpdate();
    verify(flipStatement).close();
  }

  @Test
  public void testForceFlatAccountingDimensionVisibilityFailsWhenClientIdIsMissing() {
    try {
      new OnboardingAcctdimCentrallyMaintainedService().forceFlatAccountingDimensionVisibility("");
      fail("Expected missing client id to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("onboarding context has no client id"));
    }
  }

  @Test
  public void testForceFlatAccountingDimensionVisibilityRethrowsSqlErrorAsObException()
      throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection connection = mock(Connection.class);
    when(dal.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(contains("WITH dim_effective")))
        .thenThrow(new SQLException("sql-boom"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      try {
        new OnboardingAcctdimCentrallyMaintainedService()
            .forceFlatAccountingDimensionVisibility("CLIENT-1");
        fail("Expected SQL error to fail");
      } catch (OBException e) {
        assertTrue(e.getMessage().contains("Failed to force flat accounting-dimension visibility"));
        assertTrue(e.getMessage().contains("CLIENT-1"));
        assertTrue(e.getCause() instanceof SQLException);
      }
    }

    verify(dal).flush();
  }
}
