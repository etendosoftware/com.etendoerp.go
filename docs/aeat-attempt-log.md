# AEAT attempt log — `ETGO_Fiscal_AEAT_Attempt`

**Ticket:** ETP-5716. An append-only table with one row per attempt against the AEAT for a fiscal
model declaration. It is not exposed anywhere (no window, no NEO spec, no MCP tool, no usage
event): it is read by SQL, e.g. for the KPI "% of 390 files the AEAT accepts without errors".

Writer: `FiscalAeatAttemptLog` (`com.etendoerp.go.schemaforge`). The value it stores is the
`FiscalAeatValidationOutcome` every path builds.

## What is an attempt

A row is written only when a call to the AEAT was actually tried — answered or not:

| Path (`Attempt_Path`) | Where | Recorded on |
|---|---|---|
| `direct_submission` | `Fiscal303SubmissionSupport.handleSubmit`, `Fiscal390SubmissionSupport.handleSubmit` with `testMode = false` | the AEAT answer (accepted / rejected) and the `OBException` branch (502 `SUBMISSION_FAILED`) |
| `manual_validation` | the same two handlers with `testMode = true` ("Validar sin presentar") | same two branches |
| `download` | `Fiscal390FileValidationSupport#recordOutcome` (`POST /fiscal390/generate-validated`) | every outcome |

The guards that stop before any call write nothing: no certificate, already submitted, blocking
incidents, missing presenter, snapshot failure, file generation failure. Modelo 349 never calls
the AEAT.

## Columns

| Column | Type | Null | Values |
|---|---|---|---|
| `ETGO_Fiscal_AEAT_Attempt_ID` | `VARCHAR(32)` | no | primary key |
| `AD_Client_ID`, `AD_Org_ID` | `VARCHAR(32)` | no | tenant; the organization is the one the handler resolved |
| `IsActive` | `CHAR(1)` | no | `Y` |
| `Created`, `CreatedBy` | `TIMESTAMP`, `VARCHAR(32)` | no | **when** and **who** (`AD_User_ID`, not constrained) |
| `Updated`, `UpdatedBy` | `TIMESTAMP`, `VARCHAR(32)` | no | equal to created: a row is never updated |
| `Model` | `VARCHAR(200)` | no | `303`, `390` |
| `Fiscal_Year` | `NUMERIC` | no | exercise |
| `Period` | `VARCHAR(200)` | yes | `1T`…`4T`, `01`…`12`, `0A` |
| `Fiscal_Decl_UUID` | `VARCHAR(32)` | yes | `ETGO_Fiscal_Decl_ID` of the declaration — **no foreign key** |
| `Attempt_Path` | `VARCHAR(200)` | no | `direct_submission`, `manual_validation`, `download` |
| `IsTestMode` | `CHAR(1)` | no | `Y` when the validation service was asked (nothing is filed), `N` for a presentation |
| `Environment` | `VARCHAR(200)` | yes | `production` (the AEAT production endpoint: a real filing), `preproduction` (a presentation routed elsewhere: `ETSG_ForceTestMode` or an endpoint preference), `validation` (ServValiDos), `unknown` |
| `Aeat_Host` | `VARCHAR(200)` | yes | host of the endpoint the attempt went to |
| `Outcome` | `VARCHAR(200)` | no | `accepted`, `rejected`, `connection_failure`, `not_validated` |
| `Error_Count` | `NUMERIC` | no | number of distinct AEAT errors of the answer |
| `Error_Codes` | `VARCHAR(2000)` | yes | the AEAT error codes, comma-separated, one per error; `UNCODED` for an error without a readable code |

Indexes: `ETGO_FISCAL_AEAT_ATT_MODEL (Model, Fiscal_Year, Attempt_Path)`,
`ETGO_FISCAL_AEAT_ATT_DECL (Fiscal_Decl_UUID)`. Foreign keys: client and organization only.

## Rules

- **Append-only.** Rows are never updated, replaced or deleted — unlike the declaration's AEAT
  incidents, which every attempt replaces.
- **No foreign key to the declaration.** A draft declaration can be deleted (and its incidents
  cascade); its attempts must stay. `Model`, `Fiscal_Year` and `Period` are on the row so it
  reads without the declaration.
- **Own connection, own commit.** The row is inserted through
  `SessionHandler.getNewConnection()` and committed at once, so a failed attempt (connection
  failure answered 502, a failed best-effort commit, a rollback) still leaves it.
- **Best-effort.** A failure to record is logged (`Could not record the AEAT attempt…`) and never
  breaks or blocks the attempt.
- **Codes only.** The AEAT text can carry fiscal data and is never stored. A code is kept when
  it is alphanumeric and at most 20 characters; anything else becomes `UNCODED`.
- **A real filing** is `Attempt_Path = 'direct_submission' AND Environment = 'production'`.

## Example: % of 390 files the AEAT accepts without errors

```sql
SELECT fiscal_year,
       count(*) FILTER (WHERE outcome IN ('accepted', 'rejected'))              AS validated,
       count(*) FILTER (WHERE outcome = 'accepted')                             AS accepted,
       round(100.0 * count(*) FILTER (WHERE outcome = 'accepted')
             / NULLIF(count(*) FILTER (WHERE outcome IN ('accepted', 'rejected')), 0), 1)
                                                                                AS pct_accepted,
       count(*) FILTER (WHERE outcome IN ('connection_failure', 'not_validated')) AS unanswered
  FROM etgo_fiscal_aeat_attempt
 WHERE model = '390' AND attempt_path = 'download'
 GROUP BY fiscal_year
 ORDER BY fiscal_year;
```

Most frequent AEAT error codes:

```sql
SELECT code, count(*)
  FROM etgo_fiscal_aeat_attempt, unnest(string_to_array(error_codes, ',')) AS code
 WHERE model = '390'
 GROUP BY code ORDER BY 2 DESC;
```
