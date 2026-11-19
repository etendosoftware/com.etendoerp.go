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
2. **One canonical encoder per runtime and context**, never a per-call-site fix. Entity escaping
   alone is not enough everywhere, so the policy is context-aware:
   - **HTML text and quoted attribute value:** Java `EmailEscape.escapeHtml` (`& < > " '` become
     `&amp; &lt; &gt; &quot; &#39;`) or the Handlebars default `{{ }}`. Attribute values are always
     quoted.
   - **URL (`href`/`src`):** entity escaping **plus a scheme allowlist** — `http`, `https`, `mailto`
     or no scheme (relative). `javascript:`, `data:`, `vbscript:` and anything else are rejected.
     The scheme is read after removing whitespace/control characters and lower-casing, because
     browsers ignore a tab or newline inside `java\tscript:`. Java: `EmailEscape.isSafeUrl`, enforced
     in `EmailLayout.appendCta`, which drops the button instead of emitting a live href.
   - **`<script>`, `<style>` and unquoted attributes:** stored data is **forbidden**. The only raw
     output is the platform-owned `{{{css}}}`.
3. **Raw output is an explicit, reviewed exception.** Markup that a contract assembled itself must be
   escaped first and only then wrapped (`EmailEscape.applyBold` runs after `escapeHtml`; the
   `paragraphHtml`/`greetingHtml` builders carry pre-escaped text only).
4. **Out of scope:** the MCP write path (`neo_create`) and CSV (ADR-0004).

## Search method

`{{{` and `{{&` in every `.hbs`; `SafeString` in every `.js`; `innerHTML` and
`dangerouslySetInnerHTML` under `schema_forge/tools/app-shell/src` and `schema_forge_core/packages`;
string-concatenated HTML literals in Java; every `paragraphHtml`/`greetingHtml` and `.cta(` call
site.

## Surface inventory (ETP-5384)

| Surface | Mechanism | Result |
|---|---|---|
| jsreport HTML templates (`schema_forge` `artifacts/**/template.hbs`, `templates/reports/*.hbs`) | Handlebars `{{ }}` | Encoded. The only raw output is `{{{css}}}`. No `SafeString`. Guarded. |
| jsreport templates in `schema_forge_core` (`templates/reports/*.hbs`) and the generator `cli/src/generate-report-template.js` | Handlebars `{{ }}` | Encoded; only `{{{css}}}` is raw. Guarded, including a template produced by the generator. |
| jsreport CSV templates | `{{{csvField x}}}` | Neutralized by ADR-0004 (not HTML). |
| jsreport templates stored in a database | `NeoPrintableRenderer` renders templates shipped as artifacts | None found; no DB or store template path in this module. |
| Email layout (`EmailLayout`) | `EmailEscape.escapeHtml` on text, labels and URLs | Text encoded. **CTA URL: gap closed** — it had only entity escaping, so `javascript:` would have been a live href. Now gated by the scheme allowlist. |
| `.cta(` call sites (`DefaultDocumentSendEmailContract`, `PortalLinkRenewalEmailContract`, `CompanyInvitationEmailContract`, `PortalDecisionConfirmEmailContract`, `AccountEmailContent`) | Portal, download, invitation and account links minted by the system | Not stored free text today; the allowlist is defence in depth. |
| Email pre-escaped paragraphs (`paragraphHtml`, `greetingHtml`) | Callers escape before interpolating | Encoded; operator text via `EmailMessageEdits.toHtmlBody`. |
| Process messages (`DocumentPdfMonthlyReportProcess.toHtml`) | `& < >` encoded, newline to `<br/>` | Encoded (element context). |
| `PisReturnCallbackServlet` page | Constant HTML body | No stored data interpolated. |
| SPA `ArtifactViewerPage.jsx` | `dangerouslySetInnerHTML` on highlighted JSON | Input is entity-escaped (`& < >`) before the highlighter adds its own spans. The only `dangerouslySetInnerHTML` in `app-shell/src`; guarded by an allowlist. |
| SPA `MarkdownContent.jsx`, `ConversationView.jsx` | React nodes; links: http(s) only, internal paths via `assertInternalPath` | No raw HTML; `javascript:`/`data:` degrade to text. |
| SPA `SupportChatContext.jsx` | Handles support-chat attachments and their MIME types; no HTML rendering | No `innerHTML`/`dangerouslySetInnerHTML`; nothing to encode. |
| SPA `helpDocs.js` | `innerHTML` on a detached div, read back as `textContent` | Not rendered. |
| SPA report iframe (`ReportDrawer.jsx`, `ReportViewerPage.jsx`) | Reads `contentDocument.body.innerHTML` | Read only; content comes from the encoded jsreport output. |
| `schema_forge_core/packages` search (`innerHTML`, `dangerouslySetInnerHTML`) | Library code | No render sites found (only string mentions in `domain-boundary-check.js` and the CSV fixtures). |
| Classic Etendo UI admin panels | `etendo_core`, not in these repos | **Open item.** Not changed here. Question for the issue owner: is a follow-up ticket wanted to audit them? |

## Guardrails

- `EmailEncodeOnOutputTest` (this module) drives the HTML and URL fixtures through `EmailEscape` and
  `EmailLayout`.
- `report-templates-encode-on-output.test.js` (`schema_forge`, and an equivalent in
  `schema_forge_core`) fails if an HTML `.hbs` template adds a raw `{{{ }}}`/`{{&` other than
  `{{{css}}}`, or if a helper uses `SafeString`; each rule is proven against bad fixtures. The
  `schema_forge` test also allowlists `dangerouslySetInnerHTML`.

A new rendering surface is added to the inventory above and to the fixtures first.
