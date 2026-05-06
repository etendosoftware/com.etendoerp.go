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
