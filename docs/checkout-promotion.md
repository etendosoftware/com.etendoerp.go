# Checkout promotion contract

ETP-6216: new catalog monthly purchases pay the first monthly installment at checkout,
receive the second calendar month free, and resume ordinary monthly billing in month three.
Yearly prices already charge ten monthly installments for twelve months: no second discount.
Reactivations and historical purchases without a promotion snapshot are not enrolled.

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
