# ADR-0002 — CSP and security headers via CloudFront delivery automation

- **Status:** Proposed
- **Date:** 2026-07-29 · **Revised:** 2026-07-30 (deployment probe evidence)
- **Deciders:** Etendo Go delivery (ETP-4569 assessment)
- **Jira:** ETP-4569 (assessment / this ADR) · ETP-4572 (CSP Report-Only, Delivery 1/3) · ETP-4573 (CloudFront policy automation, Delivery 2/3) · ETP-4574 (enforce in production, Delivery 3/3) · Epic ETP-3504
- **Source:** [PRD — Client & Delivery Security Hardening](https://etendoproject.atlassian.net/wiki/spaces/PYPI/pages/5106892804/), WS-2 — SEC-08, SEC-09, SEC-13
- **Findings:** SEC-08 (High) — no CSP · SEC-09 (Medium-high) — hardening headers inconsistent · SEC-13 (Low-medium) — mixed content unguarded

> Part of the ETP-4569 assessment (PRD phase P0).
>
> **Ownership note.** This ADR records a decision; it does not implement it. **Implementation ownership
> belongs to `schema_forge`**, which owns the CloudFront delivery automation per the PRD — the
> idempotent infra script and the workflow job land there under ETP-4573. The ADR is kept here with the
> rest of the ETP-3504 series (ADR-0001 session — arrives with `feature/ETP-4575`, PR #777;
> [ADR-0003](0003-attachment-authorization.md) attachments;
> [ADR-0004](0004-csv-formula-neutralization.md) CSV) so the series stays contiguous and so the
> [threat model](../security/threat-model.md) can reference all four with links that actually resolve.
> Implementers working in `schema_forge` should reach this document from
> `schema_forge/docs/ops/cloudfront-alb-routing.md`, which is the operational runbook this ADR revises.

---

## Context

### Verified current state (source review 2026-07-29, deployment probes 2026-07-30)

**No CSP exists anywhere, and the hardening headers that do exist are not owned by any repository.**

Source review:

- No `<meta http-equiv="Content-Security-Policy">` in `schema_forge/tools/app-shell/index.html` (no `http-equiv` of
  any kind).
- `schema_forge/infra/cloudfront-functions/` contains exactly **one** file — `etendo-path-rewrite.js`, a
  viewer-request function. There is no response-header layer.
- No global security-header servlet filter in `com.etendoerp.go`. The only backend header of this
  family is `nosniff`, set by `EtendoGoJwtServlet` on its own responses.
- `schema_forge/docs/architecture/07-auth-and-security.md` documents a **recommended** CSP and HSTS that were
  never implemented, and contains no threat-model or trust-boundary content.

Deployment probes (read-only, 2026-07-30) — **these contradict a source-only reading**:

| Environment | CSP | HSTS | XFO | Other |
|---|---|---|---|---|
| Production `go.etendo.cloud` | ❌ none | ✅ `max-age=31536000; includeSubDomains; preload` | `SAMEORIGIN` | `nosniff`, Referrer-Policy, Permissions-Policy present |
| Staging `go.staging.etendo.cloud` | ❌ none | ✅ same | `SAMEORIGIN` | same partial set |
| Experimental `go.experimental.etendo.cloud` | ❌ none | ❌ none | ❌ none | none |
| Staging `/etendo/sws/neo/session` (401) | ❌ none | ❌ none | ❌ none | no `Cache-Control` |

**The decisive observation: no repository artifact explains those headers.** They were configured
out-of-band at the edge. That is why SEC-09 is "partially corrected" rather than closed — the controls
exist but are unowned, undocumented, inconsistent across environments, absent on API responses, and
reversible by anyone with console or deploy-credential access without a review trail.

### Why this ADR's original §D5 had to change

The 2026-07-29 draft treated HSTS as a **rollout decision** to be gated on a subdomain inventory,
because a source-only reading showed no HSTS anywhere. The probes show
`includeSubDomains; preload` is **already live on production and staging**.

That inverts the problem. `includeSubDomains` already applies to **every** `*.etendo.cloud` host, and
`preload` is effectively irreversible on a human timescale. So the inventory is no longer a gate
before a decision — it is a **retroactive verification of a commitment already made**, and it carries
real risk: if any `*.etendo.cloud` subdomain is HTTP-only today, it is already unreachable for users
whose browsers have cached the directive.

### Interaction with ADR-0001

ADR-0001 moved the session to a `__Host-`-prefixed cookie, which **requires `Secure`**. HSTS is
therefore load-bearing rather than cosmetic, and `frame-ancestors 'none'` becomes a real control
because a framed SPA is a clickjacking/CSRF vector against a cookie session. CSP remains **defense in
depth for SEC-10, not its fix** — that was closed by making the credential unreadable from JavaScript.

### Delivery topology

| Fact | Value / source |
|---|---|
| Distributions | **3** — production (`vars.CF_DISTRIBUTION_PRODUCTION`), staging `E2XAO6Y99940X9`, experimental `E2KW4F1IFBTHJY` |
| Origins per distribution | **2** — S3 (the SPA) and the ALB (`/etendo/*` → Tomcat) |
| Who owns AWS credentials | `schema_forge/.github/workflows/deploy-staging.yml` — env selection (`:47-59`), S3 deploy, `create-invalidation` (`:154-155`) |
| Credential type | **Static long-lived keys** (`:110-111`), no OIDC — see TM-01 in the threat model |
| IaC | **None.** No Terraform, CDK or CloudFormation owns these distributions |
| Current change procedure | `schema_forge/docs/ops/cloudfront-alb-routing.md` — `get-distribution-config` → capture `ETag` → edit → `update-distribution --if-match` → poll until `Deployed` → invalidate |

---

## Decision

### D1 — A versioned CloudFront Response Headers Policy, owned by `schema_forge` automation

Create a custom, versioned policy named **`etendo-go-security-headers-v1`** and attach its
`ResponseHeadersPolicyId` to the **default behavior and every relevant cache behavior** of all three
distributions.

- Delivered as an **idempotent infra script** plus an explicit workflow job, following the existing
  `get-distribution-config` / `ETag` / `update-distribution` procedure. Re-running it must be a no-op
  when the policy already matches.
- **The policy must supersede the current out-of-band headers, not coexist with them.** Because the
  existing headers are unowned, the implementation has to reconcile rather than append: adopt the
  observed production values as the baseline where they are correct, and make the policy the single
  source of truth so drift becomes visible in review.
- **Terraform/CDK migration is desirable but does not block this fix.** The workflow already holds the
  credentials and the deploy path.
- **No CORS in this edge policy.** ADR-0001 established same-origin-only with no credentialed CORS.

> Rejected alternative: a `<meta>` CSP in `index.html`. The PRD forbids it for supported deployments,
> and the reasoning is technical: a meta tag cannot provide Report-Only, cannot provide reporting
> endpoints, and cannot express `frame-ancestors` or `sandbox`. It is **not** parity. A future
> non-CloudFront deployment must supply equivalent HTTP response headers as part of its platform
> contract.

### D2 — CSP applies to documents, not to JSON APIs

CSP attaches to HTML/document responses from the S3 origin. `/sws/neo/*` and `/sws/go/*` JSON
responses do **not** need CSP.

Their cache and security headers are a **backend** concern, explicitly out of scope here:
`Cache-Control: no-store` on authenticated NEO JSON is SEC-09b (ETP-4571), and attachment response
headers are ADR-0003 §D7. This boundary matters — the edge cannot distinguish an authenticated JSON
response from a cacheable one, so fixing SEC-09b at the edge would be wrong.

**Note, from the probes:** the backend `401` sample carried none of the hardening headers. Whether the
edge policy should also cover ALB-origin behaviors, or whether the backend sets its own, is an
implementation decision for ETP-4573 — but it must be a decision, not an omission.

### D3 — Starting CSP, tightened from observed traffic

Begin with:

```
default-src 'self';
object-src 'none';
base-uri 'self';
form-action 'self';
frame-ancestors 'none';
```

Then **enumerate each additional source from deployed configuration and observed report-only
traffic** — not from assumptions. Normative rules:

- **Provider endpoints are derived from validated environment configuration and fail closed.** The
  Sentry/GlitchTip host arrives via `VITE_SENTRY_DSN` (`schema_forge/tools/app-shell/src/lib/sentry.js:104`), so it is
  **deployment-specific and cannot be hardcoded**. The CSP must be templated from the same validated
  config the app is built with; missing or invalid config fails the build rather than emitting a
  permissive policy.
- **AWS RUM is hardcoded** to `https://dataplane.rum.eu-west-3.amazonaws.com` (`schema_forge/tools/app-shell/src/lib/rum.js:71`) — a
  stable `connect-src` entry.
- **Mixpanel** is opt-in via `VITE_MIXPANEL_ENABLED`; enumerate its endpoint during report-only.
- **Google SSO** — the identity flow is verified server-side (`EtendoGoGoogleIdentityVerifier`) and no
  Google Identity Services script was found in `schema_forge/tools/app-shell/src`. Whether a
  `script-src`/`frame-src` entry is needed **must be determined empirically**, not assumed either way.
- **Do not add `connect-src blob:` merely because downloads create blob URLs.** Add `blob:` only to
  directives with a demonstrated consumer. CSV export (ADR-0004) and attachment download both create
  blob URLs; those need `img-src`/`object-src`/navigation treatment, not `connect-src`.
- **`upgrade-insecure-requests`** is included, which also serves SEC-13 (see D8).

### D4 — Hardening headers

| Header | Target value | Current state |
|---|---|---|
| `X-Frame-Options` | `DENY` | ⚠️ `SAMEORIGIN` on prod/staging, absent on experimental — must be tightened |
| `Content-Security-Policy` | with `frame-ancestors 'none'` | ❌ absent everywhere |
| `X-Content-Type-Options` | `nosniff` | ✅ prod/staging · ❌ experimental · ❌ API |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | ✅ prod/staging · ❌ experimental · ❌ API |
| `Permissions-Policy` | `camera=(), geolocation=(), microphone=()` | ✅ prod/staging · ❌ experimental |
| `Strict-Transport-Security` | see D5 | ⚠️ already `includeSubDomains; preload` |

`SAMEORIGIN` → `DENY` is a **tightening with breakage potential**: any legitimate self-framing would
stop working. It must be verified in browser tests before enforcement, not assumed safe.

Current OCR and image fields use file selection/upload; no `getUserMedia`, `mediaDevices`, `capture`
or geolocation API is used. A future camera feature must **explicitly** narrow the directive (e.g.
`camera=(self)`) and add browser tests. Denying it now is correct precisely because it forces that
conversation.

### D5 — HSTS: retroactive verification, not a rollout gate

**Revised 2026-07-30.** `Strict-Transport-Security: max-age=31536000; includeSubDomains; preload` is
already live on production and staging.

Consequences, in order of urgency:

1. **The subdomain inventory becomes remediation, not a gate.** It must enumerate every
   `*.etendo.cloud` host and confirm each is HTTPS-only. Any HTTP-only host found is **already
   broken** for users whose browsers cached the directive — that is an incident to triage, not a
   decision to make.
2. **Confirm whether the domain is actually on the HSTS preload list.** The `preload` token being sent
   is a *request* to be included, not proof of inclusion. Submitted-and-accepted is effectively
   permanent; sent-but-not-submitted is still reversible. **These are very different risk positions
   and the difference must be established before anything else in this ADR ships.**
3. **Bring the policy under repository ownership** at the observed values, so the current
   configuration stops being invisible.
4. **Experimental has no HSTS.** Decide deliberately: extend it (consistency) or document the
   exemption (it may intentionally serve non-HTTPS test scenarios).

The original gate ordering — inventory before `includeSubDomains` — is retained for any **future**
scope expansion, and remains the correct pattern. It simply cannot be applied retroactively to a
directive already published.

### D6 — Caching policy

- HTML entry points: `Cache-Control: no-store` or a documented revalidation policy. *Probes show HTML
  already returns `no-cache, no-store, must-revalidate` — adopt and own it.*
- Content-hashed assets: `public, max-age=31536000, immutable`. *Already observed on staging hashed JS
  — adopt and own it.*
- `.well-known` resources: caching defined **per resource**, not blanket `no-store` — the OAuth2
  protected-resource metadata is a legitimately cacheable public document.

### D7 — Report-only first, then enforce behind a reversible flag

1. Deploy the policy **Report-Only in staging**.
2. **Sanitize reports** so URLs and query strings do not leak secrets or PII. Not optional
   bookkeeping: violation reports carry blocked-URI and document-URI, exactly the data class WS-3
   (SEC-14) exists to keep away from third parties. The collector is subject to the same egress policy
   and is itself a telemetry egress point.
3. Establish an **expected-violation baseline**.
4. Soak, then enforce behind a **reversible deployment flag**.

### D8 — Mixed content (SEC-13): CI guard plus CSP

Source review found no hardcoded `http://` resource URLs in `schema_forge/tools/app-shell/src`. However, the
**deployed staging bundle still contains literal localhost HTTP endpoints**, and no active violation
was executed by a static probe — so this is "no violation reproduced", not "clean".

Closed by prevention: a **CI guard** rejecting hardcoded `http://` resource URLs and localhost
literals reaching a production bundle, plus `upgrade-insecure-requests` in the CSP. Browser-flow
verification remains required.

---

## Consequences

**Positive**
- Closes SEC-08; brings SEC-09 from "unowned partial controls" to a reviewed, versioned policy.
- One versioned, idempotent policy across three distributions ⇒ no per-environment drift, and drift
  becomes reviewable instead of invisible.
- Reuses credentials, deploy path and ETag procedure the repo already owns.
- `frame-ancestors` and HSTS materially reinforce the ADR-0001 cookie session.

**Negative / costs**
- **CSP can break telemetry, SSO, blob previews and OCR.** Main risk; the reason for the report-only
  soak. Enforcing without it is the most likely way to cause a production outage in this epic.
- **`SAMEORIGIN` → `DENY` and adopting out-of-band headers are behavior changes**, not additions. The
  reconciliation step can regress a currently-working environment.
- HSTS `preload` may already be irreversible (D5).
- AWS CLI + ETag editing is race-prone: a concurrent change invalidates the ETag. The script must fail
  cleanly and re-read rather than retry blindly.
- Templating the CSP from build-time config couples delivery policy to the app build.
- **TM-01 caveat:** static deploy credentials mean the policy this ADR introduces can be silently
  detached by anyone holding those keys. The CSP's integrity depends on fixing TM-01.

**Neutral**
- No application code change beyond the CI guard and any source enumeration fixes.
- `etendo-path-rewrite.js` (viewer-request) is unaffected; response headers are a separate mechanism.

---

## Testing

Per PRD §6, unit tests alone are insufficient for headers.

**Automated header assertions** — exact values on an HTML document response, a content-hashed asset,
and a representative error response (SPA 404 and a backend 401). A third-party header score may be
attached as evidence but **is not the acceptance gate**; the gate is the asserted exact values.

**Cross-environment parity test** — the same assertions must run against all three distributions.
The current drift existed precisely because nothing checked for it.

**Browser tests** — no unexpected CSP or mixed-content violations across password login, Google SSO,
telemetry emission, attachment download, attachment inline preview, OCR, and error flows. Playwright
per `schema_forge/docs/e2e-testing-guide.md`; delegate spec authoring to the Tester agent per `schema_forge`'s
CLAUDE.md rule.

**Rollback drill** — enforced CSP must have a documented, exercised rollback.

---

## Open questions for ETP-4572 / 4573 / 4574

1. **Is `etendo.cloud` on the HSTS preload list?** Highest-priority unknown (D5.2). Determines whether
   the current HSTS scope is reversible.
2. **Who configured the existing headers, and where?** Until that is known, the reconciliation in D1
   risks fighting an out-of-band process that will reapply its own values.
3. **Production distribution ID** (`vars.CF_DISTRIBUTION_PRODUCTION`) was not read during the
   assessment; needed to complete the inventory.
4. **Should ALB-origin responses get the policy too?** The backend `401` sample had no hardening
   headers (D2).
5. **Is `'unsafe-inline'` needed for `style-src`?** Vite-built React apps often inject inline styles.
   Measure in report-only; if needed, scope to `style-src` only, **never** `script-src`.
