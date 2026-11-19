# Terms and conditions at sign-up (ETP-6081)

Every new Etendo GO account accepts the terms and conditions, and records which version it
accepted and when. The same dialog asks, separately, for consent to promotional mail (see
[Consent to promotional mail](#consent-to-promotional-mail)).

Code: `com.etendoerp.go.terms.TermsAndConditionsService`.

## Where the text lives

`ETGO_TERMS`, System data (client `0`), one row per version (and language, when it has one). The
text of each version is a Word document (`.docx`) attached to its row in Classic (ETP-6244); a row
without one is never shown.

| Column | Meaning |
|---|---|
| `AD_LANGUAGE` | optional: the language whose users see the version, e.g. `es_ES`. **Empty: every language** |
| `VERSION` | the version label, e.g. `2026-11`. The same label in every language of one release |
| `PUBLISHED` | from when the version is in force |
| `AD_MODULE_ID` | unused since ETP-6244 (the module no longer ships rows) |

`(AD_LANGUAGE, VERSION)` is unique for the rows that have a language.

The table is not module data: rows are created in the ERP, have no module, and `export.database`
and `update.database` never touch them. Until ETP-6244 the module shipped a text-only
`placeholder` version per language; those rows may remain on existing instances, and since they
have no document they are ignored.

## Loading the document from Classic

1. *Terms and Conditions* window (System Administrator): a new row with a new `VERSION` and a
   `PUBLISHED` not in the future. Leave `Language` empty to show it in every language; set it only
   for a version of one language.
2. Attach the `.docx` to the row with the paperclip (Classic attachments).

No deploy: the next request already reads it. While there is a single (Spanish) text, one row with
no language and one attachment serves every screen.

When several `.docx` are attached to one row, the most recently updated wins. Other attached files
are ignored. Never replace the document of a version already in force: accounts that accepted it
point at its label. Publish a new version instead. Deactivating a row hides it; accounts keep the
label they stored.

## How the document is shown

The register dialog downloads the `.docx` from `GET /sws/go/terms/document` the first time it opens
and converts it to HTML in the browser with mammoth (`fetchTermsDocumentHtml` in
`@etendosoftware/etendo-go-core`, loaded only then). Bold, italics, lists and tables are kept;
colours, fonts and page layout are not. A table whose rows Word marks all as header rows ("Repeat
as header row") keeps only the first one as the header.

The HTML is never injected as markup: `TermsContent.jsx` parses it and rebuilds it from an
allowlist of tags — paragraphs and wrappers (`p`, `div`, `span`, `blockquote`, `hr`, `br`),
`h1`–`h6`, inline formatting (`strong`/`b`, `em`/`i`, `u`, `s`, `sub`, `sup`), lists, tables, and
`a` with an `http(s):` or `mailto:` `href`. Every other attribute is dropped, other tags keep only
their text, and `script`/`style`/`iframe` are dropped whole. Tables scroll horizontally inside the
dialog.

## Which version is in force

Among the active rows with a document and a `PUBLISHED` that is not in the future, looked up in
this order:

1. the rows of the requested language (`es_AR`) and the rows with no language, the latest
   published first (on the same date, the one of the language);
2. another variant of the same language (`es_ES`);
3. English (`en_US`).

A missing or malformed language reads English.

## `GET /sws/go/terms?language=es_ES`

Public, no session: the register screen shows the terms before any account exists. Returns only
the identity of the version in force:

```json
{ "version": "2026-11", "language": null, "publishedAt": 1793750400000 }
```

`language` is `null` for a version of every language. `404 TERMS_NOT_FOUND` when no version with a
document is published.

## `GET /sws/go/terms/document?language=&version=2026-11`

Public as well: the `.docx` attached to the active, published row with exactly that version and
language (empty `language`: the row of every language), as
`application/vnd.openxmlformats-officedocument.wordprocessingml.document`. It serves only
attachments of `ETGO_TERMS` rows; a missing row, a row without document or a malformed parameter
is `404 TERMS_DOCUMENT_NOT_FOUND`.

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
not load the terms) or one that is not a published version with a document, the version in force is recorded instead.

A sign-up is never refused because of the terms: if the table cannot be read, the account is
created with the acceptance date and no version, and a warning is logged.

Accounts created before ETP-6081 have no version and are not asked for anything.

## Consent to promotional mail

The terms dialog of the register screen holds a checkbox, *ticked by default*, by which the account
holder agrees to receive promotional mail. It is a choice of its own: unticking it does not block
the sign-up, and the choice survives closing and reopening the dialog. It is recorded in
`ETGO_ACCOUNT.MARKETING_CONSENT` (`Y`/`N`, default `N`), on the same paths as the terms version,
with the terms acceptance date as its date:

| Path | Consent recorded |
|---|---|
| Register form (`/session/register`, legacy `/register`) | `marketingConsent` from the body |
| SSO sign-in that creates the account | `marketingConsent` from the body, next to the provider credential |
| Company invitation | not asked (the invitee joins a company, not a sign-up lead): `N` |
| Signup API | not asked: `N` |

Anything but an explicit `true` records `N`: a body without the field (an older screen, an
invitation) gave no consent. Accounts created before the field existed are `N`.

