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
three onboarding-input columns (decision 3) and the `EXPIRED` status with its `EXPIRED_AT` column
(decision 5).

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
   set only after Stripe answers that the session is `expired`, and stamps `EXPIRED_AT`. The
   window is configurable, with a minimum of 48 h, because sessions use Stripe's default 24 h
   expiry and no `expires_at` is sent.
6. **No session id** — a `CREATING` row (it never received one) is never auto-closed. It is alerted
   once, deduplicated by its deterministic billing-event id, and counted as "awaiting review" on
   later runs.
7. **Stripe status mapping** (point 2 decided by Martin on 2026-10-20) —
   - `active`/`trialing` → current. `canceled` and `incomplete_expired` close the row locally
     (EXPIRED on the preference route).
   - `paused` is **alert-only**: it never closes the row and never changes any state, on any path.
     That covers the reconciler, a `customer.subscription.updated` carrying the status, and the
     dedicated `customer.subscription.paused` event. Our checkout sets no trial
     (`HostedCheckoutService.buildSessionForm` sends no `trial_*`), so `paused` means a trial or a
     pause was configured in Stripe by hand, and a paused subscription can be resumed; closing the
     row would make that resume impossible, because a closed row is never reopened.
     `PausedSubscriptionAlert` raises **one review alert per pause episode** (decided by Martin
     on 2026-10-21; it was once per subscription), shared by every path: an open
     `reconcile:subscription-paused:<sub>` billing-event row, a WARN (`[BILLING-RECONCILE]` from
     the job, `[BILLING-ALERT]` from the webhook), and an internal alert
     (`subscription-paused-review`). A re-run or a redelivery within the same pause finds the open
     row and stays silent, so a consistent dataset still costs zero alerts.
   - **The episode ends when the subscription is seen not paused**: by the webhook on every
     subscription lifecycle event it applies (`customer.subscription.updated`, `.deleted`) and on
     `customer.subscription.resumed` (now handled, changing no state), and by the reconciliation
     on every re-read (a real run; a dry run ends nothing). Ending renames the open row to
     `<id>:ended:<epoch millis>`, which keeps it as history and frees the id, so the next pause
     alerts again. Chosen over an id derived from the pause's start because Stripe gives a
     `paused` status no start instant and `pause_collection` none at all, and the reconciliation
     has no event time to use. A resume no path saw (paused, resumed and paused again between two
     reads with the webhooks lost) folds two pauses into one alert: one too few, never one too
     many. The other way round, an event delivered out of order (an older "active" after a newer
     pause) may end an episode early: at worst one duplicate alert on the same pause later, never
     a missed one. On the webhook the rename is best effort: a failure is a WARN and the event
     goes on, so it never fails the lifecycle event nor makes Stripe redeliver it in a loop; the
     episode stays open until the next resume is seen.
   - An ended row keeps its event type (`reconciliation.subscription-paused` or
     `reconciliation.pause-collection`): any future count by type must exclude the `:ended:` ids.
   - `pause_collection` on an `active` or `trialing` subscription raises an alert only (a WARN and
     the row; no internal alert), once per episode (`reconcile:pause-collection:<sub>`), ended when
     `pause_collection` is cleared. Only the reconciliation raises it; the webhook only ends it.
   - `past_due`/`unpaid` stay with the existing webhook grace logic: the reconciler does not touch
     them.
   - `incomplete` is left alone, since the payment is still in progress.
   - A 404 means **absent**, and an absent subscription is closed only behind a circuit breaker
     (§6.4).
   - The `incomplete_expired` close and the `paused` ignore both live in
     `SubscriptionLifecycleApplier`, so the webhook and the reconciler cannot disagree.
8. **No-op runs** — the reconciler compares the would-be outcome with the stored state before
   applying anything. Only a change of status, or a close, is written. A period or `LAST_EVENT_AT`
   refresh alone is never written, so a consistent dataset gets zero writes and zero alerts.
9. **Preference route** (`ETP-5046-TRANSITIONAL-FALLBACK`) — tenants with no subscription row are
   enumerated from checkout rows that are `PROVISIONED`, have `CREATED_CLIENT_ID` and
   `STRIPE_SUBSCRIPTION_ID`, and have no active `ETGO_SUBSCRIPTION` row for that client. They are
   resolved with `TenantEnvironmentLifecycleService.targetForTenant`, which already decides between
   the row and the preferences. The subscription id is the one the creating request recorded, never
   one found by Stripe customer: a customer can own several environments.
   **Only the latest purchase of a tenant is read** (review B1, 2026-10-21). The preference
   projection holds no subscription id, so `targetForTenant` answers the same projection for every
   purchase of the tenant. A tenant re-subscribed after a cancellation (ETP-5742) has two
   provisioned purchases, and reading the canceled one would expire a paying tenant. The candidates
   are read newest first (`coalesce(PAID_AT, CREATED)`), and a purchase a later one of the same
   tenant supersedes is skipped. A tenant whose preference projection is already `EXPIRED` is not
   a candidate.
10. **Notifications** (amended by Martin on 2026-10-20) — the internal alert is sent
    (`InternalAlertService`, the same `environment-provisioning` event the onboarding sends), and so
    is the existing customer `environment-ready` email. A repair runs the shared onboarding
    (`completeCommittedOnboarding` → `sendEnvironmentReady`), exactly as a browser-driven
    provisioning does. There is no suppress switch, and no new customer email is added.
11. **Scheduling** —
    - `startup/BillingReconciliationScheduleStartup` follows the `TenantPoolScheduleStartup` shape:
      fixed process id, fixed request id at client 0, idempotent.
    - The interval is configurable and defaults to hourly. Batches are bounded per run.
    - **Production schedules it by hand** (decided by Martin, 2026-10-21). Production is updated
      by delta and never restarted (`DemoTrialNoticeProcess` documents it), so the startup hook
      never runs there: an operator schedules the process in the Process Request window, like Demo
      Trial Notices. The hook stays for environments that do restart.
    - **Strictly off by default** (decided 2026-10-18). The startup plants the request only while
      the `GoFeatureFlags` flag `billing-reconciliation-schedule` is on, so turning it on takes
      effect at the next restart.
    - **The flag gates every scheduled run** (2026-10-21): any run the Process Scheduler fires for a
      request whose timing is *Schedule* (`S`), the startup's or one an administrator created.
      `ProcessBundle.request` puts every scheduler-run bundle on the `SCHEDULED` channel, an
      immediate one included, so the request's timing is what tells a schedule apart. While the
      flag is off such a run does no work and **ends as an ERROR** (`fail()`: error result and
      exception, plus a `[BILLING-RECONCILE]` WARN), so Process Monitor shows a schedule firing for
      nothing; the message says to turn the flag on or unschedule the request. The first such run
      of each UTC day also sends **one internal alert** (`billing-reconcile-schedule-flag-off`,
      approved by Martin 2026-10-21), the breaker's pattern: a
      `reconcile:schedule-flag-off:<yyyy-MM-dd>` row, and only the call that wrote it alerts, so
      the hourly runs the same day stay silent. The request's *Dry run* parameter does not
      suppress it: the alert is about the schedule, not about the reconciliation's writes.
    - A run started from the menu, or a Process Request run immediately or later once, is never
      gated.
    - The startup never inserts its fixed request id twice: a row with that id in any status
      (typically unscheduled by an operator) is left as it is, logged at INFO. It inserts only when
      no such row exists and no active scheduled request for the process exists.
    - Every alert and repair line starts with `[BILLING-RECONCILE]`.
    - Whether the flag is needed at all, given that production schedules by hand, is an open
      decision for Martin: `docs/open-and-notable-topics.md` §1.8 (decide later).
12. **Stuck `RECEIVED` events** — a Stripe event left `RECEIVED` past a threshold (the crash window
    `BillingEventStore` documents) is never replayed. It is marked `IGNORED` ("superseded by
    reconciliation") only when the run re-read the state it could have moved (review W2,
    2026-10-21):
    - **Per family, per phase.** Payment events (`checkout.session.completed`,
      `checkout.session.async_payment_succeeded`) are swept only when phases A and B completed;
      subscription events (`invoice.paid`, `invoice.payment_failed`,
      `customer.subscription.updated`, `.deleted`, `.paused`, `.resumed`) only when phase D
      completed. A stuck `customer.subscription.resumed` is decided by whether the re-read ended
      the pause episode it would have ended (Martin, 2026-10-22; it used to stay `RECEIVED` for
      ever): re-read and no longer `paused` (`active`, `past_due`, …), the re-read ended the
      episode and the event is superseded; re-read and `paused` again (paused, resumed with the
      webhook stuck, paused again), the episode stays open and the event is parked for review;
      not re-read, it is parked as any other. A phase is
      incomplete when it threw, recorded an error, met a failed provider read, a stopped list
      sweep, capped direct reads, or a tripped breaker. Alert-only types
      (`charge.dispute.created`) are never swept. The type lists are `StripeEventTypes`, shared
      with the webhook.
    - **Per event.** A payment event is superseded when its request is past `PAID` with nothing
      pending (a re-subscription not yet applied waits for phase B). A subscription event is
      superseded when phase D read its own subscription and the provider status is one D applies or
      alerts on (`active`, `trialing`, `canceled`, `incomplete_expired`, `paused`).
    - **Otherwise** the event is parked for review once (`reconcile:stale-event-review:<event>`,
      internal alert): it names no request, the provider contradicts it, its subscription was not
      read, or D leaves the status to the webhook (`past_due`, `unpaid`, `incomplete`). A payment
      event whose request phase A has not reached yet is left for a later run.
    - **A parked event stays counted until it is resolved** (Martin, 2026-10-22). The sweep leaves
      it out, so every later run reports it as "N stuck provider events still awaiting review"
      (`STALE_EVENTS_AWAITING`) for as long as its review row exists **and** the original event is
      still `RECEIVED`. Open is read off the original event, not the review row: the operator
      resolves it by moving the original row out of `RECEIVED` (§8), and the count clears itself
      whatever happens to the review row. An event parked in the same run is counted as parked
      only, never also as awaiting. The count is taken on every run, also when no family could be
      swept.
13. **Tags and counts** — every preference-route branch carries `ETP-5046-TRANSITIONAL-FALLBACK` and
    has its own count in the run summary.
14. **Non-retryable failures** — a provisioning failure whose code is deterministic
    (`ProvisioningFailureReason.isRetryable` = false; ETP-5737, the `ad_client_name` unique violation,
    is one) goes to **needs review** once and is not retried on every run. ETP-5737 itself is not
    fixed here. An attempt of the reconciliation's own that fails this way is parked in the same
    run (Martin, 2026-10-21): it keeps its `provisioning-failed` audit row, and the review alert
    is its only alert.
15. **Re-subscriptions** (ETP-5742, review B2, 2026-10-21) — the payment webhook records a
    re-subscription's payment and then applies it to the canceled environment. When the webhook is
    lost, phase A records the payment and phase B applies the re-subscription through
    `PaidReactivationService`, a facade over the webhook's own `applyReactivationIfPending`: one
    path, two callers. `CheckoutRequestStore.findPaidReactivation` locks the request row
    (`SELECT … FOR UPDATE`) and re-reads it, so a racing webhook and run apply it once. The
    preference route's watermark moves to the new subscription's start on the provider's clock.
    A re-subscription that cannot be applied is an error of the run, never "recovered".
16. **Provider time, never server time** (review W4, 2026-10-21) — a correction is ordered against
    the stored watermark, and stored as the new one, at the instant the provider asserted the
    state (`SubscriptionLifecycleApplier.providerStateAt`): `ended_at`/`canceled_at` for a close;
    otherwise the later of the current period start and the latest invoice's `paid_at` (the reads
    expand `latest_invoice`). So a correction is stale exactly when the webhook would drop the same
    change. An absent (404) subscription has no provider instant: it is closed now and the
    watermark is left as it is.

## 5. What a run does

The phases run in this order, each with its own transactions:

| # | Phase | Candidates | Provider read | Repair |
|---|---|---|---|---|
| A | Checkout recovery (provider → local) | `CREATED` rows with a session id, older than the paid grace, not backing off | `GET /v1/checkout/sessions/{id}` | `complete` + `paid`/`no_payment_required` → `recordPaid`, then provision (or apply the re-subscription) in B. `expired`, past the abandoned window → `EXPIRED`. `open` → nothing. A failed read → back-off. |
| B | Provisioning repair | `PAID` rows older than the paid grace (`PAID_AT`, else the creation); `PROVISIONING` rows whose lease is stale or whose failure is retryable; without a created client, or a `PAID` re-subscription; not parked, not backing off | none for a new purchase; `GET /v1/subscriptions/{id}` for a re-subscription | `PaidProvisioningService` under the `claimForProvisioning` lease, or `PaidReactivationService` (decision 15). A non-retryable failure, a unique violation, an attempt that cannot start (unknown currency, missing account, not the owner, demo source refused, not confirmed paid), or an exhausted attempt budget (`max.provisioning.attempts`, §7) → needs review, once, in the same run, whether the failure was recorded earlier or met by this run's attempt (then alerted once, by the review). |
| C | No-session-id review | `CREATING` rows older than the abandoned window, not parked | none | parked for review once; the run then counts as awaiting the open reviews earlier runs parked (one parked in this run is counted as a new review only) |
| D | Subscriptions (local → provider) | open active rows with a Stripe subscription id; preference-route tenants, latest purchase only (decision 9) | `GET /v1/subscriptions?status=all&expand[]=data.latest_invoice` paginated, then `GET /v1/subscriptions/{id}` for every local id the list did not return, up to the batch size | the status mapping of decision 7, applied through `SubscriptionLifecycleApplier` + `TenantEnvironmentLifecycleService.applySubscriptionEvent` on the provider's clock (decision 16), after the no-op diff. Missing price/amount/currency on an open row (the R37 adoption step, register §2.4) is filled from the subscription's first item price, only where NULL. |
| E | Stale `RECEIVED` sweep | Stripe events of the families whose phases completed, `RECEIVED` older than the threshold, not parked | none | per decision 12: superseded (`IGNORED`), parked for review, or left. Then, on every run, the stuck events earlier runs parked that are still `RECEIVED` are counted as awaiting review (one parked in this run is counted as parked only) |

**A run that could not check everything fails** (review W3, 2026-10-21). A failed provider read,
an incomplete list sweep, capped direct reads, an item error or a tripped breaker make the run
fail (`fail()`: error result and exception, so Process Monitor records ERROR), and its summary says
what could not be checked. "Everything is consistent" is printed only by a run that read
everything and changed nothing.

**A repair commits with its audit row** (review F1, 2026-10-21). A recovered payment and an
abandoned-checkout close are left pending (`CheckoutRequestWrites.recordPaidPending` /
`recordExpiredPending`) and committed by the insert of their billing-event row, so a failed insert
rolls the repair back too; the run reports it as an error and the request backs off
(`retry-later`) before a later run retries it, so an id that already exists (after a status reverted
by hand) costs one error per back-off, not one per hourly run. Subscription
corrections and price fills already worked this way. A provisioning and a re-subscription commit
in their own transactions (the whole onboarding; the webhook's own path), so for those a failed
audit insert leaves the repair standing without its row: an error of the run that says exactly
that, never silent.

**A 404 closes only next to a complete sweep** (review F2, 2026-10-21). When the list sweep did
not complete there is no evidence the key reads our account, so a subscription answering 404 is
counted, left open, listed as unverified, and the phase is marked incomplete: nothing is closed on a
404 in that run, not even the single candidate the breaker's floor would otherwise let through.

**One run at a time** (review F4, 2026-10-21). `ReconciliationRunLock` takes a PostgreSQL
transaction-scoped advisory lock (`pg_try_advisory_xact_lock(5048, 1)`) on a dedicated pooled
connection for the whole run, released in `finally`. A second run (a menu launch over the schedule)
does not wait: it ends at once with the warning "A billing reconciliation is already running;
nothing was done." (a Warning result, not an error). Without a dedicated connection, or when
taking the lock fails with a database error, the run goes ahead unlocked, with a WARN: every
repair is fenced or idempotent on its own, so an overlap costs false error reports, never a double
repair, while failing closed would stop the reconciliation under pool strain. The fail-open is
visible: the summary (popup, log, Process Monitor) then says "Ran without the run lock (no
connection available); concurrent runs were not excluded".

**What the operator reads** (Martin, 2026-10-21; AC5, readable without the database or the logs).
The summary words a dry run as what it would do ("would close 2 abandoned checkouts", "would
provision 1 environment", "would close/expire N subscriptions", "would park N for review"); a real
run keeps the past tense. A count of one takes the singular noun in both ("1 checkout checked",
"would park 1 purchase for review"), through the one `ReconciliationResult.counted` helper. Under the counts, one line per affected item — the short request id or the
subscription id, the company name, the action and the reason — capped at 20 lines plus "+N more".
The popup shows them one per line (HTML, every value escaped), and the run log Process Monitor shows
carries the same lines. Only ids, the company name and short reasons, as the billing-event rows.

**Batches cannot starve** (review W1, 2026-10-21). A purchase parked for review is left out of the
B and C queries (no open `reconciliation.needs-review` row), and a request whose item failed is left
out of the A and B queries for a fixed 6 h back-off (one `reconcile:retry-later:<request>` row,
refreshed on each failure). So rows no run can repair do not fill the fixed-size batch on every run.

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
- the paid preparation that needs no HTTP request, split into small cores the HTTP path now calls
  too (`findCurrencyId`, `isPaidOnboardingNotOwned`, `restorePaidSelection`, `claimPaid`). They
  stay on the servlet; `EtendoGoJwtServlet.provisionPaidPurchase` chains them for a paid purchase
  with no browser attached.

`PaidProvisioningService.provision(requestId, sink)` resolves the inputs from the checkout row
(decision 3) and calls `provisionPaidPurchase` on a fresh servlet instance. That method runs the
currency, owner, paywall and demo-source checks, the fenced claim, then `runOnboarding` over an
`OnboardingStreamWriter` backed by `NdjsonSinkWriter`, which replays the NDJSON lines onto the given
`OnboardingProgressSink`. The email verification gate is not repeated: the purchase it guards is
already paid. The outcome is one of `PROVISIONED`, `FAILED` (with the failure code; whether a retry
can help is decided by the reconciler from that code and the checkout row), `NEEDS_REVIEW` (an
attempt that cannot start: `ACCOUNT_MISSING`, `UNKNOWN_CURRENCY`, `BILLING_OWNER_REQUIRED`,
`DEMO_SELECTION_REQUIRED`), `NOT_CLAIMED` (another attempt holds it, or it finished meanwhile) or
`NOT_PAID` (the paywall does not confirm the payment). The caller's `OBContext` is restored
afterwards. `handleOnboarding` still prepares from the HTTP request and streams NDJSON exactly as
before. What remains on the servlet, and how to finish the extraction, is
`docs/open-and-notable-topics.md` §4.15.

A server-repaired environment gets the same company data as a browser-driven one (review S4): the
draft's `address` and `fiscalIdValue` are carried, checked as the HTTP path checks them (the
address length limit, the Spanish tax-id check). A value that fails is left out with a WARN rather
than failing the provisioning; the owner sets it later.

`rest/PaidReactivationService` (decision 15) is the same shape for a re-subscription: a facade over
the webhook's own `applyReactivationIfPending`.

### 6.2 `payment/StripeListPager`

Stripe list pagination on top of `StripeApiClient.get`: `limit`, `starting_after=<last id>`, and a
loop while `has_more`, with a page cap. A transport failure ends the sweep. The reconciler then
treats every id it did not see as unknown, never as absent.

### 6.3 `payment/reconcile/*`

- `BillingReconciliationService` runs the phases; phase D (subscriptions) lives in
  `SubscriptionReconciler`.
- `ReconciliationSupport` holds the helpers the phases share.
- `payment/CheckoutRequestWrites` holds the new checkout-row writes: the onboarding inputs
  recorded at purchase time, the conditional `CREATED → EXPIRED` close, and the recovered payment
  (`recordPaidPending` / `recordExpiredPending` leave the write pending for its audit row, §5).
- `payment/PausedSubscriptionAlert` raises the once-per-pause-episode review alert for `paused`,
  shared by the webhook and the job, and ends the open episodes (`paused` and paused collection)
  a subscription has left.
- `ReconciliationSettings` holds the properties of §7.
- `ReconciliationResult` holds the counts and the summary.
- `ReconciliationAudit` writes the billing-event rows and the `[BILLING-RECONCILE]` lines, and
  sends the internal review alerts (review S5): one per new review, per parked stuck event, one
  per UTC day for a tripped breaker, and one per UTC day for a scheduled run while the flag is off
  (sent from `BillingReconciliationProcess`). Each is sent only by the call that wrote its row. A
  dry run writes no row and sends no internal alert.
- `ReviewQueue` parks purchases for review, keeps them out of the batches, counts the open ones,
  and restores the attempt budget of a released one (§8).
- `payment/StripeEventTypes` holds the event-type lists the webhook acts on and the sweep reads.
- `ReconciliationRunLock` keeps one run at a time (§5).

Billing-event ids are deterministic:

| Kind | `EVENT_ID` | `EVENT_RESULT` |
|---|---|---|
| payment recorded from the provider | `reconcile:payment-recovered:<requestId>` | `APPLIED` |
| environment provisioned | `reconcile:provisioned:<requestId>` | `APPLIED` |
| provisioning attempt failed (every failed attempt; a non-retryable or unique-violation one is also parked for review in the same run) | `reconcile:provisioning-failed:<requestId>:<attempt>` | `FAILED` |
| needs review (non-retryable or unique-violation failure, an attempt that cannot start, not confirmed paid, no session id, attempts exhausted) | `reconcile:needs-review:<requestId>`, then `…:<requestId>:2`, … after a release | `FAILED` while open; `IGNORED` once released |
| re-subscription applied | `reconcile:reactivation-applied:<requestId>` | `APPLIED` |
| back-off after a failed item | `reconcile:retry-later:<requestId>` (one row, refreshed) | `FAILED` |
| stuck event parked for review | `reconcile:stale-event-review:<eventId>` | `FAILED`; open while the original event is still `RECEIVED` |
| breaker tripped | `reconcile:breaker-tripped:<yyyy-MM-dd>` | `IGNORED` |
| scheduled run while the flag is off | `reconcile:schedule-flag-off:<yyyy-MM-dd>` | `IGNORED` |
| checkout expired | `reconcile:checkout-expired:<requestId>` | `APPLIED` |
| subscription closed | `reconcile:subscription-closed:<stripeSubscriptionId>` | `APPLIED` |
| subscription back to current | `reconcile:subscription-current:<stripeSubscriptionId>:<runId>` | `APPLIED` |
| price/amount filled (R37 adoption) | `reconcile:subscription-price:<stripeSubscriptionId>` | `APPLIED` |
| `pause_collection` alert (open episode) | `reconcile:pause-collection:<stripeSubscriptionId>` | `IGNORED` |
| paused subscription review alert (webhook or job; open episode) | `reconcile:subscription-paused:<stripeSubscriptionId>` | `IGNORED` |
| an ended pause episode, kept as history | `<the open id>:ended:<epoch millis>` | `IGNORED` |

An existing id is the "already alerted" answer: the row is not written again. A close whose id
already exists means the subscription was closed by an earlier run and is open again: that is an
error of the run for a person to review, never a silent skip (review S6).

### 6.4 The circuit breaker (decision 7)

A 404 for a subscription a local row names is **absent**. Absent closes are collected, not
applied, until phase D has read everything. The breaker trips, and **no absent close is applied in
that run**, when either:

- the absent count exceeds `max(1, floor(absent.max.share × subscriptions checked))`; or
- the key mode does not match the data: the configured key is `sk_live_`/`rk_live_` and an absent
  row's creating session is `cs_test_…`, or the reverse; or
- the list sweep completed and returned **none** of the local subscriptions (review S1): the key
  reads another account, even in the same mode. Stripe lists canceled subscriptions too, so on the
  right account every id we hold is listed. Without this, the floor of one would let a foreign
  same-mode key close a tenant that is the only one.

A tripped breaker is alerted (an internal alert once per UTC day), the run is reported as an
error, its subscription events are not swept, and every other repair of the run stands. The other repairs are safe because each one acts on a positive provider answer; a
wrong-mode key gets 404s, never a `paid` or `expired` answer.

### 6.5 `payment/reconcile/BillingReconciliationProcess` and the schedule

`DalBaseProcess`, modelled on `UsageAggregationProcess`: the `OBError` result, `fail()` sets an error
result and throws, and `bundle.getLogger()` lines show in Process Monitor. The startup plants the
fixed request only while the flag is on; every scheduled run (timing `S`), whoever created its
request, ends as an ERROR without doing work while the flag is off (decision 11).

## 7. Configuration

| Property / env | Default | Meaning |
|---|---|---|
| `etendo.go.flags.billing-reconciliation-schedule` / `ETGO_FLAG_BILLING_RECONCILIATION_SCHEDULE` (or ConfigCat) | off | The schedule is planted at startup only when on; a run of it does work only when on |
| `etendo.go.billing.reconcile.interval.minutes` / `ETGO_BILLING_RECONCILE_INTERVAL_MINUTES` | 60 | Interval of the planted request (read when it is first planted) |
| `etendo.go.billing.reconcile.batch.size` / `ETGO_BILLING_RECONCILE_BATCH_SIZE` | 50 | Rows per phase per run |
| `etendo.go.billing.reconcile.abandoned.hours` / `ETGO_BILLING_RECONCILE_ABANDONED_HOURS` | 48 (minimum 48) | Age before a checkout counts as abandoned |
| `etendo.go.billing.reconcile.paid.grace.minutes` / `ETGO_BILLING_RECONCILE_PAID_GRACE_MINUTES` | 15 | Age before a paid row is provisioned by the server (the browser normally does it) |
| `etendo.go.billing.reconcile.received.stale.minutes` / `ETGO_BILLING_RECONCILE_RECEIVED_STALE_MINUTES` | 60 | Age before a `RECEIVED` event counts as stuck |
| `etendo.go.billing.reconcile.absent.max.share` / `ETGO_BILLING_RECONCILE_ABSENT_MAX_SHARE` | 0.25 | Breaker threshold |
| `etendo.go.billing.reconcile.max.provisioning.attempts` / `ETGO_BILLING_RECONCILE_MAX_PROVISIONING_ATTEMPTS` | 5 | Provisioning attempts after which a purchase is parked for review (`ATTEMPTS_EXHAUSTED`) instead of retried |

A malformed value falls back to its default, and every value is clamped (`ReconciliationSettings`):
the interval to at least 5 minutes, the batch size and the paid grace to at least 1, the stale
threshold to at least 5 minutes, the abandoned window to at least 48 h, the attempt budget to at
least 1; a share outside 0…1 falls back to 0.25. The back-off after a failed item is fixed at 6 h
(`BillingReconciliationService.RETRY_BACKOFF_MINUTES`).

## 8. Operating it

- **Run by hand:** Classic, System Administrator, menu **Billing Reconciliation**. Tick *Dry run*
  for a first look. The popup shows the summary and one line per affected item; a scheduled or
  Process Request run shows the same lines in Process Monitor. Only one run at a time: a second
  one says a reconciliation is already running and does nothing.
- **Release: a fresh environment needs a full build before the menu works** (found in manual
  testing on 2026-10-21). The **Billing Reconciliation** menu entry opens its popup through the
  `ActionButtonJava_Responser` servlet that WAD generates into `srcAD`. A class-mode smartbuild
  compiles the Java but does not run WAD, so that servlet is not regenerated and the popup answers
  "You do not have sufficient privileges" even for System Administrator. On a fresh local
  environment run `./gradlew compile.complete`, then a smartbuild to deploy. A normal deploy runs
  WAD and needs nothing extra.
- **Go-live:** turn the `billing-reconciliation-schedule` flag on and create the production Process
  Request **together**. A request firing with the flag off ends as an ERROR every hour in Process
  Monitor (and alerts once a day); a flag on with no request does nothing at all.
- **Process Groups are not gated:** a run fired as part of a Process Group has no process-request
  id of its own, so it is not recognised as a scheduled run and the flag does not gate it.
- **Database timeouts end the run lock:** the lock lives in an open transaction on its own
  connection that is idle for the whole run. A server-side `idle_in_transaction_session_timeout`
  shorter than a run would end that session, and with it the lock, silently and mid-run. Keep the
  setting off (the PostgreSQL default) or longer than a run for the Etendo database user.
- **A failed run** shows HTML lines in the popup; the exception, the log and Process Monitor get
  the same content as plain text.
- **Read what changed:** the **Billing Event** window (filter `Provider = reconciler`) and the
  **Checkout Request → Billing Event** tab. The **Subscriptions** window shows corrected status and
  end dates. Scheduled runs are in **Process Monitor**.
- **Schedule it in production:** production is never restarted, so the startup hook never runs
  there. Turn the flag on (ConfigCat or the property), then in the Process Request window create a
  request for **Billing Reconciliation**, client System, timing *Schedule*, every 60 minutes, as for
  Demo Trial Notices. A scheduled run while the flag is off does nothing and ends as an ERROR in
  Process Monitor (and alerts once a day): turn the flag on, or unschedule the request.
- **Switch the schedule on elsewhere:** in an environment that restarts, set the flag and restart;
  the startup plants the request. An unscheduled request is never planted again by the startup.
- **Alerts:** grep `[BILLING-RECONCILE]` (and `[BILLING-ALERT]` for a pause the webhook saw).
  Provisioning repairs also send the internal `environment-provisioning` alert. A new review sends
  `billing-reconcile-needs-review`, a parked stuck event `billing-reconcile-stale-event`, a tripped
  breaker `billing-reconcile-breaker-tripped` (once a day), a paused subscription
  `subscription-paused-review` (once per pause episode, from the job or the webhook), and a
  scheduled request firing while the `billing-reconciliation-schedule` flag is off
  `billing-reconcile-schedule-flag-off` (once a day: no reconciliation is happening; turn the flag
  on or unschedule the request). Paused collection is a WARN and a billing-event row only.
- **Release a parked purchase** (after the cause is fixed). A parked purchase is left out of every
  batch until its open `reconciliation.needs-review` row is released:

  ```sql
  update etgo_billing_event
     set event_result = 'IGNORED',
         failure_reason = 'Released by <who>: <what was fixed>'
   where provider = 'reconciler' and event_type = 'reconciliation.needs-review'
     and request_id = '<request id>' and event_result = 'FAILED';
  ```

  The next run picks the purchase up again. Per code:
  - `ATTEMPTS_EXHAUSTED` — the released purchase gets the full attempt budget again, counted from
    the attempts recorded on the released row.
  - a non-retryable failure or `DETERMINISTIC_FAILURE` (a unique violation) — the claim refuses a
    request whose recorded failure is deterministic, so also clear it:
    `update etgo_checkout_request set failure_reason = null where request_id = '<request id>';`
    Otherwise the next run parks it again (a new row, `…:2`) and alerts once more.
  - an attempt that could not start (`ACCOUNT_MISSING`, `UNKNOWN_CURRENCY`,
    `BILLING_OWNER_REQUIRED`, `DEMO_SELECTION_REQUIRED`, `NOT_PAID`) — no attempt was claimed, so
    nothing else needs clearing: fix the cause, release the row, and the next run tries again.
  - `NO_SESSION_ID` — releasing it is pointless: the row stays `CREATING`, so the next run parks
    it again. Close it instead, once Stripe confirms no payment was taken for that account and
    time (search the Stripe dashboard by the account email): set it to `EXPIRED`, then release the
    review row as above.

    ```sql
    update etgo_checkout_request
       set checkout_status = 'EXPIRED', expired_at = now(), updated = now()
     where request_id = '<request id>' and checkout_status = 'CREATING';
    ```
- **A row backing off** (`reconcile:retry-later:<request>`) is retried by itself after 6 h; to retry
  sooner, delete that row.
- **A parked stuck event** (`reconcile:stale-event-review:<event>`, the original event's id after
  the prefix): check the event in the Stripe dashboard, then resolve the **original** event row,
  which is still `RECEIVED`. Letting Stripe redeliver it does nothing: the claim re-opens only a
  `FAILED` row, so a delivery of a `RECEIVED` one is counted as a duplicate (`DUPLICATE_COUNT`)
  and never processed.
  - To drop it (its effect is already in place, or must not happen):

    ```sql
    update etgo_billing_event
       set event_result = 'IGNORED',
           failure_reason = 'Resolved by <who>: <why>', updated = now()
     where event_id = '<evt_…>' and event_result = 'RECEIVED';
    ```
  - To replay it, first make it re-claimable, then resend the event from the Stripe dashboard
    (the event's page, or the webhook endpoint's delivery list): the next delivery re-claims
    the `FAILED` row and processes it as a fresh claim.

    ```sql
    update etgo_billing_event set event_result = 'FAILED', updated = now()
     where event_id = '<evt_…>' and event_result = 'RECEIVED';
    ```

    Resend right after the update. A `FAILED` row is not a stuck event, so no run sweeps,
    parks or counts it again; until a delivery re-claims it, it only waits.

  The review row needs no action. Once the original event is no longer `RECEIVED`, the run summary
  stops counting it as "still awaiting review"; the review row stays as history. Do not delete it
  while the original event is still `RECEIVED`: without it the event is no longer excluded, so the
  next run sweeps it, parks it again and sends a second internal alert. Deleting it after the
  original event is resolved is harmless.

## 9. Known limitations

- **Large accounts** (review S2, accepted 2026-10-21, not fixed). Phase D reads every local
  candidate per run: one query per candidate row for its checkout request (N+1), a list sweep
  capped at 100 pages (10 000 subscriptions), and direct reads capped at the batch size in a fixed
  order, so on an account past those sizes the same tail is read on every run and the run reports
  itself incomplete. Fine at today's volumes; revisit with a paged, rotating candidate cursor
  before an account nears them. Also recorded in `docs/open-and-notable-topics.md`.

## 10. Tests

The developer writes none beyond repro tests. tester-go owns the coverage required by the
acceptance criteria: both directions, the never-close branch, pagination, the idempotent re-run
and the breaker.
