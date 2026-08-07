# CSV spreadsheet neutralization fixtures

This table is the cross-runtime contract referenced by ADR-0004. Expected values describe the cell
value after neutralization and before CSV quoting.

| Input description | Input | Expected cell value |
|---|---|---|
| Equals | `=1+1` | `'=1+1` |
| Plus | `+SUM(A1:A2)` | `'+SUM(A1:A2)` |
| Minus / DDE-like | `-CMD` | `'-CMD` |
| At sign | `@SUM(A1:A2)` | `'@SUM(A1:A2)` |
| Marker behind spaces | `   =1+1` | `'   =1+1` |
| Marker behind TAB | `\t=1+1` | `'\t=1+1` |
| Marker behind CR | `\r=1+1` | `'\r=1+1` |
| Marker behind LF | `\n=1+1` | `'\n=1+1` |
| TAB as first standalone control | `\tText` | `'\tText` |
| CR as first standalone control | `\rText` | `'\rText` |
| LF as first standalone control | `\nText` | `'\nText` |
| BOM before marker | `\uFEFF=1+1` | `'\uFEFF=1+1` |
| Full-width equals | `＝1+1` | `'＝1+1` |
| Full-width plus | `＋SUM(A1:A2)` | `'＋SUM(A1:A2)` |
| Full-width minus | `－CMD` | `'－CMD` |
| Full-width at | `＠SUM(A1:A2)` | `'＠SUM(A1:A2)` |
| Already neutralized | `'=1+1` | `'=1+1` |
| Negative number | `-500.00` | `'-500.00` |
| Plain text | `Normal Value` | `Normal Value` |
| Empty | empty string | empty string |
| Null | null | empty string |

The serialized fixtures additionally assert:

- Neutralization occurs before quote escaping.
- A trigger combined with commas, double quotes or a newline remains one RFC 4180 cell.
- Backend output preserves UTF-8 BOM, comma delimiters, always-quoted fields and CRLF.
- Existing frontend presentation formats are characterized before any line-ending or quoting change.

Manual compatibility evidence for Excel desktop, LibreOffice Calc and Google Sheets belongs to
ETP-4568 and must include save/reopen behavior.
