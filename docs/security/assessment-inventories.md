# ETP-4569 assessment inventories

- **Date:** 2026-07-30
- **Jira:** ETP-4569 · Epic ETP-3504
- **Scope:** the P0 inventories required by the Client & Delivery Security Hardening PRD

## 1. Hosts, delivery and HSTS scope

| Environment | Public host | Distribution evidence | 2026-07-30 header state |
|---|---|---|---|
| Production | `go.etendo.cloud` | Workflow variable `CF_DISTRIBUTION_PRODUCTION` | Partial hardening; no CSP |
| Staging | `go.staging.etendo.cloud` | `E2XAO6Y99940X9` / `d1tf1daccdjiyj.cloudfront.net` | Partial hardening; no CSP |
| Experimental | `go.experimental.etendo.cloud` | `E2KW4F1IFBTHJY` / `dfdusgbqnsjdw.cloudfront.net` | No CSP or hardening headers |

Production and staging already return:

```text
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
```

This is observation, not approval. A complete DNS/AWS inventory was unavailable from the workspace.
Before ETP-4574 preserves or expands this directive, the delivery owner must enumerate every child
host affected by each exact hostname, prove HTTPS-only operation, identify certificate ownership and
record rollback ownership. The current directive must not be generalized to `etendo.cloud` without a
separate organization-wide inventory.

## 2. Session and credential flows

### Current deployed contract

1. Password or Google onboarding obtains a platform bearer token.
2. Environment selection obtains a second bearer token.
3. `app-shell-core` persists the auth session under `sf_auth_*` in `localStorage`.
4. Host code persists `sf_platform_token` and constructs `Authorization: Bearer`.
5. Logout clears browser state; the deployed bundle does not prove server invalidation.

Static inspection of the staging bundle found:

```text
sf_auth_token
sf_auth_user
sf_auth_client_id
sf_auth_client_name
sf_platform_token
sf_platform_auth_method
Authorization: Bearer ...
```

### Target contract reviewed in ADR-0001

Password login, Google SSO, restore, environment/role switch, refresh/replay, expiry and logout all use
a host-only `__Host-` opaque session cookie plus session-bound CSRF proof. ETP-4575 and ETP-4576 are
both `In Progress`; the target must not be described as deployed until integration evidence proves
cookie attributes, rotation, replay rejection, invalidation and legacy-key purge.

## 3. Attachment parent access and operation surface

| Operation | Input used today | Required authorization |
|---|---|---|
| List | Client-supplied table + record | READ on the resolved parent record |
| Upload | Client-supplied table + record | WRITE on the resolved parent record |
| Download one | Bare attachment ID | Derive parent from attachment; READ |
| Download all | Client-supplied table + record | READ on the resolved parent record |
| Delete | Bare attachment ID | Derive parent from attachment; WRITE |
| Update description | Bare attachment ID | Derive parent from attachment; WRITE |

The normative scenario matrix is in ADR-0003: owner, same-org/no-table-access, non-readable org,
cross-client, inactive/no-access record, nonexistent ID, mismatched supplied parent and legitimate
multi-org/admin access, across all six operations.

### Execution state

- **Code-level red evidence:** complete; every operation lacks the centralized parent authorization.
- **Black-box execution:** not run. It requires seeded records and accounts for two organizations in
  one client, a second client, a role without parent-table access and a legitimate multi-org role.
- **Safety constraint:** do not enumerate real attachment IDs or mutate customer data merely to fill
  the matrix. Provision isolated fixtures first.

## 4. Telemetry providers and envelope surface

| Provider | Enablement / endpoint | Operations and envelope risk | Current boundary |
|---|---|---|---|
| Sentry / GlitchTip | DSN from `VITE_SENTRY_DSN`; 10% tracing; environment by host | Exceptions, stack traces, breadcrumbs, URL, release, custom `extra` and `app` context | `sendDefaultPii` defaults false but is build-time overridable; no `beforeSend`/breadcrumb sanitizer |
| AWS RUM | Host-specific monitor/identity pool; `eu-west-3`; `https://dataplane.rum.eu-west-3.amazonaws.com`; 10% sessions | Performance, errors and HTTP telemetry; SDK-generated URL/request metadata; cookies allowed | No provider-independent final-envelope sanitizer or runtime kill-switch evidence |
| Mixpanel EU/configured host | Disabled unless enabled and token present; `apiHost` configurable | `track`, `page_view`, `identify`, people properties and group properties | Event-property sanitizer exists, but provider calls and final payload are not globally gated |

The functional host has useful allow/deny lists in `propertyPolicy.js` and `payload.js`. They do not
govern Sentry SDK-generated data, RUM HTTP telemetry, nested exception details, breadcrumbs, request
headers or the final serialized provider envelopes. The core package has no deny-by-default egress
gateway yet.

### Capture still required

ETP-4577/4578 must capture the actual serialized outbound request for each provider with seeded
tokens, emails, names, record values, query strings, headers and nested secrets. The P0 inventory
identifies the surfaces; it does not claim those payloads are safe.

## 5. Cache behavior

| Resource | Public observation / source evidence | Required state |
|---|---|---|
| HTML entry point | `no-cache, no-store, must-revalidate` on all three sampled hosts | Acceptable no-store behavior |
| SPA fallback/error-like path | Returns HTML `200` with the same no-store behavior | Document SPA fallback separately from real errors |
| Content-hashed JS | Staging: `public, max-age=31536000, immutable` | Correct |
| Unauthenticated NEO JSON error | `401 application/json`, no `Cache-Control` | `no-store` |
| Authenticated NEO JSON | `NeoServlet.writeResponse()` has no default | `no-store`, with explicit reviewed override only |
| `.well-known` discovery | Not sampled in this assessment | Define per resource |

The edge cache behavior for S3 content is partially correct. SEC-09b remains open because private
backend JSON does not inherit a safe default and the edge policy does not cover the sampled API
response.
