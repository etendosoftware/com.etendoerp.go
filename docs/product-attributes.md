# Configurable product attributes (ETP-5977 POC)

Each client defines extra attributes for Product, scoped by product category, and reads and writes them through NEO.
Evidence, measurements and the decisions still open live in `schema_forge/docs/plans/etp-5977-configurable-attributes-poc-evidence.md`.

## Objects

| Object | Role |
|---|---|
| `ETGO_ATTR_DEF` | One attribute definition per client and target entity (`AD_TABLE_ID`, code, label, type `T`/`N`, mandatory flag, order). |
| `ETGO_ATTR_DEF_CATEGORY` | The product categories a definition applies to. |
| `ETGO_PRODUCT_ATTR_VAL` | One typed value per (product, definition): `VALUE_TEXT`, `VALUE_NUMBER`, `VALUE_BPARTNER_ID` (at most one set). Real FK to `M_PRODUCT` with `ON DELETE CASCADE`. |
| NEO | `product/productAttributeValue` (child of Product) and the `attribute-definition` spec (`attributeDefinition`, `categoryScope`). |

## Code map

| Class | Responsibility |
|---|---|
| `com.etendoerp.go.attributes.ProductAttributeRules` | Pure value and definition rules. |
| `ProductAttributeValueEventHandler`, `AttributeDefinitionEventHandler` | Persistence rules (type, category scope, client, lifecycle). |
| `AttributeTenantWriteGuard` | Refuses an update or delete of a record outside the session's readable clients. |
| `ProductAttributeListRequest` / `ProductAttributeListHql` / `ProductAttributeListQuery` | Filter and sort of the Product list by an attribute: parse and validate (pure), build the HQL (pure), run it (DAL). |
| `schemaforge.handlers.AttributeDefinitionReadHandler` | `attrCategory=<id>` on the definitions list: the active definitions of one category. |
| `schemaforge.NeoHandler#listPage`, `NeoListPage` | Generic extension point (no entity named): a customization takes over ordering and paging of a REST list that core cannot express. Product implements it in `ProductDefaultsHandler`. |

Request parameters for the Product list (none is HQL, every value is validated and bound): `attrFilterDef` with
`attrFilterMin`/`attrFilterMax`, `attrFilterPrefix` or `attrFilterEq`; `attrSortDef` with `attrSortDir`. Exact totals need
`_noCount=false`; otherwise the total is inferred like core does.

## Known coupling and limits (accepted for the POC)

1. **The tenant guard lists its entities.** `AttributeTenantWriteGuard` observes the three attribute entities by name. A new
   attribute entity must be added to it. The underlying gap is generic, not specific to attributes: NEO's default
   `DELETE` by id, and a `PUT` that carries the record's `updated` token, do not check the record's client (core's
   `DefaultJsonDataService` loads by id without a readable-client filter). The guard is a stopgap per entity until NEO is fixed.
2. **The value side is written for Product.** `ProductAttributeValueEventHandler` and `ProductAttributeListQuery` assume Product
   and the generated collection `eTGOProductAttrValList`. A second entity needs its own value table, handler and list query, or
   these have to be parameterised by entity. `ETGO_ATTR_DEF` is already shared (`AD_TABLE_ID`).
3. **The module edits Product's NEO configuration.** The "Attribute Values" tab is added from this module to the core Product
   window (140) and `productAttributeValue` is added to the existing `product` spec, the same pattern as
   `productCharacteristic`. This module therefore depends on that window and spec, and `update.database` here changes what
   they expose.

## Traps found while building it

- A DAL parent exposes its child collection only if the child column has `IS_PARENT=Y` **and** `IS_CHILD_PROPERTY_IN_PARENT=Y`.
- A reference column named other than `<Table>_ID` cannot be table-direct (reference 19); use Search (30) with the reference
  value (e.g. `VALUE_BPARTNER_ID`: 30 + 800057).
- `@NeoExtension` handlers are not run for a REST CRUD request when the Spec entity has no `Java_Qualifier`
  (`NeoCrudHandler.dispatchCrudRequestInternal`); `readPredicates` and `listPage` are resolved through the annotation without one.
- Do not leave a `jsonb` column in the schema: `export.database` fails on it.
- A tab with no fields in grid view fails `export.database` validation.
- Renaming or deleting a class leaves its `.class` in `build/classes` and the deployed webapp; a stale `@NeoExtension` class can
  win the `(spec, entity)` claim. Delete it from both when working locally.
