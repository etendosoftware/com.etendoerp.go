# Payments API — `/sws/go/payments/v1` (ETP-6322)

The versioned API through which the ERP that issues Etendo's own invoices learns about every
Stripe charge, issues the legal invoice, mails it to the customer and reports back.

- **One payment per paid Stripe invoice with an amount above zero**: a new purchase, a renewal or
  the proration of a plan change. A free first month or a full discount is no payment.
- Recorded by the payment webhook (`POST /sws/go/checkout/webhook`) **in the same transaction**
  that applies the `invoice.paid` event. If the row cannot be written, the webhook fails and
  Stripe delivers the event again: a payment is never acknowledged without being kept. Only
  charges made after this was deployed are recorded; there is no backfill.
- **Out of scope:** refunds and disputes (corrective invoices).

Code: `com.etendoerp.go.payment.invoicing` — `StripeInvoicePayment` (reads the event),
`PaymentMethodReader` (asks Stripe for the card), `PaymentLedger` (rules), `PaymentLedgerStore`
(SQL), `PaymentStatus` (states), `PaymentsApiServlet` (wire). Keys: `com.etendoerp.go.common.ApiKeyRing`,
shared with the signup API. Tables: `ETGO_PAYMENT`, `ETGO_PAYMENT_EVENT`.

## Decisions (2026-11-17)

- **Plan prices are without VAT.** The base of the invoice is `amountPaid`. `taxAmount` is only
  what Stripe reports (empty while Stripe computes no tax); computing VAT is the ERP's job, and is
  deliberately not done here yet.
- **Billing email = the account's email**, else the address Stripe billed.
- **The ERP decides** whether an invoice may be simplified (no customer data, up to 400 € VAT
  included for Spanish customers) or must be complete. Fiscal data are **not** required at
  purchase and there is no waiting state: every payment is `pending_invoice` at once, with
  whatever fiscal data exist.
- **The fiscal snapshot** (`fiscalData`) is one per payment, so a renewal carries the data of its
  own date. It is taken when the payment is recorded, **refreshed on every read while the payment
  has no invoice number**, and **frozen by the `PATCH` that reports the invoice number**: from then
  on it is the snapshot that invoice was issued with. The first payment of an annual plan usually
  arrives before the environment exists; its snapshot is empty until the company is created and
  the ERP reads it again.

## Authentication: API keys

Every call carries `X-Api-Key: <key>`, server to server only (no CORS). Keys are configured as
`etgo.payments.api.keys` (system property) or `ETGO_PAYMENTS_API_KEYS` (environment):

```
ETGO_PAYMENTS_API_KEYS=erp:<sha256 hex>
```

Same format, hashing and rotation as the signup API (`signup-api.md`), but **its own ring**: a
signup key does not open this API. The key's name is recorded on every event the caller reports.
No key configured: every call answers `401 INVALID_API_KEY`.

## Endpoints

| Call | What |
|---|---|
| `GET /payments?status=&limit=&after=` | A page of payments, oldest first. `status`: `pending_invoice`, `invoiced`, `mail_sent`, `completed` or `error` (omitted: all). `limit`: 1–200, default 50. `after`: the `nextCursor` of the previous page. |
| `GET /payments/{id}` | One payment, with its history in `events`. |
| `PATCH /payments/{id}` | `{"invoiceNumber":"F2026-0001"}`. The first number moves the payment to `invoiced` and freezes its fiscal snapshot. The same number again is a no-op (200); another number is `409 INVOICE_NUMBER_CONFLICT`. |
| `POST /payments/{id}/events` | `{"event":"invoiced|mail_sent|completed|error","step":"…","reason":"…"}`. `step` and `reason` are required for `error`. |

The ERP's loop: list `pending_invoice` (and `error`, to retry), issue the invoice, `PATCH` its
number, mail it, `POST mail_sent`, then `POST completed`.

### List answer

```json
{ "data": [ { …payment… } ], "hasMore": true, "nextCursor": "<payment id>" }
```

The cursor is keyset (created, id): a payment that changes state while the ERP pages through
`pending_invoice` does not shift the pages.

### A payment

```json
{
  "id": "…", "status": "pending_invoice", "paymentType": "renewal",
  "billingReason": "subscription_cycle", "accountId": "…", "billingEmail": "owner@example.com",
  "environmentClientId": "…", "currency": "EUR",
  "amountPaid": "45.00", "subtotal": "45.00", "taxAmount": "0.00", "totalAmount": "45.00",
  "paidAt": "2026-11-17T10:00:00Z", "periodStart": "…", "periodEnd": "…",
  "lines": [ { "description": "1 × Pro", "quantity": 1, "amount": "45.00",
               "priceId": "price_…", "periodStart": "…", "periodEnd": "…" } ],
  "fiscalData": { "name": "…", "legalName": "…", "taxId": "B12345678", "address1": "…",
                  "address2": null, "postalCode": "…", "city": "…", "region": "…",
                  "countryCode": "ES", "country": "Spain" },
  "fiscalDataAt": "…",
  "paymentMethod": { "type": "card", "brand": "visa", "last4": "4242", "wallet": "apple_pay",
                     "receiptUrl": "https://pay.stripe.com/…", "chargeId": "ch_…" },
  "invoiceNumber": null, "invoicedAt": null,
  "stripe": { "invoiceId": "in_…", "invoiceNumber": "…", "subscriptionId": "sub_…",
              "customerId": "cus_…" },
  "created": "…", "updated": "…"
}
```

- Amounts are decimal strings with the currency's decimals, never floats.
- `paymentType`: `new` (`subscription_create`), `renewal` (`subscription_cycle`), `plan_change`
  (`subscription_update`) or `other`.
- `fiscalData` is `null` while the account has no company; each field is `null` when blank. The
  company is the tenant's legal entity, else its first organization (the one with a tax id first).
- `paymentMethod` is asked to Stripe while recording. A failing or slow Stripe never fails the
  webhook: the payment is then recorded with `paymentMethod: null`.
- `GET /payments/{id}` adds `events`: `[{ "event", "step", "reason", "channel", "at" }]`, oldest
  first, starting with `recorded`.

## States

```
pending_invoice ─(number)─▶ invoiced ─▶ mail_sent ─▶ completed (final)
  any state but completed ─error─▶ error ─(the failed step, again)─▶ forward
```

| Refusal | When |
|---|---|
| `409 INVOICE_NUMBER_MISSING` | `invoiced`, `mail_sent` or `completed` before the `PATCH` with the number. |
| `409 INVALID_TRANSITION` | A step back (e.g. `invoiced` after `mail_sent`). |
| `409 PAYMENT_COMPLETED` | Anything after `completed`. |

Reporting the state the payment already has is accepted again (a retried call) and recorded.

## Errors

`{ "error": { "code", "message", "field" } }` — callers branch on `code`; `field` only when it
applies; `message` is English, for logs.

| Status | Code |
|---|---|
| 400 | `INVALID_REQUEST` (bad `status`, `limit`, body, `invoiceNumber`, `event`, `step`, `reason`) |
| 401 | `INVALID_API_KEY` |
| 404 | `NOT_FOUND` (path), `PAYMENT_NOT_FOUND` |
| 405 | `METHOD_NOT_ALLOWED` |
| 409 | `INVOICE_NUMBER_CONFLICT`, `INVOICE_NUMBER_MISSING`, `INVALID_TRANSITION`, `PAYMENT_COMPLETED` |
| 500 | `PAYMENTS_SERVER_ERROR` (details only in the server log, never fiscal data) |

## Local testing

With `stripe listen --forward-to localhost:<port>/etendo/sws/go/checkout/webhook` and the
listener's secret in `etendo.go.checkout.webhook.secret`, a test-mode purchase or
`stripe trigger invoice.paid` records a row. Then:

```bash
curl -H "X-Api-Key: <key>" "localhost:<port>/etendo/sws/go/payments/v1/payments?status=pending_invoice"
```
