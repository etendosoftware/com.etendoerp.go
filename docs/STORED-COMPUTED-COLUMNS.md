# Stored Computed Columns (EPL-1807)

> A functional + technical guide to the stored computed column engine introduced in
> Etendo core by epic **EPL-1807**, illustrated with the pilot module
> [`com.etendoerp.storedcomputedcolumn`](https://github.com/etendosoftware/com.etendoerp.storedcomputedcolumn).
>
> **Audience:** module developers and functional/technical leads.
> **Scope:** what the feature is, why it exists, how it works end to end, and how to configure
> a stored computed column of your own.

---

## 1. TL;DR

Etendo has always supported **virtual computed columns** (`AD_Column.SQLLogic`): a read-only column
whose value is *not* a real column in the table but a SQL expression that the database evaluates
**every time you SELECT the table**. Great for deriving display values — but the expression runs on
every read, cannot be indexed, and has no idea when its inputs change.

**Stored computed columns** flip that trade-off. The column is a **real, physical column** in the
table. Its value is **calculated once, when the data it depends on actually changes**, and then
**persisted**. Reads are just plain column reads — indexable, sortable, filterable, and as fast as
any other stored column.

```
Virtual (SQLLogic)                    Stored computed (Computation_Mode = 'S')
────────────────────                  ─────────────────────────────────────────
value = expression                    value = a real column
evaluated on EVERY read               calculated on WRITE of a dependency, then stored
cannot be indexed                     fully indexable / sortable / filterable
implicit dependencies                 explicit, declared dependencies
fails at runtime in prod              fails at build/validation time
```

The engine lives in **core** (EPL-1807). A module only **declares** the column, a **SQL function**
that computes it, and the **dependency rows** that say "recompute when *this* source table changes".
Core generates the triggers, keeps the value fresh, enforces read-only at every layer, and validates
the whole definition at build time.

---

## 2. The problem it solves

`AD_Column.SQLLogic` (virtual computed columns) is embedded into the Hibernate mapping as a
*formula* and evaluated lazily at query time. That has real runtime costs:

- **Read cost scales with result-set size.** An expensive per-row expression slows every grid query
  in proportion to how many rows are returned.
- **Cannot be indexed.** Filtering or sorting on a virtual column forces a full scan.
- **Dependencies are implicit.** A change in a referenced table produces no notification and no
  refresh — the value is simply recomputed the next time someone reads it.
- **Errors surface in production.** Broken SQL or a type mismatch fails at runtime, not at setup.

These costs compound as a window's usage and data volume grow. Stored computed columns make
performance, correctness, and dependency relationships **explicit, enforceable, and auditable** —
while leaving existing `SQLLogic` columns untouched (this is additive, not a migration).

---

## 3. Core concepts & vocabulary

| Term | What it is |
|------|-----------|
| **Stored computed column** | An `AD_Column` with `Computation_Mode = 'S'`. A physical column maintained by the engine. |
| **Computation function** | A SQL function `f(target_id) → value` that returns the column's value for one target record. The **sole writer** of the value. |
| **Target table / target record** | The table the column lives on (e.g. `C_Order`), and the specific row whose value is being computed. |
| **Source table** | A table whose changes should trigger a recompute (e.g. `C_OrderLine`). |
| **Dependency row** | An `AD_COLUMN_COMP_DEPENDENCY` record wiring one source table + events + watched columns + a target-id resolver to the stored column. |
| **Watched columns** | On an UPDATE dependency, the subset of source columns that actually matter — the recompute only fires if one of them changed. |
| **Target ID resolver** | SQL that maps a changed *source* row back to the affected *target* record id(s). |
| **Dirty row** | An entry in `AD_STOREDCOLUMN_DIRTY`: "this target record of this column needs recalculation". |
| **Refresh mode** | How dirty rows get drained: `S` synchronous, `Q` queued/async, `M` manual. |
| **Computation sequence number** | Global ordering. Lower numbers recompute first, so a column can safely read another stored column that was computed earlier. |

### The `AD_Column` fields added by EPL-1807

| Field | Type | Meaning |
|-------|------|---------|
| `Computation_Mode` | CHAR(1) | `N` normal (default) · `V` virtual/`SQLLogic` (existing) · `S` stored computed |
| `Computation_Function` | VARCHAR | Fully-qualified SQL function name; required when mode = `S` |
| `Refresh_Mode` | CHAR(1) | `S` synchronous (end-of-transaction) · `Q` queued (async) · `M` manual |
| `Computation_Sequence_Number` | INTEGER | Global refresh order (default `10` for `S` columns) |

---

## 4. The three refresh modes

A stored computed column chooses **when** its stored value catches up with its sources:

| Mode | How it drains | When the value is correct | Use it for |
|------|---------------|---------------------------|------------|
| **`S` — Synchronous** | A `DEFERRABLE INITIALLY DEFERRED` constraint trigger recomputes **inside the same transaction**, just before commit. | **Always** — at commit, transactionally consistent. A computation error rolls back the whole transaction. | Values that must be exact at read time (document totals, values a validation reads). PostgreSQL only. |
| **`Q` — Queued (async)** | Dirty rows persist after commit; a **background process** drains them later. | **Eventually** — after the next queue-processor run. Bounded by the scheduler interval. | Values whose consumers tolerate lag: dashboards, KPIs, non-blocking displays. Expensive computations. |
| **`M` — Manual** | Dirty rows persist; an operator runs the rebuild on demand. | Only after a manual **Rebuild Stored Column** run. | One-off / operator-driven population. |

> **Platform note:** synchronous (`S`) refresh is **PostgreSQL only** — it relies on deferred
> constraint triggers, which Oracle does not have. On Oracle a column configured `S` is treated as
> `Q`: everything drains through the async queue. See [§9 Oracle](#9-oracle-support).

---

## 5. How it works, end to end

The refresh is a **two-phase, end-of-transaction** mechanism. The key design decision: triggers on
source tables do **almost nothing** during your DML — they just record *what* needs recomputing.
The actual (potentially expensive) computation happens **once per affected target**, at the end.

```
   Business write (you change a C_OrderLine)
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 1 — DIRTY COLLECTION  (AFTER trigger, during the txn)   │
│                                                               │
│  ad_scd_dep_* trigger on the source table:                    │
│   1. (UPDATE only) did any WATCHED column change? if not, RET │
│   2. run TARGET_ID_RESOLVER_SQL → affected target id(s)       │
│   3. INSERT one dirty row per target into AD_STOREDCOLUMN_DIRTY│
│      (INSERT … ON CONFLICT DO NOTHING — dedup within txn)     │
│  No computation. No locks beyond the insert.                  │
└─────────────────────────────────────────────────────────────┘
        │
        ▼  (Refresh_Mode = 'S')
┌─────────────────────────────────────────────────────────────┐
│ Phase 2 — DEFERRED RECALCULATION  (just before COMMIT)        │
│                                                               │
│  ad_scd_dirty_aiu — a DEFERRABLE INITIALLY DEFERRED           │
│  CONSTRAINT trigger on AD_STOREDCOLUMN_DIRTY:                  │
│   1. SET LOCAL my.scd_refreshing = 'true'  (recursion guard)  │
│   2. read+delete all dirty rows for THIS txn, ordered by      │
│      Computation_Sequence_Number ASC                          │
│   3. call ad_scd_recompute once per target row, in            │
│      Computation_Sequence_Number order                        │
│   4. ad_scd_recompute takes a FOR UPDATE lock on the target    │
│      and writes UPDATE <col> = <fn>(<pk>) unconditionally      │
│      (no IS DISTINCT FROM guard — that would compute twice)    │
│  A function error here rolls back the whole transaction.      │
└─────────────────────────────────────────────────────────────┘

        │  (Refresh_Mode = 'Q')  dirty rows simply persist after commit
        ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 2' — ASYNC DRAIN  (background process, later)           │
│  StoredColumnQueueProcessor claims a batch (per client,       │
│  serially, in sequence order), recomputes, writes, deletes.   │
└─────────────────────────────────────────────────────────────┘
```

### Why two phases?

1. **Fast dirty collection** — triggers insert ids and return; no computation during DML.
2. **Non-blocking** — the deferred pass runs at commit time, not between statements.
3. **End-of-transaction consistency** — the function always sees the *final committed state* of all
   source data for that transaction.
4. **Exactly once per target** — bulk-updating N source rows produces exactly N recalculations after
   deduplication, not N × (number of statements).
5. **No feedback loops** — the recursion guard `my.scd_refreshing` makes the engine's own writes
   invisible to the dependency triggers, so a refresh cannot cascade into an infinite loop.

### The target-id resolver (how a source change finds its target)

`TARGET_ID_RESOLVER_SQL` is embedded verbatim into the generated trigger, inside a
`FOR v_target_id IN ( … ) LOOP`. It runs where PostgreSQL's `NEW` / `OLD` pseudo-records are
available, so it reads the changed row's fields directly. It can return **0, 1, or N** target ids.
Two canonical patterns:

**Pattern 1 — single, immutable target** (the FK to the target never changes on update):

```sql
SELECT COALESCE(NEW.c_order_id, OLD.c_order_id)
-- NEW on insert/update, OLD on delete
```

**Pattern 2 — reparenting** (the FK *can* be reassigned on update — a line moved to another order).
A single update is then a **two-target** event: the *old* parent's aggregate is now stale (a child
left) and the *new* parent's is stale (a child arrived). Both must recompute:

```sql
SELECT NEW.c_order_id WHERE NEW.c_order_id IS NOT NULL
UNION
SELECT OLD.c_order_id WHERE OLD.c_order_id IS NOT NULL
```

- `UNION` (not `UNION ALL`) collapses the two rows into one when `NEW = OLD` (an ordinary update
  that didn't move the line), keeping the queue clean.
- The `IS NOT NULL` guards make the same resolver safe for INSERT (`OLD` is null) and DELETE
  (`NEW` is null).

> **Rule of thumb:** immutable mapping → `COALESCE`. Walkable/reassignable FK → `UNION` form —
> otherwise every reparenting update corrupts one aggregate.

---

## 6. Chaining: `Computation_Sequence_Number`

A stored computed column may read *another* stored computed column. Chaining works by **ordering**,
not cascade: the engine's own recompute writes are suppressed by the recursion guard, so a chain
only works because **both columns are dirtied independently by the same source write**, and the
drain recomputes them in `Computation_Sequence_Number` order — the upstream (lower number) column
first, so the downstream column reads a *fresh* upstream value.

- Give the upstream column a **strictly lower** sequence number than the downstream column.
- Equal numbers do **not** order deterministically (ties break arbitrarily) — the build validator
  flags this (rule **V17**).
- A dependency **cycle** between stored columns is a hard build error (rule **V14**).

---

## 7. Read-only enforcement (belt and suspenders)

The stored value must **always** be the output of the function — never written by application code,
callouts, or manual edits. Read-only is enforced at *every* layer, so no single layer is the sole
gatekeeper:

- **DAL / Hibernate** — the property is mapped `insert="false" update="false"` with **no setter**
  (`Property.isStoredComputed()` drives `DalMappingGenerator`). A `save()` emits no SQL for the
  column; an accidental write is a compile error.
- **`AD_Field.ReadOnlyLogic = 'Y'`** — propagated automatically by a Gradle/ModuleScript step
  (`EnforceStoredComputedReadOnly`), a callout at field-creation time, and an `@OBDALEventHandler`
  (`ADFieldStoredComputedHandler`) on every save, including programmatic ones.
- **Schema Forge pipeline** — `resolve-curated.js` forces `readOnly` regardless of `decisions.json`;
  `push-to-neo.js` sets `Is_ReadOnly = true` in `ETGO_SF_FIELD`; the pipeline validator blocks any
  contract field backed by a stored computed column that is not read-only.
- **Generated React UI** — stored computed fields are emitted display-only, never as an input.

---

## 8. Configuring your own stored computed column

This is what a module author actually does. (Everything below is "declaration"; core owns the
triggers.)

### Step 1 — Write the computation function

A SQL function taking the target record id and returning the value:

```sql
CREATE OR REPLACE FUNCTION etscc_sumlineamounts(p_c_order_id VARCHAR)
RETURNS NUMERIC AS $$
  SELECT COALESCE(SUM(ol.linenetamt), 0)
  FROM   c_orderline ol
  WHERE  ol.c_order_id = p_c_order_id;
$$ LANGUAGE sql STABLE;   -- IMMUTABLE/STABLE required; VOLATILE is rejected (V7)
```

Requirements the validator enforces: arity 1 (single id arg), a return type compatible with the
column's reference/data type, and **not** `VOLATILE` (no side effects — the deferred pass must be a
pure read-then-write).

### Step 2 — Mark the `AD_Column` as stored computed

On the target column (e.g. `EM_ETSCC_LINETOTAL` on `C_Order`):

- `Computation_Mode = 'S'` (or `'Q'` / `'M'`)
- `Computation_Function = 'etscc_sumlineamounts'`
- `Refresh_Mode = 'S'`
- `Computation_Sequence_Number = 10` (raise it above any column this one reads)
- Leave `SQLLogic` **empty** (a stored column with both is a hard error, V1)

### Step 3 — Declare the dependencies

One `AD_COLUMN_COMP_DEPENDENCY` row per source table you must react to:

| Field | Example | Notes |
|-------|---------|-------|
| `Source_Table_ID` | `C_OrderLine` | The table whose changes trigger a refresh |
| `Insert_Event` / `Update_Event` / `Delete_Event` | Y / Y / Y | Which events fire |
| `Watched_Columns` | `LineNetAmt` (+ `QtyOrdered`) | Required for UPDATE; recompute only if one changed |
| `Target_ID_Resolver_SQL` | `SELECT COALESCE(NEW.c_order_id, OLD.c_order_id)` | Maps source row → target id(s); must never return NULL |
| `SeqNo` | 10 | Row ordering within the column's dependency set |

Exactly **one** of `Target_ID_Resolver_SQL` / `Target_Link_Column_ID` must be set (rule V11).

### Step 4 — Deploy

```bash
# from the Etendo root
./gradlew update.database     # validates, generates triggers, backfills existing rows
```

`update.database` runs the whole pipeline: build-time validation → trigger generation → initial
population of existing rows (see §10).

### Step 5 — Export back into the module

```bash
./gradlew export.database      # persists the AD config into your module's src-db/
```

The dependency rows carry an `AD_Module_ID` and export/import like any other AD dictionary data. The
**generated triggers** (`ad_scd_*`) are owned by the generator and excluded from DB Source Manager —
you never hand-edit or export them.

---

## 9. The pilot module — a concrete example

`com.etendoerp.storedcomputedcolumn` (v1.0.0) is the pilot. It contains **no Java** — only AD
configuration + a SQL test suite — and exercises the whole engine on the **Sales Order header**
(`C_Order`):

| Field label | Column | Formula | Refresh mode |
|-------------|--------|---------|--------------|
| **Line Total** | `EM_ETSCC_LINETOTAL` | `SUM(C_OrderLine.LineNetAmt)` for the order | `S` — the primary pilot |
| **Line Total Queued** | `EM_ETSCC_LINETOTAL_Q` | same sum | `Q` — exercises the async path |
| **Average Price** | `EM_ETSCC_avg_price` | `SUM(LineNetAmt) / NULLIF(SUM(QtyOrdered),0)` | `S` |

- **Computation functions:** `ETSCC_SUMLINEAMOUNTS(p_c_order_id)` and
  `ETSCC_AVERAGE_PRICE(p_c_order_id)`.
- **Source table:** `C_OrderLine`, resolved back to the order via `C_Order_ID`.
- **Watched columns:** `LineNetAmt` (all three) plus `QtyOrdered` (Average Price also depends on qty).
- **Events:** insert, update-of-watched-column, delete.

From a user's point of view: edit a line's quantity or amount (or add/remove a line) and save — the
header's **Line Total** / **Average Price** update **immediately** (synchronous columns), while
**Line Total Queued** catches up **within moments** (the async queue). Changing an *unwatched*
column (e.g. a line's description) enqueues nothing.

The module ships assertion-driven SQL harnesses under `src-test/sql/` (engine scenarios, queue
scenarios, Oracle parity, stress at volume, concurrency) that prove every guarantee against a real
database.

---

## 10. Oracle support

The **synchronous** mechanism is PostgreSQL-specific by design — it relies on
`DEFERRABLE INITIALLY DEFERRED` **constraint triggers** (Oracle has deferred constraints but not
deferred *trigger firing*), plus `pg_current_xact_id()`, `INSERT … ON CONFLICT`, partial indexes,
`IS DISTINCT FROM`, `SET LOCAL` session variables, and `pg_proc` introspection — none of which have
direct Oracle equivalents.

On Oracle, therefore:

- Stored computed columns run **only** in `Q` (queued) mode — a column configured `S` is treated as
  `Q`. `M` is also supported; `S` is rejected/normalised at validation time.
- Dependency triggers are PL/SQL and use `MERGE` for dedup instead of `ON CONFLICT DO NOTHING`.
- The deferred constraint trigger is **not** created; dirty rows always go to the async queue.
- The **same Java queue processor** drains both platforms; the same computation functions produce
  identical results. **Only the timing differs** — Oracle columns are eventually consistent.
- The AD field UI warns about eventual consistency when `Q` is selected.

Function/return-type/volatility introspection (validator rules V5–V7) is skipped on Oracle
(existence-only), and trigger-drift detection (V15) checks presence but not body.

---

## 11. Operating the feature

### Initial population (first activation)

When the generator deploys a column's `ad_scd_*` objects for the first time it backfills existing
rows according to the refresh mode:

- **`S`** → rebuilt inline during `update.database`, **unless** the target table exceeds
  `LARGE_TABLE_THRESHOLD` (100,000 rows) — above that it logs a WARN and enqueues a sentinel instead
  so the build doesn't block.
- **`Q`** → enqueues one **null sentinel per client** that has rows in the target table; each
  client's next queue-processor run does that client's full rebuild off-line.
- **`M`** → nothing; run **Rebuild Stored Column** when ready.

### The async queue processor (`Q` columns)

AD process **Stored Computed Column Queue Processor** (`Value = StoredColumnQueueProcessor`,
`AD_Process_ID = D35DC63A8838412890AEE01D31CD70A3`). **Not** shipped with an active schedule — each
installation creates a **Process Request** (*General Setup → Process Scheduling → Process Request*)
with the interval matching its lag tolerance. Parameters: **Max Records** (batch size, default 100)
and **Retry Threshold** (failures before dead-lettering, default 5).

> **Run exactly one drainer per client.** The queue is partitioned by `AD_Client_ID`; within a
> client it drains **serially** in `Computation_Sequence_Number` order, and that ordering is a
> correctness requirement for chained columns. Two concurrent drainers for the *same* client can
> reorder a chain and store a stale value. Different clients' drainers **may** run concurrently
> (disjoint partitions). `PREVENTCONCURRENT='Y'` is a helpful guard but not a guarantee (it's
> node-local) — enforce one-per-client operationally.

The `Q` drain **scales vertically** (bigger batches, more frequent runs), never horizontally — do
not add a second parallel Process Request.

### Failure handling (dead-lettering)

A per-target recompute failure is isolated in its own savepoint (the rest of the batch still
commits); the dirty row's `RETRY_COUNT` increments, `ERROR_MSG` is stored, and once `RETRY_COUNT`
hits **Retry Threshold** it is dead-lettered (`IS_IGNORED = 'Y'`, WARN-logged) so one poison row
can't stall the queue. A **fresh source change** on that `(column, target)` clears the ignored row
and gives it a clean retry.

```sql
SELECT ad_column_id, target_record_id, retry_count, error_msg
FROM   ad_storedcolumn_dirty
WHERE  is_ignored = 'Y'
ORDER  BY updated DESC;
```

### Manual repair & consistency check

- **Rebuild Stored Column** (`Value = StoredColumnRebuild`,
  `AD_Process_ID = DA0CCF7EF06F46588AD5E7EF5073FC81`) → re-derives every target row from current
  dependencies through the shared Java engine `StoredColumnRecomputer.rebuild(...)` (it does **not**
  call the PL/pgSQL `ad_scd_rebuild`, so it works on PostgreSQL *and* Oracle). Always safe to re-run.
  Use it after fixing a resolver/function, for `M`-column population, or to clear a dead-letter
  backlog once the root cause is fixed. It recomputes only the **caller's client's** rows — except a
  **System** caller (`AD_Client_ID='0'`), which repairs **all** clients. See the FAQ (§15 Q3) for how
  this differs from calling `ad_scd_rebuild` directly.
- **`ad_scd_check(<column_id>)`** → returns the count of target rows currently out of sync (stored
  value ≠ recomputed value). Use it to confirm the queue has caught up, e.g. after a bulk import, or
  as a scheduled health check to detect drift from a missing dependency declaration.

---

## 12. Build-time validation

Every `update.database` runs `StoredComputedValidator` (via the `ValidateStoredComputedColumns`
ModuleScript, and re-run as Gate 0 inside `GenerateStoredComputedTriggers`) **before** any trigger
DDL. It is **read-only and idempotent**. A broken definition aborts the build *before* it can deploy
inconsistent database objects. Findings are aggregated into a single report (errors first) thrown as
one `BuildException`.

Rules (HARD = aborts, SOFT = warns):

| Rule | Check | Severity |
|------|-------|----------|
| V1 | Stored column must have empty `SQLLogic` | HARD |
| V2 | Stored column must have a `Computation_Function` | HARD |
| V3 | Stored column must have `Computation_Sequence_Number > 0` | HARD |
| V4 | Computation function must exist in the DB | HARD |
| V5 | Function arity must be 1 (arg string/ID-typed) | HARD (arity) / SOFT (arg type) |
| V6 | Return type compatible with column reference family | HARD (void/trigger/record) / SOFT (mismatch) |
| V7 | Function should be `IMMUTABLE`/`STABLE`, not `VOLATILE` | SOFT |
| V8 | Active stored column must have ≥1 active dependency | HARD |
| V9 | Update-event dependency must declare ≥1 watched column | HARD |
| V10 | Watched column must belong to the dependency's source table | HARD |
| V11 | Dependency sets exactly one of `target_id_resolver_sql` / `target_link_column_id` | HARD |
| V14 | No dependency cycle among stored columns | HARD |
| V15 | Deployed triggers/functions must match current metadata | HARD (missing) / SOFT (drift) |
| V16 | FK/watched columns should have a supporting index | SOFT |
| V17 | On each `A → B` edge, `seq[A] < seq[B]` strictly | SOFT |

The `ETGO_SCD_VALIDATION` toggle governs enforcement:

| Value | Behaviour |
|-------|-----------|
| `enforce` (default) | Hard violations abort `update.database`; warnings logged. |
| `warn` | **All** violations logged as warnings; build proceeds. **Escape hatch only.** |

```bash
# One-off warn-only build (does not block on hard violations):
./gradlew update.database -DETGO_SCD_VALIDATION=warn
```

Resolution order: JVM `-DETGO_SCD_VALIDATION=…` → env var → default `enforce`. Use `warn` only to
unblock an emergency build or to survey the full backlog on a legacy DB; restore `enforce` as soon
as definitions are fixed — running `warn` permanently defeats the guard.

---

## 13. Generated database objects (reference)

Owned by `GenerateStoredComputedTriggers`; never hand-edited, excluded from DB Source Manager. Each
carries a comment block with `AD_Column_ID`, generator version, and a SHA-256 hash of the dependency
metadata (the staleness signal for incremental regeneration).

| Object | Kind | Role |
|--------|------|------|
| `ad_scd_dep_*` (per dependency row) | PL/pgSQL fn + AFTER trigger on the source table | Phase 1 dirty collection (watched-column check, resolver, dirty insert) |
| `ad_scd_dirty_aiu` | `DEFERRABLE INITIALLY DEFERRED` CONSTRAINT trigger on `AD_STOREDCOLUMN_DIRTY` | Phase 2 deferred recalculation (`S`) |
| `ad_scd_process_dirty` | PL/pgSQL fn | The deferred-pass body: guard → ordered drain → compute → `IS DISTINCT FROM` write |
| `ad_scd_recompute` | PL/pgSQL fn | Recompute one target row for one column |
| `ad_scd_rebuild(<column_id>)` | PL/pgSQL fn (generic, core) | Idempotent full rebuild |
| `ad_scd_check(<column_id>)` | PL/pgSQL fn (generic, core) | Count of stale rows |
| `my.scd_refreshing` | session variable | Recursion guard — dependency triggers no-op while set |

### The dirty table — `AD_STOREDCOLUMN_DIRTY`

One row = one target record of one column that needs recalculation. Key columns: `AD_Column_ID`,
`Target_Record_ID` (NULL = "recompute all rows for this `AD_Client_ID`" sentinel), `Transaction_ID`
(`pg_current_xact_id()`, NULL on Oracle), `Refresh_Mode` + `Computation_Sequence_Number` (copied from
the column at insert), `Created`, `Retry_Count` / `Error_Msg` / `Is_Ignored` (dead-lettering).
Dedup constraints: `UNIQUE (AD_Column_ID, Target_Record_ID, Transaction_ID)` and a per-client partial
unique index for the null sentinel.

### Key core source files

| File | Role |
|------|------|
| `src-util/modulescript/.../GenerateStoredComputedTriggers.java` | Generates/deploys all `ad_scd_*` objects; backfill; Gate 0/1 |
| `src-util/modulescript/.../StoredComputedValidator.java` | V1–V17 build validation (shared pure logic) |
| `src-util/modulescript/.../ValidateStoredComputedColumns.java` | ModuleScript entry point for the build gate |
| `src-util/modulescript/.../EnforceStoredComputedReadOnly.java` | Propagates `AD_Field.ReadOnlyLogic='Y'` |
| `src/org/openbravo/erpCommon/ad_process/StoredColumnQueueProcessor.java` | Async `Q` drainer (per-client, serial) |
| `src/org/openbravo/erpCommon/ad_process/StoredColumnRebuild.java` | **Rebuild Stored Column** process |
| `src/org/openbravo/erpCommon/ad_process/StoredColumnRecomputer.java` | Dialect-neutral recomputer (`rebuild(con, columnId, clientId)`) |
| `src/org/openbravo/event/ColumnStoredComputedHandler.java` | Runtime DAL observer (shares V1–V3/V14 pure logic) |
| `src/org/openbravo/event/ADFieldStoredComputedHandler.java` | Enforces read-only on every `AD_Field` save |
| `src/org/openbravo/base/model/{Column,Property}.java`, `dal/core/DalMappingGenerator.java` | `isStoredComputed()` + `insert/update="false"` mapping |

---

## 14. Choosing wisely — when *not* to use it

- **Needs to be exact mid-transaction?** Stored columns reflect **transaction-boundary** state only.
  Business logic that needs a fresh value *during* a transaction must call the computation function
  directly. (`Q`/Oracle columns are only *eventually* consistent.)
- **The value is cheap to compute and rarely read?** A virtual `SQLLogic` column may be simpler — no
  triggers, no queue.
- **The computation has side effects?** Not allowed — the function must be pure (`VOLATILE` is
  rejected). The deferred pass is a pure read-then-write.
- **Write-heavy source, read-light target?** You'd pay commit-time overhead on every source write
  for a value almost nobody reads. Consider `Q`, or reconsider storing it at all.

The engine deliberately does **not** guarantee that every computed column is a good storage
candidate — eligibility is a design decision, validated but not assumed.

---

## 15. FAQ — PostgreSQL internals (`S`) & initial calculation

### Q1. How does the `S` (synchronous) case actually work at the PostgreSQL level?

Two sets of objects, both deployed by `GenerateStoredComputedTriggers` during `update.database`:

**A. The static engine** — deployed once, idempotently (`deployEngine`), shared by all columns:

| Function / trigger | Role |
|--------------------|------|
| `ad_scd_recompute(column_id, target_id)` | Recomputes ONE target row. Resolves the physical table/column/function/PK from `AD_COLUMN`, takes a `FOR UPDATE` lock on the target row, and runs `UPDATE <table> SET <col> = <fn>(<pk>) WHERE <pk> = target`. The write is **unconditional** — no `IS DISTINCT FROM` guard (guarding would run the aggregate twice per row; the enqueue trigger's watched-column check already filters no-op source changes upstream). |
| `ad_scd_process_dirty()` | The deferred-drain orchestrator (below). |
| `ad_scd_rebuild(column_id)` | Full rebuild — recompute every row of the column. |
| `ad_scd_check(column_id)` | Count of stale rows (`<col> IS DISTINCT FROM <fn>(<pk>)`). |
| `ad_scd_dirty_aiu` | `CREATE CONSTRAINT TRIGGER … AFTER INSERT ON ad_storedcolumn_dirty DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.refresh_mode = 'S') EXECUTE FUNCTION ad_scd_process_dirty()`. |

**B. Per-dependency enqueue triggers** — one `ad_scd_<depId>_trf()` function + AFTER trigger on each
source table, declared for exactly the configured events.

End-to-end sequence for an `S` column:

1. **Business DML on a source row** (e.g. edit a `C_OrderLine`). The per-dependency AFTER trigger
   fires. On UPDATE it first checks the watched columns; if none changed it returns immediately.
2. It runs `TARGET_ID_RESOLVER_SQL` and does `INSERT … ON CONFLICT DO NOTHING` into
   `ad_storedcolumn_dirty`, stamping `transaction_id = pg_current_xact_id()`, `refresh_mode = 'S'`,
   and the column's `computation_sequence_number`.
3. That INSERT **arms** the deferred constraint trigger `ad_scd_dirty_aiu`. Because it is
   `DEFERRABLE INITIALLY DEFERRED` it does not run now — it is queued to fire **at COMMIT**, once per
   inserted dirty row.
4. **At commit** `ad_scd_process_dirty()` fires. The first firing:
   - checks the `my.scd_refreshing` GUC; if already `'true'`, returns immediately;
   - sets `my.scd_refreshing = 'true'` (transaction-local, `set_config(…, true)`);
   - selects all dirty rows of **this transaction** (`transaction_id = pg_current_xact_id()`) with
     `refresh_mode = 'S'` and non-null target, **ordered by `computation_sequence_number,
     target_record_id`**;
   - calls `ad_scd_recompute(column, target)` for each;
   - `DELETE`s all processed `S` dirty rows of this transaction.
5. Firings 2..N (from the other inserted dirty rows of the same transaction) see
   `my.scd_refreshing = 'true'` and return immediately → the whole queue is drained **exactly once
   per transaction**.
6. The same `my.scd_refreshing` GUC is what stops the engine's own `UPDATE`s to the target table
   from re-firing the enqueue triggers — no recursion.

Everything runs in the **same backend process, same transaction, at commit** → the value is
transactionally consistent, and if the computation function raises, the **entire business
transaction rolls back**. Chained columns work only because both are dirtied by the same source
write and drained in `computation_sequence_number` order in step 4 — the engine never cascades its
own writes.

### Q2. I created or updated a stored computed column with `S` — how do I do the initial calculation?

It depends on whether it's a **first activation** or an **update to an existing definition**.
`GenerateStoredComputedTriggers` (run automatically by `update.database`) only auto-populates a
column on **first activation** — detected when *none* of its dependency functions existed before
this run.

**Case A — brand-new `S` column (first activation).** Just run:

```bash
./gradlew update.database
```

The initial calculation happens **automatically**:
- **≤ 100,000 target rows** (`LARGE_TABLE_THRESHOLD`) → rebuilt inline during the build, via
  `DO $$ … PERFORM ad_scd_rebuild('<column_id>') … $$` (with `my.scd_refreshing` set). Nothing else
  to do.
- **> 100,000 rows** → it does **not** rebuild inline (to avoid a long-blocking build); it logs a
  WARN and enqueues one per-client null sentinel instead. Finish the population by running the
  **Stored Computed Column Queue Processor** once, or a manual rebuild (below).

**Case B — you UPDATED an existing `S` column** (changed the computation function, a resolver, the
watched columns, or fixed a bug). This is **not** a first activation, so `update.database`
regenerates the triggers but **deliberately leaves the already-stored values untouched** — it does
not recompute existing rows. From here on, new source changes keep the value fresh, but historical
rows still hold the old value. To recompute the existing data you must trigger a rebuild
**manually**:

- **AD process (recommended on multi-client tenants):** run **Rebuild Stored Column**
  (`StoredColumnRebuild`). Client-scoped (a System caller rebuilds all clients); PG + Oracle.
- **SQL directly (dev / single-tenant / System-level global repair):**
  ```sql
  SELECT ad_scd_rebuild('<AD_Column_ID>');   -- recompute every row (idempotent, always safe)
  SELECT ad_scd_check('<AD_Column_ID>');     -- how many rows are still stale?
  ```
  ⚠️ `ad_scd_rebuild` recomputes **all rows of the table, with no client filter** — see Q3.

> **Rule of thumb:** a *new* `S` column → `update.database` populates it (inline under 100k rows). A
> *changed* `S` definition → `update.database` only refreshes the triggers; run **Rebuild Stored
> Column** (or `ad_scd_rebuild(...)`) yourself to backfill existing rows.

### Q3. What does `ad_scd_rebuild(<column_id>)` do — and how does it differ from the process?

`ad_scd_rebuild` is the engine's **PL/pgSQL full-rebuild** function (deployed with the static
engine). It recomputes and rewrites the stored value of **every row of the target table** for one
column:

```sql
CREATE OR REPLACE FUNCTION ad_scd_rebuild(p_column_id varchar)
RETURNS integer AS $$
DECLARE v_table varchar; v_pk varchar; v_id varchar; v_cnt integer := 0;
BEGIN
  -- 1. resolve the physical table + PK from AD_COLUMN / AD_TABLE metadata
  SELECT lower(t.tablename),
         lower((SELECT k.columnname FROM ad_column k
                 WHERE k.ad_table_id = c.ad_table_id AND k.iskey = 'Y'))
    INTO v_table, v_pk
    FROM ad_column c JOIN ad_table t ON t.ad_table_id = c.ad_table_id
   WHERE c.ad_column_id = p_column_id;
  IF v_table IS NULL OR v_pk IS NULL THEN RETURN 0; END IF;        -- unknown column → no-op
  -- 2. iterate EVERY row of the target table
  FOR v_id IN EXECUTE format('SELECT %I::varchar AS id FROM %I', v_pk, v_table)
  LOOP
    PERFORM ad_scd_recompute(p_column_id, v_id);                   -- 3. recompute row by row
    v_cnt := v_cnt + 1;
  END LOOP;
  RETURN v_cnt;                                                    -- 4. count of rows touched
END;
$$ LANGUAGE plpgsql;
```

1. Looks up the physical **table** and **primary key** for the column from AD metadata; returns `0`
   if the column can't be resolved (no-op).
2. Iterates **every** row of the target table.
3. For each, calls `ad_scd_recompute(column_id, target_id)` — which `FOR UPDATE`-locks that row and
   writes `UPDATE <table> SET <col> = <fn>(<pk>)` unconditionally.
4. Returns the number of rows recomputed. Idempotent — always safe to re-run.

**Key difference vs. the AD process — client scoping:**

| Path | How it recomputes | Scope | Platforms |
|------|-------------------|-------|-----------|
| `SELECT ad_scd_rebuild('<id>')` (direct SQL / psql) | this PL/pgSQL function | **ALL rows of the tenant — no client filter** | PostgreSQL only |
| **Rebuild Stored Column** AD process | `StoredColumnRecomputer.rebuild(...)` in **Java** (does *not* call `ad_scd_rebuild`) | only the **caller's client** (System → all clients) | PostgreSQL + Oracle |

So the AD process routes through the Java recomputer precisely to scope by client and to support
Oracle; the raw `ad_scd_rebuild` SQL function touches every row of the table regardless of client.
On a multi-client tenant, prefer the **AD process**; use `ad_scd_rebuild` via psql for
dev / single-tenant, or for a deliberate global repair run as **System**.

---

## 16. Source references

The design/spec docs below live in the **Etendo core** checkout under `epl-1807/` (the epic
working folder), not in this module:

- Spec: `epl-1807/REQUIREMENTS.md`
- Async operations & validation toggle: `epl-1807/OPERATIONS.md`
- Resolver patterns (COALESCE vs UNION): `epl-1807/NOTES.md`
- Phase plans: `epl-1807/PLAN-PHASE*.md`
- Pilot module: `com.etendoerp.storedcomputedcolumn` (README + `src-test/sql/`)
- Spanish version of this guide: `COLUMNAS-COMPUTADAS-ALMACENADAS.md` (same folder)

> **Naming note.** The original `REQUIREMENTS.md` draft used provisional `sf_*` names
> (`sf_rebuild`, `sf_check`, `sf.refreshing`, `AD_StoredColumn_Dirty`). The **merged
> implementation** uses `ad_scd_*` (`ad_scd_rebuild`, `ad_scd_check`), the guard `my.scd_refreshing`,
> and tables `AD_STOREDCOLUMN_DIRTY` / `AD_COLUMN_COMP_DEPENDENCY`. This guide uses the final,
> merged names.
