# CSP Report-Only: policy, inventory and report handling (ETP-4572)

Implements ADR-0002 D3/D4/D7 for Delivery 1/3. Headers are attached at the edge in ETP-4573;
this delivery supplies the policy value and the report sanitizer.

- `CspPolicy.reportOnlyValue(reportUri, extraConnectOrigins)` builds
  `Content-Security-Policy-Report-Only`: `default-src 'self'; object-src 'none'; base-uri 'self';
  form-action 'self'; frame-ancestors 'none'; connect-src 'self' <AWS RUM> <deployment origins>;
  upgrade-insecure-requests; report-uri <collector>`. Invalid origins throw (fail closed).
- `CspPolicy.PERMISSIONS_POLICY` = `camera=(), geolocation=(), microphone=()`.
- `CspReportSanitizer.sanitize(body)` accepts legacy `csp-report` and Reporting API payloads and
  stores only: blockedUri, documentUri, effectiveDirective, disposition, classification. URLs lose
  userinfo, query and fragment; token-like or e-mail path segments become `[redacted]`; `blob:` and
  `data:` keep only the scheme; script samples, referrers and the original policy are dropped.

## Inventory (what must be verified in staging soak)

| Area | Expected treatment | Classification |
|---|---|---|
| Telemetry: AWS RUM | `connect-src` entry (hardcoded) | `INVENTORY_TELEMETRY` if reported |
| Telemetry: Sentry/GlitchTip | origin from validated `VITE_SENTRY_DSN`, passed as extra origin | `UNEXPECTED` until configured |
| Telemetry: Mixpanel (opt-in) | endpoint measured during soak | `UNEXPECTED` until added |
| SSO (Google) | verified server-side; no script/frame entry assumed, determine empirically | `UNEXPECTED` |
| Blobs: attachment preview, CSV export | `img-src`/`object-src`/navigation only, never `connect-src` | `INVENTORY_BLOB` |
| Browser extensions | not actionable | `EXPECTED_EXTENSION` |
| Anything else | triage during soak | `UNEXPECTED` |

Not covered here: serving the headers and the collector HTTP endpoint wiring (ETP-4573), and the
staging Playwright soak (login, SSO, OCR, attachments, telemetry), which needs a deployed staging.
