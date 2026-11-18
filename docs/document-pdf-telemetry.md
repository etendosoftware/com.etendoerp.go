# Generated document PDFs telemetry (ETP-5993, OKR O4.KR2)

Counts every **effective** PDF of a document (a *comprobante*) and the template it was printed
with, so the two KPIs of the template personalization (etendo-ai#29) can be measured each month:

| KPI | Definition |
|---|---|
| **Documents generated per template per month** | Generated document PDFs per variant (`classic`, `compact`, `modern`). Previews excluded. |
| **Organizations using the templates per month** | Distinct organizations with at least one generated document PDF in the month, per variant and overall. |

The baseline published with these figures is `okr/o4-kr2-baseline-comprobantes.md`.

## What is counted

A row is one `document.pdf.generate` usage event in `ETGO_USAGE_EVENT` (the same table and
writer as every other usage event, `UsageEventRecorder`). The shape is enforced by
`DocumentPdfUsage`, whatever the caller sent:

| Column | Value |
|---|---|
| `AD_CLIENT_ID`, `AD_ORG_ID`, `AD_USER_ID`, `AD_ROLE_ID` | from the token (`OBContext`), never from the body |
| `EVENT_TYPE` | `document.pdf.generate` |
| `SOURCE` | `ui` (the app) or `mcp` (the server render) |
| `TARGET` | the document type: `sales-invoice`, `sales-order`, `sales-quotation`, `purchase-order`, `goods-shipment`, `payment-in`, `return-material-receipt`, `return-to-vendor-shipment` |
| `ACTION` | the channel: `download`, `print`, `email`, `server_render` |
| `OUTCOME` | always `ok` — a failed generation is not a generated document |
| `OCCURRED_AT` | when it was generated (client clock clamped to the last 24 h, as for every UI event) |
| `PROPERTIES` | `{"variant":"classic|compact|modern"}`, plus `"actor":"support"` for the Etendo support user |

**Never recorded:** the document id, number, date, amounts, business partner, file name, the
footer or the logo. `ERROR_CODE`, `DURATION_MS`, `COST_USD` and any other property the client
sends are discarded when the event is rebuilt. The list print (`listing`) is not a document and is
not accepted.

### Producers

| Channel | Producer | Where |
|---|---|---|
| `download`, `print`, `email` | the app, which renders the PDF in the browser (jsreport) | `POST /sws/neo/usage` → `NeoUsageEventEndpoint` → `DocumentPdfUsage.fromClient` |
| `server_render` | the server render for the MCP (ETP-6013), once the stored PDF is committed | `NeoPrintAfterComplete.render` → `DocumentPdfUsage.recordServerRender` |

The preview is **not** a channel: the preview panel and the preview cache (`GenericPreviewModal`,
`EM_ETGO_IsPreviewMain`) never report, and an event whose `action` is `preview`, or anything else
outside the list, is dropped and counted as `dropped` in the `202` answer. A client can never send
`server_render`. A MCP `etendo_send_document_email` of an already stored PDF generates nothing and
is not counted; when the PDF had to be rendered first, that render is the `server_render` row.

The server render prints the base design and applies no saved variant, so its rows carry
`classic`.

### The app's call

The app sends one event per generated document right after the PDF is produced (a multi-select
print of N documents sends N events, in one request of up to 50):

```http
POST /sws/neo/usage
Authorization: Bearer <NEO token>
Content-Type: application/json

{"events":[{
  "eventType": "document.pdf.generate",
  "target": "sales-invoice",
  "action": "download",
  "properties": {"variant": "modern"},
  "occurredAt": "2026-11-03T10:15:00Z",
  "sessionKey": "<app session>",
  "appVersion": "<build>"
}]}
```

`properties.variant` is the variant the app applied. When it is missing or not one of the three,
the backend uses the organization's saved one (`ETGO_DOC_TEMPLATE_CONFIG`, the same lookup the app
makes) or `classic` when none is saved; if that lookup fails the event is dropped rather than
guessed. Wiring these calls into the app's download, print and email paths is the frontend side of
this ticket (`schema_forge`, `documentPdfRegistry.js`, `DocumentPrintDrawer`, `SendDocumentModal`).

## Monthly aggregation

Background process **Document PDF Monthly Report** (`ETGO_DocumentPdfMonthlyReport`,
`DocumentPdfMonthlyReportProcess`), System access level, read-only. One optional parameter,
**Month** (any day of the month); without it the previous calendar month is reported, so a Process
Request scheduled monthly (e.g. day 1 at 06:00) produces each month's figures. The result message
and the log (`Document PDF monthly report`) carry the report as Markdown, ready to paste into the
published baseline:

- totals: documents generated, unique organizations, unique tenants;
- per template: documents and unique organizations;
- per document type × template;
- per channel.

An organization is the pair tenant + organization. Rows of the Etendo support user are excluded,
as in every usage aggregation. Months follow the server's calendar, as the daily usage aggregation
does.

The query (`DocumentPdfMonthlyReader.MONTH_SQL`), for an audit run straight on the database:

```sql
select e.ad_client_id, e.ad_org_id, e.target, e.action,
       coalesce(cast(e.properties as jsonb) ->> 'variant', '') as variant, count(*)
  from etgo_usage_event e
 where e.event_type = 'document.pdf.generate'
   and e.occurred_at >= date '2026-11-01' and e.occurred_at < date '2026-12-01'
   and e.outcome = 'ok'
   and coalesce(cast(e.properties as jsonb) ->> 'actor', '') <> 'support'
 group by 1, 2, 3, 4, 5;
```

### Audit queries

```sql
-- KPI 1: documents per template
select cast(properties as jsonb) ->> 'variant' as variant, count(*)
  from etgo_usage_event
 where event_type = 'document.pdf.generate'
   and occurred_at >= date '2026-11-01' and occurred_at < date '2026-12-01'
   and coalesce(cast(properties as jsonb) ->> 'actor', '') <> 'support'
 group by 1 order by 1;

-- KPI 2: unique organizations, overall and per template
select cast(properties as jsonb) ->> 'variant' as variant,
       count(distinct ad_client_id || '|' || ad_org_id) as organizations
  from etgo_usage_event
 where event_type = 'document.pdf.generate'
   and occurred_at >= date '2026-11-01' and occurred_at < date '2026-12-01'
   and coalesce(cast(properties as jsonb) ->> 'actor', '') <> 'support'
 group by rollup (1);

-- Privacy check: no row may carry anything but the variant (and the support marker)
select count(*) from etgo_usage_event
 where event_type = 'document.pdf.generate'
   and (error_code is not null or duration_ms is not null or cost_usd is not null
        or (cast(properties as jsonb) - 'variant' - 'actor') <> '{}'::jsonb);
```

The last query must answer `0`; it is the evidence that the dataset holds no document data.

## Accuracy and limits

- The app reports after it produced the PDF; a report lost on the way (tab closed, network down)
  is a document not counted. The writer drops rows only under sustained overload or at shutdown
  (`UsageEventRecorder`), never silently: both are logged.
- A user can inflate their own count by calling the endpoint by hand, as with every UI usage event
  (D3); the per-user rate limits bound it. Acceptable for a product KPI, not for billing.
- `usage.events.enabled=false` in `Openbravo.properties` turns every usage event off on that
  instance, these included.
