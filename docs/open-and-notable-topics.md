# Open and notable topics — recurring billing block (ETP-5045 … ETP-5053)

A living register of the decisions still owed, the operational constraints that are not visible
from the code, the traps that would otherwise be rediscovered the hard way, and the decisions taken
since, with the implementation work each one still leaves.

**Scope:** the whole billing development — ETP-5045 (durable checkout state), ETP-5050 (usage
measurement), ETP-5046 (Subscription Plan Catalog + subscriptions), ETP-5047 (environment-access
enforcement and the lifecycle on the subscription row), ETP-5048 (billing reconciliation),
ETP-5049 (the plan catalog on the upgrade page and `/billing/subscription`), and what they hand to
ETP-5051/5053.

**Status key:** 🔴 decision owed · 🟠 constraint to respect · 🟡 known issue, worked around ·
✅ clarified or solved — the decision is taken; implementation may still be owed (§6)

**Ticket line:** every topic carries a `**Ticket:**` line under its heading. *Owner* = the ticket that must resolve or respect it. *Related* = where it came from or what it touches. **No ticket** = nothing currently covers it; it needs its own ticket or a decision on where it goes.

**To raise with:** a topic whose answer must come from a named person carries a `**To raise
with:**` line under its ticket line. Most 🔴 decisions were answered by Sebastián Barrozo
(product/tech lead) in a chat with Martin Taal on 2026-09-28. Of the points that chat left open, or
opened, only §1.2 is still owed: §1.4 was decided and moved to §5, and §1.6 and §1.7 were removed
(see §1).

**A settled topic moves to §6, it is not deleted.** Once a topic is decided or fixed it moves to
§6 "Clarified and solved" and **keeps its original number and heading text**, so a citation such
as `§1.1` or `§5.10` still resolves. A moved topic carries a `**Status:**` line (`decided <date>
(<who>)` or `partly decided`), the decision in one to three sentences, a `**Follow-up:**` line
naming the implementation work still owed and its ticket (or `no ticket`), and only as much of the
original analysis as a reader still needs; the full history stays in git, the only copy that cannot
drift from the code. **Section numbers are stable**: other documents cite them (`§3.7`, `§4.8`), so
a moved topic leaves a gap in its old section rather than shifting its neighbours, and a new topic
takes the next free number of the section it belongs to — a topic decided on arrival too (§5.15
sits in §6 under its §5 number). A gap with no §6 entry (§3.1, §3.9 and §5.11 among them) is a
topic deleted on or before 2026-09-28 under the earlier rule, or one removed or moved later and
named in its section's note (§1.4, §1.6, §1.7, §1.9, §2.4, §3.8); its history is in git.

---

## 1. Decisions still owed

*Moved to §6 on 2026-09-28: §1.1. On 2026-10-14: §1.3. On 2026-10-23: §1.5.*
*Removed on 2026-10-23 (Martin Taal): §1.6 "One-time cutover now (Phase F early)" — both rollouts
are supported, gradual (client by client) and one-time, so there is no decision to make; §1.7
"`END_DATE` meaning" — covered by §5.13.*
*Moved to §5 on 2026-10-23: §1.9 "Retire `GET /sws/go/billing/offers`?" — decided (retire it), now
§5.16 (ETP-5900); §1.4 "Per-client transactions versus today's per-resource-day transactions" —
decided (one write transaction per tenant), now §5.17 (ETP-5905).*

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
onto a real Stripe subscription (a plan change, ETP-5053) before any quota applies to it. Until it
is decided, the ETP-5051 evaluator treats such a row as having no current period: ALLOW, nothing
measured, one WARN per tenant per hour (§5.9).

**Sub-question (raised by Martin Taal, 2026-10-23): anchor every subscription to calendar
months?** A customer who subscribes on the 15th would first pay a prorated partial month, then be
billed on the 1st of every month.

- **How, in Stripe.** Checkout in subscription mode accepts `subscription_data.billing_cycle_anchor`
  (a timestamp, e.g. the next 1st) and `subscription_data.proration_behavior` (`create_prorations`
  charges the partial first period, `none` makes it free). Verify both against Stripe's current API
  reference when implementing. Existing subscriptions would need their anchor reset through a
  subscription update.
- **Why it matters.** One uniform period for every tenant simplifies per-period usage and quotas
  (§5.5, ETP-5051) and reporting, and gives the no-Stripe-subscription case above a natural answer:
  the calendar month.
- **Open sub-points:** whether a yearly plan is anchored to the 1st of the month or to 1 January;
  which time zone defines "the 1st" (UTC or the tenant's); and how the proration line reads on the
  first invoice.

### 🔴 1.8 Is the `billing-reconciliation-schedule` flag overkill? — decide later (ETP-5048)

**Ticket:** related ETP-5048; no ticket of its own. **Status:** decision owed, **deferred** — safe
to revisit after the first production weeks.

**To raise with / decides:** Martin

The flag is a second switch on top of one production already has. Production is never restarted,
so there the reconciliation is scheduled by hand in the Process Request window, and unscheduling
that request already stops it. On top of that, the flag:

- gates every scheduled run (timing *Schedule*, whoever created the request);
- turns a scheduled run into an ERROR in Process Monitor while it is off, and sends one internal
  alert per UTC day for it (`billing-reconcile-schedule-flag-off`, a
  `reconcile:schedule-flag-off:<yyyy-MM-dd>` row; approved 2026-10-21);
- drives `BillingReconciliationScheduleStartup`, which plants the request on environments that do
  restart.

**Options:**

1. **Keep it as a master kill switch** — stop every scheduled reconciliation from ConfigCat (or the
   property) without touching Process Request, on every environment at once.
2. **Drop it, and the startup hook** — rely on Process Request alone, as Demo Trial Notices does.

**What removing it deletes:** the `FLAG_BILLING_RECONCILIATION_SCHEDULE` flag, the startup hook
(`BillingReconciliationScheduleStartup` and its fixed request id), the in-run gate and the flag-off
ERROR path with its once-a-day alert in `BillingReconciliationProcess` (`isScheduledRun`,
`timingOf`, `alertFlagOffOncePerDay`), their tests
(`BillingReconciliationScheduleStartupTest`, the gate cases of `BillingReconciliationProcessTest`)
and their documentation (design `plans/2026-10-18-etp-5048-billing-reconciliation-design.md`
decision 11, `billing-reconciliation.md` §3, the flag section of
`feature-flags-and-tenant-upgrade.md`). The menu entry, the process and Process Request scheduling
are unaffected either way.

---

## 2. Deployment — constraints that are not visible in the code

*Removed on 2026-10-23 (Martin Taal): §2.4 "The R37 sandbox pre-check must be re-run on every
target environment" — self-evident; its one non-obvious fact, the price fill on R37's rows, moved
to §2.3.*

### 🟠 2.1 Fallback buyers are unlimited and stuck on `legacy-productive`

**Ticket:** owner ETP-5053 (moving them to a real plan); felt in ETP-5051 (quotas). Related
ETP-5046, whose develop merge introduced the fallback.

- **Who.** Customers who bought through the legacy price fallback land on the grandfathered
  `legacy-productive` plan.
- **Unlimited.** That plan has no quota rows, so they have no limits (§5.1).
- **Real since ETP-5051.** Quotas are now reported and, in mode `block`, enforced on NEO creates
  (`plan-quotas.md`); these are the only customers they cannot limit.
- **The fix is a plan change, not a quota.** Adding quotas to `legacy-productive` is not the fix;
  moving these customers to a real catalog plan is. That is a plan change, which only ETP-5053
  provides. Until then they stay on `legacy-productive`.
- **How many depends on the environment.** New fallback buyers appear only while no active catalog
  plan carries a provider price (`PlanCatalogService` fallback predicate). In production the
  fallback is the selling path until the first priced plan is created, so everyone who buys before
  then joins this group.
- **What their subscription row records.** At purchase, the charged price id but no amount or
  currency, because the plan has none (design doc §7). Since ETP-5048 the billing reconciliation
  fills a missing price id, amount and currency on an open row from the subscription's Stripe price,
  once, audited as a `reconcile:subscription-price:<sub>` billing event
  (`SubscriptionReconciler.fillMissingPrice`, §2.3). Until the reconciliation has run on an
  environment such rows stay without an amount; a fractional-cent price leaves the amount empty
  even then (§4.11).

How the fallback works: design doc §6.1; the operator procedure: its §6.2. The offers endpoint
that used to quote the legacy price was fixed in ETP-5049 and is now to be retired (§5.16, ETP-5900).

### 🟠 2.3 Run the backfill AFTER the deploy, never before — and only as part of the switch

**Ticket:** owner ETP-5046 (R37 deployment) and ETP-5048 (the price fill on R37's rows); moving
grandfathered tenants to a priced plan is ETP-5053.

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

**R37's rows carry no amount or currency; the billing reconciliation fills them.** R37 gives each
backfilled tenant a row on the priceless `legacy-productive` plan, with the charged Stripe price id
where its checkout request recorded one and never an amount or currency (design doc §7). Since
ETP-5048 the reconciliation's local-to-provider phase fills `PROVIDER_PRICE_ID`,
`SNAPSHOT_AMOUNT` and `SNAPSHOT_CURRENCY` from the subscription's first item price on every open
row where they are NULL, once, audited as a `reconcile:subscription-price:<sub>` billing event
(`SubscriptionReconciler.fillMissingPrice`; `plans/2026-10-18-etp-5048-billing-reconciliation-design.md`
§5). Until it has run on an environment such rows stay priceless — harmless for access, which reads
`STATUS` alone, and visible only in billing records and quota snapshots.

### 🟠 2.5 `./gradlew test` needs JDK 17, not the default 21

**Ticket:** no ticket — local build environment.

`build.gradle` targets Java 17; core's bundled Groovy/ASM cannot read Java 21 bytecode.
`:compileTestGroovy` dies with `Unsupported class file major version 65` while compiling core's
own Spock specs — so **no test runs at all**, and the failure looks unrelated to whatever you
changed. `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`. CI already runs on JDK 17
(`.github/workflows/mini-pipeline.yml`, `actions/setup-java` with `java-version: '17'`); the trap is
local only, where the default JDK is still 21.

### 🟠 2.6 Gradle reports `UP-TO-DATE` and runs nothing

**Ticket:** no ticket — build-tooling behaviour, applies to every ticket.

A re-run after an unrelated change silently executes zero tests while printing `BUILD SUCCESSFUL`.
Delete `build/test-results/{test,goIsolatedDalTest,goTestUnit}` and pass `--rerun`, then read the
task outcome lines — not just the build result. `goTestUnit` (ETP-5695, `build.gradle`) is the
database-free half of the suite and keeps its own results directory like the other two. This
produced a false "verified" claim once during ETP-5046.

### 🟠 2.7 `Hooks-Verified` seals do NOT survive a rebase, despite claiming to

**Ticket:** owner [ETP-5898](https://etendoproject.atlassian.net/browse/ETP-5898) — a bug in the
git-hooks seal (`.githooks`). schema_forge carries the same hook code
(`.githooks/commit-msg`, `.githooks/lib/hooks-proof.sh`) and the same bug.

The trailer carries two fingerprints: one over the commit's tree (fp1), and — in `v2` — one over
its patch-id (fp2), so that *"a rebase/cherry-pick that replays the SAME diff onto another base
keeps a valid seal"*. In practice fp2 is wrong for many existing commits.

**Cause.** `.githooks/commit-msg` computes fp2 as `hooks_fingerprint` of `hooks_patchid_staged`,
which is `git diff --cached | git patch-id --stable` — the index against **HEAD**. During
`git commit --amend` HEAD is still the commit being amended, so that diff is only the amend delta,
not the commit's diff against its parent: **empty for a message-only amend** (fp2 is then the
fingerprint of the empty value, identical for every such commit on one hooks version) and partial
for an amend that changes files. The verifier recomputes the patch-id from the commit against its
first parent (`hooks_patchid_of_commit`), so the two differ. The same script already handles the amend
for the `Checks:` list — it diffs the index against the merge-base, with an "AMEND-SAFE" comment —
but not for fp2. Amend is the likely cause; it is not proven commit by commit.

**Scale** (all refs of this repo, 2026-10-23): **286 commits** carry `f99dd62f8d62`, the empty-value
fp2 of hooks version `731f5aff`; on the current version `dfaa09a4` four commits carry its
empty-value fp2 `36620d89e685`, the latest on 2026-10-22 (`6727f6400`, `e50aaef29`).

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
`SubscriptionModelSwitch#isOn` (no cache, no restart). It has three readers: the paid upgrade, once
per purchase; the **ETP-5742** re-subscription (`EtendoGoJwtServlet.applyReactivation`), once per
applied re-subscription, to decide whether the payment also keeps the preference route's own
watermark; and `TenantPlanPreferenceFallback`, once per resolution or batch it serves, only to pick
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

### 🟠 2.10 Billing reconciliation go-live: the flag and the Process Request go together (ETP-5048)

**Ticket:** ETP-5048. Production is never restarted, so the reconciliation is scheduled by hand
there (`billing-reconciliation.md` §3).

Turn the `billing-reconciliation-schedule` flag on **and** create the production Process Request
(**Billing Reconciliation**, client System, timing *Schedule*, hourly) in the same step. A request
firing while the flag is off does no work and ends as an ERROR in Process Monitor every hour, plus
one internal alert a day (`billing-reconcile-schedule-flag-off`); a flag on with no request does
nothing at all. Whether the flag is needed is open: §1.8.

On a fresh local environment the menu popup answers "You do not have sufficient privileges" until a
full build has run (`./gradlew compile.complete`, then a smartbuild): a class-mode smartbuild does
not regenerate the WAD servlet the popup goes through. A normal deploy runs WAD. See
`billing-reconciliation.md` §2.

### 🟠 2.11 Usage aggregation go-live: the flag and the Process Request go together (ETP-5051)

**Ticket:** ETP-5051. Production is never restarted, so the usage aggregation is scheduled by hand
there (`feature-flags-and-tenant-upgrade.md` §1, `usage-aggregation-schedule`).

Turn the `usage-aggregation-schedule` flag on **and** create the production Process Request
(**Billing Resource Usage Aggregation**, client System, timing *Schedule*, every two hours) in the
same step — the §2.10 shape. A request firing while the flag is off does no work and ends as an
ERROR in Process Monitor (no internal alert, unlike the reconciliation); a flag on with no request
does nothing, and every quota on the stored aggregate then reads stale usage, which the
`[USAGE-LAG]` WARN reports. A quota whose consumption source is `live` does not depend on the job.

---

## 3. Legacy plans and the cutover

*Removed on 2026-10-23 (Martin Taal): §3.8 "One environment-access check, one 402 body, one kill
switch (ETP-5047)" — documented in `feature-flags-and-tenant-upgrade.md` §1
(`environment-access-enforcement-off`, *Operating environment-access enforcement*),
`neo-headless.md` §4.1 and §7, and ADR-0001.*

### 🟠 3.2 Phase F — deleting the transitional plan code — has no ticket

**Ticket:** no ticket — needs its own once every environment has switched (§2.9). Blocked by
ETP-5897 (§3.13).

Phase F deletes the transitional fallback that answers a rowless tenant from the retired
preferences (design doc §8; `feature-flags-and-tenant-upgrade.md` §3, *Transitional read
fallback*). Without a ticket that code stays indefinitely.

- **Precondition — R42.** R42 (ETP-5548) re-inserts the `ETGO_TenantPlan` marker for every paid
  tenant, rows or not, and only R37's retirement branch removes it again. The end condition below
  holds only once R37 has run after R42 for every tenant (§3.6) and §3.13 is fixed (ETP-5897).
- **Trigger, on every environment** (design doc §8.1): it has switched (§2.9);
  `select count(*) from ad_preference where attribute = 'ETGO_TenantPlan'` reaches 0; and no log
  line starts with `ETP-5046-TRANSITIONAL-FALLBACK: tenant` at WARN. Grep that prefix, not the rest
  of the sentence (its wording changed in ETP-5047); the DEBUG line logged while the switch is OFF
  shares it.
- **What it deletes** — every site carrying the grep marker `ETP-5046-TRANSITIONAL-FALLBACK`
  (8 source, 7 test files on 2026-10-23):
  - `TenantPlanPreferenceFallback` and its use in `TenantPlanService.resolvePlan`;
  - `TenantPlanService.markProductive`, `retireProductivePreference`, `PREFERENCE_ATTRIBUTE`, and
    their calls in `EtendoGoJwtServlet.applyPaidUpgradeSideEffects`;
  - `SubscriptionModelSwitch`, its gates, and the `ETGO_SubscriptionModel` `AD_Ref_List` value;
  - the `allClientIds` overload of `EnvironmentPlanCache.of` and its callers;
  - the rowless-tenant preference reads and writes in `TenantEnvironmentLifecycleService`
    (`ETGO_SubscriptionStatus` / `DueAt`, `ETGO_EnvironmentType`, the event watermark
    `recordSubscriptionEventAt` / `readEventWatermark`), settling §3.5's write-path asymmetry;
  - the preference routes of the ETP-5048 reconciliation (`SubscriptionReconciler`, the
    `PREFERENCE_ROUTE_*` counters in `ReconciliationResult`) and of the ETP-5742 re-subscription
    (`EtendoGoJwtServlet.applyReactivation`). Both are correct today: a rowless tenant has only the
    preferences.
- **In the same cleanup:** fold `EnvironmentPlanCache` into a local map in
  `EtendoGoJwtServlet.handleEnvironments` (from `findLatestForClients`; 1–3 environments per account).
- **Not Phase F:** whoever retires `legacy-productive` (after ETP-5053) must change
  `EnsureLegacyPlanScript` in the same change, or the row is back after the next
  `update.database`. Deactivating the row survives (§2.8).

---

### 🟠 3.5 An unrecognised subscription status means ENTITLED, never locked out

**Ticket:** owner ETP-5046 (decided). Binding on whichever ticket first adds a `STATUS` value
(ETP-5053 is the likely one). The write-path asymmetry is settled in Phase F (§3.2).

`TenantEnvironmentLifecycleService.subscriptionStatusOf()` maps the latest row (a closed one reads
`canceled`) onto the access policy:

| `ETGO_SUBSCRIPTION.STATUS` | `EnvironmentAccessPolicy.SubscriptionStatus` |
|---|---|
| `active` | `CURRENT` |
| `past_due` | `PAST_DUE` |
| `canceled` | `EXPIRED` |
| **anything else, including null/blank** | **`LEGACY_ENTITLEMENT`** |

- **Fail-open on purpose:** a paying customer locked out by a status the build predates is an
  outage; a wrong admit is only a billing discrepancy.
- **The trap:** a new `STATUS` value is invisible — no error, no log, it reads as
  `LEGACY_ENTITLEMENT`. Whoever adds one must extend `subscriptionStatusOf()` and the check
  constraint `ETGO_SUB_STATUS_CHK` in the same change.
- **Still preferences, for a rowless tenant only** (row read first): `ETGO_EnvironmentType` and
  `ETGO_SubscriptionStatus` / `DueAt`. Both go in Phase F.
- **Write-path asymmetry (open).** `applyPaidUpgradeSideEffects` opens the row and retires
  `ETGO_TenantPlan`, yet still calls `markProductive()` unconditionally, writing
  `ETGO_SubscriptionStatus = CURRENT`. Nothing updates that preference on `past_due` or `canceled`,
  so it goes stale against the row: harmless for reads, misleading to anyone inspecting it. Phase F
  decides: stop writing it, or keep it in step.

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

**Ticket:** owner ETP-5047 (delivered). Binding on ETP-5053, whose plan change closes a row and
opens its successor. Related ETP-5443, ETP-5488, ETP-5046, ETP-5048.

Stripe lifecycle events (`invoice.paid`, `invoice.payment_failed`, `customer.subscription.*`) land
on an `ETGO_SUBSCRIPTION` row; only a tenant with no active row still gets the preference
projection (`ETP-5046-TRANSITIONAL-FALLBACK`). Design doc §8.4; operator view:
`feature-flags-and-tenant-upgrade.md` §1 *Operating environment-access enforcement* and §3.

**Which row** (`TenantEnvironmentLifecycleService.targetForSubscription` / `targetForTenant`):

- the **open** row with the event's `STRIPE_SUBSCRIPTION_ID` — the id is unique only among open
  rows (a successor row keeps it), so `END_DATE IS NULL` is part of the lookup;
- else the tenant from the checkout request, then the Stripe customer, and its latest row: no
  active row → preferences; an open row with no Stripe id, or with this one → that row; an open row
  for another subscription → `IGNORED`; only closed rows → `IGNORED`. Nothing revives a closed row;
- a new tenant's first events are `IGNORED` (`unresolved subscription`) and never redelivered, so
  the paid onboarding reads the period from Stripe itself (below).

**Rules to keep:**

- **Closed means canceled.** `customer.subscription.deleted`, a terminal `canceled` and
  `incomplete_expired` close the row (`END_DATE` = `ended_at`, else `canceled_at`, else now; never
  before `START_DATE`). A closed row reads `canceled` whatever its `STATUS`: access `EXPIRED`, plan
  `free`. `findLatest` answers the open row, else the latest closed one — never the fallback.
- **`paused` is alert-only** on the webhook and the reconciliation: one review alert per pause
  episode (`PausedSubscriptionAlert`), status and access untouched — a closed row can never be
  reopened, and a paused one can be resumed. Details: ETP-5048 design.
- **A row makes the tenant productive** (`resolve`, `isProductiveEnvironment`): any active row,
  open or closed, wins over `ETGO_EnvironmentType`.
- **Close with a targeted update, never a session flush.** `openSubscription` closes a stale open
  `canceled` row through `SubscriptionService.closeInDatabase` (one HQL update guarded by
  `END_DATE IS NULL`) before inserting: `etgo_sub_open_envclient_uq` needs the close in the
  database first, and a flush under `SystemContext` would write the whole pending onboarding as
  user `0`. ETP-5053 must close rows the same way.
- **Three columns, three jobs.** `GRACE_ANCHOR`: end of the paid period, set only while
  `past_due`; an old-shape row falls back to `CURRENT_PERIOD_END` (`graceAnchorOf`).
  `CURRENT_PERIOD_START/END`: Stripe's period, written as a pair; on `invoice.paid` from the invoice
  lines, never from `invoice.payment_failed`. `LAST_EVENT_AT`: the forward-only ordering watermark;
  while NULL the `ETGO_SubscriptionEventAt` preference is read instead.
- **Period after onboarding:** `fillSubscriptionPeriodBestEffort` →
  `SubscriptionService.fillPeriodFromProviderAfterCommit` reads the subscription once the row is
  open and writes the period only while both boundaries are NULL, so an event's period wins. Best
  effort: one WARN on failure, the onboarding is never affected.
- **Read nullable Stripe strings with `StripeJson.text`**, never a bare `optString`, which turns
  JSON `null` into the text `'null'`.
- **Context:** the webhook has no `OBContext` and installs its own (`SystemContext.run`); reads run
  in admin mode (ETP-5488). A failed status write marks the event `FAILED`; a failed watermark write
  on the preference route answers 500, so Stripe redelivers.
- **Dev lifecycle tool:** mirrors `CURRENT` / `PAST_DUE` / `EXPIRED` onto the open row only;
  `NONE` / `LEGACY_ENTITLEMENT` are ignored once a row exists. Accepted.
- **R37 carries the preferences onto the row** (`STATUS` with `NONE → canceled`, `GRACE_ANCHOR`,
  `LAST_EVENT_AT`; design doc §7.0) and needs the ETP-5047 columns (§2.3).

**Open:** the preference watermark of a rowless tenant (`recordSubscriptionEventAt`,
`readEventWatermark`) ends with Phase F (§3.2).

### 🟡 3.10 A JWT minted before the block keeps working on Copilot until it expires

**Ticket:** accepted as a known risk (Martin, 2026-09-28); no ticket — found in ETP-5047 QA (BUG-1); a fix would live in `com.etendoerp.copilot` or in the secure-web-services token lifetime.

The environment-access check (`EnvironmentAccessGuard`, `feature-flags-and-tenant-upgrade.md` §1) runs where a request enters a tenant through this module: NEO,
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
`neo-headless.md` §7 states for any new tenant servlet; or (b) shorten the secure-web-services token lifetime —
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
guard AND the `OBContext` (`neo-headless.md` §7); a failed role lookup refuses the request with **503** instead of
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

### 🟠 3.13 R42 re-inserts the `ETGO_TenantPlan` marker next to a subscription row after the switch

**Ticket:** owner [ETP-5897](https://etendoproject.atlassian.net/browse/ETP-5897) — must land
before Phase F, which it blocks (§3.2). Related ETP-5046 (opt-in R37, the switch of §2.9), ETP-5548
(R42).
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
  (the design doc's §6.2 checklist, repeated in this file's §2.9) goes back above 0 after the next full data-fix run, and so does §3.2's
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

The tenants to treat are exactly the ones the marker check of the switch verification (the design
doc's §6.2, this file's §2.9) reports with a marker and an `ETGO_SUBSCRIPTION` row.

**Proper fix.** A subscription-aware successor of R42 — same restoration, but no marker for a tenant
that has any `etgo_subscription` row — plus retiring R42 in `retired.json` (`supersededBy` the
successor; the R42 file stays byte-for-byte untouched, as for every applied fix). It must land
**before Phase F**: Phase F deletes the fallback that would read a stale marker, but its trigger
(§3.2) cannot be met while R42 keeps re-creating them. Recorded on the data-fix side in R37's
header as well.

## 4. Known issues

*Fixed on arrival and recorded in §6 on 2026-10-14: §4.14. Moved to §6 on 2026-10-23: §4.8.
On 2026-10-24: §4.17. Marked resolved inside their open topic on 2026-10-23: §4.13 items 11 and
12.*

### 🟡 4.4 The at-sign guard is over-tested (ETP-5050)

**Ticket:** no ticket — its former owner ETP-5050 is merged, so nothing owns the trim now.

`UsageMessages.atSafe` replaces `@` with `(at)` in any value interpolated into a user-facing
message, because `OBMessageUtils` treats `@token@` as a substitution and an unescaped at-sign in a
billing resource catalog search key, an HQL fragment or a Hibernate/CDI exception message can blank the message. The
guard is sound and has ~26 call sites (2026-10-23; ETP-5048's `BillingReconciliationProcess`
added five).

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
there are ~26 call sites) but over-applied at sixteen; three or four spot checks carry the same
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

One caller of a plan-less overload is not a test: `DevProvisioningFailureFixtureService.create`
(`rest/`, shipped in `src/` but enabled only in a `local` runtime) records its E2E failure fixture
through the four-argument `recordRequested`, so it reaches the null plan too.

**Suggested fix:** `Objects.requireNonNull(plan, ...)` and give the fixtures a real `Plan` — the
test fixtures and `DevProvisioningFailureFixtureService` alike, or the local fixture breaks.
`GrandfatheredSubscriptionIntegrationTest` already creates one, so the pattern exists. Not done
during the ETP-5045 merge because changing a method contract mid-merge is the wrong moment.

### 🟡 4.7 The rest of the `OBContext` survey — one real, one false alarm, one unread

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
| `roles/RoleInheritanceReconciliationService` | ⚪ **false positive** — its only `setOBContext` match is prose in a comment (line 431) describing a *caller* that runs as system; there is no call |
| `rest/EtendoGoJwtServlet` | ❓ **unaudited** — 30 raw system installs (`OBContext.setOBContext(ZERO_ID, …)`, recounted 2026-10-23); ten sites now go through `payment/SystemContext` (among them the lifecycle webhook, the re-subscription, the paused/resumed events and the ETP-5548 checks `isProductiveNameTakenByAccount` and `isAssociatedDemo`). Note the checkout paths still run in system context after the name check: `requireBillingOwner` → `hasOwnedEnvironment` installs it first, raw |

**`CompanyInvitationService` — the real one.** Two sites, both `restorePreviousMode()`-only:

- `resolveInvitation(...)` — installs at **536**, unwinds at **581** (line numbers as of 2026-10-23)
- `withAdminMode(...)` — installs at **785**, unwinds at **790**

It is the cheapest of the three to fix, because `withAdminMode` is *already* the `runAsSystem`
shape — a wrapper every accept path funnels through (`acceptExistingAccountInAdminMode` at 606,
`registerAndAcceptInAdminMode` at 694). It simply never captures. `resolveInvitation` is the only
method opening the context inline, so routing it through `withAdminMode` would collapse the class
to one context site and fix both at once. The case to care about is
`registerAndAcceptInAdminMode`, which creates a user and accepts an invitation: anything continuing
on that thread afterwards runs as system. That is the shape of the hazard; no exploit has been
traced.

**`EtendoGoJwtServlet` — deliberately left as a question.** 30 raw installs next to ten sites
routed through `SystemContext` is not evidence of 28 leaks, and it is not evidence of none either.
A heuristic once marked it "OK" on the strength of a single capture/restore site; nobody has read
the raw ones. Whoever picks this up should treat the
verdict as unknown rather than inherit an unearned pass.

**Left unfixed on purpose.** Neither belongs to ETP-5046, and widening an already large merge to
carry them would make it harder to review, not safer. They want their own ticket — and the fix is
mechanical once the pattern is recognised, which is the entire reason this section exists.

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
`HttpUrlConnectionStripeApiClient`. `HostedCheckoutService` and `PlanPriceDerivationHandler` use
it, and so, since ETP-5047/5048, do `SubscriptionReconciler`, `BillingReconciliationService`,
`StripeListPager` and `StripeSubscriptionPeriodReader`. `StripePriceService` (the price lookup of every checkout and of
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
buyer's checkout then fails on it.

**A third reader since ETP-5048.** The reconciliation's price fill (§2.3) reads the subscription's
first item price through its own `SubscriptionReconciler.ProviderPrice`, which takes the amount
from `unit_amount` only. For a fractional-minor-unit price (`unit_amount` null) it fills
`PROVIDER_PRICE_ID` and `SNAPSHOT_CURRENCY` but leaves `SNAPSHOT_AMOUNT` NULL — and, the price id
and currency now being set, never revisits the row — while plan save would have read
`unit_amount_decimal`. One shared parser fixes this reader too. The old transport also turns every failure into a generic
`IOException`, so on the checkout path "that price does not exist" (a completed 4xx) and "Stripe
was unreachable" are indistinguishable — the split `StripeResponse` / `StripeTransportException`
exists to prevent exactly that.

Fix: move both services onto `StripeApiClient` and have plan save and checkout share ONE price
parser, so a price that saves is a price that can be bought. Their specs
(`StripePriceServiceTest`, `StripeCustomerPortalServiceTest`) then use the recording fake instead
of static `CheckoutConfiguration` mocks. Found after the ETP-5046 develop merge; not implemented.

### 🟡 4.12 `NeoAuthenticatorEnvironmentAccessTest` re-tests the shared auth pipeline (ETP-5047)

**Ticket:** no ticket — the trim is parked (§4.13); related ETP-5047, ETP-5455.

`schemaforge/NeoAuthenticatorEnvironmentAccessTest` (~594 lines, 17 tests as of 2026-10-23) was written for ETP-5443,
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

**Suggested shape:** ~4 tests, ~150–200 lines, down from ~594:

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

**Ticket:** items 1–10: no ticket — candidates for one follow-up ticket; items 11 and 12:
**resolved** (ETP-5743 and ETP-5742, both linked to ETP-5047). Related ETP-5047.

Found during ETP-5047's development, review and QA, then taken out of its scope by Martin on
2026-10-14. The topic stays open for items 1–10. The **Since** column says where each one comes
from, checked against `develop` (go `3b4afe5a9`, sf `995a2cf9f`) and the ETP-5047 base (go
`fa7668ff7`, sf `59b286c2b`): `develop` = was already there before ETP-5047; **ETP-5047** = arrived
with ETP-5047's own changes. ETP-5047 is merged to `main`, so the column no longer decides a deploy;
it says only where the bug came from. Fixes for most are
built, reviewed and QA'd but **parked, unpushed, on local branches only** — a reader on another
machine cannot see them; they are the starting point for whichever ticket picks this up:

- `com.etendoerp.go` — `parked/ETP-5047-followups` (18 commits on `fa7668ff7`, head `ca1dc71cc`);
- `schema_forge` — `parked/ETP-5047-followups` (14 commits on `59b286c2b`, head `5da6521f0`);
- `schema_forge_core` — `parked/ETP-5047-core-402-reader` (`d57298c06`).

Those branches also carry the opt-in enforcement of §1.3 and the test trim of §4.12; several fixes
below assume it (item 1's 503 is decided per enforcement flag).

| # | What happens | Impact | Since | Parked fix |
|---|---|---|---|---|
| 1 | When the commercial access check itself throws (e.g. a DB error) NEO answers **500** `CONTEXT_UNAVAILABLE`: the guard runs in the bind step, and since ETP-5489 (`03a71370c`) an exception there goes to `EnvironmentRequestAuthenticator.contextUnavailableFor`, no longer to `refusedFor` (it used to answer **401**, which the SPA read as an expired session and logged the user out). MCP answers 503 there since ETP-5047. | The logout is gone. What remains: a generic 500 rather than a retryable 503 that says the access could not be verified, and a different status from MCP for the same failure. | `develop` (the 401; the 500 since ETP-5489) | go: 503 `ENVIRONMENT_UNVERIFIED` in the guard's shared body, decided per enforcement flag (`8818b02b0`, `a77f783c9`) — written against the 401 and predating ETP-5489, so it needs a rebase onto `contextUnavailableFor`. |
| 2 | A SQL-level error inside `TenantEnvironmentLifecycleService.evaluateAccess` aborts the request's PostgreSQL transaction. | Later DAL work and the end-of-request commit fail (500s); on `POST /session/environment` the rotated session can be lost → logout. | Transaction abort: `develop`. Session loss: **ETP-5047** (that endpoint evaluates access only since `enforcedDecision` / `accessDecision`). | go: a savepoint around the evaluation and a DB-backed integration test (`3f64f1219`); a WARN when no savepoint can be taken (`ca1dc71cc`). |
| 3 | Error bodies differ by door. **402:** the Copilot bridge answers a flat `{"error": "<message>"}` and the OAuth2 API-key endpoints their own envelope (`access_denied`), not the shared structured body. **503** (unresolved tenant — `EffectiveClientResolver` — and MCP's failed check): NEO answers `{error: {message, status}}`, MCP and the Copilot bridge a flat `{"error": "<message>"}`, OAuth2 `access_denied`. | Clients need one reader per door; on OAuth2 a retryable 503 looks like a denial. | 402 shapes: `develop`. Every 503: **ETP-5047**. | go: shared 402/503 body on the Copilot bridge (`e8d5d8ed8`), `temporarily_unavailable` for the OAuth2 503 (`959689518`); sf: the readers accept both shapes. |
| 4 | SPA: a 5xx or network failure on `windowaccessmap` shows "your role has no access"; session restore on a 5xx shows a blank screen. | Wrong copy for a temporary failure; a blank app with no way out. | `develop` | sf: `AccessUnreachableScreen` (retry / log out) and `SessionBootingScreen`. |
| 5 | SPA: a list shows an empty grid on any load failure — no error state. | A failed load looks like "no records". | `develop` | None yet. |
| 6 | SPA: the backend error code is lost on several paths — `useEntity` save / delete / process, `apiError`, the API-keys page, the `DataTable` inline toggle, `useCreateMovement` / `useStatementActions` — so English backend text reaches the user. | Untranslated messages in a Spanish UI. | `develop` | sf: a code → i18n map (`lib/backendErrors.js`, `lib/requestError.js`) covers all but the two finance hooks. |
| 7 | SPA API-keys page: hardcoded English confirm titles and copy toast; builds `createApiFetch` from the `@/auth/api.js` barrel instead of `useApiFetch`. | Breaks the i18n and request-policy rules of `schema_forge/CLAUDE.md`. | `develop` | sf: translated, moved to `useApiFetch` (`6e2453a5c`). |
| 8 | SPA does not recognise the OAuth2 API-key 402 envelope (`error: access_denied` + `error_description`) as an environment block; the message is untranslated. | Low: another request's 402 normally blocks the app first. | `develop` | None. |
| 9 | A brief access-map failure during a background refresh replaces the whole app. | Unsaved form input is lost. Core behaviour. | `develop` | None — needs an `app-shell-core` change: keep the previous access map on a background refresh. |
| 10 | Two readers of the 402 decision: core `apiFetch` reads only the message text; schema_forge's `readEnvironmentAccessDecision` (`d18c484d9`) reads `error.decision` first. | Same answer while the backend keeps both in sync; they drift if it stops. | **ETP-5047** | core: the reader moves into `app-shell-core` (`d57298c06`); the schema_forge side waits for a core release. |
| 11 | ✅ **Resolved.** Purchase-source ownership was `EtendoGoJwtDalHelper.clientBelongsToAccountEmail`: any active user of the environment whose email or username is the account email (or an `email+suffix` variant). That matches a member **invited** into another owner's environment, not only its owner. | An invited member might name another owner's demo as `demoClientId`, so a purchase would associate, copy and revoke a demo the buyer does not own — against FR-08 of `schema_forge/docs/plans/etp-5396-demo-to-pro-prd.md`. | `develop` (predates ETP-5046/5047) | **Fixed by ETP-5743** (`d4eb9e739`): the purchase-source checks (`isOwnedClient`, `isOwnedFreeDemoClient` in `EtendoGoJwtServlet`) ask `EtendoGoJwtDalHelper.clientOwnedByAccountEmail`, which requires the account's user to be the flagged owner. A tenant created before the owner flag (ETP-4830) has no owner recorded and keeps the old account-match rule until a backfill assigns one. |
| 12 | ✅ **Resolved.** A canceled tenant could not re-subscribe its own environment: every checkout provisions a new one, and since ETP-5047 a canceled productive tenant is not a demo source either (§4.14). The flow was decided on 2026-10-14 (Martin Taal; §2.9, §3.7). The same-name check `ownsProductiveNamed` (pre-checkout and onboarding) asks `resolvePlan`, which reads a canceled tenant as `free`, so its name does not count as taken. | A canceled customer who wants to come back gets a second environment, and may give it the canceled one's company name. | **ETP-5047** (it closes the row on cancel, so a canceled tenant reads `free`; the missing flow itself predates it) | **Fixed by ETP-5742**: the owner re-subscribes the canceled environment from its blocked screen (`POST /sws/go/billing/reactivation`, applied by the payment webhook), and `ownsProductiveNamed` also counts a productive lifecycle type, so the name stays taken (`feature-flags-and-tenant-upgrade.md` §3). |

### 🟡 4.15 Paid provisioning is only half extracted from `EtendoGoJwtServlet` (ETP-5048)

**Ticket:** accepted by Martin on 2026-10-18 as a narrower extraction; to be finished by
[ETP-5892](https://etendoproject.atlassian.net/browse/ETP-5892) "Extract onboarding steps and webhook
application from EtendoGoJwtServlet" (created 2026-10-23 in epic ETP-3504), which must be coordinated
with ETP-5396. Related ETP-5048.

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
  goal is reconciliation, with no change in behaviour to show for it. This reason covers only the
  provisioning steps that existed before ETP-5048.
- **New ETP-5048 code that was placed in the servlet, not deferred:**
  - `provisionPaidPurchase` and its two private helpers `serverSideAddress` and `serverSideTaxId`.
    `provisionPaidPurchase` calls the private servlet methods `findCurrencyId`,
    `isPaidOnboardingNotOwned`, `evaluatePaywall`, `restorePaidSelection` and `claimPaid`.
  - The webhook paused/resumed glue, `raisePausedSubscriptionAlert` and `endResolvedPauseEpisodes`.
    They use the servlet's stores (`billingEventStore`, `checkoutRequestStore`) and its
    `internalAlertService`, and `raisePausedSubscriptionAlert` also calls the private
    `resolveLifecycleTarget`. The logic itself lives in `payment/PausedSubscriptionAlert`.

  Moving these alone was considered on 2026-10-23 and not done. It would only relocate the
  coupling: those private methods would be widened to package-private, and `PaidProvisioningService`
  would still instantiate a servlet. ETP-5892 rewrites them on top of the extracted onboarding
  service anyway.
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

**To finish it** (ETP-5892). The servlet is about 7,200 lines. Since ETP-5847, `runOnboarding` has a
second caller besides the HTTP path and `provisionPaidPurchase`: `provisionSignupEnvironment`
(passwordless signup).
1. Move the step methods into an onboarding service that takes an `OnboardingProgressSink` instead
   of a `PrintWriter`, migrating the spying tests to that service.
2. Have the servlet build an `NdjsonOnboardingProgressSink` and call it.
3. `PaidProvisioningService` then stops instantiating a servlet, and `NdjsonSinkWriter` can be
   deleted.
4. Move the Stripe webhook event application (`applyCheckoutEvent`, `applyCheckoutPaid`,
   `applySubscriptionLifecycle`, `applyAlertOnlyEvent`, `resolveLifecycleTarget`,
   `raisePausedSubscriptionAlert`, `endResolvedPauseEpisodes`) into a service in
   `com.etendoerp.go.payment`, next to `CheckoutWebhookProcessor`.
5. The same for `PaidReactivationService` (ETP-5048 review B2): it is a facade that calls the
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

### 🟡 4.18 R37 has not run in production, yet its integration test was removed

**Ticket:** no ticket. Related ETP-5046 (R37), ETP-5047 (`66f7489c6`, which removed the test).

**To raise with / decides:** Martin — whether to restore the test.

`66f7489c6` ("Remove the R37 backfill integration test", 2026-10-21) deleted
`payment/SubscriptionBackfillIdempotencyIntegrationTest` and its two `build.gradle` entries in the
isolated-DAL test lists, on the premise that R37 had already been applied in production and so had
no environment left to break. **R37 has not run in production** (Martin Taal, 2026-10-23): it is
still the opt-in step 3.4 of the switch to the subscription model on every environment (§2.3,
§2.9).

**What the removed class covered.** It was the only test that **executed** the real
`20261005T180000Z__R37-tenant-subscription-backfill.sql` against a database, on synthetic tenants
(17 tests): a second run inserts nothing (the two-layer idempotency guard) and `@check` converges
to zero rows (otherwise the runner would re-apply the fix on every run); the abort guard writes
nothing when the legacy plan is inactive; the inserted row's shape, the Stripe ids and charged
price id copied from the checkout request, and `STATUS` / `GRACE_ANCHOR` / `LAST_EVENT_AT` seeded
from the lifecycle preferences; the marker retired in the same transaction, and only for a tenant
with a row; and the R42 / ETP-5047 cases where a marker next to an open, closed or inactive row is
retired without a backfill (§5.13).

**What remains.** `schema_forge/cli/test/data-fixes-r37-tenant-subscription-backfill.test.js`, a
Node test that reads the same SQL file **as text**, without a database: header metadata and
opt-in, tenant scoping of every statement, the shape of `@check` branches (A) and (B), the
missing-plan abort guard, the backfill `INSERT` and the `@report` contract. It pins what the SQL
says, not what it does when run — idempotency and the convergence of `@check` are no longer checked
by any test.

**If restored:** the removed class is recoverable from git (`66f7489c6^`), with its two entries in
`build.gradle`'s `isolatedDalTests` / `isolatedDalTestClasses`. It skips itself when the sibling
schema_forge checkout is absent.

### 🟡 4.19 A block quota is enforced on creates only — a resource that grows otherwise is never refused (ETP-5051)

**Ticket:** accepted as designed (Martin Taal, 2026-10-24); no ticket. Owner of the check ETP-5051.

The NEO write check (`NeoQuotaWriteGate`, `neo-headless.md` §4.16) runs after an insert, on the
row just created, and refuses it only when that row matches the resource's HQL restriction. Three
ways to consume a resource never pass through it:

- **Growth through a state change.** `POSTED_SALES_INVOICES` counts `e.posted = 'Y'`, and an
  invoice is created unposted, so it never matches at create time: a `block` quota on it is never
  refused, however far over the tenant is. Posting is a process, not a NEO create.
- **A customization that creates the row itself.** A `NeoHandler`/`@NeoExtension` whose `handle()`
  returns its own response skips the default create, and the check with it.
- **Rows created by processes**, imports or the Classic UI. The check sits on the NEO write path
  only; there is no DAL observer, by decision.

Such quotas are still measured and reported: on every environment of `GET /sws/go/environments`
(`quotas`, WARN and DENY only — what the SPA's quota banner reads, because core's
`loginEnvironment()` drops the session/environment body), on entering the environment (the same
`quotas` on `POST /sws/go/session/environment`) and on the account page (`planQuotas`).
**If it is ever needed:** check at the state change (the posting process) through the same
`QuotaEvaluator`, never with a second comparison. The enforcement table, the extensibility ladder
and every accepted limit, for operators: `plan-quotas.md` §2, §3 and §6.

**Concurrent creates (review W4, decided by the coordinator 2026-10-24).** For a `live` `block`
quota the check takes a PostgreSQL transaction advisory lock per tenant and resource
(`pg_advisory_xact_lock`, `QuotaLock`) right before the live count, held until the create commits
or rolls back, so two concurrent creates are counted one after the other and cannot both slip
under the cap. It is taken only once a created row matched the resource and the tenant has such a
quota: no other create pays for it. The stored `aggregate` takes no lock — it is lagged by the
usage job, so its cap is approximate anyway and may be overshot by what was created since the last
run. A batch adds to that (review N6): each operation is its own create, the per-create charge
resets per operation and the aggregate never sees rows earlier operations of the batch created,
so a batch of N creates can go up to N−1 over an `aggregate` cap.

**Which creates are checked: a per-client cache** (redesign, decided by Martin Taal 2026-10-24).
`QuotaClientCache` keeps, per client id, the client's open subscription (period, closed flag) and
its plan's active `block` quotas on declarative resources, grouped by the entity each counts. An
entry is built on the request's own session through one `DalQuotaLookup.forTenant` call and trusted
for 60 s; a client with no block quota gets an empty entry, cached too. A create of an entity the
request's client does not cap returns at once with **no query**, whatever other clients' plans cap
— this replaced a global index of every capped entity, which made a tenant with no quota pay one
row-match query per create of an entity some other plan capped (review N4). A quota switched on
or off, a plan change or a renewal reaches a client's creates within 60 s. The map is keyed by
client id, not by HTTP session (bearer, batch and MCP requests have none), and bounded: expired
entries are swept at most once a minute when an entry is stored, and if 10 000 entries are still
held after a sweep the map is cleared and each client rebuilds its entry on its next create.

**A failing check** (review B1; QA BUG-4). Only an error raised before Hibernate sees a query
(a fragment `UsageQueryComposer` rejects, a missing strategy counter) fails open: logged, the
create stands. Everything else fails **closed**: rolled back, answered 500 `QUOTA_CHECK_FAILED` (in
a batch: a failed operation, the batch rolls back). That covers an error from the database, a
transaction Hibernate marked rollback-only — including an HQL that fails to parse, e.g. a
restriction naming an unknown property, which Hibernate marks rollback-only (accepted by the
coordinator as the safe direction; `UsageResourceValidator` rejects such a restriction at save
time) — and a client's cache entry that cannot be built, which is refused before the create runs
(no callout, no insert).

**Which created row counts** (re-review W2). A created row is charged to the current period only
when it matches the resource's restriction **and** its date property falls in the counted window;
a row dated outside it (a backdated invoice, say) does not consume this period and stands. An
in-window row is refused when the consumption before the create — a live count leaving out the
created rows, or the stored aggregate — plus one exceeds the included quantity. Because the window
is day-aligned (`[startOfDay(start), startOfDay(end))`), a row created on a period's last, partial
day falls outside it: it is not charged to the ending period and a block quota does not refuse it
(QA INFO note, accepted). That day opens the next period's window, so the row counts there once
the renewal is recorded (corrected in the ETP-5051 docs pass; the earlier text said it counted in
no period).
The quota *reports* never run on the request's transaction (`IsolatedDalRead`), so a failing
report cannot poison what the request writes. Each report is one hop to a bounded pool of 4 daemon
workers (64 queued, more refused and reported as `[]`), with a 25 s `statement_timeout` inside the
worker's transaction and a 30 s wait in the caller (re-review W2–W4). The usage-lag WARN is checked
by the reports and the usage job, never on a create.

### 🟡 4.20 The CSV/XLSX import shows a quota refusal as a generic row error (ETP-5051)

**Ticket:** no ticket. Related ETP-5051 (found in its frontend work); the fix belongs to
schema_forge_core.

The SPA's generic import (`ImportDialog` in `@etendosoftware/app-shell-core`) writes through
`/sws/neo/batch`, so a create of a capped entity **is** refused: 403 `QUOTA_EXCEEDED` (or 500
`QUOTA_CHECK_FAILED`) at `error.detail.error` of the batch failure envelope, and nothing of that
batch is saved. The refusal itself is correct; only its message is lost. The row message is decided
in core's `importEngine.classifyImportError`, which has no quota case, so the user sees the generic
row error instead of "plan limit reached". Every other SPA write path translates both codes
(`schema_forge/docs/generated-custom-windows/app-shell-functional-flows.md` §3.2a-ter).

**Fix:** a quota case in `classifyImportError` in schema_forge_core, reading the code at
`error.detail.error` as `extractQuotaExceeded` / `isQuotaCheckFailed` (`lib/planQuotas.js`) do in
the functional repo, then a core version bump. Workaround meanwhile: none in the import; the
quota banner and `/account` show the limit.

### 🟡 4.21 A request right after `POST /sws/go/session` or `/session/environment` can get a 401

**Ticket:** no ticket. Pre-existing; observed while testing ETP-5051, outside its scope.

Right after `POST /sws/go/session` (login) or `POST /sws/go/session/environment` (environment
entry), an immediate follow-up request with the freshly rotated session cookie and CSRF token was
answered 401 in about one of three attempts (seen with `curl` against a local Tomcat on
`GET /sws/go/environments` and `GET /sws/go/billing/subscription`); the same request moments later
succeeded. The pattern looks like **commit-after-response**: the response, with the new session
cookie, reaches the client before the transaction that stores the session is committed, so a fast
next request does not find it yet. Not confirmed in the code.

**Workaround:** none in the product. In the observation a repeat of the same request moments
later went through; whether the SPA hits the window in practice was not measured.

**To investigate:** when the session row is committed relative to writing the response in the
session endpoints (ADR `adr/0001-backend-managed-session.md`), and whether the rotation on
environment entry has the same order.

## 5. Handed forward to later tickets

*Moved to §6 on 2026-09-28: §5.7, §5.10, §5.12; §5.15 was decided on arrival and sits there too.
On 2026-10-23: §5.14, §5.5. On 2026-10-24 (ETP-5051): §5.1, §5.6, §5.8, §5.9. Moved in from §1
on 2026-10-23: §1.9, now §5.16; §1.4, now §5.17.*

### 🔴 5.13 `END_DATE` records what happened, never what will happen — and any value closes the row

**Ticket:** owner ETP-5053 (the `END_DATE` guard or meaning change). Closing the row on cancel was
settled in ETP-5047 (§3.7).

**To raise with:** Sebastián Barrozo — not discussed on 2026-09-28.

**What it means today.** `END_DATE` is past tense: the moment this row stopped being the tenant's
current row. Its writers: a cancellation (ETP-5047 — Stripe's `ended_at`, else `canceled_at`, else
now; never before `START_DATE`), the **ETP-5048 billing reconciliation**, which closes a row it
finds terminal at the provider (status read through `SubscriptionLifecycleApplier`) or absent there
(behind its circuit breaker), through the webhook's own write path —
`TenantEnvironmentLifecycleService.applySubscriptionEvent`, then
`SubscriptionService.applyLifecycleOutcome`, same closing rule — `openSubscription` closing a stale canceled row before a re-subscription
(ETP-5047, now), and a plan change (ETP-5053), which sets it to *now* and inserts
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
purpose. This was pinned by `SubscriptionBackfillIdempotencyIntegrationTest`, which executed the
real SQL. That class was removed in `66f7489c6` on the premise that R37 had already been applied
in production; the premise is false — R37 has not run in production (Martin Taal, 2026-10-23). What
coverage remains, and what is lost, is §4.18. The schema_forge source test
(`cli/test/data-fixes-r37-tenant-subscription-backfill.test.js`) still guards the file's text.

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

### 🟠 5.16 Retire `GET /sws/go/billing/offers` — decided, owed to ETP-5900

**Ticket:** owner [ETP-5900](https://etendoproject.atlassian.net/browse/ETP-5900). Related ETP-5049.
Was §1.9 (decided 2026-10-23, Martin Taal: retire it).

Since the ETP-5049 frontend slice the SPA no longer calls the endpoint — the upgrade page quotes
the plan catalog (§5.14) — so it has no caller left (§1.5, §2.1, §4.8). **Scope:** remove the
route and `handleBillingOffers` in `EtendoGoJwtServlet`, `BillingOfferConfiguration`, and
`PlanCatalogService#findDefaultPurchasablePlan` with its `DefaultPlan` if nothing else uses them
afterwards, plus their tests and docs (`feature-flags-and-tenant-upgrade.md` §2, *The billing
offer*); the path then answers 404.

### 🟠 5.17 One write transaction per tenant for the usage job — decided, owed to ETP-5905

**Ticket:** owner [ETP-5905](https://etendoproject.atlassian.net/browse/ETP-5905). Related ETP-5050
(the usage job) and §5.15. Was §1.4 (decided 2026-10-23, Martin Taal).

Sebastián asked for the scheduled usage job to run a separate transaction per client, so the whole
run need not be atomic (§5.15). Today the run is not atomic, but it is not per client either:
`UsageAggregationService.aggregateResourceDay` commits each resource-day on its own
(`commitAndClose`, `rollbackAndClose` on failure; `UsageRunLedger` writes the run log once at the
end so a failure survives its rollback), and each of those transactions counts **every tenant at
once**. `DeclarativeHqlCounter` runs one query per day grouped by client, on purpose — per tenant it
would be *tenants × resources × days* queries per run, "tens of thousands of round trips nightly"
with a few hundred tenants (its javadoc). A per-tenant query form exists (`UsageQueryComposer`,
used by the single-tenant backfill and the save-time validation probe), but counting with it would
pay that cost on the two-hour cadence of §5.15.

**Decision: count grouped, write per tenant.**

- **Counting stays as it is:** the grouped queries, one per resource × day, with the results
  collected in memory.
- **Writing becomes one transaction per tenant:** each tenant's rows for the whole computation
  period in one transaction, including `zeroOutTenantsThatNoLongerCount`, the settled/final-day
  rules and the tenant's settling window.
- **Consequences:** the same query count as today; a write failure loses only that tenant's period,
  which the next run retries; commits become one per tenant instead of one per resource-day. A
  failing counting query still fails for every tenant — inherent to a grouped query.

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

**Ticket:** related ETP-5047 (`feature-flags-and-tenant-upgrade.md` §1); the opt-in implementation and the production rollout have no
ticket.

**Status:** decided 2026-10-14 (Martin Taal, following Sebastián Barrozo's rollout proposal of
2026-09-28) — implementation owed.

**Decision: OFF by default, behind a positive flag.** Environment-access enforcement should refuse
a tenant only when an "enforce" flag resolves to `true` for it (per `clientId` or for everyone), and
otherwise only log what it would have refused. This reverses Martin's decision of 2026-09-25 (enforcement on by default, with a kill switch).
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

### ✅ 1.5 ETP-5049: which two cards, and where does each card's `price_id` come from?

**Ticket:** owner ETP-5049 (§5.14) — backend slice 1 (`com.etendoerp.go`), frontend slice 2
(schema_forge `feature/ETP-5049`).

**Status:** decided 2026-10-23 (Martin Taal)

**Decision: the Plan step is a view of the plan catalog.** The three open points resolve as:

- **Which cards:** whatever `ETGO_PLAN` lists — one card per plan of `GET /sws/go/plans`, with the
  catalog's name, description and price. No fixed number of cards.
- **Where the `price_id` lives:** in `ETGO_PLAN.PROVIDER_PRICE_ID`, resolved server-side from the
  `planKey` the card selects (`HostedCheckoutService`). The browser never receives a provider price
  id (§4.8).
- **The Payment step:** a read-only summary of the chosen plan (`upgrade-plan-summary`); the second
  chooser (`PlanSelector`) is gone.

This meets the 2026-09-28 decision — cards each configured with a Stripe `price_id`, the price read
from Stripe — through the catalog: each plan row carries its price id, and
`PlanPriceDerivationHandler` derives the displayed price from Stripe when the plan is saved. The catalog view **supersedes the
"two fixed cards" shape** of that decision; how many cards show is now an operator decision made in
the **Plans** window, not a code change.

**Follow-up:** no ticket. Marketing's third tier and custom plans (§5.14) are new `ETGO_PLAN` rows,
not code. `GET /sws/go/billing/offers` is kept server-side (catalog-derived since ETP-5049, §2.1)
but has no frontend caller any more; retiring it was decided on 2026-10-23 (§5.16, ETP-5900).

### ✅ 4.8 `priceId` still reaches the browser on two develop paths

**Ticket:** owner ETP-5049 (fixed); related develop ETP-5463 and the ETP-5046 review (W3).

**Status:** fixed 2026-10-23 in ETP-5049 (slice 1, backend).

**Decision: no browser-facing response carries a provider price id.** `HostedCheckoutService`
no longer puts `priceId` in the `POST /sws/go/checkout/sessions` and `POST /sws/go/billing/purchases`
answers (`createProviderSession`, and `buildResult` on a reopened open session), and
`GET /sws/go/billing/offers` returns the quoted `planKey` instead of the price id. The charged price
id stays server-side: it is still stored on the checkout request (`STRIPE_PRICE_ID`), and a reopened
checkout still charges it. Before the removal the consumers were checked: no code in
`schema_forge` (`tools/app-shell/src`, `e2e`) or `schema_forge_core/packages` reads `priceId` from
these responses — the only matches are tests asserting that `priceId` is *absent* from request
bodies — and the SPA's one offers consumer (`getBillingOffer` in `lib/upgrade/api.js`) was already
removed by the ETP-5049 frontend slice (schema_forge `feature/ETP-5049`).

**Follow-up:** none. `schema_forge/docs/stripe-local-testing.md` showed `"priceId"` in its example
checkout answer and called it "a known leftover"; the ETP-5049 frontend slice removed both.

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

**Follow-up:** none. The two related tickets are done too: ETP-5742 lets that tenant re-subscribe
its own environment instead, and ETP-5743 (`d4eb9e739`) restricts the source to the account owner
(§4.13, items 12 and 11).

### ✅ 4.17 The grace banner promises a block the kill switch will not carry out (ETP-5049)

**Ticket:** owner ETP-5051 (the backend field and the banner wording). Related ETP-5049 (the
banner) and ETP-5047 (the kill switch, `feature-flags-and-tenant-upgrade.md` §1).

**Status:** decided 2026-10-24 (Martin Taal) — the limitation accepted on 2026-10-23 is lifted;
backend delivered in ETP-5051.

**Decision:** every environment of `GET /sws/go/environments` carries `accessEnforced`: the
environment-access kill switch evaluated for that tenant, independent of today's decision —
`false` while `environment-access-enforcement-off` is on for it, `true` otherwise. The grace
banner (`BillingStatusBanner`) uses neutral wording when it is `false` ("payment overdue", with no
"will be blocked on <date>"). `GET /sws/go/billing/subscription` carries the same field for its
tenant (contract §4c), so the /account grace countdown drops "before access is paused" too.
`accessState` is unchanged: during grace it still reads `ALLOWED` either way.

**Follow-up:** none. The banner and countdown wording shipped with the ETP-5051 frontend in
schema_forge (`BillingStatusBanner`, `SubscriptionSection`; `app-shell-functional-flows.md`
§3.2a and §3.2a-quater).

### ✅ 5.1 ETP-5051: "no quota row" means UNLIMITED

**Ticket:** owner ETP-5051 (delivered).

**Status:** implemented 2026-10-24 (ETP-5051).

**Decision, unchanged:** no active quota row means unlimited, never zero. `INCLUDED_QTY` keeps no
default (`PlanQuotaSchemaInvariantTest`), and `ETGO_PLAN_QUOTA` keeps `ISDELETEABLE='Y'`. The
evaluator (`com.etendoerp.go.usage.quota.QuotaEvaluator`) reads the quota rows exactly as
`PlanCatalogService.listActiveQuotas` returns them — the reader of `GET /sws/go/billing/subscription`
— so an inactive row is no row on every surface. No row means no evaluation and **no consumption
query**; there is no `COALESCE`: a null included quantity is not evaluated (`QuotaRules.verdict`)
rather than read as zero. On the write path an entity that no active `block` quota of the
request's client counts costs no query either (`QuotaClientCache`, one lookup per client a minute). The environment list
(`quotas` per environment, contract §4b) reads the enforced quota rows of all its plans in one
query (`EnvironmentQuotaReport`), none when no listed tenant has a plan, and measures consumption
only for a tenant whose plan has an enforced quota.

**Follow-up:** none. The acceptance test exists: `QuotaEvaluatorTest`, nested *no quota rows
means zero consumption queries* (no subscription row, all rows inactive, mode `off`), with
`EnvironmentQuotaReportTest` for the environment list (no plan or no enforced row: no evaluation).

### ✅ 5.5 Consumption window = the subscription billing period — decided, filled from ETP-5047 on

**Ticket:** owner ETP-5051 (reads the period); ETP-5047 populates it (delivered).

**Status:** decided by the PRD (§4 decisions table); moved here on 2026-10-23, it was never open.

**Decision: the consumption window is the subscription billing period, never the calendar
month.** There is deliberately no period column on `ETGO_PLAN_QUOTA`; ETP-5051 reads the window
from `ETGO_SUBSCRIPTION.CURRENT_PERIOD_START` / `CURRENT_PERIOD_END`. An upgrade does not reset it —
the Stripe anchor is preserved (PRD §8, ETP-5053).

**What ETP-5051 must know:** since ETP-5047 `CURRENT_PERIOD_START/END` hold **Stripe's billing
period and nothing else** — the grace anchor moved to `GRACE_ANCHOR` (§3.7). `openSubscription`
writes neither column; a paid onboarding reads the period from Stripe once it is open (§3.7, the
`CURRENT_PERIOD_START/END` writer list), and every later `customer.subscription.updated` or
`invoice.paid` keeps it current. A new row is therefore periodless only when that read failed (one
WARN, `Could not read the billing period of Stripe subscription …`) or it names no Stripe
subscription — then until its first period-bearing event. Rows written before ETP-5047 may still
carry the old grace anchor in `CURRENT_PERIOD_END` with `CURRENT_PERIOD_START` NULL (read that
shape as "no period"). §5.9 covers the periodless cases.

**Follow-up:** none. ETP-5051 reads the period from those two columns (2026-10-24): the counted
window is the days `[startOfDay(CURRENT_PERIOD_START), startOfDay(CURRENT_PERIOD_END))`, the same
for the stored aggregate and the live count (`QuotaPeriod`), so consecutive periods share no day.
A periodless row has the answer of §5.9.

### ✅ 5.6 Stock versus flow aggregation over the period — owned by nobody

**Ticket:** owner ETP-5051 (delivered).

**Status:** decided 2026-10-24 (Martin Taal), on the place Sebastián Barrozo settled on 2026-09-28
(the billing resource, not the quota); implemented in ETP-5051.

**Decision:** `ETGO_BILLING_RESOURCE.AGGREGATION` — `sum` / `max` / `last`, required, default `sum`
(check `ETGO_BILLRES_AGGR_CHK`, list reference `ETGO_UsageAggregation`, field **Aggregation** on the
Billing Resource window; DAL property `aggregation`). How a period combines
(`usage.quota.UsageAggregation`):

- **Stored aggregate:** the tenant's `ETGO_USAGE_DAILY` rows of the period, combined with it.
- **Live, named strategy:** the counter's daily values over the period, combined the same way.
- **Live, declarative:** one `composePerTenantCount` over the whole period — a declarative
  resource counts dated rows, a flow, so the aggregation does not apply to it.

`last` is the most recent day that has a stored value; an omitted day is zero, so `max` and `last`
stay right over a period that began before measurement (§1.1).

**Follow-up:** set `max` or `last` on the stock resources when their catalog rows exist (none yet:
`activeUsers` and `productiveEnvironments` have no counter, §1.1). No ticket.

### ✅ 5.7 `ETGO_PLAN_QUOTA.CONSUMPTION_SOURCE` means something else in ETP-5051

**Ticket:** owner ETP-5051.

**Status:** decided 2026-09-28 (Sebastián Barrozo); values decided and implemented 2026-10-24
(Martin Taal, ETP-5051).

**Decision: ETP-5051's meaning wins.** `CONSUMPTION_SOURCE` represents the **data source** of a
quota's consumption — the stored aggregate versus a live count at evaluation time — and
aggregation is configured on the billing resource (§5.6), not on the quota.

ETP-5046 shipped the other meaning: String, list reference `ETGO_QuotaConsumptionSource`, a single
`AD_REF_LIST` value `sum`, check `ETGO_PLNQTA_SOURCE_CHK` (`CONSUMPTION_SOURCE IS NULL OR
CONSUMPTION_SOURCE = 'sum'`) — an aggregation.

**Done in ETP-5051:** the values are `aggregate` (the stored daily usage) and `live` (counted at
evaluation time from the same catalog descriptor); null means `aggregate`. Changed together: the
`AD_REF_LIST` values of `ETGO_QuotaConsumptionSource` (`sum` dropped: the window offers only
`aggregate` and `live`), the reference description, and the check `ETGO_PLNQTA_SOURCE_CHK`.

**The check still accepts `sum`, as a legacy alias read as `aggregate`** (`CONSUMPTION_SOURCE IS
NULL OR CONSUMPTION_SOURCE IN ('aggregate', 'live', 'sum')`; `ConsumptionSource.fromColumn`). The
earlier claim that no stored row used the column held for no shipped sourcedata or data-fix, but
the table is deployed and an operator may have picked `sum`, its only list value until ETP-5051. A
check that rejected it would fail `update.database` on such an environment, and a module script
cannot rewrite those rows first: `update.database` alters the model (checks included) **before**
it runs module scripts (verified in the DBSM log: *Updating database model* precedes *Running
modulescripts*). So the honest fix is the alias, not a migration.

**Follow-up:** none required. Dropping `sum` from the check needs a data-fix run before the deploy
that removes it; no ticket.

### ✅ 5.8 A quota on a yearly plan is a yearly quota

**Ticket:** owner ETP-5051.

**Status:** done 2026-10-24 (ETP-5051): the window help, the operator documentation
(`plan-quotas.md` §4.2) and the SPA.

The window is the billing period (§5.5), so `INCLUDED_QTY` on a `year` plan is consumed over the
whole year. The **Plans** window's **Quotas** tab help and the **Included Qty** help now say so, and
every `QuotaItem` carries `period` (the plan interval) with `periodStart` / `periodEnd`: the
counted window as server-local days (`yyyy-MM-dd`, end exclusive), not the period's UTC instants.

**Follow-up:** none. `/account` shows the period word next to the included quantity ("… per
year") and the counted window above the list (`app-shell-functional-flows.md` §3.2a-quater).

### ✅ 5.9 A subscription with no current period needs a defined answer

**Ticket:** owner ETP-5051 (the evaluator's answer, delivered); no ticket for the lookup script.

**Status:** partly decided 2026-09-28 (Sebastián Barrozo: fix periodless rows by hand); the
evaluator's answer decided 2026-10-24 (Martin Taal) and implemented in ETP-5051.

**Decision:** a subscription with no current period is **ALLOW, with no consumption query**, and
one WARN per tenant per hour (`[QUOTA] Tenant … has … active quota row(s) but its subscription has
no current billing period`). There is no current period when either bound is missing (a failed
provider read, an R37 backfill, the pre-ETP-5047 shape with only an end), the bounds are inverted,
the row is closed (`END_DATE`; not warned about), or now is outside `[start, end)` — a renewal not
yet recorded. On the account page such a row's `consumed`, `verdict` and period bounds are null.
The manual fix of 2026-09-28 stands: read the period from Stripe and update the row by hand; a row
with a real Stripe subscription fills its own period at its next period-bearing event (§5.5).

**Follow-up:** the read-only helper script that crosses the Stripe API with the rows — no ticket;
a row with no Stripe subscription has no period to read (§1.2).

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

**Follow-up:** no ticket — the script needs an owner. It depends on §5.6 (how days combine;
decided 2026-10-24: `ETGO_BILLING_RESOURCE.AGGREGATION`)
and on the period it reports (§5.5, §5.10). PRD §14
(`schema_forge/docs/plans/2026-08-27-recurring-billing-and-resource-limits-prd.md`) still assigns
the report to ETP-5050 and should be updated to the script.

### ✅ 5.14 ETP-5049: the upgrade page's Plan step does not show the plan catalog

**Ticket:** owner ETP-5049 (fixed: frontend in schema_forge `feature/ETP-5049`, backend slice 1 in
`com.etendoerp.go`). Introduced by the ETP-5046 develop merge, which combined two independent
designs of the same page.

**Status:** decided 2026-10-23 (Martin Taal) — the open points are §1.5, decided the same day.

**Decision: the Plan step (step 1 of `UpgradePage.jsx`) shows the catalog.** One selectable card per
`GET /sws/go/plans` plan, with name, description and price from the catalog; the static
`upgrade-plan-productive` card (`upgradePlanProductiveName` / `upgradePlanProductiveTagline`) and
the `SkeletonPlanCard` placeholders (`upgrade-plan-coming-soon-1/-2`) are removed. The Payment step
shows the chosen plan read-only (`upgrade-plan-summary`) with no second chooser, and checkout sends
that plan's `planKey`. The consequences listed before are gone: a plan's name is visible on the step
where it is chosen, several plans each get their own card, and the Plan step after the cutover shows
the catalog plan rather than the same static card. The SPA no longer calls
`GET /sws/go/billing/offers`; the catalog is its only quote.

The 2026-09-28 decision (Sebastián Barrozo) kept two fixed cards, each configured with a Stripe
`price_id` and its price read from Stripe. The catalog view meets that through `ETGO_PLAN`
(`PROVIDER_PRICE_ID`, price derived from Stripe) and supersedes its "two fixed cards" shape (§1.5).

**Follow-up:** no ticket. Custom plans through a commercial agreement and marketing's third tier
become catalog rows when they are defined. The now caller-less `GET /sws/go/billing/offers` is to
be retired (decided 2026-10-23, §5.16, ETP-5900).

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
  atomic (§5.17).
- **A warning is logged when calculations fall behind the configured cadence**, to spot volume
  problems early.

**Follow-up:** the scheduling, the cadence setting and the lag warning are done (ETP-5051,
2026-10-24); one transaction per tenant is ETP-5905 (§5.17).

- **Scheduled behind a flag.** `usage-aggregation-schedule` (default off) gates every scheduled
  run; `UsageAggregationScheduleStartup` plants the System process request on restarting
  environments, production schedules it by hand (§2.11). The ETP-5048 shape: never re-inserted
  after an unschedule, a flag-off scheduled run ends as ERROR.
- **The cadence setting:** `etendo.go.usage.schedule.interval.minutes` /
  `ETGO_USAGE_SCHEDULE_INTERVAL_MINUTES`, default 120 (`UsageScheduleSettings`). Each run still
  recomputes the whole settling window plus a sealing day.
- **The lag warning:** `[USAGE-LAG]`, at most hourly, when the last successful run in
  `ETGO_USAGE_RUN_LOG` is older than twice the cadence (`UsageFreshness`), checked when a scheduled
  run starts and when a quota reads the stored aggregate. A run that counted nothing for any tenant
  writes no run-log row, so an installation with no usage at all reads as "never ran".
- **The check reads stored usage by default** (`aggregate`); a quota row may choose `live` instead
  (§5.7), which counts at evaluation time and does not depend on the job.

---

## Related documents

- `plans/2026-09-18-etp-5046-plan-and-subscription-design.md` — the ETP-5046 design of record
- `plans/2026-09-18-etp-5046-stage-b-ad-handoff.md` — the AD authoring record
- `plans/2026-09-15-etp-5050-usage-measurement-design.md` — the usage engine design
- `plan-quotas.md` — the operator/developer entry point for plan quotas (ETP-5051): where they are
  enforced and where only reported, the extensibility ladder, capping a resource, freshness and the
  accepted limits (§4.19, §4.20)
- `schema_forge/docs/usage-measurement.md` — §4 is the full flow-versus-stock analysis
- `schema_forge/docs/plans/2026-08-27-recurring-billing-and-resource-limits-prd.md` — the governing PRD
- `feature-flags-and-tenant-upgrade.md` — paywall, plan marker, transitional fallback, the
  environment-access kill switch and how to operate it (§1, "Operating environment-access
  enforcement")
- `schema_forge/docs/stripe-local-testing.md` — lifecycle webhooks, the 402 body and the kill switch
  exercised locally (matrix SF-STRIPE-LOCAL-10…26)
- `schema_forge/docs/plans/2026-09-22-etp-5443-subscription-lifecycle-design.md` — the ETP-5443
  lifecycle design; its storage, correlation and enforcement statements are extended by §3.7 and `feature-flags-and-tenant-upgrade.md` §1
- `adr/0001-backend-managed-session.md` — the ETP-5455 amendment: the one auth pipeline and the
  surface policies that decide which endpoints run the commercial access check
