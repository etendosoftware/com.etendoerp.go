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
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
 * Unit tests for {@link AbstractAccountTreeReportHandler}: the parameters, validation, tenant
 * checks, client scoping and node-row mapping that {@link BalanceSheetReportHandler} and
 * {@link ProfitLossReportHandler} inherit. Every test runs on both subclasses; what each one adds
 * (its SQL, its date semantics) stays in its own test class.
 *
 * @covers com.etendoerp.go.schemaforge.AbstractAccountTreeReportHandler
 * @covers com.etendoerp.go.schemaforge.AbstractSqlReportHandler
 */
class AbstractAccountTreeReportHandlerTest {

  static Stream<Named<AbstractAccountTreeReportHandler>> handlers() {
    return Stream.of(
        Named.of("BalanceSheetReportHandler", new BalanceSheetReportHandler()),
        Named.of("ProfitLossReportHandler", new ProfitLossReportHandler()));
  }

  static NeoContext context(String method, JSONObject body) {
    return NeoContext.builder().specName("report").entityName("report").httpMethod(method)
        .requestBody(body).build();
  }

  // -------------------------------------------------------------------------
  // reportParameters
  // -------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("declares accountLevel as a closed C/D/E/S set")
  void reportParametersDeclaresAccountLevelEnum(AbstractAccountTreeReportHandler handler) {
    NeoReportParam accountLevel = handler.reportParameters().orElseThrow().stream()
        .filter(p -> "accountLevel".equals(p.getName())).findFirst().orElseThrow();

    assertEquals(List.of("C", "D", "E", "S"), accountLevel.getAllowedValues());
  }

  // -------------------------------------------------------------------------
  // Validation — 400s, all reachable without touching the DB (pure input checks first)
  // -------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("POST without yearId is refused with 400")
  void postWithoutYearIdIsRefused(AbstractAccountTreeReportHandler handler) throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      NeoResponse response = handler.handle(context("POST", new JSONObject()));

      assertEquals(400, response.getHttpStatus());
      assertEquals("year_id_required", response.getBody().getString("error"));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("POST with a malformed yearId is refused with 400")
  void postWithMalformedYearIdIsRefused(AbstractAccountTreeReportHandler handler)
      throws Exception {
    assertRefused(handler, new JSONObject().put("yearId", "not an id!"), "year_id_invalid");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("POST with an invalid accountLevel is refused with 400")
  void postWithInvalidAccountLevelIsRefused(AbstractAccountTreeReportHandler handler)
      throws Exception {
    assertRefused(handler, new JSONObject().put("yearId", "year1").put("accountLevel", "X"),
        "account_level_invalid");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("POST with a malformed dateTo is refused with 400")
  void postWithMalformedDateToIsRefused(AbstractAccountTreeReportHandler handler)
      throws Exception {
    assertRefused(handler, new JSONObject().put("yearId", "year1").put("dateTo", "24/09/2026"),
        "dateTo_invalid");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("POST with compareTo=true and no referenceYearId is refused with 400")
  void postWithCompareToAndNoReferenceYearIsRefused(AbstractAccountTreeReportHandler handler)
      throws Exception {
    assertRefused(handler, new JSONObject().put("yearId", "year1").put("compareTo", true),
        "reference_year_id_required");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("POST with compareTo=true and a malformed referenceYearId is refused with 400")
  void postWithCompareToAndMalformedReferenceYearIsRefused(
      AbstractAccountTreeReportHandler handler) throws Exception {
    assertRefused(handler, new JSONObject().put("yearId", "year1").put("compareTo", true)
        .put("referenceYearId", "not an id!"), "reference_year_id_invalid");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("POST with compareTo=false ignores an absent referenceYearId entirely")
  void postWithCompareToFalseDoesNotRequireReferenceYear(
      AbstractAccountTreeReportHandler handler) throws Exception {
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

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("POST with a malformed orgId is refused with 400")
  void postWithMalformedOrgIdIsRefused(AbstractAccountTreeReportHandler handler)
      throws Exception {
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
  // Tenant isolation (ETP-5793)
  // -------------------------------------------------------------------------

  /**
   * ETP-5793: another tenant's year answers exactly like an unknown one
   * ({@code AbstractAccountTreeReportHandler#validateYearsResolved}).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("POST with another tenant's yearId answers exactly like an unknown one")
  void postWithForeignYearAnswersLikeUnknown(AbstractAccountTreeReportHandler handler)
      throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      stubHappyPathObContextAndDal(contextMock, obDalMock, Collections.emptyList());
      stubForeignYear();

      NeoResponse foreign = handler.handle(context("POST", new JSONObject().put("yearId", "foreignYear1")));
      NeoResponse unknown = handler.handle(context("POST", new JSONObject().put("yearId", "unknownYear1")));

      assertEquals(422, foreign.getHttpStatus());
      assertEquals("year_not_resolved", foreign.getBody().getString("error"));
      assertEquals(unknown.getBody().toString().replace("unknownYear1", "foreignYear1"),
          foreign.getBody().toString());
    }
  }

  /** ETP-5793: the comparison year gets the same tenant check as the main one. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("POST with another tenant's referenceYearId answers exactly like an unknown one")
  void postWithForeignReferenceYearAnswersLikeUnknown(AbstractAccountTreeReportHandler handler)
      throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      stubHappyPathObContextAndDal(contextMock, obDalMock, Collections.emptyList());
      stubForeignYear();

      NeoResponse foreign = handler.handle(context("POST", new JSONObject().put("yearId", "year1")
          .put("compareTo", true).put("referenceYearId", "foreignYear1")));
      NeoResponse unknown = handler.handle(context("POST", new JSONObject().put("yearId", "year1")
          .put("compareTo", true).put("referenceYearId", "unknownYear1")));

      assertEquals(422, foreign.getHttpStatus());
      assertEquals("reference_year_not_resolved", foreign.getBody().getString("error"));
      assertEquals(unknown.getBody().toString().replace("unknownYear1", "foreignYear1"),
          foreign.getBody().toString());
    }
  }

  /**
   * ETP-5793: another tenant's org answers exactly like an unknown one
   * ({@code AbstractSqlReportHandler#validateOrgId}, shared by every SQL report).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("POST with another tenant's orgId answers exactly like an unknown one")
  void postWithForeignOrgAnswersLikeUnknown(AbstractAccountTreeReportHandler handler)
      throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      stubHappyPathObContextAndDal(contextMock, obDalMock, Collections.emptyList());
      Client otherClient = mock(Client.class);
      when(otherClient.getId()).thenReturn("otherClientId");
      Organization foreignOrg = mock(Organization.class);
      when(foreignOrg.getClient()).thenReturn(otherClient);
      OBDal dal = OBDal.getInstance();
      when(dal.get(Organization.class, "foreignOrg1")).thenReturn(foreignOrg);

      NeoResponse foreign = handler.handle(context("POST",
          new JSONObject().put("yearId", "year1").put("orgId", "foreignOrg1")));
      NeoResponse unknown = handler.handle(context("POST",
          new JSONObject().put("yearId", "year1").put("orgId", "unknownOrg1")));

      assertEquals(400, foreign.getHttpStatus());
      assertEquals("organization_not_resolved", foreign.getBody().getString("error"));
      assertEquals(unknown.getBody().toString().replace("unknownOrg1", "foreignOrg1"),
          foreign.getBody().toString());
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
  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("binds the query to OBContext's client, with no client id read from the body")
  void clientIdComesFromObContextNotBody(AbstractAccountTreeReportHandler handler)
      throws Exception {
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

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("an empty result set still returns a 200 with an empty data array")
  void emptyResultReturnsEmptyDataArray(AbstractAccountTreeReportHandler handler)
      throws Exception {
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

  /**
   * Guards the positional {@code COL_SHOW_VALUE_COND = 12} / {@code COL_IS_SUMMARY = 13} mapping:
   * raw rows go through the real {@code queryNodeRows}/{@code queryOperandRows} path and the
   * clamp must show up in the response. With the two indexes swapped, {@code S} would not be
   * clamped and {@code H} would read -1 instead of 2.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  @DisplayName("maps showvaluecond/issummary by position so a P-summary node is clamped")
  void mapsShowValueCondAndIsSummaryColumns(AbstractAccountTreeReportHandler handler)
      throws Exception {
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

  /** Asserts a POST with the grant and {@code body} is refused with 400 and {@code error}. */
  private static void assertRefused(AbstractAccountTreeReportHandler handler, JSONObject body,
      String error) throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals(error, response.getBody().getString("error"));
    }
  }

  /** Makes {@code foreignYear1} resolve to a year of another client. */
  private static void stubForeignYear() {
    Client otherClient = mock(Client.class);
    when(otherClient.getId()).thenReturn("otherClientId");
    Year foreignYear = mock(Year.class);
    when(foreignYear.getClient()).thenReturn(otherClient);
    OBDal dal = OBDal.getInstance();
    when(dal.get(Year.class, "foreignYear1")).thenReturn(foreignYear);
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

  private static BigDecimal amountOf(JSONArray data, String nodeId) throws Exception {
    for (int i = 0; i < data.length(); i++) {
      JSONObject row = data.getJSONObject(i);
      if (nodeId.equals(row.getString("node_id"))) {
        return new BigDecimal(row.get("amount").toString());
      }
    }
    throw new AssertionError("row " + nodeId + " not in " + data);
  }

  static String describeFailure(NeoResponse response) {
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
   * operand-rows queries these handlers issue.
   *
   * @return the shared mocked {@link NativeQuery}, for assertions on bound parameters
   */
  static NativeQuery<Object[]> stubHappyPathObContextAndDal(
      MockedStatic<OBContext> contextMock, MockedStatic<OBDal> obDalMock, List<Object[]> rows) {
    return stubHappyPathObContextAndDal(contextMock, obDalMock, rows, null);
  }

  /**
   * Same as {@link #stubHappyPathObContextAndDal(MockedStatic, MockedStatic, List)}, but when
   * {@code operandRows} is not {@code null} the operands query ({@code c_elementvalue_operand})
   * gets its own mocked query returning them, while the node query returns {@code rows}.
   */
  @SuppressWarnings("unchecked")
  static NativeQuery<Object[]> stubHappyPathObContextAndDal(
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
    // ETP-5793: the org is resolved through TenantOwnership, which checks the readable clients.
    when(org.getClient()).thenReturn(client);
    when(obContext.getReadableClients()).thenReturn(new String[] { "sessionClientId1" });
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
    when(year.getClient()).thenReturn(client);

    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    when(dal.get(Organization.class, "sessionOrgId1")).thenReturn(org);
    when(dal.get(Year.class, "year1")).thenReturn(year);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);

    return query;
  }
}
