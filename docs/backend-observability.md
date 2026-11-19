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
- `backend_goods_shipment_completed` (ETP-5908)
- `backend_bank_bounce_pair_decided` (ETP-5581)
- `backend_bank_bounce_pair_restored` (ETP-5581)
- `backend_bank_bounce_suggestions_applied` (ETP-5581)
- `backend_portal_document_sent` (ETP-6209)
- `backend_portal_document_downloaded` (ETP-6209)
- `backend_quotation_decided` (ETP-6209)

### Added to every event by the Mixpanel sink (ETP-5581)

`MixpanelNeoTelemetrySink` adds two properties to **every** backend event it submits, after the
caller's properties (neither key is on the allowlist, so a caller can never set or spoof them):

| Property | Value |
|---|---|
| `environment` | the deployment: `etendo.go.mixpanel.environment` / `ETGO_MIXPANEL_ENVIRONMENT` when set, else the deployment's `DD_ENV` (Datadog agent property `dd.env`; the ECS tasks already declare `production`, `experimental`, `staging`), else `unknown`. Lower-cased, with Datadog's aliases folded (`prod` → `production`, `stage` → `staging`), so a KPI collector can filter `environment = production` |
| `$insert_id` | a random 32-hex id per emission (`NeoTelemetryEvent.getInsertId()`), which Mixpanel uses to drop a duplicate submission of the same event. It identifies the emission, never a record |

The log sink does not print them.

### Common identity on every event (ETP-6228)

Platform's agreement of 2026-11-02: the sink also adds the common identity, last, so a caller
property can never set or replace it. Every id is an internal identifier (no name or email); a part
that cannot be resolved is left out, never guessed:

| Property | Value |
|---|---|
| `ad_user_id` | the `AD_User_ID` of the session (or of the row, for MCP usage) |
| `ad_client_id` | the `AD_Client_ID` the event happened under; same value as the SPA's |
| `account_id` | transition: the former name of `ad_client_id`, same value, so saved reports keep working. Drop it once they move |
| `etgo_account_id` | the `ETGO_Account` that **owns** the client (the account of its flagged owner user, `OwnerSupport.findOwnerUserId`), not the account of whoever is signed in. Absent for a client with no owner flag |
| `tenant_type` | `demo` or `productive`, from the environment lifecycle (the source of `environmentType`) |

`NeoTelemetryIdentity` is resolved once per event, from one client, when the event is built, by
`RuntimeNeoTelemetryIdentityResolver`: the user and client come from the explicit arguments or the
session (`OBContext`; the session's user only for the session's own client, never another tenant's),
and `etgo_account_id` and `tenant_type` are looked up together for that client, cached for 60 s
(at most 256 clients, least recently used out first; the owner account also sits in the
`OwnerAccountResolver` 60 s cache, so a transfer shows up within two minutes).
The lookup runs in its own DAL session through `IsolatedDalRead` (ETP-6386), never on the caller's
session and never on a sender thread: a failed query cannot poison a request's transaction, a
sender thread never opens a session nothing closes, and the read ends with a rollback, so telemetry
never writes (the tenant type can insert an `AD_Preference`; the rollback discards it). It waits at
most 5 s; on a timeout or any failure the event goes out with `ad_user_id` and `ad_client_id` but
without `etgo_account_id` and `tenant_type`. The facts are not cached, but the client is skipped for
30 s (a negative cache, also bounded to 256 clients) so a slow database does not make every event
wait 5 s; after that the next event retries. The isolated read shares its 4 workers with the
reports (a timed-out read is cancelled and removed from the queue). The cache lock is not held
during the lookup. System context (client or
user `0`) or no session means no identity: events from a scheduler or an anonymous endpoint go out
without the ids.

Emitters that send from a background thread (`BankBounceKpiTelemetry`, `ContactTypeTelemetry`,
`AccountingKpiTelemetry`, `GoodsShipmentTelemetry`, `InvoiceInboxTelemetry`, `PortalKpiTelemetry`)
capture the identity on the calling thread through the service (`NeoTelemetryService.captureIdentity`,
so it uses the service's own resolver) and hand it to the sender (`NeoTelemetryIdentity.runWith`);
`McpSessionManager.executeInContext` captures it for the call's client and user on the request
thread, while the call's context and DAL session (owned by `DalRequestFilter`) are live, and the row
carries it to the writer thread, which has no session and never touches `OBDal`. A new emitter
must capture the identity on the request thread, at its public entry point (never inside an
after-commit callback or on the sender) and hand it over with `runWith`; the resolver itself never
touches the caller's or a sender's session. Portal customer-triggered events run in System context
and so carry no `ad_user_id`; app-side ones carry the signed-in user's.

The same owner account is exposed to the SPA as `environment.etgoAccountId` in `GET /sws/go/session`
(`OwnerAccountResolver`), so both sides send one `etgo_account_id`.

**Open items.** PM confirmation of `ad_user_id` under the ETP-5791 legal rule (no business record id
of any kind) is pending. The backend `distinct_id` stays the fixed per-deployment value; the proposal (SHA-256
of `ad_user_id` with a per-deployment secret salt) is pending sign-off and is not shipped. Platform
must still name the `ETGO_Account` lookup table's test-account flag in Mixpanel.

`backend_write_operation_completed` is emitted by NEO CRUD for write methods
only: `POST`, `PUT`, `PATCH`, and `DELETE`. `GET` requests are not emitted to
avoid high-volume read telemetry.

## Payload Safety

Payloads are allowlisted before reaching the sink.

Allowed low-cardinality keys:

- `account_id` — the tenant (`AD_Client_ID`); the web client sends the same key. No business
  record id of any kind (contacts, documents, ...) — legal rule ETP-5791; the internal identity ids
  of the ETP-6228 section above are allowed. PM confirmation of `ad_user_id` under that rule is
  pending.
- `category`
- `change_origin`
- `change_trigger`
- `channel`
- `confidence` (ETP-5581)
- `decision` (ETP-5581)
- `detection_signal` (ETP-5581)
- `internal` (ETP-6230) — boolean: the tenant owner's e-mail domain is internal (see *Accounting Calendar KPIs*)
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

## Accounting Calendar KPIs (ETP-6230)

`AccountingKpiTelemetry` (`schemaforge/telemetry`) emits four events for the accounting-calendar
KPIs (ETP-5577). All carry `account_id` (tenant `AD_Client_ID`) and `internal`; nothing else
identifies anybody.

| Event | Emitted | Once? | Properties |
|---|---|---|---|
| `backend_productive_environment_created` | right after the onboarding commit (`EtendoGoJwtServlet`), only when `preparation.paidUpgrade` | by construction (a paid upgrade is a new tenant) | - |
| `backend_calendar_review_completed` | Primeros pasos confirm / regenerate (after its commit) and a successful manual **open** in `PeriodOpenCloseHandler` | once per tenant **per kind** | `type` (`confirm`, `modify` for regenerate), `source` (`first_steps`, `calendar_window` REST, `mcp`, `batch`) |
| `backend_first_document_processed` | outcome probe after a successful write | once per tenant | `operation` (`post` when the request's action was `post`, else `complete`), `source`, `entity`, `specName` |
| `backend_period_refused` | every refusal keyed `PeriodNotAvailable` / `PeriodNotValid` | no | `operation`, `source`, `errorCode`, `entity`, `specName` |

`source` for the last two: `spa` (any REST call that is not a batch), `mcp`, `batch` (REST batch,
MCP `etendo_batch`, MCP `etendo_action_batch`, async batch rows). One rule for every batch. The
manual-open review derives its `source` from the write channel instead; see Limitations.

**Population.** Review, first-document and signup events only for tenants carrying
`ETGO_CalendarReviewRequired` (productive tenants onboarded after ETP-5577; permanent marker; the
signup event is emitted for every paid onboarding). `backend_period_refused` goes out for every
tenant. Follow-up: `tenant_type` when ETP-6072 lands; `ad_client_id` when ETP-6228 lands.

**Threading.** The hooks only *enqueue* `{clientId, source, entity, operation hint}` (REST:
`NeoServlet` / `NeoPseudoSpecDispatcher`; MCP: `McpAccountingKpiObserver`, called from
`McpServlet.recordToolCall` *before* the `McpUsageLogger.isEnabled()` early return, so the KPI does
not depend on the usage-log switch; async batch: the `BatchJobService` worker). One daemon sender
(`accounting-kpi-telemetry`, queue 1000, drop when full) then does gate, probe, emit and marker on
its **own** connection from `ExternalConnectionPool` (like `McpUsageLogger`): zero SQL on the
business connection, nothing a failure can propagate to the request. Errors are logged at debug
and forgotten.

**Probe cost control.** The probe scans seven tables, six of which have no `ad_client_id`-leading index, so it is
bounded: at most **one probe per tenant per 60 s** (writes inside the window are coalesced and
the next probe after it sees them), and a tenant found *not* review-required is skipped for 10
minutes (a TTL, not permanent, because the marker can be set later). The probe also runs about
**5 s after** the write is queued, not at once: the request's transaction is committed by
`DalRequestFilter` after the hook returns, and the probe runs on its own connection, so it can only
see committed rows. If the commit takes longer than the delay, that document is found by the
tenant's next probe (see Limitations). Refusal and review emits are immediate.
*Open check:* run `EXPLAIN (ANALYZE)` of the probe query on the production read replica for the
largest tenants before relying on the 60 s interval.

**Markers** are client preferences, written by the sender after the event is emitted:
`ETGO_KpiReviewFirstStepsSent`, `ETGO_KpiReviewManualOpenSent`, `ETGO_KpiFirstDocSent` (no AD
change). A lost marker can only cost a duplicate event; reports count distinct `account_id`.
Tenants whose first document was reported are also kept in a positive-only in-memory set.

**First-document probe.** Is any document of the tenant `Processed='Y' OR Posted='Y'` in
`C_Invoice`, `M_InOut`, `FIN_Payment`, `GL_Journal`, `M_Movement`, `M_Inventory`,
`FIN_BankStatement` (all have both columns)? The list is the KPI's definition of "document" and
lives in the telemetry class; no hook branches on the entity a request touched. Orders are
excluded: `C_Order_Post` has no period check.

**`internal`.** `true` when the owner's e-mail domain is in the comma-separated list from system
property `etendo.go.kpi.internal.email.domains` / env `ETGO_KPI_INTERNAL_EMAIL_DOMAINS` (default
`etendo.software,etendo.ai,smfconsulting.es`). The owner is the earliest-created active `AD_User`
of the tenant with an e-mail. The sender reads it, reduces it to a boolean and caches it per tenant (`true` for good, `false` for 1 hour, since a tenant with no e-mail user may get one);
the address is never sent. Reports filter `internal != true`. Interim until the platform's
test-account mark exists.

**Refusal detection.** REST: `NeoServlet.writeResponse`, status >= 400, `messageKeys` flat, under
`error`, or under `error.detail.error` (batch envelope). MCP: the tool answer's JSON text (or, on a
prose error, `recognizeMessageKeys`), one event per failed item for `etendo_action_batch`;
`etendo_job_status` / `etendo_job_cancel` are skipped (they re-render old rows on every poll).
Async batch rows are observed in the worker as each row finishes.

**Limitations.**
- *Late detection:* a document completed or posted outside a request (async batch worker, Etendo
  Classic, background posting) is only seen on the tenant's next NEO write, and carries that
  write's `source`/`operation`. The probe is also asynchronous, so it can lag the commit by one
  write.
- `operation` is derived from the request's *action* name (`post`, else `complete`; a plain CRUD
  write with no action is `complete`), not from which flag the data shows. For
  `backend_period_refused` it is the action name, else the HTTP method or MCP verb.
- *Probe lag:* the probe is delayed ~5 s and throttled to one per tenant per 60 s, so the first
  document can be reported up to a minute (or, if the tenant stops writing, one write) late, and a
  commit slower than the delay is seen on the next probe.
- `manual_open` reviews are queued with `NeoBatchScope.runAfterCommit`: inside a batch scope (REST
  `/sws/neo/batch`, MCP `etendo_batch`, sync or async) they are sent after the batch commits and
  dropped on rollback, so a rolled-back open leaves neither an event nor the permanent marker.
  Outside a batch scope `runAfterCommit` runs at once, i.e. before `DalRequestFilter` commits the
  request, so a commit that then fails costs one stray event. The review markers are shared across
  channels (REST and MCP).
- *`source` of a manual open* is taken from the write channel, not from the tool: a channel ending
  in `_batch` (`rest_batch`, `mcp_batch`) gives `batch`, a channel starting with `mcp` gives `mcp`,
  anything else gives `calendar_window`. `etendo_action_batch` opens **no** `NeoBatchScope`, so a
  manual open made through it runs on the plain `mcp` channel and reports `mcp`, not `batch`. This
  differs from `backend_first_document_processed` / `backend_period_refused`, which the MCP observer
  labels `batch` for `etendo_action_batch` and `etendo_batch` alike (by tool name).
- Not counted: opens by document category (process 168), anything done in Etendo Classic, a
  refusal that only exists as prose in an HTTP >= 500 answer (not recognised, as in the REST
  refusal identification).
- Tenants that were productive before this deploy and already have documents emit one stray
  first-document event on their next write but have no signup event, so they fall outside every KPI.
- A completion marked by `documentStatus` written raw on create (ETP-6240) is not `Processed`, so
  it is not detected, which is the right answer.

## Goods Shipment Completion KPI (ETP-5908)

`GoodsShipmentTelemetry` (`schemaforge/handlers`), called by `GoodsShipmentHeaderHandler`, emits
`backend_goods_shipment_completed` when a **sales** goods shipment is completed through the NEO
`documentAction` (REST single, REST batch or MCP). It feeds the *active client* definition of the
Movements-tab KPIs: a client with at least 5 completed sales shipments in the Monday-Sunday week.

| Property | Value |
|---|---|
| `account_id` | tenant `AD_Client_ID` |
| `flag_enabled` | `product-movements-tab` as evaluated for the request's account (`ProductMovementsPolicy.isEnabled()`); `false` when the evaluation fails |

**Fires** once for a request that moves the shipment from any status other than `CO` to `CO`, for
a shipment with `IsSOTrx='Y'` whose document type is not a return (`C_DocType.IsReturn='N'`, the
same rule as the `goods-shipment` bucket of `ProductTransactionsHandler`). A cloned shipment
(`CloneShipmentHook`) is created as a draft and fires when it is completed through the same action.

**Does not fire** for a draft, a void, a failed or rejected completion (HTTP error, or the stored
status is not `CO`), a re-run of the action on an already completed document, a customer return
(`IsReturn='Y'`), a purchase receipt (`IsSOTrx='N'`), nor for a completion done outside the NEO
action (SQL data-fixes, Classic window). Reactivating a completed shipment and completing it again
is a new completion and fires again.

**Only after commit, no retries.** Same mechanism as the Contact Type KPIs: the post-hook hands the
event to `NeoBatchScope.runAfterCommit` (queued until the batch commits inside `/sws/neo/batch` and
`etendo_batch`), sending runs on one bounded background thread (`goods-shipment-kpi-telemetry`,
queue 1000, drop when full), each event is sent once, and the Mixpanel sink adds `$insert_id` and
`environment`. The status reads run under a JDBC savepoint, rolled back on failure, so a failing read cannot
abort the PostgreSQL transaction of the completion; a failure in the background send is caught and
only logged. Cost on the request thread: two single-row reads per completion plus the
`product-movements-tab` evaluation (account lookup and flag resolution), which is memoized per
tenant for the duration of a batch (`NeoBatchScope.memoize`; the flag cannot change inside one
batch) and evaluated once per completion outside a batch. No record identifier,
document number or user data is sent.

**Not implemented:** the common properties agreed on 2026-11-02 (`tenant_type`, `ad_user_id`,
`ad_client_id`, `etgo_account_id`) are not sent by this event; they are blocked on ETP-6228.

## Customer Portal KPIs (ETP-6209)

`PortalKpiTelemetry` (`com.etendoerp.go.portal`) emits the three events behind the PO's customer
portal metrics (GitHub etendosoftware/etendo-ai#22, *Métricas de éxito*). Every event carries only
allowlisted keys; no new key was added to the allowlist.

| Event | Properties | Fires |
|---|---|---|
| `backend_portal_document_sent` | `account_id`, `type` | once per document: the first time a portal link of an email **send** (`LINK_ORIGIN = SEND`) is confirmed delivered and no other sent `SEND` link of the same record exists. Renewals, decision links and re-sends of the same document never fire |
| `backend_portal_document_downloaded` | `account_id`, `type` | once per document: the first successful portal `/pdf` stream of a document that was sent with a portal link. "First" is the atomic stamp `ETGO_PORTAL_LINK.FIRST_DOWNLOADED` on the document's first sent `SEND` link — only the request whose `UPDATE … WHERE first_downloaded IS NULL` set it emits, so concurrent downloads emit once. A document never sent with a link emits nothing: it is outside the KPI's denominator |
| `backend_quotation_decided` | `account_id`, `decision` (`accepted`, `rejected`), `source` (`portal`, `app`) | `portal`: a portal decision applied by its confirmation (`appliedNow`). `app`: a rejection (`RejectQuotationHandler#reject`, so also MCP and `DocAction = RJ`), or a conversion into an order (`Convertquotation`) or an invoice (`createDraftInvoice` on a quotation) — **not** when the quotation was `ETGO_AC`, i.e. already accepted from the portal and counted then |

`type` is the portal document kind in lower case: `invoice`, `quotation`, `order`, `shipment`. A
credit note is an `invoice`: the portal kinds do not tell them apart, and the PO's invoice metric
counts every document of the invoice kind. App downloads are out of scope.

**Only after commit, once, never in the way.** The portal sites run after their own commit. An
app decision registers its event on the write's transaction (a Hibernate synchronization: sent on
`STATUS_COMMITTED`, never on a rollback), or on the batch's commit inside `/sws/neo/batch` and
`etendo_batch` (`NeoBatchScope.runAfterCommit`). Sending runs on one bounded background thread
(`portal-kpi-telemetry`, queue 1000, drop when full), once, no retry; the Mixpanel sink adds
`$insert_id` and `environment`. The download stamp runs after the PDF was streamed, so it never
delays the file; any failure is logged and swallowed. The usage events `portal.*` (`ETGO_USAGE_EVENT`)
are unchanged.

**Mixpanel recipes** (filter `environment = production`):

- **Primary — invoices downloaded from the portal:** unique-per-event count of
  `backend_portal_document_downloaded` where `type = invoice` ÷ count of
  `backend_portal_document_sent` where `type = invoice`, same period (target ≥ 30 % at 3 months).
  Monthly absolute: the numerator by month. Both events fire once per document, so plain event
  counts are document counts.
- **Secondary — quotations answered from the portal:** count of `backend_quotation_decided` where
  `source = portal`, broken down by `decision`, ÷ count of `backend_portal_document_sent` where
  `type = quotation` (target ≥ 20 %). Manual decisions (`source = app`) are excluded from the
  metric and kept for comparison.
- Per tenant: break down by `account_id`. The events carry no record id, so a document sent in one
  month and downloaded or answered in the next falls in different periods; read the ratio over
  windows long enough (a quarter) for that lag to wash out.

**Not implemented:** the common properties agreed on 2026-11-02 (`tenant_type`, `ad_user_id`,
`ad_client_id`, `etgo_account_id`), blocked on ETP-6228, as for the other KPI blocks.

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
  the key of a pair applied in the same call is ignored, never an error. An agent (MCP) cannot
  send `bounceDecisions` (it is not in the agent contract). Instead, **when its call applied at
  least one `bouncePair` group**, every detected **preselected** pair (`high`, never ambiguous) it
  left out — not among its groups, and with no line taken by any group of the same call, transaction
  groups included — is an `unchecked` decision (surface `modal`, channel `mcp`), counted in the
  summary (`pairs_shown`, `pairs_unchecked`, `preselection_changed`). An MCP call that applied no
  bounce-pair group did not look at the bounces and sends no bounce event at all. The SPA path does
  not infer anything: it reports what it showed in `bounceDecisions`.
- **`discardPair` (surface `panel`, UI or MCP)**: `discarded` after the discard; `failed` on a 409.
- **One rule for `failed`, modal and panel:** every 409 refusal of a pair is `failed`; it carries
  the proposal properties (`detection_signal` … `chosen_among_alternatives`) when the action's
  detection sees the pair, and only `account_id`, `channel`, `flag_enabled`, `decision`, `surface`
  when it does not (e.g. a line reconciled meanwhile). A malformed request (400 / 404) emits
  nothing. `failed` never counts in the KPI.
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
| `preselected` | `confidence == "high"`: the pair came preselected, so the KPI can subtract it from the preselected discards |
| `days_since_decided` | **UTC calendar days** between `EM_ETGO_Bounce_Discarded` and the restore (a discard at 23:59 UTC restored at 00:01 UTC is 1), computed in the backend; the collector subtracts it from the restore's UTC day to get the decision's UTC day. Never negative |
| `channel`, `flag_enabled` | as above |

`backend_bank_bounce_suggestions_applied` — once per `applySuggestions` that decided at least one
bounce pair (applied or reported in `bounceDecisions`).

| Property | Value |
|---|---|
| `pairs_shown` | pairs decided in the call (every decision event above, `failed` included) |
| `pairs_discarded`, `pairs_unchecked`, `pairs_sent_to_panel` | per decision (`failed` = shown − the three) |
| `preselection_changed` | a preselected pair was unchecked or sent to the panel, or a non-preselected pair was applied |
| `account_id`, `channel`, `flag_enabled` | as above |

**Known gap — a discard saved before a later failure of the same call.** `applySuggestions`
discards its bounce pairs first, then matches the transaction groups; Core's matching services
commit mid-flow. If the final `processReconciliation` then fails, the call answers an error and
`finish` is never reached, so the bounce discards already committed get **no** `decided` event
(and no summary). Rare; a later `restorePair` of such a pair then sends a `restored` with no
matching decision, which can only lower the KPI's `P_discarded − P_restored` for that day.

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
