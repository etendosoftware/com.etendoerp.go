# AEAT Modelo 303 electronic submission — `POST /neo/fiscal303/submit`

**Ticket:** ETP-4456 Phase 2. Reuses Classic's already-built (Phase 1) AEAT telematic
presentation machinery — `org.openbravo.module.aeat303.es` — directly from Etendo Go's
`fiscal-models` window, no reimplementation of the AEAT protocol.

Full design rationale, decision log, and the AEAT protocol details (request/response JSON
shapes, endpoints, the JSON-protocol migration from the 2019-era form-urlencoded protocol) live
in the plan file, not duplicated here:
`../../../schema_forge/docs/plans/2026-07-15-ETP-4456-aeat-303-electronic-submission.md`
(see "⚠️ Protocol update" section for the AEAT wire-protocol reference).

Implementation: `src/com/etendoerp/go/schemaforge/Fiscal303BoxesHandler.java` (`handleSubmit` and
its private helpers — kept in this file rather than a sibling class, since the submission flow
reuses `resolveTaxReport`/`resolveAcctSchema`/`resolvePeriods`/`resolveDeclType` this handler
already has). Tests: `src-test/src/com/etendoerp/go/schemaforge/Fiscal303SubmitHandlerTest.java`
(29 tests) + `Fiscal303BoxesHandlerTest.java` (71 tests, regression guard for the rest of the
handler) — both green.

## Contract

### Request

```
POST /neo/fiscal303/submit?year=<YYYY>&period=<1T..4T|01..12>&tipo=<C|I|V|U|G|N>&id=<declId>
Content-Type: application/json
```

| Query param | Required | Meaning |
|---|---|---|
| `year` | yes | Fiscal year, same convention as `/fiscal303/generate`/`/fiscal303/boxes` |
| `period` | yes | Period (quarterly `1T`..`4T` or monthly), same convention as sibling entities |
| `tipo` | yes | AEAT declaration-type letter code the frontend already computes (`C`/`I`/`V`/`U`/`G`, default `N` for zero-result) |
| `id` | yes | `ETGO_Fiscal_Decl` id of the declaration being submitted — resolved and ownership-checked against the current client/org before anything else runs |

Body (JSON):

```json
{ "testMode": false, "idi": "ES", "nrc": "", "presenterNif": "B20868352", "presenterName": "F&B España, S.A" }
```

| Field | Required | Notes |
|---|---|---|
| `testMode` | no (default `false`) | `true` routes to ServValiDos (validation only, no cert, no declaration-record persistence — see "Persistence" below, which now also covers the test-mode PDF attach); `false` routes to production `PresBasicaDos` |
| `idi` | no (default `ES`) | Justificante language: `ES`/`EN`/`CA`/`GL`/`VA` |
| `nrc` | no | Only forwarded to AEAT when the declaration type is `I` (ingreso) — see `resolveNrcForSubmission` below; ignored for any other type even if present in the body |
| `presenterNif` / `presenterName` | **required for production**, ignored in test mode | NIF/name of the certificate holder — AEAT's Firma No Criptográfica verifies these against the certificate. The frontend defaults them from the same org-identification data (`orgIdent`) already used for file generation, but they're editable in `AeatSubmitFlow`'s confirm screen in case the certificate holder differs from the declarant |

The `.303` file content itself is **never accepted from the client** — `handleSubmit` always
regenerates it server-side via the same reflective `OBTL_TaxReport_I.generateElectronicFile(...)`
call `handleGenerate` uses for manual downloads (`generateElectronicFile`, extracted as a
package-private method precisely so both entry points share it and a submission always reflects
current DB state, exactly like a fresh "Generar fichero").

### Response

`200 OK` (submission reached the AEAT, successfully or not):

```json
{
  "status": "SUCCESS",
  "testMode": false,
  "csv": "...",
  "presentationDate": "...",
  "registryNumber": "...",
  "justificanteNumber": "...",
  "pdfBase64": "<base64 or null>",
  "pdfDownloadFailed": false,
  "errors": [],
  "warnings": [],
  "declarationData": {
    "nif": "...", "businessName": "...", "fiscalYear": "...", "period": "...",
    "declarationType": "...", "resultAmount": "...", "iban": "..."
  }
}
```

`status` is one of:

| Value | Meaning |
|---|---|
| `SUCCESS` | Production submission accepted by the AEAT |
| `TEST_SUCCESS` | ServValiDos validation accepted (declaration NOT filed — `DeclarationStatus`/`DeclarationFileName` untouched; the returned PDF is now still attached under a `TEST-`-prefixed filename, see "Persistence" below) |
| `ERROR` | Either a pre-flight failure (see `errorCode` table) or an AEAT-side rejection/error |

`declarationData` is always populated (from `AEAT303DeclarationDataExtractor.extract(fileContent)`
— parsed from the freshly-generated file, not from client input) regardless of outcome.
`pdfBase64` is `null` when the result carries no PDF bytes (e.g. `pdfDownloadFailed=true`, or an
AEAT-side error). `errors`/`warnings` are the raw AEAT-reported strings (`respuesta.errores` /
`avisos`), passed through unmodified — no re-wording.

Non-200 pre-flight failures use `buildFailureJson(testMode, errorCode, message)`, same overall
shape minus `declarationData`/`csv`/etc:

```json
{ "status": "ERROR", "testMode": false, "errorCode": "NO_CERTIFICATE", "errors": ["..."], "warnings": [] }
```

| `errorCode` | HTTP status | Fires when | Notes |
|---|---|---|---|
| (none — plain `sendError`, no JSON body) | `400` | `id` query param missing/blank | Not a `buildFailureJson` shape — a bare `sendError`, matches the sibling entities' convention |
| (none — plain `sendError`, no JSON body) | `404` | `id` doesn't resolve to a declaration belonging to the current client/org (`belongsTo`) | Same as above — no JSON body |
| `MISSING_PRESENTER` | `400` | Production (`testMode=false`) and either `presenterNif` or `presenterName` is blank | Test mode never triggers this — presenter fields are optional there |
| `NO_CERTIFICATE` | `409` | Production and `AEAT303SubmissionService.hasOrgCertificate(org)` is false | Checked **before** constructing `AEAT303SubmissionService` for the actual submission — session-cert upload is NOT supported by this endpoint (see below) |
| `ALREADY_SUBMITTED` | `409` | Production and the declaration's `DeclarationStatus` is already `submitted_ack` | The idempotency guard (BUG-1 fix) — see dedicated section below |
| `SUBMISSION_FAILED` | `500` (file-generation failure) or `502` (AEAT call itself threw `OBException`) | File regeneration threw, or `AEAT303SubmissionService.submitProduction`/`submitValidation` threw `OBException` (e.g. connection error, unsupported-charset gate, non-JSON response) | The one case where a raised exception maps to this code; a non-`OBException` runtime exception is a known, accepted gap (see "Known gaps") |

An AEAT-side rejection that the service parses successfully (e.g. the E0100803 "double space in
razón social" case from real hands-on testing) is **not** an `errorCode` — it's a normal `200`
response with `status: "ERROR"` and the AEAT's own messages in `errors[]`. `errorCode` is reserved
for pre-flight failures that never reached the AEAT.

## Reuse pattern: direct import, not reflection

Unlike `handleGenerate`'s file generation (which resolves the report class name from an AD_COLUMN
and instantiates it reflectively, since different report years/models plug in different Java
classes), the submission path imports and calls Classic's presentation classes **directly**:

- `org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService`
- `org.openbravo.module.aeat303.es.presentation.AEAT303DeclarationDataExtractor`
- `org.openbravo.module.aeat303.es.presentation.AEAT303DeclarationData`
- `org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult`

This mirrors the precedent already established elsewhere in this same handler
(`AEAT303CalculationsHelper`, used by `handleGenerate`/`handleBoxes`) — `com.etendoerp.go`
already has `org.openbravo.module.aeat303.es` on its compile classpath, so there is no need for
reflection here; the presentation classes are just regular Java dependencies.

**Why this is safe:** `AEAT303SubmissionService` (and everything it calls —
`AEAT303SubmissionRequestBuilder`, `AEAT303ResponseParser`, `AEAT303CertificateSessionService`)
has **no dependency on `HttpServletRequest` or the HTTP session**. Its public methods
(`submitProduction(ProductionSubmissionRequest)`, `submitValidation(fileContent, fiscalYear,
period, language)`, `hasOrgCertificate(Organization)`) take only plain data (an `Organization`,
strings, an optional session-certificate object) and return a plain `AEAT303SubmissionResult`
DTO. Classic's own UI (`AEAT303PresentationServlet`) happens to call the *same* service methods
from a servlet with session state, but the service itself never reaches into that context — so
calling it from Go's stateless, single-POST `NeoServlet` request/response cycle is exactly as
valid as calling it from Classic's multi-screen popup. This was confirmed by reading the class
(see `AEAT303SubmissionService.java`), not assumed.

One real behavioral difference this reuse inherits transparently: `submitProduction` already
handles the AEAT's own two-step response shape for production (`urlPdf` in the JSON response,
followed by an automatic authenticated GET to fetch the actual PDF bytes) and its own
`pdfDownloadFailed` degradation (submission still counted successful, PDF just missing) — Go's
handler does not need to know any of that, it only reads the final `AEAT303SubmissionResult`.

## Certificate flow: no session-upload fallback here

Classic's popup flow supports two certificate sources: the organization's stored certificate
(`ETSG_Certificate` via `SifGeneralUtils`), or — if the org has none — a session-only `.p12`
upload that is used for that one submission and never persisted
(`AEAT303CertificateSessionService`).

**This endpoint only supports the first.** Production submission requires the certificate
already stored via the existing `POST /neo/certificate` (same store `fiscal-config`'s
`CertModal.jsx` writes to, through `NeoCertificateHelper` → Classic's `AddCertificateToOrg`). If
`hasOrgCertificate(org)` is false, the request is rejected with `NO_CERTIFICATE` before
`AEAT303SubmissionService` even attempts to build an SSL context.

This is a deliberate design decision, not an oversight: a stateless single-POST API has no clean
equivalent of Classic's multi-screen "upload cert → confirm → submit" flow — there is no place to
hold an uploaded-but-not-yet-submitted certificate between two separate HTTP requests without
inventing new session/temp-storage machinery. Test-mode (ServValiDos) submissions need no
certificate at all, so this restriction only ever affects production submissions.

## Idempotency guard (`ALREADY_SUBMITTED` — BUG-1 fix)

**What it blocks:** a production (`testMode=false`) submission of a declaration whose
`ETGO_Fiscal_Decl.DeclarationStatus` is already `submitted_ack` is rejected outright —
`409 Conflict`, `errorCode: "ALREADY_SUBMITTED"` — **before** `AEAT303SubmissionService` is even
constructed. This is verified in `Fiscal303SubmitHandlerTest.
testHandleSubmit_alreadySubmittedDeclaration_blocksResubmission` via Mockito's
`mockConstruction(...).constructed().isEmpty()` — the strongest available proof that no AEAT call
of any kind could have happened for this request.

Motivation: without this guard, a double-click, a network retry, or a second open tab on an
already-successfully-submitted production declaration would silently fire a **second real
submission to the live AEAT service**. Per the AEAT protocol, a genuine repeat presentation of the
same declaration must be filed as a "complementaria" (a distinct declaration type with its own
flag) — a plain resubmission is not the correct way to correct or repeat a filing.

**What it does NOT block:** test-mode (`testMode=true`) resubmissions of an already-`submitted_ack`
declaration are explicitly allowed — ServValiDos never mutates declaration status (see
"Persistence" below), so re-validating an already-submitted declaration is harmless. This is
covered by its own dedicated test
(`testHandleSubmit_testModeResubmissionOfAlreadySubmittedDeclaration_isAllowed`).

**Explicit non-goal:** this fix does **not** implement "complementaria" filing support. Filing a
legitimate correction/repeat of an already-submitted declaration is a separate, manual AEAT
process with its own declaration-type flag, deliberately out of scope here — the guard only stops
the *accidental*, unguarded resubmission path.

The frontend surfaces this with a dedicated message (`fm.aeat.error.alreadySubmitted`, both
locales) instead of dumping the generic AEAT error list, and — like `MISSING_PRESENTER` but unlike
`NO_CERTIFICATE` — shows no "go to fiscal-config" shortcut button (that stays correctly gated to
`NO_CERTIFICATE` only, since a certificate is not what's missing here).

## Persistence

**Only a successful PRODUCTION submission mutates the declaration record**
(`persistSuccessfulSubmission`): `DeclarationStatus` → `submitted_ack`, `DeclarationFileName` set
to a generated justificante filename, `FileExternal` set to `false`, saved and committed. Test-mode
submissions (success or error) and failed production submissions never touch the declaration row —
matching Classic's "test submissions leave no trace" rule.

`handleSubmit` branches on success: `testMode ? attachTestJustificante(...) :
persistSuccessfulSubmission(...)`. **`attachTestJustificante` (ETP-4456, 2026-07-28)** attaches the
AEAT-returned PDF to the declaration for a successful test-mode (`TEST_SUCCESS`) submission too —
under a distinct filename, `"TEST-justificante-303-<year>-<period>.pdf"`, so it's unambiguous in the
attachments list — by calling the same existing `attachJustificante(...)` helper `
persistSuccessfulSubmission` uses. It does **not** call any setter on the declaration and never
saves/commits it: `DeclarationStatus`/`DeclarationFileName`/`FileExternal` remain exactly as they
were before the request, preserving the "test submissions leave no trace" rule stated above at the
record level — only the attachment (a side effect of `AttachImplementationManager`, not of the
declaration entity itself) is new. See the "Justificante" tab section in
`../../../schema_forge/docs/generated-custom-windows/fiscal-models.md` and the "Phase 2.2" section
in the plan doc for the frontend wiring that surfaces this (the `receiptRefreshTick` refresh, since
test mode has no status change to key off of).

### AEAT validation errors and warnings — persisted on every attempt (`ETGO_Fiscal_Decl_Incident`, ETP-4456)

Unlike the declaration-record mutation above (production-success only), `handleSubmit` persists
BOTH the AEAT-reported error list AND warning list on **every** submission attempt — test mode and
production, success or failure alike — via
`replaceIncidents(decl, result.getErrors(), result.getWarnings())` (`AbstractFiscalHandler` →
`FiscalDeclCrudHandler#replaceIncidents`), called right after `result` is obtained, unconditionally
(not gated by `result.isSuccessful()`). `replaceIncidents` always deletes every existing
`ETGO_Fiscal_Decl_Incident` row for the declaration first, then inserts one row per entry in
`result.getErrors()` (tagged `severity = "block"`) followed by one row per entry in
`result.getWarnings()` (tagged `severity = "warn"`) — each a raw `"CODE - message"` AEAT string,
split via `FiscalDeclCrudHandler#splitAeatError`. Deduplication is applied independently per
severity group — an error and a warning sharing the exact same raw text are persisted as two
distinct rows, never collapsed. A successful attempt has both lists empty, so the declaration
simply ends up with zero incident rows — no separate success-path branch needed. Best-effort:
wrapped in its own try/catch in `handleSubmit`, logged on failure, never allowed to mask the
actual submission response already computed.

**Table `ETGO_Fiscal_Decl_Incident`:** `ETGO_Fiscal_Decl_Incident_ID` (PK, VARCHAR32) + the
standard client/org/audit columns + `ETGO_Fiscal_Decl_ID` (FK to `ETGO_Fiscal_Decl`, Java property
`fiscalDeclaration`) + `CODE` (VARCHAR, the AEAT error code, e.g. `35068` or `E010124`) + `MESSAGE`
(long text — AEAT's free-text error description) + `SEVERITY` (VARCHAR(200), added in this
increment — `"block"` for errors, `"warn"` for warnings; a row with no/blank value defaults to
`"block"` via `FiscalDeclCrudHandler#resolveSeverity`, covering rows persisted before this column
existed).

**Read endpoint:** `GET /fiscal303/incidents?id=<declId>` (also reachable as `/fiscal349/incidents`
for free — same generic table, only 303 writes to it today) → ownership-checked the same way as
`/fiscal303/declarations`, returns `{"data":[{"code","message","severity"}, ...]}`. Consumed by the
frontend's "Incidencias" tab — see the "Incidencias" tab section in
`../../../schema_forge/docs/generated-custom-windows/fiscal-models.md`.

**Semantics:** incidents are **replaced, not appended** — a second attempt with different AEAT
errors/warnings fully replaces the first attempt's rows, for both severities together. A
successful attempt (test or production) with no errors and no warnings always leaves the
declaration's incidents empty.

### Known gaps (deliberate follow-ups, not bugs)

1. **CSV / registry / justificante numbers are not persisted.** They are returned to the frontend
   in every successful response, but `ETGO_Fiscal_Decl` has no columns for them today — adding some
   would require `update.database`, which wasn't run as part of this change. A decision for later:
   add 3-4 nullable columns, or accept response-only delivery (current state).

2. ~~**PDF attachment (`attachJustificante`) is a best-effort no-op today.** `ETGO_Fiscal_Decl` has
   **no `AD_Tab` registered at all** (verified: zero rows in `AD_Tab` for its `AD_Table_ID` — it's
   a headless-only table with no Classic AD window), and `NeoAttachmentsHelper.getAttachManager()
   .upload(...)` requires a tab id to attach to.~~ — **RESOLVED (ETP-4456, 2026-07-28).** A new
   `AD_Window`/`AD_Tab` bound to `ETGO_Fiscal_Decl` now exists, so `attachJustificante`'s existing
   `NeoAttachmentsHelper.resolveTableId`/`resolveTabId` calls resolve successfully instead of
   finding nothing. No code in this handler changed — the fix is AD metadata only. This endpoint's
   own behavior around the attach step (never blocks/fails/rolls back the submission if it can't
   resolve) is unchanged and still applies as a defensive fallback. Full detail (the new
   `AD_Window`/`AD_Tab` ids, the frontend "Justificante" tab that surfaces the result, the still-
   outstanding `export.database` step, and the manual QA checklist): see the "Phase 2.1 —
   Justificante attachment fix" section in
   `../../../schema_forge/docs/plans/2026-07-15-ETP-4456-aeat-303-electronic-submission.md`.

Gap 1 above still mirrors the same "flag it, don't silently drop it" pattern used elsewhere in this
handler — see the code comment on `persistSuccessfulSubmission` for the in-source version of this
note.

## Other known, accepted gaps (not fixed, tracked in the plan)

- **BUG-2 (MEDIUM):** an unexpected non-`OBException` thrown from the AEAT call path (i.e. not
  caught by the existing `catch (OBException e)` around `submitProduction`/`submitValidation`)
  leaks the raw Java exception class name/message into the API error response body instead of the
  normal `{status, errorCode, errors[]}` shape. Same pre-existing pattern as the sibling entities
  `boxes`/`generate`/`modified` in this handler — accepted as-is; the frontend still degrades to a
  generic connection-error banner, just with a minor info leak visible in devtools.
- See the plan file for BUG-3 (frontend base64-download edge case) and the `AD_DATASET` checksum
  staleness follow-up (Classic side, Phase 1).

## Related endpoints in this window

| Method | Path | Handler |
|---|---|---|
| `GET` | `/fiscal303/boxes?year=&period=` | `Fiscal303BoxesHandler.handleBoxes` |
| `GET` | `/fiscal303/generate?year=&period=&tipo=` | `Fiscal303BoxesHandler.handleGenerate` |
| `POST` | `/fiscal303/submit?year=&period=&tipo=&id=` | `Fiscal303BoxesHandler.handleSubmit` (this doc) |
| `GET` | `/fiscal303/incidents?id=` | `FiscalDeclCrudHandler.handleIncidents` — persisted AEAT errors (ETP-4456) |
| `GET` | `/fiscal303/modified?year=&period=&since=` | `Fiscal303BoxesHandler.handleModified` |
| `POST` | `/certificate` | `NeoCertificateHelper` — certificate storage used by this endpoint's production path |

Frontend consumer: `../../../schema_forge/tools/app-shell/src/windows/custom/fiscal-models/models/303/AeatSubmitFlow.jsx`,
documented in `../../../schema_forge/docs/generated-custom-windows/fiscal-models.md`.
