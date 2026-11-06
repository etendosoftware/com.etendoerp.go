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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Date;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.database.ExternalConnectionPool;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyException;
import org.openbravo.erpCommon.utility.PropertyNotFoundException;

import com.etendoerp.go.usageevents.LogCapture;

/**
 * The retention purge of raw {@code publicapi.request} rows: which rows the delete can reach, the
 * cutoff ({@code max(preference, longest settling window + 1)} days), the preference fallbacks and
 * the guarantee that a failure returns 0 and never reaches the aggregation run.
 *
 * <p>New class: {@code UsageAggregationProcessTest} only asserts that the process calls the purge, and
 * {@code UsageSettingsTest} is about the settling-window preference; neither fits this logic.</p>
 *
 * @covers com.etendoerp.go.usage.PublicApiUsageRetention
 */
class PublicApiUsageRetentionTest {

  private static final Date NOW = new Date(1_790_000_000_000L);

  private MockedStatic<ExternalConnectionPool> poolStatic;
  private MockedStatic<Preferences> preferences;
  private MockedStatic<UsageSettings> settings;
  private final ExternalConnectionPool pool = mock(ExternalConnectionPool.class);
  private final Connection connection = mock(Connection.class);
  private final PreparedStatement statement = mock(PreparedStatement.class);

  @BeforeEach
  void setUp() throws SQLException {
    poolStatic = mockStatic(ExternalConnectionPool.class);
    poolStatic.when(ExternalConnectionPool::getInstance).thenReturn(pool);
    when(pool.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    preferences = mockStatic(Preferences.class);
    settings = mockStatic(UsageSettings.class);
    settings.when(UsageSettings::getMaxSettlingWindowDays).thenReturn(5);
    givenPreference("90");
  }

  @AfterEach
  void tearDown() {
    settings.close();
    preferences.close();
    poolStatic.close();
  }

  private void givenPreference(String value) {
    try {
      preferences.when(() -> Preferences.getPreferenceValue(anyString(), anyBoolean(), anyString(),
          anyString(), any(), any(), any())).thenReturn(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Date daysBeforeMidnight(Date now, int days) {
    Calendar cal = Calendar.getInstance();
    cal.setTime(UsageDayRange.startOfDay(now));
    cal.add(Calendar.DAY_OF_MONTH, -days);
    return cal.getTime();
  }

  // ── cutoff ─────────────────────────────────────────────────────────────

  @Test
  void theCutoffIsLocalMidnightOfTheRetentionDaysAgo() {
    assertEquals(daysBeforeMidnight(NOW, 90), PublicApiUsageRetention.cutoff(NOW, 90, 5));
  }

  @Test
  void theCutoffNeverGoesBelowTheLongestSettlingWindowPlusOne() {
    assertEquals(daysBeforeMidnight(NOW, 8), PublicApiUsageRetention.cutoff(NOW, 3, 7));
    assertEquals(daysBeforeMidnight(NOW, 7), PublicApiUsageRetention.cutoff(NOW, 7, 6));
    assertEquals(daysBeforeMidnight(NOW, 30), PublicApiUsageRetention.cutoff(NOW, 30, 6));
  }

  // ── preference ─────────────────────────────────────────────────────────

  @Test
  void aPositiveNumberIsTheConfiguredRetention() {
    assertEquals(30, PublicApiUsageRetention.parse("30"));
    assertEquals(45, PublicApiUsageRetention.parse(" 45 "));
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "  ", "abc", "0", "-5", "3.5", "9999999999" })
  void aBlankBadOrNotPositivePreferenceFallsBackTo90(String raw) {
    assertEquals(90, PublicApiUsageRetention.parse(raw));
  }

  @Test
  void anAbsentPreferenceFallsBackTo90() {
    assertEquals(90, PublicApiUsageRetention.parse(null));
  }

  @Test
  void aBadPreferenceIsWarned() {
    try (LogCapture log = LogCapture.of(PublicApiUsageRetention.class)) {
      PublicApiUsageRetention.parse("soon");

      assertEquals(1, log.messages(Level.WARN).size());
      assertTrue(log.messages(Level.WARN).get(0).contains("ETGO_PublicApiUsageRetentionDays"));
    }
  }

  @Test
  void theConfiguredDaysComeFromTheSystemLevelListPreference() {
    givenPreference("21");

    assertEquals(21, PublicApiUsageRetention.configuredDays());
    preferences.verify(() -> Preferences.getPreferenceValue("ETGO_PublicApiUsageRetentionDays", true,
        "0", "0", null, null, null));
  }

  @Test
  void aMissingPreferenceKeepsTheDefault() {
    preferences.when(() -> Preferences.getPreferenceValue(anyString(), anyBoolean(), anyString(),
        anyString(), any(), any(), any())).thenThrow(new PropertyNotFoundException());

    assertEquals(90, PublicApiUsageRetention.configuredDays());
  }

  @Test
  void aConflictingOrFailingPreferenceKeepsTheDefault() {
    preferences.when(() -> Preferences.getPreferenceValue(anyString(), anyBoolean(), anyString(),
        anyString(), any(), any(), any())).thenThrow(new PropertyException("conflict"));
    assertEquals(90, PublicApiUsageRetention.configuredDays());

    preferences.when(() -> Preferences.getPreferenceValue(anyString(), anyBoolean(), anyString(),
        anyString(), any(), any(), any())).thenThrow(new OBException("boom"));
    assertEquals(90, PublicApiUsageRetention.configuredDays());
  }

  // ── purge ──────────────────────────────────────────────────────────────

  @Test
  void theDeleteOnlyReachesRowsOfThePublicApiType() {
    String sql = PublicApiUsageRetention.PURGE_SQL.toLowerCase();

    assertTrue(sql.startsWith("delete from etgo_usage_event where event_type = ?"), sql);
    assertTrue(sql.contains("occurred_at < ?"), sql);
    assertFalse(sql.contains("etgo_usage_daily"), sql);
  }

  @Test
  void purgeDeletesRowsBeforeTheCutoffOnItsOwnConnectionAndCommits() throws SQLException {
    when(statement.executeUpdate()).thenReturn(42);

    int deleted = PublicApiUsageRetention.purge(NOW);

    assertEquals(42, deleted);
    InOrder order = inOrder(connection, statement);
    order.verify(connection).setAutoCommit(false);
    order.verify(statement).setString(1, "publicapi.request");
    ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);
    order.verify(statement).setTimestamp(org.mockito.ArgumentMatchers.eq(2), cutoff.capture());
    order.verify(statement).executeUpdate();
    order.verify(connection).commit();
    order.verify(connection).setAutoCommit(true);
    assertEquals(daysBeforeMidnight(NOW, 90).getTime(), cutoff.getValue().getTime());
    verify(connection).close();
  }

  @Test
  void purgeUsesTheLargerOfThePreferenceAndTheSettlingWindowPlusOne() throws SQLException {
    givenPreference("2");
    settings.when(UsageSettings::getMaxSettlingWindowDays).thenReturn(9);

    PublicApiUsageRetention.purge(NOW);

    ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);
    verify(statement).setTimestamp(org.mockito.ArgumentMatchers.eq(2), cutoff.capture());
    assertEquals(daysBeforeMidnight(NOW, 10).getTime(), cutoff.getValue().getTime());
  }

  @Test
  void aBadPreferenceMakesPurgeUseTheDefault() throws SQLException {
    givenPreference("0");

    PublicApiUsageRetention.purge(NOW);

    ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);
    verify(statement).setTimestamp(org.mockito.ArgumentMatchers.eq(2), cutoff.capture());
    assertEquals(daysBeforeMidnight(NOW, 90).getTime(), cutoff.getValue().getTime());
  }

  @Test
  void aFailedDeleteRollsBackReturnsZeroAndWarnsWithoutThrowing() throws SQLException {
    when(statement.executeUpdate()).thenThrow(new SQLException("deadlock"));

    try (LogCapture log = LogCapture.of(PublicApiUsageRetention.class)) {
      assertEquals(0, PublicApiUsageRetention.purge(NOW));

      assertEquals(1, log.messages(Level.WARN).size());
    }
    verify(connection).rollback();
    verify(connection, never()).commit();
    verify(connection).setAutoCommit(true);
    verify(connection).close();
  }

  @Test
  void noPoolOrNoConnectionReturnsZeroWithoutThrowing() throws SQLException {
    poolStatic.when(ExternalConnectionPool::getInstance).thenReturn(null);
    assertEquals(0, PublicApiUsageRetention.purge(NOW));

    poolStatic.when(ExternalConnectionPool::getInstance).thenReturn(pool);
    doThrow(new IllegalStateException("exhausted")).when(pool).getConnection();
    assertEquals(0, PublicApiUsageRetention.purge(NOW));
  }

  @Test
  void aFailureReadingTheSettlingWindowReturnsZeroWithoutThrowing() throws SQLException {
    settings.when(UsageSettings::getMaxSettlingWindowDays)
        .thenThrow(new IllegalStateException("no DAL"));

    assertEquals(0, PublicApiUsageRetention.purge(NOW));
    verify(statement, never()).executeUpdate();
    verify(connection, never()).prepareStatement(any());
  }
}
