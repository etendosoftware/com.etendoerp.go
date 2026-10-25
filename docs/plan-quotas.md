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
| The decisions behind each rule, and the accepted limits as register topics | [`open-and-notable-topics.md`](open-and-notable-topics.md) §4.19, §5.1–§5.9, §5.15, §5.18 |
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
ETGO_SUBSCRIPTION      (the tenant's open row: its plan, CURRENT_PERIOD_START / _END)
   ▼
QuotaEvaluator  ── consumption over the period ──►  QuotaRules.verdict → ALLOW | WARN | DENY
   │                 aggregate: ETGO_USAGE_DAILY (written by the usage job)
   │                 live:      counted now, with the catalog's own rule
   ▼
report points (entry, environment list, /account)        NEO write path (block, on create)
```

- **No active quota row means unlimited**, never zero, and costs no consumption query (§5.1 of the
  register). Deleting or deactivating a row is how a cap is removed.
- **The window is the subscription's billing period**, never the calendar month (§5.5), counted in
  whole server-local days: `[startOfDay(CURRENT_PERIOD_START), startOfDay(CURRENT_PERIOD_END))`.
  On a yearly plan the included quantity is a **yearly** quantity (§5.8).
- **A subscription with no current period** (a bound missing, inverted, the row closed, or now
  outside the period) is ALLOW with no consumption query, plus one `[QUOTA]` WARN per tenant per
  hour while the row is open (§5.9).
- **One evaluator, one comparison.** Every point below gets its verdict from `QuotaEvaluator`,
  which compares only through `QuotaRules.verdict`. Nothing else in the module compares a consumed
  quantity with an included one.

---

## 2. Where quotas act

Exactly four points read quotas today. Three report; one refuses.

| Point | Surface | What it does | Modes evaluated | Refuses? |
|---|---|---|---|---|
| **Environment entry** | `POST /sws/go/session/environment` → `quotas` | Lists the tenant's WARN and DENY items. Login is never refused for a quota. | `warn`, `block` | **No** — report |
| **Environment list** | `GET /sws/go/environments` → `quotas` and `accessEnforced` per environment | The same WARN/DENY items for every listed tenant; the SPA's quota banner reads them for the current environment. Never fails the list. | `warn`, `block` | **No** — report |
| **`/account`** | `GET /sws/go/billing/subscription` → `planQuotas`, `accessEnforced` | Every active quota with its consumption, mode `off` included (measured, no verdict), for the **entered environment's** subscription (the account's newest only when no environment is entered — ETP-5051 O-1, see [`feature-flags-and-tenant-upgrade.md`](feature-flags-and-tenant-upgrade.md), *Which subscription the page shows*). | all | **No** — report |
| **NEO write path** | REST `POST /sws/neo/{spec}/{entity}`, `/sws/neo/batch`, MCP `etendo_create`, MCP `etendo_batch` and the MCP batch jobs | Refuses a **create** of the counted entity with 403 `QUOTA_EXCEEDED` when the row would take the tenant over the included quantity. | `block` only, on **declarative** resources only | **Yes** — on create of the counted entity only |

Read the last row literally:

- **Only a create** is checked. An update, a delete, a process or a state change that makes an
  existing row count is not.
- **Only the counted entity.** The gate matches the created row's DAL entity against the
  resource's `COUNTED_ENTITY`; a create of anything else costs one in-memory map lookup and no
  query.
- **Only `block` quotas on declarative resources.** A `warn` quota never refuses. A `block` quota
  on a **named-strategy** resource is measured and reported as DENY at the three report points,
  but never refused on a write: a strategy names no entity for the gate to match.
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

**There is no other enforcement point.** Not the Classic UI, not processes (posting, completing,
generating invoices from orders), not a DAL observer, not a `NeoHandler` whose `handle()` creates
the row and short-circuits the default create. A quota cannot cap "everything everywhere"; it caps
NEO creates of one entity, and reports everything else. Adding a point is development (§3, tier 4).

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
   NEO write path within 60 s (§5). "Wired point" means the four rows of §2 and nothing else.
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
   at a point is tier 4. Reference: `schema_forge/docs/neo-headless-extensibility.md` §2.8.
4. **Enforce at an unwired place — development, additive, through the same evaluator.** For
   example at posting (the only way a posted-invoices count grows, §6), at a process, or for
   Classic writes through a DAL observer. The new point must take its verdict from
   `QuotaEvaluator` (`refusalForCreate`, or `evaluate` for a report) — never a second comparison
   of consumed against included, which is how two points drift apart (PRD §9.2). It is additive:
   no existing point changes.

---

## 4. Capping a resource — operator guide

Two Classic windows, **Billing Resource** and **Plans**, both System-level (`ACCESSLEVEL=4`) and
reached from a System role: a tenant sees its caps on `/account` but cannot change them.

### 4.1 The resource exists in the catalog

**Billing Resource** holds what can be counted. **The module ships it empty** — every resource is
a row an operator adds (tier 2) or a strategy a developer deploys (tier 3).

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
| Included Qty | integer ≥ 0, **required, no default** | Per billing period of the plan. `0` in mode `block` refuses every in-window create of the entity |
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

> **Yearly plans.** The window is the plan's billing period, so on a `year` plan `Included Qty` is
> consumed over the whole year, not per month. Enter the yearly figure. The window help says so,
> and every quota item carries `period` (`month` / `year`) so the SPA shows "per year" next to it.

### 4.3 Removing or relaxing a cap

Deactivate or delete the quota row (unlimited), switch the mode to `warn` or `off`, or raise
`Included Qty`. The reports see the change on their next request; the NEO write path within 60 s.

### 4.4 Example: cap sales orders at 500 per billing period

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

- At 400 orders in the period the tenant's environment list and entry report WARN (the SPA shows
  the amber banner); `/account` shows "Near the limit".
- The 500th order is still accepted; once 500 are counted, the next NEO create of a sales order
  whose `orderDate` is in the period answers 403 `QUOTA_EXCEEDED`, `/account` shows "Limit
  reached" and the SPA shows its translated "plan limit reached" message. A sales order
  backdated to a previous period is not refused. A purchase order (`salesTransaction = false`) is
  not refused either: it does not match the restriction.
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

---

## 5. Freshness: when does a change or a create show up

| What changes | Reports (entry, list, `/account`) | NEO write path |
|---|---|---|
| A quota row edited, (de)activated; a plan change; a renewal | Next request (no cache) | Within **60 s**: each client's block quotas and period are cached for 60 s **per JVM** (`QuotaClientCache`) |
| A create, `live` source | Next request (counted now) | Immediately: the check counts under a per-tenant, per-resource advisory lock, so concurrent creates cannot both slip under the cap |
| A create, `aggregate` source | After the next usage-job run | After the next usage-job run: until then the stored aggregate does not include it, so a `block` cap may be **overshot by what was created since the last run** (and a batch by up to N−1 more, §6) |

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
    default create;
  - rows created by processes (posting, completion, generation from another document);
  - creates in the Classic UI — there is no DAL observer, by decision.
  All of them are still counted and reported.
- **The SPA's CSV/XLSX import shows a generic row error.** The import writes through
  `/sws/neo/batch`, so a capped entity **is** refused, but the row message is decided in core's
  `importEngine.classifyImportError` (`@etendosoftware/app-shell-core`), which has no quota case
  yet; the user sees a generic row error, not "plan limit reached". The fix is owed to
  [ETP-5911](https://etendoproject.atlassian.net/browse/ETP-5911) (register §5.18).
- **The last, partial day of a period is charged to the next period.** The window is
  day-aligned, so a row dated on the day the period ends, created before the renewal time on that
  day, falls outside the ending period's window: it is not charged to it, and a `block` quota does
  not refuse it even at the cap. That day opens the next period's window, so the row counts there
  once the renewal is recorded.
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
| A quota never refuses | Mode is `block`? The resource is declarative and its `COUNTED_ENTITY` is the entity being created? The created row matches the restriction at create time (not after a state change)? Its date is in the period? The source is `aggregate` and the usage job is not running (`[USAGE-LAG]`)? The tenant's subscription has a current period (`[QUOTA] … has no current billing period`)? |
| A quota refuses too early / too late with `aggregate` | Stored usage is up to one cadence old; switch to `live` for an exact cap |
| `consumed` is null on `/account` | No current period on the subscription row (register §5.9); fix the period by hand from Stripe |
| No banner although `/account` shows DENY | The environment list blanked the report (a `[QUOTA] Could not …` line). A DENY banner cannot be dismissed. If the DENY is newer than the page, the SPA picks it up on the next tab focus, within 5 minutes, or at the next `QUOTA_EXCEEDED` refusal; with an `aggregate` source it shows only once the usage job has stored the new usage (§5) |
| `/account` shows no subscription inside an environment the account owns | The page describes the entered environment only (ETP-5051 O-1): that environment has no billable purchase of this account — a demo, an environment the account was invited into, or a purchase whose `CREATED_CLIENT_ID` is not set. Another environment's subscription is never shown in its place |
| 500 `QUOTA_CHECK_FAILED` | `[QUOTA]` ERROR lines: a database failure during the check, or the client's quota entry could not be built. Nothing was saved; retrying is safe |

Log prefixes: `[QUOTA]` (evaluator, gate, reports), `[USAGE-LAG]` (stored usage late).
