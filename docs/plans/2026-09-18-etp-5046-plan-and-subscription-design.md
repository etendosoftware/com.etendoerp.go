# ETP-5046 — Subscription Plan Catalog and per-tenant subscription: design

Status: implemented on `feature/ETP-5046`. Supersedes the `ETGO_TenantPlan` `AD_Preference`
marker as the source of truth for whether a tenant is paying.

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
  from the typed billing offer. If Stripe cannot quote it, the plan is left out (logged), never a
  500. Its `description` is sent **empty**: the row's `DESCRIPTION` documents the fallback for
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

**What it costs**, recorded in `open-and-notable-topics.md`: fallback buyers land on
`legacy-productive`, which has no quota rows and is therefore **unlimited**; the plan list shows the
grandfathered plan's own name (with an empty description, see above); and the typed billing offer
(`etendo.go.billing.offer.*`) can disagree with the Stripe price the checkout actually charges.

## 7. Backfill

Every tenant carrying the `productive` preference gets one open subscription row on the
grandfathered `legacy-productive` plan, with the Stripe customer and subscription ids — and the
Stripe price id actually charged (`STRIPE_PRICE_ID`, recorded on requests since develop's ETP-5463)
into `PROVIDER_PRICE_ID` — copied from its latest paid `ETGO_CHECKOUT_REQUEST` row where one
exists (NULL otherwise; `SNAPSHOT_AMOUNT`/`SNAPSHOT_CURRENCY` always stay NULL, since the request
stores no amount), **and retires that tenant's now-stale `ETGO_TenantPlan` preference in the
same transaction** (§8). Delivered as `20260924T150000Z__R37-tenant-subscription-backfill.sql`
under `schema_forge/cli/src/data-fixes/sql/` — re-dated from `20260918T120000Z` during the develop
merge, see §7.3. Re-running creates zero rows and retires nothing;
`@check` converges to 0 for two independent reasons afterwards, since it requires both a
productive preference (gone) and no open subscription (present).

The `legacy-productive` plan itself ships as **module sourcedata**
(`src-db/database/sourcedata/ETGO_PLAN.xml` + an `AD_DATASET_TABLE` row), not as a companion
data-fix. A `--client 0` companion fix was rejected: if an operator forgot to run it first, the
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
| absent, blank or unknown | `active` | `CURRENT` (the preference reader's own fallback is `LEGACY_ENTITLEMENT`, also entitled) |

`CURRENT_PERIOD_END` comes from `ETGO_SubscriptionDueAt`, cast only when the value has the ISO-8601
UTC shape `Instant.toString()` writes; anything else becomes NULL, mirroring the Java reader's
"ignore an invalid due timestamp", so a cosmetic value never fails a tenant. `CURRENT_PERIOD_START`
stays NULL, so `ETGO_SUB_PERIOD_CHK` can never reject the insert.

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

---

### 7.3 The file date is load-bearing

The runner applies, per tenant, only fixes **strictly newer** than the newest `PROCESSED` fix
(`run.js`, `<=` skip, no look-back). R37 was authored as `20260918T120000Z`; by the time ETP-5046
merged, develop carried fixes up to `20260922T130000Z`, one of them
(`R38-org-legalentity-pointer`) with the *identical* `20260918T120000Z`. On any environment that
had processed those, R37 would have been skipped silently — no ledger row, no error. It was renamed
to `20260924T150000Z` before reaching a shared environment (renaming an *unapplied* fix is allowed;
`sql/README.md` rule 3 forbids it only once applied). The regression test pins it strictly after
the newest develop fix at merge time.

## 8. Cutover: per-tenant retirement, not a flag day

**The `ETGO_TenantPlan` preference is retired PER TENANT, at the moment that tenant gains a live
subscription — never fleet-wide on a flag day.** A tenant's subscription row appearing and its
preference row disappearing are the same transaction, on both paths:

| Path | Who moves the tenant | Mechanism |
|---|---|---|
| Tenants that predate the subscription model | `R37-tenant-subscription-backfill` | Statement 3 of `@apply`: `DELETE FROM ad_preference` scoped by `visibleat_client_id`, guarded on an open subscription **existing** for the tenant — same transaction as the `INSERT` |
| Tenants that pay from now on | `EtendoGoJwtServlet#applyPaidUpgradeSideEffects` | On a successful subscription write it calls `TenantPlanService#retireProductivePreference` instead of `markProductive` |

The fleet therefore converges from both ends, and the preference stops being a parallel source of
truth: **it is written only when the subscription write FAILED**, which is precisely the case
`TenantPlanPreferenceFallback` exists to cover. Both retirement paths remove *every*
`ETGO_TenantPlan` row visible at the tenant, whatever its value or `isactive` flag — once a
subscription exists, any surviving marker is a second answer to a question that now has one
authority, and a leftover inactive row would keep the end-condition count of §8.1 permanently
above zero, which would block Phase F forever.

### 8.1 The observable end condition

This is the point of doing it per tenant. Phase F — deleting `markProductive`,
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
newer than that tenant's watermark — the newest timestamp among its `PROCESSED` ledger rows. So
for any single tenant:

- within one run, R31 (2026-09-01) is always visited before R37 (2026-09-24);
- once R37 is `PROCESSED` the watermark is `>= 2026-09-24T15:00:00Z`, so R31 is skipped on every
  later run — **including** the case where R31 itself `FAILED`, because the watermark is a date,
  not a per-fix flag.

R31 can therefore never execute against a tenant whose preference R37 has already retired. Neither
R31 nor R32 was edited: an applied data-fix is immutable (`sql/README.md` rule 3) and is superseded
by a new dated file, never edited in place.

> **Any FUTURE fix must key on `etgo_subscription`, not on `ETGO_TenantPlan`.** After R37 the
> preference is present *only* for tenants the backfill has not reached, so "has no productive
> preference" no longer means "is a free tenant" — it increasingly means "is a paying tenant that
> has already been migrated". A new fix written against the preference would invert its own intent,
> silently, on exactly the tenants that pay.

### 8.3 Deploy ordering still matters

- **Run the data-fix after the deploy, not before.** A tenant provisioned between the schema
  landing and the code shipping gets a preference and no subscription, and reads as free. The
  backfill's `@check` catches exactly that tenant, because it keys on the preference rather than
  on a date.
- **`markProductive` survives until Phase F**, because it is now the safety net for the one case
  that still needs it: a paid upgrade whose subscription write failed. Deleting it — or the
  fallback that reads it — before the end condition above is met is the one genuinely unsafe
  action left in this ticket.

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
  everything it does;
- `setPreference` runs in admin mode, mirroring ETP-5488's `readPreference`.

`CheckoutWebhookEndpointIntegrationTest` pins it with a signed, correlated
`invoice.payment_failed` delivered with no context, once for a tenant with an `ETGO_SUBSCRIPTION`
row and once without, and asserts the thread comes back with no context.

`SubscriptionService` used to "open a system context only when there is none". That branch never
fired — the `setAdminMode(true)` just before it installs an admin context — so it was removed; the
service runs in admin mode and never replaces the caller's context. The onboarding path still
reads the checkout request through a capture-and-restore helper, which is now merely redundant.

**Any new caller with no user context (webhooks, background processes) must install and restore
its own system context. Do not rely on a store to have left one behind.**

Out of scope here: usage capture and reporting, overage pricing, quota *evaluation* and
enforcement (ETP-5051), the rest of the subscription lifecycle (ETP-5047 — since the develop
merge the ETP-5443 webhooks already write `STATUS` and the grace anchor onto the open row, see
`open-and-notable-topics.md` §3.7), reconciliation (ETP-5048), plan change and proration
(ETP-5053).
