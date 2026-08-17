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
GET /sws/neo/assignuserroles?UserId=<id>&TemplateRoleIds=<id1,id2,...>
GET /sws/neo/userroleassignments[?UserId=<id>]
GET /sws/neo/systemroletemplates
Authorization: Bearer {token}
```

`NeoGoWebhookBridge` runs `SFListMenu`/`SFWindowAccessMap`/`SFRolesOverview`/`SFAssignUserRoles`/
`SFUserRoleAssignments`/`SFSystemRoleTemplates` (§8, §8b, §8c, §8d, §8e, §8f) through NEO's own
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
`SFUserRoleAssignments` (ETP-4906), and `SFSystemRoleTemplates` (ETP-4906) are `/sws/neo/*`-only
— all three were authored after this pattern was already established, so none ever had a legacy
`/webhooks/*` path to keep.

Each webhook's own access rule is unaffected and still enforced inside its `get()` — see
§8/§8b/§8c/§8d/§8e/§8f for what each one checks (`NeoAccessHelper.isAdminOrClientAdmin`,
window/process access checks, etc.). Non-`GET` requests get `405`; a webhook that throws gets
`500` with the exception message (except `SFAssignUserRoles`'s own expected domain-validation
rejections, and `SFUserRoleAssignments`'s own expected domain rejections — see §8d/§8e for why
those are a `200` result instead).

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
   | `/batch` + MCP `neo_batch` | `BatchService#createRecord` (the batch enters at `handleDefault`, i.e. after the CRUD gate) | per-op `405`, whole batch rolled back |
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

> **⚠️ Known follow-up gap (ETP-4852, not fixed here):** `SFRolesOverview.GOCLIENT_ROLE_IDS` is
> still hardcoded to GOClient's own 5 per-client role ids (the table above). Since ETP-4852
> moved the 4 fixed roles to system-level templates and introduced per-user personal
> composition roles (§8d), this webhook's "Configuración > Roles" aggregate no longer reflects
> the real per-tenant picture for any client OTHER than GOClient (whose old per-client role
> copies still exist, untouched, pending ETP-4877's migration) — a tenant onboarded after
> ETP-4852 has no roles at all under these 5 hardcoded ids to aggregate. Reworking this webhook
> to aggregate the system templates + each tenant's own personal roles is a natural follow-up,
> out of scope for ETP-4852 itself (which only builds the mechanism, per the ticket).

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
`currentRole` into the 3-arg `assignTemplateRoles(String, List, Role)` overload for exactly this
purpose — see that method's javadoc. A 2-arg `assignTemplateRoles(String, List)` overload also
exists (delegates with `callerRole=null`, skipping the boundary check entirely); today it is only
reached by plain unit tests and the integration test's fixture calls, but REVIEW flagged it as a
non-blocking latent risk — a future caller reaching for the 2-arg overload instead of the 3-arg
one would silently ship without this protection.

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

It guards four triggers, all variations on the same root cause (`WindowAccessInjector` not
skipping `client`/`organization` in `getSkippedProperties()`, so core's `updateRoleAccess` blindly
copies the template's own ownership fields onto an already-existing inherited row):
1. **A template gains a new/updated `AD_Window_Access` grant.** For every OTHER role actively
   inheriting from that template, if it already has its own active row for the same window not
   already sourced from that same template, the row is deleted before core propagates — forcing
   core onto the safe CREATE path instead of the corrupting UPDATE path (mirrors
   `preventWindowAccessOverlapCorruption`'s own mechanism, generalized to every dependent role).
2. **A role gains a brand-new `AD_Role_Inheritance` from an already-overlapping template**
   (e.g. a raw Classic "add inheritance" edit, not `assignTemplateRoles`) — same delete-before-write
   logic, scoped to the one role gaining the one new inheritance.
3. **A role loses an existing `AD_Role_Inheritance`.** Core's `applyRemoveInheritance`
   re-derives the dependent's access against every REMAINING template it still inherits from via
   the same corrupting `updateRoleAccess` path. For every remaining template, for every window it
   grants, if the dependent's existing row isn't already sourced from that exact template, it is
   deleted first, forcing the safe CREATE path again. A row whose window is granted by NO
   remaining template is left alone — core's own non-corrupting delete handles that case already.
4. **Most-permissive-wins enforcement + `InheritedFrom` bookkeeping on widen.** None of the three
   triggers above decide the access LEVEL the CREATE path should use when 2+ actively-inherited
   templates grant the same window — outside `assignTemplateRoles`, core's propagation can leave a
   role read-only even though another active template grants it full. `widenInheritedAccessLevelIfNeeded`
   runs on the same `EntityNewEvent` as trigger 1-3's CREATE-path row: if any of the role's OTHER
   active template inheritances grants the same window at a more permissive level, it widens the
   fresh row to full (one-directional — never narrows) AND repoints `InheritedFrom` to the
   justifying template, so a LATER removal of that exact template correctly re-triggers
   re-derivation instead of leaving the row stuck at full forever. A same-flush race this fix
   exposed (Hibernate runs entity Deletions after Insertions in its default action-queue order, so
   a just-removed template's `RoleInheritance` row can still look `active=true` mid-flush) is
   closed via a per-transaction `TEMPLATES_BEING_REMOVED` thread-local marker, cleared by a
   `TransactionCompletedEvent` observer.

Deliberately narrow in scope: only reacts when the touched `AD_Window_Access` row's owning role is
itself a template (the same signal core's own handler uses to decide whether to propagate at all),
and never touches the template's own row or any grant LEVEL outside trigger 4's widen check.
`reconcileWindowAccessAfterComposition` (above) remains the most-permissive-wins union authority
for the role it actively composes; this class's job is making sure core never gets the chance to
corrupt or silently under-resolve a BYSTANDER role's access for every entry point that service
doesn't see. Full design rationale, each empirically-discovered failure mode, and the live
reproduction steps for all four triggers are documented in the class's own javadoc
(`src/com/etendoerp/go/roles/WindowAccessOverlapCorruptionGuard.java`) and in ETP-4906's plan doc
("B6 Findings" sections, `etendo_schema_forge` repo). `WindowAccessOverlapCorruptionGuard` has no
dedicated unit-test file of its own — it is exercised entirely through
`UserRoleCompositionServiceOverlapIntegrationTest` (all 4 triggers, real DB, `WeldBaseTest`) and
`UserRoleCompositionServiceRealAccessControlIntegrationTest` (real seed-data access-control
scenarios) — see §9.

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

No `userCount` (there is no meaningful "assigned users" count for a system-level template — every
tenant that composes from it gets its own personal role, per `UserRoleCompositionService`) and no
client-admin row (`SystemRoleTemplates`'s own class javadoc explicitly excludes the client-level
"Admin" role from the template set — there is nothing to represent at system level).

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
| `TemplateRoleWindowAccessTest` (ETP-4878) | -- | The real ETP-4878 permission matrix in `TemplateRoleWindowAccess` (`src/com/etendoerp/go/roles/`), DB-free (12 tests): exactly the 4 non-Admin template roles present, exact grant counts per role (Sales 13 / Purchasing 11 / Finance 27 / Inventory 13, 64 total), Asientos manuales resolves to Simple G/L Journal and never to the classic G/L Journal window (`132`), Sales has no grant for Pago, "Categoría del producto" is read-only for Sales/Purchasing but full for Finance/Inventory, no role repeats the same `AD_Window_ID` twice, `byRoleId()` returns a fresh mutable map per call. QA (Sentinel) added 3 more: the 64 grants resolve to exactly 33 DISTINCT `AD_Window_ID`s (not just a raw count that would stay 64 even under duplication); all 8 window/role pairs from the old ETP-4852 2-window smoke test survive unchanged (same full access) in the new matrix, confirming `EnsureSystemRoleTemplatesScript#removeStaleWindowAccess`'s delete path is never actually exercised by that specific migration; and at least one window (e.g. Contactos, Pedido de venta) is granted at genuinely conflicting access levels across 2+ roles — the data-level root cause behind the ETP-4852 cross-template overlap bug fixed in `UserRoleCompositionService` (see §8d and `UserRoleCompositionServiceOverlapIntegrationTest`). |
| `UserRoleCompositionServiceTest` | -- | Pure-Mockito unit test covering `assignTemplateRoles`'s input-validation guard clauses — the slice that fails before any persistence side effect: blank user id, `null` template id list, unknown user, unknown/inactive template id, a role that is not a template, the client-admin "Admin" role rejected even if somehow marked as a template, requested-id dedup happening before the per-id validation loop (verified via a single `Role` lookup despite 3 whitespace-noisy repeats of the same id), and the two `enforceCallerClientBoundary` regression cases from REVIEW cycle 1: a caller whose client differs from the target user's is rejected with a "different client" message, while the literal System Administrator role id (`"0"`) bypasses the check and reaches the (unrelated) template-validation error instead. **ETP-4906 additions:** `getAppliedTemplateRoleIds`'s read path — blank/unknown user id rejected the same way, a user with no `Default_Ad_Role_ID` yet returns an empty list without ever calling `createPersonalRole`, a reusable personal role with 2 active `AD_Role_Inheritance` rows returns both `InheritFrom` ids in `Seqno` order, and the read path enforces the exact same `enforceCallerClientBoundary` regression pair (cross-client rejected, System Administrator bypasses) as the write path. |
| `UserRoleCompositionServiceIntegrationTest` | 446 | Real-DB, end-to-end proof (6 tests) of the full add/reconcile/retract lifecycle: a system-level (`AD_Client_ID = '0'`) template's `AD_Window_Access` propagates onto a per-tenant personal role purely via core's own `RoleInheritanceEventHandler`/`RoleInheritanceManager` (no hand-rolled copy in this module); removing a template on a later call retracts what it had propagated; re-running with the identical template set is a no-op (0 added, 0 removed); an empty template list on a user's FIRST-EVER composition call still creates the personal role and syncs `AD_User_Roles`/`Default_Ad_Role_ID` rather than leaving the user role-less; three occurrences of the same valid template id in one request collapse into exactly one `AD_Role_Inheritance` row instead of one per occurrence; and a recompose call mixing one still-valid template with one bogus id is rejected wholesale without mutating the inheritance/access an earlier, unrelated successful call had already applied. Extends `WeldBaseTest`, NOT plain `OBBaseTest` — role-inheritance propagation is driven by a Hibernate interceptor firing a CDI event that only `WeldBaseTest`'s Arquillian-booted container wires to an observer; under plain `OBBaseTest` the propagation silently never fires, which is a test-harness gap, not a bug in the service. |
| `UserRoleCompositionServiceOverlapIntegrationTest` | 718 | Real-DB proof (8 tests, `WeldBaseTest`) of the cross-template `AD_Window_Access` overlap fix AND `WindowAccessOverlapCorruptionGuard` (see §8d above): composing Finance (full) + Sales (read-only) on a shared window succeeds (no `OBSecurityException`) and resolves to full access, with `client`/`organization` on the shared row matching the personal role's own, and both templates' non-shared windows also present (a real union); the same conflicting grants requested in the OPPOSITE order still resolve to full — add order never changes the most-permissive-wins outcome; re-running the identical overlapping template set is a no-op; `getAppliedTemplateRoleIds` reflects a real overlapping composition, not just a mocked one. **Task B6 additions (`WindowAccessOverlapCorruptionGuard`'s own coverage, all 4 triggers):** a bystander role never passed to `assignTemplateRoles` (e.g. gaining 2 overlapping inheritances via a raw Classic edit) is also protected (trigger 1/2); removing one of two overlapping template inheritances from a composed role is protected on the REMOVE path (trigger 3); gaining a read-only template inheritance never downgrades an existing full grant from another active template (trigger 4, most-permissive-wins); and removing the template that justified a previously-widened access level correctly downgrades the row to the remaining template's level instead of staying stuck at full (trigger 4, `InheritedFrom` bookkeeping). Uses the real Finance/Sales system templates (not throwaway roles) plus one confirmed-unused window (`AD_Window_ID = 100`) for the shared grant, so it is independent of whatever the templates' own real grants happen to be. |
| `UserRoleCompositionServiceRealAccessControlIntegrationTest` (B5, ETP-4906) | 228 | Real-DB proof (3 tests, `WeldBaseTest`) that `WindowAccessOverlapCorruptionGuard`'s protection produces the CORRECT effective access outcome, not just a crash-free one, against real ETP-4878 seed-data templates: a Sales-only composed role has no access to Purchase Invoice; a Purchasing-only composed role has no access to Sales Invoice; and a Sales-only role is read-only on "Categoría del producto", then adding Finance upgrades it to full (most-permissive-wins) — the same scenario §8d's four outcomes (no-access ×2, read-only, full) are meant to cover end-to-end. |
| `UserRoleCompositionServiceOverlapReverificationTest` | 275 | QA (Sentinel) independent re-verification (3 tests) of the same overlap fix, deliberately NOT reusing the fix author's own integration test: 3 simultaneously-overlapping templates (Finance/Sales/Purchasing on a shared window) resolve to most-permissive-wins with the "winner" (Purchasing, full) in the middle of the composition order — ruling out a pairwise-only fix that only checks the newest template against the immediately-preceding state; and two cases seeded with the REAL ETP-4878 matrix's own access levels (not the synthetic window `100`) — Sales (full) + Inventory (read-only) on Contactos resolves to full, and Sales + Purchasing both read-only on Categoría del producto stays read-only (confirms the fix does not spuriously promote a window to full just because 2+ templates share it). Also closes a data point the original QA report got wrong: `ad_window_access_un_key` is a plain `CREATE UNIQUE INDEX` on `(ad_role_id, ad_window_id)`, invisible to a `pg_constraint`-only query — Sales already had a live pre-existing row for Contactos, so this suite seeds only the missing side instead of inserting a duplicate. |
| `SFAssignUserRolesTest` | 278 | Unit test (8 tests) proving the webhook wires parameters/results/errors correctly, with `UserRoleCompositionService` itself intercepted via `mockConstruction` (its real behavior is the integration test's job): access gate (no role / restricted role denied without constructing the service), the happy path (admin composes, parses a whitespace/empty-entry-noisy `TemplateRoleIds` CSV, returns the assignment summary), missing `UserId` rejected before construction, an absent `TemplateRoleIds` parameter resolving to an empty (not `null`) list meaning "revoke all", a domain `OBException` folding into a `success:false` HTTP-200 result rather than the bridge's `error`/500 path, an unexpected `RuntimeException` surfacing as the bridge's `error` field instead, and the REVIEW cycle 1 regression proving the webhook actually forwards its already-resolved `currentRole` through to `assignTemplateRoles`'s 3-arg overload — the exact wiring the tenant-boundary check depends on. |
| `SFUserRoleAssignmentsTest` (ETP-4906) | -- | Unit test mirroring `SFAssignUserRolesTest`'s `mockConstruction` convention for §8e's read endpoint: access gate denies with the mode-appropriate empty shape (bulk `{"assignments":{}}` with no `UserId`, single `{"userId":...,"templateRoleIds":[]}` with one) without constructing the service; bulk mode returns every user's assignments keyed by id, scoped to `currentRole.getClient().getId()`; single mode returns one user's ids and proves `currentRole` is forwarded into the boundary-checking overload (mirrors `SFAssignUserRolesTest`'s own forwarding regression); a cross-tenant read attempt and an unknown-user-id `OBException` both fold into the single-mode empty shape rather than the bridge's `error`/500 path; an unexpected `RuntimeException` still surfaces as `error`. |
| `SFSystemRoleTemplatesTest` (ETP-4906) | -- | Unit test (12 tests) mirroring `SFRolesOverviewTest`'s structure for §8f's endpoint: admin/client-admin access gate (no role, restricted role, System Administrator, client-admin — all resolved without the caller's own client ever appearing in any stub); roles resolved via `OBDal.get(Role.class, id)` against the 4 fixed `SystemRoleTemplates` ids rather than a client-scoped `Role` criteria; response omits `userCount`/`isClientAdmin` entirely; Finance/Sales/Purchasing/Inventory ordering; a template id resolving to `null` or to an inactive `Role` is skipped gracefully rather than erroring; GO-window intersection (native-only windows excluded) and tier resolution (full/read-only), mirroring `SFRolesOverview`'s identical logic; exception handling. |

Tests are located in `src-test/src/com/etendoerp/go/schemaforge/` (including its `webhooks/`
subpackage, e.g. `SFAssignUserRolesTest`/`SFUserRoleAssignmentsTest`/`SFSystemRoleTemplatesTest`).
The `NeoPseudoSpecDispatcher` routing for `userroleassignments` and `systemroletemplates` is
covered by `NeoPseudoSpecDispatcherTest` (same package), mirroring its existing per-endpoint
dispatch/method-not-allowed test pairs. The `AD_Role`-templates/composition classes —
`UserRoleCompositionServiceTest`, `UserRoleCompositionServiceIntegrationTest`,
`UserRoleCompositionServiceOverlapIntegrationTest`,
`UserRoleCompositionServiceOverlapReverificationTest`,
`UserRoleCompositionServiceRealAccessControlIntegrationTest`, and `TemplateRoleWindowAccessTest` —
are the exception, living under `src-test/src/com/etendoerp/go/roles/` alongside the
`com.etendoerp.go.roles` production classes they cover, which also includes
`WindowAccessOverlapCorruptionGuard` itself (§8d) — it has no dedicated test class of its own and
is exercised entirely through `UserRoleCompositionServiceOverlapIntegrationTest`/
`UserRoleCompositionServiceRealAccessControlIntegrationTest` above.

---

## 10. Future Considerations

**Granular override registry.** The current hook mechanism uses a single `javaQualifier` on the entity level. A dedicated override table (per-method, per-entity granularity) would allow more precise hook targeting without requiring a custom handler to inspect the HTTP method internally.

**Cascade validation filters for selectors.** Selectors currently query all valid values without considering dependent field constraints. A validation rule table would allow defining cascading filters (e.g., filtering products by the selected product category).

**PATCH method in Etendo core.** PATCH is handled via a `service()` override that intercepts the method string and routes it to the PUT handler internally. Native PATCH support in the Etendo servlet infrastructure would allow true partial-update semantics.

**OpenAPI auto-generation.** Specs contain enough metadata (entities, fields, methods, selectors) to auto-generate OpenAPI 3.0 documents. This would enable client SDK generation and interactive API documentation.

**Callout endpoints.** Etendo callouts (field-change triggers) are not exposed through the API. A callout endpoint would allow clients to request server-side field recalculations when a field value changes.

**Custom HQL selectors.** OBUISEL selectors with `isCustomQuery = true` are fully supported. The `executeCustomHqlQuery()` method handles custom HQL with org filtering, validation rules, search across searchable properties, and pagination.
