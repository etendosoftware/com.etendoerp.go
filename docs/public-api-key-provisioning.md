# Self-service public API keys

Etendo Go exposes an authenticated, owner-scoped credential lifecycle at `/oauth2/api-keys`.
The caller supplies only a display name and one or more public capabilities. The server derives
the client, organization, user, and role from the bearer token and verifies the active role's DAL
assignments before accessing a key.

## Contract

| Method | Path | Result |
| --- | --- | --- |
| GET | `/oauth2/api-keys` | Safe metadata for keys owned by the current user and tenant |
| POST | `/oauth2/api-keys` | Creates a key and returns `clientSecret` exactly once |
| GET/PUT/DELETE | `/oauth2/api-keys/{id}` | Read, rename/activate, or idempotently delete an owned key |
| POST | `/oauth2/api-keys/{id}/rotate` | Replaces the hash, revokes old tokens, and returns one new secret |
| POST | `/oauth2/api-keys/{id}/revoke-tokens` | Revokes active tokens without returning credential material |

New routes persist with the generated `OAuth2Client` and `OAuth2Token` entities through `OBDal`
and `OBQuery`. The existing administrator `/oauth2/clients` and MCP behavior are unchanged.

`ETGO_OAUTH2_CLIENT` is a system-level technical entity, so self-service records are persisted
with `AD_CLIENT_ID = '0'`. This does not make them system-owned: tenant ownership is enforced by
the private owner-organization scope marker, organization, user, role, and public-key marker. The
server validates that marker against the authenticated tenant before every lifecycle operation.
Token context resolution derives the operational client from the token organization rather than
from the technical row's client value.

## Capabilities and lifecycle

Only these public capabilities are accepted:

- `public-api:read`
- `public-api:write`
- `public-api:process`

They are mapped server-side to internal scopes after role access checks. A private
`neo:public-api-key` marker distinguishes these records from administrator/MCP clients. Wildcards,
unknown capabilities, AD identity fields, and raw internal scopes are never accepted from callers.

There may be at most 10 active keys per user, tenant, and organization. Active names are unique
case-insensitively. Secrets are stored only as hashes, are excluded from list/update/error/audit
payloads, and are never written to logs. Lifecycle audit events contain only the event name and
non-secret IDs.

Example creation request:

```http
POST /oauth2/api-keys
Authorization: Bearer <session-jwt>
Content-Type: application/json

{"name":"Production integration","capabilities":["public-api:read"]}
```

The response's `clientSecret` must be stored immediately by the caller. It is not recoverable
through a later list or update request. Rotation invalidates the previous secret and active tokens.

## Security edge cases

- A key ID owned by another user, organization, or tenant behaves as not found.
- Missing/invalid authentication returns `401`; an inactive or unauthorized role returns `403`
  without revealing tenant data.
- Inactive, deleted, or revoked credentials cannot issue new tokens.

The `client_credentials` grant returns an opaque token. `/sws/neo/*` accepts it through
`NeoAuthenticator`'s `OAuth2Filter.validateToken` lookup even after the temporary legacy-JWT
Bearer flag is disabled. The resolved user, role, organization, client, expiry, revocation, and
scope checks remain server-side: `neo:read` is required for `GET`/`HEAD`; `neo:write` (or
`neo:*`) is required for create, update, and delete. End-to-end consumption still requires a
healthy local Etendo deployment and tenant/user fixtures; unit tests alone do not claim that flow.
