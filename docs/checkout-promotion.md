# Checkout promotion contract

## Current offer: 30-day free trial (ETP-6082)

A monthly catalog plan with a price above zero starts with a **30-day free trial** (fixed days,
not a calendar month), once per account:

- **Who gets it.** An account that has never paid a purchase (`CheckoutRequestStore.findForAccount`:
  no `PAID`, `PROVISIONING` or `PROVISIONED` request). A second environment of the same account,
  a re-subscription and the legacy plan pay from the first month. Yearly prices already charge ten
  installments for twelve months: no trial.
- **Checkout.** The session sends `subscription_data[trial_period_days]=30`. Nothing is due today,
  so it also sends `payment_method_collection=always`: with the default `if_required` Stripe would
  skip the card and the trial would end with nothing to charge. Should that still happen,
  `trial_settings[end_behavior][missing_payment_method]=cancel` ends the subscription instead of
  leaving it unpaid. The hosted page explains the offer (`custom_text[submit][message]`).
- **During the trial.** The subscription is `trialing`, which the lifecycle treats as current: the
  environment is provisioned and usable. Stripe issues a zero invoice at the start; it is not a
  payment (HubSpot leaves the deal in Tarjeta añadida, see `hubspot-sync.md`).
- **Cancelling.** The customer portal cancels at period end, and during the trial the period ends
  with the trial: nothing is ever charged, and `customer.subscription.deleted` at that moment
  closes the environment through the ordinary cancellation path.
- **After the trial.** The first installment is charged on day 30 and the plan recurs monthly. A
  failed charge follows the ordinary `past_due` path, now for an environment that already exists.
- **Plan changes during the trial** (decided with the business, ETP-6216). The trial is the
  account's, not the plan's: a plan change neither ends nor restarts it, and the first charge is
  the plan active when the trial ends. `PlanChangeService` produces it: an upgrade updates the
  price at once and its proration is zero while `trialing`; a downgrade is scheduled at the period
  end, which is the trial end, and resends the trial end on the current phase (a schedule phase
  sent without `trial_end` has no trial, so Stripe ended it and charged at once). A switch from
  monthly to yearly also keeps the trial, so the yearly price is charged when the trial ends.
  Verified in Stripe test mode on 2026-11-16 (upgrade, monthly to yearly, downgrade).
  After the trial, monthly-to-yearly charges the yearly price minus the unused part of the month,
  with no extra discount.
- **Catalog.** `GET /sws/go/plans` returns `freeTrialDays` per plan for the requesting account (30
  or 0), so the upgrade page announces the trial only when checkout will grant it.

The terms are recorded as `promotion.intent` with version `monthly-free-trial-v1`
(`freeTrialDays`, `amountDueTodayMinor`). Nothing runs after payment: the trial is the provider's.

## Previous offer: second month free (ETP-6216, no longer sold)

Monthly purchases recorded under `monthly-second-month-v1` paid the first monthly installment at
checkout, received the second calendar month free, and resumed ordinary monthly billing in month
three. Those purchases keep their terms: the rest of this document describes how they are still
applied and recovered. Reactivations and historical purchases without a promotion snapshot are
not enrolled.

## Durable execution

Before contacting Checkout, write immutable commercial terms in `ETGO_BILLING_EVENT`,
provider `reconciler`, type `promotion.intent`, keyed by checkout request. These are internal
intent records, not verified Stripe events. Reopens use that exact snapshot. The provider
metadata carries the server's promotion version and request correlation.

After confirmed checkout payment, migrate its subscription to a schedule and retain the
exact current phase start/end returned by Stripe. Append one discounted calendar month,
then one normal month and release the schedule to normal recurrence. No fixed 30-day dates.
The 100-percent coupon is confined to the second phase. Annual purchases never use it.
Empty `add_invoice_items` lists are omitted from the phase update because Stripe rejects
attempts to unset this parameter; existing invoice items remain confined to the first phase.
The appended phases last one month each through `duration[interval]=month` and
`duration[interval_count]=1`. They never send `iterations`: Stripe removed it in API
`2025-09-30.clover` and answers `400 parameter_unknown`, which made every promoted checkout
webhook fail and retry until the provider gave up (etendo-ai#141, from the 2026-11-12 release).
Recovery replays those intents only while the first phase is open; after it ends the promotion
fails for review ("installation window has elapsed").

The provider schedule is tagged with request/version metadata; retries recognize completion
before making changes. Deterministic coupon and request-scoped idempotency keys fence POSTs.
The billing event intent and applied markers survive restarts. Reconciliation retries paid
intents whose webhook application failed or crashed. A dry run makes no provider mutations.
An expired eligibility window or an unrelated pre-existing schedule fails for review instead
of silently shifting the free month or overwriting another schedule.

## Validation boundary

Mocked tests cover terms, phase boundaries, second-phase-only discount, retries, yearly
exclusion and rejection of unrelated schedules. No live coupon, Checkout, payment or provider
schedule is created during development. Production availability requires deploy and separate
Stripe Test Mode clock verification, including the zero-amount month-two invoice lifecycle.

Local verification on 2026-11-11 used the normal Etendo Gradle wrapper and JDK 17:

```sh
./gradlew goTestUnit \
  --tests 'com.etendoerp.go.payment.CheckoutPromotionTermsTest' \
  --tests 'com.etendoerp.go.payment.MonthlySecondMonthPromotionServiceTest' \
  --tests 'com.etendoerp.go.payment.PromotionRecoveryTest' \
  --tests 'com.etendoerp.go.payment.SubscriptionLifecycleApplierTest' \
  --tests 'com.etendoerp.go.payment.HostedCheckoutServiceTest' \
  --tests 'com.etendoerp.go.payment.HostedCheckoutSessionPlanTest' \
  --tests 'com.etendoerp.go.payment.HostedCheckoutLegacyFallbackTest' \
  --tests 'com.etendoerp.go.payment.StripePriceServiceTest' \
  --tests 'com.etendoerp.go.rest.EtendoGoJwtServletBillingCookieAuthTest' \
  --tests 'com.etendoerp.go.rest.EtendoGoJwtServletTest' \
  --console plain
```

Result: **239 tests passed, zero failures, errors or skipped tests** across ten classes,
including the local HTTP provider fixtures. Production and test compilation also passed.
The local environment first needed the documented `epic/ETP-3504` core baseline and a
refresh of its generated SQLC output; no compatibility patches were added to task sources.
These tests use mocked persistence and Stripe responses. They do not prove concurrent DAL
transactions, real Stripe scheduling or production catalog configuration.

Recovery requires the existing **Billing Reconciliation** process to run (manually or through
its configured Process Request). `billing-reconciliation-schedule` is off by default; this
change does not enable it. Provider webhook retries handle ordinary transient failures;
operators must run reconciliation for crash recovery when no recurring request is configured.
Failures are isolated per intent and backed off for one hour so an old broken purchase cannot
starve later paid promotions. A failure beyond Stripe's idempotency retention, before the local
schedule marker committed, needs operator review rather than adopting an unrelated schedule.

## Provider reference

- [Subscription schedules](https://docs.stripe.com/billing/subscriptions/subscription-schedules)
- [Migrate from a subscription](https://docs.stripe.com/api/subscription_schedules/create)
- [Update every retained phase](https://docs.stripe.com/api/subscription_schedules/update)

`from_subscription` is a separate creation call; phase modifications require a second update.
Existing discounts and settings must be preserved. No SDK or Stripe API version is upgraded.
