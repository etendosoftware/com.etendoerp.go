# Como com.etendoerp.copilot usa com.etendoerp.openapi

## Resumen

Copilot integra con el modulo OpenAPI de dos formas:

1. **Documentacion de endpoints** - Registra los endpoints REST de Copilot en el spec OpenAPI global
2. **Generacion de tools para agentes** - Usa specs OpenAPI como fuente de conocimiento para generar herramientas ejecutables que los agentes LLM pueden invocar

---

## 1. Registro de endpoints: OpenAPIDoc

**Archivo:** `com.etendoerp.copilot/src/com/etendoerp/copilot/hook/OpenAPIDoc.java`

Implementa la interfaz `OpenAPIEndpoint` del modulo openapi. Se activa cuando el tag es "copilot" (case-insensitive).

### Endpoints que registra

| Path | Metodo | Descripcion |
|---|---|---|
| `/sws/copilot/transcription` | POST | Transcribir audio a texto (multipart/form-data) |
| `/sws/copilot/assistants` | GET | Listar asistentes disponibles para el usuario |
| `/sws/copilot/aquestion` | GET | Hacer pregunta via query params |
| `/sws/copilot/question` | POST | Hacer pregunta via JSON body |
| `/sws/copilot/cacheQuestion` | POST | Guardar pregunta en sesion |
| `/sws/copilot/file` | POST | Subir archivo (multipart/form-data) |
| `/sws/copilot/configCheck` | POST | Verificar conectividad y configuracion |
| `/sws/copilot/structure` | GET | Obtener estructura de un asistente |

Todos taggeados como "Copilot" en el spec.

---

## 2. Generacion de specs como archivos de conocimiento: OpenAPISpecFlowFile

**Archivo:** `com.etendoerp.copilot/src/com/etendoerp/copilot/hook/OpenAPISpecFlowFile.java`

Implementa `CopilotFileHook`. Se ejecuta cuando un `CopilotFile` de tipo "FLOW" se crea o actualiza.

### Flujo de ejecucion

1. Detecta que el CopilotFile tiene tipo "FLOW" y un OpenApiFlow asociado
2. Llama a `OpenAPIController.getOpenAPIJson()` pasando el nombre del flow
3. Agrega al spec una nota especial para Copilot:
   > "To use this API with Copilot, it's necessary to use the literal string 'ETENDO_TOKEN' as Bearer token in all the requests."
4. Escribe el JSON a un archivo temporal y lo adjunta como knowledge base file

Esto permite que los agentes de Copilot "aprendan" APIs definidas en OpenAPI flows.

---

## 3. Tool generation desde specs OpenAPI (Python)

**Archivo:** `com.etendoerp.copilot/copilot/core/toolgen/openapi_tool_gen.py`

Funcion principal: `generate_tools_from_openapi(openapi_spec)`

### Que hace

- Parsea un spec OpenAPI (el JSON generado en el paso anterior)
- Por cada path + metodo, genera una funcion Python ejecutable
- Crea modelos Pydantic para validacion de tipos (params, body, response)
- Maneja autenticacion automaticamente:
  - Endpoints de Etendo: usa el placeholder `ETENDO_TOKEN`
  - APIs externas: acepta token como parametro opcional
- Nombra los tools como `{METHOD}{PathTitleCased}` (ej: `POSTSwsCopilotQuestion`)

### Manejo especial para Etendo Headless

- Convierte `_startRow`/`_endRow` a `startRow`/`endRow`
- Agrega documentacion de query operators
- Omite parametros internos con prefijo `_`
- Para PUT/POST usa `exclude_unset=True` (comportamiento PATCH-like)

---

## 4. Configuracion en base de datos

Copilot define estos registros en las tablas del modulo openapi:

### Flow
- **Copilot** (ID: `37FF96E51341486BB25AF7EB15BE6C44`) - `OPEN_SWAGGER=N`

### Requests
| Nombre | Tipo | Descripcion |
|---|---|---|
| Agents | ETRX_Tab | Lista de agentes |
| AgentAccess | ETRX_Tab | Gestion de acceso por rol |

### Flowpoints
| Flow | Request | Metodos habilitados |
|---|---|---|
| Copilot | Agents | Solo GET |
| Copilot | AgentAccess | Solo POST |

### Foreign key en ETCOP_FILE
La tabla `ETCOP_FILE` tiene una FK a `ETAPI_OPENAPI_FLOW`, permitiendo asociar archivos de Copilot con flows de OpenAPI.

---

## 5. Patron de integracion

```
com.etendoerp.openapi                    com.etendoerp.copilot
┌─────────────────────┐                  ┌──────────────────────────┐
│ OpenAPIEndpoint     │◄─── implements ──│ OpenAPIDoc               │
│ (interface)         │                  │ (registra 8 endpoints)   │
├─────────────────────┤                  ├──────────────────────────┤
│ OpenAPIController   │◄─── usa ────────│ OpenAPISpecFlowFile      │
│ .getOpenAPIJson()   │                  │ (genera spec → file)     │
├─────────────────────┤                  ├──────────────────────────┤
│ OpenApiFlow         │◄─── FK ─────────│ CopilotFile (ETCOP_FILE) │
│ OpenApiFlowPoint    │                  │ (tipo FLOW)              │
├─────────────────────┤                  ├──────────────────────────┤
│ ETAPI_OPENAPI_FLOW  │◄─── data ───────│ sourcedata XML            │
│ ETAPI_OPENAPI_REQ   │                  │ (flow + requests)        │
└─────────────────────┘                  ├──────────────────────────┤
                                         │ openapi_tool_gen.py      │
                                         │ (spec → tools LLM)       │
                                         └──────────────────────────┘
```

El ciclo completo:
1. Se define un flow en OpenAPI (via BD o UI)
2. Se crea un CopilotFile tipo FLOW asociado a ese flow
3. El hook genera el spec JSON y lo adjunta como knowledge file
4. El tool_loader de Python carga el spec
5. openapi_tool_gen convierte cada operacion en un tool ejecutable
6. El agente LLM puede invocar esos tools para llamar a la API
