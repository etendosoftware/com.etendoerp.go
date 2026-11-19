# ADR-0005 — Encode stored free text on output (HTML surfaces)

- **Status:** Accepted — ETP-5384
- **Jira:** ETP-5384 · found by the MCP security review of ETP-5335 (2026-09-16)
- **Precedent:** [ADR-0004](0004-csv-formula-neutralization.md) (one canonical policy plus fixtures)
- **Fixtures:** [`../security/html-encoding-fixtures.md`](../security/html-encoding-fixtures.md)

## Context

`<script>alert(document.cookie)</script>` written to a free-text field (for example `description`
through `neo_create`) is stored as given. That is correct for a write API. The exposure is any
surface that renders stored text as markup without encoding it.

## Decision

1. **Encode on output, at the rendering surface.** Stored text is data. It is never sanitized,
   rewritten or rejected on write for XSS reasons. Sanitize-on-input would leave every row already in
   the database and every write through the classic Etendo UI untouched.
2. **One canonical encoder per runtime**, never a per-call-site fix:
   - Java: `EmailEscape.escapeHtml` — encodes `& < > " '` (`&amp; &lt; &gt; &quot; &#39;`).
   - Handlebars (jsreport): the default `{{ }}` escaping.
3. **Raw output is an explicit, reviewed exception.** Markup that a contract assembled itself must be
   escaped first and only then wrapped (`EmailEscape.applyBold` runs after `escapeHtml`; the
   `paragraphHtml`/`greetingHtml` builders carry pre-escaped text only).
4. **Out of scope:** the MCP write path (`neo_create`) and CSV (ADR-0004).

## Surface inventory (ETP-5384)

| Surface | Mechanism | State |
|---|---|---|
| jsreport HTML templates (`schema_forge` `artifacts/**/template.hbs`, `templates/reports/*.hbs`) | Handlebars `{{ }}` | Encoded. The only `{{{ }}}` in HTML templates is the platform-owned `{{{css}}}`. No `SafeString` helper exists. |
| jsreport CSV templates | `{{{csvField x}}}` | Neutralized by ADR-0004 (not HTML). |
| Outgoing email, layout (`EmailLayout`) | `EmailEscape.escapeHtml` on every string, CTA url and label included | Encoded. |
| Outgoing email, pre-escaped paragraphs (`paragraphHtml`, `greetingHtml`) | Callers escape before interpolating | Encoded; operator text via `EmailMessageEdits.toHtmlBody`. |
| Process messages (`DocumentPdfMonthlyReportProcess.toHtml`) | `& < >` encoded, newline to `<br/>` | Encoded (element context). |
| Admin panels | Classic Etendo UI, owned by `etendo_core`, not in this repo | Not changed here. |

## Guardrails

- `EmailEncodeOnOutputTest` (this module) drives the fixtures through `EmailEscape` and `EmailLayout`.
- `report-templates-encode-on-output.test.js` (`schema_forge`) fails if an HTML `.hbs` template adds a
  raw `{{{ }}}` or `{{&` other than `{{{css}}}`, or if a helper returns `SafeString`.

A new rendering surface is added to the inventory above and to the fixtures first.
