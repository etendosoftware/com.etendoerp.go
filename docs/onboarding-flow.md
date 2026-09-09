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
 8. bankConnectionSync  — schedule the PSD2 daily bank-statement sync (non-fatal; wired live 2026-06-28)
 9. bpGroupAcctPatch    — patch C_BP_Group_Acct columns the core trigger never populates (ETP-4720)
10. acctdimVisibility   — force flat accounting-dimension visibility (gap K1, ETP-4854)
11. baseline            — stamp the tenant's data-fix baseline (registerBaseline; always LAST)
```

The `orgReady` and `fiscal` steps were added to fix the "environment not ready
for invoicing" error that occurred when the org-accessibility filter hid all
org-scoped records because `isready=N`.

**Removed in ETP-5079 — the `customer` step.** Onboarding used to run a
`customer` step between `orgInfo` and `bankConnectionSync` that created a
synthetic "Default Customer" `C_BPARTNER` (search key
`ONBOARDING_DEFAULT_CUSTOMER`), its address and a "Default Customer Contact"
`AD_User`, so a demo Sales Invoice had a counterparty. **A new tenant is now
born with zero business partners.** The service
(`OnboardingDefaultCustomerService`), its servlet step, its `customer` NDJSON
progress events and the follow-up
`OnboardingAccountingWiringService#wireBusinessPartnerAccounts` call it carried
are all gone. Two consequences worth knowing:
* `C_BP_Customer_Acct` / `C_BP_Vendor_Acct` are empty on a fresh tenant. That is
  correct, not a gap: both inserts are set-based over `C_BPartner`, and partners
  the tenant creates later get their posting rows from Classic's own
  `c_bpartner_trg`.
* The SPA's post-onboarding readiness gate must not require a customer. Its
  `customers` leg was removed in the same ticket
  (`etendo_schema_forge/tools/app-shell/src/pages/onboarding/onboardingReadiness.js`)
  — without that change onboarding would finish provisioning and then refuse to
  let the user into the new environment.

Steps 8–10 are corrective/preventive gap-closing steps layered on top of the
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

`validateImportedSeed` fails the onboarding when the imported seed has no
product, warehouse or price list; `isSeedAlreadyPresent` uses the same three
counts as the idempotent-resume probe, so a retry after a partial failure
finishes the job instead of re-importing and duplicating rows. **Financial
accounts are deliberately NOT part of either check (ETP-5079):** the dataset no
longer ships the three template accounts, so a zero count is now the normal
outcome. Keeping them in the gate would fail every onboarding; keeping them in
the probe would make it permanently `false` and turn every retry into a
duplicate import. The count is still logged for diagnostics.

### `OnboardingAccountingWiringService`
Step 2 (`wire`) creates the client's accounting schema / `C_AcctSchema_Default`
wiring; a later entry point on the SAME service, `patchBpGroupAcctMissingColumns`
(step 9), patches 5 `C_BP_Group_Acct` columns left NULL by both the core
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

### `OnboardingBankConnectionSyncService`
Step 8. Intentionally **non-fatal** — always returns `true` and swallows
errors (logs + `done` "skipped"). Schedules one daily `AD_Process_Request` per
client that runs PSD2 `Get Bank Statements`, so Salt Edge-connected accounts
auto-import statements. Has a post-commit companion,
`activateSchedule(clientId)`, called right after `commitDalChanges` (not
inside this chain) because the Quartz scheduler needs a committed row.

### `OnboardingAcctdimCentrallyMaintainedService`
Step 10 (`forceFlatAccountingDimensionVisibility`, ETP-4854, gap K1). Backfills
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
Step 11, always last. Stamps the data-fix baseline row (`applied_utc =
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
| `C_DOCTYPE` | Document types (invoice, order, etc.) — base names are always **English** |
| `C_DOCTYPE_TRL` | Document-type translations. Added in ETP-5079: without it a tenant got 49 doc types and **zero** translations, so every document type rendered with its English base name whatever the user's language |
| `C_PAYMENTTERM` | Payment terms required for invoicing |
| `AD_SEQUENCE` / `GL_CATEGORY` | Document-number sequences and GL categories |
| `M_COSTING_RULE` | Without a costing rule a new tenant computes cost for zero transactions (`M_Transaction.iscostcalculated` stuck `'N'`); the bundled row seeds a validated Standard rule (ETP-4760) |

A table that is NOT on this list never reaches a tenant, however much its XML
file contains — `C_BPARTNER`, for instance, is absent, so onboarding creates no
sample business partners at all. Editing an XML for a non-listed table changes
nothing for a new tenant.

**Dataset content corrected by ETP-5079.** The bundled data now ships: price
lists named "Tarifa de venta/compra principal"; a **single** warehouse
"Almacen Principal" (value `AG`) with one locator; **no** sample products —
only the internal `ETGO_DTO` "Discount" product the inline-discount feature
resolves at runtime, which is not sample data and must never be removed; **no**
default financial accounts (a consequence a tenant must accept: it cannot
register a payment or receipt until it creates an account itself); 11 document
sequences whose `STARTNO` equals their `CURRENTNEXT`; and English base names for
every document type, with the Spanish wording in `C_DOCTYPE_TRL`. Two
`M_PRODUCT_CATEGORY` rows are kept — `Otros` as the generic starter category and
`Discounts`, which `ETGO_DTO` requires (`Bebidas` was dropped in ETP-5079) —
along with all four `FIN_PAYMENTMETHOD` rows. Corrective twin for
already-provisioned tenants: `R31-document-sequence-startno` in
`etendo_schema_forge`, which covers the sequences.

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

## First Steps Checklist (post-signup onboarding window)

After an environment exists, the app shows a "First Steps" window that walks the
user through the initial setup tasks. Its progress is persisted server-side in
`ETGO_ACCOUNT.FIRST_STEPS` (nullable `VARCHAR(1000)` JSON blob:
`{ "v": 1, "seen": true, "completed": ["company-data", "products"] }`), so the
checklist keeps its state across logins and devices.

### Which steps a tenant is shown (plan gate)

The checklist is **shorter on a trial**. Two steps carry `productiveOnly` in
`firstStepsConfig.js` — invoice numbering and the fiscal configuration — and are hidden while
the tenant is on the free plan:

| Plan | Steps shown | Counter |
|---|---|---|
| free / trial | create-account, company-data, products, contacts, team | `x/5` |
| productive | the five above plus fiscal-config and invoice-sequence | `x/7` |

The reason is functional, not cosmetic: a document series a tenant abandons after the trial
numbers nothing, and the fiscal setup is what the productive environment gets created with.
Before the gate a trial tenant could never finish the checklist — the two rows it had no way to
act on held it at 5/7 permanently.

**Where the plan comes from.** No endpoint answers "what plan is the environment I am inside
on". `GET /sws/go/onboarding/first-steps` and `/sws/go/me` are account-scoped and do not know
which client the shell opened; `POST /sws/go/login` returns only a JWT and the role list. So the
browser derives it: `GET /sws/go/environments` reports `plan` per environment (ETP-4686) and the
session's client id is in `localStorage.sf_auth_client_id` — `useTenantPlan` matches the two. It
uses the same predicate as the company switcher's Demo/Productivo badge, so the badge and the
checklist length cannot disagree.

**An unknown plan shows everything.** `useTenantPlan` answers `null` when it cannot know — no
platform token, a failed request, or a client id with no matching row — and
`isProductivePlan(null)` is deliberately `true`. Hiding invoice numbering from a tenant that
paid for it is a worse failure than showing a trial two extra rows, and it is also what every
tenant saw before the gate existed.

**The server allowlist is NOT gated.** `FIRST_STEPS_IDS` stays the full set of six toggleable
ids: it has no notion of a plan, and a tenant that goes productive must be able to persist the
two steps that just appeared. The narrowing happens client-side — `FirstStepsProvider` passes
`toggleableStepIds(plan)` to `useFirstSteps` as its write allowlist, so a step the current plan
does not show cannot be written by accident. The two lists are allowed to differ; only the
client's may be the smaller one.

**Progress is counted over the visible list**, not over the stored ids. A tenant that completed
everything while productive and is later reported free (an `/environments` hiccup) would
otherwise render `7/5`.

Endpoints (session-token auth, same Bearer model as `/me`):

- `GET  /sws/go/onboarding/first-steps` — returns `{ status, firstSteps }`;
  `firstSteps` is the stored object or `null` when nothing has been saved yet.
  Invalid stored JSON is logged as a warning and reported as `null`, never as an
  error — a corrupt value can never lock the user out of the window.
- `POST /sws/go/onboarding/first-steps` — body
  `{ "firstSteps": { "v", "seen", "completed" } }` saves; `{ "firstSteps": null }`
  clears the stored value.

Both endpoints answer `401` without a valid `Authorization: Bearer <session_token>`
header (via `runWithAuthenticatedAccount`, the same template the draft endpoints use).

### Sanitization on write

The client payload is never persisted as-is. `sanitizeFirstSteps` rebuilds the
stored object field by field:

| Field | Stored as |
|---|---|
| `v` | always `1` (`FIRST_STEPS_VERSION`), whatever the client sent |
| `seen` | coerced to a real boolean, defaulting to `false` |
| `completed` | the client array intersected with a fixed allowlist of step ids |

The step-id allowlist is, in stored order:

`company-data`, `fiscal-config`, `products`, `contacts`, `invoice-sequence`, `team`

That is the order the checklist renders (`firstStepsConfig.js`), so a stored value reads
the way the user saw it. The order is cosmetic — the frontend only tests membership — but
keeping the two lists aligned is what makes a stored blob readable at a glance.

Consequences of the intersection, all deliberate:

- **Unknown step ids are dropped silently**, not rejected — an older or newer
  frontend never gets a `400` for sending an id this backend does not know.
- `create-account` is deliberately **not** allowlisted: it is implicit, the account
  already exists. A client sending it has it dropped.
- Non-string entries (numbers, `null`, nested objects) are dropped.
- Duplicates collapse, and the stored array is always emitted in allowlist
  order, so the persisted value is stable regardless of the order the client
  sent its ids.
- A `completed` value that is not an array at all is treated as empty.

### Size cap

The serialized value is capped at 1000 chars (`FIRST_STEPS_MAX_LENGTH`, matching
the column width); a longer payload gets a `400 First steps payload is too large`.
Because the sanitizer emits a fixed-shape object drawn from a closed allowlist,
the current maximum output is well under 100 chars — the cap is a defensive guard
that only becomes reachable if the allowlist grows substantially, and it exists so
the endpoint can never write a value the column cannot hold.

Unlike the onboarding draft, this value is **not** cleared by
`POST /sws/go/onboarding` — the checklist is about what the user has done *after*
the environment exists, so it must survive environment creation.

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

## Invoice numbering (First Steps step)

Invoice numbering is configured in the **Document Sequence** window (`document-sequence`,
AD window `112`), which the "Customize your invoices" First Steps step navigates to. There is no
onboarding endpoint for it: the window is an ordinary NEO CRUD spec over `AD_Sequence`, so the
prefix, suffix, starting number and next number are edited through the generic
`/sws/neo/document-sequence` path like any other window.

An earlier iteration of this step edited the sales and purchase prefixes inline through
`GET`/`POST /sws/go/onboarding/invoice-sequence`. Both endpoints and
`OnboardingInvoiceSequenceService` were removed when the window landed — a form that reached
exactly two of a tenant's sequences was a narrower answer than the window, and keeping both
meant two ways to write the same rows.

### Prefix validation

`DocumentSequenceHandler` (a `NeoHandler` bound to the spec's `Java_Qualifier`) rejects a prefix
the Spanish fiscal localizations would refuse, **before** it is stored — see
`docs/neo-headless-extensibility.md` for the handler pattern. Classic validates the same rules,
but only inside `ProcessInvoiceTbaiHook.preProcess`, which runs when a *rectificative* invoice is
completed in a TicketBAI-configured organization. A prefix chosen during onboarding therefore
went unchecked for as long as it took to issue that first corrective invoice.

The rules, taken from that hook:

| Rule | Rejects |
|---|---|
| length | more than 20 characters |
| lowercase / accents | `[a-záéíóúüñ]` |
| forbidden letters | `[IOYWÑ]` |
| character set | anything outside `A-Z0-9-` |

They are applied **only when the organization's country is Spain** (`AD_OrgInfo` →
`C_Location` → `C_Country.CountryCode = 'ES'`), because they are localization rules, not Etendo
ones: `W`, for instance, is a perfectly ordinary prefix letter elsewhere. Widening or narrowing
that gate is a Localization-team decision, not a GO one.


## Tax identifier validation (NIF / CIF / NIE)

A tenant sets its own fiscal identifier at exactly two moments, and both are guarded by
`com.etendoerp.go.common.SpanishTaxIdValidator`:

| Moment | Guard | Answer |
|---|---|---|
| Signup wizard (`fiscalIdValue`) | `EtendoGoJwtServlet#validateOnboardingTaxId`, called from `parseOnboardingRequest` | `400` before the NDJSON provisioning stream opens |
| Signup wizard, in the browser | `CompanyStepWithTaxId` (`pages/onboarding/onboardingSteps.jsx`) | on blur and on **Empezar** — the click does not advance the view |
| Organización window (`AD_OrgInfo.TaxID`) | `OrganizationInformationHandler` (`Java_Qualifier` `organization-information`) | `400` from the NEO CRUD write |

The browser runs the same three rules in `tools/app-shell/src/lib/taxIdValidation.js` so the user
is told before the round trip; the two Java call sites are what make it binding.

The wizard's own step lives in the published `@etendosoftware/etendo-go-core` package, whose
`CompanyStep` takes no validator from `config` — so it is guarded from the consuming repo instead:
`coreSteps` is a plain `{ id, component }` array and `onNext` is a prop, so
`pages/onboarding/onboardingSteps.jsx` swaps in a wrapper that validates before delegating.
Two constraints shaped that wrapper and are worth knowing before changing it: it adds **no DOM of
its own** (`OnboardingFlow` renders the step as a direct child of a `lg:grid-cols-[...]`
container, so a wrapping `<div>` collapses the two-column layout), and it can show **no inline
message under the field** (no error slot exists), so the message is a toast plus `aria-invalid`
on the input. All three
implementations share one case list — see `SpanishTaxIdValidatorTest` and
`taxIdValidation.test.js`, which are deliberately the same values.

### What classic validates, and why that was not enough

Nothing validates `AD_OrgInfo.TaxID`: no callout, no validation rule, no event handler
(verified against the instance's `AD_COLUMN`). The only check that ever looks at an
organization's own identifier is `com.etendoerp.verifactu`'s `InitialValidator`, which calls
`NIFValidator.validateCompanyNIF(VerifactuUtils.getTaxIDIssuer(...))` while **completing an
invoice** — so a wrong NIF typed at signup surfaced as a failure to invoice, weeks later, on a
value already stamped across the tenant's fiscal configuration.

What classic does observe is the **business partner** identifier, through
`org.openbravo.module.bptaxidkey`'s `ViesStatusObserver`. That one is not a substitute: it
records a `V`/`I`/`P` status in `EM_OBTIK_VIESStatus`, logs and swallows its own failures
("VIES check failed (non-fatal)"), and so never rejects anything — and it needs the
country-prefixed form (`ES12345678Z`), whereas a bare NIF makes its `substring(0, 2)` read `12`
as a country code.

### The three accepted shapes

| Shape | Pattern | Check digit |
|---|---|---|
| CIF (company) | `[ABCDEFGHJKLMNPQRSUVW]\d{7}[0-9A-J]` | Luhn-like mod 10; **both** the digit and its `JABCDEFGHI` letter are accepted |
| DNI/NIF (natural person) | `\d{8}[A-Z]` | number mod 23 → `TRWAGMYFPDXBNJZSQVHLCKE` |
| NIE (foreign resident) | `[XYZ]\d{7}[A-Z]` | same, with `X`/`Y`/`Z` read as a leading `0`/`1`/`2` |

Accepting all three is not thoroughness for its own sake: the wizard offers `businessType`
`company` / `freelancer`, and an autónomo has a personal DNI rather than a company CIF.
Validating only the CIF form would have refused every freelancer in the product.

The CIF algorithm is the one in Verifactu's `NIFValidator#validateCompanyNIF`, re-implemented
rather than called: Verifactu is a Spain-localization module that is not installed on every
instance, so depending on it would break the deployments that lack it. The person and NIE check
digits are not in that class at all.

### Gates and non-rules

- **Blank is accepted.** The wizard marks the field optional and `wireOrgInfo()` only persists
  a non-blank value; on the Organización window requiredness is a separate check that runs
  first, so an emptied field reports "required", not "invalid format".
- **A value made only of separators is rejected.** `normalize()` strips whitespace, `.` and
  `-`, so `"---"` would otherwise normalize to empty and be waved through — and the window's
  required check passes it too, since that one only tests for blankness.
- **Applied only for Spain.** The signup path gates on the payload's `countryCode`, the handler
  on the organization's `C_Country` (via `OrganizationCountrySupport`, shared with the
  invoice-prefix rules). The browser module does NOT gate: `OnboardingPage.jsx` hardcodes
  `countryCodes: ['ES']`, and the Organización screen has no ISO code to gate on — only a
  country label derived from the address identifier. Shipping a second country means giving
  that module the gate too.

## Which tenant an onboarding endpoint writes to

Applies to every account-authenticated onboarding endpoint — `/onboarding/first-steps`,
`/onboarding/company-data` and `/onboarding/draft`.

The onboarding endpoints authenticate an **account**, and an account can own several
environments — so the account alone does not say which tenant to write to. The token the app
sends from inside an environment is the NEO session JWT (the branch
`findActiveAccountByBearerToken` resolves through the `user` claim), and it carries the
session's own `client` and `organization` claims. Those scope the request.

`resolveTenantSession` then re-checks the claimed client against the account that owns it, so a
token can never name a client its account does not own. A pure account-session token, which has
no environment behind it, is answered `400`.
