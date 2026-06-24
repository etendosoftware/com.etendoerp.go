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

`backend_write_operation_completed` is emitted by NEO CRUD for write methods
only: `POST`, `PUT`, `PATCH`, and `DELETE`. `GET` requests are not emitted to
avoid high-volume read telemetry.

## Payload Safety

Payloads are allowlisted before reaching the sink.

Allowed low-cardinality keys:

- `category`
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

Allowed boolean keys:

- `critical`
- `supportRequested`

Allowed numeric keys:

- `accuracy`
- `attempt`
- `count`
- `correctCount`
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
