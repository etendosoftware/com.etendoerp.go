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

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;
import com.etendoerp.go.schemaforge.util.ReportAccessCatalog;

/**
 * Unit tests for {@link TrialBalanceReportHandler} (ETP-5483 slice 2) — the MCP report tool
 * {@code generate_report_trial_balance}, a faithful Java port of
 * {@code artifacts/report-trial-balance/report-contract.json}'s {@code sql.query} plus
 * {@link TrialBalanceFolding} (whose own folding rules are covered by
 * {@code TrialBalanceFoldingTest}, not here).
 */
class TrialBalanceReportHandlerTest {

  private final TrialBalanceReportHandler handler = new TrialBalanceReportHandler();

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
    Named named = TrialBalanceReportHandler.class.getAnnotation(Named.class);
    assertNotNull(named);
    assertEquals("trialBalanceReportHandler", named.value());
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
  @DisplayName("declares accountLevel as a closed C/D/E/S set")
  void reportParametersDeclaresAccountLevelEnum() {
    NeoReportParam accountLevel = handler.reportParameters().orElseThrow().stream()
        .filter(p -> "accountLevel".equals(p.getName())).findFirst().orElseThrow();

    assertEquals(List.of("C", "D", "E", "S"), accountLevel.getAllowedValues());
  }

  @Test
  @DisplayName("declares groupBy as a closed dimension set")
  void reportParametersDeclaresGroupByEnum() {
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
      assertEquals("Trial Balance", response.getBody().getString("name"));
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
   * The core scoping guarantee: even if a caller stuffs an {@code ad_client_id}-shaped value
   * somewhere in the body, the query is bound to the SESSION's client — there is no body key this
   * handler reads for it at all. Verified by asserting the exact bind value passed to the native
   * query, resolved only through {@link OBContext}.
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
    NativeQuery<Object[]> query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.list()).thenReturn(Collections.emptyList());

    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    when(dal.get(Organization.class, "sessionOrgId1")).thenReturn(org);

    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      contextMock.when(OBContext::getOBContext).thenReturn(obContext);
      // setAdminMode(boolean)/restorePreviousMode are void statics; mockStatic leaves them as
      // harmless no-ops by default, so no explicit stub is needed for them.
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
      verify(query, atLeastOnce())
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
}
