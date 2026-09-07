# Document Email Contract Implementation Guide

Use this guide when adding a document-send transactional email contract to `com.etendoerp.go`.

The module owns runtime execution: contract lookup, authorization, recipient resolution, template data, throttle, idempotency, audit, suppression, kill switches, and provider calls. Browser clients must call Etendo Go with a contract command and must not send provider payloads.

## Before You Start

1. Read [transactional-email-contracts.md](transactional-email-contracts.md).
2. Confirm the matching Schema Forge contract entry exists in `schema-forge/docs/email-contracts.md`.
3. Confirm the command shape uses:

   ```http
   POST /sws/neo/email-contracts/{contractName}/send
   ```

4. Reject any design that accepts arbitrary `to`, `template`, `data`, `subject`, sender, Reply-To, provider URL, API key, or provider metadata from the browser.

## Contract Shape

Document-send contracts should extend `DefaultDocumentSendEmailContract` when the default document behavior is enough.

The default payload is intentionally minimal:

| Variable | Description |
|----------|-------------|
| `name` | Recipient display name from trusted server data |
| `document_type` | Contract-defined document label |
| `document_number` | Document number from the trusted record |
| `download_link` | Server-generated signed document download URL |

Do not include `amount` by default. Enable it only when a provider template explicitly requires it. Document-specific aliases, such as `invoice_number`, must also be explicit opt-ins.

## Step-by-Step Implementation

### 1. Create the contract class

For a minimal document contract, create a small class under:

```text
src/com/etendoerp/go/schemaforge/email/contracts/
```

Example:

```java
public class SalesOrderSendEmailContract extends DefaultDocumentSendEmailContract {
  public static final String NAME = "sales-order-send";

  public SalesOrderSendEmailContract(EmailDocumentRecordResolver documentResolver) {
    super(NAME, "Sales Order", documentResolver);
  }
}
```

Use the extended constructor only when the provider template requires extra compatibility fields:

```java
super(NAME, "invoice", "Sales Invoice", "invoice_number", true, documentResolver);
```

The final boolean controls whether `amount` is emitted.

### 2. Create a document resolver

Create a resolver for one document family by implementing `EmailDocumentRecordResolver` in the implementation package:

```java
final class DalSalesOrderEmailDocumentResolver implements EmailDocumentRecordResolver {
  @Override
  public Optional<EmailDocumentRecord> resolve(String recordId) {
    // Load and validate one trusted document family.
  }
}
```

Do not add document-specific methods to a shared framework resolver. The email framework must not grow methods such as `findSalesOrder`, `findSalesQuotation`, or `findPurchaseInvoice`. Each document implementation owns its resolver and injects it into its contract.

### 3. Resolve the trusted record with DAL

Implement the resolver in a document-owned class, for example `DalOrderEmailDocumentResolver` or `DalInvoiceEmailDocumentResolver`, under `com.etendoerp.go.schemaforge.email.contracts`.

Rules:

1. Load the Openbravo entity by ID through DAL.
2. Return empty when the record is missing or inactive.
3. Verify the record belongs to a readable client.
4. Verify document-family constraints, for example sales transactions only for sales order contracts.
5. Resolve the recipient from `C_BPartner.EM_Etgo_Email`, falling back to an active contact email.
6. Let `DefaultDocumentSendEmailContract` generate the signed document link server-side from the
   resolved record metadata and the send idempotency key. Document resolvers should not construct
   browser `blob:` URLs or unaudited public links.

Do not trust browser-provided recipient, template, or variable values.

### 4. Provide the contract through CDI

Expose document contracts through an injected `EmailContractProvider` in the implementation package:

```java
@ApplicationScoped
public final class SalesDocumentEmailContractProvider implements EmailContractProvider {
  @Override
  public Collection<EmailContract> getContracts() {
    return List.of(
        new SalesOrderSendEmailContract(new DalOrderEmailDocumentResolver("sales-order")));
  }
}
```

`DefaultEmailContractRegistry` loads `EmailContractProvider` instances through CDI. Registry lookup is the boundary that turns a route name into a server-owned contract. Missing contracts should keep returning `VALIDATION_FAILED` with HTTP 404.

### 5. Keep delivery policy explicit

`DefaultDocumentSendEmailContract` provides the standard document send policy:

- deterministic idempotency key based on contract, trusted document tenant, and record
- per-user throttle
- per-recipient throttle
- per-record throttle
- per-tenant throttle

If a new document family has different abuse characteristics, override the policy and document the reason.

### 5b. Know what your contract inherits about the readable history

`DefaultDocumentSendEmailContract` also overrides `logsSendHistory()` to `true`, so **a new document-send contract writes a readable `ETGO_EMAIL_SEND_LOG` row for every send attempt the moment you extend the base class** — recipients, subject, the operator's message and the download link **in clear**, on the sending tenant's own client. That is the intended default for commercial documents a tenant emails to a business partner, and it is what makes the document's Emails card work with no further wiring.

Two things follow, and both are your call to make deliberately:

- **If the family is not that** — if its recipients are the platform's own users, or its copy carries single-use links or tokens — do **not** extend `DefaultDocumentSendEmailContract` blindly. Override `logsSendHistory()` back to `false` and say why in the class javadoc, the way the account/auth contracts inherit the interface default. Clear-text correspondence in a tenant-readable table is a privacy decision, not a free feature.
- **`getSpecName()` matters.** The base class derives it by stripping the `-send` suffix from the contract name, so `return-to-vendor-send` yields `return-to-vendor`. It scopes the read endpoint's optional `specName` filter and resolves the row's `AD_Table` through `ETGO_SF_SPEC -> AD_Window -> first tab`. A contract whose name does not follow the `${windowName}-send` convention gets `null` and loses both — override `getSpecName()` rather than renaming the window.

Reference: `docs/transactional-email-contracts.md` → *Readable send history (ETP-5069)*, and the functional repo's `docs/ops/transactional-email-security.md` → *Email Audit Redaction & Storage Policy* for the policy this sits under.

### 6. Add tests

Update or add tests under:

```text
src-test/src/com/etendoerp/go/schemaforge/email/
```

Minimum coverage:

1. Registry lookup finds the new contract by name.
2. Valid command resolves recipient and template variables from trusted records.
3. Default document payload does not include `amount` unless the contract opts in.
4. Missing or inaccessible record returns authorization or validation failure.
5. Missing recipient returns `NO_RECIPIENT`.
6. Provider passthrough fields are rejected by the executor.
7. Repeated idempotency key does not call the provider twice.
8. `logsSendHistory()` returns what the family actually intends — inherited `true` for a document a tenant emails to a business partner, explicitly `false` otherwise.

### 7. Update documentation

Update:

1. `docs/transactional-email-contracts.md` in this module.
2. `schema-forge/docs/email-contracts.md`.
3. The matching Schema Forge generated window guide.

## Review Checklist

- Contract class is small and delegates common document behavior to `DefaultDocumentSendEmailContract` when possible.
- Document resolver is owned by the implementation and injected into the contract.
- No document-specific method is added to `EmailContractDataResolver` or any shared framework resolver.
- Resolver loads and validates trusted server records.
- Recipient is resolved server-side.
- Browser-provided provider payload fields are rejected.
- Default payload remains `name`, `document_type`, `document_number`, and `download_link`.
- `amount` and aliases are explicit compatibility opt-ins only.
- `logsSendHistory()` is correct for the family, and `getSpecName()` resolves (contract name follows `${windowName}-send`, or the method is overridden).
- Provider registration, resolver, payload, failure, and idempotency behavior are covered by tests.
- Runtime and Schema Forge docs are updated in the same change.
