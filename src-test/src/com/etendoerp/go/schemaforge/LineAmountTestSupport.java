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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.codehaus.jettison.json.JSONObject;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

/**
 * Test support for the ETP-5528 line-amount tests: the only DB read on their path is
 * {@code NeoCommercialLinePolicy#fetchTaxRate}, which goes straight to JDBC through
 * {@code OBDal.getInstance().getConnection(false)}. The whole chain down to the ResultSet is
 * stubbed here, every mock built into a local before it is stubbed (no nested stubbing).
 */
final class LineAmountTestSupport {

  private LineAmountTestSupport() {
  }

  /**
   * Opens a static mock of {@link OBDal} whose tax-rate query answers {@code rate}. The caller owns
   * the returned mock and must close it (try-with-resources).
   */
  static MockedStatic<OBDal> taxRate(double rate) throws SQLException {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getDouble(1)).thenReturn(rate);

    MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    return dalMock;
  }

  /**
   * Asserts {@code body[key]}: absent when {@code expected} is {@code null}, otherwise equal to it
   * at 2 decimals — the scale every commercial amount is persisted and compared with.
   */
  static void assertAmount(String label, String expected, JSONObject body, String key) {
    if (expected == null) {
      assertFalse(body.has(key), label + ": " + key + " must stay absent, was " + body.opt(key));
      return;
    }
    Object actual = body.opt(key);
    assertEquals(new BigDecimal(expected).setScale(2, RoundingMode.HALF_UP),
        new BigDecimal(String.valueOf(actual)).setScale(2, RoundingMode.HALF_UP),
        label + ": " + key);
  }
}
