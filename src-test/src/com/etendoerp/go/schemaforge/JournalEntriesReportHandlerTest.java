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
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Unit tests for {@link JournalEntriesReportHandler} (ETP-5483 slice 3) — the MCP report tool
 * {@code generate_report_journal_entries}, a faithful Java port of {@code
 * artifacts/report-journal-entries/report-contract.json}'s {@code sql.query} plus
 * {@link JournalEntriesGrouping} (whose own nesting/truncation rules are covered by
 * {@code JournalEntriesGroupingTest}, not here).
 */
class JournalEntriesReportHandlerTest {

  private final JournalEntriesReportHandler handler = new JournalEntriesReportHandler();

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
    Named named = JournalEntriesReportHandler.class.getAnnotation(Named.class);
    assertNotNull(named);
    assertEquals("journalEntriesReportHandler", named.value());
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
  @DisplayName("declares limit as a described integer parameter")
  void reportParametersDeclaresLimit() {
    NeoReportParam limit = handler.reportParameters().orElseThrow().stream()
        .filter(p -> "limit".equals(p.getName())).findFirst().orElseThrow();

    assertEquals(NeoReportParam.TYPE_INTEGER, limit.getType());
    assertFalse(limit.isRequired());
  }

  @Test
  @DisplayName("declares every show*Entries toggle plus showDimensions/showEntryDescription")
  void reportParametersDeclaresAllToggles() {
    List<String> names = handler.reportParameters().orElseThrow().stream()
        .map(NeoReportParam::getName).toList();

    for (String expected : List.of("showRegularEntries", "showPlClosingEntries",
        "showClosingEntries", "showOpeningEntries", "showDivideUpEntries", "showDimensions",
        "showEntryDescription")) {
      assertTrue(names.contains(expected), "missing declared parameter: " + expected);
    }
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
      assertEquals("Journal Entries", response.getBody().getString("name"));
      assertTrue(response.getBody().getJSONArray("parameters").length() > 0);
    }
  }

  // -------------------------------------------------------------------------
  // Validation — 400s, all reachable without touching the DB
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
  @DisplayName("POST with a zero/negative limit is refused with 400")
  void postWithInvalidLimitIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("dateFrom", "2026-01-01");
      body.put("dateTo", "2026-09-24");
      body.put("limit", 0);

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("limit_invalid", response.getBody().getString("error"));
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
  // Client scoping — from OBContext, never from the request body
  // -------------------------------------------------------------------------

  /**
   * The core scoping guarantee, mirroring {@code TrialBalanceReportHandlerTest}'s own: even if a
   * caller stuffs an {@code ad_client_id}-shaped value somewhere in the body, the query is bound
   * to the SESSION's client. This handler runs TWO native queries (the main entries query plus
   * the entries-count query), so both mocked queries return sane defaults and the clientId bind
   * is asserted on the main query.
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

    Language language = mock(Language.class);
    when(language.getLanguage()).thenReturn("en_US");

    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getCurrentOrganization()).thenReturn(org);
    when(obContext.getLanguage()).thenReturn(language);

    Session session = mock(Session.class);
    NativeQuery<Object[]> mainQuery = mock(NativeQuery.class);
    when(mainQuery.setParameter(anyString(), any())).thenReturn(mainQuery);
    when(mainQuery.list()).thenReturn(Collections.emptyList());

    NativeQuery<Number> countQuery = mock(NativeQuery.class);
    when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
    when(countQuery.uniqueResult()).thenReturn(0);

    // The count query's SQL starts with "SELECT COUNT(DISTINCT", the main query's with "WITH je".
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
  // Entry-type toggle fallback — pure bind-value assertion (no DB needed beyond the mock)
  // -------------------------------------------------------------------------

  /**
   * When all five {@code show*Entries} toggles are explicitly false, the handler still binds
   * them as false — {@link JournalEntriesReportHandler#buildEntryTypeFilterSql()}'s own fallback
   * clause (not exercised here directly, since it is private SQL text) handles falling back to
   * regular entries only at the DB level. This test asserts the handler passes the caller's
   * explicit choice through untouched, i.e. it does not silently coerce them to true.
   */
  @Test
  @DisplayName("explicit false toggles are bound as false, not silently defaulted to true")
  void explicitFalseTogglesAreBoundAsFalse() throws Exception {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("sessionClientId1");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("sessionOrgId1");
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getId()).thenReturn("schemaId1");
    when(org.getGeneralLedger()).thenReturn(schema);
    Language language = mock(Language.class);
    when(language.getLanguage()).thenReturn("en_US");

    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getCurrentOrganization()).thenReturn(org);
    when(obContext.getLanguage()).thenReturn(language);

    Session session = mock(Session.class);
    NativeQuery<Object[]> mainQuery = mock(NativeQuery.class);
    when(mainQuery.setParameter(anyString(), any())).thenReturn(mainQuery);
    when(mainQuery.list()).thenReturn(Collections.emptyList());
    NativeQuery<Number> countQuery = mock(NativeQuery.class);
    when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
    when(countQuery.uniqueResult()).thenReturn(0);
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
      body.put("showRegularEntries", false);
      body.put("showPlClosingEntries", false);
      body.put("showClosingEntries", false);
      body.put("showOpeningEntries", false);
      body.put("showDivideUpEntries", false);

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(200, response.getHttpStatus());

      ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
      ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
      verify(mainQuery, atLeastOnce())
          .setParameter(nameCaptor.capture(), valueCaptor.capture());

      List<String> names = nameCaptor.getAllValues();
      for (String toggle : List.of("showRegular", "showPlClosing", "showClosing", "showOpening",
          "showDivideUp")) {
        int idx = names.indexOf(toggle);
        assertTrue(idx >= 0, "must bind " + toggle);
        assertEquals(Boolean.FALSE, valueCaptor.getAllValues().get(idx));
      }
    }
  }

  // -------------------------------------------------------------------------
  // toIsoDate — fact_acct.dateacct rendered as yyyy-MM-dd, not Timestamp#toString()
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toIsoDate renders a midnight Timestamp as yyyy-MM-dd, not '... 00:00:00.0'")
  void toIsoDateRendersMidnightTimestampAsPlainDate() {
    Timestamp value = Timestamp.valueOf("2026-01-01 00:00:00");

    assertEquals("2026-01-01", JournalEntriesReportHandler.toIsoDate(value));
  }

  @Test
  @DisplayName("toIsoDate renders a late-in-the-day Timestamp with no day shift")
  void toIsoDateRendersLateTimestampWithNoDayShift() {
    Timestamp value = Timestamp.valueOf("2026-03-31 23:59:59");

    assertEquals("2026-03-31", JournalEntriesReportHandler.toIsoDate(value));
  }

  @Test
  @DisplayName("toIsoDate renders a java.sql.Date as yyyy-MM-dd")
  void toIsoDateRendersSqlDate() {
    Date value = Date.valueOf("2026-02-15");

    assertEquals("2026-02-15", JournalEntriesReportHandler.toIsoDate(value));
  }

  @Test
  @DisplayName("toIsoDate renders a LocalDateTime as yyyy-MM-dd")
  void toIsoDateRendersLocalDateTime() {
    LocalDateTime value = LocalDateTime.of(2026, 4, 7, 13, 45, 0);

    assertEquals("2026-04-07", JournalEntriesReportHandler.toIsoDate(value));
  }

  @Test
  @DisplayName("toIsoDate renders a LocalDate as yyyy-MM-dd")
  void toIsoDateRendersLocalDate() {
    LocalDate value = LocalDate.of(2026, 6, 30);

    assertEquals("2026-06-30", JournalEntriesReportHandler.toIsoDate(value));
  }

  @Test
  @DisplayName("toIsoDate truncates a raw timestamp-shaped String to its date prefix")
  void toIsoDateTruncatesRawTimestampString() {
    assertEquals("2026-05-06", JournalEntriesReportHandler.toIsoDate("2026-05-06 00:00:00.0"));
  }

  @Test
  @DisplayName("toIsoDate returns a short, non-date String unchanged")
  void toIsoDateReturnsShortStringUnchanged() {
    assertEquals("abc", JournalEntriesReportHandler.toIsoDate("abc"));
  }

  @Test
  @DisplayName("toIsoDate returns an empty string for null")
  void toIsoDateReturnsEmptyForNull() {
    assertEquals("", JournalEntriesReportHandler.toIsoDate(null));
  }

  // -------------------------------------------------------------------------
  // buildDataArray — MCP response shape (ETP-5483: isreturn field)
  // -------------------------------------------------------------------------

  /**
   * Builds a single-line {@link JournalEntriesGrouping.Row} with every field explicit, so each
   * test only varies the parameter it cares about. Mirrors {@code JournalEntriesGroupingTest}'s
   * own {@code row} helper, plus the fields {@code buildDataArray} itself reads (docbasetype,
   * isReturn, recordId, adTableId).
   */
  private static JournalEntriesGrouping.Row buildRow(String factAcctGroupId, String dateacct,
      long entryNo, String docbasetype, boolean isReturn, String recordId, String adTableId,
      String accountNo, String accountName, String debit, String credit) {
    return new JournalEntriesGrouping.Row(dateacct, entryNo, "Receipt", docbasetype, isReturn,
        "goods-receipt", "doc-1", null, null, "Some description", "Acme", "Widget", "Proj A",
        "CC1", factAcctGroupId, recordId, adTableId, accountNo, accountName,
        new BigDecimal(debit), new BigDecimal(credit));
  }

  @Test
  @DisplayName("a return receipt (MMR, isReturn=true) renders isreturn: true")
  void buildDataArrayRendersIsReturnTrueForReturnReceipt() throws Exception {
    List<JournalEntriesGrouping.Row> rows = List.of(
        buildRow("grp-1", "2026-01-05", 1, "MMR", true, "rec-1", "table-99", "430", "Customers",
            "100", "0"));
    List<JournalEntriesGrouping.Entry> entries = JournalEntriesGrouping.nest(rows);

    JSONArray data = JournalEntriesReportHandler.buildDataArray(entries, false, false);

    assertEquals("MMR", data.getJSONObject(0).getString("docbasetype"));
    assertTrue(data.getJSONObject(0).getBoolean("isreturn"));
  }

  @Test
  @DisplayName("a regular receipt (MMR, isReturn=false) renders isreturn: false")
  void buildDataArrayRendersIsReturnFalseForRegularReceipt() throws Exception {
    List<JournalEntriesGrouping.Row> rows = List.of(
        buildRow("grp-1", "2026-01-05", 1, "MMR", false, "rec-1", "table-99", "430", "Customers",
            "100", "0"));
    List<JournalEntriesGrouping.Entry> entries = JournalEntriesGrouping.nest(rows);

    JSONArray data = JournalEntriesReportHandler.buildDataArray(entries, false, false);

    assertEquals("MMR", data.getJSONObject(0).getString("docbasetype"));
    assertFalse(data.getJSONObject(0).getBoolean("isreturn"));
  }

  @Test
  @DisplayName("renders header fields and lines carrying account_no/account_name/amtacctdr/amtacctcr in order")
  void buildDataArrayRendersHeaderFieldsAndOrderedLines() throws Exception {
    List<JournalEntriesGrouping.Row> rows = List.of(
        buildRow("grp-1", "2026-01-05", 1, "ARI", false, "rec-1", "table-99", "430", "Customers",
            "100", "0"),
        buildRow("grp-1", "2026-01-05", 1, "ARI", false, "rec-1", "table-99", "700", "Sales",
            "0", "100"));
    List<JournalEntriesGrouping.Entry> entries = JournalEntriesGrouping.nest(rows);

    JSONObject entry = JournalEntriesReportHandler.buildDataArray(entries, false, false)
        .getJSONObject(0);

    assertEquals(1, entry.getLong("entry_no"));
    assertEquals("2026-01-05", entry.getString("dateacct"));
    assertEquals("Receipt", entry.getString("document_type"));
    assertEquals("ARI", entry.getString("docbasetype"));
    assertEquals("goods-receipt", entry.getString("doc_window"));
    assertEquals("doc-1", entry.getString("doc_record_id"));
    assertEquals("rec-1", entry.getString("record_id"));
    assertEquals("table-99", entry.getString("ad_table_id"));

    JSONArray lines = entry.getJSONArray("lines");
    assertEquals(2, lines.length());

    JSONObject firstLine = lines.getJSONObject(0);
    assertEquals("430", firstLine.getString("account_no"));
    assertEquals("Customers", firstLine.getString("account_name"));
    assertEquals(100.0, firstLine.getDouble("amtacctdr"), 0.0001);
    assertEquals(0.0, firstLine.getDouble("amtacctcr"), 0.0001);

    JSONObject secondLine = lines.getJSONObject(1);
    assertEquals("700", secondLine.getString("account_no"));
    assertEquals("Sales", secondLine.getString("account_name"));
    assertEquals(0.0, secondLine.getDouble("amtacctdr"), 0.0001);
    assertEquals(100.0, secondLine.getDouble("amtacctcr"), 0.0001);
  }

  @Test
  @DisplayName("showDimensions=false omits bpname/productname/projectname/costcentername from lines")
  void buildDataArrayOmitsDimensionsWhenDisabled() throws Exception {
    List<JournalEntriesGrouping.Row> rows = List.of(
        buildRow("grp-1", "2026-01-05", 1, "ARI", false, "rec-1", "table-99", "430", "Customers",
            "100", "0"));
    List<JournalEntriesGrouping.Entry> entries = JournalEntriesGrouping.nest(rows);

    JSONObject line = JournalEntriesReportHandler.buildDataArray(entries, false, false)
        .getJSONObject(0).getJSONArray("lines").getJSONObject(0);

    assertFalse(line.has("bpname"));
    assertFalse(line.has("productname"));
    assertFalse(line.has("projectname"));
    assertFalse(line.has("costcentername"));
  }

  @Test
  @DisplayName("showDimensions=true includes bpname/productname/projectname/costcentername on lines")
  void buildDataArrayIncludesDimensionsWhenEnabled() throws Exception {
    List<JournalEntriesGrouping.Row> rows = List.of(
        buildRow("grp-1", "2026-01-05", 1, "ARI", false, "rec-1", "table-99", "430", "Customers",
            "100", "0"));
    List<JournalEntriesGrouping.Entry> entries = JournalEntriesGrouping.nest(rows);

    JSONObject line = JournalEntriesReportHandler.buildDataArray(entries, true, false)
        .getJSONObject(0).getJSONArray("lines").getJSONObject(0);

    assertEquals("Acme", line.getString("bpname"));
    assertEquals("Widget", line.getString("productname"));
    assertEquals("Proj A", line.getString("projectname"));
    assertEquals("CC1", line.getString("costcentername"));
  }

  @Test
  @DisplayName("showEntryDescription=false omits entry_description from the entry")
  void buildDataArrayOmitsEntryDescriptionWhenDisabled() throws Exception {
    List<JournalEntriesGrouping.Row> rows = List.of(
        buildRow("grp-1", "2026-01-05", 1, "ARI", false, "rec-1", "table-99", "430", "Customers",
            "100", "0"));
    List<JournalEntriesGrouping.Entry> entries = JournalEntriesGrouping.nest(rows);

    JSONObject entry = JournalEntriesReportHandler.buildDataArray(entries, false, false)
        .getJSONObject(0);

    assertFalse(entry.has("entry_description"));
  }

  @Test
  @DisplayName("showEntryDescription=true includes entry_description on the entry")
  void buildDataArrayIncludesEntryDescriptionWhenEnabled() throws Exception {
    List<JournalEntriesGrouping.Row> rows = List.of(
        buildRow("grp-1", "2026-01-05", 1, "ARI", false, "rec-1", "table-99", "430", "Customers",
            "100", "0"));
    List<JournalEntriesGrouping.Entry> entries = JournalEntriesGrouping.nest(rows);

    JSONObject entry = JournalEntriesReportHandler.buildDataArray(entries, false, true)
        .getJSONObject(0);

    assertEquals("Some description", entry.getString("entry_description"));
  }
}
