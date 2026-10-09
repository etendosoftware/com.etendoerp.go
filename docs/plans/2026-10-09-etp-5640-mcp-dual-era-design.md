# MCP dual-era server — supporting spec 2026-07-28 next to the initialize era (ETP-5640)

Status: design (spike, no production code) · Date: 2026-10-09 · Module: `com.etendoerp.go`
(`src/com/etendoerp/go/mcp/`) · Depends on: ETP-5639 (legacy era caught up to 2025-11-25)

Origin: `schema_forge/docs/plans/2026-10-06-mcp-method-not-found-and-server-discover.md` §3, §4,
§5.2 and ticket draft T5.

Normative sources (read 2026-10-09):
[versioning](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning) ·
[server/discover](https://modelcontextprotocol.io/specification/2026-07-28/server/discover) ·
[Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http) ·
[base protocol / `_meta` / error codes](https://modelcontextprotocol.io/specification/2026-07-28/basic/index) ·
[caching](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching) ·
[changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog) ·
[schema.ts](https://github.com/modelcontextprotocol/specification/blob/main/schema/2026-07-28/schema.ts).

Line numbers below are from `feature/ETP-5640` (cut from `develop`, contains ETP-5639).

---

## 0. Summary of decisions

| # | Decision |
|---|---|
| D1 | **Era is decided per request from the body, not from headers alone.** A request is *modern* when `params._meta["io.modelcontextprotocol/protocolVersion"]` is present, or its method is `server/discover`, or its `MCP-Protocol-Version` header names a modern version. Everything else is *legacy* and keeps ETP-5639 behaviour byte for byte. `initialize` is always legacy. |
| D2 | **Strict where the spec's era detection depends on it, lenient where it does not.** Modern requests get spec error bodies with HTTP status (`400` + `-32022` / `-32020` / `-32602`, `404` + `-32601`). A *missing* mirror header (`MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name`) or missing `clientCapabilities` is served with one WARN by default (`mcp.modern.strict=false`); a *mismatching* header is always `400 -32020`. Legacy requests keep the ETP-5639 lenient policy unchanged. |
| D3 | **`server/discover` goes in last, as its own revertable commit.** It is the switch that moves Claude Code and the Claude.ai connector to modern mode; everything a modern client needs (result shape, errors, telemetry) ships before it. |
| D4 | **Kill switch: backend feature flag `mcp-modern-era-disabled` (`GoFeatureFlags`, OpenFeature + ConfigCat).** Dual-era is ON by default: the flag unset, `false`, unreachable or failing all resolve to `false` = modern served. `true` restores today's behaviour exactly (row K1): `server/discover` → `-32601`, `_meta` ignored, every request legacy. No restart only where `etendo.go.configcat.sdkKey` is set (ConfigCat provider). Without an SDK key, `PropertiesFeatureProvider` reads `etendo.go.flags.mcp-modern-era-disabled` / `ETGO_FLAG_MCP_MODERN_ERA_DISABLED`, and flipping it needs a Tomcat restart. A client that already cached "modern" keeps working (tool calls need no `initialize`). |
| D5 | **Telemetry: a server-derived session key for modern traffic.** Client name/version come from each request's `_meta`. The session key is minted per (user, client, role, client name) and renewed after 30 min of inactivity, prefixed `m-` so the era is visible without a schema change. No new column, no change to `McpUsageRow`'s shape. |
| D6 | **Modern-only response decoration.** `resultType: "complete"` and a minimal `_meta.serverInfo` on every modern result; `ttlMs` + `cacheScope` on `server/discover`, `tools/list`, `resources/list`, `resources/read`. Legacy responses are not touched. |
| D7 | **Resource not found → `-32602` in the modern era only.** Today it is not `-32002` but `-32603` plus an ERROR stack trace (§5.4); legacy keeps its code, both eras lose the stack trace. |

The user's decisions on the open questions are recorded in §9.

---

## 1. What the code does today (facts the design starts from)

- **One servlet, one POST path.** `McpServlet.doPost` (`McpServlet.java:159-252`) authenticates
  (`:165`), refuses commercially blocked tenants (`:169`), parses the body, runs the
  `MCP-Protocol-Version` check for every non-`initialize` method (`:196-202`), dispatches
  (`:205`, switch at `:624-645`) and writes the result with HTTP `200` (`:220-222`).
- **Every JSON-RPC error is written with HTTP 200** (`writeRpcError`, `:260-273`). Unknown methods
  answer `200` + `-32601` (`:229-236`); anything else, including bad params, `200` + `-32603`
  (`:237-245`).
- **Version policy (ETP-5639).** `McpProtocolVersion.SUPPORTED` = `2024-11-05 … 2025-11-25`
  (`McpProtocolVersion.java:54`). `initialize` negotiates (`:69-72`, used at
  `McpServlet.java:661`). Later requests: header missing → `2025-03-26`; unsupported → served with
  the session's version plus one WARN, **never refused** (`McpProtocolVersion.java:83-96`). The
  class Javadoc already announces that this turns strict with 2026-07-28 (`:35-40`).
- **The handshake carries only telemetry.** `handleInitialize` (`McpServlet.java:658-700`) mints a
  key with `McpUsageTelemetry.openSession` (`McpUsageTelemetry.java:173-183`) and publishes it in
  `Mcp-Session-Id`. `doPost` binds the echoed header to a ThreadLocal (`McpServlet.java:183-184`,
  `McpUsageTelemetry.java:91`); `McpCallObservation.sessionKey()` reads the header again
  (`McpCallObservation.java:56`); `recordToolCall` resolves the client name from it
  (`McpServlet.java:335-336`).
- **Auth is per request** (`authenticate`, `:465-517`): Bearer (OAuth2 → legacy JWT) or the cookie
  session. Nothing auth-related depends on `initialize`. Tool calls already work without it.
- **Who consumes the session key besides the usage row:** the WARN/ERROR lines
  (`sessionForLog`, `McpUsageTelemetry.java:148-151`; callers `McpServlet.java:235,239`,
  `McpProtocolVersion.java:94`, `McpRoutingException.java:107`, `McpToolRouter.java:246,252,256,312,1563,1566`,
  `McpWriteRequestSupport.java:658`), the feedback INFO line (`McpFeedbackTool.java:169`) and the
  **feedback rate limit**, which falls back to one shared `__anonymous__` bucket when there is no key
  (`McpFeedbackTool.java:71,100-104`).
- **Production (Datadog, last 3 days, from the coordinator):** 245 × `MCP client called unsupported
  method 'server/discover'`, all `session=none`, clients `claude-code` (majority) and
  `Anthropic/ClaudeAI`. No Cursor/ChatGPT. Today those clients read `200 -32601` as "legacy" and
  fall back to `initialize`.

## 2. Era detection on Streamable HTTP

### 2.1 What marks a request

The spec's own rule for a dual-era server (versioning, *Compatibility Matrix*): *"A request carrying
modern per-request `_meta` is served statelessly according to this revision. An `initialize` request
selects legacy semantics."* The body is the source of truth; headers are mirrors (Streamable HTTP,
*Server Validation*). So:

| Signal | Meaning | Why it is unambiguous |
|---|---|---|
| `params._meta["io.modelcontextprotocol/protocolVersion"]` present (any value) | **Modern** | The key is reserved by MCP and defined only from 2026-07-28; no legacy revision sends it |
| `method == "server/discover"` | **Modern** | The method does not exist in any legacy revision |
| `MCP-Protocol-Version` header ∈ modern set (`2026-07-28`) | **Modern** (malformed if `_meta` is missing) | Legacy clients only send legacy versions |
| `method ∈ {initialize, notifications/initialized, initialized}` | **Legacy**, always — `_meta` ignored | Spec: `initialize` selects legacy semantics |
| None of the above | **Legacy** | ETP-5639 path, unchanged |

`Mcp-Method`, `Mcp-Name` and `Mcp-Session-Id` are **not** era signals. A stale `Mcp-Session-Id` on a
modern request is ignored, never echoed, and no key is minted for it (Streamable HTTP, *Earlier
Streamable HTTP Revisions*).

The classifier is a pure function of `(method, params._meta, headers)`, so it lives in a new
package-private class (`McpRequestEra`, name provisional) with no servlet, DAL or OBContext
dependency, and is unit-tested on its own. It returns the era, the effective protocol version, the
per-request `clientInfo`, and either "serve" or the error to answer.

### 2.2 Where the lenient policy turns strict

ETP-5639's lenience exists so a legacy client is never cut off for a header it gets wrong. It stays
exactly as is for **legacy** requests, including an unknown header value with no `_meta` (row L3
below): there is no modern marker, so it is a legacy client with a bad header, not a probe.

It turns strict for **modern** requests, because a dual-era client decides between "modern server"
and "fall back" from the body of a `4xx` (Streamable HTTP, *Backward Compatibility*): a recognised
modern error means "stay modern and retry", anything else means "fall back to `initialize`". So every
modern refusal must be a spec error with the spec's status — never the legacy `200`:

- unsupported version → `400` + `-32022` with `data.supported`/`data.requested`;
- header present and different from the body → `400` + `-32020` (`HeaderMismatch`), always;
- unknown method → `404` + `-32601`;
- malformed `_meta` → `400` + `-32602`.

**Missing** mirror headers and missing `clientCapabilities` are the exception (D2). The spec lists
them as validation failures, but a `-32020` is a *recognised modern error*: the client would not fall
back, it would retry the same request and fail forever. If a shipping client omits `Mcp-Name` (a
2026-07-28 addition, SEP-2243) the strict reading turns a cosmetic gap into a full outage for that
client. We have no intermediary routing on these headers, so the security reason behind the rule does
not apply to us. Default: serve + one WARN per request; `mcp.modern.strict=true` makes them `400`.
A mismatch is a different case — two sources of truth disagreeing — and is refused in both modes.

### 2.3 Decision table — request shape → era → response

`H` = `MCP-Protocol-Version` header, `M` = `_meta` protocolVersion. "lenient/strict" = `mcp.modern.strict`.
All rows assume the request already passed auth (`401`/`403`/`402` are unchanged and era-independent).

| # | Request shape | Era | Response |
|---|---|---|---|
| L1 | `initialize` (any headers, `_meta` ignored) | legacy | `200`, negotiated legacy version (`2026-07-28` asked → `2025-11-25`, as today), `Mcp-Session-Id` minted |
| L2 | No `M`; `H` absent or ∈ legacy set | legacy | ETP-5639 unchanged: `200`, no `resultType`, unknown method `200 -32601` |
| L3 | No `M`; `H` unknown (e.g. `2027-01-01`, `garbage`) | legacy | ETP-5639 unchanged: served with session version or latest + WARN |
| L4 | `notifications/initialized` | legacy | `202 Accepted`, no body |
| M1 | `M = 2026-07-28`, `H = M`, `Mcp-Method` = method, `Mcp-Name` ok | modern | `200`, result + `resultType` + `_meta.serverInfo` (+ cache hints, §5.2); no `Mcp-Session-Id` |
| M2 | `M` present, not a modern version we serve (`2027-01-01`, or a legacy version such as `2025-11-25` sent per request) | modern | `400` + `-32022` `{supported:[…], requested:M}` |
| M3 | `M` present, `H` present and `≠ M` | modern | `400` + `-32020` |
| M4 | `M` present, `H` absent | modern | lenient: serve as M1 + WARN · strict: `400 -32020` |
| M5 | `M` present, `Mcp-Method` present and `≠ method`; or `Mcp-Name` present and `≠ params.name`/`params.uri` after Base64-sentinel decoding | modern | `400` + `-32020` |
| M6 | `M` present, `Mcp-Method` absent, or `Mcp-Name` absent on `tools/call`/`resources/read` | modern | lenient: serve + WARN · strict: `400 -32020` |
| M7 | `M` present, `clientCapabilities` absent or not an object | modern | lenient: serve + WARN · strict: `400 -32602` |
| M8 | `M` not a string (number, object) | modern | `400` + `-32602` |
| M9 | `server/discover` without `M` | modern | lenient: `DiscoverResult` + WARN · strict: `400 -32602` |
| M10 | No `M`, `H = 2026-07-28` (modern header, no `_meta`) | modern | lenient: serve as M1 + WARN · strict: `400 -32602` |
| M11 | Modern, method `ping`, `subscriptions/listen`, `resources/templates/list`, `logging/setLevel` or any unknown method | modern | `404` + `-32601` |
| M12 | Modern, method `initialize` | — | cannot happen: L1 wins (initialize is always legacy) |
| K1 | flag `mcp-modern-era-disabled` = `true`, any shape | legacy | today's behaviour: `server/discover` → `200 -32601`, `_meta` ignored, M-rows behave as L2/L3 |

`supported` (in M2 and in `server/discover`) lists every version served, newest first:
`["2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"]`, as the spec's own `-32022`
example does (it lists a legacy version next to the modern one). The legacy entries tell a dual-era
client it can also fall back via `initialize`.

Non-protocol failures in the modern era (`-32603` from a handler, tool errors carried in
`isError: true`) keep HTTP `200`: the spec assigns statuses only to the cases above, and keeping `200`
there means the client's era logic never sees them.

## 3. `server/discover`, `ping`, `initialize`

### 3.1 `server/discover` (modern only)

```json
{
  "resultType": "complete",
  "supportedVersions": ["2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"],
  "capabilities": {
    "tools":     { "listChanged": false },
    "resources": { "listChanged": false }
  },
  "_meta": {
    "io.modelcontextprotocol/serverInfo": {
      "name": "etendo-mcp", "version": "1.0.0", "title": "Etendo MCP",
      "websiteUrl": "https://app.etendo.ai", "description": "…",
      "icons": [{ "src": "https://app.etendo.ai/favicon.png", "mimeType": "image/png", "sizes": ["513x513"] }]
    }
  },
  "ttlMs": 3600000,
  "cacheScope": "public"
}
```

- `capabilities` and `serverInfo` come from **one builder shared with `initialize`**
  (today inlined in `handleInitialize`, `McpServlet.java:674-697`), so the two eras cannot drift.
  `listChanged: false` is kept: we do not implement `subscriptions/listen`, so we never push
  `list_changed`, and the TTL becomes the client's only freshness signal — which is what the caching
  page prescribes for that case.
- `serverInfo` here is the full `Implementation` (title, icons, description — same as
  `initialize`). Per-result `_meta.serverInfo` (§5.1) carries only `name` + `version`.
- `instructions`: **omitted**, as `initialize` omits it today. Adding server instructions is a
  separate, ACE-relevant change for both eras, not part of the era work.
- `ttlMs: 3600000`, `cacheScope: "public"`: the answer is the same for every caller (no role, tenant
  or language in it), so `public` is correct per the caching page. Provisional default.
- Auth: still required — `server/discover` is a POST like any other and passes through
  `authenticate` first. The production probes already arrive authenticated (they reach dispatch
  today), and an unauthenticated one gets the same `401` + `WWW-Authenticate` that starts OAuth.

### 3.2 `ping` and `initialize`

- `ping`: legacy only. Removed by 2026-07-28 (changelog major #5) → modern `404 -32601` (M11).
  Legacy keeps `{}` (`McpServlet.java:632-633`).
- `initialize` / `notifications/initialized`: legacy only, and returned **unchanged**:
  `protocolVersion` (negotiated within the legacy set — a client asking `2026-07-28` still gets
  `2025-11-25`, as `McpServletTest` pins today), `capabilities`, `serverInfo`, and the
  `Mcp-Session-Id` header. No `resultType`, no cache hints.

## 4. Per-request `_meta`

Read from `params._meta` (a request with no `params` has no `_meta`):

| Key | Required by spec | Use | Validation |
|---|---|---|---|
| `io.modelcontextprotocol/protocolVersion` | yes | era + version | string; ∈ `MODERN_SUPPORTED` (`["2026-07-28"]`) else `-32022`; equals header (`-32020`) |
| `io.modelcontextprotocol/clientCapabilities` | yes | none — we never require a client capability, so `-32021` is never emitted | object; absent → M7 |
| `io.modelcontextprotocol/clientInfo` | SHOULD | telemetry name/version, log lines | `name`/`version` read as trimmed strings, bounded length; anything else ignored, never refused |
| `io.modelcontextprotocol/logLevel` | no (deprecated) | none — we emit no `notifications/message` | ignored |
| `traceparent` / `tracestate` / `baggage` | no | none now; presence logged once per derived session (§6.3) to learn whether clients send it | ignored |

`McpProtocolVersion` grows a second set: `LEGACY_SUPPORTED` (today's `SUPPORTED`) and
`MODERN_SUPPORTED = ["2026-07-28"]`, plus `ALL_SUPPORTED` (newest first) for `-32022` and discover.
`forRequest` stays legacy-only; its WARN keeps firing only for L3.

`-32022` body (schema `UnsupportedProtocolVersionError`):

```json
{ "jsonrpc": "2.0", "id": 1,
  "error": { "code": -32022, "message": "Unsupported protocol version",
             "data": { "supported": ["2026-07-28", "2025-11-25", "…"], "requested": "2027-01-01" } } }
```

`ProtocolErrorAdapters.buildJsonRpcError` has no `data`; the modern path needs an overload with
`data` and with an HTTP status, instead of `writeRpcError`'s hard-coded `200` (`McpServlet.java:265`).

Code-range rule (base protocol, *Error Codes*): we must not emit any code in `-32020…-32099` other
than the three defined ones. Today the MCP module emits only `-32601`/`-32603`, so nothing to fix.

## 5. Response changes

### 5.1 Every modern result

- `resultType: "complete"` (required by schema `Result`). We never need client input, so
  `"input_required"` (MRTR) never occurs.
- `_meta["io.modelcontextprotocol/serverInfo"] = {name, version}` (SHOULD). Minimal on purpose:
  ~50 bytes per response instead of ~400 with icons and description.
- Added in one place — `doPost`, after dispatch, when the era is modern — not in each handler, so a
  new method cannot forget it. Legacy results are left byte-identical (clients of 2025-11-25 accept
  extra keys, but there is no reason to change what works).

### 5.2 Cache hints (`CacheableResult`)

| Result | `ttlMs` (provisional) | `cacheScope` | Why |
|---|---|---|---|
| `server/discover` | 3 600 000 | `public` | identical for all callers |
| `tools/list` | 300 000 | `private` | depends on role/window access, OAuth scopes and the user's language (`McpServlet.java:716-738`, titles via `McpToolTitles`) |
| `resources/list` | 300 000 | `private` | filtered by role (`McpResourceProvider.appendSpecResources`) |
| `resources/read` | 300 000 | `private` | access-checked per role |

Five minutes is shorter than what legacy clients effectively use today (`listChanged: false`, so they
keep the list for the whole session) and shorter than `McpConfigCache`'s 30-min access TTL. A config
push (`push-to-neo`) or role change is therefore visible to a modern client within five minutes. We
paginate nothing, so the per-page rules do not apply.

### 5.3 Deterministic `tools/list` order

Already deterministic for a given DB state and scope set: built-ins in fixed order, then process /
report tools from specs ordered by name, then the CRUD tools in fixed order, then the image tools
(`ToolRegistry.java:89-147`, `ORDER BY name` at `:105-109`, CRUD order at `:250-290`). What is not
proven is the order **inside** generated schemas — the `spec` enums and `McpNamedFilterCatalog.summary`
(`:264-265`). Work item: a test that builds the catalog twice and compares the rendered JSON
byte for byte, fixing any `HashMap`/`HashSet` iteration it exposes. Both eras benefit (prompt-cache
hit rate), so it is not era-gated.

### 5.4 Resource not found

Today an unknown URI throws `IllegalArgumentException` (`McpResourceProvider.java:173`) and an
unknown or inaccessible spec throws `OBSecurityException` (`:252`, `:292`, `:308`). Both land in the
generic catch: **`200` + `-32603` and an ERROR log with stack trace** — not the legacy `-32002`.

- Introduce `McpResourceNotFoundException` for "no such resource for this caller". Unknown spec and
  inaccessible spec stay the **same** answer (no existence leak), as they are today.
- Modern: `-32602` (Invalid params), HTTP `200` (the spec assigns no status to it), message
  `Resource not found: <uri>`.
- Legacy: keep `-32603` (no client depends on it, and changing a legacy code is out of scope; moving
  legacy to `-32002` is a one-line follow-up if wanted).
- Both: WARN one line, no stack trace — a caller asking for something that does not exist is not a
  server failure (same reasoning as ETP-5639's unknown-method fix).

### 5.5 Headers

- **Request**: read `Mcp-Method`, `Mcp-Name` (decode `=?base64?…?=`, case-sensitive sentinel) for
  validation only (M5/M6). `Mcp-Param-*`: we declare no `x-mcp-header`, so we recognise none and
  ignore them (spec: only *recognised* ones are validated).
- **Response**: modern requests never get `Mcp-Session-Id`. Legacy `initialize` keeps minting it.
- **CORS** (`setCorsHeaders`, `McpServlet.java:136-141`): allow-list becomes
  `Content-Type, Authorization, Accept, Mcp-Session-Id, MCP-Protocol-Version, Mcp-Method, Mcp-Name, X-Go-CSRF, X-Go-Account`.
  Exposed headers unchanged (`Mcp-Session-Id, WWW-Authenticate`, still needed by legacy browser
  clients). `Mcp-Param-*` cannot be wildcarded under credentialed CORS and we declare none.
- `GET` already answers `405` (`:415-430`, ETP-5639) and `DELETE` gets `HttpServlet`'s default `405`;
  both are what the 2026-07-28 transport asks for, and legacy tolerates them. No change.

## 6. Telemetry without `Mcp-Session-Id`

### 6.1 Client name

Per request, from `_meta["io.modelcontextprotocol/clientInfo"]` — the spec says clients SHOULD send it
on every request. Resolved once in `doPost` into the per-request context and bound to a ThreadLocal
next to the session key, so `recordToolCall` (`McpServlet.java:335-370`) and `clientNameFor`
(`:286-297`) read it from there instead of from the `SESSIONS` registry. Legacy keeps reading the
registry. Claude Code and the Claude.ai connector therefore keep their `client_name` the moment they
switch — the coordinator's main concern.

### 6.2 What replaces the session key — options

| Option | Grouping quality | Cost / risk | Verdict |
|---|---|---|---|
| A. Per-call rows, `session_key = null` | none: a task is no longer readable as a sequence; feedback loses its story; all modern clients share the `__anonymous__` feedback bucket (10 reports/hour **across every tenant**, `McpFeedbackTool.java:71,100-104`) | zero code | Rejected — the rate-limit consequence alone is a bug |
| B. Client-supplied id (`traceparent` trace-id, or a vendor `_meta` key) | best when present | no MCP-standard conversation id exists; we do not know whether Claude Code sends `traceparent`; heterogeneous semantics (a trace can be one turn, not one task) | Not as the base. Measure presence during rollout (§6.3); revisit with data |
| C. **Server-derived key**: `m-<uuid>` per (`AD_User_ID`, token `AD_Client_ID`, `AD_Role_ID`, `clientInfo.name`), renewed after 30 min without a call | good: matches how one agent works on one task; same heuristic web analytics uses | two concurrent conversations of the same user with the same client merge; node-local map (multi-task ECS splits a task across nodes) | **Recommended** |
| D. Server-minted handle as a tool argument (the spec's own advice for cross-call *state*, SEP-2567) | exact | adds an argument to every tool schema (ACE cost), relies on the agent echoing it, and it is state the server does not need | Rejected — telemetry is not state the protocol should carry |
| E. Offline sessionisation in SQL (gap on `created` per user + client name) | good, cluster-wide | not available at request time, where the log lines and the feedback rate limit need a key | Kept as the **authoritative** cross-node grouping for analysis; complements C |

### 6.3 Recommended design (C, with E as ground truth)

- `McpUsageTelemetry.sessionFor(DerivedKey tuple, ClientInfo, nowMs)`: a bounded
  (`MAX_TRACKED_SESSIONS = 1000`, oldest-first, like `SESSIONS` at `McpUsageTelemetry.java:74-82`)
  map tuple → `{sessionKey, lastSeenMs}`; a gap > 30 min mints a new `m-` + UUID (38 chars;
  `ETGO_MCP_USAGE.SESSION_KEY` is `VARCHAR(200)`,
  `src-db/database/model/tables/ETGO_MCP_USAGE.xml:36`). The tuple uses only values we already
  hold before dispatch (`AuthIdentity`, `McpServlet.java:918-932`) — the effective tenant is resolved
  later in `executeInContext`, but the token's client is stable for one caller, which is all the key
  needs. The tuple is kept in memory only and never logged.
- The key is bound with the existing `setCurrentSessionKey` (`McpServlet.java:183`), so every
  `session=` log line (§1) and the feedback rate limit work unchanged.
- `McpCallObservation.sessionKey()` (`McpCallObservation.java:56`) stops re-reading the header and
  takes the resolved key (constructor component), so both eras go through one value.
- **Prefix as era marker**: legacy keys stay bare UUIDs; modern keys start with `m-`. Datadog
  (`session=m-*`) and SQL (`session_key LIKE 'm-%'`) can split eras without a new column. A
  `protocol_version` column was considered and dropped: it needs an AD change + `export.database` for
  a value the prefix already gives.
- **One INFO line per new derived session** (not per request):
  `MCP modern session started: session=m-… client=claude-code/<version> protocol=2026-07-28 traceparent=yes|no`.
  It is the rollout's main evidence (§8) and answers option B's open data question.
- **Applies whatever the kill-switch flag says.** A modern-cached client keeps sending `_meta` after a
  rollback; reading its `clientInfo` costs nothing and keeps the telemetry intact. Only the protocol
  behaviour is switched off.

Impact by consumer:

| Consumer | Change |
|---|---|
| `McpUsageTelemetry` | + per-request `ClientInfo` ThreadLocal, + derived-session map; `openSession`/`clientInfo` unchanged for legacy |
| `McpUsageRow` | none in shape; Javadoc of `clientName`/`clientVersion`/`sessionKey` ("from the `initialize` handshake") updated |
| `McpUsageLogger` | none |
| WARN/ERROR lines (`session=`) | none — they read `sessionForLog()`, now non-`none` for modern traffic |
| `McpFeedbackTool` | none in code; modern clients stop sharing the anonymous bucket (fixes option A's bug before it exists) |
| Feedback INFO line | none — `session=` carries the `m-` key |
| `docs/mcp-usage-telemetry.md` §*The session key* | rewritten for both eras |

## 7. Changelog items not covered above — verified against the 2026-07-28 changelog

| Changelog item | Affects us? | Decision |
|---|---|---|
| Sessions / `Mcp-Session-Id` removed (major #1) | yes | §5.5, §6 |
| `initialize` removed, per-request `_meta` (major #2) | yes | §2–§4 |
| `server/discover` (major #3) | yes | §3.1 |
| GET stream + `resources/subscribe` replaced by `subscriptions/listen` (major #4) | we never offered either | **Left out**: not advertised, `404 -32601` |
| `ping`, `logging/setLevel`, `roots/list_changed` removed; `logLevel` in `_meta` (major #5) | `ping` only | §3.2; we emit no log notifications |
| Tasks moved to an extension (major #6) | no | Left out |
| MRTR / `InputRequiredResult` (major #7) | no server-to-client requests in our server | Left out; `resultType` is always `complete` |
| `resultType` required (major #8) | yes | §5.1 |
| SSE resumability removed (major #9) | we never stream | none |
| `extensions` in capabilities (minor #1) | no extensions | none |
| OpenTelemetry `_meta` keys (minor #2) | only as a telemetry signal | §4, §6.3 |
| Deterministic `tools/list` (minor #3) | yes | §5.3 |
| `Mcp-Method` / `Mcp-Name`, `x-mcp-header` (minor #4) | yes / no | §5.5; we declare no `x-mcp-header` |
| `CacheableResult` (minor #5) | yes — note it also lists `prompts/list` and `resources/templates/list`, which we do not serve | §5.2 |
| Resource not found `-32002` → `-32602` (minor #6) | yes | §5.4 |
| Auth: `iss` in authorization responses (RFC 9207), DCR `application_type`, issuer-bound credentials, DCR deprecated in favour of CIMD (minor #7–#9, deprecated #4) | **our OAuth2 authorization server**, not the MCP servlet | **Left out of this design**; needs its own review by the OAuth owner (was already "review, not blocking" in the analysis §5.1) |
| `inputSchema`/`outputSchema` loosened to any JSON Schema 2020-12 (minor #10) | relaxes what we may send | none |
| URL-elicitation completion removed (minor #11) | no | none |
| Error-code allocation policy (minor #12) | must not emit `-32020…-32099` except the 3 defined | we comply (§4) |
| Roots / Sampling / Logging deprecated | not used | none |
| `Origin` `403` (transport *Security*) | restated in 2026-07-28 | Stays **discarded** for the reasons of T4 (remote, Bearer-authenticated server) |

Also out: `resources/templates/list` stays unanswered (`404 -32601` modern, `200 -32601` legacy), as
decided 2026-10-06; the conformant answer would be `{resourceTemplates: [], ttlMs, cacheScope}` if
a client ever needs it.

## 8. Implementation breakdown, tests, rollout

### 8.1 Commits (one concern each, in this order)

`server/discover` is last because it is the go-live: before it, no client has a reason to send a
modern request, so commits 1–8 change nothing observable for production clients.

| # | Commit | Files |
|---|---|---|
| 1 | Classify MCP requests by protocol era (pure classifier, no wiring) | new `McpRequestEra` (or similar), `McpProtocolVersion` (legacy/modern/all sets) |
| 2 | Add the MCP modern-era kill switch and the strict-header switch | `GoFeatureFlags.FLAG_MCP_MODERN_ERA_DISABLED` (§8.5); `mcp.modern.strict` read with `GoRuntimeProperties.readBoolean` (default `false`) |
| 3 | Allow `Mcp-Method` and `Mcp-Name` in MCP CORS preflights | `McpServlet.setCorsHeaders` |
| 4 | Serve modern requests statelessly with spec error statuses | `McpServlet.doPost`/`dispatchMethod`, `ProtocolErrorAdapters` (error with `data` + status); no `Mcp-Session-Id` on modern; `ping` legacy-only |
| 5 | Decorate modern results with `resultType`, `serverInfo` and cache hints | `McpServlet` (post-dispatch decoration; shared serverInfo/capabilities builder) |
| 6 | Answer an unknown MCP resource as not found, without a stack trace | `McpResourceProvider`, new `McpResourceNotFoundException`, `McpServlet` catch |
| 7 | Read client identity from per-request `_meta` and derive modern sessions | `McpUsageTelemetry`, `McpCallObservation`, `McpServlet.recordToolCall`/`clientNameFor`, `McpUsageRow` Javadoc, `docs/mcp-usage-telemetry.md` |
| 8 | Pin the `tools/list` order and fix any unordered schema part | `ToolRegistry` (only if the test finds something), test |
| 9 | **Answer `server/discover`** (go-live) | `McpServlet.dispatchMethod`, `McpProtocolVersion` Javadoc, `docs/neo-headless.md` MCP section |

If real-client validation fails, revert commit 9 alone (or flip the switch); 1–8 are inert without it.
Shared-code rule: every branch above is on protocol structure (method, `_meta`, headers); nothing
names a spec, entity or table.

### 8.2 Test plan (`tester-go`, JUnit + Mockito, reuse first)

`make find-tests` (run 2026-10-09) points at these files; extend them:

| Unit | Extend | Cases |
|---|---|---|
| `McpServlet` | `McpServletTest.java` (57 tests; protocol cases at `:423-490`, unknown-method at `:709-734`) | full decision table §2.3 through `doPost` with mocked request headers — one test per row L1–L4, M1–M11, K1; asserted on HTTP status, error `code`/`data`, presence/absence of `resultType`, `_meta.serverInfo`, `ttlMs`/`cacheScope`, `Mcp-Session-Id` |
| `McpProtocolVersion` | `McpServletTest.java` (its only test home) | sets and ordering; `forRequest` unchanged for L2/L3 |
| new era classifier | **new** `McpRequestEraTest.java` — justified: new unit with no existing coverage | pure table tests incl. Base64 `Mcp-Name` decoding, sentinel-shaped plain value, non-string `M`, `_meta` not an object |
| `McpUsageTelemetry` | `McpUsageTenantQaTest.java` | derived key stable across calls, renewed after the gap, distinct per user/client name, `m-` prefix, bounded map; `clientInfo` from `_meta`; ThreadLocals cleared in `finally` |
| `McpCallObservation` | no test exists (`find-tests`: none) — cover through `McpServletTest` | row carries resolved key in both eras |
| `McpFeedbackTool` | `McpFeedbackToolTest.java` | two modern clients do not share a rate-limit bucket |
| `McpResourceProvider` | `McpResourceProviderTest.java` | unknown URI / inaccessible spec → `McpResourceNotFoundException`, same answer for both |
| `ToolRegistry` | `ToolRegistryGenerateToolsTest.java` | two builds render identical JSON |

Two existing tests change meaning and must be **rewritten, not deleted** (coverage gate):
`McpServletTest` `:709-734` (`server/discover` → `-32601`) becomes the K1 case (flag `true`) plus a new
M-row for the answered probe; `:477` (header `2026-07-28` served leniently with a WARN) becomes M10.

**Compatibility matrix** (spec versioning page), each as one end-to-end `doPost` sequence:

| Client | Sequence | Expected |
|---|---|---|
| Legacy 2024-11-05 | `initialize` (no header) → `tools/list` with `Mcp-Session-Id` | unchanged; session minted; rows keyed by UUID |
| Legacy 2025-11-25 | `initialize` → `tools/call` with header + session | unchanged; no `resultType` |
| Dual-era (Claude Code shape) | `server/discover` with `_meta` → `tools/list` → `tools/call` | `DiscoverResult`; modern results; no session header; rows with `client_name` from `_meta` and the same `m-` key |
| Dual-era, flag `true` | same | `200 -32601` on discover → `initialize` → legacy, as today |
| Modern-only | `tools/call` with `_meta` `2027-01-01` → retry with `2026-07-28` | `400 -32022` listing `2026-07-28` → success |
| Modern, bad headers | header ≠ `_meta`; `Mcp-Name` ≠ `params.name` | `400 -32020` in both strict modes |
| Modern client after rollback | `tools/call` with `_meta`, flag `true` | served (legacy shape), `client_name` still from `_meta` |

### 8.3 Rollout and verification with real clients

1. **Local** (`local-env` for `feature/ETP-5640`): scripted `curl` matrix of §2.3; then
   `claude mcp add --transport http` against the local URL. Expected: no
   `unsupported method 'server/discover'` WARN, one `MCP modern session started … client=claude-code`
   INFO, tool calls work, `ETGO_MCP_USAGE` rows with `client_name='claude-code'` and
   `session_key LIKE 'm-%'`. MCP Inspector (browser) exercises CORS with the new headers.
2. **Staging / experimental** (public URL, needed for the Claude.ai connector): connector add +
   a read and a write; Cursor — expected to stay legacy (`initialize`, UUID keys), proving the legacy
   path in the same build.
3. **Production**: watch for a week in Datadog —
   - `unsupported method 'server/discover'` drops to ~0 (it was ~80/day);
   - `MCP modern session started` appears for `claude-code` and `Anthropic/ClaudeAI`;
   - new WARNs from the lenient path (missing `Mcp-Name`, missing `clientCapabilities`) tell us
     whether `mcp.modern.strict=true` is ever safe;
   - `-32020` / `-32022` answers should be ~0; any burst means a client mismatch → rollback;
   - in `ETGO_MCP_USAGE`, share of rows with null `client_name` must not rise.

**Rollback**: set the flag `mcp-modern-era-disabled` to `true` for the environment.
- Where `etendo.go.configcat.sdkKey` is set: in the ConfigCat dashboard — live within one poll
  interval (60 s), **no restart**.
- Where it is not set: `PropertiesFeatureProvider` reads `etendo.go.flags.mcp-modern-era-disabled`
  (JVM / `Openbravo.properties`) or `ETGO_FLAG_MCP_MODERN_ERA_DISABLED` (environment), and the
  change takes effect only after a **Tomcat restart**.

Check which one an environment uses before relying on it for an incident. Effect: new probes get `-32601` and fall back to
`initialize`; clients that cached "modern" keep working because tool calls never needed
`initialize`, and the spec makes them treat a missing `resultType` as `complete`. Hard rollback:
revert commit 9 alone.

### 8.4 Risks

| Risk | Mitigation |
|---|---|
| A modern-mode bug breaks Claude Code / Claude.ai with **no fallback** (clients cache the era per origin and may persist it) | Commit 9 last and alone; staging with both clients before production; kill switch keeps cached-modern clients working |
| A client omits a newly required header → retry loop on `-32020` | Lenient default for *missing* headers (D2); WARN shows it |
| Derived sessions merge two concurrent conversations, or split across ECS tasks | Accepted for telemetry; offline SQL sessionisation stays authoritative (§6.2 E) |
| `ttlMs` delays a tool-catalog change by up to 5 min for modern clients | Shorter than the per-session caching legacy clients already do |
| `cacheScope: "public"` on discover shared across tokens | Content holds nothing user-specific; access is still enforced per request |
| Rollback needs a restart where `etendo.go.configcat.sdkKey` is not set | Confirm the SDK key is configured in each shared environment before go-live; local and CI use the property, where a restart is fine |

### 8.5 The kill-switch flag

- **Key `mcp-modern-era-disabled`**, constant `GoFeatureFlags.FLAG_MCP_MODERN_ERA_DISABLED`. The name
  says what `true` does. Every `GoFeatureFlags` flag resolves to `false` when it is missing, when
  ConfigCat is unreachable or when evaluation fails, so a flag that *enabled* the modern era would
  switch it off on any control-plane failure. Inverted, a failure leaves the default behaviour
  (dual-era) in place, and only a deliberate `true` rolls back.
- **Environment level only.** It is evaluated with an empty context (no targeting key, no
  attributes). Do not target it per account or tenant: clients cache the era per **origin**
  (versioning, *Backward Compatibility*), and one origin (`/sws/mcp`) serves every tenant, so a
  per-account answer would hand one cached decision to clients that the flag treats differently.
- **Backend-only.** Nothing in the browser reads it and it must never be added to the web client's
  `flag-keys.js` (same rule as `bp-portal-link`).
- **Restart or not.** Flipping it is live (one ConfigCat poll, 60 s) only where
  `etendo.go.configcat.sdkKey` is set (ConfigCat provider). Without an SDK key,
  `PropertiesFeatureProvider` reads `etendo.go.flags.mcp-modern-era-disabled` /
  `ETGO_FLAG_MCP_MODERN_ERA_DISABLED`, and flipping it needs a Tomcat restart.
- **Cost per request.** Evaluated once per POST. The context is a shared constant, so nothing is
  allocated per request beyond OpenFeature's own evaluation context. ConfigCat answers from its
  in-memory snapshot (auto-poll every 60 s); the properties provider reads a JVM/Openbravo property.
  Neither makes a network call on the request thread.
- **Telemetry ignores it.** `_meta.clientInfo` and the derived session key are used with the flag on
  or off (§6.3).
- **Retirement:** remove the flag and its branch once the modern era is stable in production.

## 9. Decisions on the open questions (user, 2026-10-09)

1. **Kill switch:** the `GoFeatureFlags` flag of §8.5 instead of an `Openbravo.properties` switch.
   Dual-era is on by default; the flag set to `true` rolls back. Without a restart only where
   `etendo.go.configcat.sdkKey` is set (ConfigCat provider). Without an SDK key,
   `PropertiesFeatureProvider` reads `etendo.go.flags.mcp-modern-era-disabled` /
   `ETGO_FLAG_MCP_MODERN_ERA_DISABLED`, and flipping it needs a Tomcat restart (§8.3, §8.5).
2. **Header strictness:** keep the lenient default for missing headers. `mcp.modern.strict` stays a
   plain property, default `false`.
3. **Telemetry grouping:** the derived session key (30 min idle gap) is accepted.

Provisional technical defaults (not questions, change freely): `ttlMs` values (§5.2), 30-min idle
gap, `m-` prefix, `supportedVersions` listing legacy versions too.
