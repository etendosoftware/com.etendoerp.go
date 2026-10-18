# ETP-5048 — Billing reconciliation: design of record

**Status:** approved by Martin Taal on 2026-10-18. This is the design the implementation follows.
**Stack:** ETP-5045 (durable checkout state, Classic windows) → ETP-5046 (plans and subscriptions)
→ ETP-5047 (the subscription model and environment access) → **ETP-5048**.
**Governing PRD:** `schema_forge/docs/plans/2026-08-27-recurring-billing-and-resource-limits-prd.md`
§9.3 and §10.

## 1. Goal

Money taken and service delivered must eventually agree, without a human reading Stripe by hand.
An operator can also force the check from Etendo Classic and read the result there.

Two gaps motivate it:

- The browser polls the checkout status for 60 seconds and then gives up
  (`schema_forge/tools/app-shell/src/pages/UpgradePage.jsx`, `waitForCheckoutPayment`: 60 tries one
  second apart). Provisioning only ever starts from that browser (`POST /sws/go/onboarding`). The
  webhook records the payment and never provisions (`EtendoGoJwtServlet.applyCheckoutPaid`), so a
  customer who closed the tab, or whose webhook was lost, has paid and has no environment.
- A subscription cancelled or paused at Stripe while the webhook was down leaves the tenant marked
  as paying, because only an event moves the local state.

Out of scope: refunds, disputes, and usage-aggregate reconciliation (ETP-5050).

## 2. Premises corrected (the Jira text and PRD §2.3 were written before ETP-5050/5389)

| Premise as written | What is true on 2026-10-18 |
|---|---|
| Mirror `OnboardingPsd2SyncService` (`AD_Process_Request` + `OBScheduler`). | That class no longer exists. ETP-4690 renamed it `OnboardingBankConnectionSyncService`, and ETP-5275 deleted it (commit `6a7e30e4b`). The live precedent for **one global request at client 0** is `startup/TenantPoolScheduleStartup` (ETP-5389): fixed process id, fixed request id, idempotent insert, then registration with the live scheduler. That is the shape copied here. |
| `AD_PROCESS.xml` has 2 entries, both `ISBACKGROUND=N`, and zero `AD_MENU` rows point at a process. | 4 processes; 2 are background (`ETGO_UsageAggregation` from ETP-5050, `ETGO_TenantPoolFill` from ETP-5389). The "Billing Resource Usage Aggregation" menu entry is `ACTION=P` on a background process. ETP-5050 is the precedent copied for the Classic launch. |
| Frontend polling at `UpgradePage.jsx` ~289-293. | `waitForCheckoutPayment` is at ~188-195 (the call site is ~250). |

## 3. Boundary with ETP-5396 (decision 1)

ETP-5396 (`schema_forge/docs/plans/etp-5396-demo-to-pro-technical-design.md`) proposes a separate
**purchase/provisioning work record**: an immutable input snapshot, target-tenant reservation,
checkpoints, a lease, and a retry budget with backoff. It also says it "must supersede conflicting …
ETP-5048 recovery assumptions". On 2026-10-18 most of ETP-5396's code is already in `develop` (the
lifecycle state, NEO enforcement, owner-only checkout, the stale-lease re-claim on the checkout
row), but no work-record table exists on any branch.

**ETP-5048 builds now:**
- the reconciler and its background process;
- Stripe list pagination;
- both directions, abandoned-checkout cleanup, and the sweep of stuck `RECEIVED` events;
- alerting, and Classic visibility of every repair.

Provisioning repair calls an extracted paid-provisioning service (§6.1), fenced by the existing
`CheckoutRequestStore.claimForProvisioning` lease (`PROVISIONING_AT` and the
`PROVISIONING_ATTEMPTS` fencing token on the checkout row). Its state lives on rows that already
exist: the checkout row and an `ETGO_BILLING_EVENT` audit row per repair. The only additions are the
three onboarding-input columns (decision 3) and the `EXPIRED` status (decision 5).

**ETP-5396 extends later:** its work record, target-tenant reservation, multi-step checkpoints and
retry budget/backoff, and the provider-neutral port. It can move the orchestration into its record
without changing how ETP-5048 detects drift or audits a repair: the reconciler's detection reads the
checkout and subscription rows, and its audit rows are ordinary billing events.

## 4. Decisions (approved 2026-10-18)

1. **Boundary with ETP-5396** — §3.
2. **Classic launch** — a background `AD_PROCESS` (`ETGO_BillingReconciliation`, ACCESSLEVEL 4 =
   System) on a menu entry, copied from ETP-5050's `ETGO_UsageAggregation`, with an optional
   **Dry run** parameter. The run result is an `OBError`. The `fail()` pattern sets an error result
   *and* throws, so a scheduled run is recorded as ERROR in Process Monitor. Each repair is written as
   an `ETGO_BILLING_EVENT` row with `PROVIDER='reconciler'`, a deterministic `EVENT_ID`, and the FK to
   the checkout request when there is one. There is no run-log table.
3. **Inputs** — `LANGUAGE`, `CURRENCY` and `COUNTRY` are added to `ETGO_CHECKOUT_REQUEST` and
   written at checkout creation from the purchase body. The SPA sends `language` (`UpgradePage.jsx`,
   `createBillingPurchase`); `currency`/`countryCode` are recorded when sent. A legacy row, or a
   null column, falls back to the account's `ONBOARDING_DRAFT`, then to `en_US` / `EUR` / `ES` (the
   same defaults `parseOnboardingRequest` applies).
4. **Extraction** — the paid provisioning path is reachable through `PaidProvisioningService`,
   which takes an `OnboardingProgressSink`. The servlet keeps its NDJSON contract unchanged. See §6.1
   for exactly what moved.
5. **Abandoned checkouts** — a new terminal status `EXPIRED` (CHECK constraint and ref-list value).
   It sits outside the forward-only rank: only `CREATED` moves to it, and nothing moves out. It is
   set only after Stripe answers that the session is `expired`. The window is configurable, with a
   minimum of 48 h, because sessions use Stripe's default 24 h expiry and no `expires_at` is sent.
6. **No session id** — a `CREATING` row (it never received one) is never auto-closed. It is alerted
   once, deduplicated by its deterministic billing-event id, and counted as "awaiting review" on
   later runs.
7. **Stripe status mapping** —
   - `active`/`trialing` → current. `canceled`, `paused` and `incomplete_expired` close the row
     locally.
   - `pause_collection` on an active subscription raises an alert only.
   - `past_due`/`unpaid` stay with the existing webhook grace logic: the reconciler does not touch
     them.
   - `incomplete` is left alone, since the payment is still in progress.
   - A 404 means **absent**, and an absent subscription is closed only behind a circuit breaker
     (§6.4).
   - The mapping for `paused` and `incomplete_expired` lives in `SubscriptionLifecycleApplier`, so
     the webhook (`customer.subscription.updated`) and the reconciler agree.
8. **No-op runs** — the reconciler compares the would-be outcome with the stored state before
   applying anything. Only a change of status, or a close, is written. A period or `LAST_EVENT_AT`
   refresh alone is never written, so a consistent dataset gets zero writes and zero alerts.
9. **Preference route** (`ETP-5046-TRANSITIONAL-FALLBACK`) — tenants with no subscription row are
   enumerated from checkout rows that are `PROVISIONED`, have `CREATED_CLIENT_ID` and
   `STRIPE_SUBSCRIPTION_ID`, and have no active `ETGO_SUBSCRIPTION` row for that client. They are
   resolved with `TenantEnvironmentLifecycleService.targetForTenant`, which already decides between
   the row and the preferences. The subscription id is the one the creating request recorded, never
   one found by Stripe customer: a customer can own several environments.
10. **Notifications** — internal alert only (`InternalAlertService`, the same `environment-provisioning`
    event the onboarding sends). No customer email.
11. **Scheduling** —
    - `startup/BillingReconciliationScheduleStartup` follows the `TenantPoolScheduleStartup` shape:
      fixed process id, fixed request id at client 0, idempotent.
    - The interval is configurable and defaults to hourly. Batches are bounded per run.
    - Scheduled runs are gated by the `GoFeatureFlags` flag `billing-reconciliation-schedule`, which
      **defaults OFF**. The request is planted on every instance, but a scheduled run with the flag
      off returns at once, as the tenant-pool filler does, so flipping the flag needs no restart.
    - A run started from the menu or an ad-hoc Process Request is never gated.
    - Every alert and repair line starts with `[BILLING-RECONCILE]`.
12. **Stuck `RECEIVED` events** — a Stripe event left `RECEIVED` past a threshold (the crash window
    `BillingEventStore` documents) is not replayed. The run re-reads the provider state of every local
    row the event could have moved, and then the event row is marked `IGNORED` with the reason
    "superseded by reconciliation".
13. **Tags and counts** — every preference-route branch carries `ETP-5046-TRANSITIONAL-FALLBACK` and
    has its own count in the run summary.
14. **Non-retryable failures** — a provisioning failure whose code is deterministic
    (`ProvisioningFailureReason.isRetryable` = false; ETP-5737, the `ad_client_name` unique violation,
    is one) goes to **needs review** once and is not retried on every run. ETP-5737 itself is not
    fixed here.

## 5. What a run does

The phases run in this order, each with its own transactions:

| # | Phase | Candidates | Provider read | Repair |
|---|---|---|---|---|
| A | Checkout recovery (provider → local) | `CREATED` rows with a session id, older than the paid grace | `GET /v1/checkout/sessions/{id}` | `complete` + `paid`/`no_payment_required` → `recordPaid`, then provision. `expired`, past the abandoned window → `EXPIRED`. `open` → nothing. |
| B | Provisioning repair | `PAID` rows older than the paid grace; `PROVISIONING` rows whose lease is stale or whose failure is retryable; all without a created client | none (the payment is already recorded) | `PaidProvisioningService` under the `claimForProvisioning` lease. A non-retryable failure → needs review, once. |
| C | No-session-id review | `CREATING` rows older than the abandoned window | none | alert once, then count as awaiting review |
| D | Subscriptions (local → provider) | open active rows with a Stripe subscription id; preference-route tenants (decision 9) | `GET /v1/subscriptions` paginated, then `GET /v1/subscriptions/{id}` for every local id the list did not return | the status mapping of decision 7, applied through `SubscriptionLifecycleApplier` + `TenantEnvironmentLifecycleService.applySubscriptionEvent`, after the no-op diff. Missing price/amount/currency on an open row (the R37 adoption step, register §2.4) is filled from the subscription's first item price, only where NULL. |
| E | Stale `RECEIVED` sweep | Stripe events `RECEIVED` older than the threshold | none (A–D already re-read the state) | mark `IGNORED`, "superseded by reconciliation". Skipped for subscription events when D tripped its breaker. |

**Foreign subscriptions.** A Stripe subscription that does not carry `metadata.request_id`
(`HostedCheckoutService` writes `subscription_data[metadata][request_id]`) is not ours. The
reconciler only acts on ids a local row names, so a foreign subscription is never touched; the list
sweep skips it.

**Dry run.** Every read happens and every decision is computed and counted, but nothing is written,
claimed or provisioned, and no billing-event row is created. The summary says "dry run".

## 6. Components

### 6.1 `rest/PaidProvisioningService` — the extraction (decision 4)

The provisioning steps (tenant selection, the pooled and classic paths, dataset, metadata, commit,
post-commit steps) stay where they are, shared by the free and the paid path. Moving them would
rewrite about 60 servlet spy-based tests for no change in behaviour. What moved is the
**transport-independent run**:

- the body that used to sit in `handleOnboarding` (heartbeat aside): the try/catch/finally around
  `executeOnboardingProvisioning`, the rollback, the outcome record and the alert. It is now
  `EtendoGoJwtServlet.runOnboarding(OnboardingPreparation, OnboardingStreamWriter)`.
- the paid preparation that needs no HTTP request: owning account, inputs from the checkout row
  (decision 3), demo-source restore, and the fenced claim. It now lives in `PaidProvisioningService`.

`PaidProvisioningService.provision(requestId, sink)` builds the preparation from the checkout row,
claims it, runs `runOnboarding` over an `OnboardingStreamWriter` whose NDJSON lines are fed into the
given `OnboardingProgressSink`, and returns a classified outcome: provisioned, failed retryable,
failed needs-review, claim refused, or inputs unavailable. `handleOnboarding` still prepares from
the HTTP request and streams NDJSON exactly as before.

### 6.2 `payment/StripeListPager`

Stripe list pagination on top of `StripeApiClient.get`: `limit`, `starting_after=<last id>`, and a
loop while `has_more`, with a page cap. A transport failure ends the sweep. The reconciler then
treats every id it did not see as unknown, never as absent.

### 6.3 `payment/reconcile/*`

- `BillingReconciliationService` runs the phases.
- `ReconciliationSettings` holds the properties of §7.
- `ReconciliationResult` holds the counts and the summary.
- `ReconciliationAudit` writes the billing-event rows and the `[BILLING-RECONCILE]` lines.

Billing-event ids are deterministic:

| Kind | `EVENT_ID` | `EVENT_RESULT` |
|---|---|---|
| payment recorded from the provider | `reconcile:payment-recovered:<requestId>` | `APPLIED` |
| environment provisioned | `reconcile:provisioned:<requestId>` | `APPLIED` |
| provisioning failed, retryable | `reconcile:provisioning-failed:<requestId>:<attempt>` | `FAILED` |
| needs review (non-retryable failure, missing inputs, no session id) | `reconcile:needs-review:<requestId>` | `FAILED` |
| checkout expired | `reconcile:checkout-expired:<requestId>` | `APPLIED` |
| subscription closed | `reconcile:subscription-closed:<stripeSubscriptionId>` | `APPLIED` |
| subscription back to current | `reconcile:subscription-current:<stripeSubscriptionId>:<runId>` | `APPLIED` |
| price/amount filled (R37 adoption) | `reconcile:subscription-price:<stripeSubscriptionId>` | `APPLIED` |
| `pause_collection` alert | `reconcile:pause-collection:<stripeSubscriptionId>` | `IGNORED` |

An existing id is the "already alerted" answer: the row is not written again, and the case counts as
awaiting review.

### 6.4 The circuit breaker (decision 7)

A 404 for a subscription a local row names is **absent**. Absent closes are collected, not
applied, until phase D has read everything. The breaker trips, and **no absent close is applied in
that run**, when either:

- the absent count exceeds `max(1, floor(absent.max.share × subscriptions checked))`; or
- the key mode does not match the data: the configured key is `sk_live_`/`rk_live_` and an absent
  row's creating session is `cs_test_…`, or the reverse.

A tripped breaker is alerted, the run is reported as an error, and every other repair of the run
stands. The other repairs are safe because each one acts on a positive provider answer; a
wrong-mode key gets 404s, never a `paid` or `expired` answer.

### 6.5 `payment/reconcile/BillingReconciliationProcess` and the schedule

`DalBaseProcess`, modelled on `UsageAggregationProcess`: the `OBError` result, `fail()` sets an error
result and throws, and `bundle.getLogger()` lines show in Process Monitor. A scheduled run (the fixed
request id) returns at once while the flag is off.

## 7. Configuration

| Property / env | Default | Meaning |
|---|---|---|
| `etendo.go.flags.billing-reconciliation-schedule` / `ETGO_FLAG_BILLING_RECONCILIATION_SCHEDULE` (or ConfigCat) | off | Scheduled runs do work only when on |
| `etendo.go.billing.reconcile.interval.minutes` / `ETGO_BILLING_RECONCILE_INTERVAL_MINUTES` | 60 | Interval of the planted request (read when it is first planted) |
| `etendo.go.billing.reconcile.batch.size` / `ETGO_BILLING_RECONCILE_BATCH_SIZE` | 50 | Rows per phase per run |
| `etendo.go.billing.reconcile.abandoned.hours` / `ETGO_BILLING_RECONCILE_ABANDONED_HOURS` | 48 (minimum 48) | Age before a checkout counts as abandoned |
| `etendo.go.billing.reconcile.paid.grace.minutes` / `ETGO_BILLING_RECONCILE_PAID_GRACE_MINUTES` | 15 | Age before a paid row is provisioned by the server (the browser normally does it) |
| `etendo.go.billing.reconcile.received.stale.minutes` / `ETGO_BILLING_RECONCILE_RECEIVED_STALE_MINUTES` | 60 | Age before a `RECEIVED` event counts as stuck |
| `etendo.go.billing.reconcile.absent.max.share` / `ETGO_BILLING_RECONCILE_ABSENT_MAX_SHARE` | 0.25 | Breaker threshold |

## 8. Operating it

- **Run by hand:** Classic, System Administrator, menu **Billing Reconciliation**. Tick *Dry run*
  for a first look. The popup shows the summary.
- **Read what changed:** the **Billing Event** window (filter `Provider = reconciler`) and the
  **Checkout Request → Billing Event** tab. The **Subscriptions** window shows corrected status and
  end dates. Scheduled runs are in **Process Monitor**.
- **Switch the schedule on:** set the flag (ConfigCat, or the property, then restart).
- **Alerts:** grep `[BILLING-RECONCILE]`. Provisioning repairs also send the internal
  `environment-provisioning` alert.

## 9. Tests

The developer writes none beyond repro tests. tester-go owns the coverage required by the
acceptance criteria: both directions, the never-close branch, pagination, the idempotent re-run
and the breaker.
