# MCP usage telemetry (`ETGO_MCP_USAGE`) — Track B1

One row per MCP tool call, so that what agents actually do — and where they actually get stuck — is
measurable on real traffic rather than only on the synthetic traffic of the test harness.

Design source: `schema_forge/docs/plans/2026-09-11-mcp-test-harness-design.md`, Track B,
decisions D23–D33. This document covers **B1 only**. The Mixpanel exporter (B2) and the
`neo_feedback` tool (B3) are not built.

## The two rules

**1. Telemetry must never break or slow a tool call (D25).** It is not a convention here, it is
structural, and it is the primary correctness requirement:

- The row is written on a connection borrowed straight from `ExternalConnectionPool` — never from
  `OBDal`, never from the Hibernate `SessionHandler`. There is no shared session, no shared
  connection and no shared transaction, so a failing telemetry `INSERT` has nothing to roll back
  but itself. This is the specific hazard D25 names: an in-transaction write would roll back the
  user's order.
- The business transaction is already committed and closed by `McpSessionManager.executeInContext`,
  and the response is already written to the caller, before a row is even enqueued.
- The write happens on a single daemon thread fed by a **bounded** queue (2 000 rows). The caller
  pays a queue offer, not a database round-trip. A full queue drops the row.
- `McpUsageLogger.enqueue`, the writer task, and `McpServlet.recordToolCall` each catch `Throwable`.
  There is no code path from a telemetry failure back to the caller or to the tool result.

Accepted costs, stated plainly: rows buffered at shutdown are lost, and rows are dropped rather
than queued without bound under sustained overload. Both are the correct trade — telemetry is
diagnostic, the user's transaction is not.

**But neither loss is silent.** D24 makes this table the source of truth on the argument that
Mixpanel can drop events and the table cannot, and a silent gap is indistinguishable from "nobody
called the MCP that hour" — a wrong conclusion somebody will eventually draw. So every unpersisted
row (queue full, no connection pool, failed `INSERT`, still queued at shutdown) increments
`McpUsageLogger.droppedRows` and is surfaced at WARN: the first one immediately, then at most one
line per minute carrying the running total, plus a final count when `McpServlet.destroy()` stops the
writer. The gap is made *visible*, deliberately not *queryable* — a sentinel row was considered and
rejected, because it would touch the `row_type` check constraint and D31's contract for something a
log line already answers.

**2. Shape, never content (D25).** The table records *that* `neo_create` was called on `sales-order`
touching `businessPartner` and `orderDate`. It does not record the partner, the amount, or anything
the user typed. `fields_touched` is built by reading JSON **keys** (`McpUsageTelemetry.fieldsTouched`);
a `fields` array is a projection list, whose entries are field names too. The error envelope's
`detail` is deliberately *not* stored — it is prose about one call and can quote what the agent sent.

## Table

`ETGO_MCP_Usage`, AD table id `91714108E63E46E7882A53C5D84F8140`, data access level 3
(Client/Organization). Standard AD audit columns plus:

| Column | Type | Meaning |
|---|---|---|
| `Session_Key` | VARCHAR(200) | MCP session, so a sequence of calls reads as one task |
| `Tool_Name` | VARCHAR(200), NOT NULL | `neo_create`, `neo_list`, … |
| `Verb` | VARCHAR(200) | CRUD/action verb the call resolved to |
| `Target_Entity` | VARCHAR(200) | `spec` or `spec/entity` |
| `Fields_Touched` | TEXT | field **names**, comma-separated, sorted |
| `Outcome` | VARCHAR(200), NOT NULL | `ok` / `error` (check constraint) |
| `Error_Code` | VARCHAR(200) | canonical code (`validation_error`, `not_found`, `server_error`, …) |
| `Duration_MS`, `Req_Bytes`, `Resp_Bytes` | NUMERIC | cost and payload size — feeds M3 / ACE |
| `Client_Name`, `Client_Version` | VARCHAR(200) | from the MCP `initialize` handshake |
| `Row_Type` | VARCHAR(200), NOT NULL | `tool_call` / `feedback` (check constraint) |
| `Payload` | TEXT | null on every `tool_call` row; reserved for B3 feedback reports |

Indexes: `etgo_mcp_usage_cli_created (ad_client_id, created)` and `etgo_mcp_usage_session
(session_key)`.

Two names deviate from the design table on purpose:

- **`Target_Entity`, not `entity` and not `entity_name`** — the DAL generator turns a column into an
  accessor on a `BaseOBObject` subclass, so any column whose accessor `BaseOBObject` already defines
  makes the generated class fail to compile. `entity` collides with `getEntity()` and `entity_name`
  collides with `getEntityName()` — the second one was found the hard way, by breaking the build.
  Before naming a column here, check its accessor against the full reserved set:
  `getClient`, `getCreatedBy`, `getCreationDate`, `getEntity`, `getEntityName`, `getId`,
  `getIdentifier`, `getInheritedFrom`, `getInstance`, `getOrganization`, `getSeparator`,
  `getUpdated`, `getUpdatedBy`, `getValue`, `isActive`, `isAllowRead`, `isNew`, `isNewOBObject`,
  plus the matching setters and `setDefaultValue`.
- **`Session_Key`, not `session_id`** — in Etendo a `*_ID` column means a foreign key; a plain
  VARCHAR named `Session_ID` misrepresents itself to the model validator and to anyone reading it.

The generated DAL class is `com.etendoerp.go.schemaforge.data.McpUsage` — the package comes from the
module's `AD_PACKAGE`, not from `AD_TABLE`, which stores the simple class name only. The **table**
keeps the `ETGO_` DB prefix (D29); only the Java class drops it, to match the module's own naming
(`EmailSafety`, `Invitation`, `SFSpec`).

Nothing in this module imports the entity — the writer uses plain JDBC — so the class name is free
to change until something does.

Harness traffic is **not** separated (D30) — no `source` column. `client_name` already identifies
the caller. There is **no retention policy** (D33): no purge, no aggregation. Revisit after a month
of real traffic.

## The session key

The design assumes an MCP session exists. It did not: `McpServlet` is stateless and nothing minted a
session identifier — `Mcp-Session-Id` appeared only in the CORS allow-list.

`McpUsageTelemetry.openSession` now mints one during `initialize`, stores the handshake's
`clientInfo`, and `McpServlet` echoes the key in the `Mcp-Session-Id` response header — where the
Streamable HTTP transport says it belongs. A spec-conformant client returns it on every later
request, which is what ties a sequence of tool calls together and what carries `client_name` forward
(`initialize` and `tools/call` are separate HTTP requests with nothing else in common).

A client that ignores the header still works: its rows carry a null `session_key` and no client
name. The registry holds at most 1 000 sessions and evicts oldest-first; losing an entry costs the
client name on later rows of a very old session and can never fail a call.

## Opt-out (D28)

On by default. An instance opts out with `mcp.telemetry.enabled=false` in `Openbravo.properties`.

It is a property and not an AD Preference on purpose: D28 asks for a *per-instance* switch, a
Preference is per client/org/user/role, and a property needs neither an `OBContext` nor a database
read on the hot path.

## Reading the table from a deployed instance

`schema_forge/scripts/mcp-usage-dump.sh <ssh-alias>` exports the table as JSONL, one object per
row, from any instance reachable over SSH. The alias is the only connection argument: the script
reads that host's own `gradle.properties` and runs `psql` there, so no credential travels to the
operator's machine.

```bash
scripts/mcp-usage-dump.sh etendo-go-experimental --count
scripts/mcp-usage-dump.sh etendo-go-experimental --row-type feedback
scripts/mcp-usage-dump.sh etendo-go-production --mark-reviewed
make mcp-usage HOST=etendo-go-experimental MARK_REVIEWED=1     # same thing from the Makefile
```

Dumps land in `schema_forge/mcp-usage/<ssh-alias>-<timestamp>.jsonl`, a folder whose contents are
gitignored — this is real telemetry and does not belong in a commit. Override with `--out`.

**`isactive = 'N'` means reviewed.** The table has no review column, so `isactive` is repurposed as
one. This is safe because the writer always inserts `'Y'` (see `INSERT_SQL` in `McpUsageLogger`) and
nothing in the module ever reads the column back — flipping it is inert for the runtime. It is a
convention, not a constraint: if the table is ever surfaced as an AD window, the standard grid hides
`'N'` rows and any user can flip them back.

Exports skip reviewed rows by default, so repeated runs return only what is new; `--include-reviewed`
brings them all back. `--mark-reviewed` marks exactly the rows it exported, in the same statement
that reads them (`UPDATE … RETURNING`), so no row can be marked without having been written out.

The `payload` column stays a JSON **string** in the output — it is only populated on `feedback` rows.
Read those with `jq -r 'select(.row_type=="feedback") | .payload | fromjson'`.

## Code map

| File | Role |
|---|---|
| `mcp/McpUsageRow.java` | immutable value object; holds no Etendo type, so it stays valid off-thread |
| `mcp/McpUsageTelemetry.java` | derives verb / entity / field names / error code; owns the session registry |
| `mcp/McpUsageLogger.java` | bounded queue, daemon writer thread, own connection, own transaction |
| `mcp/McpServlet.java` | `handleInitialize` opens the session; `doPost` calls `recordToolCall` after the response is written |

---

## B3 — `neo_feedback`

A tool the calling agent invokes to report, in its own words, what confused it, what it could not
find, what it had to guess at, and what failed.

**Why it is the highest-value row here:** B1 sees *that* an agent called `neo_schema` five times and
gave up. It cannot see what the agent was *trying to do*. That intent is what turns a metric into an
actionable defect, and the agent is the only party that holds it.

### The payload schema is not ours (D27)

`McpFeedbackVerdict` mirrors `schema_forge/mcp-tests/runner/verdict.py` field for field:
`schemaVersion`, `outcome` (OKAY/ERROR/MIXED), `summary`, `achieved`, `plannedApproach`, `howKnown`,
`frictions[]` (`what`, `cost`, `phase`), `failures[]` (`tool`, `payload`, `error`, `recovered`,
`howRecovered`), `wastedCalls[]` (`tool`, `expected`, `whatHappened`), `suggestions[]` (`what`,
`kind`, `wouldHaveSaved`). Identity is
the point: a friction a lab probe reports and the same friction a real client's agent reports must
land as the same row, so the two corpora aggregate. Change `verdict.py` and this together, and bump
`schemaVersion` on both.

#### Schema v2 — what the agent expected, not only what broke

v1 recorded what went wrong and nothing about what was meant to happen, which left two blind spots.

- **`wastedCalls[]`** — a call that returned 200 and was useless left no trace at all: no error, no
  `failures[]` entry, nothing in the transcript marking it, even though it cost exactly as much as a
  failure and is precisely what bad metadata produces. It gets its own list rather than being folded
  into `frictions[]` so it can be *counted*.
- **`plannedApproach` / `howKnown`** — the plan the agent had and where it got it from. That is the
  question that says what to document. Both are **recall**, not the plan: they are asked after the
  task is over, by an agent that already knows how it turned out and will reconstruct something
  tidier than what it had. The bias is accepted on purpose, because the objective call sequence sits
  in the same session's rows — the contrast between the claimed plan and the executed one is
  informative even when the claim is polished. Read them against the transcript, never alone.

Both field descriptions say outright that "I had no plan" and "I guessed" are acceptable answers. A
schema that makes the honest answer feel wrong gets fiction back, and fiction here is worse than a
blank.

**A v1 report still validates.** All three additions are optional: an older client omits them and
`normalize` writes `null` / `[]` in their place. This tool is called by agents we do not control, so
rejecting an older shape would silence exactly the reporters we most want to hear from.

#### Schema v3 — the one forward-looking field, made countable

`suggestions[]` was a flat array of strings: the only place an agent could say what would have made
the task easy, and the only field that could not be counted, grouped or compared across reports. It
is now an array of objects — `what`, `kind`, `wouldHaveSaved`.

`kind` is one of `shortcut`, `missingCapability`, `clearerDocs`, `betterMetadata`, `other`.

- **`other` is a normal answer, not a last resort.** A closed enum with no way out makes an agent
  cram a bad fit into a real category and corrupts the counts in silence. What accumulates under
  `other` is how we learn which category is missing next, so the description says outright that
  picking it is fine.
- **Nothing degrades *into* `other` — the fallback is `null`.** "The agent chose `other`" and "the
  agent did not classify this" are different facts, and merging them destroys the only signal
  `other` carries: it is the bucket we read to decide what the next `kind` should be, so
  unclassified entries landing in it would have us invent a category out of noise. `kind` is
  non-null only when the agent deliberately picked one of the five values; absent, blank and
  unrecognised all store `null`, and `kind` is therefore not a required property of the tool
  schema.
- **There is no defect/bug/error value, and there must never be one.** A calling agent cannot
  distinguish "the product is broken" from "I failed to find it"; letting it label its own ignorance
  as a defect would poison the corpus with authority it does not have. That call belongs to whoever
  reads the report.
- **`wouldHaveSaved` is the anti-waffle field.** It asks what the suggestion would have saved *on
  this task*, and says plainly that "nothing here" is an acceptable answer — otherwise the model
  invents a payoff.

**Compatibility is not automatic this time.** v3 changes the *type* of an existing field, so a v1/v2
client sending `suggestions:["…"]` would break. Those entries are **upgraded, never rejected**: the
string becomes `what`, `kind` becomes `null` (a legacy client had no field to classify with, so it
never chose anything) and `wouldHaveSaved` is left empty. One stored shape is the whole point of
D27 — an old client's suggestion and a new one have to land in the same bucket, and they cannot if
half the corpus is strings. An unrecognised `kind` such as `"defect"` becomes `null` by the same
reasoning: that client *did* classify, but not into a category we recognise, so crediting it with a
deliberate `other` would be a claim we cannot support. Nothing throws, and no value outside the five
is ever stored.

### One row, written by the servlet

A `neo_feedback` call *is* a tool call, so it produces exactly one row (D31): `row_type = 'feedback'`
with the normalized report in `Payload`, carrying the same session, tenant, timestamp and client
columns as everything else — which is what makes the feedback readable as part of the session's
story rather than an isolated complaint. `McpFeedbackTool` validates and rate-limits but writes
nothing, so there is no second writer and no shared mutable state.

A rejected or rate-limited call is an ordinary error row with an empty `Payload`.

### Data, never instructions

Every string is written by an agent outside our trust boundary. `McpFeedbackVerdict.normalize`
rebuilds the JSON from recognised keys instead of storing what arrived, so an unexpected key cannot
ride along and later be read as though this module had put it there. Nothing downstream executes,
interprets, or acts on the content. Caps: 64 000 chars per report, 50 entries per list, 4 000 chars
per text field.

Rule 2 does **not** apply to `Payload` — this row type is the documented exception, and
`failures[].payload` holds the literal arguments of a failing call. Every other column stays
shape-only as always.

### Rate limit

10 accepted reports per session per hour, bounded at 1 000 tracked sessions. A client that does not
echo `Mcp-Session-Id` shares one anonymous bucket — deliberately the stricter reading, because an
unidentified flooder is the case the limit exists for.

### Getting agents to use it

- The **tool description** says plainly that reporting friction is wanted, costs nothing, and is
  never held against the agent — including failures it worked around itself.
- **Error envelopes carry the invitation** under a `feedback` key, on the two generic builders in
  `McpToolResponses`.

> Deviation from the brief, deliberate: the invitation is a **sibling of `seeAlso`, not a reuse of
> it**. `seeAlso` is single-valued and on the write paths it already carries a `docs` recipe, so
> writing the invitation there would delete the more actionable pointer at exactly the moment the
> agent needs it.

`neo_feedback` is read-tier (`neo:read`): gating it behind `neo:write` would silence exactly the
read-only sessions most likely to get lost.

---

## B2 — Mixpanel through the existing sink

`McpUsageLogger` emits `NeoTelemetryEvents.BACKEND_MCP_TOOL_CALL_COMPLETED` through the module's
existing `NeoTelemetryService` (`schemaforge/telemetry/`). **No second Mixpanel client, no second
config, no second sink.** Emission runs on the writer thread *after* the row is committed, so D24's
ordering is literal: the authoritative record exists before the projection is attempted.

What travels is shape only, same rule as the table: tool, verb, target entity, field **names**,
outcome, error code, row type, client name, latency, byte counts. **`Payload` never travels** — a
feedback row's report is free text and contains the literal arguments of failing calls, which is
precisely the content the rule exists to keep off a third party.

Opt-out is independent: `mcp.telemetry.mixpanel.enabled=false` keeps the table and refuses the third
party. Turning the table off turns the projection off too; not the reverse.

Adding the MCP property keys to `NeoTelemetryService`'s allowlist is additive — it permits more, it
cannot change what an existing caller emits, and the denylist is still consulted first.

EU residency verified, not re-implemented: `MixpanelNeoTelemetryConfig.DEFAULT_API_HOST` is already
`https://api-eu.mixpanel.com`.

### Known gaps in the shared sink — NOT fixed here

Out of scope on purpose: that sink is shared with `NeoCrudHandler`, `NeoWriteRefusalLog` and
`ReconciliationKpiTelemetry`, and changing its behaviour has blast radius beyond ETP-5306.

1. Posts to `/track`, not `/import` (untrusted-client endpoint, 5-day event window).
2. No `$insert_id`, so a retry after a timeout double-counts. Ours would come from the row id.
3. No batching, no 429 backoff.
4. The POST is **synchronous on the calling thread** with a 5 s timeout. D32 already protects *us*
   — `McpUsageLogger` has its own queue and writer thread — but not the other three callers.
5. `distinct_id` is the fixed string `neo-backend`, so every tenant collapses into one Mixpanel
   user. D26's salted hash of `ad_client_id` is not implemented, and per-tenant cohorts are
   therefore impossible today.
6. The sink logs **INFO per event**, which at MCP call volume is a lot of log.
