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

package com.etendoerp.go.usageevents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The event recorded for a Copilot tool call that used a model (the document OCR): its cost lands in
 * {@code COST_USD}, which billing sums; only known shape keys reach {@code PROPERTIES}.
 *
 * @covers com.etendoerp.go.usageevents.CopilotToolUsageRecorder
 */
class CopilotToolUsageRecorderTest {

  @Test
  void aPricedOcrCallIsAnAiToolEventWithItsCostInTheColumn() throws Exception {
    JSONObject usage = new JSONObject().put("model", "gemini/gemini-3.8-flash")
        .put("inputTokens", 1500).put("outputTokens", 300).put("costUsd", 0.0021)
        .put("prompt", "must never be stored");

    UsageEvent event = CopilotToolUsageRecorder.build("SimpleOcrTool", usage, 820L).build();

    assertEquals(UsageEventTypes.AI_TOOL_EXECUTE, event.eventType());
    assertEquals(UsageEvent.SOURCE_BACKEND, event.source());
    assertEquals("SimpleOcrTool", event.target());
    assertEquals(CopilotToolUsageRecorder.ACTION_EXECUTE, event.action());
    assertEquals(UsageEvent.OUTCOME_OK, event.outcome());
    assertEquals(820L, event.durationMs());
    assertEquals(0, new BigDecimal("0.0021").compareTo(event.costUsd()));
    JSONObject properties = new JSONObject(event.properties());
    assertEquals("gemini/gemini-3.8-flash", properties.getString("model"));
    assertEquals(1500, properties.getInt("inputTokens"));
    assertEquals(300, properties.getInt("outputTokens"));
    assertEquals(3, properties.length(), "no key but the known ones: " + properties);
  }

  @Test
  void anUnpricedOrNegativeCostIsNoCost() throws Exception {
    assertNull(build(new JSONObject()).costUsd());
    assertNull(build(new JSONObject().put("costUsd", -1)).costUsd());
    assertNull(build(new JSONObject().put("costUsd", "0.5")).costUsd());
  }

  private static UsageEvent build(JSONObject usage) {
    return CopilotToolUsageRecorder.build("SimpleOcrTool", usage, 1L).build();
  }
}
