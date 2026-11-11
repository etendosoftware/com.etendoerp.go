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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.llm.DecisionQuestion.Option;
import com.etendoerp.go.llm.DecisionQuestion.Type;
import com.sun.net.httpserver.HttpServer;

/**
 * The Decisions API call against a local server speaking LiteLLM's {@code /decisions} format:
 * what is sent, how each answer type is read, and which failures may be retried.
 *
 * @covers com.etendoerp.go.llm.LlmDecider
 * @covers com.etendoerp.go.llm.DecisionInput
 * @covers com.etendoerp.go.llm.DecisionQuestion
 * @covers com.etendoerp.go.llm.DecisionResult
 * @covers com.etendoerp.go.llm.LlmConfig
 */
class LlmDeciderTest {

  private static final List<DecisionQuestion> QUESTIONS = List.of(
      DecisionQuestion.predicate("refund", "Is the customer asking for a refund?"),
      DecisionQuestion.choice("document", "Which document is involved?", List.of(
          new Option("sales-invoice", "The customer was billed."),
          Option.of("goods-shipment"))),
      DecisionQuestion.score("urgency", "How urgent is it?", List.of(
          Option.of("low"), Option.of("normal"), new Option("high", "Asks for today."))));

  private static final String ANSWERS = "{\"model\":\"gpt-6-luna\",\"answers\":["
      + "{\"type\":\"predicate\",\"name\":\"refund\",\"probability\":0.97},"
      + "{\"type\":\"choice\",\"name\":\"document\",\"choice\":\"sales-invoice\","
      + "\"probabilities\":[{\"value\":\"sales-invoice\",\"probability\":0.9},"
      + "{\"value\":\"goods-shipment\",\"probability\":0.1}],\"confidence\":0.85},"
      + "{\"type\":\"score\",\"name\":\"urgency\",\"score\":1.7,"
      + "\"probabilities\":[{\"value\":0,\"label\":\"low\",\"probability\":0.05},"
      + "{\"value\":1,\"label\":\"normal\",\"probability\":0.2},"
      + "{\"value\":2,\"label\":\"high\",\"probability\":0.75}],\"confidence\":0.6}],"
      + "\"usage\":{\"input_tokens\":401,\"output_tokens\":0,\"total_tokens\":401}}";

  private HttpServer server;
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<JSONObject> request = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();
  private final List<DecisionUsage> recorded = new ArrayList<>();
  private int status = 200;
  private String answer = ANSWERS;
  private String cost = "0.0000401";

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      calls.incrementAndGet();
      path.set(exchange.getRequestURI().getPath());
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      try {
        request.set(new JSONObject(
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      } catch (Exception e) {
        request.set(null);
      }
      if (cost != null) {
        exchange.getResponseHeaders().add(LlmDecider.COST_HEADER, cost);
      }
      byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private LlmDecider decider(String key) {
    return deciderFor(new LlmConfig(baseUrl() + "/", key, null, 5000));
  }

  private LlmDecider deciderFor(LlmConfig config) {
    return new LlmDecider(config, "invoice-inbox", HttpClient.newHttpClient(),
        recorded::add);
  }

  private static DecisionInput ticket() {
    return DecisionInput.ofText("I was charged twice for order #4411, refund one today.");
  }

  @Test
  void theRequestCarriesGuidanceEvidenceAndTypedQuestions() throws Exception {
    decider("sk-test").decide(new DecisionInput()
        .guidance("You triage tickets of a Spanish ERP.")
        .text("Ticket:")
        .json(new JSONObject().put("charges", new JSONArray().put(49).put(49)))
        .image("https://example.com/receipt.png"), QUESTIONS);

    assertEquals("/decisions", path.get());
    assertEquals("Bearer sk-test", authorization.get());
    JSONObject body = request.get();
    assertEquals("etendo-decisions", body.getString("model"));

    // The only shape LiteLLM 1.106 validates: one user message of input_text/input_image parts.
    JSONArray input = body.getJSONArray("input");
    assertEquals(1, input.length());
    JSONObject user = input.getJSONObject(0);
    assertEquals("user", user.getString("role"));
    JSONArray parts = user.getJSONArray("content");
    assertEquals(4, parts.length());
    assertEquals("You triage tickets of a Spanish ERP.", parts.getJSONObject(0).getString("text"));
    assertEquals("Ticket:", parts.getJSONObject(1).getString("text"));
    assertEquals("input_text", parts.getJSONObject(2).getString("type"));
    assertEquals("{\"charges\":[49,49]}", parts.getJSONObject(2).getString("text"));
    assertEquals("input_image", parts.getJSONObject(3).getString("type"));
    assertEquals("https://example.com/receipt.png",
        parts.getJSONObject(3).getString("image_url"));

    JSONArray questions = body.getJSONArray("questions");
    assertEquals("predicate", questions.getJSONObject(0).getString("type"));
    JSONObject choice = questions.getJSONObject(1);
    assertEquals("choice", choice.getString("type"));
    assertEquals("The customer was billed.",
        choice.getJSONArray("choices").getJSONObject(0).getString("description"));
    assertFalse(choice.getJSONArray("choices").getJSONObject(1).has("description"));
    JSONObject score = questions.getJSONObject(2);
    assertEquals("high", score.getJSONArray("levels").getJSONObject(2).getString("label"));
  }

  @Test
  void eachAnswerTypeIsReadWithItsProbabilities() {
    DecisionResult result = decider("sk-test").decide(ticket(), QUESTIONS);

    assertEquals("gpt-6-luna", result.model());
    assertEquals(401, result.inputTokens());
    assertEquals(0.97, result.answer("refund").probability());

    DecisionAnswer document = result.answer("document");
    assertEquals("sales-invoice", document.choice());
    assertEquals(0.85, document.confidence());
    assertEquals(Map.of("sales-invoice", 0.9, "goods-shipment", 0.1), document.probabilities());

    DecisionAnswer urgency = result.answer("urgency");
    assertEquals(1.7, urgency.score());
    assertEquals(List.of("low", "normal", "high"), List.copyOf(urgency.probabilities().keySet()));
    assertEquals(0.75, urgency.probabilities().get("high"));
  }

  @Test
  void anAnsweredRequestIsRecordedWithTheGatewayCost() {
    DecisionResult result = decider("sk-test").decide(ticket(), QUESTIONS);

    assertEquals(new BigDecimal("0.0000401"), result.costUsd());
    assertEquals(1, recorded.size());
    DecisionUsage usage = recorded.get(0);
    assertEquals("invoice-inbox", usage.target());
    assertEquals("etendo-decisions", usage.model());
    assertTrue(usage.ok());
    assertNull(usage.errorCode());
    assertEquals(new BigDecimal("0.0000401"), usage.costUsd());
    assertEquals(401, usage.inputTokens());
    assertEquals(3, usage.questions());
  }

  @Test
  void aMissingOrUnreadableCostIsUnpricedNotFree() {
    cost = null;
    assertNull(decider("sk-test").decide(ticket(), QUESTIONS).costUsd());

    cost = "-1";
    assertNull(decider("sk-test").decide(ticket(), QUESTIONS).costUsd());

    cost = "n/a";
    assertNull(decider("sk-test").decide(ticket(), QUESTIONS).costUsd());
    assertNull(recorded.get(2).costUsd());
  }

  @Test
  void everyAttemptedCallThatFailsIsRecordedAsAnError() {
    status = 429;
    cost = "0";
    answer = "{\"error\":{\"message\":\"no credits\"}}";
    assertThrows(LlmDecisionException.class, () -> decider("sk-test").decide(ticket(), QUESTIONS));

    status = 200;
    answer = "{\"answers\":[]}";
    assertThrows(LlmDecisionException.class, () -> decider("sk-test").decide(ticket(), QUESTIONS));

    server.stop(0);
    assertThrows(LlmDecisionException.class, () -> decider("sk-test").decide(ticket(), QUESTIONS));

    assertEquals(List.of("http_429", "invalid_response", "unreachable"),
        recorded.stream().map(DecisionUsage::errorCode).collect(Collectors.toList()));
    assertTrue(recorded.stream().noneMatch(DecisionUsage::ok));
    assertEquals(BigDecimal.ZERO, recorded.get(0).costUsd());
    assertNull(recorded.get(2).costUsd());
  }

  @Test
  void aFailureToRecordNeverBreaksTheDecision() {
    LlmDecider decider = new LlmDecider(new LlmConfig(baseUrl(), "sk-test", null, 5000),
        "invoice-inbox", HttpClient.newHttpClient(), usage -> {
          throw new IllegalStateException("no DAL");
        });

    assertEquals("sales-invoice", decider.decide(ticket(), QUESTIONS).answer("document").choice());
  }

  @Test
  void aRefusalIsAnAnswerButFailsTheShortcuts() {
    answer = "{\"answers\":[{\"type\":\"refusal\",\"name\":\"single\"}]}";

    DecisionAnswer refused = decider("sk-test").decide(ticket(),
        List.of(DecisionQuestion.predicate("single", "Is it spam?"))).answers().get(0);
    assertTrue(refused.refused());
    assertEquals(Type.PREDICATE, refused.type());
    assertNull(refused.probability());

    assertFalse(assertThrows(LlmDecisionException.class,
        () -> decider("sk-test").probability("text", "Is it spam?")).isRetryable());
  }

  @Test
  void theShortcutsAskOneQuestion() throws Exception {
    answer = "{\"answers\":[{\"type\":\"choice\",\"name\":\"single\",\"choice\":\"b\"}]}";

    assertEquals("b", decider("sk-test").choose("text", "Pick one", "a", "b").choice());
    assertEquals(1, request.get().getJSONArray("questions").length());
  }

  @Test
  void aChoiceOutsideTheValuesIsRejected() {
    answer = "{\"answers\":[{\"type\":\"choice\",\"name\":\"single\",\"choice\":\"c\"}]}";

    LlmDecisionException e = assertThrows(LlmDecisionException.class,
        () -> decider("sk-test").choose("text", "Pick one", "a", "b"));
    assertFalse(e.isRetryable());
    assertTrue(e.getMessage().contains("'c'"));
  }

  @Test
  void answersThatDoNotMatchTheQuestionsAreRejected() {
    answer = "{\"answers\":[{\"type\":\"predicate\",\"name\":\"refund\",\"probability\":1}]}";
    assertThrows(LlmDecisionException.class, () -> decider("sk-test").decide(ticket(), QUESTIONS));

    answer = "{\"answers\":[{\"type\":\"predicate\",\"name\":\"other\",\"probability\":1}]}";
    assertThrows(LlmDecisionException.class, () -> decider("sk-test").decide(ticket(),
        List.of(DecisionQuestion.predicate("refund", "Refund?"))));

    answer = "{\"answers\":[{\"type\":\"score\",\"name\":\"refund\",\"score\":1}]}";
    assertThrows(LlmDecisionException.class, () -> decider("sk-test").decide(ticket(),
        List.of(DecisionQuestion.predicate("refund", "Refund?"))));
  }

  @Test
  void aRateLimitIsRetryableAndCarriesTheGatewayMessage() {
    status = 429;
    answer = "{\"error\":{\"message\":\"You have no credits remaining.\",\"code\":\"429\"}}";

    LlmDecisionException e = assertThrows(LlmDecisionException.class,
        () -> decider("sk-test").decide(ticket(), QUESTIONS));
    assertTrue(e.isRetryable());
    assertEquals("Decision request failed with HTTP 429: You have no credits remaining.",
        e.getMessage());
  }

  @Test
  void aRejectedRequestIsNotRetryableButAServerErrorIs() {
    status = 401;
    answer = "{\"error\":{\"message\":\"Authentication Error\"}}";
    assertFalse(assertThrows(LlmDecisionException.class,
        () -> decider("sk-bad").decide(ticket(), QUESTIONS)).isRetryable());

    status = 503;
    assertTrue(assertThrows(LlmDecisionException.class,
        () -> decider("sk-test").decide(ticket(), QUESTIONS)).isRetryable());
  }

  @Test
  void noServerIsRetryable() {
    server.stop(0);

    assertTrue(assertThrows(LlmDecisionException.class,
        () -> decider("sk-test").decide(ticket(), QUESTIONS)).isRetryable());
  }

  @Test
  void withoutAKeyNothingIsSentAndTheCopilotKeyIsNamed() {
    LlmDecisionException e = assertThrows(LlmDecisionException.class,
        () -> decider(null).decide(ticket(), QUESTIONS));

    assertFalse(e.isRetryable());
    assertTrue(e.getMessage().contains("openai.api.key"));
    assertEquals(0, calls.get());
    assertTrue(recorded.isEmpty());
  }

  @Test
  void unusableRequestsFailBeforeAnyCall() {
    LlmDecider decider = decider("sk-test");
    DecisionQuestion q = DecisionQuestion.predicate("q", "Q?");

    assertThrows(IllegalArgumentException.class, () -> decider.decide(new DecisionInput(),
        List.of(q)));
    assertThrows(IllegalArgumentException.class, () -> decider.decide(ticket(), List.of()));
    assertThrows(IllegalArgumentException.class, () -> decider.decide(ticket(), List.of(q, q)));
    assertThrows(IllegalArgumentException.class, () -> DecisionQuestion.predicate("q", " "));
    assertThrows(IllegalArgumentException.class, () -> DecisionQuestion.choice("q", "Q?", "a"));
    assertThrows(IllegalArgumentException.class,
        () -> DecisionQuestion.choice("q", "Q?", "a", "a"));
    assertThrows(IllegalArgumentException.class, () -> DecisionQuestion.score("q", "Q?",
        IntStream.range(0, 11).mapToObj(i -> Option.of("l" + i)).collect(Collectors.toList())));
    assertThrows(IllegalArgumentException.class, () -> new DecisionInput().json("not json"));
    assertEquals(0, calls.get());
    assertTrue(recorded.isEmpty());
  }

  @Test
  void anotherModelKeepsTheGatewayAndTheKey() throws Exception {
    LlmConfig config = new LlmConfig(baseUrl(), "sk-test", null, 5000).withModel("jev");

    deciderFor(config).decide(ticket(), QUESTIONS);

    assertEquals("jev", request.get().getString("model"));
    assertEquals("Bearer sk-test", authorization.get());
    assertFalse(config.toString().contains("sk-test"));
  }
}
