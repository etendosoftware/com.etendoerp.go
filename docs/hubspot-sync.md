# HubSpot sync (ETP-5847, ETP-6082)

Marketing follows every self-serve signup in HubSpot, from the confirmed account through the trial
to a paying customer or a lost deal, and then every paying environment's subscription. Etendo GO reports the account lifecycle events and
the Stripe billing events to the HubSpot CRM API.

- **Account and demo events** move the account's contact and its deal in the **Self-Serve**
  pipeline. Only accounts created by the app's own registration (`/sws/go/register`, SSO) get
  them. An account created through the signup API (`SIGNUP_CHANNEL` set) is marketing's: marketing
  creates its contact and deal itself. The exception is a **booked demo**: the signup API call
  names the deal marketing created for the booking, and the sync moves that deal in the **Demo**
  pipeline from the moment the demo took place (see [Booked demos](#booked-demos)).
- **Billing events** reach every account with a deal, the signup API's included. They land on the
  deal of the productive environment the subscription pays for.

Code: `com.etendoerp.go.hubspot`.

## The pipeline

Marketing defined each stage of the Self-Serve pipeline (ETP-6082, 2026-11-05):

| Stage | Id | Reached when | Event |
|---|---|---|---|
| MQL — Alta la cuenta | `1471483196` | the account exists and its address is confirmed | `ACCOUNT_CREATED` |
| SQL — Cuenta activada | `1471483197` | the owner used the ERP: the first qualification milestone | `MILESTONE` |
| Oportunidad — Tarjeta añadida | `1471504904` | the demo became a productive on a monthly plan (a paid checkout) | `PAID` |
| Cliente — Primer cobro pagado | `1471504905` | the first charge above zero was paid. A monthly plan's free first month is a zero invoice, which is never reported, so its first charge is the first renewal; an annual plan pays the year up front | `PAYMENT`, `PAID` (annual) |
| Prueba vencida sin tarjeta | `1471504906` | 14 days passed and no productive was created | `DEMO_EXPIRED` |
| Cliente perdido | `1471504907` | the subscription ended: cancelled or not renewed (paid before or not) | `CANCELED` |

**A stage only moves forward.** Each move names the stages it may leave, and the deal is read
first, so a late or repeated event never takes it back:

| Move to | Only from |
|---|---|
| SQL | MQL |
| Tarjeta añadida | MQL, SQL, Prueba vencida (an owner who pays after the trial ended) |
| Primer cobro pagado | MQL, SQL, Tarjeta añadida, Prueba vencida; Cliente perdido only on a re-subscription or a withdrawn cancellation |
| Prueba vencida | MQL, SQL |
| Cliente perdido | any other |

**A re-subscription is the one way out of Cliente perdido.** "Reactivar suscripción" (ETP-5742)
pays a new subscription for the cancelled productive itself, so its invoices land on the same deal.
A `PAYMENT` of an environment whose `CANCELED` was already sent earlier is that re-subscription: it
sets `etendo_es_reactivacion=true` on the deal (on any deal, marketing's too; HubSpot uses it to
send the welcome-back flow instead of the standard onboarding) and, on a deal the sync moves,
takes it from Cliente perdido straight to Primer cobro pagado: a charge above zero was just paid
(marketing's spec, ETP-6082). Later renewals follow the usual moves.

## What is sent

| Event | When | HubSpot |
|---|---|---|
| `ACCOUNT_CREATED` | the owner confirms the address: the welcome mail's login link (`/session/link`, the usual path since ETP-6081), the confirmation link (`/verify-email`), or a later SSO sign-in on that address. An SSO sign-up at once, since its provider vouched for it | contact upserted by email: `firstname`/`lastname` (the account name split at its first space), `etendo_camino_entrada=self_serve`, `phone` and `sector` when the account has them. Deal `{account name} — Self-serve` in MQL, with `etendo_account_id`, `etendo_camino_entrada_deal=self_serve` and `etendo_estado_suscripcion=trial`, associated with the contact |
| `DEMO_READY` | the onboarding creates the demo (`markDemoReady`) | contact: `etendo_inicio_prueba`, `etendo_fin_prueba` (`yyyy-MM-dd`, UTC), `country`. The deal is renamed `{company} — Self-serve` and gets the company (`name`, `country`), unless it has one. An account confirmed before ETP-6082 has no deal yet: it is created here, in MQL. A booked demo's deal only gets `etendo_account_id` (see [Booked demos](#booked-demos)) |
| `DEMO_ENTERED` | first entry into the demo | contact: `etendo_prueba_activada=si`. The deal does not move: entering is not using |
| `MILESTONE` | each qualification milestone, once (see below) | deal to SQL, and the milestone's date property |
| `PAID` | a paid checkout of the account after its demo, also after the trial ended | `etendo_plan` (the plan's `ETGO_PLAN.VALUE`) and `etendo_periodicidad` (the bought price's `ETGO_PLAN.BILLING_INTERVAL`) on any deal; on one the sync moves, the stage to Tarjeta añadida, or to Primer cobro pagado when the plan is annual |
| `DEMO_EXPIRED` | the trial ends (the access policy answers `DEMO_TRIAL_EXPIRED`) and the account has no productive | deal to Prueba vencida |

- **`phone`** is `ETGO_ACCOUNT.PHONE` read when the event is sent (ETP-6081): one value with its
  prefix, `+34 600123456`. The register form asks for it, optionally. An SSO sign-up has none. The
  sync never clears it in HubSpot.
- **`sector`** is `ETGO_ACCOUNT.SECTOR`, the required "Sector de actividad" of the register form.
  An account without one (an SSO sign-up never sees that form) is asked it in the onboarding's
  company step, with the same list: the session's `account.sectorMissing` shows the field, and
  `POST /onboarding` stores it on the account before provisioning, so the demo's `DEMO_READY`
  carries it. A sector already chosen is never overwritten.
  It holds the internal values of HubSpot's enumeration (`industria`, `distribución_logística`,
  `servicios`, `startups_tecnología`, `otro`), which marketing keeps fixed; the form shows
  translated labels. Any other value is dropped, by the register endpoint and again by the sender,
  because HubSpot would refuse the whole contact. Listed in `HubSpotOutbox.SECTORS` and
  `onboarding/businessSectors.js` in `etendo-go-core`: keep both in step.
- **`country`** (HubSpot's standard "Country/Region", on the contact and the company) is the Spanish
  name of the country the owner entered at onboarding ("España", from its ISO code), as the portal's
  existing records have it; the contact also gets the code in `hs_country_region_code` ("ES"). A
  demo reported before ETP-6082 has no code and sends the stored country name instead. Not `ip_country`: HubSpot keeps
  it read-only for its own IP geolocation and answers 400 `READ_ONLY_VALUE` for the whole contact.

### Qualification milestones

Detected in the demo, each one reported once (`EVENT_KEY` = the milestone) with the date it was
first reached. The first one moves the deal to SQL.

| Milestone | Reached when | Date property (`datetime`) |
|---|---|---|
| `import` | an import finished with rows (an asynchronous `ETGO_BATCH_JOB` in `SUCCEEDED`/`PARTIAL`: only the import dialog starts one) | `etendo_fecha_primera_importacion` |
| `business_partner` | a business partner created | `etendo_fecha_primer_tercero` |
| `product` | a product created | `etendo_fecha_primer_producto` |
| `order` | an order created (sales or purchase) | `etendo_fecha_primer_pedido` |
| `invoice` | an invoice created | `etendo_fecha_primera_factura` |
| `invoice_completed` | an invoice completed (its last update) | `etendo_fecha_primera_factura_completada` |
| `invoice_posted` | an invoice posted (its last update) | `etendo_fecha_primera_factura_contabilizada` |

- **Only what the owner did counts.** A row counts when it is newer than the demo (the
  `DEMO_READY` row) and is not mapped in `AD_REF_DATA_LOADED`. The onboarding dataset and the
  optional sample data are both loaded by `DataImportService` (not as a client import), which maps
  every row it inserts there, so a demo created with sample data is not activated by it: its
  partners, products, orders and completed, posted invoices count for nothing. The sample data is
  imported after the demo is reported ready, so the date alone would not exclude it.
- **Each milestone dates the deal**: marketing created the date properties as `datetime` deal
  properties on 2026-11-05, and each gets the instant the milestone was first reached (ISO 8601,
  UTC). A later milestone only writes its date: the deal is already past MQL.

### Billing events

From the Stripe payment webhook, on the deal of the environment the subscription pays for. Dates
are `yyyy-MM-dd`, UTC.

| Event | Stripe event | Deal |
|---|---|---|
| `PAYMENT` | `invoice.paid` with an amount; a zero total (a free month, a full discount) is no payment and is not reported (ETP-6082) | `etendo_estado_suscripcion=active`, `etendo_fecha_ultimo_pago`, `amount`, `etendo_periodicidad` (`monthly`/`annual`). On a deal the sync moves, also `etendo_plan`, and Primer cobro pagado: every reported payment is a charge above zero, so the first one is the customer's first payment, whichever invoice it is. After the environment's `CANCELED`, also `etendo_es_reactivacion=true` and the re-subscription move above |
| `PAYMENT_FAILED` | `invoice.payment_failed`, each attempt | `etendo_estado_suscripcion=suspended`, `etendo_fecha_cobro_fallido`. A failed **first** charge never provisions its productive: it lands on the account's deal instead of waiting for one |
| `CANCEL_REQUESTED` | `customer.subscription.updated` that schedules a cancellation (`cancel_at_period_end` or `cancel_at`) | `etendo_estado_suscripcion=cancelled` from the moment it is asked, and `etendo_fecha_baja` = the day the owner loses access (`cancel_at`, else the end of the current period; the day asked if Stripe gives neither). The stage does not move yet: the owner keeps access until then |
| `CANCEL_REVERTED` | `customer.subscription.updated` that withdraws it | `etendo_estado_suscripcion=active`, `etendo_fecha_baja` emptied; a deal in Cliente perdido goes back to Primer cobro pagado |
| `CANCELED` | `customer.subscription.deleted` | `etendo_estado_suscripcion=cancelled`, and Cliente perdido on a deal the sync moves, whether it ever paid or not; `etendo_fecha_baja` (the day it ended) only if the deal has none, so a scheduled cancellation keeps the end of access it already set |

- **A recovered charge** (Stripe's retry succeeds) is a `PAYMENT`, so the state goes back to
  `active`.
- **The interval** comes from the invoice line's price, or, on API versions that only name the
  price, from the length of the billed period (300 days or more is annual).
- **One deal per productive environment.** The first productive of an account takes the account's
  deal: the one its demo created, or else the one marketing created with `etendo_account_id`. Each
  further productive gets its own deal, `{environment} — Self-serve`, in the first deal's pipeline
  (Self-Serve, or Demo for a booked demo), created in Tarjeta añadida (Primer cobro pagado when its
  event is a payment or annual) and associated with the contact and the company of the first one. The decision is recorded as a
  `PRODUCTIVE_DEAL` row, already `SENT`, whose `HUBSPOT_OBJECT_ID` is the deal.
- **`etendo_account_id` is unique in HubSpot** (portal setting), so only the account's first deal
  carries it. A further productive's deal has none: it is found again (on a retry) by its name and
  its contact, and its later events through the `PRODUCTIVE_DEAL` row.
- **Stages move only on the deals of the Self-Serve and Demo pipelines**, each with its own stage
  ids. A deal in any other pipeline only gets its properties written, `PAID`'s plan and interval
  included.
- **An account with no deal anywhere** (a customer from before the deploy) has its billing events
  `SKIPPED`.

### Common to every event

- **`etendo_account_id` links HubSpot to Etendo.** It is the `ETGO_ACCOUNT_ID`.
- **`etendo_plan` is a closed list** in HubSpot: `start, core, pro, scale, enterprise`. The
  production plan keys must use the same values. Another key is left out of the call (logged),
  because HubSpot would refuse the whole update.
- **`lifecyclestage` is never sent** (marketing's spec, ETP-6082). HubSpot derives the contact's
  lifecycle stage (MQL, SQL, Oportunidad, Cliente) from its deal's stage.
- **A contact may get a second company.** HubSpot associates every contact with the company of its
  email domain, creating that company if needed (portal setting). The company of the signup form is
  still the one associated with the deal.

### Booked demos

A demo booked on the website follows the **Demo** pipeline (`952778001`). Marketing creates the
contact and the deal when the demo is booked, and on the demo's day the website calls the signup
API (`POST /accounts`, see [signup-api.md](signup-api.md)) with the deal's id in `hubspotDealId`.
Marketing moves the deal up to "MQL — Demo realizada"; the sync takes it from there.

| Stage | Id | Moved by |
|---|---|---|
| Demo agendada | `1471455755` | marketing (the booking) |
| Cuenta creada | `1471455756` | marketing |
| MQL — Demo realizada | `1471455757` | marketing, after the demo |
| SQL — Cuenta activada | `1471455758` | the sync: first qualification milestone, only from Demo realizada |
| Oportunidad — Tarjeta añadida | `1471455759` | the sync, as in Self-Serve |
| Cliente — Primer cobro pagado | `1471455760` | the sync, as in Self-Serve |
| Prueba vencida sin tarjeta | `1471455761` | the sync, only from Demo realizada or SQL |
| Cliente perdido | `1471455762` | the sync, as in Self-Serve |

- **The call records the deal.** `POST /accounts` with `hubspotDealId` writes a `DEMO_DEAL` row,
  already `SENT`, whose `HUBSPOT_OBJECT_ID` is the deal. It marks the account as a booked demo's.
- **No contact or deal is created.** When the environment is ready, `DEMO_READY` reads the deal
  (an id that names no deal is a final `FAILED`) and writes `etendo_account_id` on it. Every later
  event finds the deal through that id, as on Self-Serve. The contact gets the trial dates and the
  country, but not `etendo_camino_entrada`: its way in is marketing's.
- **Only the stages after the demo are the sync's.** A milestone moves the deal to SQL only from
  "Demo realizada": the account is created an hour before the demo and used live during it, so
  what happens before marketing marks the demo done does not qualify the deal.
- **The rest is Self-Serve's:** the detected events (entry, milestones, payment, expiry) and the
  billing events, each move read in the deal's own pipeline.

## How it works

```
registration / onboarding ──(same transaction)──▶ ETGO_HUBSPOT_OUTBOX (PENDING)
Stripe payment webhook ─────(same transaction)──▶        ▲
HubSpot Sync process, every minute ── detect ────────────┘  first entry, milestones, payment, expired trial
                                   └─ send ─────────────▶ HubSpot API ──▶ SENT / retry / FAILED
```

- **Reported events.** `ACCOUNT_CREATED` and `DEMO_READY` are written by business code
  (`HubSpotOutbox`) in the transaction of the change itself, so the event exists exactly when the
  confirmed account or the demo does. An unconfirmed sign-up is not a lead: the register endpoint
  stores the account (with its phone and sector) and the event waits for the confirmation. Each
  confirming path reports it only when a confirmation was pending, so an old account signing in
  is never reported.
  - The row is written in plain JDBC inside a savepoint.
  - If the write fails, only the event is lost, with an error in the log. The registration or
    onboarding carries on.
- **Billing events** are written by the Stripe webhook (`HubSpotOutbox.stripeEvent`) in the
  transaction of the event, before it is applied. If applying fails, the row is rolled back with
  it and Stripe's redelivery adds it again.
  - The account is the purchase's: the `request_id` Etendo put in the subscription's metadata, else
    the subscription id recorded on `ETGO_CHECKOUT_REQUEST`. An event of no Etendo purchase is not
    recorded.
  - The environment is decided when the row is sent, from the purchase that bought the
    subscription. The first payment usually arrives before its environment exists: it is retried
    until the environment is provisioned.
- **Detected events.** The process detects the others for every demo reported ready that has not
  paid yet. A demo whose trial expired stays watched for 90 days, so a late payment still moves
  its deal.
  - *Entered:* a session (`ETGO_GO_SESSION.CTX_CLIENT_ID`) or a `session.login` usage event on the
    demo.
  - *Milestones:* see above. Not queried again once all seven are reported.
  - *Paid:* an `ETGO_CHECKOUT_REQUEST` of the account with `PAID_AT` at or after the demo. Checked
    before the expiry, so a demo that paid is never reported expired.
  - *Expired:* `EnvironmentAccessPolicy`, the same answer the paywall and the trial notices give.
    The demo stays alive after its owner bought a productive, and expires on schedule; that is no
    lost trial, so it is reported only when the account has no productive at all (the demo carries
    no `ETGO_AssociatedProductiveClientId`, and no purchase of the account created an environment).
- **One row per account, event type and `EVENT_KEY`** (unique index), so an event detected or
  reported twice is sent once. The demo events happen once per account (`EVENT_KEY = '-'`), a
  milestone once per milestone; a billing event is keyed by its Stripe invoice (`PAYMENT`), event (`PAYMENT_FAILED`, cancellation
  changes) or subscription (`CANCELED`).
- **Late billing events.** Stripe does not deliver in order. A billing event older (`OCCURRED_AT`)
  than one of the same environment already sent is `SKIPPED`, so a late failure never overwrites
  a later payment.
- **Order.** An account's rows go oldest first. A row that cannot be sent yet holds back that
  account's later rows, so a deal is never moved before it exists.
- **Retries.**
  - Throttling (429), outages (5xx, no answer) and auth problems (401/403) are retried after 1, 2,
    4… minutes, up to 6 hours apart.
  - After 12 attempts (about a day and a half), the row is given up as `FAILED`.
  - Any other refusal (400, 404…) is final: `FAILED`, with HubSpot's answer in `LAST_ERROR`.
- **Repeat-safe.** The contact is upserted by email. A deal is looked up by `etendo_account_id`
  before one is created, so a retry after a half-finished send does not duplicate it.
- **The company is a courtesy.** If HubSpot refuses it (a token without the companies scope), the
  deal is created without one.

## Configuration

Read as a system property, then `Openbravo.properties`, then the environment
(`GoRuntimeProperties`):

| Property | Environment | Default |
|---|---|---|
| `etendo.go.hubspot.token` | `ETGO_HUBSPOT_TOKEN` | none: **without it nothing is sent**, and events stay `PENDING` until one is set |
| `etendo.go.hubspot.api.base.url` | `ETGO_HUBSPOT_API_BASE_URL` | `https://api.hubapi.com` |
| `etendo.go.hubspot.pipeline` | `ETGO_HUBSPOT_PIPELINE` | `952774980` (Self-Serve, portal 52086088) |
| `etendo.go.hubspot.stage.account` | `ETGO_HUBSPOT_STAGE_ACCOUNT` | `1471483196` (MQL — Alta la cuenta) |
| `etendo.go.hubspot.stage.activated` | `ETGO_HUBSPOT_STAGE_ACTIVATED` | `1471483197` (SQL — Cuenta activada) |
| `etendo.go.hubspot.stage.card` | `ETGO_HUBSPOT_STAGE_CARD` | `1471504904` (Oportunidad — Tarjeta añadida) |
| `etendo.go.hubspot.stage.first.payment` | `ETGO_HUBSPOT_STAGE_FIRST_PAYMENT` | `1471504905` (Cliente — Primer cobro pagado) |
| `etendo.go.hubspot.stage.expired` | `ETGO_HUBSPOT_STAGE_EXPIRED` | `1471504906` (Prueba vencida sin tarjeta) |
| `etendo.go.hubspot.stage.lost` | `ETGO_HUBSPOT_STAGE_LOST` | `1471504907` (Cliente perdido) |
| `etendo.go.hubspot.demo.pipeline` | `ETGO_HUBSPOT_DEMO_PIPELINE` | `952778001` (Demo) |
| `etendo.go.hubspot.demo.stage.entry` | `ETGO_HUBSPOT_DEMO_STAGE_ENTRY` | `1471455757` (MQL — Demo realizada) |
| `etendo.go.hubspot.demo.stage.activated` | `ETGO_HUBSPOT_DEMO_STAGE_ACTIVATED` | `1471455758` (SQL — Cuenta activada) |
| `etendo.go.hubspot.demo.stage.card` | `ETGO_HUBSPOT_DEMO_STAGE_CARD` | `1471455759` (Oportunidad — Tarjeta añadida) |
| `etendo.go.hubspot.demo.stage.first.payment` | `ETGO_HUBSPOT_DEMO_STAGE_FIRST_PAYMENT` | `1471455760` (Cliente — Primer cobro pagado) |
| `etendo.go.hubspot.demo.stage.expired` | `ETGO_HUBSPOT_DEMO_STAGE_EXPIRED` | `1471455761` (Prueba vencida sin tarjeta) |
| `etendo.go.hubspot.demo.stage.lost` | `ETGO_HUBSPOT_DEMO_STAGE_LOST` | `1471455762` (Cliente perdido) |

Production needs the token alone: the defaults are its pipelines. A test portal has its own
pipeline and stage ids. Read them with `GET /crm/v3/pipelines/deals`.

**When marketing changes the pipeline**, every id here changes, and HubSpot refuses each deal
created or moved with an old one (400, `FAILED`). Then the account has no deal and its later events
are `SKIPPED` without a warning. This happened on 2026-10-29: change the defaults (or set the
variables), then requeue the account's `FAILED` and `SKIPPED` rows (see Operations).

The token is a HubSpot private app token. It needs these scopes:
- `crm.objects.contacts.read`/`write`
- `crm.objects.deals.read`/`write`
- `crm.objects.companies.read`/`write`, for the company

## Operations

- **Schedule the process once per environment.** In Classic, *Process Request* → *HubSpot Sync*
  (System client), every minute. Production is updated by delta and never restarted, so nothing
  schedules it automatically.
- **Inspect the outbox:**

  ```sql
  select event_type, status, attempt_count, next_attempt_at, last_error, hubspot_object_id
    from etgo_hubspot_outbox where etgo_account_id = '<id>' order by created;
  ```

  - For `DEMO_READY`, `PRODUCTIVE_DEAL`, `DEMO_DEAL` and (from ETP-6082) `ACCOUNT_CREATED`,
    `HUBSPOT_OBJECT_ID` is the deal's id. An `ACCOUNT_CREATED` sent before ETP-6082 holds the
    contact's.
  - To retry a `FAILED` or `SKIPPED` row after fixing the cause, set it back to `PENDING` with
    `attempt_count = 0` and `next_attempt_at = null`. Requeue the account's `DEMO_READY` too, so
    its deal exists before the later rows are sent again: the rows keep their order.
- **Accounts from before the deploy** have no `DEMO_READY` row. They get no deal, their later demo
  events are not detected, and their billing events are `SKIPPED`.
- **Several Tomcats.** Each one would run the process. Keep it scheduled on one: two concurrent
  passes could send the same row twice.
