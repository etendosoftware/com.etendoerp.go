# Onboarding Flow

## Overview

The `POST /sws/go/onboarding` endpoint streams NDJSON progress events while
setting up a newly registered client. The core method is
`EtendoGoJwtServlet.ensureOnboardingDataset`, which runs five steps in order.
Each step either completes or emits an `{"status":"error"}` event and aborts.

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
