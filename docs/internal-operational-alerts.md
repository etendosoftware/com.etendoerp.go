# Internal operational alerts

ETP-5548 adds a server-only `internal-alert` email contract and the reusable `InternalAlertService`. Its first producer reports the outcome of customer environment provisioning: demo and productive environments, through either the classic path or an assigned pool client. Preparing an unassigned warm pool tenant does not generate a customer provisioning alert. Authentication and prevalidation rejections do not generate provisioning events.

## Configuration

Properties use the existing `ConfigPropertyReader` precedence and can also be configured through environment variables. Recipients and accepted result statuses are controlled on the server; they cannot be supplied through the email endpoint.

| Property | Environment variable | Default |
| --- | --- | --- |
| `etendo.go.internalAlerts.enabled` | `ETGO_INTERNAL_ALERTS_ENABLED` | `true` |
| `etendo.go.internalAlerts.recipients` | `ETGO_INTERNAL_ALERTS_RECIPIENTS` | `builds@etendo.software` |
| `etendo.go.internalAlerts.statuses` | `ETGO_INTERNAL_ALERTS_STATUSES` | `OK,ERROR` |
| `etendo.go.internalAlerts.language` | `ETGO_INTERNAL_ALERTS_LANGUAGE` | `es_ES` |

`recipients` is a comma-separated list of at most ten valid email addresses. `statuses` accepts `OK`, `ERROR`, or both as a comma-separated list; matching is case-insensitive. An empty or invalid recipient/status configuration fails closed and logs a safe delivery warning. Supported email catalog languages are `es_ES` and `en_US`; the shared renderer falls back to Spanish for unsupported languages. Disabling alerts prevents provider calls and email transaction work.

```properties
etendo.go.internalAlerts.enabled=true
etendo.go.internalAlerts.recipients=builds@etendo.software
etendo.go.internalAlerts.statuses=ERROR
etendo.go.internalAlerts.language=es_ES
```

Existing email provider configuration remains required (`etendo.go.email.provider.*` / `ETGO_EMAIL_PROVIDER_*`). No provider endpoint or credential is introduced by this feature. Local instances may point to the production gateway: use `etendo.go.internalAlerts.enabled=false` for manual local work where real operational notifications are unwanted. Unit tests inject a fake provider or a mocked alert sender and never send real email.

## Transaction boundaries and delivery

An `OK` event is emitted immediately after the provisioning transaction commits, before subsequent checkout bookkeeping, costing scheduling, or customer-ready notifications. An `ERROR` event is emitted only after confirmed rollback of an uncommitted provisioning attempt. If rollback itself fails, both the alert and the paid checkout failure annotation are suppressed so neither audit nor diagnostic persistence can commit partially provisioned data. This also applies to rollback failure after a post-commit bookkeeping exception; a committed tenant flag alone is not proof that the current transaction is clean. The remaining request transaction is also marked rollback-only for `DalThreadCleaner`, because the servlet catches the exception and the outer cleaner would otherwise attempt a normal commit. A safe correlation/stage warning remains in logs. Confirmed rollback retains the existing failure annotation behavior. A committed flag prevents downstream bookkeeping exceptions from generating a false provisioning failure alert. A boolean provisioning failure follows the same rollback/error path as an exception.

The internal sender mirrors the existing transactional auth-email sender: switch temporarily to the System context, submit through `TransactionalEmailService`, flush and close the email transaction, then restore the previous context. The caller must first settle its business transaction. Provider, audit, configuration, or sender failures are contained, logged using safe categories, and cannot roll back provisioning or alter its outcome. Failed email transactions are rolled back separately. Sending is synchronous and best effort; there is no durable notification queue or automatic retry guarantee.

The existing safety engine handles deduplication and throttling. The server-derived key includes event, immutable attempt/correlation ID, and result, so repeating the same event is suppressed while a distinct retry or an `ERROR` followed by `OK` remains deliverable. Limits are 120 sends per recipient per 15 minutes and 500 global sends per minute; throttle/suppression outcomes are recorded by the existing engine. Delivery records remain in the safety audit, not document send history.

## Authorization and payload

`InternalAlertEmailContract` is registered with `CoreEmailContractProvider`. Only the in-process `TrustedBody`, constructed internally with a private constructor, is authorized. HTTP JSON cannot create that Java type, regardless of flags or fields supplied by a caller. Recipients come exclusively from server configuration. Rendering uses translated `EmailContent` blocks and `EmailLayout`, with the existing `custom` provider template.

`InternalAlertEvent` is immutable and carries only event key, result, attempt ID, environment type, provisioning path (`POOL`, `CLASSIC`, or `UNKNOWN` before selection), client ID when known, stage, and sanitized exception class/category. It deliberately contains no account email, password, payment token, provider credential, raw request, exception message, or stack trace. Operational token fields are restricted in shape and length. The customer-visible failure response and existing diagnostics remain separate from this alert payload.

The first producer stages cover admin context, tenant selection, admin resolution, productive metadata, organization, dataset, lifecycle, and commit. Successful alerts report `committed`; their path preserves whether provisioning claimed a pooled client or used the classic flow.

## Verification evidence

The focused JUnit execution passed 146 tests, including the actual transactional-email pipeline with fake providers, configuration/result filters, translated rendering, plain-JSON rejection, idempotency, and provisioning outcome ordering. Rollback-failure regressions assert that neither internal alerts nor checkout failure diagnostics can commit unresolved provisioning changes, including a failure after the original tenant commit. Unit tests sent no real email.
