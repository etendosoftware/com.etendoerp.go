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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.PeriodControlLog;
import org.openbravo.model.financialmgmt.calendar.Year;
import org.openbravo.service.db.CallProcess;

import com.etendoerp.go.schemaforge.telemetry.AccountingKpiTelemetry;

/**
 * Unit tests for {@link PeriodOpenCloseHandler}.
 *
 * <p>Covers the null-guard branches in {@link PeriodOpenCloseHandler#doHandle}
 * (Period not found → 404, Process 167 not found → 500), the happy path (200),
 * and the {@link PeriodOpenCloseHandler#onError} envelope. ETP-6230: a successful manual open
 * queues the calendar-review KPI, a close or a failure does not.
 *
 * @covers com.etendoerp.go.schemaforge.PeriodOpenCloseHandler
 */
public class PeriodOpenCloseHandlerTest {

  private static final String RECORD_ID = "period-id-1";

  private NeoContext buildContext() throws Exception {
    JSONObject fieldValues = new JSONObject().put("openClose", "O");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    return NeoContext.builder()
        .specName("open-close-period-control").entityName("periodControl")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("openClose").recordId(RECORD_ID).requestBody(body).build();
  }

  @Test
  public void testDoHandlePeriodNotFoundReturns404() throws Exception {
    PeriodOpenCloseHandler handler = new PeriodOpenCloseHandler();
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Period.class), anyString())).thenReturn(null);

      NeoResponse r = handler.handle(buildContext());

      assertEquals(404, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("not found"));
    }
  }

  @Test
  public void testDoHandleProcess167NotFoundReturns500() throws Exception {
    PeriodOpenCloseHandler handler = new PeriodOpenCloseHandler();
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Period period = mock(Period.class);
      Year year = mock(Year.class);
      Calendar calendar = mock(Calendar.class);
      when(period.getYear()).thenReturn(year);
      when(year.getCalendar()).thenReturn(calendar);
      when(dal.get(eq(Period.class), anyString())).thenReturn(period);

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      PeriodControlLog logEntry = mock(PeriodControlLog.class);
      when(provider.get(PeriodControlLog.class)).thenReturn(logEntry);
      when(logEntry.getId()).thenReturn("log-id-1");

      when(dal.get(eq(Process.class), eq("167"))).thenReturn(null);

      NeoResponse r = handler.handle(buildContext());

      assertEquals(500, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("167"));
    }
  }

  @Test
  public void testDoHandleHappyPathReturns200() throws Exception {
    PeriodOpenCloseHandler handler = new PeriodOpenCloseHandler();
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
         MockedStatic<CallProcess> callProcessMock = Mockito.mockStatic(CallProcess.class);
         MockedStatic<AccountingKpiTelemetry> kpi = Mockito.mockStatic(AccountingKpiTelemetry.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      doNothing().when(session).refresh(any());

      Period period = mock(Period.class);
      Year year = mock(Year.class);
      Calendar calendar = mock(Calendar.class);
      Client tenant = mock(Client.class);
      when(tenant.getId()).thenReturn("TENANT-1");
      when(period.getClient()).thenReturn(tenant);
      when(period.getYear()).thenReturn(year);
      when(year.getCalendar()).thenReturn(calendar);
      when(dal.get(eq(Period.class), anyString())).thenReturn(period);

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      PeriodControlLog logEntry = mock(PeriodControlLog.class);
      when(provider.get(PeriodControlLog.class)).thenReturn(logEntry);
      when(logEntry.getId()).thenReturn("log-id-1");

      Process process167 = mock(Process.class);
      when(process167.getName()).thenReturn("C_Period_Process");
      when(dal.get(eq(Process.class), eq("167"))).thenReturn(process167);

      CallProcess cp = mock(CallProcess.class);
      callProcessMock.when(CallProcess::getInstance).thenReturn(cp);
      ProcessInstance pInstance = mock(ProcessInstance.class);
      when(pInstance.getResult()).thenReturn(1L);
      when(pInstance.getErrorMsg()).thenReturn(null);
      when(cp.call(eq(process167), anyString(), isNull())).thenReturn(pInstance);

      NeoResponse r = handler.handle(buildContext());

      assertEquals(200, r.getHttpStatus());
      assertEquals("success", r.getBody().getString("status"));
    }
  }

  // ── ETP-6230: the manual-open calendar review ─────────────────────────────

  private void runOpenClose(String openClose, long resultCode, String channel,
      java.util.function.Consumer<MockedStatic<AccountingKpiTelemetry>> check) throws Exception {
    runOpenClose(openClose, resultCode, channel, false, false, kpi -> { }, check);
  }

  /**
   * @param scoped run the handler inside an open NeoBatchScope
   * @param commit when scoped, run the after-commit actions (otherwise the scope just closes)
   * @param beforeCommit checked right after the handler returned, before any commit
   */
  private void runOpenClose(String openClose, long resultCode, String channel, boolean scoped,
      boolean commit, java.util.function.Consumer<MockedStatic<AccountingKpiTelemetry>> beforeCommit,
      java.util.function.Consumer<MockedStatic<AccountingKpiTelemetry>> check) throws Exception {
    JSONObject body = new JSONObject().put("fieldValues", new JSONObject().put("openClose",
        openClose));
    NeoContext ctx = NeoContext.builder()
        .specName("open-close-period-control").entityName("periodControl")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("openClose").recordId(RECORD_ID).requestBody(body).build();
    String previous = channel != null ? NeoWriteChannel.enter(channel) : null;
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
         MockedStatic<CallProcess> callProcessMock = Mockito.mockStatic(CallProcess.class);
         MockedStatic<AccountingKpiTelemetry> kpi = Mockito.mockStatic(AccountingKpiTelemetry.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));
      Period period = mock(Period.class);
      Client tenant = mock(Client.class);
      when(tenant.getId()).thenReturn("TENANT-1");
      when(period.getClient()).thenReturn(tenant);
      Year year = mock(Year.class);
      when(period.getYear()).thenReturn(year);
      when(year.getCalendar()).thenReturn(mock(Calendar.class));
      when(dal.get(eq(Period.class), anyString())).thenReturn(period);
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      PeriodControlLog logEntry = mock(PeriodControlLog.class);
      when(provider.get(PeriodControlLog.class)).thenReturn(logEntry);
      when(logEntry.getId()).thenReturn("log-id-1");
      Process process167 = mock(Process.class);
      when(process167.getName()).thenReturn("C_Period_Process");
      when(dal.get(eq(Process.class), eq("167"))).thenReturn(process167);
      CallProcess cp = mock(CallProcess.class);
      callProcessMock.when(CallProcess::getInstance).thenReturn(cp);
      ProcessInstance pInstance = mock(ProcessInstance.class);
      when(pInstance.getResult()).thenReturn(resultCode);
      when(cp.call(eq(process167), anyString(), isNull())).thenReturn(pInstance);

      NeoBatchScope previousScope = scoped ? NeoBatchScope.open(false) : null;
      try {
        new PeriodOpenCloseHandler().handle(ctx);
        beforeCommit.accept(kpi);
        if (scoped && commit) {
          NeoBatchScope.runAfterCommitActions();
        }
      } finally {
        if (scoped) {
          NeoBatchScope.close(previousScope);
        }
      }

      check.accept(kpi);
    } finally {
      if (channel != null) {
        NeoWriteChannel.restore(previous);
      }
    }
  }

  @Test
  public void aSuccessfulOpenFromTheCalendarWindowQueuesAConfirmedManualOpenReview()
      throws Exception {
    runOpenClose("O", 1L, null, kpi -> kpi.verify(() ->
        AccountingKpiTelemetry.calendarReviewCompleted("TENANT-1", "manual_open", "confirm",
            "calendar_window")));
  }

  @Test
  public void anOpenThroughTheMcpChannelReportsSourceMcp() throws Exception {
    runOpenClose("O", 1L, NeoWriteChannel.MCP, kpi -> kpi.verify(() ->
        AccountingKpiTelemetry.calendarReviewCompleted("TENANT-1", "manual_open", "confirm",
            "mcp")));
  }

  private static final java.util.function.Consumer<MockedStatic<AccountingKpiTelemetry>> NO_REVIEW =
      kpi -> kpi.verify(() -> AccountingKpiTelemetry.calendarReviewCompleted(any(), any(), any(),
          any()), never());

  private static java.util.function.Consumer<MockedStatic<AccountingKpiTelemetry>> oneReview(
      String source) {
    return kpi -> kpi.verify(() -> AccountingKpiTelemetry.calendarReviewCompleted("TENANT-1",
        "manual_open", "confirm", source));
  }

  @Test
  public void insideABatchScopeTheReviewIsQueuedOnlyAfterTheCommit() throws Exception {
    runOpenClose("O", 1L, null, true, true, NO_REVIEW, oneReview("batch"));
  }

  @Test
  public void aBatchScopeClosedWithoutCommitQueuesNoReview() throws Exception {
    runOpenClose("O", 1L, null, true, false, NO_REVIEW, NO_REVIEW);
  }

  @Test
  public void outsideABatchScopeTheReviewIsQueuedImmediately() throws Exception {
    runOpenClose("O", 1L, null, false, false, oneReview("calendar_window"),
        oneReview("calendar_window"));
  }

  @Test
  public void theBatchChannelsReportSourceBatch() throws Exception {
    // rest_batch / mcp_batch are derived: a REST or MCP write inside an open NeoBatchScope
    assertEquals("rest_batch", batchChannelOf(null));
    assertEquals("mcp_batch", batchChannelOf(NeoWriteChannel.MCP));
    runOpenClose("O", 1L, null, true, true, NO_REVIEW, oneReview("batch"));
    runOpenClose("O", 1L, NeoWriteChannel.MCP, true, true, NO_REVIEW, oneReview("batch"));
  }

  private static String batchChannelOf(String base) {
    String previous = base != null ? NeoWriteChannel.enter(base) : null;
    NeoBatchScope scope = NeoBatchScope.open(false);
    try {
      return NeoWriteChannel.current();
    } finally {
      NeoBatchScope.close(scope);
      if (base != null) {
        NeoWriteChannel.restore(previous);
      }
    }
  }

  @Test
  public void theRestChannelReportsTheCalendarWindowSource() throws Exception {
    runOpenClose("O", 1L, NeoWriteChannel.REST, oneReview("calendar_window"));
  }

  @Test
  public void aCloseDoesNotQueueAReview() throws Exception {
    runOpenClose("C", 1L, null, kpi -> kpi.verify(() ->
        AccountingKpiTelemetry.calendarReviewCompleted(any(), any(), any(), any()), never()));
  }

  @Test
  public void aFailedOpenDoesNotQueueAReview() throws Exception {
    runOpenClose("O", 0L, null, kpi -> kpi.verify(() ->
        AccountingKpiTelemetry.calendarReviewCompleted(any(), any(), any(), any()), never()));
  }

  @Test
  public void testOnErrorReturns500() throws Exception {
    PeriodOpenCloseHandler handler = new PeriodOpenCloseHandler();
    NeoResponse r = handler.onError("period-id", new RuntimeException("boom"));
    assertEquals(500, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message").contains("boom"));
  }
}
