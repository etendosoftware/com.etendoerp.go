# AEAT Modelo 390 (annual VAT summary) — `/neo/fiscal390/*`

ETP-5716 — backend base of Modelo 390 in "Modelos Fiscales". Same architecture as Modelo 303
(`aeat-303-submit-endpoint.md`): a NEO built-in handler (`Fiscal390BoxesHandler`, routed by
`NeoBuiltInEndpointHandler`) on top of the classic module `org.openbravo.module.aeat390.es`.
Telematic submission, 303 preload, incident rules and an invoice-sources tab are NOT part of this
step; the extension points they plug into are listed at the end.

## Routes

All routes require the "Modelos Fiscales" grant (Tax Report window, same gate as 303/349).

| Method | Path | Handler |
|---|---|---|
| `GET` | `/fiscal390/boxes?year=&period=0A[&<input>=…]` | box preview (`Fiscal390BoxesHandler`) |
| `GET` | `/fiscal390/generate?year=&period=0A[&<input>=…]` | TXT file (`Fiscal390GenerateSupport`) |
| `GET` | `/fiscal390/modified?year=&period=0A&since=` | shared invoice-change probe |
| `GET/POST/PUT/DELETE` | `/fiscal390/declarations` (= `/fiscal303/declarations`) | shared `FiscalDeclCrudHandler` |
| `GET` | `/fiscal390/incidents?id=` | shared `FiscalDeclCrudHandler` |

Declarations use `model: "390"`, `period: "0A"`.

### Errors

| Status | When |
|---|---|
| `400` | `period` is not `0A`; an input is not a number (message names it) |
| `404` / `405` | unknown entity / wrong method (`generate` is GET-only) |
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
  "warnings": [ { "code": "UNSUPPORTED_RATE | NO_BOX", "parameter": "VAT_PURCHASE_NORMAL", "rate": "3.00" } ]
}
```

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

## `GET /fiscal390/generate`

Reflective call of the resolved report's `javaClassName` (`OBTL_TaxReport_I
.generateElectronicFile`), like 303, with every input forwarded verbatim plus `FileName`.
Classic 390 returns `{"fileName", "files": Set<File>}` (TXT, informative CSV, XML when
`GenerateXML = Y`) instead of 303's `"file"`: the `.txt` is read (classic writes UTF-8), every
returned temp file is deleted, and the content is served through the shared
`writeGeneratedFile` in ISO-8859-1. Download name `390_0A_<exercise>.txt` — never derived from the
report name, which carries the layout year. Response headers `X-Fiscal-Tax-Report` and
`X-Fiscal-Layout-Year` report the resolved report.

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

- Submission: `Fiscal390BoxesHandler.isKnownEntity`/`allowsPost` (add `submit`, POST-only) and
  the same `generateElectronicFile` path; snapshot is already provided for model `390`.
- 303 preload: fill `manualData.aeatInputs` (`INGRESAR_xx`, `DEVOLVER_xx`, …).
- Incidents: `warnings` + `FiscalDeclCrudHandler.replaceIncidents`.
- Invoice sources tab: add the array to the payload and declare it in
  `Fiscal390SnapshotSupport.excludedLists` so the snapshot stays bounded.
