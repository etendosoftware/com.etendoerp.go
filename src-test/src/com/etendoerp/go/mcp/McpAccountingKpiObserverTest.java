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

package com.etendoerp.go.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.telemetry.AccountingKpiTelemetry;

/**
 * What {@link McpAccountingKpiObserver} hands to the accounting KPI telemetry after an MCP tool
 * call: which tools it skips, the {@code source} and {@code operation} it derives, and when it
 * queues the first-document probe or the refusal observation.
 *
 * @covers com.etendoerp.go.mcp.McpAccountingKpiObserver
 */
public class McpAccountingKpiObserverTest {

  private static final String CLIENT = "CLIENT-1";
  private static final String REFUSAL = "{\"error\":\"validation_error\",\"messageKeys\":"
      + "[\"PeriodNotAvailable\"]}";

  private MockedStatic<AccountingKpiTelemetry> kpi;

  @Before
  public void setUp() {
    kpi = mockStatic(AccountingKpiTelemetry.class);
  }

  @After
  public void tearDown() {
    kpi.close();
  }

  private static JSONObject params(String action) throws Exception {
    JSONObject arguments = new JSONObject().put("spec", "sales-invoice").put("entity", "header");
    if (action != null) {
      arguments.put("action", action);
    }
    return new JSONObject().put("arguments", arguments);
  }

  private static JSONObject ok(String text) throws Exception {
    return new JSONObject().put("content", new JSONArray().put(new JSONObject().put("text", text)));
  }

  private static JSONObject failed(String text) throws Exception {
    return ok(text).put("isError", true);
  }

  private void verifyProbe(String source, String spec, String entity, String operation) {
    kpi.verify(() -> AccountingKpiTelemetry.enqueueFirstDocumentProbe(CLIENT, source, spec, entity,
        operation));
  }

  private void verifyNoRefusal() {
    kpi.verify(() -> AccountingKpiTelemetry.observeRefusal(anyString(), anyString(), any(), any(),
        any(), any(), any()), never());
  }

  private void verifyNoProbe() {
    kpi.verify(() -> AccountingKpiTelemetry.enqueueFirstDocumentProbe(any(), any(), any(), any(),
        any()), never());
  }

  @Test
  public void jobPollingToolsAreNeverObserved() throws Exception {
    McpAccountingKpiObserver.observe(CLIENT, "etendo_job_status", params(null), failed(REFUSAL));
    McpAccountingKpiObserver.observe(CLIENT, "etendo_job_cancel", params(null), ok("{}"));

    kpi.verifyNoInteractions();
  }

  @Test
  public void aBlankToolOrTenantOrAMissingResultIsIgnored() throws Exception {
    McpAccountingKpiObserver.observe(CLIENT, " ", params(null), ok("{}"));
    McpAccountingKpiObserver.observe(null, "etendo_create", params(null), ok("{}"));
    McpAccountingKpiObserver.observe(CLIENT, "etendo_create", params(null), null);

    kpi.verifyNoInteractions();
  }

  @Test
  public void aSuccessfulCreateQueuesTheProbeWithSourceMcpAndNoActionHint() throws Exception {
    McpAccountingKpiObserver.observe(CLIENT, "etendo_create", params(null), ok("{\"id\":\"1\"}"));

    verifyProbe("mcp", "sales-invoice", "header", null);
    verifyNoRefusal();
  }

  @Test
  public void anActionArgumentBecomesTheOperation() throws Exception {
    McpAccountingKpiObserver.observe(CLIENT, "etendo_action", params("post"), ok("{}"));

    verifyProbe("mcp", "sales-invoice", "header", "post");
  }

  @Test
  public void anActionToolWithoutAnActionArgumentSendsNoHint() throws Exception {
    McpAccountingKpiObserver.observe(CLIENT, "etendo_action", params(null), ok("{}"));

    verifyProbe("mcp", "sales-invoice", "header", null);
  }

  @Test
  public void anActionBatchSendsItsActionAsTheHint() throws Exception {
    McpAccountingKpiObserver.observe(CLIENT, "etendo_action_batch", params("post"), ok("{}"));

    verifyProbe("batch", "sales-invoice", "header", "post");
  }

  @Test
  public void aSuccessfulReadQueuesNothing() throws Exception {
    McpAccountingKpiObserver.observe(CLIENT, "etendo_list", params(null), ok("{\"items\":[]}"));

    kpi.verifyNoInteractions();
  }

  @Test
  public void aFailedWriteObservesTheJsonRefusalAndDoesNotQueueTheProbe() throws Exception {
    McpAccountingKpiObserver.observe(CLIENT, "etendo_action", params("complete"), failed(REFUSAL));

    ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
    kpi.verify(() -> AccountingKpiTelemetry.observeRefusal(eq(CLIENT), eq("mcp"),
        eq("sales-invoice"), eq("header"), eq("complete"), body.capture(), isNull()));
    assertEquals("PeriodNotAvailable", body.getValue().getJSONArray("messageKeys").getString(0));
    verifyNoProbe();
  }

  @Test
  public void aProseFailureIsHandedOverAsText() throws Exception {
    McpAccountingKpiObserver.observe(CLIENT, "etendo_create", params(null),
        failed("@PeriodNotAvailable@"));

    kpi.verify(() -> AccountingKpiTelemetry.observeRefusal(CLIENT, "mcp", "sales-invoice", "header",
        "create", null, "@PeriodNotAvailable@"));
  }

  @Test
  public void aBatchToolReportsSourceBatchAndScansASuccessfulAnswerToo() throws Exception {
    McpAccountingKpiObserver.observe(CLIENT, "etendo_batch", params(null), ok(REFUSAL));

    ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
    kpi.verify(() -> AccountingKpiTelemetry.observeRefusal(eq(CLIENT), eq("batch"),
        eq("sales-invoice"), eq("header"), eq("batch"), body.capture(), isNull()));
    assertNotNull(body.getValue());
    verifyProbe("batch", "sales-invoice", "header", null);
  }

  @Test
  public void aProseSuccessOfABatchIsNotRecognisedAsARefusal() throws Exception {
    McpAccountingKpiObserver.observe(CLIENT, "etendo_batch", params(null), ok("done"));

    verifyNoRefusal();
    verifyProbe("batch", "sales-invoice", "header", null);
  }

  @Test
  public void aFailureInsideTheTelemetryNeverReachesTheToolCall() throws Exception {
    kpi.when(() -> AccountingKpiTelemetry.enqueueFirstDocumentProbe(any(), any(), any(), any(),
        any())).thenThrow(new IllegalStateException("boom"));

    McpAccountingKpiObserver.observe(CLIENT, "etendo_create", params(null), ok("{}"));

    // reaching this line is the contract: the exception was swallowed after the probe was attempted
    verifyProbe("mcp", "sales-invoice", "header", null);
  }
}
