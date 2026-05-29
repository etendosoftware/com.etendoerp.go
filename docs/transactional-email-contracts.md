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
| `InMemoryEmailSafetyStore` | Default safety-store implementation until a persistent DAL-backed store is configured |
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

Idempotency lookups are scoped by contract and tenant/client in the default store. Contracts should still generate deterministic keys that include the relevant business record and semantic action/version, for example `invoice-send:<invoiceId>:v1`.

The default `InMemoryEmailSafetyStore` is process-local and suitable for executor wiring and tests. Production deployments that require cluster-wide enforcement must replace it with a persistent implementation without changing contract code.

## Built-In v1 Contracts

| Contract | Provider template | Recipient source | Required command fields |
|----------|-------------------|------------------|-------------------------|
| `reset-password` | `reset-password` | `ETGO_Account.email` resolved by `accountId` | `version`, `accountId`, `link` |
| `new-account` | `new-account` | `ETGO_Account.email` resolved by `accountId` | `version`, `accountId`, `link` |
| `login-alert` | `login-alert` | `AD_User.email` resolved by `userId` | `version`, `userId`; optional `loginEventId`, `ip`, `date` |
| `sales-invoice-send` | `invoice` | `C_BPartner.EM_Etgo_Email`, falling back to active contact email, resolved from the invoice business partner | `version`, `recordId` |
| `sales-order-send` | `document` | `C_BPartner.EM_Etgo_Email`, falling back to active contact email, resolved from the sales order business partner | `version`, `recordId` |
| `sales-quotation-send` | `document` | `C_BPartner.EM_Etgo_Email`, falling back to active contact email, resolved from the sales quotation business partner | `version`, `recordId` |

`custom` and `support-custom-email` are not registered by default. A custom HTML email can only be added later as an explicit support/admin contract with role checks, reason capture, sanitizer, throttle, and audit.

The account-link contracts accept only absolute `http://` or `https://` links. Document-send contracts share the default document payload strategy: `name`, `document_type`, `document_number`, and `download_link`. Optional fields such as `amount`, and document-specific aliases such as `invoice_number`, must be enabled by the explicit contract only when a provider template requires them.

Document-send contracts generate `download_link` from server configuration:

| Property | Environment Variable | Purpose |
|----------|----------------------|---------|
| `etendo.go.email.documentDownloadBaseUrl` | `ETGO_EMAIL_DOCUMENT_DOWNLOAD_BASE_URL` | Base URL used to build document download links as `{base}/{documentType}/{recordId}` |

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
