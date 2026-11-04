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
- `backend_bank_bounce_pair_decided` (ETP-5581)
- `backend_bank_bounce_pair_restored` (ETP-5581)
- `backend_bank_bounce_suggestions_applied` (ETP-5581)

### Added to every event by the Mixpanel sink (ETP-5581)

`MixpanelNeoTelemetrySink` adds two properties to **every** backend event it submits, after the
caller's properties (neither key is on the allowlist, so a caller can never set or spoof them):

| Property | Value |
|---|---|
| `environment` | the deployment: `etendo.go.mixpanel.environment` / `ETGO_MIXPANEL_ENVIRONMENT` when set, else the deployment's `DD_ENV` (Datadog agent property `dd.env`; the ECS tasks already declare `production`, `experimental`, `staging`), else `unknown`. Lower-cased, with Datadog's aliases folded (`prod` → `production`, `stage` → `staging`), so a KPI collector can filter `environment = production` |
| `$insert_id` | a random 32-hex id per emission (`NeoTelemetryEvent.getInsertId()`), which Mixpanel uses to drop a duplicate submission of the same event. It identifies the emission, never a record |

The log sink does not print them. Platform's agreement of 2026-11-02 (`etgo_account_id`,
`tenant_type`, `ad_user_id`, `ad_client_id` on every event) is **not** implemented in the backend
yet: backend events still name the tenant as `account_id` only.

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
- `confidence` (ETP-5581)
- `decision` (ETP-5581)
- `detection_signal` (ETP-5581)
- `entity`
- `entityType`
- `flow`
- `kpiId`
- `module`
- `operation`
- `source`
- `specName`
- `status`
- `surface` (ETP-5581)
- `type`
- `types_source`

Allowed boolean keys (a non-boolean value is dropped):

- `adjacent` (ETP-5581)
- `ambiguous` (ETP-5581)
- `chosen_among_alternatives` (ETP-5581)
- `critical`
- `flag_enabled`
- `is_customer`
- `is_vendor`
- `previous_is_customer`
- `preselected` (ETP-5581)
- `preselection_changed` (ETP-5581)
- `previous_is_vendor`
- `supportRequested`

Allowed numeric keys:

- `accuracy`
- `attempt`
- `count`
- `correctCount`
- `days_since_created` (0–36500)
- `days_since_decided` (0–36500, ETP-5581)
- `durationMs`
- `httpStatus`
- `pairs_shown`, `pairs_discarded`, `pairs_unchecked`, `pairs_sent_to_panel` (0–1000000, ETP-5581)
- `position`
- `score`
- `signals_count` (0–3, ETP-5581)
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

**No retries.** `NeoTelemetryService` sends each event once and swallows sink failures. Since
ETP-5581 the Mixpanel sink adds a random `$insert_id` to every event (see *Added to every event by
the Mixpanel sink*), so a duplicate submission is dropped by Mixpanel; there is still no retry.

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

## Bank Bounce-Pair KPIs (ETP-5581)

`BankBounceKpiTelemetry` (`schemaforge`) emits the events of the bounce-pair KPI (issue #15: the
acceptance rate of the bounce suggestions). **Flag-owned**: only reached with
`bank-bounce-suggestions` on (each action evaluates `BankBounceSuggestionsFlag` once; with it off the
actions answer 403 or skip the bounce groups and nothing here runs), so every event carries
`flag_enabled: true`. Best effort: building an event never throws into the action, and sending runs
on one bounded background thread (`bank-bounce-kpi-telemetry`, queue 1000, drop when full), so a
telemetry failure can never fail, slow or roll back a discard. A successful decision is handed to
`NeoBatchScope.runAfterCommit` once the discard is flushed (sent at once outside a batch, after the
batch commits inside one); a refused action rolls back and emits no `discarded`. `failed` and the
panel's `unchecked` describe no write and are sent at once.

**Legal rule.** No line id, amount, counterparty or statement text: the only identifier is
`account_id` (the account's `AD_Client_ID`).

**What was proposed** comes from the action's own detection (`BankBouncePairService.Detection`,
the same detection `autoMatch` and `pendingLines` run), never from the client: a pair the request
names is matched to the detected pair whose bounce is one of its lines and whose original, or one
of whose candidates, is the other.

`backend_bank_bounce_pair_decided` — one decision on one pair.

| Property | Value |
|---|---|
| `account_id` | tenant `AD_Client_ID` |
| `detection_signal` | primary signal as detected: `pis_link` > `return_text` > `counterparty` (the chosen candidate's for an ambiguous pair, the preferred candidate's while none is chosen); absent for a pair with none |
| `signals_count` | how many of the three signals the pair met |
| `confidence` | `high` / `review` **as proposed** (an ambiguous pair, and any pair detection did not propose, is `review`) |
| `ambiguous` | detection left the original for the user to choose |
| `adjacent` | statement adjacency chose the original (or the two candidates) |
| `preselected` | the pair came checked (proposed `high`) |
| `decision` | `discarded`, `unchecked`, `sent_to_panel`, `failed` |
| `surface` | `modal` (`applySuggestions`, also when an agent calls it) / `panel` (`discardPair`, `dismissPair`) |
| `chosen_among_alternatives` | the user discarded one candidate of an ambiguous pair (`discardPair`, or `dismissPair` naming the candidate) |
| `channel` | `mcp` for an MCP tool call or MCP batch, else `ui` (the server cannot tell the SPA from another REST client) |
| `flag_enabled` | always `true` |

When it is emitted:

- **`applySuggestions` (surface `modal`)**, once the call succeeded: one per `bouncePair` group —
  `discarded`, or `failed` when its discard was refused with a 409 (a line already reconciled,
  discarded or taken by an earlier group of the call...; a malformed group emits nothing) — then
  one per entry of the optional body key `bounceDecisions: [{groupKey, decision}]`
  (`unchecked` / `sent_to_panel`): the SPA's report of the pairs it SHOWED and did not apply.
  `groupKey` is the `autoMatch` group's; it is resolved through the call's detection, read before
  the transaction groups take their lines. An unknown key, an unknown decision, a repeated key or
  the key of a pair applied in the same call is ignored, never an error. MCP calls cannot send
  `bounceDecisions` (it is not in the agent contract), so they report the applied pairs only.
- **`discardPair` (surface `panel`, UI or MCP)**: `discarded` after the discard; `failed` on a 409
  for a pair the detection proposes (a pair it does not see emits nothing).
- **`dismissPair` (surface `panel`, UI only)**: `unchecked` — the panel's *No es un rebote*. Nothing
  is written, so the pair is proposed again next time and each dismissal counts.

A pair discarded that detection did not propose (sent through the API or the MCP) still emits
`discarded`, with the signals of its two lines, `review`, `preselected: false` and
`ambiguous: false`: every discard that `restorePair` can undo has its decision.

`backend_bank_bounce_pair_restored` — `restorePair` restored a **linked pair** (a line whose
`EM_ETGO_Bounce_Pair_Line_ID` points to a counterpart that points back). A line restored alone
(discarded on its own in Classic, or a one-sided link) emits nothing.

| Property | Value |
|---|---|
| `account_id` | tenant `AD_Client_ID` |
| `detection_signal` | stored `EM_ETGO_Bounce_Signal` (absent when null) |
| `confidence` | stored `EM_ETGO_Bounce_Confidence` (`high` only for a pair detection proposed exactly as high) |
| `days_since_decided` | whole days since `EM_ETGO_Bounce_Discarded`, computed in the backend |
| `channel`, `flag_enabled` | as above |

`backend_bank_bounce_suggestions_applied` — once per `applySuggestions` that decided at least one
bounce pair (applied or reported in `bounceDecisions`).

| Property | Value |
|---|---|
| `pairs_shown` | pairs decided in the call (every decision event above, `failed` included) |
| `pairs_discarded`, `pairs_unchecked`, `pairs_sent_to_panel` | per decision (`failed` = shown − the three) |
| `preselection_changed` | a preselected pair was unchecked or sent to the panel, or a non-preselected pair was applied |
| `account_id`, `channel`, `flag_enabled` | as above |

Example (`backend_bank_bounce_pair_decided` as submitted to Mixpanel):

```json
{"event": "backend_bank_bounce_pair_decided", "properties": {
  "token": "…", "distinct_id": "neo-backend", "time": 1793700000,
  "account_id": "4028E6C72959682B01295A070852010D", "channel": "ui", "flag_enabled": true,
  "detection_signal": "pis_link", "signals_count": 2, "confidence": "high", "ambiguous": false,
  "adjacent": false, "preselected": true, "chosen_among_alternatives": false,
  "decision": "discarded", "surface": "modal",
  "environment": "production", "$insert_id": "3f0c2b9e8d7a4c1b9e6f5a4d3c2b1a09"}}
```

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
