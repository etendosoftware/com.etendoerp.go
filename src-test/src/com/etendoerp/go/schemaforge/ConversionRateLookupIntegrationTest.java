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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

/**
 * Real-PostgreSQL proof of the day rule of {@link ConversionRateLookup#multiplyRate}: for a
 * document of day {@code D}, among the active rows whose range touches the day
 * ({@code ValidFrom < D+1 00:00}, {@code ValidTo >= D 00:00}), the client's row wins over System's,
 * then the latest {@code ValidFrom} — "the last range of the day".
 *
 * <p>The predicate and the ordering are SQL, so only a real query shows that a range starting at
 * 09:00 beats the one it closed at 08:59, and that a System row starting later in the day does not
 * beat the client's. The unit tests of the callers only see the SQL text.
 *
 * <p><b>No data is left behind.</b> The rows are inserted with plain JDBC on the request
 * connection and nothing commits: {@link #rollbackChanges()} rolls the transaction back. They use
 * a pair and a day (GBP to USD in 1901) no sample data touches, so existing rates cannot interfere.
 *
 * @covers com.etendoerp.go.schemaforge.ConversionRateLookup
 */
public class ConversionRateLookupIntegrationTest extends OBBaseTest {

  private static final String GBP = "114";
  private static final String USD = "100";
  private static final String SYSTEM_CLIENT = "0";
  private static final List<String> ORG_0 = List.of("0");
  private static final LocalDate DAY = LocalDate.of(1901, 3, 15);

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

  /** A rate entered at 09:00 closes the previous range at 08:59: the 09:00 range is the day's. */
  @Test
  public void twoRangesOnTheSameDayAnswerTheLastOne() throws SQLException {
    insertRate(TEST_CLIENT_ID, at(DAY, 0, 0), at(DAY, 8, 59), "1.10");
    insertRate(TEST_CLIENT_ID, at(DAY, 9, 0), null, "1.20");

    assertRate("1.20", lookup(DAY));
  }

  /**
   * A day with no change of rate takes the range that spans it; a range that starts the next day
   * (even at 00:00) is not that day's, and a day before every range has no rate.
   */
  @Test
  public void aDayWithoutChangesAnswersTheRangeValidThatDay() throws SQLException {
    insertRate(TEST_CLIENT_ID, at(DAY.minusDays(10), 0, 0), at(DAY.plusDays(4), 23, 59), "1.30");
    insertRate(TEST_CLIENT_ID, at(DAY.plusDays(1), 0, 0), null, "1.40");

    assertRate("1.30", lookup(DAY));
    assertRate("1.40", lookup(DAY.plusDays(1)));
    assertNull(lookup(DAY.minusDays(11)));
  }

  /** The client's own range wins over System's, even when System's starts later in the day. */
  @Test
  public void theClientRowWinsOverTheSystemRow() throws SQLException {
    insertRate(TEST_CLIENT_ID, at(DAY, 0, 0), null, "1.50");
    insertRate(SYSTEM_CLIENT, at(DAY, 9, 0), null, "2.00");

    assertRate("1.50", lookup(DAY));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private BigDecimal lookup(LocalDate day) throws SQLException {
    return ConversionRateLookup.multiplyRate(conn, GBP, USD, TEST_CLIENT_ID, ORG_0, day);
  }

  private static void assertRate(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual));
  }

  private static Timestamp at(LocalDate day, int hour, int minute) {
    return Timestamp.valueOf(LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)));
  }

  /** Inserts an active spot rate of organization {@code 0}; an empty end is stored open. */
  private void insertRate(String clientId, Timestamp validFrom, Timestamp validTo, String rate)
      throws SQLException {
    String sql = "INSERT INTO c_conversion_rate (c_conversion_rate_id, ad_client_id, ad_org_id,"
        + " isactive, created, createdby, updated, updatedby, c_currency_id, c_currency_id_to,"
        + " validfrom, validto, conversionratetype, multiplyrate, dividerate)"
        + " VALUES (get_uuid(), ?, '0', 'Y', NOW(), '100', NOW(), '100', ?, ?, ?, ?, 'S', ?, ?)";
    BigDecimal multiply = new BigDecimal(rate);
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, clientId);
      ps.setString(2, GBP);
      ps.setString(3, USD);
      ps.setTimestamp(4, validFrom);
      ps.setTimestamp(5, validTo);
      ps.setBigDecimal(6, multiply);
      ps.setBigDecimal(7, BigDecimal.ONE.divide(multiply, 12, RoundingMode.HALF_UP));
      ps.executeUpdate();
    }
  }
}
