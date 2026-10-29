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
| Control plane | **Datadog Feature Flags** → **ConfigCat** → **local configuration**, first usable arm wins (see *The swap point*) |
| Evaluation | In-process on every arm. The hosted arms evaluate against an in-memory snapshot refreshed in the background; a flag check is never a network call. |

Application code never imports an OpenFeature type. It calls one entry point:

```java
boolean enabled = GoFeatureFlags.isEnabled(
    GoFeatureFlags.FLAG_BP_PORTAL_LINK,
    FeatureFlagContext.forAccount(accountEmail));
```

### The swap point

`GoFeatureFlags.createProvider()` is the **only** place that decides which provider backs the API.
It installs exactly one provider, the first arm whose prerequisites are all met
(`GoFeatureFlags.selectProvider(...)`):

| # | Arm | Selected when | Provider |
|---|-----|---------------|----------|
| 1 | **Datadog** (ETP-5751) | `DD_FEATURE_FLAGS_ENABLED=true` (or `-Ddd.feature.flags.enabled=true`) **and** `dd-java-agent` is loaded in the JVM | `datadog.trace.api.openfeature.Provider` (`com.datadoghq:dd-openfeature`) |
| 2 | **ConfigCat** (ETP-5267) | `etendo.go.configcat.sdkKey` / `ETGO_CONFIGCAT_SDK_KEY` is set and not blank | `ConfigCatProvider` (`dev.openfeature.contrib.providers:configcat:0.1.1`) |
| 3 | **Local properties** | neither of the above | `PropertiesFeatureProvider` |

Two rules govern the order, and they point in opposite directions on purpose:

- **A missing prerequisite skips an arm.** The Datadog switch on in a JVM without the agent (dev, CI,
  e2e, a local Tomcat) falls through to ConfigCat, then to local properties, and logs a `WARN` that
  names `DD_FEATURE_FLAGS_ENABLED`. A blank SDK key falls through the same way. Absent configuration
  is not an error.
- **A selected arm that breaks turns every flag off.** Once a hosted arm is installed, a failure to
  initialize it (a wrong ConfigCat key, a Datadog provider that cannot start) resolves every flag to
  its `false` code default. It never falls through to a lower arm whose local value might say `true`
  — that is the ETP-4966 shape and must never be "improved" into a fallback that allows.

The agent check is a class probe, not a configuration read: `dd-openfeature` is only a bridge to
classes that `dd-java-agent` puts on the bootstrap class path
(`datadog.trace.api.featureflag.FeatureFlaggingGateway`), which no public jar ships. Without them the
provider can never become ready, so installing it would silently turn every flag off.

Moving to another control plane still means returning a different `FeatureProvider` from that one
method, plus its dependency in `build.gradle`. Nothing else in `GoFeatureFlags`, nothing else in the
package, and no caller anywhere changes. Keep it that way. Provider-specific concerns — credentials,
polling schedules, caches, retry and staleness handling — belong **inside** the provider. Any
provider bound here must honour the guarantees below: never block the calling thread on I/O beyond
the bounded first wait, never throw, and resolve to the caller's default when it cannot answer.

### Datadog Feature Flags (ETP-5751)

The flags live in the Datadog Feature Flags product. `dd-openfeature` is a thin OpenFeature
provider; the flag configuration is fetched by **`dd-java-agent`**, which must be loaded with
`-javaagent`. The runtime image (`.github/docker/Dockerfile.runtime`) bakes the agent in at
`/opt/datadog/dd-java-agent.jar` with a pinned version and SHA-256, and
`.github/docker/docker-entrypoint.sh` appends `-javaagent:` to `CATALINA_OPTS` **only when
`DD_FEATURE_FLAGS_ENABLED` is true** — an environment without the `DD_*` variables starts exactly as
before. Tracing is not needed for this and defaults to disabled (`DD_TRACE_ENABLED=false`).

| Variable | Value | Purpose |
|----------|-------|---------|
| `DD_FEATURE_FLAGS_ENABLED` | `true` | Starts the agent's feature-flag subsystem **and** selects the Datadog arm here — one knob, not two that can disagree. **The only documented value is `true`** (the entrypoint and `GoRuntimeProperties.readBoolean` both also take `Y`; `1`/`yes` are rejected by both) |
| `DD_API_KEY` | secret | Authenticates the agentless configuration fetch |
| `DD_SITE` | e.g. `datadoghq.eu` | Datadog site of the organisation |
| `DD_ENV` | `experimental` / `production` / … | **Chooses the Datadog environment** the flags answer from (see below) |
| `DD_SERVICE` | `etendo-go` | Service identity |
| `DD_VERSION` | image version | Optional, reported with evaluations |
| `DD_TRACE_ENABLED` | `false` | No APM tracing — only feature flags |

**`DD_ENV` → Datadog environment.** Each Datadog environment declares the `DD_ENV` values it answers
to (its *queries*). Flags do not target services: every service whose `DD_ENV` matches an
environment reads that environment's flag state.

| ECS deployment | `DD_ENV` | Datadog environment |
|----------------|----------|---------------------|
| experimental | `experimental` | Experimental |
| production | `production` | Production (also answers to `prod`) |
| staging | `staging` | Staging (also answers to `stage`) — **disabled** in Datadog |

Only **Experimental** and **Production** are enabled in Datadog today; Staging and Development are
disabled, so a JVM whose `DD_ENV` points at them resolves every flag to its `false` code default —
the same result as a `DD_ENV` that matches no environment, which is not an error either. The web
client maps `VITE_APP_ENV` onto the same environments. Check the queries in Datadog before
introducing a new value.

**Targeting.** Datadog has no reserved user fields: the targeting key drives percentage bucketing
and every rule matches a flat attribute **by its exact, case-sensitive name**. The rules in the
Datadog project match on lowercase `email`, so `FeatureFlagContext.forAccount(...)` publishes the
account email as the targeting key, as `email` (Datadog, **lowercased** with `Locale.ROOT` — rule
values are matched case-sensitively and every value in the Datadog project is lowercase) **and** as
`Email` (ConfigCat, as stored — see *Per-account targeting* below). Drop `Email` only when ConfigCat is retired.

**Data sent to Datadog.** Evaluation is in-process, but the agent reports flag exposures and
evaluations to Datadog with the evaluation context — the account email included — so that context
leaves the JVM. The web client sends the same attributes from the browser (see
`docs/feature-flags.md` in the functional repo). This is accepted under the same Datadog data
processing as our logs and RUM.

**Startup.** Installing the provider waits at most 5 s (`DATADOG_MAX_INIT_WAIT_SECONDS`; Datadog's
own default is 30 s) for the first configuration. If it has not arrived, the provider stays
installed, every flag reads `false` meanwhile, and it turns ready by itself as soon as the agent
delivers the configuration — no restart. After that, flag changes in Datadog reach the JVM without a
restart.

> **VERSION LOCK — `dd-openfeature` MUST equal the deployed `dd-java-agent` version** (currently
> **1.66.0**). The provider
> calls into the agent's internal bootstrap classes, which carry no compatibility guarantee across
> versions. The version is pinned once, as `ext.ddJavaAgentVersion` in this module's `build.gradle`.
> `deploy-staging.yml` reads it and passes it to the image build as `--build-arg
> DD_JAVA_AGENT_VERSION`, and `Dockerfile.runtime` verifies the download against its pinned
> `DD_JAVA_AGENT_SHA256` — so bumping the Gradle pin without updating the checksum **fails the image
> build** instead of shipping a mismatch. Update both in the same change (and the mirror copy in
> `etendo-go-infraestructure/docker/Dockerfile.runtime`). A mismatch that did reach runtime would
> show as "every flag off" or a `LinkageError` at the first evaluation.

**Where the image is built.** `deploy-staging.yml` builds the staging image from
`.github/docker/` of this module. The experimental and production images are built on their build
servers from `/opt/docker-build`, whose copies have diverged: the agent block of
`Dockerfile.runtime` and the `DD_FEATURE_FLAGS_ENABLED` block of `docker-entrypoint.sh` are
**patched into** those copies by hand, never copied over whole.

**Applying the variables.** Every `DD_*` is read from the process environment, not from
`Openbravo.properties`, so each one is applied with `setup-env-var.sh apply --no-entrypoint` and no
`--prop`. `DD_API_KEY` is a per-environment secret, `etendo/<env>/datadog-api-key`;
`DD_FEATURE_FLAGS_ENABLED=true` goes last, with `--deploy`. Experimental first, then the smoke check
— including the *proof it reads Datadog and not ConfigCat* (a targeting rule that makes a flag
diverge between the two control planes for one tester) — and only then production. Full procedure:
`etendo-go-infraestructure/COMO-REEMPLAZAR-AL-TUTI.md`, section *Datadog Feature Flags (ETP-5751)*.

**Rollback.** Remove `DD_FEATURE_FLAGS_ENABLED` from the task definition (or set it to `false`) and
redeploy. The entrypoint stops loading the agent and the backend falls back to ConfigCat (while
`ETGO_CONFIGCAT_SDK_KEY` is set) or to local properties. No image rebuild is needed. The web
client's rollback is independent: the `VITE_FLAGS_PROVIDER=configcat` Actions variable plus a
redeploy.

### ConfigCat (ETP-5267)

`etendo.go.configcat.sdkKey` (JVM system property or `Openbravo.properties`) or the environment
variable `ETGO_CONFIGCAT_SDK_KEY` selects ConfigCat when the Datadog arm is not selected. Note the
spelling: **`sdkKey`**, camel case — `etendo.go.configcat.sdk-key` is not read. While Datadog rolls
out, ConfigCat stays as the second arm so an environment whose image has not gained the agent keeps
reading hosted flags.

The provider is bound to the OpenFeature **domain** `etendo-go` rather than the global default
provider, so this module cannot clobber a provider installed by another module.

### Configuration

A flag `my-flag` is read from `etendo.go.flags.my-flag`, resolved in priority order: JVM system
property, `Openbravo.properties`, environment variable `ETGO_FLAG_MY_FLAG` (uppercased, every
non-alphanumeric character replaced by `_`). See `com.etendoerp.go.common.GoRuntimeProperties`.

These local properties are a **plain boolean per environment**, and they are read **only on the local
arm** — wherever Datadog or ConfigCat is selected they are inert. Per-account targeting is a hosted-arm
concern only — see **Per-account targeting** below for why there is deliberately no local equivalent.

Declare every flag's key as a constant on `GoFeatureFlags` and add its row here.

| Flag | Property | Environment variable | Default |
|------|----------|---------------------|---------|
| `bp-portal-link` | `etendo.go.flags.bp-portal-link` | `ETGO_FLAG_BP_PORTAL_LINK` | absent ⇒ **`false`** |
| `environment-access-enforcement-off` | `etendo.go.flags.environment-access-enforcement-off` | `ETGO_FLAG_ENVIRONMENT_ACCESS_ENFORCEMENT_OFF` | absent ⇒ **`false`** = enforcing (an OFF switch — see its section below) |
| `onboarding-tenant-pool` | `etendo.go.flags.onboarding-tenant-pool` | `ETGO_FLAG_ONBOARDING_TENANT_POOL` | absent ⇒ **`false`** |
| `mcp-modern-era-disabled` | `etendo.go.flags.mcp-modern-era-disabled` | `ETGO_FLAG_MCP_MODERN_ERA_DISABLED` | absent ⇒ **`false`** (dual-era on) |
| `batch-callout-skip-disabled` | `etendo.go.flags.batch-callout-skip-disabled` | `ETGO_FLAG_BATCH_CALLOUT_SKIP_DISABLED` | absent ⇒ **`false`** (opt-in skip honoured) |
| `contact-category-type` | `etendo.go.flags.contact-category-type` | `ETGO_FLAG_CONTACT_CATEGORY_TYPE` | absent ⇒ **`false`** (pre-ETP-5791 contact behaviour); per account on a hosted arm only — see its section below |
| `billing-reconciliation-schedule` | `etendo.go.flags.billing-reconciliation-schedule` | `ETGO_FLAG_BILLING_RECONCILIATION_SCHEDULE` | absent ⇒ **`false`** (no schedule is planted) |
| `usage-aggregation-schedule` | `etendo.go.flags.usage-aggregation-schedule` | `ETGO_FLAG_USAGE_AGGREGATION_SCHEDULE` | absent ⇒ **`false`** (no schedule is planted) |
| `structured-address-for-sepa` | `etendo.go.flags.structured-address-for-sepa` | `ETGO_FLAG_STRUCTURED_ADDRESS_FOR_SEPA` | absent ⇒ **`false`** (legacy single-line address; TTL 2026-11-15 — SEPA 2025 rulebooks ban unstructured addresses in SEPA remittance files from that date, per [etendosoftware/etendo-ai#24](https://github.com/etendosoftware/etendo-ai/issues/24)) |
| *(pattern for a new flag)* | `etendo.go.flags.<key>` | `ETGO_FLAG_<KEY>` | absent ⇒ **`false`** |

`bp-portal-link` (ETP-5267) decides whether a `sales-invoice-send` email carries a link to the
Business Partner self-service portal. It is **targeted per sending account** and false for everyone
until a targeting rule on the hosted control plane (Datadog, or ConfigCat while it remains a
fallback) names that account's email, so day-to-day enablement happens in that dashboard and takes
effect without a restart. Where no hosted arm is selected the flag degrades to the local
per-environment boolean above, which has no per-account notion — so a shared environment needs a
hosted arm set up before the link is switched on for anyone. It is
**backend-only** — no key in the web client's `flag-keys.js`, nothing in the
browser reads it, and none must be added. It gates the link only: the portal route, the three
`/sws/portal/*` endpoints, the `etgo_portal_access` table and the revoke action all ship
unconditionally, and revocation in particular must work whatever the flag says, since it is the only
kill switch for a link already out.

`structured-address-for-sepa` (ETP-5806, TTL 2026-11-15) decides whether the structured SEPA
address fields (Street/Number/Building Name) are exposed and enforced, backend-only — the frontend
only ever reads `capabilities.structuredAddressForSepa` (off `SFWindowAccessMap` and the session
bootstrap) and never evaluates the flag key itself. Since ETP-5806's staged-rollout follow-up,
**every** call site is targeted per account, mirroring `bp-portal-link`'s pattern: the real
`ETGO_ACCOUNT` email goes into `FeatureFlagContext.forAccount(...)` rather than `null`. This lets a
Datadog targeting rule (e.g. "email is one of [...]") roll the feature out to the localization team
first, then the rest of Etendo, then everyone, instead of flipping it for every tenant at once; with
no targeting rule configured it is still the plain per-environment boolean default, so the TTL stays
a hard global deadline.

The email reaches each call site in one of two ways, and the split is the point — resolving it
implicitly inside the shared evaluator would be wrong for the second group:

| Call site | Gates | Where the email comes from |
|---|---|---|
| `SFWindowAccessMap` | the capability signal | `GoAccountResolver.currentAccountEmail()`, resolved **before** the webhook's own `OBContext.setAdminMode()` |
| `ContactsLocationAddressHandler`, `WarehouseLocationHandler`, `BpLocationHandler` | NEO read/write (validation, persistence, response shape) | `GoAccountResolver.currentAccountEmail()`, once per request at the handler's entry point, then threaded through its own helpers |
| `AddressVirtualSelectorPolicy`, `McpSchemaFieldBuilder` | MCP schema exposure | `GoAccountResolver.currentAccountEmail()` (both are request-scoped — nothing is cached across accounts) |
| `EtendoGoJwtServlet#buildSessionCapabilities` | the capability signal in the onboarding bootstrap | the restored session's own account |
| `EtendoGoJwtServlet#validateStructuredAddressBlock` / `…Length` | onboarding validation | passed down from `prepareOnboarding`, which already resolved the account |
| `OnboardingOrgInfoService#createLocation` | onboarding **persistence** | carried on `OrgInfoInput` from the same request — provisioning runs under an admin execution context, so there is no caller identity left to recover |

`StructuredAddressComposer.isEnabled(String accountEmail)` is the one evaluator they all share and
it deliberately has **no account-less overload**. That is what keeps the ends honest: the gap this
follow-up closed was a targeted account seeing the 3 fields in the UI (per-account capability) while
the backend validated, accepted and persisted them per the global default (account-less gating) —
the user filled them in, saved, and the data was silently dropped by the flag's "ignore, don't
reject when off" contract. Watch for the ordering trap when adding a call site: resolve the account
email **before** any no-arg `OBContext.setAdminMode()`, which substitutes the admin identity when
there is no context at all.

`tenant-upgrade` was the only earlier backend flag and it retired with ETP-4966.

**Lesson from the retired flag, worth honouring for the next one:** a flag whose two ends read from
different control planes has no single truth. Either both ends resolve the same key from the same
control plane, or the backend is the only evaluator and the browser asks it. An unset backend key is
indistinguishable from a disabled feature — which is how a charged account got a free environment.

Accepted affirmatives: `true`, `Y`, `yes`, `1`. Accepted negatives: `false`, `N`, `no`, `0`
(case-insensitive).

### Per-account targeting (ETP-5267) — hosted arms only, deliberately

> ETP-5751 added the Datadog arm and the lowercase `email` attribute it matches on (see *Datadog
> Feature Flags* above). Everything below about ConfigCat's `Email` still holds while ConfigCat is a
> fallback arm.

This module publishes the account identity into every evaluation, so a hosted provider can target on
it: `FeatureFlagContext.forAccount(...)` sets the `ETGO_ACCOUNT` email as the OpenFeature
**targeting key** *and* as the `Email` attribute. Both, because ConfigCat's `ContextTransformer` maps
the targeting key to its user *identifier* and only an attribute named exactly `Email` to the
*email* — so a dashboard rule written against Email (the obvious one to write) matches, and so does
one written against Identifier. Publishing only the targeting key is the trap: the obvious rule would
match nobody, and a flag that silently resolves false is indistinguishable from one deliberately off.

**There is no local per-account mechanism, and that is a decision rather than a gap.** An earlier
iteration of ETP-5267 added an `etendo.go.flags.<key>.emails` allowlist to
`PropertiesFeatureProvider` and it was removed the same day, for two reasons:

- **It would silently do nothing wherever ConfigCat is configured.** Only one provider is ever
  installed, so with an SDK key set the property is inert — a configuration knob that appears to
  control something and does not. That is the same family of failure as ETP-4966.
- **Two ways to express one decision is two things to keep in sync**, in code shared by every flag in
  the module.

Consequences to hold onto:

- **A dev box needs no targeting.** It has one user, so the plain boolean is enough there.
- **A shared environment must have a hosted arm (Datadog, or ConfigCat while it remains the
  fallback) configured before the flag is switched on**, because the local arm can only answer
  "everyone" or "nobody".
- **Evaluation is in-memory and costs no network call.** `dd-java-agent` keeps the Datadog
  configuration in memory and refreshes it in the background; ConfigCat's `autoPoll` does the same
  every 60 s (`CONFIGCAT_POLL_SECONDS`). A flag check inside request handling is a map lookup.
- **A flag that does not exist on the hosted control plane resolves to its code default, `false`** —
  asking for an undefined key is never an error.
- **Creating a flag or changing a targeting rule takes effect with no restart** (on ConfigCat,
  within one poll interval). That, not the local properties, is the day-to-day path.

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

**Status (ETP-5791 audit): half closed.** The web client now consumes them —
`refreshAccountIdentity` in the functional repo's `tools/app-shell/src/lib/flags/bootstrap.js` reads
`/sws/neo/session` and `buildEvaluationContext` publishes `accountEmail` (lowercased) as `email`. So a
rule on the account **email** agrees on both ends. The **targeting key** still differs (web: the
`ETGO_ACCOUNT` id; backend: the email), so identifier rules and percentage rollouts still bucket
differently, and the tenant attribute is `account_id` on the web and `clientId` on the backend. The
first two-ended per-account flag, `contact-category-type`, is therefore targeted on email only — see
its section below.

**What remains open is the targeting key.** Until both ends use the same key, **a flag with a
frontend end may be made targeting-aware only through rules on the email attribute** — never on the
identifier, a percentage rollout or a tenant attribute named for one end only. Full client-side
reasoning is in `docs/feature-flags.md` in the functional repo.

**What ETP-5267 changed, and why it is not a violation of the above.** A targeting-aware provider
*is* now installed (see **Per-account targeting**), which the previous wording ruled out
categorically. The narrower rule is the correct one: this divergence is between *two* evaluators, so
it can only bite a flag the browser also reads. A backend-only flag that targets the **account
email** — the key this section says the backend targets on — has no second end to disagree with. A
flag read on both ends still needs the frontend half of ETP-4693 first.

### Failure behaviour — never block, never fail, default false

| Situation | Result |
|-----------|--------|
| Flag not configured | Code default (`false`) |
| Flag configured with a non-boolean value | Code default, with a `PARSE_ERROR` on the evaluation so a typo is visible rather than silently reading as "disabled" |
| Provider registration failed | No provider bound; every flag resolves to its default |
| Hosted provider not ready within the bounded first wait | Provider stays bound; every flag resolves to its default until the first configuration arrives, then it serves live values |
| Datadog switched on but `dd-java-agent` not loaded | The Datadog arm is skipped (logged `WARN`); the next arm is selected |
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

### `demo-data-transfer` — retired (ETP-5480)

The ETP-5443 flag that gated the ETP-5364 demo-to-productive data transfer is gone: the transfer is
permanent. `DemoDataTransferFlag`, `GoFeatureFlags.FLAG_DEMO_DATA_TRANSFER` and the older
synchronous NEO grid-import path it kept alive for the flag-off case (`OnboardingDataTransferService`)
were removed. A leftover `etendo.go.flags.demo-data-transfer` / `ETGO_FLAG_DEMO_DATA_TRANSFER`
setting, or a ConfigCat key of that name, is no longer read and can be deleted.

What was a toggle point is now unconditional in `EtendoGoJwtServlet`:

| Point | Behaviour |
|---|---|
| `GET /sws/go/demo-data-transfer`, `POST /sws/go/demo-data-transfer/retry` | always routed; authenticated like any other session endpoint |
| `recordDemoDataTransferSelection` (checkout / purchase) | records the body's `dataTransfer` selection when the purchase has a demo source |
| `startDemoDataTransferBestEffort` (paid onboarding commit) | starts the asynchronous transfer from the demo persisted on the purchase |

The worker thread is still created on first submission, so an instance that never receives a
transfer never starts one. Only the server-recorded asynchronous job copies data; the onboarding
body's `dataTransfer` is ignored. Source and target client IDs must differ.

Checkout records `{products, contacts}` under its request ID before contacting Stripe. The first
selection is immutable on checkout reopen. `GET /billing/purchases/{id}` and the billing overview
include `dataTransferEnabled: true` for a purchase with a demo source (kept for the upgrade page,
which still reads it) and include `dataTransfer` only when a server-side selection exists. An older
purchase with no selection therefore remains `NOT_REQUESTED`; the browser must not guess its
choice. The recovery procedure is in
[`demo-data-transfer-recovery.md`](demo-data-transfer-recovery.md).

### `environment-access-enforcement-off` (ETP-5047) — backend-only kill switch, enforcing by default

The commercial access check — HTTP 402 once a demo trial has expired or a subscription's payment
grace has elapsed — went live with ETP-5443 with no off switch. This flag is that switch. It is
**phrased as an OFF switch on purpose**: every failure in the table above resolves to `false`, and
`false` here means *still enforcing*. An unset key, an unreachable ConfigCat, a wrong SDK key or an
evaluation error therefore all keep today's behaviour; only the flag resolving to `true` — on ConfigCat the boolean setting served as `true`; locally
(`etendo.go.flags.environment-access-enforcement-off` / `ETGO_FLAG_ENVIRONMENT_ACCESS_ENFORCEMENT_OFF`)
any of `true`, `Y`, `yes`, `1`, case-insensitive and trimmed. `false`, `N`, `no`, `0` keep
enforcing, and any other value is a `PARSE_ERROR` that keeps enforcing too.
A positively phrased "enforcement enabled" flag would do the opposite — a missing key would silently
open every blocked tenant — so do not flip the polarity.

Evaluated in exactly one place, `EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(clientId)`,
called by `EnvironmentAccessGuard` — the one check every tenant entry point shares — and, report-only, by the environments list and `GET /sws/go/billing/subscription` (`accessEnforced`, ETP-5051). The guard serves the bind
step of the shared auth pipeline (`EnvironmentRequestAuthenticator`, ETP-5455) for every surface
whose `SurfacePolicy` requires commercial access (`NEO_API`: NEO; `NEO_DATA`: favorites, fiscal test
mode, report selectors, the OAuth2 API-key endpoints), the `/sws/go` endpoints that act on the
session's tenant (`resolveTenantSession`), MCP (`McpServlet`) and the legacy environment login
(`GET /sws/go/login`). `NEO_AUXILIARY` surfaces (survey configuration, support) never ask.
`POST /sws/go/session/environment` evaluates it but never refuses — it reports `accessDecision`,
which is informational and backend-only (the SPA's blocked screen is driven by the NEO 402) — so
the blocked customer reaches the pay path. The context is
account-less and carries the tenant as the `clientId` attribute, so a ConfigCat rule can switch
enforcement off for one tenant or for all. It is consulted **only for a denial**: an allowed request
never evaluates it. The one exception is a report, never a refusal: since ETP-5051 every environment
of `GET /sws/go/environments`, and `GET /sws/go/billing/subscription` for its tenant, carry
`accessEnforced`, the switch evaluated for that tenant whatever today's decision is (`false` while the
switch is on for it), so the grace banner and the /account grace countdown drop their "will be
blocked on <date>" / "before access is paused" promise when no block would follow
(`open-and-notable-topics.md` §4.17).
Locally: `etendo.go.flags.environment-access-enforcement-off=true` /
`ETGO_FLAG_ENVIRONMENT_ACCESS_ENFORCEMENT_OFF=true`.

| Flag | A tenant whose decision is `DEMO_TRIAL_EXPIRED` / `SUBSCRIPTION_REQUIRED` |
|---|---|
| unset / `false` / unreadable | 402 on NEO, the `NEO_DATA` servlets, the `/sws/go` tenant-session endpoints, MCP and `GET /sws/go/login`; INFO log `Commercial access denied at <entry point> for tenant <id>: <DECISION>` |
| `true` (for that `clientId`, or globally) | allowed; INFO log `Environment access enforcement is switched off: <entry point> would have refused tenant <id> (<DECISION>)` |

It is an incident switch (a wrong status after a provider outage, a bad deploy), not a way to give a
tenant free access. No key exists in the web client's `flag-keys.js`, and none must be added: the
SPA follows the 402 it is given. The 402 body and the entry points are in *Operating
environment-access enforcement* below and in `neo-headless.md` §4.1; keep the body's `message`
unchanged for at least one release after a backend change, since an older SPA still parses it
(`environmentAccessGate.js` reads `error.decision` first). The row lifecycle behind the decision is
`open-and-notable-topics.md` §3.7; the registry entry is `environment-access-enforcement-kill-switch`
in `schema_forge/flags-registry.json`.

### Operating environment-access enforcement (ETP-5047) — deploy, switch off, support

The one operator-facing page for the commercial block. Enforcement needs **no switch to turn on**:
it is live from the moment the module is deployed.

**Deploy order.**

1. Deploy the ETP-5047 module (`update.database` adds `ETGO_SUBSCRIPTION.GRACE_ANCHOR` and
   `LAST_EVENT_AT`).
2. Only then run the `R37-tenant-subscription-backfill` data-fix — opt-in, as step 4 of *The
   subscription-model switch* (§3), after `ETGO_SubscriptionModel` is set. It now inserts into those two
   columns, so run before the deploy it fails on every tenant (`FAILED`, retried by the runner — an
   error, not a silent skip). An environment that already ran the pre-ETP-5047 R37 needs nothing
   re-run: its rows are read through the `GRACE_ANCHOR` / watermark fallbacks
   (`open-and-notable-topics.md` §2.3, §3.7).
3. Before running R37 on an environment, settle the two edge cases in `open-and-notable-topics.md`
   §3.11: list the tenants whose `ETGO_SubscriptionStatus` is `PAST_DUE` with no valid
   `ETGO_SubscriptionDueAt` (R37 backfills them `past_due` with no anchor — blocked at once), and,
   if subscriptions have already been canceled live on that environment, decide the `@check`
   change first.

**Switching enforcement off (incident only).**

| Where | How | Scope | Takes effect |
|---|---|---|---|
| ConfigCat (SDK key configured) | boolean setting `environment-access-enforcement-off` served as `true` | everyone, or one tenant through a targeting rule on the `clientId` attribute (the tenant's `AD_Client_ID`) | without a restart, on the next poll |
| Local configuration (no SDK key) | `etendo.go.flags.environment-access-enforcement-off=true` in `Openbravo.properties` (or the JVM property, or `ETGO_FLAG_ENVIRONMENT_ACCESS_ENFORCEMENT_OFF=true`) | the whole environment — there is no per-tenant form locally | after a Tomcat restart |

Confirm it took: a would-be refusal logs `Environment access enforcement is switched off: <entry
point> would have refused tenant <id> (<DECISION>) and allowed it` at INFO, and
`GET /sws/go/environments` reports that tenant's `accessState` as `ALLOWED`. Undo by removing the
rule/property (or setting `false`). Anything that is not a clean `true` keeps enforcing.

**The customer is not told.** A tenant in payment grace keeps seeing the in-app failed-payment
banner (ETP-5049) announcing that the environment *will be blocked on* its `graceEndsAt`, even
with the switch on for it; once that date passes the banner disappears and the environment keeps
working with no in-app warning at all. The SPA cannot see the switch: during grace `accessState`
reads `ALLOWED` either way. Accepted known limitation — tell support when you switch a tenant in
grace off; details and the possible fix in `open-and-notable-topics.md` §4.17.

**What support sees for a blocked tenant.**

- **The customer**, after entering the environment (entry is never refused), gets the blocked
  screen instead of the app: *"Access suspended for non-payment"* with **Manage subscription** →
  `/account` (`SUBSCRIPTION_REQUIRED`), or *"Your trial period has ended"* with **Upgrade plan** →
  `/upgrade` (`DEMO_TRIAL_EXPIRED`). `/account` (billing, Stripe Customer Portal) and `/upgrade`
  keep working, and the environment picker labels the tenant *Access suspended* / trial expired
  from `accessState`.
- **An API client or AI agent** gets HTTP 402 with
  `{"error":{"message":"Environment access is not available: <DECISION>","status":402,"code":"ENVIRONMENT_ACCESS_DENIED","decision":"<DECISION>"}}`
  from NEO, MCP (a plain HTTP 402, not a JSON-RPC error), the `NEO_DATA` servlets (favorites,
  fiscal test mode, report selectors), the `/sws/go` tenant-session endpoints and
  `GET /sws/go/login`. (The OAuth2 API-key endpoints refuse through the same guard, in the OAuth2
  servlet's own error envelope.) A token on org `0` (the System client after `COALESCE`) is judged
  on its role's tenant, on NEO and MCP alike; if that role lookup fails the request gets **503**
  (`The environment of this credential could not be verified; retry later`) — never an unjudged
  pass (`open-and-notable-topics.md` §3.12). One gap: an Etendo JWT minted **before** the block
  keeps working on Copilot until it expires (`open-and-notable-topics.md` §3.10).
- **The log** has exactly one INFO line per refused request, written by `EnvironmentAccessGuard`
  whichever surface refused: `Commercial access denied at <entry point> for tenant <AD_Client_ID>:
  <DECISION>`. Entry points: `neo`, `mcp`, `report-selectors`, `oauth2-api-keys`,
  `tenant-session` (the `/sws/go` tenant-session endpoints), `environment-login`
  (`GET /sws/go/login`), and the `JwtAuthUtils` servlets' own labels (`favorites GET`,
  `fiscal-test-mode GET`, ...). Grep for `Commercial access denied` and the tenant id. Entering the
  environment (`POST /sws/go/session/environment`) writes no such line: it refuses nothing.
- **Why it is blocked**: `GET /sws/go/environments` (`accessState`, `subscriptionStatus`, trial
  fields) and the tenant's subscription rows —

  ```sql
  select status, start_date, end_date, grace_anchor, current_period_start, current_period_end,
         last_event_at, stripe_subscription_id
    from etgo_subscription
   where environment_client_id = '<AD_Client_ID>' and isactive = 'Y'
   order by end_date desc nulls first, created desc;
  ```

  A `past_due` row is blocked once `grace_anchor + etendo.go.billing.grace.days` (default 15) has
  passed; a `canceled` or closed row is blocked at once. The lifecycle events that led there are in
  `ETGO_BILLING_EVENT` (`event_result`, `failure_reason`); a `charge.dispute.created` there never
  blocks anyone.
- **Unblocking** is the customer paying: for a `past_due` row, through `/account` (the Stripe
  Customer Portal) — the resulting `invoice.paid` sets the open row back to `active`; for a canceled
  subscription, a new purchase, which opens a fresh row (`open-and-notable-topics.md` §3.7). A hand edit of the row is overwritten by the next
  lifecycle event; the kill switch is for incidents, not for granting access.
- **A new tenant whose row has no billing period** (`current_period_start/end` NULL in the query
  above). A paid onboarding reads the period from Stripe right after it closes the checkout
  request, because the purchase's first `invoice.paid` / `customer.subscription.updated` arrive
  before the tenant exists and are ignored for good (`unresolved subscription`). When that read
  fails the log has one WARN, `Could not read the billing period of Stripe subscription <sub_…>
  for client <AD_Client_ID>: <reason>. The subscription row stays without a period until its next
  lifecycle event` (preceded by the transport's own `Provider answered …` / `Payment provider
  call … did not complete` WARN for a 5xx, 429, timeout or DNS/TLS failure); an unexpected
  failure outside the read logs `The subscription of client <id> could not be given its billing
  period` instead. Access is unaffected — the period is for the quotas and period usage
  (ETP-5051), not the access decision — and the next lifecycle event (the first renewal at the
  latest) fills it. A row with no `stripe_subscription_id` (payment-mode checkout) is skipped with
  an INFO and stays periodless. See `open-and-notable-topics.md` §3.7 and §5.5.

### `onboarding-tenant-pool` (ETP-5389) — backend-only, off by default

Switches onboarding between the classic from-scratch path (off) and claiming a pre-provisioned
tenant from `ETGO_TENANT_POOL` (on). Evaluated through `TenantPoolConfig.isEnabled(accountEmail)`
at two points: the onboarding claim (with the signup's account, so the hosted control plane can target
it per account) and every run of the "Tenant Pool Filler" background process (account-less). Off, the
claim never touches the pool and the filler run does nothing — onboarding is byte-for-byte the
classic path. The pool size and the other knobs are plain runtime properties, not flags. Full
reference: [`onboarding-flow.md`](onboarding-flow.md), "Tenant pool".

### `mcp-modern-era-disabled` (ETP-5640) — backend-only kill switch, inverted

Turns off the stateless MCP era (spec 2026-07-28) on `/sws/mcp`: `true` makes `server/discover`
answer `-32601` again and serves every request under the `initialize`-based era, exactly as before
ETP-5640. **Inverted on purpose**: every flag resolves to `false` when it is missing or the control
plane is unreachable, and that must leave the server dual-era rather than switch it off. Environment
level only (evaluated with an empty context) — never target it per account, because MCP clients
cache the era per origin and one origin serves every tenant. Its only caller is
`McpRequestEra.modernEnabled()`. Retire it, keeping the modern path, once the modern era is stable in
production. Full reference: [`neo-headless.md`](neo-headless.md) §4.12.30.

### `batch-callout-skip-disabled` (ETP-5676) — backend-only kill switch, inverted

Turns off the per-batch redundant-callout skip: `true` makes every `/sws/neo/batch` and
`etendo_batch` request run every create-cascade callout, even one sent with
`_skipRedundantCallouts: true`. Unset or `false` honours that opt-in; a batch without it never
skips anything either way. Inverted for the same reason as `mcp-modern-era-disabled`: a missing
flag or an unreachable control plane must not change behaviour. Environment level (empty context),
evaluated once per batch; its only caller is `NeoBatchScope.calloutSkipKillSwitchOn()`. Retire it,
keeping the skip, once the skip has run on real tenant imports without a wrong default. Full
reference: [`neo-headless.md`](neo-headless.md) §4.3.6.

To flip it: set it to `true` in ConfigCat (live within one 60 s poll). The local value
(`etendo.go.flags.batch-callout-skip-disabled=true` in `Openbravo.properties`, or
`ETGO_FLAG_BATCH_CALLOUT_SKIP_DISABLED=true`, plus a Tomcat restart) is read **only where no
ConfigCat SDK key is configured**: `GoFeatureFlags#createProvider()` installs one provider, ConfigCat
when `etendo.go.configcat.sdkKey` resolves, `PropertiesFeatureProvider` otherwise, never both.

### `contact-category-type` (ETP-5791) — per account, read on both ends

Turns on contact types driven by the contact category: a category (`C_BP_Group`) must declare
Customer and/or Vendor/Creditor, a new contact inherits them unless the request sends them, a
contact cannot be saved with no type, and moving a contact to another category re-inherits both.
Unset or `false` (the default) keeps the pre-ETP-5791 behaviour: the contact and contact-category
customizations return `null` and the default CRUD runs. Only behaviour is gated — the new
`EM_ETGO_ISCUSTOMER`/`EM_ETGO_ISVENDOR` columns, AD metadata, `ETGO_SF_FIELD` rows and
reference/sample data ship unconditionally. Full reference: [`neo-headless.md`](neo-headless.md)
§5.3.6.

**Targeted per account, like `bp-portal-link`.** The backend always reads it through
`ContactTypeRules.isEnabled()`, which evaluates `FeatureFlagContext.forAccount(<ETGO_ACCOUNT email
of the request's user>)` plus `clientId` (the tenant). The email comes from
`GoAccountResolver.currentAccountEmail()` — the same resolver `/sws/neo/session` and `bp-portal-link`
use — and REST single, REST batch and MCP all bind the authenticated AD user, so the same person gets
the same answer on every channel. A user with no account behind it is evaluated with an account-less context: it matches no
per-account rule and gets the flag's default (catch-all) value — off, unless the flag is on for
everyone (hosted default `true`, or the local `ETGO_FLAG_CONTACT_CATEGORY_TYPE` / property). Per-account
targeting needs a **hosted arm** (Datadog, or ConfigCat while it is the fallback); where none is
selected the flag degrades to the local per-environment boolean above, which can only answer
"everyone" or "nobody".

**The web client reads it too** (`CONTACT_CATEGORY_TYPE` in the functional repo's `flag-keys.js`:
the Contact Category window and the contacts import), so this is a two-ended flag and the two ends
must agree. They agree on a rule written against the **account email**: the web client puts
`/sws/neo/session`'s `accountEmail` (the same `GoAccountResolver` resolution) lowercased into its
`email` attribute, which ConfigCat maps to *Email* and Datadog matches as `email`; the backend
publishes the same address as `Email` (as stored) and `email` (lowercased). Rules that do **not**
agree, so do not write them for this flag:

- **Identifier / targeting key, and percentage rollouts** — the web client's targeting key is the
  `ETGO_ACCOUNT` *id*, the backend's is the account *email*, so the two bucket differently.
- **Per-tenant rules** — the tenant reaches the web client as `account_id` and the backend as
  `clientId`; a tenant rule must name both attributes.
- **ConfigCat Email with upper-case letters** — the web client lowercases it, the backend sends it as
  stored; write the rule value in lowercase (Datadog already compares the lowercased `email`).

It also decides a new tenant's default contact category: on, the onboarding makes
"Cliente/Proveedor" the default (`OnboardingDefaultContactCategoryService`, read at hand-over, so
pool tenants built while it was off still get it when claimed); off, the dataset's "Cliente" stays
default. The hand-over runs as System, so it is evaluated for the **account the tenant is handed
to** (`ContactTypeRules.isEnabled(accountEmail, clientId)`), not for the current user. Tenants
created while it was off are never revisited. See [`onboarding-flow.md`](onboarding-flow.md)
"Default contact category".

**Before targeting anyone in an environment, the contact-category data-fixes R55 and R56 must have
run there** (`R55-bp-category-customer-vendor-flags`, `R56-bp-category-custom-flags-from-contacts`,
in the functional repo's `cli/src/data-fixes/sql/`). Categories created before ETP-5791 have neither
`EM_ETGO_ISCUSTOMER` nor `EM_ETGO_ISVENDOR` (`N`/`N`); with the flag on, the "at least one type" rule
rejects saving such a category, and a new contact in it inherits nothing — a targeted user would be
left with categories they cannot save until someone sets their type.

### `billing-reconciliation-schedule` (ETP-5048) — backend-only, strictly off by default

Whether the billing reconciliation (`ETGO_BillingReconciliation`) runs on a schedule.

- **Production schedules it by hand.** Production is updated by delta and never restarted, so no
  startup hook runs there: turn the flag on and create a Process Request for **Billing
  Reconciliation** (client System, timing *Schedule*, hourly), like Demo Trial Notices.
- **Environments that restart:** while the flag is off, `BillingReconciliationScheduleStartup`
  plants no process request; turning it on takes effect at the next restart (hourly by default,
  `etendo.go.billing.reconcile.interval.minutes`). A fixed request an operator unscheduled is left
  as it is, never planted again.
- **The flag gates every scheduled run** (timing *Schedule*), the planted one or one an
  administrator created. While it is off such a run does no work and ends as an **ERROR** in
  Process Monitor, saying to turn the flag on or unschedule the request; the first such run of each
  UTC day also sends one internal alert (`billing-reconcile-schedule-flag-off`).
- A run started from the Classic menu **Billing Reconciliation**, or a Process Request run
  immediately or later once, is never gated.

Environment level, evaluated with an empty context; read on every scheduled run, so turning it off
(ConfigCat, live within one poll) stops the scheduled work without a restart. Its callers are
`BillingReconciliationScheduleStartup` and `BillingReconciliationProcess`. Operating the
reconciliation (scheduling, settings, alerts, procedures):
[`billing-reconciliation.md`](billing-reconciliation.md). Design of record:
[`plans/2026-10-18-etp-5048-billing-reconciliation-design.md`](plans/2026-10-18-etp-5048-billing-reconciliation-design.md).

### `usage-aggregation-schedule` (ETP-5051) — backend-only, strictly off by default

Whether the usage aggregation (`ETGO_UsageAggregation`, menu **Billing Resource Usage
Aggregation**) runs on a schedule (`open-and-notable-topics.md` §5.15). The same shape as
`billing-reconciliation-schedule` above:

- **Production schedules it by hand.** Turn the flag on and create a Process Request for **Billing
  Resource Usage Aggregation** (client System, timing *Schedule*, every two hours) in the same step.
  A request firing while the flag is off does no work; a flag on with no request does nothing.
- **Environments that restart:** while the flag is off, `UsageAggregationScheduleStartup` plants no
  process request; turning it on takes effect at the next restart. The interval is
  `etendo.go.usage.schedule.interval.minutes` / `ETGO_USAGE_SCHEDULE_INTERVAL_MINUTES` (default
  **120**, at least 15), read when the request is first planted. A fixed request an operator
  unscheduled is left as it is, never planted again.
- **The flag gates every scheduled run** (timing *Schedule*), the planted one or one an
  administrator created. While it is off such a run does no work and ends as an **ERROR** in
  Process Monitor, saying to turn the flag on or unschedule the request. Unlike the reconciliation
  it sends no internal alert.
- A run started from the Classic menu, or a Process Request run immediately or later once, is never
  gated.
- **Lag warning.** A `[USAGE-LAG]` WARN (at most one an hour) is logged when the last successful
  run in `ETGO_USAGE_RUN_LOG` finished more than twice the cadence ago, or never: at the start of
  each scheduled run, and whenever a plan quota reads the stored aggregate. A run that counted
  nothing for any tenant writes no run-log row, so an installation with no usage at all reads as
  "never ran".

Each run still writes one transaction per resource-day; one transaction per tenant is ETP-5905
(§5.17). Environment level, evaluated with an empty context; read on every scheduled run, so
turning it off (ConfigCat, live within one poll) stops the scheduled work without a restart. Its
callers are `UsageAggregationScheduleStartup` and `UsageAggregationProcess`. What the
schedule feeds — plan quotas on the stored aggregate — and how stale they may get:
[`plan-quotas.md`](plan-quotas.md) §5.

## 2. The onboarding paywall

### Local pooled failure fixture

The provisioning failure E2E fixture is available only when
`etendo.go.runtime.environment=local` (or `ETGO_RUNTIME_ENVIRONMENT=local`).
`POST /sws/go/dev/provisioning-failure-fixture` creates a paid test checkout and
reserves a dedicated pooled tenant. If no reusable fixture tenant exists, it provisions one with
the normal pool provisioner. No client ID or pool feature flag is required. The fixture pool row
has an `E2E fixture:` marker; normal pool claims exclude it. The returned `requestId` is used as
`paymentToken` in the real onboarding request, which claims only that reserved tenant and fails
during its finalization. A new provisioning version replaces the dedicated fixture tenant; the
previous fixture pool row becomes `STALE` only after the replacement is reserved. Its client
(whose admin remains inactive) is retained for explicit operator cleanup. Repeated E2E runs on one version reuse the same
dedicated tenant. `DELETE /sws/go/dev/provisioning-failure-fixture/{cleanupToken}` deletes
the test checkout and restores the dedicated tenant to `READY` for reuse. Both routes require the
authenticated fixture owner. These routes are rejected outside `local`.

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
   reconciled), not a new environment, so it is not charged again. Since ETP-5548 "targets" means
   the half-built client this same attempt left under its provisioning name — never a finished
   environment that happens to share the company name.
4. **Otherwise → the payment decides:** a `CheckoutRequestStore`-confirmed payment (an
   `ETGO_CHECKOUT_REQUEST` row at `PAID` or later) ⇒ allowed; token absent ⇒ `PAYMENT_REQUIRED`;
   token present but unconfirmed ⇒ `PAYMENT_DECLINED`. Both refusals answer 402 with
   `error: payment_required`, differing only in `message`.

Ownership is counted with `EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail`, which reuses the
same username-match rule as `GET /sws/go/environments`.

The hosted checkout entry point also requires a server-marked owner. `POST
/sws/go/checkout/sessions` returns HTTP 403 with `BILLING_OWNER_REQUIRED` when the authenticated
account only has invited memberships or has no owner record. The check reads `AD_User.EM_ETGO_Is_Owner`
through `OwnerSupport`; an administrator role name or an email match is not sufficient. Invitation
access remains independent because environment discovery and NEO entry continue to evaluate the
destination membership separately.

The demo trial lasts **14 days** (product definition, ETP-5548), counted from the
`ETGO_DemoTrialStartedAt` stamped when the demo becomes ready. Override it per instance with
`etendo.go.demo.trial.days` (`ETGO_DEMO_TRIAL_DAYS`). The expiry is computed from the configured
length on every read rather than stored, so changing it — including the 15 → 14 change — moves the
deadline of existing demos too, not only new ones. The renewal grace window is separate:
`etendo.go.billing.grace.days` (`ETGO_BILLING_GRACE_DAYS`), 15 days.

### Frozen demo trial (ETP-5847)

A demo that must never expire (a sales demo) gets the client preference
`ETGO_DemoTrialFrozenDays`, set by hand in Classic. Its value is the number of days left the demo
always shows: `14` on a demo created today keeps showing 14.

- **How it works:** `TenantEnvironmentLifecycleService.resolve` reports a trial start that moves
  with the clock (`now - (trial days - frozen days)`). The access check, the trial bar of
  `GET /sws/go/environments` and the HubSpot sync all read that snapshot, so the demo is never
  expired and they all agree. `trialStartedAt` in the API therefore moves too.
- **The stored start is kept:** `ETGO_DemoTrialStartedAt` is never rewritten. Removing (or
  deactivating) the preference resumes the trial from its real start, so an old demo is expired at
  once.
- **Only the trial:** a demo associated with a productive environment
  (`ETGO_AssociatedProductiveClientId`) stays blocked. To keep a demo next to its productive,
  remove that preference from the demo as well.
- **Notices and periods:** the daily *Demo Trial Notices* pass sends a frozen demo no notice (its
  start moves, so the notice key would change every day) and opens its never-opened fiscal periods
  through the end the trial shows, in the owner's default role and organization. Without that pass
  scheduled, the demo can post only within the periods the onboarding opened.
- **Invalid values:** anything but a whole number of 0 or more is ignored with a warning in the
  log, and the trial runs as usual.

### Trial notices (ETP-5453)

The owner of a demo is emailed before the trial ends and once it has ended, by the background
process **Demo Trial Notices** (`ETGO_DemoTrialNotices`, `DemoTrialNoticeProcess`).
It is **scheduled by hand**, once per instance: in Classic, as System Administrator, open
**Process Request**, create a record for *Demo Trial Notices* with timing *Scheduled*, frequency
*Daily* and a morning start time, and press *Schedule Process*. Nothing schedules it at startup on
purpose: production is updated by delta and never restarted, so a startup hook would never run
there. An instance where nobody scheduled it sends no notices at all.

- **Who is checked:** every active client carrying `ETGO_DemoTrialStartedAt` or
  `ETGO_LegacyTransitionStartedAt`. `DemoTrialNoticePlanner` decides from
  `EnvironmentAccessPolicy`, so the email and the access gate cannot disagree about when a trial
  ends.
- **What is sent:** a reminder when the days left (rounded up, as the picker shows them) fit a
  threshold — `etendo.go.demo.trial.reminder.days` (`ETGO_DEMO_TRIAL_REMINDER_DAYS`), default `3,1`
  — and the expiry notice once the decision becomes `DEMO_TRIAL_EXPIRED`. The smallest fitting
  threshold wins, so a missed run is caught up the next day. The expiry notice goes out only during
  the first 3 days after the end: older demos are never mailed out of the blue, including on the
  first run after a deploy.
- **Who is never notified:** productive environments, inactive clients, tenants with no lifecycle
  metadata, demos with a frozen trial (see above), and any demo associated with a productive
  environment. A demo is never converted:
  paying creates a new productive environment and the old demo is cut at once, so its owner must not
  be told that the trial "ended".
- **Recipient and language:** the platform account (`ETGO_ACCOUNT`) behind the client's owner
  (`AD_User.EM_ETGO_Is_Owner`), in the client's language (`AD_Client.AD_Language`). Onboarding does
  not set that column today, so the notices currently go out in Spanish, the email fallback.
- **Idempotency:** contract `demo-trial-notice`, keyed on client, trial start and milestone
  (`reminder-3`, `reminder-1`, `expired`) in `ETGO_Email_Safety`. A re-run, a restart or a retry
  sends nothing new; a trial restarted with a new start is notified again.
- **Best effort:** each environment commits on its own. A failed send is logged, counted in the
  run's result (a warning, not an error) and retried on the next run.
- **Not callable from the browser:** the contract accepts only a System-context caller and
  re-derives the owed notice itself; the command carries nothing but `clientId`.

Local verification, case by case: `etendo_schema_forge/docs/etp-5396-local-lifecycle-tool.md`.

For legacy free tenants, enforcement is opt-in through `etendo.go.demo.transition.activation.at`
(`ETGO_DEMO_TRANSITION_ACTIVATION_AT`), an ISO-8601 UTC instant selected during rollout. The first
lifecycle read persists that instant per tenant as `ETGO_LegacyTransitionStartedAt`; the configured
trial duration then determines the deadline. With no activation instant configured, legacy tenants
remain unresolved for a deliberate, reviewable rollout rather than receiving a guessed deadline.

The account-level billing projection is available at `GET /sws/go/billing/overview`, and an
individual purchase can be read at `GET /sws/go/billing/purchases/{purchaseId}`. Both responses are
scoped to the authenticated account and expose only the local purchase status, environment name,
and safe provisioning reference. They do not expose Stripe customer/session identifiers or create
a second payment ledger.

**The billing offer (ETP-5049).** `GET /sws/go/billing/offers` quotes the plan catalog's default
plan (`PlanCatalogService#findDefaultPurchasablePlan`), from the Stripe Price checkout charges for
it:

- while the legacy price fallback is active, the grandfathered `legacy-productive` plan at the
  configured `CheckoutConfiguration.priceId()` — exactly what a checkout that names no plan charges;
- otherwise the first purchasable plan of `GET /sws/go/plans` (cheapest first, the plan the upgrade
  page selects by default) at its own `PROVIDER_PRICE_ID`.

Both branches use the predicates checkout decides on, so the offer never quotes a plan checkout
would refuse. The default matches the first `/plans` entry **only while that plan can be quoted**:
`/plans` leaves out a plan it cannot quote (the grandfathered plan when Stripe cannot price the
configured legacy id, a catalog row with no display price or currency) and lists the next one,
whereas the offer does not skip ahead — it quotes the default plan live from Stripe and answers 503
when Stripe cannot price it. Amount, currency, and recurring interval come from the chargeable price itself:

```json
{ "code": "productive-tenant", "planKey": "productive-monthly",
  "amountMinor": 2900, "currency": "EUR", "interval": "month" }
```

`planKey` (added in ETP-5049) is the key a checkout for the quoted plan names. The response carries
no provider price id (removed in ETP-5049; nothing read it). The endpoint returns
`503 BILLING_OFFER_UNAVAILABLE` when nothing is for sale, when Stripe cannot be reached, or when the
price is missing, inactive, or has an unsupported shape; it never shows an independent configured
price as a fallback. Before ETP-5049 the offer always quoted `CheckoutConfiguration.priceId()`, so
after the cutover it disagreed with the charged price (`open-and-notable-topics.md` §2.1).

> **No frontend caller.** Since the ETP-5049 frontend slice the upgrade page quotes the plan catalog
> (`GET /sws/go/plans`) only and no longer calls this endpoint (`getBillingOffer` was removed from
> `lib/upgrade/api.js`). The endpoint is kept server-side for any other billing consumer; removing it
> is a separate decision.

**No browser-facing response carries a provider price id.** The `POST /sws/go/checkout/sessions`
and `POST /sws/go/billing/purchases` answers (`requestId`, `checkoutUrl`, `mode`, plus the selection
fields each endpoint adds) no longer carry the `priceId` they had until ETP-5049
(`open-and-notable-topics.md` §4.8). The charged price id stays on the checkout request row.

**The subscription page (`GET /sws/go/billing/subscription`).** Besides the live Stripe detail
(`plan`, `amountMinor`, `currency`, `status`, `renewalAt`, `cancelAtPeriodEnd`), the stored grace
state (`graceEndsAt`, `graceDaysRemaining`) and `clientId`, the answer carries the plan catalog view
of the subscription (ETP-5049, additive):

```json
{
  "hasSubscription": true,
  "plan": "Etendo GO Productive", "amountMinor": 2900, "currency": "EUR", "status": "active",
  "renewalAt": "2026-11-23T00:00:00Z", "cancelAtPeriodEnd": false,
  "graceEndsAt": null, "graceDaysRemaining": 0, "clientId": "4B1E…",
  "planKey": "productive-monthly",
  "planName": "Productive",
  "planQuotas": [
    { "resourceKey": "POSTED_SALES_INVOICES", "resourceName": "POSTED_SALES_INVOICES",
      "unitLabel": "Inv", "includedQty": 500, "enforcementMode": "warn", "warningThreshold": 80,
      "period": "year", "consumptionSource": "aggregate", "consumed": 412, "verdict": "WARN",
      "periodStart": "2026-04-23", "periodEnd": "2027-04-23" }
  ]
}
```

**Which subscription the page shows (ETP-5051, O-1).** An account can own several environments,
each paid by its own Stripe subscription and customer. The page and the Customer Portal
(`POST /sws/go/billing/subscription/portal`) resolve the purchase through the one servlet helper
`findShownSubscription`, so the portal always acts on the subscription the page shows:

- **Session entered into an environment** (the cookie session's client, written by
  `POST /sws/go/session/environment`): that environment's own billable purchase,
  `CheckoutRequestStore#findSubscriptionForAccountClient` — the newest row of this account whose
  `CREATED_CLIENT_ID` is the entered client and that carries both Stripe ids. Plan, `planQuotas`
  and their consumption, grace, price and `accessEnforced` all describe the environment the user
  is in. When the account has no such purchase (a demo, or an environment it was only invited
  into), the page answers `{"hasSubscription": false}` and the portal `404 NO_SUBSCRIPTION`: it
  never falls back to another environment's subscription, which was the bug (inside SPRING7 the
  quota banner's "Ver el uso del plan" opened SPRING8's plan and quotas).
- **No environment entered** (account-level session, or the legacy platform-token bearer, which
  carries no environment): the account's newest billable purchase
  (`CheckoutRequestStore#findSubscriptionForAccount`), as before.

Ownership is the query's: both lookups filter on the account id and email, so a client id that is
not one of the account's own purchases resolves to nothing, whoever owns that client.

**The portal for a named environment (ETP-5051, O-1b).** "Mis empresas" manages each row's own
environment, whatever environment the session is in, so the portal takes an optional JSON body:

```json
{ "clientId": "<AD_CLIENT_ID of the row>" }
```

- **`clientId` present** (non-blank and not `"0"`): `findSubscriptionForAccountClient(account id,
  email, clientId)`, the same ownership-carrying query. A client the account did not buy, or a
  purchase without a Stripe customer, answers `404 NO_SUBSCRIPTION`, as before.
- **No body, an empty body, or no `clientId`** (absent, JSON `null`, blank or `"0"`): the rule above (`findShownSubscription`). The
  `/account` Subscription section keeps sending no body.
- **A body that is not a JSON object:** `400 "Invalid JSON body"`, the servlet's usual envelope,
  with nothing looked up.
- The response is unchanged (`{"url": "…"}`), and so are the credential rules: the body is read
  only after the account is resolved under `PLATFORM_CREDENTIAL_ONLY`, and a cookie-session POST
  still needs its `Origin` and `X-Go-CSRF` proof.

- **`planKey` / `planName`** come from the tenant's `ETGO_SUBSCRIPTION` row (open, else the latest
  closed one — `SubscriptionService#findLatest`, the row the lifecycle reads) and its `ETGO_PLAN`.
  `plan` stays the live Stripe product name. Both are JSON null when the tenant has no subscription
  row — no environment yet, or a tenant still answered by the retired `ETGO_TenantPlan` preference —
  never `"free"`, which would name a catalog row that does not exist.
- **`planQuotas`** lists the plan's active `ETGO_PLAN_QUOTA` rows, ordered by resource key. It is
  informational — what the plan includes, not what has been used. `resourceKey`, `resourceName` and
  `unitLabel` come from `ETGO_BILLING_RESOURCE`; `includedQty`, `enforcementMode`
  (`warn`/`block`/`off`) and `warningThreshold` (percent, may be null) from the quota row; `period`
  is the quota's consumption window: `year` whenever the subscription row has a `START_DATE`, since
  plan limits count over the subscription year whatever the billing interval (a monthly plan,
  as in the example, still reports `year`); for a row without one, the plan's billing interval
  (`month`/`year`, null when the plan has none), over which it then counts
  (`open-and-notable-topics.md` §5.5, §5.8; `plan-quotas.md` §1). ETP-5051 adds the measurement:
  `consumptionSource`, `consumed` and `verdict` (null when unmeasured), and `periodStart` /
  `periodEnd`, the **counted window as date-only strings** (`yyyy-MM-dd`, server-local days): the
  first counted day and the exclusive end day, so consumption covers `[periodStart, periodEnd)`.
  They are not the subscription's UTC instants, which can name the day before (a period starting
  at 00:30 CEST is 22:30 UTC the previous day). Null with no current period. It is empty when there
  is no plan **and** when the plan has no quota rows — with a `planKey`, empty means **unlimited**
  (§5.1).
- **`graceEndsAt`** is computed by `EnvironmentAccessPolicy.graceEndsAt`: the stored renewal due
  date plus `etendo.go.billing.grace.days`, for a `PAST_DUE` subscription only. The access policy
  and the per-environment `graceEndsAt` of `GET /sws/go/environments` use the same method, so the
  page, the banner and the lockout cannot drift apart — except that the kill switch can cancel the
  lockout without the page or the banner knowing (`open-and-notable-topics.md` §4.17).

> **UI consumer since ETP-5051.** The `/account` Subscription section (`SubscriptionSection`,
> schema_forge) shows `planName` (falling back to the live Stripe product name `plan`) and every
> `planQuotas` item with its consumption, verdict and period, and switches the grace countdown to
> neutral text when `accessEnforced` is `false`
> (`schema_forge/docs/generated-custom-windows/app-shell-functional-flows.md` §3.2a-quater).
> `planKey` still has no UI consumer. Operating quotas: [`plan-quotas.md`](plan-quotas.md).

`POST /sws/go/billing/purchases` also checks the durable request table for an active purchase with
the same account and environment name. A duplicate submission returns the existing purchase only
when it matches the original immutable demo selection; a conflicting choice returns HTTP 409. A
retry cannot change the source during a payment/webhook race or create a second provider checkout.
An unresolved `CREATING` row therefore remains visible for reconciliation rather than being silently
replaced.

For an existing unpaid request, checkout reuses its stored Stripe Price ID. If its recorded Stripe
Checkout Session is still open, the same session URL is returned. A replacement session is created
only after Stripe confirms that the previous session expired, using a stable idempotency key tied to
the expired session; the replacement ID is then saved on the same request. This prevents two active
sessions from charging the same purchase and preserves the original price and environment choice.

Demo selection is valid only when the request is made from an authenticated free/demo environment
linked to the account. A productive environment cannot supply a demo selection, nor be one — a
canceled one included, although its plan reads free (ETP-5047, below). When one or more
free/demo environments are linked to the account, include the chosen environment's
`AD_CLIENT_ID` as `demoClientId` in the checkout request. Selection is required in this case. The
backend validates that it is a free/demo environment
linked to the authenticated account and stores it on the checkout request before redirecting to the
payment provider. The account-scoped purchase response returns the same `demoClientId`, while
`GET /sws/go/environments` returns each environment's `clientId`. The UI can therefore keep the
selection synchronized by matching these IDs; names are for display only. Onboarding uses the ID
saved with the paid request and ignores a `demoClientId` sent in its body. New checkout rows mark
the selection as recorded even when the user has no demo to select, so a later retry cannot mistake
that deliberate empty choice for an older checkout.

When a purchase starts from an authenticated productive environment, the backend derives the
current client from the authenticated session or signed environment token and verifies that the
account owns it. That purchase records an explicit empty demo selection and ignores browser-supplied
demo and transfer choices. It creates a separate productive environment: no demo is inferred,
associated or revoked, and no company profile or demo data is copied. A demo-to-productive conversion
continues to require the explicit demo selection described above.

**A demo is the origin of at most one productive environment (ETP-5548, product decision).** The
first paid environment created from a demo associates it (`ETGO_AssociatedProductiveClientId` on
the demo) and revokes its access. From then on the demo is spent as a source:

- `GET /sws/go/environments` reports `associatedWithProductive: true` for it, and the upgrade page
  leaves it out of the source picker and does not prefill the new name from it.
- A purchase whose session is in that demo is treated like a productive-origin purchase: it records
  an empty selection and ignores any `demoClientId` / `dataTransfer` in the body. The result is a
  clean productive environment, with no origin.
- Naming a spent demo as `demoClientId` from another demo session answers 400
  `INVALID_DEMO_SELECTION`, like a demo that is not free or not owned.
- A purchase that recorded the demo **before** it was spent (two purchases started from the same
  demo, the other one finished first) is already paid, so onboarding does not refuse it: it drops
  the source and provisions a clean productive environment — no association, profile copy,
  revocation or data transfer.

**A productive environment is never a source, even when its plan reads free (ETP-5047).** A
canceled subscription closes the tenant's row, and a closed row reads plan `free`
(`TenantPlanService#resolvePlan`), so the plan alone would let a canceled productive tenant pass for
a free demo. Source eligibility therefore also asks
`TenantEnvironmentLifecycleService#isProductiveEnvironment`, which reads the lifecycle type the same
way `resolve` does: any active subscription row, open or closed, makes the tenant productive (both
reads of `SubscriptionService#findLatest` ignore an inactive row); without one,
the `ETGO_EnvironmentType = PRODUCTIVE` marker or the retired `ETGO_TenantPlan` preference does, so
the check holds while `ETGO_SubscriptionModel` is OFF too.

- The rule excludes what is positively productive; it does not require a demo marker. A tenant that
  predates the lifecycle metadata (no row, a missing or blank `ETGO_EnvironmentType`, free plan) stays
  a valid source.
- It is eligibility, not access: it never reads the access decision, so the access-enforcement kill
  switch does not change it.
- A purchase whose session is in a canceled productive environment is treated like any
  productive-origin purchase: no `DEMO_SELECTION_REQUIRED`, any `demoClientId` / `dataTransfer` in
  the body is ignored, and a clean productive environment is provisioned. Naming such an environment
  as `demoClientId` from a demo session answers 400 `INVALID_DEMO_SELECTION`.
- The account's source candidates (`EtendoGoJwtDalHelper#findFreeTenantIdsByAccountEmail`, used by
  the paid onboarding re-check and by the data-transfer status and retry fallbacks, both through
  `findOnlyFreeTenantIdByAccountEmail`) leave it out.
- A purchase that recorded a productive environment as its source before this rule existed is
  already paid, so onboarding does not refuse it: like a spent demo, it drops the source and
  provisions a clean productive environment.
- **It fails closed on the type reads only.** When the `SubscriptionService#findLatest` read or the
  `ETGO_EnvironmentType` read throws, the answer is "productive" (not a source): the worst outcome
  is a clean environment, never the association of the wrong tenant. A genuine demo whose read
  fails is dropped as a source without a message — accepted 2026-10-14 (Martin Taal),
  `open-and-notable-topics.md` §4.14. A failure inside the retired `ETGO_TenantPlan` fallback
  (`TenantPlanPreferenceFallback#isProductive`, via `TenantPlanService#resolvePlanWithoutSubscription`)
  is swallowed there and answers `free`, so the tenant counts as **not** productive — fail-open,
  accepted the same day; the fallback retires in Phase F.
- `GET /sws/go/environments` keeps reporting the facts: `plan: "free"`, `environmentType:
  "PRODUCTIVE"`, `subscriptionStatus: "EXPIRED"`. The upgrade page keys its picker on
  `environmentType`, not only on `plan`.

**Only the demo's owner may give it away (ETP-5743).** An invited member has an environment user
in the owner's demo, so matching the account email alone is not proof of ownership: the purchase
copies the demo's profile and data and then locks it. Every purchase-source check therefore
requires the account's user in that demo to carry `AD_User.EM_ETGO_Is_Owner = 'Y'`
(`EtendoGoJwtDalHelper.clientOwnedByAccountEmail`):

- `GET /sws/go/environments` reports `ownedByAccount: false` for a demo the account was only
  invited into, and the upgrade page leaves it out of the source picker.
- A purchase whose session is in such a demo is a clean productive purchase, exactly like one from
  a spent demo: the body's `demoClientId` / `dataTransfer` are ignored.
- Naming such a demo as `demoClientId` answers 400 `INVALID_DEMO_SELECTION`.
- Onboarding resolves a paid purchase's recorded source against the account's **owned** free demos
  only, so a selection recorded before this rule is refused with `DEMO_SELECTION_REQUIRED` (409)
  instead of copying another owner's demo.
- A tenant created before the owner flag existed (ETP-4830) has no owner at all. For those the
  account email match is still accepted, until a backfill assigns an owner.

Paid onboarding uses `PROVISIONING_ATTEMPTS` as a durable fencing token. The claim is normally
taken from `PAID`; if the row has remained `PROVISIONING` longer than
`etendo.go.billing.provisioning.lease.minutes` (`ETGO_BILLING_PROVISIONING_LEASE_MINUTES`), it is
reclaimed, its attempt number is incremented, and its timestamp is renewed. The initial lease is
30 minutes. Completion is an atomic status update guarded by that attempt number, so an old worker
cannot close a request after a retry has taken over. This makes browser refreshes, process restarts,
and stale workers recoverable without a second payment.

The selected `demoClientId` is read from that same account-scoped paid request before a provisioning
claim starts. Every retry therefore associates and copies data from the same demo, even if the
account has created or selected another environment since the original checkout. A recorded empty
selection also stays empty on retry. Older checkout rows without a saved demo selection resume the
original purchase and checkout session; provisioning creates a new productive environment without
demo profile/data copy, demo access revocation, or transfer. Reopening an existing purchase resumes
it silently instead of asking the user to choose a demo again. Onboarding does not infer a demo for
that purchase. If setup is already running, the response says to refresh its status before trying
again. Once an attempt is marked failed, retrying the same paid request is allowed; a stale worker
cannot mark the newer attempt complete.

The paid flow starts the durable transfer after provisioning commits. Active products, their
current cost and their prices, and active contacts are copied under target client references;
global units and tax categories remain global references. Only prices on the demo's **default**
sales and purchase price lists are migrated (the newest version of each), onto the target's
default lists; prices on any other list are left behind. A contact address reuses only an
address row already used by another contact, never the organization's fiscal address. A contact
keeps its source currency, else gets the target organization's (ETP-5768): the copy runs as
System and bypasses the NEO hook that defaults it, and Core refuses to complete any document for
a contact without one. Missing required target references fail the job with a visible reason.

Each product and each contact is committed on its own, so the First Steps counters move while
the job runs. A failure therefore rolls back only the item in progress and leaves the earlier
ones copied; a retry re-runs every item through the same upserts (existing target search keys,
price and cost rows are updated, never duplicated), so it completes the job without duplicating
what was already copied.

### Company profile transfer during paid provisioning (ETP-5443)

The billing request stores the selected demo client ID in `ETGO_CHECKOUT_REQUEST.DEMO_CLIENT_ID`
before the browser leaves for checkout. The authenticated account's current free/demo environments
are checked when checkout is created. After payment, onboarding loads that ID from the account-scoped
checkout request and uses it for profile copy, demo access revocation, and optional data transfer.
The onboarding body cannot change the saved choice. A retry therefore uses the same demo even if
the browser has lost state or another demo environment has since been created.

For new purchases, a recorded empty selection means that this purchase has no demo source; onboarding
does not infer one. A saved demo ID that is no longer linked to the account or is no longer free/demo
is rejected before provisioning starts — except a spent demo or a productive environment, which
continue without a source (see *A productive environment is never a source* above). Legacy purchases with no saved selection marker resume the
original purchase/session and provision without associating, copying, revoking, or transferring from
a demo. Reopening those purchases does not prompt for a new demo choice.

The selected demo's unique active business organization is the profile source. The profile is copied
to the exact organization created for the productive client in this paid onboarding attempt. If that
source has zero or multiple active business organizations, paid provisioning fails rather than
silently continuing with a partial profile.

When a source organization exists, the copied profile comprises its business type
(`AD_Org.ETGO_Business_Type`), its legal name (`AD_Org.Social_Name`) only when the user edited it in
the demo, its tax ID and fiscal
address including its country (`AD_OrgInfo.TaxID` and the linked location); and company logo
(`AD_OrgInfo.Your_Company_Document_Image`). The logo is copied to a new `AD_Image` row owned by the
target client, so the productive organization does not depend on an image row owned by the demo
client. The target is identified by the productive client created in this paid onboarding attempt,
not by organization name. This keeps the profile on the intended target even when another
organization has the same name.

The organization **name** is never copied (ETP-5548): the productive environment keeps the name the
user typed on the purchase. Copying it made a renamed purchase come out with the demo's name, since
onboarding names the organization after the company. For the same reason the legal name is copied
only when it differs from the demo's organization and client names — that is the value onboarding
seeds, not a legal identity the user chose, and carrying it over would print the old name on the
new environment's documents.

After a paid productive environment created from an explicitly selected demo has been provisioned
successfully and the demo environment has been associated with it, the account loses access to that
demo environment. This does not revoke access to the successfully provisioned productive
environment. An independent purchase from an existing productive environment has no demo to
associate or revoke.

When a source demo organization exists, this profile setup is required for paid productive
provisioning. It does not depend on whether the account selected product or contact transfer. The
target's currency is retained from paid onboarding and its ledger configuration; currency is not
copied from the demo organization.

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

**What is sold comes from the Subscription Plan Catalog (ETP-5046).** The browser names a plan by
`planKey` (listed by `GET /sws/go/plans`), never a price; the server resolves the plan's Stripe
price. While no plan carries a provider price, the **legacy price fallback** keeps selling the
grandfathered `legacy-productive` plan at `etendo.go.checkout.price.id`, and the first priced plan
retires it with no redeploy. Rules: `plans/2026-09-18-etp-5046-plan-and-subscription-design.md`
§6–§6.1; operator go-live procedure: §6.2.

### Checkout provisioning status contract

`GET /sws/go/checkout/sessions/{requestId}` is the account-scoped polling contract after the
Stripe return. It uses the checkout request row as the source of truth and returns a derived
`status`, `retryAllowed`, `clientId` when an environment has been linked, `updatedAt` (ISO-8601
instant of the row's last change), and — only for `provisioning_failed` — a `failureCode` plus a
fixed `failureReason` describing that code:

| `status` | Meaning | `retryAllowed` |
|----------|---------|----------------|
| `pending` | Unknown, unpaid, or pre-payment request | `false` |
| `paid` | Payment confirmed; provisioning has not claimed the request | `true` |
| `provisioning` | An active fenced provisioning attempt is running | `false` |
| `provisioning_failed` | The last attempt recorded a diagnostic failure | `true`, unless its `failureCode` is non-retryable |
| `stalled` | The provisioning lease expired without a diagnostic failure | `true` |
| `provisioned` | The request is terminal and carries its created client id | `false` |
| `expired` | ETP-5048: the billing reconciliation closed the request after the provider confirmed its session expired unpaid (`CHECKOUT_STATUS = EXPIRED`); a new purchase starts a new request | `false` |

**Failure codes (ETP-5548).** The raw cause of a failed attempt (exception text, internal ids,
SQL) stays in `ETGO_CHECKOUT_REQUEST.FAILURE_REASON` for operations and is never returned. The row
stores it as `CODE: cause`; the endpoint returns only the code and a fixed description, and the
client localizes the code. The codes, their encoding and their fixed descriptions live in
`com.etendoerp.go.payment.ProvisioningFailureReason`; `CheckoutRequestStore` only consults them:

| `failureCode` | Cause | Retryable |
|---------------|-------|-----------|
| `PROVISIONING_FAILED` | Any failure without a more precise code, including every reason written before codes existed | yes |
| `CLIENT_NAME_IN_USE` | The account already has a productive environment with the purchase's company name (ETP-5548; before it: the name belonged to another account's environment) | **no** |

A non-retryable code fails identically on every attempt — the checkout fixes its company name — so
`retryAllowed` is `false` and `claimForProvisioning` refuses to reclaim the row even for a caller
that ignores the flag. Such a purchase needs support. The same collision is refused **before
payment** by `POST /sws/go/checkout/sessions` and `POST /sws/go/billing/purchases` (409
`CLIENT_NAME_IN_USE`, provider never contacted); the provisioning-time code covers the race of a
name taken between payment and setup.

**Company names are not unique (ETP-5548).** Only one rule applies to the name: an account never
has two productive environments with the same company name (case and blanks ignored). Another
account's environment, or this account's demo, may share it. A purchase always creates a new
productive environment — it never resumes, and so never converts, an existing one because the
names match: the classic path builds the client under a provisioning name tied to the checkout
request and applies the company name only at the end (`docs/onboarding-flow.md`, Overview).

The recorded cause is the one the customer was shown: a step that fails gracefully writes its own
NDJSON `result` line (message and optional `code`) and returns `false`. The onboarding handler
promotes that `false` to its rollback path but does **not** write a second, generic result, which
would replace the real one in the client — the client keeps the last `result` line
(`OnboardingStreamWriter` records the first one).

Retrying always reuses the same `requestId`. `claimForProvisioning` advances the attempt token
atomically; an active `PROVISIONING` row and a terminal `PROVISIONED` row cannot be claimed again,
and a failed or stale attempt can be reclaimed with a new fencing token. This prevents a browser
retry from creating a second environment or charging the customer again.

The token is server-generated and correlated server-side, so a browser cannot turn a successful
return URL into authorization. `CheckoutRequestStore.isPaidFor` matches on the request id **plus**
the account email **plus** the environment name (the paywall always passes one; the status endpoint
passes `null` and matches on request id and account only); a confirmed payment therefore cannot be
redirected to another account or another environment.

> **Closed by ETP-5045 — payment state and webhook idempotency are durable.** The former
> `CheckoutPaymentRegistry` (a static `ConcurrentHashMap` per JVM) is retired. Both halves of the
> state it held now live in the database, so a task recycle, a redeploy or a second node between
> the payment and the onboarding call no longer loses a paid request or reprocesses a retried event:
>
> - **`ETGO_CHECKOUT_REQUEST`** (`CheckoutRequestStore`) — one row per checkout attempt, written
>   before Stripe is contacted and advanced forward-only through
>   `CREATING → CREATED → PAID → PROVISIONING → PROVISIONED`. `isPaidFor` reads it.
> - **`ETGO_BILLING_EVENT`** (`BillingEventStore`) — one row per provider event id. The unique
>   constraint `ETGO_BILLEVT_EVENT_UQ` on `EVENT_ID` *is* the idempotency gate: the first delivery
>   inserts the row, every later delivery hits the constraint and only increments
>   `DUPLICATE_COUNT` / `LAST_DUPLICATE_AT`. `EVENT_RESULT` moves `RECEIVED → APPLIED | IGNORED |
>   FAILED`, and the three end states are deliberately not equally locked:
>   - **`APPLIED` is terminal and enforced as such.** Every write of another result carries an
>     `eventResult <> 'APPLIED'` guard, so a late failure on a redelivery cannot reopen an event
>     whose payment was already recorded (spec: `testAppliedIsTerminalAgainstALaterFailureOrIgnore`).
>   - **`IGNORED` is terminal by intent but not locked** — a later `markFailed` does overwrite it,
>     which makes the row re-claimable again. That is deliberate: a later delivery of the same id
>     may carry the correlation the ignored one lacked (spec: `testIgnoredIsNotLockedTheWayAppliedIs`).
>   - **`FAILED` is not terminal at all**: the next delivery flips the row back to `RECEIVED`
>     atomically and re-claims it, so Stripe's own retry repairs a transient handler failure.
>
>   `PROCESSED_AT` is first-write-wins. `REQUEST_ID` always stores the raw `metadata.request_id`;
>   `ETGO_CHECKOUT_REQUEST_ID` is resolved at claim time when that request exists (an event may
>   legitimately reference a request this instance never issued). `PAYLOAD_SUMMARY` is an
>   allow-list (`data.object.{id,customer,subscription,livemode,payment_status,amount_total,
>   currency,mode}` + `metadata.request_id`, expanded objects reduced to their id, at most 2000
>   chars) — never the raw body, never card data. The allow-list itself lives in
>   `WebhookPayloadSummary`, not in the store: it is pure JSON with no DAL, so the generic
>   `EventStore` seam can build a summary without reaching into the concrete store
>   (`BillingEventStore.summarize` is a thin delegate).
> - **`FAILURE_REASON` is not only about failures.** The same column carries the `IGNORED` reason,
>   so most rows in a healthy instance read `unhandled event type` in a column named failure
>   reason. On the genuine failure path it holds a fixed phrase plus the **exception class name
>   only** — never the exception message, because a provider-controlled message can quote payload
>   fragments and this column is required to stay operationally safe. The detail stays in the log.
>
> `EtendoGoJwtServlet.handleCheckoutWebhook` runs `CheckoutWebhookProcessor.evaluate(...)`
> (signature via the unchanged `CheckoutWebhookVerifier`, then payload shape, then the claim) with
> the same wire contract as before: `400 INVALID_CHECKOUT_SIGNATURE`, `400 INVALID_CHECKOUT_PAYLOAD`,
> `200 {"received":true}` for a duplicate. An accepted `checkout.session.completed` /
> `checkout.session.async_payment_succeeded` with `metadata.request_id` + `account_email` calls
> `CheckoutRequestStore.recordPaid` and marks the event `APPLIED`. There are **three** ignore
> reasons, all recorded on the row: `unhandled event type` (any other event type),
> `missing correlation metadata` (no `metadata.request_id` / `account_email`) and
> `unknown checkout request` (the correlation id names no `ETGO_CHECKOUT_REQUEST` this instance
> issued — `recordPaid` reports that back rather than failing). A `RuntimeException` in the handler
> marks the event `FAILED` and answers `500 CHECKOUT_WEBHOOK_FAILED`, which is what makes Stripe
> retry and re-claim it. **`APPLIED` therefore always means a payment was actually recorded**, never
> merely that the handler ran: an event whose request id is unknown is `IGNORED`, so the audit row
> cannot claim an effect that did not happen. Both tables are readable as System Administrator from
> the read-only Classic windows **Checkout Request** (with a **Billing Event** child tab linked
> through that FK) and **Billing Event** (standalone, same menu parent).

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

> **Since ETP-5046 the marker is no longer the source of truth — on an environment that has
> switched to the subscription model.** There a tenant is productive when it has an open
> `ETGO_SUBSCRIPTION` row in status `active` or `past_due`; the `ETGO_TenantPlan` preference
> described below is written only when that subscription write fails, is retired per tenant, and is
> read only as a transitional fallback. Until the operator switches (the System preference
> `ETGO_SubscriptionModel = Y`, see *The subscription-model switch* below), every new purchase still
> writes the marker and opens no row, exactly as before ETP-5046.

A tenant created through the paid flow is marked **productive**; every other tenant is **free**.

Storage is an `AD_Preference` row with attribute **`ETGO_TenantPlan`**, made visible at the tenant's
own client (`com.etendoerp.go.payment.TenantPlanService`). This reuses existing AD metadata — no new
table, column or window — following the same mechanism the module already uses for navigator
favorites and saved filters. The row is created at runtime as ordinary data, so **no
`export.database` is required**.

Absence of the preference means `free`. Every tenant provisioned before this feature, and every first
(unpaid) tenant, reads back as free without a migration.

**Since ETP-5548 the plan record is mandatory, and it is the last write before the commit.** The
plan (since ETP-5046 the `ETGO_SUBSCRIPTION` row, or this marker as the fallback when the row cannot
be opened — see *Transitional read fallback* below), the lifecycle projection
(`ETGO_EnvironmentType=PRODUCTIVE`) and the removal of the tenant's own `ETSG_ForceTestMode` override
are written by `applyPaidUpgradeSideEffects` immediately before the onboarding `commitDalChanges`,
after the organization, dataset and demo-profile steps. A failure of any of the three — for the
plan, neither the subscription nor the fallback marker recorded — aborts the onboarding, so a paid
tenant never commits unmarked or half-marked.

The position matters as much as the obligation. The classic path commits internally while it builds
the tenant (`InitialClientSetup`, `InitialOrgSetup`), and a marker written before those steps was
committed by them: a later failure then left a paid, incomplete environment that read back as
productive and appeared in `/environments` (reproduced on ETP-5548 with a second purchase from an
already-associated demo, a case that now provisions a clean environment instead, see above). Written last, a failed attempt leaves at most an incomplete classic client
**without** productive metadata, which the retry resumes by name.

A classic dataset pass on a paid tenant runs while the plan still reads `free`, so it inserts
`ETSG_ForceTestMode='Y'` like for any free tenant; the override removal at the end flips it to `N`
(firing the fiscal-config cascade) and deletes it, leaving the tenant on the System default. The
pooled path has no internal commits, so it is fully atomic either way.

**Known limit of the classic path:** because `InitialClientSetup`/`InitialOrgSetup` commit, a failed
classic paid attempt leaves the client and organization behind. The client still carries its
provisioning name (`PEND-<requestId without hyphens>`, ETP-5548), which hides it from `/environments` and
is how the next attempt with the same `requestId` finds and reuses it. A client created at or
after the payment of a `PAID`/`PROVISIONING` purchase with the same company name is hidden too
(the window between the commit and the purchase being marked provisioned); an older tenant of
that name — the demo the purchase came from — stays listed.

The write and the read must also agree on the column. `Preferences.setPreferenceValue(...,
isListProperty=false, ...)` stores the key in `AD_Preference.Attribute`, which is what `resolvePlan`
queries; flipping that boolean would store it in `Property` instead, match nothing, and make every
paid tenant read back as free with nothing reporting it. `TenantPlanServiceTest` asserts both halves
for that reason.

A `paymentToken` the webhook confirmed is what makes an environment productive — including for an
account's first environment.

**A purchase provisions a new environment (ETP-5548); a re-subscription does not (ETP-5742).**
`resolveCheckoutSelection` turns a purchase from a productive or spent-demo session into a clean
new environment, and the paid onboarding's `selectTenant` only ever picks a pooled tenant, a new
classic client, or the half-built client this same checkout left under its provisioning name —
never a finished environment. Since ETP-5047 a canceled productive tenant is no longer a demo
source either (§2, *A productive environment is never a source*). Getting a canceled environment
back is the re-subscription below, decided 2026-10-14 (Martin Taal).

#### Re-subscribing a canceled environment (ETP-5742)

The owner of a productive environment whose subscription was **canceled** pays for that same
environment again, with its data. It is not a purchase of a new one.

- **Where.** The blocked screen of a `SUBSCRIPTION_REQUIRED` environment whose `/environments`
  entry reads `subscriptionStatus: "EXPIRED"` (`isCanceledSubscription` in
  `environmentPresentation.js`) says the subscription was canceled. The owner
  (`ownedByAccount: true`) gets **Reactivar suscripción**, which goes straight to Stripe Checkout.
  An invited member is told to ask the owner. A subscription left **unpaid** (`PAST_DUE` past its
  grace) keeps **Gestionar suscripción** → `/account`: the Customer Portal fixes the payment
  method, and a second subscription would bill twice.
- **`POST /sws/go/billing/reactivation`.** The body is empty. The environment is the session's,
  never a request field, and the call is account-level like every other pay endpoint, so a blocked
  session reaches it. It refuses `403 REACTIVATION_OWNER_REQUIRED` unless
  `clientOwnedByAccountEmail` (ETP-5743), and `409 REACTIVATION_NOT_AVAILABLE` unless the lifecycle
  snapshot is `PRODUCTIVE` + `EXPIRED` (a closed `ETGO_SUBSCRIPTION` row and the
  `ETGO_SubscriptionStatus` preference answer alike). It answers like a purchase: `201` with
  `requestId` and `checkoutUrl`. An unpaid re-subscription of the same environment is reopened
  (`200`); one already paid answers `200 {"requestId", "status": "paid"}`. The plan is the canceled
  row's; with no row it is the legacy price fallback, as a purchase naming no plan.
- **The request names its tenant from the start.** `ETGO_CHECKOUT_REQUEST.CREATED_CLIENT_ID` is set
  when the request is recorded. A new purchase sets it only at `PROVISIONED`, so "names a tenant
  and is not provisioned" identifies a re-subscription (`CheckoutRequestStore.isPendingReactivation`)
  with no column of its own. Onboarding never claims one: `claimForProvisioning` requires
  `CREATED_CLIENT_ID is null`.
- **The payment webhook applies it** (`applyReactivationIfPending`, on `checkout.session.completed`).
  It records the payment exactly as a paid upgrade does (`applyPaidUpgradeSideEffects`), then
  closes the request as `PROVISIONED`, which commits both together:
  - **a tenant with a row** — a fresh row opens next to the closed one, and its period is read from
    Stripe afterwards, as after an onboarding;
  - **a tenant with no row** (switch OFF) — the `ETGO_TenantPlan` marker and
    `ETGO_SubscriptionStatus = CURRENT` are written, and `ETGO_SubscriptionEventAt` moves to the
    payment, so a late event of the canceled subscription is stale.
- **A lost payment webhook is repaired by the billing reconciliation** (ETP-5048): it records the
  payment from Stripe and applies the re-subscription through the same method, via
  `PaidReactivationService`. `CheckoutRequestStore.findPaidReactivation` locks and re-reads the
  request, so the webhook and the reconciliation apply it once.

  A failure rolls back with the event: the webhook answers 500, Stripe redelivers, and the request
  is still `PAID` for the retry. The new subscription's own lifecycle events find the tenant
  through the request (`findByStripeSubscription`) or through the open row.
- **Return.** Stripe returns to `/dashboard?reactivation=success&requestId=…` (cancel:
  `reactivation=cancelled`, back to the button). The blocked screen polls
  `GET /checkout/sessions/{requestId}` until `provisioned` and reloads the app. Usually the webhook
  has already applied it and the app simply loads.
- **The canceled environment keeps its name.** The pre-checkout and onboarding name checks
  (`ownsProductiveNamed`) also count a tenant whose lifecycle type is productive. A closed row makes
  its plan read `free`, and without that a new purchase under its name would be charged and then
  refused by the company-name constraint. That check reads `resolve` and fails open; it does not use
  `isProductiveEnvironment`, which fails closed.

The plan record was built for this already:

- a tenant with any `ETGO_SUBSCRIPTION` row, open or closed, is recorded on a row whatever the
  subscription-model switch says (*Only a tenant's first row is switched*, below), so its payment
  never lands in a marker hidden behind a closed row;
- `SubscriptionService#openSubscription` opens a fresh row next to a closed one, and closes a
  canceled open row whose close never arrived before it does
  (`SubscriptionResubscriptionIntegrationTest`; `open-and-notable-topics.md` §3.7,
  "Re-subscribing opens a fresh row").

The one way such a tenant's payment could still reach the marker is a *failed* row lookup with the
switch OFF — an accepted edge, see *Only a tenant's first row is switched*. The re-subscription
does not take it: it asks `hasAnySubscription` itself, unguarded, before recording the payment, so a
failing lookup fails the webhook for a retry instead.

### Transitional read fallback (ETP-5046 cutover) — grep `ETP-5046-TRANSITIONAL-FALLBACK`

Since ETP-5046 the source of truth for "is this tenant paying" is the tenant's **open
`ETGO_SUBSCRIPTION` row** when it has one, not the preference. A tenant without a row — every paying
tenant of an environment that has not switched yet (below), and on a switched one any tenant the
opt-in **R37 backfill** has not reached — would otherwise resolve to `free`: paid features off,
wrong environment ordering, and, once ETP-5047's enforcement lands, denied login. The backfill is
deliberately gated on a human re-verifying that production Stripe checkout has not gone live (if it
has, a real paying cohort exists that the backfill would orphan), so it is opt-in and never part of
a full data-fix run.

#### The subscription-model switch (`ETGO_SubscriptionModel`) — operator steps

Each environment moves to the subscription model **once, at a moment its operator chooses**. Until
then the whole environment — new customers included — keeps the preferences (`ETGO_TenantPlan`,
`ETGO_SubscriptionStatus`/`DueAt`, `ETGO_EnvironmentType`) exactly as develop does, and the
transitional fallback below is what answers for every paying tenant.

`com.etendoerp.go.payment.SubscriptionModelSwitch#isOn` is the single answer. It reads an active
**list** preference at System level — Property `ETGO_SubscriptionModel` (an `AD_Ref_List` value of
`Property Configuration`), `AD_CLIENT_ID = '0'`, no visibility narrower than the system (Visible-at
Client/Organization empty or `0`, no role, user or window) — and is ON only for the value `Y`.
Absent, any other value, a tenant-scoped row or a failed read all mean OFF. It is read on every
purchase (no cache, no restart needed) and only `applyPaidUpgradeSideEffects` consults it: it
switches whether a **new** tenant gets a row, never how a tenant is read — a tenant with a row is
decided by the row whatever the switch says. The module ships the list value but no preference row;
a shipped row would be reset by every `update.database`.

**After installing ETP-5046/5047 nothing changes in how purchases are recorded.** The switch is OFF
(no row ships), and R37 is opt-in (`-- @optIn: true`), so a plain `make data-fixes` never runs it.
At the time of writing (2026-10-14, before ETP-5046 reaches develop) develop has no
`SubscriptionService`, so an environment built from develop has no subscription rows; one that ran
an ETP-5046/5047 feature-branch build may already have some. A tenant without a row keeps writing
the `ETGO_TenantPlan` marker until the operator switches.

Switching, on the target environment — **switch first, then backfill** (order decided 2026-10-14,
Martin Taal):

1. Re-verify the R37 pre-check (production Stripe checkout has not gone live since 2026-08-27,
   recorded in R37's `@report`), and settle the two R37 edge cases of
   `open-and-notable-topics.md` §3.11 there.
2. `make data-fixes` in `schema_forge` — bring every tenant's chain current. R37 is **opt-in**: a
   full run never applies it, and `FIX=` refuses a tenant whose chain is behind R37's date
   (`BLOCKED_CHAIN_BEHIND`, `optInChainGate` in `cli/src/data-fixes/run.js`).
3. Set the System preference in the Classic **Preference** window (System Administrator): Client
   System, **Property List** checked, Property *Billing: new purchases open a subscription row
   (Y/N)* (`ETGO_SubscriptionModel`), Value `Y`, Organization `*`, no role/user/window, **no
   Module**. No restart: it is read on every purchase. It is system-wide; there is no per-client
   form.
4. Run R37 — for the whole environment, or client by client with `CLIENT=<ad_client_id>`; dry run
   first each time:
   ```bash
   make data-fixes FIX=R37-tenant-subscription-backfill [CLIENT=<ad_client_id>] DRY_RUN=1
   make data-fixes FIX=R37-tenant-subscription-backfill [CLIENT=<ad_client_id>]
   ```
   A tenant onboarded after step 2 is refused `BLOCKED_CHAIN_BEHIND` (its chain starts at the
   onboarding baseline): run `make data-fixes` again and repeat this step for it.
5. Verify: R37's rows in `etgo_data_fix_history`
   (`fix_id = '20261005T180000Z__R37-tenant-subscription-backfill'`) are all `APPLIED` or
   `SKIPPED_NOT_NEEDED`, and
   `select count(*) from ad_preference where attribute = 'ETGO_TenantPlan' and isactive = 'Y' and
   upper(trim(value)) = 'PRODUCTIVE'` returns **0**, and the switch row reads exactly as the
   reader selects it — exactly ONE row, with `trim(value) = 'Y'` (the value is not filtered on
   purpose, so a second row or an `N` shows up instead of hiding):
   ```sql
   select ad_preference_id, value, created, updated from ad_preference
    where property = 'ETGO_SubscriptionModel' and ispropertylist = 'Y' and isactive = 'Y'
      and ad_client_id = '0' and coalesce(visibleat_client_id, '0') = '0'
      and coalesce(visibleat_org_id, '0') = '0' and visibleat_role_id is null
      and ad_user_id is null and ad_window_id is null
    order by created desc, updated desc, ad_preference_id desc;
   ```
   **Done** when `select count(*) from ad_preference where attribute = 'ETGO_TenantPlan';` returns
   **0** and the `ETP-5046-TRANSITIONAL-FALLBACK` WARN lines stop. Between steps 3 and 4 those
   lines are expected: with the switch ON the fallback logs every tenant still on the marker at WARN
   (at DEBUG while OFF), and each tenant's line stops once R37 has reached it. Rows that remain
   are the worklist: a tenant with no subscription row whose marker is inactive or not
   `productive` matches neither R37 branch and needs a decision by hand, and a tenant onboarded
   later can get its marker back from R42 (`open-and-notable-topics.md` §3.13).

**Why switch first.** Once the switch is ON every new purchase opens a row, so a single R37 pass
catches every tenant still on the marker. In the old order (R37, then the switch) a purchase made
between the two steps still wrote the marker and needed a second R37 run. Both orders are safe —
rows are decided by the row, markers by the fallback. Migrating client by client *before*
switching on has the same gap: a migrated tenant has a row and needs nothing more, but every other
tenant that bought while the switch was OFF wrote the marker, so run R37 once more for the whole
environment after enabling the switch.

Notes for the operator:

- **Both models coexist until the end.** The switch is system-wide; only R37 is per client. Readers
  look at the tenant's row first and fall back to the preference, so a tenant R37 has not reached
  keeps working.
- **One-way per tenant.** Once a tenant has any `ETGO_SUBSCRIPTION` row it stays on rows whatever
  the switch says (`SubscriptionService#hasAnySubscription`, below).
- **What R37 touches.** It inserts the backfilled row and deletes the tenant's `ETGO_TenantPlan`
  preference. It does **not** create the `ETGO_SubscriptionModel` preference (step 3 does), and it
  deliberately keeps `ETGO_EnvironmentType` and `ETGO_SubscriptionStatus`: every purchase writes
  them on both routes (`TenantEnvironmentLifecycleService#markProductive`), and nothing reads them
  once a row exists. On a row tenant `ETGO_SubscriptionStatus` goes stale — read `ETGO_SUBSCRIPTION`.
- **Not picked up by R37:** a productive tenant without an `ETGO_TenantPlan` marker (made productive
  by hand or with a dev tool). R37's `@check` keys on the marker, so such a tenant gets no row.

**Only a tenant's first row is switched.** A tenant that already has a subscription row — any row,
open or closed — is recorded on a row when it pays, whatever the switch says, because its row is
what every read answers from; a payment recorded only in the marker would leave it blocked behind a
canceled or closed row. **A failed row lookup takes the marker path — accepted, decided 2026-10-14
(Martin Taal), no code change.** With the switch OFF, a lookup that throws is logged at ERROR and
answered "no row", so a re-purchasing tenant whose only row is closed would stay blocked while it
pays. No purchase reaches a tenant with a closed row, and the ETP-5742 re-subscription, which does,
checks for a row itself and fails instead (above). The acceptance does not depend on it — it
fails closed in practice: the lookup runs in the onboarding transaction, and on PostgreSQL a failed
SQL statement aborts it, so the upgrade fails and stays retryable; only a failure other than a
failed SQL statement (a mapping or hydration error after the statement ran, for instance) can reach
the marker (`open-and-notable-topics.md` §2.9, design doc §6.3).

**One-way in practice.** Setting the switch back to `N` sends only tenants that have never had a
row back to the marker; every tenant with a row stays on it, payments included. Two populations that
only grow apart — turning it back OFF undoes nothing. Full design: §6.2/§6.3 of
`plans/2026-09-18-etp-5046-plan-and-subscription-design.md`.

**The preference is the safety net, not a parallel truth — and it is retired per tenant.** With the
switch OFF and no row for the tenant, `applyPaidUpgradeSideEffects` opens no row and calls
`markProductive`, logged at INFO.
With it ON it writes the subscription row and then:

- **success** → it does *not* call `markProductive`; it calls
  `TenantPlanService.retireProductivePreference(clientId)` instead, removing any stale
  `ETGO_TenantPlan` row this tenant still carries, so a newly paid tenant lands directly in the
  post-cutover state;
- **failure** → it calls `markProductive`, because the preference is then the only record that the
  tenant paid, and the fallback below is the only thing that will read it back. If that write fails
  too, the onboarding aborts (ETP-5548, see above) and the purchase stays retryable.

Both writes are **System-owned rows (client `0`) written from the new tenant's context**, so both
run through `payment/SystemContext` (the subscription insert in `SubscriptionService.openSubscription`,
the marker delete in `TenantPlanService.retireProductivePreference`). Admin mode alone is not
enough: `setAdminMode(true)` keeps the DAL check that a row's client equals the *current* client,
and a refusal from that check also marks the whole request for rollback, which no best-effort
`catch` can undo. A refused subscription save is additionally evicted from the session, so it
cannot resurface as a `StaleStateException` in the onboarding's next flush. Both are pinned from a
real tenant context by `TenantContextSubscriptionWriteIntegrationTest`. The billing period the
onboarding later reads from Stripe for that row (ETP-5047) is written the same way — under
`SystemContext`, so its `UPDATEDBY` is `0` — but as one conditional HQL `UPDATE` in its own
transaction, after the checkout request is closed (`open-and-notable-topics.md` §3.7).

R37 — run by the operator as step 4 of the switch above — does the same thing for tenants that
predate the subscription model (statement 3 of its `@apply`, in the same transaction as the
backfilled row) — and, more widely than the runtime path,
for **every tenant that has any `ETGO_SUBSCRIPTION` row**, open or closed. That widening exists
because develop's data-fix R42 (`20260929T190000Z__R42-paid-provisioning-commercial-metadata`,
ETP-5548) re-inserts the marker for paid-provisioned tenants, including ones that already have a
row; the runner runs R37 only for a tenant whose chain is already past R42, so R37 removes it
(design doc §8 and §8.2, including the `run.js --fix <R42>` caveat and the residual for tenants
that first reach R42 after the switch, `open-and-notable-topics.md` §3.13). The fleet therefore
converges from both ends onto one **observable end condition**: `select count(*) from ad_preference where
attribute = 'ETGO_TenantPlan'` reaching 0, with the WARN line below silent. Both retirement paths
remove *every* `ETGO_TenantPlan` row visible at the tenant, whatever its value or `isactive` flag —
a leftover would keep that count above zero forever and block Phase F. Neither retirement may fail
an upgrade that has already been paid for: a failed one is logged and ignored, since the fallback
simply keeps answering until R37 retires the row.

**Any future data-fix must key on `etgo_subscription`, not on `ETGO_TenantPlan`** — after R37 the
preference is present only for tenants the backfill has not reached, so "no productive preference"
increasingly means "already migrated paying tenant", not "free tenant". `R31`/`R32` are safe only
because the runner's filename-ordered, watermark-gated execution — plus its rule that an opt-in fix
such as R37 runs only for a tenant whose chain is already past the fix's date — guarantees they can
never run for a tenant after R37 has; see §8.2 of
`plans/2026-09-18-etp-5046-plan-and-subscription-design.md`.

The read side therefore tolerates the gap. `com.etendoerp.go.payment.TenantPlanPreferenceFallback`
answers for a tenant **only when it has no active subscription row, open or closed** (since
ETP-5047 a canceled subscription closes its row, and a closed row reads as `canceled`, so it must not
fall through to the preference; both reads of `SubscriptionService#findLatest` filter on `isactive`,
so a tenant whose only rows are inactive falls back too):

| Path | Where | Cost |
|---|---|---|
| Single tenant | `TenantPlanService.resolvePlan` | one extra query, and only when the tenant has no active subscription row |
| Bulk (`GET /environments`) | `EnvironmentPlanCache.of(allClientIds, openSubscriptions)` | **one** extra query for all the missing ids at once; none when every tenant has a row |

Both paths must fall back or neither: if only one did, the environment list and `resolvePlan` would
disagree about the same tenant, which is worse than the bug being worked around.

**The preference is not scoped by `AD_CLIENT_ID`.** `Preferences.setPreferenceValue` stores the row
at `AD_CLIENT_ID = '0'` and encodes the tenant it is about in **`VISIBLEAT_CLIENT_ID`** only, so a
lookup filtered on `AD_CLIENT_ID` matches **zero** rows for every tenant — silently. That exact
mistake nearly shipped in the R37 backfill SQL, and two specs
(`TenantPlanServiceTest.PreferenceFallback#scopesTheLookupByVisibleAtClientAndNeverByAdClient` for
the read, `TenantPlanServiceTest.RetireProductivePreference#scopesTheLookupByVisibleAtClientAndNeverByAdClient`
for the retirement) pin the column so it cannot recur. It is worse on the retirement side: a removal
filtered on `AD_CLIENT_ID` is a silent no-op, so the end-condition count would never reach 0 and
Phase F would be blocked forever with nothing reporting why.

A tenant answered by the fallback reports `plan: "productive"` with **`planKey` and
`subscriptionStatus` both JSON null**. There is genuinely no Subscription Plan Catalog row and no subscription behind
it; inventing a key such as `"legacy-productive"` would claim a row that does not exist and would
hide the gap from anyone reading the payload.

Every resolution served by the fallback logs one line naming the tenant — one line per resolution,
never per comparison — at a level that follows the subscription-model switch (read only when the
fallback serves a tenant, at most once per resolution or batch). With the switch **OFF** it is
**DEBUG**: every paying tenant is recorded with the preference on purpose. With it **ON** it is
**WARN**, pointing at the backfill, because a tenant without a row is unexpected there:

```
ETP-5046-TRANSITIONAL-FALLBACK: tenant {} has no active ETGO_SUBSCRIPTION row, open or closed; resolving it as '{}' from the retired {} preference. The ETP-5046 backfill has not run for this tenant — run it and this line disappears.
```

Once switched, that WARN is the deletion signal:
**when it stops appearing, every tenant has a subscription row** and the fallback is safe to remove. Read it
together with the preference count above: the count says no tenant still *has* a marker, the silence
says no tenant still *needs* one. Phase F deletes `TenantPlanPreferenceFallback`, `markProductive`,
`retireProductivePreference`, `PREFERENCE_ATTRIBUTE` and every call site — one
`grep -r ETP-5046-TRANSITIONAL-FALLBACK` finds all of it.

### Exposure in `/environments`

The `GET /sws/go/environments` response gained additive fields:

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
- **`graceEndsAt`** (per environment, with the other lifecycle fields — `subscriptionStatus`,
  `renewalDueAt`, the trial fields — so absent when the tenant has no lifecycle metadata) is the
  ISO instant a `PAST_DUE` subscription's payment grace ends, JSON null for any other status
  (ETP-5049). It is `EnvironmentAccessPolicy.graceEndsAt`, the rule the access policy locks the
  tenant out on and `GET /sws/go/billing/subscription` reports, so an in-app grace banner needs no
  copy of `etendo.go.billing.grace.days`. It is the policy's date, not a promise: with the kill
  switch on for the tenant nothing is locked at that instant — `accessEnforced` says so (below,
  `open-and-notable-topics.md` §4.17).
- **`accessState`** (per environment, when the tenant has lifecycle metadata) is the
  `EnvironmentAccessPolicy.Decision` name. Since ETP-5047 it is the decision **actually enforced**
  (`EnvironmentAccessGuard.enforcedDecision`): with the kill switch on for that tenant a refusal
  reads `ALLOWED`, while `subscriptionStatus` and the trial fields keep reporting the facts.
- **`accessEnforced`** (per environment, always present; ETP-5051) is `false` while the
  `environment-access-enforcement-off` kill switch is on for that tenant, `true` otherwise —
  whatever today's decision. The grace banner drops its "will be blocked on <date>" when it is
  `false`. `GET /sws/go/billing/subscription` carries the same field for its tenant.
- **`renewalAt`** (per environment, always present; ETP-5051 O-1b) is the ISO instant this
  environment's own subscription next renews, in the same format as the `renewalAt` of
  `GET /sws/go/billing/subscription`, or JSON null. "Mis empresas" shows it per row. It is read
  from local state only — the `CURRENT_PERIOD_END` of the tenant's `ETGO_SUBSCRIPTION` row, from
  the same batched `findLatestForClients` read as `plan`, so no Stripe call per environment — and
  it is non-null only while that row is renewing: effective status `active` (Stripe `active` and
  `trialing` are both stored so), not past due, not canceled, not closed, and **not scheduled to
  cancel** — `ETGO_SUBSCRIPTION.CANCEL_AT_PERIOD_END = 'Y'`. That column is display-only: the
  lifecycle webhooks (`customer.subscription.updated`/`.deleted`) and the billing reconciliation
  write Stripe's `cancel_at_period_end` into it, and nothing that decides access, grace or the
  status reads it. **Rows that existed before the column start at `N`:** a subscription already
  scheduled to cancel shows its date until its next `customer.subscription.updated` or the next
  reconciliation run, which re-reads every paying subscription anyway and syncs the flag at no
  extra provider cost (`CANCEL_FLAGS_SYNCED` in its report). A past-due subscription is left to
  the webhook, and its `renewalAt` is null regardless. A row that cannot be read reports null,
  never a failed list.
- **`quotas`** (per environment, always present, `[]` when nothing to report; ETP-5051) is the
  tenant's plan quotas at verdict WARN or DENY, as `QuotaItem`s (`neo-headless.md` §4.16 lists
  the keys). Report-only. The SPA's quota banner reads it for the current environment, because
  core's `loginEnvironment()` drops the `POST /sws/go/session/environment` body that carries the
  same items. One batched read of the listed plans' enforced quota rows, none when no tenant has a
  plan; a failed evaluation reports `[]` with a `[QUOTA]` log line, never a failed list.

All are backward compatible; clients that ignore them are unaffected.

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
  "brandingUpdated": "2026-09-29T10:15:30Z",
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
| Webhook signature, payload shape and durable event claim | `com.etendoerp.go.payment.CheckoutWebhookVerifier`, `CheckoutWebhookProcessor`, `BillingEventStore` (`ETGO_BILLING_EVENT`) |
| Payload allow-list (what may ever be written down) | `com.etendoerp.go.payment.WebhookPayloadSummary` |
| Confirmed-payment correlation, checkout lifecycle | `com.etendoerp.go.payment.CheckoutRequestStore` (`ETGO_CHECKOUT_REQUEST`) |
| Plan read/write | `com.etendoerp.go.payment.TenantPlanService` |
| Subscription-model switch (does a new purchase open a row?) | `com.etendoerp.go.payment.SubscriptionModelSwitch` |
| Gate wiring, 402 response, plan marking | `com.etendoerp.go.rest.EtendoGoJwtServlet` |
| Demo data transfer worker | `com.etendoerp.go.payment.DemoDataTransferService` |
| Environment access check (auth pipeline bind step, MCP, `/login`, tenant session), 402 body, kill switch | `com.etendoerp.go.payment.EnvironmentAccessGuard`, `EnvironmentAccessEnforcementFlag`; carried to the consumers by `com.etendoerp.go.auth.EnvironmentAuthOutcome#getAccessDenial` |
| Ownership count, `plan` in `/environments` | `com.etendoerp.go.rest.EtendoGoJwtDalHelper` |

## Internal provisioning outcome alerts (ETP-5548)

Customer demo/productive creation now emits an internal `OK` after the tenant transaction commits or an `ERROR` after an uncommitted attempt is rolled back, including assigned pool tenants. Recipients default to `builds@etendo.software`; enabled state, recipients, result filters and language are configurable. Delivery failures are contained and do not change provisioning or purchase outcomes. See [Internal operational alerts](internal-operational-alerts.md) for exact properties, authorization, transaction boundaries and payload restrictions.
