# Plan quotas (ETP-5051)

The single entry point for plan quotas: what a quota limits, **where it acts and where it does
not**, how an operator caps a resource, how fresh the numbers are, and what extending it costs.

Audience: the operator who configures plans and the developer asked to "limit X". Read §2 and §3
before promising a customer that something can be capped.

| For | Read |
|---|---|
| The HTTP contract: the 403 `QUOTA_EXCEEDED` and 500 `QUOTA_CHECK_FAILED` bodies, the gate's transaction handling, the cache, locks, failure modes | [`neo-headless.md`](neo-headless.md) §4.16 |
| The `quotas` and `accessEnforced` fields of `GET /sws/go/environments`, `planQuotas` of `GET /sws/go/billing/subscription` | [`feature-flags-and-tenant-upgrade.md`](feature-flags-and-tenant-upgrade.md) §2 and §3 (*Exposure in `/environments`*) |
| The `usage-aggregation-schedule` flag | [`feature-flags-and-tenant-upgrade.md`](feature-flags-and-tenant-upgrade.md) §1 |
| The decisions behind each rule, and the accepted limits as register topics | [`open-and-notable-topics.md`](open-and-notable-topics.md) §1.1 (seats), §4.19, §5.1–§5.9, §5.15, §5.18 |
| How usage is counted and stored (catalog, counting modes, settling window, run log) | `schema_forge/docs/usage-measurement.md` |
| What the SPA shows (banner, `/account`, translated refusals) | `schema_forge/docs/generated-custom-windows/app-shell-functional-flows.md` §3.2a-bis … §3.2a-quater |
| The product requirements, including the ladder of §4 below | `schema_forge/docs/plans/2026-08-27-recurring-billing-and-resource-limits-prd.md` §7, §9.2 |

---

## 1. The model in one picture

```
ETGO_BILLING_RESOURCE  (System catalog: what is countable and how)
   │  COUNTING_MODE  D = declarative: COUNTED_ENTITY + DATE_PROPERTY + HQL_RESTRICTION
   │                 S = named strategy: STRATEGY_QUALIFIER → @Named UsageResourceCounter
   │  AGGREGATION    sum | max | last   (how the days of a period combine)
   ▼
ETGO_PLAN_QUOTA        (Plans window → Quotas tab: one row per plan and resource)
   │  INCLUDED_QTY, ENFORCEMENT_MODE off|warn|block, WARNING_THRESHOLD %, CONSUMPTION_SOURCE
   ▼
ETGO_SUBSCRIPTION      (the tenant's open row: its plan, START_DATE, CURRENT_PERIOD_START / _END)
   ▼
QuotaEvaluator  ── consumption over the subscription year ──►  QuotaRules.verdict → ALLOW | WARN | DENY
   │                 aggregate: ETGO_USAGE_DAILY (written by the usage job)
   │                 live:      counted now, with the catalog's own rule
   ▼
report points (entry, environment list, /account)        NEO write path (block, on create)
                                                          user handler (block, seats only, §2.1)
```

- **No active quota row means unlimited**, never zero, and costs no consumption query (§5.1 of the
  register). Deleting or deactivating a row is how a cap is removed.
- **The window is the subscription year, whatever the billing interval** (register §5.5, §5.8):
  the year that starts on each anniversary of `ETGO_SUBSCRIPTION.START_DATE`
  (`QuotaPeriod.yearContaining`, each anniversary computed from the anchor itself, so a 29 February
  start falls on 28 February in non-leap years without drifting), never the calendar year or month, counted in whole server-local
  days: `[startOfDay(anniversary), startOfDay(next anniversary))`. `Included Qty` is therefore
  always a **yearly** quantity: a monthly-paid plan counts over the whole year and can reach its
  limit in its first month. A row with no `START_DATE` (or one dated after now) falls back to the
  billing period, `[startOfDay(CURRENT_PERIOD_START), startOfDay(CURRENT_PERIOD_END))`.
- **The year follows the subscription row.** `START_DATE` is set when the row is opened
  (`SubscriptionService.openSubscription`), so a new row — a re-subscription after a cancel, or a
  plan change that opens a successor row (register §5.13) — starts a new quota year; renewals of
  the same row do not.
- **A subscription with no current period** (a bound missing, inverted, the row closed, or now
  outside the period) is ALLOW with no consumption query, plus one `[QUOTA]` WARN per tenant per
  hour while the row is open (§5.9). This still holds with the yearly window: the year is only
  measured while a billing period is in effect, so a periodless row is not measured even when its
  `START_DATE` is known.
- **One evaluator, one comparison.** Every point below gets its verdict from `QuotaEvaluator`,
  which compares only through `QuotaRules.verdict`. Nothing else in the module compares a consumed
  quantity with an included one.

---

## 2. Where quotas act

Exactly five points read quotas today. Three report; two refuse.

| Point | Surface | What it does | Modes evaluated | Refuses? |
|---|---|---|---|---|
| **Environment entry** | `POST /sws/go/session/environment` → `quotas` | Lists the tenant's WARN and DENY items. Login is never refused for a quota. | `warn`, `block` | **No** — report |
| **Environment list** | `GET /sws/go/environments` → `quotas` and `accessEnforced` per environment | The same WARN/DENY items for every listed tenant; the SPA's quota banner reads them for the current environment. Never fails the list. | `warn`, `block` | **No** — report |
| **`/account`** | `GET /sws/go/billing/subscription` → `planQuotas`, `accessEnforced` | Every active quota with its consumption, mode `off` included (measured, no verdict), for the **entered environment's** subscription (the account's newest only when no environment is entered — ETP-5051 O-1, see [`feature-flags-and-tenant-upgrade.md`](feature-flags-and-tenant-upgrade.md), *Which subscription the page shows*). | all | **No** — report |
| **NEO write path** | REST `POST /sws/neo/{spec}/{entity}`, `/sws/neo/batch`, MCP `etendo_create`, MCP `etendo_batch` and the MCP batch jobs; **and the NEO actions that create documents** (REST `…/{id}/action/{name}` and MCP `etendo_action` alike), listed below | Refuses a **create** of the counted entity with 403 `QUOTA_EXCEEDED` when the row would take the tenant over the included quantity. | `block` only, on **declarative** resources only | **Yes** — on create of the counted entity only |
| **User seats** | The `user` spec's create, and a `PUT`/`PATCH` of a `user` that reactivates an inactive one that would count as a seat once active (`UserRoleAssignmentHandler` → `SeatQuotaGuard`) | Refuses the new seat with 403 `QUOTA_EXCEEDED` when the tenant already uses every seat of its plan. §2.1 | `block` only, on the `tenantSeats` **strategy** resource only | **Yes** — a new user, or a reactivated one that is a seat once active |

The NEO actions checked (ETP-5051), all through `NeoQuotaCreateCheck` — the same guard, restriction
match, lock and count as the CRUD gate, run on the created header **and its lines** right after they
are saved and **before** anything irreversible (a completion that commits, the response):

| Action (window) | Creates | Note |
|---|---|---|
| `createDraftInvoice` (sales order, sales quotation, goods shipment) | sales invoice | checked before the invoice is completed in the same request |
| `createPurchaseInvoice` (purchase order, goods receipt) | purchase invoice | checked before completion |
| `createShipment` (sales order) | goods shipment | |
| `createGoodsReceipt` (purchase order) | goods receipt | |
| `Convertquotation` (sales quotation) | sales order | checked after core's conversion, before the answer; nothing is committed before it |
| return creation (`NeoReturnReceiptService`, `createPurchaseReturn`) | return shipment / receipt | |
| return invoice (return material receipt, return to vendor) | rectificative invoice | checked before completion |
| follow-up documents (invoice → shipment / receipt, `FollowUpActionHandler`) | goods movement | |
| clone (`neoCloneRecordHandler`, any window) | a copy of the record, with the children a hook copies | |
| line imports (`Import*Lines*Handler`: `importOrderLines`, `importShipmentLines` / `importReceiptLines` on invoices) | lines added to an existing document | only the added lines are checked |
| return line imports (`importShipmentLines` on a return material receipt, `importReceiptLines` on a return to vendor; `ReturnShipmentUtils.importReturnLines`) | return lines added to an existing return | only the added lines are checked |

A refusal rolls the whole action back (the generated invoice is never completed, the converted order
never kept) and answers the CRUD gate's envelope: 403 `QUOTA_EXCEEDED` with the `quota` body, or 500
`QUOTA_CHECK_FAILED`. Discount lines added by `TotalDiscountService` while a document is saved are
not checked.

Read the NEO write path row literally:

- **Only a create** is checked. An update, a delete, a process or a state change that makes an
  existing row count is not.
- **Only the counted entity.** The gate matches the created row's DAL entity against the
  resource's `COUNTED_ENTITY`; a create of anything else costs one in-memory map lookup and no
  query.
- **Only `block` quotas on declarative resources.** A `warn` quota never refuses. A `block` quota
  on a **named-strategy** resource is measured and reported as DENY at the three report points,
  but never refused by this gate: a strategy names no entity for the gate to match. The one
  strategy resource that is refused is the seats resource, and that refusal lives in the user
  handler, not in the gate (§2.1).
- **Only rows that count in the current period's window.** The created row must match the HQL
  restriction **and** its date property must fall inside the counted window. A backdated row
  consumes no current-period quota and stands.
- **Only restrictions that hold at create time.** The row is matched as it is created. A
  restriction on a property that changes after the create — `e.posted = 'Y'`, a document status
  such as `e.documentStatus = 'CO'` — matches no new row, so a `block` quota on it is measured and
  reported but **never refused on create** (§6). For a refusable cap, restrict on properties the
  row already has when it is created, for example `e.salesTransaction = true`, and use the `live`
  source for an exact cap (§4.5). Excluding a later state (`e.documentStatus <> 'VO'`) is fine:
  the new row does not have it yet, so it still matches.

The reports run in their own isolated, bounded read pool and never touch the request's
transaction; the write check runs inside the create's transaction. Both are described in
[`neo-headless.md`](neo-headless.md) §4.16.

**There is no other enforcement point.** Not the Classic UI, not processes run outside NEO
(posting, completing, Classic "generate invoices"), not a DAL observer, not a `NeoHandler` whose
`handle()` creates the row and short-circuits the default create **unless it is one of the actions
listed above**. A quota cannot cap "everything everywhere"; it caps
NEO creates of one entity (and the tenant's seats, §2.1), and reports everything else. Adding a
point is development (§3, tier 4).

### 2.1 Seats: the check in the user handler (ETP-5051)

**What a seat is.** `TenantSeatCounter` (`@Named("tenantSeats")`) counts the active `AD_User` rows
of the tenant that the Users window lists as people of the company:

- the owner (`EM_ETGO_IS_OWNER = 'Y'`), and
- every user with an `ETGO_INVITATION` row whose status is not `REVOKED`. A user created in the Go
  SPA always gets one at creation, so a **pending** invitation already takes its seat; `EXPIRED`,
  `DELIVERY_FAILED` and `ACCEPTED` take one too. A revoked invitation does not.

Bootstrap users, the client admin and business-partner contacts have neither, so they never count.
An inactive user does not count: deactivating a user frees its seat, and deleting it removes its
invitations with it (`ETGO_INVITATION_USER_FK` cascades). `ETGO_ACCOUNT` is deliberately **not**
the source: it is global (client 0, one row per email) and only created when an invitation is
accepted, so counting it would let an admin invite without limit. The count is a **stock**: every
call returns today's value, dated at the start of the requested range.

**Where it is checked.** The NEO write gate never refuses a strategy resource (§2), so
`UserRoleAssignmentHandler`'s `handle()` pre-hook runs `SeatQuotaGuard.refusalForNewSeat` itself:

- on a **create** of a user (spec `user`), right after the duplicate-email check and before the
  username is derived — nothing is inserted when it refuses;
- on a **`PUT`/`PATCH`** whose body sets `active` to `true` (`true`, `"true"` or `"Y"`) on a user
  that is inactive now **and would count as a seat once active** — the owner, or a user holding an
  invitation that is not `REVOKED` (`TenantSeatCounter.isSeatOnceActive`). Such a reactivation takes
  a seat back, so `SeatQuotaGuard.refusalForReactivation` checks it as a new one. Reactivating a
  user that would be no seat — a contact without invitation, a user whose invitations are all
  revoked — is never refused. An update that sends no `active`, or sends it for a user that is
  already active, is not checked. Loading the target user is part of the check: if it fails, the
  update answers 500 `QUOTA_CHECK_FAILED` like any other check error.

`isSeatOnceActive` and the tenant count share one seat rule (`SEAT_CONDITION` in
`TenantSeatCounter`), the count adding only that the user is active, so the two cannot drift
apart.

**The verdict.** The guard looks for the plan's quotas whose billing resource has Strategy
Qualifier `tenantSeats`, and takes the verdict from `QuotaEvaluator.refusalForCreate`: a `block`
quota refuses when the seats in use plus one exceed `Included Qty`, i.e. once every seat is taken.
No such quota, a quota in mode `warn` or `off`, or no current period (§1; the periodless `[QUOTA]`
WARN applies) lets the user through, as for every other quota. The refusal is the write gate's
403 `QUOTA_EXCEEDED` body ([`neo-headless.md`](neo-headless.md) §4.16), `consumed` being the seats
in use and `period` the quota window, so a client handles it as any other quota refusal (keyed off
`error.code`). It **fails closed**: any error while checking answers 500 `QUOTA_CHECK_FAILED`, and
nothing is saved.

**How it differs from the write gate.**

- **Always counted live.** The guard calls `TenantSeatCounter.seatsOf` directly, whatever the
  quota's Consumption Source; the source only decides what the three report points show (§4.6).
- **No cache.** The plan's quotas are read through `DalQuotaLookup` on every checked request, not
  through `QuotaClientCache`, so a quota change reaches the next user create at once, not within
  60 s.
- **No lock.** The guard takes no `QuotaLock`, so two users created at the same moment for the
  last free seat can both be accepted.

**Not checked:** a user reactivated in the Classic UI or by any path other than the `user` spec;
an invitation sent through `POST /sws/go/company-invitations` to an existing `AD_User` of the
tenant that had none (that user starts taking a seat without the check). Both are still counted
and reported.

---

## 3. The extensibility ladder

PRD §7.3, verbatim:

| Change | Cost |
|---|---|
| Change or enable a limit on a catalogued resource, at an already-wired enforcement point | **Configuration** |
| Add a resource that is a row count over an entity, with any HQL restriction | **Configuration** |
| Add a resource whose counting is not a row count | **One additive `@Named` strategy class** |
| Enforce at a place not yet wired, for example Etendo Classic writes via a DAL observer | **Development** |

*Changing a limit is configuration; adding an enforcement point is development.*

What each tier means with what ETP-5051 delivered:

1. **Change or enable a limit on a catalog resource at a wired point — configuration.** Edit or
   add the row in **Plans → Quotas** (§4.2). It reaches the reports on the next request and the
   NEO write path within 60 s (§5). "Wired point" means the five rows of §2 and nothing else.
2. **A new row-count resource with an HQL restriction — configuration.** First a catalog row in
   **Billing Resource** (declarative: entity, date property, restriction; validated when saved),
   then a quota row on the plan. With mode `block` it is refused on NEO creates of that entity
   from the moment its quota row is cached. Example in §4.4.
3. **Novel counting logic — one additive `@Named` counting strategy.** Implement
   `com.etendoerp.go.usage.UsageResourceCounter`, annotate it `@Named("<qualifier>")` **and
   nothing else** (no normal scope: the lookup matches on the bean name and a client proxy would
   be skipped silently), deploy it, and point a catalog row with counting mode *strategy* at the
   qualifier. Both consumption sources use it: the usage job stores its daily values, and a `live`
   quota calls it over the period. Such a resource is **reported** at all three report points,
   but a `block` quota on it **refuses nothing** on the NEO write path (§2): making it refusable
   at a point is tier 4. Reference: `schema_forge/docs/neo-headless-extensibility.md` §2.8; the
   one production strategy is `TenantSeatCounter` (`tenantSeats`, §2.1).
4. **Enforce at an unwired place — development, additive, through the same evaluator.** For
   example at posting (the only way a posted-invoices count grows, §6), at a process, or for
   Classic writes through a DAL observer. The new point must take its verdict from
   `QuotaEvaluator` (`refusalForCreate`, or `evaluate` for a report) — never a second comparison
   of consumed against included, which is how two points drift apart (PRD §9.2). It is additive:
   no existing point changes. The seat check (§2.1) is such a point: it makes the `tenantSeats`
   strategy refusable at the user paths and asks `QuotaEvaluator.refusalForCreate` for the
   verdict.

---

## 4. Capping a resource — operator guide

Two Classic windows, **Billing Resource** and **Plans**, both System-level (`ACCESSLEVEL=4`) and
reached from a System role: a tenant sees its caps on `/account` but cannot change them.

### 4.1 The resource exists in the catalog

**Billing Resource** holds what can be counted. **The module ships it empty** — every resource is
a row an operator adds (tier 2) or a strategy a developer deploys (tier 3). One strategy is
deployed, `tenantSeats`; its catalog row is still the operator's to add (§4.6).

| Field | Meaning |
|---|---|
| Search Key (`VALUE`) | The stable key; it is the `resourceKey` clients see |
| Name, Unit Label | What the SPA shows: "{Name}: {consumed} of {included} {Unit Label}" — a missing name falls back to the key |
| Counting Mode | `D` declarative or `S` strategy (`schema_forge/docs/usage-measurement.md` §2) |
| Counted Entity, Date Property, HQL Restriction | Declarative only: the DAL entity, the date that places a row in a day, and a fragment written against alias `e` |
| Strategy Qualifier | Strategy only: the `@Named` value of the deployed counter |
| Aggregation | `sum` (default), `max` or `last`: how the days of a period combine. `sum` for a flow (documents created); `max` or `last` for a stock (users, environments). A live declarative count is one count over the whole period, so aggregation does not apply to it (register §5.6) |

Saving a row validates it: an unknown entity or property, a fragment that does not parse, a
qualifier no deployed counter carries are rejected at save time, and the composed query is probed
once (`LAST_VALIDATION_MS`).

### 4.2 The quota row on the plan

**Plans → Quotas**, one row per plan and billing resource:

| Field | Values | Notes |
|---|---|---|
| Billing Resource | a catalog row | |
| Included Qty | integer ≥ 0, **required, no default** | Per subscription year, whatever the billing interval (§1). `0` in mode `block` refuses every in-window create of the entity |
| Enforcement Mode | `off` / `warn` / `block`, **required, no default** | See the verdict table below |
| Warning Threshold | 1–100 (percent) or empty | Empty: no early warning, only at the limit |
| Consumption Source | `aggregate` (also when empty) or `live` | §5. The legacy value `sum` still stored on old rows reads as `aggregate` |
| Active | Y/N | An inactive row is no row: the resource is unlimited for the plan |

The verdict, computed by `QuotaRules.verdict` from the consumption over the period:

| Mode | DENY when | WARN when | Else | Refused on create? |
|---|---|---|---|---|
| `block` | consumed ≥ included | threshold set and consumed ≥ included × threshold / 100 | ALLOW | Yes, when the create would exceed the included quantity (consumption before it + 1 > included) |
| `warn` | never | consumed ≥ included, or the threshold is reached | ALLOW | Never |
| `off` | — | — | no verdict | Never; measured on `/account` only, and no query anywhere else |

> **Yearly limits, on every plan.** The window is the subscription year (§1), so `Included Qty` is
> consumed over the whole year on a monthly-paid plan too: enter the yearly figure. A tenant that
> uses it up in its first month is refused (`block`) until the next anniversary of its
> `START_DATE`. Every quota item carries `period` = `year` (`TenantQuotas.getPlanInterval()`), so
> the SPA shows "per year" next to it; only a subscription row without `START_DATE` reports the
> plan's billing interval (`month` / `year`) and counts over the billing period.

### 4.3 Removing or relaxing a cap

Deactivate or delete the quota row (unlimited), switch the mode to `warn` or `off`, or raise
`Included Qty`. The reports see the change on their next request; the NEO write path within 60 s.

### 4.4 Example: cap sales orders at 500 per subscription year

A Billing Resource row, declarative:

| Field | Value |
|---|---|
| Search Key | `SALES_ORDERS` |
| Name / Unit Label | `Sales orders` / `orders` |
| Counting Mode | `D` |
| Counted Entity | `Order` |
| Date Property | `orderDate` |
| HQL Restriction | `e.salesTransaction = true` |
| Aggregation | `sum` |

Then, on the plan, a Quotas row: `SALES_ORDERS`, Included Qty `500`, Enforcement Mode `block`,
Warning Threshold `80`, Consumption Source `live`.

What follows:

- At 400 orders in the subscription year the tenant's environment list and entry report WARN (the
  SPA shows the amber banner); `/account` shows "Near the limit". On a monthly-paid plan that can
  happen in the first month: the 500 are for the whole year (§1).
- The 500th order is still accepted; once 500 are counted, the next NEO create of a sales order
  whose `orderDate` is in the subscription year answers 403 `QUOTA_EXCEEDED`, `/account` shows
  "Limit reached" and the SPA shows its translated "plan limit reached" message. A sales order
  backdated to a previous subscription year is not refused. A purchase order
  (`salesTransaction = false`) is not refused either: it does not match the restriction.
- An order created in Classic, or by a process, is not refused, but it is counted: the next report
  includes it.

With `aggregate` instead of `live`, the same quota reads the stored daily usage: no live count per
create, but the cap is only as fresh as the last usage-job run (§5), and only enforceable while
the schedule is on.

### 4.5 Example: a refusable cap on sales invoices

Counting **posted** invoices (`e.posted = 'Y'`) cannot be refused: an invoice is created unposted,
so the create never matches (§2, §6). The configuration route to a refusable invoice cap counts
**sales invoices created** instead, with a restriction that holds at create time:

| Field | Value |
|---|---|
| Search Key | `CREATED_SALES_INVOICES` |
| Name / Unit Label | `Sales invoices` / `invoices` |
| Counting Mode | `D` |
| Counted Entity | `Invoice` |
| Date Property | `invoiceDate` |
| HQL Restriction | `e.salesTransaction = true` |
| Aggregation | `sum` |

On the plan: a Quotas row with Enforcement Mode `block` and Consumption Source `live`, so every
NEO create of a sales invoice is checked against an exact count.

- **The metric changes meaning:** it counts invoices created in the period, drafts included,
  whether or not they are ever completed or posted.
- **Voided invoices.** Voiding an invoice creates a reversal invoice, and both are sales invoices,
  so as written both count. Excluding the voided originals —
  `e.salesTransaction = true and e.documentStatus <> 'VO'` — still matches every new invoice,
  because a new invoice is never `VO`. How a reversal is flagged depends on the document type and
  the modules installed: **verify on your data** which rows the restriction counts (void a test
  invoice, then compare the `/account` figure) before promising a customer what the cap includes.
- A reversal created by the void process is not refused (processes are not an enforcement
  point, §2), but it is counted if it matches.

**Restricting on the document type: use `transactionDocument`, not `documentType`.** A restriction
on the kind of invoice goes through the invoice's target document type, `C_DocTypeTarget_ID`. The
document category alone does not separate standard invoices from the others: `ARI` (AR invoice) is
also the category of return and rectificative document types. Whether those count is the
operator's choice — counting them is a legitimate setup. To leave them **out**, add their flags:

| To count | HQL Restriction |
|---|---|
| every sales invoice | `e.salesTransaction = true` |
| `ARI` invoices, returns excluded | `e.salesTransaction = true and e.transactionDocument.documentCategory = 'ARI' and e.transactionDocument.return = false` |
| `ARI` invoices, returns and rectificatives excluded | `e.salesTransaction = true and e.transactionDocument.documentCategory = 'ARI' and e.transactionDocument.return = false and e.transactionDocument.etsgIsRectificative = false` |

`return` is `C_DocType.IsReturn` (core). `etsgIsRectificative` is `C_DocType.EM_Etsg_Isrectificative`,
added by the module `com.etendoerp.sif.general`: the last restriction only works where that module
is installed, and on an instance without it the save-time validation rejects the unknown property.
Checked on 2026-10-26 against the local database with these exact fragments (over `DocumentType`,
every client): 99 `ARI` document types; 90 once returns are excluded (9 are returns); 47 once
rectificatives are excluded too (43 are rectificatives). **Verify on your data** which document
types your tenants use before promising what a cap includes.

A join is fine: the write check matches the inserted row with the full HQL restriction in the
create's own transaction (§2). `e.documentType` (`C_DocType_ID`) does not hold at create time on
every path: its AD default is `'0'`, a draft created through the CRUD POST keeps it until
`C_Invoice_Post` copies the target into it on completion, and only some generators set it
themselves. Verified on 2026-10-25 against the local database: `AD_Column.DefaultValue` of
`C_Invoice.C_DocType_ID` is `0`, and draft invoices with `C_DocType_ID = '0'` exist next to drafts
from the order generator that carry the target. A restriction on `e.documentType` would therefore
miss CRUD-created invoices at create time and never refuse them (§2, *Only restrictions that hold at
create time*).

### 4.6 Example: cap the seats of a plan

Seats are counted by the deployed strategy `tenantSeats` (§2.1); nothing is seeded, so capping them
is configuration on each environment. A Billing Resource row, strategy:

| Field | Value |
|---|---|
| Search Key | `SEATS` (any stable key) |
| Name / Unit Label | `Users` / `users` |
| Counting Mode | `S` |
| Strategy Qualifier | `tenantSeats` |
| Aggregation | `last` |

Then, on **each plan that should cap seats**, a Quotas row: the `SEATS` resource, Included Qty `5`,
Enforcement Mode `block`, Consumption Source `live`, Warning Threshold optional. A plan without the
row has unlimited seats.

What follows:

- The owner and every invited user count, a pending invitation included. With 5 seats in use, the
  next user created in the Users window (or through the `user` spec on any channel) answers 403
  `QUOTA_EXCEEDED`, and so does switching an inactive invited user back to active (an inactive
  contact without invitation can always be switched back on: it takes no seat). Deactivating a user
  frees its seat at once; the next create is accepted.
- `/account` shows 5 of 5 users with "Limit reached"; the environment list reports DENY, so the
  red banner shows.
- `Included Qty` `1` lets the owner work alone: every other user is refused.

**Why `last` and `live`.** The check counts live whatever the source (§2.1); `live` makes the three
report points show that same number. Seats are a stock, so the days must not be added: `sum`
would count each seat once per stored day, and `max` would report the highest stored day of the
year, so a freed seat would never lower it. With `aggregate`, `last` shows the count of the latest
usage-job run. While the usage schedule is on, the job also stores the seat count, but a stock has
no history: every day it recomputes gets today's value (register §1.1). `last` reads the newest
day, so the reports are unaffected; the stored past days are not a true history.

---

## 5. Freshness: when does a change or a create show up

| What changes | Reports (entry, list, `/account`) | NEO write path |
|---|---|---|
| A quota row edited, (de)activated; a plan change; a renewal | Next request (no cache) | Within **60 s**: each client's block quotas and period are cached for 60 s **per JVM** (`QuotaClientCache`) |
| A create, `live` source | Next request (counted now) | Immediately: the check counts under a per-tenant, per-resource advisory lock, so concurrent creates cannot both slip under the cap |
| A create, `aggregate` source | After the next usage-job run | After the next usage-job run: until then the stored aggregate does not include it, so a `block` cap may be **overshot by what was created since the last run** (and a batch by up to N−1 more, §6) |
| A seat taken or freed; a seat quota edited (§2.1) | `live`: next request. `aggregate`: after the next usage-job run | The user handler's check: **immediately**, whatever the source — it reads the plan's quotas and counts the seats on every checked request, with no cache |

**`live` versus `aggregate`.** `live` is exact and costs one query per evaluation (and a lock on
a checked create). `aggregate` costs a cheap read of the stored daily rows, but lags by up to one
usage-job cadence, and **does not move at all while the usage job is not scheduled**. Use `live`
for a hard `block` cap; `aggregate` suits `warn` quotas and expensive counts.

### 5.1 Turning the usage schedule on

The stored aggregate is written by the **Billing Resource Usage Aggregation** process
(`ETGO_UsageAggregation`). Nothing runs it on a schedule until an operator turns it on:

- **Production** (never restarted): turn the `usage-aggregation-schedule` flag on **and** create a
  Process Request — **Billing Resource Usage Aggregation**, client System, timing *Schedule*,
  every 120 minutes — in the same step. A request firing while the flag is off does no work and
  ends as an **ERROR** in Process Monitor; a flag on with no request does nothing (register §2.11).
- **Environments that restart:** turn the flag on; at the next restart
  `UsageAggregationScheduleStartup` plants the System request itself, every
  `etendo.go.usage.schedule.interval.minutes` / `ETGO_USAGE_SCHEDULE_INTERVAL_MINUTES` minutes
  (default **120**, at least 15). Changing the interval later means editing the request too.
- **Lag warning.** `[USAGE-LAG]` (at most one line an hour) is logged when the last successful run
  in `ETGO_USAGE_RUN_LOG` finished more than **twice the cadence** ago — four hours by default — or
  never. It is checked when a scheduled run starts and when a report reads the stored aggregate,
  never on a create. An installation whose runs counted nothing for any tenant writes no run-log
  row and so reads as "never ran".
- A `live` quota does not depend on the job.

Flag details: [`feature-flags-and-tenant-upgrade.md`](feature-flags-and-tenant-upgrade.md) §1,
`usage-aggregation-schedule`.

---

## 6. Accepted limits

Each was accepted during ETP-5051; none is a bug to fix silently.

- **A resource that grows through a state change is only warned about.** A restriction on a
  property that changes after the create (`e.posted = 'Y'`, a document status) matches no row at
  create time — an invoice is created unposted — so a `block` quota on it is never refused. It is
  measured and reported at the three report points. Refusing it at the state change means
  enforcing at posting: tier 4 (register §4.19). The configuration route to a refusable cap is a
  restriction that holds at create time, such as `e.salesTransaction = true` with `live` (§4.5).
- **Not covered by the write check:**
  - rows created by a customization whose `handle()` creates them itself and short-circuits the
    default create, other than the NEO actions listed in §2;
  - rows created by processes outside NEO (posting, completion, Classic generation from another
    document), and rows a PL/SQL procedure inserts — for example the reversal invoice of a void;
  - creates in the Classic UI — there is no DAL observer, by decision.
  All of them are still counted and reported.
- **The SPA's CSV/XLSX import shows a generic row error.** The import writes through
  `/sws/neo/batch`, so a capped entity **is** refused, but the row message is decided in core's
  `importEngine.classifyImportError` (`@etendosoftware/app-shell-core`), which has no quota case
  yet; the user sees a generic row error, not "plan limit reached". The fix is owed to
  [ETP-5911](https://etendoproject.atlassian.net/browse/ETP-5911) (register §5.18).
- **The last, partial day of a window is charged to the next window.** The window is
  day-aligned, so a row dated on the day the window ends — the subscription's anniversary, or the
  billing period's end on a row without `START_DATE` — created before the boundary time on that
  day, falls outside the ending window: it is not charged to it, and a `block` quota does not
  refuse it even at the cap. That day opens the next window, so the row counts there once that
  window is in effect (for the billing-period fallback, once the renewal is recorded).
- **A batch can exceed an `aggregate` cap by more than the lag.** Each batch operation is its own
  create: the charge for rows already created resets per operation, and the stored aggregate
  never sees rows that earlier operations of the same batch created. A batch of N creates can
  therefore go up to N−1 over an `aggregate` cap, on top of the job's lag. A `live` count sees
  them (same transaction) and is exact; use `live` where a batch must not overshoot.
- **One broken quota or one failing tenant blanks alerts on the environment list.** A quota
  row whose evaluation fails reports `[]` for that tenant; a database failure while evaluating one
  tenant also reports `[]` for every tenant not yet evaluated in that request (one log line gives
  the count). The list itself never fails. The write path is not affected: it fails closed (500
  `QUOTA_CHECK_FAILED`) instead.
- **A WARN banner can be dismissed for the browser session; a DENY banner cannot.** The WARN
  dismissal names the reported verdicts, so a new alert (another resource, or WARN turning into
  DENY) shows again. A banner with any DENY item has no close button and ignores a stored
  dismissal. The SPA re-reads the environment list when the tab regains focus, every 5 minutes
  while it is visible, and right after a 403 `QUOTA_EXCEEDED`, so a quota that reaches DENY
  between page loads shows within that time, not at the next login (schema_forge
  `useEnvironmentListFreshness`).
- **Two concurrent batches can deadlock on the quota locks.** A batch that creates rows of two
  `live` `block` resources takes one lock per resource in its own order; two batches of the same
  tenant in opposite orders deadlock, PostgreSQL aborts one, and that batch answers 500
  `QUOTA_CHECK_FAILED` and rolls back entirely. Nothing is half-saved; **the client retries the
  refused batch.** A single create takes one lock and cannot deadlock this way.
- **The write cache is per JVM.** On a multi-node deployment each node trusts its own entry for
  60 s, so a node can enforce an older configuration for up to a minute after the change.

---

## 7. Diagnosing

| Symptom | Look at |
|---|---|
| A quota never refuses | Mode is `block`? The resource is declarative and its `COUNTED_ENTITY` is the entity being created (a strategy resource is refused only for seats, §2.1)? The created row matches the restriction at create time (not after a state change)? Its date is in the subscription year? The source is `aggregate` and the usage job is not running (`[USAGE-LAG]`)? The tenant's subscription has a current period (`[QUOTA] … has no current billing period`)? |
| A quota is reached in the first month of a monthly plan | Expected: the window is the subscription year, not the billing period (§1). `Included Qty` is a yearly figure |
| A user create or reactivation answers 403 `QUOTA_EXCEEDED` | The plan has a `block` quota on a `tenantSeats` resource and every seat is taken (§2.1); a reactivation is refused only for a user that is a seat once active (owner, or a non-revoked invitation). Seats are the owner plus the active users with a non-revoked invitation, pending ones included — not the rows of `ETGO_ACCOUNT`. Deactivate a user, or raise `Included Qty` |
| More users are created than the seat quota allows | Two creates at the same moment for the last seat (the seat check takes no lock); a user reactivated outside the `user` spec; an invitation through `POST /sws/go/company-invitations` to a user that had none (§2.1, *Not checked*). Mode `warn`/`off`, or the Strategy Qualifier is not `tenantSeats`? |
| A quota refuses too early / too late with `aggregate` | Stored usage is up to one cadence old; switch to `live` for an exact cap |
| `consumed` is null on `/account` | No current period on the subscription row (register §5.9); fix the period by hand from Stripe |
| No banner although `/account` shows DENY | The environment list blanked the report (a `[QUOTA] Could not …` line). A DENY banner cannot be dismissed. If the DENY is newer than the page, the SPA picks it up on the next tab focus, within 5 minutes, or at the next `QUOTA_EXCEEDED` refusal; with an `aggregate` source it shows only once the usage job has stored the new usage (§5) |
| `/account` shows no subscription inside an environment the account owns | The page describes the entered environment only (ETP-5051 O-1): that environment has no billable purchase of this account — a demo, an environment the account was invited into, or a purchase whose `CREATED_CLIENT_ID` is not set. Another environment's subscription is never shown in its place |
| 500 `QUOTA_CHECK_FAILED` | `[QUOTA]` ERROR lines: a database failure during the check, or the client's quota entry could not be built. Nothing was saved; retrying is safe |

Log prefixes: `[QUOTA]` (evaluator, gate, reports), `[USAGE-LAG]` (stored usage late).
