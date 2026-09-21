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

> **Always write "Subscription Plan Catalog" in full, never a bare "catalog".** This module has
> more than one catalog and the short form is ambiguous: ETP-5050's `ETGO_BILLING_RESOURCE` is the
> *billing resource catalog* (what can be **metered**), while `ETGO_PLAN` is the Subscription Plan
> Catalog (what can be **bought**). `ETGO_PLAN_QUOTA` is the join between the two. The data-fix
> runner's ordered set of `.sql` files is also called a catalog in `run.js`. In code the name is
> already explicit — `PlanCatalogService`, `planCatalog` — and prose should match it.

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

`etendo.go.checkout.price.id` is **deleted, with no fallback.** A fallback price is a price
nobody reviewed, selected exactly when the intended configuration is missing.

The client sends `planKey`. The server resolves it to a provider price id through
`PlanCatalogService`. There is no request field for a price and no code path that reads one, so a
body containing `"priceId"` is *ignored, not validated*.

- Unknown or inactive key → `400 PLAN_NOT_AVAILABLE`. The response deliberately does **not**
  distinguish the two: the endpoint must not confirm which keys exist.
- Valid key with no provider price (the legacy plan) → `503 CHECKOUT_NOT_CONFIGURED`.

`CheckoutConfiguration.isConfigured()` now proves only that Stripe credentials exist. It used to
also prove that *a purchasable thing existed*; that guarantee moved to the Subscription Plan
Catalog, which is why both map onto the same `CHECKOUT_NOT_CONFIGURED` response. **This is the
easiest thing in the ticket to lose silently in review.**

### 6.1 Deploy ordering — a real operational requirement

Two consequences that are not code problems:

1. The moment this ships, checkout returns `CHECKOUT_NOT_CONFIGURED` until an operator creates an
   `ETGO_PLAN` row with a real Stripe price id. **No sourcedata row can supply one**, because a
   real price id is environment-specific (test vs live Stripe). This is a pre-deploy step.
2. `planKey` is required, so **the module and the app-shell must ship together.** There is no
   default-plan property to bridge a version skew.

---

## 7. Backfill

Every tenant carrying the `productive` preference gets one open subscription row on the
grandfathered `legacy-productive` plan, with Stripe ids copied from its `ETGO_CHECKOUT_REQUEST`
row where one exists, **and retires that tenant's now-stale `ETGO_TenantPlan` preference in the
same transaction** (§8). Delivered as `R37-tenant-subscription-backfill` under
`schema_forge/cli/src/data-fixes/sql/`. Re-running creates zero rows and retires nothing;
`@check` converges to 0 for two independent reasons afterwards, since it requires both a
productive preference (gone) and no open subscription (present).

The `legacy-productive` plan itself ships as **module sourcedata**
(`src-db/database/sourcedata/ETGO_PLAN.xml` + an `AD_DATASET_TABLE` row), not as a companion
data-fix. A `--client 0` companion fix was rejected: if an operator forgot to run it first, the
per-tenant `@check` would return 0 rows, the runner would record `SKIPPED_NOT_NEEDED` — a status
that **advances the watermark** — and every paying tenant would be silently and permanently
reclassified as free.

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

- within one run, R31 (2026-09-01) is always visited before R37 (2026-09-18);
- once R37 is `PROCESSED` the watermark is `>= 2026-09-18T12:00:00Z`, so R31 is skipped on every
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

### 8.4 Known follow-up: `CheckoutRequestStore` leaks its `OBContext`

Every method in `CheckoutRequestStore` does `OBContext.setOBContext("0","0","0","0")` plus
`setAdminMode(true)`, but its `finally` calls only `restorePreviousMode()` — which pops the
**admin-mode stack, not the context**. The caller's `OBContext` is silently replaced with the
system one and never put back.

That is harmless where the store was called before this ticket (the pre-stream paywall path).
It is **not** harmless from `applyPaidUpgradeSideEffects`, which runs *after* `prepareAdminContext`
mid-onboarding, where every later provisioning step depends on the context it was given.

ETP-5046 works around it rather than changing the store: the subscription path reads the checkout
request through a helper that captures and restores the caller's `OBContext`, and
`SubscriptionService` opens a system context **only when `OBContext.getOBContext()` is null** (the
webhook case) instead of unconditionally. A spec asserts `setOBContext` is never called when the
caller already has one.

**The store itself should still be fixed** — the workaround protects this ticket's callers, not
the next one's. Not done here because it touches a path ETP-5046 does not otherwise change.

Out of scope here: usage capture and reporting, overage pricing, quota *evaluation* and
enforcement (ETP-5051), subscription lifecycle webhooks (ETP-5047), reconciliation (ETP-5048),
plan change and proration (ETP-5053).
