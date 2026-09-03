# Onboarding Flow

## Overview

The `POST /sws/go/onboarding` endpoint streams NDJSON progress events while
setting up a newly registered client. The core method is
`EtendoGoJwtServlet.ensureOnboardingDataset`, which runs the steps below in
order (reconcile model, ETP-4428: every step is idempotent/self-guarding, so
the full chain runs unconditionally on every call, repairing whatever a prior
partial failure left missing). Each step either completes or emits an
`{"status":"error"}` event and aborts. After all steps complete, the endpoint
commits the DAL transaction and then sends the `environment-ready`
transactional email best-effort. Email delivery failure is audited by the
transactional email safety store and does not roll back the already committed
environment.

**Do not hardcode the step count in prose** — the list below is the source of
truth; keep it (and this list ONLY) in sync with
`EtendoGoJwtServlet.ensureOnboardingDataset` whenever a step is added,
removed, or reordered.

## Step Sequence

```
 1. dataset             — import sampledata XML into the new client/org
 2. accounting          — wire the accounting schema (OnboardingAccountingWiringService#wire)
 3. periodControl       — open the initial fiscal calendar / period control
 4. sequences           — generate document-number sequences (AD_SEQUENCE)
 5. orgReady            — mark the org as ready (AD_ORG.isready = Y)
 6. fiscal              — seed SII descriptions (AEATSII_DESCRIPTION)
 7. orgInfo             — wire org fiscal/address info from the signup form
 8. customer            — ensure a default customer business partner exists
 9. bankConnectionSync  — schedule the PSD2 daily bank-statement sync (non-fatal; wired live 2026-06-28)
10. bpGroupAcctPatch    — patch C_BP_Group_Acct columns the core trigger never populates (ETP-4720)
11. acctdimVisibility   — force flat accounting-dimension visibility (gap K1, ETP-4854)
12. baseline            — stamp the tenant's data-fix baseline (registerBaseline; always LAST)
```

The `orgReady`, `fiscal`, and `customer` steps were added to fix the
"environment not ready for invoicing" error that occurred when the
org-accessibility filter hid all org-scoped records because `isready=N`.

Steps 9–11 are corrective/preventive gap-closing steps layered on top of the
original five (`accounting`, `periodControl`, `orgInfo` predate them too, ETP
numbers as noted). `baseline` is always the final step — it stamps
`ONBOARDING_PROVISIONED_THROUGH` (in `OnboardingBaselineService`) so the
corrective data-fix runner (`cli/src/data-fixes/` in `etendo_schema_forge`)
knows which fixes this tenant was already born with and skips them. **Full
per-step rationale, the preventive/corrective "two fronts" pairing, and the
onboarding-gap catalog (A1…K1) live in the sibling functional repo:**
`etendo_schema_forge/docs/etendo-ad/onboarding-and-datafixes-map.md` and
`onboarding-gaps.md` — this file intentionally does not duplicate that detail
(see the repo-topology split: this repo documents runtime/API behavior, the
functional repo documents the gap analysis and data-fixes).

## Services

### `OnboardingDatasetImportService`
Imports the curated GOClient sampledata XML files into the target client/org via
`DataImportService`. The dataset is loaded from the classpath (staged during
WAR build — see `onboarding-sampledata-packaging.md`).

### `OnboardingAccountingWiringService`
Step 2 (`wire`) creates the client's accounting schema / `C_AcctSchema_Default`
wiring; a later entry point on the SAME service, `patchBpGroupAcctMissingColumns`
(step 10), patches 5 `C_BP_Group_Acct` columns left NULL by both the core
trigger and this service's own initial SQL (ETP-4720). See
`etendo_schema_forge/docs/etendo-ad/onboarding-and-datafixes-map.md` for the
full root-cause writeup.

`wire()`'s internal step order is: wire the org's general ledger →
`ensureOrganizationAcctSchema` → `wireAccountElementTree` →
`rebrandImportedChartNames` → **`provisionGlItemsForImportedChart`
(ETP-5020)** → `provisionEntityPostingAccounts`. The GL Item step runs AFTER
the chart names are rebranded (a GL Item minted against the dataset's generic
"GOClient" names would immediately diverge from the tenant's real subaccount
name — exactly the divergence ETP-5020 exists to prevent) and BEFORE the
unrelated per-entity posting-account provisioning.

`provisionGlItemsForImportedChart` iterates every leaf (`elementLevel = 'S'`)
`ElementValue` of the tenant's freshly-imported chart and calls
`GlItemProvisioningSupport#ensureGlItemForSubaccount` for each — the SAME
support class `ChartOfAccountsHandler.afterHandle`'s live subaccount-create
hook uses, so the bulk onboarding path and the live per-subaccount path can
never drift into different behavior. For every active `AcctSchema`, it looks
up (never creates) the natural `C_ValidCombination` the `C_ELEMENTVALUE_TRG`
native trigger — or, for the bulk chart, the dataset's own bundled
`C_VALIDCOMBINATION.xml` rows (see "Dataset Included Tables" below) — already
produced for that leaf, and wires it as both the debit and credit account of
one auto-created (invisible) `C_Glitem`/`C_Glitem_Acct` pair. A summary/heading account has no such combination and is silently skipped (no GL Item is ever created for it). Idempotent and best-effort: re-running onboarding never duplicates a GL Item, and a provisioning failure for one schema or one leaf never blocks remaining schemas, the rest of the chart, or the onboarding chain. See
`GlItemProvisioningSupport`'s class javadoc
(`src/com/etendoerp/go/schemaforge/handlers/GlItemProvisioningSupport.java`)
for the full design rationale, and
`etendo_schema_forge/docs/plans/santo_ETP-5020-gl-item-auto-management.md`
for the original ticket analysis.

### `OnboardingPeriodControlService`
Step 3. Opens the initial fiscal calendar / period control for the new
client/org so documents can be posted from day one.

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

### `OnboardingOrgInfoService`
Step 7. Wires the org's fiscal/address information collected on the signup
form (country, fiscal ID, address) onto the newly created `AD_Org`/legal
entity.

### `OnboardingDefaultCustomerService`
Creates a default `C_BPARTNER` customer record if none already exists for the
org. The default customer is pre-selected on new sales invoice drafts.

### `OnboardingBankConnectionSyncService`
Step 9. Intentionally **non-fatal** — always returns `true` and swallows
errors (logs + `done` "skipped"). Schedules one daily `AD_Process_Request` per
client that runs PSD2 `Get Bank Statements`, so Salt Edge-connected accounts
auto-import statements. Has a post-commit companion,
`activateSchedule(clientId)`, called right after `commitDalChanges` (not
inside this chain) because the Quartz scheduler needs a committed row.

### `OnboardingAcctdimCentrallyMaintainedService`
Step 11 (`forceFlatAccountingDimensionVisibility`, ETP-4854, gap K1). Backfills
`C_AcctSchema_Element.isactive` per elementtype from the client's current
effective `AD_Client.<Dim>_Acctdim_*` config, then flips
`AD_Client.Acctdim_Centrally_Maintained` to `'N'` so the "Dimensiones
contables" screen is functional for the tenant from birth, with no change in
observed dimension visibility. Lockstep corrective twin:
`R23-acctdim-centrally-maintained.sql` in `etendo_schema_forge`. Full
root-cause and safety analysis:
`etendo_schema_forge/docs/etendo-ad/onboarding-gaps.md` §K1.

**Runtime consumer, flat-source-only (ETP-5101).** The class this step backfills toward —
`C_AcctSchema_Element.IsActive`, the "Ledger Configuration" screen's per-dimension switch — is
also the *only* source `AccountingDimensionsSupport`
(`src/com/etendoerp/go/schemaforge/AccountingDimensionsSupport.java`) reads at request time for
every GO consumer of accounting-dimension visibility: `FinancialAccountTransactionsHandler`
(`enabledDimensions`/`headerDimensions` on the New/Edit Movement UI), `MatchRuleHandler`
(`GET ?action=activeDimensions` and its save-time dimension filter for the Automatch rule
catalog), and `ReconciliationHandler` (dimensions assignable on a reconciliation difference
posting). An earlier version of `AccountingDimensionsSupport` instead read
`Acctdim_Centrally_Maintained`/`AD_Client_AcctDimension`'s per-document-type matrix, scoped to
`docBaseType = FAT`, on the theory that a `FIN_Finacc_Transaction` needed the same
document-type-scoped treatment a real header+lines document gets. That machinery has been
removed entirely: a `FIN_Finacc_Transaction` is a tab-level-1 line under
`FIN_Financial_Account`, never a document header, and product direction settled on the same flat,
per-tenant switch every other GO window already uses — no document-type override. This step's
backfill is what makes that flat switch a reliable source for a tenant from birth; see
`AccountingDimensionsSupport`'s own class javadoc for the full history.

### `OnboardingBaselineService`
Step 12, always last. Stamps the data-fix baseline row (`applied_utc =
ONBOARDING_PROVISIONED_THROUGH`, a hardcoded cutoff — NOT `now()`) so the
corrective data-fix runner knows which fixes a freshly-onboarded tenant
already has natively and skips them. Single source of truth for the
watermark — there is no separate `RegisterBaselineStep`.

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
| `M_COSTING_RULE` | Without a costing rule a new tenant computes cost for zero transactions (`M_Transaction.iscostcalculated` stuck `'N'`); the bundled row seeds a validated Standard rule (ETP-4760) |

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

SSO-only accounts are created without a local password hash. Existing local
accounts are auto-linked by email only when the provider-specific verifier marks
that email as authoritative. The Google implementation does this for any email
verified by Google (where the `email_verified` claim is `true`). No email
verification fields or login gates are added.

## Onboarding Draft (resume support)

The create-environment wizard can be resumed after a re-login. The in-progress
wizard state is persisted server-side in `ETGO_ACCOUNT.ONBOARDING_DRAFT`
(nullable `VARCHAR(4000)` JSON blob: `{ "step": 1|2, "form": { ... } }`).

Endpoints (session-token auth, same Bearer model as `/me`):

- `GET  /sws/go/onboarding/draft` — returns `{ status, draft }`; `draft` is the
  stored object or `null`. Invalid stored JSON is ignored and reported as `null`.
- `POST /sws/go/onboarding/draft` — body `{ "draft": { "step", "form" } }` saves;
  `{ "draft": null }` clears. Only whitelisted wizard form fields are persisted
  (`fullName`, `businessType`, `clientName`, `currency`, `language`,
  `countryCode`, `fiscalIdType`, `fiscalIdValue`, `address`, `sector`) and the
  serialized draft is capped at 4000 chars (400 otherwise).

The draft is cleared automatically (best-effort, non-blocking) by
`POST /sws/go/onboarding` right after the environment commit succeeds, so a
completed onboarding never resurrects a stale wizard.

The frontend (`OnboardingPage.jsx`) fetches the draft when an authenticated
account has zero environments, restores step + form, shows a one-time
"progress restored" banner, and autosaves changes debounced (1.5 s) while the
wizard is visible and not running.

## Startup Access Self-Healer (`NeoAccessStartup`)

`com.etendoerp.go.startup.NeoAccessStartup` is an `ApplicationInitializer`
(`@ApplicationScoped` + `@ComponentProvider.Qualifier`) that grants — idempotently,
on every application startup — the `WindowAccess` / `ProcessAccess` that automatic
roles are missing for module-shipped NEO windows/processes.

### Why it exists

Window access for automatic roles (`ad_role.ismanual='N'`) is normally created by
two DB triggers, both gated by `IF AD_isTriggerEnabled()='N' THEN RETURN;`:

- `AD_WINDOW_TRG` (AFTER INSERT on `AD_WINDOW`) — inserts `ad_window_access` for all
  existing non-manual roles.
- `AD_ROLE_TRG` (INSERT/UPDATE on `AD_ROLE`) — rebuilds window/process/form access
  for non-manual roles by UserLevel (destructive: it DELETEs then re-inserts).

Module windows install via `update.database`, which runs with **triggers disabled**,
so `AD_WINDOW_TRG` never fires for them. The base GOClient sampledata ships no
`AD_WINDOW_ACCESS.xml` and its roles do not go through onboarding (`CreateRoleStep`).
Net result: on a clean install nothing grants those roles access to module windows
(e.g. "Match Rule" `24963D64E83B4543A7F6BD248CF944EE`, Verifactu/SII/TBAI windows).

### Behavior

1. `initialize()` spawns a daemon thread so it never blocks (nor fails) startup.
2. The worker first waits for `SessionInfo.isInitialized()` (poll ~100 ms, ~60 s
   timeout, then proceed) — borrowing a DAL connection too early hits the
   `ad_context_info` temp-table problem.
3. Under `OBContext.setAdminMode()`, it selects all roles with `active = true` and
   `manual = false`, skips the system client (`'0'`), and for each remaining role
   grants any missing access:
   - active `SFSpec` of `specType='W'` with a non-null window → `WindowAccess`
     (client = role's client, org `'0'`, `editableField = true`),
   - active `SFSpec` of `specType='P'` with a non-null process → `ProcessAccess`,
   - only when the role does not already have it (existing ids queried per role).
4. One `flush()` + `commitAndClose()`. On any error it logs and
   `rollbackAndClose()`s — startup is never allowed to fail.

The grant logic mirrors `CreateRoleStep` exactly, so freshly onboarded tenants and
self-healed existing tenants converge on the same access set.

### Invariants

- **INSERT-only into the access tables.** It never touches `AD_WINDOW` nor any
  `AD_ROLE` row, so it can never trigger `AD_ROLE_TRG`'s destructive rebuild.
- **Idempotent.** Re-running on the next restart grants nothing new.
- **No SQL migration.** Existing databases self-heal on the next Tomcat restart.
