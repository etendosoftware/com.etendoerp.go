# Feature Flags and the Paid Tenant Upgrade

Backend reference for the `tenant-upgrade` feature (ETP-4686, epic ETP-3504): how feature flags are
evaluated on the server, how the onboarding paywall gates a second tenant, and how a tenant's
commercial plan is recorded.

## 1. Feature flag stack

| Layer | Choice |
|-------|--------|
| Application API | **OpenFeature** — `dev.openfeature:sdk:1.20.1` (the real SDK, not a lookalike) |
| Control plane | **Local configuration**, via `PropertiesFeatureProvider` |
| Evaluation | In-process. No network call, no background thread, no polling. |

Application code never imports an OpenFeature type. It calls one entry point:

```java
boolean enabled = GoFeatureFlags.isEnabled(
    GoFeatureFlags.FLAG_TENANT_UPGRADE,
    FeatureFlagContext.forAccount(accountEmail));
```

The hosted control plane (Mixpanel Feature Flags with local evaluation and polling, per team plan
§5.6) is **deliberately deferred**. Standing up OpenFeature first means the migration later is a
provider swap rather than a rewrite of every call site.

### The swap point

`GoFeatureFlags.createProvider()` is the **only** place that decides which provider backs the API:

```java
private static FeatureProvider createProvider() {
  return new PropertiesFeatureProvider();
}
```

Moving to a hosted control plane means returning a different `FeatureProvider` from that one method,
plus adding its dependency in `build.gradle`. Nothing else in `GoFeatureFlags`, nothing else in the
package, and no caller anywhere changes.

Keep it that way. Provider-specific concerns — credentials, polling schedules, caches, retry and
staleness handling — belong **inside** the provider. Any provider bound here must honour the
guarantees below: never block the calling thread on I/O, never throw, and resolve to the caller's
default when it cannot answer.

The provider is bound to the OpenFeature **domain** `etendo-go` rather than the global default
provider, so this module cannot clobber a provider installed by another module.

### Configuration

A flag `my-flag` is read from `etendo.go.flags.my-flag`, resolved in priority order: JVM system
property, `Openbravo.properties`, environment variable `ETGO_FLAG_MY_FLAG` (uppercased, every
non-alphanumeric character replaced by `_`). See `com.etendoerp.go.common.GoRuntimeProperties`.

| Flag | Property | Environment variable | Default |
|------|----------|---------------------|---------|
| `tenant-upgrade` | `etendo.go.flags.tenant-upgrade` | `ETGO_FLAG_TENANT_UPGRADE` | absent ⇒ **`false`** |

Accepted affirmatives: `true`, `Y`, `yes`, `1`. Accepted negatives: `false`, `N`, `no`, `0`
(case-insensitive).

Because flags come from configuration, this provider serves **environment-level rollout, not
per-user targeting**. The evaluation context is accepted for API compatibility and passed through,
but does not affect the result. Per-user bucketing arrives with the hosted provider.

### Targeting key

The backend targets on the **account email**. The web client must use the same value or the two ends
will bucket the same user differently.

This needs care, because the only account identity the client persists (`sf_auth_user`) is written
by `buildEnvironmentSessionStorage` in `@etendosoftware/etendo-go-core` as
`env.adminUserName || env.adminUser` — the **ERP admin username of the selected environment**, not
the account email. Targeting on that would silently disagree with the backend.

So `GET /sws/go/environments` returns the account email at the top level (see §3), letting the client
target correctly from a call it already makes rather than a second round trip to `/me`. This is inert
while the properties provider ignores the evaluation context, but it must be honoured the moment a
targeting-aware provider is wired up.

### Failure behaviour — never block, never fail, default false

| Situation | Result |
|-----------|--------|
| Flag not configured | Code default (`false`) |
| Flag configured with a non-boolean value | Code default, with a `PARSE_ERROR` on the evaluation so a typo is visible rather than silently reading as "disabled" |
| Provider registration failed | No provider bound; every flag resolves to its default |
| Unknown flag key, type mismatch, unexpected error | Default |

The code default for `tenant-upgrade` is **`false`**, so with no configuration at all the product
behaves exactly as it did before this feature.

Only boolean flags are backed by configuration. The other OpenFeature types return the caller's
default with a `TYPE_MISMATCH` rather than pretending to resolve, so a future typed flag fails
visibly instead of silently reading as an empty string or zero.

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

The `GET /sws/go/environments` response gained two additive fields:

```json
{
  "environments": [
    {
      "clientId": "…", "clientName": "…", "orgId": "…", "orgName": "…",
      "adminUserId": "…", "adminUser": "…", "adminUserName": "…",
      "plan": "free"
    }
  ],
  "accountEmail": "user@example.com"
}
```

- **`plan`** (per environment) is `"free"` or `"productive"`, for badging each tenant in the picker.
- **`accountEmail`** (top level) is the flag-targeting identity described in §1.

Both are backward compatible; clients that ignore them are unaffected.

> **Open item — the plan badge is not yet rendered.** The environment picker (`EnvSelectStep.jsx`)
> lives in `@etendosoftware/etendo-go-core`, not in the functional repo, so consuming `plan` needs a
> change and version bump on the core side. The backend field is stable and shipped; the UI side is
> waiting on that pickup.

## 4. When the hosted control plane lands

Checklist for the follow-up that replaces local configuration with Mixpanel Feature Flags:

1. Add `com.mixpanel:mixpanel-java-openfeature` and `com.mixpanel:mixpanel-java` to
   `build.gradle`. Pin `mixpanel-java` at **1.9.0 or later** — the `exposureExecutor` builder
   option, which keeps Mixpanel's per-evaluation exposure event off the request thread, does not
   exist in the 1.8.0 the provider declares. Without it every flag check does a synchronous HTTP
   POST.
2. Return the Mixpanel provider from `GoFeatureFlags.createProvider()`. Nothing else changes.
3. Configure it for **local** evaluation with polling. Run the initial definitions fetch on a daemon
   thread — it is a blocking HTTP call, and doing it inline would make the first flag evaluation in
   a JVM wait on Mixpanel.
4. Watch for an `org.json` classpath collision. `mixpanel-java` parses definitions with
   `org.json:json`, while `WebContent/WEB-INF/lib` already ships a legacy Eclipse-repackaged
   `org.json-1.0.0.v201011060100.jar`. Both provide the same package and the winner depends on
   classloader ordering. It degrades safely — the fetch and parse are inside catch-all handlers, so
   if the legacy classes win, definitions never become ready and every flag reads `false` — but that
   looks identical to the flag simply being off, so check it first if flags never turn on.

## 5. Source map

| Concern | Class |
|---------|-------|
| Flag entry point, provider swap point, failure policy | `com.etendoerp.go.featureflags.GoFeatureFlags` |
| Local configuration-backed provider | `com.etendoerp.go.featureflags.PropertiesFeatureProvider` |
| Vendor-neutral targeting context | `com.etendoerp.go.featureflags.FeatureFlagContext` |
| Shared property resolution (system → Openbravo → env) | `com.etendoerp.go.common.GoRuntimeProperties` |
| Paywall decision | `com.etendoerp.go.payment.TenantPaywallService` |
| Payment token validation (mock) | `com.etendoerp.go.payment.MockPaymentService` |
| Plan read/write | `com.etendoerp.go.payment.TenantPlanService` |
| Gate wiring, 402 response, plan marking | `com.etendoerp.go.rest.EtendoGoJwtServlet` |
| Ownership count, `plan` in `/environments` | `com.etendoerp.go.rest.EtendoGoJwtDalHelper` |
