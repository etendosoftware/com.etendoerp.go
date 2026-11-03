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

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Client of the Decisions API ({@code POST /decisions} on the LiteLLM gateway, OpenAI format).
 *
 * <p>A decision model answers typed questions about an input with probabilities instead of text.
 * One request carries the shared {@link DecisionInput} and any number of
 * {@link DecisionQuestion}s, each answered independently.</p>
 *
 * <pre>{@code
 * DecisionResult r = new LlmDecider().decide(
 *     DecisionInput.ofText("I was charged twice for order #4411, refund one today."),
 *     List.of(
 *         DecisionQuestion.predicate("refund", "Is the customer asking for a refund?"),
 *         DecisionQuestion.choice("document", "Which document is involved?",
 *             "sales-invoice", "goods-shipment", "sales-order"),
 *         DecisionQuestion.score("urgency", "How urgent is the request?",
 *             List.of(Option.of("low"), Option.of("normal"), Option.of("high")))));
 * r.answer("refund").probability();   // 0.98
 * r.answer("document").choice();      // "sales-invoice"
 * }</pre>
 *
 * <p>Blocking, one HTTP call per {@code decide}, no retries (see
 * {@link LlmDecisionException#isRetryable()}). Do not hold a DAL transaction open around it.</p>
 *
 * <p>Every request that reaches the gateway, answered or not, is recorded as one
 * {@code ai.decision.execute} usage event under the caller's {@code OBContext}, with the cost the
 * gateway reported ({@link DecisionUsage}); it counts in the tenant's AI spend.</p>
 */
public class LlmDecider {

  private static final Logger log = LogManager.getLogger(LlmDecider.class);
  private static final int MAX_ERROR_DETAIL = 300;
  /** Header where LiteLLM reports what a request cost, in USD. */
  static final String COST_HEADER = "x-litellm-response-cost";
  /** Usage-event target when the caller names none. */
  static final String DEFAULT_TARGET = "decisions";
  private static final String REFUSAL = "refusal";
  private static final String PROBABILITY = "probability";
  private static final String QUESTION_NAME = "single";

  private final LlmConfig config;
  private final String target;
  private final HttpClient httpClient;
  private final Consumer<DecisionUsage> usageSink;

  /** Creates a decider with the runtime configuration, recorded under the default target. */
  public LlmDecider() {
    this(DEFAULT_TARGET);
  }

  /**
   * Creates a decider with the runtime configuration ({@link LlmConfig#fromRuntime()}).
   *
   * @param target the feature asking (e.g. {@code invoice-inbox}), recorded as the usage event's
   *     target so its AI spend can be told apart
   */
  public LlmDecider(String target) {
    this(LlmConfig.fromRuntime(), target);
  }

  /**
   * Creates a decider for the given configuration.
   *
   * @param config gateway, key and model to use
   * @param target the feature asking, recorded as the usage event's target
   */
  public LlmDecider(LlmConfig config, String target) {
    this(config, target, HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.getTimeoutMs()))
        .version(HttpClient.Version.HTTP_1_1)
        .build(), DecisionUsage::submit);
  }

  LlmDecider(LlmConfig config, String target, HttpClient httpClient,
      Consumer<DecisionUsage> usageSink) {
    this.config = config;
    this.target = StringUtils.defaultIfBlank(target, DEFAULT_TARGET);
    this.httpClient = httpClient;
    this.usageSink = usageSink;
  }

  /**
   * Asks every question about the input in one request.
   *
   * @param input the shared evidence; at least one text, JSON or image part
   * @param questions at least one question; names must be unique
   * @return one answer per question, in question order; a refused question is an answer with
   *     {@code refused() == true}
   * @throws IllegalArgumentException if the input is empty or the questions are not usable
   * @throws LlmDecisionException if the request fails or the response does not match the
   *     questions
   */
  public DecisionResult decide(DecisionInput input, List<DecisionQuestion> questions) {
    validate(input, questions);
    if (!config.isConfigured()) {
      throw new LlmDecisionException("No LLM API key: openai.api.key (the Copilot key) is not"
          + " set in Openbravo.properties.", false);
    }
    String requestBody;
    try {
      JSONArray questionsJson = new JSONArray();
      for (DecisionQuestion question : questions) {
        questionsJson.put(question.toJson());
      }
      requestBody = new JSONObject()
          .put("model", config.getModel())
          .put("input", input.toJson())
          .put("questions", questionsJson)
          .toString();
    } catch (JSONException e) {
      throw new LlmDecisionException("Could not build the decision request.", false, e);
    }
    long started = System.currentTimeMillis();
    HttpResponse<String> response;
    try {
      response = send(requestBody);
    } catch (LlmDecisionException e) {
      recordUsage(questions, started, "unreachable", null, 0);
      throw e;
    }
    BigDecimal cost = cost(response);
    String body = response.body() == null ? "" : response.body();
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      recordUsage(questions, started, "http_" + status, cost, 0);
      throw new LlmDecisionException("Decision request failed with HTTP " + status + ": "
          + errorDetail(body), isRetryable(status));
    }
    DecisionResult result;
    try {
      result = parse(body, questions, cost);
    } catch (LlmDecisionException e) {
      recordUsage(questions, started, "invalid_response", cost, 0);
      throw e;
    }
    recordUsage(questions, started, null, cost, result.inputTokens());
    return result;
  }

  /** Recording must never break or change the decision: a failure here is only logged. */
  private void recordUsage(List<DecisionQuestion> questions, long started, String errorCode,
      BigDecimal cost, long inputTokens) {
    try {
      usageSink.accept(new DecisionUsage(target, config.getModel(), errorCode == null, errorCode,
          System.currentTimeMillis() - started, cost, inputTokens, questions.size()));
    } catch (RuntimeException e) {
      log.warn("Decision usage not recorded for target {}: {}", target, e.getMessage());
    }
  }

  /** The cost LiteLLM reported; null when absent, unreadable or negative. */
  static BigDecimal cost(HttpResponse<?> response) {
    String raw = response.headers().firstValue(COST_HEADER).orElse(null);
    if (StringUtils.isBlank(raw)) {
      return null;
    }
    try {
      BigDecimal cost = new BigDecimal(raw.trim());
      return cost.signum() < 0 ? null : cost;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Shortcut for one choice question over a text.
   *
   * @param text the evidence
   * @param instructions what to choose
   * @param values 2 or more distinct values
   * @return the answer; {@code choice()} is one of {@code values}
   * @throws LlmDecisionException if the request fails or the model refuses
   */
  public DecisionAnswer choose(String text, String instructions, String... values) {
    return single(text, DecisionQuestion.choice(QUESTION_NAME, instructions, values));
  }

  /**
   * Shortcut for one yes/no question over a text.
   *
   * @param text the evidence
   * @param instructions the condition to check
   * @return the probability, 0 to 1, that the condition is true
   * @throws LlmDecisionException if the request fails or the model refuses
   */
  public double probability(String text, String instructions) {
    return single(text, DecisionQuestion.predicate(QUESTION_NAME, instructions)).probability();
  }

  private DecisionAnswer single(String text, DecisionQuestion question) {
    DecisionAnswer answer = decide(DecisionInput.ofText(text), List.of(question)).answers().get(0);
    if (answer.refused()) {
      throw new LlmDecisionException("The decision model refused to answer.", false);
    }
    return answer;
  }

  private static void validate(DecisionInput input, List<DecisionQuestion> questions) {
    if (input == null || input.isEmpty()) {
      throw new IllegalArgumentException("input needs at least one text, JSON or image part");
    }
    if (questions == null || questions.isEmpty()) {
      throw new IllegalArgumentException("at least one question is required");
    }
    Set<String> names = new HashSet<>();
    for (DecisionQuestion question : questions) {
      if (!names.add(question.getName())) {
        throw new IllegalArgumentException("duplicate question name: " + question.getName());
      }
    }
  }

  private HttpResponse<String> send(String body) {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(config.decisionsUrl()))
        .timeout(Duration.ofMillis(config.getTimeoutMs()))
        .header("Authorization", "Bearer " + config.getApiKey())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new LlmDecisionException("Decision request to " + config.decisionsUrl()
          + " did not complete.", true, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmDecisionException("Decision request was interrupted.", false, e);
    }
  }

  private DecisionResult parse(String body, List<DecisionQuestion> questions, BigDecimal cost) {
    try {
      JSONObject response = new JSONObject(body);
      JSONArray answers = response.getJSONArray("answers");
      if (answers.length() != questions.size()) {
        throw new LlmDecisionException("Decision model returned " + answers.length()
            + " answers for " + questions.size() + " questions.", false);
      }
      List<DecisionAnswer> parsed = new ArrayList<>(questions.size());
      for (int i = 0; i < questions.size(); i++) {
        parsed.add(answer(questions.get(i), answers.getJSONObject(i)));
      }
      JSONObject usage = response.optJSONObject("usage");
      return new DecisionResult(Collections.unmodifiableList(parsed),
          response.optString("model", config.getModel()),
          usage == null ? 0 : usage.optLong("input_tokens", 0), cost);
    } catch (JSONException e) {
      throw new LlmDecisionException("Decision gateway returned an unreadable response.", false,
          e);
    }
  }

  private static DecisionAnswer answer(DecisionQuestion question, JSONObject json)
      throws JSONException {
    String name = json.optString("name", null);
    if (name != null && !name.equals(question.getName())) {
      throw new LlmDecisionException("Decision answers are out of order: expected '"
          + question.getName() + "', got '" + name + "'.", false);
    }
    String type = json.optString("type", "");
    if (REFUSAL.equals(type)) {
      return new DecisionAnswer(question.getName(), question.getType(), true, null, null, null,
          Map.of(), null);
    }
    if (!question.getType().wire().equals(type)) {
      throw new LlmDecisionException("Question '" + question.getName() + "' asked for "
          + question.getType().wire() + " but was answered as '" + type + "'.", false);
    }
    switch (question.getType()) {
      case PREDICATE:
        return new DecisionAnswer(question.getName(), question.getType(), false,
            json.getDouble(PROBABILITY), null, null, Map.of(), null);
      case CHOICE:
        String choice = json.getString("choice");
        if (question.getOptions().stream().noneMatch(o -> o.value().equals(choice))) {
          throw new LlmDecisionException("Decision model chose '" + choice + "', which is not"
              + " one of the choices of '" + question.getName() + "'.", false);
        }
        return new DecisionAnswer(question.getName(), question.getType(), false, null, choice,
            null, probabilities(json, "value"), optDouble(json, "confidence"));
      default:
        return new DecisionAnswer(question.getName(), question.getType(), false, null, null,
            json.getDouble("score"), probabilities(json, "label"), optDouble(json, "confidence"));
    }
  }

  /** Probability per choice value or level label, in the order the gateway listed them. */
  private static Map<String, Double> probabilities(JSONObject json, String key)
      throws JSONException {
    JSONArray entries = json.optJSONArray("probabilities");
    if (entries == null) {
      return Map.of();
    }
    Map<String, Double> result = new LinkedHashMap<>();
    for (int i = 0; i < entries.length(); i++) {
      JSONObject entry = entries.getJSONObject(i);
      result.put(entry.getString(key), entry.getDouble(PROBABILITY));
    }
    return Collections.unmodifiableMap(result);
  }

  private static Double optDouble(JSONObject json, String key) throws JSONException {
    return json.has(key) && !json.isNull(key) ? json.getDouble(key) : null;
  }

  private static boolean isRetryable(int status) {
    return status == 408 || status == 429 || status >= 500;
  }

  /** The gateway's own message (LiteLLM: {@code error.message}), shortened for a log line. */
  private static String errorDetail(String body) {
    String detail = body;
    try {
      JSONObject error = new JSONObject(body).optJSONObject("error");
      if (error != null && error.has("message")) {
        detail = error.getString("message");
      }
    } catch (JSONException notJson) {
      // Keep the raw body.
    }
    return StringUtils.abbreviate(StringUtils.defaultIfBlank(detail, "(empty body)"),
        MAX_ERROR_DETAIL);
  }
}
