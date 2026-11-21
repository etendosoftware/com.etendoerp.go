# HTML encode-on-output fixtures

Cross-runtime contract referenced by ADR-0005. Expected values are the text after encoding for an
HTML element or quoted-attribute context. Both runtimes neutralise every markup and
attribute-breakout character, but they spell some entities differently, so each runtime has its own
column: Java `EmailEscape.escapeHtml`, and JavaScript `Handlebars.escapeExpression` (what `{{ }}`
uses). Handlebars additionally encodes `=` and the backtick as `&#x3D;` / `&#x60;`; Java leaves them,
which is safe because attribute values are always quoted.

`EmailEncodeOnOutputTest` (Java) asserts the Java column; `report-templates-encode-on-output.test.js`
in `schema_forge` and `schema_forge_core` assert the JavaScript column.

| Input description | Input | Java | JavaScript (Handlebars) |
|---|---|---|---|
| Script tag | `<script>alert(document.cookie)</script>` | `&lt;script&gt;alert(document.cookie)&lt;/script&gt;` | same |
| Image onerror | `<img src=x onerror=alert(1)>` | `&lt;img src=x onerror=alert(1)&gt;` | `&lt;img src&#x3D;x onerror&#x3D;alert(1)&gt;` |
| Ampersand | `Tom & Jerry` | `Tom &amp; Jerry` | same |
| Double quote (attribute breakout) | `" onmouseover="x` | `&quot; onmouseover=&quot;x` | `&quot; onmouseover&#x3D;&quot;x` |
| Single quote | `' onmouseover='x` | `&#39; onmouseover=&#39;x` | `&#x27; onmouseover&#x3D;&#x27;x` |
| Already encoded is encoded again | `&lt;` | `&amp;lt;` | same |
| Unquoted attribute breakout | `x onmouseover=alert(1)>` | `x onmouseover=alert(1)&gt;` | `x onmouseover&#x3D;alert(1)&gt;` |
| Plain text | `Normal Value` | same | same |
| Empty / null | empty string / null | empty string | empty string |

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

The URL scheme allowlist is implemented in Java only (`EmailEscape.isSafeUrl`, used by the email
layout): the jsreport templates take no stored data in `href`/`src`, and the guardrail tests keep it
that way. **Executable twins:** `EmailEncodeOnOutputTest` (Java, both tables) and the two JS
`report-templates-encode-on-output.test.js` suites (HTML table). Add a row HERE first, then to every
test.
