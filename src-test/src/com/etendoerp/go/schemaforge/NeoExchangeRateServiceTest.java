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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Pure unit tests for {@link NeoExchangeRateService}: the private JDBC helpers {@code queryRate}
 * and {@code resolveToDbId} (reached via reflection) and the public
 * {@code handleValidateExchangeRate} entry point with OBContext/OBDal statics mocked. JDBC objects
 * are Mockito mocks; no real database is touched.
 */
public class NeoExchangeRateServiceTest {

  // ---------------------------------------------------------------
  // Reflection helpers
  // ---------------------------------------------------------------

  private static Double invokeQueryRate(Connection conn, String fromId, String toId,
      String clientId, String orgId, LocalDate date) throws Exception {
    Method m = NeoExchangeRateService.class.getDeclaredMethod("queryRate", Connection.class,
        String.class, String.class, String.class, String.class, LocalDate.class);
    m.setAccessible(true);
    return (Double) m.invoke(null, conn, fromId, toId, clientId, orgId, date);
  }

  private static String invokeResolveToDbId(String currencyOrIso, Connection conn) throws Exception {
    Method m = NeoExchangeRateService.class.getDeclaredMethod("resolveToDbId", String.class,
        Connection.class);
    m.setAccessible(true);
    return (String) m.invoke(null, currencyOrIso, conn);
  }

  /** Wires conn.prepareStatement(any) → ps → ps.executeQuery() → rs. */
  private static PreparedStatement wirePreparedStatement(Connection conn, ResultSet rs)
      throws SQLException {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    return ps;
  }

  // ---------------------------------------------------------------
  // queryRate
  // ---------------------------------------------------------------

  @Test
  public void testQueryRateReturnsMultiplyRateWhenRowExists() throws Exception {
    Connection conn = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    wirePreparedStatement(conn, rs);
    when(rs.next()).thenReturn(true);
    when(rs.getDouble("multiplyrate")).thenReturn(1.09);

    Double rate = invokeQueryRate(conn, "from", "to", "client", "org", LocalDate.of(2026, 1, 15));
    assertEquals(Double.valueOf(1.09), rate);
  }

  @Test
  public void testQueryRateReturnsNullWhenNoRow() throws Exception {
    Connection conn = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    wirePreparedStatement(conn, rs);
    when(rs.next()).thenReturn(false);

    assertNull(invokeQueryRate(conn, "from", "to", "client", "org", LocalDate.of(2026, 1, 15)));
  }

  // ---------------------------------------------------------------
  // resolveToDbId
  // ---------------------------------------------------------------

  @Test
  public void testResolveToDbIdReturnsDbIdWhenMatchFound() throws Exception {
    Connection conn = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    wirePreparedStatement(conn, rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn("CURR-ID-100");

    assertEquals("CURR-ID-100", invokeResolveToDbId("USD", conn));
  }

  @Test
  public void testResolveToDbIdFallsBackToInputWhenNoMatch() throws Exception {
    Connection conn = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    wirePreparedStatement(conn, rs);
    when(rs.next()).thenReturn(false);

    // No DB match → original value passes through unchanged
    assertEquals("LEGACY-ID", invokeResolveToDbId("LEGACY-ID", conn));
  }

  // ---------------------------------------------------------------
  // handleValidateExchangeRate
  // ---------------------------------------------------------------

  @Test
  public void testHandleValidateMissingParamsReturns400() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("fromCurrency")).thenReturn(null);
    when(request.getParameter("toCurrency")).thenReturn("EUR");
    when(request.getParameter("date")).thenReturn("2026-01-15");

    NeoResponse response = NeoExchangeRateService.handleValidateExchangeRate(request);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void testHandleValidateSameCurrencyReturnsRateOne() throws Exception {
    HttpServletRequest request = requestWith("USD", "USD", "2026-01-15");

    Connection conn = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    wirePreparedStatement(conn, rs);
    // resolveToDbId for both currencies resolves to the same id
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn("SAME-ID");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      stubContext(ctxMock);
      OBDal obDal = mock(OBDal.class);
      when(obDal.getConnection()).thenReturn(conn);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse response = NeoExchangeRateService.handleValidateExchangeRate(request);

      assertEquals(200, response.getHttpStatus());
      assertTrue(response.getBody().getBoolean("hasRate"));
      assertEquals(1.0, response.getBody().getDouble("rate"), 0.0001);
    }
  }

  @Test
  public void testHandleValidateDirectRateFound() throws Exception {
    HttpServletRequest request = requestWith("USD", "EUR", "2026-01-15");

    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    // 1st resolveToDbId → USD-ID, 2nd resolveToDbId → EUR-ID, 3rd queryRate (direct) → 1.16
    ResultSet rsFrom = mock(ResultSet.class);
    when(rsFrom.next()).thenReturn(true);
    when(rsFrom.getString(1)).thenReturn("USD-ID");
    ResultSet rsTo = mock(ResultSet.class);
    when(rsTo.next()).thenReturn(true);
    when(rsTo.getString(1)).thenReturn("EUR-ID");
    ResultSet rsRate = mock(ResultSet.class);
    when(rsRate.next()).thenReturn(true);
    when(rsRate.getDouble("multiplyrate")).thenReturn(1.16);
    when(ps.executeQuery()).thenReturn(rsFrom, rsTo, rsRate);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      stubContext(ctxMock);
      OBDal obDal = mock(OBDal.class);
      when(obDal.getConnection()).thenReturn(conn);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse response = NeoExchangeRateService.handleValidateExchangeRate(request);

      assertEquals(200, response.getHttpStatus());
      assertTrue(response.getBody().getBoolean("hasRate"));
      assertEquals(1.16, response.getBody().getDouble("rate"), 0.0001);
    }
  }

  @Test
  public void testHandleValidateInverseRateFallback() throws Exception {
    HttpServletRequest request = requestWith("USD", "EUR", "2026-01-15");

    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    ResultSet rsFrom = mock(ResultSet.class);
    when(rsFrom.next()).thenReturn(true);
    when(rsFrom.getString(1)).thenReturn("USD-ID");
    ResultSet rsTo = mock(ResultSet.class);
    when(rsTo.next()).thenReturn(true);
    when(rsTo.getString(1)).thenReturn("EUR-ID");
    // direct query → no row
    ResultSet rsDirect = mock(ResultSet.class);
    when(rsDirect.next()).thenReturn(false);
    // inverse query → 2.0 → response rate = 0.5
    ResultSet rsInverse = mock(ResultSet.class);
    when(rsInverse.next()).thenReturn(true);
    when(rsInverse.getDouble("multiplyrate")).thenReturn(2.0);
    when(ps.executeQuery()).thenReturn(rsFrom, rsTo, rsDirect, rsInverse);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      stubContext(ctxMock);
      OBDal obDal = mock(OBDal.class);
      when(obDal.getConnection()).thenReturn(conn);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse response = NeoExchangeRateService.handleValidateExchangeRate(request);

      assertEquals(200, response.getHttpStatus());
      assertTrue(response.getBody().getBoolean("hasRate"));
      assertEquals(0.5, response.getBody().getDouble("rate"), 0.0001);
    }
  }

  @Test
  public void testHandleValidateNoRateFoundReturnsFalse() throws Exception {
    HttpServletRequest request = requestWith("USD", "EUR", "2026-01-15");

    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    ResultSet rsFrom = mock(ResultSet.class);
    when(rsFrom.next()).thenReturn(true);
    when(rsFrom.getString(1)).thenReturn("USD-ID");
    ResultSet rsTo = mock(ResultSet.class);
    when(rsTo.next()).thenReturn(true);
    when(rsTo.getString(1)).thenReturn("EUR-ID");
    ResultSet rsDirect = mock(ResultSet.class);
    when(rsDirect.next()).thenReturn(false);
    ResultSet rsInverse = mock(ResultSet.class);
    when(rsInverse.next()).thenReturn(false);
    when(ps.executeQuery()).thenReturn(rsFrom, rsTo, rsDirect, rsInverse);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      stubContext(ctxMock);
      OBDal obDal = mock(OBDal.class);
      when(obDal.getConnection()).thenReturn(conn);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse response = NeoExchangeRateService.handleValidateExchangeRate(request);

      assertEquals(200, response.getHttpStatus());
      assertFalse(response.getBody().getBoolean("hasRate"));
    }
  }

  @Test
  public void testHandleValidateSqlExceptionReturns500() throws Exception {
    HttpServletRequest request = requestWith("USD", "EUR", "2026-01-15");

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenThrow(new SQLException("boom"));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      stubContext(ctxMock);
      OBDal obDal = mock(OBDal.class);
      when(obDal.getConnection()).thenReturn(conn);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse response = NeoExchangeRateService.handleValidateExchangeRate(request);
      assertEquals(500, response.getHttpStatus());
    }
  }

  // ---------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------

  private static HttpServletRequest requestWith(String from, String to, String date) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("fromCurrency")).thenReturn(from);
    when(request.getParameter("toCurrency")).thenReturn(to);
    when(request.getParameter("date")).thenReturn(date);
    return request;
  }

  private static void stubContext(MockedStatic<OBContext> ctxMock) {
    OBContext obCtx = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");
    when(obCtx.getCurrentClient()).thenReturn(client);
    when(obCtx.getCurrentOrganization()).thenReturn(org);
    ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
  }
}
