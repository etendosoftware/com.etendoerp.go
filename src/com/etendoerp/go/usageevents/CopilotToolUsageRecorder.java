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

import javax.enterprise.context.ApplicationScoped;

import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.copilot.hook.CopilotToolUsageHook;

/**
 * Records every Copilot tool call that used a model (today the document OCR, SimpleOcrTool) as one
 * {@value UsageEventTypes#AI_TOOL_EXECUTE} event, with the same token keys as the agent chat's
 * {@value UsageEventTypes#AI_AGENT_MESSAGE} and its cost in {@code COST_USD}, so the money spent on
 * AI sums in one column (and in the {@code aiSpend} billing resource, {@code AiSpendCounter}).
 *
 * <p>Copilot calls it on the request thread, with the caller's {@code OBContext}, which is where
 * user, role and tenant come from.
 */
@ApplicationScoped
public class CopilotToolUsageRecorder implements CopilotToolUsageHook {

  static final String ACTION_EXECUTE = "execute";

  @Override
  public void exec(String toolName, String agentId, JSONObject usage, long durationMs) {
    UsageEventRecorder.submit(build(toolName, usage, durationMs).fromContext().build());
  }

  /**
   * The event without its who-columns. Only the known keys of {@code usage} are kept: it comes from
   * the Copilot service and the event must carry shape, never content.
   */
  static UsageEvent.Builder build(String toolName, JSONObject usage, long durationMs) {
    return UsageEvent.builder()
        .eventType(UsageEventTypes.AI_TOOL_EXECUTE)
        .source(UsageEvent.SOURCE_BACKEND)
        .target(toolName)
        .action(ACTION_EXECUTE)
        .outcome(UsageEvent.OUTCOME_OK)
        .durationMs(durationMs)
        .costUsd(UsageEvent.costOf(usage.opt("costUsd")))
        .property("model", usage.optString("model", null))
        .property("inputTokens", usage.optLong("inputTokens", 0))
        .property("outputTokens", usage.optLong("outputTokens", 0));
  }
}
