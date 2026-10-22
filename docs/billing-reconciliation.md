# Billing reconciliation (`ETGO_BillingReconciliation`)

The operator reference for the billing reconciliation (ETP-5048): what a run does, how to launch
and schedule it, every setting, the alerts it sends, how to read what it did, and the procedures
for the cases it leaves to a person.

The background process makes money taken and service delivered agree without anyone reading
Stripe by hand. It recovers payments whose webhook was lost, provisions purchases that were paid
but never got an environment, closes abandoned checkouts, corrects subscriptions whose status
changed at Stripe while the webhook was down, and sweeps provider events stuck in `RECEIVED`.

Why it works this way (decisions, rejected alternatives, the boundary with ETP-5396's work record)
is recorded in the design of record,
[`plans/2026-10-18-etp-5048-billing-reconciliation-design.md`](plans/2026-10-18-etp-5048-billing-reconciliation-design.md).
This page does not depend on it.

## 1. What a run does

A run goes through five phases in order. Each phase uses its own transactions, and a phase that
fails does not stop the next one.

| # | Phase | Looks at | What it can do |
|---|---|---|---|
| A | Checkout recovery (Stripe → local) | `CREATED` checkouts that have a Stripe session id and are older than the paid grace | Reads the Checkout Session. Paid: records the payment, and phase B provisions it. Expired and older than the abandoned window: closes the checkout as `EXPIRED`. Still open: nothing. |
| B | Provisioning repair | `PAID` purchases older than the paid grace (`PAID_AT`, or the creation when there is none); `PROVISIONING` purchases whose lease expired (`stalled`, §4.1) or whose recorded failure is retryable. Only purchases with no environment yet, or a `PAID` re-subscription | Provisions the environment through the same onboarding the browser runs (the customer gets the usual `environment-ready` email), or applies the re-subscription to its canceled environment. A purchase no retry can fix is parked for review (§7.1). |
| C | No-session-id review | `CREATING` checkouts that never received a Stripe session id and are older than the abandoned window | Parks them for review. They are never closed automatically: without a session id Stripe cannot confirm that no payment was taken. |
| D | Subscriptions (local → Stripe) | Open subscriptions with a Stripe subscription id, and tenants still on the preference route (latest purchase only) | Reads every subscription from Stripe. `active`/`trialing`: current. `canceled`/`incomplete_expired`: closes the subscription locally. `paused`, and `pause_collection` on an active subscription: alert only, no state change. `past_due`, `unpaid`, `incomplete`: left to the webhook. A subscription Stripe answers 404 for is closed only behind the absent-subscription breaker (below). Also fills a missing price, amount and currency on an open subscription. |
| E | Stuck `RECEIVED` sweep | Stripe events still `RECEIVED` after the stale threshold, only of the families whose phases completed in this run | Marks an event `IGNORED` ("superseded") when the run re-read the state it would have changed, parks it for review when no re-read can vouch for it (§7.3), or leaves it for a later run. |

**The absent-subscription breaker.** A subscription that Stripe answers 404 for is *absent*.
Absent closes are collected and applied only after phase D has read everything, and none is
applied in that run when the breaker trips:

- more absent subscriptions than `max(1, floor(absent.max.share × subscriptions checked))`;
- the key mode does not match the data (a live key `sk_live_`/`rk_live_` against a checkout
  created with a test session `cs_test_…`, or the reverse);
- the Stripe list sweep completed and returned none of the local subscriptions (the key reads
  another account).

A tripped breaker makes the run fail and sends one alert per UTC day. Check the configured Stripe
key: the breaker keeps tripping until it reads the right account in the right mode. Every other
repair of the run stands, because each one acts on a positive Stripe answer.

**Dry run.** Every read happens and every decision is computed and counted, but nothing is written,
claimed or provisioned, no billing-event row is created and no internal alert is sent. The summary
words everything as what the run would do.

**Every repair is a billing-event row.** Each repair or alert is written as an `ETGO_BILLING_EVENT`
row with `PROVIDER = 'reconciler'`, `EVENT_TYPE = 'reconciliation.<kind>'`, a deterministic
`EVENT_ID` (`reconcile:<kind>:<id>…`) and, when there is one, the link to its checkout request. An
existing id means the repair or alert was already recorded, so a re-run over the same state writes
nothing and alerts nobody. A consistent dataset costs zero writes and zero alerts. There is no
run-log table.

**Every log line starts with `[BILLING-RECONCILE]`.** Repairs are logged at INFO, alerts at WARN.

**A run that could not check everything fails.** A failed Stripe read, an incomplete list sweep,
capped direct reads, an item error or a tripped breaker make the run end as an error, and the
summary says what could not be checked. "Everything is consistent; nothing was changed" is
printed only by a run that read everything and changed nothing. On a very large account (more than
10 000 subscriptions, or more direct reads than the batch size), phase D cannot read everything in
one run and reports itself incomplete on every run; see the design's *Known limitations*.

**One run at a time.** A run holds a PostgreSQL advisory lock for its whole duration. A second run
(a menu launch over a scheduled one) does not wait: it ends at once with the warning "A billing
reconciliation is already running; nothing was done." When no dedicated database connection can be
had, the run goes ahead without the lock and its summary says "Ran without the run lock (no
connection available); concurrent runs were not excluded". Every repair is fenced or idempotent on
its own, so an overlap costs false error reports, never a double repair.

### 1.1 The run summary

The summary is one paragraph, `Run <id>: …` (or `Dry run <id>: …`), listing every non-zero count,
followed by one line per affected item. A count of one reads in the singular.

| Counter | Real run | Dry run |
|---|---|---|
| Checkouts checked (phase A) | `N checkouts checked` | same |
| Payments recovered | `N payments recovered from the provider` | `would record N payments recovered from the provider` |
| Abandoned checkouts closed | `N abandoned checkouts closed` | `would close N abandoned checkouts` |
| Environments provisioned | `N environments provisioned` | `would provision N environments` |
| Re-subscriptions applied | `N re-subscriptions applied` | `would apply N re-subscriptions` |
| Failed provisioning attempts | `N provisioning attempts failed` | same |
| New reviews (parked this run) | `N new purchases needing review` | `would park N purchases for review` |
| **Open reviews from earlier runs** | `N purchases still awaiting review` | same |
| Subscriptions checked (phase D) | `N subscriptions checked` | same |
| Subscriptions closed | `N subscriptions closed` | `would close/expire N subscriptions` |
| Subscriptions back to current | `N subscriptions set back to current` | `would set N subscriptions back to current` |
| Prices filled | `N subscription prices filled` | `would fill N subscription prices` |
| Paused-collection alerts | `N paused-collection alerts` | `would raise N paused-collection alerts` |
| Paused subscriptions | `N paused subscriptions needing review` | `would raise N paused-subscription review alerts` |
| Absent at Stripe (404) | `N subscriptions absent at the provider` | same |
| Preference route, checked | `N tenants on the preference route checked` | same |
| Preference route, corrected | `N tenants on the preference route corrected` | `would correct N tenants on the preference route` |
| Stuck events superseded | `N stuck provider events superseded` | `would supersede N stuck provider events` |
| Stuck events parked this run | `N stuck provider events needing review` | `would park N stuck provider events for review` |
| **Stuck events parked earlier, unresolved** | `N stuck provider events still awaiting review` | same |
| Failed Stripe reads | `N provider reads that failed` | same |

The two "still awaiting review" counts are not writes: they report what earlier runs parked and
nobody has resolved yet, and they appear on every run until it is resolved (§7.1, §7.3). A purchase
or event parked in the current run is counted as new only, never also as awaiting.

After the counts the summary may add, in this order: `Everything is consistent; nothing was
changed` (no writes, no failures), `Dry run: nothing was written`, `Not everything could be
checked, so the run is reported as failed (<phase>: <reason>)`, `Absent-subscription breaker
tripped, no absent subscription was closed: <reason>`, `N errors: …` (the first five), and the
no-lock note above. A run with nothing at all to look at says `nothing to check`.

The item lines name the short request id or the subscription id, the company name, the action and
the reason, for example `req 1a2b3c4d (Acme SL): close abandoned checkout — the provider session
expired` (`would close …` in a dry run). At most 20 lines are listed, then `+N more`.

## 2. Running it from Classic

Etendo Classic, role **System Administrator**, menu **Billing Reconciliation**. Tick **Dry run**
for a first look. The popup shows the summary and one line per affected item.

| Popup title | Meaning |
|---|---|
| Billing Reconciliation Complete | Everything was read; the repairs listed were made |
| Billing Reconciliation Finished With Errors | Something could not be checked or repaired; the summary says what |
| Billing Reconciliation Failed | The run itself threw; the reason is shown |
| Billing Reconciliation Already Running | Another run holds the lock; this one did nothing |
| Billing Reconciliation Schedule Is Off | A scheduled run fired while the schedule flag is off (§3) |

A failed run shows HTML lines in the popup; the exception, the server log and Process Monitor get
the same content as plain text. A scheduled or Process Request run writes the summary and the item
lines to its run log in **Process Monitor**, and a failed one is recorded there as ERROR.

**A fresh environment needs a full build before the menu works.** The menu entry opens its popup
through the `ActionButtonJava_Responser` servlet that WAD generates into `srcAD`. A class-mode
smartbuild compiles the Java but does not run WAD, so the popup answers "You do not have sufficient
privileges" even for System Administrator. On a fresh local environment run
`./gradlew compile.complete`, then a smartbuild to deploy. A normal deploy runs WAD and needs
nothing extra.

## 3. Scheduling

Whether the reconciliation runs on a schedule is governed by the backend flag
`billing-reconciliation-schedule`, strictly off by default. Its sources (local property, environment
variable or ConfigCat) and its full behaviour are documented once, in
[`feature-flags-and-tenant-upgrade.md`](feature-flags-and-tenant-upgrade.md) §1, section
*`billing-reconciliation-schedule` (ETP-5048)*. In short:

- **The flag gates every scheduled run**: any run the Process Scheduler fires for a request whose
  timing is *Schedule*, whoever created the request. While the flag is off such a run does no work,
  ends as an ERROR in Process Monitor saying to turn the flag on or unschedule the request, and the
  first such run of each UTC day sends one internal alert. The request's *Dry run* parameter does
  not suppress that alert.
- A run started from the menu, or a Process Request run *immediately* or *later once*, is never
  gated.
- A run fired as part of a **Process Group** has no process-request id of its own, so it is not
  recognised as a scheduled run and the flag does not gate it.

**Production schedules it by hand.** Production is updated by delta and never restarted, so the
startup hook never runs there. Turn the flag on and create the Process Request **together**:

1. Turn `billing-reconciliation-schedule` on (ConfigCat, or the property where no ConfigCat key is
   configured).
2. In the **Process Request** window create a request for **Billing Reconciliation**, client
   System, timing *Schedule*, every 60 minutes, as for Demo Trial Notices.

A request firing while the flag is off ends as an ERROR every hour (and alerts once a day); a flag
that is on with no request does nothing at all. To stop the schedule, turn the flag off (it is read
on every run, so ConfigCat stops it within one poll) or unschedule the request.

**Environments that restart.** Set the flag and restart: `BillingReconciliationScheduleStartup`
plants a fixed System request, every `etendo.go.billing.reconcile.interval.minutes` minutes (§4).
The interval is read only when the request is first planted; changing the property later does not
re-time an existing request, and a request created by hand uses the interval set on it. A planted
request that an operator unscheduled is left as it is and never planted again.

**Keep `idle_in_transaction_session_timeout` off for the Etendo database user** (the PostgreSQL
default), or longer than a run. The run lock lives in an open transaction on its own connection,
idle for the whole run; a shorter server-side timeout ends that session, and with it the lock,
silently and mid-run.

## 4. Settings

Every setting below is read from the module's layered configuration: JVM system property, then
`Openbravo.properties`, then environment variable. The provisioning lease is the exception (see
its row). A malformed value falls back to its default; every value is then clamped
(`ReconciliationSettings`).

| Property / environment variable | Default | Clamp | Meaning |
|---|---|---|---|
| `etendo.go.billing.reconcile.interval.minutes` / `ETGO_BILLING_RECONCILE_INTERVAL_MINUTES` | 60 | at least 5 | Interval of the request the startup plants (read when it is first planted, §3) |
| `etendo.go.billing.reconcile.batch.size` / `ETGO_BILLING_RECONCILE_BATCH_SIZE` | 50 | at least 1 | Candidate rows per phase per run; also caps the direct subscription reads of phase D and the stuck events handled by phase E |
| `etendo.go.billing.reconcile.abandoned.hours` / `ETGO_BILLING_RECONCILE_ABANDONED_HOURS` | 48 | at least 48 | Age before a checkout counts as abandoned (phases A and C). Sessions carry Stripe's default 24 h expiry, so a shorter window could only meet sessions Stripe still reports open |
| `etendo.go.billing.reconcile.paid.grace.minutes` / `ETGO_BILLING_RECONCILE_PAID_GRACE_MINUTES` | 15 | at least 1 | Age before the server touches a checkout or a paid purchase (phases A and B); the browser normally finishes within it |
| `etendo.go.billing.reconcile.received.stale.minutes` / `ETGO_BILLING_RECONCILE_RECEIVED_STALE_MINUTES` | 60 | at least 5 | Age before a `RECEIVED` Stripe event counts as stuck (phase E) |
| `etendo.go.billing.reconcile.absent.max.share` / `ETGO_BILLING_RECONCILE_ABSENT_MAX_SHARE` | 0.25 | 0…1, otherwise 0.25 | Breaker threshold: the share of checked subscriptions that may be absent before no absent close is applied (§1) |
| `etendo.go.billing.reconcile.max.provisioning.attempts` / `ETGO_BILLING_RECONCILE_MAX_PROVISIONING_ATTEMPTS` | 5 | at least 1 | Provisioning attempts after which a purchase is parked for review (`ATTEMPTS_EXHAUSTED`) instead of retried |
| `etendo.go.billing.provisioning.lease.minutes` / `ETGO_BILLING_PROVISIONING_LEASE_MINUTES` | 30 | at least 1 | The provisioning lease (§4.1). Read from the **JVM system property or the environment variable only**, not from `Openbravo.properties` (`CheckoutRequestStore`) |

The back-off after a failed item is **fixed at 6 hours** and not configurable: a request whose item
failed (a Stripe read, an exception, an audit row that could not be written) is left out of phases A
and B for 6 h (§7.2).

The schedule flag is not a setting of this table; see §3.

### 4.1 The provisioning lease and what it means for the reconciliation

A provisioning attempt claims its purchase by moving it to `PROVISIONING` and stamping
`PROVISIONING_AT`; `PROVISIONING_ATTEMPTS` is the fencing token, so only the latest attempt can
complete the purchase. The lease is shared by the browser path and the reconciliation, and it is
**not renewed while an onboarding runs**: `PROVISIONING_AT` changes only when a new attempt
reclaims the purchase.

- A `PROVISIONING` purchase with no recorded failure and a `PROVISIONING_AT` older than the lease is
  **`stalled`**. Phase B treats it as abandoned and starts a new attempt, which takes over the
  fencing token.
- A `PROVISIONING` purchase inside its lease is left alone: an attempt is presumed to be running.
- An onboarding that takes longer than the lease can therefore be overtaken by a reconciliation
  attempt. The token keeps that safe (the old attempt can no longer complete the purchase), but it
  costs a second attempt. Keep the lease longer than the slowest onboarding.

## 5. Alerts

The reconciliation tells a person about what needs action through internal alerts, sent on the
channel of the provisioning alerts. Every one is sent with status `ERROR` and category
`REVIEW_REQUIRED`, only by the call that wrote its deterministic row, so a re-run over the same
state stays silent. A dry run sends none.

| Alert event | Sent when | How often |
|---|---|---|
| `billing-reconcile-needs-review` | A purchase is parked for review (§7.1) | Once per parking; a released purchase parked again alerts again |
| `billing-reconcile-stale-event` | A stuck Stripe event is parked for review (§7.3) | Once per event |
| `billing-reconcile-breaker-tripped` | The absent-subscription breaker tripped (§1) | Once per UTC day |
| `subscription-paused-review` | A subscription is `paused` at Stripe, seen by the reconciliation or the webhook | Once per pause episode; a later pause alerts again |
| `billing-reconcile-schedule-flag-off` | A scheduled request fired while the schedule flag is off (§3) | Once per UTC day, whatever the request's *Dry run* says |
| `environment-provisioning` | A repair provisioned (or failed to provision) an environment; the onboarding's own alert | Per attempt outcome, as for a browser-driven onboarding |

Recipients, the on/off switch and the accepted statuses are the internal-alert settings
(`etendo.go.internalAlerts.recipients`, `.enabled`, `.statuses`), documented in
[`internal-operational-alerts.md`](internal-operational-alerts.md) §Configuration. Because every
reconciliation alert has status `ERROR`, a `statuses` setting without `ERROR` silences them all.

In the server log, grep `[BILLING-RECONCILE]` (and `[BILLING-ALERT]` for a pause the webhook saw).
A paused collection (`pause_collection` on an active subscription) is a WARN and a billing-event row
only, with no internal alert.

## 6. Reading what it did

In Etendo Classic:

- **Billing Event** window, filtered on `Provider = reconciler`: every repair and alert.
- **Checkout Request** window, **Billing Event** tab: the rows of one purchase.
- **Subscriptions** window: corrected statuses and end dates.
- **Process Monitor**: scheduled runs, their result and their run log.

What a reconciler row means, by `EVENT_TYPE` (always `reconciliation.<kind>`). A `FAILED` result
does not always mean something to fix:

| `EVENT_TYPE` | `EVENT_RESULT` | Needs a person? |
|---|---|---|
| `reconciliation.payment-recovered`, `.provisioned`, `.reactivation-applied`, `.checkout-expired`, `.subscription-closed`, `.subscription-current`, `.subscription-price` | `APPLIED` | No: a repair that was made |
| `reconciliation.needs-review` | `FAILED` while open, `IGNORED` once released | **Yes**, while `FAILED` (§7.1) |
| `reconciliation.stale-event-review` | `FAILED` | **Yes**, while the original event is still `RECEIVED` (§7.3) |
| `reconciliation.provisioning-failed` | `FAILED` | No: history of one failed attempt (`EVENT_ID` ends in the attempt number) |
| `reconciliation.retry-later` | `FAILED` | No: a back-off marker, one row per request, refreshed on each failure (§7.2) |
| `reconciliation.breaker-tripped` | `IGNORED` | **Yes**: check the Stripe key (§1) |
| `reconciliation.schedule-flag-off` | `IGNORED` | **Yes**: turn the flag on or unschedule the request (§3) |
| `reconciliation.subscription-paused`, `.pause-collection` | `IGNORED` | Review the subscription at Stripe. The row is open while its id has no `:ended:` suffix; an ended episode is kept as `<id>:ended:<epoch millis>` |

### 6.1 Useful SQL

What the reconciliation wrote recently:

```sql
select received_at, event_type, event_id, event_result, request_id, failure_reason, payload_summary
  from etgo_billing_event
 where provider = 'reconciler'
 order by received_at desc
 limit 50;
```

Purchases parked for review and not yet released (the "purchases still awaiting review" count):

```sql
select rv.request_id, rv.received_at, rv.failure_reason, rv.payload_summary,
       cr.client_name, cr.checkout_status, cr.provisioning_attempts,
       cr.failure_reason as checkout_failure
  from etgo_billing_event rv
  left join etgo_checkout_request cr on cr.request_id = rv.request_id
 where rv.provider = 'reconciler' and rv.event_type = 'reconciliation.needs-review'
   and rv.event_result = 'FAILED'
 order by rv.received_at;
```

The review code is in `FAILURE_REASON` (`Needs manual review (<code>): …`) and in `PAYLOAD_SUMMARY`
(`{"code": …, "attempts": …}`).

Stuck events parked for review and not yet resolved (the "stuck provider events still awaiting
review" count):

```sql
select ev.event_id, ev.event_type, ev.received_at, ev.request_id
  from etgo_billing_event ev
  join etgo_billing_event rv on rv.event_id = 'reconcile:stale-event-review:' || ev.event_id
 where ev.provider = 'stripe' and ev.event_result = 'RECEIVED'
 order by ev.received_at;
```

Requests backing off, and when they become candidates again:

```sql
select request_id, failure_reason, duplicate_count,
       coalesce(last_duplicate_at, received_at) + interval '6 hours' as retried_after
  from etgo_billing_event
 where provider = 'reconciler' and event_type = 'reconciliation.retry-later'
   and coalesce(last_duplicate_at, received_at) > now() - interval '6 hours';
```

Paid purchases that still have no environment (what phase B looks at):

```sql
select request_id, checkout_status, paid_at, provisioning_at, provisioning_attempts,
       failure_reason, client_name
  from etgo_checkout_request
 where checkout_status in ('PAID', 'PROVISIONING') and created_client_id is null
 order by coalesce(paid_at, created);
```

## 7. Procedures

### 7.1 Release a parked purchase

A purchase parked for review has an open `reconciliation.needs-review` row (`FAILED`) and is left
out of every batch until that row is released. Fix the cause first (per code, below), then release
it:

```sql
update etgo_billing_event
   set event_result = 'IGNORED',
       failure_reason = 'Released by <who>: <what was fixed>',
       updated = now()
 where provider = 'reconciler' and event_type = 'reconciliation.needs-review'
   and request_id = '<request id>' and event_result = 'FAILED';
```

The next run picks the purchase up again. **Release by setting `IGNORED`; do not delete the row.**
The released row keeps the history, and it records the attempt count the purchase was parked at,
which the attempt budget is counted from. A purchase parked again gets a new row
(`reconcile:needs-review:<request id>:2`, `…:3`, …) and a new alert.

Per review code (the code is in the row's `FAILURE_REASON` and `PAYLOAD_SUMMARY`):

- **`ATTEMPTS_EXHAUSTED`** — the purchase reached `max.provisioning.attempts` (§4). Find out why the
  attempts failed (the `reconciliation.provisioning-failed` rows and the checkout's
  `FAILURE_REASON`), fix it, and release. The released purchase gets the full attempt budget again,
  counted from the attempts recorded on the released row.
- **`CLIENT_NAME_IN_USE`** (name clash) — the account already has a productive environment with the
  purchase's company name. The name is fixed on the purchase (`ETGO_CHECKOUT_REQUEST.CLIENT_NAME`),
  so every retry fails the same way. Remove the clash (rename the account's other productive
  environment, or, with the customer's agreement, change the purchase's `CLIENT_NAME`), then also
  clear the recorded failure before releasing:

  ```sql
  update etgo_checkout_request set failure_reason = null, updated = now()
   where request_id = '<request id>';
  ```

  Without that, the next run parks it again (a new review row and a second alert): the
  reconciliation parks any purchase whose recorded failure is non-retryable, and the provisioning
  claim refuses to reclaim a `CLIENT_NAME_IN_USE` row whoever asks.
- **`DETERMINISTIC_FAILURE`** — the recorded failure is a unique-key violation (`duplicate key value
  violates unique`, SQLSTATE `23505`), which a retry repeats. Fix the colliding data, clear the
  checkout's `FAILURE_REASON` as above, then release; otherwise the next run finds the same text and
  parks it again.

  After clearing the failure, the purchase is a `PROVISIONING` row with no failure, so it is retried
  once its lease has expired (§4.1); normally that happened long ago.
- **`ACCOUNT_MISSING`, `UNKNOWN_CURRENCY`, `BILLING_OWNER_REQUIRED`, `DEMO_SELECTION_REQUIRED`,
  `NOT_PAID`** — the attempt could not start, so no attempt was claimed and nothing else needs
  clearing. Fix the cause, release the row, and the next run tries again.
- **`NO_SESSION_ID`** — releasing it is pointless: the checkout stays `CREATING`, so the next run
  parks it again. Close it instead, once Stripe confirms that no payment was taken for that account
  at that time (search the Stripe dashboard by the account email). Set it to `EXPIRED` **first**,
  then release the review row as above:

  ```sql
  update etgo_checkout_request
     set checkout_status = 'EXPIRED', expired_at = now(), updated = now()
   where request_id = '<request id>' and checkout_status = 'CREATING';
  ```

### 7.2 A request backing off

A request whose item failed has a `reconcile:retry-later:<request id>` row and is left out of
phases A and B for 6 hours, so a row that fails on every run cannot fill the batch and starve the
rows behind it. It is retried by itself once the 6 hours have passed. The row's `FAILURE_REASON`
says what failed. To retry sooner, delete the marker:

```sql
delete from etgo_billing_event
 where provider = 'reconciler' and event_id = 'reconcile:retry-later:<request id>';
```

### 7.3 A parked stuck event

A stuck event parked for review has a `reconcile:stale-event-review:<event id>` row; the original
Stripe event id follows the prefix. The original event row is still `RECEIVED`. Check the event in
the Stripe dashboard, then resolve the **original** row, not the review row.

Letting Stripe redeliver the event does nothing: a delivery re-opens only a `FAILED` row, so a
delivery of a `RECEIVED` one is counted as a duplicate (`DUPLICATE_COUNT`) and never processed.

- **To drop it** (its effect is already in place, or must not happen):

  ```sql
  update etgo_billing_event
     set event_result = 'IGNORED',
         failure_reason = 'Resolved by <who>: <why>',
         processed_at = coalesce(processed_at, now()), updated = now()
   where event_id = '<evt_…>' and event_result = 'RECEIVED';
  ```

- **To replay it**, first make it re-claimable, then resend the event from the Stripe dashboard (the
  event's page, or the webhook endpoint's delivery list). The next delivery re-claims the `FAILED`
  row and processes it as a fresh claim.

  ```sql
  update etgo_billing_event set event_result = 'FAILED', updated = now()
   where event_id = '<evt_…>' and event_result = 'RECEIVED';
  ```

  Resend right after the update. A `FAILED` row is not a stuck event, so no run sweeps, parks or
  counts it again; until a delivery re-claims it, it only waits.

The review row needs no action. Once the original event is no longer `RECEIVED`, the run summary
stops counting it as "still awaiting review"; the review row stays as history. **Do not delete the
review row while the original event is still `RECEIVED`**: without it the event is no longer
excluded, so the next run sweeps it, parks it again and sends a second internal alert. Deleting it
after the original event is resolved is harmless.
