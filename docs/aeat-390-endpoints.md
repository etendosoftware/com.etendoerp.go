# AEAT Modelo 390 (annual VAT summary) — `/neo/fiscal390/*`

ETP-5716 — backend base of Modelo 390 in "Modelos Fiscales". Same architecture as Modelo 303
(`aeat-303-submit-endpoint.md`): a NEO built-in handler (`Fiscal390BoxesHandler`, routed by
`NeoBuiltInEndpointHandler`) on top of the classic module `org.openbravo.module.aeat390.es`.
The incident rules are NOT part of this step; the extension points they plug into are listed at
the end. The invoices (Facturas) tab is `GET /fiscal390/sources`; the AEAT telematic filing is
`POST /fiscal390/submit`; the preload from the year's Modelo 303s and the cuadre with them is
`GET /fiscal390/preload`.

## Routes

All routes require the "Modelos Fiscales" grant (Tax Report window, same gate as 303/349).

| Method | Path | Handler |
|---|---|---|
| `GET` | `/fiscal390/boxes?year=&period=0A[&<input>=…]` | box preview (`Fiscal390BoxesHandler`) |
| `GET` | `/fiscal390/generate?year=&period=0A[&<input>=…]` | TXT file (`Fiscal390GenerateSupport`) |
| `GET` | `/fiscal390/sources?year=&period=0A[&box=&type=&tax=&q=&page=&pageSize=]` | Facturas tab (`Fiscal390SourcesSupport`) |
| `GET` | `/fiscal390/preload?year=&period=0A[&monthlyReturn=Y\|N]` | 303 preload + cuadre (`Fiscal390PreloadSupport`, `Fiscal390PreloadAggregator`) |
| `POST` | `/fiscal390/submit?year=&period=0A&id=` | AEAT telematic filing (`Fiscal390SubmissionSupport`) |
| `GET` | `/fiscal390/modified?year=&period=0A&since=` | shared invoice-change probe |
| `GET/POST/PUT/DELETE` | `/fiscal390/declarations` (= `/fiscal303/declarations`) | shared `FiscalDeclCrudHandler` |
| `GET` | `/fiscal390/incidents?id=` | shared `FiscalDeclCrudHandler` |

Declarations use `model: "390"`, `period: "0A"`.

### Errors

| Status | When |
|---|---|
| `400` | `period` is not `0A`; an input is not a number (message names it); `sources` with a non-numeric `box`/`page`/`pageSize` or `pageSize` outside 0–500 |
| `404` / `405` | unknown entity / wrong method (`generate` is GET-only, `submit` POST-only) |
| `409` | `generate` when the latest declaration of the year is submitted (shared ETP-5438 guard) |
| `409 DUPLICATE_ORDINARY` | `POST /declarations` of a 2nd ordinary 390 for the same org/year — see below |
| `500` | no 390 tax report up to the year, incomplete fiscal calendar, classic validation errors (translated AD message, e.g. missing main IAE activity) |

## Annual period

`0A` maps to periods 1–12 of the fiscal year (`AbstractFiscalHandler.resolvePeriods`, constant
`ANNUAL_PERIOD`). If any month has no period the request fails with a message naming the missing
months — an annual declaration over an incomplete calendar would under-report silently. `Tn` and
month codes are unchanged.

## Tax report resolution (year fallback)

`AEAT390_<year>_Y` when it exists, otherwise the latest `AEAT390_<YYYY>_Y` with `YYYY <= year`
(`AbstractFiscalHandler.findLatestTaxReport` / `pickLatestTaxReport`, generic, used only by 390):
active rows of the org or org `0`, key parsed with `^AEAT390_(\d{4})_Y$`, the DECLARED exercise
decides (never today's date), an inactive row never shadows an active one, on a tie the org's own
row wins over org `0`. **Product decision: there is no `AEAT390_2026_Y`; 2026 resolves to
`AEAT390_2025_Y` through this fallback — it is the normal path, not an edge case.** 303/349 keep
their exact-key lookups.

The resolved key is returned as `taxReport` in the boxes payload and therefore frozen in the
submission snapshot (`ETGO_Fiscal_Decl.submitted_snapshot`) — no DDL. Once submitted, `generate`
is refused, so a submitted declaration never regenerates with another class. Reverting a
declaration to draft clears its snapshot (existing behaviour), after which it resolves again.

## User inputs

The values the user types (compensations, territory percentages, monthly results, flags such as
`ISSUPPLEMENTARY`) are named by the classic `TaxReportParameter` search keys
(`390_Report_Tax_Parameters.xml`): `LIQANUAL_COMPENSATION`, `LIQANUAL_VAT_LAW_REGULARIZATION`
[658], `LIQANUAL_VAT_IMPORT` [659], `LIQANUAL_NoCurrentTypeBASE` [660],
`LIQANUAL_NoCurrentTypeAMOUNT` [661], `COMMON_TERRITORY`/`ALAVA`/`GUIPUZCOA`/`VIZCAYA`/`NAVARRA`
[87–91], `COMMON_TERRITORY_COMPENSATION` [93], `INGRESAR_01..12`, `DEVOLVER_01..12`,
`TOTAL_LASTPERIOD` [97], `TOTAL_LASTPERIOD_DEVOLVER` [98], `LIQANUAL_ENDCOMPENSATION` [662],
`390REAGYP` [101], `390USED_GOODS` [227], `390TRAVEL_AGENCY` [228],
`390OPSUJETASCONOSS` [127], `390OPSINTRAGRUPO` [128], `OPER_MONTHLYRETURN` [111].

`390OTHER_UEMENBER` (old box [112], *Entregas de bienes objeto de instalación o montaje en otros
Estados miembros*) is NOT read: box [112] is not part of the 2025 model (AEAT record design
DR390e2025), and classic only prints it from the obsolete 2015/2018 page-6 writers that
`AEAT390Report2021.generateTXTPage6` replaced. A stored value is ignored. (The 2026 model
reintroduces a box [112] with a different meaning — not covered here.)

They are read from the latest declaration's `manualData.aeatInputs` (object key → string value,
decimal comma accepted) and, for `boxes`/`generate`, overridden by query parameters of the same
name. The submission snapshot is computed from the STORED inputs: the frontend must save
`manualData.aeatInputs` before (not in the same PUT as) the status change to submitted, because
the snapshot is taken before the PUT's `manualData` is applied.

## `GET /fiscal390/boxes` response

```json
{
  "boxes":   { "5": "1500.00", "6": "315.00", "47": "339.68", "84": "260.00", "...": "..." },
  "summary": { "accrued": "[47]", "deductible": "[64]", "result": "[65]", "annualResult": "[86] or [94]" },
  "mode":    "liquidation | territory",
  "taxReport": { "searchKey": "AEAT390_2025_Y", "layoutYear": 2025, "fallback": true },
  "inputs":  { "LIQANUAL_VAT_IMPORT": "40" },
  "warnings": [ { "code": "UNSUPPORTED_RATE | NO_BOX", "parameter": "VAT_PURCHASE_NORMAL", "rate": "3.00" } ],
  "invoiceBoxes": [ 3, 4, 5, 6, 33, 34, 99 ]
}
```

`invoiceBoxes` lists the boxes filled from invoices (not the derived/echoed ones): the boxes the
Facturas tab can list lines for ("Ver facturas" in the Casillas tab).

Box keys are AEAT box numbers without leading zeros. Amounts are 2-decimal strings.

### How the boxes are computed

`Fiscal390BoxesCalculator` walks the classic configuration exactly like
`AEAT390CashVATReadyReport.exploreRegGeneralGroups/exploreTaxableAmountGroups` — groups
`VAT_SALES`, `VAT_PURCHASE`, `VOLUME_OPERATIONS`, `SPECIFIC_OPERATIONS`; each output parameter's
taxes; split by rate when `aeat390Issplit` (+ element total when `aeat390Istotal`); amounts from
classic `AEAT390CashVATReadyCalculationsHelper.calculateAmounts` (posted invoices of the 12
periods, cash-VAT merge). Each figure goes to its box through `Fiscal390BoxLayout`, a table of
bean path → box extracted mechanically from the classic TXT writers (2015–2025). The reference
data's own box lists are metadata only (and contain a typo: "662" for box 632).

Unlike classic, an unsupported tax rate does not abort: it is reported as `UNSUPPORTED_RATE`
and left out; a supported rate the form does not print for that element is `NO_BOX` (kept in the
totals). Both would make the classic file generation fail or differ — show them to the user.

### Computed boxes (`Fiscal390DerivedBoxes`, the single place)

Classic computes these on the bean or while writing TXT page 6
(`AEAT390Report2014`, `AEAT390Report2021.generateTXTPage6`):

```
[47] = [34] + Σ recargo-equivalencia cuotas + [44]   (only the RE cuotas when [34] is empty)
[64] = Σ deductible cuotas (split element Total cuota, non-split element cuota) + [661]
[65] = [47] − [64]
liquidation mode (no territory percentage):
  [84] = [65] + [658]
  [85] = LIQANUAL_COMPENSATION (only when ≥ 0.01)
  [86] = [84] + [659] − LIQANUAL_COMPENSATION
territory mode (any of [87]–[91] > 0):
  [84] = [65] + [661] + [658]    (classic does not take [661] off this slot)
  [92] = round2([84] × [87] / 100), HALF_UP
  [94] = [92] + [659] − [93]
[95] = Σ INGRESAR_01..12      [96] = Σ DEVOLVER_01..12
[108] = [99]+[653]+[103]+[104]+[105]+[110]+[125]+[126] − [106] − [107]
        + [127] + [128] + [101] + [227] + [228]
```

[661] (*cuotas deducibles en virtud de resolución administrativa o sentencia firmes con tipos no
vigentes*) is not in classic's bean totals (`SumDeducciones`, `ResRegGeneral`): the TXT writer
adds it to [64] and takes it off [65] while printing page 4 (`AEAT390Report2018.generateTXTPage4`)
and takes it off the liquidation [84]/[86] while printing page 6
(`AEAT390Report2021.generateTXTPage6`). The preview reproduces what the file prints, so in
liquidation mode [84] = [65] + [658] holds.

Classic quirks with [661] in territory mode:
- the territory [84] (and so [92]/[94]) is printed from `Administraciones.SumResultados`, which
  never subtracts [661], so the file shows [84] = [65] + [661] + [658]. The preview shows the same
  value (screen = file); it is a classic inconsistency, not a GO formula choice.
- not reproduced: the TXT also prints the liquidation-section [84]/[86] slots as `0 − [661]`; the
  preview only reports the territory [84].

## `GET /fiscal390/sources` — the Facturas tab

The invoice-tax lines behind the invoice boxes. **Not a second query**: the same
`Fiscal390BoxesCalculator` walk runs, but each figure is built from the lines classic
`calculateAmounts` adds to its CSV object (`AEAT390CSV.addAEAT390CSVLine`, overridden in memory by
`Fiscal390SourcesSupport.CapturingCsv`) — the per-line, already signed and rounded amounts classic
sums into that figure. `Engine.placed(parameter, taxes, baseBox, cuotaBox)` (no-op for the boxes
endpoint) then attributes those lines to the boxes the figure landed in. Consequences:

- **for each box, the sum of `boxAmounts[box]` over its rows equals the box value** (acceptance
  criterion; `Fiscal390SourcesSupportTest#testPerBoxSumsEqualCalculatorBoxValues`);
- inclusion is classic's: posted invoices of the 12 periods with accounting facts — normal,
  negative, reversal and credit-memo lines, cash-VAT lines per payment;
- a line whose figure feeds no box (zero, `UNSUPPORTED_RATE`, negative volume base, `NO_BOX`) is
  not listed; derived boxes ([47], [64], [65], [84]…[108]) have no lines.

Query parameters (all optional besides `year`/`period`): `box` (AEAT box number), `type`
(`sales` | `purchase`, from the invoice's `isSOTrx`), `tax` (`C_Tax_ID`), `q` (case-insensitive
substring of document number, business partner or tax name), `page` (1-based, default 1),
`pageSize` (default 50, max 500, `0` = every row — used by the CSV export).

```json
{
  "rows": [ {
    "id": "<C_InvoiceTax_ID>[#n]", "invoiceTaxId": "…", "invoiceId": "…", "ref": "F001",
    "date": "2026-01-15", "accountingDate": "2026-01-15", "type": "sales | purchase",
    "party": "Acme", "docType": "AR Invoice", "taxId": "…", "tax": "IVA 21%", "rate": "21",
    "base": "1000.00", "vat": "210.00", "total": "1210.00",
    "boxes": "5,6,33,34,99", "boxAmounts": { "5": "1000.00", "6": "210.00", "33": "1000.00", "34": "210.00", "99": "1000.00" }
  } ],
  "count": 128, "page": 1, "pageSize": 50,
  "totals": { "base": "…", "vat": "…", "total": "…", "box": "<sum of boxAmounts[box]; only with box=>" },
  "boxes":  { "5": "1400.00", "6": "294.00", "…": "…" },
  "taxes":  [ { "id": "…", "name": "IVA 10%", "rate": "10" } ],
  "submitted": false
}
```

One row per `C_INVOICETAX` line (a line is usually counted by several parameters — rate box,
element total, volume box; an intra-EU acquisition on both the accrued and the deductible side —
and lists every box it feeds). Should a line be counted with different amounts by different
parameters, it gets one row per distinct amount (`id` suffixed `#2`, …). `totals` are over every
filtered row, not just the page; `boxes` and `taxes` are unfiltered (box chips and the Impuesto
filter). Inputs (`manualData.aeatInputs`) do not affect invoice boxes, so they are not read.

**Submitted declarations** — consistent with Modelo 303, whose snapshot keeps only the
`sourceCount` of its per-invoice drilldown: once the latest declaration of the year is submitted
with a snapshot, the endpoint answers `submitted: true` with no rows (a live list could no longer
add up to the frozen boxes). The frontend shows the "per-invoice detail is not kept" note.

## `GET /fiscal390/generate`

Reflective call of the resolved report's `javaClassName` (`OBTL_TaxReport_I
.generateElectronicFile`), like 303, with every input forwarded verbatim plus `FileName`.
Classic 390 returns `{"fileName", "files": Set<File>}` (TXT, informative CSV, XML when
`GenerateXML = Y`) instead of 303's `"file"`: the `.txt` is read (classic writes UTF-8), every
returned temp file is deleted, and the content is served through the shared
`writeGeneratedFile` in ISO-8859-1. Download name `390_0A_<exercise>.txt` — never derived from the
report name, which carries the layout year. Response headers `X-Fiscal-Tax-Report` and
`X-Fiscal-Layout-Year` report the resolved report.

## `POST /fiscal390/submit` — AEAT telematic filing

Request body (JSON): `{ testMode, idi, presenterNif, presenterName }` — the same shape as
`/fiscal303/submit` (see `aeat-303-submit-endpoint.md`), without `nrc`: a 390 is informative (the
payment was settled by the last 303), so it is never sent.

The AEAT protocol is the classic one, reused, not copied: `AEAT303SubmissionService` (module
`org.openbravo.module.aeat303.es`) speaks the generic direct-presentation services, and its
`ProductionSubmissionRequest.Builder#modelo` / `submitValidation(modelo, …)` select the model —
here `MODELO = 390`, `PERIODO = 0A`, `NRC = ""`. Endpoints, mutual TLS with the organization
certificate (`ETSG_Certificate`), `FIR = FirmaBasica`, response parsing and the justificante PDF
download are identical to 303. `ETSG_ForceTestMode = Y` on the client routes a production call to
the AEAT test environment (`prewww1.aeat.es/.../PresBasicaDos`), exactly like 303.

Flow of a production filing:

1. Guards: `id` present (`400`); the declaration exists, belongs to the client and to the org
   declarations are stored under, is a 390 of `year` (`404`). Then, each answered with the
   submission-failure JSON of 303 (`status: "ERROR"`, `errorCode`):

   | Status | `errorCode` | When |
   |---|---|---|
   | `409` | `ALREADY_SUBMITTED` | status is in the submitted family — correct it with a substitute |
   | `409` | `BLOCKING_INCIDENTS` | the declaration has a blocking RULE incident (`block` or blank severity, code not `AEAT-…`) |
   | `400` | `MISSING_PRESENTER` | `presenterNif` / `presenterName` blank |
   | `409` | `NO_CERTIFICATE` | the organization has no stored certificate |
   | `500` | `SUBMISSION_FAILED` | the file could not be generated |
   | `500` | `SNAPSHOT_FAILED` | the figures could not be frozen — nothing was sent to the AEAT |
   | `502` | `SUBMISSION_FAILED` | transport / non-JSON answer from the AEAT |

2. The file is generated server-side from the declaration's **stored** `manualData.aeatInputs`
   (never from the request): the frontend saves pending changes before opening the flow, so the
   filed file, the snapshot and what the user saved are the same inputs.
3. The submission snapshot is computed from those inputs and validated against the column
   BEFORE the AEAT call (ETP-5438 rule).
4. AEAT accepts (`200`, `status: "SUCCESS"`): one commit stores `status = submitted_ack`,
   `submissionMethod = aeat_telematic`, `fileName = justificante-390-<year>-0A.pdf`, the snapshot,
   `manualData.submission` (below) and attaches the justificante PDF to the declaration.
5. AEAT rejects (`200`, `status: "ERROR"`, `errors[]` as returned by the AEAT, code included):
   the declaration stays a draft; only its AEAT incidents change (below).

**AEAT incidents.** Like 303, every attempt that reaches the AEAT — validation and production,
accepted or rejected — replaces the declaration's AEAT incidents with the returned `errors`
(`block`) and `warnings` (`warn`), committed with the rest of the write; a clean answer leaves
none. They are coded `AEAT-<AEAT code>` (`FiscalDeclCrudHandler.AEAT_INCIDENT_CODE_PREFIX`,
`replaceAeatIncidentsNoCommit`), which does two things:

- only `AEAT-…` rows are replaced, so the model's own rule incidents are kept;
- they never block: the `BLOCKING_INCIDENTS` guard (and the page's Generar / Presentar gate) count
  only rule incidents, so a stored AEAT error does not block the retry that fixes it. This is the
  same outcome as 303, which stores AEAT errors with the raw code and does not gate its filing on
  stored incidents at all. 303 keeps the raw code and `replaceIncidentsNoCommit` (unchanged).

Whoever writes the 390 rule incidents must not wipe the `AEAT-…` rows (or accepts that a rule
recompute clears the last AEAT answer). Test mode (`testMode: true`, ServValiDos with
`MODELO = 390`) never changes the declaration itself; a returned draft PDF is attached as
`TEST-justificante-390-<year>-0A.pdf`.

The response is the 303 shape (`status`, `testMode`, `csv`, `presentationDate`,
`registryNumber`, `justificanteNumber`, `pdfBase64`, `pdfDownloadFailed`, `errors`, `warnings`)
with `declarationData = { fiscalYear, period, declarationType, resultAmount }` (`resultAmount` =
the snapshot's `summary.annualResult`, production only). The JSON builders and the PDF attachment
are shared with 303 in `FiscalAeatSubmissionSupport`.

### `manualData.submission`

The filing data of a submitted 390, shown in the "Presentación en la AEAT" card:

```json
{ "method": "aeat_telematic" | "manual", "presentationDate": "…", "justificante": "3901234567890",
  "csv": "…", "registryNumber": "…", "user": "…", "submittedAt": "2027-01-15T09:20:30Z" }
```

Written by the backend on a telematic filing (`user` = the session user's name). On "Registrar
como presentada" the frontend writes it (justificante, date, user; no CSV) with a `PUT
manualData`, and only then sends the status `PUT` — the same status and method as the 303/349
manual registration: `submitted_ack` + `manual_ack` when the justificante PDF is attached,
`submitted` + `manual_no_receipt` otherwise. The two are never the same request (see "User
inputs"). A manually registered 390 can therefore be reactivated ("Reactivar declaración"); a
telematic one (`aeat_telematic`) cannot (`rejectTelematicReactivation`).

### Substitute declaration

"Crear declaración sustitutiva" on a submitted 390 is a plain `POST /declarations` with
`type: "com"` (allowed next to the ordinary, see below) followed by a `PUT manualData` that copies
the inputs and sets `identification = { sustitutiva: true, formerStatement: <13-digit
justificante> }` plus the classic flags (`ISSUPPLEMENTARY = Y`, `SUPPLEMENTARYNO`). A draft of the
exercise already existing answers `409` (shared ETP-5272 guard).

### Live test against the AEAT test environment

Never run against production while developing. With the user's test certificate stored for the
organization (Fiscal configuration → certificate) and the client preference `ETSG_ForceTestMode =
Y`, a production filing goes to `prewww1.aeat.es`. "Validar sin presentar" uses ServValiDos
(`prewww2.aeat.es`, no certificate). Unit tests mock `AEAT303SubmissionService`
(`Fiscal390SubmissionSupportTest`); nothing in the suite reaches the network.

## `GET /fiscal390/preload` — preload from the 303s and the cuadre

The Modelo 303 self-assessments of the exercise, the 390 inputs they determine and the result the
390 should reach to agree with them. Read-only, nothing is stored: the frontend applies the
values to `manualData.aeatInputs` (only to the inputs the user did not change) and records them in
`manualData.aeatPreloaded` (origin "Modificado" / Restaurar).

### Which 303s, which boxes

- **Declarations:** every `ETGO_Fiscal_Decl` with `model = 303` of the org (the org declarations
  are stored under, `declarationOrgId`) and year. Per period the latest **submitted** one wins
  (highest `DECL_SEQ`); a period with none submitted uses its latest draft. `superseded` counts the
  other declarations of the period.
- **Boxes of a period:** submitted with a submission snapshot → the frozen `boxes`
  (`source: snapshot`); draft, or a legacy submitted one without snapshot → computed live like
  `GET /fiscal303/boxes` (`source: live`, one classic compute per period). Either way the
  declaration's `manualData.manualOverrides` go on top and the 303 derivation runs
  (`Fiscal390PreloadAggregator.effectiveBoxes`, the Java port of the frontend
  `applyOverrides` + `recomputeDerivedBoxes`: 45, 46, 64, 66 = 64 × 65 %, 69 = 66 + 77 − 78 + 68 +
  108, 71 = 69 − 70 + 109 − 112; boxes 70/77/78/109/110/111 floored at 0). A live compute that fails
  (e.g. no 303 tax report) leaves that period out with `source: error` and `COMPUTE_FAILED`.
- **Figure of a period = [69]**, not [71]: a rectificativa subtracts in [70] what the replaced
  declaration declared, so [69] is the period's full result after its compensation [78]. For an
  ordinary 303, [70] = [109] = [112] = 0 and [69] = [71]. `tipo_declaracion`
  (`manualData.identification`) tells a credit to compensate from a refund.
- **Periodicity / missing periods:** monthly when the year has any monthly 303, else quarterly. A
  quarter covers its three months, so a year that switched to monthly is not short. The periods
  not covered are `missingPeriods` (+ `MISSING_303`).

### 303 → 390 mapping

| 390 input (box) | From the 303s | Justification |
|---|---|---|
| `INGRESAR_<mm>` (Σ = [95]) | [69] > 0 of the period, in its closing month (T1 → 03 … T4 → 12) | AEAT [95]: "suma de las cantidades a ingresar … de las autoliquidaciones periódicas del ejercicio", including deferred or unpaid ones. Classic only has per-month inputs and only their sum is filed |
| `DEVOLVER_<mm>` (Σ = [96]) | [69] < 0 with tipo `D`/`V`/`X`, only when `monthlyReturn` | AEAT [96]: refunds requested by declarants in the monthly refund register, the last period included. Classic rejects `DEVOLVER_xx` without `ISMONTHLYRETURN` (`AEAT390_MonthlyReturn_Check`) |
| `TOTAL_LASTPERIOD` [97] | \|[69]\| of the LAST period (T4/12) when it is a credit (tipo not `D`/`V`/`X`) | AEAT [97]: "si el resultado de la última autoliquidación fue a compensar" |
| `TOTAL_LASTPERIOD_DEVOLVER` [98] | \|[69]\| of the last period when a refund and NOT in the monthly register | AEAT [98]; "si ha consignado alguna cantidad en las casillas 96 ó 97 no cumplimente la 98" — a registered declarant's last refund goes to `DEVOLVER_12` |
| `LIQANUAL_COMPENSATION` [85] — or `COMMON_TERRITORY_COMPENSATION` [93] in territory mode | the part of Σ[78] that consumed prior-year credits | AEAT [85]/[93]: credits "generadas en ejercicios anteriores y aplicadas en el ejercicio (… consignado en la casilla 78 de alguna de las autoliquidaciones)" |
| `LIQANUAL_ENDCOMPENSATION` [662] | credits generated in a non-last period of the year and never applied | AEAT [662]: "cuotas a compensar generadas en el ejercicio en alguno de los períodos … distintos del último cuando no se hubiesen aplicado" |
| `COMMON_TERRITORY` [87] | last 303 [65] (= [107], ETP-5391), default 100 | only when Σ last 303 [89]–[92] > 0; otherwise no territory key is sent |
| `ALAVA` [88] / `GUIPUZCOA` [89] / `VIZCAYA` [90] / `NAVARRA` [91] | last 303 [89] / [90] / [91] / [92] | ETP-5391 last-period territorial boxes, same order |

[85]/[662] come from one walk in period order: each [78] consumes the prior-year carry first —
the first 303's [110] ("cuotas pendientes de compensación de periodos anteriores") — then the
credits generated earlier in the year. When the first 303 has no [110] the carry is unknown and
every [78] is taken as prior-year (the literal AEAT text); `carryOverKnown: false` +
`PRIOR_CARRY_UNKNOWN`. A refund (`D`/`V`/`X`) outside the last period without the monthly register
cannot be a 390 refund: it is treated as a credit and reported (`REFUND_WITHOUT_MONTHLY_REGISTER`).
Keys whose source is unknown are omitted, not zero: with the last 303 missing there is no
`TOTAL_LASTPERIOD`, `TOTAL_LASTPERIOD_DEVOLVER` nor territory key (`LAST_PERIOD_MISSING`).

`monthlyReturn` (`Y`/`N`) is the on-screen `ISMONTHLYRETURN`; omitted, the stored one is used.

### Cuadre

```
expectedResult = [95] − [96] − [97] − [98] − [662]        (all from the 303s)
cuadre:  annualResult − expectedResult,  annualResult = [86]  (territory mode: [94])
OK when |difference| ≤ 1,00 €  (Fiscal390PreloadAggregator.RECONCILIATION_TOLERANCE,
                                frontend RECONCILIATION_TOLERANCE_390; Jira: rule pending)
```

Why these boxes: over the year Σ303[69] = Σ([66] + [77] + [68] + [108]) − Σ[78], and
Σ303[69] = [95] − [96] − [97] − [98] − (credits generated in non-last periods). Those credits
are either applied later in the year (Σ[78] − [85]) or still pending ([662]). The annual result
is [86] = [84] + [659] − [85] with [84] ≈ Σ([66] + [68] + [108]) and [659] ≈ Σ[77] (deferred import
VAT), so [86] ≈ Σ303[69] + (Σ[78] − [85]) = [95] − [96] − [97] − [98] − [662]. The 303 figures
are the state's share ([66] = [64] × [65] %), so in territory mode the counterpart is [94]
(= [92] + [659] − [93], [92] = [84] × [87] %). The identity does not depend on the [85]/[662]
split, so an unknown carry does not by itself create a difference. The 390 side is the result on
screen (live inputs), so the comparison is done by the frontend; `reconcile(annualResult,
expectedResult)` in `Fiscal390PreloadAggregator` gives the same answer server-side for the
incidents phase ("Diferencia con los 303").

### Response

```json
{
  "year": 2026, "periodicity": "quarterly", "monthlyReturn": false,
  "declarations": [ {
    "id": "…", "period": "T1", "month": "03", "status": "submitted", "submitted": true,
    "source": "snapshot | live | error", "superseded": 0, "tipo": "I",
    "result": "120.00", "periodResult": "120.00", "kind": "pay | compensate | refund | zero",
    "compensationApplied": "0.00", "error": "<only with source=error>"
  } ],
  "missingPeriods": [ "T4" ], "lastPeriod": "T4 | null",
  "inputs": { "INGRESAR_01": "0.00", "…": "…", "INGRESAR_12": "0.00", "LIQANUAL_COMPENSATION": "0.00",
              "LIQANUAL_ENDCOMPENSATION": "0.00", "TOTAL_LASTPERIOD": "0.00", "TOTAL_LASTPERIOD_DEVOLVER": "0.00" },
  "totals": { "85": "0.00", "95": "…", "96": "…", "97": "…", "98": "…", "662": "…" },
  "territory": false, "carryOverKnown": true,
  "expectedResult": "…", "tolerance": "1.00",
  "warnings": [ { "code": "MISSING_303 | LAST_PERIOD_MISSING | DRAFT_303 | PRIOR_CARRY_UNKNOWN | REFUND_WITHOUT_MONTHLY_REGISTER | COMPUTE_FAILED", "periods": "T4" } ]
}
```

`result` is the 303's own [71] (what its screen shows); `periodResult` is [69], the figure used.
`DEVOLVER_xx` keys are present only with `monthlyReturn`. `expectedResult` is `null` when no 303
of the year could be used. Tests: `Fiscal390PreloadAggregatorTest`.

## One ordinary per exercise

`FiscalDeclCrudHandler.SINGLE_ORDINARY_MODELS = {"390"}`: `POST /declarations` of an ordinary
390 when one already exists for the org/year (any status) answers

```json
{ "error": { "status": 409, "code": "DUPLICATE_ORDINARY", "message": "…", "existingId": "<id>" } }
```

`existingId` is the latest ordinary (highest `DECL_SEQ`). A substitute declaration
(`type: "com"`) is still allowed. 303/349 are unchanged (rectificativa flow, ETP-5187).

## Module dependencies

GO imports classic 390 classes. The jars come from the `localization.spain.extensions` bundle
(as for 303/349), and `AD_MODULE_DEPENDENCY` now declares `org.openbravo.module.aeat390.es`
(5.0.0, MAJOR) and `org.openbravo.module.organization.representative.es` (3.1.0, MAJOR; imported
by `AEAT390ReportDao`). `org.openbravo.module.epigrafes.iae.es` is reached transitively through
aeat390. Run `update.database` before any `export.database`, or the export drops these rows.

## Extension points for the next steps

- Submission: done (`POST /fiscal390/submit`, above).
- 303 preload: done (`GET /fiscal390/preload`, above).
- Incidents: `warnings` + `FiscalDeclCrudHandler.replaceIncidents`. The preload gives
  "Faltan 303 del ejercicio" (`missingPeriods`) and "Diferencia con los 303"
  (`Fiscal390PreloadAggregator.reconcile` against the computed [86]/[94]).
- Invoice sources tab: done as its own endpoint (`GET /fiscal390/sources`, above), so the boxes
  payload and its snapshot stay fixed-size.
