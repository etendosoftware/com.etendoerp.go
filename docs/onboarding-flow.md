# Onboarding Flow

## Overview

The `POST /sws/go/onboarding` endpoint streams NDJSON progress events while
setting up a newly registered client. The core method is
`EtendoGoJwtServlet.ensureOnboardingDataset`, which runs five steps in order.
Each step either completes or emits an `{"status":"error"}` event and aborts.
After all steps complete, the endpoint commits the DAL transaction and then sends
the `environment-ready` transactional email best-effort. Email delivery failure
is audited by the transactional email safety store and does not roll back the
already committed environment.

## Step Sequence

```
1. dataset    — import sampledata XML into the new client/org
2. sequences  — generate document-number sequences (AD_SEQUENCE)
3. orgReady   — mark the org as ready (AD_ORG.isready = Y)
4. fiscal     — seed SII descriptions (AEATSII_DESCRIPTION)
5. customer   — ensure a default customer business partner exists
```

Steps 3–5 were added to fix the "environment not ready for invoicing" error that
occurred when the org-accessibility filter hid all org-scoped records because
`isready=N`.

## Services

### `OnboardingDatasetImportService`
Imports the curated GOClient sampledata XML files into the target client/org via
`DataImportService`. The dataset is loaded from the classpath (staged during
WAR build — see `onboarding-sampledata-packaging.md`).

### `OnboardingSequenceGeneratorService`
Generates `AD_SEQUENCE` records for all document types that require a number
sequence (invoices, orders, delivery notes, etc.). Runs under the client's admin
context so sequences are owned by the correct client.

### `OnboardingMarkOrgReadyService`
Executes the `AD_Org_Ready` Etendo process which sets `AD_ORG.isready = Y`.
This step is mandatory: until an org is ready, Etendo's org-accessibility filter
excludes its records from every OBDal query, making all the imported reference
data invisible to the rest of the onboarding and to the frontend.

The service:
- Skips silently if the org is already ready.
- Flushes pending OBDal changes before running the process so the process sees
  a consistent DB state.
- Defensively sets `isready = Y` via OBDal if the process completed without
  flipping the flag (guard against process implementations that skip the update
  under certain conditions).

### `OnboardingFiscalDataSetupService`
Creates two `AEATSII_DESCRIPTION` records (Ventas + Compras) for the new
client if none exist yet. These SII descriptions are required by the Spanish
SII reporting module and must be present before the user raises their first
invoice. Runs under the admin user's execution context.

### `OnboardingDefaultCustomerService`
Creates a default `C_BPARTNER` customer record if none already exists for the
org. The default customer is pre-selected on new sales invoice drafts.

## Dataset Included Tables

`OnboardingDatasetDefinition.INCLUDED_TABLES` is the whitelist of XML table
names that the import step processes. Key entries and their rationale:

| Table | Reason |
|-------|--------|
| `C_BP_TAXCATEGORY` | Referenced by `C_TAX`; must be imported before tax records |
| `C_TAX` / `C_TAXCATEGORY` | VAT rates required for invoicing |
| `C_DOCTYPE` | Document types (invoice, order, etc.) |
| `C_PAYMENTTERM` | Payment terms required for invoicing |
| `AD_SEQUENCE` / `GL_CATEGORY` | Document-number sequences and GL categories |

## NDJSON Progress Events

Each step emits two events:

```json
{"step":"dataset","status":"in_progress","message":"Importing onboarding dataset..."}
{"step":"dataset","status":"done","message":"Dataset imported successfully"}
```

On error:

```json
{"step":"sequences","status":"error","message":"broken sequences","success":false}
```

The final event always carries `"success": true|false`.

## Transactional Email Behavior

The onboarding flow participates in the local-account transactional auth email
model:

- `/sws/go/register` sends `new-account` after the account commit.
- `/sws/go/onboarding` sends `environment-ready` only after onboarding commits.
- Both emails use server-generated links based on `etendo.go.app.baseUrl` or
  `ETGO_APP_BASE_URL`.
- Email verification is intentionally out of scope for local accounts; onboarding
  and login are not blocked by an email verification state because SSO is the
  next authentication step.
- `login-alert` remains a registered contract but is not triggered until the SSO
  and risk-policy model is defined.

## Provider-Agnostic SSO Behavior

SSO account login is provider-agnostic at the account boundary. The public
endpoint shape is `POST /sws/go/sso/{provider}` and the backend resolves the
provider-specific verifier from a server-side registry. All providers return the
same internal assertion shape: provider id, stable external subject, resolved
email, display name, and whether the provider is authoritative for that email.

Google is the first implementation at `POST /sws/go/sso/google`. It uses Google
Identity Services, not the deprecated Google Sign-In `gapi.auth2` platform
library. The web client must render the Google button with `google.accounts.id`
and should enable FedCM for the button flow.

The Google JavaScript callback flow sends only the Google ID token in
`credential`; provider payload fields such as `subject`, `email`, or `name` are
ignored as client authority. If a Google form/login-uri flow later sends a
`g_csrf_token`, the server validates it against the matching GIS cookie, but the
callback flow is not gated on that cookie. The server validates the ID token with
Google, checks the configured audience, and stores the Google `sub` claim as the
stable external subject.

Configuration:

| Property | Environment variable | Description |
| --- | --- | --- |
| `etendo.go.sso.google.clientId` | `ETGO_GOOGLE_CLIENT_ID` | Required Google Web OAuth client ID. Multiple IDs can be comma-separated. |
| `etendo.go.sso.google.hostedDomain` | `ETGO_GOOGLE_HOSTED_DOMAIN` | Optional Google Workspace hosted-domain restriction. |

SSO-only accounts are created without a local password hash. Existing local
accounts are auto-linked by email only when the provider-specific verifier marks
that email as authoritative. The Google implementation does this for `@gmail.com`
addresses or verified Google Workspace `hd` claims; other matching emails
require an explicit linking flow. No email verification fields or login gates
are added.
