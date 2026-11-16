/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService;

import com.etendoerp.go.schemaforge.FiscalAeatValidationOutcome.Path;

/**
 * Unit tests for {@link FiscalAeatAttemptLog}: what one attempt writes (per path, with the
 * warning columns), the environment an endpoint stands for, the codes column and the best-effort
 * contract (one retry without the warning columns, never an exception). No database: the log's
 * own connection is a mock.
 *
 * @covers com.etendoerp.go.schemaforge.FiscalAeatAttemptLog
 */
public class FiscalAeatAttemptLogTest {

  private static final String VALIDATION = AEAT303SubmissionService.DEFAULT_VALIDATION_ENDPOINT;
  private static final String PRODUCTION = AEAT303SubmissionService.DEFAULT_PRODUCTION_ENDPOINT;
  private static final String PREPRODUCTION =
      AEAT303SubmissionService.DEFAULT_EXTERNAL_TEST_ENDPOINT;
  private static final FiscalAeatAttemptLog.Declaration DECLARATION =
      new FiscalAeatAttemptLog.Declaration("390", 2026, "0A", "DECL1", "org1");

  /** The log under test, handing out the queued connections instead of a real one. */
  private static final class StubbedLog extends FiscalAeatAttemptLog {
    private final List<Connection> queued = new ArrayList<>();
    private int opened;
    private RuntimeException openFailure;

    @Override
    Connection openConnection() throws SQLException {
      if (openFailure != null) {
        throw openFailure;
      }
      return queued.get(opened++);
    }
  }

  private MockedStatic<OBContext> obContextMock;
  private StubbedLog log;

  @Before
  public void setUp() {
    OBContext ctx = mock(OBContext.class);
    User user = mock(User.class);
    when(user.getId()).thenReturn("user1");
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client1");
    when(ctx.getUser()).thenReturn(user);
    when(ctx.getCurrentClient()).thenReturn(client);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(ctx);
    log = new StubbedLog();
  }

  @After
  public void tearDown() {
    obContextMock.close();
  }

  // ── helpers ─────────────────────────────────────────────────────────

  /** Queues a connection whose statement for {@code sql} is the returned mock. */
  private PreparedStatement queueConnection(String sql) throws SQLException {
    Connection connection = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(connection.prepareStatement(sql)).thenReturn(ps);
    log.queued.add(connection);
    return ps;
  }

  private static FiscalAeatValidationOutcome answered(Path path, boolean accepted,
      boolean testMode, String[] errors, String[] warnings) {
    AEAT303SubmissionResult r = new AEAT303SubmissionResult();
    r.setStatus(accepted ? AEAT303SubmissionResult.Status.SUCCESS
        : AEAT303SubmissionResult.Status.ERROR);
    r.setTestMode(testMode);
    for (String error : errors) {
      r.addError(error);
    }
    for (String warning : warnings) {
      r.addWarning(warning);
    }
    return FiscalAeatValidationOutcome.answered("390", path, r);
  }

  private static FiscalAeatValidationOutcome accepted(Path path, boolean testMode) {
    return answered(path, true, testMode, new String[0], new String[0]);
  }

  // ── the row ─────────────────────────────────────────────────────────

  /** A rejected download validation: every column, the codes without their text. */
  @Test
  public void recordWritesTheWholeRowWithTheWarningColumns() throws Exception {
    PreparedStatement ps = queueConnection(FiscalAeatAttemptLog.INSERT_SQL);
    when(log.queued.get(0).getAutoCommit()).thenReturn(false);
    FiscalAeatValidationOutcome outcome = answered(Path.DOWNLOAD, false, true,
        new String[] { "E0100803 - Razon social B12345678", "NIF no identificado" },
        new String[] { "A001 - Aviso informativo" });

    log.record(DECLARATION, outcome, VALIDATION);

    verify(ps).setString(1, "client1");
    verify(ps).setString(2, "org1");
    verify(ps).setString(3, "user1");
    verify(ps).setString(4, "user1");
    verify(ps).setString(5, "390");
    verify(ps).setInt(6, 2026);
    verify(ps).setString(7, "0A");
    verify(ps).setString(8, "DECL1");
    verify(ps).setString(9, "download");
    verify(ps).setString(10, "Y");
    verify(ps).setString(11, FiscalAeatAttemptLog.ENV_VALIDATION);
    verify(ps).setString(12, "prewww2.aeat.es");
    verify(ps).setString(13, "rejected");
    verify(ps).setInt(14, 2);
    verify(ps).setString(15, "E0100803,UNCODED");
    verify(ps).setInt(16, 1);
    verify(ps).setString(17, "A001");
    verify(ps).executeUpdate();
    verify(log.queued.get(0)).commit();
    verify(log.queued.get(0)).close();
    assertEquals(1, log.opened);
  }

  /** Each path writes its own key and test-mode flag; an auto-commit connection is not committed. */
  @Test
  public void recordWritesThePathAndTestModeOfEachAttempt() throws Exception {
    Object[][] cases = {
        { Path.DOWNLOAD, true, VALIDATION, "download", "Y", FiscalAeatAttemptLog.ENV_VALIDATION },
        { Path.MANUAL_VALIDATION, true, VALIDATION, "manual_validation", "Y",
            FiscalAeatAttemptLog.ENV_VALIDATION },
        { Path.PRE_SUBMISSION_VALIDATION, true, VALIDATION, "pre_submission_validation", "Y",
            FiscalAeatAttemptLog.ENV_VALIDATION },
        { Path.DIRECT_SUBMISSION, false, PRODUCTION, "direct_submission", "N",
            FiscalAeatAttemptLog.ENV_PRODUCTION } };
    for (Object[] c : cases) {
      PreparedStatement ps = queueConnection(FiscalAeatAttemptLog.INSERT_SQL);
      Connection connection = log.queued.get(log.queued.size() - 1);
      when(connection.getAutoCommit()).thenReturn(true);

      log.record(DECLARATION, accepted((Path) c[0], (Boolean) c[1]), (String) c[2]);

      String label = c[3].toString();
      verify(ps).setString(9, label);
      verify(ps).setString(10, (String) c[4]);
      verify(ps).setString(11, (String) c[5]);
      verify(ps).setString(13, "accepted");
      verify(ps).setInt(14, 0);
      verify(ps).setString(15, null);
      verify(ps).setInt(16, 0);
      verify(ps).setString(17, null);
      verify(ps).executeUpdate();
      verify(connection, never()).commit();
    }
    assertEquals(cases.length, log.opened);
  }

  /** An attempt that got no answer is a row too: its outcome, no codes, no host when unknown. */
  @Test
  public void recordWritesAnUnansweredAttempt() throws Exception {
    PreparedStatement ps = queueConnection(FiscalAeatAttemptLog.INSERT_SQL);
    FiscalAeatValidationOutcome outcome = FiscalAeatValidationOutcome.unanswered("390",
        Path.DIRECT_SUBMISSION, false, new OBException("x", new SocketTimeoutException()));

    log.record(DECLARATION, outcome, null);

    verify(ps).setString(9, "direct_submission");
    verify(ps).setString(10, "N");
    verify(ps).setString(11, FiscalAeatAttemptLog.ENV_UNKNOWN);
    verify(ps).setString(12, null);
    verify(ps).setString(13, "connection_failure");
    verify(ps).setInt(14, 0);
    verify(ps).executeUpdate();
  }

  // ── environment / host ──────────────────────────────────────────────

  @Test
  public void environmentOfTellsARealFilingFromEverythingElse() {
    FiscalAeatValidationOutcome filing = accepted(Path.DIRECT_SUBMISSION, false);
    FiscalAeatValidationOutcome validation = accepted(Path.MANUAL_VALIDATION, true);

    assertEquals(FiscalAeatAttemptLog.ENV_PRODUCTION,
        FiscalAeatAttemptLog.environmentOf(filing, PRODUCTION));
    assertEquals(FiscalAeatAttemptLog.ENV_PRODUCTION,
        FiscalAeatAttemptLog.environmentOf(filing, "  " + PRODUCTION + " "));
    assertEquals(FiscalAeatAttemptLog.ENV_PREPRODUCTION,
        FiscalAeatAttemptLog.environmentOf(filing, PREPRODUCTION));
    assertEquals(FiscalAeatAttemptLog.ENV_VALIDATION,
        FiscalAeatAttemptLog.environmentOf(validation, VALIDATION));
    // Test mode never files, whatever AEAT endpoint it is pointed at.
    assertEquals(FiscalAeatAttemptLog.ENV_VALIDATION,
        FiscalAeatAttemptLog.environmentOf(validation, PRODUCTION));
  }

  /** A host that is not the AEAT's is never reported as an AEAT environment. */
  @Test
  public void environmentOfAnEndpointOutsideTheAeatIsOverride() {
    FiscalAeatValidationOutcome filing = accepted(Path.DIRECT_SUBMISSION, false);
    FiscalAeatValidationOutcome validation = accepted(Path.MANUAL_VALIDATION, true);

    for (String endpoint : new String[] { "http://localhost:8080/stub",
        "https://aeat.es.evil.com/wlpl/PFTW-PICW/PresBasicaDos",
        "https://www1.agenciatributaria.gob.es.evil.com/x" }) {
      assertEquals(endpoint, FiscalAeatAttemptLog.ENV_OVERRIDE,
          FiscalAeatAttemptLog.environmentOf(filing, endpoint));
      assertEquals(endpoint, FiscalAeatAttemptLog.ENV_OVERRIDE,
          FiscalAeatAttemptLog.environmentOf(validation, endpoint));
    }
  }

  /** No endpoint: a filing went nobody knows where; test mode is the validation service anyway. */
  @Test
  public void environmentOfAnUnresolvedEndpoint() {
    FiscalAeatValidationOutcome filing = accepted(Path.DIRECT_SUBMISSION, false);
    FiscalAeatValidationOutcome validation = accepted(Path.MANUAL_VALIDATION, true);

    assertEquals(FiscalAeatAttemptLog.ENV_UNKNOWN,
        FiscalAeatAttemptLog.environmentOf(filing, null));
    assertEquals(FiscalAeatAttemptLog.ENV_UNKNOWN,
        FiscalAeatAttemptLog.environmentOf(filing, "   "));
    assertEquals(FiscalAeatAttemptLog.ENV_VALIDATION,
        FiscalAeatAttemptLog.environmentOf(validation, ""));
  }

  @Test
  public void isAeatHostAcceptsOnlySubdomainsOfTheAeat() {
    assertTrue(FiscalAeatAttemptLog.isAeatHost("prewww2.aeat.es"));
    assertTrue(FiscalAeatAttemptLog.isAeatHost(" WWW1.AgenciaTributaria.gob.ES "));
    assertFalse(FiscalAeatAttemptLog.isAeatHost("aeat.es.evil.com"));
    assertFalse(FiscalAeatAttemptLog.isAeatHost("evilaeat.es"));
    assertFalse(FiscalAeatAttemptLog.isAeatHost("agenciatributaria.gob.es.evil.com"));
    assertFalse(FiscalAeatAttemptLog.isAeatHost("localhost"));
    assertFalse(FiscalAeatAttemptLog.isAeatHost(""));
    assertFalse(FiscalAeatAttemptLog.isAeatHost(null));
  }

  @Test
  public void hostOfReadsTheHostAndToleratesAnythingElse() {
    assertEquals("prewww2.aeat.es", FiscalAeatAttemptLog.hostOf(" " + VALIDATION + " "));
    assertNull(FiscalAeatAttemptLog.hostOf("ht tp://not a url"));
    assertNull(FiscalAeatAttemptLog.hostOf("no-scheme-no-host"));
    assertNull(FiscalAeatAttemptLog.hostOf(""));
    assertNull(FiscalAeatAttemptLog.hostOf(null));
  }

  @Test
  public void endpointOfFollowsTheTestModeAndNeverThrows() {
    AEAT303SubmissionService service = mock(AEAT303SubmissionService.class);
    when(service.getValidationEndpoint()).thenReturn(VALIDATION);
    when(service.getProductionEndpoint()).thenReturn(PREPRODUCTION);

    assertEquals(VALIDATION, FiscalAeatAttemptLog.endpointOf(service, true));
    assertEquals(PREPRODUCTION, FiscalAeatAttemptLog.endpointOf(service, false));

    when(service.getProductionEndpoint()).thenThrow(new OBException("no context"));
    assertNull(FiscalAeatAttemptLog.endpointOf(service, false));
  }

  // ── codes column ────────────────────────────────────────────────────

  /** 250 codes of 9 chars do not fit 2000: the column holds whole codes only (200 of them). */
  @Test
  public void joinCodesCutsAtAWholeCode() {
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < 250; i++) {
      codes.add(String.format("E%08d", i));
    }

    String joined = FiscalAeatAttemptLog.joinCodes(codes);

    assertEquals(1999, joined.length());
    String[] kept = joined.split(",");
    assertEquals(200, kept.length);
    assertEquals("E00000000", kept[0]);
    assertEquals("E00000199", kept[199]);
  }

  @Test
  public void joinCodesKeepsWhatFitsExactlyAndIsNullWhenEmpty() {
    // 100 codes of 19 chars + 99 separators = 1999; one more char would not fit a 101st code.
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      codes.add(String.format("E%018d", i));
    }
    assertEquals(1999, FiscalAeatAttemptLog.joinCodes(codes).length());
    codes.add("X");
    assertEquals(1999, FiscalAeatAttemptLog.joinCodes(codes).length());

    assertEquals("A,B", FiscalAeatAttemptLog.joinCodes(List.of("A", "B")));
    assertNull(FiscalAeatAttemptLog.joinCodes(List.of()));
  }

  // ── best-effort ─────────────────────────────────────────────────────

  /** A table that predates the warning columns: ONE retry, with the row minus those columns. */
  @Test
  public void anSqlFailureIsRetriedOnceWithoutTheWarningColumns() throws Exception {
    PreparedStatement first = queueConnection(FiscalAeatAttemptLog.INSERT_SQL);
    when(first.executeUpdate()).thenThrow(new SQLException("column warning_count does not exist"));
    PreparedStatement retry = queueConnection(FiscalAeatAttemptLog.INSERT_SQL_NO_WARNINGS);
    FiscalAeatValidationOutcome outcome = answered(Path.MANUAL_VALIDATION, true, true,
        new String[0], new String[] { "A001 - Aviso" });

    log.record(DECLARATION, outcome, VALIDATION);

    assertEquals(2, log.opened);
    verify(retry).setString(9, "manual_validation");
    verify(retry).setString(13, "accepted");
    verify(retry).setInt(14, 0);
    verify(retry).setString(15, null);
    verify(retry, never()).setInt(eq(16), anyInt());
    verify(retry, never()).setString(eq(17), any());
    verify(retry).executeUpdate();
    verify(log.queued.get(0)).close();
    verify(log.queued.get(1)).close();
    assertFalse(FiscalAeatAttemptLog.INSERT_SQL_NO_WARNINGS.contains("warning_"));
    assertTrue(FiscalAeatAttemptLog.INSERT_SQL.contains("warning_count, warning_codes"));
  }

  /** The retry fails too: nothing reaches the caller and there is no third attempt. */
  @Test
  public void aFailingRetryIsSwallowed() throws Exception {
    PreparedStatement first = queueConnection(FiscalAeatAttemptLog.INSERT_SQL);
    when(first.executeUpdate()).thenThrow(new SQLException("first"));
    PreparedStatement retry = queueConnection(FiscalAeatAttemptLog.INSERT_SQL_NO_WARNINGS);
    when(retry.executeUpdate()).thenThrow(new SQLException("second"));

    log.record(DECLARATION, accepted(Path.DOWNLOAD, true), VALIDATION);

    verify(first).executeUpdate();
    verify(retry).executeUpdate();
    assertEquals(2, log.opened);
  }

  /** A failure that is not an SQLException (no connection to be had) is not retried. */
  @Test
  public void recordNeverThrowsWhenNoConnectionCanBeOpened() {
    log.openFailure = new IllegalStateException("no pool");

    log.record(DECLARATION, accepted(Path.DOWNLOAD, true), VALIDATION);

    assertEquals(0, log.opened);
  }

  @Test
  public void recordNeverThrowsWithoutASessionContext() throws Exception {
    PreparedStatement ps = queueConnection(FiscalAeatAttemptLog.INSERT_SQL);
    obContextMock.when(OBContext::getOBContext).thenReturn(null);

    log.record(DECLARATION, accepted(Path.DOWNLOAD, true), VALIDATION);

    assertEquals(0, log.opened);
    verify(ps, never()).executeUpdate();
  }
}
