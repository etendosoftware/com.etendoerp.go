# ETP-5050 — Usage measurement by scheduled DB aggregation (shadow mode)

**Status:** design approved, implementation in progress
**Jira:** ETP-5050 (parent epic ETP-3504)
**Parent PRD:** `schema_forge/docs/plans/2026-08-27-recurring-billing-and-resource-limits-prd.md` §6–7.3
**Branches:** `feature/ETP-5050` in `schema_forge` (from `origin/feature/ETP-5045-2`) and in
`com.etendoerp.go` (from `feature/ETP-5045`)

---

## 1. Goal

Measure real consumption per tenant per day, **without charging anything and without
touching business data**, driven by a resource catalog that is data rather than code. The
output is inspectable usage, so quotas and prices can be calibrated against reality before
any customer is billed for it.

This task is deliberately independent of the billing foundation: measuring does not need a
subscription, a period or durable payment state. Charging does.

**Nothing in this task writes to a business table, and nothing calls an external service.**

## 2. Why aggregation by query, not write-time instrumentation

Consumption is counted by a scheduled query over existing business tables, not by
observing writes as they happen. The module already proves the point:
`BankStatementLineAggregateHandler` maintains an aggregate online through an
`EntityPersistenceEventObserver`, and `BackfillBankStatementAggregatesProcess` recomputes
the same aggregate by query — the query-based recompute had to be added because the
observer alone was not trustworthy. Starting from the query is arriving at the trustworthy
half first.

Each advantage is a risk avoided:

- **No code on the write path.** An observer on document creation runs inside every tenant
  save; a defect there breaks ERP operation, not just billing. A read-only aggregation
  cannot.
- **No capture gaps by construction.** An observer must be attached to every relevant
  entity and every path, and one omission under-counts silently. A query counts what is in
  the table — Etendo Classic windows and processes, the NEO/React stack, batch processes,
  CSV imports and data-fixes — without enumerating them. This is what makes Classic usage
  countable at all.
- **Idempotent by nature.** An aggregate computed by query is a pure function of database
  state, so it can be recomputed and corrected. An event-emission model must implement its
  own de-duplication.
- **Historical backfill, which is what enables shadow mode.** Past periods can be computed,
  so a full month of consumption can be reviewed before anyone is charged. Write-time
  instrumentation can only count from the day it is deployed.

## 3. The settling window

`settlingWindowDays` is configurable, default 5.

Each run iterates the days still inside the window — `[today - settlingWindowDays, today]`
— and for each day, for each active catalog resource, **recomputes the whole day from
scratch**. The recomputed value replaces whatever was there. Nothing is incremental and
nothing is diffed: the day's count is a pure function of database state at the moment of
the run, which is what makes a re-run idempotent and a backfill possible.

Bucketing is by the **date column named in the catalog**, which is also what the counting
HQL filters on.

### Finality

> A day becomes final once its settling window has elapsed. Until then it is recomputed on
> every run. Once final, it never changes — a document voided afterwards does not reduce
> it.

This is the normative wording. It preserves the property that matters — a reported value is
never revised downward after being reported to a payment provider — without the permanent
under-count that a naive closed-day rule would cause.

### Why the window exists

The billable unit for documents is a **posted** sales invoice. `C_Invoice` carries `POSTED`
as a flag and has **no posting timestamp** — verified against
`etendo_core/src-db/database/model/tables/C_INVOICE.xml`, where the available dates are
`CREATED`, `DATEINVOICED`, `DATEACCT` and `UPDATED`.

A plain "closed day is final" therefore under-counts permanently: an invoice dated the 27th
and posted on the 30th reads as unposted when the 27th is computed, and is never counted
afterwards. `UPDATED` is not an escape — it moves on any change, so a later edit would
re-bucket an invoice into a different day and shift counts between days already reported.

**Accepted limitation:** a change landing *after* the window has elapsed is not counted. It
is stated here rather than left to be discovered. No counter or metric is built for it in
this task.

### Lengthening the window is not retroactive

Finality is recorded on the row (`IS_SETTLED`), not recomputed from the current preference. So
shortening a tenant's window is self-correcting, but **lengthening it does not reopen days that
were already flagged final** — the scheduled run will never revisit them, and only an explicit
backfill will.

This follows from "once final, it never changes" rather than contradicting it, but it does mean
the preference does not retroactively control days already decided. Operators changing a window
upward should expect to backfill if they want the older days recomputed.

### Configuration

An operator knob, not per-tenant, so it uses the module's existing precedence helper rather
than `AD_Preference`:

```java
ConfigPropertyReader.readConfigValue(
    "etendo.go.usage.settlingWindowDays", "ETGO_USAGE_SETTLING_WINDOW_DAYS", "5");
```

`ConfigPropertyReader` resolves Java system property → `Openbravo.properties` → environment
variable → default. Parse defensively; a non-numeric value falls back to 5 with a warning.

## 4. One query per resource-day, not per tenant-day

Counting one tenant at a time would be `tenants × resources × windowDays` queries per run —
with a few hundred tenants that is tens of thousands of round trips nightly. The job groups
by client instead, so a run is `resources × windowDays` queries (15 at the defaults).

Tenant attribution is therefore **structural**: it comes from the `group by`, which a
restriction fragment cannot subvert, rather than from a filter it might escape.

**A tenant with no matching rows produces no group, so no row is written; absence of a row
means zero.** The run log records which (resource, day) pairs were computed, so "computed
and zero" stays distinguishable from "never computed" without writing
`tenants × resources × days` explicit zeros.

This is correct for *flow* resources (things that happen on a day) and wrong for *stock*
resources (things that exist on a day) — which is why a stock belongs in a strategy, not
the declarative mode.

## 5. The resource catalog is data

Adding a countable resource must not require a schema change, so the set of resources is a
table, not an enum and not a set of columns.

`ETGO_BILLING_RESOURCE` holds one row per countable resource: key, display name, unit
label, counting mode and the descriptor the mode needs. System-level (`ACCESSLEVEL=4`),
with its own Classic window and menu entry.

Two counting modes, in this order of preference:

1. **Declarative HQL** — the row names an entity, its date property, and an optional HQL
   restriction fragment. The job builds the counting query from that metadata. Adding a
   resource of this kind is inserting a row: no DDL, no code, no deploy.
2. **Named strategy** — the row names a CDI qualifier; the job resolves a counting strategy
   bean by `@Named`, exactly as `NeoServlet` resolves `NeoHandler` implementations. This
   mode is the escape hatch for what is not a plain row count.

### The `@Named`-only rule

> `@Named` only — **never** `@ApplicationScoped` or any other normal scope.

Handler lookup reads the `@Named` annotation off the concrete class. A normal-scoped bean is
a Weld client proxy whose subclass does not carry the (non-`@Inherited`) `@Named`, so the
bean is **silently skipped**. `@Named`-only defaults to `@Dependent`, which is not proxied.
This regressed before, in ETP-4244. The rule belongs in the class javadoc of every strategy.

Discovery uses the module's shared helper `NeoHandlerLookup` (which matches on
`Bean#getName()`, the CDI-standard way), generalised for `UsageResourceCounter` — not a
re-implementation of the older annotation-reading path.

### The SPI

```java
public interface UsageResourceCounter {
  /** @return one entry per day in range that has a value; days omitted count as zero. */
  List<DailyCount> count(UsageCountRequest request);   // clientId, from, to, resource
}
```

A strategy is handed a day range and returns a value per day, so it can express what the
declarative mode cannot — a *stock* (a point-in-time snapshot, where the value does not come
from counting rows dated that day) or a distinct count.

### Seeded resources

| Resource | Mode | Definition |
|---|---|---|
| Posted sales invoices | `D` | `Invoice` / `invoiceDate` / `e.salesTransaction = true and e.posted = 'Y'` |
| Active users | `S` | qualifier `activeUsers` |
| Productive environments | `S` | qualifier `productiveEnvironments` — a daily snapshot of existing active clients |

**Product decision recorded:** "documents issued" resolves to posted sales invoices, not all
document types and not one resource per type. Posting is what makes the invoice real, so it
is the honest unit. Two consequences to be aware of rather than discovered later: revenue
tracks the customer's posting discipline, and an unposted invoice is never counted. Because
the catalog is data, this choice is reversible without development — splitting into
per-document-type resources later is inserting rows.

**Productive environments** is a *stock*, not a flow. Counting clients by `creationDate`
would count clients *created* that day; the strategy instead writes a daily snapshot of
clients that exist and are active. No rollup is performed in this task, so the distinction
does not yet bite — but storing a flow here would make every later reading of it wrong.

Request volume for API and MCP is **not** seeded — see §10.

## 6. The HQL restriction, and why it is safe

A configurable query fragment is the Etendo convention, not an exception to it:
`AD_TAB.whereclause`, `AD_REF_TABLE.whereclause` and `AD_VAL_RULE` all carry
implementer-authored fragments today. A resource whose restriction is one line of HQL is far
more expressive than a structured predicate builder, and it keeps tier 2 of the extensibility
ladder wide — which is the whole point.

Three guardrails, all requirements of this task and not suggestions:

### 6.1 System-authored only

`ETGO_BILLING_RESOURCE` is a System-level table and its window is reachable only by an
authorized system role. A resource restriction **must never become tenant-editable**. Someone
who can write this fragment can already edit `AD_TAB.whereclause`; that is the trust boundary
being reused, and it must not be widened.

### 6.2 Tenant and period scoping live outside the fragment

The PRD composes the fragment as `... and ( <fragment> )` and argues that parenthesization
makes an `or 1=1` harmless. **Parenthesization alone does not hold.** A fragment of
`1=1) or (1=1` composes to `... and (1=1) or (1=1)` — the `or` is now top-level and the
client filter is gone. One bad catalog row would mis-attribute every tenant at once.

The module has no where-clause sanitizer to reuse (`NeoCrudHandler.applyWhereClause` and
`NeoCrudHelper.buildWhereClause` concatenate and trust), so containment is made
**structural**: the fragment goes into a subquery, and the day bounds plus the client
grouping stay in the outer query.

```hql
select outer_.client.id, count(*)
  from <Entity> outer_
 where outer_.<dateProperty> >= :dayStart
   and outer_.<dateProperty> <  :dayEnd
   and outer_.id in (select e.id from <Entity> e where ( <fragment> ))
 group by outer_.client.id
```

The fragment still uses alias `e`, exactly as documented for implementers. A paren breakout
inside the subquery can only widen the subquery's candidate set; the outer `and`-chain still
clamps the day, and client attribution comes from the `group by`, which no fragment can
reach. A breakout that escapes the subquery entirely leaves unbalanced parentheses and fails
to parse — which save-time validation catches.

A per-tenant form (`outer_.client.id = :clientId`, no grouping) comes from the same composer
and is used for single-tenant backfill and for the save-time validation probe.

Bounds are always named parameters. Queries run on the raw Hibernate session
(`OBDal.getInstance().getSession().createQuery`), which applies no DAL client/org filter, with
the tenant pinned by parameter where the per-tenant form is used. Wrapped in
`OBContext.setAdminMode(false)` / `restorePreviousMode()`.

This composition lives in **one** class, `UsageQueryComposer`, unit-testable with no DB.

### 6.3 Validated at save time, with a cost guard

Saving a catalog row runs the composed query once against a bounded window: a malformed
fragment is rejected at configuration time, not discovered at 02:00 by a failing job.

An `EntityPersistenceEventObserver` on `ETGO_BILLING_RESOURCE` (`onNew`/`onUpdate`) rejects,
in order: unbalanced parentheses; `;`; `--` or `/*`; unknown entity name; unknown date
property; then composes and executes the query once. Elapsed milliseconds are recorded in
`LAST_VALIDATION_MS`, so a fragment that would table-scan every tenant nightly is visible
before it is scheduled.

The probe counts one whole day and is bounded by a 10-second query timeout. It deliberately
does not limit the result set: timing only the first group would measure neither the nightly
cost nor the same query plan, and this number exists to predict the nightly cost. The timeout
is what makes that safe — a fragment too expensive to count a single day is rejected outright
instead of hanging the save that is validating it, which would be the worst case of exactly
the problem the probe exists to surface.

*Note:* this observer sits on a **System configuration table**, not a business table. It
does not contradict §2 — that rule is about tenant saves.

## 7. The extensibility ladder

Reproduced verbatim from PRD §7.3, so nobody promises more than the design gives.

| Change | Cost |
|---|---|
| Change or enable a limit on a catalogued resource, at an already-wired enforcement point | **Configuration** |
| Add a resource that is a row count over an entity, with any HQL restriction | **Configuration** |
| Add a resource whose counting is not a row count | **One additive `@Named` strategy class** |
| Enforce at a place not yet wired, for example Etendo Classic writes via a DAL observer | **Development** |

Only the last tier is development, and it is additive rather than a modification. That is the
actual test of extensibility, and the honest boundary: *changing a limit is configuration;
adding an enforcement point is development.*

## 8. Schema

Both tables follow `ETGO_BILLING_EVENT` — the module's most recent and most complete
table+window addition. Fixed column order (PK, `AD_CLIENT_ID`, `AD_ORG_ID`, `ISACTIVE`,
`CREATED`, `CREATEDBY`, `UPDATED`, `UPDATEDBY`, then business columns), all IDs
`VARCHAR(32)`, empty `<default/>`/`<onCreateDefault/>` on every column, an `ISACTIVE` check
constraint, and **constraint names truncated to 30 characters**.

`AD_TABLE.CLASSNAME` carries a **bare** class name. The fully-qualified form used by
`ETGO_DATA_FIX_HISTORY` produces a double-nested generated package and is not repeated.

### `ETGO_BILLING_RESOURCE` — `ACCESSLEVEL=4` (System), `ISDELETEABLE=N`

`VALUE` (search key, unique), `NAME`, `DESCRIPTION`, `UNIT_LABEL`, `COUNTING_MODE`
(`D`/`S`, list reference `ETGO_UsageCountingMode`), `COUNTED_ENTITY`, `DATE_PROPERTY`,
`HQL_RESTRICTION`, `STRATEGY_QUALIFIER`, `LAST_VALIDATED`, `LAST_VALIDATION_MS`.

### `ETGO_USAGE_DAILY` — `ACCESSLEVEL=4`, `ISDELETEABLE=N`

System-owned data *about* a tenant, so `AD_CLIENT_ID='0'` and the measured tenant is a plain
FK column — the `ETGO_DATA_FIX_HISTORY.REMEDIATED_CLIENT_ID` precedent. This makes
cross-tenant aggregation natural instead of fighting client filtering.

`MEASURED_CLIENT_ID` (FK `AD_CLIENT`), `ETGO_BILLING_RESOURCE_ID` (FK), `USAGE_DAY`, `QTY`,
`COMPUTED_AT`, `IS_SETTLED`.

Unique `ETGO_USGDAY_TEN_RES_DAY_UQ` on (`MEASURED_CLIENT_ID`, `ETGO_BILLING_RESOURCE_ID`,
`USAGE_DAY`) — the idempotency key, modelled on `ETGO_DFH_TENANT_FIX_UN`. Upsert against it
makes a re-run idempotent by overwrite, with no "already processed" marker.

Day granularity is chosen so a later Stripe Billing Meters emission can use a deterministic
event identifier per tenant, resource and day and be idempotent end to end. **No emission in
this task.**

Module identity: `AD_MODULE_ID` `94E1B433CF55451EABB764750AC5902A`, `AD_PACKAGE_ID`
`E48DF286D9B9EAA833A51BA7689C9010`, DB prefix `ETGO`. New IDs via `make uuid` only — never
hand-typed. Sourcedata records stay **sorted by ascending UUID**; `check-etgo-xml.sh` and
`.github/workflows/xml-order-check.yml` enforce it.

## 9. Process, scheduling and surfaces

**Process.** `UsageAggregationProcess extends DalBaseProcess`, with an `AD_PROCESS` row
(`ISBACKGROUND=Y`) and two optional `AD_PROCESS_PARA` for a from/to backfill range. Both are
firsts for this module — its two existing processes are `ISBACKGROUND=N` with no parameters.
Able to run for a given date range and to recompute an already-computed range without
duplicating rows.

**Scheduling.** The module ships no `AD_PROCESS_REQUEST` sourcedata — a Process Request is
instance data, not model data — so the schedule is planted at runtime by an
`ApplicationInitializer`, modelled on `StoredColumnQueueScheduleStartup`: fixed deterministic
request id, per-process idempotency check with `setFilterOnReadableClients(false)`, System
client/org/user, daily frequency, and `registerWithScheduler` kept package-visible so tests
can override it.

The *scheduling* (not the process) sits behind the module's feature-flag helper, default
**false**, so merging does not start a nightly job on every instance that takes the update
and the query cost of §11 can be measured on one environment first. The process itself always
exists and can be run by hand.

**Classic windows.** An editable window over `ETGO_BILLING_RESOURCE` with a menu entry, and a
**read-only** window over `ETGO_USAGE_DAILY` showing the final flag, so the numbers are
inspectable without database access — the convention the module already uses for
`ETGO_DATA_FIX_HISTORY` and `ETGO_MATCH_RULE`. Read-only is expressed on the tab
(`ISREADONLY=Y`, `EM_OBUIAPP_CAN_ADD=N`, `EM_OBUIAPP_CAN_DELETE=N`,
`EM_OBUIAPP_SHOW_CLONE_BUTTON=N`), not on the window.

**Daily usage report.** The NEO report pattern, never Jasper: an `ETGO_SF_SPEC` row with
`SPEC_TYPE='R'`, one `ETGO_SF_ENTITY` carrying `JAVA_QUALIFIER` with all writes `N`, and a
`@Named` handler implementing `NeoHandler` and overriding `reportParameters()`. Returns one
row per tenant, resource and day, with the count and the settled flag; filters on tenant,
resource and date range. Read-only, **no period rollup and no price applied**. CSV/XLSX export
comes free via `NeoCsvExportService`, whose `csvField` neutralization is already canonical.

## 10. Out of scope

- **API and MCP request volume.** A request leaves no row, so no query can count it after the
  fact. It needs a counter written by a request filter — a named strategy plus real
  instrumentation, which is a separate decision. The catalog and the SPI make it additive when
  that decision is taken; it is explicitly deferred, not forgotten.
- **Rollup of any kind** — SUM or MAX over a period, the would-have-billed figure, price.
  This task delivers counts per day, per resource, per tenant, and nothing more.
- **Late changes** after the settling window has elapsed.
- Emission to Stripe Billing Meters, overage pricing, quota enforcement, customer-facing
  consumption display.

## 11. Risk to measure early

Query cost depends on an index over **the date column named in the catalog** —
`C_INVOICE.DATEINVOICED` for the seeded resource. This must be measured against realistic
volume rather than assumed, and **with the window applied** (a run is `settlingWindowDays ×
resources` queries, not one).

Grouping by client rather than looping tenants (§4) already removes the multiplier that would
have hurt most, so what remains is the per-day scan itself.

If the plain query does not hold, in order: add a module-owned index on the entity's date
column via `src-db/database/model/modifiedTables/`; then narrow `settlingWindowDays`; then
move to a narrower incremental window per run. **Not** write-time instrumentation.

## 12. Acceptance criteria

1. A new resource can be added by inserting a catalog row from the Classic window, with its
   own HQL restriction, and the next run counts it. No code change, no deploy, no restart.
2. A resource with a novel counting rule can be added as one `@Named` strategy class with no
   modification to the aggregation job or to any existing resource.
3. An invoice posted after its invoice date, but inside the settling window, is counted.
   Covered by an explicit test.
4. A day past its settling window is never recomputed, and voiding a document inside it does
   not reduce the value.
5. **A fragment containing `1=1) or (1=1` counts more rows within its tenant and period and
   does not cross either boundary.** Asserted by test. *(This supersedes the PRD's `or 1=1`
   wording, which passes while the guardrail is still broken — see §6.2.)*
6. A malformed fragment is rejected when the catalog row is saved, not when the nightly job
   runs.
7. Running the process twice over the same date range produces identical aggregate rows and
   no duplicates.
8. A backfill over a past month matches a hand-written verification query for a sample of
   tenants.
9. Documents created through Etendo Classic and through the NEO stack are both counted,
   verified with a fixture that writes through each path.
10. No write to any business table, and no call to any external service, anywhere in this
    task.

## 13. Dependencies

Starts in parallel with ETP-5045. Blocks ETP-5046, whose per-plan quota rows reference this
catalog, and ETP-5051, which compares against these aggregates.

Note the consequence honestly: the critical path to a first recurring charge is the later of
ETP-5045 and this task, rather than ETP-5045 alone. That is the price of the resource catalog
being data, and it is worth paying once.

**Branch note:** `feature/ETP-5050` was branched from the ETP-5045 work in both repos rather
than from `develop`, so its PR carries the ETP-5045 commits and cannot land before that branch
merges. This is a deviation from the ticket, which declares the task dependency-free.
