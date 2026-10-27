# Backend Observability

`com.etendoerp.go` exposes backend KPI telemetry through
`NeoTelemetryService` in `com.etendoerp.go.schemaforge.telemetry`.

The service is intentionally fail-open: telemetry sink failures are logged and
swallowed so observability never blocks the business operation.

## Event Contract

Backend telemetry event names use the `backend_` prefix. Event constants live in
`NeoTelemetryEvents` and mirror the App Shell event catalog where a frontend
event exists.

Current constants:

- `backend_accounting_entry_generated`
- `backend_accounting_entry_created`
- `backend_accounting_entry_validated`
- `backend_accounting_link_validated`
- `backend_acceptance_integrity_check_completed`
- `backend_ocr_field_accuracy`
- `backend_ocr_extraction_evaluated`
- `backend_bank_match_attempted`
- `backend_bank_reconciliation_match_evaluated`
- `backend_asset_created`
- `backend_depreciation_calculation_validated`
- `backend_email_invoice_ingested`
- `backend_invoice_ingestion_completed`
- `backend_master_data_quality_evaluated`
- `backend_monthly_close_started`
- `backend_monthly_close_completed`
- `backend_role_assignment_validated`
- `backend_stock_count_reconciled`
- `backend_stock_movement_validated`
- `backend_write_operation_completed`
- `backend_contact_created` (ETP-5791)
- `backend_contact_type_changed` (ETP-5791)

`backend_write_operation_completed` is emitted by NEO CRUD for write methods
only: `POST`, `PUT`, `PATCH`, and `DELETE`. `GET` requests are not emitted to
avoid high-volume read telemetry.

## Payload Safety

Payloads are allowlisted before reaching the sink.

Allowed low-cardinality keys:

- `account_id` — the tenant (`AD_Client_ID`). The **only** identifier a backend event may carry
  (PM legal rule, ETP-5791); the web client sends the same key. No record id of any kind.
- `category`
- `change_origin`
- `change_trigger`
- `channel`
- `entity`
- `entityType`
- `flow`
- `kpiId`
- `module`
- `operation`
- `source`
- `specName`
- `status`
- `type`
- `types_source`

Allowed boolean keys (a non-boolean value is dropped):

- `critical`
- `flag_enabled`
- `is_customer`
- `is_vendor`
- `previous_is_customer`
- `previous_is_vendor`
- `supportRequested`

Allowed numeric keys:

- `accuracy`
- `attempt`
- `count`
- `correctCount`
- `days_since_created` (0–36500)
- `durationMs`
- `httpStatus`
- `position`
- `score`
- `step`
- `total`
- `value`

Numeric keys must receive finite numeric values within their configured bounds.
String and boolean values are dropped for numeric keys.

The denylist always wins. Do not pass secrets, raw URLs, document numbers,
record ids, names, labels, OAuth values, authorization values, or user-entered
free text.

## NEO Write Timing

`NeoCrudHandler` wraps write operations with `NeoTelemetryService` and emits:

```text
backend_write_operation_completed
```

Properties:

- `source`: `neo`
- `specName`: NEO spec name
- `entity`: NEO entity name
- `operation`: `create`, `update`, or `delete`
- `status`: `success` or `failed`
- `durationMs`: rounded operation duration
- `httpStatus`: response status when available

The event intentionally excludes `recordId`, request body, response body,
document numbers, labels, and backend error messages.

## Bank Reconciliation KPIs

`ReconciliationHandler` emits authoritative aggregate telemetry for the bank
reconciliation matching flow:

- `backend_bank_match_attempted`: emitted by `GET action=autoMatch` after the
  preview engine evaluates pending bank-statement lines.
- `backend_bank_reconciliation_match_evaluated`: emitted by
  `POST action=applySuggestions` after accepted suggestion groups are applied.

Properties:

- `source`: `neo`
- `kpiId`: `bank_reconciliation_match_rate`
- `module`: `bank-reconciliation`
- `entityType`: `bankStatementLine`
- `flow`: `auto-match` or `apply-suggestions`
- `total`: pending lines evaluated, or suggestion groups requested
- `count`: groups found, or groups attempted
- `correctCount`: operations proposed by preview, or groups successfully applied

The events intentionally exclude financial-account ids, statement-line ids,
transaction ids, document numbers, descriptions, partner names, and raw
statement text.

## Contact Type KPIs (ETP-5791)

`ContactTypeTelemetry` (`schemaforge/handlers`) emits two events for contacts (`C_BPartner`) written
through NEO — the `contacts/businessPartner` header (`BusinessPartnerHandler`) and the
`contacts/customer` / `contacts/vendorCreditor` tabs (`ContactTypeFlagGuardHandler`). They are
emitted whether the `contact-category-type` flag is on or off; `flag_enabled` is the segment.

**Only after commit.** The post-hook hands the event to `NeoBatchScope.runAfterCommit`: a single
REST or MCP write is already committed by core when `afterHandle()` runs, so it is sent at once;
inside `/sws/neo/batch` or `etendo_batch` (sync or async job, i.e. imports) it is queued and sent
only after the batch / row commits, so a failed import row sends nothing. A rejected write never
reaches the post-hook. Sending runs on one bounded background thread (`contact-kpi-telemetry`,
queue 1000, drop when full) so a save never waits on Mixpanel.

**No retries, no de-duplication.** `NeoTelemetryService` sends each event once, has no event id
(`$insert_id`) and swallows sink failures; none is added here.

`backend_contact_created` — a contact create was committed.

| Property | Value |
|---|---|
| `account_id` | tenant `AD_Client_ID` |
| `is_customer`, `is_vendor` | the stored types after the create |
| `types_source` | per flag `explicit` (caller sent it), `inherited` (taken from the category — flag on only), `default` (neither: AD/mandatory defaults `IsCustomer=Y`, `IsVendor=N`; always the case for an omitted flag while the flag is off, and with it on when the category defines no type); `mixed` when the two flags differ |
| `channel` | see below |
| `flag_enabled` | `contact-category-type` as evaluated for this write |

`backend_contact_type_changed` — an existing contact's `IsCustomer`/`IsVendor` actually changed
(compared stored-before vs stored-after) and the write was committed. Not sent for a write that
leaves both unchanged, for a rejected write, nor for SQL data-fixes (they bypass NEO).

| Property | Value |
|---|---|
| `account_id` | tenant `AD_Client_ID` |
| `previous_is_customer`, `previous_is_vendor` | stored types before the write |
| `is_customer`, `is_vendor` | stored types after the write |
| `days_since_created` | whole days between the contact's `Created` and now, computed in the backend |
| `change_trigger` | `category_change` when the same write moved the contact to another category, else `checkbox` |
| `change_origin` | `system` when the backend re-inherited the types from the new category (flag on, write carried neither flag), else `user` (the caller sent them — the Contacts form sends its overwritten checkboxes on a category change, so that case reads `category_change` + `user`) |
| `channel`, `flag_enabled` | as above |

`channel` is what the server can actually tell apart (`NeoWriteChannel`): `rest` (single
`/sws/neo` write — the SPA form and any other REST client are indistinguishable), `mcp` (MCP tool
call), `rest_batch` (`/sws/neo/batch`, sync or async — SPA imports and document/OCR ingestion
included, not distinguishable from each other), `mcp_batch` (`etendo_batch`, sync or job). Telling
UI from document vs OCR apart would need a client-sent marker; none exists today.

Contacts created outside NEO CRUD (demo-data transfer, onboarding sample data) emit nothing.

## Adding Authoritative Backend KPIs

Use `NeoTelemetryService.emit()` from a `NeoHandler.afterHandle()` hook only
when the backend is the authority for the fact being measured.

Example:

```java
telemetryService.emit(NeoTelemetryEvents.BACKEND_ASSET_CREATED, Map.of(
    "kpiId", "kpi_adopt_fixed_asset_from_purchase_invoice",
    "module", "fixed_assets",
    "source", "purchase_invoice",
    "entity", "asset",
    "entityType", "fixed_asset",
    "channel", "purchase_invoice",
    "operation", "create",
    "status", "success",
    "value", 1));
```

Keep window-specific decisions inside the corresponding `NeoHandler`; do not
add window-specific logic to generic services.

## Current Sink

The default sink writes redacted structured logs. A Mixpanel sink should be a
separate change after the runtime configuration contract is decided.

Required before a Mixpanel sink:

- Confirm the exact `AD_SysConfig` key names.
- Store project token and endpoint in runtime configuration, never in source.
- Add tests for disabled config, provider failures, and no-secret logging.

## SQL Integrity Jobs

`ETGO_KPI_CHECK` is not part of this change. It requires full AD model and
sourcedata registration, UUID generation through the project tooling, entity
generation, and `export.database` from the Etendo root.
