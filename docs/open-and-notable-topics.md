# Open and notable topics — recurring billing block (ETP-5045 … ETP-5053)

A living register of the decisions still owed, the operational constraints that are not visible
from the code, and the traps that would otherwise be rediscovered the hard way.

**Scope:** the whole billing development — ETP-5045 (durable checkout state), ETP-5050 (usage
measurement), ETP-5046 (Subscription Plan Catalog + subscriptions), and what they hand to ETP-5047/5048/5051/5053.

**Status key:** 🔴 decision owed · 🟠 constraint to respect · 🟡 known issue, worked around

**This register carries only what is still live.** A topic is deleted once it is fixed or settled —
it does not graduate to a "closed" section. The history stays in the commit that resolved it, which
is the only copy that cannot drift from the code. **Section numbers are stable**: other documents
cite them (`§3.7`, `§4.8`), so a deleted topic leaves a gap in the numbering rather than shifting
its neighbours.

---

## 1. Decisions still owed

### 🔴 1.1 `activeUsers` — data source, and flow versus stock (ETP-5050)

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

Full analysis: `schema_forge/docs/usage-measurement.md` §4.

### 🔴 1.2 `productiveEnvironments` is a flow wearing a stock's name

Specified as `Client` bucketed by `creationDate` — which is option 3 above *by accident*. It
counts clients **created** that day (a flow) while the billable quantity is how many exist (a
stock). With no rollup yet the difference does not bite, but **storing a flow under a stock's name
makes every later reading of it wrong**, including any quota built on it in ETP-5051.

---

## 2. Deployment — constraints that are not visible in the code

### 🟠 2.1 The legacy price fallback — why it exists and when it switches itself off

`etendo.go.checkout.price.id` was deleted "with no fallback" on this branch, which made two deploy
constraints hard: a priced `ETGO_PLAN` row had to exist before the code went live (no sourcedata
row can carry a real, environment-specific Stripe price id), and the module and the app-shell had
to ship together (`planKey` required, no default). The develop merge reinstated the property as a
**transitional fallback** to remove both.

**Activation rule — one predicate, `PlanCatalogService.isLegacyFallbackActive()`:** the property is
non-blank **and** no active plan carries a provider price id. `GET /sws/go/plans` and checkout both
call it, so the list never offers what checkout refuses. While active, the list is exactly
`legacy-productive` quoted from the Stripe price (`retrieveConfiguredPrice()`; a failed lookup omits
it, never 500), and a checkout naming `legacy-productive` — or no plan — sells that price and
records `legacy-productive` plus the charged price id on the request.

**Retirement is automatic.** The first priced plan makes the predicate false on the next request:
no redeploy, no property change. The property can be removed afterwards, with one visible effect:
`GET /sws/go/billing/offers` still reads it and then answers `503 BILLING_OFFER_UNAVAILABLE` (see
the price-source bullet below). The step-by-step operator procedure is the design doc's §6.2.

Things to know while it is active:

- **The reload edge case.** A buyer whose page loaded the fallback list before the first priced plan
  appeared submits `legacy-productive` and gets `400 PLAN_NOT_AVAILABLE`; the page shows
  `upgradePlanNotAvailable` ("reload the page"). Nothing is charged.
- **Fallback buyers are unlimited.** They land on `legacy-productive`, which has zero quota rows
  (§5.1): once ETP-5051 enforces quotas they will not be capped. Their subscription row snapshots
  the charged price id but no amount/currency (the plan has none). Moving them to a real plan is a
  plan change (ETP-5053).
- **The list shows the grandfathered plan's own name** — `ETGO_PLAN.NAME` of `legacy-productive`
  ("Legacy Productive (grandfathered)"); edit the row if buyers should see something else. Its
  `description` is always sent **empty**: the row's `DESCRIPTION` is operator documentation that
  names `etendo.go.checkout.price.id`, so it is never shown to a buyer (the row is kept as is).
- **`GET /sws/go/billing/offers` quotes the legacy price, never the plan catalog.** Since develop's
  ETP-5463 the offer has no typed configuration of its own: `BillingOfferConfiguration` derives it
  from the configured Stripe price (`retrieveConfiguredPrice()`). While the fallback is active that
  is exactly the charged price. Once a priced plan retires the fallback the offer keeps quoting the
  legacy price — a different amount from what checkout now charges — and once the property is
  removed it answers `503`. The upgrade page quotes the plan catalog and uses the offer only while
  that lookup is in flight or has failed; any other consumer of the offer is exposed to the
  difference. Pointing the offer at the plan catalog belongs with the plan-selection UI (ETP-5049).
- On an environment with neither a priced plan nor the property, checkout answers
  `PLAN_NOT_AVAILABLE` for the legacy key / no key, and `GET /sws/go/plans` is empty.

### 🟠 2.3 Run the backfill AFTER the deploy, never before

`resolvePlan` reads the subscription first. A tenant provisioned between the schema landing and
the code shipping gets a preference and no subscription. The backfill's `@check` catches exactly
that tenant because it keys on the preference rather than on a date — but only if it runs
afterwards.

The transitional fallback (§3.2) means this ordering is no longer *load-bearing for uptime*, only
for completeness.

### 🟠 2.4 The sandbox attestation is per-environment and blocks merge

`R37`'s `@report` carries the verbatim outcome of a manual pre-check: *re-verify that production
Stripe checkout has not gone live since 2026-08-27; if it has, a real paying cohort exists that
the backfill would orphan and an adoption step is required first.*

The regression test **fails the build** while the `TODO-PRECHECK-R37` marker is present, so it
cannot ship unfilled. The check must be run against the **target** environment — verifying it on a
dev box proves nothing about staging. On the dev box (2026-09-18) the Stripe key is `sk_test`, so
the assumption holds *there*.

### 🟠 2.5 `./gradlew test` needs JDK 17, not the default 21

`build.gradle` targets Java 17; core's bundled Groovy/ASM cannot read Java 21 bytecode.
`:compileTestGroovy` dies with `Unsupported class file major version 65` while compiling core's
own Spock specs — so **no test runs at all**, and the failure looks unrelated to whatever you
changed. `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`. **Confirm what CI uses** before
trusting a green local run.

### 🟠 2.6 Gradle reports `UP-TO-DATE` and runs nothing

A re-run after an unrelated change silently executes zero tests while printing `BUILD SUCCESSFUL`.
Delete `build/test-results/{test,goIsolatedDalTest}` and pass `--rerun`, then read the task outcome
lines — not just the build result. This produced a false "verified" claim once during ETP-5046.

### 🟠 2.7 `Hooks-Verified` seals do NOT survive a rebase, despite claiming to

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

### 🟠 3.1 The grandfathered plan has no price, deliberately

`legacy-productive` ships as module sourcedata with `PROVIDER_PRICE_ID`, `BILLING_INTERVAL`,
`DISPLAY_PRICE` and `CURRENCY_CODE` all NULL, and **zero quota rows**. That makes it unlimited by
definition and a no-op for the price-derivation handler. It is purchasable only through the legacy
price fallback (§2.1), and never on a price of its own.

A backfilled subscription is therefore **not a full billing record**. Its two jobs are **access
control** (`resolvePlan` reads productive) and **webhook correlation** (ETP-5047 matches on
`stripe_subscription_id`, which the backfill copies where a checkout request exists). Neither needs
a price. Where the checkout request recorded one, the charged Stripe price id is copied into
`PROVIDER_PRICE_ID` anyway (NULL otherwise); no amount or currency is ever snapshotted.

This is acceptable *only because* every existing Stripe subscription is Test Mode. If production
checkout has gone live, the answer is not a priceless row — it is the adoption step: read each real
subscription's price from Stripe and record it. The `StripeApiClient` built in ETP-5046 makes that
cheap; it was left out of scope while the sandbox assumption holds.

### 🟠 3.2 The preference is retired per tenant, not fleet-wide

A tenant's `ETGO_TenantPlan` preference is deleted **at the moment it gains a live subscription** —
in `R37`'s `@apply` (same transaction as the INSERT, guarded on an open subscription existing) and
in `applyPaidUpgradeSideEffects` (on a successful subscription write).

A **transitional read fallback** (`TenantPlanPreferenceFallback`) covers the gap: no open
subscription → fall back to the preference, so no paying tenant is ever stranded as free. It applies
to both resolution paths — the single-tenant one and the `EnvironmentPlanCache` bulk one — because
if only one had it, the environment list and `resolvePlan` would disagree about the same tenant,
which is worse than the original bug.

**The observable end condition**, which is the point of doing it this way:

```sql
select count(*) from ad_preference where attribute = 'ETGO_TenantPlan';   -- reaches 0
```

plus the fallback's WARN lines ceasing. When both hold, everything marked
`ETP-5046-TRANSITIONAL-FALLBACK` plus `markProductive` and `PREFERENCE_ATTRIBUTE` can be deleted.
That is a query, not a judgement call.

The WARN is deliberate: a silent fallback would let the backfill be forgotten indefinitely.

### 🟠 3.3 The preference is scoped by `VISIBLEAT_CLIENT_ID`, never `AD_CLIENT_ID`

`Preferences.setPreferenceValue` writes the row at `ad_client_id = '0'` and puts the tenant in
`VISIBLEAT_CLIENT_ID`. Filtering on `ad_client_id` matches **zero rows for every tenant** —
verified on live data: `via_visibleat = 6, via_adclient = 0`.

This mistake nearly shipped twice on ETP-5046. On the read side it silently reclassifies every
paying tenant as free; on the retirement side it is a silent no-op that would block the §3.2 end
condition forever with nothing reporting why.

Note `ETSG_ForceTestMode` uses the **opposite** shape — the row's own `AD_CLIENT_ID` *is* the
tenant, because its handlers resolve on `Preference.client`. The two preferences are not
interchangeable.

### 🟠 3.4 Ordering invariant: R31/R32 versus R37 — a fiscal-compliance stake

`R31` (force test mode on demo/free tenants) and `R32` (its inverse) read `ETGO_TenantPlan`
directly in SQL and know nothing about `etgo_subscription`. If `R31` ever ran for a tenant **after**
`R37` deleted its preference, it would read a paying tenant as free and force
`ETSG_ForceTestMode='Y'` — routing their real SII/TicketBAI/VeriFactu submissions to the tax
authority's **test** endpoints. That is a compliance failure, not a config nit.

Two independent mechanisms prevent it:

- The runner's watermark is a **strict date** (`Math.max` over PROCESSED fix timestamps, "skip
  everything at or before it, no look-back"). Once `R37` (2026-09-24) is processed, `R31`
  (2026-09-01) is skipped forever.
- If `R31` had `FAILED`, the runner halts that tenant's chain, so `R37` could never run for it.

`R31`/`R32` were **not edited** — an applied data-fix is immutable per the framework README and is
superseded by a new dated file, never modified in place.

> **Any FUTURE data-fix must key on `etgo_subscription`, not on `ETGO_TenantPlan`.** After `R37`
> the preference is present only for tenants the backfill has not reached.

---

### 🟠 3.5 An unrecognised subscription status means ENTITLED, never locked out

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
  access policy still sees a productive environment and applies `EXPIRED`. Watch §3.4: onboarding a
  canceled tenant again would run `forceTestModeForFreeTenant` on it.
- **`canceled` does not close the row** (`END_DATE` stays null). Closing a row is how a plan change
  opens its successor (ETP-5053); a later re-subscription of the same tenant is not modelled yet —
  `openSubscription` returns the existing open row untouched.
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
  owned by the tenant, unlike the plan marker of §3.3). Without that, the row — which wins once it
  exists — would have reset every past-due or expired tenant to paying. Design doc §7.0.

## 4. Known issues

### 🟡 4.2 `ETGO_SF_FIELD` rows with a dangling `AD_COLUMN` break `update.database`

Recurring. `update.database` fails `etgo_sf_fld_col_fk` on an `ETGO_SF_FIELD` row whose
`AD_COLUMN_ID` is not in `AD_COLUMN`. **The usual cause is an unpulled module, not bad data**: the
column belongs to another module whose local checkout predates it, so `update.database` has simply
not created it yet. Pull first, then re-check:

```bash
cd modules && grep -rl "<THE_ID>" .        # nothing? pull every module and grep again
for d in */; do [ -d "$d/.git" ] && (cd "$d" && git pull --ff-only); done
```

**Do not delete the `<AD_COLUMN_ID>` line.** That advice used to stand here and was reversed on
2026-09-21: during ETP-5046 it removed a valid reference (`Invoice_Date` on
`etvfac_inv_sent_status_v`, added to `com.etendoerp.verifactu` by ETP-5229 — the local checkout was
simply behind), which made `update.database` green while silently desynchronising this repo from
schema_forge's pipeline (`make regen-check` DRIFT, `offline-regen-check.yml` failing). It was
reverted in `301de9b5`. Only if the id exists in **no** module after pulling is it genuinely
dangling; then removing the line (never the record) is defensible, since the column is
`required="false"` and the field stays identified by its `JAVA_QUALIFIER`.

**`check-etgo-xml.sh` does not catch this** — its referential-integrity pass does not validate
`ETGO_SF_FIELD.AD_COLUMN_ID` against core's `AD_COLUMN`, so only a failed `update.database` finds
it (for the unpulled-module case that silence is actually correct: the reference is valid). If a
check is ever added, it must resolve ids against every module's sourcedata, not against the local
database. When sweeping, sweep for all of them at once — the error names only the first.

### 🟡 4.3 Behaviour change: `revertTestModeForProductiveTenantBestEffort` fires more often

It previously fired only when `markProductive` succeeded. It now fires whenever the tenant was
recorded productive by **either** store, so it also covers the success path — where it previously
reverted test mode only as a side effect of the preference write. Same intent (ETP-5117), wider
trigger. Given §3.4's stake, firing more often is the safe direction, but it is a behaviour change.

---

### 🟡 4.4 The at-sign guard is over-tested (ETP-5050)

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

Fixing the same leak twice — in `CheckoutRequestStore` (ETP-5045) and then in `BillingEventStore`
(ETP-5046) — raised the obvious question: how many more of these are there? Below is a module-wide
survey of every class that installs a system context, with the verdict for each. **The grep alone
lies in both directions** — it flags comments as calls and cannot see a guarded install — so each
line below is the result of reading the code, not of counting matches.

| Class | Verdict |
|---|---|
| `payment/CheckoutRequestStore` | ✅ fixed on ETP-5045; the account-id lookups develop added afterwards reintroduced raw installs and were routed through `runAsSystem` in the 2026-09-24 develop merge |
| `payment/BillingEventStore` | ✅ fixed on ETP-5046 |
| `payment/SubscriptionService` | ✅ admin mode only, never replaces the caller's context; the old `openSystemContextWhenAbsent()` was dead (admin mode had already installed a context) and was removed |
| `rest/TransactionalAuthEmailSender` | ✅ captures and restores |
| `rest/CompanyInvitationService` | ❌ **real, unfixed** — see below |
| `roles/RoleInheritanceReconciliationService` | ⚪ **false positive** — its only `setOBContext` match is prose in a comment (line 358) describing a *caller* that runs as system; there is no call |
| `rest/EtendoGoJwtServlet` | ❓ **unaudited** — 22 raw installs; the lifecycle webhook is the one site now routed through `payment/SystemContext` |

**`CompanyInvitationService` — the real one.** Two sites, both `restorePreviousMode()`-only:

- `resolveInvitation(...)` — installs at **509–510**, unwinds at **553–555**
- `withAdminMode(...)` — installs at **755–756**, unwinds at **759–761**

It is the cheapest of the three to fix, because `withAdminMode` is *already* the `runAsSystem`
shape — a wrapper every accept path funnels through (`acceptExistingAccountInAdminMode` at 576,
`registerAndAcceptInAdminMode` at 661). It simply never captures. `resolveInvitation` is the only
method opening the context inline, so routing it through `withAdminMode` would collapse the class
to one context site and fix both at once. The case to care about is
`registerAndAcceptInAdminMode`, which creates a user and accepts an invitation: anything continuing
on that thread afterwards runs as system. That is the shape of the hazard; no exploit has been
traced.

**`EtendoGoJwtServlet` — deliberately left as a question.** 22 installs and one capture/restore is
not evidence of 21 leaks, and it is not evidence of none either. A heuristic marked it "OK" on the
strength of that single site; nobody has read the other 21. Whoever picks this up should treat the
verdict as unknown rather than inherit an unearned pass.

**Left unfixed on purpose.** Neither belongs to ETP-5046, and widening an already large merge to
carry them would make it harder to review, not safer. They want their own ticket — and the fix is
mechanical once the pattern is recognised, which is the entire reason this section exists.

### 🟡 4.8 `priceId` still reaches the browser on two develop paths

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

`GET /sws/go/plans` (legacy fallback: `retrieveConfiguredPrice()`) and every checkout
(`StripePriceService.retrievePrice` on the plan's `PROVIDER_PRICE_ID`) call Stripe synchronously,
once per request. The price for a given id is effectively immutable, so a small per-id cache with a
short TTL (minutes) would remove that round-trip and the dependency of the plans page on Stripe
latency; the checkout path must keep failing closed on a lookup error rather than serving a stale
miss. Raised in the ETP-5046 review (S1); not implemented.

### 🟡 4.10 `ETARC_VECTOR_SOURCE.DISTANCE_METRIC` is exported by this module before its column exists

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

## 5. Handed forward to later tickets

### 🟠 5.1 ETP-5051: "no quota row" means UNLIMITED

`ETGO_PLAN_QUOTA.INCLUDED_QTY` is `required="true"` with **no default**, in the DDL *and* in
`AD_COLUMN.DEFAULTVALUE`. This deliberately breaks the module's own pattern — every comparable
required DECIMAL here carries `<default>0</default>`.

A `LEFT JOIN ... COALESCE(included_qty, 0)` in the evaluator silently caps every unquota'd resource
at zero. `PlanQuotaSchemaInvariantTest` guards the schema side (mutation-tested); **nothing can
guard the evaluator except this paragraph.** `ETGO_PLAN_QUOTA` is also the only new table with
`ISDELETEABLE='Y'`, because deleting the last quota row is how an operator restores unlimited.

The rest of the quota definition ETP-5051 inherits is in §5.5–§5.9; usage per subscription, which
no ticket owns yet, is in §5.10–§5.12.

### 🟠 5.2 ETP-5047: correlate to the OPEN row

`STRIPE_SUBSCRIPTION_ID` is deliberately **not unique**: a plan change updates the item on the same
Stripe subscription while opening a *new* Etendo row, so two local rows legitimately share one id.
Lifecycle webhooks must resolve to the row with `END_DATE IS NULL`, not to "the" row.

### 🟠 5.3 ETP-5053: a plan change opens a new row

Re-pricing a plan does **not** re-price existing subscribers — the subscription carries its own
`PROVIDER_PRICE_ID` and amount snapshot, never rewritten by a plan edit. A plan change closes the
current row (`END_DATE`) and inserts a successor, preserving price history. `PENDING_PLAN_ID` and
`PENDING_EFFECTIVE_DATE` already exist, nullable and hidden, so no second AD pass is needed.

### 🟠 5.4 Known gaps: no plan-change path, a partial lifecycle on the table

- **No plan change exists.** `SubscriptionService` can open a row and read it; nothing closes one
  and opens the successor. A tenant cannot move between plans — including a legacy-fallback buyer
  moving to the first real plan — until ETP-5053. `PENDING_PLAN_ID` / `PENDING_EFFECTIVE_DATE` stay
  unread.
- **Lifecycle webhooks update the row's status and grace anchor only** (§3.7): no period window,
  no event watermark on the row, no closing on cancel, no re-subscription. ETP-5047 owns the rest.

**Quota definition — owner ETP-5051.**

Facts checked against the DDL, `SubscriptionService` and the PRD on 2026-09-24; the ETP-5051 scope
is quoted from its Jira text as read that day.

### 🟠 5.5 Consumption window = the subscription billing period — decided, but no row carries one yet

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

The same problem as §1.1/§1.2, one level up. `ETGO_USAGE_DAILY` stores one `QTY` per tenant,
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

`ETGO_PLAN_INTERVAL_CHK` allows `month` and `year`, and the window is the billing period (§5.5), so
`INCLUDED_QTY` on a `year` plan is consumed over the whole year, not per month. Correct by the
design, surprising to an operator. **Must be stated** in the operator docs and in the **Plans** /
**Quotas** window help when ETP-5051 makes quotas live. (Annual intervals are also listed as
deferred in PRD §16, yet the schema already accepts them.)

### 🔴 5.9 A subscription with no current period needs a defined answer

Beyond §5.5's "no row has a period yet", two cases stay periodless by nature: rows backfilled by
R37 (`CURRENT_PERIOD_START` always NULL, design §7.0) and a `canceled` row (`CURRENT_PERIOD_END`
cleared, `END_DATE` still null — §3.7). **To decide in ETP-5051:** what the evaluator does with no
window — skip, treat as unlimited, or use the last known period. Latent today only because
`legacy-productive` has no quota rows (§5.1); the first quota on a plan such a tenant can be on
makes it live.

**Usage per subscription — owner: the ticket that introduces usage-based charging.**

PRD §3 and §16 defer overage charging "with its own PRD"; period history could also fit ETP-5047 or
ETP-5048.

### 🔴 5.10 There is no per-subscription or per-period usage figure, stored or planned

`ETGO_USAGE_DAILY` (`MEASURED_CLIENT_ID`, `ETGO_BILLING_RESOURCE_ID`, `USAGE_DAY`, `QTY`,
`IS_SETTLED`) has no link to a subscription. It joins one only **by value**:
`ETGO_SUBSCRIPTION.ENVIRONMENT_CLIENT_ID = MEASURED_CLIENT_ID` and `USAGE_DAY` inside the period.
ETP-5051 computes that on the fly, for evaluation only; ETP-5048's reconciliation covers payments
and subscription status, not usage. **To decide:** whether a period's usage is ever stored, and by
whom.

### 🔴 5.11 Past billing periods cannot be reconstructed locally — cheap now, impossible later

`ETGO_SUBSCRIPTION` has one period slot and no history; once ETP-5047 fills it, each renewal will
overwrite it. `ETGO_BILLING_EVENT` does not keep invoice periods either: `PAYLOAD_SUMMARY`'s
allow-list (`WebhookPayloadSummary`) is `id, customer, subscription, livemode, payment_status,
amount_total, currency, mode` — no `period_start`/`period_end`. So "usage in the last billed
period" needs Stripe's invoice dates, and any overage billing, which bills a **closed** period,
needs a local period history. **To decide:** a small period-history table, or recording the invoice
period on the `invoice.paid` billing event. Starting to record now costs little; periods that were
never recorded cannot be backfilled from local data.

### 🔴 5.12 The would-have-billed report is scheduled in the PRD and excluded by its owner

PRD §14 assigns "would-have-billed report. Shadow mode" to **ETP-5050** (PRD §6 motivates the
historical backfill by shadow mode). ETP-5050's design (`plans/2026-09-15-etp-5050-usage-measurement-design.md` §10) lists
"Rollup of any kind — SUM or MAX over a period, the would-have-billed figure, price" as **out of
scope**. Nobody delivers it now. **To decide:** which ticket owns it — it also depends on §5.6
(how days combine) and §5.5 (which period).

---

## Related documents

- `plans/2026-09-18-etp-5046-plan-and-subscription-design.md` — the ETP-5046 design of record
- `plans/2026-09-18-etp-5046-stage-b-ad-handoff.md` — the AD authoring record
- `plans/2026-09-15-etp-5050-usage-measurement-design.md` — the usage engine design
- `schema_forge/docs/usage-measurement.md` — §4 is the full flow-versus-stock analysis
- `schema_forge/docs/plans/2026-08-27-recurring-billing-and-resource-limits-prd.md` — the governing PRD
- `feature-flags-and-tenant-upgrade.md` — paywall, plan marker, transitional fallback
