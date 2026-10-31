# Plan changes (ETP-5053)

How a tenant moves between plans at any point of the billing period: what applies now, what
waits for the period end, what it does to the quota year, and what the operator configures.
Builds on [`plan-quotas.md`](plan-quotas.md) (the yearly quota window) and on the subscription
lifecycle of ETP-5047 (the webhook path, the row model).

| For | Read |
|---|---|
| The quota window and where quotas refuse | [`plan-quotas.md`](plan-quotas.md) §1, §2 |
| The row model, `END_DATE` and the pending columns as decisions | [`open-and-notable-topics.md`](open-and-notable-topics.md) §5.5, §5.8, §5.13 |
| The product requirements | `schema_forge/docs/plans/2026-08-27-recurring-billing-and-resource-limits-prd.md` §8 |
| What the SPA shows | `schema_forge/docs/generated-custom-windows/app-shell-functional-flows.md` §3.2a-sexies |

---

## 1. The rules in one sentence each

- **Upgrade, same interval** (e.g. Core monthly → Pro monthly): applies **now**. The billing date
  does not move, the quota year does not restart, usage already counted this year carries over
  against the new, higher limit.
- **Monthly → annual**: applies **now**. Stripe restarts the billing cycle on the change date
  (the interval changed) and invoices the year upfront, crediting unused monthly time; the quota
  year restarts on that date too and usage starts from zero. The customer pays a full year
  upfront, so this is not an abuse path.
- **Annual → monthly**: not offered by default. Offering it is a configuration row (§2), not code.
- **Downgrade**: applies at the **end of the current billing period** (month or year). Nothing is
  deleted. The quota year and its usage carry over; if usage is already above the lower plan's
  limit, new creates of that resource are refused (quota `block` mode, NEO write path) until the
  next anniversary or a new upgrade, and existing data stays readable. The customer sees today's
  usage against the target plan's limits before confirming.
- **Upgrade then downgrade never resets usage.** Only a billing-interval change or a new
  subscription after a cancellation starts a new quota year.

## 2. Allowed plan changes — configuration, not code

**Plans** window (Classic) → **Allowed Plan Changes** tab (`ETGO_PLAN_CHANGE`), one row per
transition offered from the plan of the header:

| Field | Meaning |
|---|---|
| Target Plan | The plan the customer can move to |
| Timing | `immediate` (an upgrade, or monthly → annual) or `period_end` (a downgrade) |
| Active | An inactive row is no row: the change is not offered |

A change with no active row is **refused by the API** (`409 PLAN_CHANGE_NOT_ALLOWED`) and **not
offered** by the account page or the operator button. The direction is what the row declares,
never inferred from prices or names, so a mixed change (Scale monthly → Core annual) is settled
explicitly by whoever writes the row. A row whose target plan is inactive, or has no provider
price, is not offered either.

**Seeding.** The first `update.database` that creates the table seeds it from the environment's
own plan catalog (`SeedAllowedPlanChangesScript`, a module script): same interval, higher tier →
`immediate`; same interval, lower tier → `period_end`; monthly → annual of the same or a higher
tier → `immediate`; nothing from annual to monthly. The catalog has no tier column, so the seed
ranks active, priced plans by display price within their interval and currency — acceptable
once, at seed time, because the operator reviews the result in the tab. The seed runs **only into
an empty table**: one row present, active or not, and it does nothing, so operator decisions are
never overwritten or re-added.

## 3. Mechanics at the provider

All provider calls are made by `PlanChangeService`, through `StripeApiClient`.

**Upgrade (`immediate`).** `POST /v1/subscriptions/{sub}` with the item (`items[0][id]`) moved to
the target price and **`proration_behavior=always_invoice`**. This is the commercial choice this
module makes and documents: the difference is billed now, so the next invoice stays at the sticker
price and the UI has nothing to explain about it. **`billing_cycle_anchor` is never passed** (PRD
§8.2): it would move the customer's billing date forever. The call carries an idempotency key per
row and target (`plan-change:<row>:<plan>`), so a retried request is the same operation and there
is never a second proration. A monthly → annual change is the same call; Stripe resets the cycle
itself because the interval changed, which is what moves the quota year.

**Downgrade (`period_end`).** A Subscription Schedule from the subscription
(`POST /v1/subscription_schedules`, `from_subscription`), then its phases rewritten: the current
phase as it stands, then one iteration on the target price from `current_period_end`, with
`proration_behavior=none` and `end_behavior=release`. The row records the intention in
`PENDING_PLAN_ID` / `PENDING_EFFECTIVE_DATE`; the subscription keeps its plan until the boundary.
A second downgrade to another plan **updates the same schedule** (no second schedule); the same
target again answers `alreadyPending` and asks the provider nothing.

**A downgrade is pending and the customer upgrades.** The schedule is released
(`POST /v1/subscription_schedules/{id}/release`) and the pending columns cleared, then the item is
updated. One intention per subscription.

**Change requested while past due or canceled.** Refused before any provider call
(`409 SUBSCRIPTION_NOT_CURRENT`). A target plan with no provider price, or a provider that is not
configured, is `503 CHECKOUT_NOT_CONFIGURED`; a provider 4xx is `502 BILLING_PROVIDER_ERROR` with
the provider's error code in the message; a transport failure is `502` too.

**The prorated upgrade charge fails.** Not a failure here. The change already happened at the
provider; Stripe invoices the difference and, if the charge fails, moves the subscription to
`past_due`, which ETP-5047 handles. There is no rollback and no compensating transaction.

## 4. How the row follows

**Detection.** `customer.subscription.updated` carries the item's price
(`SubscriptionLifecycleApplier.itemPriceIdOf`). When it no longer matches the open row's
`PROVIDER_PRICE_ID`, the provider has applied a plan change — an upgrade made just now, or a
downgrade phase the schedule reached at the boundary. `SubscriptionPlanRollover` resolves the
target plan **by that price** (`PlanCatalogService.findByProviderPrice`) and rolls the row. A price
no active plan carries is logged at ERROR (`[BILLING-ALERT]`) and the event is otherwise applied
to the existing row; **nothing is guessed** until an operator attaches the price to its plan.
`subscription_schedule.*` events are not consumed: observability only, never a source of
entitlement. The billing reconciliation (ETP-5048) re-reads subscriptions through the same
applier, so it rolls a row the webhook missed.

**Rollover** (`SubscriptionService.rollToPlan`). The open row is closed at *now* by a targeted
statement (the same one a re-subscription uses: Hibernate flushes inserts before updates, and
`etgo_sub_open_envclient_uq` would reject the successor while the predecessor still reads as open)
and the successor is inserted in the same transaction: same tenant, account, Stripe ids, status,
billing period, grace anchor, watermark and cancel flag; a new price snapshot from the item's
price; pending columns cleared; and the **quota-year anchor**:

- an upgrade or a downgrade: the predecessor's anchor (`TenantQuotas.quotaYearAnchorOf`:
  `QUOTA_YEAR_START`, else `START_DATE`), so usage carries over;
- a billing-interval change: the new provider `current_period_start` the event reports (Stripe
  restarted the cycle), so the year restarts with the billing;
- the same plan under a new price id (a rotated price): the row's snapshot is updated, nothing
  rolls.

An upgrade requested through the API applies the provider's answer **at once** through the same
path (a synthetic `customer.subscription.updated` into
`TenantEnvironmentLifecycleService.applySubscriptionEvent`), so the new plan is visible before the
webhook arrives; the webhook then finds the price already matching and rolls nothing. The
60-second `QuotaClientCache` means the write-path check sees the new limits within a minute.

**`END_DATE` stays past tense** (register §5.13, option 1). Every writer sets it to now; a
closing instant more than five minutes ahead of the clock is refused
(`SubscriptionService.requireNotFuture`). A future intention lives only in the pending columns.

## 5. Surfaces

**API** (`EtendoGoJwtServlet`, platform credential):

- `GET /sws/go/billing/subscription` now carries `quotaYearStart` (`yyyy-MM-dd`) and
  `pendingChange` (`{ planKey, planName, billingInterval, displayPrice, currency, effectiveAt,
  timing }` or null).
- `GET /sws/go/billing/subscription/plan-changes[?clientId=]` — the offered changes of the shown
  subscription (or the named environment's, ownership as for the portal): `currentPlan`,
  `subscriptionStatus`, `pendingChange`, and `changes[]`, each a plan with `timing`,
  `intervalChange` and `quotas[]` — today's usage measured against the **target** plan's limits
  (the page's own `QuotaItem` shape).
- `POST /sws/go/billing/subscription/plan-change` `{ planKey, clientId? }` — answers the target
  plan with `timing`, `effectiveAt` and `alreadyPending`; refusals as in §3.

**Account page** (`/account`, `SubscriptionSection` + `PlanChangeDialog`): a **Change plan**
action on a renewing subscription with a catalog plan; the dialog offers only the listed targets,
states the direction, the timing and the quota consequence (every exceeded quota with its numbers)
before confirming, and the page shows a persistent "Your plan changes to X on <date>" line while a
downgrade is pending. Strings in `en_US.json`, `es_ES.json`, `es_AR.json`; amounts through
`formatCurrency`, dates through `formatCalendarDate`.

**Classic** (operators): the **Allowed Plan Changes** tab (§2); on the **Subscription** window the
**Quota Year Start**, **Pending Plan** and **Pending Effective Date** fields (read-only) and the
**Change Plan** button (`ETGO_SubscriptionPlanChange`, a process definition on the open row), which
runs exactly the customer's change through the same service, for assisted cases.

## 6. Out of scope (and where it would go)

Refunds and credit-balance handling; annual → monthly (a configuration row, plus a product
decision about its quota year); a configurable quota-year behaviour on plan change (follow-up);
overage charging.
