# HTML encode-on-output fixtures

Cross-runtime contract referenced by ADR-0005. Expected values are the text after encoding for an
HTML element or attribute context. `EmailEncodeOnOutputTest` mirrors this table row for row.

| Input description | Input | Expected output |
|---|---|---|
| Script tag | `<script>alert(document.cookie)</script>` | `&lt;script&gt;alert(document.cookie)&lt;/script&gt;` |
| Image onerror | `<img src=x onerror=alert(1)>` | `&lt;img src=x onerror=alert(1)&gt;` |
| Ampersand | `Tom & Jerry` | `Tom &amp; Jerry` |
| Double quote (attribute breakout) | `" onmouseover="x` | `&quot; onmouseover=&quot;x` |
| Single quote | `' onmouseover='x` | `&#39; onmouseover=&#39;x` |
| Already encoded is encoded again | `&lt;` | `&amp;lt;` |
| Unquoted attribute breakout | `x onmouseover=alert(1)>` | `x onmouseover=alert(1)&gt;` |
| Plain text | `Normal Value` | `Normal Value` |
| Empty / null | empty string / null | empty string |

Emphasis (`**bold**`) is applied only after encoding, so `**<b>x</b>**` becomes
`<strong>&lt;b&gt;x&lt;/b&gt;</strong>`.

## URL context (`href`/`src`)

Allowed schemes: `http`, `https`, `mailto`, or none (relative). The scheme is read after removing
whitespace/control characters and lower-casing. A rejected URL is never emitted as an `href`.

| Input description | Input | Result |
|---|---|---|
| https with query | `https://example.com/a?b=1&c=2` | allowed |
| http | `http://example.com` | allowed |
| mailto | `mailto:a@example.com` | allowed |
| Relative path | `/portal/doc/1` | allowed |
| Colon after the path starts | `relative/path?x=a:b` | allowed |
| javascript | `javascript:alert(1)` | rejected |
| Mixed case, tab inside | `JaVa\tScript:alert(1)` | rejected |
| Leading spaces | `  javascript:alert(1)` | rejected |
| Newline inside | `java\nscript:alert(1)` | rejected |
| data | `data:text/html,<script>alert(1)</script>` | rejected |
| vbscript | `vbscript:msgbox(1)` | rejected |
| Empty / null | empty string / null | rejected |

**Executable twins:** `EmailEncodeOnOutputTest` (Java) mirrors both tables row for row;
`report-templates-encode-on-output.test.js` in `schema_forge` and `schema_forge_core` carries the
template rules. Add a row HERE first, then to every test.
