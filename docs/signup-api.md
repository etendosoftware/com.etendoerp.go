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

The app's own registration (`/sws/go/register`, `/onboarding`) creates passwordless accounts too
since ETP-6081, and mails the same one-time login link in its welcome, right after sign-up
(see `onboarding-flow.md` → *Sign-up without a password*). The rest of this page is the API only.

## Links between the website and the onboarding

- **Into the account creation view.** `/onboarding` opens on the login view by default. A website
  button that should open account creation instead ("Start for free") links to
  `<app>/onboarding?view=register`. The onboarding drops `view` from the address bar once read, so
  a reload lands on the login view. A visitor who is already signed in goes to their environments
  as usual.
- **Back to the website.** The onboarding's logo and name link to the URL the SPA was built with
  in `VITE_WEBSITE_URL` (`deploy-staging.yml` reads it from the `WEBSITE_URL` Actions variable;
  one site for every target). Unset, they are not a link. The variable is baked at build time:
  changing it takes a new SPA build.

Code: `com.etendoerp.go.signup` (`SignupApiServlet`, `SignupRequest`, `SignupAdminRequests`,
`SignupOptions`, `SignupApiKeys`), `com.etendoerp.go.accounts` (`DemoExpiryService`,
`AccountGroupService`, ETP-6223) and `com.etendoerp.go.payment.AccountDemoExpiry` on top of `com.etendoerp.go.rest.SignupAccountService` (account lifecycle,
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
  "source": { "utm_source": "google", "utm_campaign": "q4", "referrer": "https://..." },
  "hubspotDealId": "123456789"
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
| `termsAccepted` | no | when `true`, the acceptance time is stored (`TERMS_ACCEPTED_AT`) with the terms version in force (`TERMS_VERSION`, see [terms-and-conditions.md](terms-and-conditions.md)); never rejected |
| `sendEmails` | no | default `true`. `false`: no mail at all; the caller asks for the link with `login-link` (sales preparing a demo) |
| `source` | no | a JSON object, stored as-is (`SIGNUP_SOURCE`), ≤ 2000 characters serialized |
| `hubspotDealId` | no | a booked demo's HubSpot deal: digits only (a number or its text), ≤ 30. The HubSpot sync then reports this account's demo on that deal and moves it in the Demo pipeline instead of leaving the account out (ETP-6082, see [hubspot-sync.md](hubspot-sync.md#booked-demos)). Omit it for any other signup |

Unknown fields are ignored. `202 Accepted`:

```json
{ "accountId": "2224E90CE34C4DD5BC86386C0AB6DAC9", "email": "ana@empresa.es", "status": "provisioning" }
```

## `GET /accounts/{id}`

```json
{ "accountId": "…", "email": "ana@empresa.es", "status": "ready",
  "demoExpiresAt": "2026-12-01", "groups": ["beta"] }
```

`status` is `provisioning`, `ready` or `failed`; a failed one also carries `error` (the reason, in
English). Only accounts created through this API are visible: any other id is `404`.

Since ETP-6223, two more fields, always present (callers that ignore unknown fields are not
affected):

- `demoExpiresAt` — the day the account's demo expires (`YYYY-MM-DD`, see
  [Demo expiry](#patch-accountsid-demo-expiry)): the edited date, or the end of the standard trial.
  `null` until the environment is `ready`, and for an environment that is no longer a running demo.
- `groups` — the keys of the [groups](#groups) the account belongs to; `[]` for none.

## `PATCH /accounts/{id}`: demo expiry

Moves the day the account's demo expires (ETP-6223). Unlike the calls above it reaches **any**
account, also one registered in the app: its main use is on accounts that already exist.

```json
{ "demoExpiresAt": "2026-12-01" }
```

- `demoExpiresAt` is a calendar date, `YYYY-MM-DD`. The trial ends at the **start of that day, in
  UTC** (`2026-12-01T00:00:00Z`). A date in the past ends the demo now.
- `null` clears the edited date: the demo goes back to its standard trial
  (`etendo.go.demo.trial.days`, 14 by default, from its start).
- It applies to every demo the account owns (environments whose owner user is the account). An
  account with none — not created yet, or already replaced by a productive environment — is
  `409 ACCOUNT_HAS_NO_DEMO`.
- The access check, the trial bar (`GET /sws/go/environments`), the trial reminders and the expiry
  notice (`DemoTrialNotice*`), and HubSpot all read the new date. A trial whose end was edited is
  de-duplicated on its end too, so moving the date re-arms its reminders.
- A demo frozen with `ETGO_DemoTrialFrozenDays` stays frozen: the freeze wins over the date.

Answers `200` with the day the demo now expires, read back as the access check reads it:

```json
{ "accountId": "…", "demoExpiresAt": "2026-12-01" }
```

The date is `ETGO_ACCOUNT.DEMO_EXPIRES_AT`, editable in Classic in the **Etendo GO Accounts**
window (*Demo Expires At*); both see the same value. Classic applies the same rule as the API
(`AccountDemoExpiryHandler`): setting a date on an account that owns no running demo is refused
with *This account has no running demo…*. Clearing the date is always allowed there, so a date left
on an account whose demo became productive can be removed. That window only edits accounts: it
does not create or delete them (tab pattern *Edit Only*).

## Groups

Groups of accounts, starting with **Beta** (key `beta`, shipped with the module). A group is only
a label: it enables no feature and changes no limit. Like `PATCH /accounts/{id}`, membership
reaches any account. Classic manages the same rows in the **Account Groups** window (a *Group* tab
with an *Accounts* child tab) and shows each account's groups in **Etendo GO Accounts** →
*Groups*.

A group key has lowercase letters, digits, `-` and `_`, starts with a letter or a digit, and is at
most 60 characters. It never changes: it is what callers (and, later, Datadog) refer to.

Classic enforces the same rules on the group's *Search Key* (`AccountGroupKeyHandler`, one rule in
`AccountGroupService.isValidKey`): an invalid key is refused, the key of an existing group cannot be
edited, and a group shipped with a module (`AD_MODULE_ID` set, such as `beta`) cannot be deleted —
deactivate it instead.

### `POST /groups`

```json
{ "key": "beta", "name": "Beta", "description": "Beta users" }
```

`key` and `name` (≤ 120) are required, `description` (≤ 255) is optional. `201`:

```json
{ "key": "beta", "name": "Beta", "description": "Beta users", "active": true }
```

A key in use is `409 GROUP_ALREADY_EXISTS`.

### `GET /groups`

Every group, active or not, by key: `200 { "groups": [ { "key", "name", "description", "active" } ] }`.

### `GET /groups/{key}/accounts`

The members, by email:

```json
{ "key": "beta", "accounts": [
  { "accountId": "…", "email": "ana@empresa.es", "name": "Ana García", "addedAt": "2026-11-12T09:30:00Z" }
] }
```

### `PUT /groups/{key}/accounts/{accountId}` and `DELETE …`

Add an account to the group, or take it out. Both are idempotent and answer `200`:

```json
{ "key": "beta", "accountId": "…", "member": true, "changed": false }
```

`changed` tells whether the call did something (`false`: it already was, or already was not, a
member). An unknown group is `404 GROUP_NOT_FOUND`, an unknown or inactive account
`404 ACCOUNT_NOT_FOUND`. A membership deactivated in Classic counts as none, and `PUT` reactivates
it.

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
| 400 | `INVALID_HUBSPOT_DEAL_ID` | `hubspotDealId` | not the id of a HubSpot deal (digits only, ≤ 30) |
| 400 | `RESERVED_COMPANY_NAME` | `companyName` | the name of a client still being provisioned |
| 400 | `INVALID_DATE` | `demoExpiresAt` | not a `YYYY-MM-DD` calendar date |
| 400 | `INVALID_GROUP_KEY` | `key` | not a valid group key |
| 401 | `INVALID_API_KEY` | — | missing or unknown `X-Api-Key` |
| 404 | `ACCOUNT_NOT_FOUND` | — | no account created through this API has that id (`PATCH` and group membership: no active account has it) |
| 404 | `GROUP_NOT_FOUND` | — | no group has that key |
| 404 | `NOT_FOUND` | — | unknown path |
| 405 | `METHOD_NOT_ALLOWED` | — | wrong method for the path |
| 409 | `EMAIL_ALREADY_REGISTERED` | `email` | the address already has an account |
| 409 | `EMAIL_PENDING_VERIFICATION` | `email` | the address has an app account still waiting for its confirmation |
| 409 | `ENVIRONMENT_NOT_READY` | — | login link before the environment is ready |
| 409 | `RETRY_NOT_ALLOWED` | — | retry of a creation that did not fail |
| 409 | `ACCOUNT_HAS_NO_DEMO` | — | demo expiry of an account that owns no running demo |
| 409 | `GROUP_ALREADY_EXISTS` | `key` | a group with that key exists |
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
| `DEMO_EXPIRES_AT` | ETP-6223: the day the account's demos expire; empty = standard trial |

Groups (ETP-6223): `ETGO_GROUP` (`VALUE` = the key, unique; `NAME`; `DESCRIPTION`; `ISACTIVE`;
`AD_MODULE_ID` set only on the groups the module ships, such as Beta) and `ETGO_ACCOUNT_GROUP`
(one row per account and group, unique per pair, deleted with either side).

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
