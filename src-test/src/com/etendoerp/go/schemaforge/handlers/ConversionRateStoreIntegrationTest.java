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
package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

/**
 * Real-PostgreSQL proof that {@link ConversionRateStore} reads the System ({@code 0}) rows — where
 * the rate downloader writes — next to the client's own, the client's winning (ETP-6365):
 * {@code currentRates}, {@code currenciesWithRates} and {@code latestSuccessfulDownload}.
 *
 * <p>Which rows a query admits and how it orders them is SQL, so only a real query shows it.
 *
 * <p><b>No data is left behind.</b> Rows are inserted with plain JDBC on the request connection
 * and nothing commits: {@link #rollbackChanges()} rolls the transaction back. The pair (ALL and AFA)
 * has no rate in the sample data, so existing rows cannot interfere.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.ConversionRateStore
 */
public class ConversionRateStoreIntegrationTest extends OBBaseTest {

  private static final String ORG_CURRENCY = "271"; // ALL
  private static final String OTHER = "195"; // AFA
  private static final String SYSTEM = "0";

  private final ConversionRateStore store = new ConversionRateStore();
  private Connection conn;

  @Before
  public void openConnection() {
    setTestUserContext();
    conn = OBDal.getInstance().getConnection();
  }

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  /** A System rate alone is the client's current rate and makes the currency one with rates. */
  @Test
  public void aSystemRateIsReadAsTheClientsOwn() throws SQLException {
    insertRate(SYSTEM, 1, "2.00");

    List<ConversionRateStore.CurrentRate> current = currentRatesOfOther();
    assertEquals(1, current.size());
    assertEquals(0, new BigDecimal("2.00").compareTo(current.get(0).rate()));
    assertTrue(store.currenciesWithRates(TEST_CLIENT_ID, ORG_CURRENCY).contains(OTHER));
  }

  /** The client's own range wins over System's, even when System's started later. */
  @Test
  public void theClientsRateWinsOverTheSystemOne() throws SQLException {
    insertRate(SYSTEM, 1, "2.00");
    insertRate(TEST_CLIENT_ID, 2, "1.50");

    List<ConversionRateStore.CurrentRate> current = currentRatesOfOther();
    assertEquals(1, current.size());
    assertEquals(0, new BigDecimal("1.50").compareTo(current.get(0).rate()));
  }

  /** A successful download logged by System counts for the client; a failed run never does. */
  @Test
  public void theLatestDownloadIncludesSystemRuns() throws SQLException {
    assumeTrue("com.smf.currency.conversionrate is not installed",
        store.hasTable(ConversionRateStore.TABLE_SYNC_LOG));
    Timestamp success = Timestamp.valueOf("2999-01-01 00:00:00");
    insertSyncLog(SYSTEM, success, "SUCCESS");
    insertSyncLog(TEST_CLIENT_ID, Timestamp.valueOf("2999-06-01 00:00:00"), "FAILED");

    assertEquals(success.getTime(), store.latestSuccessfulDownload(TEST_CLIENT_ID).getTime());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private List<ConversionRateStore.CurrentRate> currentRatesOfOther() {
    return store.currentRates(TEST_CLIENT_ID, ORG_CURRENCY).stream()
        .filter(rate -> OTHER.equals(rate.currency().id()))
        .collect(Collectors.toList());
  }

  /** An open spot range ALL → AFA of organization {@code 0}, started {@code daysAgo} days ago. */
  private void insertRate(String clientId, int daysAgo, String rate) throws SQLException {
    String sql = "INSERT INTO c_conversion_rate (c_conversion_rate_id, ad_client_id, ad_org_id,"
        + " isactive, created, createdby, updated, updatedby, c_currency_id, c_currency_id_to,"
        + " validfrom, validto, conversionratetype, multiplyrate, dividerate)"
        + " VALUES (get_uuid(), ?, '0', 'Y', NOW(), '100', NOW(), '100', ?, ?,"
        + " now() - (? * interval '1 day'), NULL, 'S', ?, ?)";
    BigDecimal multiply = new BigDecimal(rate);
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, clientId);
      ps.setString(2, ORG_CURRENCY);
      ps.setString(3, OTHER);
      ps.setInt(4, daysAgo);
      ps.setBigDecimal(5, multiply);
      ps.setBigDecimal(6, BigDecimal.ONE.divide(multiply, 12, RoundingMode.HALF_UP));
      ps.executeUpdate();
    }
  }

  private void insertSyncLog(String clientId, Timestamp syncDate, String status)
      throws SQLException {
    String sql = "INSERT INTO smfcr_sync_log (smfcr_sync_log_id, ad_client_id, ad_org_id,"
        + " isactive, created, createdby, updated, updatedby, sync_date, pairs_updated,"
        + " pairs_failed, status)"
        + " VALUES (get_uuid(), ?, '0', 'Y', NOW(), '100', NOW(), '100', ?, 1, 0, ?)";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, clientId);
      ps.setTimestamp(2, syncDate);
      ps.setString(3, status);
      ps.executeUpdate();
    }
  }
}
