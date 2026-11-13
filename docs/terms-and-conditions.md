# Terms and conditions at sign-up (ETP-6081)

Every new Etendo GO account accepts the terms and conditions, and records which version it
accepted and when. The same dialog asks, separately, for consent to promotional mail (see
[Consent to promotional mail](#consent-to-promotional-mail)).

Code: `com.etendoerp.go.terms.TermsAndConditionsService`.

## Where the text lives

`ETGO_TERMS`, System data (client `0`), one row per language and version:

| Column | Meaning |
|---|---|
| `AD_LANGUAGE` | the language of the text, e.g. `es_ES`, `en_US` |
| `VERSION` | the version label, e.g. `2026-11`. The same label in every language of one release |
| `CONTENT` | optional: the text, when the row has no Word document attached (see [Content format](#content-format)) |
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

## Loading the document from Classic

The real text is a Word document loaded in Classic, never shipped with the module:

1. *Terms and Conditions* window (System Administrator): one new row per language, with the same
   `VERSION` and a later `PUBLISHED`. `CONTENT` can stay empty.
2. On each row, attach the `.docx` with the paperclip (Classic attachments).

While there is no English text, attach the Spanish document to the `en_US` row too, so an English
screen shows it rather than the placeholder.

A row with a `.docx` attached shows the document; without one, its `CONTENT`. When several `.docx`
are attached to one row, the most recently updated wins. Other attached files are ignored. Never
replace the document of a version already in force: accounts that accepted it point at its label.
Publish a new version instead.

## Content format

**Document.** `GET /sws/go/terms` says `hasDocument: true`; the register dialog then downloads the
`.docx` from `GET /sws/go/terms/document?language=<language>&version=<version>` (the `language`
and `version` of that body) the first time it opens, and converts it to HTML in the browser with
mammoth (`fetchTermsDocumentHtml` in `@etendosoftware/etendo-go-core`, loaded only then). Bold,
italics, lists and tables are kept; colours, fonts and page layout are not. A table whose rows
Word marks all as header rows ("Repeat as header row") keeps only the first one as the header.

**`CONTENT`.** Plain text, line breaks kept; HTML when it holds a block tag (`<p>`, `<ul>`,
`<table>`, …).

Either way the HTML is never injected as markup: `TermsContent.jsx` parses it and rebuilds it from
an allowlist of tags — paragraphs and wrappers (`p`, `div`, `span`, `blockquote`, `hr`, `br`),
`h1`–`h6`, inline formatting (`strong`/`b`, `em`/`i`, `u`, `s`, `sub`, `sup`), lists, tables, and
`a` with an `http(s):` or `mailto:` `href`. Every other attribute is dropped, other tags keep only
their text, and `script`/`style`/`iframe` are dropped whole. Tables scroll horizontally inside the
dialog.

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
{ "version": "2026-11", "language": "es_ES", "content": "…", "publishedAt": 1793750400000,
  "hasDocument": false }
```

`hasDocument` is true when a `.docx` is attached to the row (ETP-6244); `content` is then
whatever the row holds, possibly empty.

`404 TERMS_NOT_FOUND` when no version is published at all.

## `GET /sws/go/terms/document?language=es_ES&version=2026-11`

Public as well (ETP-6244): the `.docx` attached to the active, published row with exactly that
language and version, as `application/vnd.openxmlformats-officedocument.wordprocessingml.document`.
It serves only attachments of `ETGO_TERMS` rows; a missing row, a row without document or a
malformed parameter is `404 TERMS_DOCUMENT_NOT_FOUND`.

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

