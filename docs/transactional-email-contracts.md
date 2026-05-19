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
| `TransactionalEmailService` | Executes a named contract and maps provider outcomes to NEO responses |
| `EmailContractRegistry` | Finds the server-side contract by name |
| `EmailContract` | Resolves recipient, template, and variables from trusted server context |
| `EmailProviderAdapter` | Backend-only boundary to the external provider |
| `ApiGatewayEmailProviderAdapter` | HTTP adapter for API Gateway-style providers |
| `EmailProviderConfig` | Reads provider configuration from server-side properties or environment variables |

Initial contract registration is intentionally separate from the executor. A missing contract returns `VALIDATION_FAILED` with HTTP 404 until a concrete contract is registered.

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
| `PROVIDER_FAILED` | 502/503 | Provider rejected the request, is unavailable, or is not configured |

Authorization, recipient derivation for real business records, anti-abuse controls, audit persistence, kill switches beyond adapter enabled state, and concrete contract registration are implemented by later ETP tasks.
