# Plan Upgrade and Downgrade (ETP-5053) Implementation Plan

> Steps use checkbox (`- [ ]`) syntax for tracking. Tasks 1–8 are implemented on
> `feature/ETP-5053` and checked; Tasks 9–10 are what remains before the change is merged and live.

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

## Acceptance criteria → evidence

| Acceptance criterion (ticket) | Where it is proven |
|---|---|
| Same-interval upgrade applies now; billing period boundary unchanged | `PlanChangeServiceTest.anUpgradeUpdatesTheItemWithProrationAndNeverMovesTheBillingAnchor` |
| Mid-year upgrade keeps the anchor and accrued usage | `SubscriptionPlanRolloverTest.anUpgradeWithinTheIntervalRollsOverAndKeepsTheQuotaYearAnchor`, `SubscriptionServiceTest.theSuccessorInheritsTheProviderSubscriptionAndTheGivenQuotaYearAnchor`, `QuotaDefinitionTest.theQuotaYearIsAnchoredOnTheQuotaYearStartWhenTheRowCarriesOne` |
| Upgrade then downgrade does not reset usage | Same inheritance path for both directions (`SubscriptionPlanRolloverTest.aRowWithoutAnAnchorHandsItsStartDateDown`) |
| Monthly → annual applies now; billing and quota year start on the change date | `SubscriptionPlanRolloverTest.aBillingIntervalChangeStartsTheQuotaYearOnTheNewProviderPeriod`, `anIntervalChangeWithoutAReportedPeriodAnchorsOnNow` |
| No active row → refused; annual → monthly refused by the seed; a new row enables it without code | `PlanChangeServiceTest.aChangeWithNoActiveAllowedRowIsRefusedAndTheSamePlanTooBecauseItHasNone`, `PlanChangeCatalogTest.aChangeWithNoActiveRowIsNotAllowed`, `SeedAllowedPlanChangesScriptTest.theSeedFollowsTheTicketsRuleAndIsGuardedAgainstExistingRows` |
| A downgrade changes nothing until the boundary, then does via `customer.subscription.updated` | `PlanChangeServiceTest.aDowngradeSchedulesTheTargetPriceFromThePeriodEndWithoutProrationAndRecordsItPending` + the rollover tests |
| Downgrade into an exceeded quota shows the numbers; creates refused afterwards, data readable | `PlanChangeServiceTest.theOfferedChangesAreTheAllowedRowsWhoseTargetIsSellable` (usage vs target quotas), `PlanChangeDialog.vitest.jsx`; the refusal itself is ETP-5051's `block` mode on the NEO write path |
| Pending downgrade visible in Classic and to the customer; cancelled by an upgrade | Read-only pending fields (Task 1), `SubscriptionSection.vitest.jsx`, `PlanChangeServiceTest.anUpgradeOverAPendingDowngradeReleasesTheScheduleAndClearsThePendingColumns` |
| Failed prorated charge leaves the upgraded plan in `past_due`, no rollback | **Superseded on 2026-11-13 (option B):** the upgrade is sent with `payment_behavior=pending_if_incomplete`, so a failed charge changes nothing and the customer gets the invoice page (`PlanChangeServiceTest.anUpgradeWhoseInvoiceIsUnpaidWaitsForThePaymentAndChangesNothingLocally`); that invoice's `invoice.payment_failed` is not a renewal failure (`SubscriptionLifecycleApplierTest.aFailedPlanChangeInvoiceIsNotARenewalFailure`) |
| `END_DATE` never future (guard tested) | `SubscriptionServiceTest.aFutureClosingInstantIsRefusedAndNothingIsWritten`, `aClosingInstantWithinTheClockSkewToleranceStillCloses` |
| Repeated requests are idempotent | `PlanChangeServiceTest.theSameDowngradeTwiceIsIdempotentAndAsksTheProviderNothing`, `aSecondDowngradeToAnotherPlanUpdatesTheExistingScheduleInsteadOfCreatingOne`, the idempotency key on the upgrade, `SubscriptionPlanRolloverTest.theSamePriceIsNoChange` |
| Past due / canceled refused with a clear message | `PlanChangeServiceTest.aPastDueOrCanceledSubscriptionIsRefusedBeforeAnyProviderCall` |

## Out of scope (from the ticket)

Refunds and credit-balance handling; annual → monthly (a configuration row plus a product decision
on its quota year); configurable quota-year behaviour on plan change (follow-up ticket); overage
charging.
