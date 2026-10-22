# Signup API — `/sws/go/signup/v1` (ETP-5847)

The versioned API that creates an Etendo GO account and its demo environment from outside the
app: the marketing site (etendo.ai, through its backend), a CRM, or an agent.

- The account has **no password** and gets **no confirmation mail**.
- Its demo environment is created **in the background**, by the same pipeline as the app's
  onboarding.
- When the environment is ready, the owner gets a **one-time login link** that signs them in
  without a password — mailed, unless the caller turned mails off.
- Later sign-ins use "Forgot password?" on the login screen, which lets an account with no password
  set one.

The app's own registration (`/sws/go/register`, `/onboarding`) is unchanged.

Code: `com.etendoerp.go.signup` (`SignupApiServlet`, `SignupRequest`, `SignupOptions`,
`SignupApiKeys`) on top of `com.etendoerp.go.rest.SignupAccountService` (account lifecycle,
background worker, login link) and `AccountRegistrationService` (rules shared with `/register`).
The login link is consumed by `POST /sws/go/session/link` in `EtendoGoJwtServlet`, and the app
page that receives it is the `login-link` step of `@etendosoftware/etendo-go-core`'s onboarding.

## Authentication: API keys

Every call carries `X-Api-Key: <key>`. **Never from a browser**: a key in a page is a key anyone
can read. The marketing form posts to its own site's backend, which calls this API.

Keys are configured on the server as `etgo.signup.api.keys` (system property) or
`ETGO_SIGNUP_API_KEYS` (environment):

```
ETGO_SIGNUP_API_KEYS=web:<sha256 hex>,crm:<sha256 hex>,agent:<sha256 hex>
```

- Each entry is `<channel>:<SHA-256 hex of the key>`. Only hashes are configured, so a leaked
  configuration hands out no key. Hash a new key with `printf %s '<key>' | shasum -a 256`.
- The channel name is stored on every account the key creates (`ETGO_ACCOUNT.SIGNUP_CHANNEL`).
- Revoke a key by removing its entry, rotate it by replacing the hash. A Tomcat restart is not
  needed for a system property set at runtime, but an environment variable is read by the JVM at
  start.
- No key configured: every call answers `401 INVALID_API_KEY`.

There is no CORS: the API is server to server.

Out of this version, decided 2026-10-21: captcha, rate limiting, and hiding whether an address is
already registered (the caller is told, `EMAIL_ALREADY_REGISTERED`).

## Base URL

`https://<app host>/etendo/sws/go/signup/v1`. The servlet has its own mapping
(`AD_MODEL_OBJECT` *Signup API Servlet*, `/sws/go/signup/v1/*`). It is a longer prefix than
`/sws/go/*`, so these calls never reach `EtendoGoJwtServlet` or the SecureWebServices dispatcher.

## Flow

```
caller                                   Etendo GO
  │ POST /accounts ───────────────────▶ account (no password) + company data stored
  │ ◀── 202 { accountId, provisioning }  environment creation starts in the background
  │ GET /accounts/{id} (poll) ─────────▶ provisioning → ready | failed
  │                                      ready + sendEmails → "environment ready" mail with the link
  │ POST /accounts/{id}/login-link ────▶ fresh link (and the mail, if sendEmail)
  │ POST /accounts/{id}/retry ─────────▶ failed → provisioning again
owner
  │ opens <app>/onboarding?loginToken=…  page with an "Enter" button (opening does not spend it)
  │ clicks ────────────────────────────▶ POST /sws/go/session/link: link spent, address confirmed,
  │ ◀── signed in, in the new environment  cookie session, then the app enters the environment
```

## `GET /config`

What an account may be created with. Mirrors the app's onboarding: Spain, euros, Spanish or English.
Display names are not served; render them in the caller's language.

```json
{
  "countries":  [{ "code": "ES", "currency": "EUR", "language": "es_ES" }],
  "currencies": ["EUR"],
  "languages":  ["es_ES", "en_US"],
  "limits":     { "name": 60, "email": 60, "companyName": 40, "taxId": 20, "source": 2000 },
  "taxIdValidatedFor": ["ES"]
}
```

## `POST /accounts`

```json
{
  "name": "Ana García",
  "email": "ana@empresa.es",
  "companyName": "Empresa SL",
  "countryCode": "ES",
  "currency": "EUR",
  "language": "es_ES",
  "taxId": "B12345674",
  "includeSampleData": true,
  "termsAccepted": true,
  "sendEmails": true,
  "source": { "utm_source": "google", "utm_campaign": "q4", "referrer": "https://..." }
}
```

| Field | Required | Rule |
|---|---|---|
| `name` | yes | trimmed, ≤ 60 |
| `email` | yes | valid address, ≤ 60, case-insensitive |
| `companyName` | yes | ≤ 40; not a reserved provisioning name |
| `countryCode` | yes | one of `config.countries` |
| `currency` | no | one of `config.currencies`; defaults to the country's |
| `language` | no | one of `config.languages`; defaults to the country's. Also the language of the ready mail |
| `taxId` | no | ≤ 20; validated as NIF/CIF/NIE when `countryCode` is `ES` |
| `includeSampleData` | no | default `false` |
| `termsAccepted` | no | when `true`, the acceptance time is stored (`TERMS_ACCEPTED_AT`); never rejected |
| `sendEmails` | no | default `true`. `false`: no mail at all; the caller asks for the link with `login-link` (sales preparing a demo) |
| `source` | no | a JSON object, stored as-is (`SIGNUP_SOURCE`), ≤ 2000 characters serialized |

Unknown fields are ignored. `202 Accepted`:

```json
{ "accountId": "2224E90CE34C4DD5BC86386C0AB6DAC9", "email": "ana@empresa.es", "status": "provisioning" }
```

## `GET /accounts/{id}`

```json
{ "accountId": "…", "email": "ana@empresa.es", "status": "ready" }
```

`status` is `provisioning`, `ready` or `failed`; a failed one also carries `error` (the reason, in
English). Only accounts created through this API are visible: any other id is `404`.

## `POST /accounts/{id}/login-link`

Body (optional): `{ "sendEmail": false, "language": "es_ES" }`. Answers `200`:

```json
{ "link": "https://app.etendo.ai/onboarding?loginToken=…", "expiresAt": "2026-10-28T18:17:07Z", "emailSent": false }
```

- The link is valid for **7 days** (as a company invitation) and **works once**.
- Each new link replaces the previous one, which stops working.
- With `sendEmail: true` it also sends the "environment ready" mail carrying this link.
- Only the link's hash is stored. The link exists only in this response and in the mail.
- It needs the environment to be ready (`409 ENVIRONMENT_NOT_READY`) and the server's public app
  URL, `etendo.go.app.baseUrl` (`503 LOGIN_LINK_UNAVAILABLE` without it).

When the account was created with `sendEmails: true`, the worker issues the first link and mails it
by itself the moment the environment is ready.

## `POST /accounts/{id}/retry`

Runs a `failed` environment creation again: `202 { accountId, status: "provisioning" }`. Any other
state is `409 RETRY_NOT_ALLOWED`. The onboarding resumes a half-built client by its provisioning
name, so a retry does not create a second one.

## Errors

```json
{ "error": { "code": "FIELD_TOO_LONG", "field": "companyName", "max": 40, "message": "..." } }
```

`field` and `max` only when they apply. Branch on `code`; `message` is English, for logs. The codes
are the contract: never rename one.

| HTTP | `code` | `field` | When |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | — | the body is not a JSON object |
| 400 | `REQUIRED_FIELD` | the field | missing, blank or `null` |
| 400 | `INVALID_EMAIL_FORMAT` | `email` | |
| 400 | `FIELD_TOO_LONG` | the field | over its limit; carries `max` |
| 400 | `UNSUPPORTED_COUNTRY` / `UNSUPPORTED_CURRENCY` / `UNSUPPORTED_LANGUAGE` | the field | not in `config` |
| 400 | `INVALID_TAX_ID_FORMAT` | `taxId` | not a NIF/CIF/NIE |
| 400 | `INVALID_TAX_ID_CHECK_DIGIT` | `taxId` | check digit mismatch |
| 400 | `RESERVED_COMPANY_NAME` | `companyName` | the name of a client still being provisioned |
| 401 | `INVALID_API_KEY` | — | missing or unknown `X-Api-Key` |
| 404 | `ACCOUNT_NOT_FOUND` | — | no account created through this API has that id |
| 404 | `NOT_FOUND` | — | unknown path |
| 405 | `METHOD_NOT_ALLOWED` | — | wrong method for the path |
| 409 | `EMAIL_ALREADY_REGISTERED` | `email` | the address already has an account |
| 409 | `EMAIL_PENDING_VERIFICATION` | `email` | the address has an app account still waiting for its confirmation |
| 409 | `ENVIRONMENT_NOT_READY` | — | login link before the environment is ready |
| 409 | `RETRY_NOT_ALLOWED` | — | retry of a creation that did not fail |
| 503 | `LOGIN_LINK_UNAVAILABLE` | — | the server has no `etendo.go.app.baseUrl` |
| 500 | `SIGNUP_SERVER_ERROR` | — | anything else; retry later |

## The login link

- **Where it lands.** `<etendo.go.app.baseUrl>/onboarding?loginToken=<token>`, the same route the
  confirmation and reset links use. The app takes the token off the address bar and shows an
  "Enter Etendo" button.
- **What the click does.** It calls `POST /sws/go/session/link {token}`, which:
  - spends the link (`LOGIN_LINK_INVALID` on reuse or after expiry);
  - confirms the address, because receiving the link proved it;
  - opens a cookie session with `AUTH_METHOD = 'link'`;
  - answers like `POST /session`, plus `environment.userId`.

  The app then enters the account's environment exactly as after a password login.
- **Why a button and not on load.** Mail scanners open links to check them. Spending the link on
  load would spend it for the scanner.

## Data

The columns on `ETGO_ACCOUNT` (ETP-5847):

| Column | Meaning |
|---|---|
| `LOGIN_LINK_HASH`, `LOGIN_LINK_EXPIRES` | the pending login link (hash only) |
| `SIGNUP_CHANNEL` | the API key's name; not null = created through this API |
| `SIGNUP_STATUS` | `provisioning` / `ready` / `failed` |
| `SIGNUP_ENVIRONMENT` | client id of the environment created |
| `SIGNUP_ERROR` | why the creation failed |
| `SIGNUP_SEND_EMAILS` | whether the ready mail goes out by itself |
| `SIGNUP_SOURCE` | the caller's `source` JSON |
| `TERMS_ACCEPTED_AT` | when the terms were accepted |

The company data travels as the account's `ONBOARDING_DRAFT` until the environment exists, so a
retry finds it. The onboarding clears the draft on success.

## Operations

- **Background workers.** Two daemon threads (`etgo-signup-provisioning`) per Tomcat. A creation
  takes about as long as an app onboarding (tens of seconds; less with the tenant pool).
- **A Tomcat restart mid-creation** leaves the account `provisioning` with no worker running it.
  Nothing re-queues it by itself, and `retry` only accepts `failed`, so it needs a manual
  `SIGNUP_STATUS = 'failed'` first. Accepted: production Tomcat restarts are deploys.
- **No public app URL** (`etendo.go.app.baseUrl` unset): environments are still created, but no link
  can be built. The automatic mail is skipped with a warning in the log, and `login-link` answers
  `503`.
