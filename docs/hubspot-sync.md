# HubSpot sync (ETP-5847)

Marketing follows every self-serve signup in HubSpot, from the account to a won or lost deal, and
then every paying environment's subscription. Etendo GO reports the account lifecycle events and
the Stripe billing events to the HubSpot CRM API.

- **Account and demo events** move the account's contact and its deal in the **Self-serve**
  pipeline. Only accounts created by the app's own registration (`/sws/go/register`, SSO) get
  them. An account created through the signup API (`SIGNUP_CHANNEL` set) is marketing's: marketing
  creates its contact and deal itself.
- **Billing events** reach every account with a deal, the signup API's included. They land on the
  deal of the productive environment the subscription pays for.

Code: `com.etendoerp.go.hubspot`.

## What is sent

| Event | When | HubSpot |
|---|---|---|
| `ACCOUNT_CREATED` | the account is created (registration or first SSO login) | contact upserted by email: `firstname`/`lastname` (the account name split at its first space), `etendo_camino_entrada=self_serve`, `lifecyclestage=lead` |
| `DEMO_READY` | the onboarding creates the demo (`markDemoReady`) | contact: `etendo_inicio_prueba`, `etendo_fin_prueba` (`yyyy-MM-dd`, UTC). Company: `name`, `country`, associated with the contact. Deal `{company} — Self-serve` in the trial stage, with `etendo_account_id`, `etendo_camino_entrada_deal=self_serve` and `etendo_estado_suscripcion=trial`, associated with the contact and the company |
| `DEMO_ENTERED` | first entry into the demo | contact: `etendo_prueba_activada=si`; deal to the active stage, unless it is already won or lost |
| `PAID` | a paid checkout of the account after its demo | deal to the won stage, with `etendo_plan` = the plan's `ETGO_PLAN.VALUE` |
| `DEMO_EXPIRED` | the trial ends unpaid (the access policy answers `DEMO_TRIAL_EXPIRED`) | deal to the lost stage |

### Billing events

From the Stripe payment webhook, on the deal of the environment the subscription pays for. Dates
are `yyyy-MM-dd`, UTC.

| Event | Stripe event | Deal |
|---|---|---|
| `PAYMENT` | `invoice.paid`, every one (a zero total from a coupon too) | `etendo_estado_suscripcion=active`, `etendo_fecha_ultimo_pago`, `amount`, `etendo_periodicidad` (`monthly`/`annual`). On a deal the sync created, also the won stage and `etendo_plan` |
| `PAYMENT_FAILED` | `invoice.payment_failed`, each attempt | `etendo_estado_suscripcion=suspended`, `etendo_fecha_cobro_fallido` |
| `CANCEL_REQUESTED` | `customer.subscription.updated` that schedules a cancellation (`cancel_at_period_end` or `cancel_at`) | `etendo_fecha_baja` = the day the owner asked (`canceled_at`). The state stays `active`: the owner keeps access until the period ends |
| `CANCEL_REVERTED` | `customer.subscription.updated` that withdraws it | `etendo_estado_suscripcion=active`, `etendo_fecha_baja` emptied |
| `CANCELED` | `customer.subscription.deleted` | `etendo_estado_suscripcion=cancelled`; `etendo_fecha_baja` only if the deal has none, so a scheduled cancellation keeps the day it was asked |

- **A recovered charge** (Stripe's retry succeeds) is a `PAYMENT`, so the state goes back to
  `active`.
- **The interval** comes from the invoice line's price, or, on API versions that only name the
  price, from the length of the billed period (300 days or more is annual).
- **One deal per productive environment.** The first productive of an account takes the account's
  deal: the one its demo created, or else the one marketing created with `etendo_account_id`. Each
  further productive gets its own deal, `{environment} — Self-serve`, created won and associated
  with the contact and the company of the first one. The decision is recorded as a
  `PRODUCTIVE_DEAL` row, already `SENT`, whose `HUBSPOT_OBJECT_ID` is the deal.
- **`etendo_account_id` is unique in HubSpot** (portal setting), so only the account's first deal
  carries it. A further productive's deal has none: it is found again (on a retry) by its name and
  its contact, and its later events through the `PRODUCTIVE_DEAL` row.
- **Stages move only on the deals the sync created** (the Self-serve pipeline). A marketing deal
  only gets its properties written.
- **An account with no deal anywhere** (a customer from before the deploy) has its billing events
  `SKIPPED`.

### Common to every event

- **`etendo_account_id` links HubSpot to Etendo.** It is the `ETGO_ACCOUNT_ID`.
- **`etendo_plan` is a closed list** in HubSpot: `start, core, pro, scale, enterprise`. The
  production plan keys must use the same values. Another key is left out of the call (logged),
  because HubSpot would refuse the whole update.
- **`lifecyclestage` is set only once**, at sign-up. HubSpot does not move it backwards. HubSpot
  itself then moves it forward: `opportunity` once the contact has an open deal, `customer` once the
  deal is won (seen in portal 52086088, 2026-10-22).
- **A contact may get a second company.** HubSpot associates every contact with the company of its
  email domain, creating that company if needed (portal setting). The company of the signup form is
  still the one associated with the deal.

## How it works

```
registration / onboarding ──(same transaction)──▶ ETGO_HUBSPOT_OUTBOX (PENDING)
Stripe payment webhook ─────(same transaction)──▶        ▲
HubSpot Sync process, every minute ── detect ────────────┘  first entry, payment, expired trial
                                   └─ send ─────────────▶ HubSpot API ──▶ SENT / retry / FAILED
```

- **Reported events.** `ACCOUNT_CREATED` and `DEMO_READY` are written by business code
  (`HubSpotOutbox`) in the transaction of the change itself, so the event exists exactly when the
  account or the demo does.
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
- **Detected events.** The process detects the other three for every demo reported ready whose
  deal is still open:
  - *Entered:* a session (`ETGO_GO_SESSION.CTX_CLIENT_ID`) or a `session.login` usage event on the
    demo.
  - *Paid:* an `ETGO_CHECKOUT_REQUEST` of the account with `PAID_AT` at or after the demo.
  - *Expired:* `EnvironmentAccessPolicy`, the same answer the paywall and the trial notices give.
- **One row per account, event type and `EVENT_KEY`** (unique index), so an event detected or
  reported twice is sent once. The demo events happen once per account (`EVENT_KEY = '-'`); a
  billing event is keyed by its Stripe invoice (`PAYMENT`), event (`PAYMENT_FAILED`, cancellation
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
| `etendo.go.hubspot.pipeline` | `ETGO_HUBSPOT_PIPELINE` | `944761479` (Self-serve, portal 52086088) |
| `etendo.go.hubspot.stage.trial` | `ETGO_HUBSPOT_STAGE_TRIAL` | `1458221379` (Prueba activa) |
| `etendo.go.hubspot.stage.active` | `ETGO_HUBSPOT_STAGE_ACTIVE` | `1458221380` (Prueba activada) |
| `etendo.go.hubspot.stage.won` | `ETGO_HUBSPOT_STAGE_WON` | `1458221381` (Ganado — añadió tarjeta) |
| `etendo.go.hubspot.stage.lost` | `ETGO_HUBSPOT_STAGE_LOST` | `1458221382` (Perdido — venció sin pago) |

A test portal has its own pipeline and stage ids. Read them with
`GET /crm/v3/pipelines/deals`.

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

  - For `DEMO_READY` and `PRODUCTIVE_DEAL`, `HUBSPOT_OBJECT_ID` is the deal's id.
  - To retry a `FAILED` row after fixing the cause, set it back to `PENDING` with
    `attempt_count = 0`.
- **Accounts from before the deploy** have no `DEMO_READY` row. They get no deal, their later demo
  events are not detected, and their billing events are `SKIPPED`.
- **Several Tomcats.** Each one would run the process. Keep it scheduled on one: two concurrent
  passes could send the same row twice.
