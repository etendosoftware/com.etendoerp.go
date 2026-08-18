# ETP-4569 security findings revalidation

- **Assessment date:** 2026-07-30
- **Target:** current source on `epic/ETP-3504` plus public deployment probes
- **Staging host:** `https://go.staging.etendo.cloud`
- **Source of record:** [Client & Delivery Security Hardening PRD](https://etendoproject.atlassian.net/wiki/spaces/PYPI/pages/5106892804/)
- **Jira:** ETP-4569 · Epic ETP-3504

## Method and limits

The assessment used source review in `com.etendoerp.go`, `schema_forge` and `schema_forge_core`,
public HTTP response/header probes, and static inspection of the deployed staging bundle. It did not
use production credentials, mutate data, enumerate attachment IDs, or capture authenticated provider
traffic.

The original `contacts-test-report.md` referenced by the PRD is not present in the workspace. The
approved PRD is therefore the source of record for the original findings.

Status meanings:

- **Reproduced:** the vulnerable contract is present in current source or public deployment evidence.
- **Partially corrected:** at least one material control exists, but the PRD invariant is not closed.
- **Not reproduced:** the tested condition was absent in the evidence available on the assessment date.

## Findings

| Finding | Status on 2026-07-30 | Evidence | Implementation owner |
|---|---|---|---|
| **SEC-04 — CSV formula injection** | **Partially corrected** | `NeoCsvExportService` gained classic `= + - @` neutralization in ETP-4560 and `app-shell-core` gained the matching client helper in ETP-4559. The fiscal-monitor builder still only quote-escapes values. Neither implementation covers the agreed full-width variants, and TAB/CR/LF are skipped as leading whitespace rather than treated as standalone triggers. | ETP-4568, unassigned / `TBD` |
| **SEC-08 — no CSP** | **Reproduced** | No CSP header was returned by production, staging or experimental HTML. No CSP is owned in `infra/` or the deployment workflow. | ETP-4572, unassigned / `TBD` |
| **SEC-09 — hardening headers** | **Partially corrected** | Production and staging return HSTS, `nosniff`, Referrer-Policy and Permissions-Policy, but use `X-Frame-Options: SAMEORIGIN`; experimental returns none of them. The representative backend `401` response also lacks them. No repository-owned policy explains the drift. | ETP-4573/4574, unassigned / `TBD` |
| **SEC-09b — NEO JSON cache control** | **Reproduced** | `NeoServlet.writeResponse()` copies opt-in response headers but sets no default `Cache-Control`. Public `GET /etendo/sws/neo/session` returned `401 application/json` without `Cache-Control`. Authenticated `/session` still requires a credentialed confirmation after the fix lands. | ETP-4571, unassigned / `TBD` |
| **SEC-10 — browser-readable session token** | **Reproduced** | The deployed staging bundle contains `sf_auth_token`, `sf_platform_token` and many `Authorization: Bearer` construction sites. `app-shell-core/src/auth/session.js` defaults to `window.localStorage`. ETP-4575/4576 are in progress but are not deployed closure evidence. | Román Magnoli, ETP-4575/4576 |
| **SEC-11b — attachment IDOR** | **Reproduced — automated** | `NeoServlet.processRequest()` enables admin mode for the built-in dispatch. List disables readable-org filtering; download/delete/description load an attachment by bare ID; upload/download-all trust supplied parent context. No centralized parent-record authorization exists. Reproduced executably by `NeoAttachmentAuthorizationMatrixTest` (see Automated evidence). The black-box confirmation is still pending provisioned low-privilege accounts. | ETP-4570, unassigned / `TBD` |
| **SEC-12 — attachment response/upload hardening** | **Partially corrected** | `Content-Disposition` is set. Single download still echoes stored MIME via `resolveContentType`, neither single nor zip download sets `nosniff`, and upload only validates the outer multipart type before materializing the file. | ETP-4571, unassigned / `TBD` |
| **SEC-13 — mixed content** | **Partially corrected / no active violation reproduced** | All three public entry points are HTTPS and no active HTTP request was executed by this static probe. The deployed staging bundle still contains literal localhost HTTP endpoints, and there is no CI guard or enforced `upgrade-insecure-requests` CSP. Browser-flow verification remains required. | ETP-4572/4574, unassigned / `TBD` |
| **SEC-14 — telemetry egress governance** | **Reproduced** | Sentry, AWS RUM and Mixpanel adapters remain in the functional host. The KPI/property sanitizer does not cover Sentry errors, breadcrumbs, context, SDK-generated URLs/headers or final provider envelopes. `VITE_SENTRY_SEND_DEFAULT_PII` can still enable PII at build time. Core observability remains a context/no-op rather than a deny-by-default gateway. | ETP-4577/4578, unassigned / `TBD` |

## Public deployment evidence

Collected with read-only requests on 2026-07-30.

| Probe | Result |
|---|---|
| Production `/` | `200 text/html`; no CSP; hardening headers present; HTML `no-cache, no-store, must-revalidate` |
| Staging `/` | Same partial header set as production; no CSP |
| Experimental `/` | `200 text/html`; no CSP or hardening headers |
| Staging hashed JS | `public, max-age=31536000, immutable`; same partial hardening set |
| Staging SPA fallback | `200 text/html`; no CSP; HTML no-store behavior |
| Staging `/etendo/sws/neo/session` without credentials | `401 application/json`; no `Cache-Control`; no edge hardening headers |
| Staging bundle strings | Legacy storage keys, Bearer construction sites and localhost HTTP endpoints present |

## Automated evidence

- `./gradlew test --tests com.etendoerp.go.schemaforge.NeoCsvExportServiceTest` — passed on 2026-07-30. This verifies the existing ETP-4560 classic-trigger mitigation; it does not close the remaining SEC-04 paths.
- `node --test packages/app-shell-core/src/lib/csv/__tests__/csvSerializer.test.js` — 23 tests passed on 2026-07-30. The standalone control/full-width fixtures remain red requirements for ETP-4568.
- `./gradlew test --tests com.etendoerp.go.schemaforge.NeoAttachmentAuthorizationMatrixTest` —
  9 tests, 5 passed, 4 skipped, 0 failures on 2026-07-30. This is the **E5 deliverable**: the five
  passing tests reproduce SEC-11b by pinning the current insecure contract (a foreign attachment is
  streamed, deleted and re-described with no authorization primitive consulted; stored MIME echoed
  without `nosniff`; missing distinguishable from unauthorized). The four skipped tests express the
  ADR-0003 target contract and stay disabled until ETP-4570 lands.

  **Declared coverage limit (no silent caps):** scenarios S2–S5 are proven collectively rather than
  individually, because the code never inspects caller context — that collapse is the finding.
  Separating S2/S3/S4/S5 by real role and organization data, plus S8 (legitimate multi-org access),
  requires `OBBaseTest` fixtures and remains an external dependency below. `handleList`,
  `handleUpload` and `handleDownloadAll` are not covered, so S7 belongs to ETP-4570.

## Residual risks and accountable follow-ups

| Risk | Accountable follow-up |
|---|---|
| SEC-10 remains exposed until both in-progress tasks are deployed and legacy Bearer is disabled | Román Magnoli — ETP-4575/4576 |
| SEC-04/08/09/09b/11b/12/13/14 implementation tasks have no assignee | Epic owner / reporter Sebastian Barrozo |
| HSTS `includeSubDomains; preload` is already live without a repository inventory or rollback record | ETP-4574 owner |
| The attachment matrix is automated only to the depth mocks allow; per-scenario role/org separation (S2–S5), S8 and the client-supplied-parent cases (S7) need seeded `OBBaseTest` fixtures, and the black-box confirmation needs provisioned low-privilege accounts | ETP-4570 owner plus staging administrator |
| Serialized telemetry envelopes were not captured with authenticated staging traffic | ETP-4577/4578 owner plus Security/Privacy |
| Blanket admin mode may expose built-in operations beyond attachments | Follow-up security audit required |

## ADR review outcome

| ADR | Assessment outcome |
|---|---|
| ADR-0001 — backend-managed session | Accepted target on `feature/ETP-4575` / PR #777. Its cookie, CSRF, rotation, expiry, environment-switch and logout contracts match the PRD. Current staging remains legacy and is not marked closed. |
| ADR-0002 — edge security headers | Proposed in `schema_forge`. Updated for the partial headers already live on production/staging and the missing experimental/API coverage. HSTS inventory is a release gate. |
| ADR-0003 — attachment authorization | Proposed. Centralized parent-record authorization, normal RBAC, narrow admin scope, uniform `404` and the eight-scenario/six-operation matrix are normative for ETP-4570/4571. |
| ADR-0004 — CSV neutralization | Proposed. Updated to recognize ETP-4559/4560 as partial fixes while retaining fiscal-monitor, standalone control, full-width and compatibility-fixture scope for ETP-4568. |

Security/architecture sign-off is still required before changing any ADR status to Accepted.

## Exit criteria for ETP-4569

- ADRs 0001–0004 are reviewed against this current-state table.
- The STRIDE model and assessment inventories are linked from the ticket.
- The missing black-box/credentialed evidence is recorded as an explicit external dependency, not
  represented as a passing result.
- Implementation tasks consume these red findings; closure is verified later in ETP-4579.
