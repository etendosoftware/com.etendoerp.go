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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Pure unit tests for {@link CurrencyOptionsHandler}: the Connection-based private logic that
 * builds currency options, queries direct/inverse rates, and resolves ISO codes. Reached via
 * reflection (mirroring {@code SelectorQueryExecutorTest}). All JDBC objects are Mockito mocks.
 * {@code handle} is covered for how it scopes the rates (ETP-5840: rate type from the context
 * attribute, {@code date} and {@code organization} parameters, 400 on a bad date), with
 * OBContext/OBDal/OBCurrencyUtils statically mocked.
 *
 * @covers com.etendoerp.go.schemaforge.CurrencyOptionsHandler
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
  public void testQueryDirectRatesSqlIncludesSystemClientRatesWithTenantPriority() throws Exception {
    // ETP-4474 regression: queryDirectRates must include both the tenant's rates and the shared
    // system ('0') rates, ordered ad_client_id ASC so the '0' row is written first and the tenant
    // row overwrites it in the map (last-write-wins → tenant wins).
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    Connection conn = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    wirePreparedStatement(conn, rs);
    when(rs.next()).thenReturn(false);

    invokeQueryDirectRates(handler, conn, "USD-ID", "client-1", "org-1", DATE);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(conn).prepareStatement(sqlCaptor.capture());
    String sql = sqlCaptor.getValue();
    assertTrue(sql.contains("cr.ad_client_id IN ('0', ?)"));
    assertTrue(sql.contains("ORDER BY c.iso_code, cr.ad_client_id ASC, cr.validfrom ASC"));
  }

  @Test
  public void testMergeInverseRatesSqlIncludesSystemClientRatesWithTenantPriority() throws Exception {
    // ETP-4474 regression: the inverse-rate query mirrors the same system+tenant scoping and ASC
    // ordering as the direct query.
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    Connection conn = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    wirePreparedStatement(conn, rs);
    when(rs.next()).thenReturn(false); // no inverse rows → no ISO resolution query issued

    invokeMergeInverseRates(handler, conn, "USD-ID", new LinkedHashMap<>(), "client-1", "org-1", DATE);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(conn).prepareStatement(sqlCaptor.capture());
    String sql = sqlCaptor.getValue();
    assertTrue(sql.contains("cr.ad_client_id IN ('0', ?)"));
    assertTrue(sql.contains("ORDER BY c.iso_code, cr.ad_client_id ASC, cr.validfrom ASC"));
  }

  @Test
  public void testQueryDirectRatesTenantRowOverridesSystemRowForSameCurrency() throws Exception {
    // ETP-4474 regression: with ad_client_id ASC the '0' (system) row for a currency is written
    // first and the tenant row for the SAME currency overwrites it — the map keeps the tenant value.
    CurrencyOptionsHandler handler = new CurrencyOptionsHandler();
    Connection conn = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    wirePreparedStatement(conn, rs);

    // Two rows, same currency id: system rate 1.10 first, tenant rate 1.16 second (ASC ordering)
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString("cid")).thenReturn("EUR-ID", "EUR-ID");
    when(rs.getDouble("multiplyrate")).thenReturn(1.10, 1.16);

    Map<String, double[]> result = invokeQueryDirectRates(handler, conn, "USD-ID", "client-1", "org-1", DATE);

    // Last write (tenant) wins, single entry for the currency
    assertEquals(1, result.size());
    assertEquals(1.16, result.get("EUR-ID")[0], 0.0001);
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

  /**
   * ETP-5840: the org currency comes first ({@code rate 1}, {@code isOrgCurrency: true}), then EVERY
   * active currency of the client or system by ISO code — one with a rate for the date carries it,
   * one without is still listed, with {@code rate: null}, so it can be picked.
   */
  @Test
  public void testBuildCurrencyOptionsListsEveryCurrencyWithNullRateWhenMissing() throws Exception {
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

    // selectable currencies (by ISO code) → EUR (rated), GBP (no rate), USD (the org's, skipped)
    PreparedStatement psCurrencies = mock(PreparedStatement.class);
    ResultSet rsCurrencies = mock(ResultSet.class);
    when(rsCurrencies.next()).thenReturn(true, true, true, false);
    when(rsCurrencies.getString(1)).thenReturn("EUR-ID", "GBP-ID", "USD-ID");
    when(rsCurrencies.getString(2)).thenReturn("EUR", "GBP", "USD");
    when(psCurrencies.executeQuery()).thenReturn(rsCurrencies);

    when(conn.prepareStatement(anyString())).thenReturn(psDirect, psInverse, psIsoOrg, psCurrencies);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      when(obDal.getConnection()).thenReturn(conn);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      JSONArray result = invokeBuildCurrencyOptions(handler, "USD-ID", "org-1", "client-1",
          java.time.LocalDate.of(2026, 1, 15));

      assertEquals(3, result.length());
      JSONObject org = result.getJSONObject(0);
      assertEquals("USD-ID", org.getString("id"));
      assertEquals("USD", org.getString("isoCode"));
      assertEquals(1.0, org.getDouble("rate"), 0.0001);
      assertTrue(org.getBoolean("isOrgCurrency"));
      JSONObject eur = result.getJSONObject(1);
      assertEquals("EUR-ID", eur.getString("id"));
      assertEquals(1.16, eur.getDouble("rate"), 0.0001);
      assertFalse(eur.getBoolean("isOrgCurrency"));
      JSONObject gbp = result.getJSONObject(2);
      assertEquals("GBP", gbp.getString("isoCode"));
      assertTrue(gbp.isNull("rate"));
      assertFalse(gbp.getBoolean("isOrgCurrency"));
      verify(psCurrencies).setString(1, "client-1");
    }
  }

  // ---------------------------------------------------------------
  // handle: which organization, client and date scope the rates (ETP-5840)
  // ---------------------------------------------------------------

  private static final String SESSION_ORG = "session-org";
  private static final String SESSION_CLIENT = "session-client";

  /** One handle() run: what it resolved is read back from the statics and the bound statements. */
  private static final class HandleRun {
    final OBDal dal = mock(OBDal.class);
    final OBContext obContext = mock(OBContext.class);
    final PreparedStatement ps = mock(PreparedStatement.class);

    HandleRun() throws SQLException {
      Organization sessionOrg = mock(Organization.class);
      when(sessionOrg.getId()).thenReturn(SESSION_ORG);
      Client sessionClient = mock(Client.class);
      when(sessionClient.getId()).thenReturn(SESSION_CLIENT);
      when(obContext.getCurrentOrganization()).thenReturn(sessionOrg);
      when(obContext.getCurrentClient()).thenReturn(sessionClient);
      when(obContext.getReadableOrganizations()).thenReturn(new String[] { SESSION_ORG, "org-2" });
      ResultSet empty = mock(ResultSet.class);
      when(empty.next()).thenReturn(false);
      when(ps.executeQuery()).thenReturn(empty);
      Connection conn = mock(Connection.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(dal.getConnection()).thenReturn(conn);
    }

    NeoResponse handle(NeoContext ctx) {
      try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
           MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
           MockedStatic<OBCurrencyUtils> currencies = Mockito.mockStatic(OBCurrencyUtils.class)) {
        obCtx.when(OBContext::getOBContext).thenReturn(obContext);
        obDal.when(OBDal::getInstance).thenReturn(dal);
        currencies.when(() -> OBCurrencyUtils.getOrgCurrency(anyString())).thenReturn("EUR-ID");
        NeoResponse response = new CurrencyOptionsHandler().handle(ctx);
        ArgumentCaptor<String> org = ArgumentCaptor.forClass(String.class);
        currencies.verify(() -> OBCurrencyUtils.getOrgCurrency(org.capture()), atMostOnce());
        resolvedOrg = org.getAllValues().isEmpty() ? null : org.getValue();
        return response;
      }
    }

    /** The organization whose currency handle() asked for, or {@code null} when it did not. */
    String resolvedOrg;
  }

  private static NeoContext currencyOptions(String recordId, Map<String, String> query,
      JSONObject body, DocumentCurrencyRate.RateType type) {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName("currencyOptions").recordId(recordId).queryParams(query).requestBody(body)
        .build();
    if (type != null) {
      ctx.setAttribute(DocumentCurrencyRate.ATTR_RATE_TYPE, type);
    }
    return ctx;
  }

  /**
   * Table-driven: the organization, client and date the rates are scoped by. A new record (or a
   * header that published no rate type) uses the {@code organization} parameter only when the role
   * can read it, else the session's, and the {@code date} parameter (query string or, from MCP, the
   * action body) else today. A saved document uses ITS organization and client and the reference
   * date its customization declares ({@code accountingDate} for an invoice), unless {@code date}
   * asks for another.
   */
  @Test
  public void testHandleScopesRatesByTheResolvedOrganizationClientAndDate() throws Exception {
    Organization docOrg = mock(Organization.class);
    when(docOrg.getId()).thenReturn("doc-org");
    Client docClient = mock(Client.class);
    when(docClient.getId()).thenReturn("doc-client");
    Invoice invoice = mock(Invoice.class);
    when(invoice.get("organization")).thenReturn(docOrg);
    when(invoice.get("client")).thenReturn(docClient);
    when(invoice.get(Invoice.PROPERTY_ACCOUNTINGDATE))
        .thenReturn(java.sql.Date.valueOf("2026-02-28"));
    when(invoice.get(Invoice.PROPERTY_INVOICEDATE)).thenReturn(java.sql.Date.valueOf("2026-02-01"));
    DocumentCurrencyRate.RateType invoiceType = SalesInvoiceHeaderHandler.RATE_TYPE;
    String today = java.time.LocalDate.now().toString();

    Object[][] cases = {
        // name, recordId, query, body, rate type, expected org, client, date
        { "new, readable org and date params", "new",
            Map.of("organization", "org-2", "date", "2026-03-31"), null, null,
            "org-2", SESSION_CLIENT, "2026-03-31" },
        { "new, unreadable org param falls back to session", "new",
            Map.of("organization", "other-org"), null, null,
            SESSION_ORG, SESSION_CLIENT, today },
        { "new, date in the action body (MCP)", null, null,
            new JSONObject().put("date", "2026-04-01"), invoiceType,
            SESSION_ORG, SESSION_CLIENT, "2026-04-01" },
        { "saved invoice, accounting date", "inv-1", null, null, invoiceType,
            "doc-org", "doc-client", "2026-02-28" },
        { "saved invoice, date param wins", "inv-1", Map.of("date", "2026-05-05"), null,
            invoiceType, "doc-org", "doc-client", "2026-05-05" },
        { "saved record without a rate type is scoped like a new one", "inv-1", null, null, null,
            SESSION_ORG, SESSION_CLIENT, today },
    };
    for (Object[] c : cases) {
      String name = (String) c[0];
      HandleRun run = new HandleRun();
      when(run.dal.get(Invoice.ENTITY_NAME, "inv-1")).thenReturn(invoice);
      @SuppressWarnings("unchecked")
      NeoResponse response = run.handle(currencyOptions((String) c[1], (Map<String, String>) c[2],
          (JSONObject) c[3], (DocumentCurrencyRate.RateType) c[4]));

      assertEquals(name, 200, response.getHttpStatus());
      JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
      assertTrue(name, data.getJSONObject(0).getBoolean("isOrgCurrency"));
      assertEquals(name, c[5], run.resolvedOrg);
      verify(run.ps, atLeastOnce()).setString(anyInt(), eq((String) c[6]));
      verify(run.ps, atLeastOnce()).setDate(anyInt(), eq(java.sql.Date.valueOf((String) c[7])));
    }
  }

  /** A {@code date} that is not {@code yyyy-MM-dd} is the caller's error (400); a missing document is 404. */
  @Test
  public void testHandleAnswers400ForABadDateAnd404ForAMissingDocument() throws Exception {
    HandleRun badDate = new HandleRun();
    assertEquals(400, badDate.handle(currencyOptions("new", Map.of("date", "31/03/2026"), null,
        null)).getHttpStatus());

    HandleRun missing = new HandleRun();
    assertEquals(404, missing.handle(currencyOptions("inv-404", null, null,
        SalesInvoiceHeaderHandler.RATE_TYPE)).getHttpStatus());
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
