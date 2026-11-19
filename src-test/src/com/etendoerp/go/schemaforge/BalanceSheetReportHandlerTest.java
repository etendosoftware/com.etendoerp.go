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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.util.List;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Unit tests for {@link BalanceSheetReportHandler} (ETP-5483 slice 4) — the MCP report tool
 * {@code generate_balance_sheet}, a faithful Java port of {@code
 * artifacts/balance-sheet/report-contract.json}'s {@code sql.query}/{@code sql.operandsQuery} plus
 * {@link AccountReportTree} (whose own roll-up/formula rules are covered by
 * {@code AccountReportTreeTest}, not here). What it inherits from
 * {@link AbstractAccountTreeReportHandler} is covered by {@code AbstractAccountTreeReportHandlerTest}.
 *
 * @covers com.etendoerp.go.schemaforge.BalanceSheetReportHandler
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
}
