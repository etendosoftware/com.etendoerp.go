# CSP Report-Only: policy, inventory and report handling (ETP-4572)

Implements ADR-0002 D3/D4/D7 for Delivery 1/3. The policy is versioned in
[`csp-report-only.json`](csp-report-only.json), which the edge automation (ETP-4573) consumes to
attach the headers; `CspPolicyTest` fails when `CspPolicy` and that file differ.

## Policy

- `Content-Security-Policy-Report-Only`: `default-src 'self'; object-src 'none'; base-uri 'self';
  form-action 'self'; frame-ancestors 'none'; img-src 'self' blob: data:; frame-src 'self' blob:;
  connect-src 'self' <AWS RUM> <deployment origins>; report-uri /sws/go/csp-report;
  report-to csp-endpoint`.
- `Reporting-Endpoints: csp-endpoint="/sws/go/csp-report"` accompanies it (`report-to`).
- `Permissions-Policy: camera=(), geolocation=(), microphone=()`.
- `upgrade-insecure-requests` is omitted: browsers ignore it in Report-Only. It is added at
  enforcement (ETP-4574).
- Deployment origins (e.g. the host of `VITE_SENTRY_DSN`) are validated https origins passed at
  render time; invalid input throws instead of loosening the policy.

## Collector

`CspReportServlet` (`/sws/go/csp-report`, unauthenticated, registered in `AD_MODEL_OBJECT*`)
always answers 204. `CspReportCollector` caps the body at 16 KB, rate-limits per client and
globally, runs `CspReportSanitizer`, and logs only the sanitized fields (one line per report, plus
a counter per classification). Raw bodies are never logged or echoed, including rejected ones.

`CspReportSanitizer` accepts legacy `csp-report` and Reporting API payloads and keeps only
blockedUri, documentUri, effectiveDirective, disposition and classification. URLs lose userinfo,
query and fragment (presigned-URL signatures included); token-, UUID-, record-ID-, JWT- and
e-mail-like path segments become `[redacted]`; `blob:` and `data:` keep only the scheme.

## Inventory and classification (to verify in the staging soak)

| Area | Treatment | Classification |
|---|---|---|
| Telemetry: AWS RUM | `connect-src` entry (hardcoded) | `INVENTORY_TELEMETRY` if reported |
| Telemetry: Sentry/GlitchTip | origin from validated `VITE_SENTRY_DSN`, passed at render time | `UNEXPECTED` until configured |
| Telemetry: Mixpanel (opt-in) | endpoint measured during soak | `UNEXPECTED` until added |
| SSO (Google) | identity verified server-side; no script/frame entry allowed | `INVENTORY_SSO` (Google sign-in hosts) |
| Blobs: attachment/print/report previews, CSV export | `blob:` in `img-src` and `frame-src`; never `connect-src`; `object-src` stays `'none'` | `INVENTORY_BLOB` |
| Same-origin / `data:` previews in `frame-src`, `img-src`, `object-src`, `media-src`, `child-src` | allowed by `'self'`/`data:` where listed; reported otherwise | `INVENTORY_PREVIEW` |
| Browser extensions | not actionable | `EXPECTED_EXTENSION` |
| Anything else | triage during soak | `UNEXPECTED` |

Not covered here: attaching the headers at the edge (ETP-4573) and the staging Playwright soak
(login, SSO, OCR, attachments, telemetry), which needs a deployed staging with the header applied.
