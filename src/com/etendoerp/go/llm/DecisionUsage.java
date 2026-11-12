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

package com.etendoerp.go.llm;

import java.math.BigDecimal;

import com.etendoerp.go.usageevents.UsageEvent;
import com.etendoerp.go.usageevents.UsageEventRecorder;
import com.etendoerp.go.usageevents.UsageEventTypes;

/**
 * What one Decisions API request cost, recorded as one
 * {@value UsageEventTypes#AI_DECISION_EXECUTE} event in {@code ETGO_USAGE_EVENT}; its
 * {@code COST_USD} adds to the tenant's AI spend ({@code aiSpend}, {@code AiSpendCounter}).
 *
 * @param target the feature that asked (the decider's target)
 * @param model the model group requested
 * @param ok whether a usable answer came back
 * @param errorCode why not, when not ok ({@code http_429}, {@code unreachable}, ...)
 * @param durationMs from sending the request to reading the answer
 * @param costUsd what the gateway reported in {@code x-litellm-response-cost}; null if it did not
 * @param inputTokens input tokens billed; 0 when unknown
 * @param questions how many questions the request asked
 */
record DecisionUsage(String target, String model, boolean ok, String errorCode, long durationMs,
    BigDecimal costUsd, long inputTokens, int questions) {

  static final String ACTION = "decide";

  /**
   * Records the usage under the caller's {@code OBContext}. Never throws and never blocks: the
   * recorder writes on its own thread.
   *
   * @param usage what to record
   */
  static void submit(DecisionUsage usage) {
    UsageEventRecorder.submit(UsageEvent.builder()
        .fromContext()
        .eventType(UsageEventTypes.AI_DECISION_EXECUTE)
        .source(UsageEvent.SOURCE_BACKEND)
        .target(usage.target())
        .action(ACTION)
        .outcome(usage.ok() ? UsageEvent.OUTCOME_OK : UsageEvent.OUTCOME_ERROR)
        .errorCode(usage.errorCode())
        .durationMs(usage.durationMs())
        .costUsd(usage.costUsd())
        .property("model", usage.model())
        .property("inputTokens", usage.inputTokens())
        .property("questions", usage.questions())
        .build());
  }
}
