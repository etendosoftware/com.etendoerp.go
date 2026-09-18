# ETP-5046 — Stage B handoff: Application Dictionary records

**Owner of this stage: Martin** (the AD records are authored by hand in the Application
Dictionary UI; the DAL entity classes are generated from them, so **no Java referencing these
entities can be written before `export.database` has run** and the generated classes exist).

Stage A (this ticket's other half) is already done — the physical DDL is committed:

- `src-db/database/model/tables/ETGO_PLAN.xml` *(new)*
- `src-db/database/model/tables/ETGO_PLAN_QUOTA.xml` *(new)*
- `src-db/database/model/tables/ETGO_SUBSCRIPTION.xml` *(new)*
- `src-db/database/model/tables/ETGO_CHECKOUT_REQUEST.xml` *(edited — new `ETGO_PLAN_ID` column + FK)*

`update.database` has **not** been run yet — Tomcat must be stopped first, and that is the
human's call.

## Module constants

| Key | Value |
|---|---|
| `AD_MODULE_ID` | `94E1B433CF55451EABB764750AC5902A` (Etendo Go) |
| `AD_PACKAGE_ID` | `E48DF286D9B9EAA833A51BA7689C9010` |
| Java package | `com.etendoerp.go.schemaforge.data` |
| DB prefix | `ETGO` |
| `AD_CLIENT_ID` / `AD_ORG_ID` on every new AD record | `0` |
| `DEVELOPMENTSTATUS` | `RE` |

### Generating new IDs

**Never invent, hand-type, or copy-paste a UUID.** Etendo IDs are 32 uppercase hex characters
without hyphens. The ETP-5050 handoff doc says `make uuid`, but **that target does not exist in
this module's Makefile** (there is no Makefile here at all — `make uuid` is a
`schema_forge`-repo target). The working fallback from this module's root is:

```bash
uuidgen | tr -d - | tr a-z A-Z
```

Existing IDs (references, tables, elements) must always be looked up, never guessed.

### Reference-type legend

`10` String · `11` Integer · `13` ID · `14` Text · `15` Date · `16` DateTime · `17` List ·
`18` Table · `19` TableDir · `20` YesNo · `22` Number · `30` Search

---

## 1. Table `ETGO_PLAN` — the plan catalog

### AD_TABLE

| Field | Value |
|---|---|
| Name | `ETGO_PLAN` |
| DB Table Name | `etgo_plan` |
| Java Class Name | `Plan` — **bare name, not fully qualified** |
| Data Access Level | `4` (System only) |
| Is View | `N` |
| Security Enabled | `N` |
| Deleteable | `N` |
| Changelog | `N` |
| Development Status | `RE` |
| Data Origin Type | `Table` |

> **`CLASSNAME` must be a bare class name.** The `ETGO_DATA_FIX_HISTORY` row was authored with a
> fully-qualified `CLASSNAME` and the generator produced a double-nested package
> (`com.etendoerp.go.schemaforge.data.com.etendoerp.go.schemaforge.data.…`). The same trap caught
> `ETGOSurveyType`. Write `Plan`, `PlanQuota`, `Subscription` — the package comes from
> `AD_PACKAGE_ID`, never from `CLASSNAME`.

> **`ISDELETEABLE='N'`** here: a plan is referenced by subscriptions and by checkout requests;
> deactivate (`ISACTIVE='N'`) instead of deleting.

### AD_COLUMN

| Column | Ref | Ref value | Len | Mand | Default |
|---|---|---|---|---|---|
| `Etgo_Plan_ID` | 13 | — | 32 | Y | |
| `AD_Client_ID` | 19 | — | 32 | Y | |
| `AD_Org_ID` | 19 | — | 32 | Y | |
| `Isactive` | 20 | — | 1 | Y | `Y` |
| `Created` | 16 | — | 19 | Y | |
| `Createdby` | 30 | — | 32 | Y | |
| `Updated` | 16 | — | 19 | Y | |
| `Updatedby` | 30 | — | 32 | Y | |
| `Value` | 10 | — | 60 | Y | |
| `Name` | 10 | — | 120 | Y | |
| `Description` | 10 | — | 255 | N | |
| `Provider_Price_ID` | 10 | — | 255 | **N** | |
| `Billing_Interval` | **17** | **`ETGO_BillingInterval`** | 60 | N | |
| `Display_Price` | **22** | — | 20 | N | |
| `Currency_Code` | 10 | — | 3 | N | |
| `Price_Synced_At` | 16 | — | 19 | N | |

> **`Provider_Price_ID` is nullable on purpose.** The grandfathered legacy plan has no provider
> price. The DDL enforces the pairing instead: `ETGO_PLAN_PRICED_CHK` allows either
> *(price id, display price, currency all NULL)* or *(price id present)* — so a half-filled
> price row cannot exist, while the legacy row stays valid.

> **`ETGO_PLAN_PRICE_UQ` is a PARTIAL unique index** (`WHERE PROVIDER_PRICE_ID IS NOT NULL`), so
> many plans may have a NULL price id while a non-NULL one stays unique.

### Window / tab / menu

- Window **"Plans"**, `WINDOWTYPE=M`, **editable** — this is the catalog the system administrator
  maintains.
- Header tab **"Plan"** over `ETGO_PLAN`: `TABLEVEL=0`, `SEQNO=10`.
- Child tab **"Quotas"** over `ETGO_PLAN_QUOTA`: `TABLEVEL=1`, `SEQNO=20` (see §2).
- Menu entry + `AD_TREENODE`.
- Show at least: Value, Name, Description, Billing Interval, Display Price, Currency Code,
  Provider Price ID, Price Synced At, Active.

---

## 2. Table `ETGO_PLAN_QUOTA` — per-plan resource allowances

### AD_TABLE

Same as above except:

| Field | Value |
|---|---|
| Name | `ETGO_PLAN_QUOTA` |
| DB Table Name | `etgo_plan_quota` |
| Java Class Name | `PlanQuota` |
| Data Access Level | `4` (System only) |
| Deleteable | **`Y`** |

> **`ISDELETEABLE='Y'` — and this one is deliberate, not an oversight.** "No quota row" means
> *unlimited*. An operator who adds a quota by mistake, or who wants to lift a cap back to
> unlimited, must be able to **delete** the row; setting `ISACTIVE='N'` is not the same thing and
> an evaluator that only filters on active rows would behave differently from one that does not.
> Deleting is the documented way to restore the unlimited state.

### AD_COLUMN

| Column | Ref | Ref value | Len | Mand | Default |
|---|---|---|---|---|---|
| `Etgo_Plan_Quota_ID` | 13 | — | 32 | Y | |
| `AD_Client_ID` | 19 | — | 32 | Y | |
| `AD_Org_ID` | 19 | — | 32 | Y | |
| `Isactive` | 20 | — | 1 | Y | `Y` |
| `Created` | 16 | — | 19 | Y | |
| `Createdby` | 30 | — | 32 | Y | |
| `Updated` | 16 | — | 19 | Y | |
| `Updatedby` | 30 | — | 32 | Y | |
| `Etgo_Plan_ID` | **19** | — | 32 | Y | **`ISPARENT='Y'`** |
| `Etgo_Billing_Resource_ID` | 19 | — | 32 | Y | |
| `Included_Qty` | 11 | — | 20 | Y | **(none — see below)** |
| `Enforcement_Mode` | **17** | **`ETGO_QuotaEnforcementMode`** | 60 | Y | **(none — see below)** |
| `Warning_Threshold` | 11 | — | 10 | N | |
| `Consumption_Source` | 10 | — | 60 | N | |

> ### `INCLUDED_QTY` and `ENFORCEMENT_MODE` must have **NO `DEFAULTVALUE`** in `AD_COLUMN`
>
> This is the single most important line in this document. `INCLUDED_QTY` has no DB default in
> the DDL, and it must have no AD default either. A default of `0` would silently cap **every
> resource on every plan at zero** the moment the ETP-5051 quota evaluator goes live — every
> tenant blocked, with no error anywhere to explain it. The column is mandatory precisely so the
> author is forced to state the number.
>
> `ENFORCEMENT_MODE` likewise: defaulting it to `warn` or `block` picks a policy on the
> operator's behalf. Mandatory, no default.

> **`Etgo_Plan_ID` is reference `19` (TableDir) with `ISPARENT='Y'`.** That flag is what makes
> the Quotas tab a *child* tab of the Plan tab rather than a standalone grid.

### Window / tab / menu

The Quotas tab lives inside the **"Plans"** window from §1 — there is no separate window.

**Tab hierarchy is IMPLICIT in Etendo.** There is no parent-tab foreign key on `AD_TAB`: a
`TABLEVEL=1` tab attaches to the **nearest preceding `TABLEVEL=0` tab in `SEQNO` order**. Get the
`SEQNO` values wrong and the child silently hangs off the wrong parent — or off nothing.
Precedent: ETP-4352, commit `797a855e` ("Feature ETP-4352: Fix survey config row creation,
ordering and Javadoc"), which was a 2-line `AD_TAB.xml` fix to get exactly this ordering right.

So: Plan tab `SEQNO=10` / `TABLEVEL=0`, Quotas tab `SEQNO=20` / `TABLEVEL=1`, in that order.

Show in the Quotas tab: Billing Resource, Included Qty, Enforcement Mode, Warning Threshold,
Consumption Source, Active.

---

## 3. Table `ETGO_SUBSCRIPTION` — the tenant's subscription history

### AD_TABLE

Same as §1 except:

| Field | Value |
|---|---|
| Name | `ETGO_SUBSCRIPTION` |
| DB Table Name | `etgo_subscription` |
| Java Class Name | `Subscription` |
| Data Access Level | `4` (System only) |
| Deleteable | `N` |

### AD_COLUMN

| Column | Ref | Ref value | Len | Mand | Default |
|---|---|---|---|---|---|
| `Etgo_Subscription_ID` | 13 | — | 32 | Y | |
| `AD_Client_ID` | 19 | — | 32 | Y | |
| `AD_Org_ID` | 19 | — | 32 | Y | |
| `Isactive` | 20 | — | 1 | Y | `Y` |
| `Created` | 16 | — | 19 | Y | |
| `Createdby` | 30 | — | 32 | Y | |
| `Updated` | 16 | — | 19 | Y | |
| `Updatedby` | 30 | — | 32 | Y | |
| `Environment_Client_ID` | **18** | **`129`** | 32 | Y | |
| `Etgo_Plan_ID` | 19 | — | 32 | Y | |
| `Status` | **17** | **`ETGO_SubscriptionStatus`** | 60 | Y | `active` |
| `Start_Date` | 16 | — | 19 | Y | |
| `End_Date` | 16 | — | 19 | N | |
| `Current_Period_Start` | 16 | — | 19 | N | |
| `Current_Period_End` | 16 | — | 19 | N | |
| `Stripe_Customer_ID` | 10 | — | 255 | N | |
| `Stripe_Subscription_ID` | 10 | — | 255 | N | |
| `Etgo_Account_ID` | 19 | — | 32 | N | |
| `Provider_Price_ID` | 10 | — | 255 | N | |
| `Snapshot_Amount` | 22 | — | 20 | N | |
| `Snapshot_Currency` | 10 | — | 3 | N | |
| `Pending_Plan_ID` | **18** | **`ETGO_PLAN` table reference** | 32 | N | |
| `Pending_Effective_Date` | 16 | — | 19 | N | |

> ### `ENVIRONMENT_CLIENT_ID` uses reference **18 (Table)** with `AD_Reference_Value_ID = 129` — **NOT TableDir**
>
> A column named `*_Client_ID` that is not **literally** `AD_Client_ID` does not resolve by the
> TableDir naming convention: TableDir strips the `_ID` suffix and looks for a table of that
> name, and `Environment_Client` is not a table. Use reference `18` with
> `AD_Reference_Value_ID='129'` (the `AD_Client` table reference).
>
> Precedents in this very module: `ETGO_USAGE_DAILY.Measured_Client_ID` and
> `ETGO_DATA_FIX_HISTORY.Remediated_Client_ID` — both do exactly this.
>
> And note **`ENVIRONMENT_CLIENT_ID` is the tenant.** `AD_CLIENT_ID` on this table is the *audit*
> column and is `'0'` (System) — these are system-owned rows *about* a tenant, which is what
> makes cross-tenant queries natural instead of fighting client filtering.

> **`Pending_Plan_ID` also cannot be TableDir** — the column is not named `Etgo_Plan_ID`, so use
> reference `18` (Table) pointing at the `ETGO_PLAN` table reference. (Look the reference id up
> after `export.database` creates it; do not guess it.)

> **`ETGO_SUB_STRIPESUB_IX` is deliberately NON-unique.** An ETP-5053 plan change closes the
> current local row and opens a second one while Stripe keeps the **same** subscription id, so
> two local rows legitimately share `STRIPE_SUBSCRIPTION_ID`. A unique index here would block
> every plan change.

> **The load-bearing constraint is `ETGO_SUB_OPEN_ENVCLIENT_UQ`** — a partial unique index on
> `ENVIRONMENT_CLIENT_ID WHERE ISACTIVE='Y' AND END_DATE IS NULL`. At most one OPEN subscription
> per tenant, enforced by the database, not by application code.

### AD_FIELD — hidden fields

`Pending_Plan_ID` and `Pending_Effective_Date` **must get `AD_FIELD` records with
`ISDISPLAYED='N'`.** They still need the field records (so the columns are part of the tab and
available to the datasource), but they are internal scheduling state for the ETP-5053 deferred
plan change and must not be shown or edited by an operator.

### Window / tab / menu

- Window **"Subscriptions"**, `WINDOWTYPE=M`, **read-mostly**. Read-only is set on the *tab*,
  not the window:
  - `ISREADONLY=Y`
  - `EM_OBUIAPP_CAN_ADD=N`
  - `EM_OBUIAPP_CAN_DELETE=N`
  - `EM_OBUIAPP_SHOW_CLONE_BUTTON=N`
- One header tab over `ETGO_SUBSCRIPTION`, `TABLEVEL=0`, `SEQNO=10`.
- Menu entry + `AD_TREENODE`.
- Show: Environment Client, Plan, Status, Start Date, End Date, Current Period Start,
  Current Period End, Snapshot Amount, Snapshot Currency, Stripe Customer ID,
  Stripe Subscription ID, Active.

---

## 4. `ETGO_CHECKOUT_REQUEST` — new column

`ETGO_PLAN_ID`, **optional**: rows created before this ticket have no plan, so the column must
be nullable and the AD column mandatory flag must be `N`.

| Column | Ref | Ref value | Len | Mand | Default |
|---|---|---|---|---|---|
| `Etgo_Plan_ID` | 19 | — | 32 | **N** | |

Add an `AD_FIELD` in the existing Checkout Request tab (displayed, read-only is fine).

---

## 5. New AD_REFERENCE (List) definitions

Model each on `ETGO_DataFixStatus` (`B2F9A0ED913348AA8C16728D437C353D`):
`AD_REFERENCE` with `VALIDATIONTYPE='L'`, plus one `AD_REF_LIST` row per value.

### `ETGO_BillingInterval`

| Value | Name |
|---|---|
| `month` | Monthly |
| `year` | Yearly |

### `ETGO_QuotaEnforcementMode`

| Value | Name |
|---|---|
| `warn` | Warn only |
| `block` | Block |

### `ETGO_SubscriptionStatus`

| Value | Name |
|---|---|
| `active` | Active |
| `past_due` | Past due |
| `canceled` | Canceled |

The stored values are lowercase on purpose — they mirror the provider's own vocabulary and the
DDL check constraints (`ETGO_PLAN_INTERVAL_CHK`, `ETGO_PLNQTA_MODE_CHK`, `ETGO_SUB_STATUS_CHK`)
match them exactly. Changing the case in AD would make every insert fail the check constraint.

---

## 6. AD_MESSAGE values

All with `MSGTYPE='E'` (Error), `AD_MODULE_ID='94E1B433CF55451EABB764750AC5902A'`.

| `VALUE` | Suggested English `MSGTEXT` |
|---|---|
| `ETGO_PlanPriceNotFound` | The price @price@ does not exist in the payment provider. |
| `ETGO_PlanPriceUnauthorized` | The payment provider rejected the credentials used to read price @price@. Check the API key configuration. |
| `ETGO_PlanPriceRejected` | The payment provider rejected the request for price @price@. |
| `ETGO_PlanPriceUnreachable` | The payment provider could not be reached while validating price @price@. Try again later. |
| `ETGO_PlanPriceNotRecurring` | The price @price@ is a one-off price. A plan requires a recurring price. |
| `ETGO_PlanPriceArchived` | The price @price@ is archived in the payment provider and cannot be used for a new plan. |
| `ETGO_PlanIntervalCountUnsupported` | The price @price@ bills every @count@ @interval@. Only a billing interval count of 1 is supported. |
| `ETGO_PlanIntervalMismatch` | The price @price@ bills @providerInterval@ but the plan declares @planInterval@. |

Each message needs a matching `AD_MESSAGE` record plus its `AD_MESSAGE_TRL` where translations
are required.

---

## 7. Sourcedata — the grandfathered plan

One `AD_DATASET_TABLE` row so the legacy plan ships as module sourcedata:

| Field | Value |
|---|---|
| `AD_DATASET_ID` | `'0'` |
| `AD_TABLE_ID` | the `ETGO_PLAN` table id |
| `WHERECLAUSE` | `value = 'legacy-productive'` |
| `ISEXCLUDEAUDIT` | `Y` |
| `AD_MODULE_ID` | `94E1B433CF55451EABB764750AC5902A` |

The single row it exports:

| Column | Value |
|---|---|
| `AD_CLIENT_ID` | `0` |
| `AD_ORG_ID` | `0` |
| `ISACTIVE` | `Y` |
| `VALUE` | `legacy-productive` |
| `NAME` | `Legacy Productive (grandfathered)` |
| `DESCRIPTION` | *(optional)* |
| `PROVIDER_PRICE_ID` | **NULL** |
| `BILLING_INTERVAL` | **NULL** |
| `DISPLAY_PRICE` | **NULL** |
| `CURRENCY_CODE` | **NULL** |
| `PRICE_SYNCED_AT` | **NULL** |

**Zero quota children.** No `ETGO_PLAN_QUOTA` rows for this plan — no quota row means unlimited,
which is exactly the grandfathered contract. Do not add a dataset table entry for
`ETGO_PLAN_QUOTA`.

All four price columns being NULL is what `ETGO_PLAN_PRICED_CHK` permits; this row is the reason
the check is an either/or rather than a plain NOT NULL.

---

## Checklist

The comparable commits in this module (`36be4eb3` for `ETGO_DATA_FIX_HISTORY`, `6ddd0da0` for
`ETGO_BILLING_EVENT`) each touched the same 11 files. Expect the same set, plus
`AD_DATASET_TABLE.xml` and `AD_MESSAGE.xml` for this ticket:

- [ ] `src-db/database/model/tables/<TABLE>.xml` *(already done in Stage A)*
- [ ] `AD_TABLE.xml` — 3 new rows
- [ ] `AD_COLUMN.xml` — all columns of the 3 new tables + `Etgo_Plan_ID` on `ETGO_CHECKOUT_REQUEST`
- [ ] `AD_ELEMENT.xml`
- [ ] `AD_REFERENCE.xml` — `ETGO_BillingInterval`, `ETGO_QuotaEnforcementMode`, `ETGO_SubscriptionStatus`
- [ ] `AD_REF_LIST.xml` — 2 + 2 + 3 values
- [ ] `AD_MESSAGE.xml` — the 8 `ETGO_PlanPrice*` / `ETGO_PlanInterval*` messages
- [ ] `AD_WINDOW.xml` — "Plans", "Subscriptions"
- [ ] `AD_TAB.xml` — Plan (`TABLEVEL=0`, `SEQNO=10`), Quotas (`TABLEVEL=1`, `SEQNO=20`), Subscription (`TABLEVEL=0`, `SEQNO=10`)
- [ ] `AD_FIELD.xml` — including `ISDISPLAYED='N'` for `Pending_Plan_ID` and `Pending_Effective_Date`
- [ ] `AD_DATASET_TABLE.xml` — the `legacy-productive` export
- [ ] `AD_MENU.xml`
- [ ] `AD_TREENODE.xml` — **easy to forget; this is what actually places the menu entry**
      (`AD_TREE_ID=10`, `PARENT_ID=0`, `SEQNO=999`)

---

## Closing steps

1. `./gradlew export.database` from the **Etendo root** (not from the module directory).
2. `./fix-etgo-xml-order.sh` — sorts the sourcedata records by ascending UUID.
3. `./check-etgo-xml.sh` — verifies order, unique constraints and referential integrity.

Sourcedata records **must** stay sorted by ascending UUID or `.github/workflows/xml-order-check.yml`
fails the PR. Run both scripts before committing, not after CI complains.

---

## Report back — what the developer needs before any Java can be written

Once `export.database` has run, tell Claude:

1. **The three generated entity class names**, fully qualified as the generator actually emitted
   them — expect `com.etendoerp.go.schemaforge.data.Plan`,
   `com.etendoerp.go.schemaforge.data.PlanQuota`,
   `com.etendoerp.go.schemaforge.data.Subscription`, but **confirm rather than assume**: the
   `ETGO_DATA_FIX_HISTORY` row used a fully-qualified `CLASSNAME` and ended up double-nested.
2. **The property name generated for `ETGO_PLAN_ID` on `Subscription`** — i.e. whether the
   getter is `getPlan()` / `PROPERTY_PLAN` or something else. The DAL property name comes from
   the AD element, not from the column name, and it cannot be guessed.
3. **Confirmation that `VALUE` maps to the `searchKey` property** (`getSearchKey()` /
   `PROPERTY_SEARCHKEY`) on `Plan` — that is the conventional mapping for a `Value` column, and
   the ETP-5051 lookup code depends on it.

Until all three answers are in, Stage C (Java) stays blocked.
