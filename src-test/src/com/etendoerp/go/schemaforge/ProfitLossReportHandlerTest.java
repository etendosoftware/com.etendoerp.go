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

import static com.etendoerp.go.schemaforge.AbstractAccountTreeReportHandlerTest.describeFailure;
import static com.etendoerp.go.schemaforge.AbstractAccountTreeReportHandlerTest.stubHappyPathObContextAndDal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.List;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Unit tests for {@link ProfitLossReportHandler} (ETP-5483 slice 5) — the MCP report tool
 * {@code generate_profit_loss}, a faithful Java port of {@code
 * artifacts/profit-loss/report-contract.json}'s {@code sql.query}/{@code sql.operandsQuery} plus
 * {@link AccountReportTree} (reused UNCHANGED from {@link BalanceSheetReportHandler}; its own
 * roll-up/formula rules are covered by {@code AccountReportTreeTest}, not here). What it inherits
 * from {@link AbstractAccountTreeReportHandler} is covered by
 * {@code AbstractAccountTreeReportHandlerTest}.
 *
 * @covers com.etendoerp.go.schemaforge.ProfitLossReportHandler
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
  @DisplayName("declares no required parameter: yearId may be replaced by dateFrom + dateTo")
  void reportParametersDeclareNothingRequired() {
    List<NeoReportParam> params = handler.reportParameters().orElseThrow();

    assertEquals(0, params.stream().filter(NeoReportParam::isRequired).count(),
        "yearId is conditionally required (no dateFrom + dateTo) and referenceYearId too "
            + "(compareTo), which the schema cannot express");
  }

  @Test
  @DisplayName("declares breakdown as a closed none/month/quarter set")
  void reportParametersDeclareBreakdownEnum() {
    NeoReportParam breakdown = handler.reportParameters().orElseThrow().stream()
        .filter(p -> "breakdown".equals(p.getName())).findFirst().orElseThrow();

    assertEquals(List.of("none", "month", "quarter"), breakdown.getAllowedValues());
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
  // Validation — 400s, all reachable without touching the DB (pure input checks first)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST without yearId and with only one date is still refused with 400")
  void postWithoutYearIdAndOneDateIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      NeoResponse response = handler.handle(context("POST",
          new JSONObject().put("dateFrom", "2026-07-01")));

      assertEquals(400, response.getHttpStatus());
      assertEquals("year_id_required", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST without yearId but with dateFrom and dateTo runs on the dates alone")
  void postWithDatesOnlyRunsWithoutYearBind() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      NativeQuery<Object[]> query = stubHappyPathObContextAndDal(contextMock, obDalMock,
          Collections.emptyList());

      NeoResponse response = handler.handle(context("POST",
          new JSONObject().put("dateFrom", "2026-07-01").put("dateTo", "2026-09-30")));

      assertEquals(200, response.getHttpStatus(), () -> describeFailure(response));
      ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
      verify(query, atLeastOnce()).setParameter(nameCaptor.capture(), any());
      assertFalse(nameCaptor.getAllValues().contains("yearId"),
          "with no yearId the query must not bind one (the SQL has no year bound then)");
      assertTrue(nameCaptor.getAllValues().contains("dateTo"));
    }
  }

  @Test
  @DisplayName("POST with an unknown breakdown is refused with 400")
  void postWithInvalidBreakdownIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      NeoResponse response = handler.handle(context("POST",
          new JSONObject().put("yearId", "year1").put("breakdown", "week")));

      assertEquals(400, response.getHttpStatus());
      assertEquals("breakdown_invalid", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("POST with a breakdown and compareTo is refused with 400")
  void postWithBreakdownAndCompareToIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      NeoResponse response = handler.handle(context("POST", new JSONObject()
          .put("yearId", "year1").put("breakdown", "month").put("compareTo", true)
          .put("referenceYearId", "year2")));

      assertEquals(400, response.getHttpStatus());
      assertEquals("breakdown_with_compare", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("a breakdown longer than 24 periods is refused with 400")
  void breakdownWithTooManyPeriodsIsRefused() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      stubHappyPathObContextAndDal(contextMock, obDalMock, Collections.emptyList());

      NeoResponse response = handler.handle(context("POST", new JSONObject()
          .put("dateFrom", "2024-01-01").put("dateTo", "2026-06-30").put("breakdown", "month")));

      assertEquals(400, response.getHttpStatus());
      assertEquals("breakdown_too_many_periods", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("a quarter breakdown lists its columns from the range's first month in meta")
  void quarterBreakdownListsPeriodsInMeta() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      stubHappyPathObContextAndDal(contextMock, obDalMock, Collections.emptyList());

      NeoResponse response = handler.handle(context("POST", new JSONObject()
          .put("dateFrom", "2026-07-01").put("dateTo", "2027-06-30").put("breakdown", "quarter")));

      assertEquals(200, response.getHttpStatus(), () -> describeFailure(response));
      JSONObject meta = response.getBody().getJSONObject("response").getJSONObject("meta");
      assertEquals("quarter", meta.getString("breakdown"));
      assertEquals(4, meta.getJSONArray("periods").length());
      assertEquals("2026-07/2026-09", meta.getJSONArray("periods").getJSONObject(0)
          .getString("key"));
      assertEquals("2027-04/2027-06", meta.getJSONArray("periods").getJSONObject(3)
          .getString("key"));
    }
  }

  @Test
  @DisplayName("a month breakdown of a mid-month range clips its first and last slices")
  void monthSlicesAreClippedToTheRange() {
    List<java.time.LocalDate[]> slices = AbstractAccountTreeReportHandler.slices(
        java.time.LocalDate.of(2026, 7, 15), java.time.LocalDate.of(2026, 9, 10), 1);

    assertEquals(3, slices.size());
    assertEquals(java.time.LocalDate.of(2026, 7, 15), slices.get(0)[0]);
    assertEquals(java.time.LocalDate.of(2026, 7, 31), slices.get(0)[1]);
    assertEquals(java.time.LocalDate.of(2026, 9, 1), slices.get(2)[0]);
    assertEquals(java.time.LocalDate.of(2026, 9, 10), slices.get(2)[1]);
    assertEquals("2026-08", AbstractAccountTreeReportHandler.sliceKey(slices.get(1)));
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

  // -------------------------------------------------------------------------
  // Period filter — dateFrom has a real effect here
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // ShowValueCond columns (ETP-5662)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("node SQL selects showvaluecond/issummary AFTER own_amt_ref")
  void selectsShowValueCondColumnsAfterOwnAmtRef() {
    String cols = handler.ownAmountColumns();
    assertTrue(cols.indexOf("own_amt_ref") < cols.indexOf("ev.showvaluecond"),
        "positional COL_* mapping relies on these coming last");
    assertTrue(cols.indexOf("ev.showvaluecond") < cols.indexOf("ev.issummary"));
  }
}
