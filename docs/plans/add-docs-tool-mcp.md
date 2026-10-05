# Plan: Add a `docs` MCP tool (Context7 docs search)

## Goal
Add a new MCP tool named `docs` to the Etendo Go MCP server that performs a
documentation lookup against Context7 for the `etendosoftware/etendo-go-docs`
library, filtered by a topic/term. Functionally equivalent to:

```bash
curl -s "https://context7.com/api/v1/etendosoftware/etendo-go-docs?topic=<term>&type=txt&tokens=<n>"
```

The tool lets an MCP client (e.g. an AI agent) ask "search the Etendo Go docs for
*finance*" and get back the relevant documentation text inline.

## Context (how the MCP server is built)
All paths under `modules/com.etendoerp.go/`.

- **Transport / server:** `src/com/etendoerp/go/mcp/McpServlet.java` — JSON-RPC 2.0 over HTTP (`/sws/mcp`), OAuth2 Bearer auth via `OAuth2Filter`.
- **Tool catalog:** `src/com/etendoerp/go/mcp/ToolRegistry.java` — `generateTools(Set<String> scopes)` builds the `tools/list` response. Tools are generated dynamically; static tools (like `neo_discover`) are added unconditionally inside this method. Schema is built with helper methods `buildObjectSchema(props, required)`, `stringProp(...)`, `intProp(...)`, `enumProp(...)`.
- **Tool dispatch:** `src/com/etendoerp/go/mcp/McpToolRouter.java` — `route(toolName, arguments, scopes)`: a `switch` on `toolName`. Returns an MCP result via `wrapAsTextContent(String)` / `wrapAsErrorContent(String)`.
- **Spec name resolution:** `ToolRegistry.resolveSpecName(toolName, args)` — called for every tool in `route()` before `authorizeSpecAccess(specName)`. `authorizeSpecAccess` is a no-op when the spec name is blank (`McpToolRouter.java:843-846`).
- **Scope/RBAC:** `src/com/etendoerp/go/mcp/McpAuthorizationService.java` — `authorizeToolCall(toolName, scopes)` maps each tool to a required OAuth2 scope (`requiredScopeFor`). Unknown tool names currently fall through to `neo:process`.
- **CRUD detection:** `ToolRegistry.isCrudTool(toolName)` — drives `resolveSpecName` (CRUD tools take the spec from args, not from the tool name).
- **Tests:** `src-test/src/com/etendoerp/go/mcp/` — JUnit 5 + Mockito with `MockedStatic`. Relevant: `ToolRegistryGenerateToolsTest`, `McpToolRouterTest`, `McpToolRouterRouteTest`, `McpToolDefinitionTest`.
- **No existing outbound HTTP.** The `docs` tool would be the first tool that calls an external service. Use the JDK `java.net.http.HttpClient` (Java 11+, no new dependency).

> ⚠️ Architectural note: the MCP module today is purely a server in front of NEO
> Headless and makes **no external calls**. `docs` introduces an outbound HTTP
> dependency. Keep all the egress logic in one isolated helper class so the
> network concern doesn't leak into the router.

## Design decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Tool name | `docs` | Matches user request; short, discoverable. |
| Required scope | `neo:read` (read-only) | Docs lookup is non-mutating. Add to `requiredScopeFor` so it doesn't default to `neo:process`. |
| Always available? | Add when `permissions.canRead` (like `neo_discover`), independent of any spec | Not tied to a DB spec. |
| Spec resolution | Treat as having no spec | Add `docs` to `isCrudTool()` **or** special-case it so `resolveSpecName` returns `null` → `authorizeSpecAccess(null)` no-ops. Prefer an explicit `isStaticTool()` check to avoid overloading `isCrudTool` semantics. |
| HTTP client | `java.net.http.HttpClient` | JDK built-in, no new dependency. |
| Base URL / library | Constant, overridable by preference | Default `https://context7.com/api/v1/etendosoftware/etendo-go-docs`. |
| Auth to Context7 | Optional `Authorization: Bearer` if an API key preference is set; works without it (low rate limit is acceptable) | User confirmed no auth needed initially. |
| Inputs | `topic` (required string), `tokens` (optional int, default 5000), `type` (optional, default `txt`) | Mirrors the curl. |

## Inputs (JSON Schema)
```json
{
  "type": "object",
  "properties": {
    "topic":  { "type": "string",  "description": "Term/topic to search in the Etendo Go docs (e.g. 'finance', 'payment')." },
    "tokens": { "type": "integer", "description": "Approx. max size of the returned docs (default 5000)." },
    "type":   { "type": "string",  "description": "Response format: 'txt' (default) or 'json'." }
  },
  "required": ["topic"]
}
```

## Implementation steps

### 1. New helper class — `Context7DocsClient.java`
`src/com/etendoerp/go/mcp/Context7DocsClient.java`

- Constants:
  - `DEFAULT_BASE_URL = "https://context7.com/api/v1/etendosoftware/etendo-go-docs"`
  - `DEFAULT_TOKENS = 5000`, `DEFAULT_TYPE = "txt"`
- Method `String fetchDocs(String topic, int tokens, String type)`:
  1. URL-encode `topic` (`URLEncoder.encode(topic, UTF_8)`).
  2. Build URI: `<base>?topic=<topic>&type=<type>&tokens=<tokens>`.
  3. `HttpClient.newHttpClient()` with a connect timeout (e.g. 10s) and a request timeout (e.g. 30s).
  4. Optional: if a `Context7` API key is configured (see step 4), add `Authorization: Bearer <key>`.
  5. `GET`; on HTTP 2xx return body; otherwise throw `McpToolException` with status + truncated body.
  6. Validate `topic` non-blank; clamp `tokens` to a sane range (e.g. 500–20000).
- Keep it dependency-free and easily mockable (inject the `HttpClient`, or wrap the call in a protected method, so tests can stub the network).

### 2. Register the tool — `ToolRegistry.java`
- In `generateTools(...)`, after the `neo_discover` block:
  ```java
  if (permissions.canRead) {
    tools.add(buildDocsTool());
  }
  ```
- Add `buildDocsTool()` using `buildObjectSchema` + `stringProp`/`intProp` per the schema above (model it on `buildListTool`).
- Add `docs` to whatever predicate makes `resolveSpecName` return `null` for it (preferably a new `isStaticTool(toolName)` helper covering `neo_discover` + `docs`, or extend `isCrudTool` if you accept the semantic overlap). Confirm `resolveSpecName("docs", args)` does **not** fall through to `snakeToKebab("docs")` (which would make `authorizeSpecAccess` try to find a non-existent spec and throw).

### 3. Dispatch the tool — `McpToolRouter.java`
- Add a `case "docs":` in the `route(...)` switch **before** the `default` (process) branch:
  ```java
  case "docs":
    return handleDocs(arguments);
  ```
- Implement `handleDocs(JSONObject arguments)`:
  - Read `topic` (required → `wrapAsErrorContent` if blank), `tokens` (default 5000), `type` (default `txt`).
  - Call `new Context7DocsClient().fetchDocs(topic, tokens, type)`.
  - Return `wrapAsTextContent(body)`; on exception return `wrapAsErrorContent(...)`.

### 4. Authorization — `McpAuthorizationService.java`
- In `requiredScopeFor`, add:
  ```java
  case "docs":
    return SCOPE_READ;
  ```
  (Otherwise it defaults to `neo:process`, which is wrong for a read-only docs lookup.)

### 5. (Optional) Config / API key
- If you want the Context7 API key configurable, read it from an Etendo Preference (e.g. `ETGO_Context7_ApiKey`) or a system property. Resolve it inside `Context7DocsClient`; absence = no `Authorization` header. Document the preference name in `docs/neo-headless-guide.md`.
- Optionally make the base URL a preference too, for testing against a mock.

### 6. Tests (`src-test/src/com/etendoerp/go/mcp/`)
- **`ToolRegistryGenerateToolsTest`**: assert `docs` is present when scopes include `neo:read`, and absent when read is not granted.
- **`McpToolDefinitionTest` / new `Context7DocsClientTest`**: unit-test URL construction, `topic` encoding, `tokens` clamping, and error handling for non-2xx (inject a mocked `HttpClient`/`HttpResponse`).
- **`McpToolRouterRouteTest`**: route `docs` with a stubbed `Context7DocsClient` and assert the result is wrapped via `wrapAsTextContent`; assert blank `topic` → error content.
- **`McpAuthorizationServiceTest`** (if it exists, else add a case): `docs` requires `neo:read`; call without read scope → `OBSecurityException`.

### 7. Docs
- Add a short section to `docs/neo-headless-guide.md` (or the MCP-specific guide) describing the `docs` tool, its inputs, the underlying Context7 endpoint, and the optional API-key preference.

## Files touched
| File | Change |
|------|--------|
| `src/com/etendoerp/go/mcp/Context7DocsClient.java` | **new** — outbound HTTP to Context7 |
| `src/com/etendoerp/go/mcp/ToolRegistry.java` | register `docs`, `buildDocsTool()`, static-tool handling in `resolveSpecName` |
| `src/com/etendoerp/go/mcp/McpToolRouter.java` | `case "docs"` + `handleDocs()` |
| `src/com/etendoerp/go/mcp/McpAuthorizationService.java` | `docs` → `neo:read` |
| `src-test/.../mcp/*` | unit/route/registry/auth tests |
| `docs/neo-headless-guide.md` | document the tool |

## Risks / edge cases
- **First outbound call from the module** — set timeouts; never block a request thread indefinitely. Consider whether Context7 latency/availability should degrade gracefully (return an error content block, never a 500 from the servlet).
- **URL injection** — always URL-encode `topic`.
- **Response size** — clamp `tokens`; very large bodies bloat the MCP response. Default 5000.
- **Egress policy** — confirm the Etendo server is allowed to reach `context7.com` (firewall/proxy). If a corporate proxy is required, `HttpClient.Builder.proxy(...)` must honor it.
- **Scope default trap** — forgetting step 4 silently requires `neo:process` for a read-only tool.
- **`resolveSpecName` trap** — forgetting to mark `docs` as static makes `authorizeSpecAccess("docs")` throw "Spec not found".

## Acceptance
- `tools/list` includes `docs` for a `neo:read` (or `neo:*`) session.
- Calling `docs` with `{"topic":"finance"}` returns the Context7 docs text as MCP text content.
- Calling without `topic` returns an error content block (not a server crash).
- All new/updated unit tests pass; no live network needed in tests (HTTP client mocked).
