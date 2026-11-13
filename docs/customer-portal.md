# Customer portal — `/sws/portal/*` (ETP-5267, ETP-6209)

Runtime reference for the read-only portal a tenant's customer (Business Partner) opens from a
link in a sales document email. The design, the decisions and their rationale are in the
functional repo: `etendo_schema_forge/docs/plans/2026-11-11-customer-portal-sales-documents.md`
(the ETP-5267 MVP plan, `2026-09-10-bp-self-service-portal.md`, is historical). The exact response
bodies are pinned in the javadoc of `PortalServlet`; this page is the map.

Code: `src/com/etendoerp/go/portal/` (servlet, link service, DAL, queries, rate limiter, KPI
events) plus two shared pieces outside it — the document-send email contracts
(`transactional-email-contracts.md` → *Customer portal link*) and
`schemaforge/AnonymousAttachmentStreamer` (the PDF, §5).

## 1. The link model — `ETGO_PORTAL_LINK`

One row per email that carried a link (`PortalLink` / `PortalLinkDal`). It replaces the MVP's
`ETGO_PORTAL_ACCESS` (one stable row per Business Partner), which is dropped.

| What | How |
|---|---|
| Token | 256-bit `SecureRandom`, base64url, written into that one email; only its SHA-256 (`TOKEN_HASH`, unique) is stored. `etendo.go.portal.tokenSecret` is no longer read |
| Scope | `AD_CLIENT_ID` + `C_BPARTNER_ID` of the document the link was sent for. The portal never takes a client or Business Partner from the request (`PortalSession` has no public constructor) |
| Document | `AD_TABLE_ID`, `RECORD_ID`, `DOCUMENT_KIND` (`QUOTATION`, `ORDER`, `SHIPMENT`, `INVOICE`; a credit note is `INVOICE`) |
| Recipients | `RECIPIENTS_TO`, `RECIPIENTS_CC`, `EMAIL_LANGUAGE` of the send — a renewal goes back to exactly these |
| Lifecycle | Minted **pending** (`SENT_AT` null) while the email is built; confirmed by `EmailContract#onDelivered` only on a `SENT` outcome (`SENT_AT`, `EXPIRES_AT = SENT_AT + TTL`, `ETGO_EMAIL_SEND_LOG_ID`). A pending row is invalid forever |
| Chain | `LINK_ORIGIN` = `SEND` or `RENEWAL`; `PARENT_LINK_ID` (the expired link a renewal replaced) and `ROOT_LINK_ID` (the original send — the KPI unit). Loose references, no DB foreign key, on purpose |
| Usage | `FIRST_USED`, `LAST_USED` |

Each link lives **48 hours** from its send (`etendo.go.portal.link.ttlMinutes`,
`ETGO_PORTAL_LINK_TTL_MINUTES`). A new send never touches older rows. Which emails mint a link, and
the `bp-portal-link` gate, are in `transactional-email-contracts.md` and
`feature-flags-and-tenant-upgrade.md` (*Per-account targeting*). The endpoints below never read
the flag.

**No revoke action.** The MVP's backend-only revoke (`SFPortalAccess`, pseudo-spec
`portalaccess`) is removed. To stop a link already out, deactivate its row (`ISACTIVE = 'N'`): it
answers the same 401 as an unknown token at once and cannot be renewed.

## 2. Endpoints

All under the portal's own servlet, isolated from NEO (never reachable through `NeoServlet` or the
CRUD engine), reachable with no Etendo session. Every response is `no-store`.

| Endpoint | Answers |
|---|---|
| `GET /me[/{token}]` | The link's state: `valid` (Business Partner name, tenant name, `hasLogo`, `entryDocument` or `null` + `entryKind` when the link's own document left its scope), `expired`, `unavailable`; or the 401 below |
| `POST /renew` | For an expired link: `{state:"renewed", renewal:"sent"}`, `{state:"renewed", renewal:"recentlySent"}` (cool-down, nothing sent), `{state:"unavailable"}`, `{state:"valid"}` (not expired), `503` when the email could not be sent |
| `GET /logo` | The issuing company's logo, resolved from the link's organization; served for any sent link, so the expired page keeps the branding |
| `GET /home` | Pending-payment balances, one tile per kind (count + highlight), recent documents — server-side aggregates |
| `GET /documents?type=&status=&dateFrom=&dateTo=&limit=&offset=` | One kind's list, filtered and paged (default 50, max 200), with invoice payment fields |
| `GET /documents/{kind}/{id}` | Read-only detail, related documents (each re-checked against the scope), `hasPdf` |
| `GET /documents/{kind}/{id}/pdf` | The document's printable (§5) |

**Invalid** — unknown, pending, deactivated or malformed token — is one byte-identical answer:
`401 {"error":"This link is no longer valid"}`. Only a link that was really emailed can answer
`expired` or `unavailable`. On an expired or unavailable link the data reads (`/home`,
`/documents*`) answer that same 401; the page then re-checks `/me` once. A document outside the
scope, of the wrong kind or in a hidden status is **404, never 403**.

### Visible documents

Sales side, active, the link's tenant and Business Partner, and a per-kind status **allow-list**
(an unknown status is hidden by default), all in `PortalDocumentQuery`:

| Kind | Source | Visible `DocStatus` |
|---|---|---|
| Quotation | `C_Order`, `SOSubType` in (`OB`, `ON`) | `UE`, `CO`, `CA`, `ETGO_CI`, `CL`, `CJ`, `VO` |
| Order | `C_Order`, other subtypes, return-material orders (`RM`) excluded | `CO`, `CL`, `VO` |
| Shipment | `M_InOut`, not a customer return | `CO`, `CL`, `VO` |
| Invoice / credit note | `C_Invoice` | `CO`, `CL`, `VO` |

Drafts and pre-completion states are never shown. The scope is client-wide (every tenant has a
single organization today).

## 3. The token header — `X-Portal-Token`

The page sends the token in **`X-Portal-Token`** on every request after the first load.
`PortalServlet#resolveToken` reads, in order: `X-Portal-Token`, the `/me/{token}` path segment,
then `Authorization: Bearer` (legacy). Not `Authorization` first, because a browser that also holds
an Etendo GO session would send its own credential there: the SPA's request helper drops an
explicit token under the cookie scheme, and the portal answered "invalid" to a valid link. The
token never goes in a query string and is never logged; requests are identified by the link id.
The header passes through CloudFront and the ALB untouched.

## 4. Renewal and the closing checks

Opening an expired link makes the page call `POST /renew` (a POST, so link scanners that pre-fetch
GETs never send an email). `PortalLinkService#renew`:

1. Applies the **closing checks** (`PortalAccessChecks`). Any hit answers `unavailable`, no row,
   no email. The reason goes to the log and the KPI `errorCode`, never to the customer:

   | `errorCode` | Condition |
   |---|---|
   | `BLOCKED_CUSTOMER` | `C_BPartner.Customer_Blocking = 'Y'` (the master flag only) |
   | `INACTIVE` | `C_BPartner.IsActive = 'N'` (an inactive `AD_User` contact is not a block) |
   | `PLAN_EXPIRED` | `EnvironmentAccessGuard#checkAsSystem(clientId, "portal")` returns a denial; a legacy tenant (no decision) is allowed, and the enforcement kill switch applies |

   The same checks also close a **still-valid** link (decision D-1): `/me` answers `unavailable`.
2. Locks the expired link's row (`SELECT … FOR UPDATE`) and applies the limits, counting renewals by
   creation, pending ones included — so the cool-down holds across requests and nodes. A second
   open waits for the first one's commit and then answers `recentlySent`.
3. Mints a `RENEWAL` row chained to the expired one and sends `portal-link-renewal` to the
   **original To and CC** — never to whoever opened the link — in the original language.

| Property (env) | Default | Meaning |
|---|---|---|
| `etendo.go.portal.renewal.cooldownMinutes` (`ETGO_PORTAL_RENEWAL_COOLDOWN_MINUTES`) | 15 | At most one renewal per link per cool-down; inside it the answer is `recentlySent` and nothing is sent |
| `etendo.go.portal.renewal.maxPerDay` (`ETGO_PORTAL_RENEWAL_MAX_PER_DAY`) | 0 = no limit | Optional cap per Business Partner per rolling 24 h |

An expired link can be renewed at **any age** today, and nothing purges old rows; both wait for a
PO answer (plan → *Still open*).

## 5. The PDF — `AnonymousAttachmentStreamer`

`/pdf` and the detail's `hasPdf` serve the document's **main attachment**, the same file the
email's download link serves. Both callers share `schemaforge/AnonymousAttachmentStreamer`:
`findMainAttachmentOfClient` (native SQL plus an explicit check that the attachment belongs to the
caller's client — the link's here, the signed token's there) and `stream`, which bypasses
`AttachImplementationManager.download()` (ETP-5304). A session-less request cannot use
`NeoAttachmentsHelper.handleGetMain` or `NeoAttachmentsDownloader`: both resolve through the
session's tenant. A document never emailed or printed has no main attachment: `hasPdf:false`.
A stream failure answers a `text/plain` 500 (the email link's format), unlike the portal's other
JSON errors — a known optional cleanup. Details: `neo-headless.md`, *Shared with the customer
portal (ETP-6209)*.

## 6. Rate limits

`PortalRateLimiter`, keyed on `HttpServletRequest#getRemoteAddr`, one in-memory counter each,
`429` over the ceiling:

| Endpoints | Per client IP, per minute |
|---|---|
| `GET /me` | 30 |
| `POST /renew` | 30 |
| `GET /home`, `GET /documents*` (shared) | 120 |

**Production (review W6, resolved by infra with no code change).** Traffic goes browser →
CloudFront → ALB → Tomcat. Infra added `RemoteIpValve` to the Tomcat image, live from the next
production deploy: `getRemoteAddr()` then returns the real client IP, so the limits are per client
(before it, they counted per proxy address). A single `etendo-core` task runs (two briefly during a
deploy), so the counters are effectively fleet-wide and reset on every deploy; scaling out would
make each task count on its own. Do not parse `X-Forwarded-For` in the portal — the valve is the
one trusted place that reads it.

## 7. KPI events

Four server-only types in `UsageEventTypes` (a browser cannot submit them), source `backend`,
`sessionKey` = `ROOT_LINK_ID` so a renewal counts for its original send. Emitted by
`PortalUsageEvents` after the business write.

| Type | When |
|---|---|
| `portal.link.sent` | A link is confirmed after its email left (`SEND` origin) |
| `portal.link.opened` | `/me` answers `valid` |
| `portal.link.renewed` | Ok when the renewal link is confirmed after its email left; refused with `errorCode` `BLOCKED_CUSTOMER`, `INACTIVE`, `PLAN_EXPIRED`, `COOLDOWN`, `DAILY_LIMIT`, the email status (`DUPLICATE`, `THROTTLED`) or `SEND_FAILED` |
| `portal.document.downloaded` | `/pdf` after the file was actually streamed (a failed read emits nothing) |

Metric queries, the per-send vs per-document reading and the link-scanner caveat: plan §A.7. The
metric definitions are pending PO confirmation.

## 8. Tests

`src-test/src/com/etendoerp/go/portal/` (`PortalLinkServiceTest`, `PortalServletTest`,
`PortalLinkDalTest`, `PortalDocumentQueryTest`, `PortalDocumentsEndpointTest`,
`PortalDocumentLinksTest`, `PortalRateLimiterTest`, `PortalUsageEventsTest`),
`schemaforge/AnonymousAttachmentStreamerTest`, and
`schemaforge/email/contracts/PortalLinkRenewalEmailContractTest`. The feature's test list is kept in
the functional repo's `flags-registry.json` (`bp-self-service-portal`).
