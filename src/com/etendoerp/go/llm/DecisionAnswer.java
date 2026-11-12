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

import java.util.Map;

/**
 * The answer to one {@link DecisionQuestion}. Which fields are set depends on the type:
 *
 * <table>
 * <caption>Fields per type</caption>
 * <tr><th>Type</th><th>Set</th></tr>
 * <tr><td>predicate</td><td>{@code probability} (that the condition is true)</td></tr>
 * <tr><td>choice</td><td>{@code choice}, {@code probabilities} per value, {@code confidence}</td></tr>
 * <tr><td>score</td><td>{@code score} (probability-weighted level index, 0 = lowest),
 * {@code probabilities} per level label, {@code confidence}</td></tr>
 * <tr><td>refused</td><td>nothing but {@code name} and {@code type}</td></tr>
 * </table>
 *
 * <p>These are probabilities, not verdicts: pick thresholds from labelled examples according to
 * the cost of a false positive and a false negative.</p>
 *
 * @param name the question's name
 * @param type the question's type
 * @param refused whether the model declined to answer this question
 * @param probability predicate only
 * @param choice choice only; always one of the question's values
 * @param score score only
 * @param probabilities choice and score; insertion order is the question's option order
 * @param confidence choice and score
 */
public record DecisionAnswer(String name, DecisionQuestion.Type type, boolean refused,
    Double probability, String choice, Double score, Map<String, Double> probabilities,
    Double confidence) {
}
