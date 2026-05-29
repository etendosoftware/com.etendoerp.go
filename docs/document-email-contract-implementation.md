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
| `download_link` | Server-generated document download URL |

Do not include `amount` by default. Enable it only when a provider template explicitly requires it. Document-specific aliases, such as `invoice_number`, must also be explicit opt-ins.

## Step-by-Step Implementation

### 1. Create the contract class

For a minimal document contract, create a small class under:

```text
src/com/etendoerp/go/schemaforge/email/
```

Example:

```java
public class SalesOrderSendEmailContract extends DefaultDocumentSendEmailContract {
  public static final String NAME = "sales-order-send";

  public SalesOrderSendEmailContract(EmailContractDataResolver dataResolver) {
    super(NAME, "Sales Order", dataResolver::findSalesOrder);
  }
}
```

Use the extended constructor only when the provider template requires extra compatibility fields:

```java
super(NAME, "invoice", "Sales Invoice", "invoice_number", true, dataResolver::findSalesInvoice);
```

The final boolean controls whether `amount` is emitted.

### 2. Add a resolver method

Add a method to `EmailContractDataResolver`:

```java
Optional<EmailDocumentRecord> findSalesOrder(String orderId);
```

The method must return an empty result for invalid, inactive, inaccessible, or unsupported records.

### 3. Resolve the trusted record with DAL

Implement the resolver in `DalEmailContractDataResolver`.

Rules:

1. Load the Openbravo entity by ID through DAL.
2. Return empty when the record is missing or inactive.
3. Verify the record belongs to a readable client.
4. Verify document-family constraints, for example sales transactions only for sales order contracts.
5. Resolve the recipient from `C_BPartner.EM_Etgo_Email`, falling back to an active contact email.
6. Generate the document link server-side with `buildDocumentDownloadLink(documentType, recordId)`.

Do not trust browser-provided recipient, template, or variable values.

### 4. Register the contract

Add the contract to `DefaultEmailContractRegistry`:

```java
register(new SalesOrderSendEmailContract(dataResolver));
```

Registry lookup is the boundary that turns a route name into a server-owned contract. Missing contracts should keep returning `VALIDATION_FAILED` with HTTP 404.

### 5. Keep delivery policy explicit

`DefaultDocumentSendEmailContract` provides the standard document send policy:

- deterministic idempotency key based on contract and record
- per-user throttle
- per-recipient throttle
- per-record throttle
- per-tenant throttle

If a new document family has different abuse characteristics, override the policy and document the reason.

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

### 7. Update documentation

Update:

1. `docs/transactional-email-contracts.md` in this module.
2. `schema-forge/docs/email-contracts.md`.
3. The matching Schema Forge generated window guide.

## Review Checklist

- Contract class is small and delegates common document behavior to `DefaultDocumentSendEmailContract` when possible.
- Resolver loads and validates trusted server records.
- Recipient is resolved server-side.
- Browser-provided provider payload fields are rejected.
- Default payload remains `name`, `document_type`, `document_number`, and `download_link`.
- `amount` and aliases are explicit compatibility opt-ins only.
- Registry, resolver, payload, failure, and idempotency behavior are covered by tests.
- Runtime and Schema Forge docs are updated in the same change.
