# NEO Headless API

## 1. Overview

NEO Headless is a metadata-driven REST API layer for Etendo ERP. It exposes Etendo windows and processes as JSON APIs without requiring hand-written endpoints. An administrator defines a **spec** (backed by an AD_Window or AD_Process), selects which tabs, columns, and HTTP methods to expose, and NEO Headless generates the full CRUD and process-execution endpoints at runtime. Custom business logic can be injected via CDI hook handlers.

The module lives in `com.etendoerp.go` (Java package `com.etendoerp.go.schemaforge`). The servlet is registered at `/sws/neo/*` and authenticates via JWT bearer tokens through the SecureWebServices infrastructure.

---

## 2. Architecture

```
Client (Bearer JWT)
    |
    v
NeoServlet (/sws/neo/*)
    |
    +-- authenticateJwt() --> SecureWebServicesUtils (decode + OBContext)
    |
    +-- parsePath() --> NeoPathInfo (specName, entityName, recordId, selector?, action?)
    |
    +-- findSpec(specName) --> ETGO_SF_Spec (active, by name)
    |
    +-- Route by spec type:
    |     |
    |     +-- Process spec (type P) --> NeoProcessService.executeProcess()
    |     |
    |     +-- Window spec (type W):
    |           |
    |           +-- Selector path? --> NeoSelectorService
    |           +-- Action path?   --> handleButtonAction() --> NeoProcessService
    |           +-- CRUD path?     --> findEntity() --> check method flags
    |                 |
    |                 +-- javaQualifier set? --> CDI lookup NeoHandler --> handler.handle(ctx)
    |                 |     |
    |                 |     +-- returns NeoResponse? --> write response
    |                 |     +-- returns null?        --> fall through to default
    |                 |
    |                 +-- default: DataSourceServlet (Etendo RX internal)
    |
    v
  Response (JSON)
```

Key components:
| Class / package | Responsibility |
|-------|----------------|
| `NeoServlet` | Main entry point. JWT auth, path parsing, routing, parent-child filtering. |
| `NeoHandler` | CDI hook interface. Return `NeoResponse` or `null` to fall through. |
| `NeoContext` | Immutable request context (builder pattern). Carries spec, entity, method, body, tab, OBContext. |
| `NeoResponse` | Response wrapper with static builders: `ok()`, `created()`, `noContent()`, `error()`. |
| `NeoSelectorService` | Selector facade for FK dropdown listing and querying. Delegates metadata discovery and policy dispatch to selector subpackages. |
| `schemaforge.selector.meta` | Selector descriptor and context metadata (`SelectorMeta`, `RichFieldMeta`, `SelectorContextResolver`, `SelectorDescriptorResolver`). |
| `schemaforge.selector.policy` | Selector policy SPI/registry for context filters, reference overrides, virtual columns, and response enrichment. |
| `NeoProcessService` | Process execution (OBUIAPP, Classic, scheduling, DB procedure). Parameter validation and process metadata. |
| `PopulateSpecHelper` | Auto-populates entities and fields from AD metadata. |
| `PopulateSpecProcess` | AD_Process (button) wrapper around PopulateSpecHelper. |
---

## 3. Database Schema

Three custom tables store the API specification. All belong to the `ETGO` module prefix.

### ETGO_SF_SPEC

Top-level specification record. Each spec maps to either an AD_Window (CRUD) or an AD_Process (POST-only).

| Column | Type | Notes |
|--------|------|-------|
| `ETGO_SF_SPEC_ID` | VARCHAR (PK) | UUID |
| `NAME` | VARCHAR | Unique. Used as the first segment of the URL path. |
| `DESCRIPTION` | VARCHAR | Optional human-readable description. |
| `SPEC_TYPE` | CHAR(1) | `'W'` = Window/CRUD, `'P'` = Process/POST-only. |
| `AD_WINDOW_ID` | VARCHAR (FK) | Required when `SPEC_TYPE = 'W'`. |
| `AD_PROCESS_ID` | VARCHAR (FK) | Required when `SPEC_TYPE = 'P'`. |
| `AD_MODULE_ID` | VARCHAR (FK) | Module that owns this spec. |
| `ISACTIVE` | CHAR(1) | Only active specs are served. |
| `AD_CLIENT_ID` | VARCHAR (FK) | Standard Etendo audit column. |
| `AD_ORG_ID` | VARCHAR (FK) | Standard Etendo audit column. |
| `CREATED`, `CREATEDBY`, `UPDATED`, `UPDATEDBY` | Standard | Audit trail. |

### ETGO_SF_ENTITY

Represents a tab (for window specs) or the process itself (for process specs) within a spec.

| Column | Type | Notes |
|--------|------|-------|
| `ETGO_SF_ENTITY_ID` | VARCHAR (PK) | UUID |
| `ETGO_SF_SPEC_ID` | VARCHAR (FK) | Parent spec. |
| `NAME` | VARCHAR | Used as the second segment of the URL path (window specs). |
| `AD_TAB_ID` | VARCHAR (FK) | Links to the AD_Tab. Null for process specs. |
| `ISINCLUDED` | CHAR(1) | `'Y'`/`'N'`. Only included entities are served. |
| `ISGET` | CHAR(1) | Enable GET list. |
| `ISGETBYID` | CHAR(1) | Enable GET by ID. |
| `ISPOST` | CHAR(1) | Enable POST create. |
| `ISPUT` | CHAR(1) | Enable PUT update. |
| `ISPATCH` | CHAR(1) | Enable PATCH partial update. |
| `ISDELETE` | CHAR(1) | Enable DELETE. |
| `JAVA_QUALIFIER` | VARCHAR | CDI `@Named` qualifier for a custom `NeoHandler`. |
| `SEQNO` | NUMERIC | Display/processing order. |
| `AD_MODULE_ID` | VARCHAR (FK) | Module that owns this entity. |

### ETGO_SF_FIELD

Represents a column (for window specs) or a process parameter (for process specs) within an entity.

| Column | Type | Notes |
|--------|------|-------|
| `ETGO_SF_FIELD_ID` | VARCHAR (PK) | UUID |
| `ETGO_SF_ENTITY_ID` | VARCHAR (FK) | Parent entity. |
| `AD_COLUMN_ID` | VARCHAR (FK) | Links to AD_Column. Null for process specs. |
| `ISINCLUDED` | CHAR(1) | Only included fields participate in selectors and actions. |
| `ISREADONLY` | CHAR(1) | Field-level read-only flag. |
| `DEFAULTVALUE` | VARCHAR | Default value override. For process specs, stores the parameter default. |
| `JAVA_QUALIFIER` | VARCHAR | For process specs: stores the parameter DB column name. |
| `SEQNO` | NUMERIC | Display/processing order. |
| `AD_MODULE_ID` | VARCHAR (FK) | Module that owns this field. |

### ETGO_PREVIEW_FILE

Stores one preview file per `(AD_CLIENT_ID, SPEC_NAME, RECORD_ID)` tuple. Used by `GET/POST/DELETE /sws/neo/preview-file`.

| Column | Type | Notes |
|--------|------|-------|
| `ETGO_PREVIEW_FILE_ID` | VARCHAR(32) | PK — 32-char uppercase UUID |
| `AD_CLIENT_ID` | VARCHAR(32) | FK → AD_Client |
| `AD_ORG_ID` | VARCHAR(32) | FK → AD_Org |
| `ISACTIVE` | CHAR(1) | Always `'Y'` |
| `CREATED` / `UPDATED` | TIMESTAMP | Audit timestamps |
| `CREATEDBY` / `UPDATEDBY` | VARCHAR(32) | Audit user FKs |
| `RECORD_ID` | VARCHAR(32) | PK of the source document |
| `SPEC_NAME` | VARCHAR(100) | Spec identifier (e.g. `purchase-invoice`) |
| `FILE_NAME` | VARCHAR(255) | Original filename |
| `MIME_TYPE` | VARCHAR(100) | e.g. `application/pdf`, `image/png` |
| `FILE_DATA` | CLOB | Base64-encoded file content |

Access level: `3` (Client + Org). One row per tuple — `POST` uses upsert semantics.

---

## 4. API Reference

### 4.1 Authentication

All requests require a JWT bearer token in the `Authorization` header:

```
Authorization: Bearer <jwt-token>
```

The token is decoded via `SecureWebServicesUtils.decodeToken()`. Required JWT claims:

| Claim | Description |
|-------|-------------|
| `ad_user_id` | Etendo user ID |
| `ad_role_id` | Etendo role ID |
| `ad_org_id` | Organization ID |
| `ad_client_id` | Client ID |
| `m_warehouse_id` | Warehouse ID (optional but expected) |

A missing or invalid token returns `401 Unauthorized`.

### 4.2 URL Patterns

All URLs are relative to the servlet root `/sws/neo`.

**Window specs (SPEC_TYPE = 'W'):**

| Pattern | Method | Description |
|---------|--------|-------------|
| `/{specName}/{entityName}` | GET | List records |
| `/{specName}/{entityName}` | POST | Create record |
| `/{specName}/{entityName}/{recordId}` | GET | Get record by ID |
| `/{specName}/{entityName}/{recordId}` | PUT | Full update |
| `/{specName}/{entityName}/{recordId}` | PATCH | Partial update |
| `/{specName}/{entityName}/{recordId}` | DELETE | Delete record |
| `/{specName}/{entityName}/selectors` | GET | List FK selectors |
| `/{specName}/{entityName}/selectors/{fieldIdentifier}` | GET | Query selector values (accepts DAL property name or DB column name) |
| `/{specName}/{entityName}/{recordId}/action` | GET | List button actions |
| `/{specName}/{entityName}/{recordId}/action/{columnName}` | POST | Execute button action |

**Process specs (SPEC_TYPE = 'P'):**

| Pattern | Method | Description |
|---------|--------|-------------|
| `/{specName}` | GET | Describe process (parameters, metadata) |
| `/{specName}` | POST | Execute process |

### 4.3 CRUD Operations

CRUD operations delegate to Etendo's internal `DataSourceServlet` (from the EtendoRX module). This means the request/response format follows the standard Etendo data source conventions.

Each HTTP method must be explicitly enabled on the entity record via the corresponding flag (`ISGET`, `ISPOST`, etc.). A request to a disabled method returns `405 Method Not Allowed`.

**GET list** -- `GET /{specName}/{entityName}`

Standard Etendo data source query parameters apply (filtering, sorting, pagination). The servlet passes `tabId` and `windowId` to the underlying DataSourceServlet so that tab-level HQL where clauses are applied automatically.

**GET by ID** -- `GET /{specName}/{entityName}/{recordId}`

Returns a single record. Requires either `ISGET` or `ISGETBYID` to be enabled.

**POST create** -- `POST /{specName}/{entityName}`

Request body is JSON. Delegated to DataSourceServlet's POST handler.

Before persistence, NEO resolves defaults and executes the header-tab callout cascade, in this
strict order:

1. **Snapshot** the field names present in the request body exactly as submitted by the client
   (`NeoMandatoryDefaultsService.injectMandatoryDefaults`, before step 2 runs).
2. **Inject generic mandatory-column defaults** (`injectDefaultsForActiveColumns`) — plain
   `AD_Column` defaults, session context, parent-tab values — for any column the client did not
   submit.
3. **Run the callout cascade** (`NeoDefaultsCascadeHelper.executeCalloutCascadeForCreate`),
   passing the *step-1 snapshot* as `protectedFields` — never a snapshot taken after step 2.

Only fields present in the step-1 snapshot are protected from callout overwrite; a field the
backend itself filled in during step 2 with a generic, context-agnostic default is **not**
protected and can be corrected by a callout that resolves a more specific value from a field the
client did submit (e.g. the Business Partner). Getting this ordering backwards — snapshotting
`protectedFields` from the body *after* the generic defaults already ran — silently freezes those
generic defaults, because the cascade then treats a value the backend just invented as if the
user had chosen it on purpose (ETP-4784: "Tipo factura" stuck at the generic default instead of
the Business Partner's configured value).

This still preserves ETP-4772's original intent: a value the client genuinely submitted in the
original POST (including one forced by upstream logic before the request reached NEO, e.g. a
rectifying-document key per ETP-4783) is in the step-1 snapshot and stays protected from being
overwritten by a re-cascaded callout.

**PUT / PATCH update** -- `PUT|PATCH /{specName}/{entityName}/{recordId}`

Both PUT and PATCH are delegated to DataSourceServlet's PUT handler internally. PATCH is handled via a `service()` override that intercepts the PATCH method at the Servlet API level.

**DELETE** -- `DELETE /{specName}/{entityName}/{recordId}`

Delegated to DataSourceServlet's DELETE handler.

**CSV export (generic)** -- `GET /{...}?export=csv`

Any list GET (generic CRUD entity *or* a custom `NeoHandler`) can stream its result as a CSV download instead of JSON by adding `export=csv`. The servlet runs the handler exactly as usual and, before writing the JSON envelope, hands the produced rows to `NeoCsvExportService`, which serializes them and streams the file (`Content-Type: text/csv`, `Content-Disposition: attachment`). No per-window code is needed — it operates on the standard `{response:{data:{<key>:[...]}}}` envelope.

Optional query params (all but `export` are optional):

| Param | Purpose |
|-------|---------|
| `export=csv` | Opt into CSV streaming. |
| `ids=a,b,c` | Keep only rows whose `id` is in the set. The client sends the already-filtered ids so a server-side export honors the on-screen (client-side) filters without re-implementing them. |
| `columns=key:Label:type\|key2:Label2` | Ordered column spec. `key` may be a dotted path into nested values (e.g. `txns.0.documentNo`). `type=date` reformats an ISO date to `dd-MM-yyyy`. Omitted → every key of the first row is used. |
| `filename=Name` | Download filename (`.csv` appended if missing). |

The export is intercepted at the two points where list responses are written: `NeoCrudHandler.handleWindowEntityCrud` (generic CRUD + entity-qualifier handlers) and `NeoRequestRouter.handleReportSpecRequest` (single-segment custom handlers such as `bank-statements`). Output is built fully in server memory from the rows the handler already returns, so large lists are streamed by the server rather than assembled in the browser.

#### 4.3.1 Date format contract (ETP-4793 / IMP-16)

**NEO speaks ISO-8601 dates in both directions**, on the REST API and over MCP:

| Property kind | Wire format | Example |
|---|---|---|
| Date | `yyyy-MM-dd` | `2026-08-06` |
| DateTime | `yyyy-MM-dd'T'HH:mm:ss` | `2026-08-06T18:55:31` |

That is not a preference, it is what the layers on both sides parse: the DAL
(`JsonUtils.createDateFormat` → `JsonToDataConverter`) on the way in, and the React form
(`dateOnly.js`, `date-field.jsx`) on the way out.

Three things inside Etendo nevertheless produce non-ISO date strings, so NEO normalizes:

- **`@#Date@` defaults are always `dd-MM-yyyy`.** Core `Utility.getContext` special-cases the name
  (`Utility.java:410`) and returns `DateTimeData.today(conn)`, a generated `.xsql` method whose
  output format is **hardcoded**. No session value, locale or `dateFormat.java` setting can change
  it — an earlier attempt to override it via `vars.setSessionValue("#Date", …)` was dead code and
  has been removed.
- **The `dateFormat.java` UI pattern**, when a value crosses a legacy boundary.
- **Raw Postgres timestamps** from `@SQL=` defaults (`yyyy-MM-dd HH:mm:ss.ffffff+00`).

Why this is a correctness issue and not cosmetics: `JsonUtils.createDateFormat()` calls
`setLenient(true)`, so a `dd-MM-yyyy` value is **not rejected** — it is reinterpreted. `06-08-2026`
persists as year **0012** and `24-06-2026` as `0029-12-17`. It also happens when the caller sends
no date at all, because both write paths (`McpToolRouter`, `NeoCrudHandler`) re-run
`injectMandatoryDefaults` immediately before saving, so the bad value is server-produced.

`NeoDateFormat` (`schemaforge/util/`) is the single definition of the canonical form and the only
place the three accepted shapes are listed. Two coercers apply it — one per write stack:

| Point | Class | Effect |
|---|---|---|
| Read — `/defaults` response | `NeoDefaultsService.canonicalizeDateDefaults` | every date-valued default leaves as ISO |
| Write — REST | `NeoTypeCoercionHelper.coerceField` | date branch, reached via `coerceTypes` |
| Write — MCP | `McpToolRouterSupport.coercePrimitiveFieldValue` | same branch, mirrored |

**A coercer only protects the call sites that invoke it**, and that — not the coercer — is what made
IMP-16 read as fixed while `neo_update` still corrupted. Every path that persists must run its
stack's pass:

| Path | Invocation | Note |
|---|---|---|
| `POST /crud` (React form, and every `neo_batch` op via `BatchService`) | `NeoCrudHandler.executePostCreate` → `coerceTypes` | also re-run by `NeoTypeCoercionHelper.wrapForSmartclient` |
| `PUT`/`PATCH /crud` | `NeoCrudHandler.executeUpdate` → `wrapForSmartclient` → `coerceTypes` | the REST wrapper coerces; the MCP one does not |
| `neo_create` | `McpToolRouter.handleCreate` → `coerceFieldTypes` | mandatory: `injectMandatoryDefaults` injects `dd-MM-yyyy` server-side |
| `neo_update` | `McpToolRouter.handleUpdate` → `coerceFieldTypes` | **added 2026-08-10**; before that this verb had no coercion pass at all |

The MCP pass runs **before** the entity's `NeoHandler` pre-hook, so a hook that mirrors one date
field into another (e.g. `AbstractInvoiceHeaderHandler#mirrorAccountingDate`) copies an
already-canonical value. The corollary is the one known gap: a value a pre-hook *introduces* in a
non-ISO shape is not re-canonicalized. Hooks must emit ISO.

A source-reading guard (`McpWriteVerbCoercionCallSiteTest`) fails the build if a method of
`McpToolRouter` reaches `jsonService.add`/`update` without calling `coerceFieldTypes` — a missing
call site is invisible to the coercers' own unit tests, which passed the whole time `neo_update` was
writing year 0015.

**Which properties are eligible** is decided in one place —
`NeoDateFormat.canonicalShapeFor(Property)` — and it is deliberately narrower than "the Java type is
a `Date`". Etendo has **five** date-ish domain types and `JsonToDataConverter` branches on all of
them (`Property.java:1107-1124`):

| Domain type | Predicate | NEO normalizes? |
|---|---|---|
| `DateDomainType` | `isDate()` | ✅ → `yyyy-MM-dd` |
| `DatetimeDomainType` | `isDatetime()` | ✅ → `yyyy-MM-dd'T'HH:mm:ss` |
| `TimestampDomainType` | `isTime()` | ❌ left as-is |
| `AbsoluteTimeDomainType` | `isAbsoluteTime()` | ❌ left as-is |
| `AbsoluteDateTimeDomainType` | `isAbsoluteDateTime()` | ❌ left as-is |

The last three are excluded for a concrete reason, not as margin. The two `Time` kinds are
**time-of-day** values: the converter discards everything before the `T`, appends `+0000` and
supplies the calendar day itself — so rewriting such a value to `yyyy-MM-dd` would delete the only
half it reads. `AbsoluteDateTime` is explicitly timezone-free and would need an offset policy no
caller has asked for. All three keep today's behaviour exactly.

Three deliberate constraints:

- **An unrecognised shape is never blanked or guessed at.** Blanking would turn a formatting problem
  into a missing mandatory field, and a guessed date is worse than the lenient parser this replaces.
  What happens *instead* differs per stack: REST passes it through with a `WARN`, MCP answers a
  structured 422 — see §4.3.1.1.
- **A non-zero zone offset is refused, not converted.** `2026-08-06T14:30:00+02:00` already reaches
  the DAL correctly (`JsonUtils.convertFromXSDToJavaFormat` rewrites `+02:00` to `+0200`, which the
  datetime parser honours), and the canonical form has nowhere to put an offset — dropping it would
  shift the instant by two hours, making the fix the corruption. A **zero** offset (`Z`, `+00`,
  `+00:00`) *is* dropped: an offset-less canonical value is read as UTC by that same method, so the
  two are identical.
- **The callout boundary is unchanged.** Normalization on the read path runs *after* the callout
  cascade, and `CalloutRequestBuilder.reformatDateParams` converts ISO back to the UI pattern
  before a legacy callout runs — so callouts receive exactly the same `dd-MM-yyyy` they did before.

Full investigation, including the corrupt rows this found in a live database:
`docs/mcp-evaluation/imps/IMP-16.md` in the `schema_forge` repo.

##### 4.3.1.1 Unusable dates on the MCP write verbs — 422 (ETP-4793 / IMP-24)

`neo_create` and `neo_update` **reject** a date value they cannot read, rather than letting it reach
the DAL. What the agent used to get back was the DAL's own leak — `{"status":-4}` plus a bare
`java.text.ParseException` naming no field, so it could not tell *which* date was wrong, or that a
date was the problem at all. It now gets:

```json
{
  "status": 422,
  "error": "validation_error",
  "detail": "One or more date values are not in a format this API can read",
  "invalidDates": [
    { "name": "orderDate", "received": "06/08/2026",
      "expectedFormat": "yyyy-MM-dd", "example": "2026-08-10" }
  ],
  "hint": "Send dates as ISO: yyyy-MM-dd for dates, yyyy-MM-dd'T'HH:mm:ss for datetimes. …",
  "seeAlso": "docs(topic:\"creating records\")"
}
```

`received` is echoed back on purpose: the field name alone cannot distinguish a wrong *format* from a
wrong *date*, and `2026-02-30` is ISO-shaped and still impossible (the strict resolver is what makes
it an error instead of a silent slide to the 28th).

Two conditions gate the rejection, and dropping either one would make it wrong:

| Gate | Why |
|---|---|
| `NeoDateFormat.isOffsetDateTime(value)` must be false | `toCanonical` returns `null` for **two** reasons. A non-zero-offset datetime is refused *because it is already correct* (see the constraint above) — a 422 there would break a working call, not fix a broken one. The classifier exists solely to keep these two `null`s apart |
| The value must be **caller-supplied** | A server-injected `dd-MM-yyyy` default is our bug. Answering it with a 422 hands the agent an error about a field it never sent. Those keep the pass-through `WARN`, which is the signal that the default needs fixing at source |

The witness for the second gate is per-verb: `handleCreate` uses `userProvided`, the snapshot taken
before `injectMandatoryDefaults` runs; `handleUpdate` needs none, because it never injects defaults,
so every key in the body is the caller's.

**REST stays lenient.** `NeoTypeCoercionHelper` keeps the pass-through `WARN`, following the same
line IMP-15 drew: the React form is not an agent, it has a date picker, and changing the REST
contract to fix an MCP ergonomics defect would be a breaking change bought for nothing. This is the
one documented place where the two write stacks answer the same input differently.

#### 4.3.2 Boolean format contract (ETP-4793)

**NEO speaks real JSON booleans in both directions.** Etendo stores booleans as `char(1) 'Y'/'N'`,
and the legacy machinery that feeds `/defaults` — AD_Column default expressions, callout responses,
combo option values — hands those raw strings straight through. A response that mixes both shapes
breaks any consumer that trusts the declared type: in JavaScript the string `"N"` is **truthy**, so
an agent reading `{"printDiscount": "N"}` concludes that discount printing is on.

This was observed as a **per-producer** inconsistency, not a per-spec one — which is why it looked
so arbitrary. On the same `c_invoice` columns:

| Field | `sales-invoice/header` | `purchase-invoice/header` |
|---|---|---|
| `printDiscount` | `true` | `"Y"` |
| `etvfacSentToVerifac` | `"N"` | `false` |
| `etvfacSimpinvart7273` | `false` | `"N"` |
| `etvfacInvNoIDArt61d` | `false` | `"N"` |

The direction **inverts** between the two specs. The cause is that `/defaults` is built by five
producers and only one of them coerced: `NeoDefaultsService.coerceBooleanDefault` was reachable
from pass 1 alone, while the callout writeback and combo preselection in
`NeoDefaultsCascadeHelper`, `NeoHiddenMandatoryDefaultsResolver`, and anything a handler injects
all wrote their value directly. Which fields a callout touches differs per window, so which shape a
field ends up with differs per window too.

`NeoBooleanFormat` (`schemaforge/util/`) is the single definition, applied at three points — the
same shape as the date fix above:

| Point | Class | Effect |
|---|---|---|
| Read — `/defaults` response | `NeoDefaultsService.canonicalizeBooleanDefaults` | post-pass: every boolean-valued default leaves as a JSON boolean |
| Write — REST | `NeoTypeCoercionHelper.coerceField` | Boolean branch |
| Write — MCP | `McpToolRouterSupport.coercePrimitiveFieldValue` | same branch, mirrored |

**Eligibility** is `NeoBooleanFormat.isBooleanProperty(Property)` — a primitive property whose Java
type is `Boolean`.

Three deliberate constraints:

- **Read and write parse differently, on purpose.** `toCanonical` is strict: it accepts `Y`/`N`/
  `true`/`false` (case-insensitive, trimmed) and returns `null` for anything else, so an
  unrecognised value is left **verbatim** and logged at `WARN`. Turning an unknown string into
  `false` would state something the ERP never stated. `toLenientBoolean`, used on the write path,
  keeps the pre-existing behaviour where anything not recognised as true becomes `false` —
  tightening that would reject payloads agents send today.
- **Case sensitivity is no longer surface-dependent.** The two write coercers disagreed: MCP
  accepted a lowercase `"y"`, REST did not, so the same payload coerced differently depending on how
  it arrived. Both now share one parse.
- **The callout boundary is unchanged.** The read-path normalization is a post-pass that runs
  *after* the cascade, so legacy callouts receive exactly what they received before. The pass-1
  `coerceBooleanDefault` is kept for the same reason — it runs before the cascade and its timing is
  part of today's callout input.

The React front end is **not** affected and needs no change: every boolean it reads from the server
already goes through an explicit `=== true || === 'Y' || === 'true'` guard (`EntityForm.jsx`,
`InlineLinesPanel.jsx`, `DataTable.jsx`, `listModalCells.jsx`, and ~26 more sites), so `"N"` was
never misread as checked. Those guards exist *because* the backend did not guarantee the type;
consolidating them behind a single helper is a follow-up, not part of this change — React still
talks to endpoints that do not run this post-pass.

### 4.4 Selectors (FK Dropdowns)

The selector service resolves foreign key references and provides searchable dropdown values.

**List selectors** -- `GET /{specName}/{entityName}/selectors`

Returns all FK fields for the entity that are included in the spec.

Response:

```json
{
  "selectors": [
    {
      "columnName": "C_BPartner_ID",
      "referenceType": "TableDir",
      "type": "simple",
      "targetEntity": "BusinessPartner",
      "displayProperty": "name"
    },
    {
      "columnName": "M_Product_ID",
      "referenceType": "OBUISEL",
      "type": "rich",
      "targetEntity": "Product",
      "displayProperty": "name"
    }
  ],
  "count": 2
}
```

**Query selector values** -- `GET /{specName}/{entityName}/selectors/{fieldIdentifier}`

`{fieldIdentifier}` accepts **either** identity of the field's column:

- the **DAL property name** (canonical, e.g. `priceList`, `partnerAddress`) — this is what
  the rest of the NEO API uses everywhere else (POST/PATCH body, GET response, `/defaults`,
  callouts) and what the generated selector URLs (`apiPrediction.selectors[].url`) carry;
- the **DB column name** (backward-compat, e.g. `M_PriceList_ID`) — resolved via a fast exact
  match so existing clients using column-name selector URLs keep working.

The endpoint tries the exact DB-column match first, then falls back to matching the DAL property
name. See ETP-4058.

Query parameters:

| Param | Default | Max | Description |
|-------|---------|-----|-------------|
| `q` | (none) | -- | Search text. Filters on display property (simple) or all searchable fields (rich). Case-insensitive partial match. |
| `limit` | 20 | 100 | Page size. |
| `offset` | 0 | -- | Page offset. |

Response (simple selector):

```json
{
  "items": [
    { "id": "ABC123", "label": "Customer A" },
    { "id": "DEF456", "label": "Customer B" }
  ],
  "columns": [],
  "totalCount": 42,
  "limit": 20,
  "offset": 0,
  "hasMore": true
}
```

Response (rich OBUISEL selector):

```json
{
  "items": [
    {
      "id": "ABC123",
      "label": "Product X",
      "name": "Product X",
      "searchKey": "PROD-001",
      "productCategory": "Category A"
    }
  ],
  "columns": [
    { "name": "name", "label": "Name", "sortNo": 10 },
    { "name": "searchKey", "label": "Search Key", "sortNo": 20 },
    { "name": "productCategory", "label": "Category", "sortNo": 30 }
  ],
  "totalCount": 150,
  "limit": 20,
  "offset": 0,
  "hasMore": true
}
```

**Reference type resolution priority:**

1. **OBUISEL Selector** -- checked first via `referenceSearchKey` or column reference. Returns rich multi-column results with searchable fields from `OBUISEL_Selector_Field`.
2. **TableDir (ref 19)** -- column name convention: `{TableName}_ID` resolves to target table.
3. **Table (ref 18) / Search (ref 30)** -- resolved via `AD_Ref_Table` (target table, key column, display column, optional where clause from `HQLWhereClause`, falling back to a translated `SQLWhereClause` -- see the ETP-4975 note below).

OBUISEL selectors with custom HQL queries are fully supported. The service uses `Session.createQuery()` to execute the custom HQL with org security filtering, validation rules, search across searchable properties, and pagination.

The service resolves `@param@` placeholders in OBUISEL HQL where clauses: `@AD_Org_ID@`, `@AD_Client_ID@`, `@AD_User_ID@`, `@AD_Role_ID@`.

**Searchable-field fallback and `SQLWhereClause` support (ETP-4975).** Two fixes in `SelectorDescriptorResolver` (`com.etendoerp.go.schemaforge.selector.meta`):

- `ensureSearchableFallback()` -- when an OBUISEL selector has no `OBUISEL_Selector_Field` rows configured, the resolver falls back to a small set of candidate searchable properties (identifier/name, `valueProp`, search key). `description` is now always added to that fallback list too, appended after the existing empty-list check so it never short-circuits the earlier candidates. Example: the "IAE Activity Type" selector (`epiae_type`: Key + Description, no explicit search-field config) previously only searched the short Key column -- typing "alquiler" (present only in the description) matched nothing.
- `resolveRefTable()` -- previously read only `AD_Ref_Table.HQLWhereClause` and silently dropped the filter for any reference configured the classic way, with the clause on `SQLWhereClause` instead. It now falls back to `SQLWhereClause`, translated to HQL via the now-public `SqlToHqlTranslator.convertSqlToHql()` (the same translator `SelectorValidationResolver` already uses for AD validation-rule clauses -- see `resolveValidationFilter` below), whenever `HQLWhereClause` is empty. This fixed the GO tax selector (`C_Tax_ID`, Table ref 18) showing "child" breakdown taxes (`Parent_Tax_ID` not null) that Classic excludes via `C_Tax.Parent_Tax_ID IS NULL`, which lives in `SQLWhereClause` for that reference with `HQLWhereClause` empty.

**`NESTED_SUBQUERY` guard, shared across both SQL-to-HQL paths.** `SelectorValidationResolver` guards AD_Validation clauses against a nested `(SELECT ...` subquery (raw SQL table names the generic translator cannot map to HQL entities) by dropping the whole offending clause instead of emitting broken HQL. The `resolveRefTableWhereClause()` path added above initially had no equivalent guard, and it was hit live: the `M_Warehouse_ID` reference's classic `SQLWhereClause` (`M_Warehouse.AD_Client_ID=@#AD_Client_ID@ AND (select ad.isactive from ad_org ad where ad.ad_org_id = M_Warehouse.AD_Org_ID) = 'Y'`) reached Hibernate as invalid HQL (`QuerySyntaxException: ad_org is not mapped`) the first time the Warehouse selector was actually queried in an E2E integration test, rather than degrading gracefully at config-resolution time the way the validation-clause path already did. The fix moves `NESTED_SUBQUERY` (and a new `dropNestedSubqueryClauses` helper) into `SqlToHqlTranslator` itself -- the class both callers already share -- so `convertSqlToHql()` now splits its input into top-level AND-segments, drops any segment containing a nested `(SELECT ...)`, and translates only what survives (returning `null`, i.e. no filter, when every segment is dropped). `SelectorValidationResolver` was updated to reference `SqlToHqlTranslator.NESTED_SUBQUERY` instead of keeping its own copy of the regex, so any future caller of `convertSqlToHql()` is protected by construction. Covered by `SelectorDescriptorResolverTest#resolveTarget_tableRef_sqlWhereClauseWithNestedSubquery_dropsSubqueryClause` (the C_Tax/C_TaxCategory case) and `#resolveTarget_tableRef_warehouseCorrelatedSubqueryWhereClause_dropsSubqueryClause` (the exact Warehouse regression case) in `src-test/src/com/etendoerp/go/schemaforge/selector/meta/SelectorDescriptorResolverTest.java`.

**Internal package split:**

| Layer | Package / classes | Notes |
|---|---|---|
| Request facade | `NeoSelectorService` | Resolves the requested field and orchestrates list/query responses. |
| Metadata | `com.etendoerp.go.schemaforge.selector.meta` | Reads AD/OBUISEL metadata and normalizes selector descriptors. |
| Policies | `com.etendoerp.go.schemaforge.selector.policy` | Applies registered context filters and post-query enrichments through `SelectorPolicyRegistry`. |
| Execution | `SelectorQueryExecutor`, `SelectorQueryBuilder`, `ComboReferenceSelectorExecutor`, `ListReferenceSelectorExecutor`, `SelectorResponseSupport` | Executes HQL/reference lookups and shapes the response. These remain in `schemaforge` to avoid widening package-private contracts. |

New entity-specific selector behavior should be implemented as a selector policy where possible, not as another hardcoded branch in `NeoSelectorService`.

### 4.5 Button Actions (Process Execution on Records)

Button actions are fields whose AD_Column has `AD_Reference_ID = '28'` (Button type) with a linked process.

**List actions** -- `GET /{specName}/{entityName}/{recordId}/action`

```json
{
  "actions": [
    {
      "columnName": "DocAction",
      "processType": "OBUIAPP",
      "processName": "Complete Order"
    }
  ]
}
```

**Execute action** -- `POST /{specName}/{entityName}/{recordId}/action/{columnName}`

The `recordId` from the URL path is injected into the process parameters automatically. Request body contains additional process parameters as JSON.

Process access is checked before execution. If the current role lacks access to the process, the request returns `403 Forbidden`.

### 4.6 Process Specs (Standalone Processes)

Process specs (`SPEC_TYPE = 'P'`) expose an AD_Process as a standalone API endpoint.

**Describe process** -- `GET /{specName}`

Returns process metadata including all parameters:

```json
{
  "id": "ABC123",
  "name": "Generate Report",
  "description": "Generates a monthly report",
  "helpComment": "",
  "uiPattern": "S",
  "javaClassName": "com.example.GenerateReport",
  "processType": "OBUIAPP",
  "parameters": [
    {
      "name": "Date From",
      "dbColumnName": "DateFrom",
      "sequenceNumber": 10,
      "mandatory": true,
      "defaultValue": "",
      "description": "Start date",
      "referenceId": "15",
      "referenceType": "Date",
      "isRange": false,
      "length": 0
    }
  ],
  "parameterCount": 1
}
```

**Execute process** -- `POST /{specName}`

Request body is a JSON object with parameter values keyed by DB column name:

```json
{
  "DateFrom": "2024-01-01",
  "DateTo": "2024-01-31"
}
```

Mandatory parameters are validated before execution. Missing mandatory parameters (without a default value) return `400` with a message identifying the missing parameter.

**Supported process types:**

| Type | UIPattern | Handler | Support |
|------|-----------|---------|---------|
| OBUIAPP | `S` (Standard) | `BaseProcessActionHandler` subclass | Supported. Invoked via reflection on `doExecute(Map, String)`. |
| Classic | (any) | `DalBaseProcess` subclass | Supported. Invoked via reflection on `doExecute(ProcessBundle)`. |
| DB Procedure | -- | PL/SQL procedure | Returns `501 Not Implemented`. |

**Response format:**

OBUIAPP processes return the handler's message/severity structure, translated to:

```json
{
  "status": "success",
  "message": "Process completed"
}
```

Classic processes return OBError results, translated to:

```json
{
  "status": "success",
  "title": "Process Complete",
  "message": "5 records updated"
}
```

Errors from either type return HTTP 400 with `"status": "error"`.

### 4.6a Attachments Endpoint (ETP-4315)

A built-in endpoint over Etendo's real `Attachment`/`C_File` table, backing both the generic
"Adjuntos" tab and, via the "main document" marker below, the sidebar/preview panel of every
document window. Implemented in `NeoAttachmentsHelper.java`, routed from
`NeoBuiltInEndpointHandler.java`.

**Base path:** `/sws/neo/attachments`

#### GET — List attachments

```
GET /sws/neo/attachments/{tableName}/{recordId}
Authorization: Bearer {token}
```

`{tableName}` is the AD_Table physical name (case-insensitive, e.g. `C_Invoice`, `C_Order`,
`M_InOut`). Returns `200 { "items": [...] }`, one entry per attachment
(`id`, `name`, `size`, `dataType`, `description`, `uploadedAt`, `updatedAt`, `uploadedBy`).
Excludes whichever attachment is currently marked as the record's "main" document (see below) —
that one belongs to the sidebar/preview, not the generic list. Returns `400` if `tableName` or
`recordId` is missing, `404` if `tableName` does not resolve to a known active table.

#### GET — Fetch the "main" (sidebar/preview) attachment

```
GET /sws/neo/attachments/{tableName}/{recordId}/main
Authorization: Bearer {token}
```

Returns `200` with the marked attachment's metadata, or `200 {}` if none is marked. At most one
attachment can be "main" per `(tableName, recordId)` at any time — this is what guarantees the
sidebar/tab and the preview panel always show the same file (the bug ETP-4315 fixed: previously
the sidebar picked "the first attachment" with no way to know which one the preview meant).

#### PATCH — Mark or unmark an attachment as "main"

```
PATCH /sws/neo/attachments/file/{attachmentId}/main
Authorization: Bearer {token}
Content-Type: application/json

{ "isMain": true }
```

Marking (`isMain: true`) **deletes** any attachment previously marked for the same
`(table, record)` pair, in the same transaction — enforced at the application level (delete-old-
then-mark-new), not via a DB constraint, since the marker column has no generated DAL property.
Unmarking (`isMain: false`) just clears the flag on the given attachment. Returns
`200 { "id", "isMain" }`, or `404` if the attachment does not exist.

#### POST — Upload (optionally marking as "main" in the same request)

```
POST /sws/neo/attachments/{tableName}/{recordId}?markAsMain=true
Authorization: Bearer {token}
Content-Type: multipart/form-data; boundary=...

--...
Content-Disposition: form-data; name="file"; filename="invoice.pdf"
Content-Type: application/pdf

<binary>
--...--
```

Expects a single multipart part named `file`. Optional query parameter `tabId` overrides automatic
tab resolution (useful when a table has multiple tabs). With `markAsMain=true`, the newly-created
attachment is marked as the record's main document immediately after upload — deleting any
previously-marked attachment, same as the PATCH above. Returns `201` with
`{ "name", "message", "id"?, "isMain"? }` (the last two only present when `markAsMain=true`).

#### GET — Download a single attachment

```
GET /sws/neo/attachments/file/{attachmentId}
Authorization: Bearer {token}
```

Streams the file body directly (not wrapped in JSON) with `Content-Type` from the attachment's
`dataType` and an RFC 5987 `Content-Disposition: attachment` header. Returns `404` if the
attachment does not exist.

#### GET — Download all attachments as a zip

```
GET /sws/neo/attachments/{tableName}/{recordId}?zip=true
Authorization: Bearer {token}
```

Streams a zip of every attachment for the record, **excluding** whichever one is marked as main
(it already has its own dedicated download button in the preview panel).

#### DELETE — Remove an attachment

```
DELETE /sws/neo/attachments/file/{attachmentId}
Authorization: Bearer {token}
```

Returns `204` on success, `404` if the attachment does not exist.

#### PATCH — Update description

```
PATCH /sws/neo/attachments/file/{attachmentId}
Content-Type: application/json

{ "description": "Signed by customer on receipt" }
```

Updates `C_File.text`. Returns `200 { "id", "description" }`.

#### Frontend integration

`useMainAttachment` (`tools/app-shell/src/windows/custom/shared/useMainAttachment.js`, backed by
`@/components/copilot/ocr/listAttachments.js`) wraps the "main" GET/PATCH/POST-with-markAsMain
calls above, exposing the same public shape as the legacy `usePreviewAttachment` (see 4.7) so it
drops into `GenericPreviewModal`'s `attachmentConfig` unchanged except for the selector flag
`useMainAttachment: true` and `tableName` (physical table) replacing `specName`. As of 2026-08-18
every window with a preview panel (sales/purchase invoice, sales/purchase order, sales quotation,
goods receipt/shipment, return-to-vendor shipment, return material receipt) uses this path; the
legacy hook remains wired as the unselected branch in `GenericPreviewModal` only until Phase 9 of
`docs/plans/2026-08-14-etp-4315-attachment-preview-unification-plan.md` retires it.

### 4.7 Preview File Endpoint — retired for preview panels, pending removal (ETP-4315 Phase 9)

**As of 2026-08-18, no window's preview panel uses this endpoint anymore** — every one of them was
migrated to the Attachments endpoint's "main" marker (4.6a) above, which fixes the sidebar/preview
desync this endpoint could never guarantee (nothing tied a `ETGO_PREVIEW_FILE` row to a *specific*
real attachment). This section is kept only until
`docs/plans/2026-08-14-etp-4315-attachment-preview-unification-plan.md` Phase 9 deletes
`NeoPreviewFileService.java`, this routing block, and the `ETGO_PREVIEW_FILE` table.

A built-in endpoint for persisting document preview files. Files are stored per `(clientId, specName, recordId)` tuple in `ETGO_PREVIEW_FILE`.

**Base path:** `/sws/neo/preview-file`

#### GET — Fetch stored file

```
GET /sws/neo/preview-file?specName={spec}&recordId={id}
Authorization: Bearer {token}
```

Returns `200` with a JSON body on both hit and miss:

```json
// Hit — file found
{ "fileName": "receipt.pdf", "mimeType": "application/pdf", "fileData": "<base64>" }

// Miss — no file stored yet
{}
```

Required query parameters: `specName`, `recordId`. Returns `400` if either is missing.

#### POST — Store or replace file

```
POST /sws/neo/preview-file
Authorization: Bearer {token}
Content-Type: application/json

{
  "specName": "purchase-invoice",
  "recordId": "ABC123",
  "fileName": "receipt.pdf",
  "mimeType": "application/pdf",
  "fileData": "<base64>"
}
```

Upsert semantics: inserts a new row if no file exists for the tuple; otherwise updates `FILE_NAME`, `MIME_TYPE`, and `FILE_DATA` in place. Returns `200` with `{ "id": "<saved-record-id>" }`.

All five body fields are required. Returns `400` if any is blank or missing, or if the body is not valid JSON.

#### DELETE — Remove stored file

```
DELETE /sws/neo/preview-file?specName={spec}&recordId={id}
Authorization: Bearer {token}
```

Returns `200 {}` when the row is removed. Returns `404` when no file exists for the tuple. Returns `400` if `specName` or `recordId` is missing.

### 4.8 Document Download Endpoint

Transactional email document links are served by a signed-token endpoint:

```
GET /sws/neo/document-download/{token}
```

This endpoint is link-token based because email recipients do not have the browser session that
created the original preview `blob:` URL. The token is signed server-side and includes the email
contract, document spec, record id, client id, send idempotency key, and expiration.

**Resolution (as of ETP-4315 Phase 8, 2026-08-18):** `NeoDocumentDownloadService.handle()` no
longer looks up `ETGO_PREVIEW_FILE`. After validating the token, it maps the token's `specName` to
a physical table (the same 8-window map as `documentEmailSend.js`'s `WINDOW_ATTACHMENT_TABLE`) and
resolves the attachment currently marked "main" for `(tableId, recordId)` via
`NeoAttachmentsHelper.findMainAttachment`, then streams it through the same
`AttachImplementationManager.download()` path `GET /sws/neo/attachments/file/{id}` uses. An extra
`attachment.getClient()` check enforces the token's client scope, since the attachment lookup
itself runs without a client filter under the servlet's existing admin-mode wrapping
(`NeoServlet.handleDocumentDownload`, already in place before this change — no new admin-mode
wrapping was needed). **Accepted behavior change:** a link sent before the document's marked
attachment was replaced now 404s, instead of the old cache's risk of silently serving stale or
mismatched content — the replaced attachment was hard-deleted server-side (4.6a's mark-as-main
delete-old-then-mark-new semantics), so there is nothing left to serve.

The send event remains audited by the transactional email service, but download authorization does
not rely on in-memory audit state.

#### Frontend integration

The React hook `useMainAttachment` (`tools/app-shell/src/windows/custom/shared/useMainAttachment.js`, see 4.6a) is what actually gets uploaded here — `documentEmailSend.js`'s `cacheDocumentPreviewFile()` uploads-and-marks through the same Attachments endpoint before the email send request, for the 8 windows in `WINDOW_ATTACHMENT_TABLE`. The legacy hook `usePreviewAttachment` (`tools/app-shell/src/windows/custom/shared/usePreviewAttachment.js`) wraps the retired endpoint in 4.7 and is no longer reachable for any window's preview panel; it remains wired as `GenericPreviewModal`'s unselected fallback branch only until Phase 9 removes it.

`GenericPreviewModal` consumes whichever hook is selected via `attachmentConfig.useMainAttachment` and manages the left-panel state machine identically either way:

| State | Left panel shown |
|-------|-----------------|
| `storeCondition=false` | Caller's `leftPanel` prop unchanged |
| Checking / storing | Spinner |
| No file cached, `autoFetch=false` | Drop zone (upload via drag-and-drop or file picker) |
| No file cached, `autoFetch=true` | Caller's `leftPanel` (live viewer) while background caching runs |
| File cached | PDF/image viewer + delete button |

### 4.9 Global Similarity Search Endpoint

```
GET /sws/neo/simsearch?entityName={entity}&items=["term1","term2"]&qtyResults=5&minSimPercent=30
Authorization: Bearer {token}
```

Fuzzy/trigram matching against an AD entity's identifier columns, for resolving free-text values
(e.g. a CSV import's `country`/`region` cell) to real records. This is a global pseudo-spec — like
`batch`, it bypasses ETGO_SF_SPEC/ETGO_SF_ENTITY resolution entirely — reusing
`com.etendoerp.copilot.toolpack.webhooks.SimSearch#handleSimSearch` directly rather than
duplicating its pg_trgm/`etcotp_sim_search` matching logic.

**Why this exists instead of calling the "SimSearch" webhook directly:** the Webhooks module
requires a per-`(webhook, role)` grant row in `SMFWHE_DEFINEDWEBHOOK_ROLE`, provisioned by hand per
role per environment via the Webhooks window's "Role Access" tab. This endpoint is reached through
NEO's own JWT bearer authentication instead (same as every other `/sws/neo/*` request) — no
additional per-role grant is needed. Entity-level security is unaffected: `SimSearch.handleSimSearch`
still filters through `OBContext.getEntityAccessChecker().getReadableEntities()`, so a role can only
match against entities it can already read.

**Query parameters:**

| Param | Required | Default | Description |
|-------|----------|---------|-------------|
| `entityName` | Yes | — | AD entity name to search (e.g. `Country`) |
| `items` | Yes | — | JSON array of search terms, one result set per term |
| `qtyResults` | No | `1` | Max matches returned per term |
| `minSimPercent` | No | `30` | Minimum similarity score (0-100) to include a match |

**Response:** `200` with one key per input item (`item_0`, `item_1`, ...), each holding the same
`{status, data}` shape `SimSearch`'s webhook already returns — so existing callers only need their
request URL updated, not their response parsing.

**Session-language terms are translated before matching.** `SimSearch` compares trigrams against
the **base** row only — translated text lives in a sibling `*_Trl` table it never reads — so a
Spanish session searching `España` scores 0.083 against the base-language `Spain` and resolves
nothing. Before delegating, the endpoint looks the term up in the entity's `*_Trl` sibling for the
current session language and substitutes the base-language name (`España` → `Spain`, `Unidad` →
`Unit`); `SimSearch` then matches at 100% and the matching logic itself is unchanged.

The lookup is generic, not a per-entity or per-language table: `NeoTrl.resolveSearchMeta()`
discovers the `*_Trl` sibling by convention, so every translatable entity and every language whose
translations an instance has loaded is covered by the same call — a newly loaded `fr_FR` resolves
`Espagne` with no code change.

The substitution is an **exact** (trimmed, case-insensitive) match, and falls through to the term as
typed whenever it is not unambiguous: no translation row, no `*_Trl` sibling, a translation shared by
several base rows, or a translation equal to the base name. Those requests behave exactly as they did
before translation existed, which is what preserves the matcher's typo tolerance — the fuzziness stays
in `SimSearch`, never in the decision of what to hand it.

> **Callers must send `Accept-Language`.** The session language comes from the header
> `NeoAuthenticator` applies to the `OBContext`. A request without it falls back to the AD user's
> default language, so a Spanish UI driven by a user whose AD default is `en_US` gets no translation
> at all. `lib/simSearch.js` in app-shell-core sends it; any new client must too.

Returns `400` if `entityName`/`items` is missing or `items` is not valid JSON, `422` if `entityName`
does not resolve to a readable entity, `405` for any method other than `GET`.

---

### 4.9a Global Semantic Vector Search Endpoint

```
GET /sws/neo/vectorsearch?query={text}&namespaces={namespace[,namespace]}&topK=10&metadataFilter={json}
Authorization: Bearer {token}
```

This authenticated pseudo-spec delegates embedding and pgvector queries to
`com.etendoerp.db.extended`'s `VectorSearchService`. `namespaces` is a required,
comma-separated selection of active, compatible DB Extended sources. The browser never supplies
tenant scope: DB Extended derives client and organization from `OBContext`; Go maps every requested
namespace to its AD table and requires the active role to have entity read access before searching.

`query` and `namespaces` are required. `topK` defaults to `10` and is limited to `1..50`.
`metadataFilter` is optional JSONB containment input for DB Extended. The response is its portable
`{ namespaces, matches }` payload. Invalid request data returns `400`, unauthorized sources return
`403`, controlled DB Extended capability/source failures return `422`, and provider failures return
a sanitized `500`. Only `GET` is supported.

Schema Forge configures its consumer through the Vite contract
`VITE_VECTOR_SEARCH_NAMESPACES`; leaving it empty disables semantic matches while normal page search
remains available.

---

### 4.10 NEO Pseudo-Spec Bridge for Etendo GO's Own Webhooks

```
GET /sws/neo/listmenu[?q=<term>]
GET /sws/neo/windowaccessmap
GET /sws/neo/rolesoverview
GET /sws/neo/assignuserroles?UserId=<id>&TemplateRoleIds=<id1,id2,...>
GET /sws/neo/userroleassignments[?UserId=<id>]
GET /sws/neo/systemroletemplates
GET /sws/neo/debuginvitationbypass?Action=forceAccept&Email=<email>       (dev/QA only — §8g)
GET /sws/neo/debuginvitationbypass?Action=forceStatus&Email=<email>&Status=<status>  (dev/QA only — §8g)
GET /sws/neo/resendinvitation?AdUserId=<id>                               (§8h)
GET /sws/neo/promoteuserrole?UserId=<id>&Mode=promote|demote              (§8i)
Authorization: Bearer {token}
```

`NeoGoWebhookBridge` runs `SFListMenu`/`SFWindowAccessMap`/`SFRolesOverview`/`SFAssignUserRoles`/
`SFUserRoleAssignments`/`SFSystemRoleTemplates`/`SFDebugInvitationBypass`/`SFResendInvitation`/
`SFPromoteUserRole` (§8, §8b, §8c, §8d, §8e, §8f, §8g, §8h, §8i) through NEO's own
JWT authentication instead of the Webhooks module's HTTP dispatch — the same pattern
`NeoSimSearchEndpoint` (§4.9) already used for `SimSearch`. Each of these pseudo-specs constructs
the corresponding `BaseWebhookService` and calls its unchanged `get(Map, Map)` method directly;
the response body is the exact `{"result": "<value>"}` / `{"error": "<message>"}` shape the
Webhooks module itself produces (verified by disassembling `WebhookServiceHandler.buildResponse`
in `webhookevents-3.1.0.jar`), so callers only need their request URL updated, never their
response-parsing logic. `SFListMenu`/`SFWindowAccessMap`/`SFRolesOverview` still work at their
original `/webhooks/*` paths too — the Webhooks module dispatch was not removed for them — but
`/sws/neo/*` is the path the Go SPA (`tools/app-shell` in `etendo_schema_forge`) actually calls,
and no `SMFWHE_DEFINEDWEBHOOK_ROLE` grant is required for it. `SFAssignUserRoles` (ETP-4852),
`SFUserRoleAssignments` (ETP-4906), `SFSystemRoleTemplates` (ETP-4906),
`SFDebugInvitationBypass` (ETP-4830), `SFResendInvitation` (ETP-4830), and `SFPromoteUserRole`
(ETP-5019) are `/sws/neo/*`-only — all six were authored after this pattern was already
established, so none ever had a legacy `/webhooks/*` path to keep.

Each webhook's own access rule is unaffected and still enforced inside its `get()` — see
§8/§8b/§8c/§8d/§8e/§8f/§8g/§8h/§8i for what each one checks (`NeoAccessHelper.isAdminOrClientAdmin`,
window/process access checks, etc.). Non-`GET` requests get `405`; a webhook that throws gets
`500` with the exception message (except `SFAssignUserRoles`'s own expected domain-validation
rejections, `SFUserRoleAssignments`'s own expected domain rejections, and `SFPromoteUserRole`'s
own expected domain rejections — see §8d/§8e/§8i for why those are a `200` result instead).
**`debuginvitationbypass` is different from every other pseudo-spec in this list: it is also
gated by a runtime flag checked in `NeoPseudoSpecDispatcher` BEFORE `SFDebugInvitationBypass` is
even constructed** — see §8g for why and how. `resendinvitation` (§8h) and `promoteuserrole`
(§8i) have NO such flag — both are real, always-on production features, gated only by their own
admin/client-admin check plus their own further scoping (client boundary for `resendinvitation`;
owner/admin caller + tenant-boundary + target-not-owner/not-already-Admin for
`promoteuserrole`, enforced inside `UserRoleCompositionService`, §8i).

### 4.11 NEO Pseudo-Spec Bridge Pattern (preferred for new Etendo-GO-authored webhooks)

**Any new webhook authored for Etendo GO itself (not a third-party module) should default to this
NEO pseudo-spec bridge instead of the Webhooks module's `/webhooks/*` + `SMFWHE_DEFINEDWEBHOOK_ROLE`
grant.** The reason is entirely about that grant table, not about the Webhooks module being wrong in
general: `SMFWHE_DEFINEDWEBHOOK_ROLE` is reset to its XML-only baseline every time `update.database`
runs, silently wiping any tenant-specific grant an onboarding step or data-fix had inserted. A
webhook reached only through `/webhooks/*` therefore needs an ongoing, per-tenant, per-role
provisioning process to keep working across environment rebuilds — exactly the pain that produced
the `R16` data-fix in `etendo_schema_forge` (`cli/src/data-fixes/sql/20260727T114306Z__R16-tenant-
roles-and-webhook-access.sql`) before this pattern existed. A request reaching any `/sws/neo/*` path
only needs a valid NEO bearer token; no separate per-role grant is possible or needed.

**No security is weakened by this pattern.** Entity/window/process-level access is still whatever
the webhook's own `get()` already enforces (`NeoAccessHelper`, `OBContext.getEntityAccessChecker()`,
etc.) — the bridge only changes *how the request reaches that code*, not what the code is allowed to
do once it gets there.

**How to add a new one:**
1. Write the webhook as a normal `BaseWebhookService` (`get(Map<String,String> parameter, Map<String,String> responseVars)`,
   `responseVars.put("result", ...)` / `.put("error", ...)`) — no special interface needed.
2. Add one `if ("<pseudo-spec-name>".equals(pathInfo.specName))` branch to `NeoServlet.processRequest`,
   alongside the `listmenu`/`windowaccessmap`/`rolesoverview`/`simsearch`/`batch` branches, calling
   `goWebhookBridge.handle(request, new YourWebhook())`.
3. Do **not** make the bridge itself dispatch by name generically — keep it an explicit allow-list
   (see `NeoGoWebhookBridge`'s class javadoc for why: bypassing the grant gate for a *third-party*
   module's webhook is not this bridge's call to make, only Etendo GO's own).

See `NeoGoWebhookBridge.java` and `NeoSimSearchEndpoint.java` for the two existing implementations,
and `etendo_schema_forge/docs/neo-headless-extensibility.md` for the sibling `NeoHandler` CDI-hook
pattern (a different extension point — request enrichment/validation hooks on existing CRUD/process
specs — not a replacement for this one).

---

### 4.12 MCP Tool Ergonomics (Wave 3 — ETP-4601)

The MCP tool layer (`/sws/neo/mcp`, routed by `McpToolRouter`) exposes the same specs described
above to AI agents as JSON-RPC tools (`neo_discover`, `neo_schema`, `neo_create`, `neo_update`, …).
Wave 3 of the MCP improvements adds three agent-ergonomics features on top of that surface. Each is
additive and backwards-compatible: an existing caller that ignores the new parameter/field sees the
exact same responses as before.

#### 4.12.1 `neo_schema({view:"actions"})` — actions-only projection (IMP-6)

`neo_schema` normally returns the full field dump for an entity — for a compliance-heavy window this
can be ~97 fields, most of which an agent does not need when its only goal is to find out *which
buttons/processes it can trigger* on that entity. The optional `view` parameter collapses the
response down to the callable actions.

`view` is an enum whose only accepted value is `"actions"` (matched case-insensitively). Omitting it
— or passing anything else — is a no-op: the caller keeps receiving the full, unchanged schema.

**Request:**

```json
{
  "tool": "neo_schema",
  "arguments": {
    "spec": "sales-order",
    "entity": "header",
    "view": "actions"
  }
}
```

**Response** (`{spec, entity, actions, actionCount, invokableCount}` — the full `fields` array is
dropped):

```json
{
  "spec": "sales-order",
  "entity": "header",
  "actions": [
    {
      "name": "completeAction",
      "label": "Complete",
      "type": "button",
      "invokeVia": "neo_action",
      "action": "completeAction",
      "processType": "OBUIAPP",
      "processName": "Complete",
      "processId": "ABC123..."
    },
    {
      "name": "cancelAction",
      "label": "Cancel Document",
      "type": "button",
      "invokeVia": "neo_action",
      "action": "cancelAction",
      "processType": "OBUIAPP",
      "processName": "Cancel Document",
      "processId": "..."
    },
    {
      "name": "calculatePromotions",
      "label": "Calculate Promotions",
      "type": "button",
      "invokable": false,
      "notInvokableReason": "discarded: this action is not part of the curated agent surface for this window",
      "action": "Calculate_Promotions",
      "processType": "Classic",
      "processName": "Calculate Promotions",
      "processId": "..."
    }
  ],
  "actionCount": 3,
  "invokableCount": 2
}
```

Behavior details (`McpActionsView`):

- The view is a **pure re-shape** of the field array `neo_schema` already builds
  (`McpSchemaFieldBuilder.buildSchemaFieldsArray`) — it simply filters down to the `type:"button"`
  entries, in their original order. No additional DAL/model access is performed.
- Each returned action is already fully self-describing: `action`, `processType`, `processName` and
  `processId` tell the agent exactly how to invoke it via `neo_action` — no follow-up `neo_schema`
  call on the full entity is required.
- **`invokeVia` is a claim, not a decoration (IMP-21).** Fire only the actions that carry
  `invokeVia:"neo_action"`. An action the agent cannot run instead reports `invokable: false` plus a
  `notInvokableReason`, for one of three causes, reported in that order: it is curated
  `visibility:"discarded"` (deliberately out of this window's agent surface); AD itself does not
  display the button in the tab (`AD_Field.isDisplayed = 'N'`), which makes it an internal flag
  rather than a user-facing action; or the AD button column has no process wired behind it so there
  is nothing to run. Uncurated buttons that AD displays and that have a process stay invokable —
  absence of curation is not a decision to exclude. A button with **no** `AD_Field` in the tab is not
  hidden: module-contributed buttons routinely have none, and treating that as hidden would retire
  them.
- The **hidden** check exists because `Processing` and `DocAction` on `C_Invoice` point at the *same*
  `AD_Process` (`111`, `C_Invoice_Post0`). `Processing` is the classic procedure's internal
  "in progress" flag, hidden in all three windows that have a field for it, and it was the one action
  the catalog still offered as callable while carrying no `actionValues`, no `actionParameter` and no
  `agentPrompt`. Curation cannot express this: `Processing` is curated `system`, which states that
  the server fills a payload value and says nothing about a button.
- **`invokableCount`** sits next to `actionCount` so the split is visible before reading the array.
  On `sales-invoice/header` the catalog has 22 actions and only a handful are callable.
- A button carries **no `required` flag** (IMP-21). A button has no payload value, so AD's NOT NULL
  flag on the trigger column says nothing about what the agent must send; it used to be reported as
  `required: true` right next to an honest `userRequired: false`.
- **`businessCritical`** is derived for actions that curation left unflagged: a button bound to the
  shared `docAction` list (it drives the document state machine, and that binding is what
  `actionParameter` records) and the `Posted` accounting trigger are business-critical by
  construction. Curated flags always win — the derivation only fills gaps, never clears a flag.
- Module-contributed buttons have no `AD_Field` in the tab, so their label fell back to the raw
  column name (`EM_Psd2_Generate Bank Payment`). The fallback chain is now curated `AD_Field` label
  → process name → the column name with its `EM_<module>_` prefix stripped.
- An entity with no button fields returns `"actions": []` and `"actionCount": 0` (never `null`).

**When to use it:** the agent knows the entity and only wants the menu of things it can *do* to a
record (complete, cancel, post, …), not the full editable/read-only column list.

#### 4.12.2 `neo_discover` → `primaryEntity` — the root entity of a window spec (IMP-9)

A window spec (`SPEC_TYPE = 'W'`) can include several entities (Header, Lines, …). To create a
document an agent must create the **root/header** record first, then attach child rows. Previously it
had to infer which included entity was the header by calling `neo_schema` on each. `neo_discover` now
surfaces that directly: each window spec that has entities carries a `primaryEntity` field naming the
root entity.

**Response fragment** (`handleDiscover` → `McpToolRouterSupport.buildDiscoverSpec`):

```json
{
  "name": "sales-order",
  "type": "W",
  "description": "Sales Order",
  "primaryEntity": "header",
  "entities": [
    { "name": "header", "methods": ["GET", "POST", "PUT", "DELETE"], "readOnly": false },
    { "name": "lines",  "methods": ["GET", "POST", "PUT", "DELETE"], "readOnly": false }
  ]
}
```

Resolution rules (`McpToolRouterSupport.resolvePrimaryEntityName`):

- **Authoritative signal:** the included entity whose linked `AD_Tab` has `tabLevel == 0` is the
  header (the same convention `McpToolRouter.resolveParentFK` relies on). `SFEntity` carries no
  parent column, so hierarchy is read off the linked `AD_Tab`.
- **Fallback:** when no included entity has a level-0 tab — or an entity has no linked tab at all
  (handler-backed entities) — the first included entity by ascending `seqNo` is used.
- `primaryEntity` is only ever emitted **alongside `entities`**, i.e. for `W` specs. Process (`P`)
  and report (`R`) specs never carry it.
- A spec with no included entities yields `null`, and the key is omitted from the response entirely.

#### 4.12.3 FK resolution on the write verbs (IMP-4, extended to every verb by IMP-15)

Historically every foreign-key field in a write body required the exact 32-character record id,
forcing an agent to call `neo_selectors` first even for an obvious single-match lookup. Wave 3 lets a
write body pass a **human search string** for an FK field; the router resolves it to the real record
id server-side before persisting, via the same selector path `neo_selectors` uses
(`NeoSelectorService.querySelectorByColumn`, limit 10). This runs for both `neo_create` and
`neo_update` (`McpFkResolver.resolveFkNames`, invoked from `handleCreate` and `handleUpdate`) and,
since IMP-15, on every `neo_batch` operation body (`McpToolRouter.resolveBatchFkNames`, run before
the batch transaction opens).

**Request** — `businessPartner` given by name instead of id:

```json
{
  "tool": "neo_create",
  "arguments": {
    "spec": "sales-order",
    "entity": "header",
    "businessPartner": "Acme Corp",
    "orderDate": "2026-08-03"
  }
}
```

If exactly one business partner matches `"Acme Corp"`, the resolver replaces the value in place with
that record's id and the create proceeds normally.

**Which values are resolved, and which are passed through untouched:**

- A value is treated as an already-valid id — and left untouched — when it is exactly **32 hex
  characters** (`[0-9A-Fa-f]{32}`, upper/lower/mixed case). `looksLikeId` matches `95E2A8B5…`; it
  rejects a 31-char string, a string with a non-hex char, an empty string, and `null`.
- **Id-first (ETP-4793 / IMP-15).** The shape check above is not the only id test: every Etendo
  `_ID` column is a `VARCHAR`, and legacy master data (currency, UOM, document type, tax rate) still
  carries short numeric ids such as `"102"` for EUR. Any value that fails the shape check is
  therefore **probed as a record id of the target entity** before the selector runs, and only falls
  through to the name lookup when no readable record carries it. This is what makes
  `neo_defaults → currency:"102" → neo_create` work: before IMP-15 that value went down the name
  path, matched no currency literally *named* `"102"`, and came back as a 422 advising the agent to
  "pass the exact record id instead" — which is what it had done.
- Only FK fields are considered: a key is resolved only if it maps to a DAL property that is a
  non-primitive association with a target entity. Non-FK fields, non-string values, and empty strings
  are never touched.
- The same resolver runs on **`neo_create`, `neo_update` and `neo_batch`** (each op's `body`), so one
  field body is accepted verbatim by every write verb. In a batch, `"$ref:<opId>"` placeholders are
  skipped — the op they point at has not run yet, so the value is neither an id nor a name.

**Outcomes by selector match count** (`decideOutcome`):

| Matches | Outcome | Effect |
|---------|---------|--------|
| 0 | `NOT_FOUND` | Returns a structured `not_found` error; the write is rejected. |
| 1 | `RESOLVED` | Value replaced in place with the matched record id; write proceeds. |
| >1 | `AMBIGUOUS` | Returns a structured `ambiguous_fk` error carrying the candidate list; the write is rejected. |

Both error shapes are returned as an MCP error content payload with HTTP-style
`status: 422` (`STATUS_UNPROCESSABLE`) and a `field` naming the offending key:

**Not found:**

```json
{
  "status": 422,
  "error": "not_found",
  "detail": "No match for 'businessPartner'='Acme Corp': it is neither the id of an existing record nor a value any selector matched. Use neo_selectors to find a valid one.",
  "field": "businessPartner"
}
```

**Ambiguous** (`candidates` is the raw selector `items` array, capped at the selector limit of 10):

```json
{
  "status": 422,
  "error": "ambiguous_fk",
  "detail": "'businessPartner'='Acme' matched 3 records. Pick one of the candidates' ids, or narrow the search text.",
  "field": "businessPartner",
  "candidates": [
    { "id": "…", "name": "Acme Corp" },
    { "id": "…", "name": "Acme Industries" },
    { "id": "…", "name": "Acme Logistics" }
  ]
}
```

> **Known limitation — selector context.** The selector context passed to the resolver is built from
> the `AD_Tab` alone (`McpSelectorContextHelper.buildSelectorContextParams(null, adTab)` — window
> sales/purchase context, business-partner role). It does **not** synthesize `recordContext` /
> `parentContext` from the in-flight body, because that would require resolving fields in dependency
> order (e.g. `priceList` needs `businessPartner` resolved first). A **dependent** FK — such as
> `partnerAddress` depending on `businessPartner` — may therefore match more records than a
> context-aware `neo_selectors` call would, and can return a false `ambiguous_fk`. When that happens,
> resolve the dependent field explicitly via `neo_selectors` with an explicit `recordContext` and
> pass its resulting id.

If the selector lookup itself fails (HTTP status ≥ 400 or a null body) or no `AD_Column` can be
resolved for the key, the resolver logs a warning/debug line and leaves the value as-is rather than
failing the write — the downstream DAL then surfaces its own validation error for the unresolved
reference.

#### 4.12.4 `neo_batch` failure envelope (IMP-15)

`BatchService` serves both the REST `/batch` endpoint and `neo_batch`, and its failure body forwards
the offending sub-response verbatim under `error.detail`. For a REST caller that is useful; for an
agent it meant a raw DAL payload — `{"response":{"status":-4,"errors":{…}}}` — with no stable code to
branch on. The MCP layer therefore rewrites the failure in place
(`McpToolRouterSupport.toMcpBatchFailure`) into the same envelope every other MCP error uses, while
the REST contract stays untouched:

```json
{
  "committed": false,
  "atomic": true,
  "failedAt": { "index": 1, "id": "l0" },
  "persisted": [],
  "hint": "Nothing was persisted: the batch was rolled back as a unit …",
  "error": {
    "status": 400,
    "error": "validation_error",
    "detail": "Operation 'l0' rejected by server: id: New object Currency(null) …",
    "seeAlso": "docs(topic:\"creating records\")"
  }
}
```

`error` is one of `validation_error` (any other 4xx), `not_found` (404), `method_not_allowed` (405)
or `server_error` (5xx, and the batch-wide failure reported at index `-1`) — only the first three are
worth retrying with a corrected request. The DAL's own text is preserved inside `detail`; its numeric
`status: -4` is dropped, since it names nothing an agent can act on.

##### 4.12.4.1 `atomic` / `persisted` — the batch rolls back as a unit (IMP-23)

**`neo_batch` and `POST /batch` are atomic**: a failure rolls back every operation, so the recovery
is to fix the operation named in `failedAt` and retry the whole batch. `atomic: true` with
`persisted: []` is the normal failure shape. Both keys are present on every failure body, empty
array included — "nothing landed" and "we are not saying" must not look alike to a caller.

**They were not atomic until IMP-23 option B**, and the history explains the two keys. `BatchService`
always held a single transaction — one `commitAndClose()` after the loop, `rollbackAndClose()` on
failure — but each op reached core's `DefaultJsonDataService#update`, whose success branch ends with
an unconditional `OBDal.getInstance().commitAndClose()`. Every op committed itself and the batch's
rollback found an empty session, so a failure at op *n* left ops `0..n-1` durable. That commit cannot
be suppressed from outside — it takes no parameter, `SessionHandler#setDoRollback` is read only by
`DalThreadHandler` at thread end, and disabling triggers makes `commitAndClose` throw — so the batch
now routes through `NeoBatchJsonDataService`, core's write path subclassed with the commit deferred
to `BatchService`. The failure mode was asymmetric (a validation or FK failure is caught before any op
runs and looked atomic; a persist-time failure left records behind), which is why it read as
intermittent across four benchmark runs, and why a `sales-order` header sat orphaned for five days.

**The one case that is still not atomic**, reported rather than hidden: an operation whose handler
runs an Etendo process commits inside that process by design (`ProcessInvoiceUtil#process`). No
caller-side transaction ownership can undo that. `BatchService` detects it generically — a
`commitAndClose()` underneath closes the Hibernate session, so the `Session` identity changes
mid-batch — and then reports `atomic: false` with `persisted` naming the records that outlived the
rollback.

| `atomic` | `persisted` | What the caller does |
|---|---|---|
| `true` | `[]` | Fix the op in `failedAt`, retry the whole batch |
| `false` | the surviving ops | Delete those records, or reuse them and resubmit only the remaining ops — a plain retry duplicates them |

So a caller must **check `atomic` before retrying** rather than assuming either outcome.

Unchanged on success: a fully successful batch still returns `committed:true` with every `recordId`.

#### 4.12.5 `neo_list` / `neo_get` — unknown projection fields (IMP-18)

The `fields:[…]` projection is a whitelist, so a misspelt name used to be indistinguishable from a
field that simply held no value: the key was absent from the row either way. `neo_schema` already
reported its rejects (§ its own `fields` argument, `unknownFields`), and the two tools now behave the
same way — one argument name, one contract.

Any requested name the entity cannot return comes back under `response`, next to `data`, sorted:

```json
{
  "response": {
    "data": [ { "id": "…", "documentNo": "INV-1" } ],
    "unknownFields": ["totalGross"]
  }
}
```

Three decisions worth knowing, because each one is a case where the obvious implementation lies:

- **Validated against what the entity can emit, not against the rows returned.** On an empty result
  set no row can answer the question — and that is exactly when a typo is most expensive, since the
  agent reads "no matches" and concludes the data is missing rather than that it asked wrong.
- **The emittable set is the spec's exposure, post-rename** (`NeoFieldFilter.emittableResponseKeys()`):
  API keys, not DAL property names. A property the spec does not include, or that is served under a
  `javaQualifier` alias, is just as unreachable for the caller as one that does not exist — so
  requesting `dateAcct` when the spec exposes `accountingDate` is reported. When no `ETGO_SF_FIELD`
  config exists the filter is inactive and the response is unfiltered, so the DAL entity's property
  list is the fallback; if neither source resolves, nothing is reported (silence beats accusing a
  valid field).
- **Only an explicit `fields:[…]` whitelist is judged.** A `view:"summary"` set is derived server-side
  from properties that already resolved, so an unknown name there would be our bug, not the caller's.

A requested `$_identifier` companion is normalised to its base property, for both the projection and
the validation — `fields:["businessPartner$_identifier"]` returns the FK *and* its label (it used to
return only `id`) and is never mislabelled as unknown.

The always-readable audit keys are known too (ETP-5073). `updated` is an AD *column* on every table
but not an AD *field*, so no `ETGO_SF_FIELD` row exists for it and no window can opt in; the read
path serves it anyway (`NeoFieldFilter.ALWAYS_READABLE_KEYS`, ETP-4787). Until ETP-5073 the emittable
set omitted it, so `fields:["name","updated"]` returned `updated` in `data` **and** listed it in
`unknownFields` — a response contradicting itself, which for an agent consumer is worse than no
signal at all. The set is now unioned into `emittableResponseKeys()` only: `updated` stays
unwritable, and a client that sends it on a create is still filtered/rejected exactly as before.

---

## 5. Configuration

### 5.1 Creating a Spec

Specs can be created through the Etendo Application Dictionary UI (ETGO_SF_Spec window) or programmatically via webhooks.

**SFUpsertSpec webhook:**

| Parameter | Required | Description |
|-----------|----------|-------------|
| `Name` | Yes | Spec name (becomes the URL segment). |
| `ModuleID` | Yes | Owning module ID. |
| `SpecType` | No | `'W'` (default) or `'P'`. |
| `WindowID` | When `W` | AD_Window_ID to expose. |
| `ProcessID` | When `P` | AD_Process_ID to expose. |
| `Description` | No | Human-readable description. |
| `SpecID` | No | Provide to update an existing spec. |

Response includes `SpecID` and `SpecType`.

**SFUpsertEntity webhook:**

| Parameter | Required | Description |
|-----------|----------|-------------|
| `SpecID` | Yes | Parent spec ID. |
| `TabID` | Yes | AD_Tab_ID. |
| `ModuleID` | Yes | Owning module ID. |
| `Name` | No | Entity name. Defaults to tab name. |
| `IsGet`, `IsGetbyid`, `IsPost`, `IsPut`, `IsPatch`, `IsDelete` | No | HTTP method flags (`Y`/`N`). Default `N`. |
| `JavaQualifier` | No | CDI `@Named` qualifier for hook handler. |
| `SeqNo` | No | Sequence number. |
| `EntityID` | No | Provide to update an existing entity. |

**SFUpsertField webhook:**

| Parameter | Required | Description |
|-----------|----------|-------------|
| `EntityID` | Yes | Parent entity ID. |
| `ColumnID` | Yes | AD_Column_ID. |
| `ModuleID` | Yes | Owning module ID. |
| `IsIncluded` | No | `Y`/`N`. Default `Y`. |
| `IsReadOnly` | No | `Y`/`N`. Default `N`. |
| `DefaultValue` | No | Default value override. |
| `JavaQualifier` | No | For process specs: parameter DB column name. |
| `SeqNo` | No | Sequence number. |
| `FieldID` | No | Provide to update an existing field. |

### 5.2 Populating from AD Metadata

Rather than creating entities and fields one by one, the **SFPopulateSpec** webhook (or the **Populate** button on the ETGO_SF_Spec window) reads the AD metadata and auto-creates all entity and field records.

**For Window specs:** reads all active tabs from the linked AD_Window. For each tab, creates an ETGO_SF_Entity and one ETGO_SF_Field per active column in the tab's table.

**For Process specs:** creates a single entity (POST-only) and one field per active AD_Process_Parameter. Since process parameters have no AD_Column, the `AD_COLUMN_ID` is left null and the parameter's DB column name is stored in `JAVA_QUALIFIER`.

Running populate again deletes all existing child entities and fields before re-creating them.

**SFPopulateSpec webhook parameters:**

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `SpecID` | Yes | -- | Spec to populate. |
| `IncludeAllMethods` | No | `N` | Set all HTTP method flags to `Y`. |
| `ExcludeSystemColumns` | No | `Y` | Skip `AD_CLIENT_ID`, `AD_ORG_ID`, `ISACTIVE`, `CREATED`, `CREATEDBY`, `UPDATED`, `UPDATEDBY`. |

Response includes `EntitiesCreated` and `FieldsCreated` counts.

### 5.3 Custom Handlers (NeoHandler Interface)

To inject custom business logic, implement the `NeoHandler` interface and annotate the class with `@Named`:

```java
package com.example;

import javax.inject.Named;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;

@Named("myCustomHandler")
public class MyCustomHandler implements NeoHandler {

  @Override
  public NeoResponse handle(NeoContext context) {
    if ("POST".equals(context.getHttpMethod())) {
      // Custom create logic
      JSONObject result = new JSONObject();
      result.put("id", "new-id");
      return NeoResponse.created(result);
    }
    // Return null to fall through to default DataSourceServlet behavior
    return null;
  }
}
```

Then set `JAVA_QUALIFIER = 'myCustomHandler'` on the corresponding ETGO_SF_Entity record.

**Handler behavior:**
- The handler receives a `NeoContext` with all request information (spec name, entity name, HTTP method, record ID, request body, query params, AD_Tab, OBContext).
- Return a `NeoResponse` to take full control of the response.
- Return `null` to let the request fall through to the default DataSourceServlet handling.
- If the handler class is not found via CDI, the request falls through to default handling with a warning log.

**Advanced pattern — legacy `ad_actionButton` servlets with no `CallProcess` path:** most custom
handlers either call `CallProcess` (stored-procedure AD Processes) or run their own HQL query.
`YearCloseHandler` (`calendar` spec, `JAVA_QUALIFIER = 'year-close'`) is the first case in this
module of a third shape: AD Processes 800036/800038 ("Close Year"/"Undo Close Year") are legacy
classname-based `ad_actionButton` servlets with `AD_Process.procedurename = NULL` — `CallProcess`
has no code path for these at all. The handler invokes the servlet's private `processButton(...)`
method directly via reflection instead of simulating an HTTP request. See
`docs/neo-headless-guide.md` §16 ("Patron avanzado") for the full write-up and code sketch, and
the class javadoc in `YearCloseHandler.java` for the complete rationale. Treat this as a
last-resort pattern, not a default — only reach for it once you've confirmed (not assumed) that
`CallProcess` genuinely has no path for the process in question.

**NeoContext fields:**

| Field | Type | Description |
|-------|------|-------------|
| `specName` | `String` | Spec name from URL. |
| `entityName` | `String` | Entity name from URL. |
| `httpMethod` | `String` | `GET`, `POST`, `PUT`, `PATCH`, or `DELETE`. |
| `recordId` | `String` | Record ID from URL (null for list operations). |
| `requestBody` | `JSONObject` | Parsed request body (null for GET/DELETE). |
| `queryParams` | `Map<String, String>` | URL query parameters. |
| `adTab` | `Tab` | Resolved AD_Tab (null for process specs). |
| `obContext` | `OBContext` | Authenticated user context. |
| `previousResult` | `NeoResponse` | Mutable. Can be set by the handler for post-processing patterns. |

**NeoResponse static builders:**

| Method | HTTP Status | Description |
|--------|-------------|-------------|
| `NeoResponse.ok(JSONObject)` | 200 | Success with body. |
| `NeoResponse.created(JSONObject)` | 201 | Created with body. |
| `NeoResponse.noContent()` | 204 | Success, no body. |
| `NeoResponse.error(int, String)` | (given) | Wraps `message` in the nested envelope `{"error": {"message": "...", "status": N}}`. |
| `NeoResponse.error(int, JSONObject)` | (given) | Sends `body` **verbatim**, unwrapped — for a handler that already builds its own flat response shape (e.g. `{"success": ..., "message": ...}`). |

**⚠️ Overload pitfall (ETP-4706):** `error(int, String)` and `error(int, JSONObject)` produce very
different response shapes, and the compiler will silently pick the `String` overload if you pass
`body.toString()` instead of `body` — turning your flat `{"success", "message"}` object into a
message *string* nested one level deeper as `{"error": {"message": "{\"success\":...}", "status": N}}`.
`DocumentPostingService#handleAction` and `NotPostedDocumentsHandler#buildPostResponse` both hit
this: they had already built a flat JSON body and called `NeoResponse.error(422, body.toString())`,
so every client reading a top-level `message` field silently got the stringified JSON blob instead
(masked further because most clients then fell back to `res.statusText`, e.g. "Unprocessable
Entity", not a JSON-parse error). Fixed by passing `body` directly. When a handler already owns its
response shape, call `error(status, JSONObject)`; only call `error(status, String)` when you want the
standard nested envelope built for you.

Responses support custom headers via `withHeader(name, value)`.

**Real-world example — `ChartOfAccountsHandler` GL Item auto-management (ETP-5020):** `schemaforge/handlers/ChartOfAccountsHandler.java` (`@Named("chart-of-accounts")`, wired on the chart-of-accounts spec) keeps Etendo Classic's `C_Glitem` plumbing invisible behind the `C_ElementValue` subaccount UI.

On a successful live `POST` to the chart-of-accounts entity, its `afterHandle` hook reads the created subaccount id from the previous NEO create response, loads the saved `ElementValue`, resolves every active `AcctSchema` for the client, and delegates to `GlItemProvisioningSupport#ensureGlItemForSubaccount`. The support class creates one invisible `C_Glitem` named after the subaccount and one `C_Glitem_Acct` row per active accounting schema, with debit and credit both pointing at the subaccount's natural `C_ValidCombination`.

The natural combination is looked up, never created by the handler: `C_ELEMENTVALUE_TRG` creates it for leaf accounts, and the support class matches the trigger-shaped row by `Account_ID`, `C_AcctSchema_ID`, and all 11 optional dimensions being null, including `locationFromAddress` and `locationToAddress`. The lookup is deterministic (`ORDER BY id`, max 1). Summary/heading accounts have no natural combination, so they are skipped and no GL Item is created for them.

Idempotency is based on the existing `C_Glitem_Acct` link to that natural combination, not on the GL Item name. This avoids colliding with manually created GL Items that happen to share a name. When a link already exists during provisioning, the existing GL Item is reused and its name is resynchronized from the subaccount. This keeps onboarding re-runs and later provisioning passes aligned without relying on the GL Item name as the idempotency key. If another schema becomes active later, the support scans the subaccount's other natural combinations and reuses the already-created GL Item instead of creating a second one.

On successful `PATCH` or `PUT` requests that include `active`, the hook re-reads the saved subaccount state and mirrors it onto any already-provisioned `C_Glitem_Acct.active` rows. Pre-ETP-5020 subaccounts that do not yet have GL Item account links simply no-op on this path.

Both creation and active-state synchronization are best-effort secondary effects: failures are logged and swallowed so they never block the parent NEO save. Failures are also isolated per schema, so one broken accounting schema does not stop the remaining schemas for the same subaccount.

**Real-world example — `TbaiConfigSequenceHandler`** (`schemaforge/handlers/TbaiConfigSequenceHandler.java`, `@Named("tbai-config-sequence-handler")`, wired as the `header` entity's `JAVA_QUALIFIER` for the `tbai-config` spec): a post-hook (`afterHandle`) that runs on every successful `POST`/`PUT` of the TBAI Fiscal Configuration. It walks the config's organization tree — plus organization `*` (id `0`), added explicitly since Document Types are very commonly defined at org `*` and would otherwise be silently excluded (same precedent as `SelectorOrgFilter#buildOrganizationPredicate`) — and finds every **active** `DocumentType` whose backing table is `C_Invoice` — which naturally covers sales invoices (`ARI`), purchase invoices (`API`), and their credit notes (`ARC`/`APC`), since all four share that table. Rather than one sequence per Document Type, it ensures the whole scope shares **exactly one** chaining `Sequence` (prefix `TBAI-`): it reuses one already assigned to any qualifying Document Type in scope, or creates a single new one only if none exists yet. This is the core fiscal-correctness rule — TicketBAI chains invoice numbers with a single scope-wide counter, so independent per-Document-Type sequences could collide. A Document Type that already has a chaining sequence (`EM_Tbai_Ad_Sequence_ID`) is left untouched, so re-saving the config is safe (idempotent). Any error is logged and swallowed: this is a best-effort secondary side effect and must never fail the parent save request.

**Real-world example — `RectificativeSupport` (optional-column guard shared across handlers, ETP-4737 "Factura Rectificativa"):** `schemaforge/RectificativeSupport.java` is a package-private helper (not itself a `NeoHandler`) that guards every read of the optional `C_DocType.EM_ETSG_ISRECTIFICATIVE` column, owned by the (optional) SIF General module (`com.etendoerp.sif.general`). Because that column may not exist in a given database, a naive `SELECT` against it would abort the whole shared read-only PostgreSQL transaction for the rest of the request — so `isColumnPresent()` checks `information_schema.columns` once, lazily, and caches the result (`volatile Boolean`, double-checked locking); `isRectificative(DocumentType)` and every caller go through that guard before touching the column, degrading gracefully to `false`/legacy-only behavior when the module isn't installed. Three independent call sites share this one guard instead of each re-implementing the check:
  - `AbstractInvoiceHeaderHandler#enrichIsRectificative` (GET-response enrichment shared by `SalesInvoiceHeaderHandler`/`PurchaseInvoiceHeaderHandler`) and its abstract `classifyDocType(DocumentType)` — resolved by each subclass into the unified `SUBTYPE_RECTIFICATIVA` constant (collapsing the former separate `NC`/`DEV` AR subtypes and the AP credit-memo subtype into one), with a legacy category-based fallback (`ARC`/`ARI_RM` for AR, `APC`/`API`+`isReturn` for AP) so invoices already using the old, now-deactivated Credit Note / Return Invoice document types keep classifying correctly.
  - `ReturnShipmentUtils.findReturnDocTypeForOrg(orgId, docCategory, isSales, requireReturn, requireRectificative)` — the `requireRectificative` parameter, when `true`, adds `Restrictions.eq(DocumentType.PROPERTY_ETSGISRECTIFICATIVE, true)` to the doc-type lookup criteria (silently ignored when the column is absent). Called with `requireRectificative=true` by both `ReturnMaterialReceiptHeaderHandler`'s and `ReturnToVendorShipmentHeaderHandler`'s `createReturnInvoice` action, so a confirmed Goods Return (either direction) auto-generates its invoice against the new unified rectificative doc type rather than a hardcoded legacy category.
  This is the pattern to follow for any future optional-module column: a single lazily-cached presence guard in a small dedicated class, consumed by every handler that needs it, rather than each handler probing `information_schema` independently.

**Real-world example — `NeoHandlerUtils.injectDefaultLocatorIfMissing` (locator/warehouse safeguard shared across InOut-line handlers, ETP-4671 + ETP-4863):** `schemaforge/NeoHandlerUtils.java` bundles three static helpers — `injectDefaultLocatorIfMissing(JSONObject body, Logger log)`, `resolveDefaultLocatorForWarehouse(String warehouseId, Logger log)`, and `belongsToWarehouse(String locatorId, String warehouseId, Logger log)` — called as a `handle()` pre-hook by every `M_InOutLine`-based create flow: `GoodsReceiptLineHandler`, `GoodsShipmentLineHandler`, `ReturnToVendorShipmentLineHandler`, and `ReturnMaterialReceiptLineHandler`. On a line `POST` it guarantees `storageBin` (`M_InOutLine.M_Locator_ID`) always resolves to a `Locator` that belongs to the header `M_InOut`'s own warehouse (`M_InOut.M_Warehouse_ID`):
  - A blank `storageBin`, or an unresolved raw-AD-default token shaped like `@OnHandLocatorDefault@` (see the `@OnHandLocatorDefault@` caveat in `docs/neo-headless-guide.md` §11), is filled with the header warehouse's default active `Locator`.
  - An explicit value that already belongs to the header warehouse is left untouched — the guarantee is about the warehouse, not about collapsing every line onto the warehouse's single "default" bin, so a deliberate picking-bin choice (from the user or an import flow) survives.
  - An explicit value that belongs to a *different* warehouse, or that doesn't resolve to a real, active `Locator` at all, is corrected to the header warehouse's default — this is an unconditional guarantee (ETP-4863 BUG-1), not a fill-if-absent default.
  - When the header warehouse has no default locator configured, the field is left as-is (same graceful-degradation contract as the original ETP-4671 fix) instead of failing the request.

  Typical call site (`GoodsShipmentLineHandler#handle`):
  ```java
  if (NeoEndpointType.CRUD.equals(context.getEndpointType())
      && "POST".equalsIgnoreCase(context.getHttpMethod())) {
    NeoHandlerUtils.injectDefaultLocatorIfMissing(context.getRequestBody(), log);
  }
  ```
  `InventoryLineHandler` (Physical Inventory, a sibling window) deliberately does **not** use this helper — it always overwrites `storageBin` on every `POST`, a different and stronger guarantee that would silently discard a valid in-warehouse bin choice for this InOut family.

**The DAL counterpart — `NeoHandlerUtils.anchorLocatorToWarehouse` (ETP-4863):** `injectDefaultLocatorIfMissing` can only guard what arrives as a JSON body on a line `POST`. Several flows build `M_InOutLine` records **directly through `OBProvider` + `OBDal`** and therefore never reach a `NeoHandler` at all:

  | Call site | What it imports |
  |---|---|
  | `NeoReturnReceiptService.createReturnLineShell` | source document's lines → a return (also used by `CreatePurchaseReturnHandler` and `ReturnShipmentUtils.buildAndSaveReturnLine`) |
  | `ReturnShipmentUtils.assignBinsToLines` | header-level backfill run by `ReturnMaterialReceiptHeaderHandler` / `ReturnToVendorShipmentHeaderHandler` on `documentAction` |
  | `InOutLineFromOrderFactory.createAndLinkLine` | an order's lines → a Goods Shipment / Goods Receipt |

  All of them route their candidate bin through `anchorLocatorToWarehouse(Locator candidate, Warehouse headerWarehouse, Logger log)`, the entity-level twin of the CRUD rule. The rule is unconditional — **the method never returns a locator belonging to another warehouse, and no call site may keep one** — resolved as a 4-step cascade:

  1. `candidate` already belongs to `headerWarehouse` → returned as-is, no query. The guarantee is about the *warehouse*, not about collapsing every line onto one bin, so a deliberate picking-bin choice survives.
  2. otherwise the warehouse's `isDefault` active bin (`findDefaultLocatorForWarehouse`).
  3. otherwise **any** active bin of that warehouse, lowest `searchKey` (`findAnyActiveLocatorForWarehouse`). A warehouse with bins but none flagged default is a configuration gap, and a gap is not a licence to keep pointing at another warehouse.
  4. only when the warehouse has **no active bin at all** → `null`. Every call site writes that `null` through, so the document fails loudly at `M_INOUT_POST` (`InoutLineWithoutLocator`) instead of silently booking stock in the wrong warehouse. In particular `assignBinsToLines` *clears* an unanchorable bin rather than skipping the write — skipping would leave the line pointing at the wrong warehouse, the exact failure this exists to prevent.

  Steps 2 and 3 are **two separate lookups on purpose**. Step 2 (`findDefaultLocatorForWarehouse`) is also the entirety of what the CRUD path resolves via `resolveDefaultLocatorForWarehouse`, and that behaviour is verified in runtime; folding the step-3 relaxation into it would silently change what a line `POST` defaults to. The widening therefore lives in `resolveWarehouseAnchorBin`, composed on top, and only the DAL paths get it.

  Batch callers that anchor many lines of the same header (`assignBinsToLines`) hoist steps 2–4 out of their loop via `resolveWarehouseAnchorBin` and apply `locatorBelongsToWarehouse` per line — Hibernate's L1 cache does not deduplicate criteria queries, so the naive per-line form issues one query per line needing correction.

  **Scope of "one definition":** these helpers unify the CRUD path and the four DAL paths above. `NeoCommercialDocumentFactory.findDefaultLocator(Warehouse)` is still a *separate* implementation of "the warehouse's default bin" (default-flagged, else any active — the same cascade, expressed independently) used by the order→shipment/receipt and invoice→shipment handlers to pick the locator they pass IN. It was deliberately left alone: it feeds `createAndLinkLine`, whose result is re-anchored anyway, so its output can no longer reach the database unchecked.

  **Exempt by design — `CreateInvoiceShipmentHandler`:** it is a fifth DAL writer of `setStorageBin` (invoice → shipment) and is deliberately NOT in the table above. It already resolves its locator from the shipment header's own warehouse and throws an `OBException` when none resolves, so it is structurally incapable of persisting a foreign bin — the same reasoning that keeps `InventoryLineHandler` out of the CRUD helper. Any *new* `M_InOutLine` write path, though, belongs on the list.

  Two failure modes this closed, both observed live:
  - **Imported return lines** copied the SOURCE document's bin verbatim, so a return whose header sat in warehouse A but was built from a document whose lines sat in warehouse B booked its stock transactions in B. `M_INOUT_POST` follows the line's bin, not the header.
  - **`assignBinsToLines` had its precedence inverted** — it preferred `line.getCanceledInoutLine().getStorageBin()` OVER the line's own value. A return line references its source line even when the user typed it by hand in the window, so this header-level pass silently overwrote the correct bin the line handler had just set. Confirmed on RFC Receipts 1000057/1000059/1000061/1000063: header in "Almacen GO", lines rewritten to `AS-0-0-0` of "Almacén Secundario". The line's own bin now wins; the source document's bin is only a fallback for a line that has none.

  One behaviour was also **widened**, but only at the no-locator-at-all edge: `createReturnLineShell` used to guard the write with `if (anchoredBin != null)`, so when the header warehouse had no active locator whatsoever the shell kept whatever bin the entity provider defaulted to instead of an explicit `null`. It now always calls `setStorageBin` with the anchor result, so that edge fails loudly at posting instead of silently keeping a stale value — the same contract every other anchored write path follows. A source line with no bin whose header warehouse DOES have an active locator was already anchored to it before this widening: the cascade treats "absent" and "belongs elsewhere" identically, so that scenario is preexisting behaviour, not part of what changed here.

**Real-world example — `NeoExchangeRateService.hasRate` (one lookup behind two surfaces, ETP-4838):** exchange-rate availability is asked twice for the same user gesture — once by the frontend through `GET /sws/neo/validate-exchange-rate` before it applies a currency change, and once by `afterCallout()` on the order/invoice header handlers, which appends a `WARNING` message when the user edits `currency` by hand. Both now call the package-private `NeoExchangeRateService.hasRate(from, to, date)`, which reuses the endpoint's own `queryRate` — including its `AD_Client_ID IN ('0', ?)` scoping and its inverse-direction fallback — and fails **open** (returns `true`) on any error so a DB hiccup never manufactures a false warning.

  Previously `AbstractOrderHeaderHandler` and `AbstractInvoiceHeaderHandler` each carried a private `hasConversionRate()` copy of the query filtered by `ad_client_id = ?` alone. When ETP-4474 moved the currencyLayer rate sync to the System client, those copies went blind to it: the endpoint answered `hasRate: true` and the callout warned `noExchangeRateAvailable` for the very same pair and date. The lesson generalises — **when a handler needs an answer a NEO endpoint already computes, call the endpoint's helper, don't re-derive the query.** Two copies of a client-scoping filter is exactly the kind of drift that survives review and only surfaces when the data moves.

**Real-world example — `NeoHandlerUtils.fetchProductCodesForLines` (SKU enrichment shared across order/invoice-line handlers, ETP-4941):** the printed PDF's "CÓD." column (Sales Quotation, Sales Order, Purchase Order, Sales Invoice) is meant to show the product's search key (`M_Product.Value`, the SKU), but the field it reads was never populated in the GET response, so the template's fallback chain always landed on the line number instead. `AbstractInOutLineHandler` already solved this for `M_InOutLine` lines (goods shipments/receipts) by joining `m_product` and injecting a `productCode` field on every GET record. ETP-4941 extracts that same query into a shared static helper and reuses it for the two other line families that print through the same PDF template:

  - `NeoHandlerUtils.fetchProductCodesForLines(List<String> lineIds, String lineTable, String lineIdCol, Logger log)` runs `SELECT l.<lineIdCol>, p.value FROM <lineTable> l JOIN m_product p ON p.m_product_id = l.m_product_id WHERE l.<lineIdCol> IN (...)` and returns a `Map<lineId, sku>`. `lineTable`/`lineIdCol` are fixed literals supplied by the caller (never derived from request input), so building the SQL string from them carries no injection risk — only the `?` placeholders carry caller-derived values (`@SuppressWarnings("java:S2077")` documents this at the call site). A line id with no matching row — bad id, deleted line, or a line whose product was removed — is simply absent from the returned map (inner `JOIN`, not `LEFT JOIN`); a matching row whose `p.value` is blank is also skipped. `null`/empty `lineIds` short-circuits to an empty map without touching the DB.
  - `OrderLineHandler#enrichProductCode` calls it with `("c_orderline", "c_orderline_id")`, covering sales-order, purchase-order, and sales-quotation lines (all backed by `c_orderline`).
  - `InvoiceLineHandler#enrichProductCode` calls it with `("c_invoiceline", "c_invoiceline_id")`, covering sales-invoice and purchase-invoice lines.

  Both call sites run from the `GET` branch of `afterHandle()`, immediately before `DiscountLineFilter.filterFromResponse(context)` — mutating the response body in place (same pattern as `InvoiceLineHandler#enrichSourceInvoiceLineId`) so the field survives regardless of whether the discount filter later replaces the response. A line whose product resolves to a non-blank SKU gets `productCode` set on its JSON record; every other line is left without the field. The backend never writes a placeholder value for the missing case — the frontend's `resolveProductCode` (`documentPdf.js`) owns the `'—'` fallback shown on the printed document. Any DB error is caught, logged, and swallowed: this is a best-effort display enrichment and must never fail the parent GET request.
**Real-world example — `UserRoleAssignmentHandler`'s admin-created-user invitation (ETP-4830, superseding ETP-4829):** the `user` entity's `POST` post-hook used to eagerly provision an `etgo_account` row via a now-deleted `EtendoGoAccountProvisioning` bridge class — `pending` by default, or `active` immediately if the admin typed a password on the create form (a temporary workaround gated by `PasswordPolicy.isStrong` in the `handle()` pre-hook). ETP-4830 replaced both:

  - The `handle()` pre-hook no longer reads or validates a `password` field at all — the field, if the frontend still sends one, is simply ignored by this handler (it still reaches `AD_User.Password`, Openbravo's own classic-backend login, unrelated to `etgo_account`). Invite-email is now the only way to activate an admin-created user's account.
  - **Ordering (ETP-4830 human-directed requirement, item #14): "create user → assign personal role → invite."** `afterHandle()`'s `POST` branch FIRST calls `ensurePersonalRoleForNewlyCreatedUser` — which delegates to `UserRoleCompositionService#createFreshPersonalRole(User)`, never the get-or-create `ensurePersonalRole(User)`, so a brand-new user can never end up with someone else's orphaned role (see the org-access/defaults writeup in §8d) — and only THEN calls `CompanyInvitationService#createInvitationForNewlyCreatedUser`, so no other role can ever land on the user before its own personal role exists. The strict ordering is proven by a call-order test, not just "both ran" (`UserRoleAssignmentHandlerTest`'s `afterHandleAssignsPersonalRoleBeforeInvitationOnCreate`). Template-role composition on top of that empty personal role happens independently, any time after creation, via `AssignTemplateRolesControl`'s own save — which is why the invitation intentionally skips the "invited user already has an active role" check below (an empty personal role with no templates composed onto it is not a meaningful "active role" from that check's perspective).
  - `afterHandle()`'s `POST` branch then calls `CompanyInvitationService#createInvitationForNewlyCreatedUser(obContext, email, appBaseUrl, language)` — the same invitation/token/`company-invitation`-contract/dedup/throttle machinery ETP-4894 built for a company administrator inviting an *existing* user (§ `docs/transactional-email-contracts.md`). It resolves the inviter from `context.getObContext()` (captured by the dispatcher before this method's own `OBContext.setAdminMode(true)`, so it still reflects the real acting admin's client/org/user) rather than from an authenticated `etgo_account` bearer token, since this runs from a NeoHandler post-hook, not from the public `/sws/go/invitations` endpoint. It deliberately skips the "invited user already has an active role in the invitation organization" check `createInvitation` otherwise enforces — a freshly `POST`-created `AD_User` has zero roles yet by construction beyond the empty personal role just created above (role composition happens independently, any time after creation, via `AssignTemplateRolesControl`'s own save/`PUT`), so that check would always 400 here and adds no real safety. `CompanyInvitationService` grew a `requireExistingRole` private overload for this rather than a bespoke duplicate of `createInvitationForInviter`, so the new call site still gets dedup-of-an-open-invitation and throttling for free.
  - There is no eager `etgo_account` row on this path any more: the invitation's `register-and-accept` flow is now the *sole* place an `etgo_account` gets created for an admin-created user, lazily, once the invitee actually accepts. Accepting that invitation does not require the invited `AD_User` to already hold a role — an earlier revision's `hasActiveRoleForOrganization` accept-time check was dropped from both `acceptExistingAccountInAdminMode` and `registerAndAcceptInAdminMode` for exactly this reason (a freshly-created user has none yet by construction); see `docs/transactional-email-contracts.md` for the full accept-time contract this handler's invitation flows into.
  - `afterHandle()`'s `GET` branch (list and single-record) now also attaches an `invitationStatus` field to every row — `null` when no invitation was ever sent, otherwise one of `PENDING`/`SENT`/`ACCEPTED`/`EXPIRED`/`REVOKED`/`DELIVERY_FAILED` — via a new `CompanyInvitationService.findLatestInvitationStatus(clientId, email)` (backed by a new `CompanyInvitationDalHelper.findLatestInvitation`, unlike `findOpenInvitation` this is not restricted to `PENDING`/`SENT`). This is what lets the frontend render a "pending invite" badge on the Usuarios grid without a separate round trip.

  Both changes are best-effort, same contract as the handler's other two concerns: any failure is logged and swallowed, never failing the parent `AD_User` request.

  - **`ETGO_INVITATION_USER_FK` cascade delete (ETP-4830):** because this handler makes admin-created-user invitations part of the normal create flow, `ETGO_INVITATION` rows now exist for ordinary users, not just for ETP-4894's opt-in "invite an existing user" path. `ETGO_INVITATION.AD_USER_ID` originally referenced `AD_USER` with no `ON DELETE` behavior (`onDelete` omitted in `src-db/database/model/tables/ETGO_INVITATION.xml`, i.e. `NO ACTION`), so deleting an `AD_User` that had ever received an invitation failed with a 500 ("Este registro no puede ser eliminado ya que está relacionado con otros elementos existentes.") — a pre-existing ETP-4894 schema gap, only surfaced now that this handler makes invitation rows routine. Fixed by adding `onDelete="cascade"` to `ETGO_INVITATION_USER_FK`: deleting the `AD_User` now deletes its `ETGO_INVITATION` row(s) with it, since a dangling invitation for a user that no longer exists can never sensibly be accepted. The sibling `ETGO_INVITATION_CREATEDBY_FK`/`ETGO_INVITATION_UPDATEDBY_FK`/`ETGO_INVITATION_ACCOUNT_FK`/`ETGO_INVITATION_CLIENT_FK`/`ETGO_INVITATION_ORG_FK` constraints are intentionally left as `NO ACTION` — those reference the actor/tenant, not the invited user, and Etendo audit columns (`CREATEDBY`/`UPDATEDBY`) are never expected to be deleted out from under a row.

---

## 6. Parent-Child Tab Filtering

When an entity maps to a child tab (`tabLevel > 0`), the servlet automatically filters records by the parent record. The caller provides the parent record ID via the `parentId` query parameter:

```
GET /sws/neo/sales-order/OrderLine?parentId=ABC123
```

The servlet resolves the parent-child relationship using Etendo's built-in utilities:

1. `KernelUtils.getParentTab(childTab)` finds the parent tab in the window's tab hierarchy.
2. `ApplicationUtils.getParentProperty(childTab, parentTab)` determines the FK property name on the child entity.
3. The property type is inspected via `ModelProvider`:
   - **Entity reference** (most common): generates `e.salesOrder.id='ABC123'`
   - **Primitive** (rare): generates `e.salesOrder='ABC123'`

The generated HQL fragment is injected as a `whereAndFilterClause` parameter into the wrapped request passed to the DataSourceServlet. The `tabId` and `windowId` are also always passed so that the DataSourceServlet applies any tab-level HQL where clauses defined in the AD.

Tabs with `DisableParentKeyProperty = Y` skip parent filtering.

---

## 7. Security

NEO Headless enforces security at multiple levels:

1. **Authentication:** Every request requires a valid JWT bearer token. The token is decoded and validated by `SecureWebServicesUtils`. Invalid or missing tokens return `401`.

2. **OBContext enforcement:** The JWT claims (`ad_user_id`, `ad_role_id`, `ad_org_id`, `m_warehouse_id`, `ad_client_id`) are used to create a full `OBContext`, which is set for the duration of the request. All DAL queries respect the user's organization and client access.

3. **Window access control (tiered read/write):** `NeoAccessHelper.hasWindowAccess(windowId, httpMethod)` resolves access in this order:
   1. No role assigned on the request → deny.
   2. System Administrator role (`"0"`) or any role with `AD_Role.is_client_admin = 'Y'` → always allowed, any method.
   3. No active `AD_Window_Access` row for role+window → deny.
   4. An active row exists: `GET` is always allowed; `POST`/`PUT`/`PATCH`/`DELETE` are allowed only when the row's `IsReadWrite` flag is `true` — a read-only `AD_Window_Access` row grants visibility but denies writes.

   Denied requests return `403 Forbidden`. This is enforced identically at both entry points into window data: the REST servlet (`NeoRequestRouter.handleWindowSpecRequest`) and the MCP tool router (`McpToolRouter`, which maps `neo_create`→`POST`, `neo_update`→`PUT`, `neo_delete`→`DELETE`, everything else→`GET` before calling the same helper).

4. **Windowless/custom spec access ("combination" specs):** a spec with no single backing `AD_Window` (`spec.getADWindow() == null`) can't be checked against one window ID, so `NeoAccessHelper.hasWindowAccessForSpec(spec, httpMethod)` applies three tiers, in priority order:
   1. No role assigned → deny, unconditionally.
   2. The spec's `SFEntity` children resolve — via their `AD_Tab` — to one or more real `AD_Window`s (a "combination" of windows) → the role must have `hasWindowAccess` (for the same `httpMethod`) to **every** one of them; deny if any single one is inaccessible.
   3. No entity has a populated `AD_Tab` at all (no combination data exists — the current shape of the `dashboard` and `not-posted-documents` specs) → fall back to allowing any authenticated role. There is no per-window `AD_Window_Access` provisioning for these specs today, so denying everyone would be a regression rather than a fix.

   Wired into `NeoRequestRouter`, `ToolRegistry#addWindowSpec`, `NeoDiscoveryHelper#isSpecAccessible`, and the MCP support layer (`McpToolRouterSupport`) — anywhere a spec's accessibility needs resolving without assuming a single `AD_Window`. Before this fix (ETP-4510 BUG-3), a windowless spec skipped the access check entirely, even for a request with no role assigned at all.

5. **Process access control:** For process specs and button actions, the servlet checks `ADProcessAccess` for the current role before execution — binary, no read/write tiering: any active row grants full execute access. A request with no role assigned is denied the same as an unrecognized role. Denied requests return `403 Forbidden`.

6. **OBUIAPP process access for report handlers:** two report-type specs (`not-posted-documents`, `aging-receivable`) have no `AD_Process` and no backing `AD_Window`, and previously had zero access control. Their `NeoHandler.handle()` now gates access via `NeoAccessHelper.hasObuiappProcessAccess(processId)` against the real OBUIAPP process, resolved through the `AD_Menu.em_obuiapp_process_id` FK (never by name-matching).

7. **Aging report prerequisites:** before `aging-receivable` delegates to Core's `AgingDao`, its NEO handler validates the resolved organization, organization tree, accounting schema/currency, and confirmed-payment-status reference. A missing derived prerequisite returns an actionable `400` or `422`; it does not surface as a generic `500` from the Core DAO.

8. **Method-level control:** Each HTTP method must be explicitly enabled on the entity record. Disabled methods return `405 Method Not Allowed`.

   MCP `neo_discover` mirrors this configuration per entity through its `methods` array and
   `readOnly` flag. `readOnly: true` means at least one read method is enabled and no POST, PUT,
   PATCH, or DELETE method is enabled, so agents must not attempt a write even when the parent
   window spec is otherwise available.

   **Spec-level `readOnly` marker (ETP-4254 AC#4):** a type-`W` spec whose every included entity
   is read-only also carries `"readOnly": true` at the spec level, so an agent can pick the
   writable specs out of the catalog without inspecting every entity of every spec. The key is
   emitted **only when true** — writable specs have no `readOnly` key at all, and the negative
   case is already carried per entity. It is never emitted for type-`P`/`R` specs. Derived from
   the same entity list the `entities` array is built from, so it costs no extra query.

   ```json
   { "name": "monitor-verifactu", "type": "W", "readOnly": true,
     "entities": [ { "name": "header", "methods": ["GET"], "readOnly": true } ] }
   { "name": "sales-order", "type": "W",
     "entities": [ { "name": "header", "methods": ["GET","POST","PUT","PATCH","DELETE"],
                     "readOnly": false } ] }
   ```

   **The flags are enforced identically on every write entry point (ETP-4254).** The single
   source of truth is `NeoMethodPolicy` (`schemaforge/util/NeoMethodPolicy.java`); `GET` is
   enabled by either `ISGET` or `ISGETBYID`. It is consulted by:

   | Entry point | Where | Refusal |
   |---|---|---|
   | REST CRUD | `NeoCrudHandler#handleWindowEntityCrud` | `405` `"<METHOD> not enabled for <entity>"` |
   | `/batch` + MCP `neo_batch` | `BatchService#createRecord` (the batch enters at `handleDefault`, i.e. after the CRUD gate) | per-op `405`; the batch stops there and rolls back the earlier ops — see §4.12.4.1 |
   | MCP `neo_create` / `neo_update` / `neo_delete` | `McpToolRouterSupport#requireMethodEnabled` | MCP tool error naming the enabled methods and stating the entity is read-only |

   Before ETP-4254 only the REST path checked them, so turning the mutation flags off on a
   monitor/log window blocked the React UI with a `405` while an MCP agent could still write —
   and `neo_discover` reported `readOnly: true` while the write succeeded. Note that
   `hasSpecAccess` (ETP-4510 `AD_Window_Access` tiering) is *role*-level and does not substitute
   for this *entity*-level gate.

   **Not gated by the flags, by design:** the sub-endpoints — `/action/*`, `/process`,
   `/callout`, `/selector`, `/defaults`. `NeoRequestRouter` dispatches them before the CRUD
   gate is reached, so a read-only-CRUD monitor window can still legitimately expose a button
   action (e.g. `fiscal-monitor`'s `Correct_Invoice`). Do not extend the gate to them.

   **MCP tool catalog consequence:** `ToolRegistry` builds one readable enum plus one enum per
   write verb. Read tools (`neo_list`/`neo_get`/`neo_selectors`/`neo_defaults`/`neo_schema`) get
   every accessible window spec. `neo_create`, `neo_update` and `neo_delete` each get only specs
   with at least one entity enabling POST, PUT or DELETE respectively
   (`McpToolRouterSupport#hasEntityWithMethod`). This per-verb split matters for mixed specs:
   `monitor-verifactu` is offered by `neo_update` because one entity keeps PUT/PATCH, but not by
   `neo_create` or `neo_delete`. Fully read-only monitors remain readable and absent from all
   CRUD-write enums. `neo_action` keeps the read enum because actions are not gated by the method
   flags.

   **Catalog exclusion — needs BOTH conditions (`isCatalogExcludedSpec`).** A type-`W` spec is
   dropped from the CRUD catalog *and* from discovery *and* from `McpResourceProvider` only when
   it has neither surface:

   1. every included entity is handler-backed (no `AD_Tab`), so the generic CRUD path cannot
      serve it (`isHandlerOnlySpec`), **and**
   2. no entity's handler declares `NeoHandler#servesActions()`, so there is no `/action` route
      either (`NeoActionSurface`).

   This replaced a hardcoded `"dashboard"` spec-name literal, and is scoped to type-`W` specs
   because type-`R` report specs are handler-only by design. Condition 2 is not optional:
   `hasSpecAccess` also gates `neo_action`, so testing condition 1 alone hid
   `not-posted-documents` — a tab-less spec whose handler serves the `post` / `bulk-post`
   actions — and took a real transactional action away from agents. `dashboard` satisfies both
   conditions and is reached through `neo_widget` instead.

   Because `ETGO_SF_ENTITY` carries no action metadata, condition 2 is a CDI probe of the
   entity's `Java_Qualifier` handler and is **fail-open**: a missing qualifier aside, an
   unregistered handler or a CDI failure keeps the spec visible. **Any handler serving ACTION
   requests should override `servesActions()`** — it is only consulted for tab-less specs today,
   but the declaration keeps the catalog honest if the spec ever loses its tabs.

9. **Field-level control:** Only fields with `ISINCLUDED = 'Y'` participate in selector listings and button action discovery.

10. **Tenant-owner protection (ETP-4830).** `AD_User.EM_ETGO_Is_Owner` (`char(1)`, `NOT NULL DEFAULT 'N'`, an `EM_ETGO_`-prefixed extension column on core's `AD_User` table — same convention as `AD_Role.EM_ETGO_Show_Acct_Fields`, added via the `/etendo:alter-db` webhook mechanism, never by hand-editing core's model XML) flags the ONE `AD_User` who completed self-service onboarding/registration for a client — that client's owner. Read/written via native SQL only (`OwnerSupport`, `schemaforge/util/OwnerSupport.java`), never a DAL getter/setter — the column is not mapped as a typed entity property, exactly the same reasoning `SFWindowAccessMap#resolveShowAccountingFields` documents for its own precedent column.

    - **Assignment.** Auto-set, once, on the real founding admin `AD_User` right after `EtendoGoJwtServlet#createClient` provisions a brand-new client (`InitialClientSetup.createClient`) — before the GOClient sample dataset import (`OnboardingDatasetImportService`) brings in its own bundled `AD_User` rows (`GOAdmin`/`Finance Tester`/`GOuser`), so the founder is unambiguously identified, never one of those sample rows. `OwnerSupport#markAsOwnerIfNoneExists` is idempotent — a no-op once a client already has an owner — so it is safe to call on every resumed/retried onboarding pass, and it is best-effort: a failure here never fails tenant provisioning, it just leaves that client with no owner-lock yet (same as every tenant provisioned before this column existed).
    - **Enforcement, path (a) — generic `AD_User` PUT/PATCH.** `UserRoleAssignmentHandler`'s write-path guard rejects the ENTIRE request with `400` when the target record is flagged as owner and the requester (`context.getObContext().getUser()`) is anyone other than that same owner — blanket, regardless of which fields the request touches. Runs BEFORE the email-immutability and self/last-admin-lockout guards on that same entity, so a non-owner's attempt to edit the owner is rejected with the owner-specific message, not one of those others'. A self-edit by the owner is a no-op here and falls through to those guards normally.
    - **Enforcement, path (b) — role reassignment.** `UserRoleCompositionService#assignTemplateRoles`'s 4-arg overload (`(String, List, Role, String)`) takes the caller's own resolved `AD_User_ID` and rejects composing template roles onto the owner/admin — **unconditionally as of ETP-5019** (no self-service exception; also triggered by currently holding the client-admin role, not just the owner flag — see §8d for the full ETP-5019 writeup), independently of path (a) — an admin reassigning the owner's role through `SFAssignUserRoles` never goes through `UserRoleAssignmentHandler`'s write path at all, so closing only one of the two would leave the other wide open. `SFAssignUserRoles` resolves the caller's `AD_User_ID` from the same `OBContext` its `currentRole` access-gate check already reads, before entering admin mode, and forwards it through — the 2-arg/3-arg overloads (unit tests, any caller with no per-request identity) pass `null`, which skips the check entirely, the same "nothing to enforce" convention `enforceCallerClientBoundary` uses for a `null` caller role.
    - **Baseline / rollout.** Every user existing before this column shipped reads back `false` (the column backfilled `NOT NULL DEFAULT 'N'` on every pre-existing row), so path (a) and path (b)'s owner-flag signal are a guaranteed no-op for them until a separate, human-reviewed backfill data-fix (Remedy's domain, `cli/src/data-fixes/` in `etendo_schema_forge`) assigns a retroactive owner. **Shipped 2026-08-26 (ETP-4877):** `20260826T120000Z__R26-tenant-owner-and-personal-role-retrofit.sql` Step 0 flags the earliest-created `is_client_admin`-holding `AD_User` per client (human-confirmed heuristic), atomically and idempotently, mirroring `OwnerSupport#markAsOwnerIfNoneExists`'s own "never overwrite, never move ownership" shape. One live edge case found and left flagged, not fixed: a tenant with ZERO `is_client_admin` holders at all (e.g. "QA Testing" on the local dev DB) has nothing for this heuristic to act on — surfaced via the fix's own `@report`, not silently skipped. **This does NOT extend to path (b)'s second signal (ETP-5019):** `enforceOwnerProtection` also rejects composition for any user CURRENTLY holding the client-admin role, read live off `AD_Role.is_client_admin` — entirely independent of `EM_ETGO_Is_Owner` — so a pre-existing tenant's real admin user was already protected against the self-overwrite bug regardless of the R26 backfill's status. See §8d.
    - **Read-side exposure (ETP-4830 item #4).** `UserRoleAssignmentHandler#attachOwnerFlag` attaches a boolean `isOwner` field to every `user` GET response row (list + single-record alike) — the same pattern `attachInvitationStatus` already established for `invitationStatus`, one row at a time, best-effort (a lookup failure is logged and swallowed, the row simply never gets the field, never propagated to the caller). Unlike `attachInvitationStatus`, no `clientId`/admin-mode scoping is needed: `OwnerSupport#isOwner` reads straight off the row's own id via a native query, which bypasses OBContext's row-level filtering entirely. The Go SPA (`tools/app-shell` in `etendo_schema_forge`) renders a small neutral "Owner" pill from this field — `windows/custom/user/OwnerBadge.jsx`, shown in both the Users list grid and the detail header's toolbar — see that repo's `docs/generated-custom-windows/user.md` "Owner badge" section.
    - **Scope.** Write-path enforcement (paths a/b above) plus this read-side exposure; there is still no UI to ASSIGN/change ownership — only the auto-set-once-at-registration path and a future backfill (not asked for, tracked separately) ever write the column.

**Report spec access control (ETP-4596):** `NeoAccessHelper.hasReportSpecAccess(SFSpec, String)` is the single gate now shared by all 4 access-check call sites that previously either skipped `SPEC_TYPE = 'R'` report specs entirely or fell through a `spec.getProcess() == null` guard that was always true for them — `NeoRequestRouter.handleReportSpecRequest` (the real HTTP data-access gate, which previously had zero check), `NeoDiscoveryHelper.isSpecAccessible`, `McpToolRouterSupport.hasSpecAccess`, and `ToolRegistry`. It checks a linked `AD_Process`/`OBUIAPP_Process` first when the spec has one (delegating to items 5/6 above), else falls back to the same constituent-window check from item 4, keyed off each active/included `SFEntity`'s `AD_TAB_ID`. Five of the 8 report specs now have `AD_TAB_ID` populated and gate on the classic "Financial Account" window (`AD_Window_ID=94EAA455D2644E04AB25D93BE5157B6D`): `financial-accounts-page`, `financial-account-transactions`, `bank-statements`, `bank-reconciliation`, `financial-account-bank-connection`. Verified end-to-end against real roles: `403` for a role lacking Financial Account window access, `200` for a role that has it; discovery listing correctly excludes these specs for an unauthorized role while still showing them to an authorized one.

**Known limitations (ETP-4596):** two report specs — `tax-report` and `inventory-stock-report` — are wired to neither a classic `AD_Process` nor a populated `AD_TAB_ID` yet, so they still hit `hasReportSpecAccess`'s permissive fallback and remain reachable by any authenticated role regardless of `AD_Window_Access`. Closing this needs a functional decision on their process/window mapping (pending, tracked separately); once linked, they gate with zero further code changes. Unrelated to access control: `bank-reconciliation`'s handler currently returns `500` for correctly-authorized roles due to a pre-existing `ReconciliationHandler` dispatch bug ("No AD_Tab linked to entity") — the RBAC gate added above is confirmed correct for it; the report itself is separately non-functional today even for authorized users.

---

## 8. Navigation Menu (SFListMenu Webhook)

`SFListMenu` (`GET /webhooks/SFListMenu`, or preferably `GET /sws/neo/listmenu` — §4.10) returns the `AD_Menu` tree — or a flat filtered search with `?q=` — as JSON, pruned down to what the requesting role can actually reach. It is the role-filtered menu-tree webhook, correctly implemented and available for any client to consume. The webhook itself is authored alongside the `SFUpsertSpec`/`SFPopulateSpec` configuration webhooks (§5.1) in the Webhooks module infrastructure, but the Go SPA reaches it through the NEO pseudo-spec bridge (§4.10), not `/webhooks/SFListMenu` directly.

> **Note:** the Go SPA sidebar (`tools/app-shell` in `etendo_schema_forge`) now consumes this webhook (`useRoleMenu()` → `lib/menuTree.js`) to compute which menu entries the current role may see — but only for *filtering*: the tree's structure, labels, and icons still come from a static `menu.json`, and `useRoleMenu()` only extracts the set of allowed `windowId`/`processId`/`obuiappProcessId`s from the fetched tree to hide/show the corresponding static entries. Fully driving the rendered tree's shape (order, grouping, nesting) from this webhook's response, rather than only its ids, is still open — tracked as ETP-4598.

**Endpoints:**

| Pattern | Method | Description |
|---------|--------|-------------|
| `/webhooks/SFListMenu` (legacy) / `/sws/neo/listmenu` (preferred) | GET | Full nested menu tree, filtered by the current role's access |
| `/webhooks/SFListMenu?q=<term>` (legacy) / `/sws/neo/listmenu?q=<term>` (preferred) | GET | Flat list of menu entries whose name matches `<term>` (case-insensitive substring), same filtering |

**Response shape:**

```json
{
  "tree": [
    {
      "id": "...", "name": "Sales", "type": "folder",
      "children": [
        { "id": "...", "name": "Sales Order", "type": "window", "windowId": "143" }
      ]
    }
  ],
  "count": 2,
  "viewerRoleId": "...",
  "viewerIsClientAdmin": false
}
```

`type` is derived from `AD_Menu.issummary`/`action`: `folder` (summary node), `window` (`action = 'W'`), `process` (`action = 'P'`), `report` (`action = 'R'`), `form` (`action = 'X'`), or `other`. Leaf nodes carry whichever of `windowId`, `processId`, `obuiappProcessId`, `formId` applies; folders carry `children` instead.

**`viewerRoleId`/`viewerIsClientAdmin` (ETP-5019 follow-up).** The CALLING user's own current
`AD_Role_ID` and whether that role is the tenant's client-admin role — reusing the role already
resolved for the tree/search filtering above, not a second lookup. Absent entirely on the no-role
response (`{"tree": [], "count": 0}`, no viewer keys at all). This has nothing to do with menu
rendering itself; it exists because `SFListMenu` is the app's only once-per-session call, so the
frontend's `useViewerRole()` hook (`tools/app-shell/src/hooks/useViewerRole.js` in
`etendo_schema_forge`) piggybacks on it to expose the current viewer's own permission level to any
component that needs to gate UI by it (e.g. the User window's admin promote/demote buttons),
without a dedicated endpoint or a second network round trip.

**Access filtering:** the requesting role is captured once, at the very top of the request, *before* the servlet enters `OBContext.setAdminMode()` — admin mode is only used to bypass row-level security on the underlying native SQL queries that build the tree, never to decide access. A request with no role assigned gets `{"tree": [], "count": 0}` immediately, without even querying the database.

Once the role is known, each node is checked (a node must pass every check it carries):

| Node carries | Checked via | Notes |
|---|---|---|
| `windowId` | `NeoAccessHelper.hasWindowAccess(role, windowId)` | Any tier (read-only or full) is enough to appear in the menu — the menu answers "is this reachable at all", not "can I write to it". |
| `processId` | `NeoAccessHelper.hasProcessAccess(role, processId)` | Binary, as in §7. |
| `obuiappProcessId` | `NeoAccessHelper.hasObuiappProcessAccess(processId)` | Covers `action = 'OBUIAPP_Process'` menu entries, linked via `AD_Menu.em_obuiapp_process_id` rather than `ad_window_id`/`ad_process_id`. This closes the gap where the two OBUIAPP-gated report specs (§7 item 6) were still fully visible in the menu even though their handlers now reject unauthorized requests. |

A node carrying none of these IDs (typically a `report`/`form`/`other` node with no OBUIAPP link) is left unfiltered — filtering those is out of scope for this change.

Folder nodes are never filtered directly: their children are filtered first (post-order), and the folder itself is dropped from the tree only if it ends up with zero accessible children. `count` is recomputed after pruning — it does not reflect the raw row count from the DB.

**Explicitly out of scope:** this endpoint controls what appears *in the menu*. It does not block direct/deep-link navigation to a window the role has no access to — that reactive gap remains open. The proactive counterpart — telling the frontend up front what tier it has for every window, before it renders anything — is `SFWindowAccessMap`, described next (ETP-4520).

---

## 8b. Proactive Window-Access Map (SFWindowAccessMap Webhook)

`SFWindowAccessMap` (`GET /webhooks/SFWindowAccessMap`, or preferably `GET /sws/neo/windowaccessmap` — §4.10) reports, for the current authenticated user/role, its access tier for every window it has an explicit grant for, plus whether it may see accounting-sensitive data — so the frontend can adapt *before* rendering instead of discovering a `403` reactively per-request (§7 item 3). The webhook is authored in the same Webhooks module infrastructure as `SFListMenu`, but the Go SPA reaches it through the NEO pseudo-spec bridge (§4.10).

**Response shape:**

```json
{
  "windowAccess": { "111": "full", "268": "read-only" },
  "capabilities": { "showAccountingFields": true, "isAdminOrClientAdmin": true }
}
```

`windowAccess` keys are `AD_Window_ID`s; a window with no active `AD_Window_Access` row for the role is simply absent — the frontend treats a missing key as `"none"`.

**Resolution order** (mirrors `NeoAccessHelper.hasWindowAccess(Role, String, String)`, §7 item 3):

1. No role assigned → `{"windowAccess": {}, "capabilities": {}}`, without querying the database — same convention as `SFListMenu`: the role is captured once, at the very top of the request, before the servlet enters `OBContext.setAdminMode()`.
2. System Administrator role (`"0"`) or a client-admin role (`NeoAccessHelper.isAdminOrClientAdmin(Role)`, now `public` specifically so this webhook can reuse it) → every distinct `AD_Window` backing an active, `SPEC_TYPE = 'W'` `ETGO_SF_SPEC` resolves to `"full"`, and `capabilities.showAccountingFields` / `capabilities.isAdminOrClientAdmin` are both always `true` — the accounting column is never even queried for this branch.
3. Otherwise, for every active `AD_Window_Access` row the role has: `IsReadWrite = true` → `"full"`; `IsReadWrite = false` → `"read-only"`. `capabilities.showAccountingFields` is read directly off the new `AD_Role.EM_ETGO_Show_Acct_Fields` boolean extension column (ETP-4520) for the resolved role, via a native SQL lookup rather than the DAL entity model (the column was added straight to the physical table and is not yet mapped as a typed entity property). `capabilities.isAdminOrClientAdmin` is always `false` in this branch — reaching it at all already proves the bypass check in step 2 failed for this role.

**`AD_Role.EM_ETGO_Show_Acct_Fields`:** a Yes/No extension column added by this module (`AD_Column_ID = A0F2D12B5B4A48C2855EE73E3E93E274`, default `N`) and exposed as a real field (`AD_Field_ID = 98C71197D0744EED96856A497E49F159`) on the classic `AD_Role` window/tab, so a functional consultant can toggle it like any other role attribute. It gates accounting-sensitive field/tab visibility in Etendo GO — e.g. the `Posted` status pill on invoice windows and the financial-account edit form's "Cuentas contables" tab — independently of per-window `AD_Window_Access`. **`resolveShowAccountingFields` above reads it as a flat stored value with no join to `AD_Role_Inheritance` — it is a DERIVED fact, not an independent one, for any role composed via `UserRoleCompositionService` (ETP-4852).** `UserRoleCompositionService#syncShowAccountingFieldsFlag` (ETP-4877), called unconditionally at the end of every `reconcileInheritances`, keeps a personal role's column in sync with whether it currently inherits from the system Finance template (`'Y'` iff yes, `'N'` otherwise — both directions, including Finance being removed). The retroactive half for personal roles that predate this sync (or were never touched by a live composition call) is `R26-tenant-owner-and-personal-role-retrofit.sql` Step 8b in `etendo_schema_forge`, plus a one-time system-level health check (Step 8a) correcting the Finance template's own column, found stale (`'N'`) on the local dev DB. Both predicates must be kept in lockstep.

**`capabilities.isAdminOrClientAdmin`** (ETP-4513) is the proactive signal the frontend uses to decide whether to show admin-only settings entries — e.g. the "Configuración > Roles" menu item, backed by `SFRolesOverview` (§8c) — up front, instead of showing them to every role and handling denial only once the page itself loads.

---

## 8c. Roles Overview (SFRolesOverview Webhook)

`SFRolesOverview` (`GET /webhooks/SFRolesOverview`, or preferably `GET /sws/neo/rolesoverview` — §4.10) returns, for an admin/client-admin caller only, a cross-role aggregate for the CALLING TENANT's 5 fixed roles (ETP-4513 — "Configuración > Roles"): each role's display name, raw `AD_Role.description`, count of distinct assigned users, an explicit window count, and the list of Etendo GO windows it can reach (`AD_Window_Access`, intersected with the windows Etendo GO actually exposes today) — plus (ETP-4907) a full window × role permission `matrix`, grouped by top-level menu category. The webhook is authored in the same Webhooks module infrastructure as `SFListMenu`/`SFWindowAccessMap`, but the Go SPA (`RolesOverviewPage.jsx`) reaches it through the NEO pseudo-spec bridge (§4.10).

Unlike `SFWindowAccessMap`, which answers "what can the CURRENT caller's own role reach", this endpoint is a cross-role aggregate: it always returns data for all 5 of the caller's OWN tenant's roles regardless of which one the caller happens to be using. That is exactly why it is gated to admin/client-admin callers only.

**UI-excluded windows (ETP-5068).** `resolveActiveEtendoGoWindowsById()` subtracts
`SFRolesOverview.UI_EXCLUDED_WINDOW_IDS` from the active-`SPEC_TYPE='W'` spec set: windows Etendo GO
serves read-only over NEO/MCP but deliberately shows nowhere in its own UI. Because that one method is
the single source every downstream structure derives from — each role's `windows` array, its
`windowCount`, and the `matrix` — a single entry in that set removes the window from **both** admin
screens at once:

- **"Configuración > Roles"** (`RolesAccessMatrix.jsx`) renders `matrix.categories` directly.
- **"Usuario > Roles"** (`UserRolesTab.jsx`) walks `SFListMenu`'s raw AD tree but intersects it
  against the union of every role's `windows[]` from THIS endpoint (`activeWindowIds`), which is also
  what already keeps classic-only entries such as Application Dictionary out of that tab.

Note the exclusion cannot be achieved by revoking `AD_Window_Access`: the `matrix` lists every GO
spec window regardless of grants (an ungranted window simply shows `access: "none"`), and the grants
are deliberately kept so administrators can still reach the window in Etendo classic. It is also
deliberately NOT applied in `SFListMenu`, whose tree must keep reporting the native AD menu as-is for
its other consumers (`useRoleMenu`'s allowed-id filter, the Explorer's spec picker).

Current contents: `6FEBA130CDE24CC09041FFA6117ADFA9` — "Conversion Rate Downloader Log" (ETP-5068),
an internal log of the conversion-rate downloader job that adds no value to the Etendo Go end user.

> **Doc correction (ETP-4907):** this section previously described a `SFRolesOverview.GOCLIENT_ROLE_IDS` hardcoded to GOClient's own 5 per-client role ids. That was already stale — the webhook was fixed on 2026-07-27 (live RolesPresa bug) to resolve roles by name (`Finance`/`Sales`/`Purchasing`/`Inventory`) plus `is_client_admin='Y'`, scoped to `currentRole.getClient()`, with no hardcoded id list at all. This section now documents the actual current behavior, including the ETP-4907 system-template fallback below.

**Endpoint:**

| Pattern | Method | Description |
|---------|--------|-------------|
| `/webhooks/SFRolesOverview` (legacy) / `/sws/neo/rolesoverview` (preferred) | GET | Per-role aggregate (user count, window count, reachable windows) for the caller's own 5 fixed roles, plus the full permission matrix |

**Response shape:**

```json
{
  "roles": [
    {
      "id": "9B8D736190724807AB256DC95F20EC5E",
      "name": "GOClient Admin",
      "rawDescription": "*** Please, do not edit this role. Use Copy Record instead ***",
      "isClientAdmin": true,
      "roleSource": "tenant",
      "userCount": 1,
      "windowCount": 48,
      "windows": [
        { "id": "143", "name": "Sales Order", "tier": "full" },
        { "id": "259", "name": "Business Partner", "tier": "read-only" }
      ]
    },
    {
      "id": "B88A34B5D1874F8685FA6F3C3A609412",
      "name": "Finance",
      "rawDescription": null,
      "isClientAdmin": false,
      "roleSource": "systemTemplate",
      "userCount": 9,
      "windowCount": 27,
      "windows": [ "..." ]
    }
  ],
  "matrix": {
    "categories": [
      {
        "name": "Sales Management",
        "windows": [
          {
            "id": "143",
            "name": "Sales Order",
            "access": {
              "9B8D736190724807AB256DC95F20EC5E": "full",
              "B88A34B5D1874F8685FA6F3C3A609412": "none",
              "...": "read-only"
            }
          }
        ]
      }
    ]
  }
}
```

Field types: `id`/`name`/`rawDescription` (`string`, `rawDescription` may be `null`), `isClientAdmin` (`boolean`), `roleSource` (`string`, `"tenant"` or `"systemTemplate"`), `userCount`/`windowCount` (`integer`), `windows` (`array` of `{id, name, tier}` — `tier` is `"full"` or `"read-only"`, never `"none"` — sorted by name), `matrix.categories` (`array` of `{name, windows}` — sorted by category name), each matrix window's `access` (`object` keyed by role `id` from the `roles` array above, value `"full"` / `"read-only"` / `"none"`).

> **Data-accuracy caveat (ETP-4907 REVIEW/QA finding) — do NOT "fix" the code to match the Figma
> reference screenshot's numbers.** The ETP-4907 reference screenshot shows 17/17/17/18
> `windowCount` and 13/17/9/126 `userCount` for the 4 template roles (Finance/Sales/Purchasing/
> Inventory order) — those are **mockup placeholders**, not live data, and were never meant to be
> reproduced exactly. The confirmed LIVE figures for GOClient (2026-08-18) were: Admin **48
> windows / 2 users** (exact match with the pre-ETP-4907 behavior — Admin is never subject to the
> system-template fallback), and for the 4 templates **Finance 27 / Sales 13 / Purchasing 11 /
> Inventory 13 windows** (independently verified against `AD_Window_Access` for
> `SystemRoleTemplates`'s roles). The templates' `userCount` figures were **not** independently
> DB-verified beyond confirming the composition query (`UserRoleCompositionService
> #getAppliedTemplateRoleIdsForClient`) runs and returns a plausible count — a future reader
> seeing a `userCount` that doesn't match the Figma mock should treat the Figma numbers as wrong,
> not the code.
>
> **Update (ETP-5065):** the Admin figure above is now stale on `userCount` (not `windowCount`) —
> see the cross-client bootstrap-user fix described below. Re-verified live against the same
> GOClient DB, 2026-08-27: Admin is now correctly **48 windows / 1 user** (the second "user" the
> 2026-08-18 count included was `AD_User_ID = '100'`, the System-level `admin`/`admin` bootstrap
> login that core auto-grants an active role on every client — never a real GOClient member).



**Access gate:** the current role is captured once, at the very top of `get(Map, Map)`, before the servlet enters `OBContext.setAdminMode()` — same convention as `SFListMenu`/`SFWindowAccessMap`: admin mode is only used to bypass row-level security on the underlying queries, never to decide access. A request with no role assigned, or a role that is not admin/client-admin (`NeoAccessHelper.isAdminOrClientAdmin(Role)`), gets `{"roles": []}` immediately, without querying a single `Role` — mirroring `SFListMenu`'s "deny silently, don't 403" convention for this webhook family.

**`rawDescription` is NOT display copy.** `AD_Role.description` is boilerplate for 4 of the 5 GOClient roles today (`"*** Please, do not edit this role. Use Copy Record instead ***"`) — this backend has no i18n awareness, so it cannot produce user-facing copy itself. The field is returned only as a raw/debug fallback; the frontend (`RolesOverviewPage.jsx` in `etendo_schema_forge`) maps the 4 fixed role NAMES (and the `isClientAdmin` flag for the 5th) to curated, i18n-keyed copy instead of rendering this field. The same applies to `matrix.categories[].name`, which is the raw (English) top-level `AD_Menu` folder name for each window — the frontend is expected to map/translate it, not render it verbatim.

**Tenant-relative role resolution:** the client-admin role plus the 4 named roles (`Finance`/`Sales`/`Purchasing`/`Inventory`) are resolved via an `OBCriteria<Role>` scoped to `currentRole.getClient()` — never a hardcoded id list, and never GOClient's ids for a different tenant's caller. A fixed name with no ACTIVE match at the tenant level simply falls through to the system-template fallback below (or is omitted entirely if that also fails to resolve) rather than erroring.

**System-template fallback (ETP-4907).** ETP-4852 introduced 4 single, system-owned (`AD_Client_ID = '0'`) template roles (`SystemRoleTemplates`, §8f) that a tenant's users now *compose* their access from (`UserRoleCompositionService`, §8d), rather than every tenant keeping its OWN active copy of the 4 fixed-name roles. A tenant that has migrated to this model — confirmed live for GOClient, 2026-08-18: its own `Finance`/`Sales`/`Purchasing`/`Inventory` rows are `IsActive = 'N'` — would otherwise silently drop from 5 role cards to 1 (just its client-admin role). For each of the 4 fixed names with no active tenant-scoped match, this webhook now falls back to the matching `SystemRoleTemplates.byName()` system role:

- **`windows`/`windowCount`** are resolved via the exact same `AD_Window_Access` query used for a real tenant role (it already disables client/organization filtering, so it works unchanged for a system-client role — no separate "system template window resolution" exists).
- **`userCount`** is the number of this client's users whose PERSONAL role currently composes that template — from `UserRoleCompositionService#getAppliedTemplateRoleIdsForClient(String)` (called once per request, lazily, whenever at least one fixed-name card needs composition data) — **never** a direct `AD_User_Roles` count against the template itself, which would always read zero (users are never assigned a template role directly).
- **`id`** is the SYSTEM template's own `AD_Role_ID` (client `'0'`) — the SAME id `SFSystemRoleTemplates` (§8f) returns for that role. Callers must not assume every card's `id` belongs to the caller's own client.
- **`roleSource`** is `"systemTemplate"` (vs. `"tenant"` for a real tenant-owned role, including the client-admin card, which is never subject to this fallback — see the class javadoc's "Never touches the Admin role" convention shared with `UserRoleCompositionService`).

Both paths can appear side-by-side within one response (a tenant may have migrated some fixed roles but not others) — this is intentional graceful coexistence, not a bug. **ETP-5065 hybrid-state rule:** when a tenant still has its own active Finance/Sales/Purchasing/Inventory role, that tenant role remains the source for `windows`, `windowCount`, and `matrix` access, but `userCount` is the union of direct assignees of the tenant role and users whose personal role composes the matching system template. This prevents migrated users from disappearing from the card while preserving the tenant role's access data. **Admin is never affected by template composition**: it is always sourced from the tenant's own client-level `AD_Role`/`AD_User_Roles`/`AD_Window_Access`; after ETP-5065's cross-client bootstrap-user exclusion, GOClient's Admin count is 48 windows / 1 user.

**`windows`/`matrix` window universe:** every distinct `AD_Window` backing an active, `SPEC_TYPE = 'W'` `ETGO_SF_SPEC` — i.e. every window Etendo GO actually exposes today — so inherited/legacy grants to native-only Etendo windows don't leak into either structure. Each `windows[]` entry's `tier` resolves the same way as `SFWindowAccessMap`: `IsReadWrite = true` → `"full"`, `IsReadWrite = false` → `"read-only"`; a role's `windows` array only lists windows it can actually reach (sorted by name).

**`matrix`** additionally covers **every** Etendo GO window — including ones no role in the response can reach at all (`"none"`) — grouped by the window's top-level `AD_Menu` folder (tree `'10'`, the same tree `SFListMenu` walks) via one recursive-CTE native query; a window linked from two different top-level folders deterministically picks the alphabetically-first one, and a window with no resolvable folder falls back to the `"Other"` bucket. Categories are sorted by name; each category's windows are sorted by name.

---

## 8d. Compose User Roles From Templates (SFAssignUserRoles Webhook, ETP-4852)

`SFAssignUserRoles` (`GET /sws/neo/assignuserroles?UserId=<id>&TemplateRoleIds=<id1,id2,...>`
— reached ONLY through the NEO pseudo-spec bridge, §4.10/§4.11; there is no legacy
`/webhooks/SFAssignUserRoles` path since this webhook was authored after the bridge pattern was
already established) backs the reworked "assign roles to user" UI: instead of picking ONE shared
role (the old `AD_User.Default_Ad_Role_ID` single-dropdown flow, still handled by
`UserRoleAssignmentHandler`'s `user`-entity `PUT`/`PATCH` sync for any caller who has not been
migrated to the new flow yet), an admin picks **1+** system-level template roles to **compose**.

**The model this replaces:** four fixed roles (Finance/Sales/Purchasing/Inventory) used to be
duplicated per client by `OnboardingRoleProvisioningService`, unwired from the live onboarding
chain by this same ticket and then deleted outright (REVIEW cycle 1 finding S1, confirmed
unreachable from any production path) — see the now-retired-and-deleted pointers left in
`SFRolesOverview`'s javadoc for what it used to do. ETP-4852 moves them to a single canonical, system-owned
(`AD_Client_ID = '0'`) `AD_Role` row per role, `IsTemplate = 'Y'` / `IsManual = 'Y'`, seeded once
by the `EnsureSystemRoleTemplatesScript` `ModuleScript` (`com.etendoerp.go.roles`) — a plain
Etendo dataset (`referencedata/standard/`) was considered instead but rejected: dataset import
only fires during `InitialClientSetup` for a brand-new client or via a manual
admin-triggered `UpdateReferenceData` action, neither of which runs on every `update.database`
execution against every environment (new or existing), which is the one guarantee this seeding
actually needs. The "Admin"
(`is_client_admin = 'Y'`) role stays client-level, per tenant, untouched.

**The mechanism (`com.etendoerp.go.roles.UserRoleCompositionService`):** each user gets exactly
ONE personal, non-template, non-admin `AD_Role` (found by reuse or created on first use), which
inherits — via a real `AD_Role_Inheritance` row per requested template, added through `OBDal`
(never native SQL) — from every template the caller asked for. Core's own
`RoleInheritanceEventHandler`/`RoleInheritanceManager` (unmodified, `org.openbravo.role.
inheritance`) does the actual propagation of `AD_Window_Access` (and every other inheritable
access type) onto the personal role — this module never hand-copies an access row. Requesting a
DIFFERENT set on a later call reconciles (adds what's missing, removes what's no longer
requested) rather than only ever growing.

**Org access + user defaults on creation (ETP-4830 items #6.1/#6.2).** Confirmed against real
tenant data that a freshly-minted personal role previously had ZERO `AD_Role_OrgAccess` rows —
`createPersonalRole` set the role's own header org to `'*'` but never granted it access to
anything — and the user's `Default_Ad_Client_ID`/`Default_Ad_Org_ID`/`EM_SMFSWS_Default_WS_Role_ID`
were left at whatever generic `AD_User` defaulting produced (NOT tenant-scoped — one real test
user's `Default_Ad_Client_ID` pointed at a completely different tenant's client entirely).
`createPersonalRole` now also: (1) grants the role `AD_Role_OrgAccess` to the user's own
organization plus the wildcard `'*'` (skipping the duplicate when the user's own org already IS
`'*'`); (2) sets `Default_Ad_Client_ID`/`Default_Ad_Org_ID` to the user's own client/organization,
`Default_M_Warehouse_ID` to the first active warehouse found for that organization (left untouched
if none exists — never forced to a wrong org's warehouse), and `EM_SMFSWS_Default_WS_Role_ID` to
the newly-created role itself. `Default_Ad_Role_ID` is deliberately NOT touched by this method —
every existing caller already sets it immediately after `createPersonalRole` returns, at the same
"a role was just resolved for this user" moment. Applies uniformly to BOTH callers of
`createPersonalRole` (`createFreshPersonalRole`'s always-fresh path and
`resolveOrCreatePersonalRole`'s get-or-create path), so it fires wherever a personal role is
genuinely minted, regardless of which caller triggered it — including the still-unbuilt ETP-4877
existing-user backfill, once that reuses this same method as planned.

**Verified live (not just from reading the source):** a template role and a personal role
naturally end up on DIFFERENT `AD_Client_ID`s (`'0'` vs. the tenant's own) — confirmed this does
NOT block enforcement anywhere in either NEO (`NeoAccessHelper.findActiveWindowAccess`) or
classic core (`EntityAccessChecker`, `Seguridad_data.xsql`), since neither filters
`AD_Window_Access` by client, and a tenant role's own readable-clients set always includes `'0'`
by design (the exact mechanism reference/tax tables already rely on). See
`UserRoleCompositionServiceIntegrationTest` for the real-DB proof (create template → add
inheritance → confirm `AD_Window_Access` appears on the personal role with the right
`InheritedFrom`, across clients).

**Response shape:**

```json
// Success:
{"success": true, "userId": "...", "personalRoleId": "...",
 "templateRoleIds": ["...", "..."], "added": 1, "removed": 0}
// Validation failure or access denial (still HTTP 200 — see below):
{"success": false, "message": "Role is not a template, cannot be composed: ..."}
```

**Access gate:** admin/client-admin only (`NeoAccessHelper.isAdminOrClientAdmin`), captured
before entering admin mode — same convention as `SFRolesOverview`. No role, or a restricted
role, gets `{"success": false, "message": "Not authorized"}` without touching the database.

**Tenant boundary of the TARGET user (REVIEW cycle 1 fix, ETP-4852).** The access gate above
only answers "may this caller use this webhook at all" — it does not limit WHICH `userId` a
client-admin may target, because `isAdminOrClientAdmin` treats a per-tenant client-admin the
same as the literal System Administrator. The original implementation never verified that the
target user's client matched the CALLER's own client, so a client-admin for Tenant A could
reassign — or completely strip, with an empty `TemplateRoleIds` — any Tenant B user's roles: a
real cross-tenant privilege-escalation bug, not a theoretical one. The fix,
`UserRoleCompositionService.enforceCallerClientBoundary(User, Role)`, runs immediately after
`user` is resolved and before any template validation or admin-mode entry, and rejects a
non-system caller whose client differs from the target user's. The bypass is scoped to the
LITERAL System Administrator role id (`"0"`) — never to a mere `isClientAdmin()` role, however
privileged within its own tenant. `SFAssignUserRoles.get()` forwards its already-resolved
`currentRole` into the tenant-boundary-enforcing `assignTemplateRoles` overload for exactly this
purpose — see that method's javadoc. **Overload note, updated by ETP-4830:** at the time of this
fix that was the 3-arg `assignTemplateRoles(String, List, Role)` overload directly; the real
webhook call site today is the 4-arg `assignTemplateRoles(String, List, Role, String)` overload
(which also carries the caller's `AD_User_ID` for the owner-protection check described below) —
the 3-arg form still exists but now only delegates to the 4-arg one with `callerUserId=null`. The
tenant-boundary logic itself, `enforceCallerClientBoundary`, is unchanged either way. A 2-arg
`assignTemplateRoles(String, List)` overload also exists (delegates down with `callerRole=null`,
skipping the boundary check entirely); today it is only reached by plain unit tests and the
integration test's fixture calls, but REVIEW flagged it as a non-blocking latent risk — a future
caller reaching for the 2-arg overload instead of a `Role`/caller-carrying one would silently ship
without this protection.

**Owner/admin protection of the TARGET user (ETP-4830, made unconditional by ETP-5019) — a
genuinely separate check from the tenant boundary above.** The tenant-boundary check answers "is
this target user in the caller's own client at all"; it says nothing about whether the target is
that client's owner/admin. `AD_User.EM_ETGO_Is_Owner` (see §7 item 10 for the full mechanism) flags
the ONE user who completed self-service registration for a client.
`UserRoleCompositionService.enforceOwnerProtection(User, String)` closes the role-reassignment half
of the owner-protection rule: it runs right after `enforceCallerClientBoundary`, inside the 4-arg
`assignTemplateRoles(String, List, Role, String)` overload, and rejects composing ANY template role
onto the target when EITHER of two signals fires — `OwnerSupport.isOwner(user.getId())` reads
`true`, OR the target's current `Default_Ad_Role_ID` resolves to a role with `isClientAdmin() ==
true` (so a second user manually granted the classic "Admin" role via core, not necessarily the
flagged owner, is covered too) — as long as a real caller identity was supplied
(`callerUserId != null`).

> **ETP-5019 fix — the original self-service exception is gone.** ETP-4830's first cut treated
> `callerUserId.equals(user.getId())` — the owner reassigning their OWN roles — as a no-op, on the
> theory that only a DIFFERENT caller targeting the owner was the risk. That theory was wrong: the
> owner's/admin's default role is the client-admin "Admin" role, which `isReusablePersonalRole`
> explicitly refuses to treat as a reusable personal role (`isClientAdmin()` check) — so composing
> even ONE template role, self-service or not, made `resolveOrCreatePersonalRole` mint a brand-new
> personal role and `user.setDefaultRole(personalRole)` SILENTLY REPLACED the Admin role with it.
> The reported bug: a tenant owner/admin composing roles through the normal `User` window UI
> unknowingly demoted themselves. The fix removed the self-service exception entirely (the guard is
> now truly unconditional whenever a caller identity is present) and added the independent
> `isClientAdmin()` signal so any current client-admin holder is covered even before the ETP-4877
> owner-flag backfill runs (see the Baseline/rollout note in §7 item 10).

`SFAssignUserRoles.get()` resolves `callerUserId` from the same `OBContext` its `currentRole`
access-gate check already reads — before entering admin mode — and forwards it through as the 4th
argument. The 3-arg overload (unit tests, any caller with no per-request identity) delegates with
`callerUserId=null`, which skips this check entirely, mirroring the 2-arg/`callerRole=null`
convention above. **This check is independent of, and does not substitute for,
`UserRoleAssignmentHandler`'s equivalent guard on the plain `AD_User` PUT/PATCH path (unaffected by
ETP-5019 — still the original owner-vs-non-owner rule, self-edit by the owner remains a no-op
there)** — an admin reassigning the owner's role through this webhook never reaches that handler's
write path at all, so both had to be closed separately. On the frontend,
`AssignTemplateRolesControl` (`tools/app-shell/src/windows/custom/user/`, `etendo_schema_forge`)
additionally detects the same condition client-side and renders a locked message instead of the
composition editor, so the doomed interaction is never even offered — see that repo's
`docs/generated-custom-windows/user.md` → "Owner/admin composition lock (ETP-5019)".

> **Template-role lifecycle: deactivation while depended-upon is a DB-level non-issue (QA
> finding, ETP-4852).** Reading `src-db/database/model/triggers/AD_ROLE_CHECK_TRG.xml` directly
> confirms core's own `ad_role_check_trg()` — a `BEFORE UPDATE` trigger on `AD_Role` itself —
> refuses to set `IsActive = 'N'`, or uncheck `IsTemplate`, on ANY role that an
> `AD_Role_Inheritance` row still points `InheritFrom` at (`@CannotUncheckTemplateRole@`), for
> EVERY writer (`OBDal` or raw SQL alike), gated only by core's own explicit trigger-disable
> bypass. In practice: **a template role can never be deactivated while an active inheritance
> still depends on it — the database physically prevents it.** Whoever eventually builds
> ETP-4877's existing-tenant retrofit never needs application-level code to defend against
> "template deactivated out from under an active inheritance", because it cannot happen.

**⚠️ Deliberately does NOT use the bridge's `error`/HTTP 500 path for expected rejections.**
`NeoGoWebhookBridge` always maps `responseVars["error"]` to `500` — correct for a genuine crash,
misleading for "that id isn't a valid template". `SFAssignUserRoles` catches `OBException`
itself and folds it into a `result` (HTTP 200) body with `success: false`; only an unexpected
`RuntimeException` reaches the bridge's `error`/`500` path. Callers must branch on the body's
`success` flag, not the HTTP status.

**ETP-4878 — the four templates now carry the real permission matrix, not the ETP-4852 smoke
test.** `EnsureSystemRoleTemplatesScript` (the `ModuleScript`) no longer inserts a fixed 2-window
pair per role; it now iterates the full matrix from `TemplateRoleWindowAccess`
(`src/com/etendoerp/go/roles/TemplateRoleWindowAccess.java`) — a plain data class holding the
Ventas/Compras/Financiero/Almacén columns from the ticket ("Admin" stays client-level, out of
scope). Final grant counts: **Sales 13, Purchasing 11, Finance 27, Inventory 13** (33 distinct
`AD_Window_ID`s, 64 role×window rows total). "Asientos manuales" resolves to **Simple G/L
Journal** (`B917E8A7B0864ACEA9D941E3B7494E53`), not the classic `G/L Journal` window (`132`,
which literally carries the matching ES label but has no Schema Forge spec) — a human call made
explicitly for this ticket on an otherwise genuinely ambiguous resolution. The script's
reconciliation is now two-sided: it inserts/corrects every grant the matrix calls for AND removes
(hard `DELETE`) any existing grant a role has for a window the matrix does NOT call for — so the
old smoke-test pairs are cleaned up on the next `update.database`, not just added to.
`TemplateRoleWindowAccess` is unit-tested directly (`TemplateRoleWindowAccessTest`, `src-test/`)
since it has zero DB/SQL dependencies — no Gradle classpath workaround needed, unlike the
`ModuleScript` itself which stays DB-only.

**ETP-4830 item #6.3 — process/report access, mechanical follow-up to the window matrix above.**
A real-DB audit found all four templates had ZERO `AD_Process_Access`/`obuiapp_process_access`
rows despite the 64 window grants — a composed user could OPEN a window (e.g. Sales Order) but
not click any of its action buttons (Process Order, Add Payment, Post, ...), since
`NeoAccessHelper#hasProcessAccess`/`#hasObuiappProcessAccess` gate those independently of window
access, and both are real, load-bearing checks (button actions, process execution, menu
filtering, MCP tool access all go through them). `EnsureSystemRoleTemplatesScript
#reconcileProcessAccess` closes this: for every window a role has FULL (not read-only) access to,
it grants every classic `AD_Process`/OBUIAPP process reachable as a BUTTON on that window (an
`AD_Column` with `AD_Process_ID`/`EM_OBUIAPP_Process_ID` set, on an active `AD_Field`) — queried
LIVE against the DB every run, not a hardcoded literal list like the window matrix, so it
self-heals if a button is later added to or removed from one of these windows. Read-only window
grants contribute no process access (these buttons are all mutating actions — unlocking them from
a read-only grant would contradict what "read-only" means).

**Deliberately mechanical, not a hand-curated ETP-4878-style matrix — human-approved scope
reduction.** A real audit found ~270 individual report/process items exist system-wide across the
4 roles; curating each one by hand (the way the 64-window matrix was designed) was explicitly
ruled out as its own follow-up ticket, too large for a single pass. This mechanical rule closes
the button-linked subset (~180 of those ~270 — confirmed via the same audit: 87 classic + 93
OBUIAPP button-linked processes across the 33 already-granted windows) with zero new per-item
judgment calls. **STANDALONE reports/processes not tied to any window button (~90 items — 52
report-type + 22 process-type + 18 OBUIAPP-process menu entries at the system client level)
remain a known, separate, NOT-yet-closed gap** — out of scope for this pass.

**Propagation onto composed personal roles needed zero new code.** Core's
`RoleInheritanceManager` already propagates `AD_Process_Access`/`obuiapp_process_access` the exact
same generic way it propagates `AD_Window_Access` — confirmed by inspecting its registered
`AccessTypeInjector` implementations (`ReportAndProcessAccessInjector`,
`ProcessDefinitionAccessInjector`, alongside `WindowAccessInjector`/`FormAccessInjector`/
`TabAccessInjector`/`FieldAccessInjector`/`OrgAccessInjector`/others). Seeding the TEMPLATE role's
own access (this script's only job) is sufficient — `UserRoleCompositionService` needed no
changes at all for personal roles to inherit these new grants, the same way they already inherit
window access.

**Cross-template `AD_Window_Access` overlap — self-contained fix for a latent core bug (found via
ETP-4878's overlapping matrix, QA/Sentinel; fixed here, not in core, per an explicit human
decision).** Composing a personal role from 2+ templates that grant the SAME window used to throw
`OBSecurityException` and roll back the whole call — reachable the moment any two requested
templates share a window, regardless of whether they agree on access level. Root cause traced
into `org.openbravo.role.inheritance`: `WindowAccessInjector` never overrides `AccessTypeInjector
#getSkippedProperties()` (the base default only skips `creationDate`/`createdBy`), so when a
SECOND template's inheritance propagates to a window a FIRST template already covered,
`RoleInheritanceManager#handleAccess` takes the UPDATE path (`updateRoleAccess` → `DalUtil.
copyToTarget`), which overwrites the personal (tenant-client) role's existing row with the
template's OWN `client`/`organization` (system client `"0"`) — the very next flush then fails
`SecurityChecker.checkWriteAccess` under the tenant-scoped `OBContext`. The CREATE path
(`copyRoleAccess`) does not hit this: `OBContext.setAdminMode(boolean)`'s bypass around it is
still active when `Session.save()`'s interceptor callback fires (new-entity saves are checked
immediately), while the UPDATE path's dirty-check-driven callback fires later, at the caller's own
flush, by which point that inner bypass has already been restored.

`UserRoleCompositionService.reconcileInheritances` now does two things beyond core's own
mechanism, both scoped to `WindowAccess` only: (1) right before a new template's `AD_Role_
Inheritance` is saved, it removes the personal role's existing active `WindowAccess` row for
every window that template also grants — forcing core's propagation through the safe CREATE path
for every one of that template's windows, overlapping or not (this also has to null the row's
`InheritedFrom` field before removing it, mirroring core's own `deleteRoleAccess`, since core's
`InheritedAccessEnabledEventHandler` otherwise rejects deleting any row that still has one set);
(2) once the whole add/remove loop finishes, a final pass pins `client`/`organization` on every
inherited row back to the personal role's own values (belt-and-braces, defending the CREATE path
too even though it has not been observed to corrupt it) and resolves the ticket-required
most-permissive-wins union — a window ends up full (`✓`) access if ANY currently-applied template
grants it full, read-only (`R`) only if ALL of them do. Both steps use the same narrow,
method-scoped `OBContext.setAdminMode(false)` bypass core's own `copyRoleAccess`/`updateRoleAccess`/
`deleteRoleAccess` use for exactly this kind of cross-client write — never the outer, method-wide
bypass, which stays at `setAdminMode(true)` so `enforceCallerClientBoundary`'s tenant-boundary
guarantee (above) stays intact for the rest of this class. See
`UserRoleCompositionServiceOverlapIntegrationTest` and
`UserRoleCompositionServiceOverlapReverificationTest` (§9) for the real-DB proof (conflicting
access levels resolve to full regardless of which template is added second, a real union of both
templates' non-shared windows, a no-op re-run stays correct, and — independently re-verified
against the real ETP-4878 matrix rather than a synthetic window — the fix holds for 3
simultaneously-overlapping templates, not just 2).

**System-wide guard beyond the personal role: `WindowAccessOverlapCorruptionGuard` (Task B6,
ETP-4906).** Everything above (`reconcileInheritances`) only ever protects the ONE
`personalRole` a given `assignTemplateRoles` call is actively composing. Core's own propagation
is not scoped that narrowly — `RoleInheritanceManager#propagateNewAccess`/`applyNewInheritance`/
`applyRemoveInheritance` iterate EVERY role that inherits from whichever template was touched,
reachable from ANY entry point (a raw Etendo Classic edit to a template's own Window Access tab,
another role gaining/losing an inheritance — zero `UserRoleCompositionService` code in the call
stack, live-reproduced). Any such BYSTANDER role hits the exact same ownership-corrupting
`OBSecurityException` this section describes, with no protection from the two role-scoped helpers
above. `com.etendoerp.go.roles.WindowAccessOverlapCorruptionGuard` — a plain module-level
`EntityPersistenceEventObserver` (no core patch), the same extension point `ContactNameSyncHandler`
already uses — closes that system-wide gap by watching `AD_Window_Access` and `AD_Role_Inheritance`
persistence events directly, independent of which module or UI action triggered them. `@Priority`
on its `onSave`/`onUpdate`/`onDelete` observer methods (CDI 2.0 §10.4.2, Weld 3.1) guarantees it
runs BEFORE core's own unprioritized propagation handlers (`InheritedAccessEnabledEventHandler`,
`RoleInheritanceEventHandler`) for the same event — the only way to win that race, since core never
forwards a `preFlush`/`postFlush` hook to CDI observers.

It has grown, round by round (B6 rounds 1-8, ETP-4906), into **seven guarded triggers**, all
variations on the same root cause (`WindowAccessInjector` not skipping `client`/`organization` in
`getSkippedProperties()`, so core's `updateRoleAccess` blindly copies the template's own ownership
fields onto an already-existing inherited row) plus one correctness-only refinement (BUG-2) layered
onto trigger 7. This section summarizes the current mechanism; the exhaustive empirically-found
failure mode for each — including the two rejected intermediate designs that turned out wrong on
live re-verification — lives in the class's own javadoc, which is the source of truth this section
is kept in sync with:

1. **A template gains a new/updated `AD_Window_Access` grant (`onSave`/`onUpdate`, `guardDependentsOf`).**
   For every OTHER role actively inheriting from that template, if it already has its own active
   row for the same window not already sourced from that same template, the row is deleted before
   core propagates — forcing core onto the safe CREATE path instead of the corrupting UPDATE path
   (mirrors `preventWindowAccessOverlapCorruption`'s own mechanism, generalized to every dependent
   role).
2. **A role gains a brand-new `AD_Role_Inheritance` from an already-overlapping template**
   (`guardNewInheritance`, e.g. a raw Classic "add inheritance" edit, not `assignTemplateRoles`) —
   same delete-before-write logic, scoped to the one role gaining the one new inheritance.
3. **A role loses an existing `AD_Role_Inheritance` (`guardRemovedInheritance`).** Core's
   `applyRemoveInheritance` re-derives the dependent's access against every REMAINING template it
   still inherits from via the same corrupting `updateRoleAccess` path. For every remaining
   template, for every window it grants, if the dependent's existing row isn't already sourced from
   that exact template, it is deleted first, forcing the safe CREATE path again. A row whose window
   is granted by NO remaining template is left alone — core's own non-corrupting delete handles that
   case already.
4. **Most-permissive-wins enforcement on widen (`widenInheritedAccessLevelIfNeeded`).** None of the
   triggers above decide the access LEVEL the CREATE path should use when 2+ actively-inherited
   templates grant the same window — outside `assignTemplateRoles`, core's propagation can leave a
   role read-only even though another active template grants it full. This runs on the same
   `EntityNewEvent` as triggers 1-3's CREATE-path row: if any of the role's OTHER active template
   inheritances grants the same window at a more permissive level, it widens the fresh row to full
   (one-directional — never narrows).
5. **`InheritedFrom` bookkeeping on widen, same method.** The fourth trigger's first implementation
   widened `editableField` but left `InheritedFrom` pointing at the (less-permissive) template the
   CREATE path originally sourced the row from, which broke re-derivation on a later removal of the
   ACTUAL justifying template. `widenInheritedAccessLevelIfNeeded` now also repoints `InheritedFrom`
   to the template that justifies the widened value (`findActiveTemplateGrantingFullAccess`, tie
   broken by highest `AD_Role_Inheritance.SeqNo`, mirroring core's own `propagateDeletedAccess`
   heuristic), only in the branch that actually widens. A same-flush race this exposed (Hibernate
   runs entity Deletions after Insertions in its default action-queue order, so a just-removed
   template's `RoleInheritance` row can still look `active=true` mid-flush) is closed via a
   per-transaction `TEMPLATES_BEING_REMOVED` thread-local marker, cleared by a
   `TransactionCompletedEvent` observer.
6. **REMOVE-path duplicate-INSERT crash with 3+ overlapping templates (`guardRemovedInheritance`,
   `repointInPlace`/`collectWindowGrantors`/`repointWindowIfNeeded`).** Live-confirmed on the real
   `SFAssignUserRoles` flow with a role composed from all 4 system templates: the old
   delete-once-per-remaining-template loop could delete the SAME dependent row more than once across
   core's own multi-pass `calculateAccesses` walk (no flush between passes, `FlushMode.COMMIT`),
   so two remaining templates both scheduled a CREATE for the identical `(AD_Role_ID, AD_Window_ID)`
   pair — a real `ad_window_access_un_key` constraint violation, not a corner case. Fixed by
   computing, ONCE per window across ALL remaining templates together, the single winning template
   (highest `SeqNo` grantor — core's own `isPrecedent` compares only `SeqNo` order, not access
   level; an earlier "most-permissive template wins ownership" attempt was tried and reproduced the
   identical crash one hop later, for exactly this reason) and the most-permissive access level
   across every remaining grantor, then correcting the existing row IN PLACE via a bulk HQL UPDATE
   (`repointInPlace`) — never a delete+recreate. REMOVE-side only; the ADD-side triggers are
   structurally incapable of this multi-pass race (core's ADD path only ever iterates exactly one
   template per event).
7. **Core cannot always SEE a pre-existing, already-correct row before it propagates
   (`clearConflictingAccessUnconditionally` / `repointIfAlreadySourcedFromTemplate`,
   `PropagationTrigger`).** Triggers 1-2 originally skipped deleting a dependent's row when it was
   already sourced from the propagating template, trusting core's own `isPrecedent` to leave it
   alone — wrong whenever the dependent's client falls outside the CALLING context's own
   readable-clients list, which makes core's own row lookup (`AccessTypeInjector#findAccess`) blind
   to it, so core always takes the CREATE branch regardless and risks a duplicate INSERT. Fixed by
   deleting unconditionally. That fix in turn assumed both callers of `guardDependentsOf` feed a
   core propagation path with a CREATE fallback — true for `onSave` (`propagateNewAccess`) but FALSE
   for `onUpdate` (`propagateUpdatedAccess`, which only ever UPDATEs a row it can find and otherwise
   does nothing), so unconditional deletion on the `onUpdate` path could permanently lose a
   dependent's access with nothing left to restore it. `guardDependentsOf` now takes a
   `PropagationTrigger` (`NEW_GRANT` vs. `UPDATED_GRANT`) so the two callers get different
   treatment: `NEW_GRANT` keeps the unconditional delete (safe — `propagateNewAccess` always
   recreates); `UPDATED_GRANT` instead corrects an already-template-sourced row IN PLACE
   (`repointIfAlreadySourcedFromTemplate`, the same bulk-HQL-UPDATE technique as trigger 6) rather
   than deleting it, so the dependent's row survives regardless of what core's own propagation does
   afterward.

**[BUG-2] Most-permissive-wins gap in trigger 7's `onUpdate` branch (found by QA's Final Coverage
Pass, 2026-08-18).** `repointIfAlreadySourcedFromTemplate` only ever compared the dependent's row
against the ONE template whose own access just changed, copying that template's new value directly
— unlike triggers 4-6, it never surveyed the dependent's OTHER actively-inherited templates first.
Live-reproduced: a dependent inheriting full access from two overlapping templates A and B;
downgrading B's own access via a routine Classic edit dragged the dependent down to read-only too,
even though A still actively granted full. Fixed by having `repointIfAlreadySourcedFromTemplate`
survey every OTHER actively-inherited template (`findActiveTemplateGrantingFullAccess`, 3-arg
`excludedTemplate` overload) before writing, resolving the final level as the MAX across the edited
template's new value and every other active grantor, and repointing `InheritedFrom` to whichever
template actually justifies that value — reusing the same survey pattern triggers 6/4-5 already
established, not new divergent logic.

Deliberately narrow in scope: only reacts when the touched `AD_Window_Access` row's owning role is
itself a template (the same signal core's own handler uses to decide whether to propagate at all),
and never touches the template's own row or any grant LEVEL outside triggers 4-7/BUG-2's own
widen/repoint logic. `reconcileWindowAccessAfterComposition` (above) remains the most-permissive-wins
union authority for the role it actively composes; this class's job is making sure core never gets
the chance to corrupt or silently under-resolve a BYSTANDER role's access for every entry point that
service doesn't see. Full design rationale, each empirically-discovered failure mode (including the
two intermediate designs that were tried and reproduced their own crash before landing on the
current one), and the live reproduction steps for all seven triggers plus BUG-2 are documented in
the class's own javadoc (`src/com/etendoerp/go/roles/WindowAccessOverlapCorruptionGuard.java`) and
in ETP-4906's plan doc ("B6 Findings" sections, `etendo_schema_forge` repo) — treat the javadoc as
canonical for current-state mechanics; the plan doc's per-round sections are historical trail.
`WindowAccessOverlapCorruptionGuard` has no dedicated unit-test file of its own — it is exercised
entirely through `UserRoleCompositionServiceOverlapIntegrationTest` (all 7 triggers plus BUG-2 and
its 2 coverage-gap follow-ups, real DB, `WeldBaseTest`), `UserRoleCompositionServiceOverlapReverificationTest`
(an independent re-verification of the same overlap fix, deliberately not reusing the fix author's
own test), and `UserRoleCompositionServiceRealAccessControlIntegrationTest` (real seed-data
access-control scenarios) — see §9.

**Same protection extended to `AD_Process_Access`/`OBUIAPP_Process_Access` (ETP-4830 item 7).**
`ProcessAccessOverlapCorruptionGuard` and `ObuiappProcessAccessOverlapCorruptionGuard` are sibling
`EntityPersistenceEventObserver`s that mirror this same ADD/UPDATE/REMOVE-path trigger set for
`AD_Process_Access` and `OBUIAPP_Process_Access` respectively — a role's active processes/reports
are exposed to the identical cross-template ownership-corruption risk that windows are. The
entity-agnostic pieces (the "which active templates does a role inherit from" query, the
SeqNo-descending winner/most-permissive-wins reconciliation, the same-flush
`TEMPLATES_BEING_REMOVED` marker, and a few pure `Role`/`RoleInheritance` helpers) live in the
shared `com.etendoerp.go.roles.overlap` package (`ActiveTemplateInheritance`,
`OverlapReconciliationCore`, `TemplateRemovalTracker`) instead of being re-derived per guard.
`OBUIAPP_Process_Access` carries no unique constraint (unlike `AD_Process_Access`/
`AD_Window_Access`), so its own residual gap manifests as a silent duplicate row rather than a
loud `ConstraintViolationException` — see `ObuiappProcessAccessOverlapCorruptionGuard`'s own
class/method javadoc for the full detail.

**Twelve matrix rows are a documented, deliberate gap — not yet implementable.** Every one of
them has NO `AD_Window_ID` at all backing it in this environment (either a pure custom/aggregate
Schema Forge page with zero classic-AD entity, or a report-type spec whose access resolves via a
different, non-window mechanism), so `AD_Window_Access` cannot express a grant for it at all:
**Inicio (Dashboard)**, **Favoritos**, **Copilot (Asistente IA)**, **Informes de inventario**,
**Documentos no contabilizados**, **Monitor fiscal**, **Modelos fiscales**, **Informes
financieros**, **Informe Antigüedad de Cobros**, **Informe Antigüedad de Pagos**, **Escaneo
inteligente**, **Configuración fiscal**. Full per-row resolution detail (which spec/artifact was
checked, why it has no window) lives in `EnsureSystemRoleTemplatesScript`'s own class javadoc.
Closing this gap needs either building the missing AD entity/spec first, or a different grant
mechanism entirely — left for a follow-up ticket. Separately, "Roles", "Usuario", and "Conectar
asistente de IA" DO resolve to real `AD_Window_ID`s but are deliberately granted to none of the
four templates — the matrix shows "—" for all four non-Admin roles on all three, so they stay
Admin-only.

**Still open (ETP-4877, unchanged by ETP-4878):** the ~21 existing tenants still holding
per-client duplicated role copies are untouched by this mechanism (a migration, not a runtime
fallback).

---

## 8e. Read User Role Assignments (SFUserRoleAssignments Webhook, ETP-4906)

`SFUserRoleAssignments` (`GET /sws/neo/userroleassignments[?UserId=<id>]` — reached ONLY through
the NEO pseudo-spec bridge, §4.10/§4.11; no legacy `/webhooks/*` path, same as `SFAssignUserRoles`)
is the read-path companion §8d's write endpoint needed: "which template roles does user X (or
every user of my client) currently have applied". It backs the multi-role assignment UI's initial
chip state (form load) and the Users grid's role-chips column, and is the one genuinely new
backend surface ETP-4906 introduced — everything else in that ticket reuses `SFAssignUserRoles`/
`SFRolesOverview`/`SFListMenu` as-is. All resolution logic lives in
`UserRoleCompositionService#getAppliedTemplateRoleIds(String, Role)` (single user) and
`UserRoleCompositionService#getAppliedTemplateRoleIdsForClient(String)` (bulk); this webhook is
only a parameter-marshalling + access-gating shim, exactly like §8d's.

**Two modes, selected by whether `UserId` is present:**

| Mode | Request | Use case |
|------|---------|----------|
| Bulk | `GET /sws/neo/userroleassignments` (no `UserId`) | Users grid — every user of the caller's OWN client in one call |
| Single | `GET /sws/neo/userroleassignments?UserId=<id>` | User form load — one user's applied template ids |

**Response shape:**

```json
// Bulk mode:
{"assignments": {"9B8D...": ["tpl-finance", "tpl-sales"], "A1C2...": []}}
// Single mode:
{"userId": "9B8D...", "templateRoleIds": ["tpl-finance", "tpl-sales"]}
```

**Access gate:** admin/client-admin only (`NeoAccessHelper.isAdminOrClientAdmin`), captured
before any lookup — same convention as `SFAssignUserRoles`/`SFRolesOverview`. No role, or a
restricted role, gets this webhook's own empty-result shape, shaped per the mode that was
requested (`{"assignments": {}}` for bulk, `{"userId": "...", "templateRoleIds": []}` for
single) — never a raw `403`, this webhook family's "deny silently" convention.

**Tenant boundary of the TARGET user — single mode only.** The access gate above only answers
"may this caller use this webhook at all" — it does not limit WHICH `userId` a client-admin may
read, for the exact same reason §8d's write path needs its own boundary check
(`isAdminOrClientAdmin` treats a per-tenant client-admin the same as the literal System
Administrator). `SFUserRoleAssignments.get()` forwards its already-resolved `currentRole` into
`UserRoleCompositionService#getAppliedTemplateRoleIds(String, Role)`, which runs the SAME
`enforceCallerClientBoundary(User, Role)` check §8d's `assignTemplateRoles(String, List, Role)`
uses — see that method's javadoc. A cross-tenant read attempt therefore rejects exactly like a
cross-tenant write attempt does, just folded into the empty single-mode result instead of a
`success:false` body (this endpoint has no `success` field at all — every expected rejection,
including "unknown user id", degrades to the same empty shape, so a caller can never distinguish
"exists in another tenant" from "doesn't exist" from "not authorized").

**Bulk mode needs no target-user boundary check at all** — it is always scoped to
`currentRole.getClient().getId()`, never a caller-supplied client id (no such parameter is
exposed), mirroring `SFRolesOverview`'s identical "always the caller's own client" convention.
`UserRoleCompositionService#getAppliedTemplateRoleIdsForClient(String)` resolves every user of
that client in one query pass — deliberately NOT a loop calling the single-user method once per
user (§8d's mechanism involves several queries per personal-role resolution; doing that once per
grid row would not scale) — see that method's own javadoc for the bulk-equivalent identity checks
it applies (mirroring `isReusablePersonalRole`'s active/non-template/non-admin/same-client/
not-an-inheritance-target/exclusively-assigned rules, re-expressed as `Restrictions.in(...)` batch
queries instead of one query per candidate role).

**Read-only — never mints a personal role.** Unlike `assignTemplateRoles`, neither
`getAppliedTemplateRoleIds` nor `getAppliedTemplateRoleIdsForClient` ever calls
`createPersonalRole`: a user with no personal role yet (never composed, or a brand-new user)
simply has zero applied templates — an empty list/array, not a role created as a side effect of
a GET.

**Deliberately does NOT use the bridge's `error`/HTTP 500 path for expected rejections**, for the
same reason §8d's write endpoint doesn't: `NeoGoWebhookBridge` always maps
`responseVars["error"]` to `500` — correct for a genuine crash, misleading (and a
same-tenant-vs-other-tenant information leak) for "that id doesn't resolve for you". This class
catches `OBException` and folds it into its own empty-result shape; only an unexpected
`RuntimeException` reaches the bridge's `error`/`500` path.

---

## 8f. System-Level Role Templates (SFSystemRoleTemplates Webhook, ETP-4906)

`SFSystemRoleTemplates` (`GET /sws/neo/systemroletemplates` — reached ONLY through the NEO
pseudo-spec bridge, §4.10/§4.11; no legacy `/webhooks/*` path, same as `SFAssignUserRoles`/
`SFUserRoleAssignments`) returns the 4 fixed role templates (Finance/Sales/Purchasing/Inventory,
`com.etendoerp.go.roles.SystemRoleTemplates`) — resolved at the SYSTEM client
(`AD_Client_ID = '0'`), never the caller's own tenant. It backs the "which template roles can I
compose from" question for the multi-role assignment UI (`AssignTemplateRolesControl.jsx`,
`UserRolesTab.jsx`, `RoleChipsCell.jsx`, `RoleFilterControl.jsx` in `etendo_schema_forge`).

**Why not `SFRolesOverview` (§8c)?** That webhook is hard-scoped to the CALLING tenant's own
client by design — it resolves the 4 fixed role NAMES plus the client-admin role WITHIN
`currentRole.getClient()`, for its own ETP-4513 "Configuración > Roles" page. This is correct for
that page, but breaks down the moment a tenant has no per-client copies of these roles left to
find — which is now the target end state ETP-4906 itself needs: "no template role should be at
client level, only at system level" (Manual QA Feedback Round 2, finding 2, `docs/plans/2026-08-
14-etp-4906-multi-role-user-assignment.md`). Repointing `SFRolesOverview` itself would have broken
its own unrelated page instead of fixing this — hence a new, separate webhook rather than a change
to an existing one.

**Response shape** — deliberately a subset of `SFRolesOverview`'s per-role shape (§8c), omitting
what only makes sense for a tenant-scoped aggregate:

```json
{"roles": [
  {"id": "B88A34B5D1874F8685FA6F3C3A609412", "name": "Finance",
   "windows": [{"id": "...", "name": "...", "tier": "full"}]},
  {"id": "15ECC46CFBD74CF3A76D1F4DC8BA9F80", "name": "Sales", "windows": [...]},
  {"id": "5E279F5102F9410F9B8CCBA424741F46", "name": "Purchasing", "windows": [...]},
  {"id": "73581A7B4F414A2C9059C83CE7BE97BF", "name": "Inventory", "windows": [...]}
]}
```

No `userCount` — not because none can be computed (ETP-4907's `SFRolesOverview` system-template
fallback, §8c, does compute one, via `UserRoleCompositionService#getAppliedTemplateRoleIdsForClient`
counting how many of a CLIENT's users currently compose from a given template), but because THIS
endpoint's own consumer — "which templates can I compose from" — has no use for a per-tenant
aggregate; that is `SFRolesOverview`'s job, not this one's. And no client-admin row
(`SystemRoleTemplates`'s own class javadoc explicitly excludes the client-level "Admin" role from
the template set — there is nothing to represent at system level).

**Access gate:** admin/client-admin only (`NeoAccessHelper.isAdminOrClientAdmin`), captured before
any lookup — same convention as every sibling webhook in this family. No role, or a restricted
role, gets `{"roles": []}`, never a raw `403` — this family's "deny silently" convention.

**Resolution:** the 4 fixed ids from `SystemRoleTemplates.byName()` are looked up directly via
`OBDal.get(Role.class, id)` — never a client-scoped `Role` criteria keyed off the caller's own
client, the way `SFRolesOverview` resolves its roles. A template id that no longer resolves to an
active `Role` (deleted or deactivated — not expected, but not this webhook's job to prevent) is
skipped rather than surfaced as an error. Each role's `windows` array follows the exact same
GO-window intersection + tier resolution as `SFRolesOverview` (`AD_Window_Access`, intersected
with the windows Etendo GO actually exposes, tier `full`/`read-only` from `IsReadWrite`) — with
client/organization filtering explicitly disabled on that query, since these roles live at the
system client and a non-system caller's ambient readable-client set would otherwise filter their
`AD_Window_Access` rows out entirely.

---

## 8g. Debug Invitation Bypass (SFDebugInvitationBypass Webhook, ETP-4830)

`SFDebugInvitationBypass` (`GET /sws/neo/debuginvitationbypass?Action=<forceAccept|forceStatus>&...`
— reached ONLY through the NEO pseudo-spec bridge, §4.10/§4.11; no legacy `/webhooks/*` path) is a
**dev/QA-only** endpoint that lets a tester exercise the invite-email flow (ETP-4830 —
`CompanyInvitationService#createInvitationForNewlyCreatedUser`, admin-created-user invitations)
and the frontend's `PendingInvitationPill` states without a real email round-trip: creating and
verifying an inbox for every `invitationStatus` value in manual QA does not scale.

**GATED OFF BY DEFAULT — this is the security-critical property of this endpoint.**
`NeoPseudoSpecDispatcher#dispatchDebugInvitationBypass` checks
`GoRuntimeProperties.readBoolean("etendo.go.debug.invitationBypass",
"ETGO_DEBUG_INVITATION_BYPASS", false)` **before `SFDebugInvitationBypass` is even
constructed.** An environment that has not explicitly opted in (its own local
`Openbravo.properties`/env var — never a value baked into a shared config file committed to this
repo) gets a plain `404`, not a `403` and never a `200` — the endpoint behaves as if it did not
exist at all, with zero DB access and zero writes. This is a stronger guarantee than gating inside
the webhook's own `get()` would give: the webhook object, and everything it could reach, is never
constructed when the flag is off. The webhook's own admin/client-admin check
(`NeoAccessHelper.isAdminOrClientAdmin`, same convention as every sibling in this family) is
defense-in-depth only, not a substitute — a valid NEO bearer token with an admin role is reachable
in production too, which is exactly why a role check alone was rejected as insufficient for this
endpoint.

**Two actions, both reusing the real account-provisioning primitives rather than duplicating
them** (`DebugInvitationBypassService`, `com.etendoerp.go.rest` package — placed there
specifically so it can call `EtendoGoJwtDalHelper`/`CompanyInvitationDalHelper` directly, both
package-private by design to keep account-provisioning DAL calls inside that package;
`SFDebugInvitationBypass` itself, in `com.etendoerp.go.schemaforge.webhooks`, is only a thin
parameter-marshalling + defense-in-depth-access-gating shim — the same shim/service split
`SFAssignUserRoles`/`UserRoleCompositionService` already use, §8d):

- **`forceAccept`** (`Email` or `AdUserId`, optional `Name`) — finds-or-creates an active
  `etgo_account` for that email, reusing `EtendoGoJwtDalHelper#createAccount` (the exact same
  primitive `CompanyInvitationService#registerAndAcceptInAdminMode` calls) rather than a second,
  divergent account-creation code path. A newly created account gets a random policy-compliant
  temporary password, returned in the response (`temporaryPassword`) — there is no email to
  deliver it through, by design. If a matching `ETGO_INVITATION` row exists for that email (any
  status, most recent one — this is a dev tool, not scoped to "the caller's own tenant only"), it
  is flipped to `ACCEPTED` and linked to the account. Skips the token/email round-trip entirely:
  unlike the real `register-and-accept` flow, no `ETGO_INVITATION` row needs to exist first.
- **`forceStatus`** (`InvitationId`, or `Email` to resolve the most recently created invitation for
  that address) — directly sets `ETGO_INVITATION.STATUS` to any value from its enum
  (`PENDING`/`SENT`/`ACCEPTED`/`EXPIRED`/`REVOKED`/`DELIVERY_FAILED`), for visually exercising
  every `PendingInvitationPill` state in `etendo_schema_forge` without waiting on real delivery.

```json
// forceAccept success:
{"success": true, "email": "...", "accountId": "...", "accountCreated": true,
 "temporaryPassword": "...", "invitationId": "...", "invitationStatus": "ACCEPTED"}
// forceStatus success:
{"success": true, "invitationId": "...", "email": "...", "status": "SENT"}
// Validation failure or access denied (still HTTP 200, matching SFAssignUserRoles's own
// "don't 500 a validation rejection" convention, §8d):
{"success": false, "message": "..."}
```

**Frontend counterpart:** `useUserDebugMode.js`/`UserDebugPanel.jsx` in `etendo_schema_forge`
(activated by typing `debuguser` anywhere in the app, mirroring `fiscal-monitor`'s own
`debugfiscal`/`useDebugMode.js` convention as a fully independent module) — see that repo's
`docs/generated-custom-windows/user.md` "Debug mode" section. The keydown listener there only
registers in a dev build (`import.meta.env.DEV`), but that is a discoverability nicety on the UI
side only; this endpoint's own flag, described above, is the actual security boundary regardless
of which frontend build happens to be pointed at this backend.

---

## 8h. Resend Invitation (SFResendInvitation Webhook, ETP-4830)

`SFResendInvitation` (`GET /sws/neo/resendinvitation?AdUserId=<id>` — reached ONLY through the NEO
pseudo-spec bridge, §4.10/§4.11; no legacy `/webhooks/*` path) is the admin-side counterpart to the
create-time invite flow (§8a-equivalent — `CompanyInvitationService#createInvitationForNewlyCreatedUser`):
lets an admin re-trigger an invitation for an existing `AD_User` from the `user` window's detail-header
"Reenviar invitación" button, next to `PendingInvitationPill` (`etendo_schema_forge`'s
`windows/custom/user/index.jsx`).

**Unlike §8g's debug bypass, this is a real, always-on production feature — no dev-only
`GoRuntimeProperties` flag.** The access boundary is `SFResendInvitation`'s own
`NeoAccessHelper.isAdminOrClientAdmin` check (same convention as every sibling in this family) plus
`CompanyInvitationService#resendInvitation` itself re-validating that the target `AD_User` belongs to the
caller's own client — a client-admin cannot resend an invitation for another tenant's user by id.

**Eligibility gate** — matches the frontend button's own visibility check exactly (the button is a UX
nicety only; this server-side gate is the real boundary): the target user's latest invitation (via
`CompanyInvitationService#effectiveStatus` — computed live from `expiresAt` at read time, so a
`PENDING`/`SENT` row past its deadline reads as `EXPIRED` without any stored-column write or batch
sweep job; see `etendo_schema_forge`'s `docs/generated-custom-windows/user.md` "Invite-on-create
flow" section for the resulting pill states) must
read `PENDING`, `SENT`, `EXPIRED`, or `DELIVERY_FAILED`. A `REVOKED` invitation is rejected
(`INVITATION_NOT_RESENDABLE`) rather than silently resurrected — an admin who revoked an invite on purpose
should not have that undone by a stale button click; `ACCEPTED` is rejected too (nothing left to resend); a
user with no invitation history at all is rejected (`NO_INVITATION_TO_RESEND`).

**Revoke-then-reissue, not resend-the-same-link.** If the current invitation is still open (`PENDING`/`SENT`
AND `expiresAt` has not passed), it is flipped to `REVOKED` before a brand-new token/row is minted — so the
OLD invite link stops working the instant the new one is issued. There is never a window where two links
for the same invitation are simultaneously valid. An already-`EXPIRED`/`DELIVERY_FAILED` invitation has
nothing open to revoke, so this step is a no-op for those two source states — only the mint-and-send step
runs.

**Shares its mint-and-send mechanics with `createInvitation` itself**, not a second, divergent
implementation: `CompanyInvitationService#createInvitationForInviter`'s own token-generation/persist/send
tail was extracted into a private `issueFreshInvitation(...)` helper, called both by the normal
dedup-then-mint create path and by `resendInvitation` directly (bypassing the dedup check on purpose — a
resend is an explicit admin action, not a repeat create request that should silently no-op).

```json
// success:
{"status": "success", "invitation": {"id": "...", "email": "...", "status": "SENT", "expiresAt": "..."}}
// validation failure (still HTTP 200, matching SFAssignUserRoles's own
// "don't 500 a validation rejection" convention, §8d):
{"error": true, "code": "INVITATION_NOT_RESENDABLE", "message": "...", "httpStatus": 400}
```

**Frontend counterpart:** `resendInvitationApi.js` (`etendo_schema_forge`, `lib/`) — thin
`fetchNeoWebhookJson` client, same shared mechanics as `debugInvitationBypassApi.js` (§8g) but detects the
domain-error via this webhook's own `error: true` key (not `success: false` — this response reuses
`CompanyInvitationService`'s pre-existing `errorResponse`/`invitationResponse` builders rather than
inventing a new shape for this one webhook). See that repo's `docs/generated-custom-windows/user.md`
"Resend invitation" section.

---

## 8i. Promote/Demote User Role (SFPromoteUserRole Webhook, ETP-5019)

`SFPromoteUserRole` (`GET /sws/neo/promoteuserrole?UserId=<id>&Mode=promote|demote` — reached ONLY
through the NEO pseudo-spec bridge, §4.10/§4.11; no legacy `/webhooks/*` path) backs the admin
"Promote to Admin" / "Demote from Admin" actions on the `user` window's detail-header: lets the
owner or a current Admin flip an invited user between their composed personal role
(§8d/`UserRoleCompositionService#assignTemplateRoles`) and the client's Admin role, and back again,
without losing that personal role's template composition in the process.

**Same admin/client-admin access gate as every sibling in this family** — `SFPromoteUserRole`'s own
`NeoAccessHelper.isAdminOrClientAdmin` check, evaluated BEFORE `UserRoleCompositionService` is even
constructed, same convention as `SFAssignUserRoles`/`SFResendInvitation`. Like `resendinvitation`
(§8h), there is **no dev-only `GoRuntimeProperties` flag** — this is a real, always-on production
feature. The finer-grained rules are NOT enforced by the webhook itself; they live inside
`UserRoleCompositionService#promoteToAdmin`/`#demoteFromAdmin` (Tasks 1-2, ETP-5019):

- the CALLER must be the client's owner (`OwnerSupport.isOwner`) or already hold the client-admin
  role (`callerIsOwnerOrAdmin`) — a merely-composed non-admin user cannot promote/demote anyone,
  even themselves;
- `enforceCallerClientBoundary` — the same tenant-boundary check `SFAssignUserRoles`/
  `SFUserRoleAssignments` use — stops a client-admin from targeting a user outside their own client;
- the TARGET can never be the owner (`OwnerSupport.isOwner`) for either direction — the owner
  already effectively has Admin and can never be demoted by anyone;
- `promoteToAdmin` additionally rejects a target who already holds the client-admin role;
  `demoteFromAdmin` additionally rejects a target who does NOT currently hold it.

**Mode dispatch, not two endpoints.** `Mode=promote` calls `promoteToAdmin`; `Mode=demote` calls
`demoteFromAdmin`; any other (or missing) value is rejected before either service method — or
`UserRoleCompositionService` itself — is ever constructed, same "reject cheaply before touching the
service" convention `SFAssignUserRoles` uses for a missing `UserId`.

**Promote replaces, never deletes.** Promoting sets the target's `Default_Ad_Role_ID` to the
client's Admin role and syncs `AD_User_Roles` (`UserRoleSyncSupport#syncSingleActiveUserRole`) —
the personal role's own `AD_Role` row and its `AD_Role_Inheritance` composition are left completely
intact, only unassigned, so a later demote can find and restore it by name
(`findDormantPersonalRoleByName`, scoped to the user's client) rather than starting from an empty
role again. If no dormant personal role is found (e.g. the user never had one), demote falls back to
creating a fresh one, the same `createPersonalRole` path `resolveOrCreatePersonalRole` already uses.

```json
// success (personalRoleId reused as the field name for whichever role id is now active —
// the newly-assigned Admin role's id on promote, the restored/created personal role's id on
// demote — to avoid diverging from SFAssignUserRoles's existing response shape):
{"success": true, "userId": "...", "roleId": "..."}
// domain validation failure (still HTTP 200, matching SFAssignUserRoles's own
// "don't 500 a validation rejection" convention, §8d):
{"success": false, "message": "..."}
```

**Frontend counterpart:** Task 4 of this plan (`etendo_schema_forge`) — a thin client calling this
endpoint with the same `UserId`/`Mode` params, wired to the `user` window's detail-header actions.

---

## 9. Testing

The module includes unit tests that run without a backend:

| Test class | Lines | Coverage |
|------------|-------|----------|
| `NeoServletPathTest` | 216 | URL path parsing: valid paths, selectors, actions, edge cases (empty path, trailing slashes, extra segments). |
| `NeoContextTest` | 148 | Builder pattern, all fields, HTTP method values, mutable `previousResult`. |
| `NeoResponseTest` | -- | Static builders (`ok`, `created`, `noContent`, `error`), custom headers. |
| `NeoServletTabFilterTest` | -- | Parent-child HQL where clause generation. |
| `NeoPreviewFileServiceTest` | ~250 | Validation (invalid JSON, blank fields), GET miss/hit, POST INSERT/UPDATE paths, DELETE miss/hit. All without a live DB via `MockedStatic<OBDal>` + `MockedStatic<OBContext>`. |
| `SFListMenuTest` | -- | Tree building/pruning, flat search, role-based filtering (window/process/OBUIAPP-process nodes), no-role → empty menu, multi-level nesting, viewer-role identity fields (`viewerRoleId`/`viewerIsClientAdmin`) present when a role is resolved and absent when it isn't. |
| `SFWindowAccessMapTest` | -- | Role-based windowAccess resolution (full/read-only/absent), no-role → both maps empty, admin/client-admin bypass → full access to every active Etendo GO window + every capability true, `showAccountingFields` true/false/unset/missing-role, `isAdminOrClientAdmin` true on bypass / false for a restricted role. |
| `SFRolesOverviewTest` | -- | Admin/client-admin access gate (no role, restricted role, System Administrator, client-admin); tenant-relative role resolution via a client-scoped `Role` criteria (not hardcoded ids), admin-first-then-fixed-name sort order, a tenant with fewer than 5 matching roles; distinct-user-count aggregation; GO-window intersection (native-only windows excluded); tier resolution (full/read-only); exception handling. Two defense-in-depth regression cases confirm the gate is genuinely `isAdminOrClientAdmin`, not "is this one of the tenant's 5 fixed roles": a caller authenticated AS one of those roles (Finance) but not admin/client-admin is still denied (empty `roles`, zero `Role` lookups), and a role with zero active `AD_User_Roles` AND zero active `AD_Window_Access` rows degrades gracefully to `userCount: 0` + an empty `windows` array for all 5 roles rather than throwing or omitting the role. **ETP-4907 additions:** missing tenant roles fall back to the system-level templates with composition-based `userCount` (`UserRoleCompositionService` constructed lazily, once, via `mockConstruction`); an active tenant role is never overridden by its template counterpart, and the composition service is never even constructed when unneeded; the `matrix` covers every GO window (including one no role can reach, resolving to `"none"`) grouped by category, and a window with no resolvable category falls back to the `"Other"` bucket. QA (Sentinel) added 3 more targeting the fallback's early-return branch: a system-template role that doesn't resolve at all (`OBDal.get` returns `null`, e.g. deleted/never-seeded) is silently omitted rather than appearing as a 5th entry with null/empty fields; a system-template role that resolves but is `IsActive = 'N'` is treated identically (also omitted, not returned with stale data); and the full degradation case — every one of the 4 templates missing/inactive — still returns a valid minimal response (just the admin card, `roles.length() == 1`) without ever constructing `UserRoleCompositionService`, confirming the fallback's laziness holds even under total non-resolution, not only when every fixed name already has a tenant role. |
| `TemplateRoleWindowAccessTest` (ETP-4878) | -- | The real ETP-4878 permission matrix in `TemplateRoleWindowAccess` (`src/com/etendoerp/go/roles/`), DB-free (12 tests): exactly the 4 non-Admin template roles present, exact grant counts per role (Sales 13 / Purchasing 11 / Finance 27 / Inventory 13, 64 total), Asientos manuales resolves to Simple G/L Journal and never to the classic G/L Journal window (`132`), Sales has no grant for Pago, "Categoría del producto" is read-only for Sales/Purchasing but full for Finance/Inventory, no role repeats the same `AD_Window_ID` twice, `byRoleId()` returns a fresh mutable map per call. QA (Sentinel) added 3 more: the 64 grants resolve to exactly 33 DISTINCT `AD_Window_ID`s (not just a raw count that would stay 64 even under duplication); all 8 window/role pairs from the old ETP-4852 2-window smoke test survive unchanged (same full access) in the new matrix, confirming `EnsureSystemRoleTemplatesScript#removeStaleWindowAccess`'s delete path is never actually exercised by that specific migration; and at least one window (e.g. Contactos, Pedido de venta) is granted at genuinely conflicting access levels across 2+ roles — the data-level root cause behind the ETP-4852 cross-template overlap bug fixed in `UserRoleCompositionService` (see §8d and `UserRoleCompositionServiceOverlapIntegrationTest`). |
| `UserRoleCompositionServiceTest` | -- | **ETP-4830 items #6.1/#6.2 additions:** `createFreshPersonalRole` grants `AD_Role_OrgAccess` to both the user's real organization and the wildcard `'*'` (two distinct `RoleOrganization` saves, both scoped to the role's own client); skips the duplicate org-access row when the user's own organization already IS the wildcard; sets `Default_Ad_Client_ID`/`Default_Ad_Org_ID`/`Default_M_Warehouse_ID`/`EM_SMFSWS_Default_WS_Role_ID` on the user (warehouse resolved via a `Warehouse` criteria scoped to the user's org); and skips the org/warehouse defaults entirely (no crash) when the user has no organization at all. Pure-Mockito unit test covering `assignTemplateRoles`'s input-validation guard clauses — the slice that fails before any persistence side effect: blank user id, `null` template id list, unknown user, unknown/inactive template id, a role that is not a template, the client-admin "Admin" role rejected even if somehow marked as a template, requested-id dedup happening before the per-id validation loop (verified via a single `Role` lookup despite 3 whitespace-noisy repeats of the same id), and the two `enforceCallerClientBoundary` regression cases from REVIEW cycle 1: a caller whose client differs from the target user's is rejected with a "different client" message, while the literal System Administrator role id (`"0"`) bypasses the check and reaches the (unrelated) template-validation error instead. **ETP-4906 additions:** `getAppliedTemplateRoleIds`'s read path — blank/unknown user id rejected the same way, a user with no `Default_Ad_Role_ID` yet returns an empty list without ever calling `createPersonalRole`, a reusable personal role with 2 active `AD_Role_Inheritance` rows returns both `InheritFrom` ids in `Seqno` order, and the read path enforces the exact same `enforceCallerClientBoundary` regression pair (cross-client rejected, System Administrator bypasses) as the write path. **ETP-4830 owner-protection additions:** the 4-arg `assignTemplateRoles(String, List, Role, String)` overload rejects a non-owner `callerUserId` reassigning a `EM_ETGO_Is_Owner`-flagged user's roles (`OwnerSupport.isOwner` mocked statically); the owner reassigning their OWN roles reaches the (unrelated) template-validation error instead, proving `enforceOwnerProtection` did not block it; a target NOT flagged as owner is unaffected regardless of caller mismatch (baseline); and a `null` `callerUserId` (the 2-/3-arg overloads) skips the check entirely without ever calling `OwnerSupport` — deliberately left unmocked in that one test so a regression would surface as a loud NPE, not a silent behavior change. |
| `UserRoleCompositionServiceIntegrationTest` | 446 | Real-DB, end-to-end proof (6 tests) of the full add/reconcile/retract lifecycle: a system-level (`AD_Client_ID = '0'`) template's `AD_Window_Access` propagates onto a per-tenant personal role purely via core's own `RoleInheritanceEventHandler`/`RoleInheritanceManager` (no hand-rolled copy in this module); removing a template on a later call retracts what it had propagated; re-running with the identical template set is a no-op (0 added, 0 removed); an empty template list on a user's FIRST-EVER composition call still creates the personal role and syncs `AD_User_Roles`/`Default_Ad_Role_ID` rather than leaving the user role-less; three occurrences of the same valid template id in one request collapse into exactly one `AD_Role_Inheritance` row instead of one per occurrence; and a recompose call mixing one still-valid template with one bogus id is rejected wholesale without mutating the inheritance/access an earlier, unrelated successful call had already applied. Extends `WeldBaseTest`, NOT plain `OBBaseTest` — role-inheritance propagation is driven by a Hibernate interceptor firing a CDI event that only `WeldBaseTest`'s Arquillian-booted container wires to an observer; under plain `OBBaseTest` the propagation silently never fires, which is a test-harness gap, not a bug in the service. |
| `UserRoleCompositionServiceOverlapIntegrationTest` | 1181 | Real-DB proof (13 tests, `WeldBaseTest`) of the cross-template `AD_Window_Access` overlap fix AND `WindowAccessOverlapCorruptionGuard`, all 7 triggers plus BUG-2 (see §8d above): composing Finance (full) + Sales (read-only) on a shared window succeeds (no `OBSecurityException`) and resolves to full access, with `client`/`organization` on the shared row matching the personal role's own, and both templates' non-shared windows also present (a real union); the same conflicting grants requested in the OPPOSITE order still resolve to full; re-running the identical overlapping template set is a no-op; `getAppliedTemplateRoleIds` reflects a real overlapping composition. **Triggers 1-5 (B6 rounds 1-5):** a bystander role never passed to `assignTemplateRoles` (e.g. gaining 2 overlapping inheritances via a raw Classic edit) is also protected (triggers 1-2); removing one of two overlapping template inheritances from a composed role is protected on the REMOVE path (trigger 3); gaining a read-only template inheritance never downgrades an existing full grant from another active template (trigger 4); removing the template that justified a previously-widened access level correctly downgrades the row instead of staying stuck at full (trigger 5, `InheritedFrom` bookkeeping). **Triggers 6-7 (B6 rounds 6-7):** removing one of FOUR overlapping templates (2 remaining templates still overlapping on a window) no longer duplicate-INSERTs (trigger 6); updating a template's own access level in place never deletes an already-correctly-sourced dependent row (trigger 7, the `onUpdate`/`UPDATED_GRANT` path). **BUG-2 + coverage gaps (round 8):** downgrading one of two overlapping templates' own access never downgrades a dependent when the other still grants full (`testDowngradingOneOfTwoOverlappingTemplatesNeverDowngradesDependentWhenTheOtherStillGrantsFullAccess`); a single inheritance event touching 3 windows at once resolves each window's most-permissive-wins independently (`testSingleInheritanceEventAffectingMultipleWindowsResolvesEachWindowIndependently`); two guard-triggering template updates inside one shared flush do not cause Hibernate reentrancy (`testTwoGuardTriggeringTemplateUpdatesInsideASingleFlushDoNotCauseHibernateReentrancy`). Uses the real Finance/Sales system templates (not throwaway roles) plus one confirmed-unused window (`AD_Window_ID = 100`) for the shared grant, so it is independent of whatever the templates' own real grants happen to be. |
| `UserRoleCompositionServiceRealAccessControlIntegrationTest` (B5, ETP-4906) | 228 | Real-DB proof (3 tests, `WeldBaseTest`) that `WindowAccessOverlapCorruptionGuard`'s protection produces the CORRECT effective access outcome, not just a crash-free one, against real ETP-4878 seed-data templates: a Sales-only composed role has no access to Purchase Invoice; a Purchasing-only composed role has no access to Sales Invoice; and a Sales-only role is read-only on "Categoría del producto", then adding Finance upgrades it to full (most-permissive-wins) — the same scenario §8d's four outcomes (no-access ×2, read-only, full) are meant to cover end-to-end. |
| `UserRoleCompositionServiceOverlapReverificationTest` | 308 | QA (Sentinel) independent re-verification (3 tests) of the same overlap fix, deliberately NOT reusing the fix author's own integration test: 3 simultaneously-overlapping templates (Finance/Sales/Purchasing on a shared window) resolve to most-permissive-wins with the "winner" (Purchasing, full) in the middle of the composition order — ruling out a pairwise-only fix that only checks the newest template against the immediately-preceding state; and two cases seeded with the REAL ETP-4878 matrix's own access levels (not the synthetic window `100`) — Sales (full) + Inventory (read-only) on Contactos resolves to full, and Sales + Purchasing both read-only on Categoría del producto stays read-only (confirms the fix does not spuriously promote a window to full just because 2+ templates share it). Also closes a data point the original QA report got wrong: `ad_window_access_un_key` is a plain `CREATE UNIQUE INDEX` on `(ad_role_id, ad_window_id)`, invisible to a `pg_constraint`-only query — Sales already had a live pre-existing row for Contactos, so this suite seeds only the missing side instead of inserting a duplicate. |
| `SFAssignUserRolesTest` | -- | Unit test proving the webhook wires parameters/results/errors correctly, with `UserRoleCompositionService` itself intercepted via `mockConstruction` (its real behavior is the integration test's job): access gate (no role / restricted role denied without constructing the service), the happy path (admin composes, parses a whitespace/empty-entry-noisy `TemplateRoleIds` CSV, returns the assignment summary), missing `UserId` rejected before construction, an absent `TemplateRoleIds` parameter resolving to an empty (not `null`) list meaning "revoke all", a domain `OBException` folding into a `success:false` HTTP-200 result rather than the bridge's `error`/500 path, an unexpected `RuntimeException` surfacing as the bridge's `error` field instead, and the REVIEW cycle 1 regression proving the webhook actually forwards its already-resolved `currentRole` through to `assignTemplateRoles`'s 4-arg overload — the exact wiring the tenant-boundary check depends on. **ETP-4830 addition:** a companion regression proves the webhook ALSO resolves the caller's own `AD_User_ID` (via `OBContext.getOBContext().getUser()`, stubbed on the mock context) and forwards it as the 4th argument — the wiring `enforceOwnerProtection` depends on; every pre-existing test in this file leaves `mockContext.getUser()` unstubbed (defaults to `null`), confirming `callerUserId=null` for those and that the owner-protection check stays a no-op unless a real caller identity is resolved. |
| `SFUserRoleAssignmentsTest` (ETP-4906) | -- | Unit test mirroring `SFAssignUserRolesTest`'s `mockConstruction` convention for §8e's read endpoint: access gate denies with the mode-appropriate empty shape (bulk `{"assignments":{}}` with no `UserId`, single `{"userId":...,"templateRoleIds":[]}` with one) without constructing the service; bulk mode returns every user's assignments keyed by id, scoped to `currentRole.getClient().getId()`; single mode returns one user's ids and proves `currentRole` is forwarded into the boundary-checking overload (mirrors `SFAssignUserRolesTest`'s own forwarding regression); a cross-tenant read attempt and an unknown-user-id `OBException` both fold into the single-mode empty shape rather than the bridge's `error`/500 path; an unexpected `RuntimeException` still surfaces as `error`. |
| `SFSystemRoleTemplatesTest` (ETP-4906) | -- | Unit test (12 tests) mirroring `SFRolesOverviewTest`'s structure for §8f's endpoint: admin/client-admin access gate (no role, restricted role, System Administrator, client-admin — all resolved without the caller's own client ever appearing in any stub); roles resolved via `OBDal.get(Role.class, id)` against the 4 fixed `SystemRoleTemplates` ids rather than a client-scoped `Role` criteria; response omits `userCount`/`isClientAdmin` entirely; Finance/Sales/Purchasing/Inventory ordering; a template id resolving to `null` or to an inactive `Role` is skipped gracefully rather than erroring; GO-window intersection (native-only windows excluded) and tier resolution (full/read-only), mirroring `SFRolesOverview`'s identical logic; exception handling. |
| `NeoPseudoSpecDispatcherTest#debugInvitationBypass*` (ETP-4830) | -- | The security-critical case for §8g: flag unset AND flag explicitly `"false"` both return a plain `404` with `SFDebugInvitationBypass` never constructed and `NeoGoWebhookBridge#handle` never invoked (zero DB access, not just an early-return inside the webhook); flag `"true"` dispatches through the bridge with a real `SFDebugInvitationBypass` instance; non-`GET` is rejected even when the flag is on. Uses `System.setProperty`/`clearProperty` — `ConfigPropertyReader`'s own documented precedence puts the JVM system property first, ahead of `Openbravo.properties`/env var. |
| `SFDebugInvitationBypassTest` (ETP-4830) | -- | Unit test mirroring `SFAssignUserRolesTest`'s shape for §8g's shim: access gate (no role / restricted role denied without touching `DebugInvitationBypassService`, injected as a plain Mockito mock via the package-private constructor); `forceAccept`/`forceStatus` delegate with the exact marshalled params, case-insensitive `Action` matching; an unknown/missing `Action` fails without touching the service; an unexpected `RuntimeException` from the service maps to the bridge's `error` field rather than escaping as a thrown exception. |
| `DebugInvitationBypassServiceTest` (ETP-4830) | -- | Unit test for §8g's real logic, `OBDal`/`EtendoGoJwtDalHelper`/`CompanyInvitationDalHelper`/`CompanyInvitationService` all Mockito static mocks (mirrors `CompanyInvitationServiceTest`'s conventions): `forceAccept` rejects a blank email with no resolvable `AdUserId`; creates a new account via `EtendoGoJwtDalHelper#createAccount` (asserted called, proving no duplicated account-creation logic) when none exists, returning a `temporaryPassword`; reuses an existing active account without a second `createAccount` call and without a `temporaryPassword` in the response; flips a matching open invitation to `ACCEPTED` and links the account; resolves the email from `AdUserId` when `Email` is blank. `forceStatus` rejects a status outside the enum; resolves by `InvitationId` directly (skipping the email lookup entirely) or by the most recent invitation for `Email`; fails cleanly with `success:false` when no invitation matches. |
| `SFResendInvitationTest` (ETP-4830) | -- | Unit test mirroring `SFDebugInvitationBypassTest`'s shape for §8h's shim: access gate (no role / restricted role denied without touching `CompanyInvitationService`, injected as a plain Mockito mock via the package-private constructor); delegates to `resendInvitation` with the marshalled `AdUserId` (blank when the param is absent, not `null`); an unexpected `RuntimeException` from the service maps to the bridge's `error` field rather than escaping as a thrown exception. |
| `OwnerSupportTest` (ETP-4830) | -- | Unit test for §7 item 10's `EM_ETGO_Is_Owner` read/write helper, mirroring `SFWindowAccessMapTest`'s native-query mocking convention (`MockedStatic<OBDal>` + a mocked `Session`/`NativeQuery`, `Character` rows for the `char(1)` column, never `String`): `isOwner` true/false/null-column/missing-user, and `false` for a blank/`null` id without ever touching `OBDal`; `clientHasOwner` true/false, same blank/`null` short-circuit; `markAsOwnerIfNoneExists` executes the `UPDATE` only when `clientHasOwner` first reads empty (2 native queries), is a complete no-op (only 1 native query, the check) when the client already has an owner, and never touches `OBDal` at all for a missing client id or user id. |
| `UserRoleAssignmentHandlerTest` (owner-protection additions, ETP-4830) | -- | `rejectNonOwnerEditingOwner` (§7 item 10's path (a)): a non-owner's PATCH/PUT on an `EM_ETGO_Is_Owner`-flagged record is rejected with `400` regardless of which field it touches (separate cases for `name`, `email`, and `active`, the last two proving the owner guard's own message wins over the ALSO-400 email-immutability/self-lockout guards it runs before — and that `OBDal` is never even reached for those); the owner editing their own record is a no-op that falls through to the other guards unchanged; a target NOT flagged as owner is unaffected regardless of caller (baseline); and an `OwnerSupport.isOwner` lookup failure fails CLOSED (`500`), same convention as every other guard in this handler. Every PRE-EXISTING PUT/PATCH test in this file also gained a `MockedStatic<OwnerSupport>` stub (`isOwner` → `false`) plus, where the test did not already mock it, a matching `MockedStatic<OBContext>` stub — the new guard's own `OBContext.setAdminMode`/`OwnerSupport.isOwner` calls run unconditionally on every PUT/PATCH now, ahead of the email/deactivation guards those tests actually target. **ETP-4830 item #4 additions (`attachOwnerFlag`):** `isOwner` attached `true`/`false` per row on a list GET (`OwnerSupport.isOwner` mocked statically, one stub per row id); attached on a single-record GET the same way; attached with NO `obContext`/`clientId` at all (unlike `invitationStatus`, confirming the two attach steps are independently scoped); and left unattached (best-effort, no field written, `afterHandle` itself never throws) when `OwnerSupport.isOwner` throws. |

Tests are located in `src-test/src/com/etendoerp/go/schemaforge/` (including its `webhooks/`
subpackage, e.g. `SFAssignUserRolesTest`/`SFUserRoleAssignmentsTest`/`SFSystemRoleTemplatesTest`/
`SFDebugInvitationBypassTest`/`SFResendInvitationTest`, and its `handlers/`/`util/` subpackages,
e.g. `UserRoleAssignmentHandlerTest`/`OwnerSupportTest`) and `src-test/src/com/etendoerp/go/rest/`
(e.g. `CompanyInvitationServiceTest`/`DebugInvitationBypassServiceTest` — the former's
`resendInvitation` coverage, §8h, lives alongside its pre-existing `createInvitation`/
`findLatestInvitationStatus` suites, same file, no separate class).
The `NeoPseudoSpecDispatcher` routing for `userroleassignments`, `systemroletemplates`,
`debuginvitationbypass`, `resendinvitation`, and `promoteuserrole` is covered by
`NeoPseudoSpecDispatcherTest` (same package), mirroring its existing per-endpoint dispatch/
method-not-allowed test pairs — `debuginvitationbypass` additionally covers the flag-off/flag-on
branch described in §8g (`resendinvitation` and `promoteuserrole` have no such flag to test, §8h/
§8i). The `AD_Role`-templates/composition classes —
`UserRoleCompositionServiceTest`, `UserRoleCompositionServiceIntegrationTest`,
`UserRoleCompositionServiceOverlapIntegrationTest`,
`UserRoleCompositionServiceOverlapReverificationTest`,
`UserRoleCompositionServiceRealAccessControlIntegrationTest`, and `TemplateRoleWindowAccessTest` —
are the exception, living under `src-test/src/com/etendoerp/go/roles/` alongside the
`com.etendoerp.go.roles` production classes they cover, which also includes
`WindowAccessOverlapCorruptionGuard` itself (§8d) — it has no dedicated test class of its own and
is exercised entirely through `UserRoleCompositionServiceOverlapIntegrationTest`/
`UserRoleCompositionServiceOverlapReverificationTest`/
`UserRoleCompositionServiceRealAccessControlIntegrationTest` above.

---

## 10. Future Considerations

**Granular override registry.** The current hook mechanism uses a single `javaQualifier` on the entity level. A dedicated override table (per-method, per-entity granularity) would allow more precise hook targeting without requiring a custom handler to inspect the HTTP method internally.

**Cascade validation filters for selectors.** Selectors currently query all valid values without considering dependent field constraints. A validation rule table would allow defining cascading filters (e.g., filtering products by the selected product category).

**PATCH method in Etendo core.** PATCH is handled via a `service()` override that intercepts the method string and routes it to the PUT handler internally. Native PATCH support in the Etendo servlet infrastructure would allow true partial-update semantics.

**OpenAPI auto-generation.** Specs contain enough metadata (entities, fields, methods, selectors) to auto-generate OpenAPI 3.0 documents. This would enable client SDK generation and interactive API documentation.

**Callout endpoints.** Etendo callouts (field-change triggers) are not exposed through the API. A callout endpoint would allow clients to request server-side field recalculations when a field value changes.

**Custom HQL selectors.** OBUISEL selectors with `isCustomQuery = true` are fully supported. The `executeCustomHqlQuery()` method handles custom HQL with org filtering, validation rules, search across searchable properties, and pagination.
