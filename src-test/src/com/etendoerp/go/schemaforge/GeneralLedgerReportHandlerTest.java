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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Unit tests for {@link GeneralLedgerReportHandler} (ETP-5483 slice 6, the last one) — the MCP
 * report tool {@code generate_report_general_ledger}, a faithful Java port of {@code
 * artifacts/report-general-ledger/report-contract.json}'s {@code sql.query}/{@code
 * sql.openingQuery} plus {@link GeneralLedgerGrouping} (whose own nesting/opening/running-balance
 * rules are covered by {@code GeneralLedgerGroupingTest}, not here).
 */
class GeneralLedgerReportHandlerTest {

  private final GeneralLedgerReportHandler handler = new GeneralLedgerReportHandler();

  private static NeoContext context(String method, JSONObject body) {
    return NeoContext.builder().specName("report").entityName("report").httpMethod(method)
        .requestBody(body).build();
  }

  // -------------------------------------------------------------------------
  // @Named qualifier
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("carries its own @Named qualifier")
  void carriesOwnNamedQualifier() {
    Named named = GeneralLedgerReportHandler.class.getAnnotation(Named.class);
    assertNotNull(named);
    assertEquals("generalLedgerReportHandler", named.value());
  }

  // -------------------------------------------------------------------------
  // reportParameters
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("declares dateFrom/dateTo as required, everything else optional")
  void reportParametersDeclaresRequiredDates() {
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

  @Test
  @DisplayName("declares accountLimit/linesPerAccountLimit as described integer parameters")
  void reportParametersDeclaresCaps() {
    List<NeoReportParam> params = handler.reportParameters().orElseThrow();

    NeoReportParam accountLimit = params.stream().filter(p -> "accountLimit".equals(p.getName()))
        .findFirst().orElseThrow();
    NeoReportParam linesPerAccountLimit = params.stream()
        .filter(p -> "linesPerAccountLimit".equals(p.getName())).findFirst().orElseThrow();

    assertEquals(NeoReportParam.TYPE_INTEGER, accountLimit.getType());
    assertFalse(accountLimit.isRequired());
    assertEquals(NeoReportParam.TYPE_INTEGER, linesPerAccountLimit.getType());
    assertFalse(linesPerAccountLimit.isRequired());
  }

  @Test
  @DisplayName("declares groupBy with the four dimension values as allowedValues")
  void reportParametersDeclaresGroupByAllowedValues() {
    NeoReportParam groupBy = handler.reportParameters().orElseThrow().stream()
        .filter(p -> "groupBy".equals(p.getName())).findFirst().orElseThrow();

    assertEquals(List.of("bpartner", "product", "project", "costcenter"),
        groupBy.getAllowedValues());
  }

  // -------------------------------------------------------------------------
  // GET — describe
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("GET with the grant returns the descriptor")
  void getWithGrantReturnsDescriptor() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      NeoResponse response = handler.handle(context("GET", null));

      assertEquals(200, response.getHttpStatus());
      assertEquals("General Ledger", response.getBody().getString("name"));
      assertTrue(response.getBody().getJSONArray("parameters").length() > 0);
    }
  }

  @Test
  @DisplayName("GET without the grant does not leak the descriptor")
  void getWithoutGrantDoesNotLeakDescriptor() {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(false);

      NeoResponse response = handler.handle(context("GET", null));

      assertEquals(403, response.getHttpStatus());
    }
  }

  // -------------------------------------------------------------------------
  // Validation — 400s, all reachable without touching OBContext/the DB
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST with neither date is refused with 400")
  void postWithoutDatesIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      NeoResponse response = handler.handle(context("POST", new JSONObject()));

      assertEquals(400, response.getHttpStatus());
      assertEquals("dates_required", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST with a malformed dateFrom is refused with 400")
  void postWithMalformedDateFromIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("dateFrom", "01/01/2026");
      body.put("dateTo", "2026-09-24");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("date_from_invalid", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST with dateFrom after dateTo is refused with 400")
  void postWithDateFromAfterDateToIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("dateFrom", "2026-09-24");
      body.put("dateTo", "2026-01-01");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("date_range_invalid", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST with a zero/negative accountLimit is refused with 400")
  void postWithInvalidAccountLimitIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("dateFrom", "2026-01-01");
      body.put("dateTo", "2026-09-24");
      body.put("accountLimit", 0);

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("account_limit_invalid", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST with a zero/negative linesPerAccountLimit is refused with 400")
  void postWithInvalidLinesPerAccountLimitIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("dateFrom", "2026-01-01");
      body.put("dateTo", "2026-09-24");
      body.put("linesPerAccountLimit", -5);

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("lines_per_account_limit_invalid", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST with an unrecognized groupBy is refused with 400")
  void postWithInvalidGroupByIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("dateFrom", "2026-01-01");
      body.put("dateTo", "2026-09-24");
      body.put("groupBy", "not-a-real-dimension");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("group_by_invalid", response.getBody().getString("error"));
    }
  }

  /**
   * Every 400 above must be reachable with NO {@link OBContext}/{@link OBDal} mocking at all —
   * i.e. pure input validation runs strictly before anything touches the session or the database.
   * This test documents that guarantee explicitly (mirrors the same guarantee the sibling report
   * handlers' tests assert implicitly by never mocking OBContext for their 400 tests).
   */
  @Test
  @DisplayName("pure validation runs before OBContext is touched (no OBContext/OBDal mock needed for a 400)")
  void pureValidationRunsBeforeObContext() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("dateFrom", "2026-09-24");
      body.put("dateTo", "2026-01-01");

      // No OBContext/OBDal static mock is installed here on purpose: if the handler touched
      // OBContext.getOBContext() before this validation, it would throw (no context bound to
      // this thread) instead of returning a clean 400.
      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
    }
  }

  // -------------------------------------------------------------------------
  // Access gate
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST without the grant is refused with 403 before any validation runs")
  void postWithoutGrantIsRefused() {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(false);

      // An empty body would fail date validation too, but the gate must be checked first.
      NeoResponse response = handler.handle(context("POST", new JSONObject()));

      assertEquals(403, response.getHttpStatus());
    }
  }

  // -------------------------------------------------------------------------
  // Client scoping — from OBContext, never from the request body
  // -------------------------------------------------------------------------

  /**
   * The core scoping guarantee, mirroring {@code JournalEntriesReportHandlerTest}'s own: even if
   * a caller stuffs an {@code ad_client_id}-shaped value somewhere in the body, the query is
   * bound to the SESSION's client. This handler runs FOUR native queries (main lines, account
   * totals, opening rows, total-accounts count); with an empty result set the totals/opening
   * queries are never even issued (see {@link GeneralLedgerReportHandler#buildAccountTotals}), so
   * only the main query and the count query are mocked here.
   */
  @Test
  @DisplayName("binds the query to OBContext's client, with no client id read from the body")
  void clientIdComesFromObContextNotBody() throws Exception {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("sessionClientId1");

    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("sessionOrgId1");
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getId()).thenReturn("schemaId1");
    when(org.getGeneralLedger()).thenReturn(schema);

    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getCurrentOrganization()).thenReturn(org);

    Session session = mock(Session.class);
    NativeQuery<Object[]> mainQuery = mock(NativeQuery.class);
    when(mainQuery.setParameter(anyString(), any())).thenReturn(mainQuery);
    when(mainQuery.list()).thenReturn(Collections.emptyList());

    NativeQuery<Number> countQuery = mock(NativeQuery.class);
    when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
    when(countQuery.uniqueResult()).thenReturn(0);

    // The count query's SQL starts with "SELECT COUNT(DISTINCT", the main query's with "WITH gl".
    when(session.createNativeQuery(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      return sql.contains("COUNT(DISTINCT") ? countQuery : mainQuery;
    });

    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    when(dal.get(Organization.class, "sessionOrgId1")).thenReturn(org);

    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      contextMock.when(OBContext::getOBContext).thenReturn(obContext);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      JSONObject body = new JSONObject();
      body.put("dateFrom", "2026-01-01");
      body.put("dateTo", "2026-09-24");
      body.put("clientId", "attacker-supplied-client-id");
      body.put("ad_client_id", "attacker-supplied-client-id");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(200, response.getHttpStatus(), () -> {
        try {
          return "Unexpected error: " + response.getBody();
        } catch (Exception e) {
          return "Unexpected non-200 response";
        }
      });

      ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
      ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
      verify(mainQuery, atLeastOnce())
          .setParameter(nameCaptor.capture(), valueCaptor.capture());

      int clientIdParamIndex = nameCaptor.getAllValues().indexOf("clientId");
      assertTrue(clientIdParamIndex >= 0, "the query must bind a clientId parameter");
      assertEquals("sessionClientId1", valueCaptor.getAllValues().get(clientIdParamIndex),
          "clientId must come from OBContext's current client, never from the request body");

      JSONObject responseData = response.getBody().getJSONObject("response");
      assertEquals(0, responseData.getInt("count"));
      assertFalse(responseData.getJSONObject("meta").toString().contains("attacker-supplied"),
          "the body's spoofed client id must never reach the response either");
    }
  }

  // -------------------------------------------------------------------------
  // buildDataArray — MCP response shape
  // -------------------------------------------------------------------------

  private static GeneralLedgerGrouping.Row row(String accountNo, String accountName,
      String dateacct, String factAcctGroupId, String debit, String credit) {
    return GeneralLedgerGrouping.Row.builder()
        .accountNo(accountNo)
        .accountId("acct-id-" + accountNo)
        .accountName(accountName)
        .dateacct(dateacct)
        .factAcctGroupId(factAcctGroupId)
        .groupbyname("Some movement")
        .amtacctdr(new BigDecimal(debit))
        .amtacctcr(new BigDecimal(credit))
        .bpname("Acme")
        .productname("Widget")
        .projectname("Proj A")
        .costcentername("CC1")
        .build();
  }

  private static GeneralLedgerGrouping.AccountTotal accountTotal430(BigDecimal debit,
      BigDecimal credit, long lineCount) {
    return GeneralLedgerGrouping.AccountTotal.builder()
        .accountNo("430")
        .amtacctdr(debit)
        .amtacctcr(credit)
        .lineCount(lineCount)
        .build();
  }

  @Test
  @DisplayName("renders account fields and ordered lines carrying amtacctdr/amtacctcr/runningBalance")
  void buildDataArrayRendersAccountAndOrderedLines() throws Exception {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"),
        row("430", "Customers", "2026-01-06", "grp-2", "0", "40"));
    List<GeneralLedgerGrouping.AccountTotal> totals = List.of(
        accountTotal430(new BigDecimal("100"), new BigDecimal("40"), 2));
    List<GeneralLedgerGrouping.Group> groups =
        GeneralLedgerGrouping.nest(rows, null, List.of(), totals);

    JSONObject account = GeneralLedgerReportHandler.buildDataArray(groups, false, true)
        .getJSONObject(0).getJSONArray("accounts").getJSONObject(0);

    assertEquals("430", account.getString("value"));
    assertEquals("Customers", account.getString("name"));
    assertTrue(account.has("opening"));

    JSONArray lines = account.getJSONArray("lines");
    assertEquals(2, lines.length());
    assertEquals(100.0, lines.getJSONObject(0).getDouble("amtacctdr"), 0.0001);
    assertEquals(100.0, lines.getJSONObject(0).getDouble("runningBalance"), 0.0001);
    assertEquals(60.0, lines.getJSONObject(1).getDouble("runningBalance"), 0.0001);

    assertEquals(2, account.getLong("totalLines"));
    assertFalse(account.getBoolean("linesTruncated"));

    JSONObject subtotal = account.getJSONObject("subtotal");
    assertEquals(100.0, subtotal.getDouble("amtacctdr"), 0.0001);
    assertEquals(40.0, subtotal.getDouble("amtacctcr"), 0.0001);
    assertEquals(60.0, subtotal.getDouble("total"), 0.0001);

    JSONObject total = account.getJSONObject("total");
    assertEquals(60.0, total.getDouble("total"), 0.0001);
    assertEquals(subtotal.getDouble("total") + account.getJSONObject("opening").getDouble("total"),
        total.getDouble("total"), 0.0001);
  }

  @Test
  @DisplayName("subtotal/total come from the uncapped AccountTotal aggregate, not from truncated lines")
  void buildDataArraySubtotalComesFromAggregateWhenLinesTruncated() throws Exception {
    // Only 2 of the account's 5 real movement lines are returned (linesPerAccountLimit truncation).
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "50", "0"),
        row("430", "Customers", "2026-01-06", "grp-2", "0", "20"));
    List<GeneralLedgerGrouping.OpeningRow> openingRows = List.of(
        new GeneralLedgerGrouping.OpeningRow("430", null, null, null, null,
            new BigDecimal("500"), new BigDecimal("200")));
    List<GeneralLedgerGrouping.AccountTotal> totals = List.of(
        accountTotal430(new BigDecimal("1000"), new BigDecimal("300"), 5));
    List<GeneralLedgerGrouping.Group> groups =
        GeneralLedgerGrouping.nest(rows, null, openingRows, totals);

    JSONObject account = GeneralLedgerReportHandler.buildDataArray(groups, false, true)
        .getJSONObject(0).getJSONArray("accounts").getJSONObject(0);

    assertEquals(5, account.getLong("totalLines"));
    assertTrue(account.getBoolean("linesTruncated"));
    assertEquals(2, account.getJSONArray("lines").length());

    JSONObject opening = account.getJSONObject("opening");
    assertEquals(300.0, opening.getDouble("total"), 0.0001);

    JSONObject subtotal = account.getJSONObject("subtotal");
    assertEquals(1000.0, subtotal.getDouble("amtacctdr"), 0.0001);
    assertEquals(300.0, subtotal.getDouble("amtacctcr"), 0.0001);
    assertEquals(700.0, subtotal.getDouble("total"), 0.0001);

    JSONObject total = account.getJSONObject("total");
    assertEquals(1000.0, total.getDouble("total"), 0.0001);
    assertEquals(opening.getDouble("total") + subtotal.getDouble("total"),
        total.getDouble("total"), 0.0001);

    // The last RETURNED line's runningBalance only reflects the two truncated rows — it must NOT
    // equal `total`, proving subtotal/total were not derived from the (truncated) returned lines.
    JSONArray lines = account.getJSONArray("lines");
    double lastRunningBalance = lines.getJSONObject(lines.length() - 1).getDouble("runningBalance");
    assertEquals(330.0, lastRunningBalance, 0.0001);
    assertTrue(Math.abs(lastRunningBalance - total.getDouble("total")) > 0.0001);
  }

  @Test
  @DisplayName("showOpenBalances=false omits the opening object from the account")
  void buildDataArrayOmitsOpeningWhenDisabled() throws Exception {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"));
    List<GeneralLedgerGrouping.AccountTotal> totals = List.of(
        accountTotal430(new BigDecimal("100"), BigDecimal.ZERO, 1));
    List<GeneralLedgerGrouping.Group> groups =
        GeneralLedgerGrouping.nest(rows, null, List.of(), totals);

    JSONObject account = GeneralLedgerReportHandler.buildDataArray(groups, false, false)
        .getJSONObject(0).getJSONArray("accounts").getJSONObject(0);

    assertFalse(account.has("opening"));
  }

  @Test
  @DisplayName("showDimensions=false omits bpname/productname/projectname/costcentername from lines")
  void buildDataArrayOmitsDimensionsWhenDisabled() throws Exception {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"));
    List<GeneralLedgerGrouping.AccountTotal> totals = List.of(
        accountTotal430(new BigDecimal("100"), BigDecimal.ZERO, 1));
    List<GeneralLedgerGrouping.Group> groups =
        GeneralLedgerGrouping.nest(rows, null, List.of(), totals);

    JSONObject line = GeneralLedgerReportHandler.buildDataArray(groups, false, true)
        .getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getJSONArray("lines")
        .getJSONObject(0);

    assertFalse(line.has("bpname"));
    assertFalse(line.has("productname"));
    assertFalse(line.has("projectname"));
    assertFalse(line.has("costcentername"));
  }

  @Test
  @DisplayName("showDimensions=true includes bpname/productname/projectname/costcentername on lines")
  void buildDataArrayIncludesDimensionsWhenEnabled() throws Exception {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"));
    List<GeneralLedgerGrouping.AccountTotal> totals = List.of(
        accountTotal430(new BigDecimal("100"), BigDecimal.ZERO, 1));
    List<GeneralLedgerGrouping.Group> groups =
        GeneralLedgerGrouping.nest(rows, null, List.of(), totals);

    JSONObject line = GeneralLedgerReportHandler.buildDataArray(groups, true, true)
        .getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getJSONArray("lines")
        .getJSONObject(0);

    assertEquals("Acme", line.getString("bpname"));
    assertEquals("Widget", line.getString("productname"));
    assertEquals("Proj A", line.getString("projectname"));
    assertEquals("CC1", line.getString("costcentername"));
  }

  // -------------------------------------------------------------------------
  // toIsoDate — fact_acct.dateacct rendered as yyyy-MM-dd, not Timestamp#toString()
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toIsoDate renders a midnight Timestamp as yyyy-MM-dd, not '... 00:00:00.0'")
  void toIsoDateRendersMidnightTimestampAsPlainDate() {
    java.sql.Timestamp value = java.sql.Timestamp.valueOf("2026-01-01 00:00:00");

    assertEquals("2026-01-01", GeneralLedgerReportHandler.toIsoDate(value));
  }

  @Test
  @DisplayName("toIsoDate renders a java.sql.Date as yyyy-MM-dd")
  void toIsoDateRendersSqlDate() {
    java.sql.Date value = java.sql.Date.valueOf("2026-02-15");

    assertEquals("2026-02-15", GeneralLedgerReportHandler.toIsoDate(value));
  }

  @Test
  @DisplayName("toIsoDate truncates a raw timestamp-shaped String to its date prefix")
  void toIsoDateTruncatesRawTimestampString() {
    assertEquals("2026-05-06", GeneralLedgerReportHandler.toIsoDate("2026-05-06 00:00:00.0"));
  }

  @Test
  @DisplayName("toIsoDate returns an empty string for null")
  void toIsoDateReturnsEmptyForNull() {
    assertEquals("", GeneralLedgerReportHandler.toIsoDate(null));
  }
}
