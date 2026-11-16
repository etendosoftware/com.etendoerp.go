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
| `pre_submission_validation` | the validation the server runs right before a real presentation (`FiscalAeatSubmissionSupport.validateBeforeSubmission`, 303 and 390) | every outcome; a presentation it stops leaves only this row, one that goes ahead also leaves its `direct_submission` row |
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
| `Attempt_Path` | `VARCHAR(200)` | no | `direct_submission`, `manual_validation`, `pre_submission_validation`, `download` |
| `IsTestMode` | `CHAR(1)` | no | `Y` when the validation service was asked (nothing is filed), `N` for a presentation |
| `Environment` | `VARCHAR(200)` | yes | `production` (the AEAT production endpoint: a real filing), `preproduction` (a presentation routed to another AEAT host: `ETSG_ForceTestMode` or an endpoint preference), `validation` (the AEAT validation service, ServValiDos), `override` (a host that is not the AEAT's: an endpoint preference pointing at a stub or proxy), `unknown` (no endpoint, or a malformed one). `production` is decided by host and path, not by the exact string |
| `Aeat_Host` | `VARCHAR(200)` | yes | host of the endpoint the attempt went to |
| `Outcome` | `VARCHAR(200)` | no | `accepted`, `rejected` (at least one AEAT error), `connection_failure`, `not_validated` (no usable answer, including one that is neither accepted nor carries an error) |
| `Error_Count` | `NUMERIC` | no | number of distinct codes in `Error_Codes` (`UNCODED` counts once) |
| `Error_Codes` | `VARCHAR(2000)` | yes | the distinct AEAT error codes, comma-separated; one `UNCODED` when any error has no readable code |
| `Warning_Count` | `NUMERIC` | no (default 0) | number of distinct codes in `Warning_Codes` (`avisos`) |
| `Warning_Codes` | `VARCHAR(2000)` | yes | the AEAT warning codes, same rules as `Error_Codes` |

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
- **Codes only.** The AEAT text can carry fiscal data and is never stored. A code is read from
  the RAW entry and kept only when it has the AEAT shape — up to four capital letters, two to
  seven digits, then ` - ` (`35068`, `E00`, `A001`, `PAR016`, `E010124`, `E0100803`). Anything
  else — an identity number such as `B12345678`, `12345678Z` or `X1234567L`, a word, an entry
  without code — becomes `UNCODED`. Codes are distinct: a repeated code is stored once and
  `UNCODED` once, and the counts are the number of distinct codes.
- **Retry.** The insert is retried without the warning columns only for SQLState `42703`
  (undefined column: a table that predates them); a failed insert is rolled back.
- **Warnings** come from the validator's `avisos`. The classic parser reads them on an accepted
  answer only, so a rejected attempt always has `Warning_Count = 0`. A database whose table
  predates the two warning columns still gets the row, without them (the insert is retried).
- **`override` rows** exist only from the version that added the value; earlier rows of a local
  endpoint override say `validation`. Filter on `Aeat_Host` to keep real AEAT verdicts.
- **A real filing** is `Attempt_Path = 'direct_submission' AND Environment = 'production'`.

## KPI: % of 390 files the AEAT accepts without errors

Definition:

- **Population:** model 390, path `download`, attempts that reached a real AEAT host. The filter
  is on `Aeat_Host`, not on `Environment`: rows written before the `override` value existed say
  `validation` for a local endpoint override too.
- **One row per declaration:** its FIRST attempt that got an AEAT verdict (`accepted` or
  `rejected`). Attempts without a verdict (`connection_failure`, `not_validated`) are skipped when
  picking the first and reported apart (`unanswered_attempts`).
- **KPI:** accepted first attempts / (accepted + rejected first attempts), per fiscal year.

```sql
WITH verdicts AS (
  SELECT a.*,
         row_number() OVER (PARTITION BY fiscal_decl_uuid ORDER BY created) AS n
    FROM etgo_fiscal_aeat_attempt a
   WHERE model = '390' AND attempt_path = 'download'
     AND (aeat_host LIKE '%.aeat.es' OR aeat_host LIKE '%.agenciatributaria.gob.es')
     AND outcome IN ('accepted', 'rejected')
), unanswered AS (
  SELECT fiscal_year, count(*) AS unanswered_attempts
    FROM etgo_fiscal_aeat_attempt
   WHERE model = '390' AND attempt_path = 'download'
     AND (aeat_host LIKE '%.aeat.es' OR aeat_host LIKE '%.agenciatributaria.gob.es')
     AND outcome IN ('connection_failure', 'not_validated')
   GROUP BY fiscal_year
)
SELECT v.fiscal_year,
       count(*)                                        AS declarations,
       count(*) FILTER (WHERE v.outcome = 'accepted')  AS accepted_first,
       count(*) FILTER (WHERE v.outcome = 'rejected')  AS rejected_first,
       round(100.0 * count(*) FILTER (WHERE v.outcome = 'accepted') / count(*), 1) AS pct_accepted_first,
       coalesce(max(u.unanswered_attempts), 0)         AS unanswered_attempts
  FROM verdicts v
  LEFT JOIN unanswered u ON u.fiscal_year = v.fiscal_year
 WHERE v.n = 1
 GROUP BY v.fiscal_year
 ORDER BY v.fiscal_year;
```

Most frequent error and warning codes on those first attempts — the validations Etendo is
missing on its side:

```sql
WITH firsts AS (
  SELECT *
    FROM (SELECT a.*, row_number() OVER (PARTITION BY fiscal_decl_uuid ORDER BY created) AS n
            FROM etgo_fiscal_aeat_attempt a
           WHERE model = '390' AND attempt_path = 'download'
             AND (aeat_host LIKE '%.aeat.es' OR aeat_host LIKE '%.agenciatributaria.gob.es')
             AND outcome IN ('accepted', 'rejected')) x
   WHERE n = 1
)
SELECT 'error' AS kind, code, count(*) AS first_attempts
  FROM firsts, unnest(string_to_array(error_codes, ',')) AS code
 GROUP BY code
UNION ALL
SELECT 'warning', code, count(*)
  FROM firsts, unnest(string_to_array(warning_codes, ',')) AS code
 GROUP BY code
 ORDER BY kind, first_attempts DESC, code;
```
