# Feature Flags and the Paid Tenant Upgrade

Backend reference for the `tenant-upgrade` feature (ETP-4686, epic ETP-3504): how feature flags are
evaluated on the server, how the onboarding paywall gates a second tenant, and how a tenant's
commercial plan is recorded.

## 1. Feature flag stack

| Layer | Choice |
|-------|--------|
| Application API | **OpenFeature** — `dev.openfeature:sdk:1.20.1` |
| Control plane | **Mixpanel Feature Flags**, via the official `com.mixpanel:mixpanel-java-openfeature:0.1.1` provider |
| Evaluation | **Local**, with background polling of flag definitions |

Application code never imports an OpenFeature or Mixpanel type. It calls one entry point:

```java
boolean enabled = GoFeatureFlags.isEnabled(
    GoFeatureFlags.FLAG_TENANT_UPGRADE,
    FeatureFlagContext.forAccount(accountEmail));
```

Swapping the control plane is therefore a change confined to `GoFeatureFlags`.

`mixpanel-java` is pinned to `1.9.0`, above the `1.8.0` the provider declares, because the exposure
executor described below only exists from 1.9.0.

### Local evaluation and polling

The provider is installed in local-evaluation mode. Flag definitions are fetched from Mixpanel once
at startup and then refreshed by a daemon poller; every evaluation is an in-memory rule match. **No
request ever makes a network call to decide a flag.**

The initial fetch is a blocking HTTP call, so `GoFeatureFlags` runs it — and the polling schedule it
installs — on a daemon thread (`etendo-go-flags-init`). The first flag evaluation in a JVM therefore
never waits on Mixpanel.

Mixpanel also records an *exposure* event per evaluation. Left alone the provider posts that event
synchronously on the evaluating thread, which would add HTTP latency to every flag check.
`GoFeatureFlags` supplies a bounded, daemon-backed `exposureExecutor` (capacity 1000, discard on
overflow) so exposure never blocks or fails a request. Exposure is analytics, not correctness.

### Failure behaviour — never block, never fail, default false

| Situation | Result |
|-----------|--------|
| Token missing, or flags disabled | Provider never installed; every flag resolves to its code default |
| Provider unreachable at startup | Definitions not ready → default returned (`PROVIDER_NOT_READY`); the poller keeps retrying |
| A later poll fails | Last-known definitions are retained and keep serving; definitions are only ever replaced by a **successful** fetch |
| Unknown flag key, type mismatch, unexpected error | Default |

The code default for `tenant-upgrade` is **`false`**, so with no configuration at all the product
behaves exactly as it did before this feature.

### Configuration

The project token and API host are shared with backend Mixpanel telemetry — flags and telemetry
target the same Mixpanel project. Each setting resolves from, in priority order: JVM system
property, `Openbravo.properties`, environment variable (see
`com.etendoerp.go.common.GoRuntimeProperties`).

| Property | Environment variable | Default | Meaning |
|----------|---------------------|---------|---------|
| `etendo.go.mixpanel.token` | `ETGO_MIXPANEL_TOKEN` | — | Mixpanel project token. **Absent ⇒ flags disabled.** |
| `etendo.go.mixpanel.apiHost` | `ETGO_MIXPANEL_API_HOST` | `api-eu.mixpanel.com` | Mixpanel host. A full `https://…` URL is accepted and normalized to a bare host. |
| `etendo.go.featureflags.enabled` | `ETGO_FEATUREFLAGS_ENABLED` | `true` | Master switch for backend flag evaluation. |
| `etendo.go.featureflags.pollingIntervalSeconds` | `ETGO_FEATUREFLAGS_POLLING_INTERVAL_SECONDS` | `60` | Definition refresh cadence. |
| `etendo.go.featureflags.requestTimeoutSeconds` | `ETGO_FEATUREFLAGS_REQUEST_TIMEOUT_SECONDS` | `10` | HTTP timeout for the definitions fetch. |

The provider is bound to the OpenFeature **domain** `etendo-go` rather than the global default
provider, so this module cannot clobber a provider installed by another module.

### Backend evaluation is authoritative

The web client evaluates the same flags for presentation only — which pages and buttons to show. Any
decision about permissions, data or processes is made server-side. The paywall below holds
regardless of what the client believes.

## 2. The onboarding paywall

`POST /sws/go/onboarding` gains a payment gate.

### Contract

| | |
|---|---|
| Payload field | `paymentToken` (string, optional) |
| Refusal status | **HTTP 402** |
| Refusal body | `{"error": "payment_required", "message": "…"}` |
| Flag key | `tenant-upgrade` |

The gate runs in `EtendoGoJwtServlet.handleOnboarding`, **after** the token, payload and currency are
validated but **before** the NDJSON stream opens and before any provisioning. A refused request
therefore leaves no half-created tenant behind and can still answer with a plain JSON error rather
than a stream.

### Decision rules

`com.etendoerp.go.payment.TenantPaywallService.decide(...)` is a standalone, directly testable unit —
this is the authoritative permission check, so it is deliberately not inline servlet code.

1. **Flag off → allowed.** Pre-feature behaviour, byte for byte: no token is read, no ownership
   lookup runs, no payment is ever demanded.
2. **Account owns no tenant → allowed.** A first tenant is always free.
3. **Request targets a tenant the account already owns → allowed.** That is the resume path
   `validateExistingClient` handles downstream (a partially provisioned environment being
   reconciled), not a new tenant, so it is not charged again.
4. **Otherwise → the payment token decides:** approved ⇒ allowed; absent ⇒ `PAYMENT_REQUIRED`;
   rejected ⇒ `PAYMENT_DECLINED`. Both refusals answer 402 with `error: payment_required`, differing
   only in `message`.

Ownership is counted with `EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail`, which reuses the
same username-match rule as `GET /sws/go/environments`.

### The mock payment provider

`com.etendoerp.go.payment.MockPaymentService` is the **only** mock in this flow. No money moves and
no external call is made; the token's shape decides:

| Token | Outcome |
|-------|---------|
| `mock-paid-<hex>` (e.g. `mock-paid-a1b2c3`) | Approved |
| `mock-declined`, or any other value | Declined |
| absent / blank | Missing |

The web client simulates a decline with card `4000000000000002` and then sends `mock-declined`; the
backend only ever validates the token's shape. The flag evaluation, the paywall gate and the plan
marker are all real, so replacing this class with a gateway client is the single change needed to
take real payments.

## 3. The plan marker

A tenant created through the paid flow is marked **productive**; every other tenant is **free**.

Storage is an `AD_Preference` row with attribute **`ETGO_TenantPlan`**, made visible at the tenant's
own client (`com.etendoerp.go.payment.TenantPlanService`). This reuses existing AD metadata — no new
table, column or window — following the same mechanism the module already uses for navigator
favorites and saved filters. The row is created at runtime as ordinary data, so **no
`export.database` is required**.

Absence of the preference means `free`. Every tenant provisioned before this feature, and every first
(unpaid) tenant, reads back as free without a migration.

The marker is written inside the onboarding transaction, right after the admin context is resolved,
so it commits with the tenant or rolls back with it — a tenant is never left provisioned but
unmarked. Only a request that actually had to clear the paywall counts as paid: a first tenant or a
resume stays free even if the payload carried a token.

### Exposure in `/environments`

Each item in the `GET /sws/go/environments` response carries an additional field:

```json
{
  "clientId": "…", "clientName": "…", "orgId": "…", "orgName": "…",
  "adminUserId": "…", "adminUser": "…", "adminUserName": "…",
  "plan": "free"
}
```

`plan` is `"free"` or `"productive"`. The addition is backward compatible — clients that ignore the
field are unaffected — and it lets the environment picker badge each tenant.

## 4. Operational note — `org.json` on the classpath

`mixpanel-java` parses flag definitions with `org.json:json`, while `WebContent/WEB-INF/lib` already
ships a legacy Eclipse-repackaged `org.json-1.0.0.v201011060100.jar`. Two jars therefore provide the
same `org.json` package and the winner depends on classloader ordering.

This is contained rather than dangerous: the Mixpanel definition fetch and parse are wrapped in
catch-all handlers, so if the legacy classes win, definitions simply never become ready and every
flag resolves to its default (`false`) — the same as the flag being off. If flags read as
permanently disabled in a deployment where the token is set, this classpath collision is the first
thing to check.

## 5. Source map

| Concern | Class |
|---------|-------|
| Flag entry point, provider install, failure policy | `com.etendoerp.go.featureflags.GoFeatureFlags` |
| Flag configuration | `com.etendoerp.go.featureflags.GoFeatureFlagsConfig` |
| Vendor-neutral targeting context | `com.etendoerp.go.featureflags.FeatureFlagContext` |
| Shared property resolution (system → Openbravo → env) | `com.etendoerp.go.common.GoRuntimeProperties` |
| Paywall decision | `com.etendoerp.go.payment.TenantPaywallService` |
| Payment token validation (mock) | `com.etendoerp.go.payment.MockPaymentService` |
| Plan read/write | `com.etendoerp.go.payment.TenantPlanService` |
| Gate wiring, 402 response, plan marking | `com.etendoerp.go.rest.EtendoGoJwtServlet` |
| Ownership count, `plan` in `/environments` | `com.etendoerp.go.rest.EtendoGoJwtDalHelper` |
