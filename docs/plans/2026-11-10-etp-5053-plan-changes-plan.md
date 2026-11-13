# Plan Upgrade and Downgrade (ETP-5053) Implementation Plan

> Steps use checkbox (`- [ ]`) syntax for tracking. Tasks 1–8 and 11 are implemented on
> `feature/ETP-5053` and checked; Tasks 9–10 are what remains before the change is merged and live.
> What was tested by hand and fixed since: [Local verification and fixes](#local-verification-and-fixes-2026-11-09--2026-11-13).

**Goal:** Let a customer move between plans at any moment: upgrades apply now, downgrades apply at
the end of the current billing period, and the yearly quota (ETP-5051) renews on the billing
anniversary. A plan change never resets usage; only a billing-interval change or a new
subscription after a cancellation starts a new quota year.

**Architecture:** One configuration table says which transitions exist and when they apply
(`ETGO_PLAN_CHANGE`, the **Allowed Plan Changes** tab of **Plans**). `PlanChangeService` is the
only code that asks Stripe for a change: it updates the subscription item for an `immediate` change
and builds a Subscription Schedule for a `period_end` change. The subscription row follows the
provider, never the request. `customer.subscription.updated` carries the item's price; when that
price no longer matches the open row, `SubscriptionPlanRollover` resolves the plan by price and
`SubscriptionService.rollToPlan` closes the row and opens its successor, which inherits the
quota-year anchor (`QUOTA_YEAR_START`). The API, the `/account` page and the Classic **Change
Plan** button are three callers of the same service.

**Tech stack:** Java 11+ module code (Etendo DAL, Weld CDI), JUnit 4/5 + Mockito, Stripe REST
through `StripeApiClient`; React SPA (`tools/app-shell`) with Vitest.

**Ticket:** https://etendoproject.atlassian.net/browse/ETP-5053 (Task under epic ETP-3504; blocked
by ETP-5047, builds on ETP-5046 and ETP-5051). The ticket text is the requirement of record; this
plan maps it onto code.

**Design doc of record:** [`docs/plan-changes.md`](../plan-changes.md). Product requirements:
`schema_forge/docs/plans/2026-08-27-recurring-billing-and-resource-limits-prd.md` §8.

**Branches and PRs** (one PR per repo, both into `develop`):

| Repo | Branch | PR |
|---|---|---|
| `com.etendoerp.go` | `feature/ETP-5053` | [#1509](https://github.com/etendosoftware/com.etendoerp.go/pull/1509) |
| `etendo_schema_forge` | `feature/ETP-5053` | [#2077](https://github.com/etendosoftware/etendo_schema_forge/pull/2077) |

`schema_forge_core` and the etendo_core root are not touched.

---

## Global Constraints

- All versioned content in **English**: code, comments, Javadoc, commit messages, docs.
- Commit format `Feature ETP-5053: <imperative summary>`, first line ≤ 80 chars. Every commit on
  the branch carries the ETP-5053 ticket (Git Police refuses a carried-over ETP-5051 subject).
- Every commit keeps a valid `Hooks-Verified` trailer. After a rebase that resolved a conflict,
  re-seal with `git rebase --exec 'git commit --amend --no-edit' origin/develop`; the pre-push hook
  and CI's verify check refuse a stale trailer.
- Sourcedata XML stays sorted by ascending UUID (`xml-order-check.yml`). Rebase conflicts in
  `src-db/database/sourcedata/*.xml` are almost always two different records added at the same
  spot: keep both, in UUID order.
- Tests: a class extending `OBBaseTest`/`WeldBaseTest` is named `*IntegrationTest`; every other
  test runs in `./gradlew goTestUnit` (from the etendo_core root; add `-x compileTestGroovy` on a
  JDK 21 machine). Every new or modified test class declares `@covers`, is named by behaviour (never
  by ticket) and is listed in exactly one `src-test/cells/<cell>.txt` (`./gradlew goCellCheck`).
- Committed module-script classes are compiled with **JDK 17** (class file 61): `update.database`
  on CI runs Java 17 and refuses a JDK 21 class. Push with
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` so the pre-push Gradle run passes.
- SPA strings in `en_US.json`, `es_ES.json` and `es_AR.json` (parity test); amounts through
  `formatCurrency`, dates through `formatCalendarDate`.

## Decisions taken (2026-10-31, confirmed with the requester)

| Question the ticket left open | Decision |
|---|---|
| Proration on an upgrade | `always_invoice`: the difference is billed now, so the next invoice stays at the sticker price. Documented in `plan-changes.md` §3. |
| How the change reaches the row | Only through `customer.subscription.updated` (item price ≠ row price). `subscription_schedule.*` events are not consumed. |
| How a future `END_DATE` is refused | `SubscriptionService.requireNotFuture`: more than 5 minutes ahead of the server clock is refused (skew tolerance). |
| How the Allowed Plan Changes rows are seeded | A module script (`SeedAllowedPlanChangesScript`), because the data-fix runner excludes client 0 and the table is System. It runs only into an empty table. |
| Classic operator action | In scope: a **Change Plan** button on the Subscription window. |
| PR shape | One PR per repo, not one per slice; plain branches, no worktrees. |

---

## File Structure

**`com.etendoerp.go`**

| File | Responsibility |
|---|---|
| `src-db/database/model/tables/ETGO_SUBSCRIPTION.xml` | New nullable `QUOTA_YEAR_START`. |
| `src-db/database/model/tables/ETGO_PLAN_CHANGE.xml` | The Allowed Plan Changes table, with the `ETGO_PLNCHG_TIMING_CHK` constraint. |
| `src-db/database/sourcedata/AD_*.xml`, `OBUIAPP_*.xml` | Columns, fields, tab, `ETGO_PlanChangeTiming` list, messages, the `ETGO_SubscriptionPlanChange` process and its parameter. |
| `src/com/etendoerp/go/usage/quota/TenantQuotas.java` | Anchors the quota year on `QUOTA_YEAR_START`, else `START_DATE`. |
| `src/com/etendoerp/go/payment/PlanChangeTiming.java` | `immediate` / `period_end`. |
| `src/com/etendoerp/go/payment/PlanChangeCatalog.java` | Reads the active Allowed Plan Changes rows. |
| `src-util/modulescript/.../SeedAllowedPlanChangesScript.java` (+ committed class) | One-time seed of an empty table. |
| `src/com/etendoerp/go/payment/SubscriptionLifecycleApplier.java`, `SubscriptionEventOutcome.java` | Carry the item price on the outcome; the shared synthetic subscription event. |
| `src/com/etendoerp/go/payment/SubscriptionPlanRollover.java` | Price mismatch → plan by price → roll, or park when no plan carries the price. |
| `src/com/etendoerp/go/payment/PlanCatalogService.java` | `findByProviderPrice`. |
| `src/com/etendoerp/go/payment/SubscriptionService.java` | `rollToPlan`, `writePendingChange` / `clearPendingChange`, the `END_DATE` guard. |
| `src/com/etendoerp/go/payment/PlanChangeService.java`, `PlanChangeResult.java`, `PlanChangeRefusedException.java` | The provider calls, idempotency and coded refusals. |
| `src/com/etendoerp/go/rest/EtendoGoJwtServlet.java` | `GET …/plan-changes`, `POST …/plan-change`, `quotaYearStart` and `pendingChange` on `GET /billing/subscription`. |
| `src/com/etendoerp/go/payment/classic/SubscriptionPlanChangeActionHandler.java` | The Classic **Change Plan** button. |
| `docs/plan-changes.md`, `docs/plan-quotas.md`, `docs/open-and-notable-topics.md`, `docs/INDEX.md` | Delivered documentation. |

**`etendo_schema_forge`** (`tools/app-shell`)

| File | Responsibility |
|---|---|
| `src/lib/upgrade/api.js` | Client for the two plan-change endpoints. |
| `src/components/account/PlanChangeDialog.jsx` | The targets, the direction, timing and quota consequence, the confirmation. |
| `src/components/account/SubscriptionSection.jsx` | The **Change plan** action and the persistent pending-downgrade line. |
| `src/locales/{en_US,es_ES,es_AR}.json` | Strings. |
| `docs/generated-custom-windows/app-shell-functional-flows.md` §3.2a-sexies | The flow. |

---

## Task 1: The quota-year anchor and the `END_DATE` guard

**Ticket sections:** *Quota year*, *Row lifecycle* (`END_DATE`), *Docs to update* (Quotas tab help).
**Commit:** `Feature ETP-5053: Anchor the quota year on QUOTA_YEAR_START and guard END_DATE`

- [x] Add nullable `ETGO_SUBSCRIPTION.QUOTA_YEAR_START` (column, element, read-only field on the
  Subscription window).
- [x] `TenantQuotas` anchors `QuotaPeriod.yearContaining` on `QUOTA_YEAR_START`, falls back to
  `START_DATE` when null (existing rows need no data fix), and clamps an anchor later than the
  row's start to the start.
- [x] Every close goes through `closingDateOf`, which refuses a closing instant more than five
  minutes ahead of the clock.
- [x] Show `PENDING_PLAN_ID` and `PENDING_EFFECTIVE_DATE` read-only on the Subscription window.
- [x] Fix the Quotas tab, Included Qty element and field help that still said "per billing period"
  (register §5.8).
- [x] Tests: `QuotaDefinitionTest` (anchor, fallback, clamp), `SubscriptionServiceTest` (future
  close refused, skew tolerance).

## Task 2: Allowed Plan Changes — configuration and seed

**Ticket section:** *Allowed plan changes — configuration, not code*.
**Commit:** `Feature ETP-5053: Add the Allowed plan changes configuration and its seed`

- [x] New System table `ETGO_PLAN_CHANGE` (Target Plan, Timing, Active) as the **Allowed Plan
  Changes** child tab of **Plans**; the `ETGO_PlanChangeTiming` list and the check constraint agree.
- [x] `PlanChangeCatalog`: active rows whose target plan is active, read in admin mode with client
  filters off (as the quota rows are); a change with no active row is not allowed; the direction
  is what the row declares, never inferred.
- [x] `SeedAllowedPlanChangesScript`: seeds an **empty** table from the environment's own catalog
  (same interval higher tier → `immediate`, lower tier → `period_end`, monthly → annual of the same
  or higher tier → `immediate`, no annual → monthly). Tier = rank by display price within interval
  and currency. One row present and it does nothing.
- [x] Tests: `PlanChangeCatalogTest`, `SeedAllowedPlanChangesScriptTest` (including that the
  compiled class is committed).

## Task 3: Roll the row when the provider's price moves plan

**Ticket section:** *Row lifecycle* (detection, successor row, parking an unknown price).
**Commit:** `Feature ETP-5053: Roll the subscription row over when its item price moves plan`

- [x] `SubscriptionLifecycleApplier.itemPriceIdOf`: `customer.subscription.updated` carries the
  item price (expanded or bare) on the outcome.
- [x] `PlanCatalogService.findByProviderPrice`.
- [x] `SubscriptionPlanRollover`: price ≠ open row's `PROVIDER_PRICE_ID` → resolve the plan by
  price → roll; a price no active plan carries is logged at ERROR (`[BILLING-ALERT]`) and the event
  applied to the existing row; the same plan under a new price only updates the snapshot.
- [x] `SubscriptionService.rollToPlan`: close the predecessor at now with the targeted statement
  (the unique open-row index would otherwise reject the successor), insert the successor in the
  same transaction with a fresh price snapshot, cleared pending columns and the inherited anchor;
  on a billing-interval change, anchor on the new provider `current_period_start`.
- [x] `writePendingChange` / `clearPendingChange`.
- [x] The billing reconciliation (ETP-5048) goes through the same applier, so it rolls a row the
  webhook missed.
- [x] Tests: `SubscriptionPlanRolloverTest`, `SubscriptionServiceTest`,
  `SubscriptionLifecycleApplierTest`, `PlanCatalogServiceTest`; tests that closed rows at a future
  instant now use past instants.

## Task 4: The plan-change service and API

**Ticket sections:** *Stripe mechanics*, *Failure and edge paths*, *Surfaces* (API).
**Commit:** `Feature ETP-5053: Upgrade now or schedule a downgrade via the plan change API`

- [x] `immediate`: `POST /v1/subscriptions/{sub}` moving `items[0]` to the target price with
  `proration_behavior=always_invoice`, **never** `billing_cycle_anchor`, idempotency key
  `plan-change:<row>:<plan>`; the answer is applied at once through the lifecycle path, so the row
  rolls before the webhook arrives.
- [x] `period_end`: Subscription Schedule `from_subscription`, phases rewritten (current phase,
  then the target price from `current_period_end`, `proration_behavior=none`,
  `end_behavior=release`); pending columns written. Same target again → `alreadyPending`, no
  provider call; another target → the same schedule updated.
- [x] Upgrade over a pending downgrade: release the schedule and clear the pending columns first.
- [x] Coded refusals: `NO_SUBSCRIPTION`, `PLAN_NOT_FOUND`, `PLAN_CHANGE_NOT_ALLOWED` (409),
  `SUBSCRIPTION_NOT_CURRENT` (409, before any provider call), `CHECKOUT_NOT_CONFIGURED` (503),
  `BILLING_PROVIDER_ERROR` (502). A failed prorated charge is Stripe's `past_due`, no rollback
  (superseded on 2026-11-13: see Task 11).
- [x] `EtendoGoJwtServlet`: `GET /sws/go/billing/subscription/plan-changes[?clientId=]` (offered
  changes with today's usage against each target's quotas), `POST
  /sws/go/billing/subscription/plan-change`, and `quotaYearStart` + `pendingChange` on
  `GET /sws/go/billing/subscription`.
- [x] Tests: `PlanChangeServiceTest`.

## Task 5: Classic Change Plan action and the delivered documentation

**Ticket sections:** *Surfaces* (Classic), *Docs to update in the same change*.
**Commit:** `Feature ETP-5053: Add the Classic Change Plan action and document plan changes`

- [x] **Change Plan** button on the Subscription window (`ETGO_SubscriptionPlanChange`, Target Plan
  parameter); `SubscriptionPlanChangeActionHandler` runs the customer's change through
  `PlanChangeService` and words every outcome with an `AD_MESSAGE`.
- [x] `docs/plan-changes.md` written; `plan-quotas.md` §1 says the year follows the anchor, not
  the row; the register closes §5.13 (option 1) and notes §5.5 and §5.8.
- [x] Tests: `SubscriptionPlanChangeActionHandlerTest`.

## Task 6: The account page

**Ticket section:** *Surfaces* (customer-facing `/account`).
**Commits (schema_forge):** `Feature ETP-5053: Change plan from the account page`, plus two Sonar
clean-ups (`Flatten the nested ternaries…`, `Extract the exceeded-quota filter…`).

- [x] `upgrade/api.js` client for both endpoints.
- [x] `PlanChangeDialog`: only the offered targets; direction, timing and quota consequence (every
  exceeded quota with its numbers) before confirming; refusals worded by backend code.
- [x] `SubscriptionSection`: **Change plan** on a renewing subscription with a catalog plan; a
  persistent "Your plan changes to X on <date>" while a downgrade is pending.
- [x] Strings in the three locales with a parity test; flow documented in
  `app-shell-functional-flows.md` §3.2a-sexies.
- [x] Tests: `PlanChangeDialog.vitest.jsx`, `SubscriptionSection.vitest.jsx`,
  `upgrade-api.test.js`, the locale parity test.

## Task 7: Sonar and CI fixes, rebase onto develop

- [x] Sonar findings on the go side (constants for repeated JSON fields, Javadoc on public
  constructors, method-count limits), no behaviour change.
- [x] Seed module script recompiled with JDK 17.
- [x] The carried-over ETP-5051 doc commit re-ticketed to ETP-5053.
- [x] 2026-11-10: both branches rebased onto `origin/develop`. go conflicts resolved in five
  sourcedata files (records kept in UUID order) and in `EtendoGoJwtServlet` (both imports, develop's
  new plan fields with `FIELD_BILLING_INTERVAL`); develop's rename `findPortalSubscription` →
  `findRequestedSubscription` folded into the Task 4 commit. All commits re-sealed;
  `./gradlew update.database compile.complete smartbuild` succeeds.

---

## Task 8: Make the PRs pass the checks that landed on develop since

Both checks were added or tightened on develop after the branch was written. Fixed 2026-11-10.

- [x] **Test cells (go).** `scripts/check-test-cells.py` reported five classes in no cell. Added
  to `src-test/cells/plataforma.txt` (where `SubscriptionServiceTest` already is), sorted, outside
  the `integración (BD)` section since none is DB-backed:
  - `com.etendoerp.go.modulescript.SeedAllowedPlanChangesScriptTest`
  - `com.etendoerp.go.payment.PlanChangeCatalogTest`
  - `com.etendoerp.go.payment.PlanChangeServiceTest`
  - `com.etendoerp.go.payment.SubscriptionPlanRolloverTest`
  - `com.etendoerp.go.payment.classic.SubscriptionPlanChangeActionHandlerTest`

  Verified: `python3 scripts/check-test-cells.py` reports every test class in exactly one cell.
  Commit: `Feature ETP-5053: Put the plan change tests in the plataforma cell`.
- [x] **Test named by ticket (schema_forge).**
  `tools/app-shell/src/locales/__tests__/etp5053-plan-change-keys.vitest.js` breaks rule 4 of
  `docs/testing/test-reuse-policy.md`. No existing account or subscription locale test to extend,
  so it is renamed `plan-change-locale-parity.vitest.js` (ticket removed from its title too; the
  functional-flows doc follows). Verified: the test passes and `scripts/check-test-hygiene.js
  --mode block` reports 0 findings over the branch. Commit: `Feature ETP-5053: Name the plan change
  locale test by behaviour`.
- [x] Each fix is its own `Feature ETP-5053: …` commit, sealed by the hooks.

## Task 9: Push, CI, review, merge

- [ ] Force-push both branches (the rebase rewrote them), **one after the other**: the schema_forge
  pre-push Playwright suite fails spuriously when the Java suite runs at the same time.
  ```
  cd modules/com.etendoerp.go && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 git push --force-with-lease origin feature/ETP-5053
  cd schema_forge && git push --force-with-lease origin feature/ETP-5053
  ```
- [ ] Check every PR gate: Git Police, mini pipeline (`goTestUnit` + DB-backed half on a fresh
  database), XML order check, unit-test classification, test hygiene, test cells, Sonar quality
  gate, Auto Reviewer.
- [ ] Answer review feedback with new commits (no further rewrites unless asked).
- [ ] Merge go #1509 and schema_forge #2077 together: the SPA calls endpoints that only the go PR
  provides.

## Task 10: After the merge — operator steps and verification

- [ ] On each environment, the first `update.database` creates `ETGO_PLAN_CHANGE` and runs the
  seed. An operator reviews **Plans → Allowed Plan Changes** for every plan: the seed ranks plans
  by price, so a mixed change (e.g. Scale monthly → Core annual) has to be confirmed or edited by
  hand.
- [ ] Every active plan that should be reachable has a provider price; a target without one is not
  offered (and the API answers `503 CHECKOUT_NOT_CONFIGURED`).
- [ ] Watch for `[BILLING-ALERT]` lines: a `customer.subscription.updated` with a price no active
  plan carries is parked, not applied. The fix is to attach the price to its plan; the
  reconciliation then rolls the row.
- [ ] Smoke test in Stripe test mode on a non-production environment: same-interval upgrade (billing
  date unchanged, usage kept), downgrade (pending line shown, plan unchanged until the boundary),
  upgrade over a pending downgrade (schedule released), monthly → annual (quota year restarts).
- [ ] Move ETP-5053 in Jira once both PRs are merged.

---

## Task 11: Quote the charge and apply an upgrade only once paid (option B, 2026-11-13)

Found while testing locally: the upgrade charged the saved card with no amount shown, and a charge
that failed or needed 3-D Secure would still have changed the plan. Two Stripe API changes also
surfaced: `phases[].iterations` was removed (2025-09-30.clover, the downgrade answered
`400 parameter_unknown`) and flexible billing keeps the billing date on monthly → annual.

- [x] Downgrade phase uses `duration[interval]` / `duration[interval_count]` instead of `iterations`.
- [x] `POST /billing/subscription/plan-change/preview` (`PlanChangeService.previewChange`,
  `/v1/invoices/create_preview`): amount today, currency, saved card, proration date.
- [x] The upgrade is sent with that `proration_date` and `payment_behavior=pending_if_incomplete`;
  a pending update answers `paymentRequired` with the hosted invoice page; the idempotency key
  includes the proration date.
- [x] `invoice.payment_failed` of a `subscription_update` invoice is not a renewal failure.
- [x] Dialog: charge line, confirm waits for the quote, "Complete the payment" link; Classic button
  warns with `ETGO_PlanChangePaymentRequired`; copy for monthly → annual corrected in 3 locales.
- [x] Docs: `plan-changes.md` §1, §3, §4, §5; functional flows §3.2a-sexies.

## Local verification and fixes (2026-11-09 – 2026-11-13)

What was tested by hand against Stripe test mode on a local install, what broke, and what changed
because of it. Commit hashes are on `feature/ETP-5053` (`com.etendoerp.go` unless marked **SF** for
`etendo_schema_forge`); a fix folded into an earlier commit during a rebase names that commit.

### Setup used

- Local Tomcat (`CATALINA_HOME=~/myapps/apache/apache-tomcat-9.0.24`), SPA with `make dev`
  (http://localhost:3100), Stripe API version `2026-07-29.dahlia`, flexible billing mode.
- Webhooks through `stripe listen --forward-to localhost:8080/etendo/sws/go/checkout/webhook`; its
  secret must equal `etendo.go.checkout.webhook.secret` in `config/Openbravo.properties` **and** the
  deployed `WEB-INF` copy (see the findings below).
- Test tenants: SPRING2, SPRING3 (legacy plan), SPRING6, SPRING7, SPRING8 (monthly mini → yearly),
  Springsite. Plans: `PROD_MONTH`, `PROD_MONTH_MINI`, `PROD_YEARLY`, `legacy-productive`.
- Added 2026-11-13 for chains of changes, three tiers per interval (all prices on the sandbox
  product `prod_V1t5ck5A6rDPTh`; quotas copied from the matching tier):

  | Tier | Monthly | Yearly |
  |---|---|---|
  | Mini | `PROD_MONTH_MINI` €12.50 (3, block) | `PROD_YEAR_MINI` €125 (3, block) |
  | Standard | `PROD_MONTH` €55 (100, warn) | `PROD_YEARLY` €490 (no quota) |
  | Pro | `PROD_MONTH_PRO` €99 (100, warn) | `PROD_YEAR_PRO` €990 (100, warn) |

  Allowed changes follow the seed rule: up a tier `immediate`, down a tier `period_end`, monthly →
  yearly of the same or a higher tier `immediate`, yearly → monthly of the same tier `period_end`.
  Weekly and quarterly plans are not possible: `ETGO_PLAN_INTERVAL_CHK` allows only `month` and
  `year`, and a price with `interval_count` ≠ 1 (a quarter) is refused.
- Cards: `4242…` (pays at once) and the 3-D Secure test card (`…3184`, asks to authenticate).

### Scenarios tested

| # | Scenario | Result | Fix |
|---|---|---|---|
| 1 | Onboard a new tenant (sample data) | Failed first: the `CHANGE_PLAN` button column existed in the AD but not physically | Column added to `ETGO_SUBSCRIPTION.xml` (folded into `d51ad7407`) |
| 2 | Downgrade yearly → monthly (period end) | Stripe `400 parameter_unknown` on `phases[].iterations` (removed in `2025-09-30.clover`) | `duration[interval]`/`duration[interval_count]` (folded into `f7ddd9e10`) |
| 3 | Upgrade monthly mini → yearly | Charged the saved card silently, no amount shown; a failed or 3-D Secure charge would still change the plan | Option B: quote + `pending_if_incomplete` + invoice page (`eb1c1497d`, **SF** `1d4aef299`) — Task 11 |
| 4 | Monthly → yearly keeps the billing date | Confirmed: flexible billing does not reset the anchor; the quota year follows it | Copy corrected in 3 locales (**SF** `1d4aef299`), `plan-changes.md` |
| 5 | Confirmation after a change | The dialog vanished for ~0.5 s and came back on the option list | Silent refresh that does not unmount the section (**SF** `e89318f44`) |
| 6 | Change back to yearly while a downgrade is pending | Not possible: the offer listed the scheduled target, never the current plan | Offer starts from the pending plan; "keep current plan" releases the schedule (`1f2245a96`, **SF** `15a5c024c`) |
| 7 | Dialog labels | Raw label keys shown | Stale label slices in the dev server: restart `make dev` after locale edits (no code change) |
| 8 | Selected option hard to see | — | Highlighted selection (**SF** `f52aea27c`) |
| 9 | Confirmation title | — | "Plan changed", no subtitle (**SF** `7fc39dfc6`) |
| 10 | Downgrade again after cancelling one | Bare `400`: the schedule idempotency key replayed the released schedule for 24 h | Key carries the request second; Stripe's message kept with the code (`6f4a22add`) |
| 11 | SPRING8 shown past due, upgrade "couldn't calculate the amount" | Missed webhooks (secret mismatch) left the row `past_due`; the quote was refused | Events replayed; the dialog now says a past-due subscription cannot change plan and refuses to confirm (**SF** `7c7b8ad73`) |
| 12 | Cancel a subscription with a pending downgrade (portal) | The pending plan stayed on the row and the page | Dropped when a cancellation is scheduled (`f0654d6b2`); change action hidden while cancelling (**SF** `cbd52f51d`) |
| 13 | Legacy plan → monthly | Read as an interval change (legacy had no billing interval) | `EnsureLegacyPlanScript` sets/fills `month` (`b1707c19b`) |
| 14 | Legacy → monthly with the 3-D Secure card | Paid on Stripe's invoice page, which never redirects back; the app tab kept saying payment pending | Recheck when the tab is shown again, then "Plan changed"; return hint under the button (**SF** `9ee49777a`, `61ddc58b8`) |
| 15 | Classic **Change Plan** on SPRING3 | Target Plan combo empty: a Table reference on a process parameter fails in `ComboTableDatasourceService` | Selector + HQL_JS validation listing the allowed targets (`366cc66f2`) |
| 16 | Downgrade then upgrade back (question, 2026-11-12) | The upgrade releases the schedule and clears the pending columns | Covered by `anUpgradeOverAPendingDowngradeReleasesTheScheduleAndClearsThePendingColumns` |
| 17 | Classic **Change Plan**: downgrade from the back office | Works after #15: the Target Plan list shows the allowed targets and the downgrade fills **Pending Plan** / **Pending Effective Date** on the Subscription window | — |
| 18 | Classic **Change Plan**: upgrade with the 3-D Secure card from the back office (SPRING3, Monthly → Yearly) | The button warned that the payment needs the customer and gave the invoice page; once paid, Stripe applied the change, the webhook rolled the row (Yearly from 11:55:34, Monthly closed) and the account page showed Productive Yearly | — |

### Findings that needed no code change

- **Webhook secret mismatch fails silently.** A `stripe listen` secret that differs from the
  configured one makes every event fail its signature: nothing reaches `etgo_billing_event`, while
  plan changes still look fine because the API applies Stripe's answer directly. Check with
  `stripe listen --print-secret`, fix both property copies, restart Tomcat, replay missed events
  oldest first with `stripe events resend <evt>`. This was the cause of SPRING8's past-due state.
- **"Pending plan is empty for every tenant"** was correct: the Tomcat log shows each scheduled
  downgrade was later cancelled by "keep current plan" (SPRING6, SPRING7) or by a cancellation
  (SPRING8). Nothing clears it on `update.database`.
- **Legacy plan transitions are operator configuration.** Whether the seed should add
  `legacy-productive → <plan>` rows was considered and rejected: the operator maintains the
  Allowed Plan Changes tab anyway and adds them (timing `immediate`, to move tenants off the
  grandfathered plan at once). Legacy → a cheaper plan immediately credits the unused part.
- **The seed runs once per environment in practice**: on every `update.database`, but only into an
  empty `ETGO_PLAN_CHANGE`, i.e. the first update that creates the table.
- **New AD references need a Tomcat restart**: UI definitions are cached at startup; before the
  restart the Subscriptions window failed with a FreeMarker `get(width)` error.

### Tests added for these fixes

| Area | Tests |
|---|---|
| Quote and payment | `PlanChangeServiceTest.aPreviewQuotesTheChargeAndTheCardAndChangesNothing`, `aDowngradePreviewChargesNothingAndSaysWhenItApplies`, `aPreviewRefusesWhatTheChangeWouldRefuse`, `anUpgradeIsChargedAtTheQuotedProrationDateOnlyWhileItIsRecent`, `anUpgradeWhoseInvoiceIsUnpaidWaitsForThePaymentAndChangesNothingLocally`; `SubscriptionLifecycleApplierTest.aFailedPlanChangeInvoiceIsNotARenewalFailure`; `SubscriptionPlanChangeActionHandlerTest.anUpgradeWaitingForItsPaymentSaysSoWithTheInvoicePage` |
| Pending plan and keep | `PlanChangeServiceTest.whileADowngradeIsPendingTheOfferStartsFromThePlanItMovesTo`, `keepingTheCurrentPlanCancelsTheScheduledDowngradeAndChargesNothing`, `aRefusedReleaseKeepsTheScheduledDowngradeOnBothSides`; `SubscriptionPlanChangeActionHandlerTest.keepingTheCurrentPlanSaysTheScheduledChangeWasCancelled` |
| Cancellation | `SubscriptionServiceTest.aScheduledCancellationDropsThePendingPlanChange` |
| Schedule key / provider errors | `PlanChangeServiceTest.aSecondDowngradeToAnotherPlanUpdatesTheExistingScheduleInsteadOfCreatingOne`, `aProviderRefusalIsACodedRefusalNeverAnUnhandledError` (updated) |
| Legacy interval | `EnsureLegacyPlanScriptTest.anExistingLegacyPlanWithoutIntervalGetsTheMonthlyOneButAnOperatorValueStays` |
| SPA (`PlanChangeDialog.vitest.jsx`, `SubscriptionSection.vitest.jsx`, `api` tests) | quote and card before confirming; generic charge line when the amount is unknown; no preview for a downgrade; invoice link when the payment waits; recheck on returning to the tab then "Plan changed"; keep current plan while a downgrade is pending; selected option marked; refresh without unmounting the confirmation; past-due says it cannot change and offers nothing; refused quote blocks confirming; no pending line or change action while cancelling; locale parity for every new key |

Last runs: `PlanChangeServiceTest` 24, `SubscriptionPlanChangeActionHandlerTest` 9,
`SubscriptionServiceTest` 103, `SubscriptionLifecycleApplierTest` 43, `EnsureLegacyPlanScriptTest` 8,
all green; SPA account and locale tests 114 green.

### Edge cases tested by hand (2026-11-13)

Plain upgrade and downgrade are covered by the scenarios above; these are the edges. All passed,
with the code as it stands after the fixes above: no further code change came out of them.

| # | Edge case | Expected and seen |
|---|---|---|
| E1 | **3-D Secure upgrade from the account page** (`4000 0027 6000 3184` as default card via **Manage subscription**) | "Complete the payment" with the invoice link, plan unchanged until paid; after paying in the Stripe tab and returning to the Etendo tab the dialog turned into "Plan changed" by itself (the fix of scenario 14). Back-office variant: scenario 18 |
| E2 | **Upgrade with a declined card** (`4000 0000 0000 0341`: saves fine, every charge fails) | The plan did not change and the tenant was not marked past due |
| E3 | **Quote equals charge** | The amount the dialog quoted equalled the invoice Stripe created (`stripe invoices list --subscription <sub>`) |
| E4 | **Downgrade, then a different downgrade** | The pending target was replaced on the same schedule; no second schedule |
| E5 | **Downgrade, then an upgrade to a third plan** (e.g. SPRING6: Yearly with Monthly pending → Year Pro) | The schedule was released, the pending line cleared and the upgrade charged |
| E6 | **Cancel in the portal with a downgrade pending, then reactivate** | The pending line was gone after the cancellation and stayed gone after reactivating; **Change plan** came back |
| E7 | **Double-click Confirm** on an upgrade and on a downgrade | One charge, one schedule |
| E8 | **A downgrade reaching its date** (SPRING7, Yearly → Year Mini) | The row rolled over on `customer.subscription.updated` (Yearly closed, Year Mini open from 14:04), the quota-year start stayed 2026-10-23 and the pending line cleared. How: see below |
| E9 | **Downgrade into an exceeded quota** (SPRING7: 9 sales invoices in the quota year, Year Mini allows 3 with `block`) | The dialog showed the numbers before confirming; after the downgrade applied, creating a sales invoice was refused and the existing invoices stayed readable |
| E10 | **A missed webhook**: stop `stripe listen`, change, restart and replay | The database caught up (also seen by accident with SPRING8, scenario 11) |
| E11 | **Keep from the back office**: schedule a downgrade with **Change Plan**, then choose the current plan as Target Plan | **Pending Plan** cleared. With scenarios 17 and 18 the Classic button is covered for downgrade, upgrade and keep |

**Making a downgrade apply without waiting for the period end** (E8). Every test period ends in
2027. A daily test price is not possible (`day` is refused by `ETGO_PLAN_INTERVAL_CHK`) and Stripe
test clocks need a customer created on the clock, which Checkout does not do. Instead, once the
downgrade is scheduled, move the end of the schedule's current phase a few minutes ahead,
repeating the phase list exactly as `PlanChangeService` writes it:

```
stripe subscription_schedules update <sub_sched_…> \
  -d "phases[0][items][0][price]=<current price>" -d "phases[0][start_date]=<phase 0 start, unchanged>" \
  -d "phases[0][end_date]=<now + 180>" \
  -d "phases[1][items][0][price]=<target price>" \
  -d "phases[1][duration][interval]=<target interval>" -d "phases[1][duration][interval_count]=1" \
  -d proration_behavior=none -d end_behavior=release
```

(the schedule id is `schedule` on the subscription; the current values are on
`stripe subscription_schedules retrieve <id>`). When the phase ends Stripe switches the price and
sends `customer.subscription.updated`; the rollover reacts to the price change, not to
`PENDING_EFFECTIVE_DATE`, so this runs the real code path. It does not move the billing anchor, so
it is the rollover that is proven, not a real period boundary.

**Still open**

- [ ] **Legacy → yearly** (the `legacy-productive → PROD_YEARLY` row exists locally). Legacy →
  monthly is done (scenarios 13 and 14).
- [ ] `~/mytmp/ETP-5053-e2e-stripe-testing.md` (the QA guide, outside the repo) still describes
  monthly → annual as restarting the billing date and lacks the quote, payment required, keep and
  cancellation; update it before handing it to QA.

## Acceptance criteria → evidence

| Acceptance criterion (ticket) | Where it is proven |
|---|---|
| Same-interval upgrade applies now; billing period boundary unchanged | `PlanChangeServiceTest.anUpgradeUpdatesTheItemWithProrationAndNeverMovesTheBillingAnchor` |
| Mid-year upgrade keeps the anchor and accrued usage | `SubscriptionPlanRolloverTest.anUpgradeWithinTheIntervalRollsOverAndKeepsTheQuotaYearAnchor`, `SubscriptionServiceTest.theSuccessorInheritsTheProviderSubscriptionAndTheGivenQuotaYearAnchor`, `QuotaDefinitionTest.theQuotaYearIsAnchoredOnTheQuotaYearStartWhenTheRowCarriesOne` |
| Upgrade then downgrade does not reset usage | Same inheritance path for both directions (`SubscriptionPlanRolloverTest.aRowWithoutAnAnchorHandsItsStartDateDown`) |
| Monthly → annual applies now; billing and quota year start on the change date | `SubscriptionPlanRolloverTest.aBillingIntervalChangeStartsTheQuotaYearOnTheNewProviderPeriod`, `anIntervalChangeWithoutAReportedPeriodAnchorsOnNow` |
| No active row → refused; annual → monthly refused by the seed; a new row enables it without code | `PlanChangeServiceTest.aChangeWithNoActiveAllowedRowIsRefusedAndTheSamePlanTooBecauseItHasNone`, `PlanChangeCatalogTest.aChangeWithNoActiveRowIsNotAllowed`, `SeedAllowedPlanChangesScriptTest.theSeedFollowsTheTicketsRuleAndIsGuardedAgainstExistingRows` |
| A downgrade changes nothing until the boundary, then does via `customer.subscription.updated` | `PlanChangeServiceTest.aDowngradeSchedulesTheTargetPriceFromThePeriodEndWithoutProrationAndRecordsItPending` + the rollover tests; by hand: edge case E8 |
| Downgrade into an exceeded quota shows the numbers; creates refused afterwards, data readable | `PlanChangeServiceTest.theOfferedChangesAreTheAllowedRowsWhoseTargetIsSellable` (usage vs target quotas), `PlanChangeDialog.vitest.jsx`; the refusal itself is ETP-5051's `block` mode on the NEO write path; by hand: edge case E9 |
| Pending downgrade visible in Classic and to the customer; cancelled by an upgrade | Read-only pending fields (Task 1), `SubscriptionSection.vitest.jsx`, `PlanChangeServiceTest.anUpgradeOverAPendingDowngradeReleasesTheScheduleAndClearsThePendingColumns` |
| Failed prorated charge leaves the upgraded plan in `past_due`, no rollback | **Superseded on 2026-11-13 (option B):** the upgrade is sent with `payment_behavior=pending_if_incomplete`, so a failed charge changes nothing and the customer gets the invoice page (`PlanChangeServiceTest.anUpgradeWhoseInvoiceIsUnpaidWaitsForThePaymentAndChangesNothingLocally`); that invoice's `invoice.payment_failed` is not a renewal failure (`SubscriptionLifecycleApplierTest.aFailedPlanChangeInvoiceIsNotARenewalFailure`) |
| `END_DATE` never future (guard tested) | `SubscriptionServiceTest.aFutureClosingInstantIsRefusedAndNothingIsWritten`, `aClosingInstantWithinTheClockSkewToleranceStillCloses` |
| Repeated requests are idempotent | `PlanChangeServiceTest.theSameDowngradeTwiceIsIdempotentAndAsksTheProviderNothing`, `aSecondDowngradeToAnotherPlanUpdatesTheExistingScheduleInsteadOfCreatingOne`, the idempotency key on the upgrade, `SubscriptionPlanRolloverTest.theSamePriceIsNoChange` |
| Past due / canceled refused with a clear message | `PlanChangeServiceTest.aPastDueOrCanceledSubscriptionIsRefusedBeforeAnyProviderCall` |

## Out of scope (from the ticket)

Refunds and credit-balance handling; annual → monthly (a configuration row plus a product decision
on its quota year); configurable quota-year behaviour on plan change (follow-up ticket); overage
charging.
