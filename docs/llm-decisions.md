# LLM decisions (`com.etendoerp.go.llm`)

`LlmDecider` is the Java client of the **Decisions API** (`POST /decisions` on the LiteLLM
gateway, OpenAI format). A decision model answers typed questions about an input and returns
**probabilities instead of text**: the probability that a condition holds, a pick from a fixed
list, or a level on an ordered scale. OpenAI states it is about 10x faster than generating text,
and only input tokens are billed. References: [OpenAI guide](https://developers.openai.com/api/docs/guides/decisions),
[LiteLLM `/v1/decisions`](https://docs.litellm.ai/docs/decisions).

Use it to classify, route or prioritise when the set of answers is known in advance. To generate
text or extract fields, use chat/structured outputs instead.

```java
DecisionResult r = new LlmDecider("ticket-triage").decide(   // target: who spends, in usage
    new DecisionInput()
        .guidance("You triage support tickets of a Spanish ERP.")
        .text("I was charged twice for order #4411, refund one today.")
        .json(orderJson)                       // JSONObject / JSONArray
        .image("data:image/png;base64,..."),   // or a public https URL
    List.of(
        DecisionQuestion.predicate("refund", "Is the customer asking for a refund?"),
        DecisionQuestion.choice("document", "Which document is involved?", List.of(
            new Option("sales-invoice", "The customer was billed."),
            Option.of("goods-shipment"),
            Option.of("sales-order"))),
        DecisionQuestion.score("urgency", "How urgent is the request?", List.of(
            Option.of("low"), Option.of("normal"), new Option("high", "Asks for today.")))));

r.answer("refund").probability();      // 0.97
r.answer("document").choice();         // "sales-invoice" (always one of the values)
r.answer("document").probabilities();  // {sales-invoice=0.9, goods-shipment=0.08, sales-order=0.02}
r.answer("urgency").score();           // 1.7  (0 = low … 2 = high, probability-weighted)

// Shortcuts for one question over a text (a refusal throws):
new LlmDecider("spam-filter").probability(text, "Is it spam?");
new LlmDecider("ticket-triage").choose(text, "Which department?", "billing", "technical").choice();
```

## Request

| Part | What it is |
|---|---|
| `DecisionInput` | The evidence every question is evaluated against, in the order added: `text`, `json`, `image`, plus an optional `guidance`. |
| `DecisionQuestion` | 1 or more per request, each with a unique `name` (its answer is found by it) and `instructions`. |

| Question | Options | Answer |
|---|---|---|
| `predicate(name, instructions)` | — | `probability` 0..1 that it is true |
| `choice(name, instructions, options)` | 2–255 distinct values, each with an optional `description` saying when it applies | `choice`, `probabilities` per value, `confidence` |
| `score(name, instructions, levels)` | 2–10 distinct labels, **lowest first**, optional `description` | `score` (probability-weighted level index), `probabilities` per label, `confidence` |

There is **no system prompt** in this API. Put what to evaluate in each question's
`instructions`; `guidance` carries context shared by all questions (who you are, the domain).

**What the gateway accepts.** LiteLLM 1.106 (the version behind `llm.etendo.software`) validates
`input` more strictly than OpenAI: only **one `user` message of `input_text` and `input_image`
parts**. It answers 400 to `developer`/`system` messages and to `input_json` parts. So
`DecisionInput` sends `guidance` as the first text part and `json(...)` serialized as a text part.
Not supported here: conversation history with tool calls, per-question images, `box` questions
(TypeSafe models only) and prompt caching options.

## Answers

`DecisionResult.answers()` has one `DecisionAnswer` per question, in order. `answer(name)`
looks one up. `model()`, `inputTokens()` and `costUsd()` come from the response. The answers are checked
against the questions: if the count, a name, a type or a `choice` value does not match, the call
throws `LlmDecisionException`, so a value the caller never offered is never returned.

A question the model declines comes back with `refused() == true` and no values. `choose` and
`probability` throw in that case.

These are probabilities, not verdicts. Set thresholds from labelled examples according to the
cost of a false positive and a false negative.

## Usage and spend

Every request that is sent is recorded as one `ai.decision.execute` event in `ETGO_USAGE_EVENT`.
That includes failed ones (`outcome = error`, `errorCode` `http_<status>`, `unreachable` or
`invalid_response`). A request that never left (invalid arguments, no key) is not recorded.

| Column | Value |
|---|---|
| client/org/user/role | the caller's `OBContext` |
| `target` | the feature that asked: `new LlmDecider("invoice-inbox")`; `decisions` when none is given |
| `action` / `durationMs` | `decide` / request time |
| `COST_USD` | the cost LiteLLM reports in the `x-litellm-response-cost` header; null when absent or unreadable (unpriced, never "free") |
| `properties` | `model`, `inputTokens`, `questions` |

`COST_USD` counts in the tenant's AI spend (`aiSpend` billing resource, `AiSpendCounter`) together
with the agent chat and the OCR. Recording goes through `UsageEventRecorder`: asynchronous, never
blocks. A failure to record is only logged (`Decision usage not recorded ...`), never thrown, and
never changes the decision. The event type is described with the others in `neo-headless.md`.

## Configuration

No setting of its own. `LlmConfig.fromRuntime()` reuses the Copilot credentials every Etendo Go
environment already has, read from `Openbravo.properties` exactly as Copilot's `OpenAIUtils` reads
them (legacy upper-case names included):

| Value | Source | When absent |
|---|---|---|
| API key | `openai.api.key` (legacy `OPENAI_API_KEY`) via `OpenAIUtils.getOpenaiApiKey()` | `LlmDecisionException`, no call made |
| Gateway | `copilot.proxy.url` (legacy `COPILOT_PROXY_URL`) | `https://llm.etendo.software` |
| Model | constant `etendo-decisions` | — |
| Timeout | constant 30 s (connect and request) | — |

The key is the LiteLLM key Copilot (and, through the Copilot service, the OCR) already uses. The
request goes to `{gateway}/decisions`. LiteLLM serves it with or without `/v1`, so the Copilot URL
works as is. `LlmConfig.toString()` never prints the key.

`etendo-decisions` is the LiteLLM model group for this API. It routes to OpenAI `gpt-6-luna`,
the only model OpenAI's Decisions API accepts. LiteLLM can route other decision models (TypeSafe
Jev, Cloudflare Clef, …) once they are added there; a caller selects one, keeping the same
credentials, with `new LlmDecider(LlmConfig.fromRuntime().withModel("<model group>"), "<target>")`.

## Errors

| Case | Exception | `isRetryable()` |
|---|---|---|
| Empty input, no questions, duplicate names, blank instructions, too few/many or repeated options | `IllegalArgumentException` (no call made) | — |
| No Copilot key (`openai.api.key`) | `LlmDecisionException`, names the property (no call made) | `false` |
| HTTP 408, 429, 5xx; timeout; network failure | `LlmDecisionException` with the gateway's `error.message` (max 300 chars) | `true` |
| Any other non-2xx (401 rejected key, 400 invalid request) | same | `false` |
| Answers that do not match the questions; unreadable response | `LlmDecisionException` | `false` |
| A refused question via `choose`/`probability` | `LlmDecisionException` | `false` |

LiteLLM reports an exhausted upstream account as **429** (`credit_balance_exhausted`), so it is
classified retryable even though it only clears once credits are added. Read the message before
retrying in a loop.
