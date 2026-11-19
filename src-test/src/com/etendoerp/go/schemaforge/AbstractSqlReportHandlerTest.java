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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Unit tests for the helpers {@link AbstractSqlReportHandler} shares with every SQL report: the
 * required {@code dateFrom}/{@code dateTo} range of the period reports ({@link
 * TrialBalanceReportHandler}, {@link JournalEntriesReportHandler}, {@link
 * GeneralLedgerReportHandler}), run on each of them, and {@code toIsoDate}. The access gate of
 * {@code handle()} is covered by {@code ReportHandlerAccessGateTest}.
 *
 * @covers com.etendoerp.go.schemaforge.AbstractSqlReportHandler
 */
class AbstractSqlReportHandlerTest {

  static Stream<Named<AbstractSqlReportHandler>> periodReports() {
    return Stream.of(
        Named.of("TrialBalanceReportHandler", new TrialBalanceReportHandler()),
        Named.of("JournalEntriesReportHandler", new JournalEntriesReportHandler()),
        Named.of("GeneralLedgerReportHandler", new GeneralLedgerReportHandler()));
  }

  private static NeoContext post(JSONObject body) {
    return NeoContext.builder().specName("report").entityName("report").httpMethod("POST")
        .requestBody(body).build();
  }

  // -------------------------------------------------------------------------
  // dateFrom/dateTo — required on the period reports
  // -------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @MethodSource("periodReports")
  @DisplayName("declares dateFrom/dateTo as required, everything else optional")
  void reportParametersDeclaresRequiredDates(AbstractSqlReportHandler handler) {
    List<NeoReportParam> params = handler.reportParameters().orElseThrow();

    NeoReportParam dateFrom = params.stream().filter(p -> "dateFrom".equals(p.getName()))
        .findFirst().orElseThrow();
    NeoReportParam dateTo = params.stream().filter(p -> "dateTo".equals(p.getName()))
        .findFirst().orElseThrow();
    assertTrue(dateFrom.isRequired());
    assertTrue(dateTo.isRequired());

    long optionalCount = params.stream()
        .filter(p -> !"dateFrom".equals(p.getName()) && !"dateTo".equals(p.getName()))
        .filter(NeoReportParam::isRequired)
        .count();
    assertEquals(0, optionalCount, "only dateFrom/dateTo may be required");
  }

  /**
   * Every 400 here must be reachable with NO {@code OBContext}/{@code OBDal} mocking at all —
   * i.e. pure input validation runs strictly before anything touches the session or the database:
   * if a handler touched {@code OBContext.getOBContext()} before this validation, it would throw
   * (no context bound to this thread) instead of returning a clean 400.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("periodReports")
  @DisplayName("POST with neither date is refused with 400")
  void postWithoutDatesIsRefused(AbstractSqlReportHandler handler) throws Exception {
    assertRefused(handler, new JSONObject(), "dates_required");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("periodReports")
  @DisplayName("POST with a malformed dateFrom is refused with 400")
  void postWithMalformedDateFromIsRefused(AbstractSqlReportHandler handler) throws Exception {
    assertRefused(handler, new JSONObject().put("dateFrom", "01/01/2026")
        .put("dateTo", "2026-09-24"), "date_from_invalid");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("periodReports")
  @DisplayName("POST with dateFrom after dateTo is refused with 400, before OBContext is touched")
  void postWithDateFromAfterDateToIsRefused(AbstractSqlReportHandler handler) throws Exception {
    assertRefused(handler, new JSONObject().put("dateFrom", "2026-09-24")
        .put("dateTo", "2026-01-01"), "date_range_invalid");
  }

  private static void assertRefused(AbstractSqlReportHandler handler, JSONObject body,
      String error) throws Exception {
    // Only the grant is mocked: see postWithoutDatesIsRefused.
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      NeoResponse response = handler.handle(post(body));

      assertEquals(400, response.getHttpStatus());
      assertEquals(error, response.getBody().getString("error"));
    }
  }

  // -------------------------------------------------------------------------
  // toIsoDate — fact_acct.dateacct rendered as yyyy-MM-dd, not Timestamp#toString()
  // -------------------------------------------------------------------------

  static Stream<Arguments> isoDates() {
    return Stream.of(
        Arguments.of(Named.of("midnight Timestamp, not '... 00:00:00.0'",
            Timestamp.valueOf("2026-01-01 00:00:00")), "2026-01-01"),
        Arguments.of(Named.of("late-in-the-day Timestamp, no day shift",
            Timestamp.valueOf("2026-03-31 23:59:59")), "2026-03-31"),
        Arguments.of(Named.of("java.sql.Date", Date.valueOf("2026-02-15")), "2026-02-15"),
        Arguments.of(Named.of("LocalDateTime", LocalDateTime.of(2026, 4, 7, 13, 45, 0)),
            "2026-04-07"),
        Arguments.of(Named.of("LocalDate", LocalDate.of(2026, 6, 30)), "2026-06-30"),
        Arguments.of(Named.of("raw timestamp-shaped String, truncated to its date prefix",
            "2026-05-06 00:00:00.0"), "2026-05-06"),
        Arguments.of(Named.of("short non-date String, unchanged", "abc"), "abc"),
        Arguments.of(Named.of("null, as an empty string", null), ""));
  }

  @ParameterizedTest(name = "{0} -> \"{1}\"")
  @MethodSource("isoDates")
  @DisplayName("toIsoDate renders every date shape as yyyy-MM-dd")
  void toIsoDateRendersPlainDate(Object value, String expected) {
    assertEquals(expected, AbstractSqlReportHandler.toIsoDate(value));
  }
}
