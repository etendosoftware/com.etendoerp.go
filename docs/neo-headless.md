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
| `/{specName}/{entityName}/selectors/{columnName}` | GET | Query selector values |
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

**PUT / PATCH update** -- `PUT|PATCH /{specName}/{entityName}/{recordId}`

Both PUT and PATCH are delegated to DataSourceServlet's PUT handler internally. PATCH is handled via a `service()` override that intercepts the PATCH method at the Servlet API level.

**DELETE** -- `DELETE /{specName}/{entityName}/{recordId}`

Delegated to DataSourceServlet's DELETE handler.

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

**Query selector values** -- `GET /{specName}/{entityName}/selectors/{columnName}`

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
| `NeoResponse.error(int, String)` | (given) | Error with `{"error": {"message": "...", "status": N}}`. |

Responses support custom headers via `withHeader(name, value)`.

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

3. **Window access control:** For window specs, the servlet checks `ADWindowAccess` for the current role. If the role does not have access to the window, the request returns `403 Forbidden`.

4. **Process access control:** For process specs and button actions, the servlet checks `ADProcessAccess` for the current role before execution. Denied requests return `403 Forbidden`.

5. **Method-level control:** Each HTTP method must be explicitly enabled on the entity record. Disabled methods return `405 Method Not Allowed`.

6. **Field-level control:** Only fields with `ISINCLUDED = 'Y'` participate in selector listings and button action discovery.

---

## 8. Testing

The module includes unit tests that run without a backend:

| Test class | Lines | Coverage |
|------------|-------|----------|
| `NeoServletPathTest` | 216 | URL path parsing: valid paths, selectors, actions, edge cases (empty path, trailing slashes, extra segments). |
| `NeoContextTest` | 148 | Builder pattern, all fields, HTTP method values, mutable `previousResult`. |
| `NeoResponseTest` | -- | Static builders (`ok`, `created`, `noContent`, `error`), custom headers. |
| `NeoServletTabFilterTest` | -- | Parent-child HQL where clause generation. |

Tests are located in `src-test/src/com/etendoerp/go/schemaforge/`.

---

## 9. Future Considerations

**Granular override registry.** The current hook mechanism uses a single `javaQualifier` on the entity level. A dedicated override table (per-method, per-entity granularity) would allow more precise hook targeting without requiring a custom handler to inspect the HTTP method internally.

**Cascade validation filters for selectors.** Selectors currently query all valid values without considering dependent field constraints. A validation rule table would allow defining cascading filters (e.g., filtering products by the selected product category).

**PATCH method in Etendo core.** PATCH is handled via a `service()` override that intercepts the method string and routes it to the PUT handler internally. Native PATCH support in the Etendo servlet infrastructure would allow true partial-update semantics.

**OpenAPI auto-generation.** Specs contain enough metadata (entities, fields, methods, selectors) to auto-generate OpenAPI 3.0 documents. This would enable client SDK generation and interactive API documentation.

**Callout endpoints.** Etendo callouts (field-change triggers) are not exposed through the API. A callout endpoint would allow clients to request server-side field recalculations when a field value changes.

**Custom HQL selectors.** OBUISEL selectors with `isCustomQuery = true` are fully supported. The `executeCustomHqlQuery()` method handles custom HQL with org filtering, validation rules, search across searchable properties, and pagination.
