# CSV spreadsheet neutralization fixtures

This table is the cross-runtime contract referenced by ADR-0004. Expected values describe the cell
value after neutralization and before CSV quoting.

**Executable twin:** `@etendosoftware/app-shell-core/lib/csv/csvNeutralizationFixtures.js` exports
this same table as data (`CSV_NEUTRALIZATION_FIXTURES`) plus the trigger set
(`SPREADSHEET_FORMULA_TRIGGERS`). The JavaScript suites are driven by it, and
`NeoCsvExportServiceTest.spreadsheetNeutralizationFixtures()` mirrors it row for row. Add a trigger
HERE first, then to the exported table, then to every implementation.

## Algorithm

1. Walk over the insignificant prefix: the BOM (`U+FEFF`) and Unicode whitespace **including NBSP
   (`U+00A0`)**, but **excluding TAB, CR and LF** — those are triggers in their own right, not
   something a marker can hide behind.
   - JavaScript: `/[\s﻿]/` already matches NBSP and the BOM.
   - Java: `c == '﻿' || Character.isWhitespace(c) || Character.isSpaceChar(c)`.
     `isSpaceChar` is what covers NBSP; `isWhitespace` alone does not, and omitting it made Java
     silently disagree with JavaScript.
2. If the first significant character is a trigger — `=` `+` `-` `@` TAB CR LF `＝`(U+FF1D)
   `＋`(U+FF0B) `－`(U+FF0D) `＠`(U+FF20) — prepend a single ASCII apostrophe.
3. Then, and only then, apply RFC 4180 quoting, so the apostrophe lands inside the quoted field.

An apostrophe is not a trigger, so an already-neutralized value can never be double-prefixed.

## Fixtures

| Input description | Input | Expected cell value |
|---|---|---|
| Equals | `=1+1` | `'=1+1` |
| Plus | `+SUM(A1:A2)` | `'+SUM(A1:A2)` |
| Minus / DDE-like | `-CMD` | `'-CMD` |
| At sign | `@SUM(A1:A2)` | `'@SUM(A1:A2)` |
| DDE command payload | `+cmd\|' /C calc'!A0` | `'+cmd\|' /C calc'!A0` |
| HYPERLINK payload (ETP-5032) | `=HYPERLINK("http://example.com","Click")` | `'=HYPERLINK("http://example.com","Click")` |
| Marker behind spaces | `   =1+1` | `'   =1+1` |
| Marker behind TAB | `\t=1+1` | `'\t=1+1` |
| Marker behind CR | `\r=1+1` | `'\r=1+1` |
| Marker behind LF | `\n=1+1` | `'\n=1+1` |
| TAB as first standalone control | `\tText` | `'\tText` |
| CR as first standalone control | `\rText` | `'\rText` |
| LF as first standalone control | `\nText` | `'\nText` |
| BOM before marker | `﻿=1+1` | `'﻿=1+1` |
| NBSP before marker | ` =1+1` | `' =1+1` |
| Full-width equals | `＝1+1` | `'＝1+1` |
| Full-width plus | `＋SUM(A1:A2)` | `'＋SUM(A1:A2)` |
| Full-width minus | `－CMD` | `'－CMD` |
| Full-width at | `＠SUM(A1:A2)` | `'＠SUM(A1:A2)` |
| Already neutralized | `'=1+1` | `'=1+1` |
| Negative number | `-500.00` | `'-500.00` |
| Plain text | `Normal Value` | `Normal Value` |
| Plain text behind spaces | `  Normal Value` | `  Normal Value` |
| Trigger not in first position | `Total = 1+1` | `Total = 1+1` |
| Empty | empty string | empty string |
| Null / undefined | null | empty string |

The serialized fixtures additionally assert:

- Neutralization occurs before quote escaping.
- A trigger combined with commas, double quotes or a newline remains one RFC 4180 cell.
- **Header labels** are neutralized exactly like data cells. On the backend the `columns` spec
  parser trims a label before the writer sees it, so a label whose payload is leading whitespace
  (or TAB/CR/LF) cannot reach the writer at all — those triggers are pinned on the data-cell path,
  where nothing trims.
- Backend output preserves UTF-8 BOM, comma delimiters, always-quoted fields and CRLF.
- Existing frontend presentation formats are characterized before any line-ending or quoting change.

Manual compatibility evidence for Excel desktop, LibreOffice Calc and Google Sheets belongs to
ETP-5032 and must include save/reopen behavior.
