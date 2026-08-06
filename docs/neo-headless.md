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

Before persistence, NEO resolves defaults and executes the header-tab callout cascade. Values
explicitly supplied by the client and values injected for mandatory AD columns are protected from
callout updates. Defaults for non-mandatory columns remain eligible for callout-derived updates.
This keeps an explicit or mandatory system default from being replaced by an unrelated selector
callout while preserving normal dependent-field derivation.

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
place the three accepted shapes are listed. It is applied at three points:

| Point | Class | Effect |
|---|---|---|
| Read — `/defaults` response | `NeoDefaultsService.canonicalizeDateDefaults` | every date-valued default leaves as ISO |
| Write — REST | `NeoTypeCoercionHelper.coerceField` | date branch, runs before the SmartClient wrap |
| Write — MCP | `McpToolRouterSupport.coercePrimitiveFieldValue` | same branch, mirrored |

**Which properties are eligible** is decided in one place —
`NeoDateFormat.canonicalShapeFor(Property)` — and it is deliberately narrower than "the Java type is
a `Date`". Etendo has **five** date-ish domain types and `JsonToDataConverter` branches on all of
them (`Property.java:1107-1124`):

| Domain type | Predicate | NEO normalizes? |
|---|---|---|
| `DateDomainType` | `isDate()` | ✅ → `yyyy-MM-dd` |
| `DatetimeDomainType` | `isDatetime()` | ✅ → `yyyy-MM-dd'T'HH:mm:ss` |
| `TimestampDomainType` | `isTimestamp()` | ❌ left as-is |
| `AbsoluteTimeDomainType` | `isAbsoluteTime()` | ❌ left as-is |
| `AbsoluteDateTimeDomainType` | `isAbsoluteDateTime()` | ❌ left as-is |

The last three are excluded for a concrete reason, not as margin. The two `Time` kinds are
**time-of-day** values: the converter discards everything before the `T`, appends `+0000` and
supplies the calendar day itself — so rewriting such a value to `yyyy-MM-dd` would delete the only
half it reads. `AbsoluteDateTime` is explicitly timezone-free and would need an offset policy no
caller has asked for. All three keep today's behaviour exactly.

Three deliberate constraints:

- **An unrecognised shape is passed through verbatim** and logged at `WARN`, never blanked. Blanking
  would turn a formatting problem into a missing mandatory field, and a guessed date is worse than
  the lenient parser this replaces.
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
3. **Table (ref 18) / Search (ref 30)** -- resolved via `AD_Ref_Table` (target table, key column, display column, optional HQL where clause).

OBUISEL selectors with custom HQL queries are fully supported. The service uses `Session.createQuery()` to execute the custom HQL with org security filtering, validation rules, search across searchable properties, and pagination.

The service resolves `@param@` placeholders in OBUISEL HQL where clauses: `@AD_Org_ID@`, `@AD_Client_ID@`, `@AD_User_ID@`, `@AD_Role_ID@`.

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

### 4.7 Preview File Endpoint

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
contract, document spec, record id, client id, send idempotency key, and expiration. The endpoint
serves the cached `ETGO_PREVIEW_FILE` file only after validating the token signature and expiration.
The send event remains audited by the transactional email service, but download authorization does
not rely on in-memory audit state.

#### Frontend integration

The React hook `usePreviewAttachment` (`tools/app-shell/src/windows/custom/shared/usePreviewAttachment.js`) wraps all three methods. It is activated only when `storeCondition=true`; when false the hook is a no-op and nothing is fetched or stored.

`GenericPreviewModal` consumes `usePreviewAttachment` internally via its `attachmentConfig` prop and manages the left-panel state machine:

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

Returns `400` if `entityName`/`items` is missing or `items` is not valid JSON, `422` if `entityName`
does not resolve to a readable entity, `405` for any method other than `GET`.

---

### 4.10 NEO Pseudo-Spec Bridge for Etendo GO's Own Webhooks

```
GET /sws/neo/listmenu[?q=<term>]
GET /sws/neo/windowaccessmap
GET /sws/neo/rolesoverview
Authorization: Bearer {token}
```

`NeoGoWebhookBridge` runs `SFListMenu`/`SFWindowAccessMap`/`SFRolesOverview` (§8, §8b, §8c) through
NEO's own JWT authentication instead of the Webhooks module's HTTP dispatch — the same pattern
`NeoSimSearchEndpoint` (§4.9) already used for `SimSearch`. Each of these three pseudo-specs
constructs the corresponding `BaseWebhookService` and calls its unchanged `get(Map, Map)` method
directly; the response body is the exact `{"result": "<value>"}` / `{"error": "<message>"}` shape
the Webhooks module itself produces (verified by disassembling `WebhookServiceHandler.buildResponse`
in `webhookevents-3.1.0.jar`), so callers only need their request URL updated, never their
response-parsing logic. All three still work at their original `/webhooks/SFListMenu` /
`/webhooks/SFWindowAccessMap` / `/webhooks/SFRolesOverview` paths too — the Webhooks module dispatch
was not removed — but `/sws/neo/*` is the path the Go SPA (`tools/app-shell` in
`etendo_schema_forge`) actually calls, and no `SMFWHE_DEFINEDWEBHOOK_ROLE` grant is required for it.

Each webhook's own access rule is unaffected and still enforced inside its `get()` — see §8/§8b/§8c
for what each one checks (`NeoAccessHelper.isAdminOrClientAdmin`, window/process access checks,
etc.). Non-`GET` requests get `405`; a webhook that throws gets `500` with the exception message.

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

**Response** (`{spec, entity, actions, actionCount}` — the full `fields` array is dropped):

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
    }
  ],
  "actionCount": 2
}
```

Behavior details (`McpActionsView`):

- The view is a **pure re-shape** of the field array `neo_schema` already builds
  (`McpSchemaFieldBuilder.buildSchemaFieldsArray`) — it simply filters down to the `type:"button"`
  entries, in their original order. No additional DAL/model access is performed.
- Each returned action is already fully self-describing: `invokeVia:"neo_action"` plus `action`,
  `processType`, `processName`, and `processId` tell the agent exactly how to invoke it via
  `neo_action` — no follow-up `neo_schema` call on the full entity is required.
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

#### 4.12.3 FK-by-name resolution on `neo_create` / `neo_update` (IMP-4)

Historically every foreign-key field in a write body required the exact 32-character record id,
forcing an agent to call `neo_selectors` first even for an obvious single-match lookup. Wave 3 lets a
write body pass a **human search string** for an FK field; the router resolves it to the real record
id server-side before persisting, via the same selector path `neo_selectors` uses
(`NeoSelectorService.querySelectorByColumn`, limit 10). This runs for both `neo_create` and
`neo_update` (`McpFkResolver.resolveFkNames`, invoked from `handleCreate` and `handleUpdate`).

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
- Only FK fields are considered: a key is resolved only if it maps to a DAL property that is a
  non-primitive association with a target entity. Non-FK fields, non-string values, and empty strings
  are never touched.

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
  "detail": "No match for 'businessPartner'='Acme Corp'. Use neo_selectors to search, or pass the exact record id instead.",
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

**Real-world example — `TbaiConfigSequenceHandler`** (`schemaforge/handlers/TbaiConfigSequenceHandler.java`, `@Named("tbai-config-sequence-handler")`, wired as the `header` entity's `JAVA_QUALIFIER` for the `tbai-config` spec): a post-hook (`afterHandle`) that runs on every successful `POST`/`PUT` of the TBAI Fiscal Configuration. It walks the config's organization tree — plus organization `*` (id `0`), added explicitly since Document Types are very commonly defined at org `*` and would otherwise be silently excluded (same precedent as `SelectorOrgFilter#buildOrganizationPredicate`) — and finds every **active** `DocumentType` whose backing table is `C_Invoice` — which naturally covers sales invoices (`ARI`), purchase invoices (`API`), and their credit notes (`ARC`/`APC`), since all four share that table. Rather than one sequence per Document Type, it ensures the whole scope shares **exactly one** chaining `Sequence` (prefix `TBAI-`): it reuses one already assigned to any qualifying Document Type in scope, or creates a single new one only if none exists yet. This is the core fiscal-correctness rule — TicketBAI chains invoice numbers with a single scope-wide counter, so independent per-Document-Type sequences could collide. A Document Type that already has a chaining sequence (`EM_Tbai_Ad_Sequence_ID`) is left untouched, so re-saving the config is safe (idempotent). Any error is logged and swallowed: this is a best-effort secondary side effect and must never fail the parent save request.

**Real-world example — `RectificativeSupport` (optional-column guard shared across handlers, ETP-4737 "Factura Rectificativa"):** `schemaforge/RectificativeSupport.java` is a package-private helper (not itself a `NeoHandler`) that guards every read of the optional `C_DocType.EM_ETSG_ISRECTIFICATIVE` column, owned by the (optional) SIF General module (`com.etendoerp.sif.general`). Because that column may not exist in a given database, a naive `SELECT` against it would abort the whole shared read-only PostgreSQL transaction for the rest of the request — so `isColumnPresent()` checks `information_schema.columns` once, lazily, and caches the result (`volatile Boolean`, double-checked locking); `isRectificative(DocumentType)` and every caller go through that guard before touching the column, degrading gracefully to `false`/legacy-only behavior when the module isn't installed. Three independent call sites share this one guard instead of each re-implementing the check:
  - `AbstractInvoiceHeaderHandler#enrichIsRectificative` (GET-response enrichment shared by `SalesInvoiceHeaderHandler`/`PurchaseInvoiceHeaderHandler`) and its abstract `classifyDocType(DocumentType)` — resolved by each subclass into the unified `SUBTYPE_RECTIFICATIVA` constant (collapsing the former separate `NC`/`DEV` AR subtypes and the AP credit-memo subtype into one), with a legacy category-based fallback (`ARC`/`ARI_RM` for AR, `APC`/`API`+`isReturn` for AP) so invoices already using the old, now-deactivated Credit Note / Return Invoice document types keep classifying correctly.
  - `ReturnShipmentUtils.findReturnDocTypeForOrg(orgId, docCategory, isSales, requireReturn, requireRectificative)` — the `requireRectificative` parameter, when `true`, adds `Restrictions.eq(DocumentType.PROPERTY_ETSGISRECTIFICATIVE, true)` to the doc-type lookup criteria (silently ignored when the column is absent). Called with `requireRectificative=true` by both `ReturnMaterialReceiptHeaderHandler`'s and `ReturnToVendorShipmentHeaderHandler`'s `createReturnInvoice` action, so a confirmed Goods Return (either direction) auto-generates its invoice against the new unified rectificative doc type rather than a hardcoded legacy category.
  This is the pattern to follow for any future optional-module column: a single lazily-cached presence guard in a small dedicated class, consumed by every handler that needs it, rather than each handler probing `information_schema` independently.

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

9. **Field-level control:** Only fields with `ISINCLUDED = 'Y'` participate in selector listings and button action discovery.

**Known limitations (ETP-4596):** 7 of the 8 `SPEC_TYPE = 'R'` report specs have no classic-process mapping and still have no handler-level access control. Separately, the MCP tool catalog/discovery layer (`ToolRegistry`, `NeoDiscoveryHelper`, `McpToolRouterSupport`) still exposes the *existence* of process-null specs to any authenticated caller regardless of role — metadata-only exposure; actual data access is blocked wherever a handler-level gate exists. Both gaps are tracked in ETP-4596, not fixed by ETP-4510/ETP-4511.

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
  "count": 2
}
```

`type` is derived from `AD_Menu.issummary`/`action`: `folder` (summary node), `window` (`action = 'W'`), `process` (`action = 'P'`), `report` (`action = 'R'`), `form` (`action = 'X'`), or `other`. Leaf nodes carry whichever of `windowId`, `processId`, `obuiappProcessId`, `formId` applies; folders carry `children` instead.

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

**`AD_Role.EM_ETGO_Show_Acct_Fields`:** a Yes/No extension column added by this module (`AD_Column_ID = A0F2D12B5B4A48C2855EE73E3E93E274`, default `N`) and exposed as a real field (`AD_Field_ID = 98C71197D0744EED96856A497E49F159`) on the classic `AD_Role` window/tab, so a functional consultant can toggle it like any other role attribute. It gates accounting-sensitive field/tab visibility in Etendo GO — e.g. the `Posted` status pill on invoice windows and the financial-account edit form's "Cuentas contables" tab — independently of per-window `AD_Window_Access`.

**`capabilities.isAdminOrClientAdmin`** (ETP-4513) is the proactive signal the frontend uses to decide whether to show admin-only settings entries — e.g. the "Configuración > Roles" menu item, backed by `SFRolesOverview` (§8c) — up front, instead of showing them to every role and handling denial only once the page itself loads.

---

## 8c. Roles Overview (SFRolesOverview Webhook)

`SFRolesOverview` (`GET /webhooks/SFRolesOverview`, or preferably `GET /sws/neo/rolesoverview` — §4.10) returns, for an admin/client-admin caller only, a cross-role aggregate for GOClient's 5 fixed roles (ETP-4513 — "Configuración > Roles"): each role's display name, raw `AD_Role.description`, count of distinct assigned users (`AD_User_Roles`), and the list of Etendo GO windows it can reach (`AD_Window_Access`, intersected with the windows Etendo GO actually exposes today). The webhook is authored in the same Webhooks module infrastructure as `SFListMenu`/`SFWindowAccessMap`, but the Go SPA (`RolesOverviewPage.jsx`) reaches it through the NEO pseudo-spec bridge (§4.10).

Unlike `SFWindowAccessMap`, which answers "what can the CURRENT caller's own role reach", this endpoint is a cross-role aggregate: it always returns data for all 5 GOClient roles regardless of which one the caller happens to be using. That is exactly why it is gated to admin/client-admin callers only.

**Endpoint:**

| Pattern | Method | Description |
|---------|--------|-------------|
| `/webhooks/SFRolesOverview` (legacy) / `/sws/neo/rolesoverview` (preferred) | GET | Per-role aggregate (user count + reachable windows) for all 5 GOClient roles |

**Response shape:**

```json
{
  "roles": [
    {
      "id": "9B8D736190724807AB256DC95F20EC5E",
      "name": "GOClient Admin",
      "rawDescription": "*** Please, do not edit this role. Use Copy Record instead ***",
      "userCount": 2,
      "windows": [
        { "id": "143", "name": "Sales Order", "tier": "full" },
        { "id": "259", "name": "Business Partner", "tier": "read-only" }
      ]
    }
  ]
}
```

**Access gate:** the current role is captured once, at the very top of `get(Map, Map)`, before the servlet enters `OBContext.setAdminMode()` — same convention as `SFListMenu`/`SFWindowAccessMap`: admin mode is only used to bypass row-level security on the underlying queries, never to decide access. A request with no role assigned, or a role that is not admin/client-admin (`NeoAccessHelper.isAdminOrClientAdmin(Role)`), gets `{"roles": []}` immediately, without querying a single `Role` — mirroring `SFListMenu`'s "deny silently, don't 403" convention for this webhook family.

**`rawDescription` is NOT display copy.** `AD_Role.description` is boilerplate for 4 of the 5 GOClient roles today (`"*** Please, do not edit this role. Use Copy Record instead ***"`) — this backend has no i18n awareness, so it cannot produce user-facing copy itself. The field is returned only as a raw/debug fallback; the frontend (`RolesOverviewPage.jsx` in `etendo_schema_forge`) maps each of the 5 known role ids to its own curated, i18n-keyed description (`roleDescGoClientAdmin`, `roleDescFinance`, etc. in `en_US.json`/`es_ES.json`) instead of rendering this field.

**The 5 role IDs** are GOClient's fixed, well-known roles (seeded in ETP-3504 phases 1/2 — see `artifacts/user/decisions.json` in `etendo_schema_forge`'s `defaultRole.enumValues` for the same 5 ids/names) and are intentionally hardcoded in `SFRolesOverview.GOCLIENT_ROLE_IDS` rather than derived from a client/role-name heuristic:

| Role | `AD_Role_ID` |
|------|--------------|
| GOClient Admin | `9B8D736190724807AB256DC95F20EC5E` |
| Finance | `127AE77FE2994067B7FE6495FC21D51E` |
| Sales | `2A159DF4F4B944A6AA903202AD35B545` |
| Purchasing | `A826430F723E4C1B9A53EBB0746A98C0` |
| Inventory | `55E05A4B43514A029D6FB6B8D94B49D4` |

A role id that fails to resolve (missing/renamed) is skipped defensively — the other 4 roles are still returned rather than failing the whole request.

**`windows` intersection:** a role's native `AD_Window_Access` rows are filtered down to windows Etendo GO actually exposes today — every distinct `AD_Window` backing an active, `SPEC_TYPE = 'W'` `ETGO_SF_SPEC` — so inherited/legacy grants to native-only Etendo windows don't leak into this "assigned windows" view. Each entry's `tier` resolves the same way as `SFWindowAccessMap`: `IsReadWrite = true` → `"full"`, `IsReadWrite = false` → `"read-only"`. The array is sorted by window name.

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
| `SFListMenuTest` | -- | Tree building/pruning, flat search, role-based filtering (window/process/OBUIAPP-process nodes), no-role → empty menu, multi-level nesting. |
| `SFWindowAccessMapTest` | -- | Role-based windowAccess resolution (full/read-only/absent), no-role → both maps empty, admin/client-admin bypass → full access to every active Etendo GO window + every capability true, `showAccountingFields` true/false/unset/missing-role, `isAdminOrClientAdmin` true on bypass / false for a restricted role. |
| `SFRolesOverviewTest` | -- | Admin/client-admin access gate (no role, restricted role, System Administrator, client-admin), all 5 roles returned in `GOCLIENT_ROLE_IDS` order with id/name/rawDescription, missing/renamed role id skipped gracefully, distinct-user-count aggregation, GO-window intersection (native-only windows excluded), tier resolution (full/read-only), exception handling. Two defense-in-depth regression cases confirm the gate is genuinely `isAdminOrClientAdmin`, not "is this one of the 5 known `GOCLIENT_ROLE_IDS`": a caller authenticated AS one of those 5 roles (Finance) but not admin/client-admin is still denied (empty `roles`, zero `Role` lookups), and a role with zero active `AD_User_Roles` AND zero active `AD_Window_Access` rows degrades gracefully to `userCount: 0` + an empty `windows` array for all 5 roles rather than throwing or omitting the role. |

Tests are located in `src-test/src/com/etendoerp/go/schemaforge/`.

---

## 10. Future Considerations

**Granular override registry.** The current hook mechanism uses a single `javaQualifier` on the entity level. A dedicated override table (per-method, per-entity granularity) would allow more precise hook targeting without requiring a custom handler to inspect the HTTP method internally.

**Cascade validation filters for selectors.** Selectors currently query all valid values without considering dependent field constraints. A validation rule table would allow defining cascading filters (e.g., filtering products by the selected product category).

**PATCH method in Etendo core.** PATCH is handled via a `service()` override that intercepts the method string and routes it to the PUT handler internally. Native PATCH support in the Etendo servlet infrastructure would allow true partial-update semantics.

**OpenAPI auto-generation.** Specs contain enough metadata (entities, fields, methods, selectors) to auto-generate OpenAPI 3.0 documents. This would enable client SDK generation and interactive API documentation.

**Callout endpoints.** Etendo callouts (field-change triggers) are not exposed through the API. A callout endpoint would allow clients to request server-side field recalculations when a field value changes.

**Custom HQL selectors.** OBUISEL selectors with `isCustomQuery = true` are fully supported. The `executeCustomHqlQuery()` method handles custom HQL with org filtering, validation rules, search across searchable properties, and pagination.
