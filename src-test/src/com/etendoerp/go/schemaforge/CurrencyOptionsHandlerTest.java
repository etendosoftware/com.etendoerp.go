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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.junit.Test;

/**
 * Pure unit tests for {@link CurrencyOptionsHandler}: the Connection-based private logic that
 * builds currency options, queries direct/inverse rates, and resolves ISO codes. Reached via
 * reflection (mirroring {@code SelectorQueryExecutorTest}). All JDBC objects are Mockito mocks; the
 * {@code handle}/{@code afterHandle} OBContext/OBDal paths are out of scope (DB-dependent).
 */
public class CurrencyOptionsHandlerTest {

  // ---------------------------------------------------------------
  // Reflection helpers
  // ---------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Map<String, double[]> invokeQueryDirectRates(CurrencyOptionsHandler handler,
      Connection conn, String orgCurrencyId, String clientId, String orgId, java.sql.Date sqlDate)
      throws Exception {
    Method m = CurrencyOptionsHandler.class.getDeclaredMethod("queryDirectRates", Connection.class,
        String.class, String.class, String.class, java.sql.Date.class);
    m.setAccessible(true);
    return (Map<String, double[]>) m.invoke(handler, conn, orgCurrencyId, clientId, orgId, sqlDate);
  }

  private static void invokeMergeInverseRates(CurrencyOptionsHandler handler, Connection conn,
      String orgCurrencyId, Map<String, double[]> rateMap, String clientId, String orgId,
      java.sql.Date sqlDate) throws Exception {
    Method m = CurrencyOptionsHandler.class.getDeclaredMethod("mergeInverseRates", Connection.class,
        String.class, Map.class, String.class, String.class, java.sql.Date.class);
    m.setAccessible(true);
    m.invoke(handler, conn, orgCurrencyId, rateMap, clientId, orgId, sqlDate);
  }

  private static String invokeResolveIsoCode(CurrencyOptionsHandler handler, Connection conn,
      String currencyId) throws Exception {
    Method m = CurrencyOptionsHandler.class.getDeclaredMethod("resolveIsoCode", Connection.class,
        String.class);
    m.setAccessible(true);
    return (String) m.invoke(handler, conn, currencyId);
  }

  private static JSONArray invokeBuildCurrencyOptions(CurrencyOptionsHandler handler,
      String orgCurrencyId, String orgId, String clientId, java.time.LocalDate orderDate)
      throws Exception {
    Method m = CurrencyOptionsHandler.class.getDeclaredMethod("buildCurrencyOptions", String.class,
        String.class, String.class, java.time.LocalDate.class);
    m.setAccessible(true);
    return (JSONArray) m.invoke(handler, orgCurrencyId, orgId, clientId, orderDate);
  }

  private static PreparedStatement wirePreparedStatement(Connection conn, ResultSet rs)
      throws SQLException {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    return ps;
  }

  private static final java.sql.Date DATE = java.sql.Date.valueOf("2026-01-15");

  // ---------------------------------------------------------------
  // queryDirectRates
  // ---------------------------------------------------------------

  @Test
  public void testQueryDirectRatesBuildsMapFromResultSet() throws Exception {
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    Connection conn = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    wirePreparedStatement(conn, rs);

    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString("cid")).thenReturn("EUR-ID", "GBP-ID");
    when(rs.getDouble("multiplyrate")).thenReturn(1.16, 0.85);

    Map<String, double[]> result = invokeQueryDirectRates(handler, conn, "USD-ID", "client-1", "org-1", DATE);

    assertEquals(2, result.size());
    assertEquals(1.16, result.get("EUR-ID")[0], 0.0001);
    assertEquals(0.85, result.get("GBP-ID")[0], 0.0001);
  }

  @Test
  public void testQueryDirectRatesEmptyResultSetReturnsEmptyMap() throws Exception {
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    Connection conn = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    wirePreparedStatement(conn, rs);
    when(rs.next()).thenReturn(false);

    Map<String, double[]> result = invokeQueryDirectRates(handler, conn, "USD-ID", "client-1", "org-1", DATE);
    assertTrue(result.isEmpty());
  }

  // ---------------------------------------------------------------
  // mergeInverseRates
  // ---------------------------------------------------------------

  @Test
  public void testMergeInverseRatesAddsInvertedRateForNewCurrency() throws Exception {
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    Connection conn = mock(Connection.class);

    // First query: inverse rates → one new currency JPY-ID with rate 2.0 → inverted 0.5
    PreparedStatement psInverse = mock(PreparedStatement.class);
    ResultSet rsInverse = mock(ResultSet.class);
    when(rsInverse.next()).thenReturn(true, false);
    when(rsInverse.getString("cid")).thenReturn("JPY-ID");
    when(rsInverse.getDouble("inv_rate")).thenReturn(2.0);
    when(psInverse.executeQuery()).thenReturn(rsInverse);

    // Second query: ISO resolution for inverse currencies → returns JPY-ID
    PreparedStatement psIso = mock(PreparedStatement.class);
    ResultSet rsIso = mock(ResultSet.class);
    when(rsIso.next()).thenReturn(true, false);
    when(rsIso.getString(1)).thenReturn("JPY-ID");
    when(psIso.executeQuery()).thenReturn(rsIso);

    Array sqlArray = mock(Array.class);
    when(conn.createArrayOf(anyString(), any(Object[].class))).thenReturn(sqlArray);
    when(conn.prepareStatement(anyString())).thenReturn(psInverse, psIso);

    Map<String, double[]> rateMap = new LinkedHashMap<>();
    invokeMergeInverseRates(handler, conn, "USD-ID", rateMap, "client-1", "org-1", DATE);

    assertTrue(rateMap.containsKey("JPY-ID"));
    assertEquals(0.5, rateMap.get("JPY-ID")[0], 0.0001);
  }

  @Test
  public void testMergeInverseRatesSkipsCurrencyAlreadyInRateMap() throws Exception {
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    Connection conn = mock(Connection.class);

    ResultSet rsInverse = mock(ResultSet.class);
    when(rsInverse.next()).thenReturn(true, false);
    when(rsInverse.getString("cid")).thenReturn("EUR-ID");
    when(rsInverse.getDouble("inv_rate")).thenReturn(2.0);
    PreparedStatement psInverse = mock(PreparedStatement.class);
    when(psInverse.executeQuery()).thenReturn(rsInverse);
    when(conn.prepareStatement(anyString())).thenReturn(psInverse);

    // EUR-ID already present from direct rates → inverse skipped → no ISO resolution query needed
    Map<String, double[]> rateMap = new LinkedHashMap<>();
    rateMap.put("EUR-ID", new double[]{ 1.16 });

    invokeMergeInverseRates(handler, conn, "USD-ID", rateMap, "client-1", "org-1", DATE);

    // Existing direct rate preserved, not overwritten by the inverse value
    assertEquals(1.16, rateMap.get("EUR-ID")[0], 0.0001);
    assertEquals(1, rateMap.size());
  }

  @Test
  public void testMergeInverseRatesZeroRateYieldsZero() throws Exception {
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    Connection conn = mock(Connection.class);

    PreparedStatement psInverse = mock(PreparedStatement.class);
    ResultSet rsInverse = mock(ResultSet.class);
    when(rsInverse.next()).thenReturn(true, false);
    when(rsInverse.getString("cid")).thenReturn("ZZZ-ID");
    when(rsInverse.getDouble("inv_rate")).thenReturn(0.0);
    when(psInverse.executeQuery()).thenReturn(rsInverse);

    PreparedStatement psIso = mock(PreparedStatement.class);
    ResultSet rsIso = mock(ResultSet.class);
    when(rsIso.next()).thenReturn(true, false);
    when(rsIso.getString(1)).thenReturn("ZZZ-ID");
    when(psIso.executeQuery()).thenReturn(rsIso);

    Array sqlArray = mock(Array.class);
    when(conn.createArrayOf(anyString(), any(Object[].class))).thenReturn(sqlArray);
    when(conn.prepareStatement(anyString())).thenReturn(psInverse, psIso);

    Map<String, double[]> rateMap = new LinkedHashMap<>();
    invokeMergeInverseRates(handler, conn, "USD-ID", rateMap, "client-1", "org-1", DATE);

    assertEquals(0.0, rateMap.get("ZZZ-ID")[0], 0.0001);
  }

  // ---------------------------------------------------------------
  // resolveIsoCode
  // ---------------------------------------------------------------

  @Test
  public void testResolveIsoCodeReturnsCodeWhenFound() throws Exception {
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    Connection conn = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    wirePreparedStatement(conn, rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn("EUR");

    assertEquals("EUR", invokeResolveIsoCode(handler, conn, "EUR-ID"));
  }

  @Test
  public void testResolveIsoCodeReturnsNullWhenNotFound() throws Exception {
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    Connection conn = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    wirePreparedStatement(conn, rs);
    when(rs.next()).thenReturn(false);

    assertNull(invokeResolveIsoCode(handler, conn, "EUR-ID"));
  }

  @Test
  public void testResolveIsoCodeSwallowsExceptionAndReturnsNull() throws Exception {
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenThrow(new SQLException("boom"));

    assertNull(invokeResolveIsoCode(handler, conn, "EUR-ID"));
  }

  // ---------------------------------------------------------------
  // buildCurrencyOptions (full flow via mocked OBDal connection)
  // ---------------------------------------------------------------

  @Test
  public void testBuildCurrencyOptionsPrependsOrgCurrencyWithRateOne() throws Exception {
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    Connection conn = mock(Connection.class);

    // direct rates query → EUR-ID @ 1.16
    PreparedStatement psDirect = mock(PreparedStatement.class);
    ResultSet rsDirect = mock(ResultSet.class);
    when(rsDirect.next()).thenReturn(true, false);
    when(rsDirect.getString("cid")).thenReturn("EUR-ID");
    when(rsDirect.getDouble("multiplyrate")).thenReturn(1.16);
    when(psDirect.executeQuery()).thenReturn(rsDirect);

    // inverse rates query → empty (no new currencies)
    PreparedStatement psInverse = mock(PreparedStatement.class);
    ResultSet rsInverse = mock(ResultSet.class);
    when(rsInverse.next()).thenReturn(false);
    when(psInverse.executeQuery()).thenReturn(rsInverse);

    // resolveIsoCode for org currency → "USD"
    PreparedStatement psIsoOrg = mock(PreparedStatement.class);
    ResultSet rsIsoOrg = mock(ResultSet.class);
    when(rsIsoOrg.next()).thenReturn(true);
    when(rsIsoOrg.getString(1)).thenReturn("USD");
    when(psIsoOrg.executeQuery()).thenReturn(rsIsoOrg);

    // resolveIsoCode for EUR-ID → "EUR"
    PreparedStatement psIsoEur = mock(PreparedStatement.class);
    ResultSet rsIsoEur = mock(ResultSet.class);
    when(rsIsoEur.next()).thenReturn(true);
    when(rsIsoEur.getString(1)).thenReturn("EUR");
    when(psIsoEur.executeQuery()).thenReturn(rsIsoEur);

    when(conn.prepareStatement(anyString())).thenReturn(psDirect, psInverse, psIsoOrg, psIsoEur);

    try (org.mockito.MockedStatic<org.openbravo.dal.service.OBDal> obDalMock =
             org.mockito.Mockito.mockStatic(org.openbravo.dal.service.OBDal.class)) {
      org.openbravo.dal.service.OBDal obDal = mock(org.openbravo.dal.service.OBDal.class);
      when(obDal.getConnection()).thenReturn(conn);
      obDalMock.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(obDal);

      JSONArray result = invokeBuildCurrencyOptions(handler, "USD-ID", "org-1", "client-1",
          java.time.LocalDate.of(2026, 1, 15));

      assertEquals(2, result.length());
      // org currency first
      assertEquals("USD-ID", result.getJSONObject(0).getString("id"));
      assertEquals("USD", result.getJSONObject(0).getString("isoCode"));
      assertEquals(1.0, result.getJSONObject(0).getDouble("rate"), 0.0001);
      // then EUR
      assertEquals("EUR-ID", result.getJSONObject(1).getString("id"));
      assertEquals("EUR", result.getJSONObject(1).getString("isoCode"));
      assertEquals(1.16, result.getJSONObject(1).getDouble("rate"), 0.0001);
    }
  }

  // ---------------------------------------------------------------
  // afterHandle
  // ---------------------------------------------------------------

  @Test
  public void testAfterHandleReturnsNull() {
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    assertNull(handler.afterHandle(null));
  }
}
