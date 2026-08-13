# Columnas Computadas Almacenadas (EPL-1807)

> Guía funcional + técnica del motor de columnas computadas almacenadas introducido en el core de
> Etendo por la epic **EPL-1807**, ilustrada con el módulo piloto
> [`com.etendoerp.storedcomputedcolumn`](https://github.com/etendosoftware/com.etendoerp.storedcomputedcolumn).
>
> **Audiencia:** desarrolladores de módulos y responsables funcionales/técnicos.
> **Alcance:** qué es la funcionalidad, por qué existe, cómo funciona de punta a punta y cómo
> configurar una columna computada almacenada propia.

---

## 1. Resumen ejecutivo

Etendo siempre soportó **columnas computadas virtuales** (`AD_Column.SQLLogic`): una columna de solo
lectura cuyo valor *no* es una columna real de la tabla, sino una expresión SQL que la base de datos
evalúa **cada vez que hacés un SELECT sobre la tabla**. Muy útil para derivar valores de
visualización — pero la expresión se ejecuta en cada lectura, no se puede indexar, y no tiene forma
de saber cuándo cambian sus insumos.

Las **columnas computadas almacenadas** invierten ese compromiso. La columna es una **columna
física y real** en la tabla. Su valor se **calcula una sola vez, cuando los datos de los que depende
efectivamente cambian**, y luego se **persiste**. Las lecturas son lecturas de columna comunes —
indexables, ordenables, filtrables, y tan rápidas como cualquier otra columna almacenada.

```
Virtual (SQLLogic)                    Almacenada (Computation_Mode = 'S')
────────────────────                  ─────────────────────────────────────────
valor = expresión                     valor = una columna real
evaluada en CADA lectura              calculada al ESCRIBIR una dependencia, y guardada
no se puede indexar                   totalmente indexable / ordenable / filtrable
dependencias implícitas               dependencias explícitas, declaradas
falla en runtime en producción        falla en tiempo de build/validación
```

El motor vive en el **core** (EPL-1807). Un módulo solo **declara** la columna, una **función SQL**
que la calcula, y las **filas de dependencia** que indican "recalculá cuando cambie *esta* tabla
origen". El core genera los triggers, mantiene el valor fresco, impone solo-lectura en todas las
capas, y valida la definición completa en tiempo de build.

---

## 2. El problema que resuelve

`AD_Column.SQLLogic` (columnas computadas virtuales) se incrusta en el mapeo de Hibernate como una
*fórmula* y se evalúa de forma perezosa en tiempo de consulta. Eso tiene costos reales en runtime:

- **El costo de lectura escala con el tamaño del resultado.** Una expresión cara por fila ralentiza
  cada consulta de grilla en proporción a cuántas filas se devuelven.
- **No se puede indexar.** Filtrar u ordenar por una columna virtual fuerza un full scan.
- **Las dependencias son implícitas.** Un cambio en una tabla referenciada no produce ninguna
  notificación ni refresco — el valor simplemente se recalcula la próxima vez que alguien lo lee.
- **Los errores aparecen en producción.** Un SQL roto o un desajuste de tipos falla en runtime, no
  al configurarlo.

Estos costos se acumulan a medida que crece el uso de una ventana y el volumen de datos. Las
columnas computadas almacenadas hacen que el rendimiento, la corrección y las relaciones de
dependencia sean **explícitas, exigibles y auditables** — dejando intactas las columnas `SQLLogic`
existentes (esto es aditivo, no una migración).

---

## 3. Conceptos y vocabulario

| Término | Qué es |
|---------|--------|
| **Columna computada almacenada** | Una `AD_Column` con `Computation_Mode = 'S'`. Una columna física mantenida por el motor. |
| **Función de cómputo** | Una función SQL `f(target_id) → valor` que devuelve el valor de la columna para un registro destino. Es el **único escritor** del valor. |
| **Tabla / registro destino** | La tabla donde vive la columna (ej. `C_Order`) y la fila específica cuyo valor se está calculando. |
| **Tabla origen** | Una tabla cuyos cambios deben disparar un recálculo (ej. `C_OrderLine`). |
| **Fila de dependencia** | Un registro `AD_COLUMN_COMP_DEPENDENCY` que conecta una tabla origen + eventos + columnas vigiladas + un resolver de id destino con la columna almacenada. |
| **Columnas vigiladas (watched)** | En una dependencia de UPDATE, el subconjunto de columnas origen que importan — el recálculo solo se dispara si una de ellas cambió. |
| **Resolver de id destino** | SQL que mapea una fila *origen* modificada de vuelta al/los registro(s) *destino* afectado(s). |
| **Fila sucia (dirty)** | Una entrada en `AD_STOREDCOLUMN_DIRTY`: "este registro destino de esta columna necesita recálculo". |
| **Modo de refresco** | Cómo se drenan las filas sucias: `S` síncrono, `Q` en cola/async, `M` manual. |
| **Número de secuencia de cómputo** | Orden global. Los números más bajos recalculan primero, para que una columna pueda leer con seguridad otra columna almacenada calculada antes. |

### Los campos de `AD_Column` que agrega EPL-1807

| Campo | Tipo | Significado |
|-------|------|-------------|
| `Computation_Mode` | CHAR(1) | `N` normal (default) · `V` virtual/`SQLLogic` (existente) · `S` computada almacenada |
| `Computation_Function` | VARCHAR | Nombre completo de la función SQL; requerido cuando el modo = `S` |
| `Refresh_Mode` | CHAR(1) | `S` síncrono (fin de transacción) · `Q` en cola (async) · `M` manual |
| `Computation_Sequence_Number` | INTEGER | Orden global de refresco (default `10` para columnas `S`) |

---

## 4. Los tres modos de refresco

Una columna computada almacenada elige **cuándo** su valor almacenado se pone al día con sus
orígenes:

| Modo | Cómo drena | Cuándo es correcto el valor | Usalo para |
|------|-----------|-----------------------------|------------|
| **`S` — Síncrono** | Un constraint trigger `DEFERRABLE INITIALLY DEFERRED` recalcula **dentro de la misma transacción**, justo antes del commit. | **Siempre** — al commit, consistente transaccionalmente. Un error de cómputo hace rollback de toda la transacción. | Valores que deben ser exactos al leerse (totales de documento, valores que lee una validación). Solo PostgreSQL. |
| **`Q` — En cola (async)** | Las filas sucias persisten tras el commit; un **proceso en segundo plano** las drena más tarde. | **Eventualmente** — tras la próxima corrida del procesador de cola. Acotado por el intervalo del scheduler. | Valores cuyos consumidores toleran el retraso: dashboards, KPIs, visualizaciones no bloqueantes. Cómputos caros. |
| **`M` — Manual** | Las filas sucias persisten; un operador ejecuta el rebuild bajo demanda. | Solo tras una corrida manual de **Rebuild Stored Column**. | Población puntual / dirigida por operador. |

> **Nota de plataforma:** el refresco síncrono (`S`) es **solo PostgreSQL** — depende de constraint
> triggers diferidos, que Oracle no tiene. En Oracle una columna configurada `S` se trata como `Q`:
> todo drena por la cola async. Ver [§10 Oracle](#10-soporte-oracle).

---

## 5. Cómo funciona, de punta a punta

El refresco es un mecanismo de **dos fases, a fin de transacción**. La decisión de diseño clave: los
triggers sobre las tablas origen hacen **casi nada** durante tu DML — solo registran *qué* necesita
recalcularse. El cómputo real (potencialmente caro) sucede **una vez por destino afectado**, al
final.

```
   Escritura de negocio (modificás un C_OrderLine)
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│ Fase 1 — RECOLECCIÓN DE SUCIOS  (trigger AFTER, durante la tx)│
│                                                               │
│  trigger ad_scd_dep_* sobre la tabla origen:                  │
│   1. (solo UPDATE) ¿cambió alguna columna VIGILADA? si no,RET │
│   2. corre TARGET_ID_RESOLVER_SQL → id(s) destino afectado(s) │
│   3. INSERT una fila sucia por destino en AD_STOREDCOLUMN_DIRTY│
│      (INSERT … ON CONFLICT DO NOTHING — dedup dentro de la tx)│
│  Sin cómputo. Sin locks más allá del insert.                  │
└─────────────────────────────────────────────────────────────┘
        │
        ▼  (Refresh_Mode = 'S')
┌─────────────────────────────────────────────────────────────┐
│ Fase 2 — RECÁLCULO DIFERIDO  (justo antes del COMMIT)         │
│                                                               │
│  ad_scd_dirty_aiu — un CONSTRAINT trigger DEFERRABLE          │
│  INITIALLY DEFERRED sobre AD_STOREDCOLUMN_DIRTY:              │
│   1. SET LOCAL my.scd_refreshing = 'true'  (guard anti-loop)  │
│   2. lee+borra todas las filas sucias de ESTA tx, ordenadas   │
│      por Computation_Sequence_Number ASC                      │
│   3. llama a ad_scd_recompute una vez por fila destino, en    │
│      orden de Computation_Sequence_Number                     │
│   4. ad_scd_recompute toma un lock FOR UPDATE sobre el destino │
│      y escribe UPDATE <col> = <fn>(<pk>) de forma incondicional│
│      (sin guard IS DISTINCT FROM — computaría dos veces)      │
│  Un error de la función acá hace rollback de toda la tx.      │
└─────────────────────────────────────────────────────────────┘

        │  (Refresh_Mode = 'Q')  las sucias simplemente persisten tras el commit
        ▼
┌─────────────────────────────────────────────────────────────┐
│ Fase 2' — DRENADO ASYNC  (proceso en segundo plano, después)  │
│  StoredColumnQueueProcessor toma un lote (por cliente,        │
│  serialmente, en orden de secuencia), recalcula, escribe,     │
│  borra.                                                       │
└─────────────────────────────────────────────────────────────┘
```

### ¿Por qué dos fases?

1. **Recolección de sucios rápida** — los triggers insertan ids y vuelven; no hay cómputo durante el
   DML.
2. **No bloqueante** — la pasada diferida corre al commit, no entre sentencias.
3. **Consistencia a fin de transacción** — la función siempre ve el *estado final commiteado* de
   todos los datos origen de esa transacción.
4. **Exactamente una vez por destino** — actualizar en bloque N filas origen produce exactamente N
   recálculos tras la deduplicación, no N × (cantidad de sentencias).
5. **Sin bucles de retroalimentación** — el guard `my.scd_refreshing` hace que las propias
   escrituras del motor sean invisibles para los triggers de dependencia, así un refresco no puede
   cascadear en un bucle infinito.

### El resolver de id destino (cómo un cambio origen encuentra su destino)

`TARGET_ID_RESOLVER_SQL` se incrusta textualmente en el trigger generado, dentro de un
`FOR v_target_id IN ( … ) LOOP`. Corre donde los pseudo-registros `NEW` / `OLD` de PostgreSQL están
disponibles, así que lee los campos de la fila modificada directamente. Puede devolver **0, 1 o N**
ids destino. Dos patrones canónicos:

**Patrón 1 — destino único e inmutable** (la FK al destino nunca cambia en un update):

```sql
SELECT COALESCE(NEW.c_order_id, OLD.c_order_id)
-- NEW en insert/update, OLD en delete
```

**Patrón 2 — reparenting** (la FK *puede* reasignarse en un update — una línea movida a otro
pedido). Un solo update es entonces un evento de **dos destinos**: el agregado del padre *viejo* ahora
está obsoleto (se fue un hijo) y el del *nuevo* también (llegó un hijo). Ambos deben recalcular:

```sql
SELECT NEW.c_order_id WHERE NEW.c_order_id IS NOT NULL
UNION
SELECT OLD.c_order_id WHERE OLD.c_order_id IS NOT NULL
```

- `UNION` (no `UNION ALL`) colapsa las dos filas en una cuando `NEW = OLD` (un update ordinario que
  no movió la línea), manteniendo la cola limpia.
- Los guards `IS NOT NULL` hacen que el mismo resolver sea seguro para INSERT (`OLD` es null) y
  DELETE (`NEW` es null).

> **Regla práctica:** mapeo inmutable → `COALESCE`. FK reasignable → forma `UNION` — de lo contrario
> cada update de reparenting corrompe uno de los agregados.

---

## 6. Encadenamiento: `Computation_Sequence_Number`

Una columna computada almacenada puede leer *otra* columna computada almacenada. El encadenamiento
funciona por **ordenamiento**, no por cascada: las propias escrituras de recálculo del motor están
suprimidas por el guard anti-recursión, así que una cadena solo funciona porque **ambas columnas son
ensuciadas de forma independiente por la misma escritura origen**, y el drenado las recalcula en
orden de `Computation_Sequence_Number` — primero la columna aguas arriba (número más bajo), para que
la de aguas abajo lea un valor aguas arriba *fresco*.

- Dale a la columna aguas arriba un número de secuencia **estrictamente menor** que la de aguas
  abajo.
- Números iguales **no** ordenan de forma determinista (los empates se rompen arbitrariamente) — el
  validador de build lo marca (regla **V17**).
- Un **ciclo** de dependencia entre columnas almacenadas es un error duro de build (regla **V14**).

---

## 7. Imposición de solo-lectura (cinturón y tiradores)

El valor almacenado debe ser **siempre** la salida de la función — nunca escrito por código de
aplicación, callouts, ni ediciones manuales. Solo-lectura se impone en *cada* capa, así ninguna capa
es el único guardián:

- **DAL / Hibernate** — la propiedad se mapea `insert="false" update="false"` **sin setter**
  (`Property.isStoredComputed()` alimenta a `DalMappingGenerator`). Un `save()` no emite SQL para la
  columna; una escritura accidental es un error de compilación.
- **`AD_Field.ReadOnlyLogic = 'Y'`** — propagado automáticamente por un paso Gradle/ModuleScript
  (`EnforceStoredComputedReadOnly`), un callout al crear el campo, y un `@OBDALEventHandler`
  (`ADFieldStoredComputedHandler`) en cada save, incluso los programáticos.
- **Pipeline de Schema Forge** — `resolve-curated.js` fuerza `readOnly` sin importar
  `decisions.json`; `push-to-neo.js` pone `Is_ReadOnly = true` en `ETGO_SF_FIELD`; el validador del
  pipeline bloquea cualquier campo del contrato respaldado por una columna computada almacenada que
  no sea de solo lectura.
- **UI React generada** — los campos computados almacenados se emiten como solo visualización, nunca
  como input.

---

## 8. Cómo configurar tu propia columna computada almacenada

Esto es lo que un autor de módulo realmente hace. (Todo lo de abajo es "declaración"; el core es
dueño de los triggers.)

### Paso 1 — Escribir la función de cómputo

Una función SQL que toma el id del registro destino y devuelve el valor:

```sql
CREATE OR REPLACE FUNCTION etscc_sumlineamounts(p_c_order_id VARCHAR)
RETURNS NUMERIC AS $$
  SELECT COALESCE(SUM(ol.linenetamt), 0)
  FROM   c_orderline ol
  WHERE  ol.c_order_id = p_c_order_id;
$$ LANGUAGE sql STABLE;   -- se exige IMMUTABLE/STABLE; VOLATILE se rechaza (V7)
```

Requisitos que impone el validador: aridad 1 (un único arg de id), un tipo de retorno compatible con
la referencia/tipo de dato de la columna, y **no** `VOLATILE` (sin efectos secundarios — la pasada
diferida debe ser una lectura-luego-escritura pura).

### Paso 2 — Marcar la `AD_Column` como computada almacenada

Sobre la columna destino (ej. `EM_ETSCC_LINETOTAL` en `C_Order`):

- `Computation_Mode = 'S'` (o `'Q'` / `'M'`)
- `Computation_Function = 'etscc_sumlineamounts'`
- `Refresh_Mode = 'S'`
- `Computation_Sequence_Number = 10` (subilo por encima de cualquier columna que esta lea)
- Dejá `SQLLogic` **vacío** (una columna almacenada con ambos es un error duro, V1)

### Paso 3 — Declarar las dependencias

Una fila `AD_COLUMN_COMP_DEPENDENCY` por cada tabla origen a la que debés reaccionar:

| Campo | Ejemplo | Notas |
|-------|---------|-------|
| `Source_Table_ID` | `C_OrderLine` | La tabla cuyos cambios disparan un refresco |
| `Insert_Event` / `Update_Event` / `Delete_Event` | Y / Y / Y | Qué eventos disparan |
| `Watched_Columns` | `LineNetAmt` (+ `QtyOrdered`) | Requerido para UPDATE; recalcula solo si una cambió |
| `Target_ID_Resolver_SQL` | `SELECT COALESCE(NEW.c_order_id, OLD.c_order_id)` | Mapea fila origen → id(s) destino; nunca debe devolver NULL |
| `SeqNo` | 10 | Orden de fila dentro del conjunto de dependencias de la columna |

Debe estar seteado exactamente **uno** de `Target_ID_Resolver_SQL` / `Target_Link_Column_ID`
(regla V11).

### Paso 4 — Desplegar

```bash
# desde la raíz de Etendo
./gradlew update.database     # valida, genera triggers, rellena filas existentes
```

`update.database` corre todo el pipeline: validación en build → generación de triggers → población
inicial de las filas existentes (ver §11).

### Paso 5 — Exportar de vuelta al módulo

```bash
./gradlew export.database      # persiste la config de AD en el src-db/ del módulo
```

Las filas de dependencia llevan un `AD_Module_ID` y se exportan/importan como cualquier otro dato del
diccionario. Los **triggers generados** (`ad_scd_*`) son propiedad del generador y están excluidos
del DB Source Manager — nunca los editás ni exportás a mano.

---

## 9. El módulo piloto — un ejemplo concreto

`com.etendoerp.storedcomputedcolumn` (v1.0.0) es el piloto. No contiene **nada de Java** — solo
configuración de AD + una suite de tests SQL — y ejercita todo el motor sobre la **cabecera del
Pedido de Venta** (`C_Order`):

| Etiqueta del campo | Columna | Fórmula | Modo de refresco |
|--------------------|---------|---------|------------------|
| **Line Total** | `EM_ETSCC_LINETOTAL` | `SUM(C_OrderLine.LineNetAmt)` del pedido | `S` — el piloto principal |
| **Line Total Queued** | `EM_ETSCC_LINETOTAL_Q` | la misma suma | `Q` — ejercita el camino async |
| **Average Price** | `EM_ETSCC_avg_price` | `SUM(LineNetAmt) / NULLIF(SUM(QtyOrdered),0)` | `S` |

- **Funciones de cómputo:** `ETSCC_SUMLINEAMOUNTS(p_c_order_id)` y `ETSCC_AVERAGE_PRICE(p_c_order_id)`.
- **Tabla origen:** `C_OrderLine`, resuelta de vuelta al pedido vía `C_Order_ID`.
- **Columnas vigiladas:** `LineNetAmt` (las tres) más `QtyOrdered` (Average Price también depende de
  la cantidad).
- **Eventos:** insert, update-de-columna-vigilada, delete.

Desde el punto de vista del usuario: editás la cantidad o el importe de una línea (o agregás/quitás
una línea) y guardás — el **Line Total** / **Average Price** de la cabecera se actualizan **al
instante** (columnas síncronas), mientras **Line Total Queued** se pone al día **en momentos** (la
cola async). Cambiar una columna *no vigilada* (ej. la descripción de una línea) no encola nada.

El módulo trae harnesses SQL basados en aserciones bajo `src-test/sql/` (escenarios del motor,
escenarios de cola, paridad Oracle, estrés a volumen, concurrencia) que prueban cada garantía contra
una base de datos real.

---

## 10. Soporte Oracle

El mecanismo **síncrono** es específico de PostgreSQL por diseño — depende de **constraint triggers**
`DEFERRABLE INITIALLY DEFERRED` (Oracle tiene constraints diferidas pero no *disparo de trigger*
diferido), más `pg_current_xact_id()`, `INSERT … ON CONFLICT`, índices parciales, `IS DISTINCT FROM`,
variables de sesión `SET LOCAL` e introspección de `pg_proc` — nada de lo cual tiene equivalente
directo en Oracle.

Entonces, en Oracle:

- Las columnas computadas almacenadas corren **solo** en modo `Q` (en cola) — una columna configurada
  `S` se trata como `Q`. `M` también se soporta; `S` se rechaza/normaliza en validación.
- Los triggers de dependencia son PL/SQL y usan `MERGE` para dedup en vez de `ON CONFLICT DO NOTHING`.
- El constraint trigger diferido **no** se crea; las filas sucias siempre van a la cola async.
- El **mismo procesador de cola Java** drena ambas plataformas; las mismas funciones de cómputo
  producen resultados idénticos. **Solo difiere el timing** — las columnas Oracle son eventualmente
  consistentes.
- La UI del campo de AD advierte sobre consistencia eventual cuando se selecciona `Q`.

La introspección de función/tipo-de-retorno/volatilidad (reglas V5–V7 del validador) se omite en
Oracle (solo existencia), y la detección de drift de trigger (V15) verifica presencia pero no cuerpo.

---

## 11. Operación de la funcionalidad

### Población inicial (primera activación)

Cuando el generador despliega los objetos `ad_scd_*` de una columna por primera vez, rellena las
filas existentes según el modo de refresco:

- **`S`** → se reconstruye inline durante `update.database`, **salvo** que la tabla destino supere
  `LARGE_TABLE_THRESHOLD` (100.000 filas) — por encima de eso loguea un WARN y encola un centinela en
  su lugar para no bloquear el build.
- **`Q`** → encola un **centinela nulo por cliente** que tenga filas en la tabla destino; la próxima
  corrida del procesador de cola de cada cliente hace el rebuild completo de ese cliente offline.
- **`M`** → nada; ejecutá **Rebuild Stored Column** cuando esté listo.

### El procesador de cola async (columnas `Q`)

Proceso de AD **Stored Computed Column Queue Processor** (`Value = StoredColumnQueueProcessor`,
`AD_Process_ID = D35DC63A8838412890AEE01D31CD70A3`). **No** se distribuye con una planificación
activa — cada instalación crea un **Process Request** (*General Setup → Process Scheduling → Process
Request*) con el intervalo que matchee su tolerancia al retraso. Parámetros: **Max Records** (tamaño
de lote, default 100) y **Retry Threshold** (fallos antes del dead-letter, default 5).

> **Corré exactamente un drainer por cliente.** La cola está particionada por `AD_Client_ID`; dentro
> de un cliente drena **serialmente** en orden de `Computation_Sequence_Number`, y ese ordenamiento
> es un requisito de corrección para las columnas encadenadas. Dos drainers concurrentes para el
> *mismo* cliente pueden reordenar una cadena y almacenar un valor obsoleto. Los drainers de clientes
> *distintos* **pueden** correr concurrentemente (particiones disjuntas). `PREVENTCONCURRENT='Y'` es
> un guard útil pero no una garantía (es local al nodo) — imponé uno-por-cliente operativamente.

El drenado `Q` **escala verticalmente** (lotes más grandes, corridas más frecuentes), nunca
horizontalmente — no agregues un segundo Process Request en paralelo.

### Manejo de fallos (dead-lettering)

Un fallo de recálculo por destino se aísla en su propio savepoint (el resto del lote igual
commitea); el `RETRY_COUNT` de la fila sucia incrementa, se guarda `ERROR_MSG`, y una vez que
`RETRY_COUNT` alcanza el **Retry Threshold** se manda a dead-letter (`IS_IGNORED = 'Y'`, logueado
como WARN) para que una fila envenenada no frene la cola. Un **cambio fresco en el origen** sobre esa
`(columna, destino)` limpia la fila ignorada y le da un reintento limpio.

```sql
SELECT ad_column_id, target_record_id, retry_count, error_msg
FROM   ad_storedcolumn_dirty
WHERE  is_ignored = 'Y'
ORDER  BY updated DESC;
```

### Reparación manual y verificación de consistencia

- **Rebuild Stored Column** (`Value = StoredColumnRebuild`,
  `AD_Process_ID = DA0CCF7EF06F46588AD5E7EF5073FC81`) → re-deriva cada fila destino desde las
  dependencias actuales a través del motor Java compartido `StoredColumnRecomputer.rebuild(...)` (**no**
  llama a la función PL/pgSQL `ad_scd_rebuild`, así que funciona en PostgreSQL *y* Oracle). Siempre
  seguro de re-ejecutar. Usalo tras corregir un resolver/función, para poblar columnas `M`, o para
  limpiar un backlog de dead-letters una vez arreglada la causa raíz. Recalcula solo las filas del
  **cliente que lo llama** — salvo un llamador **System** (`AD_Client_ID='0'`), que repara **todos**
  los clientes. Ver el FAQ (§15 P3) para la diferencia con llamar a `ad_scd_rebuild` directamente.
- **`ad_scd_check(<column_id>)`** → devuelve la cantidad de filas destino actualmente desincronizadas
  (valor almacenado ≠ valor recalculado). Usalo para confirmar que la cola se puso al día, ej. tras
  una importación masiva, o como chequeo de salud programado para detectar drift por una dependencia
  no declarada.

---

## 12. Validación en tiempo de build

Cada `update.database` corre `StoredComputedValidator` (vía el ModuleScript
`ValidateStoredComputedColumns`, y re-ejecutado como Gate 0 dentro de
`GenerateStoredComputedTriggers`) **antes** de cualquier DDL de trigger. Es **solo-lectura e
idempotente**. Una definición rota aborta el build *antes* de que pueda desplegar objetos de base de
datos inconsistentes. Los hallazgos se agregan en un único reporte (errores primero) lanzado como un
solo `BuildException`.

Reglas (HARD = aborta, SOFT = advierte):

| Regla | Chequeo | Severidad |
|-------|---------|-----------|
| V1 | La columna almacenada debe tener `SQLLogic` vacío | HARD |
| V2 | La columna almacenada debe tener `Computation_Function` | HARD |
| V3 | La columna almacenada debe tener `Computation_Sequence_Number > 0` | HARD |
| V4 | La función de cómputo debe existir en la BD | HARD |
| V5 | La aridad de la función debe ser 1 (arg string/tipo-ID) | HARD (aridad) / SOFT (tipo del arg) |
| V6 | Tipo de retorno compatible con la familia de referencia de la columna | HARD (void/trigger/record) / SOFT (desajuste) |
| V7 | La función debería ser `IMMUTABLE`/`STABLE`, no `VOLATILE` | SOFT |
| V8 | Columna almacenada activa debe tener ≥1 dependencia activa | HARD |
| V9 | Dependencia con evento update debe declarar ≥1 columna vigilada | HARD |
| V10 | La columna vigilada debe pertenecer a la tabla origen de la dependencia | HARD |
| V11 | La dependencia setea exactamente uno de `target_id_resolver_sql` / `target_link_column_id` | HARD |
| V14 | Sin ciclo de dependencia entre columnas almacenadas | HARD |
| V15 | Los triggers/funciones desplegados deben coincidir con la metadata actual | HARD (faltante) / SOFT (drift) |
| V16 | Las columnas FK/vigiladas deberían tener un índice de soporte | SOFT |
| V17 | En cada arista `A → B`, `seq[A] < seq[B]` estrictamente | SOFT |

El toggle `ETGO_SCD_VALIDATION` gobierna la imposición:

| Valor | Comportamiento |
|-------|----------------|
| `enforce` (default) | Las violaciones duras abortan `update.database`; las advertencias se loguean. |
| `warn` | **Todas** las violaciones se loguean como advertencias; el build continúa. **Solo escape de emergencia.** |

```bash
# Build one-off solo con warnings (no bloquea ante violaciones duras):
./gradlew update.database -DETGO_SCD_VALIDATION=warn
```

Orden de resolución: JVM `-DETGO_SCD_VALIDATION=…` → variable de entorno → default `enforce`. Usá
`warn` solo para desbloquear un build de emergencia o para relevar el backlog completo en una BD
heredada; restaurá `enforce` en cuanto se arreglen las definiciones — correr `warn` de forma
permanente anula el guard.

---

## 13. Objetos de base de datos generados (referencia)

Propiedad de `GenerateStoredComputedTriggers`; nunca se editan a mano, excluidos del DB Source
Manager. Cada uno lleva un bloque de comentario con `AD_Column_ID`, versión del generador y un hash
SHA-256 de la metadata de dependencias (la señal de obsolescencia para regeneración incremental).

| Objeto | Tipo | Rol |
|--------|------|-----|
| `ad_scd_dep_*` (por fila de dependencia) | fn PL/pgSQL + trigger AFTER sobre la tabla origen | Recolección de sucios de Fase 1 (chequeo de columna vigilada, resolver, insert de sucio) |
| `ad_scd_dirty_aiu` | CONSTRAINT trigger `DEFERRABLE INITIALLY DEFERRED` sobre `AD_STOREDCOLUMN_DIRTY` | Recálculo diferido de Fase 2 (`S`) |
| `ad_scd_process_dirty` | fn PL/pgSQL | El cuerpo de la pasada diferida: guard → drenado ordenado → cómputo → escritura `IS DISTINCT FROM` |
| `ad_scd_recompute` | fn PL/pgSQL | Recalcula una fila destino para una columna |
| `ad_scd_rebuild(<column_id>)` | fn PL/pgSQL (genérica, core) | Rebuild completo idempotente |
| `ad_scd_check(<column_id>)` | fn PL/pgSQL (genérica, core) | Cantidad de filas obsoletas |
| `my.scd_refreshing` | variable de sesión | Guard anti-recursión — los triggers de dependencia no hacen nada mientras está seteado |

### La tabla de sucios — `AD_STOREDCOLUMN_DIRTY`

Una fila = un registro destino de una columna que necesita recálculo. Columnas clave: `AD_Column_ID`,
`Target_Record_ID` (NULL = centinela "recalcular todas las filas de este `AD_Client_ID`"),
`Transaction_ID` (`pg_current_xact_id()`, NULL en Oracle), `Refresh_Mode` +
`Computation_Sequence_Number` (copiados de la columna al insertar), `Created`, `Retry_Count` /
`Error_Msg` / `Is_Ignored` (dead-lettering). Constraints de dedup:
`UNIQUE (AD_Column_ID, Target_Record_ID, Transaction_ID)` y un índice único parcial por cliente para
el centinela nulo.

### Archivos clave del core

| Archivo | Rol |
|---------|-----|
| `src-util/modulescript/.../GenerateStoredComputedTriggers.java` | Genera/despliega todos los objetos `ad_scd_*`; backfill; Gate 0/1 |
| `src-util/modulescript/.../StoredComputedValidator.java` | Validación de build V1–V17 (lógica pura compartida) |
| `src-util/modulescript/.../ValidateStoredComputedColumns.java` | Punto de entrada ModuleScript del gate de build |
| `src-util/modulescript/.../EnforceStoredComputedReadOnly.java` | Propaga `AD_Field.ReadOnlyLogic='Y'` |
| `src/org/openbravo/erpCommon/ad_process/StoredColumnQueueProcessor.java` | Drainer async `Q` (por cliente, serial) |
| `src/org/openbravo/erpCommon/ad_process/StoredColumnRebuild.java` | Proceso **Rebuild Stored Column** |
| `src/org/openbravo/erpCommon/ad_process/StoredColumnRecomputer.java` | Recalculador neutral al dialecto (`rebuild(con, columnId, clientId)`) |
| `src/org/openbravo/event/ColumnStoredComputedHandler.java` | Observer DAL en runtime (comparte lógica pura V1–V3/V14) |
| `src/org/openbravo/event/ADFieldStoredComputedHandler.java` | Impone solo-lectura en cada save de `AD_Field` |
| `src/org/openbravo/base/model/{Column,Property}.java`, `dal/core/DalMappingGenerator.java` | `isStoredComputed()` + mapeo `insert/update="false"` |

---

## 14. Elegir con criterio — cuándo *no* usarla

- **¿Necesita ser exacto a mitad de transacción?** Las columnas almacenadas reflejan el estado al
  **límite de la transacción** solamente. La lógica de negocio que necesite un valor fresco *durante*
  una transacción debe llamar a la función de cómputo directamente. (Las columnas `Q`/Oracle son solo
  *eventualmente* consistentes.)
- **¿El valor es barato de calcular y se lee poco?** Una columna virtual `SQLLogic` puede ser más
  simple — sin triggers, sin cola.
- **¿El cómputo tiene efectos secundarios?** No permitido — la función debe ser pura (`VOLATILE` se
  rechaza). La pasada diferida es una lectura-luego-escritura pura.
- **¿Origen con muchas escrituras, destino con pocas lecturas?** Pagarías overhead al commit en cada
  escritura origen por un valor que casi nadie lee. Considerá `Q`, o replanteá si conviene
  almacenarlo.

El motor deliberadamente **no** garantiza que toda columna computada sea buena candidata para
almacenamiento — la elegibilidad es una decisión de diseño, validada pero no asumida.

---

## 15. FAQ — Internals de PostgreSQL (`S`) y cálculo inicial

### P1. ¿Cómo funciona realmente el caso `S` (síncrono) a nivel PostgreSQL?

Dos conjuntos de objetos, ambos desplegados por `GenerateStoredComputedTriggers` durante
`update.database`:

**A. El motor estático** — desplegado una vez, de forma idempotente (`deployEngine`), compartido por
todas las columnas:

| Función / trigger | Rol |
|-------------------|-----|
| `ad_scd_recompute(column_id, target_id)` | Recalcula UNA fila destino. Resuelve la tabla/columna/función/PK física desde `AD_COLUMN`, toma un lock `FOR UPDATE` sobre la fila destino, y corre `UPDATE <tabla> SET <col> = <fn>(<pk>) WHERE <pk> = target`. La escritura es **incondicional** — sin guard `IS DISTINCT FROM` (guardar correría la agregación dos veces por fila; el chequeo de columna vigilada del trigger de enqueue ya filtra los cambios origen no-op aguas arriba). |
| `ad_scd_process_dirty()` | El orquestador del drenado diferido (abajo). |
| `ad_scd_rebuild(column_id)` | Rebuild completo — recalcula cada fila de la columna. |
| `ad_scd_check(column_id)` | Cantidad de filas obsoletas (`<col> IS DISTINCT FROM <fn>(<pk>)`). |
| `ad_scd_dirty_aiu` | `CREATE CONSTRAINT TRIGGER … AFTER INSERT ON ad_storedcolumn_dirty DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.refresh_mode = 'S') EXECUTE FUNCTION ad_scd_process_dirty()`. |

**B. Triggers de enqueue por dependencia** — una función `ad_scd_<depId>_trf()` + trigger AFTER sobre
cada tabla origen, declarados exactamente para los eventos configurados.

Secuencia de punta a punta para una columna `S`:

1. **DML de negocio sobre una fila origen** (ej. editar un `C_OrderLine`). El trigger AFTER de la
   dependencia dispara. En UPDATE chequea primero las columnas vigiladas; si ninguna cambió, vuelve
   de inmediato.
2. Corre `TARGET_ID_RESOLVER_SQL` y hace `INSERT … ON CONFLICT DO NOTHING` en
   `ad_storedcolumn_dirty`, estampando `transaction_id = pg_current_xact_id()`, `refresh_mode = 'S'`,
   y el `computation_sequence_number` de la columna.
3. Ese INSERT **arma** el constraint trigger diferido `ad_scd_dirty_aiu`. Como es
   `DEFERRABLE INITIALLY DEFERRED` no corre ahora — queda encolado para disparar **en el COMMIT**,
   una vez por fila sucia insertada.
4. **En el commit** dispara `ad_scd_process_dirty()`. El primer disparo:
   - chequea la GUC `my.scd_refreshing`; si ya está en `'true'`, vuelve de inmediato;
   - setea `my.scd_refreshing = 'true'` (local a la transacción, `set_config(…, true)`);
   - selecciona todas las filas sucias de **esta transacción** (`transaction_id =
     pg_current_xact_id()`) con `refresh_mode = 'S'` y destino no nulo, **ordenadas por
     `computation_sequence_number, target_record_id`**;
   - llama a `ad_scd_recompute(column, target)` por cada una;
   - hace `DELETE` de todas las filas sucias `S` procesadas de esta transacción.
5. Los disparos 2..N (de las otras filas sucias insertadas en la misma transacción) ven
   `my.scd_refreshing = 'true'` y vuelven de inmediato → toda la cola se drena **exactamente una vez
   por transacción**.
6. La misma GUC `my.scd_refreshing` es lo que evita que las propias escrituras `UPDATE` del motor
   sobre la tabla destino vuelvan a disparar los triggers de enqueue — sin recursión.

Todo corre en el **mismo proceso backend, misma transacción, en el commit** → el valor es
consistente transaccionalmente, y si la función de cómputo lanza un error, **toda la transacción de
negocio hace rollback**. Las columnas encadenadas funcionan solo porque ambas son ensuciadas por la
misma escritura origen y drenadas en orden de `computation_sequence_number` en el paso 4 — el motor
nunca cascadea sus propias escrituras.

### P2. Creé o actualicé una columna computada almacenada con `S` — ¿cómo hago el cálculo inicial?

Depende de si es una **primera activación** o una **actualización de una definición existente**.
`GenerateStoredComputedTriggers` (ejecutado automáticamente por `update.database`) solo puebla
automáticamente una columna en la **primera activación** — detectada cuando *ninguna* de sus
funciones de dependencia existía antes de esta corrida.

**Caso A — columna `S` nueva (primera activación).** Solo corré:

```bash
./gradlew update.database
```

El cálculo inicial sucede **automáticamente**:
- **≤ 100.000 filas destino** (`LARGE_TABLE_THRESHOLD`) → se reconstruye inline durante el build, vía
  `DO $$ … PERFORM ad_scd_rebuild('<column_id>') … $$` (con `my.scd_refreshing` seteado). No hay nada
  más que hacer.
- **> 100.000 filas** → **no** reconstruye inline (para no bloquear el build por mucho tiempo); loguea
  un WARN y encola un centinela nulo por cliente en su lugar. Completá la población corriendo una vez
  el **Stored Computed Column Queue Processor**, o un rebuild manual (abajo).

**Caso B — actualizaste una columna `S` existente** (cambiaste la función de cómputo, un resolver,
las columnas vigiladas, o corregiste un bug). Esto **no** es una primera activación, así que
`update.database` regenera los triggers pero **deja deliberadamente intactos los valores ya
almacenados** — no recalcula las filas existentes. De acá en más, los cambios origen nuevos mantienen
el valor fresco, pero las filas históricas siguen con el valor viejo. Para recalcular los datos
existentes tenés que disparar un rebuild **manualmente**:

- **Proceso de AD (recomendado en tenants multi-cliente):** ejecutá **Rebuild Stored Column**
  (`StoredColumnRebuild`). Es por cliente (un llamador System reconstruye todos los clientes); PG +
  Oracle.
- **SQL directo (dev / single-tenant / repair global como System):**
  ```sql
  SELECT ad_scd_rebuild('<AD_Column_ID>');   -- recalcula cada fila (idempotente, siempre seguro)
  SELECT ad_scd_check('<AD_Column_ID>');     -- ¿cuántas filas siguen obsoletas?
  ```
  ⚠️ `ad_scd_rebuild` recalcula **todas las filas de la tabla, sin filtro de cliente** — ver P3.

> **Regla práctica:** columna `S` *nueva* → `update.database` la puebla (inline si es < 100k filas).
> Definición `S` *modificada* → `update.database` solo refresca los triggers; corré vos mismo
> **Rebuild Stored Column** (o `ad_scd_rebuild(...)`) para rellenar las filas existentes.

### P3. ¿Qué hace `ad_scd_rebuild(<column_id>)` — y en qué difiere del proceso?

`ad_scd_rebuild` es la función **PL/pgSQL de rebuild completo** del motor (desplegada con el motor
estático). Recalcula y reescribe el valor almacenado de **todas las filas de la tabla destino** para
una columna:

```sql
CREATE OR REPLACE FUNCTION ad_scd_rebuild(p_column_id varchar)
RETURNS integer AS $$
DECLARE v_table varchar; v_pk varchar; v_id varchar; v_cnt integer := 0;
BEGIN
  -- 1. resuelve la tabla física + PK desde la metadata de AD_COLUMN / AD_TABLE
  SELECT lower(t.tablename),
         lower((SELECT k.columnname FROM ad_column k
                 WHERE k.ad_table_id = c.ad_table_id AND k.iskey = 'Y'))
    INTO v_table, v_pk
    FROM ad_column c JOIN ad_table t ON t.ad_table_id = c.ad_table_id
   WHERE c.ad_column_id = p_column_id;
  IF v_table IS NULL OR v_pk IS NULL THEN RETURN 0; END IF;        -- columna desconocida → no-op
  -- 2. itera CADA fila de la tabla destino
  FOR v_id IN EXECUTE format('SELECT %I::varchar AS id FROM %I', v_pk, v_table)
  LOOP
    PERFORM ad_scd_recompute(p_column_id, v_id);                   -- 3. recalcula fila por fila
    v_cnt := v_cnt + 1;
  END LOOP;
  RETURN v_cnt;                                                    -- 4. cantidad de filas tocadas
END;
$$ LANGUAGE plpgsql;
```

1. Busca la **tabla** física y la **clave primaria** de la columna en la metadata de AD; devuelve `0`
   si no puede resolver la columna (no-op).
2. Itera **todas** las filas de la tabla destino.
3. Por cada una llama a `ad_scd_recompute(column_id, target_id)` — que toma un lock `FOR UPDATE` sobre
   esa fila y escribe `UPDATE <tabla> SET <col> = <fn>(<pk>)` de forma incondicional.
4. Devuelve la cantidad de filas recalculadas. Idempotente — siempre seguro de re-ejecutar.

**Diferencia clave con el proceso de AD — scoping por cliente:**

| Vía | Cómo recalcula | Scope | Plataformas |
|-----|----------------|-------|-------------|
| `SELECT ad_scd_rebuild('<id>')` (SQL directo / psql) | esta función PL/pgSQL | **TODAS las filas del tenant — sin filtro de cliente** | Solo PostgreSQL |
| Proceso AD **Rebuild Stored Column** | `StoredColumnRecomputer.rebuild(...)` en **Java** (*no* llama a `ad_scd_rebuild`) | solo el **cliente que lo llama** (System → todos los clientes) | PostgreSQL + Oracle |

O sea, el proceso de AD va por el recalculador Java justamente para scopear por cliente y soportar
Oracle; la función SQL cruda `ad_scd_rebuild` toca cada fila de la tabla sin importar el cliente. En
un tenant multi-cliente preferí el **proceso de AD**; usá `ad_scd_rebuild` por psql para
dev / single-tenant, o para un repair global deliberado corriendo como **System**.

---

## 16. Referencias de origen

Los documentos de diseño/especificación de abajo viven en el checkout del **core de Etendo** bajo
`epl-1807/` (la carpeta de trabajo de la epic), no en este módulo:

- Especificación: `epl-1807/REQUIREMENTS.md`
- Operación async y toggle de validación: `epl-1807/OPERATIONS.md`
- Patrones de resolver (COALESCE vs UNION): `epl-1807/NOTES.md`
- Planes por fase: `epl-1807/PLAN-PHASE*.md`
- Módulo piloto: `com.etendoerp.storedcomputedcolumn` (README + `src-test/sql/`)
- Versión en inglés de esta guía: `STORED-COMPUTED-COLUMNS.md` (misma carpeta)

> **Nota sobre nombres.** El borrador original de `REQUIREMENTS.md` usaba nombres provisionales
> `sf_*` (`sf_rebuild`, `sf_check`, `sf.refreshing`, `AD_StoredColumn_Dirty`). La **implementación
> mergeada** usa `ad_scd_*` (`ad_scd_rebuild`, `ad_scd_check`), el guard `my.scd_refreshing`, y las
> tablas `AD_STOREDCOLUMN_DIRTY` / `AD_COLUMN_COMP_DEPENDENCY`. Esta guía usa los nombres finales,
> mergeados.
