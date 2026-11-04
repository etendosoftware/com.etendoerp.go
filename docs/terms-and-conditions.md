# Terms and conditions at sign-up (ETP-6081)

Every new Etendo GO account accepts the terms and conditions, and records which version it
accepted and when. The terms also carry the authorization to send the account holder commercial
content, so the record is what proves that consent.

Code: `com.etendoerp.go.terms.TermsAndConditionsService`.

## Where the text lives

`ETGO_TERMS`, System data (client `0`), one row per language and version:

| Column | Meaning |
|---|---|
| `AD_LANGUAGE` | the language of the text, e.g. `es_ES`, `en_US` |
| `VERSION` | the version label, e.g. `2026-11`. The same label in every language of one release |
| `CONTENT` | the text, plain text; line breaks are kept, nothing is rendered as HTML |
| `PUBLISHED` | from when the version is in force |
| `AD_MODULE_ID` | set only on the rows the module ships (the placeholder); empty on the ones loaded in the ERP |

`(AD_LANGUAGE, VERSION)` is unique.

**Publishing a new version** is a new row per language in the *Terms and Conditions* window
(System Administrator), with a later `PUBLISHED`. No deploy: the next request already reads it.
Rows loaded this way have no module, so `export.database` and `update.database` never touch them.
Never edit the text of a version already in force: accounts that accepted it point at its label.
Deactivating a row hides it from the endpoint; accounts keep the label they stored.

The module ships one placeholder version per language as module data (`AD_DATASET` `0`, filtered
by `AD_MODULE_ID`), to be replaced by the real text as a new version.

## Which version is in force

For a requested language, the active row with the latest `PUBLISHED` that is not in the future,
looked up in this order:

1. the requested language (`es_AR`);
2. another variant of the same language (`es_ES`);
3. English (`en_US`).

A missing or malformed language reads English.

## `GET /sws/go/terms?language=es_ES`

Public, no session: the register screen shows the terms before any account exists. Returns only
the text and its identity:

```json
{ "version": "2026-11", "language": "es_ES", "content": "…", "publishedAt": 1793750400000 }
```

`404 TERMS_NOT_FOUND` when no version is published at all.

## What an account records

`ETGO_ACCOUNT.TERMS_VERSION` and `ETGO_ACCOUNT.TERMS_ACCEPTED_AT`, on every path that creates an
account at sign-up:

| Path | Version recorded |
|---|---|
| Register form (`POST /sws/go/session/register`, legacy `/register`) | `termsVersion` from the body |
| SSO sign-in that creates the account (`/session/sso/{provider}`, legacy `/sso/{provider}`) | `termsVersion` from the body, next to the provider credential |
| Company invitation (`POST /company-invitations/register-and-accept`) | `termsVersion` from the body |
| Signup API (`termsAccepted: true`) | the version in force; nothing when `termsAccepted` is false |

The version is the one the register screen **showed**: a version published while someone was
registering does not replace what that person saw. When the body carries none (the screen could
not load the terms) or one that does not exist, the version in force is recorded instead.

A sign-up is never refused because of the terms: if the table cannot be read, the account is
created with the acceptance date and no version, and a warning is logged.

Accounts created before ETP-6081 have no version and are not asked for anything.
