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

package com.etendoerp.go.usageevents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.database.ExternalConnectionPool;
import org.openbravo.erpCommon.utility.SequenceIdData;

/**
 * Unit specs for the JDBC sink of {@link UsageEventWriter} (ETP-5462): {@code bind()} and
 * {@code insertBatch()} against a mocked {@link PreparedStatement} and pool — no database.
 *
 * <p>The binding rules exist so one odd value cannot fail an INSERT, and in a batch every other row
 * with it: text is clipped to the real column width, an id is either whole or NULL (a clipped id
 * would be a dangling FK), and a negative or oversized duration never reaches
 * {@code NUMERIC(10,0)}. The column indexes below follow {@link UsageEventWriter#INSERT_SQL}.</p>
 */
@SuppressWarnings("java:S2187") // test methods live in the @Nested classes
class UsageEventWriterJdbcTest {

  private static final String GENERATED_ID = "0123456789ABCDEF0123456789ABCDEF";
  private static final String CLIENT_ID = "A1B2C3D4E5F60718293A4B5C6D7E8F90";
  private static final String ORG_ID = "0A1B2C3D4E5F60718293A4B5C6D7E8F9";
  private static final String USER_ID = "F0E1D2C3B4A5968778695A4B3C2D1E0F";
  private static final String ROLE_ID = "11112222333344445555666677778888";
  private static final String TOO_LONG_ID = USER_ID + "X";

  // Parameter indexes of INSERT_SQL.
  private static final int P_ID = 1;
  private static final int P_CLIENT = 2;
  private static final int P_ORG = 3;
  private static final int P_CREATEDBY = 4;
  private static final int P_UPDATEDBY = 5;
  private static final int P_EVENT_TYPE = 6;
  private static final int P_SOURCE = 7;
  private static final int P_USER = 8;
  private static final int P_ROLE = 9;
  private static final int P_SESSION_KEY = 10;
  private static final int P_TARGET = 11;
  private static final int P_ACTION = 12;
  private static final int P_OUTCOME = 13;
  private static final int P_ERROR_CODE = 14;
  private static final int P_DURATION = 15;
  private static final int P_OCCURRED_AT = 16;
  private static final int P_APP_VERSION = 17;
  private static final int P_PROPERTIES = 18;

  private MockedStatic<SequenceIdData> sequence;

  @BeforeEach
  void mockUuid() {
    sequence = mockStatic(SequenceIdData.class);
    sequence.when(SequenceIdData::getUUID).thenReturn(GENERATED_ID);
  }

  @AfterEach
  void closeMocks() {
    sequence.close();
  }

  private static UsageEvent.Builder fullEvent() {
    return UsageEvent.builder()
        .clientId(CLIENT_ID).orgId(ORG_ID).userId(USER_ID).roleId(ROLE_ID)
        .eventType(UsageEventTypes.AI_SUPPORT_MESSAGE).source(UsageEvent.SOURCE_UI)
        .sessionKey("conv-1").target("sales-order").action("complete")
        .outcome(UsageEvent.OUTCOME_OK).errorCode("E42").durationMs(1234L)
        .occurredAt(Instant.parse("2026-09-23T10:00:00Z")).appVersion("3.1.0")
        .property("inputTokens", 1200);
  }

  private static UsageEvent withOccurredAt(UsageEvent e, Instant at) {
    return new UsageEvent(e.clientId(), e.orgId(), e.userId(), e.roleId(), e.eventType(),
        e.source(), e.sessionKey(), e.target(), e.action(), e.outcome(), e.errorCode(),
        e.durationMs(), at, e.appVersion(), e.properties());
  }

  private static UsageEvent withProperties(UsageEvent e, String properties) {
    return new UsageEvent(e.clientId(), e.orgId(), e.userId(), e.roleId(), e.eventType(),
        e.source(), e.sessionKey(), e.target(), e.action(), e.outcome(), e.errorCode(),
        e.durationMs(), e.occurredAt(), e.appVersion(), properties);
  }

  @Nested
  @DisplayName("bind")
  class Bind {

    private final PreparedStatement ps = mock(PreparedStatement.class);

    @Test
    void theSqlHasOnePlaceholderPerBoundParameter() {
      assertEquals(P_PROPERTIES, StringUtils.countMatches(UsageEventWriter.INSERT_SQL, '?'));
    }

    @Test
    void aCompleteEventBindsEveryColumnInOrder() throws SQLException {
      UsageEvent event = fullEvent().build();
      UsageEventWriter.bind(ps, event);

      verify(ps).setString(P_ID, GENERATED_ID);
      verify(ps).setString(P_CLIENT, CLIENT_ID);
      verify(ps).setString(P_ORG, ORG_ID);
      verify(ps).setString(P_CREATEDBY, USER_ID);
      verify(ps).setString(P_UPDATEDBY, USER_ID);
      verify(ps).setString(P_EVENT_TYPE, UsageEventTypes.AI_SUPPORT_MESSAGE);
      verify(ps).setString(P_SOURCE, UsageEvent.SOURCE_UI);
      verify(ps).setString(P_USER, USER_ID);
      verify(ps).setString(P_ROLE, ROLE_ID);
      verify(ps).setString(P_SESSION_KEY, "conv-1");
      verify(ps).setString(P_TARGET, "sales-order");
      verify(ps).setString(P_ACTION, "complete");
      verify(ps).setString(P_OUTCOME, UsageEvent.OUTCOME_OK);
      verify(ps).setString(P_ERROR_CODE, "E42");
      verify(ps).setLong(P_DURATION, 1234L);
      verify(ps).setTimestamp(P_OCCURRED_AT, Timestamp.from(event.occurredAt()));
      verify(ps).setString(P_APP_VERSION, "3.1.0");
      verify(ps).setString(P_PROPERTIES, "{\"inputTokens\":1200}");
      verify(ps, never()).setNull(anyInt(), anyInt());
    }

    @Test
    void textIsClippedToEachColumnWidth() throws SQLException {
      String huge = "y".repeat(500);
      UsageEvent event = fullEvent()
          .eventType("t".repeat(80)).source("s".repeat(40))
          .sessionKey(huge).target(huge).action(huge).outcome(huge).errorCode(huge)
          .appVersion(huge)
          .build();
      UsageEventWriter.bind(ps, event);

      verify(ps).setString(P_EVENT_TYPE, "t".repeat(UsageEventWriter.EVENT_TYPE_WIDTH));
      verify(ps).setString(P_SOURCE, "s".repeat(UsageEventWriter.SOURCE_WIDTH));
      assertClipped(P_SESSION_KEY, UsageEventWriter.SESSION_KEY_WIDTH);
      assertClipped(P_TARGET, UsageEventWriter.TARGET_WIDTH);
      assertClipped(P_ACTION, UsageEventWriter.ACTION_WIDTH);
      assertClipped(P_OUTCOME, UsageEventWriter.OUTCOME_WIDTH);
      assertClipped(P_ERROR_CODE, UsageEventWriter.ERROR_CODE_WIDTH);
      assertClipped(P_APP_VERSION, UsageEventWriter.APP_VERSION_WIDTH);
    }

    private void assertClipped(int index, int width) throws SQLException {
      ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
      verify(ps).setString(eq(index), value.capture());
      assertEquals(width, value.getValue().length(), "column " + index);
      assertTrue(value.getValue().startsWith("y"), "column " + index);
    }

    @Test
    void textAtExactlyTheWidthIsKeptWhole() throws SQLException {
      String exact = "z".repeat(UsageEventWriter.ACTION_WIDTH);
      UsageEventWriter.bind(ps, fullEvent().action(exact).build());
      verify(ps).setString(P_ACTION, exact);
    }

    @Test
    void blankOptionalValuesBecomeNull() throws SQLException {
      UsageEvent event = fullEvent()
          .roleId("  ").sessionKey("").target(" ").action(null).outcome("\t").errorCode(null)
          .appVersion("   ").durationMs(null)
          .build();
      UsageEventWriter.bind(ps, withProperties(event, "  "));

      verify(ps).setNull(P_ROLE, Types.VARCHAR);
      verify(ps).setNull(P_SESSION_KEY, Types.VARCHAR);
      verify(ps).setNull(P_TARGET, Types.VARCHAR);
      verify(ps).setNull(P_ACTION, Types.VARCHAR);
      verify(ps).setNull(P_OUTCOME, Types.VARCHAR);
      verify(ps).setNull(P_ERROR_CODE, Types.VARCHAR);
      verify(ps).setNull(P_APP_VERSION, Types.VARCHAR);
      verify(ps).setNull(P_DURATION, Types.NUMERIC);
      verify(ps).setNull(P_PROPERTIES, Types.VARCHAR);
    }

    @Test
    void valuesAreTrimmed() throws SQLException {
      UsageEventWriter.bind(ps, fullEvent().userId("  " + USER_ID + " ").target(" spec ").build());
      verify(ps).setString(P_USER, USER_ID);
      verify(ps).setString(P_CREATEDBY, USER_ID);
      verify(ps).setString(P_TARGET, "spec");
    }

    @Test
    void anOverWideIdIsNeverClippedButDropped() throws SQLException {
      UsageEvent event = fullEvent()
          .clientId(TOO_LONG_ID).orgId(TOO_LONG_ID).userId(TOO_LONG_ID).roleId(TOO_LONG_ID)
          .build();
      UsageEventWriter.bind(ps, event);

      verify(ps).setString(P_CLIENT, UsageEvent.DEFAULT_CLIENT);
      verify(ps).setString(P_ORG, UsageEvent.DEFAULT_ORG);
      verify(ps).setString(P_CREATEDBY, UsageEvent.SYSTEM_USER);
      verify(ps).setString(P_UPDATEDBY, UsageEvent.SYSTEM_USER);
      verify(ps).setNull(P_USER, Types.VARCHAR);
      verify(ps).setNull(P_ROLE, Types.VARCHAR);
      verify(ps, never()).setString(anyInt(), eq(TOO_LONG_ID));
      verify(ps, never()).setString(anyInt(), eq(USER_ID));
    }

    @Test
    void missingWhoColumnsFallBackToTheDefaults() throws SQLException {
      UsageEvent event = fullEvent().clientId(null).orgId(" ").userId(null).source(" ").build();
      UsageEventWriter.bind(ps, event);

      verify(ps).setString(P_CLIENT, UsageEvent.DEFAULT_CLIENT);
      verify(ps).setString(P_ORG, UsageEvent.DEFAULT_ORG);
      verify(ps).setString(P_CREATEDBY, UsageEvent.SYSTEM_USER);
      verify(ps).setString(P_UPDATEDBY, UsageEvent.SYSTEM_USER);
      verify(ps).setNull(P_USER, Types.VARCHAR);
      verify(ps).setString(P_SOURCE, UsageEvent.SOURCE_BACKEND);
    }

    @Test
    void aNegativeDurationIsNullAndAHugeOneIsCapped() throws SQLException {
      UsageEventWriter.bind(ps, fullEvent().durationMs(-1L).build());
      verify(ps).setNull(P_DURATION, Types.NUMERIC);

      PreparedStatement other = mock(PreparedStatement.class);
      UsageEventWriter.bind(other, fullEvent().durationMs(Long.MAX_VALUE).build());
      verify(other).setLong(P_DURATION, UsageEventWriter.DURATION_MAX);

      PreparedStatement zero = mock(PreparedStatement.class);
      UsageEventWriter.bind(zero, fullEvent().durationMs(0L).build());
      verify(zero).setLong(P_DURATION, 0L);
    }

    @Test
    void aNullOccurredAtIsBoundAsNow() throws SQLException {
      UsageEvent event = withOccurredAt(fullEvent().build(), null);
      Timestamp before = Timestamp.from(Instant.now());
      UsageEventWriter.bind(ps, event);
      Timestamp after = Timestamp.from(Instant.now());

      ArgumentCaptor<Timestamp> at = ArgumentCaptor.forClass(Timestamp.class);
      verify(ps).setTimestamp(eq(P_OCCURRED_AT), at.capture());
      assertFalse(at.getValue().before(before));
      assertFalse(at.getValue().after(after));
    }

    @Test
    void propertiesAreNeverClipped() throws SQLException {
      String big = "{\"k\":\"" + "p".repeat(10_000) + "\"}";
      UsageEventWriter.bind(ps, withProperties(fullEvent().build(), big));
      verify(ps).setString(P_PROPERTIES, big);
    }

    @Test
    void nullPropertiesBindNull() throws SQLException {
      UsageEventWriter.bind(ps, withProperties(fullEvent().build(), null));
      verify(ps).setNull(P_PROPERTIES, Types.VARCHAR);
    }
  }

  @Nested
  @DisplayName("insertBatch")
  class InsertBatch {

    private MockedStatic<ExternalConnectionPool> poolStatic;
    private final ExternalConnectionPool pool = mock(ExternalConnectionPool.class);
    private final Connection connection = mock(Connection.class);

    @BeforeEach
    void mockPool() {
      poolStatic = mockStatic(ExternalConnectionPool.class);
      poolStatic.when(ExternalConnectionPool::getInstance).thenReturn(pool);
      when(pool.getConnection()).thenReturn(connection);
    }

    @AfterEach
    void closePool() {
      poolStatic.close();
    }

    private List<UsageEvent> events(int n) {
      UsageEvent e = fullEvent().build();
      return Collections.nCopies(n, e);
    }

    private void assertConnectionReturned() throws SQLException {
      InOrder order = inOrder(connection);
      order.verify(connection).setAutoCommit(true);
      order.verify(connection).close();
    }

    @Test
    void noPoolThrowsSoTheWriterCountsTheBatch() {
      poolStatic.when(ExternalConnectionPool::getInstance).thenReturn(null);
      assertThrows(IllegalStateException.class, () -> UsageEventWriter.insertBatch(events(2)));
    }

    @Test
    void aGoodBatchIsOneExecuteAndOneCommit() throws SQLException {
      PreparedStatement ps = mock(PreparedStatement.class);
      when(connection.prepareStatement(UsageEventWriter.INSERT_SQL)).thenReturn(ps);

      assertEquals(3, UsageEventWriter.insertBatch(events(3)));

      verify(connection).setAutoCommit(false);
      verify(ps, times(3)).addBatch();
      verify(ps).executeBatch();
      verify(ps, never()).executeUpdate();
      verify(connection).commit();
      verify(connection, never()).rollback();
      verify(ps).close();
      assertConnectionReturned();
    }

    @Test
    void aFailedBatchRollsBackThenRetriesRowByRow() throws SQLException {
      PreparedStatement batchPs = mock(PreparedStatement.class);
      PreparedStatement row1 = mock(PreparedStatement.class);
      PreparedStatement row2 = mock(PreparedStatement.class);
      PreparedStatement row3 = mock(PreparedStatement.class);
      when(connection.prepareStatement(anyString())).thenReturn(batchPs, row1, row2, row3);
      when(batchPs.executeBatch()).thenThrow(new SQLException("fk violation"));
      when(row2.executeUpdate()).thenThrow(new SQLException("poisoned row"));

      assertEquals(2, UsageEventWriter.insertBatch(events(3)));

      InOrder order = inOrder(connection, batchPs, row1, row2, row3);
      order.verify(batchPs).executeBatch();
      order.verify(connection).rollback();
      order.verify(row1).executeUpdate();
      order.verify(connection).commit();
      order.verify(row2).executeUpdate();
      order.verify(connection).rollback();
      order.verify(row3).executeUpdate();
      order.verify(connection).commit();
      order.verify(connection).setAutoCommit(true);
      order.verify(connection).close();
      verify(row1).close();
      verify(row2).close();
      verify(row3).close();
    }

    @Test
    void aFailedSingleRowBatchIsRethrownWithoutARetry() throws SQLException {
      PreparedStatement ps = mock(PreparedStatement.class);
      when(connection.prepareStatement(anyString())).thenReturn(ps);
      SQLException failure = new SQLException("bad row");
      when(ps.executeBatch()).thenThrow(failure);

      SQLException thrown = assertThrows(SQLException.class,
          () -> UsageEventWriter.insertBatch(events(1)));

      assertSame(failure, thrown);
      verify(connection).rollback();
      verify(ps, never()).executeUpdate();
      assertConnectionReturned();
    }

    @Test
    void aFailedCommitAlsoFallsBackRowByRow() throws SQLException {
      PreparedStatement ps = mock(PreparedStatement.class);
      when(connection.prepareStatement(anyString())).thenReturn(ps);
      doThrow(new SQLException("commit failed")).doNothing().when(connection).commit();

      assertEquals(2, UsageEventWriter.insertBatch(events(2)));
      verify(ps, times(2)).executeUpdate();
      assertConnectionReturned();
    }

    @Test
    void rollbackAndCloseFailuresAreSwallowed() throws SQLException {
      PreparedStatement ps = mock(PreparedStatement.class);
      when(connection.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeBatch()).thenThrow(new SQLException("batch"));
      when(ps.executeUpdate()).thenThrow(new SQLException("row"));
      doThrow(new SQLException("rollback")).when(connection).rollback();
      doThrow(new SQLException("close")).when(connection).close();

      assertEquals(0, UsageEventWriter.insertBatch(events(2)));
      verify(connection).close();
    }

    @Test
    void aBindFailureIsTreatedLikeAnInsertFailure() throws SQLException {
      PreparedStatement ps = mock(PreparedStatement.class);
      when(connection.prepareStatement(anyString())).thenReturn(ps);
      doThrow(new SQLException("bind")).when(ps).setString(eq(1), any());

      assertEquals(0, UsageEventWriter.insertBatch(events(2)));
      verify(ps, never()).executeBatch();
      assertConnectionReturned();
    }
  }
}
