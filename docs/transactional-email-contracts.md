# Transactional Email Contracts

Etendo Go transactional email is exposed as a server-side contract executor. Browser clients must call Etendo Go, not the email provider.

## Endpoint

```http
POST /sws/neo/email-contracts/{contractName}/send
Authorization: Bearer <jwt>
Content-Type: application/json
```

The request body is a contract command. It must not be a provider payload.

Allowed command shape example:

```json
{
  "version": "v1",
  "recordId": "E2F7A13B...",
  "intent": "send-document",
  "idempotencyKey": "sales-invoice:E2F7A13B:send:v1"
}
```

Rejected provider passthrough shape:

```json
{
  "to": "user@example.com",
  "template": "reset-password",
  "data": {}
}
```

## Runtime Components

| Component | Responsibility |
|-----------|----------------|
| `TransactionalEmailService` | Executes a named contract, enforces executor-level safety gates, and maps provider outcomes to NEO responses |
| `EmailContractRegistry` | Finds the server-side contract by name |
| `DefaultEmailContractRegistry` | Builds the runtime registry from injected `EmailContractProvider` implementations |
| `EmailContractProvider` | CDI extension point used by feature implementations to provide one or more contracts |
| `com.etendoerp.go.schemaforge.email.contracts` | Built-in contract implementations and DAL-backed resolvers, kept outside the framework package |
| `EmailContract` | Authorizes the command, resolves the recipient, and builds template variables from trusted server context |
| `EmailAuthorizationResult` | Carries contract-specific authorization approval or rejection |
| `EmailRecipientResolution` | Carries the recipient derived from server state or from an explicit support/admin contract |
| `EmailDeliveryPolicy` | Carries contract-selected idempotency and throttle rules for a send attempt |
| `EmailSafetyStore` | Checks kill switches, idempotency, throttle counters, and audit capture |
| `DalEmailSafetyStore` | Runtime DAL-backed safety store for audit, idempotency, throttle counters, and kill switches |
| `InMemoryEmailSafetyStore` | Test-only process-local implementation for focused executor tests |
| `EmailSendLogStore` | Captures the readable per-document send history, for contracts that opt in |
| `DalEmailSendLogStore` | Runtime DAL-backed history store writing `ETGO_Email_Send_Log` rows |
| `InMemoryEmailSendLogStore` | Test-only process-local implementation for focused executor tests |
| `EmailSendHistoryRecord` | One history entry, built from the send context and the audit record of the same attempt |
| `EmailProviderAdapter` | Backend-only boundary to the external provider |
| `ApiGatewayEmailProviderAdapter` | HTTP adapter for API Gateway-style providers |
| `EmailProviderConfig` | Reads provider configuration from server-side properties or environment variables |
| `GoProviderEmailSender` | Core `EmailSender` SPI implementation routing core ERP emails over the provider when no SMTP applies |

The default executor loads injected contract providers. A missing contract still returns `VALIDATION_FAILED` with HTTP 404.

## Core ERP Emails Through the Provider (`GoProviderEmailSender`)

Everything above describes emails originated by this module's own contracts. Core ERP emails —
alerts, password resets, portal access grants, legacy Print/Email — travel a different path:
`EmailManager` routes them through `EmailSenderDispatcher` (`com.etendoerp.email.spi`, added by
ETP-4216), which picks the highest-priority sender reporting itself configured.
`GoProviderEmailSender` is this module's contribution to that SPI, so those emails can also go
out over the provider gateway.

It is a **fallback, not an override**. It reports itself configured only when all of the
following hold:

1. the provider is configured (`etendo.go.email.provider.enabled`, `.baseUrl`, `.apiKey`);
2. the send context carries no SMTP configuration, resolved or otherwise;
3. the message has no attachments and no BCC — the provider payload has no slot for either, so
   it declines rather than dropping them, and the dispatcher falls back to SMTP.

A `null` email means the dispatcher is probing for capability
(`hasAlternativeSenderConfigured()`), and the sender answers `true` so callers get past their
pre-send guard.

Priority is `50`: below `TbaiEmailSender`'s `100`, so TicketBAI keeps delivering its own
rejection alert through its own mailbox, and above `DefaultSmtpEmailSender`'s
`Integer.MIN_VALUE`.

Delivery uses the `custom` template with `data.subject` and `data.body` — the
bring-your-own-content template documented under *Provider template allowlist*. The provider
sends from its own verified identity, so core's `from`/`fromName` are ignored, as are
`sentDate` and `headerExtras`.

**Practical consequence:** the `com.smf.currency.conversionrate` failure alert now reaches its
recipient on a System-scheduled downloader, where the SMTP cascade resolves nothing because it
filters by client `0`. That module required no changes — the dispatcher selects the transport
for it.

Environments with SMTP configured, or without the provider configured, are unaffected.

Design and rollout notes: `docs/plans/2026-08-10-go-provider-email-sender-design.md`.

## Authorization and Recipient Resolution

The executor applies contract safety gates before any provider call:

1. Reject provider passthrough fields such as `to`, `template`, `data`, `from`, `replyTo`, `apiKey`, and `x-api-key`.
2. Reject caller-provided recipient fields such as `recipient`, `recipients`, `email`, and `emailAddress` unless the contract explicitly allows them.
3. Call `EmailContract.authorize(command)` so each contract can enforce contextual access to the requested record or action.
4. Call `EmailContract.resolveRecipient(command)` before provider payload creation.
5. Reject blank recipient resolutions and explicit recipient-resolution failures.
6. Require the final `EmailProviderRequest` recipient to match the resolved recipient.
7. Apply kill switches, idempotency, throttle rules, and audit before provider submission.

Default contracts must derive recipients from trusted server-side records. A caller-provided recipient is valid only for explicit support/admin contracts that override `allowsCallerProvidedRecipients()` and still apply role checks, audit, reason capture, and throttle in their contract implementation.

## Contract Implementation Rules

Each contract must implement these steps in order:

1. `authorize`: verify the current user/session can perform the requested send for the record, tenant, and action.
2. `resolveRecipient`: derive the destination from a trusted record whenever possible.
3. `resolve`: build the provider template and variables using the resolved recipient.
4. `deliveryPolicy`: define idempotency and throttle rules for the send attempt.

Feature implementations should provide contracts through `EmailContractProvider`. Document contracts should inject an `EmailDocumentRecordResolver` owned by the document implementation. Do not add document-specific resolver methods to the framework package.

Edge cases every contract family must cover:

- The caller references a record they cannot access.
- The trusted record has no valid destination email.
- The command tries to override recipient or provider fields.
- The contract resolves one recipient but builds a provider payload for another.
- A support/admin contract receives a caller-provided recipient without the required role or reason.
- A repeated command arrives with the same idempotency key.
- A tenant, template, recipient, recipient domain, user, record, or global limit is exceeded.
- A global, tenant, or template kill switch is active.

## Anti-Abuse, Idempotency, and Audit

The executor builds an `EmailSendContext` after authorization, recipient resolution, and provider request validation. The context exposes:

- contract name
- tenant/client id from `tenantId` or `clientId`
- user id from `userId`
- business record id from `recordId`
- provider template
- resolved recipient
- recipient domain

`EmailDeliveryPolicy` controls per-contract abuse behavior. It supports:

- idempotency key selected by the contract, with fallback to command body `idempotencyKey`
- `EmailThrottleRule.global(max, windowSeconds)`
- `EmailThrottleRule.perTenant(max, windowSeconds)`
- `EmailThrottleRule.perUser(max, windowSeconds)`
- `EmailThrottleRule.perTemplate(max, windowSeconds)`
- `EmailThrottleRule.perRecipient(max, windowSeconds)`
- `EmailThrottleRule.perDomain(max, windowSeconds)`
- `EmailThrottleRule.perRecord(max, windowSeconds)`

Rules whose context key is unavailable are skipped, so contracts can share policy helpers across flows where not every dimension applies.

### Readable send history (ETP-5069)

The anti-abuse ledger `ETGO_Email_Safety` is not, and must not become, a readable history. It lives at client 0 (`ACCESSLEVEL` 4), stores every recipient as a SHA-256 hash, and carries no subject and no body — an invariant asserted by `DalEmailSafetyStoreTest` and deliberately left untouched.

`ETGO_Email_Send_Log` is the second, separate ledger that answers the operator's question instead: what was sent from this document, to whom, when, and did it go out. It is a Client/Organization table (`ACCESSLEVEL` 3), so a row carries the real tenant, and it stores recipients, subject, the operator's own message and the download link in clear.

- **Written from one place.** `TransactionalEmailService#recordAudit` is the single choke point all eight audit call sites funnel through, and it runs after the `EmailSendContext` exists — the only point where the readable data and the audit outcome for the same attempt are both in hand. The history row is saved BEFORE `EmailSafetyStore#recordAudit`, so both rows share the transaction the DAL safety store closes with `SessionHandler.commitAndStart()`.
- **Gated declaratively.** `EmailContract#logsSendHistory()` defaults to `false`; `DefaultDocumentSendEmailContract` overrides it to `true`, so the six document-send contracts opt in automatically and the account/auth family (invitation, reset password, login alert, organization joined) never reaches the table. There is no contract-name list.
- **No admin mode, and no forced client 0.** `DalEmailSendLogStore` lets DAL fill `AD_Client_ID`/`AD_Org_ID`/`CreatedBy` from the caller's own session. That is what makes the readable-client filter on the read path a genuine access rule, and it makes `CreatedBy` the actual sender — closing the long-standing null-`userId` gap the client-0 ledger has.
- **`messageBody` is the operator's text, not the rendered email.** The provider's `body` template value is the whole HTML document produced by `EmailLayout.render`; what gets stored is `EmailMessageEdits#getMessage()`, pre-escape. A send that used the contract's default copy stores `null` there and keeps its subject.
- **Failures are swallowed.** A history row is a convenience; the send and its audit row are not. Every `VARCHAR` value is truncated to its column width before the insert for the same reason.
- **Read path:** `GET /sws/neo/documentemailhistory?recordId=<id>` — see `docs/neo-headless.md` §8j for the row shape and the access-rule rationale. There is also a read-only backoffice window, *Email Send History* (`AD_WINDOW` `A42231FFB2764AB38EC8D1C46637BA4C`), client-scoped like the table.
- **Six contracts record, five windows display.** `DefaultDocumentSendEmailContract` opts in all six document sends — sales-order, purchase-order, sales-quotation, sales-invoice, goods-shipment and **return-to-vendor** — but the app-shell's `EmailsCard` is currently wired into only four preview components covering five windows (`OrderPreview` serves sales-order and purchase-order, plus `InvoicePreview`, `QuotationPreview`, `GoodsShipmentPreview`). Return-to-vendor sends are recorded and readable through the backoffice window and the endpoint; they simply have no preview card yet. Nothing needs to change on the backend when one is added.
- **Accepted limitation:** the rejection paths that answer before the send context is built — bad JSON, unknown contract, forbidden provider field, failed authorization, unresolved recipient, malformed `messageEdits`, document not found — write no audit row today and therefore write no history row either. The failure statuses an operator can act on (`PROVIDER_FAILED`, `THROTTLED`, `SUPPRESSED`, `DUPLICATE`) are all post-context and are recorded, so the card is not misleading — but an empty history is not proof that nothing was attempted.
- **Accepted limitation: no retention or purge process.** Deferred deliberately, on sizing measured during ETP-5069 rather than on assumption. Rows are bounded by validation that already existed (`EmailMessageEdits.MAX_MESSAGE_LENGTH = 5000`, `MAX_SUBJECT_LENGTH = 200`) and by the per-column truncation `DalEmailSendLogStore` applies before every insert, giving ~1.5 KB typical / ~6.4 KB worst case per row — about **12–54 MB** for the full lifetime history of the local dataset's 8,454 documents, against `c_invoice` at 30 MB on the same instance. Cardinality does not increase either: one audit row per send attempt already existed, and this adds a second for six contracts. Revisit when a real instance's table grows past a size worth purging, or when a tenant asks for a deletion window (this table holds customer correspondence in clear). The two indexes — `ETGO_EMAIL_SEND_LOG_RECORD` on `RECORD_ID` and `ETGO_EMAIL_SEND_LOG_SENTAT` on `SENT_AT` — exist partly to make that future purge cheap; do not drop `SENT_AT` as unused.
- **Status vocabulary:** the eight `STATUS_*` constants at `TransactionalEmailService.java:49-56` — `SENT`, `VALIDATION_FAILED`, `PROVIDER_FAILED`, `UNAUTHORIZED`, `DUPLICATE`, `THROTTLED`, `SUPPRESSED`, `NO_RECIPIENT` — mirrored by the `ETGO_EmailSendStatus` AD reference list. **There is no `DELIVERY_FAILED`**; that value belongs to `ETGO_INVITATION.STATUS`, a different subsystem, and the two are routinely confused.

### Per-environment throttle ceilings

The document-send family (`sales-invoice-send` and its five siblings) reads its ceilings from
configuration instead of hardcoding them, because the production values are deliberately tight
enough to block ordinary development. `perRecord` allows **3 sends of the same document per hour**,
so re-sending one invoice while checking a template change locks that record out for the rest of
the hour — indistinguishable, from the operator's side, from the email system being broken.

| Property | Env var | Default | Scope |
|---|---|---|---|
| `etendo.go.email.throttle.maxPerRecord` | `ETGO_EMAIL_THROTTLE_MAX_PER_RECORD` | 3 | same document |
| `etendo.go.email.throttle.maxPerRecipient` | `ETGO_EMAIL_THROTTLE_MAX_PER_RECIPIENT` | 20 | same address |
| `etendo.go.email.throttle.maxPerUser` | `ETGO_EMAIL_THROTTLE_MAX_PER_USER` | 50 | sending operator |
| `etendo.go.email.throttle.maxPerTenant` | `ETGO_EMAIL_THROTTLE_MAX_PER_TENANT` | 100 | client |
| `etendo.go.email.throttle.maxPerDomain` | `ETGO_EMAIL_THROTTLE_MAX_PER_DOMAIN` | 200 | recipient domain |

All windows are one rolling hour. Set them in the Etendo root `gradle.properties`; the defaults are
the production values, so an environment that configures nothing behaves exactly as before this
existed.

The global rule (2000 per minute) is **not** configurable: it is a burst guard protecting the
provider, not a per-actor quota, and no development loop reaches it.

Two behaviours worth knowing:

- **Raising a ceiling resets the counter.** `DalEmailSafetyStore.findThrottle()` matches a throttle
  row on `maxAttempts` and `windowSeconds` as well as on scope and bucket key, so a changed ceiling
  finds no existing row and starts a fresh one at zero. Clearing `ETGO_EMAIL_SAFETY` by hand to
  unblock a developer is never necessary.
- **A malformed override is ignored**, with a warning logged. This is deliberate: `EmailThrottleRule`
  clamps with `Math.max(1, maxAttempts)`, so a typo parsing as `0` would silently mean *one* email
  per hour — a far worse failure than the limit staying where it was.

`EmailSafetyStore` is the persistence boundary for:

- global, tenant, and template kill switches
- successful-send idempotency lookups
- throttle counters
- audit records

Idempotency lookups are scoped by contract and tenant/client in the default DAL store. Contracts should still generate deterministic keys that include the relevant business record and semantic action/version, for example `invoice-send:<invoiceId>:v1`. Document-send contracts must derive the tenant/client part from the trusted resolved document record instead of caller-provided payload fields.

The runtime default is `DalEmailSafetyStore`, which persists records in `ETGO_EMAIL_SAFETY` through OBDal/OBQuery. Native SQL is not used for auth email audit, throttle, idempotency, or kill-switch behavior. `InMemoryEmailSafetyStore` remains available only for unit tests and local executor harnesses that intentionally avoid DAL.

## Auth Flow Entrypoints

Auth transactional emails are created server-side by the `/sws/go/*` endpoints. Browser clients never call the email provider and never send `to`, `template`, `data`, sender, Reply-To, or provider metadata for these flows.

| Endpoint | Transactional email behavior |
|----------|------------------------------|
| `POST /sws/go/register` | Creates the local account, commits it, issues a hashed 24h email-verification token, then sends `new-account` best-effort with the confirmation link as its call to action and the selected UI language when provided (ETP-4798) |
| `POST /sws/go/password-reset/request` | Returns neutral success for known, unknown, disabled, throttled, or provider-failed cases; known active accounts receive a hashed expiring reset token only when the best-effort `reset-password` email is accepted for delivery |
| `POST /sws/go/password-reset/confirm` | Accepts one valid unexpired token once, changes the password, clears/consumes reset token fields, and clears the platform session token |
| `POST /sws/go/change-password` | Requires a valid platform bearer token and current password, changes the password, rotates the platform token, and sends `password-changed` best-effort |
| `POST /sws/go/verify-email` | Unauthenticated — the mailed token is the credential. Accepts one valid unexpired token, stamps `ETGO_Account.Email_Verified`, and is idempotent: the token hash is intentionally left in place so a re-clicked link answers 200 instead of "invalid" (ETP-4798) |
| `POST /sws/go/verify-email/resend` | Requires a platform bearer token. Re-issues the token and sends `verify-email` best-effort, but only when a confirmation is genuinely pending. Answers a neutral 200 in every one of those cases so the response carries no account state; a genuine server failure is still a 500, since the endpoint is authenticated (ETP-4798) |
| `POST /sws/go/onboarding` | Refused with `403 EMAIL_NOT_VERIFIED` before the NDJSON stream opens when the account still owes an email confirmation (ETP-4798), then paywalled (ETP-4686). Otherwise commits onboarding first, then sends `environment-ready` best-effort |

When a company administrator creates an `AD_User` (ETP-4830, superseding an earlier ETP-4829
pending-account design), `UserRoleAssignmentHandler`'s `POST` post-hook does **not** provision
any `ETGO_Account` row itself. Instead it calls
`CompanyInvitationService#createInvitationForNewlyCreatedUser`, which persists an
`ETGO_Invitation` row and sends the same `company-invitation` contract described below —
identical to the flow a company administrator triggers by inviting an *existing* user, except
it skips the "invited user already has an active role" check (a freshly created `AD_User` has
none yet by construction; role assignment happens later, independently, via
`AssignTemplateRolesControl`'s own save). This is the one exception to the "only `/sws/go/*`
triggers these emails" rule above: the trigger here is a NEO Headless `user`-entity `POST`, not
a `/sws/go/*` call, though the email contract, throttling, and audit machinery are unchanged.

No `ETGO_Account` is created eagerly on this path. `register-and-accept` (§ below) is the sole
place an `ETGO_Account` gets created for an admin-created user, lazily, once the invitee actually
accepts — the invitation records `PENDING`, `SENT`, `DELIVERY_FAILED`, `ACCEPTED`, `EXPIRED`, and
`REVOKED` lifecycle states without storing the raw token. The recipient is resolved exclusively
from the `ETGO_Invitation.email` column; the browser does not send a recipient, template, or
provider payload. The `user` NeoHandler also surfaces the latest invitation's `status` back as an
`invitationStatus` field (`null` when none exists) on every `user` GET response (list or
single-record), via `CompanyInvitationService#findLatestInvitationStatus`, so the frontend can
render a "pending invite" badge.

Invitation safeguards:

- An already-open (`PENDING`/`SENT`, not yet expired) invitation for the same client/email is
  reused instead of creating a duplicate.
- A missing public app URL, provider rejection, throttle, suppression, or kill switch still
  persists the invitation in `DELIVERY_FAILED` status rather than leaving it silently unsent.
- The invitation token expires after 7 days and can be consumed only once; an expired or revoked
  token is rejected by `resolveInvitation`/`acceptExistingAccount`/`registerAndAccept`.
- **Accepting an invitation does not require the invited `AD_User` to already hold a role**
  (`acceptExistingAccountInAdminMode`/`registerAndAcceptInAdminMode`, ETP-4830): the only user-side
  check is that `AD_User` still exists and is active. An earlier revision additionally required
  `CompanyInvitationDalHelper#hasActiveRoleForOrganization` at accept time, which contradicted the
  admin-created-user flow above — a freshly created user has zero roles by construction, so that
  check made every such invitation permanently un-acceptable (409
  `INVITATION_USER_CONFIGURATION_INVALID`) until an admin manually assigned a role first, which
  itself normally happens *after* the user is invited and can sign in. What still gates who can
  accept what: possession of the invitation's own unguessable token (delivered only to
  `ETGO_Invitation.email`), the invitation not being closed (`REVOKED`/`EXPIRED`/already
  `ACCEPTED`), and — for `acceptExistingAccount` — the signed-in `ETGO_Account`'s email matching
  the invitation's email. A roleless user can still sign in after accepting; they simply cannot do
  anything until a role is assigned, identical to any other freshly created `AD_User`.
- **User/role validation runs BEFORE account creation, not after (ETP-4830 fix).** Accepting a
  brand-new-account invite (`registerAndAcceptInAdminMode`) used to crash with
  `org.hibernate.LazyInitializationException: could not initialize proxy - no Session`: the
  user/role check above read `invitation.getUser()` (a lazy `AD_User` proxy) AFTER
  `EtendoGoJwtDalHelper#createAccount` had already run, and that method ends with a
  flush-and-commit that closes the Hibernate session the proxy needs to initialize. Fixed by
  moving the validation ahead of the `createAccount()` call, while the session that loaded
  `invitation` is still open. The sibling `acceptExistingAccountInAdminMode` path never had this
  specific crash (it has no `createAccount()` call), which is why only the new-account path needed
  the reorder.

Email delivery failure is audited and must not roll back registration, onboarding completion, or a successful password change. Password reset request responses stay neutral even when delivery fails.

### Email ownership confirmation and its fail-open rule (ETP-4798)

`/sws/go/register` still returns a usable session token — the account exists and can call `/me`,
`/verify-email/resend` and `/logout`. What an unconfirmed address blocks is entering onboarding at
all: the web client lands on a confirm-your-email wall instead of step 1, and the backend
independently refuses the one irreversible, costly step (creating the tenant) with
`403 EMAIL_NOT_VERIFIED`. The wall is UX; the 403 is the gate, and it stands on its own for a
modified client or a direct request.

Verification state lives in three `ETGO_ACCOUNT` columns and is read through two **independent**
predicates, never one derived from the other:

| Predicate | Meaning | Gated? |
|-----------|---------|--------|
| `Email_Verified is not null` | the holder proved control of the address | no |
| `Email_Verified is null and Verify_Token_Hash is not null` | a confirmation was issued and is still owed | **yes** |
| both null | account predates ETP-4798, or its confirmation mail could not be sent | no |

That third row is the whole reason the gate keys off "a token was issued" rather than "not
verified". It means no backfill migration was needed and no existing user was locked out by the
deploy.

The flow **fails open only when there is nothing to fall back to**. Issuing a token overwrites
whatever was pending, so the previous state is captured first and put back when the send fails —
the same capture/restore pair the reset token uses. Which way that lands depends on whether a
confirmation was already owed:

| Send fails during | Previous token | Result |
|-------------------|----------------|--------|
| first issue at `/register` | none | restores to "no token": account ungated, onboarding proceeds |
| `/verify-email/resend` | one pending | previous token restored: account stays gated, the link already in the inbox still works |

The first row is the deliberate fail-open. When no verification link can be built
(`etendo.go.app.baseUrl` unset) or the mail is not accepted for delivery, a misconfigured provider
would otherwise silently stop every new signup from creating an environment, with no mail to click
and no way to tell from the user's side.

The second row is **not** optional, and clearing the token there would be a bypass of the whole
feature rather than a graceful degradation. `verify-email` carries a per-recipient throttle
(4 sends / 900s); once it refuses, the send returns false like any other delivery failure. If that
cleared the token, pressing "resend" until the throttle tripped would switch the gate off — no
tampering, just an impatient user waiting on a slow mail. Regression-tested by
`resendWhoseMailFailsRestoresThePendingTokenSoTheAccountStaysGated`.

SSO accounts are born confirmed, and signing in through the identity provider clears any
confirmation still pending on that address — the assertion is stronger proof of ownership than a
link we mailed.

## Built-In v1 Contracts

| Contract | Provider template | Recipient source | Required command fields |
|----------|-------------------|------------------|-------------------------|
| `reset-password` | `reset-password` | `ETGO_Account.email` resolved by `accountId` | `version`, `accountId`, `link` |
| `new-account` | `custom` | `ETGO_Account.email` resolved by `accountId` | `version`, `accountId`, `link`; optional `language` |
| `verify-email` | `custom` | `ETGO_Account.email` resolved by `accountId` | `version`, `accountId`, `link`, `recordId` (the verification token hash); optional `language` |
| `environment-ready` | `custom` | `ETGO_Account.email` resolved by `accountId` | `version`, `accountId`, `recordId` |
| `company-invitation` | `custom` | `ETGO_Invitation.email` resolved by `recordId` | `version`, `recordId`, `link`; optional `language` |
| `password-changed` | `custom` | `ETGO_Account.email` resolved by `accountId` | `version`, `accountId`, `recordId`; optional `date` |
| `login-alert` | `login-alert` | `AD_User.email` resolved by `userId` | `version`, `userId`; optional `loginEventId`, `ip`, `date` |
| `sales-invoice-send` | `invoice`, or `custom` on an edited send | `C_BPartner.EM_Etgo_Email`, falling back to active contact email, resolved from the invoice business partner | `version`, `recordId` |
| `sales-order-send` | `custom` | `C_BPartner.EM_Etgo_Email`, falling back to active contact email, resolved from the sales order business partner | `version`, `recordId` |
| `sales-quotation-send` | `custom` | `C_BPartner.EM_Etgo_Email`, falling back to active contact email, resolved from the sales quotation business partner | `version`, `recordId` |
| `purchase-order-send` | `custom` | `C_BPartner.EM_Etgo_Email`, falling back to active contact email, resolved from the purchase order business partner | `version`, `recordId` |
| `goods-shipment-send` | `custom` | `C_BPartner.EM_Etgo_Email`, falling back to active contact email, resolved from the shipment business partner | `version`, `recordId` |
| `return-to-vendor-send` | `custom` | `C_BPartner.EM_Etgo_Email`, falling back to active contact email, resolved from the vendor-return business partner | `version`, `recordId` |

`custom` and `support-custom-email` are not registered as public contracts by default. Some closed auth contracts use the provider's `custom` template because the current provider allowlist exposes `custom`, `reset-password`, `login-alert`, and `invoice`. Those contracts still generate fixed `subject` and `body` values server-side and never accept arbitrary provider payloads from the browser. A generic custom HTML email can only be added later as an explicit support/admin contract with role checks, reason capture, sanitizer, throttle, and audit.

### Provider template allowlist (ETP-4786)

The gateway exposes exactly four templates and rejects anything else with
`400 {"error": "Unknown template '<name>'. Available: ['reset-password', 'login-alert', 'invoice', 'custom']"}`.
The document-send family originally emitted `document`, which is **not** in that set, so every
document send failed with `PROVIDER_FAILED`; `sales-invoice-send` was unaffected only because it
pins `invoice`. The family now defaults to `custom` and therefore owns its `subject` and `body`,
built from the trusted document record in `DefaultDocumentSendEmailContract`. Never introduce a
template name that is not in the allowlist above — `InitialEmailContractsTest` fails if you do.

Set `etendo.go.email.provider.documentTemplate` (or `ETGO_EMAIL_PROVIDER_DOCUMENT_TEMPLATE`) to
switch the family onto a branded document template once the gateway publishes one; no code change
is required. Contracts that pin their own template (`sales-invoice-send`) ignore the property.

### Per-send template selection (ETP-4717)

The template is resolved per send, not per contract, because only `custom` can render copy the
operator authored:

| | `messageEdits` absent | `messageEdits` present |
|---|---|---|
| `sales-invoice-send` | `invoice` — branded, `subject`/`body` not emitted | `custom` + operator copy |
| every other document contract | `custom` + contract-composed default copy | `custom` + operator copy |

`messageEdits` is the allowlisted `{ subject, message }` command field posted by `SendDocumentModal`
only when the operator changed either value; an untouched send carries no such key and keeps the
byte-identical legacy payload. `EmailMessageEdits` validates it: unknown keys are rejected, both
values are length-capped, CR/LF are stripped from the subject (header-injection vector), and the
message is HTML-escaped with newlines converted to `<br>` because the content template renders
`body` as HTML. The browser still cannot choose the template, the recipient, or any provider
metadata.

The send idempotency key gains a `:{contentHash}` suffix **only** on an edited send. Without it,
correcting the text and re-sending to the same recipients reuses the previous key and is answered
`DUPLICATE`, so the corrected email would never leave. Untouched sends keep their pre-ETP-4717 key.

Contracts outside the document-send family reject `messageEdits` outright via
`EmailContractCommandSupport.rejectMessageEditsIfPresent`, mirroring the existing `recipientEdits`
rejection.

When the gateway publishes a `document` template that accepts optional `subject`/`body` overrides,
both branches of the table collapse onto it: set the property, delete the contract-composed
defaults (`buildSubject`/`buildBody`), and pass the operator copy straight through.

`login-alert` is registered but not triggered by login. It remains deferred until the SSO and risk-policy model is defined.

Caller-supplied account-link contracts accept only absolute `http://` or `https://` links. The `environment-ready` contract builds the dashboard link from server configuration and does not accept a caller-provided link. Document-send contracts share the default document payload strategy: `name`, `document_type`, `document_number`, and `download_link`. Optional fields such as `amount`, and document-specific aliases such as `invoice_number`, must be enabled by the explicit contract only when a provider template requires them.

The `new-account` contract includes the selected registration language in provider
template data as `language` when the registration request provides it. The
frontend sends the active onboarding locale (`es_ES` or `en_US`) and the backend
forwards only that allowlisted field; browser clients still cannot send generic
provider payloads.

Auth account-link contracts generate app links from server configuration:

| Property | Environment Variable | Purpose |
|----------|----------------------|---------|
| `etendo.go.app.baseUrl` | `ETGO_APP_BASE_URL` | Base URL used for onboarding, dashboard, and password reset links |

`etgo.app.url` / `ETGO_APP_URL` remain as legacy fallbacks.

Password reset links require a configured app base URL. The reset request
endpoint never builds token-bearing email links from the incoming request
`Host`; if no app base URL is configured, it keeps the neutral response,
restores the previous reset token state, and skips the email send.

Document-send contracts generate `download_link` from server configuration:

| Property | Environment Variable | Purpose |
|----------|----------------------|---------|
| `etendo.go.email.documentDownloadBaseUrl` | `ETGO_EMAIL_DOCUMENT_DOWNLOAD_BASE_URL` | Base URL for signed document download links, normally `/etendo/sws/neo/document-download` |
| `etendo.go.email.documentDownloadTokenSecret` | `ETGO_EMAIL_DOCUMENT_DOWNLOAD_TOKEN_SECRET` | Server-side HMAC secret used to sign document download tokens |
| `etendo.go.email.documentDownloadTokenTtlSeconds` | `ETGO_EMAIL_DOCUMENT_DOWNLOAD_TOKEN_TTL_SECONDS` | Optional token lifetime in seconds. Defaults to 7 days and never allows less than 60 seconds |

Document download links are generated as `{base}/{token}`. The token is tied to the email send
event through the resolved idempotency key and contains the trusted document record id and client
id resolved by the server-side contract. The public download endpoint treats the signed token as a
short-lived bearer authorization: it validates the HMAC signature and expiration, then serves only
the cached `ETGO_PREVIEW_FILE` file for the token client/spec/record tuple. The send attempt is
still audited by `TransactionalEmailService`, but the download endpoint does not depend on
process-local audit state so links keep working across restarts and clustered nodes until their
token expires.

## Document summary block (ETP-5003)

Document emails render a label/value table between the body copy and the call to action. It is built
from `EmailDocumentRecord.getDetails()`, a list of `EmailDocumentDetail` rows the DAL resolver
contributes.

```java
EmailDocumentRecord.withGeneratedDownloadLink(name, email, id, documentNo, amount, clientId,
    Arrays.asList(
        EmailDocumentDetail.date("document.detail.date", invoice.getInvoiceDate()),
        EmailDocumentDetail.date("document.detail.dueDate", invoice.getETGODueDate()),
        EmailDocumentDetail.text("document.detail.total", amount)));
```

- `date(...)` keeps the value unformatted. The resolver runs inside the **sender's** session and
  cannot know the recipient's language; `DefaultDocumentSendEmailContract` formats it through
  `EmailDates.format` once the language is known.
- `text(...)` is for values already rendered, such as a currency amount.
- A row whose value is `null` or blank is dropped by `EmailDocumentRecord`, so an absent due date
  costs nothing and needs no branch at the call site.
- **No rows means no block.** The contract prepends the document number as the first row only when
  the resolver contributed at least one other, so a resolver returning `Collections.emptyList()`
  opts that document type out entirely (`purchase-order-send` does exactly this).
- Row labels and the date pattern (`document.detail.dateFormat`) are catalog keys in both
  `emails_es_ES.properties` and `emails_en_US.properties`.

The block is independent of `messageEdits`: an operator-authored message replaces the greeting and
body copy, never the summary rows.

## Provider Configuration

No provider endpoint or API key is hardcoded in the module.

Supported server-side configuration through Java system properties, Openbravo properties, or environment variables:

| Property | Environment Variable | Purpose |
|----------|----------------------|---------|
| `etendo.go.email.provider.baseUrl` | `ETGO_EMAIL_PROVIDER_BASE_URL` | Provider/API Gateway URL |
| `etendo.go.email.provider.apiKey` | `ETGO_EMAIL_PROVIDER_API_KEY` | Provider API key |
| `etendo.go.email.provider.timeoutMs` | `ETGO_EMAIL_PROVIDER_TIMEOUT_MS` | Connect/read timeout in milliseconds |
| `etendo.go.email.provider.enabled` | `ETGO_EMAIL_PROVIDER_ENABLED` | Adapter kill switch |

The provider is considered configured only when it is enabled and both base URL and API key are present.

## Response Statuses in This Layer

`ETP-4062` implements the executor/provider boundary and returns:

| Status | HTTP | Meaning |
|--------|------|---------|
| `SENT` | 200 | Provider accepted the resolved contract request |
| `VALIDATION_FAILED` | 400/404 | Command is invalid, uses provider fields, or contract does not exist |
| `UNAUTHORIZED` | 403 | Contract authorization failed or the command uses caller-provided recipients without an explicit support/admin contract |
| `DUPLICATE` | 200 | Idempotency key already has a successful send, so the provider call is suppressed |
| `THROTTLED` | 429 | A configured throttle rule rejected the send attempt |
| `SUPPRESSED` | 403 | A global, tenant, or template kill switch suppressed the send attempt |
| `PROVIDER_FAILED` | 502/503 | Provider rejected the request, is unavailable, or is not configured |

Additional contract families are implemented by later ETP tasks.
