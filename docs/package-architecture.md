# Package Architecture Notes

## Purpose

This document records the package boundaries introduced during the `feature/ETP-3881` refactor so future changes do not collapse the same responsibilities back into oversized servlet or service classes.

## Current top-level packages

| Package | Responsibility | Change guidance |
|---|---|---|
| `com.etendoerp.go.common` | Cross-surface servlet helpers such as CORS, JWT extraction, response writing, and protocol error adapters. | Keep generic HTTP/auth helpers here only when they are reusable across OAuth2, MCP, and NEO surfaces. |
| `com.etendoerp.go.mcp` | MCP servlet, resource provider, tool registry, authorization checks, and tool routing. | MCP-specific protocol behavior belongs here; reusable access logic should delegate to `schemaforge.util`. |
| `com.etendoerp.go.oauth2` | OAuth2 servlet plus extracted authorization-code, authorization-page, and client-policy helpers. | Do not add more flow logic to `OAuth2Servlet` when it can live in a flow-specific support or policy class. |
| `com.etendoerp.go.onboarding` | Onboarding dataset import and sample-data packaging support. | Keep runtime dataset loading separate from NEO request routing. |
| `com.etendoerp.go.rest` | REST/JWT compatibility endpoints outside the NEO servlet. | Keep protocol adapters in `common` if they are shared. |
| `com.etendoerp.go.schemaforge.email` | Transactional email framework, provider adapter boundary, contract SPI, DTOs, safety controls, and shared contract base classes. | Keep this package implementation-agnostic. New document families must not add document-specific branches or methods to shared framework resolvers. |
| `com.etendoerp.go.schemaforge.email.contracts` | Built-in transactional email contract implementations and their DAL-backed record resolvers. | Add built-in contracts here through `EmailContractProvider`; each document family injects its own `EmailDocumentRecordResolver`. Future module-owned implementations may live in their own packages as long as they implement the SPI. |
| `com.etendoerp.go.schemaforge` | NEO Headless HTTP routing, CRUD/process/report/callout/defaults orchestration, selector execution, and window-specific handlers. | Generic orchestration stays here; reusable low-level helpers should move to `schemaforge.util`; selector metadata and policies must stay in selector subpackages. |
| `com.etendoerp.go.schemaforge.data` | Generated DAL entities for `ETGO_SF_*` tables. | Do not add hand-written business logic to generated entity classes. |
| `com.etendoerp.go.schemaforge.util` | Shared NEO helpers, especially access and action utility code. | Utilities here must not become window-specific behavior. |
| `com.etendoerp.go.schemaforge.webhooks` | Schema Forge metadata management webhooks. | Keep webhook DTO/parsing behavior local to webhook handlers unless reused by runtime NEO endpoints. |

## Selector package split

Selector code is intentionally split by responsibility. `NeoSelectorService` remains the request-facing facade, but metadata resolution and policy dispatch are no longer implemented directly inside the service.

| Package / class group | Responsibility | Representative classes |
|---|---|---|
| `com.etendoerp.go.schemaforge` selector execution | Request orchestration, selector query execution, response shaping, and legacy package-private execution helpers. | `NeoSelectorService`, `SelectorQueryBuilder`, `SelectorQueryExecutor`, `ComboReferenceSelectorExecutor`, `ListReferenceSelectorExecutor`, `SelectorAuxResolver`, `NeoSelectorExecutionHelper`, `SelectorResponseSupport` |
| `com.etendoerp.go.schemaforge.selector.meta` | AD/OBUISEL metadata discovery and normalized selector descriptors. This package should not perform query execution. | `SelectorMeta`, `RichFieldMeta`, `AuxFieldMeta`, `ObuiselFieldLists`, `SelectorContextResolver`, `SelectorDescriptorBuilder`, `SelectorDescriptorResolver` |
| `com.etendoerp.go.schemaforge.selector.policy` | Selector-specific policy SPI, policy registry, reference overrides, context-derived filters, virtual-column policies, and response enrichments. | `SelectorContextPolicy`, `SelectorEnrichmentPolicy`, `SelectorPolicyRegistry`, `NeoSelectorPolicy`, `ContextParamSelectorPolicy`, `ProductPriceSelectorPolicy`, `ReferenceOverrideSelectorPolicy`, `AddressVirtualSelectorPolicy` |

### Selector call flow

```text
NeoServlet
  -> NeoSelectorService
      -> SelectorDescriptorResolver / SelectorDescriptorBuilder (metadata)
      -> SelectorContextResolver (validated context params)
      -> NeoSelectorPolicy / SelectorPolicyRegistry
          -> SelectorContextPolicy implementations (extra HQL filters)
      -> SelectorQueryExecutor or reference-specific executor
      -> SelectorResponseSupport
      -> SelectorEnrichmentPolicy implementations (post-query response enrichment)
```

### Selector extension rule

When adding selector behavior:

1. Metadata shape changes go in `selector.meta`.
2. Entity-specific filters or enrichments go behind `SelectorContextPolicy` or `SelectorEnrichmentPolicy` in `selector.policy`.
3. Query execution changes stay in the execution classes under `schemaforge` unless they can be fully isolated without expanding public surface area.
4. Do not add window-specific branches to `NeoSelectorService`; use a policy or a `NeoHandler` where the behavior is tied to one window/entity.

The current execution classes remain in `com.etendoerp.go.schemaforge` to avoid widening package-private contracts into public APIs. Moving them should be a dedicated package-move commit, not mixed with behavior changes.

## OAuth2 scope split

`OAuth2Servlet` is still the HTTP entry point, but flow-specific and policy logic is now outside the servlet where possible:

| Class | Responsibility |
|---|---|
| `OAuth2Servlet` | HTTP routing, endpoint dispatch, response writing, and database persistence operations that still require servlet context. |
| `OAuth2AuthorizeSupport` | Authorization endpoint request parsing and authorization-page rendering support. |
| `OAuth2AuthorizationCodeSupport` | Authorization-code payload construction and JWT-backed user context extraction. |
| `OAuth2ClientPolicy` | Client policy validation that does not require servlet state, including redirect URI safety/registration and scope validation. |
| `OAuth2Utils` | Token, secret, and hashing helpers. |
| `OAuth2Filter` | Servlet filter integration for OAuth2-protected requests. |

New OAuth2 validation rules should prefer `OAuth2ClientPolicy` when they are pure policy decisions. Endpoint parsing/rendering should prefer a support class instead of increasing `OAuth2Servlet` method count or cognitive complexity.

### Authorize-grant token validity policy

The Authorization Code + PKCE flow (the grant used by the `/authorize` consent screen that guards `/sws/mcp`) lets the user choose how long the issued access token stays valid. The choice travels as a `validity_seconds` field and is enforced by a small policy in `OAuth2Servlet`.

**Wire contract — `POST /oauth2/authorize`.** In addition to the existing PKCE fields (`token`, `client_id`, `redirect_uri`, `code_challenge`, `state`, `scope`), the consent request may carry:

| Field | Type | Meaning |
|---|---|---|
| `validity_seconds` | integer (seconds) | Requested access-token lifetime. `0` = **no expiration**. Absent, blank, or non-numeric → treated as *absent* and falls back to the default. |

`OAuth2AuthorizeSupport` parses the value from both a JSON body (`optLong`) and a form parameter, using a negative marker (`VALIDITY_SECONDS_ABSENT`) when the field is missing or unparseable so that "absent" is distinguishable from an explicit `0`.

**Normalization / clamping — `OAuth2Servlet.normalizeValiditySeconds`.** The requested value is normalized once, at authorize time, before being embedded in the authorization-code payload:

| Constant (`OAuth2Servlet`) | Value | Rule |
|---|---|---|
| `VALIDITY_NO_EXPIRATION` | `0` | `0` is preserved as-is → token never expires. |
| `DEFAULT_AUTHORIZE_VALIDITY_SECONDS` | `86_400` (1 day) | Absent/blank/non-numeric (negative marker) → default. |
| `MAX_AUTHORIZE_VALIDITY_SECONDS` | `2_592_000` (30 days) | Requests above the max are clamped down (logged at INFO). |
| `MIN_AUTHORIZE_VALIDITY_SECONDS` | `300` (5 minutes) | Positive requests below the min are clamped up (logged at INFO). |

**Token issuance.** When the authorization code is exchanged, the normalized validity is applied:

- `EXPIRES_AT` is set to `now + validity_seconds` for a finite lifetime, or `NULL` when the validity is `0` (no expiration).
- The chosen validity is persisted on the token row (`VALIDITY_SECONDS`) so it can be reused later.
- The token response omits `expires_in` entirely when the token never expires; otherwise `expires_in` equals the granted validity in seconds.

**Refresh reuse.** A `refresh_token` grant does **not** reset the lifetime to the default: it reads the originally granted `VALIDITY_SECONDS` off the stored token and recomputes `EXPIRES_AT` from it (a `NULL`/unset stored value falls back to `DEFAULT_AUTHORIZE_VALIDITY_SECONDS`). A never-expiring token therefore stays never-expiring across refreshes, and `expires_in` is likewise omitted from the refresh response.

**Scope.** This policy applies only to the `authorization_code` (and its `refresh_token`) grant. The `client_credentials` (M2M) grant is out of scope and keeps its fixed `TOKEN_EXPIRY_SECONDS` (3600s) TTL.

**Persistence.** Both columns live on `ETGO_OAUTH2_TOKEN`:

| Column | Type | Notes |
|---|---|---|
| `EXPIRES_AT` | `TIMESTAMP`, nullable | `NULL` means the token never expires. |
| `VALIDITY_SECONDS` | `DECIMAL(20,0)`, nullable | Granted validity in seconds (`0` = no expiration); reused on refresh. |

New validity-related policy belongs with these constants and `normalizeValiditySeconds` in `OAuth2Servlet`, not scattered across the flow.

## Sonar and PR validation script

`run-sonar.sh` now defaults to PR-scoped validation:

- interactive runs without `--base-ref` prompt for the PR base commit/ref;
- non-interactive runs must pass `--base-ref <commit-or-ref>`;
- `--all-issues` produces full-project reports;
- `--allow-dirty` is an explicit exploratory escape hatch, not the canonical path;
- PR-only reports combine `git diff "$BASE_REF"...HEAD` with Sonar `inNewCodePeriod=true` so local reports match the Cloud PR view more closely.

Generated reports include both full-project and PR/new-code views:

- `sonar-issues.json`
- `sonar-issues-by-file.json`
- `sonar-issues-new-code.json`
- `sonar-issues-by-file-new-code.json`
- `sonar-issues-pr-only.json`
- `sonar-issues-by-file-pr-only.json`
- `sonar-quality-gate.json`

Canonical PR validation should run from the module root on a committed, clean tree:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./run-sonar.sh --base-ref origin/epic/ETP-3504
```
