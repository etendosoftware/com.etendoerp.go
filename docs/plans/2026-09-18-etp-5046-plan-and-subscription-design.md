# ETP-5046 — Subscription Plan Catalog and per-tenant subscription: design

Status: implemented on `feature/ETP-5046`. Supersedes the `ETGO_TenantPlan` `AD_Preference`
marker as the source of truth for whether a tenant is paying — **per environment, from the moment
its operator switches it on** (§6.3). Until then an environment keeps recording new purchases with
the preferences, exactly as develop does.

Governing PRD: `schema_forge/docs/plans/2026-08-27-recurring-billing-and-resource-limits-prd.md`.
AD authoring record: `2026-09-18-etp-5046-stage-b-ad-handoff.md`.

---

## 1. What changed and why

Before this ticket the commercial state of a tenant was one `AD_Preference` row
(`ETGO_TenantPlan` = `free` | `productive`) and there was exactly **one** purchasable thing in
the entire product: the deployment property `etendo.go.checkout.price.id`. Selling a second
plan, or changing a price, meant editing a property file and redeploying.

Now there is a **Subscription Plan Catalog** (`ETGO_PLAN` + its `ETGO_PLAN_QUOTA` children) that
commercial edits from Etendo Classic, and a per-tenant subscription record (`ETGO_SUBSCRIPTION`)
that says who is on which plan, in which status, for which period, at which price.

> **Never write a bare "catalog" for `ETGO_PLAN`.** This module has *four* of them and the short
> form is ambiguous:
>
> | Catalog | What it holds |
> |---|---|
> | **Subscription Plan Catalog** (`ETGO_PLAN`) | what can be **bought** |
> | billing resource catalog (`ETGO_BILLING_RESOURCE`, ETP-5050) | what can be **metered** |
> | message catalog (`AD_MESSAGE`) | translated user-facing copy |
> | data-fix catalog (`run.js`) | the ordered set of `.sql` fixes |
>
> `ETGO_PLAN_QUOTA` joins the first two. Write **"Subscription Plan Catalog"** at the defining
> mention in a document, class or javadoc block and **"plan catalog"** thereafter — in prose and in
> javadoc alike. In code the identifiers already say it: `PlanCatalogService`, `planCatalogService`.

---

## 2. Changing a plan's price does NOT re-price existing subscribers

**This is the single most important operational fact in this feature. Read it before editing a
plan.**

Editing `ETGO_PLAN.PROVIDER_PRICE_ID` affects **new checkouts only.** Every existing subscriber
keeps the price their subscription was created with. Their next invoice does not change.

Two mechanisms enforce it, one on each side:

- **At Stripe**, a Price object is immutable and an existing Subscription Item keeps pointing at
  the Price it was created with. Changing a plan does not touch any existing subscription.
- **In Etendo**, `ETGO_SUBSCRIPTION` carries its **own** `PROVIDER_PRICE_ID`, `SNAPSHOT_AMOUNT`
  and `SNAPSHOT_CURRENCY`, copied from the plan when the subscription is opened and **never
  rewritten when the plan is re-priced**. That makes the guarantee locally visible and locally
  assertable, rather than something you have to trust Stripe for.

This is the correct default: customers are grandfathered rather than silently re-priced. If you
edit a plan's price expecting everyone's next invoice to change, **you will be wrong, and
nothing will warn you.**

Re-pricing an existing subscriber is a *plan change*, which is ETP-5053 — and in this model a
plan change does not overwrite the row. It **closes** the current subscription (sets `END_DATE`)
and **inserts a new one** with the new plan and price, so the tenant keeps a full price history.

---

## 3. Three invariants that look like bugs and are not

### 3.1 A plan with zero quota rows is UNLIMITED

The absence of an `ETGO_PLAN_QUOTA` row *is* the unlimited case. There is no sentinel value, no
`NOT NULL DEFAULT 0`, and no auto-created row.

`ETGO_PLAN_QUOTA.INCLUDED_QTY` is `required="true"` with **no default**, in both the DDL and
`AD_COLUMN.DEFAULTVALUE`. This deliberately breaks the module's own pattern — every comparable
required DECIMAL here (`ETGO_USAGE_DAILY.QTY`, `ETGO_CHECKOUT_REQUEST.PROVISIONING_ATTEMPTS`,
`ETGO_BILLING_EVENT.DUPLICATE_COUNT`) carries `<default>0</default>`. **Do not "fix" it to match
its siblings.** A default of zero caps every resource on every plan at zero the moment a quota
evaluator goes live. An AD `DEFAULTVALUE` is just as dangerous as a DDL default: it pre-fills a
`0` into a quota row an operator opened merely to look at.

`ETGO_PLAN_QUOTA` is also the only new table with `ISDELETEABLE='Y'`, because deleting the last
quota row is how an operator *restores* the unlimited state.

> **ETP-5051 must read this.** When the quota evaluator is written, it must treat "no row" as
> unlimited. A `LEFT JOIN ... COALESCE(included_qty, 0)` silently caps every unquota'd resource
> at zero. `PlanQuotaSchemaInvariantTest` guards the schema side; nothing can guard the
> evaluator except this paragraph.

### 3.2 "Productive" means an open subscription row exists — nothing else

`TenantPlanService.resolvePlan` returns `productive` when the tenant has an open subscription
whose status is `active` or `past_due`. It does **not** look at the plan's price.

The grandfathered `legacy-productive` plan has **no** `PROVIDER_PRICE_ID` by design. Keying
"productive" on the presence of a price id would flip every backfilled tenant to free the instant
it shipped. This is the most dangerous possible mis-implementation of this ticket.

`resolvePlan` also keeps three contractual properties from its preference-backed predecessor:
**never null, never throws, degrades to `free`.** `OnboardingForceTestModeService` compares its
result to `PLAN_FREE` with no null guard.

### 3.3 `STRIPE_SUBSCRIPTION_ID` is NOT unique

A plan change (ETP-5053) updates the item on the **same** Stripe subscription — the billing
anchor is preserved, so the Stripe id does not change — while opening a **new** Etendo row. Two
local rows therefore legitimately share one `STRIPE_SUBSCRIPTION_ID`.

What *is* unique is "one open subscription per tenant", expressed as a **partial unique index**:

```sql
CREATE UNIQUE INDEX etgo_sub_open_envclient_uq
    ON etgo_subscription (environment_client_id)
 WHERE isactive = 'Y' AND end_date IS NULL;
```

ETP-5047 correlates inbound webhooks by `STRIPE_SUBSCRIPTION_ID` and must resolve to the **open**
row (`END_DATE IS NULL`), not to "the" row.

---

## 4. Dates: three pairs, three jobs

| Columns | Meaning |
|---|---|
| `START_DATE` / `END_DATE` | Lifetime of **this subscription record**. `END_DATE` null ⇒ this is the open row. A plan change closes one row and opens the next. |
| `CURRENT_PERIOD_START` / `CURRENT_PERIOD_END` | Stripe's rolling monthly billing window. Moves every cycle. |
| `PENDING_PLAN_ID` / `PENDING_EFFECTIVE_DATE` | ETP-5053 only. Shipped nullable and hidden; nothing in ETP-5046 reads or writes them. |

Do not conflate the first two. The record lifetime is not the billing period.

---

## 5. Price derivation — the display price is never typed

`ETGO_PLAN.DISPLAY_PRICE`, `CURRENCY_CODE` and `PRICE_SYNCED_AT` are **derived from the Stripe
Price object** by `PlanPriceDerivationHandler`, a CDI `EntityPersistenceEventObserver` on the
`Plan` entity. They are `ISREADONLY='Y'` in the Classic window, and a hand-typed value is
reverted on save. A customer seeing one amount while being charged another is the worst outcome
this feature can produce.

`ETGO_PLAN_PRICED_CHK` enforces the floor at the database level: no price id ⇒ no amount and no
currency.

### 5.1 Two branches that make no Stripe call, both load-bearing

1. **Blank `PROVIDER_PRICE_ID`** → the derived columns are nulled. Zero calls. This is the
   grandfathered plan.
2. **Update where the price id did not change** → the stored values are re-asserted from previous
   state. Zero calls.

Branch 2 is not an optimisation. Without it every unrelated edit — renaming the plan, toggling
`ISACTIVE`, adding a quota row from the child tab — issues an HTTP call to Stripe, and the Plans
window becomes unusable whenever Stripe is unreachable. It also delivers the never-hand-typed
guarantee without a round trip.

### 5.2 A network blip must not look like a bad price id

`StripeTransportException` is thrown **only** when the call did not complete (timeout, DNS, TLS,
5xx, 429). A completed 4xx returns a `StripeResponse`. That split is the entire mechanism
separating "Stripe is unreachable" from "that price does not exist", and each outcome has its own
`AD_MESSAGE`:

| Condition | Message |
|---|---|
| 404 + `resource_missing` | `ETGO_PlanPriceNotFound` |
| 401 / 403 | `ETGO_PlanPriceUnauthorized` |
| other 4xx | `ETGO_PlanPriceRejected` |
| transport failure | `ETGO_PlanPriceUnreachable` — *the plan was NOT saved; the id may still be valid* |
| non-recurring price | `ETGO_PlanPriceNotRecurring` |
| archived price on an active plan | `ETGO_PlanPriceArchived` |
| `interval_count != 1` | `ETGO_PlanIntervalCountUnsupported` |
| declared interval ≠ provider interval | `ETGO_PlanIntervalMismatch` |

Fail-closed throughout: an unverifiable price never saves.

### 5.3 Minor units

Stripe amounts are in the currency's minor unit and **the exponent is not in the API response**,
so `StripeCurrencyScale` holds it as a table: 0 for JPY/KRW/CLP/…, 3 for BHD/JOD/KWD/OMR/TND,
else 2. Conversion is `BigDecimal.movePointLeft(exponent)` — never `/100`, never a double. This
is why `DISPLAY_PRICE` is `DECIMAL(20,4)` and not `(20,2)`: a Kuwaiti dinar price does not fit
two decimals.

---

## 6. Checkout: the browser sends a plan key, never a price

The client sends `planKey`. The server resolves it to a provider price id through
`PlanCatalogService`, validates that price against Stripe (`StripePriceService.retrievePrice`:
active, amount, currency, `interval_count = 1`) and derives the checkout mode from the price's
interval. There is no request field for a price and no code path that reads one, so a body
containing `"priceId"` is *ignored, not validated*. The price id actually charged is stored on the
checkout request (`STRIPE_PRICE_ID`) next to the plan (`ETGO_PLAN_ID`), and a reopened checkout
charges that stored price — never the plan's current one — through develop's idempotent
reopen path (initial / replacement `Idempotency-Key`, open session reused, completed session
reported).

- Unknown or inactive key → `400 PLAN_NOT_AVAILABLE`. The response deliberately does **not**
  distinguish the two: the endpoint must not confirm which keys exist.
- Valid, active non-legacy key with no provider price → `503 CHECKOUT_NOT_CONFIGURED`.
- `legacy-productive`, or no key at all → the **legacy price fallback** below, or
  `400 PLAN_NOT_AVAILABLE` when it is inactive.

`CheckoutConfiguration.isConfigured()` proves only that Stripe credentials exist (secret key +
webhook secret). It used to also prove that *a purchasable thing existed*; that guarantee moved to
the Subscription Plan Catalog plus the fallback. **This is the easiest thing in the ticket to lose
silently in review.**

### 6.1 The legacy price fallback

`etendo.go.checkout.price.id` was originally deleted "with no fallback" — a fallback price is a
price nobody reviewed, selected exactly when the intended configuration is missing. Merging with
develop reversed that, deliberately and narrowly: the property survives as a **transitional
fallback**, and one predicate decides it.

`PlanCatalogService.isLegacyFallbackActive()` is **true iff**

1. `CheckoutConfiguration.priceId()` (`etendo.go.checkout.price.id` / `ETGO_CHECKOUT_PRICE_ID`) is
   non-blank, **and**
2. no active plan catalog row carries a provider price id (`listPurchasablePlans()` is empty).

While it holds:

- `GET /sws/go/plans` lists exactly `legacy-productive`, with `displayPrice` / `currency` /
  `billingInterval` read from Stripe via `StripePriceService.retrieveConfiguredPrice()` — never
  from `GET /sws/go/billing/offers`. If Stripe cannot quote it, the plan is left out (logged),
  never a 500. Its `description` is sent **empty**: the row's `DESCRIPTION` documents the fallback for
  operators (it names `etendo.go.checkout.price.id`) and is not buyer copy; the row is unchanged.
- A checkout naming `legacy-productive`, or naming no plan, is sold at that configured price, and
  the request records `legacy-productive` as its plan and the configured price id as its
  `STRIPE_PRICE_ID`. The subscription opened after payment snapshots that charged price id (the
  grandfathered plan has none of its own); its amount/currency snapshot stays empty.

The plan list and checkout call the **same** predicate, so the list can never offer something
checkout refuses — except across the moment the predicate flips (next paragraph).

**It retires itself.** The first priced plan an operator creates makes condition 2 false on the
next request: the list shows the priced plan(s), and `legacy-productive` / a missing key answer
`400 PLAN_NOT_AVAILABLE`. No redeploy, no property change. A buyer holding a page loaded before
the flip gets that 400; the page maps it to "the plan is no longer available, reload the page"
(`upgradePlanNotAvailable`).

**What it removes.** The two deploy-ordering requirements this section used to impose — "a priced
`ETGO_PLAN` row must exist before the code goes live" and "the module and the app-shell must ship
together" — no longer hold while a legacy price is configured: a deployment with no priced plan
keeps selling, and an app-shell older than the module (which sends no `planKey`) still buys.
They return only on an environment that has neither a priced plan nor the legacy property.

**What it costs**, recorded in `open-and-notable-topics.md` §2.1: fallback buyers land on
`legacy-productive`, which has no quota rows and is therefore **unlimited**; the plan list shows the
grandfathered plan's own name (with an empty description, see above); and
`GET /sws/go/billing/offers` always quotes the configured legacy price
(`BillingOfferConfiguration` → `retrieveConfiguredPrice()`), never the plan catalog — identical to
the charged price while the fallback is active, a different price once a priced plan has retired it,
and a `503 BILLING_OFFER_UNAVAILABLE` once the property is removed. The upgrade page quotes the plan
catalog and falls back to the offer only while that lookup is in flight or has failed.

### 6.2 Going live — operator checklist

The order below is the whole procedure; nothing else has to be redeployed. Steps 1–2 are the deploy
and change nothing for anyone; step 3 is the one-way switch to the subscription model and is
optional — an environment may stay on the preference path indefinitely.

1. **Deploy the module** (`update.database`). Its module script `EnsureLegacyPlanScript` creates
   the `legacy-productive` row if it is missing (§7); no plan carries a price yet. The module ships
   the list value `ETGO_SubscriptionModel` but **no preference row**, so the environment comes up
   with the subscription model OFF (§6.3).
2. **Keep selling as before.** With `etendo.go.checkout.secret.key`,
   `etendo.go.checkout.webhook.secret` and `etendo.go.checkout.price.id` set, and no priced plan,
   the legacy price fallback (§6.1) is active: `GET /sws/go/plans` lists `legacy-productive` at the
   configured Stripe price and checkout sells exactly that. No `ETGO_PLAN` edit is needed for this.
   While the switch is OFF every new purchase is recorded exactly as on develop: the
   `ETGO_TenantPlan` marker is written, no `ETGO_SUBSCRIPTION` row is opened, and the lifecycle
   webhooks keep writing `ETGO_SubscriptionStatus` / `ETGO_SubscriptionDueAt`. The checkout request
   still records the plan and the charged price.
3. **Switch the environment to the subscription model — once, when ready.** In this order:
   1. Re-verify the R37 `@report` pre-check (production Stripe checkout has not gone live since
      2026-08-27) against the **target** environment — `open-and-notable-topics.md` §2.4.
   2. Bring every tenant's data-fix chain current: `make data-fixes` in `schema_forge`. R37 is
      **opt-in** (`-- @optIn: true`): a full run never applies it, and `FIX=` refuses a tenant whose
      chain is behind R37's date (`BLOCKED_CHAIN_BEHIND`) — this is what keeps the ordering
      invariant of §8.2 true now that R37 is no longer visited in chain order.
   3. Run R37 explicitly, dry run first:
      `make data-fixes FIX=R37-tenant-subscription-backfill DRY_RUN=1`, then
      `make data-fixes FIX=R37-tenant-subscription-backfill`. Every productive tenant gets its open
      `legacy-productive` row and loses its marker in the same transaction (§7, §8).
   4. **Set the system preference** — Classic **Preference** window as System Administrator:
      Property `ETGO_SubscriptionModel`, Value `Y`, Client `System`, Organization `*`, no
      Visible-at Client other than System, no role, user or window, and **no Module**. From the
      next purchase on, a new tenant opens an `ETGO_SUBSCRIPTION` row and writes no marker. No
      restart: the switch is read on every purchase.
   5. Verify:
      ```sql
      -- every tenant APPLIED or SKIPPED_NOT_NEEDED; FAILED means the legacy-productive plan row is
      -- missing (R37 header, "Why @apply ABORTS") — fix it and repeat 3.3
      select remediated_client_id, status, rows_affected, detail
        from etgo_data_fix_history
       where fix_id = '20261005T180000Z__R37-tenant-subscription-backfill'
       order by status;
      -- must be 0: every active productive marker has become an open subscription row
      select count(*) from ad_preference
       where attribute = 'ETGO_TenantPlan' and isactive = 'Y'
         and upper(trim(value)) = 'PRODUCTIVE';
      -- after 3.4: exactly ONE row, and trim(value) = 'Y' -- the switch is ON
      select ad_preference_id, value, created, updated from ad_preference
       where property = 'ETGO_SubscriptionModel' and ispropertylist = 'Y' and isactive = 'Y'
         and ad_client_id = '0' and coalesce(visibleat_client_id, '0') = '0'
         and coalesce(visibleat_org_id, '0') = '0' and visibleat_role_id is null
         and ad_user_id is null and ad_window_id is null
       order by created desc, updated desc, ad_preference_id desc;
      ```
      The last query is the same selection `SubscriptionModelSwitch` makes, and like it does not
      filter on the value: the reader takes the newest matching row and trims it, so a second row
      or an `N` shows up here instead of hiding. It does not filter on `ad_module_id` either — the
      reader ignores the module. "No Module" in 3.4 is not about the switch's answer but about
      survival: a row assigned to a module in development is exported with it and reset by the next
      `update.database` (§6.3).
   Run 3.3 **before** 3.4. The reverse order works too (rows are decided by the row, preferences by
   the fallback) but opens a window in which new tenants have rows while the existing fleet does
   not — a half-switched environment nobody chose.
4. **Switch to the plan catalog when ready.** In the Classic **Plans** window, create (or activate)
   a plan and enter its **Provider Price ID** — an active, recurring Stripe price with
   `interval_count = 1`. Display price, currency, billing interval and sync time are derived from
   Stripe on save (§5); a typed value is reverted, and an unverifiable price refuses the save. The
   next request retires the fallback: the plan list shows the priced plan(s), and
   `legacy-productive` / a request with no plan answers `400 PLAN_NOT_AVAILABLE`. This step does
   not need step 3, but only with the subscription model on does a buyer get a row naming the plan
   they bought; with it off the plan is recorded on the checkout request only.
5. **Afterwards**, `etendo.go.checkout.price.id` may be removed. Its remaining reader is
   `GET /sws/go/billing/offers`, which then answers `503 BILLING_OFFER_UNAVAILABLE` — harmless for
   the upgrade page (it quotes the catalog), visible to any other consumer of the offer. Buyers who
   purchased through the fallback stay on `legacy-productive` (unlimited, their charged price id
   snapshotted) until a plan change exists (ETP-5053).

### 6.3 The subscription-model switch (`ETGO_SubscriptionModel`)

Decided 2026-10-14. One environment-level, database-stored switch decides whether a **new** paid
purchase opens an `ETGO_SUBSCRIPTION` row. `com.etendoerp.go.payment.SubscriptionModelSwitch#isOn`
is the only place that answers it; `EtendoGoJwtServlet#applyPaidUpgradeSideEffects` asks it once per
purchase, before it opens anything.

| Switch | What a new purchase records | Lifecycle webhooks then write |
|---|---|---|
| **OFF** (default: no row, or any value but `Y`, or an unreadable row) — tenant with **no** subscription row | the `ETGO_TenantPlan` marker (`markProductive`), no row, nothing retired — logged at INFO, not as a failure | the `ETGO_SubscriptionStatus` / `ETGO_SubscriptionDueAt` preferences (the tenant has no row) |
| **OFF** — tenant that already has **any** row (open or closed) | a row, exactly as ON: its row is what every read answers from | the row |
| **ON** (`Y`) | an open `ETGO_SUBSCRIPTION` row, and the tenant's marker is retired; the marker is written only if the row cannot be opened (§8) | the row (`SubscriptionService#applyLifecycleStatus`) |

**The row it reads.** A *list* preference: `ETGO_SubscriptionModel` is an `AD_Ref_List` value of the
`Property Configuration` reference (`AD_REF_LIST` `543C9E4BD9DB44A1AB41A575E0949CA2`), so it is
stored in `AD_Preference.Property` with `ISPROPERTYLIST = 'Y'` and the Preference window offers it
in its drop-down. Only an active row at `AD_CLIENT_ID = '0'` with no visibility narrower than the
system — Visible-at Client empty or `0`, Visible-at Organization empty or `0`, no role, no user, no
window — counts. If there are several, the first in `created desc, updated desc, ad_preference_id
desc` wins: the newest row, then (two rows created in one transaction share `now()`) the most
recently updated, then the id, so the winner is always deterministic. The §6.2 verification query
sorts the same way, so its first row is the one the reader uses. A row scoped to one tenant is not an environment decision
and is ignored (`ETGO_TenantPlan` is the cautionary tale: client-`0` rows that are *about* one
tenant). The reader uses a plain criteria on the row's own `AD_CLIENT_ID`, like
`FiscalTestModeResolver`, not `Preferences.getPreferenceValue`.

**Not module data, on purpose.** The module ships the list value only. A preference row shipped as
sourcedata would be reset to its shipped value by every `update.database`, silently switching an
environment back. The operator's row has no module and survives deploys.

**Read per purchase, never cached.** A purchase is rare, so the switch costs one query each time and
flipping it needs no restart. A failed read is logged at ERROR and answered OFF — the pre-ETP-5046
path, which is the safe direction.

**Only a tenant's first row is switched.** A tenant that has a row — any row, open or closed,
`SubscriptionService#hasAnySubscription` — is decided by it whatever the switch says, for reads
(`resolvePlan`, the access policy, the lifecycle webhooks) **and for writes**: with the switch OFF
its next payment still goes to a row, because a payment recorded only in the marker would be
invisible behind a canceled or closed row and the paying tenant would stay blocked. The lookup is
"any row", not "an open row", so it stays correct whatever later tickets change about which row is
current; it runs only when the switch is OFF. A rowless tenant is decided by the switch, and read
through the transitional fallback (§8). `openSubscription` has exactly one runtime caller — the
paid upgrade — so the gate there covers every row a tenant can get at runtime; R37 is the
operator's own step 3.3 of §6.2.

**A failed row lookup takes the marker path — on purpose.** With the switch OFF, if
`hasAnySubscription` itself fails the purchase is recorded with the marker and the failure is logged
at ERROR. For a tenant that did have a row that payment would be invisible behind it. Accepted
because the case is near-unreachable: a purchase always provisions a NEW tenant (ETP-5548), which
has no row, and a SQL-level failure has already marked the onboarding transaction for rollback, so
the upgrade fails closed and is retried. Failing every OFF purchase on a lookup error would be the
worse trade. To revisit when ETP-5047 is merged (register §2.9): there a tenant whose only row is
closed reads as canceled, and a payment that reached it through this path would leave it canceled.

**One-way in practice.** Turning the switch OFF again sends only tenants that have never had a row
back to the marker; every tenant with a row stays on it, payments included. The fleet is then two
populations that only grow apart, and Phase F moves further away, so turning it back OFF undoes
nothing — treat the switch as one-way.

**The fallback's log level follows the switch.** `TenantPlanPreferenceFallback` logs one line per
resolution of a rowless productive tenant (§8.1), with the grep marker
`ETP-5046-TRANSITIONAL-FALLBACK`. While OFF that is every paying tenant, on purpose, so the line is
**DEBUG** ("the subscription model is off in this environment, so this is the intended path"). While
ON a tenant without a row is unexpected, so it is **WARN** ("… has no open ETGO_SUBSCRIPTION row …").
The "silence" half of §8.1's end condition therefore means "no WARN" and only exists once the switch
is on. Cost: the switch is read only when the fallback actually serves a tenant — once per
`isProductive` resolution, once per `productiveAmong` batch — never for a tenant with a row or
without a marker; the serving path already cost one `AD_Preference` query and adds one more.

## 7. Backfill

Every tenant carrying the `productive` preference gets one open subscription row on the
grandfathered `legacy-productive` plan, with the Stripe customer and subscription ids — and the
Stripe price id actually charged (`STRIPE_PRICE_ID`, recorded on requests since develop's ETP-5463)
into `PROVIDER_PRICE_ID` — copied from its latest paid `ETGO_CHECKOUT_REQUEST` row where one
exists (NULL otherwise; `SNAPSHOT_AMOUNT`/`SNAPSHOT_CURRENCY` always stay NULL, since the request
stores no amount), **and retires that tenant's now-stale `ETGO_TenantPlan` preference in the
same transaction** (§8). Delivered as `20261005T180000Z__R37-tenant-subscription-backfill.sql`
under `schema_forge/cli/src/data-fixes/sql/` — re-dated from `20260918T120000Z` during the develop
merge, see §7.3. **Opt-in since 2026-10-14** (`-- @optIn: true`): a full `make data-fixes` never
runs it; the operator runs it with `FIX=` as step 3.3 of §6.2, at the moment the environment
switches to the subscription model (§6.3). `@check` selects a tenant on either of two grounds: **(A) backfill** — an active
productive marker and no open subscription; **(B) retirement** — any `ETGO_TenantPlan` row and any
subscription row, open or closed (added for develop's R42, §8). Re-running creates zero rows and
retires nothing: (A) turns false because the inserted row is open and the marker is gone, (B)
because every marker of a subscribed tenant is gone.

The `legacy-productive` plan itself is created by the **module script**
`EnsureLegacyPlanScript` (`src-util/modulescript/`) on every `update.database` — an idempotent
insert of the fixed id `219D5C8E15C64E97B2F553B228D30DD0` that runs only when no row carries that
key or id — not by a companion data-fix. It first shipped as module sourcedata
(`sourcedata/ETGO_PLAN.xml` + an `AD_DATASET_TABLE` row); that was withdrawn because a dataset
table is module-owned as a whole: runtime plans made plain `update.database` refuse with "local
changes", and `-Dforce` deleted them (`open-and-notable-topics.md` §2.8). `ETGO_PLAN` must never
be an AD dataset table again. A `--client 0` companion fix was rejected: if an operator forgot to run it first, the
per-tenant `@check` would return 0 rows, the runner would record `SKIPPED_NOT_NEEDED` — a status
that **advances the watermark** — and every paying tenant would be silently and permanently
reclassified as free.

### 7.0 Status and grace anchor are carried over, not reset

A backfilled row is not always `active`. Until the row exists, develop's lifecycle webhooks
(ETP-5443) project a productive tenant's billing state into two preferences —
`ETGO_SubscriptionStatus` (`CURRENT` / `PAST_DUE` / `EXPIRED`) and `ETGO_SubscriptionDueAt` (an
`Instant.toString()` value, the end of the paid period). Once the row exists it **wins** over both
(`TenantEnvironmentLifecycleService#productiveSnapshot` reads the row first), so a flat `active`
would silently turn a past-due or expired tenant back into a paying one. R37 therefore seeds:

| `ETGO_SubscriptionStatus` | `STATUS` written | read back as |
|---|---|---|
| `CURRENT` | `active` | `CURRENT` |
| `PAST_DUE` | `past_due` | `PAST_DUE` |
| `EXPIRED` | `canceled` (still open, `END_DATE` NULL — §3.7 of the open-topics register) | `EXPIRED` |
| `NONE` | `canceled` | `EXPIRED` — same access decision as `NONE` (`SUBSCRIPTION_REQUIRED`) |
| absent, blank, `LEGACY_ENTITLEMENT` or unknown | `active` | `CURRENT` (the preference reader's own fallback is `LEGACY_ENTITLEMENT`, also entitled) |

The rule behind every line: **the backfill preserves the tenant's current effective access, it
never improves it.** `NONE` locks a tenant out, so it becomes the row status that still locks it
out; mapping it to `active` would silently re-open that tenant.

`CURRENT_PERIOD_END` comes from `ETGO_SubscriptionDueAt`, cast only when the value has the ISO-8601
UTC shape `Instant.toString()` writes; anything else becomes NULL, mirroring the Java reader's
"ignore an invalid due timestamp", so a cosmetic value never fails a tenant. `CURRENT_PERIOD_START`
stays NULL, so `ETGO_SUB_PERIOD_CHK` can never reject the insert.

**Time zone assumption.** `CURRENT_PERIOD_END` is a `TIMESTAMP` without zone. The
`CAST(... AS timestamptz)` fixes the instant, and storing it renders that instant in the **database
session's** time zone (the server `timezone` setting, for the data-fix runner); Java reads it back
through `Date`/`Timestamp` in the **Tomcat JVM's** default zone. The two agree only when the DB
server `timezone` equals the JVM zone — an assumption Etendo already makes for every `TIMESTAMP`
column, and the one the webhook's own `Date.from(Instant)` write relies on. Where they differ, the
seeded grace anchor is off by the offset between the zones (a UTC server under a UTC-3 JVM shifts
the end of the paid period by 3 hours). The runner is deliberately not changed for this.

Unlike the plan marker (§7.1), both lifecycle preferences are **owned** by the tenant
(`AD_CLIENT_ID = tenant`, written by `setPreferenceValue` with `setClient(tenant)` and read back
through `PROPERTY_CLIENT`), so R37 reads them by `ad_client_id`. It does not delete them:
`ETGO_SubscriptionEventAt` (the webhook ordering watermark) stays a preference by decision, and the
status/due-at pair is simply no longer read once the row exists. Pinned by
`SubscriptionBackfillIdempotencyIntegrationTest` (runs the real SQL) and the R37 source test.

### 7.1 The preference is NOT scoped by `AD_CLIENT_ID`

`Preferences.setPreferenceValue` writes the `ETGO_TenantPlan` row at `ad_client_id = '0'` and puts
the tenant in **`VISIBLEAT_CLIENT_ID`**. `TenantPlanService` reads it back through
`PROPERTY_VISIBLEATCLIENT`, and the sibling fixes R31/R32 filter the same way.

Filtering the backfill on `ad_preference.ad_client_id = :client_id` matches **zero rows for every
tenant** — verified against live data, where all productive tenants have `ad_client_id = '0'`.
That produces the silent-skip catastrophe described above. Any future query against this marker
must use `visibleat_client_id`.

### 7.2 The abort guard cannot be written the obvious way

`SELECT 'message'::integer WHERE NOT EXISTS (...)` does **not** work in PostgreSQL. A
`text→integer` cast with a constant argument is immutable, so the planner constant-folds it and
raises at *plan* time, ignoring the `WHERE` entirely — every tenant would go `FAILED` forever.
Concatenating a column into the message makes the argument row-dependent, so it is evaluated only
for rows that pass the qual.

The guard exists because an error records `FAILED`, which does **not** advance the watermark, so
the tenant is retried. Inserting zero rows would record `APPLIED` and lose the tenant forever.

### 7.3 The file date is load-bearing

The runner applies, per tenant, only fixes **strictly newer** than the newest `PROCESSED` fix
(`run.js`, `<=` skip, no look-back). R37 was authored as `20260918T120000Z`; by the time ETP-5046
merged, develop carried fixes up to `20260922T130000Z`, one of them
(`R38-org-legalentity-pointer`) with the *identical* `20260918T120000Z`. On any environment that
had processed those, R37 would have been skipped silently — no ledger row, no error. It was renamed
to `20260924T150000Z` before reaching a shared environment (renaming an *unapplied* fix is allowed;
`sql/README.md` rule 3 forbids it only once applied). When develop was merged into ETP-5046 again on
2026-10-05 it carried fixes up to `20261005T120000Z` (`R46-acct-rpt-definitions-redelivery`), so R37
was re-dated a second time, to `20261005T180000Z`.

`schema_forge/cli/test/data-fixes-catalog-ordering.test.js` guards it from two sides: R37 must sort
strictly after `NEWEST_DEVELOP_FIX_AT_MERGE` (`20261005T120000Z__R46-acct-rpt-definitions-redelivery`,
frozen — later fixes are expected to land after R37), and no two fixes in the catalog may share a
timestamp prefix, except seven already-applied pairs frozen by exact file name
(`APPLIED_SHARED_TIMESTAMPS`; applied fixes can never be renamed). The guard cannot see a fix dated
*before* the newest one already processed on some environment — that part of the trap is still the
author's job, see `sql/README.md` "Choosing the timestamp".

Since R37 became opt-in the watermark no longer *skips* it — a full run never visits it — but the
date still matters: `FIX=` applies an opt-in fix only to a tenant whose watermark has reached the
fix's own date, and refuses the others as `BLOCKED_CHAIN_BEHIND`. Its ledger rows never move a
tenant's watermark.

---

## 8. Cutover: per-tenant retirement, not a flag day

**One environment-level moment comes first: the switch (§6.3).** Until an environment's operator
runs R37 and sets `ETGO_SubscriptionModel = Y` (§6.2 step 3), nothing below happens there — new
purchases write the marker and open no row, as on develop. Everything in this section describes an
environment that has switched.

**The `ETGO_TenantPlan` preference is retired PER TENANT, at the moment that tenant gains a live
subscription — never fleet-wide on a flag day.** A tenant's subscription row appearing and its
preference row disappearing are the same transaction, on both paths:

| Path | Who moves the tenant | Mechanism |
|---|---|---|
| Tenants that predate the subscription model — and any tenant that already has a row | `R37-tenant-subscription-backfill` | Statement 3 of `@apply`: `DELETE FROM ad_preference` scoped by `visibleat_client_id`, guarded on **any** subscription row existing for the tenant (open or closed, active or not) — same transaction as the `INSERT` when one runs |
| Tenants that pay after the switch (§6.3) | `EtendoGoJwtServlet#applyPaidUpgradeSideEffects` | On a successful subscription write it calls `TenantPlanService#retireProductivePreference` instead of `markProductive` — it retires right after opening the row, never later. With the switch OFF it opens no row and calls `markProductive`, as on develop — unless the tenant already has a row, which then records the payment as when ON (§6.3) |

**Why R37's guard is "any row", not "an open row".** Until the 2026-10-05 develop merge the guard was
an open row. Develop's `20260929T190000Z__R42-paid-provisioning-commercial-metadata.sql` (ETP-5548,
applied, immutable) is a **second marker writer**: it inserts an active `productive` marker — or
flips an existing one to `productive` — for every paid-provisioned owned tenant, deciding "paid"
from `etgo_checkout_request` and knowing nothing about `etgo_subscription`. It therefore also
targets tenants onboarded after ETP-5046, whose paid upgrade opened a row and wrote no marker.
With the old guard such a tenant matched neither half of `@check` (it has an open row), R37
recorded `SKIPPED_NOT_NEEDED`, and the marker survived forever: §8.1's count never reaches 0, and
once a row is closed (ETP-5047's close-on-cancel) `resolvePlan` falls through to the fallback, reads
the stale marker and a canceled tenant reads productive. A closed row is still proof the tenant is
on the row model, so the widened guard retires the marker next to a closed row too. The runtime
path cannot do this cleanup — it runs only when it opens a row — so **the retirement end state now
depends on R37 running after R42** for every tenant (§8.2) — guaranteed by the runner's
chain-current gate for the tenants present at the switch, not for those that reach R42 later
(§8.2, residual).

The backfill half of `@check` (branch A) is unchanged and still keys on "no **open** row". The
accepted consequence: a tenant whose only subscription row is closed and that carries an active
productive marker — R42 can produce exactly that — gets a fresh open `legacy-productive` row before
its marker is retired. Unreachable while nothing writes `END_DATE`; ETP-5047 must re-check it before
closing rows on cancel (`open-and-notable-topics.md` §5.13).

The fleet therefore converges from both ends, and the preference stops being a parallel source of
truth: **once the switch is on, the product writes it only when the subscription write FAILED**,
which is precisely the case `TenantPlanPreferenceFallback` exists to cover — R42 is the one data-fix that also writes it,
and R37 cleans up after it. Both retirement paths remove *every*
`ETGO_TenantPlan` row visible at the tenant, whatever its value or `isactive` flag — once a
subscription exists, any surviving marker is a second answer to a question that now has one
authority, and a leftover inactive row would keep the end-condition count of §8.1 permanently
above zero, which would block Phase F forever.

### 8.1 The observable end condition

This is the point of doing it per tenant. **It applies only on an environment that has switched
(§6.3)**: before that every paying tenant legitimately carries a marker (logged at DEBUG), so Phase F
also requires `ETGO_SubscriptionModel = Y` on every environment. Phase F — deleting `markProductive`,
`retireProductivePreference`, `TenantPlanService.PREFERENCE_ATTRIBUTE`,
`TenantPlanPreferenceFallback` and everything else carrying the grep marker
`ETP-5046-TRANSITIONAL-FALLBACK` — becomes a **query, not a judgement call**:

```sql
select count(*) from ad_preference where attribute = 'ETGO_TenantPlan';
```

When that reaches **0**, and the fallback's `WARN` lines
(`ETP-5046-TRANSITIONAL-FALLBACK: tenant … has no open ETGO_SUBSCRIPTION row`) have stopped
appearing in the logs, every tenant has moved and the transitional code can be deleted safely.
Until then, **the rows that remain ARE the worklist** — each one names a tenant the backfill has
not reached.

Two signals, not one, on purpose: the count says no tenant still *has* a marker; the silence says
no tenant still *needs* one. A count of 0 with the WARN line still firing would mean something is
reading a marker that no longer exists.

### 8.2 Ordering invariant: R31/R32 must never observe a post-retirement tenant

Two earlier data-fixes read this preference directly in SQL and know nothing about
`etgo_subscription`:

- `20260901T120000Z__R31-force-test-mode-demo-tenants.sql` keys on the **absence** of an active
  productive marker to force `ETSG_ForceTestMode='Y'`.
- `20260901T130000Z__R32-revert-test-mode-productive-tenants.sql` keys on its **presence**.

If R31 ever ran for a tenant **after** R37 retired that tenant's preference, it would read a paying
tenant as free and force test mode on it — which routes that tenant's real SII / TicketBAI /
VeriFactu submissions to the tax authority's **TEST endpoints**. That is a fiscal-compliance
failure, not a configuration nit.

**What makes it safe**, stated exactly: `run.js` sorts the catalog by file name (the UTC timestamp
prefix makes lexical order == chronological order) and applies, per tenant, only fixes strictly
newer than that tenant's watermark — the newest timestamp among its `PROCESSED` ledger rows. R37 is
opt-in and never visited in chain order; what replaces that is the runner's gate on `FIX=`: an
opt-in fix runs only for a tenant whose watermark is already `>= 2026-10-05T18:00:00Z`, R37's own
date (`BLOCKED_CHAIN_BEHIND` otherwise). So for any single tenant:

- R37 can only run once the chain is past R31 (2026-09-01), so R31 has had its turn first;
- from then on R31 is below the watermark and skipped on every later run — **including** the case
  where R31 itself `FAILED`, because the watermark is a date, not a per-fix flag. R37's own ledger
  rows never move the watermark, but they do not need to: the gate already required it.

R31 can therefore never execute against a tenant whose preference R37 has already retired. Neither
R31 nor R32 was edited: an applied data-fix is immutable (`sql/README.md` rule 3) and is superseded
by a new dated file, never edited in place.

**The same argument covers R42, the third fix that keys on the marker** — on its absence, to insert
it (§8). `20260929T190000Z__R42-paid-provisioning-commercial-metadata.sql` is dated before R37, so
for any single tenant the gate guarantees R42 is already at or below the watermark when R37 runs,
R37's retirement branch removes whatever marker R42 wrote, and R42 never runs for that tenant again.

**Residual since R37 became opt-in (owned by the data-fix side):** a tenant that first reaches R42
*after* its environment switched — onboarded later, starting at the onboarding baseline
(`ONBOARDING_PROVISIONED_THROUGH`, 2026-09-02, below R42) — runs R42 in its normal chain, and R37 no
longer follows it there. If R42 matches that tenant it re-inserts a marker next to the tenant's
subscription row. Plan and access are unaffected — a tenant with a row is read from its row — but
the marker keeps §8.1's count and the §6.2 verification above 0 and pollutes the Phase F worklist.
Until a successor of
R42 ships, re-run R37 for such a tenant (`make data-fixes FIX=R37-tenant-subscription-backfill
CLIENT=<id>`). Recorded in the R37 header and `open-and-notable-topics.md` §3.13 (proper fix: a
subscription-aware successor of R42, before Phase F).

**The one exception is an operator forcing R42**: `run.js --fix <R42>` ignores chain order and the
watermark. Whoever does that must follow it with `run.js --fix <R37> --client <same tenant>`, or
the re-inserted marker survives and §8.1's count never reaches 0.

> **Any FUTURE fix must key on `etgo_subscription`, not on `ETGO_TenantPlan`.** After R37 the
> preference is present *only* for tenants the backfill has not reached, so "has no productive
> preference" no longer means "is a free tenant" — it increasingly means "is a paying tenant that
> has already been migrated". A new fix written against the preference would invert its own intent,
> silently, on exactly the tenants that pay. R42 is what that looks like in practice: it reads a
> migrated paying tenant's missing marker as a gap and re-inserts it, and only R37's widened
> retirement keeps that from becoming permanent.

### 8.3 Deploy ordering still matters

- **Run R37 after the deploy and before setting the switch, never before the deploy.** It is
  opt-in now (§6.2 step 3), so nothing runs it by accident; whoever runs it does so as part of the
  switch. A tenant provisioned between the schema landing and the code shipping gets a preference
  and no subscription; the backfill's `@check` catches exactly that tenant, because it keys on the
  preference rather than on a date.
- **`markProductive` survives until Phase F**, because it is the record of every purchase on an
  environment whose switch is still OFF (§6.3), and the safety net for a paid upgrade whose
  subscription write failed on one that is ON. Phase F therefore also requires the switch to be ON
  on every environment. Deleting it — or the fallback that reads it — before the end condition
  above is met is the one genuinely unsafe action left in this ticket.

### 8.4 `CheckoutRequestStore` restores the caller's `OBContext`

Every store method used to install the system context and unwind with `restorePreviousMode()`
alone, which pops the admin-mode stack but not the context. ETP-5045 fixed that with `runAsSystem`
(capture, run as system, leave admin mode, restore the caller's context — including a `null` one).
Develop's own copy of that fix arrived through the merge, together with new account-id-scoped
lookups (`find(requestId, accountId, email)`, `findForAccount`, `findSubscriptionForAccount`,
`findActiveForAccountAndClientName`, the provider-id finders) that had reintroduced the raw
install; the merge routes those through `runAsSystem` too, so the store has one context site.

**Restoring the context is not free — it removed a context callers had been living on.** The
Stripe webhook is matched before the authentication chain and runs with `OBContext == null`. On
develop it only ever applied a lifecycle event because `BillingEventStore.claim` and the
`CheckoutRequestStore` finders *leaked* a system context onto the thread before the handler ran.
With both stores restoring the caller's (null) context, the first preference write
(`TenantEnvironmentLifecycleService.setPreference`, an `OBQuery` outside admin mode) threw an NPE
and every correlated lifecycle event ended `FAILED` with a 500 — on both routes, row and
preference. Fixed twice over, so neither half depends on the other:

- `EtendoGoJwtServlet.applySubscriptionLifecycle` installs the system context explicitly (capture
  the previous one, `setOBContext("0","0","0","0")` + admin mode, leave admin mode, restore) around
  everything it does, through `payment/SystemContext` — the one implementation of that sequence,
  which `CheckoutRequestStore` and `BillingEventStore` now delegate to as well. Both unwinding steps
  are quiet (logged, never thrown), so a failure leaving admin mode cannot skip the restore;
- `setPreference` runs in admin mode, mirroring ETP-5488's `readPreference`.

`CheckoutWebhookEndpointIntegrationTest` pins it with a signed, correlated
`invoice.payment_failed` delivered with no context, once for a tenant with an `ETGO_SUBSCRIPTION`
row and once without, and asserts the thread comes back with no context.

`SubscriptionService` used to "open a system context only when there is none". That branch never
fired — the `setAdminMode(true)` just before it installs an admin context — so it was removed; the
service runs in admin mode and never replaces the caller's context. The onboarding path still
reads the checkout request through a capture-and-restore helper, which is now merely redundant.

**Any new caller with no user context (webhooks, background processes) must install and restore
its own system context — use `SystemContext.run` / `SystemContext.call`, do not hand-write the
sequence again. Do not rely on a store to have left one behind.**

Out of scope here: usage capture and reporting, overage pricing, quota *evaluation* and
enforcement (ETP-5051), the rest of the subscription lifecycle (ETP-5047 — since the develop
merge the ETP-5443 webhooks already write `STATUS` and the grace anchor onto the open row, see
`open-and-notable-topics.md` §3.7), reconciliation (ETP-5048), plan change and proration
(ETP-5053).
