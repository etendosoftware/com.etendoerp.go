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
| `EmailProviderAdapter` | Backend-only boundary to the external provider |
| `ApiGatewayEmailProviderAdapter` | HTTP adapter for API Gateway-style providers |
| `EmailProviderConfig` | Reads provider configuration from server-side properties or environment variables |

The default executor loads injected contract providers. A missing contract still returns `VALIDATION_FAILED` with HTTP 404.

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
| `POST /sws/go/register` | Creates the local account, commits it, then sends `new-account` best-effort with the selected UI language when provided |
| `POST /sws/go/password-reset/request` | Returns neutral success for known, unknown, disabled, throttled, or provider-failed cases; known active accounts receive a hashed expiring reset token only when the best-effort `reset-password` email is accepted for delivery |
| `POST /sws/go/password-reset/confirm` | Accepts one valid unexpired token once, changes the password, clears/consumes reset token fields, and clears the platform session token |
| `POST /sws/go/change-password` | Requires a valid platform bearer token and current password, changes the password, rotates the platform token, and sends `password-changed` best-effort |
| `POST /sws/go/onboarding` | Commits onboarding first, then sends `environment-ready` best-effort |

Email delivery failure is audited and must not roll back registration, onboarding completion, or a successful password change. Password reset request responses stay neutral even when delivery fails.

## Built-In v1 Contracts

| Contract | Provider template | Recipient source | Required command fields |
|----------|-------------------|------------------|-------------------------|
| `reset-password` | `reset-password` | `ETGO_Account.email` resolved by `accountId` | `version`, `accountId`, `link` |
| `new-account` | `custom` | `ETGO_Account.email` resolved by `accountId` | `version`, `accountId`, `link`; optional `language` |
| `environment-ready` | `custom` | `ETGO_Account.email` resolved by `accountId` | `version`, `accountId`, `recordId` |
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
