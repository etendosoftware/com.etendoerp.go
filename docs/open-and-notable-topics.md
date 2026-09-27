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

---

## 3. Legacy plans and the cutover

### 🟠 3.2 Phase F — deleting the transitional plan code — has no ticket

**Ticket:** no ticket — needs its own once R37 has run on every environment.

The `ETGO_TenantPlan` preference is retired per tenant (R37 and the paid onboarding), and a
transitional read fallback answers for tenants not reached yet; design doc §8 describes both. What
is open is the cleanup that ends the transition: nothing owns it, so without a ticket the
transitional code stays indefinitely.

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
  asymmetry of §3.5.

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
access policy's enum.

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
  whichever way the payment itself was recorded.
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
(equal is skipped too). It was renamed to `20260924T150000Z__R37-tenant-subscription-backfill.sql`
before reaching any shared environment; `sql/README.md` rule 3 forbids renaming an *applied* fix,
not an unapplied one. The consequence had it shipped: every paying tenant left on the retired
preference, with §3.2's end condition never reached.

**Before merging any branch that carries a data-fix, re-check its timestamp against the newest fix
in the target branch** — and re-date it if it is not strictly newer.

**Guard since ETP-5046:** `schema_forge/cli/test/data-fixes-catalog-ordering.test.js` fails the
build when two fixes share a timestamp prefix (the seven already-applied pairs are frozen by exact
file name in `APPLIED_SHARED_TIMESTAMPS` — a third file on one of those stamps still fails) and
pins R37 strictly after `NEWEST_DEVELOP_FIX_AT_MERGE`. That catches the equal-stamp half of this
trap. The other half — a fix dated *before* the newest fix an environment has already processed —
is invisible to a catalog test, so the rule above stays the author's job; `sql/README.md` "Choosing
the timestamp" states it where fixes are written.

### 🟠 3.7 Lifecycle webhooks write the open subscription row — preferences only without one

**Ticket:** owner ETP-5047 for the open follow-ups (watermark column, period versus grace anchor, re-subscription); related develop ETP-5443 / ETP-5488 and ETP-5046.

Develop's ETP-5443 wired the Stripe lifecycle webhooks (`invoice.paid`, `invoice.payment_failed`,
`customer.subscription.updated`, `customer.subscription.deleted`) into a preference projection
(`ETGO_SubscriptionStatus` / `ETGO_SubscriptionDueAt`). Since the develop merge,
`TenantEnvironmentLifecycleService.updateSubscriptionStatus` routes the outcome per tenant:

- **open `ETGO_SUBSCRIPTION` row** → `SubscriptionService.applyLifecycleStatus` writes `STATUS`
  (`CURRENT→active`, `PAST_DUE→past_due`, `EXPIRED→canceled`) and `CURRENT_PERIOD_END` (the
  applier's grace anchor, the end of the paid period; `null` clears it);
- **no open row** → the preference projection, as before (`ETP-5046-TRANSITIONAL-FALLBACK`).

`readSubscriptionState` reads the same store the write goes to, and `resolve` reads the row first,
so the access policy, `resolvePlan` and the environment list all agree. Every read on both routes
runs in admin mode — the preference reads since ETP-5488, the row reads because
`SubscriptionService` enters admin mode itself, and the transitional `ETGO_TenantPlan` fallback since
the second develop merge — because the NEO access check runs as the calling user and a non-admin
role can read neither `AD_Preference` nor `ETGO_SUBSCRIPTION`.

**Decided behaviour (Martin, 2026-09-24), not an open question:**

- **`canceled` makes the tenant `free` immediately** for `resolvePlan` (only `active`/`past_due`
  are productive). The environment keeps its `ETGO_EnvironmentType = PRODUCTIVE` marker, so the
  access policy still sees a productive environment and applies `EXPIRED`. Onboarding a canceled
  tenant again would therefore run `forceTestModeForFreeTenant` on it, routing its SII /
  TicketBAI / VeriFactu submissions to the test endpoints — a compliance stake, not a config nit.
- **`canceled` does not close the row** (`END_DATE` stays null). Closing a row is how a plan change
  opens its successor (ETP-5053); a later re-subscription of the same tenant is not modelled yet —
  `openSubscription` returns the existing open row untouched. Whether it should is §5.13.
- **The event-ordering watermark (`ETGO_SubscriptionEventAt`) stays a preference for both
  routes.** It is webhook-stream metadata, not subscription state, and the row has no column for
  it. Follow-up for ETP-5047: it could move onto `ETGO_SUBSCRIPTION` as its own column (new AD
  column), which would let the row route drop its last preference read.
- `CURRENT_PERIOD_END` on the row now means "grace anchor" as the applier computes it, not Stripe's
  rolling billing window (§4 of the design doc). Nothing else writes it today; ETP-5047 should
  decide whether to split the two.
- The development lifecycle tool mirrors a `CURRENT`/`PAST_DUE`/`EXPIRED` status onto the row too,
  or it would stop affecting every tenant that has one. **`NONE` and `LEGACY_ENTITLEMENT` have no
  row status, so once a tenant has a row the tool's choice of either is ignored:** it is written
  to the preference, which the row route no longer reads, and the tenant keeps the row's status.
  Noted by QA; accepted for a development-only tool rather than inventing row statuses for them.
- **The webhook installs its own system context.** It runs with `OBContext == null`; develop only
  worked because two stores leaked a system context, and the ETP-5045/5046 fixes that restore the
  caller's context broke every correlated lifecycle event (NPE in the preference write → `FAILED`,
  500). `applySubscriptionLifecycle` now runs through `SystemContext.run` (capture, install,
  quiet unwind — shared with `CheckoutRequestStore`/`BillingEventStore`) and
  `setPreference` runs in admin mode; `CheckoutWebhookEndpointIntegrationTest` pins both routes.
  Design doc §8.4 has the full story — the lesson generalises to any context-less caller.
- **The backfill carries the preference state onto the row.** R37 seeds `STATUS` from
  `ETGO_SubscriptionStatus` (same mapping as above, plus `NONE → canceled` so a locked-out tenant
  stays locked out; absent/`LEGACY_ENTITLEMENT`/unknown → `active`) and
  `CURRENT_PERIOD_END` from `ETGO_SubscriptionDueAt`, reading both by `AD_CLIENT_ID` (they are
  owned by the tenant, unlike the `ETGO_TenantPlan` marker, which is scoped by
  `VISIBLEAT_CLIENT_ID`). Without that, the row — which wins once it
  exists — would have reset every past-due or expired tenant to paying. Design doc §7.0.

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
| `rest/EtendoGoJwtServlet` | ❓ **unaudited** — 28 raw system installs (counted 2026-09-28); the lifecycle webhook is the one site routed through `payment/SystemContext` |

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

**`EtendoGoJwtServlet` — deliberately left as a question.** 28 installs and one capture/restore is
not evidence of 27 leaks, and it is not evidence of none either. A heuristic marked it "OK" on the
strength of that single site; nobody has read the other 27. Whoever picks this up should treat the
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

### 🟠 5.2 ETP-5047: correlate to the OPEN row

**Ticket:** owner ETP-5047.

`STRIPE_SUBSCRIPTION_ID` is deliberately **not unique**: a plan change updates the item on the same
Stripe subscription while opening a *new* Etendo row, so two local rows legitimately share one id.
Lifecycle webhooks must resolve to the row with `END_DATE IS NULL`, not to "the" row.

---

**Quota definition — owner ETP-5051.**

Facts checked against the DDL, `SubscriptionService` and the PRD on 2026-09-24; the ETP-5051 scope
is quoted from its Jira text as read that day.

### 🟠 5.5 Consumption window = the subscription billing period — decided, but no row carries one yet

**Ticket:** owner ETP-5051 (reads the period); ETP-5047 must populate it first.

**Decided, not open.** There is deliberately no period column on `ETGO_PLAN_QUOTA`. The PRD fixes
the window (§4 decisions table, "Consumption window: the **subscription billing period**, never the
calendar month"; restated in the invariants table), and ETP-5051 reads it from
`ETGO_SUBSCRIPTION.CURRENT_PERIOD_START` / `CURRENT_PERIOD_END`. An upgrade does not reset it — the
Stripe anchor is preserved (PRD §8, ETP-5053).

**What ETP-5051 must know:** today **no subscription row carries a billing period.**
`SubscriptionService.openSubscription` writes neither column; `applyLifecycleStatus` only ever
*nulls* `CURRENT_PERIOD_START` and writes `CURRENT_PERIOD_END` as the lifecycle **grace anchor**
(set on `PAST_DUE`, cleared to null on `CURRENT` and `EXPIRED` — §3.7). So an `active` row has both
columns null. Populating the real Stripe period (and splitting it from the grace anchor) is
ETP-5047's call per §3.7; the evaluator cannot be built on these columns until that happens.

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

Beyond §5.5's "no row has a period yet", two cases stay periodless by nature: rows backfilled by
R37 (`CURRENT_PERIOD_START` always NULL, design §7.0) and a `canceled` row (`CURRENT_PERIOD_END`
cleared, `END_DATE` still null — §3.7). **To decide in ETP-5051:** what the evaluator does with no
window — skip, treat as unlimited, or use the last known period. Latent today only because
`legacy-productive` has no quota rows (§5.1); the first quota on a plan such a tenant can be on
makes it live.

---

**Usage per subscription — owner: the ticket that introduces usage-based charging.**

PRD §3 and §16 defer overage charging "with its own PRD"; recording the periods fits ETP-5047.

### 🔴 5.10 Per-period usage cannot be produced — neither the periods nor the usage per period are recorded

**Ticket:** no ticket — recording the periods fits ETP-5047 (it handles `invoice.paid`, which
carries them); storing usage per period belongs to the not-yet-created usage-based charging ticket.

Overage billing bills a **closed** billing period, so it needs two things this block records
neither of:

- **The periods themselves.** `ETGO_SUBSCRIPTION` has one period slot and no history; once
  ETP-5047 fills it, each renewal overwrites it. `ETGO_BILLING_EVENT` does not keep invoice periods
  either: `PAYLOAD_SUMMARY`'s allow-list (`WebhookPayloadSummary`) is `id, customer, subscription,
  livemode, payment_status, amount_total, currency, mode` — no `period_start`/`period_end`.
- **The usage per period.** `ETGO_USAGE_DAILY` (`MEASURED_CLIENT_ID`, `ETGO_BILLING_RESOURCE_ID`,
  `USAGE_DAY`, `QTY`, `IS_SETTLED`) has no link to a subscription. It joins one only **by value**
  (`ETGO_SUBSCRIPTION.ENVIRONMENT_CLIENT_ID = MEASURED_CLIENT_ID`, `USAGE_DAY` inside the period),
  which ETP-5051 computes on the fly for evaluation only. ETP-5048's reconciliation covers payments
  and subscription status, not usage.

The second depends on the first: usage cannot be attributed to a past period whose boundaries were
never recorded.

**To decide:**

1. **Record each billing period as it happens** — a small period-history table, or the invoice
   period kept on the `invoice.paid` billing event. The urgent half: it costs little now, and
   periods that were never recorded cannot be backfilled from local data.
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

**Ticket:** owner ETP-5053 (the `END_DATE` guard or meaning change); ETP-5047 for closing the row on cancel.

**What it means today.** `END_DATE` is past tense: the moment this row stopped being the tenant's
current row. Its only intended writer is a plan change (ETP-5053), which sets it to *now* and
inserts the successor in the same transaction. Nothing writes a non-null value yet —
`SubscriptionService#openSubscription` sets it to null and the R37 backfill always writes null.

**Future intent is not expressed on `END_DATE`; Stripe owns the timing.**

- A scheduled **downgrade** lives in `PENDING_PLAN_ID` / `PENDING_EFFECTIVE_DATE` on the open row
  (PRD §8.3). Stripe's Subscription Schedule emits `customer.subscription.updated` at the period
  boundary, and only then is the row closed and its successor opened.
- A scheduled **cancellation** (`cancel_at_period_end`) is not stored locally at all:
  `SubscriptionLifecycleApplier` deliberately ignores the flag so access continues, and the
  Subscription page reads it live from Stripe. `customer.subscription.deleted` at the boundary sets
  `STATUS = canceled`.
- An **upgrade** is immediate (PRD §8.1), so it never needs a future date.

**Cancellation does not set `END_DATE` — maybe it should.** A `canceled` row stays open
(`END_DATE` null, §3.7) and reads as free through `STATUS`. So "the open row" means "the latest
row", not "the live subscription" — which is why re-subscription is unmodelled (§3.7:
`openSubscription` returns the canceled row untouched) and why §5.9 has a periodless canceled row.
**To decide (ETP-5047):** whether `customer.subscription.deleted` should also close the row
(`END_DATE = now`), so a later paid checkout opens a fresh row with its own price snapshot. Weigh
it against the fact that a tenant with no open row leaves the row route entirely: `resolvePlan`
falls through to `TenantPlanPreferenceFallback`, and `applyLifecycleStatus` returns false so later
lifecycle events land on the preference projection (§3.7). Closing on cancel changes both.

**The trap: "open" ignores the dates.** `OPEN_ROW_PREDICATE` in `SubscriptionService` is
`endDate is null and active = true`; the partial unique index `ETGO_SUB_OPEN_ENVCLIENT_UQ` uses the
same condition, and `START_DATE` is never compared with now. Therefore:

- a **future-dated** `END_DATE` closes the row **immediately** — the tenant drops to the preference
  fallback and then to `free` while still inside a period it paid for;
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
- `feature-flags-and-tenant-upgrade.md` — paywall, plan marker, transitional fallback
