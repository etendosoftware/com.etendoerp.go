# Feature Flags and the Paid Tenant Upgrade

Backend reference for the paid productive-environment capability (ETP-4686, ETP-4966, epic
ETP-3504): how feature flags are evaluated on the server, how the onboarding paywall gates an
additional environment, and how an environment's commercial plan is recorded.

> **The `tenant-upgrade` flag was retired in ETP-4966.** The capability is permanent and cannot be
> switched off. It was gated by a key the web client resolved through ConfigCat and the backend
> through local properties, and the backend's copy was unset in *every* deployed task definition —
> so the browser offered a Stripe checkout the backend did not believe in. Accounts were charged and
> the paywall short-circuited to `ALLOWED` without ever reading the payment, which shipped paying
> customers a Demo environment with no error anywhere. §1 documents the flag stack that remains for
> future flags; §2 onwards documents the paywall, which no longer consults it.

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

No backend flag is declared today: `tenant-upgrade` was the only one and it retired with ETP-4966.
The stack stays as the entry point for the next one — declare its key as a constant on
`GoFeatureFlags` and add its row here.

| Flag | Property | Environment variable | Default |
|------|----------|---------------------|---------|
| *(none)* | `etendo.go.flags.<key>` | `ETGO_FLAG_<KEY>` | absent ⇒ **`false`** |

**Lesson from the retired flag, worth honouring for the next one:** a flag whose two ends read from
different control planes has no single truth. Either both ends resolve the same key from the same
control plane, or the backend is the only evaluator and the browser asks it. An unset backend key is
indistinguishable from a disabled feature — which is how a charged account got a free environment.

Accepted affirmatives: `true`, `Y`, `yes`, `1`. Accepted negatives: `false`, `N`, `no`, `0`
(case-insensitive).

Because flags come from configuration, this provider serves **environment-level rollout, not
per-user targeting**. The evaluation context is accepted for API compatibility and passed through,
but does not affect the result. Per-user bucketing arrives with the hosted provider.

### Targeting key — still OPEN for any future flag, no longer blocking this capability

Retiring `tenant-upgrade` removed this divergence's only live consumer: the paid-environment
capability no longer evaluates a flag, so the two ends can no longer disagree about it. Everything
below therefore describes a precondition for the **next** targeting-aware flag, not an open defect in
this feature. It is kept because the trap is real and undiscovered-by-default: this whole section
existed, and was accurate, while the feature it described silently failed for exactly the reason it
warned about.

The backend targets on the **account email**. A web client must use the same value or the two ends
will bucket the same user differently. **This is not resolved on the client side**, deliberately.

The client does not persist the account email. The only account identity it stores (`sf_auth_user`)
is written by `buildEnvironmentSessionStorage` in `@etendosoftware/etendo-go-core` as
`env.adminUserName || env.adminUser` — the **ERP admin username of the selected environment**.
Targeting on that would silently disagree with the backend.

The backend now returns the account email at the top level of `GET /sws/go/environments` (see §3).
That is necessary but **not sufficient**, for two reasons found during integration:

1. **The core helper discards it.** `fetchEnvironments` in `@etendosoftware/etendo-go-core` ends with
   `return data.environments || []`, so anything outside that array never reaches the caller. Reading
   `accountEmail` requires a direct `fetch` rather than the helper — which is the default path.
2. **Scope mismatch.** The OpenFeature evaluation context has to be set **app-wide at bootstrap**,
   before any gated UI renders — a flag that decides whether an entry point is shown at all is
   evaluated long before anyone reaches that page. Setting the context from that page would
   make a user who visits it bucket on email and a user who never does bucket on username: the same
   user bucketing differently depending on navigation history. That is worse than being uniformly
   wrong, because it disappears into aggregates instead of showing up as a clean skew.
   Compounding it, the call needs `sf_platform_token`, which is not in `ENVIRONMENT_SESSION_KEYS` and
   is not present in every app-shell session, so even a bootstrap-time fetch would yield email for
   some sessions and username for others.

**Resolution path (ETP-4693), backend half shipped.** `GET /sws/neo/session` now returns
`accountId` and `accountEmail` for the authenticated user, resolved server-side (see §5). That
endpoint is served by this module and consumed by app-shell code directly, not through the core npm
package, so it needs no core change and no version bump — which is what unblocked this after the
`/environments` route stalled on the core helper dropping top-level fields.

**This is not closed until the web client consumes them.** The backend now exposes the identity; the
frontend half is the remaining scope. Until it lands, the two ends still bucket differently and no
targeting-aware provider should be installed. Full client-side reasoning is in `docs/feature-flags.md`
in the functional repo.

### Failure behaviour — never block, never fail, default false

| Situation | Result |
|-----------|--------|
| Flag not configured | Code default (`false`) |
| Flag configured with a non-boolean value | Code default, with a `PARSE_ERROR` on the evaluation so a typo is visible rather than silently reading as "disabled" |
| Provider registration failed | No provider bound; every flag resolves to its default |
| Unknown flag key, type mismatch, unexpected error | Default |

Every flag's code default is **`false`**, so with no configuration at all a gated feature stays
hidden. Note what that guarantee does *not* cover, and what ETP-4966 paid for: defaulting to `false`
protects unfinished work from leaking, but it also makes "nobody configured this" and "this is
deliberately off" the same observable state. A flag that gates something the user can already pay
for must therefore never be the only thing standing between a payment and its effect.

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
| Feature flag | **none** — unconditional since ETP-4966 |

The gate runs in `EtendoGoJwtServlet.handleOnboarding`, **after** the token, payload and currency are
validated but **before** the NDJSON stream opens and before any provisioning. A refused request
therefore leaves no half-created tenant behind and can still answer with a plain JSON error rather
than a stream.

### Decision rules

`com.etendoerp.go.payment.TenantPaywallService.evaluate(...)` is a standalone, directly testable unit
— this is the authoritative permission check, so it is deliberately not inline servlet code. It
returns an `Outcome` carrying **two independent answers**: the `Decision` (may this request
provision) and `isProductive()` (does what it provisions become productive).

1. **Converting an existing environment (`upgradeAction=convert-demo`) → never free.** It skips both
   free paths below and falls straight through to the payment check: a conversion is a paid state
   transition, and without this guard it looks exactly like an ordinary resume of an environment the
   account already owns.
2. **Account owns no environment → allowed.** A first environment is always free.
3. **Request targets an environment the account already owns → allowed.** That is the resume path
   `validateExistingClient` handles downstream (a partially provisioned environment being
   reconciled), not a new environment, so it is not charged again.
4. **Otherwise → the payment decides:** a `CheckoutPaymentRegistry`-confirmed payment ⇒ allowed;
   token absent ⇒ `PAYMENT_REQUIRED`; token present but unconfirmed ⇒ `PAYMENT_DECLINED`. Both
   refusals answer 402 with `error: payment_required`, differing only in `message`.

Ownership is counted with `EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail`, which reuses the
same username-match rule as `GET /sws/go/environments`.

### The plan is derived from the payment, not from the decision

`isProductive()` is `true` when — and only when — the request was not refused **and** the payment
token correlates to a webhook-confirmed payment for this account and this environment name. Notably
it does **not** depend on ownership or on which rule allowed the request: an account that pays while
owning nothing still gets a productive environment.

This is the ETP-4966 fix, and the ordering matters. The previous code inferred payment from the
decision — "allowed and owns an environment and is not a plain resume" — which is only equivalent to
"paid" while the paywall is actually running. With the flag unset the paywall returned `ALLOWED`
immediately, that inference silently evaluated to `false`, and a Stripe-charged account received a
Demo environment. Any future change that reintroduces a shortcut before the payment lookup will
reproduce the same class of bug, so the payment is now read **first**, unconditionally.

### The payment provider — real Stripe, real money

`MockPaymentService` is **gone**. Payments run through Stripe hosted checkout
(`HostedCheckoutService`, `CheckoutConfiguration`), and the only thing that can pass the gate is a
payment Stripe's webhook confirmed:

| Token | Outcome |
|-------|---------|
| A `requestId` from `POST /sws/go/checkout/sessions` that the webhook later recorded as paid, for this account and environment name | Approved |
| Any other value, including one merely *shaped* like the retired mock token | `PAYMENT_DECLINED` |
| absent / blank | `PAYMENT_REQUIRED` |

The token is server-generated and correlated server-side, so a browser cannot turn a successful
return URL into authorization. `CheckoutPaymentRegistry.isPaidFor` matches on the request id **plus**
the account email **plus** the environment name; a confirmed payment therefore cannot be redirected
to another account or another environment.

> **Open risk — `CheckoutPaymentRegistry` is process-local.** It is a static
> `ConcurrentHashMap` in the JVM, so a confirmed payment lives only in the task that received the
> webhook. On ECS, a task recycle or a redeploy between the payment and the onboarding call loses it,
> and the account is charged while provisioning answers `PAYMENT_DECLINED`. Its `EVENTS` idempotency
> map also grows without eviction. Persisting it is tracked separately and is a precondition for
> treating this flow as production-grade at volume.

Money now moves for real, and these gaps are **open against real charges** — they are no longer
hypothetical preconditions for a future gateway:

- **Replay.** The token is never consumed, so one confirmed payment can create N environments. A real
  flow needs the token marked as spent, or bound to a single environment creation.
- **Check-then-act.** The paywall reads ownership, and provisioning creates the client afterwards,
  with no lock in between. Two concurrent `POST /sws/go/onboarding` calls both pass the gate. A real
  flow needs the ownership check and the creation to be atomic, or a uniqueness constraint that
  catches the loser.
- **No atomicity between payment and provisioning.** The paywall passes, then provisioning runs and
  can still fail — its `catch` rolls the DAL changes back and reports failure. That is a captured
  charge with no environment, and there is no refund, retry-with-credit or idempotency
  path anywhere in this flow. The easiest way to trigger it is an oversized `clientName`: nothing
  bounds its length on either end (`parseOnboardingRequest` only rejects the empty string), so it
  fails deep inside provisioning, well past the gate. A real flow needs the charge to be authorized
  before provisioning and captured only after it succeeds, or a compensating refund on failure.

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
so a successful write commits with the tenant. It is best-effort in the other direction: **a paid
tenant can still commit unmarked and read back as free** rather than have provisioning rolled back
over a plan marker. That trade is deliberate — the marker is commercial metadata, not part of the
tenant's functional provisioning — but it means the plan is not a guaranteed record of payment, and
reconciling one is a billing concern rather than something this write can promise.

What changed in ETP-4966 is that this case is no longer **silent**. `markProductive` returns whether
it wrote the marker, and `handleOnboarding` logs an ERROR naming the environment, the client id and
the masked account when a paid environment could not be marked. Before that, a failed marker and a
marker that was never attempted produced the identical observable state — no log line either way —
which is why diagnosing the original report had to go through the ECS task definitions to prove which
of the two had happened.

The write and the read must also agree on the column. `Preferences.setPreferenceValue(...,
isListProperty=false, ...)` stores the key in `AD_Preference.Attribute`, which is what `resolvePlan`
queries; flipping that boolean would store it in `Property` instead, match nothing, and make every
paid tenant read back as free with nothing reporting it. `TenantPlanServiceTest` asserts both halves
for that reason.

A `paymentToken` the webhook confirmed is what makes an environment productive — including for an
account's first environment, and including when converting an environment that already exists.

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

- **`plan`** (per environment) is `"free"` or `"productive"`, and is what the environment picker
  badges as *Demo* / *Productivo*. This field is the user-visible end of the whole chain: when
  ETP-4966 was reported as "I paid and it still says Demo", this is the value that was wrong.
- **`accountEmail`** (top level) is the account identity described in §1.

Both are backward compatible; clients that ignore them are unaffected.

> **`accountEmail` is invisible through the core helper.** `fetchEnvironments` in
> `@etendosoftware/etendo-go-core` returns `data.environments || []`, so it drops every top-level
> field. A consumer using the helper sees nothing and gets no error. Reading `accountEmail` needs a
> direct `fetch`. See §1 for why surfacing it is necessary but not sufficient.

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
4. **Settle the targeting key first** (§1). Until the client has one account identity available for
   every session at bootstrap, turning on a targeting-aware provider will bucket the same user
   differently on each end. This is the one item that must be closed *before* the swap, not after.
5. Watch for an `org.json` classpath collision. `mixpanel-java` parses definitions with
   `org.json:json`, while `WebContent/WEB-INF/lib` already ships a legacy Eclipse-repackaged
   `org.json-1.0.0.v201011060100.jar`. Both provide the same package and the winner depends on
   classloader ordering. It degrades safely — the fetch and parse are inside catch-all handlers, so
   if the legacy classes win, definitions never become ready and every flag reads `false` — but that
   looks identical to the flag simply being off, so check it first if flags never turn on.

## 5. The session identity endpoint (ETP-4693)

`GET /sws/neo/session` carries the platform account identity of the authenticated user, additively:

```json
{
  "currencyCode": "EUR",
  "currencyId": "…", "currencyStandardPrecision": 2,
  "yourCompanyDocumentImageId": "…",
  "organization": { "...": "..." },
  "accountId": "A1B2C3…",
  "accountEmail": "user@example.com"
}
```

**Both fields are omitted — not null, not empty — when the session's AD_User has no `ETGO_ACCOUNT`.**
A hand-created ERP user or a system user is an ordinary case, not an error. Consumers must treat
absence as "unknown identity" and never as a match: an empty-string sentinel would be
indistinguishable from a real value to a targeting rule, which is exactly the silent-mismatch class
of bug this whole item exists to avoid.

### Naming

The fields are `accountId` / `accountEmail` and they mean **`ETGO_ACCOUNT`**. Do not reuse
`account_id`: in the Mixpanel observability layer that name already means the **AD_Client (tenant)**
id. Emitting it here would silently merge two different identities across both analytics and
targeting rules. `NeoSessionAccountIdentityTest` asserts the snake_case names are never emitted, so
the convention is enforced rather than merely documented.

### How the account is resolved

`com.etendoerp.go.common.GoAccountResolver` maps the authenticated AD_User back to its account. This
is the reverse of what onboarding does: onboarding names the environment user after the account
email, appending a client-derived suffix when that username is taken
(`EtendoGoJwtSupport.buildClientUsername`):

```
first environment   -> user@example.com
later environments  -> user@example.com+acmeltd
```

The resolver tries an exact email match first, then strips the suffix and retries. It splits on the
**last** `+`, which is exact: the suffix alphabet is `[a-z0-9]` only — the client name is lowercased
and stripped of everything else — so the suffix can never contain a `+`. A plus-addressed account
therefore still resolves correctly (`user+tag@example.com+acmeltd` → `user+tag@example.com`).
Splitting on the *first* `+` would corrupt precisely those users, and would look like a rare
unexplained mismatch rather than a bug.

Both lookups are exact-match, so no LIKE pattern is built from user-controlled text and there are no
wildcards to escape. Failures degrade to "no identity" rather than propagating — session enrichment
must never break the session.

## 6. Source map

| Concern | Class |
|---------|-------|
| Flag entry point, provider swap point, failure policy | `com.etendoerp.go.featureflags.GoFeatureFlags` |
| Local configuration-backed provider | `com.etendoerp.go.featureflags.PropertiesFeatureProvider` |
| Vendor-neutral targeting context | `com.etendoerp.go.featureflags.FeatureFlagContext` |
| Typed flag/property readers (`boolean`, `int`) | `com.etendoerp.go.common.GoRuntimeProperties` |
| Shared property resolution (system → Openbravo → env) | `com.etendoerp.go.common.ConfigPropertyReader` |
| Paywall decision + productive-plan derivation | `com.etendoerp.go.payment.TenantPaywallService` |
| Stripe hosted checkout session | `com.etendoerp.go.payment.HostedCheckoutService`, `CheckoutConfiguration` |
| Webhook signature + confirmed-payment correlation | `com.etendoerp.go.payment.CheckoutWebhookVerifier`, `CheckoutPaymentRegistry` |
| Plan read/write | `com.etendoerp.go.payment.TenantPlanService` |
| Gate wiring, 402 response, plan marking | `com.etendoerp.go.rest.EtendoGoJwtServlet` |
| Ownership count, `plan` in `/environments` | `com.etendoerp.go.rest.EtendoGoJwtDalHelper` |
