# Neo Headless - Guia Completa de Desarrollo

## Indice

1. [Que es Neo Headless](#1-que-es-neo-headless)
2. [Arquitectura General](#2-arquitectura-general)
3. [Pipeline de Request](#3-pipeline-de-request)
4. [Modelo de Datos (Schema Forge)](#4-modelo-de-datos-schema-forge)
5. [Setup Inicial - Crear tu Primer Spec](#5-setup-inicial---crear-tu-primer-spec)
6. [Autenticacion](#6-autenticacion)
7. [Referencia de Endpoints](#7-referencia-de-endpoints)
8. [CRUD Operations](#8-crud-operations)
9. [Pipeline de Selectors (FK Dropdowns)](#9-pipeline-de-selectors-fk-dropdowns)
10. [Pipeline de Callouts](#10-pipeline-de-callouts)
11. [Pipeline de Defaults](#11-pipeline-de-defaults)
12. [Pipeline de Procesos](#12-pipeline-de-procesos)
13. [Pipeline de Reportes](#13-pipeline-de-reportes)
14. [Field Filtering (Control de Campos)](#14-field-filtering-control-de-campos)
15. [Parent-Child Tab Filtering](#15-parent-child-tab-filtering)
16. [Customizacion con NeoHandler (Hooks CDI)](#16-customizacion-con-neohandler-hooks-cdi)
17. [OpenAPI Auto-Generado](#17-openapi-auto-generado)
18. [Seguridad](#18-seguridad)
19. [Testing](#19-testing)
20. [Troubleshooting](#20-troubleshooting)

---

## 1. Que es Neo Headless

Neo Headless es una capa REST API metadata-driven para Etendo ERP. Expone ventanas, procesos y reportes de Etendo como endpoints JSON sin necesidad de escribir codigo de endpoint manualmente.

Un administrador define un **spec** (respaldado por un `AD_Window`, `AD_Process` o reporte), selecciona que tabs, columnas y metodos HTTP exponer, y Neo Headless genera los endpoints CRUD completos y de ejecucion de procesos en runtime.

**Ubicacion en el codigo:**
- Modulo: `com.etendoerp.go`
- Package Java: `com.etendoerp.go.schemaforge`
- Servlet: registrado en `/sws/neo/*`
- Autenticacion: JWT bearer tokens via SecureWebServices

---

## 2. Arquitectura General

```
Cliente (Bearer JWT)
    |
    v
NeoServlet (/sws/neo/*)
    |
    +-- authenticateJwt() --> SecureWebServicesUtils (decode + OBContext)
    |
    +-- parsePath() --> NeoPathInfo (specName, entityName, recordId, subpath)
    |
    +-- findSpec(specName) --> ETGO_SF_Spec (active, by name)
    |
    +-- Route por tipo de spec:
    |     |
    |     +-- Report spec (type R) --> NeoReportService
    |     |
    |     +-- Process spec (type P) --> NeoProcessService.executeProcess()
    |     |
    |     +-- Window spec (type W):
    |           |
    |           +-- /selectors?    --> NeoSelectorService
    |           +-- /callout?      --> NeoCalloutService
    |           +-- /defaults?     --> NeoDefaultsService
    |           +-- /action?       --> NeoProcessService (button processes)
    |           +-- CRUD path?     --> findEntity() --> check method flags
    |                 |
    |                 +-- javaQualifier set? --> CDI lookup NeoHandler
    |                 |     |
    |                 |     +-- returns NeoResponse? --> write response
    |                 |     +-- returns null?        --> fall through a default
    |                 |
    |                 +-- default: DataSourceServlet (Etendo RX internal)
    |
    +-- NeoFieldFilter --> filtra campos en request/response
    |
    v
  Response (JSON)
```

### Componentes Clave

| Clase | Responsabilidad |
|-------|----------------|
| `NeoServlet` | Entry point principal. Auth JWT, parsing de path, routing, filtro parent-child. |
| `NeoHandler` | Interface CDI para hooks custom. Retorna `NeoResponse` o `null` para fall-through. |
| `NeoContext` | Objeto contexto inmutable (builder pattern). Transporta spec, entity, method, body, tab, OBContext. |
| `NeoResponse` | Wrapper de response con builders estaticos: `ok()`, `created()`, `noContent()`, `error()`. |
| `NeoSelectorService` | Resolucion de FK dropdowns (TableDir, Table, Search, OBUISEL). Soporta HQL custom. |
| `NeoCalloutService` | Ejecucion de AD_Callouts via REST. Construye request sintetico. |
| `NeoDefaultsService` | Resolucion de valores por defecto (literals, context vars, SQL, sequences). |
| `NeoProcessService` | Ejecucion de procesos (OBUIAPP, Classic). Validacion de parametros. |
| `NeoReportService` | Generacion de reportes Jasper (PDF, XLS, XLSX, HTML, CSV). |
| `NeoFieldFilter` | Filtra JSON basado en config ETGO_SF_FIELD (IsIncluded, IsReadOnly). |
| `NeoOpenAPIEndpoint` | Generacion automatica de documentacion OpenAPI 3.0. |
| `PopulateSpecHelper` | Auto-popula entities y fields desde metadata AD. |

---

## 3. Pipeline de Request

Cada request a `/sws/neo/*` atraviesa las siguientes etapas en orden:

### Etapa 1: Autenticacion JWT
```
Authorization: Bearer <jwt-token>
```
Se decodifica via `SecureWebServicesUtils.decodeToken()`. Se construye un `OBContext` completo con los claims del token. Si falla: `401 Unauthorized`.

### Etapa 2: Parsing del Path
El metodo `parsePath()` descompone la URL en:
- `specName` - primer segmento (ej: `sales-order`)
- `entityName` - segundo segmento (ej: `Order`)
- `recordId` - tercer segmento opcional (UUID)
- `subpath` - segmentos especiales (`selectors`, `callout`, `defaults`, `action`, `evaluate-display`)

### Etapa 3: Lookup del Spec
Busca `ETGO_SF_Spec` activo por nombre. Si no existe: `404 Not Found`.

### Etapa 4: Routing por Tipo de Spec
- **W (Window)**: routing CRUD + subpaths especiales
- **P (Process)**: GET describe, POST ejecuta
- **R (Report)**: GET describe, POST genera

### Etapa 5: Lookup de Entity (solo Window specs)
Busca `ETGO_SF_Entity` activa dentro del spec. Verifica que el metodo HTTP este habilitado (ISGET, ISPOST, etc). Si no: `405 Method Not Allowed`.

### Etapa 6: Control de Acceso
- Window specs: verifica `ADWindowAccess` para el role actual
- Process specs: verifica `ADProcessAccess` para el role actual
- Si denegado: `403 Forbidden`

### Etapa 7: Hook Discovery (opcional)
Si la entity tiene `JAVA_QUALIFIER` configurado, busca un bean CDI `@Named("qualifier")` que implemente `NeoHandler`. Si retorna `NeoResponse`, se usa como respuesta. Si retorna `null`, continua al handler default.

### Etapa 8: Ejecucion
Delega al servicio correspondiente (DataSourceServlet, NeoSelectorService, etc).

### Etapa 9: Field Filtering
`NeoFieldFilter` filtra el response JSON removiendo campos no incluidos y en write requests, campos read-only.

---

## 4. Modelo de Datos (Schema Forge)

Tres tablas custom almacenan la especificacion del API. Todas con prefijo `ETGO`.

### Jerarquia

```
ETGO_SF_Spec (Especificacion)
  |
  +-- 1..* ETGO_SF_Entity (Entity/Tab)
        |
        +-- 1..* ETGO_SF_Field (Field/Column)
```

### ETGO_SF_SPEC

Top-level. Cada spec mapea a un `AD_Window`, `AD_Process` o Report.

| Columna | Tipo | Notas |
|---------|------|-------|
| `ETGO_SF_SPEC_ID` | VARCHAR (PK) | UUID |
| `NAME` | VARCHAR | Unico. Se usa como primer segmento de la URL. |
| `DESCRIPTION` | VARCHAR | Descripcion opcional. |
| `SPEC_TYPE` | CHAR(1) | `'W'` = Window/CRUD, `'P'` = Process, `'R'` = Report. |
| `AD_WINDOW_ID` | VARCHAR (FK) | Requerido cuando `SPEC_TYPE = 'W'`. |
| `AD_PROCESS_ID` | VARCHAR (FK) | Requerido cuando `SPEC_TYPE = 'P'` o `'R'`. |
| `AD_MODULE_ID` | VARCHAR (FK) | Modulo que posee este spec. |
| `ISACTIVE` | CHAR(1) | Solo specs activos son servidos. |
| `POPULATE` | CHAR(1) | Flag para boton de poblar desde UI. |

### ETGO_SF_ENTITY

Representa un tab (window specs) o el proceso mismo (process specs).

| Columna | Tipo | Notas |
|---------|------|-------|
| `ETGO_SF_ENTITY_ID` | VARCHAR (PK) | UUID |
| `ETGO_SF_SPEC_ID` | VARCHAR (FK) | Spec padre. |
| `NAME` | VARCHAR | Segundo segmento de la URL (window specs). |
| `AD_TAB_ID` | VARCHAR (FK) | Link al AD_Tab. Null para process specs. |
| `ISINCLUDED` | CHAR(1) | Solo entities incluidas son servidas. |
| `ISGET` | CHAR(1) | Habilitar GET lista. |
| `ISGETBYID` | CHAR(1) | Habilitar GET por ID. |
| `ISPOST` | CHAR(1) | Habilitar POST crear. |
| `ISPUT` | CHAR(1) | Habilitar PUT update completo. |
| `ISPATCH` | CHAR(1) | Habilitar PATCH update parcial. |
| `ISDELETE` | CHAR(1) | Habilitar DELETE. |
| `JAVA_QUALIFIER` | VARCHAR | CDI `@Named` qualifier para un `NeoHandler` custom. |
| `SEQNO` | NUMERIC | Orden de procesamiento/display. |

**Constraint unico:** `(ETGO_SF_SPEC_ID, NAME)`

### ETGO_SF_FIELD

Representa una columna (window specs) o parametro de proceso (process specs).

| Columna | Tipo | Notas |
|---------|------|-------|
| `ETGO_SF_FIELD_ID` | VARCHAR (PK) | UUID |
| `ETGO_SF_ENTITY_ID` | VARCHAR (FK) | Entity padre. |
| `AD_COLUMN_ID` | VARCHAR (FK) | Link al AD_Column. Null para process specs. |
| `ISINCLUDED` | CHAR(1) | Solo campos incluidos participan en selectors y actions. |
| `ISREADONLY` | CHAR(1) | Campo de solo lectura (se excluye de write requests). |
| `DEFAULTVALUE` | VARCHAR | Override de valor por defecto. |
| `JAVA_QUALIFIER` | VARCHAR | Para process specs: DB column name del parametro. |
| `SEQNO` | NUMERIC | Orden de procesamiento/display. |

---

## 5. Setup Inicial - Crear tu Primer Spec

### Opcion A: Via Webhooks (recomendado para automatizacion)

**Paso 1: Crear el Spec**

```bash
# Crear un spec de tipo Window para Sales Order (AD_Window_ID=143)
curl -X POST https://tu-etendo/webhooks/SFUpsertSpec \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "sales-order",
    "ModuleID": "<tu-module-id>",
    "SpecType": "W",
    "WindowID": "143",
    "Description": "Sales Order API"
  }'
# Response: { "SpecID": "ABC123...", "SpecType": "W" }
```

**Paso 2: Auto-poblar Entities y Fields desde metadata AD**

```bash
curl -X POST https://tu-etendo/webhooks/SFPopulateSpec \
  -H "Content-Type: application/json" \
  -d '{
    "SpecID": "ABC123...",
    "IncludeAllMethods": "Y",
    "ExcludeSystemColumns": "Y"
  }'
# Response: { "EntitiesCreated": 5, "FieldsCreated": 120 }
```

Esto lee la metadata de `AD_Window` -> `AD_Tab` -> `AD_Column` y crea automaticamente:
- Una `ETGO_SF_Entity` por cada tab activo
- Una `ETGO_SF_Field` por cada columna activa (excluyendo columnas de sistema si asi se configuro)

**Paso 3: Ajustar HTTP methods (si no usaste IncludeAllMethods)**

```bash
curl -X POST https://tu-etendo/webhooks/SFUpsertEntity \
  -H "Content-Type: application/json" \
  -d '{
    "SpecID": "ABC123...",
    "TabID": "<ad-tab-id>",
    "ModuleID": "<tu-module-id>",
    "Name": "Order",
    "IsGet": "Y",
    "IsGetbyid": "Y",
    "IsPost": "Y",
    "IsPut": "Y",
    "IsPatch": "Y",
    "IsDelete": "N"
  }'
```

**Paso 4: Ajustar visibilidad de campos**

```bash
curl -X POST https://tu-etendo/webhooks/SFUpsertField \
  -H "Content-Type: application/json" \
  -d '{
    "EntityID": "<entity-id>",
    "ColumnID": "<ad-column-id>",
    "ModuleID": "<tu-module-id>",
    "IsIncluded": "Y",
    "IsReadOnly": "N"
  }'
```

### Opcion B: Via UI de Etendo

1. Navegar a la ventana **ETGO_SF_Spec** en el Application Dictionary
2. Crear un nuevo registro con el nombre del spec y seleccionar la ventana/proceso
3. Click en el boton **"Populate"** para auto-generar entities y fields
4. Ajustar los flags de HTTP methods en cada entity
5. Ajustar IsIncluded/IsReadOnly en los fields segun necesidad

### Opcion C: Spec de Proceso

```bash
# Crear spec de tipo Process
curl -X POST https://tu-etendo/webhooks/SFUpsertSpec \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "generate-report",
    "ModuleID": "<tu-module-id>",
    "SpecType": "P",
    "ProcessID": "<ad-process-id>"
  }'

# Poblar (crea 1 entity + 1 field por parametro)
curl -X POST https://tu-etendo/webhooks/SFPopulateSpec \
  -d '{ "SpecID": "..." }'
```

### Verificar que funciona

```bash
# Listar todos los specs disponibles
curl -X GET https://tu-etendo/sws/neo/ \
  -H "Authorization: Bearer <jwt>"

# Listar registros de la entity "Order" del spec "sales-order"
curl -X GET https://tu-etendo/sws/neo/sales-order/Order \
  -H "Authorization: Bearer <jwt>"
```

---

## 6. Autenticacion

Todos los requests requieren JWT bearer token:

```
Authorization: Bearer <jwt-token>
```

### Claims JWT Requeridos

| Claim | Descripcion |
|-------|-------------|
| `ad_user_id` | ID del usuario Etendo |
| `ad_role_id` | ID del role Etendo |
| `ad_org_id` | ID de la organizacion |
| `ad_client_id` | ID del client |
| `m_warehouse_id` | ID del warehouse (opcional pero esperado) |

Token invalido o faltante retorna `401 Unauthorized`.

El token se decodifica via `SecureWebServicesUtils.decodeToken()` y se usa para construir un `OBContext` completo que dura toda la vida del request. Todas las queries DAL respetan el acceso del usuario.

---

## 7. Referencia de Endpoints

Base URL: `/sws/neo`

### Discovery

| Pattern | Method | Descripcion |
|---------|--------|-------------|
| `/` | GET | Listar todos los specs disponibles |

### Window Specs (SPEC_TYPE = 'W')

| Pattern | Method | Descripcion |
|---------|--------|-------------|
| `/{specName}/{entityName}` | GET | Listar registros |
| `/{specName}/{entityName}` | POST | Crear registro |
| `/{specName}/{entityName}/{recordId}` | GET | Obtener registro por ID |
| `/{specName}/{entityName}/{recordId}` | PUT | Update completo |
| `/{specName}/{entityName}/{recordId}` | PATCH | Update parcial |
| `/{specName}/{entityName}/{recordId}` | DELETE | Eliminar registro |
| `/{specName}/{entityName}/selectors` | GET | Listar selectors FK |
| `/{specName}/{entityName}/selectors/{columnName}` | GET | Query valores del selector |
| `/{specName}/{entityName}/defaults` | GET | Resolver valores default para nuevo registro |
| `/{specName}/{entityName}/callout` | POST | Ejecutar AD Callout |
| `/{specName}/{entityName}/evaluate-display` | GET | Evaluar display logic |
| `/{specName}/{entityName}/{recordId}/action` | GET | Listar button actions |
| `/{specName}/{entityName}/{recordId}/action/{columnName}` | POST | Ejecutar button action |

### Process Specs (SPEC_TYPE = 'P')

| Pattern | Method | Descripcion |
|---------|--------|-------------|
| `/{specName}` | GET | Describir proceso (parametros, metadata) |
| `/{specName}` | POST | Ejecutar proceso |

### Report Specs (SPEC_TYPE = 'R')

| Pattern | Method | Descripcion |
|---------|--------|-------------|
| `/{specName}` | GET | Describir reporte + formatos soportados |
| `/{specName}` | POST | Generar reporte (con parametro `format`) |

---

## 8. CRUD Operations

Las operaciones CRUD delegan al `DataSourceServlet` interno de Etendo (EtendoRX). El formato de request/response sigue las convenciones standard de Etendo data source.

Cada metodo HTTP debe estar explicitamente habilitado en el entity record. Request a metodo deshabilitado retorna `405 Method Not Allowed`.

### GET lista

```bash
GET /sws/neo/sales-order/Order?_startRow=0&_endRow=20&_sortBy=orderDate
```

Soporta los parametros standard de DataSourceServlet: filtrado, sorting, paginacion. El servlet inyecta `tabId` y `windowId` automaticamente para que se apliquen los HQL where clauses del tab.

### GET por ID

```bash
GET /sws/neo/sales-order/Order/ABC123-DEF456
```

Retorna un solo registro. Requiere `ISGET` o `ISGETBYID` habilitado.

### POST crear

```bash
POST /sws/neo/sales-order/Order
Content-Type: application/json

{
  "businessPartner": "PARTNER-ID-123",
  "orderDate": "2026-03-16",
  "warehouse": "WAREHOUSE-ID"
}
```

### PUT / PATCH update

```bash
PUT /sws/neo/sales-order/Order/ABC123
Content-Type: application/json

{
  "description": "Updated description"
}
```

Ambos PUT y PATCH se delegan al handler PUT de DataSourceServlet internamente. PATCH se intercepta via override de `service()`.

### DELETE

```bash
DELETE /sws/neo/sales-order/Order/ABC123
```

---

## 9. Pipeline de Selectors (FK Dropdowns)

El `NeoSelectorService` resuelve referencias FK y provee valores de dropdown con busqueda.

### Tipos de Selectors

**Simple selectors (TableDir ref 19, Table ref 18, Search ref 30):**
- Usan `AD_Ref_Table` para encontrar la entity target
- Una sola propiedad de display (identifier)
- Where clause opcional desde `AD_Ref_Table.HQLWhereClause`

**Rich selectors (OBUISEL_Selector):**
- Grid con multiples columnas configurables
- Propiedades buscables con `sortNo`
- Campo display/value configurable
- Soporte HQL custom completo
- Sustitucion de placeholders `@param@` desde OBContext
- Filtrado por validation rules

### Listar selectors disponibles

```bash
GET /sws/neo/sales-order/Order/selectors
```

Response:
```json
{
  "selectors": [
    {
      "columnName": "C_BPartner_ID",
      "referenceType": "TableDir",
      "type": "simple",
      "targetEntity": "BusinessPartner",
      "displayProperty": "name"
    },
    {
      "columnName": "M_Product_ID",
      "referenceType": "OBUISEL",
      "type": "rich",
      "targetEntity": "Product",
      "displayProperty": "name"
    }
  ],
  "count": 2
}
```

### Buscar valores de un selector

```bash
GET /sws/neo/sales-order/Order/selectors/C_BPartner_ID?q=customer&limit=20&offset=0
```

| Param | Default | Max | Descripcion |
|-------|---------|-----|-------------|
| `q` | (none) | -- | Texto de busqueda. Filtra en display property (simple) o todos los campos buscables (rich). Case-insensitive partial match. |
| `limit` | 20 | 100 | Page size. |
| `offset` | 0 | -- | Page offset. |

Response (simple):
```json
{
  "items": [
    { "id": "ABC123", "label": "Customer A" },
    { "id": "DEF456", "label": "Customer B" }
  ],
  "columns": [],
  "totalCount": 42,
  "limit": 20,
  "offset": 0,
  "hasMore": true
}
```

Response (rich OBUISEL):
```json
{
  "items": [
    {
      "id": "ABC123",
      "label": "Product X",
      "name": "Product X",
      "searchKey": "PROD-001",
      "productCategory": "Category A"
    }
  ],
  "columns": [
    { "name": "name", "label": "Name", "sortNo": 10 },
    { "name": "searchKey", "label": "Search Key", "sortNo": 20 },
    { "name": "productCategory", "label": "Category", "sortNo": 30 }
  ],
  "totalCount": 150,
  "limit": 20,
  "offset": 0,
  "hasMore": true
}
```

### Prioridad de resolucion de referencia

1. **OBUISEL Selector** - se chequea primero via `referenceSearchKey` o column reference
2. **TableDir (ref 19)** - convencion de nombre: `{TableName}_ID` resuelve a la tabla target
3. **Table (ref 18) / Search (ref 30)** - resuelto via `AD_Ref_Table`

### Placeholders soportados en OBUISEL HQL

`@AD_Org_ID@`, `@AD_Client_ID@`, `@AD_User_ID@`, `@AD_Role_ID@`

### Context params para selectors con Validation Rules

Si un selector tiene un `AD_Validation` con codigo que referencia parametros (ej: `@M_Product_Category_ID@`), el campo `selectorParams` en la lista de selectors indica que parametros se esperan. El cliente los envia como query params:

```bash
GET /sws/neo/sales-order/OrderLine/selectors/M_Product_ID?q=laptop&M_Product_Category_ID=CAT123
```

---

## 10. Pipeline de Callouts

El `NeoCalloutService` ejecuta AD_Callouts existentes de Etendo via REST. Permite a un frontend ejecutar la logica de campo-cambiado sin un formulario clasico.

### Endpoint

```bash
POST /sws/neo/{specName}/{entityName}/callout
```

### Request Body

```json
{
  "field": "businessPartner",
  "value": "BPARTNER-ID-123",
  "formState": {
    "orderDate": "2026-03-16",
    "warehouse": "WH-001",
    "salesTransaction": true
  },
  "auxiliaryValues": {
    "businessPartner_LOC": "LOCATION-ID"
  }
}
```

| Campo | Requerido | Descripcion |
|-------|-----------|-------------|
| `field` | Si | Nombre del campo que cambio. Acepta: nombre OBDal property (ej: `businessPartner`), DB column name (ej: `C_BPartner_ID`), o inp name (ej: `inpcBpartnerId`) |
| `value` | Si | Nuevo valor del campo |
| `formState` | No | Estado actual de todos los campos del formulario |
| `auxiliaryValues` | No | Valores auxiliares de selectors OBUISEL (ej: `campo_LOC`, `campo_DES`) |

### Pipeline interno de callout

1. **Resolucion de campo**: Recibe el nombre del campo y busca la `AD_Column` correspondiente matcheando por:
   - OBDal property name (ej: `businessPartner` para `C_BPartner_ID`) - **prioridad 1**
   - DB column name (ej: `C_BPartner_ID`) - **prioridad 2**
   - Clean REST name (ej: `bpartner`) - **prioridad 3**
   - inp name (ej: `inpcBpartnerId`) - **prioridad 4**

2. **Resolucion de callout**: Busca la clase callout desde `AD_Column.Callout` -> `AD_ModelImplementation.JavaClassName`.

3. **Construccion de request sintetico**: Crea un `SyntheticHttpServletRequest` con:
   - Parametros `inp*` mapeados desde el `formState`
   - `inpLastFieldChanged` con el campo trigger
   - `inpTabId` / `inpwindowId` del tab
   - Variables de OBContext (`inpadOrgId`, `inpadClientId`, `isSOTrx`, etc.)
   - Session attributes para `VariablesSecureApp` (`#AD_User_ID`, `#AD_Role_ID`, etc.)

4. **Ejecucion**: Instancia la clase callout, la inicializa, y ejecuta `executeSimpleCallout()`.

5. **Transformacion de response**: Convierte el resultado nativo del callout (formato inp*) a nombres OBDal property limpios.

### Response

```json
{
  "updates": {
    "paymentTerms": { "value": "PAYMENT-TERM-ID" },
    "priceList": { "value": "PRICE-LIST-ID" }
  },
  "combos": {
    "paymentMethod": {
      "selected": "SELECTED-ID",
      "entries": [
        { "id": "PM-001", "identifier": "Cash" },
        { "id": "PM-002", "identifier": "Credit Card" }
      ]
    }
  },
  "messages": [
    { "type": "WARNING", "text": "Credit limit exceeded" }
  ]
}
```

| Campo | Descripcion |
|-------|-------------|
| `updates` | Campos que el callout quiere actualizar con nuevos valores |
| `combos` | Dropdowns que el callout quiere recargar con nuevas opciones |
| `messages` | Mensajes para mostrar al usuario (tipos: MESSAGE, WARNING, ERROR, INFO, SUCCESS) |

### Notas importantes

- Si el campo no tiene callout configurado, retorna response vacio (no error)
- Solo soporta `SimpleCallout` (no callouts legacy)
- `JSEXECUTE` se ignora (no aplica en contexto REST)
- El `formState` puede enviar nombres OBDal property (ej: `orderDate`) - se mapean automaticamente a formato inp (ej: `inpdateordered`)

---

## 11. Pipeline de Defaults

El `NeoDefaultsService` resuelve valores por defecto cuando se crea un nuevo registro.

### Endpoint

```bash
GET /sws/neo/{specName}/{entityName}/defaults?parentId=PARENT-RECORD-ID
```

### Pipeline de resolucion

Para cada campo incluido (IsIncluded=Y) de la entity, resuelve en este orden:

1. **IsActive**: Siempre `true` (comportamiento NEO-specific)
2. **Link-to-parent**: Si la columna es `linkToParentColumn` y hay `parentId` en query params, usa ese valor
3. **Sequence/DocumentNo**: Para campos de secuencia, genera preview via `Utility.getDocumentNo()` con `updateNext=false`. El valor queda envuelto en `<>` (ej: `<1000234>`)
4. **@SQL= expressions**: Parsea la expression, resuelve tokens `@param@` via `Utility.getContext()`, ejecuta la query SQL
5. **Context variables**: Delega a `Utility.getDefault()` que maneja:
   - Variables de sesion (`@#AD_Org_ID@`, `@#Date@`, etc.)
   - Preferences via `Utility.getPreference`
   - Alternativas separadas por coma (`@#Var1@,@#Var2@,literal`)
   - Literales sin `@` (ej: `"DR"`, `"N"`, `"0"`)

### Bridge VariablesSecureApp

Como Neo Headless autentica via JWT y no tiene HttpSession, construye un `VariablesSecureApp` bridge desde `OBContext` con:

| Session Value | Fuente |
|---------------|--------|
| `#AD_User_ID` | `OBContext.getUser()` |
| `#AD_Client_ID` | `OBContext.getCurrentClient()` |
| `#AD_Org_ID` | `OBContext.getCurrentOrganization()` |
| `#AD_Role_ID` | `OBContext.getRole()` |
| `#AD_Language` | `OBContext.getLanguage()` |
| `#M_Warehouse_ID` | `OBContext.getWarehouse()` |
| `#Date` | Fecha actual (`yyyy-MM-dd`) |
| `#User_Level` | `OBContext.getRole().getUserLevel()` |
| `#User_Client` | `'clientId','0'` |
| `#AccessibleOrgTree` | Org tree accesible desde `OBContext` |

### Response

```json
{
  "defaults": {
    "orderDate": "2026-03-16",
    "documentNo": "<1000234>",
    "active": true,
    "salesTransaction": "Y",
    "warehouse": "WH-001-ID",
    "organization": "ORG-ID"
  },
  "metadata": {
    "unresolvedFields": ["customField"],
    "sequenceFields": ["documentNo"]
  }
}
```

Los nombres de campo en la respuesta usan nombres OBDal property (ej: `orderDate` para `DateOrdered`, `businessPartner` para `C_BPartner_ID`), consistente con los GET responses.

---

## 12. Pipeline de Procesos

El `NeoProcessService` ejecuta procesos AD_Process desde la API.

### Tipos de proceso soportados

| Tipo | UIPattern | Handler | Soporte |
|------|-----------|---------|---------|
| OBUIAPP | `S` (Standard) | `BaseProcessActionHandler` subclass | Soportado. Invocado via reflection en `doExecute(Map, String)`. |
| Classic | (any) | `DalBaseProcess` subclass | Soportado. Invocado via reflection en `doExecute(ProcessBundle)`. |
| DB Procedure | -- | PL/SQL procedure | Retorna `501 Not Implemented`. |

### Describir proceso

```bash
GET /sws/neo/generate-report
```

Response:
```json
{
  "id": "PROCESS-ID",
  "name": "Generate Report",
  "description": "Generates a monthly report",
  "uiPattern": "S",
  "javaClassName": "com.example.GenerateReport",
  "processType": "OBUIAPP",
  "parameters": [
    {
      "name": "Date From",
      "dbColumnName": "DateFrom",
      "sequenceNumber": 10,
      "mandatory": true,
      "defaultValue": "",
      "description": "Start date",
      "referenceId": "15",
      "referenceType": "Date",
      "isRange": false,
      "length": 0
    }
  ],
  "parameterCount": 1
}
```

### Ejecutar proceso

```bash
POST /sws/neo/generate-report
Content-Type: application/json

{
  "DateFrom": "2026-01-01",
  "DateTo": "2026-01-31"
}
```

Los parametros se envian keyed por DB column name. Se validan mandatorios antes de ejecucion. Parametro mandatorio faltante (sin default) retorna `400`.

### Response de ejecucion

OBUIAPP:
```json
{
  "status": "success",
  "message": "Process completed"
}
```

Classic:
```json
{
  "status": "success",
  "title": "Process Complete",
  "message": "5 records updated"
}
```

Error: HTTP 400 con `"status": "error"`.

### Button Actions (procesos sobre registros)

Los button actions son campos cuya `AD_Column` tiene `AD_Reference_ID = '28'` (Button type) con un proceso vinculado.

```bash
# Listar actions disponibles
GET /sws/neo/sales-order/Order/ABC123/action

# Ejecutar action
POST /sws/neo/sales-order/Order/ABC123/action/DocAction
Content-Type: application/json

{ "DocAction": "CO" }
```

El `recordId` de la URL se inyecta automaticamente como `inpRecordId` en los parametros del proceso.

---

## 13. Pipeline de Reportes

El `NeoReportService` genera reportes Jasper desde specs tipo `R`.

### Describir reporte

```bash
GET /sws/neo/my-report
```

Response (extiende la metadata de proceso):
```json
{
  "id": "...",
  "name": "Sales Report",
  "isReport": true,
  "supportedFormats": ["PDF", "XLS", "XLSX", "HTML", "CSV"],
  "parameters": [...]
}
```

### Generar reporte

```bash
POST /sws/neo/my-report?format=PDF
Content-Type: application/json

{
  "DateFrom": "2026-01-01",
  "DateTo": "2026-01-31"
}
```

El response es el archivo binario del reporte con headers:
- `Content-Type`: segun formato (`application/pdf`, `application/vnd.ms-excel`, etc.)
- `Content-Disposition`: `attachment; filename="sales-report-2026-03-16.pdf"`

### Mapping de tipos de parametros

Los parametros JSON se convierten a tipos Java segun `AD_Reference_ID`:

| Ref ID | Tipo | Conversion |
|--------|------|------------|
| 15, 16 | Date/DateTime | `yyyy-MM-dd` o `yyyy-MM-dd'T'HH:mm:ss` |
| 11 | Integer | `Long.parseLong` |
| 22, 12 | Number/Amount | `BigDecimal` |
| 20 | Yes/No | `"Y"` o `"true"` -> `Boolean.TRUE` |
| 10, 17, 18, 19, 30 | String/List/FK | String |

---

## 14. Field Filtering (Control de Campos)

El `NeoFieldFilter` controla que campos son visibles y escribibles en la API basado en la configuracion de `ETGO_SF_FIELD`.

### Comportamiento

| Operacion | IsIncluded=Y + IsReadOnly=N | IsIncluded=Y + IsReadOnly=Y | IsIncluded=N |
|-----------|------|------|------|
| GET response | Visible | Visible | **Removido** |
| POST/PUT/PATCH request | Aceptado | **Removido** | **Removido** |
| Selectors | Listado | Listado | **Excluido** |
| Button actions | Listado | Listado | **Excluido** |

### Reglas especiales

- El campo `id` siempre se incluye (necesario para identificacion)
- Keys de metadata (`_identifier`, `_entityName`, `$_identifier`, `recordTime`) siempre se preservan
- Para campos FK, tambien se incluye la variante `$_identifier` automaticamente
- Si no hay ETGO_SF_FIELD configurados para una entity, **no se aplica filtrado** (todo pasa)

### Ejemplo practico

Si configuras un campo como `IsIncluded=Y, IsReadOnly=Y`:
- El valor aparece en GET responses (lectura)
- Si el cliente lo envia en un POST/PUT, el campo se **remueve silenciosamente** antes de delegar al DataSourceServlet

---

## 15. Parent-Child Tab Filtering

Cuando una entity mapea a un child tab (`tabLevel > 0`), el servlet filtra registros automaticamente por el registro padre.

### Uso

```bash
GET /sws/neo/sales-order/OrderLine?parentId=PARENT-ORDER-ID
```

### Pipeline interno

1. `KernelUtils.getParentTab(childTab)` encuentra el tab padre en la jerarquia
2. `ApplicationUtils.getParentProperty(childTab, parentTab)` determina la FK property del hijo
3. Se inspecciona el tipo de la propiedad via `ModelProvider`:
   - **Entity reference** (mas comun): genera `e.salesOrder.id='ABC123'`
   - **Primitive** (raro): genera `e.salesOrder='ABC123'`
4. El fragmento HQL se inyecta como `whereAndFilterClause` en el request al DataSourceServlet

Tabs con `DisableParentKeyProperty = Y` saltan el filtro parent.

---

## 16. Customizacion con NeoHandler (Hooks CDI)

Para inyectar logica de negocio custom, implementa la interface `NeoHandler` y anotala con `@Named`.

### Interface

```java
public interface NeoHandler {
  /**
   * Handle the request.
   * Return NeoResponse to produce the full response,
   * or null to fall through to default DataSourceServlet handling.
   */
  NeoResponse handle(NeoContext context);
}
```

### Ejemplo completo

```java
package com.example;

import javax.inject.Named;
import org.codehaus.jettison.json.JSONObject;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;

@Named("myCustomHandler")
public class MyCustomHandler implements NeoHandler {

  @Override
  public NeoResponse handle(NeoContext context) {
    // Solo interceptar POST
    if ("POST".equals(context.getHttpMethod())) {
      JSONObject body = context.getRequestBody();

      // Custom validation
      if (!body.has("requiredField")) {
        return NeoResponse.error(400, "requiredField is mandatory");
      }

      // Custom create logic
      JSONObject result = new JSONObject();
      result.put("id", "new-id");
      result.put("status", "created via custom handler");
      return NeoResponse.created(result);
    }

    // Para GET, PUT, DELETE -> fall through al comportamiento default
    return null;
  }
}
```

### Configuracion

Setear `JAVA_QUALIFIER = 'myCustomHandler'` en el registro `ETGO_SF_Entity` correspondiente (via UI o webhook SFUpsertEntity).

### NeoContext - Campos disponibles

| Campo | Tipo | Descripcion |
|-------|------|-------------|
| `specName` | `String` | Nombre del spec desde URL |
| `entityName` | `String` | Nombre de entity desde URL |
| `httpMethod` | `String` | `GET`, `POST`, `PUT`, `PATCH`, o `DELETE` |
| `recordId` | `String` | Record ID desde URL (null para list operations) |
| `requestBody` | `JSONObject` | Body parseado (null para GET/DELETE) |
| `queryParams` | `Map<String, String>` | Query parameters de la URL |
| `adTab` | `Tab` | AD_Tab resuelto (null para process specs) |
| `sfEntity` | `SFEntity` | El registro ETGO_SF_Entity |
| `obContext` | `OBContext` | Contexto del usuario autenticado |
| `previousResult` | `NeoResponse` | Mutable. Se puede setear para patrones post-processing. |

### NeoResponse - Builders estaticos

| Method | HTTP Status | Descripcion |
|--------|-------------|-------------|
| `NeoResponse.ok(JSONObject)` | 200 | Exito con body |
| `NeoResponse.created(JSONObject)` | 201 | Creado con body |
| `NeoResponse.noContent()` | 204 | Exito, sin body |
| `NeoResponse.error(int, String)` | (dado) | Error con `{"error": {"message": "...", "status": N}}` |
| `NeoResponse.error(int, JSONObject)` | (dado) | Error con body JSON custom (ej: detalles de validacion) |

Headers custom via `withHeader(name, value)`.

### Comportamiento de discovery CDI

- El handler se busca via `WeldUtils` usando `@Named("qualifierValue")`
- Si la clase no se encuentra via CDI, el request **fall through al comportamiento default** con un warning log
- El `beans.xml` del modulo tiene `bean-discovery-mode="all"` para discovery automatico
- El handler se ejecuta **antes** del field filtering

---

## 17. OpenAPI Auto-Generado

`NeoOpenAPIEndpoint` genera documentacion OpenAPI 3.0 dinamicamente escaneando los registros `ETGO_SF_SPEC`.

- Registra paths para cada spec (Window, Process, Report)
- Se descubre via CDI (`beans.xml` bean-discovery-mode="all")
- Incluye schemas, parametros, y responses para cada tipo de spec

---

## 18. Seguridad

Neo Headless aplica seguridad en multiples capas:

| Capa | Mecanismo | Error Code |
|------|-----------|------------|
| Autenticacion | JWT bearer token via SecureWebServices | 401 |
| OBContext | Claims JWT -> OBContext completo. Todas las queries DAL respetan org/client access | -- |
| Window access | Verifica `ADWindowAccess` para el role actual | 403 |
| Process access | Verifica `ADProcessAccess` antes de ejecucion | 403 |
| Method-level | Cada metodo HTTP debe estar habilitado en entity record | 405 |
| Field-level | Solo campos con `ISINCLUDED = 'Y'` participan. Read-only fields no se escriben | -- |
| Error sanitization | Mensajes de error de autenticacion se sanitizan para no exponer detalles internos | -- |

---

## 19. Testing

Tests unitarios en `src-test/src/com/etendoerp/go/schemaforge/`:

| Test | Cobertura |
|------|-----------|
| `NeoServletPathTest` | URL path parsing: paths validos, selectors, actions, edge cases |
| `NeoContextTest` | Builder pattern, todos los campos, HTTP methods, `previousResult` mutable |
| `NeoResponseTest` | Builders estaticos (`ok`, `created`, `noContent`, `error`), headers custom |
| `NeoServletTabFilterTest` | Generacion de HQL where clause parent-child |
| `NeoCalloutServiceTest` | Ejecucion de callouts, mapping inp names, response transformation |
| `SFPopulateSpecTest` | Auto-poblacion desde metadata AD |
| `SFUpsertSpecTest` | Webhook de upsert spec |
| `SFUpsertEntityTest` | Webhook de upsert entity |
| `SFUpsertFieldTest` | Webhook de upsert field |
| `SFListWindowsTest` | Webhook de listar ventanas |
| `SFListProcessesTest` | Webhook de listar procesos |

---

## 20. Troubleshooting

### 401 Unauthorized
- Verificar que el token JWT es valido y no expirado
- Verificar que los claims requeridos estan presentes (`ad_user_id`, `ad_role_id`, `ad_org_id`, `ad_client_id`)

### 403 Forbidden
- El role del usuario no tiene acceso a la ventana/proceso
- Verificar `ADWindowAccess` / `ADProcessAccess` para el role

### 404 Not Found
- Verificar que el spec existe y esta activo (`ISACTIVE = 'Y'`)
- Verificar que el nombre del spec en la URL matchea exactamente el campo `NAME`
- Verificar que la entity existe y esta incluida (`ISINCLUDED = 'Y'`)

### 405 Method Not Allowed
- El metodo HTTP no esta habilitado en la entity
- Verificar los flags `ISGET`, `ISPOST`, `ISPUT`, `ISPATCH`, `ISDELETE` en `ETGO_SF_ENTITY`

### Callout no ejecuta
- Verificar que la `AD_Column` tiene un callout configurado
- Solo se soportan `SimpleCallout`, no callouts legacy
- Verificar que el nombre del campo en el request matchea alguna de las formas aceptadas (OBDal property, DB column, inp)

### Defaults no se resuelven
- Verificar que el campo tiene `AD_Column.DefaultValue` configurado
- Verificar que el campo esta incluido (`ISINCLUDED = 'Y'`)
- Para SQL defaults, verificar que los tokens `@param@` se resuelven correctamente

### Selector retorna vacio
- Verificar que el campo esta incluido (`ISINCLUDED = 'Y'`)
- Verificar que el campo tiene un reference type FK (18, 19, 30, o OBUISEL)
- Para selectors con validation rules, verificar que se envian los context params necesarios

### Repoblar spec
- El populate **elimina** todas las entities y fields existentes antes de recrear
- Si hiciste cambios manuales en entities/fields, se perderan al repoblar
- Usar los webhooks SFUpsertEntity/SFUpsertField para cambios puntuales

---

## Estructura de Archivos

```
src/com/etendoerp/go/schemaforge/
  NeoServlet.java              # Entry point, routing, auth
  NeoHandler.java              # Interface para hooks custom
  NeoContext.java              # Contexto de request (builder)
  NeoResponse.java             # Wrapper de response
  NeoSelectorService.java      # FK dropdowns
  NeoCalloutService.java       # AD Callout execution
  NeoDefaultsService.java      # Default value resolution
  NeoProcessService.java       # Process execution
  NeoReportService.java        # Jasper report generation
  NeoFieldFilter.java          # Field-level filtering
  NeoOpenAPIEndpoint.java      # OpenAPI documentation
  PopulateSpecHelper.java      # Auto-populate from AD metadata
  PopulateSpecProcess.java     # AD_Process wrapper for populate
  SyntheticHttpServletRequest.java  # Mock request for callouts
  data/
    SFSpec.java                # ORM entity (auto-generated)
    SFEntity.java              # ORM entity (auto-generated)
    SFField.java               # ORM entity (auto-generated)
  webhooks/
    SFUpsertSpec.java          # Webhook: create/update spec
    SFUpsertEntity.java        # Webhook: create/update entity
    SFUpsertField.java         # Webhook: create/update field
    SFPopulateSpec.java        # Webhook: auto-populate
    SFListWindows.java         # Webhook: list windows
    SFListProcesses.java       # Webhook: list processes
    SFListMenu.java            # Webhook: list menu items

src-db/database/
  model/tables/
    ETGO_SF_SPEC.xml           # Table definition
    ETGO_SF_ENTITY.xml         # Table definition
    ETGO_SF_FIELD.xml          # Table definition
  sourcedata/
    ETGO_SF_SPEC.xml           # Sample specs
    ETGO_SF_ENTITY.xml         # Sample entities
    ETGO_SF_FIELD.xml          # Sample fields

src-test/src/com/etendoerp/go/schemaforge/
    NeoServletPathTest.java
    NeoContextTest.java
    NeoResponseTest.java
    NeoServletTabFilterTest.java
    NeoCalloutServiceTest.java
    ...
```
