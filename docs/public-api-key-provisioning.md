# Self-service public API keys

Etendo Go exposes an authenticated, owner-scoped credential lifecycle at `/oauth2/api-keys`.
The caller supplies only a display name and one or more public capabilities. The server derives
the client, organization, user, and role from the authenticated session and verifies the active
role's DAL assignments before accessing a key.

The path is fixed: it is the `OAuth2Servlet` route. The former `etgo.public.api.keys.path`
setting (and its `ETGO_PUBLIC_API_KEYS_PATH` variable) only moved the path in the OpenAPI document,
never the route, so it was removed in ETP-5781 and is no longer read.

## Authentication

These are management endpoints of the signed-in user, under the `NEO_DATA` surface policy:

- the Etendo Go session cookie (`__Host-go_session`), with the `X-Go-CSRF` header on writes; or
- the Etendo bearer JWT, while the legacy bearer switch allows it.

OAuth2 access tokens issued to an API key are **not** accepted here. A commercially blocked
environment answers `402`. In the Etendo OpenAPI document every operation carries the
`bearerAuth` security requirement, and its description states the session-cookie alternative
(`__Host-go_session` plus `X-Go-CSRF` on writes). The cookie is not declared as a components
security scheme: the OpenAPI controller serializes with a plain Jackson mapper that writes swagger
enums by name (`"APIKEY"`, `"COOKIE"`), which is not valid OpenAPI.

## Contract

| Method | Path | Success | Body |
| --- | --- | --- | --- |
| GET | `/oauth2/api-keys` | `200` | `{"apiKeys": [<key>, ...]}` |
| POST | `/oauth2/api-keys` | `201` | `<key>` plus `clientSecret` (returned once) |
| GET | `/oauth2/api-keys/{id}` | `200` | `<key>` |
| PUT | `/oauth2/api-keys/{id}` | `200` | `<key>` |
| DELETE | `/oauth2/api-keys/{id}` | `200` | `{"id": "...", "deleted": true}` — `false` when there was nothing to delete |
| POST | `/oauth2/api-keys/{id}/rotate` | `200` | `<key>` plus `clientSecret` (returned once) and `tokensRevoked` |
| POST | `/oauth2/api-keys/{id}/revoke-tokens` | `200` | `{"id": "...", "revoked": true, "tokensRevoked": <n>}` |

`<key>` is the bare key object, never wrapped:

```json
{
  "id": "<record id>",
  "name": "Production integration",
  "clientId": "etgo-…",
  "capabilities": ["public-api:read", "public-api:write"],
  "isActive": true
}
```

`capabilities` is always read from the stored scopes, on every operation that returns a key.

### Error statuses

Errors use the OAuth error envelope (`{"error": "...", "error_description": "..."}`).

| Status | When | Operations |
| --- | --- | --- |
| `400 invalid_request` | Missing, empty or malformed JSON body, a body that is not a JSON object, `name` not a string, `isActive` not a JSON boolean (`"true"` and `null` are refused), blank or too long name, missing/unknown capability | POST, PUT |
| `401` | No valid session or bearer | all |
| `402` | The environment is not commercially available | all |
| `403 access_denied` | Inactive session context or role not held in this tenant/organization; or the active role does not allow a requested capability (`public-api:write` needs a writable window, `public-api:process` a process access) | all (the capability case on POST) |
| `404 not_found` | The key does not exist or is not owned by the caller | GET, PUT, rotate, revoke-tokens |
| `409 invalid_request` | The active-key limit is reached, or another active key already has this name | POST, PUT |

DELETE never answers `404`: deleting a key that is gone or not owned answers `200` with
`deleted: false`, so repeated deletes are idempotent.

## Persistence

The key is persisted with the generated `OAuth2Client` entity through `OBDal`/`OBQuery`
(`PublicApiKeyStore`). The existing administrator `/oauth2/clients` and MCP behavior are
unchanged.

`ETGO_OAUTH2_CLIENT` is a system-level technical entity, so self-service records are persisted
with `AD_CLIENT_ID = '0'` and `AD_ORG_ID = '0'`. This does not make them system-owned: tenant
ownership is enforced by the private owner-organization scope marker, user, role, and public-key
marker. The server validates that marker against the authenticated tenant before every lifecycle
operation.

Tokens are different. The token endpoint inserts them by SQL with `AD_CLIENT_ID = '0'` and the
**tenant organization**, so that token context resolution can derive the operational client from
the token organization. `ETGO_OAUTH2_TOKEN` is a System-level table, and the DAL refuses to flush
any change to such a row (`Entity etgo_oauth2_token may only have instances with organization *`,
checked even in admin mode). Rotate, revoke-tokens and delete therefore revoke or delete tokens
with SQL on the DAL connection, in the request's transaction — never through `OAuth2Token`.
Before ETP-5781 they used the DAL and answered `500` for every key that had ever issued a token.

## Capabilities and lifecycle

Only these public capabilities are accepted:

- `public-api:read`
- `public-api:write`
- `public-api:process`

They are mapped server-side to internal scopes after role access checks — `etendo:read`,
`etendo:write` and `etendo:process`. Keys created before ETP-5602 store the deprecated `neo:read`,
`neo:write` and `neo:process` names; they keep working and map back to the same capabilities
(see `neo-headless.md` §4.1.1). A private
`neo:public-api-key` marker distinguishes these records from administrator/MCP clients. Wildcards,
unknown capabilities, AD identity fields, and raw internal scopes are never accepted from callers.

Owner rules, per user, tenant, and organization:

- **At most 10 active keys** (`PublicApiKeyPolicy.MAX_ACTIVE_KEYS_PER_OWNER`). Checked when a key
  is created and when an inactive key is reactivated (`PUT {"isActive": true}`).
- **Active names are unique, case-insensitively.** Checked on create, on reactivation, and on a
  rename of an active key — always against the *other* active keys, so a key never conflicts
  with itself: a case-only rename (`production` → `Production`) is accepted.

Both checks and the write are serialized per owner, but only on a best-effort basis: the lock
is held within one application node and released before the request's transaction commits, and
there is no database constraint behind it. **Known limitation:** concurrent creates or
reactivations by the same owner (on two nodes, or racing the commit on one) can exceed 10 active
keys or produce two active keys with the same name.

Secrets are stored only as hashes, are excluded from list/get/update/error/audit payloads, and
are never written to logs. Lifecycle audit events contain only the event name and non-secret IDs.

Example creation request:

```http
POST /oauth2/api-keys
Cookie: __Host-go_session=<session>
X-Go-CSRF: <csrf proof>
Content-Type: application/json

{"name":"Production integration","capabilities":["public-api:read"]}
```

The response's `clientSecret` must be stored immediately by the caller. It is not recoverable
through a later list, get or update request. Rotation replaces the secret and revokes every token
the key issued; revoke-tokens revokes them without touching the secret. A revoked token is refused
by `OAuth2Filter.validateToken` (`Token has been revoked`).

## Security edge cases

- A key ID owned by another user, organization, or tenant behaves as not found.
- Missing/invalid authentication returns `401`; an inactive or unauthorized role returns `403`
  without revealing tenant data.
- Inactive, deleted, or revoked credentials cannot issue new tokens.

The `client_credentials` grant returns an opaque token. `/sws/neo/*` accepts it through
`NeoAuthenticator`'s existing `OAuth2Filter.validateToken` fallback, so the resolved identity,
expiry, revocation, and the scope checks (with their deprecated `neo:` aliases) remain server-side:
`etendo:read` for `GET`/`HEAD`, `etendo:write` for other writes, and `etendo:process` — not
`etendo:write` — for an action route (`.../{id}/action/{name}`: complete, void, close a document)
and for a process spec (ETP-5783; see `neo-headless.md` §4.1.1). A key without the needed scope gets
`403` with `WWW-Authenticate: Bearer error="insufficient_scope"`, not `401`. End-to-end consumption
still requires a healthy local Etendo deployment and tenant/user fixtures; unit tests alone do not
claim that flow.

## Tests

- `OAuth2ServletTest` — the endpoint contract over a mocked `PublicApiKeyStore` (statuses,
  capabilities in every response, reactivation rules, body validation).
- `PublicApiKeyStoreTest` — revoke and delete go through SQL, never a token entity.
- `PublicApiKeyStoreIntegrationTest` — needs a database: revoke and delete of a key with tokens
  present, and the DAL refusal that makes SQL necessary.
- `NeoOpenAPIEndpointTest` — the documented contract.
