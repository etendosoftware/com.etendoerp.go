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

import java.util.Collections;
import java.util.List;

import javax.inject.Named;

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
 * Unit tests for {@link ProfitLossReportHandler} (ETP-5483 slice 5) — the MCP report tool
 * {@code generate_profit_loss}, a faithful Java port of {@code
 * artifacts/profit-loss/report-contract.json}'s {@code sql.query}/{@code sql.operandsQuery} plus
 * {@link AccountReportTree} (reused UNCHANGED from {@link BalanceSheetReportHandler}; its own
 * roll-up/formula rules are covered by {@code AccountReportTreeTest}, not here).
 */
class ProfitLossReportHandlerTest {

  private final ProfitLossReportHandler handler = new ProfitLossReportHandler();

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
    Named named = ProfitLossReportHandler.class.getAnnotation(Named.class);
    assertNotNull(named);
    assertEquals("profitLossReportHandler", named.value());
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

  /**
   * Unlike {@link BalanceSheetReportHandler}, dateFrom/fromReferenceDate DO have effect here —
   * P&L is a period-activity report, not a cumulative snapshot. The descriptions must NOT contain
   * the "NO EFFECT" disclaimer Balance Sheet's own descriptor carries for the same two params.
   */
  @Test
  @DisplayName("documents dateFrom and fromReferenceDate as having a real effect, unlike balance sheet")
  void reportParametersDocumentsDateFromHasEffect() {
    List<NeoReportParam> params = handler.reportParameters().orElseThrow();

    NeoReportParam dateFrom = params.stream().filter(p -> "dateFrom".equals(p.getName()))
        .findFirst().orElseThrow();
    NeoReportParam fromReferenceDate = params.stream()
        .filter(p -> "fromReferenceDate".equals(p.getName())).findFirst().orElseThrow();

    assertFalse(dateFrom.getDescription().contains("NO EFFECT"));
    assertFalse(fromReferenceDate.getDescription().contains("NO EFFECT"));
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
      assertEquals("Profit & Loss", response.getBody().getString("name"));
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
  @DisplayName("POST with a malformed dateFrom is refused with 400")
  void postWithMalformedDateFromIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("yearId", "year1");
      body.put("dateFrom", "24/09/2026");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(400, response.getHttpStatus());
      assertEquals("dateFrom_invalid", response.getBody().getString("error"));
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
   * The core scoping guarantee, mirroring {@code BalanceSheetReportHandlerTest}'s own test of the
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
  @DisplayName("dateFrom, when given, is bound as a real query parameter")
  void dateFromIsBoundAsQueryParameter() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      NativeQuery<Object[]> query = stubHappyPathObContextAndDal(contextMock, obDalMock,
          Collections.emptyList());

      JSONObject body = new JSONObject();
      body.put("yearId", "year1");
      body.put("dateFrom", "2026-01-01");

      NeoResponse response = handler.handle(context("POST", body));

      assertEquals(200, response.getHttpStatus(), () -> describeFailure(response));

      ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
      verify(query, atLeastOnce()).setParameter(nameCaptor.capture(), any());
      assertTrue(nameCaptor.getAllValues().contains("dateFrom"),
          "dateFrom must reach the query as a bound parameter — unlike balance sheet, it has a "
              + "real effect on the P&L period-activity filter");
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
  @SuppressWarnings("unchecked")
  private static NativeQuery<Object[]> stubHappyPathObContextAndDal(
      MockedStatic<OBContext> contextMock, MockedStatic<OBDal> obDalMock, List<Object[]> rows) {
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

    Year year = mock(Year.class);

    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    when(dal.get(Organization.class, "sessionOrgId1")).thenReturn(org);
    when(dal.get(Year.class, "year1")).thenReturn(year);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);

    return query;
  }
}
