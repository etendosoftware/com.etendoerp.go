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
| `EmailContract` | Authorizes the command, resolves the recipient, and builds template variables from trusted server context |
| `EmailAuthorizationResult` | Carries contract-specific authorization approval or rejection |
| `EmailRecipientResolution` | Carries the recipient derived from server state or from an explicit support/admin contract |
| `EmailProviderAdapter` | Backend-only boundary to the external provider |
| `ApiGatewayEmailProviderAdapter` | HTTP adapter for API Gateway-style providers |
| `EmailProviderConfig` | Reads provider configuration from server-side properties or environment variables |

Initial contract registration is intentionally separate from the executor. A missing contract returns `VALIDATION_FAILED` with HTTP 404 until a concrete contract is registered.

## Authorization and Recipient Resolution

The executor applies contract safety gates before any provider call:

1. Reject provider passthrough fields such as `to`, `template`, `data`, `from`, `replyTo`, `apiKey`, and `x-api-key`.
2. Reject caller-provided recipient fields such as `recipient`, `recipients`, `email`, and `emailAddress` unless the contract explicitly allows them.
3. Call `EmailContract.authorize(command)` so each contract can enforce contextual access to the requested record or action.
4. Call `EmailContract.resolveRecipient(command)` before provider payload creation.
5. Reject blank recipient resolutions and explicit recipient-resolution failures.
6. Require the final `EmailProviderRequest` recipient to match the resolved recipient.

Default contracts must derive recipients from trusted server-side records. A caller-provided recipient is valid only for explicit support/admin contracts that override `allowsCallerProvidedRecipients()` and still apply role checks, audit, reason capture, and throttle in their contract implementation.

## Contract Implementation Rules

Each contract must implement these steps in order:

1. `authorize`: verify the current user/session can perform the requested send for the record, tenant, and action.
2. `resolveRecipient`: derive the destination from a trusted record whenever possible.
3. `resolve`: build the provider template and variables using the resolved recipient.

Edge cases every contract family must cover:

- The caller references a record they cannot access.
- The trusted record has no valid destination email.
- The command tries to override recipient or provider fields.
- The contract resolves one recipient but builds a provider payload for another.
- A support/admin contract receives a caller-provided recipient without the required role or reason.

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
| `PROVIDER_FAILED` | 502/503 | Provider rejected the request, is unavailable, or is not configured |

Anti-abuse controls, audit persistence, kill switches beyond adapter enabled state, and concrete contract registration are implemented by later ETP tasks.
