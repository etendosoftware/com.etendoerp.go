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
import java.util.List;

/**
 * The answers of one Decisions API request.
 *
 * @param answers one per question, in question order
 * @param model the model that answered, as the gateway reported it
 * @param inputTokens input tokens billed (cache reads and writes included); 0 when not reported
 * @param costUsd what the gateway charged, from {@code x-litellm-response-cost}; null if unpriced
 */
public record DecisionResult(List<DecisionAnswer> answers, String model, long inputTokens,
    BigDecimal costUsd) {

  /**
   * The answer to a question.
   *
   * @param name the question's name
   * @return its answer
   * @throws IllegalArgumentException if no question had that name
   */
  public DecisionAnswer answer(String name) {
    return answers.stream()
        .filter(a -> a.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no question named " + name));
  }
}
