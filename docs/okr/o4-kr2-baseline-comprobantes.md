# Baseline O4.KR2 — Comprobantes generados por plantilla

Informe de baseline del uso de las plantillas de comprobantes (clásica, compacta y moderna) de la
idea etendo-ai#29, *Personalización de PDF de usuarios*. Jira: ETP-5993 (T8).

| | |
|---|---|
| **Estado** | Instrumentación entregada. **Cifras pendientes** del primer mes productivo completo tras el despliegue. |
| **Período del baseline** | Primer mes natural completo con la instrumentación en producción (backend y app desplegados). |
| **Fuente** | `ETGO_USAGE_EVENT`, eventos `document.pdf.generate` del entorno de producción. |
| **Cálculo** | Proceso *Document PDF Monthly Report* (`ETGO_DocumentPdfMonthlyReport`), nivel System. |
| **Detalle técnico** | `../document-pdf-telemetry.md` |

## Definiciones

- **Comprobante generado:** un PDF de un comprobante descargado, impreso o enviado por email desde
  la app, o generado y guardado por el servidor para el MCP. **Las previsualizaciones no cuentan.**
  Un intento fallido no cuenta.
- **Plantilla:** la variante con la que se imprimió (`classic`, `compact`, `modern`). La
  generación del servidor imprime siempre el diseño base (`classic`).
- **Organización usuaria:** organización (entorno + organización) con al menos un comprobante
  generado en el mes. Se cuenta una vez aunque genere con varias plantillas.
- **Excluido:** el usuario de soporte de Etendo, la impresión de listados y cualquier dato del
  comprobante (número, importes, tercero, fecha del documento, fichero): no se registran.

## Cómo se obtiene

1. En el entorno de producción, como *System Administrator*: **Process Request** → proceso
   *Document PDF Monthly Report* → **Month** = cualquier día del mes del baseline → ejecutar.
   (Sin mes, informa el mes natural anterior; programarlo mensualmente el día 1 da la serie.)
2. Copiar el Markdown del mensaje del proceso (también en el log, línea
   `Document PDF monthly report`) en la sección *Resultados* de este documento.
3. Ejecutar las consultas de auditoría de `../document-pdf-telemetry.md` sobre el mismo mes y
   adjuntar el resultado como dataset auditado. La comprobación de privacidad debe devolver `0`.
4. Publicar este documento actualizado como evidencia de O4.KR2.

## Resultados

> **Pendiente.** Se completa con la salida del proceso para el primer mes productivo. Hasta que
> la instrumentación esté en producción no existen eventos que agregar: no se publican cifras
> estimadas.

### Resumen

| Indicador | Valor |
|---|---:|
| Comprobantes generados (sin previsualizaciones) | — |
| Organizaciones únicas | — |
| Entornos (tenants) únicos | — |

### Por plantilla

| Plantilla | Comprobantes | Organizaciones únicas |
|---|---:|---:|
| classic | — | — |
| compact | — | — |
| modern | — | — |
| **Total** | — | — |

### Por tipo de comprobante y plantilla

| Tipo de comprobante | classic | compact | modern | Total |
|---|---:|---:|---:|---:|
| sales-invoice | — | — | — | — |
| sales-order | — | — | — | — |
| sales-quotation | — | — | — | — |
| purchase-order | — | — | — | — |
| goods-shipment | — | — | — | — |
| payment-in | — | — | — | — |
| return-material-receipt | — | — | — | — |
| return-to-vendor-shipment | — | — | — | — |

### Por canal

| Canal | Comprobantes |
|---|---:|
| download | — |
| print | — |
| email | — |
| server_render | — |

## Evidencias

- Dataset auditado: resultado de las consultas de auditoría del mes (adjuntar).
- Comprobación de privacidad: `0` filas con datos fuera de `variant` / `actor` (adjuntar).
- Salida del proceso *Document PDF Monthly Report* del mes (adjuntar).
