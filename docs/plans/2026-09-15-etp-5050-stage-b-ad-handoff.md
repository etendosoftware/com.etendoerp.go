# ETP-5050 — Stage B handoff: Application Dictionary records

**Owner of this stage: Martin** (the AD records are authored by hand in the Application
Dictionary UI; the DAL entity classes are generated from them, so no Java referencing these
entities can be written before this stage completes).

Design: [`2026-09-15-etp-5050-usage-measurement-design.md`](./2026-09-15-etp-5050-usage-measurement-design.md)

## Before you start

Stage A is already done:

- `src-db/database/model/tables/ETGO_BILLING_RESOURCE.xml` and `ETGO_USAGE_DAILY.xml` exist.
- The module is already `ISINDEVELOPMENT='Y'` — no change needed.
- `update.database` has created both physical tables.

## After you finish

1. `./gradlew export.database` from the Etendo root.
2. Tell Claude the **fully-qualified generated entity class names** for both tables — the
   Java imports depend on them and they cannot be guessed. (Expect
   `com.etendoerp.go.schemaforge.data.<Classname>`, but confirm rather than assume: the
   `ETGO_DATA_FIX_HISTORY` row used a fully-qualified `CLASSNAME` and ended up in a
   double-nested package.)

## Module constants

| Key | Value |
|---|---|
| `AD_MODULE_ID` | `94E1B433CF55451EABB764750AC5902A` (Etendo Go) |
| `AD_PACKAGE_ID` | `E48DF286D9B9EAA833A51BA7689C9010` |
| DB prefix | `ETGO` |
| New IDs | `make uuid` only — never hand-typed |

Reference-type legend used below (as used by the sibling `ETGO_BILLING_EVENT`):
`10` String · `11` Integer · `13` ID · `14` Text · `16` DateTime · `17` List ·
`18` Table · `19` TableDir · `20` YesNo · `30` Search

---

## 1. Table `ETGO_BILLING_RESOURCE` — the resource catalog

### AD_TABLE

| Field | Value |
|---|---|
| Name | `ETGO_BILLING_RESOURCE` |
| DB Table Name | `etgo_billing_resource` |
| Java Class Name | `BillingResource` — **bare name, not fully qualified** |
| Data Access Level | `4` (System only) |
| Is View | `N` |
| Security Enabled | `N` |
| Deleteable | `N` |
| Changelog | `N` |
| Development Status | `RE` |
| Data Origin Type | `Table` |

### AD_COLUMN

| Column | Ref | Ref value | Len | Mand | Default |
|---|---|---|---|---|---|
| `Etgo_Billing_Resource_ID` | 13 | — | 32 | Y | |
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
| `Unit_Label` | 10 | — | 60 | Y | |
| `Counting_Mode` | **17** | **new list, see below** | 60 | Y | `D` |
| `Counted_Entity` | 10 | — | 100 | N | |
| `Date_Property` | 10 | — | 100 | N | |
| `Hql_Restriction` | **14** | — | 2000 | N | |
| `Strategy_Qualifier` | 10 | — | 100 | N | |
| `Last_Validated` | 16 | — | 19 | N | |
| `Last_Validation_Ms` | 11 | — | 12 | N | |

> **`Counted_Entity` was renamed from `Entity_Name`.** A column called `Entity_Name`
> generates `getEntityName()`, which every DAL entity already declares via
> `BaseOBObject`/`Identifiable` — the generated class ends up with the method defined twice
> and `generate.entities.quick` fails to compile. `Entity`, likewise, would collide with
> `getEntity()`. Any future column on an Etendo entity must avoid both names.

### New AD_REFERENCE (List) — `ETGO_UsageCountingMode`

Modelled on `ETGO_DataFixStatus` (`B2F9A0ED913348AA8C16728D437C353D`).

| Value | Name |
|---|---|
| `D` | Declarative HQL |
| `S` | Named strategy |

### AD_FIELD display logic

Display logic is **show-when-true**, so each mode's fields declare when they are visible.
Set on `AD_FIELD.DISPLAYLOGIC` in the Billing Resource tab:

| Field | DISPLAYLOGIC |
|---|---|
| `Strategy_Qualifier` | `@Counting_Mode@='S'` |
| `Counted_Entity` | `@Counting_Mode@='D'` |
| `Date_Property` | `@Counting_Mode@='D'` |
| `Hql_Restriction` | `@Counting_Mode@='D'` |

Syntax follows the module's own fields (`@EM_Etgo_Isperson@='Y'`) and core's list comparisons
(`@Action@='L'`): column-name token in `@ @`, list values quoted, `|` for OR, `&` for AND.

**Display logic hides, it does not validate.** A row saved as `S` can still carry a stale
`Hql_Restriction` in the database. The Stage D save-time validator is what enforces that a
`D` row has an entity and a date property and an `S` row has a qualifier — the display logic
is a UI affordance only.

### Window / tab / menu

- Window **"Billing Resource"**, `WINDOWTYPE=M`, editable (this is the catalog the system
  administrator maintains).
- One header tab over the table. **Editable** — unlike the usage window below.
- Menu entry + `AD_TREENODE` (`AD_TREE_ID=10`, `PARENT_ID=0`).
- Show at least: Value, Name, Unit Label, Counting Mode, Entity Name, Date Property,
  HQL Restriction, Strategy Qualifier, Last Validated, Last Validation Ms, Active.

---

## 2. Table `ETGO_USAGE_DAILY` — the aggregate

### AD_TABLE

Same as above except:

| Field | Value |
|---|---|
| Name | `ETGO_USAGE_DAILY` |
| DB Table Name | `etgo_usage_daily` |
| Java Class Name | `UsageDaily` |
| Data Access Level | `4` (System only) |
| Deleteable | `N` |

### AD_COLUMN

| Column | Ref | Ref value | Len | Mand | Default |
|---|---|---|---|---|---|
| `Etgo_Usage_Daily_ID` | 13 | — | 32 | Y | |
| `AD_Client_ID` | 19 | — | 32 | Y | |
| `AD_Org_ID` | 19 | — | 32 | Y | |
| `Isactive` | 20 | — | 1 | Y | `Y` |
| `Created` | 16 | — | 19 | Y | |
| `Createdby` | 30 | — | 32 | Y | |
| `Updated` | 16 | — | 19 | Y | |
| `Updatedby` | 30 | — | 32 | Y | |
| `Measured_Client_ID` | **18** | **`129`** | 32 | Y | |
| `Etgo_Billing_Resource_ID` | 19 | — | 32 | Y | |
| `Usage_Day` | **15** (Date) | — | 10 | Y | |
| `Qty` | 11 | — | 20 | Y | `0` |
| `Computed_At` | 16 | — | 19 | Y | |
| `Is_Settled` | 20 | — | 1 | Y | `N` |

**`Measured_Client_ID` is the one that needs care.** It is reference **18 (Table)** with
`AD_Reference_Value_ID = 129`, *not* TableDir — a column named `*_Client_ID` that is not
literally `AD_Client_ID` does not resolve by the TableDir naming convention. This is exactly
what `ETGO_DATA_FIX_HISTORY.Remediated_Client_ID` does, and it is the precedent being reused.

Why the tenant is a plain column rather than `AD_Client_ID`: these rows are System-owned data
*about* a tenant. `AD_Client_ID` is `'0'` so the rows are system data, and the measured tenant
lives in `Measured_Client_ID`. That is what makes cross-tenant aggregation natural instead of
fighting client filtering.

### Window / tab / menu

- Window **"Usage Daily"**, `WINDOWTYPE=M`.
- One header tab over the table, **READ-ONLY**. Read-only is set on the *tab*, not the window:
  - `ISREADONLY=Y`
  - `EM_OBUIAPP_CAN_ADD=N`
  - `EM_OBUIAPP_CAN_DELETE=N`
  - `EM_OBUIAPP_SHOW_CLONE_BUTTON=N`
- Menu entry + `AD_TREENODE`.
- Show: Measured Client, Billing Resource, Usage Day, Qty, Is Settled, Computed At.
  The settled flag must be visible — it is how an operator tells which days can still move.

---

## Checklist

The two comparable commits in this module (`36be4eb3` for `ETGO_DATA_FIX_HISTORY`,
`6ddd0da0` for `ETGO_BILLING_EVENT`) each touched the same 11 files. Expect the same set:

- [ ] `src-db/database/model/tables/<TABLE>.xml` *(already done in Stage A)*
- [ ] `AD_TABLE.xml`
- [ ] `AD_COLUMN.xml`
- [ ] `AD_ELEMENT.xml`
- [ ] `AD_REFERENCE.xml` — for `ETGO_UsageCountingMode`
- [ ] `AD_REF_LIST.xml` — for `D` / `S`
- [ ] `AD_WINDOW.xml`
- [ ] `AD_TAB.xml`
- [ ] `AD_FIELD.xml`
- [ ] `AD_MENU.xml`
- [ ] `AD_TREENODE.xml` — **easy to forget; this is what actually places the menu entry**

Then: `./gradlew export.database`, and run `./check-etgo-xml.sh` — sourcedata records must stay
sorted by ascending UUID or `.github/workflows/xml-order-check.yml` fails the PR.
