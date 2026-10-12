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
import static org.mockito.ArgumentMatchers.contains;
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
import org.openbravo.model.financialmgmt.calendar.Year;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Unit tests for {@link BalanceSheetReportHandler} (ETP-5483 slice 4) — the MCP report tool
 * {@code generate_balance_sheet}, a faithful Java port of {@code
 * artifacts/balance-sheet/report-contract.json}'s {@code sql.query}/{@code sql.operandsQuery} plus
 * {@link AccountReportTree} (whose own roll-up/formula rules are covered by
 * {@code AccountReportTreeTest}, not here).
 *
 * @covers com.etendoerp.go.schemaforge.BalanceSheetReportHandler
 * @covers com.etendoerp.go.schemaforge.AbstractAccountTreeReportHandler
 */
class BalanceSheetReportHandlerTest {

  private final BalanceSheetReportHandler handler = new BalanceSheetReportHandler();

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
    Named named = BalanceSheetReportHandler.class.getAnnotation(Named.class);
    assertNotNull(named);
    assertEquals("balanceSheetReportHandler", named.value());
  }

  // -------------------------------------------------------------------------
  // reportParameters
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("declares only yearId as required")
  void reportParametersDeclaresOnlyYearIdRequired() {
    List<NeoReportParam> params = handler.reportParameters().orElseThrow();

    NeoReportParam yearId = params.stream().filter(p -> "yearId".equals(p.getName()))
        .findFirst().orElseThrow();
    assertTrue(yearId.isRequired());

    long otherRequiredCount = params.stream()
        .filter(p -> !"yearId".equals(p.getName()))
        .filter(NeoReportParam::isRequired)
        .count();
    assertEquals(0, otherRequiredCount, "only yearId may be required — referenceYearId is "
        + "conditionally required (compareTo), which the schema cannot express");
  }

  @Test
  @DisplayName("declares accountLevel as a closed C/D/E/S set")
  void reportParametersDeclaresAccountLevelEnum() {
    NeoReportParam accountLevel = handler.reportParameters().orElseThrow().stream()
        .filter(p -> "accountLevel".equals(p.getName())).findFirst().orElseThrow();

    assertEquals(List.of("C", "D", "E", "S"), accountLevel.getAllowedValues());
  }

  @Test
  @DisplayName("declares dateFrom and fromReferenceDate as accepted but documents they have no effect")
  void reportParametersDocumentsUnusedDateParams() {
    List<NeoReportParam> params = handler.reportParameters().orElseThrow();

    NeoReportParam dateFrom = params.stream().filter(p -> "dateFrom".equals(p.getName()))
        .findFirst().orElseThrow();
    NeoReportParam fromReferenceDate = params.stream()
        .filter(p -> "fromReferenceDate".equals(p.getName())).findFirst().orElseThrow();

    assertTrue(dateFrom.getDescription().contains("NO EFFECT"));
    assertTrue(fromReferenceDate.getDescription().contains("NO EFFECT"));
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
      assertEquals("Balance Sheet", response.getBody().getString("name"));
      assertTrue(response.getBody().getJSONArray("parameters").length() > 0);
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

      // An empty body would fail yearId validation too, but the gate must be checked first.
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
  // Validation — 400s, all reachable without touching the DB (pure input checks first)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST without yearId is refused with 400")
  void postWithoutYearIdIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      NeoResponse response = handler.handle(context("POST", new JSONObject()));

      assertEquals(400, response.getHttpStatus());
      assertEquals("year_id_required", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST with a malformed yearId is refused with 400")
  void postWithMalformedYearIdIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("yearId", "not an id!");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("year_id_invalid", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST with an invalid accountLevel is refused with 400")
  void postWithInvalidAccountLevelIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("yearId", "year1");
      body.put("accountLevel", "X");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("account_level_invalid", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST with a malformed dateTo is refused with 400")
  void postWithMalformedDateToIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("yearId", "year1");
      body.put("dateTo", "24/09/2026");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("dateTo_invalid", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST with compareTo=true and no referenceYearId is refused with 400")
  void postWithCompareToAndNoReferenceYearIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("yearId", "year1");
      body.put("compareTo", true);

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("reference_year_id_required", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST with compareTo=true and a malformed referenceYearId is refused with 400")
  void postWithCompareToAndMalformedReferenceYearIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("yearId", "year1");
      body.put("compareTo", true);
      body.put("referenceYearId", "not an id!");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("reference_year_id_invalid", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST with compareTo=false ignores an absent referenceYearId entirely")
  void postWithCompareToFalseDoesNotRequireReferenceYear() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      stubHappyPathObContextAndDal(contextMock, obDalMock, Collections.emptyList());

      JSONObject body = new JSONObject();
      body.put("yearId", "year1");
      body.put("compareTo", false);

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(200, response.getHttpStatus(), () -> describeFailure(response));
    }
  }

  @Test
  @DisplayName("POST with a malformed orgId is refused with 400")
  void postWithMalformedOrgIdIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      Organization sessionOrg = mock(Organization.class);
      when(sessionOrg.getId()).thenReturn("sessionOrg");
      OBContext obContext = mock(OBContext.class);
      when(obContext.getCurrentOrganization()).thenReturn(sessionOrg);
      contextMock.when(OBContext::getOBContext).thenReturn(obContext);

      JSONObject body = new JSONObject();
      body.put("yearId", "year1");
      body.put("orgId", "not an id!");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("org_id_invalid", response.getBody().getString("error"));
    }
  }

  // -------------------------------------------------------------------------
  // Client scoping — from OBContext, never from the request body
  // -------------------------------------------------------------------------

  /**
   * The core scoping guarantee, mirroring {@code TrialBalanceReportHandlerTest}'s own test of the
   * same name: even if a caller stuffs an {@code ad_client_id}-shaped value somewhere in the body,
   * the query is bound to the SESSION's client — there is no body key this handler reads for it at
   * all.
   */
  @Test
  @DisplayName("binds the query to OBContext's client, with no client id read from the body")
  void clientIdComesFromObContextNotBody() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      NativeQuery<Object[]> query = stubHappyPathObContextAndDal(contextMock, obDalMock,
          Collections.emptyList());

      JSONObject body = new JSONObject();
      body.put("yearId", "year1");
      body.put("clientId", "attacker-supplied-client-id");
      body.put("ad_client_id", "attacker-supplied-client-id");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(200, response.getHttpStatus(), () -> describeFailure(response));

      ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
      ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
      verify(query, atLeastOnce()).setParameter(nameCaptor.capture(), valueCaptor.capture());

      int clientIdParamIndex = nameCaptor.getAllValues().indexOf("clientId");
      assertTrue(clientIdParamIndex >= 0, "the query must bind a clientId parameter");
      assertEquals("sessionClientId1", valueCaptor.getAllValues().get(clientIdParamIndex),
          "clientId must come from OBContext's current client, never from the request body");

      JSONObject responseData = response.getBody().getJSONObject("response");
      assertFalse(responseData.getJSONObject("meta").toString().contains("attacker-supplied"),
          "the body's spoofed client id must never reach the response either");
    }
  }

  @Test
  @DisplayName("an empty result set still returns a 200 with an empty data array")
  void emptyResultReturnsEmptyDataArray() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      stubHappyPathObContextAndDal(contextMock, obDalMock, Collections.emptyList());

      JSONObject body = new JSONObject();
      body.put("yearId", "year1");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(200, response.getHttpStatus(), () -> describeFailure(response));
      JSONObject responseData = response.getBody().getJSONObject("response");
      assertEquals(0, responseData.getInt("count"));
      assertEquals(0, responseData.getJSONArray("data").length());
    }
  }

  // -------------------------------------------------------------------------
  // ShowValueCond columns (ETP-5662)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("node SQL selects showvaluecond/issummary AFTER own_amt_ref and groups by them")
  void selectsShowValueCondColumnsAfterOwnAmtRef() {
    String cols = handler.ownAmountColumns();
    assertTrue(cols.indexOf("own_amt_ref") < cols.indexOf("ev.showvaluecond"),
        "positional COL_* mapping relies on these coming last");
    assertTrue(cols.indexOf("ev.showvaluecond") < cols.indexOf("ev.issummary"));
    String groupBy = handler.outerGroupBy();
    assertTrue(groupBy.contains("ev.showvaluecond") && groupBy.contains("ev.issummary"),
        "aggregated query must group by the new columns");
  }

  /**
   * One raw node row in the positional shape the node SQL returns: node_id, parent_id, depth,
   * sort_path, group_name, value, name, elementlevel, isalwaysshown, accountsign, own_amt,
   * own_amt_ref, showvaluecond, issummary. Character columns come back from Hibernate as
   * {@link Character}, so the flags are passed that way.
   */
  private static Object[] rawNode(String id, String parent, String sortPath, String level,
      String ownAmt, Character showValueCond, char isSummary) {
    return new Object[] { id, parent, 0, sortPath, "Activo", id, "Name " + id, level, 'N', "D",
        new BigDecimal(ownAmt), BigDecimal.ZERO, showValueCond, isSummary };
  }

  private static BigDecimal amountOf(JSONArray data,
      String nodeId) throws Exception {
    for (int i = 0; i < data.length(); i++) {
      JSONObject row = data.getJSONObject(i);
      if (nodeId.equals(row.getString("node_id"))) {
        return new BigDecimal(row.get("amount").toString());
      }
    }
    throw new AssertionError("row " + nodeId + " not in " + data);
  }

  /**
   * Guards the positional {@code COL_SHOW_VALUE_COND = 12} / {@code COL_IS_SUMMARY = 13} mapping:
   * raw rows go through the real {@code queryNodeRows}/{@code queryOperandRows} path and the
   * clamp must show up in the response. With the two indexes swapped, {@code S} would not be
   * clamped and {@code H} would read -1 instead of 2.
   */
  @Test
  @DisplayName("maps showvaluecond/issummary by position so a P-summary node is clamped")
  void mapsShowValueCondAndIsSummaryColumns() throws Exception {
    List<Object[]> nodeRows = List.of(
        rawNode("R", "", "1", "E", "0", null, 'Y'),
        rawNode("H", "R", "1.1", "E", "0", null, 'Y'),
        // P-summary with a negative raw value: clamped to 0, and its child shows 0 (cascade).
        rawNode("S", "H", "1.1.1", "C", "0", 'P', 'Y'),
        rawNode("K", "S", "1.1.1.1", "S", "-3", null, 'N'),
        // P but NOT a summary: passes through, so issummary is really read from its own column.
        rawNode("T", "H", "1.1.2", "C", "-2", 'P', 'N'),
        rawNode("L", "H", "1.1.3", "C", "4", null, 'N'),
        // Formula over S: reads S's RAW value (-3), not its clamped 0.
        rawNode("F", "R", "1.2", "C", "0", null, 'Y'));
    List<Object[]> operandRows = List.<Object[]>of(new Object[] { "F", "S", 1, 10 });

    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      stubHappyPathObContextAndDal(contextMock, obDalMock, nodeRows, operandRows);

      JSONObject body = new JSONObject();
      body.put("yearId", "year1");
      body.put("accountLevel", "S");
      body.put("showOnlyAccountsWithValue", false);

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(200, response.getHttpStatus(), () -> describeFailure(response));
      JSONArray data =
          response.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(0, amountOf(data, "S").signum(), "P-summary with -3 must be clamped to 0");
      assertEquals(0, amountOf(data, "K").signum(), "descendant of a reset node displays 0");
      assertEquals(0, amountOf(data, "T").compareTo(new BigDecimal("-2")),
          "a non-summary node is never clamped");
      assertEquals(0, amountOf(data, "H").compareTo(new BigDecimal("2")),
          "H = S clamped (0) + T (-2) + L (4)");
      assertEquals(0, amountOf(data, "F").compareTo(new BigDecimal("-3")),
          "the formula reads S's raw value through the operand query mapping");
    }
  }

  // -------------------------------------------------------------------------
  // Test helpers
  // -------------------------------------------------------------------------

  private static String describeFailure(NeoResponse response) {
    try {
      return "Unexpected status " + response.getHttpStatus() + ": " + response.getBody();
    } catch (Exception e) {
      return "Unexpected non-200 response, and the body could not be read either";
    }
  }

  /**
   * Wires the happy path through {@link OBContext}/{@link OBDal}: a session client + organization,
   * an organization whose general ledger resolves an accounting schema, and a resolvable
   * {@link Year}. Every {@code createNativeQuery} call returns the SAME mocked query, configured to
   * return {@code rows} for every {@code list()} call — sufficient for the node-rows AND
   * operand-rows queries this handler issues.
   *
   * @return the shared mocked {@link NativeQuery}, for assertions on bound parameters
   */
  private static NativeQuery<Object[]> stubHappyPathObContextAndDal(
      MockedStatic<OBContext> contextMock, MockedStatic<OBDal> obDalMock, List<Object[]> rows) {
    return stubHappyPathObContextAndDal(contextMock, obDalMock, rows, null);
  }

  /**
   * Same as {@link #stubHappyPathObContextAndDal(MockedStatic, MockedStatic, List)}, but when
   * {@code operandRows} is not {@code null} the operands query ({@code c_elementvalue_operand})
   * gets its own mocked query returning them, while the node query returns {@code rows}.
   */
  @SuppressWarnings("unchecked")
  private static NativeQuery<Object[]> stubHappyPathObContextAndDal(
      MockedStatic<OBContext> contextMock, MockedStatic<OBDal> obDalMock, List<Object[]> rows,
      List<Object[]> operandRows) {
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
    contextMock.when(OBContext::getOBContext).thenReturn(obContext);

    Session session = mock(Session.class);
    NativeQuery<Object[]> query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.list()).thenReturn((List) rows);
    if (operandRows != null) {
      NativeQuery<Object[]> operandQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(contains(
          "c_elementvalue_operand"))).thenReturn(operandQuery);
      when(operandQuery.setParameter(anyString(), any())).thenReturn(operandQuery);
      when(operandQuery.list()).thenReturn((List) operandRows);
    }

    Year year = mock(Year.class);

    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    when(dal.get(Organization.class, "sessionOrgId1")).thenReturn(org);
    when(dal.get(Year.class, "year1")).thenReturn(year);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);

    return query;
  }
}
