# Open and notable topics — recurring billing block (ETP-5045 … ETP-5053)

A living register of the decisions still owed, the operational constraints that are not visible
from the code, the traps that would otherwise be rediscovered the hard way, and the decisions taken
since, with the implementation work each one still leaves.

**Scope:** the whole billing development — ETP-5045 (durable checkout state), ETP-5050 (usage
measurement), ETP-5046 (Subscription Plan Catalog + subscriptions), and what they hand to ETP-5047/5048/5051/5053.

**Status key:** 🔴 decision owed · 🟠 constraint to respect · 🟡 known issue, worked around ·
✅ clarified or solved — the decision is taken; implementation may still be owed (§6)

**Ticket line:** every topic carries a `**Ticket:**` line under its heading. *Owner* = the ticket that must resolve or respect it. *Related* = where it came from or what it touches. **No ticket** = nothing currently covers it; it needs its own ticket or a decision on where it goes.

**To raise with:** a topic whose answer must come from a named person carries a `**To raise
with:**` line under its ticket line. Most 🔴 decisions were answered by Sebastián Barrozo
(product/tech lead) in a chat with Martin Taal on 2026-09-28; the points that chat left open, or
opened, are §1.2–§1.7.

**A settled topic moves to §6, it is not deleted.** Once a topic is decided or fixed it moves to
§6 "Clarified and solved" and **keeps its original number and heading text**, so a citation such
as `§1.1` or `§5.10` still resolves. A moved topic carries a `**Status:**` line (`decided <date>
(<who>)` or `partly decided`), the decision in one to three sentences, a `**Follow-up:**` line
naming the implementation work still owed and its ticket (or `no ticket`), and only as much of the
original analysis as a reader still needs; the full history stays in git, the only copy that cannot
drift from the code. **Section numbers are stable**: other documents cite them (`§3.7`, `§4.8`), so
a moved topic leaves a gap in its old section rather than shifting its neighbours, and a new topic
takes the next free number of the section it belongs to — a topic decided on arrival too (§5.15
sits in §6 under its §5 number). Gaps older than 2026-09-28 are topics deleted under the earlier
rule; their history is in git.

---

## 1. Decisions still owed

*Moved to §6 on 2026-09-28: §1.1. On 2026-10-14: §1.3.*

### 🔴 1.2 The billing period is per subscription, not per price

**Ticket:** related §5.9 / ETP-5051 (the evaluator that reads the period); no ticket for the
lookup script.

**To raise with:** Sebastián Barrozo

On 2026-09-28 Sebastián confirmed that every tenant backfilled from the preference has the same
billing period, "since they all have the same price id". A shared price gives the same *interval*
(`month` / `year` and its count), not the same *dates*: Stripe anchors each subscription's period
to its own start or billing-cycle anchor, so two subscriptions on one price renew on different
days. The §5.9 lookup script must therefore read each subscription's current period separately —
from the subscription, or its first item under `billing_mode: flexible`, the same source the
webhook reads (§3.7) — and never copy one period onto every row.

**A tenant with no Stripe subscription has no period at all.** R37 copies `STRIPE_CUSTOMER_ID` /
`STRIPE_SUBSCRIPTION_ID` only from the tenant's latest active `ETGO_CHECKOUT_REQUEST` that carries
a Stripe subscription id; a tenant made productive without such a checkout gets both NULL (its
`@report`: "no usable etgo_checkout_request found, stripe ids left NULL"). There is no Stripe
subscription to look up, so neither the script nor a lifecycle event (§5.5) can ever give that row
a period. **To decide:** the rule for it — e.g. a period computed locally from `START_DATE`, no
quota at all (true today, while `legacy-productive` has no quota rows, §5.1), or moving the tenant
onto a real Stripe subscription (a plan change, ETP-5053) before any quota applies to it.

### 🔴 1.4 Per-client transactions versus today's per-resource-day transactions

**Ticket:** related ETP-5050 (the usage job) and the scheduling follow-up of §5.15 (no ticket).

**To raise with:** Sebastián Barrozo

Sebastián asked for the scheduled usage job to run a separate transaction per client, so the whole
run need not be atomic (§5.15). The run already is not atomic:
`UsageAggregationService.aggregateResourceDay` commits each resource-day on its own
(`commitAndClose`, `rollbackAndClose` on failure; `UsageRunLedger` writes the run log once at the
end so a failure survives its rollback), so a failing resource or day loses only itself. But each
of those transactions counts **every tenant at once**: `DeclarativeHqlCounter` runs one query per
day grouped by client, on purpose — per tenant it would be *tenants × resources × days* queries per
run, "tens of thousands of round trips nightly" with a few hundred tenants (its javadoc). A
per-tenant query form exists (`UsageQueryComposer`, used by the single-tenant backfill and the
save-time validation probe), so per-client transactions are possible, at that cost multiplied by
the two-hour cadence of §5.15. **To confirm:** whether per-resource-day transactions satisfy the
concern, or whether isolation per client is wanted for another reason — today a resource-day that
fails, fails for every tenant in it.

### 🔴 1.5 ETP-5049: which two cards, and where does each card's `price_id` come from?

**Ticket:** owner ETP-5049 (§5.14).

**To raise with:** Sebastián Barrozo

The 2026-09-28 decision (§5.14) is two cards, each configured with a Stripe `price_id`, prices read
from Stripe. Still open:

- **Which two.** Today's Plan step has one real card (`upgrade-plan-productive`, static name and
  tagline) and two `SkeletonPlanCard` placeholders (`upgrade-plan-coming-soon-1/-2`).
- **Where each `price_id` lives.** If it is the catalog plan's `ETGO_PLAN.PROVIDER_PRICE_ID`, the
  card selects a `planKey` and ETP-5046's checkout stays as is: `HostedCheckoutService` takes a
  `planKey`, never a price, and the browser never receives a provider price id (§4.8). If it is
  configured outside the catalog, checkout bypasses it — the checkout request records no plan
  (§4.6), the subscription opens on none — and the browser holds Stripe price ids.
- **The Payment step.** Whether it keeps its second chooser (`PlanSelector`: `upgrade-plan-single`
  / `upgrade-plan-choice`) or shows the plan chosen on the Plan step.

### 🔴 1.6 One-time cutover now (Phase F early) — proposed, not decided

**Ticket:** no ticket (Phase F, §3.2).

**To raise with:** Sebastián Barrozo

Sebastián proposed (2026-09-28): there are no real customers yet, only demos, and one or two real
subscriptions are expected in the coming weeks, so make a one-time cutover now — migrate the state
worth keeping, switch to `ETGO_SUBSCRIPTION`, remove the preference fallback — after confirming
there are no live subscriptions to adopt (§2.4). Martin raised that this means moving the code to
production quickly; Sebastián's answer was the off-by-default feature-flag rollout of §1.3 —
decided on 2026-10-14, though ETP-5047 itself still ships enforcement on. **Still to decide.**
Prerequisites, from §3.2, on every environment: R37 has run after R42, the `ETGO_TenantPlan` marker
count reaches 0, and the fallback WARN no longer appears. The cutover deletes §3.2's list, and with it the watermark preference of
§3.7.

### 🔴 1.7 `END_DATE` meaning — not discussed on 2026-09-28

**Ticket:** owner ETP-5053.

**To raise with:** Sebastián Barrozo

The one 🔴 decision the 2026-09-28 answers did not touch. Options and default: §5.13.

### 🟡 1.8 Is the `billing-reconciliation-schedule` flag overkill? — decide later (ETP-5048)

**Ticket:** related ETP-5048; no ticket of its own. **Status:** open, **decide later** — safe to
revisit after the first production weeks.

**To raise with / decides:** Martin

The flag is a second switch on top of one production already has. Production is never restarted,
so there the reconciliation is scheduled by hand in the Process Request window, and unscheduling
that request already stops it. On top of that, the flag:

- gates every scheduled run (timing *Schedule*, whoever created the request);
- turns a scheduled run into an ERROR in Process Monitor while it is off;
- drives `BillingReconciliationScheduleStartup`, which plants the request on environments that do
  restart.

**Options:**

1. **Keep it as a master kill switch** — stop every scheduled reconciliation from ConfigCat (or the
   property) without touching Process Request, on every environment at once.
2. **Drop it, and the startup hook** — rely on Process Request alone, as Demo Trial Notices does.

**What removing it deletes:** the `FLAG_BILLING_RECONCILIATION_SCHEDULE` flag, the startup hook
(`BillingReconciliationScheduleStartup` and its fixed request id), the in-run gate and the flag-off
ERROR path in `BillingReconciliationProcess` (`isScheduledRun`, `timingOf`), their tests
(`BillingReconciliationScheduleStartupTest`, the gate cases of `BillingReconciliationProcessTest`)
and their documentation (design `plans/2026-10-18-etp-5048-billing-reconciliation-design.md`
decision 11 and §8, the flag section of `feature-flags-and-tenant-upgrade.md`). The menu entry, the
process and Process Request scheduling are unaffected either way.

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

### 🟠 2.3 Run the backfill AFTER the deploy, never before — and only as part of the switch

**Ticket:** owner ETP-5046 (the pre-check, an R37 deployment step) and ETP-5048 (the adoption
step, if the pre-check fails); moving grandfathered tenants to a priced plan is ETP-5053.

`resolvePlan` reads the subscription first. A tenant provisioned between the schema landing and
the code shipping gets a preference and no subscription. The backfill's `@check` catches exactly
that tenant because it keys on the preference rather than on a date — but only if it runs
afterwards.

Since 2026-10-14 R37 is **opt-in** (`-- @optIn: true`): a full `make data-fixes` never runs it, so it
cannot run before the deploy by accident. It is step 3.4 of the switch to the subscription model
(§2.9, design doc §6.2): chain current first (`make data-fixes`), then the System preference, then
`make data-fixes FIX=R37-tenant-subscription-backfill` (`DRY_RUN=1` first; `CLIENT=<id>` for one
tenant). `FIX=` refuses a tenant whose chain is behind R37's date (`BLOCKED_CHAIN_BEHIND`).

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
`StripeApiClient` makes the read cheap. **Delivered in ETP-5048:** the reconciliation's local-to-provider phase fills `PROVIDER_PRICE_ID`, `SNAPSHOT_AMOUNT` and `SNAPSHOT_CURRENCY` from the subscription's first item price on every open row where they are NULL, once, audited as a `reconcile:subscription-price:<sub>` billing event (`plans/2026-10-18-etp-5048-billing-reconciliation-design.md` §5). Until it has run on an environment such rows stay priceless — harmless for access, which reads `STATUS` alone, and visible only in billing records and quota snapshots.

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

### 🟠 2.9 The subscription model is OFF on every environment until its operator switches it on

**Ticket:** owner ETP-5046 (decided by Martin, 2026-10-14). Related ETP-5047 (enforcement reads the
rows), ETP-5051/5053 (quotas and plan changes need a row), §3.2 (Phase F).

Whether a **new** paid purchase opens an `ETGO_SUBSCRIPTION` row is decided per environment by one
database-stored switch: the System-level list preference **`ETGO_SubscriptionModel`**, answered by
`SubscriptionModelSwitch#isOn` (no cache, no restart). It has two readers: the paid upgrade, once per
purchase, and `TenantPlanPreferenceFallback`, once per resolution or batch it serves, only to pick
its DEBUG/WARN log level — while OFF that is one extra `AD_Preference` query per fallback resolution
of a rowless tenant, still uncached. Only `Y` on an active row at `AD_CLIENT_ID = '0'` with no
narrower visibility means ON; absent, any other value, a tenant-scoped row or a failed read mean
OFF. The module ships the `AD_Ref_List` value but **no preference row**, so every environment comes
up OFF after the deploy and stays exactly where develop is: new purchases write `ETGO_TenantPlan`,
open no row, and the lifecycle webhooks keep the `ETGO_SubscriptionStatus`/`DueAt` preferences.

- **Switching is one operator step, done once:** chain current → set the preference → R37 with
  `FIX=` (dry run first; all at once or client by client with `CLIENT=<id>`) → verify, three
  checks: R37 ledger rows `APPLIED`/`SKIPPED_NOT_NEEDED`;
  active `ETGO_TenantPlan='PRODUCTIVE'` markers = 0; and the switch row as the reader selects it —
  exactly ONE row with `trim(value) = 'Y'`:
  ```sql
  select ad_preference_id, value, created, updated from ad_preference
   where property = 'ETGO_SubscriptionModel' and ispropertylist = 'Y' and isactive = 'Y'
     and ad_client_id = '0' and coalesce(visibleat_client_id, '0') = '0'
     and coalesce(visibleat_org_id, '0') = '0' and visibleat_role_id is null
     and ad_user_id is null and ad_window_id is null
   order by created desc, updated desc, ad_preference_id desc;
  ```
  Full checklist: design doc §6.2, rationale §6.3; operator page:
  `feature-flags-and-tenant-upgrade.md` §3, *The subscription-model switch*.
- **Switch first, then R37 (decided 2026-10-14, Martin Taal).** With the switch ON every new
  purchase opens a row, so one R37 pass catches every tenant still on the marker. The old order
  (R37, then the switch) is safe too, but a purchase made between the two steps still writes the
  marker and needs a second R37 run. The same holds when R37 runs client by client (`CLIENT=`)
  before the switch: a migrated tenant has a row and needs nothing more, but every other tenant that
  bought while the switch was OFF wrote the marker, so run R37 once more for the whole environment
  after enabling the switch. The switch is system-wide and only R37 is per client, so both models coexist
  meanwhile (readers: row first, preference fallback).
- **One-way in practice.** Setting it back to `N` sends only tenants that have never had a row back
  to the marker; every tenant with a row stays on it, payments included. Two populations that only
  grow apart, Phase F further away — turning it back OFF undoes nothing.
- **Only a tenant's first row is gated.** A tenant with any row (open or closed, active or not,
  `SubscriptionService#hasAnySubscription`) is decided by it for reads and writes: its next payment
  goes to a row even with the switch OFF, or it would stay blocked behind a canceled or closed row.
  A tenant whose only rows are inactive gets a new active open row next to them: the partial unique
  index `etgo_sub_open_envclient_uq` covers only active rows with no end date. `openSubscription`
  has one runtime caller (the paid upgrade), which is where the gate sits.
- **A failed row lookup takes the marker path — accepted, decided 2026-10-14 (Martin Taal), no
  code change.** With the switch OFF, `EtendoGoJwtServlet#tenantHasSubscriptionRow` answers
  `false` when `hasAnySubscription` throws (logged at ERROR), so the payment goes only to the
  `ETGO_TenantPlan` marker. For a tenant whose only row is closed the marker is hidden behind that
  row: since ETP-5047 the tenant reads as canceled, so it would stay blocked (402) while it pays.
  *Not reached by a purchase:* every checkout provisions a new, rowless environment
  (`feature-flags-and-tenant-upgrade.md` §3), and since ETP-5047 a canceled productive tenant is no
  longer a demo source either (§4.14). The **ETP-5742** re-subscription does reach a tenant with a
  closed row, and avoids this path: it asks `hasAnySubscription` itself, unguarded, before
  recording the payment, so a failing lookup fails the payment webhook for a redelivery. The
  acceptance does not depend on that.
  *Why accepted:* it fails closed in practice. The lookup runs in the onboarding transaction, and on
  PostgreSQL a failed SQL statement aborts it, so the writes that follow fail too and the upgrade
  fails and stays retryable instead of being recorded in the marker; only a failure other than a
  failed SQL statement (a mapping or hydration error after the statement ran, for instance) leaves
  a usable transaction and can reach the edge. Failing every OFF purchase on a lookup error would
  be the worse trade. The purchase-source check has a sibling edge, accepted on the same reasoning
  the same day: §4.14, *Accepted edge*. **Follow-up:** none (no ticket). Design doc §6.3.
- **Several switch rows:** the reader and the verification query both take the first in
  `created desc, updated desc, ad_preference_id desc`, so the winner is deterministic even for rows
  created in one transaction.
- **The fallback's log line follows the switch:** DEBUG while OFF (every paying tenant falls back
  on purpose), WARN while ON (a tenant without a row is unexpected). §3.2's "silence" signal is
  therefore "no WARN on a switched environment".
- **Do not ship a preference row as module data.** A sourcedata row would be reset by every
  `update.database`, silently switching an environment back; the operator's row has no module.
- **Residual after the switch:** R42 re-inserts the `ETGO_TenantPlan` marker for tenants that first
  reach it after the switch — its own topic, §3.13 (interim workaround and proper fix there).

---

## 3. Legacy plans and the cutover

### 🟠 3.2 Phase F — deleting the transitional plan code — has no ticket

**Ticket:** no ticket — needs its own once every environment has switched (§2.9: R37 run and
`ETGO_SubscriptionModel = Y`).

The `ETGO_TenantPlan` preference is retired per tenant (R37 and the paid onboarding), and a
transitional read fallback answers for tenants not reached yet; design doc §8 describes both.
(Since ETP-5047 the fallback answers only for a tenant with **no active subscription row, open or
closed**: a cancellation closes its row, so a tenant with only closed rows reads as canceled from
its latest row instead — `findLatest`, §3.7. Both of `findLatest`'s reads filter on `isactive`, so
a tenant whose only rows are inactive reaches the fallback too; R37's branch (B) retires its
marker, after which the fallback stops serving it and its WARN stops.) What
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
R42 for every tenant — which the runner's chain-current gate on the opt-in R37 guarantees for the
tenants present at the switch (§3.6); a marker that reappears after R37 means either a tenant that
first reached R42 after the switch (§3.13) or someone forced R42 by hand. §3.13's proper fix is
therefore a precondition of Phase F.

**Trigger** — on every environment, first that it has switched (§2.9: the System preference
`ETGO_SubscriptionModel = Y` exists), then both of these (design doc §8.1):

```sql
select count(*) from ad_preference where attribute = 'ETGO_TenantPlan';   -- reaches 0
```

and the fallback's WARN no longer appears in the logs. It is one line, with the tenant id,
`productive` and `ETGO_TenantPlan` in the `{}` placeholders:

```
ETP-5046-TRANSITIONAL-FALLBACK: tenant {} has no active ETGO_SUBSCRIPTION row, open or closed; resolving it as '{}' from the retired {} preference. The ETP-5046 backfill has not run for this tenant — run it and this line disappears.
```

Search the WARN lines for the prefix `ETP-5046-TRANSITIONAL-FALLBACK: tenant` rather than the rest
of the sentence, whose wording changed in ETP-5047. The DEBUG line logged while the switch is OFF
shares the prefix, but a switched environment no longer emits it.

**What Phase F deletes** — every site carrying the grep marker `ETP-5046-TRANSITIONAL-FALLBACK`
(6 source files, 5 test files as of 2026-09-28):

- `TenantPlanPreferenceFallback`, and its use in `TenantPlanService.resolvePlan`;
- `TenantPlanService.markProductive`, `retireProductivePreference` and `PREFERENCE_ATTRIBUTE`, with
  their call sites in `EtendoGoJwtServlet.applyPaidUpgradeSideEffects`;
- `SubscriptionModelSwitch` and its gate in `applyPaidUpgradeSideEffects`, plus the
  `ETGO_SubscriptionModel` `AD_Ref_List` value — once every environment is ON the switch has nothing
  left to decide;
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
  with a row:** any active subscription row, open or closed, makes the tenant productive (§3.7),
  and the marker is read only for a tenant with no active row — retired in Phase F (§3.2).
- The `ETGO_SubscriptionStatus` / `ETGO_SubscriptionDueAt` preferences are still read **when the
  tenant has no active subscription row, open or closed** (`ETP-5046-TRANSITIONAL-FALLBACK`). The
  order matters: consulting them first would let the access policy and the Subscription Plan Catalog disagree about the same
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
marker, §3.2) is dated before it. Since 2026-10-14 R37 is opt-in and never visited in chain order;
the order is kept by the runner instead: `FIX=` applies an opt-in fix only to a tenant whose
watermark has already reached the fix's own date (`BLOCKED_CHAIN_BEHIND` otherwise), so R42 is
always at or below the watermark when R37 runs, R37's retirement branch removes whatever marker R42
wrote, and R42 never runs for that tenant again. R37's own ledger rows never move the watermark.
The gap this leaves — a tenant that first reaches R42 *after* the switch — is §3.13.
**The one way to run R42 after R37 for a tenant already past it is an operator forcing it** — `run.js --fix <R42>` ignores chain order and the watermark. Whoever does
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
   - no active row, open or closed → the preference projection (`ETP-5046-TRANSITIONAL-FALLBACK`);
   - an open row with no Stripe id (backfilled) or this one → that row;
   - an open row naming **another** subscription → `IGNORED`, `event for another subscription`;
   - only closed rows → `IGNORED`, `subscription closed` (a late event of the canceled
     subscription) or `no open subscription row` (the first event of a new purchase, before
     onboarding opens its row). Neither may revive or overwrite anything.

The first events of a brand-new tenant's purchase never reach step 2 either: until onboarding
closes the checkout request it names no created client, so they are `IGNORED` as `unresolved
subscription`. Stripe never redelivers an acknowledged event, so the paid onboarding reads the
billing period from the provider itself once the row is open (see `CURRENT_PERIOD_START/END`
below).

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
- **`incomplete_expired` closes the row too (ETP-5048, Martin 2026-10-20).** A subscription whose
  first payment never completed is terminal at Stripe; `SubscriptionLifecycleApplier` maps it like
  `canceled` (`EXPIRED`, row closed at `ended_at`), on the webhook and in the billing
  reconciliation alike.
- **`paused` is alert-only, on every path (ETP-5048, Martin 2026-10-20).** A
  `customer.subscription.updated` carrying `paused`, the dedicated `customer.subscription.paused`
  event, and the billing reconciliation all leave the status, the grace anchor and access untouched
  and raise **one** review alert per subscription (`PausedSubscriptionAlert`: a
  `reconcile:subscription-paused:<sub>` billing-event row, a WARN, an internal alert). Our checkout
  sets no trial, so `paused` means a trial or a pause was configured in Stripe by hand, and a paused
  subscription can be resumed — closing the row would make that impossible, since a closed row is
  never reopened. `customer.subscription.resumed` stays unhandled: a pause changed nothing.
- **The row, not the marker, makes a tenant productive (ETP-5047 review, B1).**
  `TenantEnvironmentLifecycleService.resolve` checks for any active subscription row (open or
  closed — `findLatest` ignores an inactive one) **first** and takes the productive path when
  there is one, whatever `ETGO_EnvironmentType` says.
  The earlier version decided on the marker or on `resolvePlan`, and failed open: a canceled row
  makes the plan `free`, and a tenant without the marker — every tenant provisioned before the
  marker existed (6 of 6 productive tenants on the development database) — fell into the demo path,
  got a legacy-transition start or no snapshot, and was **allowed**. A tenant with a row now never
  reaches the demo path, so no demo or legacy-transition preference is ever written for it. On the
  row path the marker is not even read, and a missing one is not reported (it is the normal state
  of a pre-marker tenant). The marker and `resolvePlan` still decide for a tenant with no active
  row (the preference fallback). `isProductiveEnvironment` reads the same way to keep a productive
  tenant, canceled included, out of the purchase-source checks (§4.14).
- **Re-subscribing opens a fresh row.** A later purchase for the same tenant finds no open row and
  `openSubscription` inserts one (`SubscriptionResubscriptionIntegrationTest`). The **ETP-5742**
  re-subscription is what reaches it: the payment webhook applies a paid re-subscription to its
  tenant through `applyPaidUpgradeSideEffects` (`feature-flags-and-tenant-upgrade.md` §3,
  *Re-subscribing a canceled environment*). A purchase still provisions a new environment. The
  acceptance of §2.9's failed row lookup does not depend on it.
  If the open row is `canceled` but was never closed — its delete event was lost, or R37
  backfilled it (R37 leaves canceled rows open on purpose: it has no provider end instant to close
  them at; its idempotency guard is "no row at all", so a re-run never inserts a second one either
  way) — `openSubscription` closes it before inserting. The close must be in the database first —
  Hibernate runs inserts before updates at flush, and `etgo_sub_open_envclient_uq` would otherwise
  reject the new row — but it is **not** a session flush: the only caller is the paid onboarding
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
    writes one. `openSubscription` itself writes neither column; a paid onboarding fills them
    once, right after it has closed the checkout request and before the sample-data import
    (`EtendoGoJwtServlet.fillSubscriptionPeriodBestEffort` →
    `SubscriptionService.fillPeriodFromProviderAfterCommit`): it commits and closes the DAL
    session, reads `GET /v1/subscriptions/{id}` (`StripeSubscriptionPeriodReader`: 3 s connect,
    5 s read, DNS not bounded; parsed with `subscriptionPeriodBoundary`, both payload shapes),
    then writes the pair with one conditional HQL `UPDATE` guarded by **both** boundaries being
    NULL — so a lifecycle event's period always wins, even one applied while Stripe was answering.
    It runs under `SystemContext` (`UPDATEDBY = '0'`) and never touches `STATUS`, `GRACE_ANCHOR`
    or `LAST_EVENT_AT`. Best effort: any failure (no secret key, transport, 4xx, a body without a
    whole, ordered period) is one WARN from the reader — the transport may add its own for a
    timeout, DNS/TLS failure, 5xx or 429 — and the row stays periodless until its next lifecycle event; the
    onboarding is never affected. A row with no Stripe id (payment-mode checkout) is skipped with
    an INFO, and a demo onboarding never calls it. (Before ETP-5047 a payment-mode checkout's JSON
    `null` subscription was stored as the text `'null'`, because jettison's `optString` turns JSON
    `null` into that text. Every nullable Stripe string is now read through `StripeJson.text`
    (`com.etendoerp.go.payment`), which replaced three private copies; never use a bare
    `optString` on a Stripe object. A row still holding `'null'` gets a 404 and the WARN — none
    on the development database.)
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

**The watermark lives on `ETGO_SUBSCRIPTION` — decided (Sebastián Barrozo, 2026-09-28).** The Stripe
event-ordering watermark moves into the subscription row and AD_Preference stops being used for it.
ETP-5047 delivered most of it: `LAST_EVENT_AT`, carried over from `ETGO_SubscriptionEventAt` by R37.
What remains — the preference write for a tenant with no row (`recordSubscriptionEventAt`) and the
read fallback while a row's `LAST_EVENT_AT` is NULL (`readEventWatermark`, both marked
`ETP-5046-TRANSITIONAL-FALLBACK` in `TenantEnvironmentLifecycleService`) — ends with Phase F (§3.2).

### 🟠 3.8 One environment-access check, one 402 body, one kill switch (ETP-5047)

**Ticket:** owner ETP-5047 (delivered); related ETP-5455 (the shared auth pipeline whose bind step runs the check).

`EnvironmentAccessGuard` is the single commercial access check. Since the merge with ETP-5455 it
runs in the bind step of the shared auth pipeline (`EnvironmentRequestAuthenticator`) for every
surface whose `SurfacePolicy` requires commercial access — `NEO_API` (NEO, every request, every
scheme) and `NEO_DATA` (`NeoFavoritesServlet`, `NeoFiscalTestModeServlet` through
`JwtAuthUtils.authenticateOrFail`, `ReportSelectorsServlet`, the OAuth2 API-key endpoints) — and
hands its denial to the consumer as `EnvironmentAuthOutcome.getAccessDenial()`. **Both the
pipeline and MCP judge the effective tenant** (ETP-5047): a token on org `0` carries the System
client `0`, which `EffectiveClientResolver` resolves to the role's client ONCE, and that one value
is what the guard judges and what the request's `OBContext` is built with — asked about `0` the
guard found no lifecycle and allowed, so a blocked tenant walked past it with such a token. A
failed role lookup refuses the request (503) rather than fall back to `0` (§3.12). Outside the
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

### 🟡 3.11 R37 edge cases found in ETP-5047 QA — one live, one resolved

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
- **Resolved in ETP-5047 — see §5.13.** R37's backfill keyed on "no OPEN row", so a tenant with
  only closed rows and a leftover or R42-written `productive` marker was re-subscribed with a fresh
  active row. Branch (A) of `@check` and the guards of `@apply` statements 1-2 now key on "no row
  at all" (active or not, open or closed), so such a tenant only has its marker retired.

### 🟡 3.12 Wildcard-client tokens — resolved in ETP-5047; check for preferences the old bug wrote

**Ticket:** owner ETP-5047 (delivered); found in its review (W1, W2).

**Resolved.** `OAuth2Filter.validateToken` derives a token's client as `COALESCE(org.ad_client_id,
oauth2_client.ad_client_id)`, so a token on org `0` carries the System client `0`. The guard was
handed that raw value on `/sws/neo/*` (`NEO_API` — `NEO_DATA` refuses OAuth2 tokens) and on MCP,
found no lifecycle for System and **allowed**, whatever the role's tenant owed. Both now resolve the
tenant ONCE through `com.etendoerp.go.auth.EffectiveClientResolver` and use that one value for the
guard AND the `OBContext` (§3.8); a failed role lookup refuses the request with **503** instead of
falling back to `0` (fail closed — a System role, whose lookup legitimately answers nothing, keeps
`0`). The lifecycle service never evaluates or writes client `0`.

**What is left: ops cleanup.** Before the fix, a wildcard MCP token on an instance with
`ETGO_DEMO_TRANSITION_ACTIVATION_AT` set made `ensureLegacyTransitionStart("0")` write a
legacy-transition start on System. Lifecycle preferences are written per tenant (`ad_client_id` =
the tenant), so any of them on client `0` is the bug's residue — find it with:

```sql
SELECT ad_preference_id, attribute, value, isactive, created FROM ad_preference WHERE ad_client_id = '0' AND attribute IN ('ETGO_EnvironmentType','ETGO_DemoTrialStartedAt','ETGO_LegacyTransitionStartedAt','ETGO_SubscriptionStatus','ETGO_SubscriptionDueAt','ETGO_SubscriptionEventAt','ETGO_AssociatedDemoClientId','ETGO_AssociatedProductiveClientId');
```

(`ETGO_TenantPlan` is deliberately absent: that marker legitimately lives on client `0`, with the
tenant in `visibleat_client_id`.) The rows are inert now — nothing reads client `0` any more — so
removing them is housekeeping, not a fix. Local dev DB (`etendo_core3`, 2026-10-06): 0 rows.

**Business rows are a second, wider residue.** Before the fix a `/sws/neo/*` request with an org-`0`
OAuth2 token also RAN as client `0`, so a record it created was stamped `ad_client_id = '0'` (the
DAL takes the client from the `OBContext`) — data of a tenant filed under System. The query above
does not see those. The writer is the user of an OAuth2 client, so this generates one count per
table that has both columns; run its output and inspect any non-zero table by hand:

```sql
SELECT format('SELECT %L AS tbl, count(*) FROM %I WHERE ad_client_id = ''0'' AND createdby IN (SELECT ad_user_id FROM etgo_oauth2_client) HAVING count(*) > 0;', c.table_name, c.table_name) FROM information_schema.columns c JOIN information_schema.columns k ON k.table_schema = c.table_schema AND k.table_name = c.table_name AND k.column_name = 'ad_client_id' WHERE c.table_schema = current_schema() AND c.column_name = 'createdby';
```

A hit is not automatically residue (an OAuth2 client of a System role writes on `0` legitimately),
so move a row only after confirming the role behind it belongs to a tenant. Not run locally: the
dev DB has no OAuth2 clients or tokens. Move this topic to §6 once both checks have run on every
shared environment.

### 🟡 3.13 R42 re-inserts the `ETGO_TenantPlan` marker next to a subscription row after the switch

**Ticket:** no ticket — needs its own (fix before Phase F). Related ETP-5046 (opt-in R37, the
switch of §2.9), ETP-5548 (R42).
**To raise with:** Martin Taal.

**What R42 does.** Develop's `20260929T190000Z__R42-paid-provisioning-commercial-metadata.sql`
(ETP-5548, applied on shared environments, immutable) restores the commercial metadata of every
paid-provisioned owned tenant: one with an active owner, a `PROVISIONED` paid
`ETGO_CHECKOUT_REQUEST` and no refunded/canceled request. Among other things it inserts an active
`ETGO_TenantPlan='productive'` marker — or flips an existing one to `productive` — whenever the
marker is missing. It decides "paid" from `etgo_checkout_request` alone and knows nothing about
`etgo_subscription`.

**How the marker comes back.** On an environment that has switched (§2.9), a new paid tenant opens
an `ETGO_SUBSCRIPTION` row and deliberately gets **no** marker. Its data-fix chain starts at the
onboarding baseline (`ONBOARDING_PROVISIONED_THROUGH`, 2026-09-02), which is below R42, so the next
full `make data-fixes` runs R42 for it: the missing marker looks like a gap, and R42 inserts it next
to the row. Before R37 became opt-in, R37 followed R42 in the same chain and retired that marker
again; now R37 runs only when an operator names it with `FIX=`, so nothing removes it. Tenants that
were present when the operator ran R37 are not affected — the runner's chain-current gate made sure
R42 had already run for them (§3.6).

**Impact — on the marker count and the Phase F worklist, not on plan or access.**
- **Access and plan: unaffected.** `resolvePlan`, the access policy and the lifecycle webhooks
  answer from the tenant's row; the marker is never consulted for a tenant that has one.
- **The switch verification breaks.** The "active `ETGO_TenantPlan='PRODUCTIVE'` markers = 0" check
  of design doc §6.2 / §2.9 goes back above 0 after the next full data-fix run, and so does §3.2's
  Phase F count — the end condition can never be reached while R42 keeps doing this.
- **The marker's meaning gets muddied.** After the switch a marker is supposed to mean "a tenant the
  backfill has not reached" or "a purchase whose row could not be opened". An R42 marker next to a
  row means neither, so the remaining markers stop being a reliable worklist for Phase F.

**Interim workaround.** After a full `make data-fixes` on a switched environment, retire the marker
of each such tenant by re-running R37 for it — its retirement branch removes the marker of any
tenant that has a subscription row:

```bash
make data-fixes FIX=R37-tenant-subscription-backfill CLIENT=<id> DRY_RUN=1
make data-fixes FIX=R37-tenant-subscription-backfill CLIENT=<id>
```

The tenants to treat are exactly the ones the §6.2 verification query reports with a marker and an
`ETGO_SUBSCRIPTION` row.

**Proper fix.** A subscription-aware successor of R42 — same restoration, but no marker for a tenant
that has any `etgo_subscription` row — plus retiring R42 in `retired.json` (`supersededBy` the
successor; the R42 file stays byte-for-byte untouched, as for every applied fix). It must land
**before Phase F**: Phase F deletes the fallback that would read a stale marker, but its trigger
(§3.2) cannot be met while R42 keeps re-creating them. Recorded on the data-fix side in R37's
header as well.

## 4. Known issues

*Fixed on arrival and recorded in §6 on 2026-10-14: §4.14.*

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

**Ticket:** no ticket — the trim is parked (§4.13); related ETP-5047, ETP-5455.

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

A trim along these lines is parked, unpushed, on the local branch `parked/ETP-5047-followups` of
`com.etendoerp.go` (`5363fa4d8`, "Trim the NEO environment-access test to what NEO decides"); see
§4.13.

### 🟡 4.13 Existing issues found while developing ETP-5047 — not fixed in ETP-5047

**Ticket:** items 1–10: no ticket — candidates for one follow-up ticket; item 11: ETP-5743; item
12: ETP-5742 (both linked to ETP-5047). Related ETP-5047.

Found during ETP-5047's development, review and QA, then taken out of its scope by Martin on
2026-10-14. The **Since** column says where each one comes from, checked against `develop` (go
`3b4afe5a9`, sf `995a2cf9f`) and the ETP-5047 base (go `fa7668ff7`, sf `59b286c2b`): `develop` =
already there today, not caused by ETP-5047; **ETP-5047** = arrives with ETP-5047's own changes —
the part to weigh when deciding whether an item blocks the ETP-5047 deploy. Fixes for most are
built, reviewed and QA'd but **parked, unpushed, on local branches only** — a reader on another
machine cannot see them; they are the starting point for whichever ticket picks this up:

- `com.etendoerp.go` — `parked/ETP-5047-followups` (18 commits on `fa7668ff7`, head `ca1dc71cc`);
- `schema_forge` — `parked/ETP-5047-followups` (14 commits on `59b286c2b`, head `5da6521f0`);
- `schema_forge_core` — `parked/ETP-5047-core-402-reader` (`d57298c06`).

Those branches also carry the opt-in enforcement of §1.3 and the test trim of §4.12; several fixes
below assume it (item 1's 503 is decided per enforcement flag).

| # | What happens | Impact | Since | Parked fix |
|---|---|---|---|---|
| 1 | NEO answers **401** when the commercial access check itself throws (e.g. a DB error): the exception reaches `EnvironmentRequestAuthenticator.refusedFor`. MCP answers 503 there since ETP-5047. | The SPA reads the 401 as an expired session and logs the user out. | `develop` | go: 503 `ENVIRONMENT_UNVERIFIED` in the guard's shared body, decided per enforcement flag (`8818b02b0`, `a77f783c9`). |
| 2 | A SQL-level error inside `TenantEnvironmentLifecycleService.evaluateAccess` aborts the request's PostgreSQL transaction. | Later DAL work and the end-of-request commit fail (500s); on `POST /session/environment` the rotated session can be lost → logout. | Transaction abort: `develop`. Session loss: **ETP-5047** (that endpoint evaluates access only since `enforcedDecision` / `accessDecision`). | go: a savepoint around the evaluation and a DB-backed integration test (`3f64f1219`); a WARN when no savepoint can be taken (`ca1dc71cc`). |
| 3 | Error bodies differ by door. **402:** the Copilot bridge answers a flat `{"error": "<message>"}` and the OAuth2 API-key endpoints their own envelope (`access_denied`), not the shared structured body. **503** (unresolved tenant — `EffectiveClientResolver` — and MCP's failed check): NEO answers `{error: {message, status}}`, MCP and the Copilot bridge a flat `{"error": "<message>"}`, OAuth2 `access_denied`. | Clients need one reader per door; on OAuth2 a retryable 503 looks like a denial. | 402 shapes: `develop`. Every 503: **ETP-5047**. | go: shared 402/503 body on the Copilot bridge (`e8d5d8ed8`), `temporarily_unavailable` for the OAuth2 503 (`959689518`); sf: the readers accept both shapes. |
| 4 | SPA: a 5xx or network failure on `windowaccessmap` shows "your role has no access"; session restore on a 5xx shows a blank screen. | Wrong copy for a temporary failure; a blank app with no way out. | `develop` | sf: `AccessUnreachableScreen` (retry / log out) and `SessionBootingScreen`. |
| 5 | SPA: a list shows an empty grid on any load failure — no error state. | A failed load looks like "no records". | `develop` | None yet. |
| 6 | SPA: the backend error code is lost on several paths — `useEntity` save / delete / process, `apiError`, the API-keys page, the `DataTable` inline toggle, `useCreateMovement` / `useStatementActions` — so English backend text reaches the user. | Untranslated messages in a Spanish UI. | `develop` | sf: a code → i18n map (`lib/backendErrors.js`, `lib/requestError.js`) covers all but the two finance hooks. |
| 7 | SPA API-keys page: hardcoded English confirm titles and copy toast; builds `createApiFetch` from the `@/auth/api.js` barrel instead of `useApiFetch`. | Breaks the i18n and request-policy rules of `schema_forge/CLAUDE.md`. | `develop` | sf: translated, moved to `useApiFetch` (`6e2453a5c`). |
| 8 | SPA does not recognise the OAuth2 API-key 402 envelope (`error: access_denied` + `error_description`) as an environment block; the message is untranslated. | Low: another request's 402 normally blocks the app first. | `develop` | None. |
| 9 | A brief access-map failure during a background refresh replaces the whole app. | Unsaved form input is lost. Core behaviour. | `develop` | None — needs an `app-shell-core` change: keep the previous access map on a background refresh. |
| 10 | Two readers of the 402 decision: core `apiFetch` reads only the message text; schema_forge's `readEnvironmentAccessDecision` (`d18c484d9`) reads `error.decision` first. | Same answer while the backend keeps both in sync; they drift if it stops. | **ETP-5047** | core: the reader moves into `app-shell-core` (`d57298c06`); the schema_forge side waits for a core release. |
| 11 | **Unverified.** Purchase-source ownership is `EtendoGoJwtDalHelper.clientBelongsToAccountEmail`: any active user of the environment whose email or username is the account email (or an `email+suffix` variant). That matches a member **invited** into another owner's environment, not only its owner. | An invited member might name another owner's demo as `demoClientId`, so a purchase would associate, copy and revoke a demo the buyer does not own — against FR-08 of `schema_forge/docs/plans/etp-5396-demo-to-pro-prd.md`. | `develop` (predates ETP-5046/5047) | None — **ETP-5743** "Restrict the purchase demo source to the account owner". |
| 12 | A canceled tenant cannot re-subscribe its own environment: every checkout provisions a new one, and since ETP-5047 a canceled productive tenant is not a demo source either (§4.14). The flow was decided on 2026-10-14 (Martin Taal; §2.9, §3.7). The same-name check `ownsProductiveNamed` (pre-checkout and onboarding) asks `resolvePlan`, which reads a canceled tenant as `free`, so its name does not count as taken. | A canceled customer who wants to come back gets a second environment, and may give it the canceled one's company name. | **ETP-5047** (it closes the row on cancel, so a canceled tenant reads `free`; the missing flow itself predates it) | **Fixed by ETP-5742**: the owner re-subscribes the canceled environment from its blocked screen (`POST /sws/go/billing/reactivation`, applied by the payment webhook), and `ownsProductiveNamed` also counts a productive lifecycle type, so the name stays taken (`feature-flags-and-tenant-upgrade.md` §3). |

### 🟡 4.15 Paid provisioning is only half extracted from `EtendoGoJwtServlet` (ETP-5048)

**Ticket:** accepted by Martin on 2026-10-18 as a narrower extraction; to be finished by ETP-5396 or a
later refactor (no ticket of its own). Related ETP-5048.

ETP-5048 needed to provision a paid purchase with no browser attached (the billing reconciliation),
and the decision was to pull the paid provisioning path out of the servlet into a service that takes
an `OnboardingProgressSink`. What shipped is narrower:

- **Still in `EtendoGoJwtServlet`:** every provisioning step — tenant selection (pooled or classic),
  client and organization creation, dataset, metadata (`applyPaidUpgradeSideEffects`), the commit,
  and the post-commit steps (`completeCommittedOnboarding`, the period fill, sample data, costing).
  They still take a `PrintWriter`, shared by the free and the paid path.
- **Why:** about 60 servlet tests drive those methods directly or spy on them on the servlet
  (`EtendoGoJwtServletOnboardingDatasetTest`, `…CoverageTest`, `…HeartbeatTest`,
  `…DemoDataTransferTest`, among others). Moving them would rewrite those tests in a change whose
  goal is reconciliation, with no change in behaviour to show for it.
- **What moved:** the transport-independent run, `EtendoGoJwtServlet.runOnboarding`, which is the
  try/catch/finally that used to sit inside `handleOnboarding` (rollback, outcome record, alert).
  Also the request-free part of the paid preparation, split into small cores the HTTP path now calls
  too: `restorePaidSelection`, `claimPaid`, `isPaidOnboardingNotOwned`, `findCurrencyId`.
- **What `PaidProvisioningService` owns:**
  - resolving the inputs from the checkout row (`LANGUAGE`/`CURRENCY`/`COUNTRY`, then the account's
    `ONBOARDING_DRAFT`, then `en_US`/`EUR`/`ES`);
  - restoring the caller's `OBContext`;
  - classifying the result (`PROVISIONED`, `FAILED`, `NEEDS_REVIEW`, `NOT_CLAIMED`, `NOT_PAID`).

  It calls `EtendoGoJwtServlet.provisionPaidPurchase` on a fresh servlet instance, which runs the
  same preparation and `runOnboarding` over an `OnboardingStreamWriter` backed by
  `NdjsonSinkWriter`. That writer replays the NDJSON lines onto the sink.
- **What it does not own:** the steps themselves, the NDJSON format, the fenced claim (still
  `CheckoutRequestStore.claimForProvisioning`), and any work record, checkpoint or retry budget
  (ETP-5396 §3.3).

**To finish it** (ETP-5396 or a later refactor):
1. Move the step methods into an onboarding service that takes an `OnboardingProgressSink` instead
   of a `PrintWriter`, migrating the spying tests to that service.
2. Have the servlet build an `NdjsonOnboardingProgressSink` and call it.
3. `PaidProvisioningService` then stops instantiating a servlet, and `NdjsonSinkWriter` can be
   deleted.
4. The same for `PaidReactivationService` (ETP-5048 review B2): it is a facade that calls the
   webhook's `applyReactivationIfPending` on a fresh servlet instance, so the lost-webhook repair of a
   re-subscription and the webhook share one fenced path. Move that method (with
   `applyPaidUpgradeSideEffects`) into a service both call.

### 🟡 4.16 Billing reconciliation phase D does not scale to very large accounts (ETP-5048)

**Ticket:** accepted by the coordinator for Martin on 2026-10-21 (review S2), not fixed; no ticket.
Related ETP-5048, design `docs/plans/2026-10-18-etp-5048-billing-reconciliation-design.md` §9.

The subscriptions phase reads every locally active subscription on every run:

- one query per candidate row to find its checkout request (N+1);
- the `/v1/subscriptions` list sweep stops at 100 pages (10 000 subscriptions);
- the direct reads of ids the sweep did not return are capped at the batch size, in a fixed order.

On an account past those sizes the same tail is read, or left unread, on every run, and the run
reports itself incomplete (so its subscription events are never swept). Fine at today's volumes.
**Fix when an account nears them:** a paged candidate cursor that rotates across runs, the checkout
lookup joined into the candidate query, and the list sweep resumed from where the last run stopped.

## 5. Handed forward to later tickets

*Moved to §6 on 2026-09-28: §5.7, §5.10, §5.12; §5.15 was decided on arrival and sits there too.*

### 🟠 5.1 ETP-5051: "no quota row" means UNLIMITED

**Ticket:** owner ETP-5051.

`ETGO_PLAN_QUOTA.INCLUDED_QTY` is `required="true"` with **no default**, in the DDL *and* in
`AD_COLUMN.DEFAULTVALUE`. This deliberately breaks the module's own pattern — every comparable
required DECIMAL here carries `<default>0</default>`.

A `LEFT JOIN ... COALESCE(included_qty, 0)` in the evaluator silently caps every unquota'd resource
at zero. `PlanQuotaSchemaInvariantTest` guards the schema side (mutation-tested); **nothing can
guard the evaluator except this paragraph.** `ETGO_PLAN_QUOTA` is also the only new table with
`ISDELETEABLE='Y'`, because deleting the last quota row is how an operator restores unlimited.

The rest of the quota definition ETP-5051 inherits is in §5.5–§5.9 (§5.7 decided, now in §6).
Usage per subscription (§5.10, §5.12) and the resource-limit check with its freshness (§5.15) were
decided on 2026-09-28 and are in §6; none of their follow-up work has a ticket yet.

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
period and nothing else** — the grace anchor moved to `GRACE_ANCHOR` (§3.7). `openSubscription`
writes neither column; a paid onboarding reads the period from Stripe once it is open (§3.7, the
`CURRENT_PERIOD_START/END` writer list), and every later `customer.subscription.updated` or
`invoice.paid` keeps it current. A new row is therefore periodless only when that read failed (one
WARN, `Could not read the billing period of Stripe subscription …`) or it names no Stripe
subscription — then until its first period-bearing event. Rows written before ETP-5047 may still
carry the old grace anchor in `CURRENT_PERIOD_END` with `CURRENT_PERIOD_START` NULL (read that
shape as "no period"). §5.9 covers the periodless cases.

### 🔴 5.6 Stock versus flow aggregation over the period — owned by nobody

**Ticket:** no ticket — falls between ETP-5050 and ETP-5051; needs an owner.

**To raise with:** Sebastián Barrozo — the owner ticket, and the column's values.

The same problem as §1.1, one level up. `ETGO_USAGE_DAILY` stores one `QTY` per tenant,
resource and `USAGE_DAY`. Summing the days of a period is right for a **flow** (posted sales
invoices) and wrong for a **stock** (active users, productive environments — daily snapshots): 5
users every day for 30 days sums to 150. ETP-5050 delivers daily counts only (its design §10
excludes "rollup of any kind — SUM or MAX over a period") and ETP-5051 does not define it either.

**Where it lives is settled (Sebastián Barrozo, 2026-09-28):** the §5.7 decision configures
aggregation on the billing resource, `ETGO_BILLING_RESOURCE` — a property of what is measured, not
of the quota. `ETGO_BILLING_RESOURCE.COUNTING_MODE` (`D` declarative HQL / `S` named strategy)
says how a day is counted, not how days combine, so nothing existing covers it.

**Still to decide:** the column and its values — proposal `sum` for flows, `max` or `last` for
stocks — and the ticket that adds it. One consequence of §1.1 to design for: a stock has stored
days only from the day its measurement was enabled, and an omitted day counts as zero
(`UsageResourceCounter`), so over a period that began earlier `max` and `last` stay right while
any average would not.

### 🟠 5.8 A quota on a yearly plan is a yearly quota

**Ticket:** owner ETP-5051 (operator docs and window help).

`ETGO_PLAN_INTERVAL_CHK` allows `month` and `year`, and the window is the billing period (§5.5), so
`INCLUDED_QTY` on a `year` plan is consumed over the whole year, not per month. Correct by the
design, surprising to an operator. **Must be stated** in the operator docs and in the **Plans** /
**Quotas** window help when ETP-5051 makes quotas live. (Annual intervals are also listed as
deferred in PRD §16, yet the schema already accepts them.)

### 🔴 5.9 A subscription with no current period needs a defined answer

**Ticket:** owner ETP-5051 (the evaluator's answer); no ticket for the lookup script.

**To raise with:** Sebastián Barrozo — the evaluator's answer, and the per-subscription period of
§1.2.

Periodless rows (§5.5): a new row whose post-onboarding Stripe read failed, or that names no Stripe
subscription, until its first period-bearing event; rows backfilled by R37
(period NULL, design §7.0) and rows in the pre-ETP-5047 shape. A `canceled` row keeps its last
period since ETP-5047 (it is closed, `END_DATE` set — §3.7), so it has a window but no longer a
current one. Latent today only because `legacy-productive` has no quota rows (§5.1); the first
quota on a plan such a tenant can be on makes it live.

**Partly decided (Sebastián Barrozo, 2026-09-28): fix periodless rows by hand.** Since ETP-5047
a new purchase normally gets its period automatically — the paid onboarding reads it from Stripe
(§5.5) — so the manual fix applies only to the periodless rows that remain: R37 backfills,
pre-ETP-5047 rows, and new rows whose post-onboarding Stripe read failed or that name no Stripe
subscription. For those, the current billing period is read from Stripe and the subscription row
updated manually. A helper **bash script** that crosses the Stripe API with the SQL data is
enough; it only looks up and must never update data automatically. It is not a button or process
in the back-office UI — that comes only if it has to be run more than once. The manual period only
bridges a gap: such a row with a real Stripe subscription fills its own period at its first
`customer.subscription.updated` / `invoice.paid` (§5.5), so the fix covers the time until the next
renewal; a row with no Stripe subscription has nothing to read and is covered by §1.2.

**Still open:**

- **What the ETP-5051 evaluator does with a periodless row until it is fixed** — skip, treat as
  unlimited, or use the last known period.
- **No ticket owns the script.**
- **The script must read each subscription's period separately, and a row with no Stripe
  subscription has none to read** — §1.2.

---

*Usage per subscription — §5.10 and §5.12, owned by the ticket that introduces usage-based
charging (PRD §3 and §16 defer overage charging "with its own PRD") — was decided on 2026-09-28
and moved to §6, with the resource-limit check of §5.15.*

### 🔴 5.13 `END_DATE` records what happened, never what will happen — and any value closes the row

**Ticket:** owner ETP-5053 (the `END_DATE` guard or meaning change). Closing the row on cancel was
settled in ETP-5047 (§3.7).

**To raise with:** Sebastián Barrozo — not discussed on 2026-09-28 (§1.7).

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

**Closing on cancel reopened an R37 edge — closed in ETP-5047 by narrowing R37's backfill guard.**
R37's backfill branch (A) keyed on "active productive marker AND no **open** row", while its
retirement branch only needs *any* row (design doc §8). Develop's R42 re-inserts that marker for a
paid-provisioned tenant regardless of its rows (§3.2), and a cancel on the row route changes neither
the checkout request nor `ETGO_SubscriptionStatus`, so neither of R42's exclusions fires. A tenant
that paid and canceled before its data-fix chain ran therefore got the marker from R42 and then a
**fresh open `legacy-productive` row** (seeded `active`) from branch (A) — a canceled customer read
as paying again. R37 has not reached a shared environment, so it was edited in place: branch (A)
and the guards of `@apply` statements 1-2 now require **no subscription row at all** — `isactive`
and `END_DATE` both ignored, the same "any row" predicate as branch (B) and the retirement. A
tenant with any row takes branch (B) only: its marker is retired and nothing is inserted. That
includes a tenant whose only rows are **inactive**: nothing in the product deactivates a row, so one
is an operator's deliberate switch-off, and a backfilled active row would undo it — the accepted
consequence (product decision) is that such a tenant loses its marker and reads as free until the
operator restores the row. Before this module's close-on-cancel deploys no tenant has a closed row,
so the only tenants whose outcome changes from the ETP-5046 text are hand-deactivated ones — on
purpose. Pinned by `SubscriptionBackfillIdempotencyIntegrationTest`
(`#testAnActiveMarkerNextToAClosedSubscriptionIsRetiredWithoutABackfill`,
`#testAnActiveMarkerNextToAnInactiveSubscriptionIsRetiredWithoutABackfill`).

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

**To raise with:** Sebastián Barrozo — which two cards and where their price ids come from (§1.5).

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

Found in the ETP-5046 manual happy path test.

**Decided (Sebastián Barrozo, 2026-09-28): not a catalog view in the initial version.** The Plan
step keeps **two cards**, each configured with a Stripe `price_id`, with prices retrieved directly
from Stripe. Custom plans come later, through a commercial agreement; marketing defines the third
tier and its requirements before it is added. That sets aside the shape this topic proposed before
(one card per catalog plan, built from `GET /sws/go/plans`). Still open, and the consequences above
stand until ETP-5049 lands: which two cards, where each card's `price_id` comes from, and whether
the Payment step keeps its second `PlanSelector` — §1.5.

---

## 6. Clarified and solved

Topics decided or fixed since 2026-09-28. Each keeps its original number and heading text; the
`**Follow-up:**` line says what is still to build. The full analysis is in this file's git history.

### ✅ 1.1 The stock resources (`activeUsers`, `productiveEnvironments`) have no history (ETP-5050)

**Ticket:** owner ETP-5050; affects ETP-5051, which would quota it.

**Status:** decided 2026-09-28 (Sebastián Barrozo)

**Decision: record the current value each day from when measurement is enabled, and never
backfill earlier dates** (option 2 of 3). A backfill would repeat today's value over every past day
and give a misleading picture.

Why it needed deciding: a **flow** happens *on* a day (three invoices dated 14 March count as 3 on
14 March, forever); a **stock** *exists on* a day (12 active users is 12 every day until something
changes). A stock has no history to recompute from — `AD_USER` answers only "how many are active
*now*" — so a trial backfill over 2010–2027 wrote 74,520 rows of essentially one figure. Rejected:
a dated history table (option 1, most faithful, most expensive) and redefining the resource as a
flow such as "users created that day" (option 3, a different quantity). `AD_SESSION` was ruled
out. `productiveEnvironments` is the second stock: the ETP-5050 design ("Seeded resources") made it
a daily snapshot of the active clients (strategy `S`), not the ticket's `Client` by `creationDate`.

**Follow-up:** no ticket.

- **Implement both counters.** No class implements `UsageResourceCounter` yet — neither
  `@Named("activeUsers")` nor `@Named("productiveEnvironments")` exists — so a catalog row for
  either fails its run ("No UsageResourceCounter deployed", `UsageAggregationService`).
- **Implementation trap: the scheduled run rewrites past days.** `runForSettlingWindow` recomputes
  every day from `today − (window + 1)` to today (7 days at the default window of 5,
  `UsageSettings`) and replaces the value of every day not yet final. A stock counter asked about
  "yesterday" answers with today's value and overwrites the true one. Answering nothing is no way
  out: a tenant absent from a day's counts has its stored row zeroed
  (`zeroOutTenantsThatNoLongerCount`). So a stock resource needs support in the service, not just
  a counter: write only the current day, leave past days untouched, and refuse a historical
  backfill (`run(from, to)` rewrites even final days).
- **`schema_forge/docs/usage-measurement.md` §4 is stale** — still titled "(open)", and its note
  on `productiveEnvironments` describes the ticket's `creationDate` wording, not the snapshot.

### ✅ 1.3 Enforcement default: shipped ON with a kill switch, or OFF behind a feature flag

**Ticket:** related ETP-5047 (§3.8); the opt-in implementation and the production rollout have no
ticket.

**Status:** decided 2026-10-14 (Martin Taal, following Sebastián Barrozo's rollout proposal of
2026-09-28) — implementation owed.

**Decision: OFF by default, behind a positive flag.** Environment-access enforcement should refuse
a tenant only when an "enforce" flag resolves to `true` for it (per `clientId` or for everyone), and
otherwise only log what it would have refused. This reverses Martin's decision of 2026-09-25 (§3.8).
It also accepts the inverted failure mode: `GoFeatureFlags.isEnabled` answers `false` for an
unconfigured flag, an unreachable control plane and any evaluation error, so with a positive flag
every such failure means **not enforcing** — a blocked tenant is let in (and logged), never refused.

**Not in ETP-5047.** ETP-5047 ships enforcement the way `develop` does today — **on** by default —
plus the kill switch `environment-access-enforcement-off`, which stops it per `clientId` only when
the flag resolves to `true` (`EnvironmentAccessEnforcementFlag`); every failure of the flag provider
keeps enforcing. Operator procedure: `feature-flags-and-tenant-upgrade.md` §1, "Operating
environment-access enforcement".

**Follow-up:** no ticket. The opt-in implementation is built, reviewed and QA'd but **parked,
unpushed**, on the local branches `parked/ETP-5047-followups` of `com.etendoerp.go` (`f988caf9f`,
"Make environment-access enforcement opt-in per tenant": the flag becomes
`environment-access-enforcement`, the kill switch is removed without a shim) and of `schema_forge`
(`1500a3916`, the `flags-registry.json` entry, plus the docs). Those branches exist only on Martin's
machine; a ticket that picks this up starts from them. Their register text also opens the question
of when that flag ends (a placeholder TTL in `flags-registry.json`). Production still needs the
switch-on procedure after the deploy: read the would-be refusals, check them against the
subscription rows, then switch on one tenant, then everyone. The parked fixes of §4.13 are on the
same branches.

### ✅ 4.14 A canceled productive tenant passed for a free demo as a purchase source

**Ticket:** owner ETP-5047 (fixed: go `99f765229`, sf `a9f570e88`); related ETP-5046 (introduced
it), ETP-5742 and ETP-5743 (§4.13, items 12 and 11).

**Status:** decided 2026-10-14 (Martin Taal) — found and fixed in ETP-5047, so it sits here on
arrival.

**Decision: a productive environment is never the demo source of a purchase, whatever its plan
reads.** Source eligibility used to rest on the plan: an environment owned by the account, on the
`free` plan and not yet the origin of a productive environment was a valid source. ETP-5046 made a
`canceled` subscription read as plan `free` (`TenantPlanService.legacyPlanForStatus`), so a
productive tenant whose subscription was canceled passed for a free demo. ETP-5047 made it certain:
every cancellation closes the row, and a closed row reads `canceled` whatever its `STATUS` says
(§3.7). On `develop` it cannot happen: the plan comes from the `ETGO_TenantPlan` marker, which a
cancellation does not touch. What it did on the ETP-5047 branch before the fix: from that tenant's
own session the checkout answered `DEMO_SELECTION_REQUIRED`, the upgrade page listed it in the
source picker, preselected it and badged it "Demo"; the backend accepted it as `demoClientId`; and
the paid setup associated it with the new environment, copied its profile and data, and revoked its
access, as if it were a demo.

The fix asks the lifecycle type as well as the plan:

- **Backend** (go `99f765229`): `TenantEnvironmentLifecycleService#isProductiveEnvironment` — any
  active subscription row, open or closed, else the `ETGO_EnvironmentType = PRODUCTIVE` marker or
  the retired `ETGO_TenantPlan` preference, so it holds with `ETGO_SubscriptionModel` OFF too. It
  writes nothing, never reads the access decision or its kill switch, and fails closed on the type
  reads only: a `findLatest` or `ETGO_EnvironmentType` read that throws answers "productive". A
  failure inside the retired `ETGO_TenantPlan` fallback (`TenantPlanPreferenceFallback#isProductive`
  via `resolvePlanWithoutSubscription`) is swallowed there and answers "not productive" —
  fail-open, accepted 2026-10-14 (Martin Taal); it retires in Phase F.
  `resolveCheckoutSelection`, `isOwnedFreeDemoClient` (400 `INVALID_DEMO_SELECTION`),
  `findFreeTenantIdsByAccountEmail` and `restorePaidOnboardingSelection` use it. A tenant that
  predates the lifecycle metadata (no row, no marker, free plan) stays a valid source.
- **SPA** (sf `a9f570e88`): `isPurchaseSourceDemo` (`lib/environmentPresentation.js`) also excludes
  `environmentType: 'PRODUCTIVE'`, and `environmentPlanLabelKey` badges it "Productive".

The effect: a canceled productive tenant's checkout provisions a clean new environment, as on
`develop`. Reference: `feature-flags-and-tenant-upgrade.md` §2, *A productive environment is never
a source*; `schema_forge/docs/paid-tenant-infrastructure.md`, step 8.

**Accepted edge — a genuine demo dropped on a lookup error: accepted 2026-10-14 (Martin Taal),
same reasoning as the failed row lookup in §2.9, no code change.** When `isProductiveEnvironment`
fails on a type read, it answers "productive", so a demo the buyer chose is dropped as a source
without a message. From a demo session (`demoClientId` + `dataTransfer`) the failure on the session
client makes `resolveCheckoutSelection` answer 201 with no source recorded; a recorded source that
fails in `restorePaidOnboardingSelection` provisions a clean environment — no profile copy, no
transfer, no association — and the demo stays alive (its INFO line says the source "is not usable
as a demo source (a productive environment, or its type could not be read)"). The picker still lists
the demo, because `resolve()` answers null on the same error, and the backend then answers 400
`INVALID_DEMO_SELECTION` on submit. *Why accepted:* as in §2.9, a failed SQL statement aborts the
PostgreSQL transaction, so the request fails and stays retryable; only a failure other than a failed
SQL statement reaches the edge, and its worst outcome is a clean environment, never the association
of the wrong tenant.

**Follow-up:** none for this fix. Letting that tenant re-subscribe its own environment instead is
ETP-5742, and restricting the source to the account owner is ETP-5743 (§4.13).

### ✅ 5.7 `ETGO_PLAN_QUOTA.CONSUMPTION_SOURCE` means something else in ETP-5051

**Ticket:** owner ETP-5051.

**Status:** decided 2026-09-28 (Sebastián Barrozo)

**Decision: ETP-5051's meaning wins.** `CONSUMPTION_SOURCE` represents the **data source** of a
quota's consumption — the stored aggregate versus a live count at evaluation time — and
aggregation is configured on the billing resource (§5.6), not on the quota.

ETP-5046 shipped the other meaning: String, list reference `ETGO_QuotaConsumptionSource`, a single
`AD_REF_LIST` value `sum`, check `ETGO_PLNQTA_SOURCE_CHK` (`CONSUMPTION_SOURCE IS NULL OR
CONSUMPTION_SOURCE = 'sum'`) — an aggregation.

**Follow-up:** ETP-5051 — define the values, then change the check constraint, the `AD_REF_LIST`
values and the reference description together, before any quota row uses the column. Cheap now: no
`ETGO_PLAN_QUOTA` row exists anywhere (no sourcedata, no data-fix creates one); a data migration
once operators have filled it in.

### ✅ 5.10 Per-period usage cannot be produced — the usage per period is not recorded, the periods only on the ledger

**Ticket:** no ticket — the usage-based charging work (PRD §3 and §16 defer overage charging "with
its own PRD").

**Status:** decided 2026-09-28 (Sebastián Barrozo)

**Decision:**

- **Billing-period history is stored in a dedicated table**, so past periods stay available after
  the current period is updated. The ledger summary alone is not enough.
- **Period usage is calculated and stored ahead of time**, so the resource-limit check reads the
  current total instead of recalculating it during the request (§5.15).

What still holds from the analysis:

- `ETGO_SUBSCRIPTION` has one period slot (`CURRENT_PERIOD_START/END`), overwritten at each renewal
  (§5.5).
- Since ETP-5047 every `invoice.paid` row of `ETGO_BILLING_EVENT` carries the period it paid for in
  its summary (`WebhookPayloadSummary`: `service_period_start` / `service_period_end`, epoch
  seconds — the invoice **line** period, extracted as `SubscriptionLifecycleApplier.invoiceServicePeriod`
  does for the row, not the invoice's own `period_*`, which looks back one period). It is an
  abbreviated JSON string, not a queryable column, and periods before the ETP-5047 deploy were never
  recorded.
- `ETGO_USAGE_DAILY` (`MEASURED_CLIENT_ID`, `ETGO_BILLING_RESOURCE_ID`, `USAGE_DAY`, `QTY`,
  `IS_SETTLED`) has no link to a subscription; it joins one only by value
  (`ETGO_SUBSCRIPTION.ENVIRONMENT_CLIENT_ID = MEASURED_CLIENT_ID`, `USAGE_DAY` inside the period).
  ETP-5048's reconciliation covers payments and subscription status, not usage.

**Follow-up:** no ticket — an owner ticket, then the table design. Open design points: whether
period usage lives in the period table or in a separate period × resource table; who writes the
period (e.g. the lifecycle webhooks on `invoice.paid` / `customer.subscription.updated`, which
already extract it) and who writes the usage (the scheduled job of §5.15); how a stock combines
over the period (§5.6).

### ✅ 5.12 The would-have-billed report is scheduled in the PRD and excluded by its owner

**Ticket:** no ticket — the script needs an owner.

**Status:** decided 2026-09-28 (Sebastián Barrozo)

**Decision:** a script that helps gather the information is enough; the full would-have-billed
report is not built in this billing block.

PRD §14 assigns "would-have-billed report. Shadow mode" to ETP-5050, whose design
(`plans/2026-09-15-etp-5050-usage-measurement-design.md` §10) lists "the would-have-billed figure"
as out of scope.

**Follow-up:** no ticket — the script needs an owner. It still depends on §5.6 (how days combine)
and on the period it reports (§5.5, §5.10). PRD §14
(`schema_forge/docs/plans/2026-08-27-recurring-billing-and-resource-limits-prd.md`) still assigns
the report to ETP-5050 and should be updated to the script.

### ✅ 5.15 The resource-limit check reads stored usage; usage is recomputed on one two-hour cadence

**Ticket:** related ETP-5051 (the check) and ETP-5050 (the usage job); no ticket for the
scheduling.

**Status:** decided 2026-09-28 (Sebastián Barrozo)

**Decision:**

- **The everyday check stays lightweight.** Identify the subscription currently in effect for the
  client/environment and compare the requested consumption with that plan's limits, reading the
  latest stored period usage (§5.10). No billing-date or plan-transition calculation per request;
  the period calculation happens separately, ahead of time. Dates still matter for billing and for
  when a scheduled downgrade takes effect (`PENDING_EFFECTIVE_DATE`, §5.13).
- **Freshness: one globally configurable two-hour cadence for all billing resources.** Martin
  proposed that some resources recompute more often than daily; Sebastián chose one cadence for
  all, to keep the design simple.
- **One scheduled job, with a separate transaction per client**, so the whole run need not be
  atomic (§1.4).
- **A warning is logged when calculations fall behind the configured cadence**, to spot volume
  problems early.

**Follow-up:** no ticket.

- **Nothing schedules the usage job yet.** `UsageAggregationProcess` runs on demand from its menu
  entry and the module ships no process request (`schema_forge/docs/usage-measurement.md` §9:
  "nothing schedules it yet").
- **The cadence setting does not exist.** The only usage setting today is the settling window
  (`UsageSettings`). Each run recomputes the whole window plus a sealing day (7 days at the
  default), so a two-hour cadence repeats that 12 times a day.
- **There is no lag warning.** `ETGO_USAGE_RUN_LOG` (`RUN_ID`, `DATE_FROM`/`DATE_TO`,
  `STARTED_AT`/`FINISHED_AT`, `STATUS`, written by `UsageRunLedger` at the end of each run) holds
  what it needs.
- **Per-client transactions conflict with the grouped all-tenant query** — §1.4.

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
