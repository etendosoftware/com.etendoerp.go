# Sonar Refactor — ETP-3928

Acciones para resolver los Sonar `S1448` (brain-overload, "too many methods") en `SelectorQueryBuilder` y `NeoServlet`.

> **Nota:** El caso `SyntheticServletRequestBase` ya quedó resuelto con `@SuppressWarnings("java:S1448")` — los métodos están forzados por la interfaz `HttpServletRequest` y un split no reduce la superficie real.

---

## 1. `SelectorQueryBuilder` (37 → ~6 métodos)

Archivo origen: `src/com/etendoerp/go/schemaforge/SelectorQueryBuilder.java`

### Acciones









## 2. `NeoServlet` (45 → ~8 métodos)

Archivo origen: `src/com/etendoerp/go/schemaforge/NeoServlet.java`

### Acciones

#### A. Crear clase `NeoAuthenticator`
- **Archivo nuevo:** `src/com/etendoerp/go/schemaforge/NeoAuthenticator.java`
- **Tipo:** clase regular instanciable (igual que los otros handlers ya existentes)
- **Mover desde `NeoServlet`:**
  - `authenticateRequest`
  - `authenticateJwt`
  - `hasWindowAccess`
  - `hasProcessAccess`
- **En `NeoServlet`:** declarar `private final NeoAuthenticator authenticator = new NeoAuthenticator();` y delegar.

#### B. Crear clase `NeoRequestRouter`
- **Archivo nuevo:** `src/com/etendoerp/go/schemaforge/NeoRequestRouter.java`
- **Tipo:** clase regular instanciable
- **Mover desde `NeoServlet`:**
  - `parseRequestPath`
  - `handleSpecRequest`
  - `handleProcessSpecRequest`
  - `handleReportSpecRequest`
  - `handleWindowSpecRequest`

#### C. Crear clase `NeoSubEndpointDispatcher`
- **Archivo nuevo:** `src/com/etendoerp/go/schemaforge/NeoSubEndpointDispatcher.java`
- **Tipo:** clase regular instanciable
- **Mover desde `NeoServlet`:**
  - `handleWindowSubEndpoint`
  - `handleSelectorSubEndpoint`
  - `handleActionSubEndpoint`
  - `handleEvaluateDisplaySubEndpoint`
  - `handleCalloutSubEndpoint`
  - `handleDefaultsSubEndpoint`
  - `handleHookedSubEndpoint`
  - `resolveActionDispatchParams`

#### D. Crear clase `NeoHookDispatcher`
- **Archivo nuevo:** `src/com/etendoerp/go/schemaforge/NeoHookDispatcher.java`
- **Tipo:** clase regular instanciable
- **Mover desde `NeoServlet`:**
  - `dispatchWithHooks` (ambas sobrecargas)
  - `buildHookContext`
  - `executeHookChain`

#### E. Crear clase `NeoSelectorEndpoint`
- **Archivo nuevo:** `src/com/etendoerp/go/schemaforge/NeoSelectorEndpoint.java`
- **Tipo:** clase regular instanciable
- **Mover desde `NeoServlet`:**
  - `handleSelector`

#### F. Crear clase `NeoCalloutEndpoint`
- **Archivo nuevo:** `src/com/etendoerp/go/schemaforge/NeoCalloutEndpoint.java`
- **Tipo:** clase regular instanciable
- **Mover desde `NeoServlet`:**
  - `handleCallout`
  - `mergeCalloutResponse`
  - `mergeJsonSection`

#### G. Crear clase `NeoDefaultsEndpoint`
- **Archivo nuevo:** `src/com/etendoerp/go/schemaforge/NeoDefaultsEndpoint.java`
- **Tipo:** clase regular instanciable
- **Mover desde `NeoServlet`:**
  - `handleDefaults`

#### H. Crear clase `NeoProcessReportEndpoint`
- **Archivo nuevo:** `src/com/etendoerp/go/schemaforge/NeoProcessReportEndpoint.java`
- **Tipo:** clase regular instanciable
- **Mover desde `NeoServlet`:**
  - `handleProcessSpec`
  - `handleReportSpec`



#### J. `NeoServlet` queda delgado (~8 métodos)
- **Conservar:**
  - `doGet`, `doPost`, `doPut`, `doDelete`, `service` (HTTP entry)
  - `processRequest` (delegación principal)
  - `lookupHandler`
  - `isMethodEnabled`
- **Agregar campos:** una instancia de cada nueva clase colaboradora (siguiendo el patrón ya existente de `discoveryHandler`, `buttonHandler`, `displayLogicHandler`, `crudHandler`).

#### K. Ajustar callers externos
- Algunos métodos privados se vuelven `public` al moverse — revisar si quedan accesos package-private rotos.

---

## Orden sugerido de ejecución

1. **`SelectorQueryBuilder`** primero (es utility puro, sin estado, refactor más mecánico).
2. **`NeoServlet`** después (más caller-graph, requiere instanciar colaboradores y pasar `this` cuando haga falta).
3. Correr tests existentes después de cada split.
4. Verificar en SonarCloud que el conteo bajó por debajo de 35 en los 3 archivos.
