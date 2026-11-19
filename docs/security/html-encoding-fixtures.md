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
| Plain text | `Normal Value` | `Normal Value` |
| Empty / null | empty string / null | empty string |

Emphasis (`**bold**`) is applied only after encoding, so `**<b>x</b>**` becomes
`<strong>&lt;b&gt;x&lt;/b&gt;</strong>`.
