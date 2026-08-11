# Design — Etendo GO provider as a core `EmailSender` (SPI bridge)

- **Date:** 2026-08-10
- **Module:** `com.etendoerp.go`
- **Depends on:** ETP-4216 (core `EmailSender` SPI — done, `etendo_core`)
- **Jira:** to be created (see *Ticket* below)

## Problem

ETP-4216 added a pluggable `EmailSender` SPI to core and shipped only the SMTP
implementation. Its *Out of scope* section reads:

> The etendo.go `EmailSender` implementation and its gateway raw/template bridge
> (delivered by the go module).

That piece was never delivered and no follow-up ticket exists. Consequence: every core
ERP email is still SMTP-only. In an environment where the client has no SMTP
configuration, those sends fail or are skipped even though a working email transport (the
GO provider: API Gateway → Lambda → Amazon SES) is configured and in daily use for
document sends.

Two independent stacks exist today and they do not know about each other:

| Path | Entry point | Transport | Uses the core SPI? |
|---|---|---|---|
| GO / NEO flows | `TransactionalEmailService` | API Gateway → SES | No |
| Core ERP emails | `EmailManager` → `EmailSenderDispatcher` | SMTP only | Yes |

Only two `EmailSender` implementations exist in the whole workspace, and **both are
SMTP**: `DefaultSmtpEmailSender` (core) and `TbaiEmailSender` (`com.smf.ticketbai`, fixed
`smtp.gmail.com` endpoint with the module's own credentials). The SPI has no gateway
transport at all.

### Concrete trigger

`com.smf.currency.conversionrate`'s failure alert (`SyncFailureEmailSender`) never
delivers on a System-scheduled downloader. `SmtpCascadeResolver` and
`CurrencyConverter.getSelectedConfig()` both filter by the context's client, and the
process request runs as client `0`, where no SMTP configuration exists. The send is
skipped with a WARN and nothing reaches the configured recipient.

## Goal

Deliver a single `EmailSender` in `com.etendoerp.go` that routes core ERP emails through
the existing GO provider when — and only when — no SMTP configuration applies.

**Non-goal:** changing `com.smf.currency.conversionrate`. It needs no modification; the
dispatcher already selects the transport for it.

## Dependency direction

Currency and every other core caller stay unaware of this module:

```
com.smf.currency.conversionrate ──▶ etendo_core (SPI) ◀── com.etendoerp.go
```

Discovery is runtime CDI (`WeldUtils.getInstances(EmailSender.class)`), so there is no
compile-time dependency and no `AD_MODULE_DEPENDENCY` entry from any caller to
`com.etendoerp.go`. With the go module absent, `DefaultSmtpEmailSender` is selected and
behavior is exactly what it is today.

## Design

### Component

One new class implementing `com.etendoerp.email.spi.EmailSender`:

```
src/com/etendoerp/go/schemaforge/email/spi/GoProviderEmailSender.java
```

A dedicated `spi` subpackage rather than the existing `email` package, for one concrete
reason: `com.etendoerp.go.schemaforge.email` already contains its own `EmailSendContext`,
which would shadow the core SPI class of the same name and be picked up silently on an
unqualified reference. From a subpackage, neither is implicitly in scope, so both must be
imported or qualified deliberately.

The dependencies this needs are all public: `EmailProviderAdapter` (interface),
`ApiGatewayEmailProviderAdapter` (class and its no-arg constructor) and the
`EmailProviderRequest` constructors.

Named after the *provider*, not after SES: the transport Etendo talks to is the API
Gateway endpoint. Whether the Lambda behind it uses SES is not Etendo's concern and can
change without touching this class.

**CDI scope:** `@ApplicationScoped`, matching both existing senders. The
`@Named`-only rule documented for `NeoHandler` does **not** apply here — `NeoHandler`
lookup reads `@Named` off the concrete class, which a Weld client proxy does not carry,
whereas this SPI is discovered *by type*. A normal scope is correct.

### Selection: `isConfigured(context)`

Four conditions, evaluated in order:

| # | Condition | Rationale |
|---|---|---|
| 1 | `providerAdapter.isConfigured()` | Property gate. `EmailProviderConfig.fromRuntime()` already resolves `etendo.go.email.provider.enabled` / `.baseUrl` / `.apiKey` from Java properties, Openbravo properties or env vars. |
| 2 | `context.getResolvedSmtpConfig() == null` and `context.getSmtpConfig() == null` | Fallback semantics: if SMTP applies, stay out of the way. |
| 3 | `context.getEmail() == null` → return `true` | This is the capability probe from `EmailSenderDispatcher.hasAlternativeSenderConfigured()`, which builds `EmailSendContext.create(null, null, null)`. Reporting `true` here is what lets callers past their pre-send guard. |
| 4 | No attachments and no BCC | Only accept what the provider payload can represent (see *Transport limits*). |

Deliberately **not** gated on an AD Preference. `Preferences.getPreferenceValue` needs a
client/org/user, and both the probe (null context) and System-scheduled background
processes (client `0`) would resolve to "disabled". A server-side property is
context-free and works identically in both cases.

Condition 2 also avoids re-running the SMTP cascade: core callers resolve it before
calling `EmailManager.sendEmail(...)`, so the answer is already in the context. No extra
DB query, no coupling to `SmtpCascadeResolver`.

### Priority: `50`

Must sit strictly between the two existing senders:

- **Below `TbaiEmailSender` (100)** — otherwise this sender would steal TicketBAI's
  rejection alert and the module's own mailbox would never be used.
- **Above `DefaultSmtpEmailSender` (`Integer.MIN_VALUE`)** — so it wins when SMTP does
  not apply.

### Delivery: `send(context)`

Maps core's `EmailInfo` onto `EmailProviderRequest` and calls
`ApiGatewayEmailProviderAdapter.send(request)`:

| Provider field | Source |
|---|---|
| recipients (`to`, `cc`) | `email.getRecipientTO()`, `email.getRecipientCC()`, honoring `adapter.supportsMultipleRecipients()` |
| `template` | `"custom"` — the bring-your-own-content template (`DefaultDocumentSendEmailContract.CONTENT_TEMPLATE`), already on the provider allowlist |
| `data` | `{ subject, body }` from `email.getSubject()` and `email.getContent()` |
| `replyTo` | `email.getReplyTo()` |

A non-successful `EmailProviderResponse` must throw. Per the dispatcher's contract the
exception propagates unchanged and there is **no** retry through SMTP — that would risk a
double send on a transient gateway failure.

### Transport limits

`EmailProviderRequest.toProviderPayload()` emits exactly `to`, `cc`, `template`, `data`
and `replyTo`. Fields core can carry that the provider cannot represent:

| Field | Handling |
|---|---|
| `attachments` | **Declines the send** (condition 4) → falls back to SMTP. Never silently dropped. |
| `recipientBCC` | **Declines the send** (condition 4). |
| `from` / `fromName` | Ignored — the provider sends from its own verified identity. |
| `sentDate`, `headerExtras` | No payload slot; dropped. |

Documents delivered through the GO stack do not attach the PDF either: they carry a
signed `data.download_link` produced by `DocumentDownloadTokenService`. Reproducing that
for arbitrary core attachments is out of scope.

### Resulting flow

```
SyncFailureEmailSender → EmailManager.sendEmail(null, email)
  └─ EmailSenderDispatcher
       ├─ TbaiEmailSender (100)        → declines (ThreadLocal inactive)
       ├─ GoProviderEmailSender (50)   → selected: no SMTP, no attachments
       │    └─ ApiGatewayEmailProviderAdapter → API Gateway → SES
       └─ DefaultSmtpEmailSender (MIN) → not reached
```

## Blast radius

Five call sites reach the dispatcher. Behavior changes **only** where the GO provider is
configured *and* no SMTP configuration applies — that is, only where the send is already
failing today.

| Caller | Attachments | Changes? |
|---|---|---|
| `EmailUtilities` — legacy Print/Email | Always ≥1 (report PDF) | No — declines, identical to today |
| `AlertProcess` — alerts | None | Yes — from failing to delivered via the provider |
| `EmailEventManager` — password reset, portal access | Per event generator | Yes, for generators that attach nothing |
| `SyncFailureEmailSender` — conversion rate downloader | None | Yes — the trigger for this work |
| `ErrorEmailSender` — TicketBAI | None | Only when TBAI's own credentials are missing; otherwise TBAI still wins at priority 100 |

The riskiest path (legacy Print/Email, which mails invoices with a PDF) is excluded
automatically by the attachment rule rather than by a special case.

No double dispatch: `DefaultSmtpEmailSender` and `TbaiEmailSender` call the low-level
multi-argument `EmailManager` overload, which does not re-enter the dispatcher.

Any environment with SMTP configured, or without the GO provider configured, is
byte-identical to today.

## Rollout risks

The risk is not regression — it is **activation**. Emails that currently die silently
start being delivered. Two checks before enabling this in a shared environment:

1. **Is SES in sandbox mode?** Sandbox only delivers to verified addresses, which is an
   effective containment net here. Production mode delivers anywhere. This is configured
   on the Lambda / SES side, not in Etendo.
2. **What addresses does the environment's database hold?** If a restored dump carries
   real customer addresses, password-reset and alert emails would reach real people from
   a test environment. Either leave the provider disabled there or rely on the sandbox.

Neither blocks implementation; both belong to enabling the feature.

## Testing

Unit tests, with a constructor overload accepting an `EmailProviderAdapter` so a fake can
be injected. `EmailProviderAdapter` is a public interface, so the double is a plain
implementation of it — no need to reach the adapter's package-private
config-plus-transport constructor, which a subpackage could not access anyway.

`isConfigured`:
- provider not configured → `false`
- resolved SMTP config present in context → `false`
- `EmailServerConfiguration` present in context → `false`
- email with attachments → `false`
- email with BCC → `false`
- null email (capability probe) → `true`
- configured, no SMTP, no attachments → `true`

`getPriority`:
- strictly less than `TbaiEmailSender`'s 100 and greater than `Integer.MIN_VALUE`

`send`:
- maps subject, body, to, cc and replyTo into the expected provider payload
- template is `"custom"`
- non-successful provider response → throws
- transport `IOException` → propagates unchanged, no SMTP retry

**Manual verification** (not unit-testable): with no SMTP configured anywhere, force a
`FAILED` or `PARTIAL` run of the Conversion Rate Downloader and confirm the alert reaches
the address in `smfcapi_currency_apiconfig.servicealertemail`. The downloader only alerts
when `pairsFailed > 0`, so a successful run proves nothing.

**Optional pre-validation.** A throwaway `com.etendoerp.email.dummysender` module exists
(a third `EmailSender` that logs instead of sending, under a `[DUMMY-SENDER]` prefix).
Installing it and forcing a failed downloader run with no SMTP proves the whole selection
path — cross-module CDI discovery, the capability probe, dispatcher selection — before any
of this class is written. It also isolates a failure: if the dummy is selected but the
real sender later is not, the problem is in this class, not in the wiring.

## Implementation notes

- **Name collision.** Two distinct `EmailSendContext` classes exist:
  `com.etendoerp.email.spi.EmailSendContext` (core, the SPI parameter) and
  `com.etendoerp.go.schemaforge.email.EmailSendContext` (this module's internal context).
  The `spi` subpackage placement above is what keeps this explicit rather than silent;
  only the core one is ever needed here.
- **Documentation is atomic with the code** (repo policy). Document the new sender in the
  module docs and record that the Conversion Rate Downloader alert no longer requires
  SMTP.

## Out of scope

- Audit records and fine-grained kill switch. Today's switch is
  `etendo.go.email.provider.enabled` — coarse and requiring a restart. Routing through
  `TransactionalEmailService` would inherit authorization, idempotency, throttling,
  kill switch and audit, but it would require a contract accepting pre-rendered
  subject/body — precisely the arbitrary-content path ETP-4065 wants to keep closed — plus
  marshalling `EmailInfo` to JSON and interpreting a `NeoResponse` from core Java code.
  Deferred deliberately.
- Converting attachments into signed download links.
- Any change to `com.smf.currency.conversionrate`.
- Improving the observability of the three silent skips in `SyncFailureEmailSender`
  (recipient missing, no transport, no failures). Worth a follow-up: writing the reason
  into `smfcr_sync_log.error_detail` would make this diagnosable from the window instead
  of by grepping `catalina.out`.

## Local smoke test — 2026-08-10

Verified on a local environment by Francisco Roig.

**Preconditions, both confirmed at run time:**

- `SELECT count(*) FROM c_poc_configuration` → `0`. No SMTP configuration exists at any
  level, so `SmtpCascadeResolver.resolve()` returns `null` and the send can only have left
  through the new sender. Before this change the alert was skipped with a WARN.
- `etendo.go.email.provider.enabled=true` with a `baseUrl` and `apiKey` set.
- The Conversion Rate Downloader process request runs as System (`ad_client_id = 0`,
  `ad_org_id = 0`), the configuration under which the alert never used to be delivered.

**Runs, both branches of the `pairsFailed > 0` condition:**

| `sync_date` | `status` | updated | failed | Failure cause |
|---|---|---|---|---|
| `2026-08-10 15:24:23.401` | `FAILED` | 0 | 4 | unreachable converter URL, forced |
| `2026-08-10 15:27:49.401` | `PARTIAL` | 3 | 1 | genuine domain error (posting date not allowed) |

**Result:** the alert reached the address configured in
`smfcapi_currency_apiconfig.servicealertemail`. Delivery confirmed by the developer.

**Unit tests at the same commit:** `./gradlew test --tests
"com.etendoerp.go.schemaforge.email.*"` — 215 tests across 15 classes, 0 failures, 0 errors,
0 skipped, including the 15 in `GoProviderEmailSenderTest`.

Note the `PARTIAL` run is the more valuable of the two: its failure came from real business
logic rather than a forced misconfiguration, so it exercises the path a client would hit.

## Ticket

No Jira issue exists for this work. Suggested content:

> **Title:** Contribute an Etendo GO `EmailSender` to the core email SPI
>
> **Goal:** Deliver the piece ETP-4216 declared out of scope: an `EmailSender` in
> `com.etendoerp.go` that routes core ERP emails through the existing provider gateway,
> so environments without SMTP can still send.
>
> **Acceptance:**
> - With the provider enabled and no SMTP configured anywhere, a core email send (the
>   Conversion Rate Downloader failure alert, scheduled as System) is delivered.
> - With the provider disabled, behavior is identical to today.
> - Emails carrying attachments or BCC fall back to SMTP instead of losing them.
> - Send failures propagate without an SMTP retry.
> - Unit tests cover selection, the null-context probe, payload mapping and failure
>   propagation.

Parent: ETP-4216 lived under *Etendo Next*, but this fits ETP-4060 (*Transactional Email
Contracts Framework*) thematically.
