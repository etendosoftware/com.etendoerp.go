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
`ETGO_PORTAL_ACCESS` (one stable row per Business Partner).

**`ETGO_PORTAL_ACCESS` is kept, unused.** The table, its model XML and its dictionary rows
(`AD_TABLE`, `AD_COLUMN`, `AD_ELEMENT`) stay exactly as they are on `develop`, so
`update.database` leaves the table and any rows in it untouched: it shipped to `main` with the MVP
and nobody has checked yet whether production holds rows. Nothing reads or writes it any more —
`PortalAccessService`, `PortalAccessDal`, `SFPortalAccess` and the `portalaccess` pseudo-spec are
removed; only the DAL class `generate.entities` builds from the dictionary remains. It will be
dropped in a follow-up once production is confirmed empty.

| What | How |
|---|---|
| Token | 256-bit `SecureRandom`, base64url, written into that one email; only its SHA-256 (`TOKEN_HASH`, unique) is stored. `etendo.go.portal.tokenSecret` is no longer read |
| Scope | `AD_CLIENT_ID` + `C_BPARTNER_ID` of the document the link was sent for. The portal never takes a client or Business Partner from the request (`PortalSession` has no public constructor) |
| Document | `AD_TABLE_ID`, `RECORD_ID`, `DOCUMENT_KIND` (`QUOTATION`, `ORDER`, `SHIPMENT`, `INVOICE`; a credit note is `INVOICE`) |
| Recipients | `RECIPIENTS_TO`, `RECIPIENTS_CC`, `EMAIL_LANGUAGE` of the send — a renewal goes back to exactly these |
| Lifecycle | Minted **pending** (`SENT_AT` null) while the email is built; confirmed by `EmailContract#onDelivered` only on a `SENT` outcome (`SENT_AT`, `EXPIRES_AT = SENT_AT + TTL`, `ETGO_EMAIL_SEND_LOG_ID`). A pending row is invalid forever |
| Chain | `LINK_ORIGIN` = `SEND`, `RENEWAL` or `DECISION` (minted by a confirmed quotation decision, §6b); `PARENT_LINK_ID` (the expired link a renewal replaced) and `ROOT_LINK_ID` (the original send — the KPI unit). Loose references, no DB foreign key, on purpose |
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
| `GET /home?months=` | Pending-payment balances, one tile per kind (count + highlight: quotations awaiting an answer, overdue invoices, orders in progress, shipments this month), recent documents, `paidThisYear` (per currency, `Invoice.totalPaid` of this year's completed/closed invoices), `invoicesThisYear` (`total`, `upToDate`) and `chart` (invoiced per month over `months` = 3, 6 or 12, default 12, in the currency invoiced in most months) — server-side aggregates |
| `GET /search?q=` | Documents of every kind matching `q` (number, reference, description), newest first, 8 at most; blank `q` answers an empty list |
| `GET /documents?type=&group=&status=&dateFrom=&dateTo=&q=&limit=&offset=` | One kind's list, filtered and paged (default 50, max 200), with invoice payment fields, a quotation's generated order (`orderId`, `orderNo`) and the kind's `groups`; `group` (one of the kind's status groups, below) replaces `status` |
| `GET /documents/{kind}/{id}` | Read-only detail, related documents (each re-checked against the scope), `hasPdf` |
| `GET /documents/{kind}/{id}/pdf` | The document's printable (§5) |

**Invalid** — unknown, pending, deactivated or malformed token — is one byte-identical answer:
`401 {"error":"This link is no longer valid"}`. Only a link that was really emailed can answer
`expired` or `unavailable`. On an expired or unavailable link the data reads (`/home`, `/search`,
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

**Status groups** (`?group=`, the page's list tabs, `PortalDocumentQuery.GROUPS`). Each is a
subset of the kind's allow-list (intersected again at query time, so a group can never widen what
is visible) plus, where the status alone cannot say it, a condition on the document:

| Kind | Group | Statuses | Condition |
|---|---|---|---|
| Quotation | `awaiting` | `UE`, `CO` | — |
| | `accepted` | `CA`, `ETGO_CI` | — |
| | `rejected` | `CJ` | — |
| Order | `inProgress` | `CO` | a line with `orderedQuantity ≠ deliveredQuantity` or `≠ invoicedQuantity` |
| | `completed` | `CO`, `CL` | `CL`, or no such line |
| | `voided` | `VO` | — |
| Shipment | `delivered` | `CO`, `CL` | — |
| | `voided` | `VO` | — |
| Invoice | `pending` | `CO`, `CL` | `outstandingAmount ≠ 0` |
| | `overdue` | `CO`, `CL` | an instalment past due (server day) with money owed |
| | `paid` | `CO`, `CL` | `outstandingAmount = 0` |

No group (or an unknown one) is every visible status. The home's tiles count the same sets
(`awaiting`, `overdue`, `inProgress`), so a tile and its tab always agree.

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
session's tenant.

**A document with no stored PDF yet** (never emailed nor printed; user decision 2026-11-13) is
rendered on its first `/pdf` request by `PortalPrintable`, with the MCP's server-side render
(`NeoPrintableRenderer` on `NeoPrintAfterComplete`'s worker, ETP-6013) — not a seventh producer:
same template the frontend publishes, same data builder, same jsreport, stored the same way as the
record's main attachment (`<spec>-<documentNo>.pdf`, `markAsMain`), so the next open, the email
link and the app's preview reuse it and the app's freshness rule applies.

- **Only for an in-scope document:** the servlet calls it after `PortalDocumentQuery#findInScope`
  returned the document, and only through the data rate limiter.
- **Identity** (`NeoPrintAfterComplete#renderAndWaitAs`): a portal request has no session, and the
  worker never falls back to an administrator. It runs as the user who **sent** the document — the
  `CREATEDBY` of the link's root row (a renewal is minted by the system and names no one) — with
  that user's default role, else their first active role, in the link's client; the link's client;
  the document's own organization; the link's email language. The worker's D23 check still
  applies (the role must be able to write the document's attachments), and a file a person marked
  main is never replaced. No sender, no role, or a system creator: no render.
- **One render per document:** concurrent opens join the render in flight in this JVM; across
  nodes the worker's `pg_advisory_xact_lock` and re-check store one file. The request waits at most
  the worker's bound (40 s).
- **Never a 500 for this:** not configured (no jsreport / printables location), not printable, not
  allowed, timed out or failed → the same 404 as before, and the page shows its data sheet with
  "PDF no disponible".
- **`hasPdf`** is true for a stored PDF or a kind whose window declares a printable in an
  environment that can render; the page then requests `/pdf` and falls back on its 404. Chosen over
  rendering inside the detail request so the detail answers fast and only a document actually
  looked at is rendered.
- **Side effect in the app:** the first customer open of a document with no PDF stores a main
  attachment credited to the sender (the render runs as that user); from then on it is the file the
  app's preview shows and the one its emails attach.
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
| `GET /home`, `GET /search`, `GET /documents*`, `POST /quotations/{id}/decision` (shared) | 120 |
| `GET /decisions/view` (with `/me`'s counter; unused by the SPA since the one-step confirm) | 30 |
| `POST /decisions/confirm` (with `/renew`'s counter) | 30 |

**Production (review W6, resolved by infra with no code change).** Traffic goes browser →
CloudFront → ALB → Tomcat. Infra added `RemoteIpValve` to the Tomcat image, live from the next
production deploy: `getRemoteAddr()` then returns the real client IP, so the limits are per client
(before it, they counted per proxy address). A single `etendo-core` task runs (two briefly during a
deploy), so the counters are effectively fleet-wide and reset on every deploy; scaling out would
make each task count on its own. Do not parse `X-Forwarded-For` in the portal — the valve is the
one trusted place that reads it.

## 6b. Accept / reject a quotation (Part C)

A customer can accept or reject a quotation of their scope, confirmed by email. Plan:
`etendo_schema_forge` plan, *Part C* (PO answers applied 2026-11-13).

- **`POST /quotations/{id}/decision`** `{decision, reasonCode?, reasonText?}` with `X-Portal-Token`.
  Same scope as the reads (`findInScope`), data rate limit, and a per-quotation cool-down
  (`etendo.go.portal.decision.cooldownMinutes`, default 2). The cool-down check runs under the
  quotation row lock (`C_Order … FOR UPDATE`, held until the request ends), so two simultaneous
  requests cannot both send; it counts requests sent or still in flight, and a request whose email
  did not leave is deactivated so a provider failure does not block the customer (Part A's renewal
  rule). Decidable = `UE` only
  (`QuotationDecisions.isDecidable`, PO answer Q3) and not expired (no `ValidUntil` = not expired). Reject
  needs one of `PRICE`, `DELIVERY`, `PAYMENT_TERMS`, `OTHER_SUPPLIER`, `NOT_NEEDED`, `OTHER` (text
  required). Answers `{state:"sent", maskedEmail}`, 404 out of scope, 409 `not_pending` /
  `expired` / `no_recipient` / `accept_unavailable`, 422 `invalid_reason`, 429 `cooldown`, 503.
- **Recipient:** the confirmation goes to the **first To of the link's root send** only, never CC.
  No To: the detail's `decision.canDecide` is false with `blockedReason: "no_recipient"`.
- **`ETGO_PORTAL_DECISION`** (native SQL, no AD registration): one row per request, the SHA-256 of
  a 256-bit token, `STATUS` `PENDING` / `CONFIRMED` / `VOIDED` / `NOT_APPLIED`, `SENT_AT` (a row is
  usable only once its email left). Single use, never expires.
- **One step from the email (call with the PO).** The confirmation email's button is explicit
  ("Confirmar rechazo" / "Confirmar aceptación", `portal-quotation-decision.confirm.cta.<DECISION>`)
  and opens the landing (`/portal/decision/<token>` in the SPA), whose script sends
  **`POST /decisions/confirm`** on load and shows the result ("Has rechazado el presupuesto …",
  already applied, superseded, no longer pending, expired). **A bare GET of the link applies
  nothing:** a scanner that does not run the page's script never reaches the POST. The token
  travels in **`X-Portal-Decision-Token`**. `GET /decisions/view` (read-only) is still served but
  the SPA no longer calls it. Confirm answers `appliedNow: true` only on the call that applied the
  decision (a re-opened link answers `confirmed` with `appliedNow: false`, and sends no second
  notice or KPI event). **Accepted trade-off (PO, one-step flow):** a mail scanner that executes
  the page's JavaScript could confirm a pending decision. The token is single-use, and the email
  only ever reaches the quotation's main recipient (the first To of the original send).
  **Into the portal (PO).** After the confirmation, whatever its state (applied now, answered
  before, superseded, no longer pending, expired), `PortalDecisionEntry` mints a new portal link
  for the customer, like a renewal: same tenant, Business Partner, recipients and language as the
  decision's link, 48 h from now, `LINK_ORIGIN = DECISION`, parent = the decision's link, root =
  its root, and usable at once (`SENT_AT`/`EXPIRES_AT` set on insert, committed): no email carries
  it. Its raw token is returned only in the confirm POST's answer (`portalToken`, plus
  `quotationId`); only its hash is stored. The SPA then opens `/portal/<token>?view=doc&kind=
  QUOTATION&id=<quotationId>` with a one-off notice carried in router state (never in the URL).
  Opening the portal records `portal.link.opened` as usual (with `origin: DECISION`). No link
  (the landing keeps its own card): invalid token, portal closed for the customer by the access
  checks, or minting failed. Bound: at most 10 `DECISION` links per decision link and day (by
  creation, like the renewal cool-down), on top of the confirm route's rate limiter (`/renew`'s,
  30 per IP and minute). **Needs `update.database`:** `ETGO_PORTAL_LINK_ORIGIN_CHK` accepts
  `DECISION`.
  **Access checks at confirm time:** a decision token never expires, so `confirm` re-runs
  `PortalAccessChecks` (blocked or inactive customer, expired plan) for the link's client and
  Business Partner; closed → `not_pending`, nothing applied, the row stays pending, the reason is
  only logged. Confirm, in one transaction: `C_Order` row `FOR UPDATE`, then the decision row;
  not pending → its state again; quotation re-checked (decidable, not expired) else `NOT_APPLIED`;
  else applied through `QuotationDecisions` — reject: the app's own reject writes, `UE` → `CJ`,
  plus the reason in the notes; accept: `UE` → **`ETGO_AC` "Aceptado"** (module value of the order
  status list, `Processed = Y`, no order generated; still converted from the app's Confirmar to an
  order or an invoice, and over MCP with `Convertquotation` / `createDraftInvoice`) — `CONFIRMED`, every other pending request of the quotation `VOIDED`, commit;
  then the seller's notice. States: `pending`, `confirmed`, `voided`, `not_pending`, `expired`;
  unknown token → the generic 401.
- **Writes as System:** the quotation's audit shows System as updater (the customer is no Etendo
  user, and writing as the seller would be false).
- **PO answers (2026-11-13).** Q1: accept → `ETGO_AC` (`AD_REF_LIST` row of the module, its
  `es_ES`/`es_AR` name "Aceptado" written by the module script
  `EnsureDocumentStatusTranslationsScript`, which also fixes `ETGO_CI`'s "Cerrado - Factura
  creada"; the module ships no translation module, so `export.database` never exports `_TRL`
  rows). `PortalDecisionService.ACCEPT_AVAILABLE` stays as a switch, now `true`. An `ETGO_AC`
  quotation shows under the portal's "Aceptados" group and allow-list. Q2: the portal reason is
  **never** mapped to the tenant's `C_Reject_Reason` (a tenant's reasons can be internal, e.g.
  "deudor", and must never reach the customer); "Otros" never creates one. The reason and the
  "Otros" text go to the quotation's notes; the seller can set the internal reason afterwards on
  the rejected quotation (the field is editable on `CJ` in the app). Q3: only `UE`, not expired.
- **PDF after a decision.** The decision moves the quotation's `updated`, so its stored printable
  is stale. `/pdf` re-renders a stale stored PDF before serving it (`PortalPrintable#isStale`, the
  app's freshness rule via `NeoDocumentPdf#isStoredStale`), keeping the stored file if the render
  fails. The app's preview already re-renders on its own freshness check.
- **"Motivo" on the rejected quotation's PDF (user decision 2026-11-16, Jira comment 149518 point
  5).** The printable of a `CJ` quotation shows one observation line "Motivo: <reason>": the
  tenant's `C_Reject_Reason` when the seller has set one, else the customer's portal reason (with
  the "Otros" text), read from the portal's note line, which the printable leaves out of the
  observations so "Motivo" never shows twice (`QuotationPrintableData.notesAndRejectReason`, port of
  `buildQuotationData`). **The internal reason is visible to the customer** (portal download and
  email attachment); the user accepted this explicitly. Editing the reason moves the quotation's
  `updated`, so the stored PDF is stale and re-rendered by the app's preview, the portal (above) and
  the MCP. The note's wording in `emails_*.properties` (`portal-quotation-decision.note*`) is read
  by the printable: keep its opening words and the "Motivo:" / "Reason:" marker.
- **KPI:** hook points only, `PortalDecisionEvents` (empty methods), pending the PO's KPI redefinition.

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

Metric queries, the per-send vs per-document reading and the link-scanner caveat: plan §A.7.
These usage events stay as they are.

**PO KPIs (etendo-ai#22, 2026-11-16) — Mixpanel.** The PO's two success metrics are computed from
three backend KPI events emitted by `PortalKpiTelemetry` through `NeoTelemetryService`; contract
and Mixpanel recipes in `backend-observability.md` → *Customer Portal KPIs (ETP-6209)*:

| Event | Hook | "First" / dedup |
|---|---|---|
| `backend_portal_document_sent` (`type`) | `PortalLinkService#confirm`, after the link's commit, `SEND` origin only | no other sent `SEND` link of the same record (renewals, decision links and re-sends never emit) |
| `backend_portal_document_downloaded` (`type`) | `/pdf` after the stream succeeded | atomic stamp `ETGO_PORTAL_LINK.FIRST_DOWNLOADED` on the document's first sent `SEND` link (`UPDATE … WHERE first_downloaded IS NULL`): only the request that set it emits; a document never sent with a link emits nothing (it is outside the KPI's denominator) |
| `backend_quotation_decided` (`decision`, `source`) | portal: `PortalDecisionService#confirm` when applied now; app: `RejectQuotationHandler#reject`, `Convertquotation` and the quotation path of `createDraftInvoice` | the app's conversion of an `ETGO_AC` quotation (accepted from the portal) is not a second decision |

`type` is the portal kind in lower case (`invoice`, `quotation`, `order`, `shipment`); a credit
note is an `invoice` (the portal kinds do not tell it apart). **Needs `update.database`** for the
`FIRST_DOWNLOADED` column.

## 8. Tests

`src-test/src/com/etendoerp/go/portal/` (`PortalLinkServiceTest`, `PortalServletTest`,
`PortalLinkDalTest`, `PortalDocumentQueryTest`, `PortalDocumentsEndpointTest`,
`PortalDocumentLinksTest`, `PortalRateLimiterTest`, `PortalUsageEventsTest`),
`schemaforge/AnonymousAttachmentStreamerTest`, and
`schemaforge/email/contracts/PortalLinkRenewalEmailContractTest`. The feature's test list is kept in
the functional repo's `flags-registry.json` (`bp-self-service-portal`).
