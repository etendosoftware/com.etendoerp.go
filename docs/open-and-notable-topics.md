# Open and notable topics — recurring billing block (ETP-5045 … ETP-5053)

A living register of the decisions still owed, the operational constraints that are not visible
from the code, and the traps that would otherwise be rediscovered the hard way.

**Scope:** the whole billing development — ETP-5045 (durable checkout state), ETP-5050 (usage
measurement), ETP-5046 (Subscription Plan Catalog + subscriptions), and what they hand to ETP-5047/5048/5051/5053.

**Status key:** 🔴 decision owed · 🟠 constraint to respect · 🟡 known issue, worked around

**Ticket line:** every topic carries a `**Ticket:**` line under its heading. *Owner* = the ticket that must resolve or respect it. *Related* = where it came from or what it touches. **No ticket** = nothing currently covers it; it needs its own ticket or a decision on where it goes.

**This register carries only what is still live.** A topic is deleted once it is fixed or settled —
it does not graduate to a "closed" section. The history stays in the commit that resolved it, which
is the only copy that cannot drift from the code. **Section numbers are stable**: other documents
cite them (`§3.7`, `§4.8`), so a deleted topic leaves a gap in the numbering rather than shifting
its neighbours.

---

## 1. Decisions still owed

### 🔴 1.1 The stock resources (`activeUsers`, `productiveEnvironments`) have no history (ETP-5050)

**Ticket:** owner ETP-5050 (product decision owed); affects ETP-5051, which would quota it.

The significant unresolved issue in the usage design, and it decides whether `activeUsers` can be
implemented at all. `AD_SESSION` is explicitly ruled out.

A **flow** happens *on* a day (three invoices dated 14 March count as 3 on 14 March, forever). A
**stock** *exists on* a day (a tenant with 12 active users has 12 every day until something
changes). **A stock cannot be expressed declaratively**, because there are no rows dated that day
to count — which is exactly why the strategy SPI exists.

The problem is that **a stock has no history to recompute from**. Asked "how many active users on
2010-01-01?", `AD_USER` can only answer "how many are active *now*". Observed directly: a trial
backfill of a stock resource over 2010–2027 wrote 74,520 rows carrying essentially one figure
repeated across seventeen years. The mechanism worked as designed; the numbers were meaningless
for every day but the current one. Neither the settling window nor the aggregation engine can fix
this — the information is not in the database.

Three options, and the choice is a product decision:

1. **Keep a dated history** (audit/snapshot table) so a past day has a real answer. Most faithful, most expensive.
2. **Only ever record the current day**, never backfill. Cheap and honest; history begins when measurement is switched on.
3. **Redefine the resource as a flow** — e.g. "users created that day" rather than "users active that day". A *different quantity*, so this is a product call, not a technical shortcut.

**`productiveEnvironments` is the second stock, with the same problem.** The ticket specified it
as `Client` bucketed by `creationDate` — clients *created* that day, a flow. The ETP-5050 design
(§"Seeded resources") corrected that to a strategy (`S`, qualifier `productiveEnvironments`): a
daily snapshot of the active clients, i.e. a stock — so the three options above apply to it too.
Neither stock counter exists yet: no `@Named("activeUsers")` or `@Named("productiveEnvironments")`
bean is deployed, so a catalog row for either would fail its run ("No UsageResourceCounter
deployed", `UsageAggregationService`) until one is.

Full analysis: `schema_forge/docs/usage-measurement.md` §4 (its note on `productiveEnvironments`
still describes the ticket's `creationDate` wording, not the design's snapshot).

---

## 2. Deployment — constraints that are not visible in the code

### 🟠 2.1 What the legacy price fallback costs

**Ticket:** owner ETP-5049 (the offer endpoint) and ETP-5053 (moving fallback buyers to a real
plan); ETP-5051 is where the uncapped buyers start to matter. Related ETP-5046, whose develop merge
introduced the fallback.

How the fallback works — the one predicate, the self-retirement on the first priced plan, the
reload edge case — is the design doc's §6.1; the operator procedure is its §6.2. This section keeps
only what is still open.

- **`GET /sws/go/billing/offers` quotes the legacy price, never the plan catalog → ETP-5049.**
  `BillingOfferConfiguration` derives the offer from `etendo.go.checkout.price.id`
  (`retrieveConfiguredPrice()`). While the fallback is active that is the charged price. Once a
  priced plan retires the fallback, the offer keeps quoting the legacy price — a different amount
  from what checkout charges — and once the property is removed it answers
  `503 BILLING_OFFER_UNAVAILABLE`. The upgrade page quotes the catalog and reads the offer only
  while that lookup is in flight or has failed, so it is barely exposed; any other consumer of the
  offer is not. ETP-5049 (plan selection in the customer UI) should point the offer at the catalog
  or retire it. The same endpoint also leaks the Stripe price id (§4.8).
- **Fallback buyers are uncapped and stuck on `legacy-productive` → ETP-5053, felt in ETP-5051.**
  A buyer who pays through the fallback lands on `legacy-productive`, which has zero quota rows and
  is therefore unlimited (§5.1). That costs nothing today and becomes real the moment ETP-5051
  enforces quotas. The fix is not a quota on the grandfathered plan but moving these tenants to a
  real plan — a plan change, which only ETP-5053 provides. Their subscription row snapshots
  the charged price id but no amount or currency, since the plan has none (design doc §7).

### 🟠 2.3 Run the backfill AFTER the deploy, never before

**Ticket:** owner ETP-5046 (the pre-check, an R37 deployment step) and ETP-5048 (the adoption
step, if the pre-check fails); moving grandfathered tenants to a priced plan is ETP-5053.

`resolvePlan` reads the subscription first. A tenant provisioned between the schema landing and
the code shipping gets a preference and no subscription. The backfill's `@check` catches exactly
that tenant because it keys on the preference rather than on a date — but only if it runs
afterwards.

The transitional fallback (design doc §8) means this ordering is no longer *load-bearing for uptime*, only
for completeness.

**Since ETP-5047 "the deploy" means the ETP-5047 deploy.** R37 now inserts `GRACE_ANCHOR` and
`LAST_EVENT_AT`, columns ETP-5047 adds; run before them it fails on every tenant (an error, which
the runner retries — not a silent skip). If an environment already ran the pre-ETP-5047 R37, its
rows are read through the `GRACE_ANCHOR` fallback (§3.7) and nothing needs re-running.

### 🟠 2.4 The R37 sandbox pre-check must be re-run on every target environment

**Ticket:** owner ETP-5046 (R37 deployment).

`R37`'s `@report` carries the verbatim outcome of a manual pre-check: *re-verify that production
Stripe checkout has not gone live since 2026-08-27; if it has, a real paying cohort exists that
the backfill would orphan and an adoption step is required first.*

The regression test fails the build while `@report` still carries the `TODO-PRECHECK-R37`
placeholder; that placeholder has been replaced by the settled wording, so it no longer blocks
the merge. The pre-check itself is not portable: it must be run against the **target**
environment — verifying it on a dev box proves nothing about staging. On the dev box
(2026-09-18) the Stripe key is `sk_test`, so the assumption holds *there*.

**If the pre-check fails → ETP-5048.** R37 gives each backfilled tenant a row on the priceless
`legacy-productive` plan, with the charged Stripe price id where its checkout request recorded one
and never an amount or currency (design doc §7). That is enough for access control and webhook
correlation, and acceptable only while every existing Stripe subscription is Test Mode. If
production checkout has gone live, those rows describe real payers, and the adoption step is to
read each one's subscription from Stripe and record its price, amount and currency. That is
ETP-5048's local-to-provider reconciliation (PRD §9.3: read the real Stripe state of every locally
active subscription and correct it locally), which fills the missing price on its first run;
`StripeApiClient` makes the read cheap. Until ETP-5048 lands such rows stay priceless — harmless
for access, which reads `STATUS` alone, and visible only in billing records and quota snapshots.

### 🟠 2.5 `./gradlew test` needs JDK 17, not the default 21

**Ticket:** no ticket — local build environment; confirming the CI JDK is a separate check.

`build.gradle` targets Java 17; core's bundled Groovy/ASM cannot read Java 21 bytecode.
`:compileTestGroovy` dies with `Unsupported class file major version 65` while compiling core's
own Spock specs — so **no test runs at all**, and the failure looks unrelated to whatever you
changed. `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`. **Confirm what CI uses** before
trusting a green local run.

### 🟠 2.6 Gradle reports `UP-TO-DATE` and runs nothing

**Ticket:** no ticket — build-tooling behaviour, applies to every ticket.

A re-run after an unrelated change silently executes zero tests while printing `BUILD SUCCESSFUL`.
Delete `build/test-results/{test,goIsolatedDalTest}` and pass `--rerun`, then read the task outcome
lines — not just the build result. This produced a false "verified" claim once during ETP-5046.

### 🟠 2.7 `Hooks-Verified` seals do NOT survive a rebase, despite claiming to

**Ticket:** no ticket — a bug in the git-hooks seal (`.githooks`); needs its own ticket.

The trailer carries two fingerprints: one over the commit's tree, and — in `v2` — one over its
patch-id, so that *"a rebase/cherry-pick that replays the SAME diff onto another base keeps a valid
seal"*. In practice the patch-id half is broken for most existing commits.

Observed while merging the ETP-5045 fix through the chain: **four ETP-5050 commits carry the
identical fp2, `f99dd62f8d62`.** A patch-id fingerprint must be unique per diff; these were sealed
from a proof file whose patch-id was not refreshed between commits. Only the two newest ETP-5050
commits have correct values.

The consequence is invisible until someone rebases, because while the tree fingerprint matches the
fallback is never exercised. A rebase of `feature/ETP-5050` left **26 of 28 commits unverified**,
which `pre-push` rejects — forcing either `--no-verify` (which also skips the test/coverage/Sonar
gate) or re-sealing every commit through the hook.

**Practical guidance until it is fixed: merge rather than rebase when propagating a fix down a
chain of already-sealed branches.** A merge commit is explicitly exempt from verification
(`hooks_verify_commit` returns 0 for anything with more than one parent), rewrites nothing, and
needs no force-push. That is how the ETP-5045 context fix reached ETP-5050 and ETP-5046.

**Caveat that applies to either strategy:** a clean textual merge is not a working one. Merging
ETP-5045 into ETP-5046 produced zero conflicts in the test file and then failed to compile —
ETP-5045's new tests call `recordRequested` with four arguments, ETP-5046 widened it to five, and
the two edits sat in different regions so git saw no conflict. Always build after propagating.

### 🟠 2.8 `ETGO_PLAN` must never be an AD dataset table — the legacy plan is seeded by a module script

**Ticket:** owner ETP-5046. Related ETP-5053 (retiring the grandfathered plan, §3.2).

`ETGO_PLAN` holds rows the module ships (`legacy-productive`) and rows operators create at runtime
(the priced plans). DBSM treats a table in the module's `AD` dataset as module-owned **as a whole**,
whatever the dataset's where clause says. With `ETGO_PLAN` in the dataset:

- plain `update.database` refuses with *"Change detected in table: etgo_plan … Database has local
  changes"* as soon as one runtime plan exists — and smartbuild then silently skips the DB update;
- `update.database -Dforce=yes` **deletes every runtime plan**, then fails recreating the foreign
  keys from `ETGO_SUBSCRIPTION`, `ETGO_CHECKOUT_REQUEST` and `ETGO_PLAN_QUOTA` that pointed at them.

So there is no `AD_DATASET_TABLE` row and no `sourcedata/ETGO_PLAN.xml` for it. The legacy row is
created by the module script `EnsureLegacyPlanScript` (`src-util/modulescript/`) with one idempotent
`INSERT … SELECT … WHERE NOT EXISTS` guarded on both the key and the fixed id
(`219D5C8E15C64E97B2F553B228D30DD0`). It runs on every `update.database`, after DBSM has applied
the model, and on `install.source` through `import.sample.data`; an existing row — including one an
operator deactivated or renamed — is never touched. Like `EnsureSystemRoleTemplatesScript`, its
compiled class is **committed** (`build/classes/com/etendoerp/go/modulescript/`, force-added):
`update.database` only runs module scripts that are already compiled and a deploy never runs
`compile.modulescript`, so after any edit recompile and `git add -f` the class, or the old logic
keeps running. `EnsureLegacyPlanScriptTest` fails the build if `ETGO_PLAN` reappears in
`AD_DATASET_TABLE.xml` or as sourcedata, or if the compiled class is not committed. The same rule applies to any other
table that mixes shipped and runtime rows: seed the shipped rows with a module script.

---

## 3. Legacy plans and the cutover

### 🟠 3.2 Phase F — deleting the transitional plan code — has no ticket

**Ticket:** no ticket — needs its own once R37 has run on every environment.

The `ETGO_TenantPlan` preference is retired per tenant (R37 and the paid onboarding), and a
transitional read fallback answers for tenants not reached yet; design doc §8 describes both.
(Since ETP-5047 the fallback answers only for a tenant with **no subscription row at all**: a
cancellation closes its row, so a tenant with only closed rows reads as canceled from its latest
row instead — `findLatest`, §3.7.) What
is open is the cleanup that ends the transition: nothing owns it, so without a ticket the
transitional code stays indefinitely.

**R42 is a second marker writer, and only R37 cleans up after it.** Develop's
`20260929T190000Z__R42-paid-provisioning-commercial-metadata.sql` (ETP-5548, applied, immutable)
inserts an active `ETGO_TenantPlan='productive'` marker — or flips an existing one to `productive` —
for every paid-provisioned owned tenant, deciding "paid" from `etgo_checkout_request` and knowing
nothing about `etgo_subscription`. With the onboarding baseline at 2026-09-02 that includes tenants
whose paid upgrade opened a subscription row and deliberately wrote no marker. The runtime path
cannot remove such a marker (it retires only at the moment it opens a row), so R37's retirement
branch does: it deletes the marker of **every tenant that has any subscription row**, open or
closed (design doc §8). The end condition below is therefore reached only once R37 has run *after*
R42 for every tenant — the normal chain order (§3.6); a marker that reappears after R37 means
someone forced R42 by hand.

**Trigger** — both, checked on every environment (design doc §8.1):

```sql
select count(*) from ad_preference where attribute = 'ETGO_TenantPlan';   -- reaches 0
```

and the fallback's WARN (`ETP-5046-TRANSITIONAL-FALLBACK: tenant … has no open ETGO_SUBSCRIPTION
row`) no longer appears in the logs.

**What Phase F deletes** — every site carrying the grep marker `ETP-5046-TRANSITIONAL-FALLBACK`
(6 source files, 5 test files as of 2026-09-28):

- `TenantPlanPreferenceFallback`, and its use in `TenantPlanService.resolvePlan`;
- `TenantPlanService.markProductive`, `retireProductivePreference` and `PREFERENCE_ATTRIBUTE`, with
  their call sites in `EtendoGoJwtServlet.applyPaidUpgradeSideEffects`;
- the `allClientIds` overload of `EnvironmentPlanCache.of` and its callers in
  `EtendoGoJwtServlet` / `EtendoGoJwtDalHelper`;
- the `ETGO_SubscriptionStatus` / `ETGO_SubscriptionDueAt` preference reads in
  `TenantEnvironmentLifecycleService` for a tenant with no row, and with them the write-path
  asymmetry of §3.5;
- the `ETGO_EnvironmentType` read in `TenantEnvironmentLifecycleService.resolve`, which since
  ETP-5047 happens only for a tenant with no row — its last reader, so the marker itself retires
  with it.

**In the same cleanup, fold `EnvironmentPlanCache` into the environment list (ETP-5047).** Replace
it with a local `Map<String, PlanView>` built in `EtendoGoJwtServlet.handleEnvironments` from
`findLatestForClients`. The environment list is its only multi-tenant caller, and there *n* is the
number of environments one account owns, typically 1–3. A dedicated class, with its thread-safety
warnings, is out of proportion to that. Half of the class leaves with the fallback anyway: the
`of(allClientIds, …)` overload and the seam that injects the preference fallback. The other caller,
the single-environment overload of `EtendoGoJwtDalHelper.buildEnvironmentJson`, builds a one-entry
cache only so it resolves the plan exactly as the list does. Once the fallback is gone it can read
that one tenant's latest row directly.

**Not Phase F, but the same kind of cleanup:** the `legacy-productive` plan is re-created on every
`update.database` by `EnsureLegacyPlanScript` (§2.8). Whoever retires the grandfathered plan — once
ETP-5053 has moved its buyers and no subscription references it — must remove or change that script
in the same change; a row deleted without it is back after the next deploy. (Deactivating the row
survives — the script only creates a missing row, it never updates one.)

---

### 🟠 3.5 An unrecognised subscription status means ENTITLED, never locked out

**Ticket:** owner ETP-5046 (decided). Binding on ETP-5047 and ETP-5053, whichever first adds a `STATUS` value. The write-path asymmetry is settled in Phase F (no ticket yet, §3.2).

**Context — two models met in the merge.** While ETP-5046 was putting subscription state into
`ETGO_SUBSCRIPTION`, `develop` shipped a parallel model for the same concept:
`EnvironmentAccessPolicy` + `TenantEnvironmentLifecycleService`, persisting to an
`ETGO_SubscriptionStatus` AD_Preference (its own javadoc calls preferences *"a compatibility-first
persistence adapter"* — the exact design ETP-5046 exists to retire). Both emitted a
`subscriptionStatus` field in the environment payload, with **different value vocabularies**.
Martin's call (2026-09-21): unify on the table. `TenantEnvironmentLifecycleService.productiveSnapshot()`
now reads the open subscription row, and `subscriptionStatusOf()` maps its `STATUS` onto the
access policy's enum. (Since ETP-5047 the method is `rowSnapshot`, and it reads the tenant's
*latest* row — open, else the most recently closed one — through
`SubscriptionService.effectiveStatusOf`, which reads a closed row as `canceled` whatever its
`STATUS` says; see §3.7.)

| `ETGO_SUBSCRIPTION.STATUS` | `EnvironmentAccessPolicy.SubscriptionStatus` |
|---|---|
| `active` | `CURRENT` |
| `past_due` | `PAST_DUE` |
| `canceled` | `EXPIRED` |
| **anything else, including null/blank** | **`LEGACY_ENTITLEMENT`** |

**The default is the load-bearing part, and it is deliberately fail-OPEN.** This value reaches
`EnvironmentAccessPolicy`, which decides whether a user may enter the product at all. A tenant
reaching this mapping demonstrably holds an *open* subscription row, so the only safe reading of a
status the deployed build does not recognise is "entitled". `NONE` would lock a paying customer
out of their own environment purely because someone added a status value this build predates. The
asymmetry justifies it: a wrongly-admitted tenant is a billing discrepancy, a wrongly-excluded
paying tenant is an outage.

**The cost of that choice, and the trap:** a new `STATUS` value is **invisible** — no error, no
warning, no log line, it simply reads as `LEGACY_ENTITLEMENT` and the tenant keeps working. Any
ticket that adds a value to `ETGO_SUBSCRIPTION.STATUS` (ETP-5053's plan changes are the likely
first) **MUST extend `subscriptionStatusOf()` in the same change**, and the DDL check constraint
`ETGO_SUB_STATUS_CHK` is the place to notice it — the constraint and the mapping must be edited
together or they drift apart in silence.

**Two things deliberately NOT moved to the table:**

- `ENVIRONMENT_TYPE` stays a preference. It records DEMO versus PRODUCTIVE, which the subscription
  table does not carry, so `applyPaidUpgradeSideEffects` still marks the lifecycle projection
  whichever way the payment itself was recorded. **Since ETP-5047 it no longer decides for a tenant
  with a row:** any subscription row makes the tenant productive (§3.7), and the marker is read
  only for a tenant with no row at all — retired in Phase F (§3.2).
- The `ETGO_SubscriptionStatus` / `ETGO_SubscriptionDueAt` preferences are still read **when the
  tenant has no subscription row at all** (`ETP-5046-TRANSITIONAL-FALLBACK`). The order matters:
  consulting them first would let the access policy and the Subscription Plan Catalog disagree about the same
  tenant, which is the whole point of the unification. That block goes in Phase F with the rest of
  the fallback, once R37 has given every productive tenant a row.

**Asymmetry on the WRITE path, recorded rather than fixed.** `applyPaidUpgradeSideEffects` now
touches three stores: it opens the `ETGO_SUBSCRIPTION` row (the record), *deletes* the
`ETGO_TenantPlan` preference when that succeeds, and then calls
`tenantEnvironmentLifecycleService.markProductive()` **unconditionally**, which writes both
`ETGO_EnvironmentType` and `ETGO_SubscriptionStatus = CURRENT`. So the happy path retires one
preference while still writing another that, after the unification, is only ever *read* when no
subscription row exists.

That is defensible — it seeds the fallback, so a tenant whose subscription row is later lost reads
`CURRENT` rather than being locked out — but it is a side effect of the merge, not a designed
behaviour, and it means **`ETGO_SubscriptionStatus` can go stale against the table**: nothing
updates it when a subscription moves to `past_due` or `canceled`. Harmless while the table wins
every read that matters; actively misleading to anyone who inspects the preference to answer "is
this tenant paying". Either stop writing it on the subscription path, or keep it in step — the
Phase F cleanup should settle which.

---

### 🟠 3.6 The data-fix watermark silently skips a fix dated at or below it — R37 was re-dated

**Ticket:** related ETP-5046 (R37 re-dated); the rule binds every branch that carries a data-fix, no single ticket.

`run.js` applies, per tenant, only fixes strictly newer than the newest `PROCESSED` fix
(`fix.timestamp <= watermark` → skip, no look-back). A fix that waits on a branch while develop
merges newer fixes is therefore dead on arrival on every environment that already ran them — no
ledger row, no error, nothing in any report.

R37 was authored as `20260918T120000Z`. At merge time develop carried fixes up to
`20260922T130000Z`, and `R38-org-legalentity-pointer` had the **identical** `20260918T120000Z`
(equal is skipped too). It was renamed to `20260924T150000Z__R37-tenant-subscription-backfill.sql`,
and again to `20261005T180000Z__R37-tenant-subscription-backfill.sql` once develop carried fixes up
to `20261005T120000Z`, before reaching any shared environment; `sql/README.md` rule 3 forbids renaming an *applied* fix,
not an unapplied one. The consequence had it shipped: every paying tenant left on the retired
preference, with §3.2's end condition never reached.

**Before merging any branch that carries a data-fix, re-check its timestamp against the newest fix
in the target branch** — and re-date it if it is not strictly newer.

**The date also fixes R37's place after R42, and that order is load-bearing.** R37 now sits at
2026-10-05T18:00:00Z; develop's R42 (`20260929T190000Z`, which re-inserts the `ETGO_TenantPlan`
marker, §3.2) sorts before it. Per tenant, the chain visits R42 and then R37 in the same run, and
R37's retirement branch removes whatever marker R42 just wrote. A failed R42 halts that tenant's
chain before R37 and the next run resumes at R42, so R42 still comes first. Once R37 is
`PROCESSED` the tenant's watermark is ≥ 2026-10-05T18:00:00Z and R42 never runs for it again. A
tenant onboarded after ETP-5046 carries the onboarding baseline (2026-09-02) as its watermark, so
its first chain runs R42 and then R37 in one pass. **The one way to run R42 after R37 is an
operator forcing it** — `run.js --fix <R42>` ignores chain order and the watermark. Whoever does
that must follow it with `run.js --fix <R37> --client <same tenant>`, or the re-inserted marker
survives and §3.2's end condition never reaches 0.

**Guard since ETP-5046:** `schema_forge/cli/test/data-fixes-catalog-ordering.test.js` fails the
build when two fixes share a timestamp prefix (the seven already-applied pairs are frozen by exact
file name in `APPLIED_SHARED_TIMESTAMPS` — a third file on one of those stamps still fails) and
pins R37 strictly after `NEWEST_DEVELOP_FIX_AT_MERGE`. That catches the equal-stamp half of this
trap. The other half — a fix dated *before* the newest fix an environment has already processed —
is invisible to a catalog test, so the rule above stays the author's job; `sql/README.md` "Choosing
the timestamp" states it where fixes are written.

### 🟠 3.7 Lifecycle webhooks write the subscription row they resolve — preferences only without one

**Ticket:** owner ETP-5047 (delivered: row resolution, the watermark column, the grace-anchor
split, close-on-cancel and re-subscription); related develop ETP-5443 / ETP-5488, ETP-5046, and
ETP-5053 (a plan change closes a row to open its successor).

Develop's ETP-5443 wired the Stripe lifecycle webhooks (`invoice.paid`, `invoice.payment_failed`,
`customer.subscription.updated`, `customer.subscription.deleted`) into a preference projection
(`ETGO_SubscriptionStatus` / `ETGO_SubscriptionDueAt`). ETP-5046 moved the write onto the open
`ETGO_SUBSCRIPTION` row; ETP-5047 made the row the event lands on explicit
(`TenantEnvironmentLifecycleService.targetForSubscription` / `targetForTenant`, then
`applySubscriptionEvent`):

1. **the open row whose `STRIPE_SUBSCRIPTION_ID` is the event's subscription** — the id is not
   unique across rows, only across open ones (a plan change keeps the Stripe subscription and opens
   a successor row, ETP-5053), so `END_DATE IS NULL` is part of the lookup;
2. otherwise the tenant is found the old way — the checkout request that bought the subscription,
   then the Stripe customer — and its **latest** row decides:
   - no row at all → the preference projection (`ETP-5046-TRANSITIONAL-FALLBACK`);
   - an open row with no Stripe id (backfilled) or this one → that row;
   - an open row naming **another** subscription → `IGNORED`, `event for another subscription`;
   - only closed rows → `IGNORED`, `subscription closed` (a late event of the canceled
     subscription) or `no open subscription row` (the first event of a new purchase, before
     onboarding opens its row). Neither may revive or overwrite anything.

The row gets `STATUS` (`CURRENT→active`, `PAST_DUE→past_due`, `EXPIRED→canceled`),
`GRACE_ANCHOR` (the applier's grace anchor; `null` clears it), the Stripe billing period in
`CURRENT_PERIOD_START/END` when the event reports one, and `LAST_EVENT_AT` (the ordering
watermark, forward-only) — see "Three columns, three jobs" below. The target's `storedState()`,
`resolve` and `TenantPlanService.resolvePlan` read the same row, so the access policy, the plan and
the environment list agree. Every read runs in admin mode (ETP-5488; the NEO check runs as the
calling user, whose role can read neither `AD_Preference` nor `ETGO_SUBSCRIPTION`).

**Decided behaviour (Martin, 2026-09-24; cancellation revised in ETP-5047):**

- **A cancellation closes the row.** `customer.subscription.deleted`, and an update to Stripe's
  terminal `canceled`, set `END_DATE` (Stripe's `ended_at`, else `canceled_at`, else now; never
  before `START_DATE`). **A closed row reads as `canceled` whatever its `STATUS` says**
  (`SubscriptionService.effectiveStatusOf`): access `EXPIRED`, plan `free`. `findLatest` /
  `findLatestForClients` answer "open row, else the latest closed one", so a canceled tenant never
  falls through to the preference fallback nor to `LEGACY_ENTITLEMENT`. Onboarding a canceled
  tenant again would run `forceTestModeForFreeTenant` on it, routing its SII / TicketBAI /
  VeriFactu submissions to the test endpoints — a compliance stake, not a config nit.
- **The row, not the marker, makes a tenant productive (ETP-5047 review, B1).**
  `TenantEnvironmentLifecycleService.resolve` checks for any subscription row (open or closed)
  **first** and takes the productive path when there is one, whatever `ETGO_EnvironmentType` says.
  The earlier version decided on the marker or on `resolvePlan`, and failed open: a canceled row
  makes the plan `free`, and a tenant without the marker — every tenant provisioned before the
  marker existed (6 of 6 productive tenants on the development database) — fell into the demo path,
  got a legacy-transition start or no snapshot, and was **allowed**. A tenant with a row now never
  reaches the demo path, so no demo or legacy-transition preference is ever written for it. On the
  row path the marker is not even read, and a missing one is not reported (it is the normal state
  of a pre-marker tenant). The marker and `resolvePlan` still decide for a tenant with no row at
  all (the preference fallback).
- **Re-subscribing opens a fresh row.** A later purchase for the same tenant finds no open row and
  `openSubscription` inserts one. If the open row is `canceled` but was never closed — its delete
  event was lost, or R37 backfilled it (R37 leaves canceled rows open on purpose: its idempotency
  guard is "no open row", so closing would let a re-run insert a duplicate) — `openSubscription`
  closes it before inserting. The close must be in the database first — Hibernate runs inserts
  before updates at flush, and `etgo_sub_open_envclient_uq` would otherwise reject the new row —
  but it is **not** a session flush: the only caller is the paid onboarding
  (`openSubscriptionBestEffort`), and a flush there, under `SystemContext`, would write the whole
  pending onboarding with `updatedBy='0'` and report an unrelated pending-write failure as "could
  not have its subscription opened". `SubscriptionService.closeInDatabase` issues one HQL `update`
  of that row alone (`END_DATE` = now, never before `START_DATE`; `UPDATED`; `UPDATEDBY` = the
  current user, as the DAL would stamp them; guarded by `END_DATE IS NULL`), which the session's
  `FlushMode.COMMIT` runs without flushing anything else, then refreshes the entity so a later flush
  has nothing of it left to write. ETP-5053 closes a row to open its successor and must follow the
  same rule: a targeted close, never a session flush.
- **Three columns, three jobs (ETP-5047).**
  - `GRACE_ANCHOR` — the end of the period the customer already paid for, set only while
    `past_due`; the access policy counts the grace days from it. Before ETP-5047 it lived in
    `CURRENT_PERIOD_END`. **Old-shape rows still work:** `SubscriptionService.graceAnchorOf`
    falls back to `CURRENT_PERIOD_END` for a `past_due` row with no `GRACE_ANCHOR` and no
    `CURRENT_PERIOD_START` — the shape the old write and a pre-ETP-5047 R37 left. The new write
    never produces that shape, and the first lifecycle event moves such a row off it. (The column
    was first declared with `onCreateDefault = CURRENT_PERIOD_END`; dbsm did not apply it to the
    existing rows of a nullable column, so the declaration was dropped and the read fallback is
    the only migration.)
  - `CURRENT_PERIOD_START/END` — Stripe's billing period, written as a pair: from the
    subscription (or its first item, `billing_mode: flexible`) on `customer.subscription.*`, and
    from the invoice **lines** on `invoice.paid` (the invoice's own `period_*` looks back one
    period). An event that reports no period leaves it alone; `invoice.payment_failed` never
    writes one.
  - `LAST_EVENT_AT` — the `created` instant of the last applied lifecycle event, forward-only.
    `ETGO_SubscriptionEventAt` is **written** only for a tenant with no row. On the row route it is
    still **read**, but only as a read-only fallback while the row's `LAST_EVENT_AT` is NULL — a row
    that predates the column (`TenantEnvironmentLifecycleService.rowState`), the same pattern as
    `graceAnchorOf` for the old anchor shape — so the first out-of-order event after the deploy is
    still recognised as stale. The first applied event writes the column and moves the row off the
    fallback for good; R37 carries the preference into the column for the tenants it backfills.
- The development lifecycle tool mirrors a `CURRENT`/`PAST_DUE`/`EXPIRED` status onto the **open**
  row (`applyLifecycleStatus`, which never closes one). **`NONE` and `LEGACY_ENTITLEMENT` have no
  row status, so once a tenant has a row the tool's choice of either is ignored**, and a tenant
  whose only row is closed cannot be flipped back by the tool at all. Accepted for a
  development-only tool rather than inventing row statuses.
- **The webhook installs its own system context.** It runs with `OBContext == null`;
  `applySubscriptionLifecycle` runs through `SystemContext.run` (capture, install, quiet unwind —
  shared with `CheckoutRequestStore`/`BillingEventStore`) and `setPreference` runs in admin mode;
  `CheckoutWebhookEndpointIntegrationTest` pins both routes. Design doc §8.4 has the full story.
  On the preference route a watermark write that fails propagates (500, the provider
  redelivers); on the row route the watermark is part of the row write. A status write that fails
  marks the event `FAILED`.
- **The backfill carries the preference state onto the row.** R37 seeds `STATUS` from
  `ETGO_SubscriptionStatus` (same mapping as above, plus `NONE → canceled` so a locked-out tenant
  stays locked out; absent/`LEGACY_ENTITLEMENT`/unknown → `active`), `GRACE_ANCHOR` from
  `ETGO_SubscriptionDueAt` and `LAST_EVENT_AT` from `ETGO_SubscriptionEventAt`, reading all three
  by `AD_CLIENT_ID`; the billing period stays NULL (the preferences never knew it). Design doc
  §7.0. **R37 was edited in ETP-5047** (on `feature/ETP-5047` only) and now needs the ETP-5047
  columns, so it must run after the ETP-5047 deploy — see §2.3. A tenant it reached in its
  pre-ETP-5047 form (anchor in `CURRENT_PERIOD_END`) reads correctly through the fallback above.

### 🟠 3.8 One environment-access check, one 402 body, one kill switch (ETP-5047)

**Ticket:** owner ETP-5047 (delivered); related ETP-5455 (the shared auth pipeline whose bind step runs the check).

`EnvironmentAccessGuard` is the single commercial access check. Since the merge with ETP-5455 it
runs in the bind step of the shared auth pipeline (`EnvironmentRequestAuthenticator`) for every
surface whose `SurfacePolicy` requires commercial access — `NEO_API` (NEO, every request, every
scheme) and `NEO_DATA` (`NeoFavoritesServlet`, `NeoFiscalTestModeServlet` through
`JwtAuthUtils.authenticateOrFail`, `ReportSelectorsServlet`, the OAuth2 API-key endpoints) — and
hands its denial to the consumer as `EnvironmentAuthOutcome.getAccessDenial()`. Outside the
pipeline it runs in MCP (`McpServlet.doPost`, every credential scheme, run as system because MCP
has no context yet), in `EtendoGoJwtServlet.resolveTenantSession` (the `/sws/go` endpoints that act
on the session's tenant) and in the legacy `GET /sws/go/login` (it hands out a raw Etendo JWT, valid
on every secure web service of the tenant; refusing it there is confirmed (Martin, 2026-09-28)).
They all answer **HTTP 402** with the body below — the OAuth2 API-key endpoints excepted, which
refuse through the same guard in the OAuth2 servlet's own error envelope:

```json
{ "error": { "message": "Environment access is not available: SUBSCRIPTION_REQUIRED",
             "status": 402, "code": "ENVIRONMENT_ACCESS_DENIED", "decision": "SUBSCRIPTION_REQUIRED" } }
```

`message` is byte-for-byte the pre-ETP-5047 text; the SPA (`environmentAccessGate.js`) reads
`error.decision` first and falls back to parsing it. **Keep the message unchanged for at least one
release** — an SPA older than the backend still parses it.

- **`POST /sws/go/session/environment` does NOT refuse — decided (Martin, 2026-09-25).** The
  blocked screen and the pages it sends the customer to (`/account`, `/upgrade`) render inside the
  entered environment; refusing entry would lock a blocked customer out of the only place that lets
  them pay. It answers as before plus `accessDecision: "<DECISION>"` when the tenant is blocked.
  **`accessDecision` is informational and backend-only:** nothing in the SPA reads it; the blocked
  screen is driven by the NEO 402 on `windowaccessmap`.
  The platform-account endpoints (`runWithPlatformAccount`: billing, portal, purchases, plans)
  never call the guard, and none of them authenticates through `JwtAuthUtils` (checked in ETP-5047:
  `EtendoGoJwtServlet` only reads `JwtAuthUtils`' claim-name constants).
- **One log line per refusal.** The guard itself logs every denial it returns, at INFO:
  `Commercial access denied at <entryPoint> for tenant <clientId>: <DECISION>`. The callers do not
  log it again (NEO, `JwtAuthUtils` and MCP used to log their own line without the tenant;
  `ReportSelectorsServlet` keeps its WARN only for refusals the guard did not decide). Entry-point
  labels: `neo`, the `JwtAuthUtils` context (`favorites GET`, ...), `report-selectors`,
  `oauth2-api-keys`, `mcp`, `tenant-session`, `environment-login`. `POST /session/environment`
  reads `enforcedDecision` instead of `check`, so it writes no refusal line — it refuses nothing.
- **Kill switch** `environment-access-enforcement-off` (backend-only, per `clientId` via ConfigCat):
  enforcing unless the flag resolves to `true` (locally `true`/`Y`/`yes`/`1`, case-insensitive;
  anything else, unset or unreadable keeps enforcing); when on, the would-be denial is logged at
  INFO and allowed, and the environment list reports `accessState` as `ALLOWED` for that tenant.
  See `feature-flags-and-tenant-upgrade.md` §1 — including "Operating environment-access
  enforcement": deploy order, switching it off, and what support sees for a blocked tenant.
- **A new tenant servlet must authenticate through the shared pipeline under a commercially gated
  policy** (`NEO_API` / `NEO_DATA` — `NeoAuthenticator`, `JwtAuthUtils.authenticateOrFail`,
  `NeoServletSupport.authenticate`) to inherit the check; one that builds its own `OBContext` from a
  session or JWT skips it silently (`AuthenticationEntryPointGuardTest` catches most of those).
  Pay-path endpoints are the exception and must stay outside it.
- **Survey configuration is deliberately NOT gated.** ETP-5047 first guarded it with the other
  `JwtAuthUtils` servlets; ETP-5455 moved it to `NEO_AUXILIARY` (ADR-0001, "Commercially blocked
  environment": support and surveys stay reachable — a survey is global configuration and feedback,
  not tenant ERP data, and a blocked customer's feedback is the one that matters). The merge keeps
  ETP-5455's decision — confirmed (Martin, 2026-09-28).
- **`charge.dispute.created`** is alert-only: recorded `APPLIED` in `ETGO_BILLING_EVENT`, a WARN
  with the dispute, charge and payment-intent ids, amount and reason — never a status change. A lost
  dispute reaches the subscription through the ordinary lifecycle events.

### 🟡 3.10 A JWT minted before the block keeps working on Copilot until it expires

**Ticket:** accepted as a known risk (Martin, 2026-09-28); no ticket — found in ETP-5047 QA (BUG-1); a fix would live in `com.etendoerp.copilot` or in the secure-web-services token lifetime.

The environment-access check (§3.8) runs where a request enters a tenant through this module: NEO,
the `NEO_DATA` servlets, MCP, and `GET /sws/go/login`, which refuses to mint a new token for a
blocked tenant. Token renewal (`SFRefreshToken`) is a NEO pseudo-spec, so it is refused too. But an
Etendo JWT **already issued** before the tenant was blocked is still valid on
`com.etendoerp.copilot`'s `/sws/copilot/*`, which authenticates it through Etendo's secure web
services and never asks the guard. It keeps working until it expires —
`SMFSWS_CONFIG.EXPIRATIONTIME`, 1440 minutes (24 h) on the development database. Found in ETP-5047
QA (BUG-1).

**Accepted:** a blocked tenant can keep using Copilot for at most one token lifetime after the
block. If it is ever closed (Copilot is another module, so no code here): either (a) Copilot's
request authentication calls `EnvironmentAccessGuard.check` (or an equivalent hook this module
exposes) with the token's client, answering the same 402 body — the durable fix, and the same rule
§3.8 states for any new tenant servlet; or (b) shorten the secure-web-services token lifetime —
cheaper, but it bounds the leak rather than closing it and affects every client of those tokens.

### 🟡 3.11 Two R37 edge cases found in ETP-5047 QA

**Ticket:** accepted as a known risk (Martin, 2026-09-28); no ticket — found in ETP-5047 QA; related ETP-5046 (R37 deployment).

- **`past_due` with no grace anchor means zero grace — blocked at once.** The access policy grants
  grace only from a non-null anchor (`EnvironmentAccessPolicy.evaluate`), and
  `SubscriptionService.graceAnchorOf` returns null for a `past_due` row with neither
  `GRACE_ANCHOR` nor the old-shape `CURRENT_PERIOD_END`. The webhook never writes that shape (an
  outcome with no anchor is ignored as `missing period end`), but **R37 can**: a tenant whose
  `ETGO_SubscriptionStatus` preference is `PAST_DUE` while `ETGO_SubscriptionDueAt` is missing or
  not ISO-shaped is backfilled as `past_due` with no anchor. That preserves its access rather
  than changing it — the preference route already read the same pair as "past due, no due date",
  i.e. blocked — accepted as is. Before running R37 on an environment, run a report query for
  tenants with `ETGO_SubscriptionStatus = PAST_DUE` and no valid `ETGO_SubscriptionDueAt`.
- **R37's `@check` keys on "no OPEN row", so it can re-subscribe a canceled tenant.** A tenant
  that still carries the `ETGO_TenantPlan = productive` preference and whose only rows are closed
  — a subscription canceled since ETP-5047 closes its row, and a failed
  `retireProductivePreference` leaves the preference behind — matches `@check`, and R37 inserts a
  fresh **active** row: a canceled tenant reads as paying again. Accepted as is; if R37 is ever
  run on an environment where subscriptions were canceled live, revisit the check first — keying
  `@check` and the statement-2 guard on "no row at all" (`NOT EXISTS` any `ETGO_SUBSCRIPTION` row
  for the tenant) closes it and stays idempotent (§3.7's reason for leaving backfilled canceled
  rows open).

## 4. Known issues

### 🟡 4.4 The at-sign guard is over-tested (ETP-5050)

**Ticket:** owner ETP-5050.

`UsageMessages.atSafe` replaces `@` with `(at)` in any value interpolated into a user-facing
message, because `OBMessageUtils` treats `@token@` as a substitution and an unescaped at-sign in a
billing resource catalog search key, an HQL fragment or a Hibernate/CDI exception message can blank the message. The
guard is sound and has 22 call sites.

The **production surface is one line**:

```java
return StringUtils.isEmpty(text) ? text : StringUtils.replace(text, "@", "(at)");
```

The **test surface is roughly 30 assertions** — about 16 executed cases in `UsageMessagesTest`
(124 lines) plus ~16 `contains("@")` assertions across `UsageResourceValidatorTest`,
`UsageAggregationProcessTest`, `UsageRunLedgerTest` and `BillingResourceEventHandlerTest`.

Roughly ten of those cases test **`String.replace` semantics rather than any decision of ours**:

- `atSignsAtTheEdgesAreReplacedToo` (4 cases) — leading / trailing / adjacent at-signs have no
  distinct behaviour to discover; that is JDK contract.
- `textWithNoAtSignIsUnchanged` (5 cases) — one suffices; "no-op when nothing matches" is again
  `String.replace`.
- `emptyPassesThroughUnchanged` asserts `assertSame("", atSafe(""))` — a string-identity detail of
  `StringUtils`, brittle and carrying no information about our behaviour.

Two **do** earn their place and should survive any trim:

- `oneAtSignIsReplacedInPlace` pins **replace versus strip**. Stripping turns `a@b.com` into
  `ab.com`, which reads as a real value and is quietly wrong, whereas `a(at)b.com` is visibly a
  substitution. Asserting the exact output is what holds that distinction; a test that only checked
  "no at-sign remains" would accept the stripping version.
- `nullPassesThrough` — these run inside error handlers, and a guard that threw would convert a
  message about a failure into a second, more confusing failure.

The call-site assertions are legitimate in principle (a guard nobody calls is not a guard, and
there are 22 call sites) but over-applied at sixteen; three or four spot checks carry the same
information.

**Suggested shape:** ~4 unit cases plus 3–4 call-site spot checks, down from ~30. Low risk, no
behaviour change — the cost being paid is maintenance drag and reviewer attention, not correctness.
Raised by Martin on 2026-09-18; not yet actioned.

### 🟡 4.6 `recordRequested` accepts a null plan that production cannot produce

**Ticket:** related ETP-5045 / ETP-5046; no ticket owns the fix — small, needs its own or can ride along with the next checkout change.

`CheckoutRequestStore.recordRequested(..., RequestOptions options, Plan plan)` records the Subscription Plan Catalog row being bought, so the
subscription opened after payment reflects **what the buyer actually saw** rather than whatever the
plan says by then. `ETGO_CHECKOUT_REQUEST.ETGO_PLAN_ID` is nullable because rows predating ETP-5046
have no plan.

But the production path cannot pass null: `HostedCheckoutService` resolves the plan (or the
grandfathered plan under the legacy price fallback) before recording. Null is therefore a
**test-only** value — the plan-less `recordRequested` overloads kept from develop pass it for
fixtures — and the parameter currently accepts the one thing it exists to prevent. A
change that dropped the plan on the way in would write a row that is indistinguishable from a legacy
one, and the subscription would open not knowing what was bought.

**Suggested fix:** `Objects.requireNonNull(plan, ...)` and give the fixtures a real `Plan`.
`GrandfatheredSubscriptionIntegrationTest` already creates one, so the pattern exists. Not done
during the ETP-5045 merge because changing a method contract mid-merge is the wrong moment.

### 🟠 4.7 The rest of the `OBContext` survey — one real, one false alarm, one unread

**Ticket:** no ticket — needs its own, as stated below.

Fixing the same leak twice — in `CheckoutRequestStore` (ETP-5045) and then in `BillingEventStore`
(ETP-5046) — raised the obvious question: how many more of these are there? Below is a module-wide
survey of every class that installs a system context, with the verdict for each. **The grep alone
lies in both directions** — it flags comments as calls and cannot see a guarded install — so each
line below is the result of reading the code, not of counting matches.

| Class | Verdict |
|---|---|
| `payment/CheckoutRequestStore` | ✅ fixed on ETP-5045; the account-id lookups develop added afterwards reintroduced raw installs and were routed through `runAsSystem` in the 2026-09-24 develop merge |
| `payment/BillingEventStore` | ✅ fixed on ETP-5046 |
| `payment/SubscriptionService` | ✅ reads are admin mode only and never replace the caller's context (the old `openSystemContextWhenAbsent()` was dead and was removed). **`openSubscription` writes through `SystemContext` since 2026-09-27**: admin mode alone was *not* enough — `setAdminMode(true)` keeps the DAL client check on, and the paid onboarding calls it as the new tenant, so every paid upgrade failed (found in the manual happy path; pinned by `TenantContextSubscriptionWriteIntegrationTest`) |
| `payment/TenantPlanService` | ✅ `retireProductivePreference` deletes its client-0 rows through `SystemContext` since 2026-09-27, for the same reason; its flush stays in the caller's context |
| `rest/TransactionalAuthEmailSender` | ✅ captures and restores |
| `rest/CompanyInvitationService` | ❌ **real, unfixed** — see below |
| `roles/RoleInheritanceReconciliationService` | ⚪ **false positive** — its only `setOBContext` match is prose in a comment (line 358) describing a *caller* that runs as system; there is no call |
| `rest/EtendoGoJwtServlet` | ❓ **unaudited** — 28 raw system installs (recounted 2026-10-05); routed through `payment/SystemContext`: the lifecycle webhook, and the two ETP-5548 checks develop brought in (`isProductiveNameTakenByAccount`, `isAssociatedDemo`). Note the checkout paths still run in system context after the name check: `requireBillingOwner` → `hasOwnedEnvironment` installs it first, raw |

**`CompanyInvitationService` — the real one.** Two sites, both `restorePreviousMode()`-only:

- `resolveInvitation(...)` — installs at **509**, unwinds at **554** (line numbers as of 2026-09-28)
- `withAdminMode(...)` — installs at **758**, unwinds at **763**

It is the cheapest of the three to fix, because `withAdminMode` is *already* the `runAsSystem`
shape — a wrapper every accept path funnels through (`acceptExistingAccountInAdminMode` at 576,
`registerAndAcceptInAdminMode` at 664). It simply never captures. `resolveInvitation` is the only
method opening the context inline, so routing it through `withAdminMode` would collapse the class
to one context site and fix both at once. The case to care about is
`registerAndAcceptInAdminMode`, which creates a user and accepts an invitation: anything continuing
on that thread afterwards runs as system. That is the shape of the hazard; no exploit has been
traced.

**`EtendoGoJwtServlet` — deliberately left as a question.** 28 raw installs next to three sites
routed through `SystemContext` is not evidence of 28 leaks, and it is not evidence of none either.
A heuristic once marked it "OK" on the strength of a single capture/restore site; nobody has read
the raw ones. Whoever picks this up should treat the
verdict as unknown rather than inherit an unearned pass.

**Left unfixed on purpose.** Neither belongs to ETP-5046, and widening an already large merge to
carry them would make it harder to review, not safer. They want their own ticket — and the fix is
mechanical once the pattern is recognised, which is the entire reason this section exists.

### 🟡 4.8 `priceId` still reaches the browser on two develop paths

**Ticket:** related develop ETP-5463 and the ETP-5046 review (W3); no ticket owns it — ETP-5049 is the natural home if it touches the account billing UI.

The plan catalog and the checkout request never carry a provider price id (`buildPlanJson`,
`api.js`: "the server never sends a provider price id"). Two develop-owned responses still do:

- `HostedCheckoutService.buildResult` / `createProviderSession` put `priceId` into the
  `POST /sws/go/checkout/sessions` and `POST /sws/go/billing/purchases` answers (develop's
  ETP-5463 shape, kept by the merge);
- `GET /sws/go/billing/offers` returns `offer.getPriceId()`.

Neither is exploitable in the sense that matters — checkout has no request field for a price, so a
client cannot choose one — but both contradict the documented rule and hand a caller the account's
Stripe price ids. Dropping them needs a check that no develop consumer (account billing UI) reads
the field. Raised in the ETP-5046 review (W3); left out of scope of the merge.

### 🟡 4.9 No short-TTL cache on Stripe price lookups

**Ticket:** related ETP-5046 review (S1); no ticket — performance follow-up, needs its own.

`GET /sws/go/plans` (legacy fallback: `retrieveConfiguredPrice()`) and every checkout
(`StripePriceService.retrievePrice` on the plan's `PROVIDER_PRICE_ID`) call Stripe synchronously,
once per request. The price for a given id is effectively immutable, so a small per-id cache with a
short TTL (minutes) would remove that round-trip and the dependency of the plans page on Stripe
latency; the checkout path must keep failing closed on a lookup error rather than serving a stale
miss. Raised in the ETP-5046 review (S1); not implemented.

### 🟡 4.10 `ETARC_VECTOR_SOURCE.DISTANCE_METRIC` is exported by this module before its column exists

**Ticket:** owner ETP-5118 / ETP-5335, outside the billing block.

Cross-repo inconsistency found during the ETP-5046 develop merge; owner **ETP-5118 / ETP-5335**,
not this block. This module's `src-db/database/sourcedata/ETARC_VECTOR_SOURCE.xml` on `develop`
(`dbf7317c`, ETP-5335, "Refresh exported database metadata after the regen") sets
`DISTANCE_METRIC` on its vector-source rows. The table belongs to `com.etendoerp.db.extended`, and
that column exists there only on the unmerged `origin/feature/ETP-5118` (`b528ac4`, "Add filter
column and distance metric to vector source") — not on its `develop`, `main` or `epic/ETP-3504` (as
of 2026-09-24). An environment whose `db.extended` lacks ETP-5118 has no column to hold the value,
so its next `export.database` rewrites the XML **without** those values — a silent diff in this
module's sourcedata that looks like someone deleted them.

Until ETP-5118 merges: do not commit an `ETARC_VECTOR_SOURCE.xml` export that drops
`DISTANCE_METRIC` — revert that file from the export instead. Resolved when ETP-5118 lands in
`db.extended` `develop`, or when ETP-5335 re-exports without the column.

### 🟡 4.11 Two Stripe price parsers, with different rules — `StripeApiClient` only half adopted

**Ticket:** related ETP-5046 (`58ea090d`); no ticket — needs its own.

ETP-5046 (`58ea090d`) put the Stripe transport behind `StripeApiClient` /
`HttpUrlConnectionStripeApiClient`, but only `HostedCheckoutService` and
`PlanPriceDerivationHandler` use it. `StripePriceService` (the price lookup of every checkout and of
`GET /sws/go/plans`) and `StripeCustomerPortalService` still open their own `HttpURLConnection`.
Nothing blocks the migration; it was simply left out of scope.

That leaves the same Stripe price parsed twice, and the two parsers disagree:

| Price as Stripe returns it | Plan save (`PlanPriceDerivationHandler`) | Checkout (`StripePriceService.parsePrice`) |
|---|---|---|
| `active` field absent | accepted (`optBoolean("active", true)`) | **refused** — must be explicitly `true` |
| Fractional minor units (e.g. 4999.5 cents, `unit_amount` null) | accepted — reads `unit_amount_decimal` | **refused** — needs a whole `unit_amount` |
| `interval_count` absent | accepted — defaults to 1 | **refused** — defaults to 0 |
| One-time (non-recurring) price | refused (`ETGO_PlanPriceNotRecurring`) | accepted — becomes `payment` mode |

The first three rows are the dangerous ones: a plan saves cleanly, is listed as purchasable, and the
buyer's checkout then fails on it. The old transport also turns every failure into a generic
`IOException`, so on the checkout path "that price does not exist" (a completed 4xx) and "Stripe
was unreachable" are indistinguishable — the split `StripeResponse` / `StripeTransportException`
exists to prevent exactly that.

Fix: move both services onto `StripeApiClient` and have plan save and checkout share ONE price
parser, so a price that saves is a price that can be bought. Their specs
(`StripePriceServiceTest`, `StripeCustomerPortalServiceTest`) then use the recording fake instead
of static `CheckoutConfiguration` mocks. Found after the ETP-5046 develop merge; not implemented.

### 🟡 4.12 `NeoAuthenticatorEnvironmentAccessTest` re-tests the shared auth pipeline (ETP-5047)

**Ticket:** owner ETP-5047; related ETP-5455.

`schemaforge/NeoAuthenticatorEnvironmentAccessTest` (572 lines, 16 tests) was written for ETP-5443,
when NEO had three separate auth paths — the cookie session (`applySessionContext`), the legacy
Bearer JWT (`authenticateJwt`) and the OAuth2 client-credentials token
(`authenticateOAuth2Token`) — each running its own access check (`enforceEnvironmentAccess`), so
each path needed its own cases. ETP-5455 collapsed all three into the shared
`EnvironmentRequestAuthenticator` pipeline, and the ETP-5046 → ETP-5047 merge rewired the class onto
it, but kept its per-scheme structure. Its class javadoc still names the four removed methods; none
of them exists in `NeoAuthenticator` any more.

Most of its tests now assert what two other classes already pin at the pipeline level:

| Tests in `NeoAuthenticatorEnvironmentAccessTest` | Already covered by |
|---|---|
| 9 decision cases: cookie and OAuth2 × `SUBSCRIPTION_REQUIRED` / `DEMO_TRIAL_EXPIRED` / `ALLOWED` / null decision, plus the Bearer `SUBSCRIPTION_REQUIRED` case | `auth/EnvironmentRequestAuthenticatorTest.everySchemeGetsTheOutcomeItsPolicyDictates` — a 36-case matrix (3 `SurfacePolicy` × 3 `AuthScheme` × 4 decisions) asserting the 402-or-authenticated outcome, the message, the denial's decision and that the policy is consulted exactly once |
| 2 kill-switch cases (switched off / explicitly false) | `EnvironmentRequestAuthenticatorTest.theEnforcementKillSwitchLetsABlockedTenantThrough` (`NEO_API`, `NEO_DATA`) plus `payment/EnvironmentAccessEnforcementFlagTest` (every spelling of the flag value) |
| 2 OAuth2 401 cases (missing identity, insufficient scope — access never asked) | the matrix's rejected-scheme branch, `EnvironmentRequestAuthenticatorTest.anOAuth2TokenWithAReadScopeCannotWrite`, and `NeoAuthenticatorSchemeParityTest.theAccessPolicyIsConsultedExactlyOnceOn{Cookie,Jwt,OAuth2}Path` |
| 2 three-scheme parity cases (same refusal message, same allowed outcome) | mostly by construction now — one pipeline and one 402 writer; the matrix asserts parity at the outcome level |

Two things are **not covered anywhere else** and must survive any trim:

- `theSharedGuardIsAskedForTheSessionTenantUnderTheNeoLabel` — the guard is asked with
  `(clientId, "neo")`. The entry-point label is NEO's own argument to the pipeline; nothing else
  checks it.
- The `assertRefusedWith402` helper — NEO writes the guard's **structured** body through
  `writeResponse` (HTTP 402, exactly `message`, `status`, `code = ENVIRONMENT_ACCESS_DENIED`,
  `decision`) and never the plain-text `sendError`. That is the one decision `NeoAuthenticator`
  itself makes on top of the pipeline outcome.

**Suggested shape:** ~4 tests, ~150–200 lines, down from 572:

1. A blocked tenant gets the structured 402 through `writeResponse`, never `sendError` —
   parameterized over the two refusal decisions (optionally also over the three schemes, which
   keeps a cheap "same body on every scheme" check).
2. An allowed or null decision authenticates, with neither `writeResponse` nor `sendError` called.
3. The guard is asked with `(clientId, "neo")`.
4. A non-commercial refusal (401 / 403) still goes through `sendError`, not the structured body.
   `NeoAuthenticatorSchemeParityTest` already asserts the `sendError` side of this (401s and the
   CSRF 403) but not that `writeResponse` stays unused, so this can equally be one `never()`
   assertion added there.

Rewrite the class javadoc for the single pipeline at the same time.

Why it matters: every change to the auth pipeline currently has to be mirrored in three test
classes asserting the same thing, which is what made the ETP-5046 → ETP-5047 merge heavy. Low
risk — test-only, no behaviour change — and the lowest priority in this section. Raised by Martin
on 2026-09-28, after the ETP-5046 → ETP-5047 merge; not yet actioned.

## 5. Handed forward to later tickets

### 🟠 5.1 ETP-5051: "no quota row" means UNLIMITED

**Ticket:** owner ETP-5051.

`ETGO_PLAN_QUOTA.INCLUDED_QTY` is `required="true"` with **no default**, in the DDL *and* in
`AD_COLUMN.DEFAULTVALUE`. This deliberately breaks the module's own pattern — every comparable
required DECIMAL here carries `<default>0</default>`.

A `LEFT JOIN ... COALESCE(included_qty, 0)` in the evaluator silently caps every unquota'd resource
at zero. `PlanQuotaSchemaInvariantTest` guards the schema side (mutation-tested); **nothing can
guard the evaluator except this paragraph.** `ETGO_PLAN_QUOTA` is also the only new table with
`ISDELETEABLE='Y'`, because deleting the last quota row is how an operator restores unlimited.

The rest of the quota definition ETP-5051 inherits is in §5.5–§5.9; usage per subscription, which
no ticket owns yet, is in §5.10 and §5.12.

---

**Quota definition — owner ETP-5051.**

Facts checked against the DDL, `SubscriptionService` and the PRD on 2026-09-24; the ETP-5051 scope
is quoted from its Jira text as read that day.

### 🟠 5.5 Consumption window = the subscription billing period — decided, filled from ETP-5047 on

**Ticket:** owner ETP-5051 (reads the period); ETP-5047 populates it.

**Decided, not open.** There is deliberately no period column on `ETGO_PLAN_QUOTA`. The PRD fixes
the window (§4 decisions table, "Consumption window: the **subscription billing period**, never the
calendar month"; restated in the invariants table), and ETP-5051 reads it from
`ETGO_SUBSCRIPTION.CURRENT_PERIOD_START` / `CURRENT_PERIOD_END`. An upgrade does not reset it — the
Stripe anchor is preserved (PRD §8, ETP-5053).

**What ETP-5051 must know:** since ETP-5047 `CURRENT_PERIOD_START/END` hold **Stripe's billing
period and nothing else** — the grace anchor moved to `GRACE_ANCHOR` (§3.7). They are filled by
the first `customer.subscription.updated` or `invoice.paid` a row receives, so a row is periodless
until then: `openSubscription` writes neither column, and rows written before ETP-5047 may still
carry the old grace anchor in `CURRENT_PERIOD_END` with `CURRENT_PERIOD_START` NULL (read that
shape as "no period"). §5.9 covers the periodless cases.

### 🔴 5.6 Stock versus flow aggregation over the period — owned by nobody

**Ticket:** no ticket — falls between ETP-5050 and ETP-5051; needs an owner.

The same problem as §1.1, one level up. `ETGO_USAGE_DAILY` stores one `QTY` per tenant,
resource and `USAGE_DAY`. Summing the days of a period is right for a **flow** (posted sales
invoices) and wrong for a **stock** (active users, productive environments — daily snapshots): 5
users every day for 30 days sums to 150. ETP-5050 delivers daily counts only (its design §10
excludes "rollup of any kind — SUM or MAX over a period") and ETP-5051 does not define it either.

**To decide:** an aggregation per **resource** — proposal: a column on `ETGO_BILLING_RESOURCE`
(`sum` for flows, `max` or `last` for stocks). It is a property of what is measured, not of the
quota, so it does not belong on `ETGO_PLAN_QUOTA`. `ETGO_BILLING_RESOURCE.COUNTING_MODE` (`D`
declarative HQL / `S` named strategy) says how a day is counted, not how days combine, so nothing
existing covers it.

### 🔴 5.7 `ETGO_PLAN_QUOTA.CONSUMPTION_SOURCE` means something else in ETP-5051

**Ticket:** owner ETP-5051.

ETP-5046 shipped `CONSUMPTION_SOURCE` (String, list reference `ETGO_QuotaConsumptionSource`, single
`AD_REF_LIST` value `sum`, check `ETGO_PLNQTA_SOURCE_CHK`: `NULL OR = 'sum'`) — i.e. an aggregation.
ETP-5051 defines the field as the **data source**: the daily aggregate (lagged by up to a day)
versus a live count at evaluation time. The two meanings do not overlap, and aggregation belongs on
the resource anyway (§5.6).

**To decide in ETP-5051, before any quota row uses the column:** its values, then change the check
constraint, the `AD_REF_LIST` values and the reference description together. It is cheap now — no
`ETGO_PLAN_QUOTA` row exists anywhere (no sourcedata, no data-fix creates one) — and a data
migration once operators have filled it in.

### 🟠 5.8 A quota on a yearly plan is a yearly quota

**Ticket:** owner ETP-5051 (operator docs and window help).

`ETGO_PLAN_INTERVAL_CHK` allows `month` and `year`, and the window is the billing period (§5.5), so
`INCLUDED_QTY` on a `year` plan is consumed over the whole year, not per month. Correct by the
design, surprising to an operator. **Must be stated** in the operator docs and in the **Plans** /
**Quotas** window help when ETP-5051 makes quotas live. (Annual intervals are also listed as
deferred in PRD §16, yet the schema already accepts them.)

### 🔴 5.9 A subscription with no current period needs a defined answer

**Ticket:** owner ETP-5051.

Periodless rows (§5.5): a row until its first period-bearing event, rows backfilled by R37
(period NULL, design §7.0) and rows in the pre-ETP-5047 shape. A `canceled` row keeps its last
period since ETP-5047 (it is closed, `END_DATE` set — §3.7), so it has a window but no longer a
current one. **To decide in ETP-5051:** what the evaluator does with no
window — skip, treat as unlimited, or use the last known period. Latent today only because
`legacy-productive` has no quota rows (§5.1); the first quota on a plan such a tenant can be on
makes it live.

---

**Usage per subscription — owner: the ticket that introduces usage-based charging.**

PRD §3 and §16 defer overage charging "with its own PRD"; ETP-5047 records the billed periods on
the billing ledger (§5.10).

### 🔴 5.10 Per-period usage cannot be produced — the usage per period is not recorded, the periods only on the ledger

**Ticket:** no ticket — ETP-5047 records the billed periods on the ledger (whether that suffices
is still open); storing usage per period belongs to the not-yet-created usage-based charging
ticket.

Overage billing bills a **closed** billing period, so it needs two things this block records
neither of:

- **The periods themselves.** `ETGO_SUBSCRIPTION` has one period slot and no history; once it is
  filled (§5.5), each renewal overwrites it. **Since ETP-5047 the ledger records the billed
  period:** for `invoice.*` events `WebhookPayloadSummary` keeps `service_period_start` /
  `service_period_end` (epoch seconds), so every `invoice.paid` row of `ETGO_BILLING_EVENT` carries
  the period it paid for from the deploy onwards. It is the invoice **line** period — the same
  extraction (`SubscriptionLifecycleApplier.invoiceServicePeriod`) that fills the row — not the
  invoice's own `period_start`/`period_end`, which on a subscription invoice looks back one period.
  One caveat: the summary is an abbreviated JSON string, not a queryable column. Periods before the
  ETP-5047 deploy were never recorded and cannot be backfilled from local data.
- **The usage per period.** `ETGO_USAGE_DAILY` (`MEASURED_CLIENT_ID`, `ETGO_BILLING_RESOURCE_ID`,
  `USAGE_DAY`, `QTY`, `IS_SETTLED`) has no link to a subscription. It joins one only **by value**
  (`ETGO_SUBSCRIPTION.ENVIRONMENT_CLIENT_ID = MEASURED_CLIENT_ID`, `USAGE_DAY` inside the period),
  which ETP-5051 computes on the fly for evaluation only. ETP-5048's reconciliation covers payments
  and subscription status, not usage.

The second depends on the first: usage cannot be attributed to a past period whose boundaries were
never recorded.

**To decide:**

1. **Whether the ledger's recorded period is enough** for overage billing of a closed period, or
   a small period-history table (a queryable column) is needed. The urgent half — starting to
   record — is done since ETP-5047.
2. **Whether a period's usage is ever stored, and by whom** — or always recomputed from
   `ETGO_USAGE_DAILY` over the recorded period.

### 🔴 5.12 The would-have-billed report is scheduled in the PRD and excluded by its owner

**Ticket:** assigned to ETP-5050 by the PRD but excluded by its design — effectively no ticket; needs an owner.

PRD §14 assigns "would-have-billed report. Shadow mode" to **ETP-5050** (PRD §6 motivates the
historical backfill by shadow mode). ETP-5050's design (`plans/2026-09-15-etp-5050-usage-measurement-design.md` §10) lists
"Rollup of any kind — SUM or MAX over a period, the would-have-billed figure, price" as **out of
scope**. Nobody delivers it now. **To decide:** which ticket owns it — it also depends on §5.6
(how days combine) and §5.5 (which period).

### 🔴 5.13 `END_DATE` records what happened, never what will happen — and any value closes the row

**Ticket:** owner ETP-5053 (the `END_DATE` guard or meaning change). Closing the row on cancel was
settled in ETP-5047 (§3.7).

**What it means today.** `END_DATE` is past tense: the moment this row stopped being the tenant's
current row. Its writers: a cancellation (ETP-5047 — Stripe's `ended_at`, else `canceled_at`, else
now; never before `START_DATE`), `openSubscription` closing a stale canceled row before a
re-subscription (ETP-5047, now), and a plan change (ETP-5053), which sets it to *now* and inserts
the successor in the same transaction. `openSubscription` sets a new row's `END_DATE` to null and
the R37 backfill always writes null.

**Future intent is not expressed on `END_DATE`; Stripe owns the timing.**

- A scheduled **downgrade** lives in `PENDING_PLAN_ID` / `PENDING_EFFECTIVE_DATE` on the open row
  (PRD §8.3). Stripe's Subscription Schedule emits `customer.subscription.updated` at the period
  boundary, and only then is the row closed and its successor opened.
- A scheduled **cancellation** (`cancel_at_period_end`) is not stored locally at all:
  `SubscriptionLifecycleApplier` deliberately ignores the flag so access continues, and the
  Subscription page reads it live from Stripe. `customer.subscription.deleted` at the boundary sets
  `STATUS = canceled` and closes the row (ETP-5047).
- An **upgrade** is immediate (PRD §8.1), so it never needs a future date.

**Cancellation closes the row — settled in ETP-5047 (§3.7).** A closed row reads as `canceled`
whatever its `STATUS` says, `findLatest` keeps a canceled tenant on the row route (never the
preference fallback), and a later paid checkout opens a fresh row with its own price snapshot.

**Closing on cancel also reopens an R37 edge (accepted risk, re-check before shipping it).** R37's
backfill branch (A) still keys on "active productive marker AND no **open** row", while its
retirement branch only needs *any* row (design doc §8). Develop's R42 can re-insert that marker for
a paid-provisioned tenant regardless of its rows (§3.2). So a tenant whose **only** subscription row
is closed, and whose chain then runs R42 → R37, gets the marker from R42 and then a **fresh open
`legacy-productive` row** from R37 branch (A) before the marker is retired — a tenant that
canceled can read as productive again. R42 skips tenants with a `REFUNDED`/`CANCELED`/`EXPIRED`
checkout request or an `ETGO_SubscriptionStatus` other than `CURRENT`/`LEGACY_ENTITLEMENT`/`PAST_DUE`, which narrows the
window but is not a guard on the subscription row. The edge is unreachable while nothing writes
`END_DATE`; the ETP-5047 change that closes rows on cancel must re-check it (and, if reachable, ship
a new dated fix — R37 and R42 cannot be edited once applied).

**The trap: "open" ignores the dates.** `OPEN_ROW_PREDICATE` in `SubscriptionService` is
`endDate is null and active = true`; the partial unique index `ETGO_SUB_OPEN_ENVCLIENT_UQ` uses the
same condition, and `START_DATE` is never compared with now. Therefore:

- a **future-dated** `END_DATE` closes the row **immediately** — the tenant reads as canceled
  (`findLatest`, §3.7) and so `free` while still inside a period it paid for;
- a successor inserted with a **future** `START_DATE` becomes the current row **immediately** — plan
  and quotas switch early, and lifecycle webhooks land on it.

Nothing rejects either shape: `ETGO_SUB_DATES_CHK` only checks `END_DATE >= START_DATE`.

**To decide (ETP-5053, before the first writer of `END_DATE` lands)** — one of:

1. **Keep the past-tense meaning and guard it.** Every writer sets `END_DATE` to the current
   timestamp and nothing else; pin that with a spec, and add a write-side assertion or DB trigger
   that rejects a future value. Cheap, and matches the PRD (no future-dated rows anywhere).
2. **Change the meaning to "valid until".** Make "open" date-aware —
   `startDate <= now and (endDate is null or endDate > now)` in `OPEN_ROW_PREDICATE` and every SQL
   copy of it (R37, reconciliation). A partial index predicate cannot reference `now()`, so "one
   current row per tenant" would have to move to an exclusion constraint on the date range or into
   code. Only worth it if future-dated rows become a requirement (e.g. scheduled upgrades, which
   the PRD does not have).

Option 1 is the default unless product asks for scheduled upgrades.

### 🟠 5.14 ETP-5049: the upgrade page's Plan step does not show the plan catalog

**Ticket:** owner ETP-5049 (plan selection in the customer UI). Introduced by the ETP-5046 develop
merge, which combined two independent designs of the same page.

`UpgradePage.jsx` (schema_forge) is a three-step checkout — **Plan → Add-ons → Payment** — from
develop (ETP-5396). Its **Plan** step is a fixed layout, not a view of the catalog:

- one `PlanCard` (`upgrade-plan-productive`) whose name and tagline are the static translations
  `upgradePlanProductiveName` / `upgradePlanProductiveTagline` ("Productivo" / "Un segundo entorno
  para trabajar de verdad"), whatever the catalog holds;
- two `SkeletonPlanCard` placeholders (`upgrade-plan-coming-soon-1/-2`).

Only the **price** on that card comes from the catalog (`formatPlanPrice` of the selected plan, or
of the first one). ETP-5046's catalog chooser, `PlanSelector` (`upgrade-plan-single` for one plan,
`upgrade-plan-choice` for several), renders only on the **Payment** step. Consequences:

- a catalog plan's name and description are first visible two steps after the buyer "chose" it;
- with several plans, the "Productivo" card quotes whichever plan is selected, or the first one,
  under a name that belongs to none of them;
- while the catalog price equals the legacy fallback price, the Plan step looks identical before
  and after the cutover (§2.1), which reads as "the new plan does not show" during testing.

Expected shape: build the Plan step from `GET /sws/go/plans` — one card per plan, "Elegir plan"
selects its `planKey` — keeping the static card only while the catalog cannot be read, and let the
Payment step show the chosen plan instead of a second chooser. Found in the ETP-5046 manual happy
path test; not implemented.

---

## Related documents

- `plans/2026-09-18-etp-5046-plan-and-subscription-design.md` — the ETP-5046 design of record
- `plans/2026-09-18-etp-5046-stage-b-ad-handoff.md` — the AD authoring record
- `plans/2026-09-15-etp-5050-usage-measurement-design.md` — the usage engine design
- `schema_forge/docs/usage-measurement.md` — §4 is the full flow-versus-stock analysis
- `schema_forge/docs/plans/2026-08-27-recurring-billing-and-resource-limits-prd.md` — the governing PRD
- `feature-flags-and-tenant-upgrade.md` — paywall, plan marker, transitional fallback, the
  environment-access kill switch and how to operate it (§1, "Operating environment-access
  enforcement")
- `schema_forge/docs/stripe-local-testing.md` — lifecycle webhooks, the 402 body and the kill switch
  exercised locally (matrix SF-STRIPE-LOCAL-10…26)
- `schema_forge/docs/plans/2026-09-22-etp-5443-subscription-lifecycle-design.md` — the ETP-5443
  lifecycle design; its storage, correlation and enforcement statements are extended by §3.7/§3.8
- `adr/0001-backend-managed-session.md` — the ETP-5455 amendment: the one auth pipeline and the
  surface policies that decide which endpoints run the §3.8 check
