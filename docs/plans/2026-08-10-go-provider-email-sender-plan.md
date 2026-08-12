# GO Provider Email Sender Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `EmailSender` to `com.etendoerp.go` that routes core ERP emails through the existing provider gateway whenever no SMTP configuration applies.

**Architecture:** One CDI bean implementing core's `com.etendoerp.email.spi.EmailSender`, discovered at runtime by `EmailSenderDispatcher`. It reports itself configured only when the provider is configured, the context carries no SMTP config, and the message has no attachments or BCC. Delivery maps `EmailInfo` onto `EmailProviderRequest` with the `custom` template and calls `ApiGatewayEmailProviderAdapter`.

**Tech Stack:** Java 11+, CDI (Weld), JUnit 4 with the vintage engine, Mockito 5.2, Jettison JSON.

**Design doc:** `docs/plans/2026-08-10-go-provider-email-sender-design.md`

## Global Constraints

- All versioned content in **English** — code, comments, javadoc, commit messages, docs.
- Tests use **JUnit 4** (`org.junit.Test`, `org.junit.Assert.*`), matching the sibling tests in `src-test/src/com/etendoerp/go/schemaforge/email/`. The module also has JUnit 5 on the classpath; do not mix styles within a class.
- **Assert exceptions with `try` / `fail("Expected …")` / `catch`.** `org.junit.Assert.assertThrows` does not resolve — the JUnit 4 on the compile classpath predates 4.13. `assertThrows` exists only as `org.junit.jupiter.api.Assertions.assertThrows`, which belongs in JUnit 5 classes (see `src-test/src/com/etendoerp/go/mcp/`). The JUnit 4 idiom is already used in `EmailFrameworkValueObjectsTest`. Note the compile happens in the **root** project (`:compileTestJava`, no module prefix), so the module's own `testImplementation` versions are not the whole story.
- Run tests from the **`etendo_core` root** with the root `test` task (contributed by the Etendo Gradle plugin — there is no per-module `test` task):
  `./gradlew test --tests "<fully.qualified.ClassName>"`
- **The human runs all Gradle builds and all commits.** Every "commit" step below is a hand-off: state the staged files and the exact message, then stop. Never run `git commit` or `./gradlew` on their behalf.
- Commit message format: `Feature ETP-<ticket>: <description>`, first line ≤ 80 chars, **no `Co-Authored-By`** (Git Police rejects it). `<ticket>` is filled in once the Jira issue is created — see the *Ticket* section of the design doc.
- Priority constant is `50` and must stay strictly between `TbaiEmailSender`'s `100` and `DefaultSmtpEmailSender`'s `Integer.MIN_VALUE`. `com.smf.ticketbai` is **not** a dependency of this module, so the test asserts the literal `50` and documents the reason in a comment — do not try to import `TbaiEmailSender`.
- Do not modify `com.smf.currency.conversionrate`. It needs no changes.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/com/etendoerp/go/schemaforge/email/spi/GoProviderEmailSender.java` | The whole bridge: selection, priority, payload mapping, delivery. |
| `src-test/src/com/etendoerp/go/schemaforge/email/spi/GoProviderEmailSenderTest.java` | Unit tests, same package so the package-private constructor is reachable. |
| `docs/transactional-email-contracts.md` | Document the new sender and its selection rules. |

The `spi` subpackage is deliberate: `com.etendoerp.go.schemaforge.email` already declares its own `EmailSendContext`, which would shadow the core SPI class of the same name on an unqualified reference.

---

## Task 1: The sender

**Files:**
- Create: `src/com/etendoerp/go/schemaforge/email/spi/GoProviderEmailSender.java`
- Test: `src-test/src/com/etendoerp/go/schemaforge/email/spi/GoProviderEmailSenderTest.java`

**Interfaces:**
- Consumes (all existing, all public):
  - `com.etendoerp.email.spi.EmailSender` — `boolean isConfigured(EmailSendContext)`, `void send(EmailSendContext) throws Exception`, `default int getPriority()`
  - `com.etendoerp.email.spi.EmailSendContext` — `create(EmailServerConfiguration, ResolvedSmtpConfig, EmailInfo)`, `getSmtpConfig()`, `getResolvedSmtpConfig()`, `getEmail()`
  - `org.openbravo.erpCommon.utility.poc.EmailInfo` — `getRecipientTO()`, `getRecipientCC()`, `getRecipientBCC()`, `getReplyTo()`, `getSubject()`, `getContent()`, `getAttachments()` (returns `List<File>`); TO/CC/BCC are comma-separated strings
  - `EmailProviderAdapter` — `boolean isConfigured()`, `boolean supportsMultipleRecipients()`, `boolean supportsCcChannel()`, `EmailProviderResponse send(EmailProviderRequest) throws IOException, JSONException`
  - `ApiGatewayEmailProviderAdapter()` — public no-arg constructor
  - `EmailProviderRequest(EmailRecipientSet, String template, JSONObject data, String replyTo)`
  - `EmailRecipientSet.of(List<String> to, List<String> cc)`
  - `EmailProviderResponse` — `isSuccessful()` (2xx), `getStatusCode()`
  - `DefaultDocumentSendEmailContract.CONTENT_TEMPLATE` — the `"custom"` literal
- Produces: `GoProviderEmailSender` as a CDI `EmailSender`. No other task consumes its API.

- [ ] **Step 1: Create the class skeleton so the test compiles**

Create `src/com/etendoerp/go/schemaforge/email/spi/GoProviderEmailSender.java`:

```java
/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.email.spi;

import java.util.Objects;

import javax.enterprise.context.ApplicationScoped;

import com.etendoerp.email.spi.EmailSendContext;
import com.etendoerp.email.spi.EmailSender;
import com.etendoerp.go.schemaforge.email.ApiGatewayEmailProviderAdapter;
import com.etendoerp.go.schemaforge.email.EmailProviderAdapter;

/**
 * Routes core ERP emails through the Etendo GO provider gateway when no SMTP configuration
 * applies, delivering the piece ETP-4216 left out of scope.
 *
 * <p>Named after the provider rather than the concrete mail service: Etendo talks to an API
 * Gateway endpoint, and which service the gateway uses behind it can change without touching
 * this class.</p>
 */
@ApplicationScoped
public class GoProviderEmailSender implements EmailSender {

  /**
   * Ordering hint. Must stay strictly between {@code TbaiEmailSender}'s 100 and
   * {@code DefaultSmtpEmailSender}'s {@link Integer#MIN_VALUE}: below TicketBAI so that module
   * keeps delivering its own rejection alert through its own mailbox, above the SMTP floor so
   * this sender wins when SMTP does not apply. {@code com.smf.ticketbai} is not a dependency of
   * this module, so the bound is documented here rather than referenced in code.
   */
  static final int PRIORITY = 50;

  private final EmailProviderAdapter providerAdapter;

  /**
   * CDI constructor using the runtime-configured API Gateway adapter.
   */
  public GoProviderEmailSender() {
    this(new ApiGatewayEmailProviderAdapter());
  }

  /**
   * Test constructor accepting an explicit adapter.
   *
   * @param providerAdapter adapter used to submit the message
   */
  GoProviderEmailSender(EmailProviderAdapter providerAdapter) {
    this.providerAdapter = Objects.requireNonNull(providerAdapter,
        "Provider adapter cannot be null");
  }

  @Override
  public int getPriority() {
    return PRIORITY;
  }

  @Override
  public boolean isConfigured(EmailSendContext context) {
    return false;
  }

  @Override
  public void send(EmailSendContext context) throws Exception {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
```

- [ ] **Step 2: Write the failing selection tests**

Create `src-test/src/com/etendoerp/go/schemaforge/email/spi/GoProviderEmailSenderTest.java`:

```java
package com.etendoerp.go.schemaforge.email.spi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Collections;
import java.util.Date;

import org.junit.Test;
import org.openbravo.email.ResolvedSmtpConfig;
import org.openbravo.erpCommon.utility.poc.EmailInfo;
import org.openbravo.model.common.enterprise.EmailServerConfiguration;

import com.etendoerp.email.spi.EmailSendContext;
import com.etendoerp.go.schemaforge.email.EmailProviderAdapter;

public class GoProviderEmailSenderTest {

  private static EmailProviderAdapter configuredAdapter() {
    EmailProviderAdapter adapter = mock(EmailProviderAdapter.class);
    when(adapter.isConfigured()).thenReturn(true);
    when(adapter.supportsMultipleRecipients()).thenReturn(true);
    when(adapter.supportsCcChannel()).thenReturn(true);
    return adapter;
  }

  private static EmailInfo.Builder emailBuilder() {
    return new EmailInfo.Builder()
        .setRecipientTO("alerts@example.com")
        .setSubject("Downloader failed")
        .setContent("<p>2 pairs failed</p>")
        .setContentType("text/html; charset=utf-8")
        .setSentDate(new Date());
  }

  @Test
  public void priorityStaysBetweenTbaiAndTheSmtpFloor() {
    // TbaiEmailSender's 100 is hardcoded here on purpose: com.smf.ticketbai is not a
    // dependency of this module, so the bound cannot be referenced.
    assertEquals(50, new GoProviderEmailSender(configuredAdapter()).getPriority());
    assertTrue(GoProviderEmailSender.PRIORITY < 100);
    assertTrue(GoProviderEmailSender.PRIORITY > Integer.MIN_VALUE);
  }

  @Test
  public void notConfiguredWhenProviderIsNotConfigured() {
    EmailProviderAdapter adapter = mock(EmailProviderAdapter.class);
    when(adapter.isConfigured()).thenReturn(false);
    EmailSendContext context = EmailSendContext.create(null, null, emailBuilder().build());
    assertFalse(new GoProviderEmailSender(adapter).isConfigured(context));
  }

  @Test
  public void notConfiguredWhenContextIsNull() {
    assertFalse(new GoProviderEmailSender(configuredAdapter()).isConfigured(null));
  }

  @Test
  public void notConfiguredWhenCascadeResolvedAnSmtpConfig() {
    EmailSendContext context = EmailSendContext.create(null, mock(ResolvedSmtpConfig.class),
        emailBuilder().build());
    assertFalse(new GoProviderEmailSender(configuredAdapter()).isConfigured(context));
  }

  @Test
  public void notConfiguredWhenAnSmtpServerRecordIsPresent() {
    EmailSendContext context = EmailSendContext.create(mock(EmailServerConfiguration.class), null,
        emailBuilder().build());
    assertFalse(new GoProviderEmailSender(configuredAdapter()).isConfigured(context));
  }

  @Test
  public void notConfiguredWhenTheMessageCarriesAttachments() {
    EmailInfo email = emailBuilder()
        .setAttachments(Collections.singletonList(new File("/tmp/invoice.pdf")))
        .build();
    EmailSendContext context = EmailSendContext.create(null, null, email);
    assertFalse(new GoProviderEmailSender(configuredAdapter()).isConfigured(context));
  }

  @Test
  public void notConfiguredWhenTheMessageCarriesBcc() {
    EmailInfo email = emailBuilder().setRecipientBCC("audit@example.com").build();
    EmailSendContext context = EmailSendContext.create(null, null, email);
    assertFalse(new GoProviderEmailSender(configuredAdapter()).isConfigured(context));
  }

  @Test
  public void configuredForTheCapabilityProbeWithNoEmail() {
    // EmailSenderDispatcher.hasAlternativeSenderConfigured() probes with a null email; callers
    // rely on this answer to get past their pre-send guard.
    EmailSendContext probe = EmailSendContext.create(null, null, null);
    assertTrue(new GoProviderEmailSender(configuredAdapter()).isConfigured(probe));
  }

  @Test
  public void configuredWhenNoSmtpAppliesAndTheMessageIsRepresentable() {
    EmailSendContext context = EmailSendContext.create(null, null, emailBuilder().build());
    assertTrue(new GoProviderEmailSender(configuredAdapter()).isConfigured(context));
  }
}
```

- [ ] **Step 3: Run the selection tests to verify they fail**

```bash
./gradlew test --tests "com.etendoerp.go.schemaforge.email.spi.GoProviderEmailSenderTest"
```

Expected: `priorityStaysBetweenTbaiAndTheSmtpFloor` passes; the four `configured*` positive
assertions fail because `isConfigured` returns a hardcoded `false`. If **no** tests run at all,
`build/test-results/test/` will be empty — that means the filter matched nothing, not that
everything passed.

- [ ] **Step 4: Implement the selection logic**

Replace the `isConfigured` stub in `GoProviderEmailSender.java` and add the imports
`org.apache.commons.lang3.StringUtils` and `org.openbravo.erpCommon.utility.poc.EmailInfo`:

```java
  /**
   * Reports this sender as eligible only when the provider is configured, no SMTP
   * configuration applies, and the message is fully representable by the provider payload.
   *
   * <p>A {@code null} email means the dispatcher is probing for capability rather than
   * selecting a transport, so the answer is "yes, this transport exists".</p>
   *
   * @param context the send context
   * @return {@code true} when this sender should carry the message
   */
  @Override
  public boolean isConfigured(EmailSendContext context) {
    if (!providerAdapter.isConfigured() || context == null) {
      return false;
    }
    // Fallback semantics: whenever SMTP applies, stay out of the way. The cascade has already
    // run in the caller, so the answer is in the context and needs no extra query.
    if (context.getResolvedSmtpConfig() != null || context.getSmtpConfig() != null) {
      return false;
    }
    EmailInfo email = context.getEmail();
    if (email == null) {
      return true;
    }
    // The provider payload has no attachment or BCC slot. Decline instead of dropping them,
    // so the dispatcher falls back to SMTP and nothing is lost silently.
    boolean hasAttachments = email.getAttachments() != null && !email.getAttachments().isEmpty();
    return !hasAttachments && StringUtils.isBlank(email.getRecipientBCC());
  }
```

- [ ] **Step 5: Run the selection tests to verify they pass**

```bash
./gradlew test --tests "com.etendoerp.go.schemaforge.email.spi.GoProviderEmailSenderTest"
```

Expected: all 9 tests PASS.

- [ ] **Step 6: Write the failing delivery tests**

Append to `GoProviderEmailSenderTest.java`, adding these imports:

```java
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.io.IOException;

import org.codehaus.jettison.json.JSONObject;
import org.mockito.ArgumentCaptor;
import org.openbravo.base.exception.OBException;

import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.EmailProviderResponse;
```

and these test methods:

```java
  private static EmailProviderRequest captureRequest(EmailProviderAdapter adapter,
      EmailInfo email) throws Exception {
    when(adapter.send(any(EmailProviderRequest.class)))
        .thenReturn(new EmailProviderResponse(200, "{\"ok\":true}"));
    new GoProviderEmailSender(adapter).send(EmailSendContext.create(null, null, email));
    ArgumentCaptor<EmailProviderRequest> captor =
        ArgumentCaptor.forClass(EmailProviderRequest.class);
    verify(adapter).send(captor.capture());
    return captor.getValue();
  }

  @Test
  public void sendMapsSubjectBodyAndRecipientsOntoTheCustomTemplate() throws Exception {
    EmailInfo email = emailBuilder()
        .setRecipientCC("copy@example.com")
        .setReplyTo("noreply@example.com")
        .build();

    EmailProviderRequest request = captureRequest(configuredAdapter(), email);

    assertEquals("custom", request.getTemplate());
    assertEquals(Collections.singletonList("alerts@example.com"), request.getRecipients().getTo());
    assertEquals(Collections.singletonList("copy@example.com"), request.getRecipients().getCc());
    assertEquals("noreply@example.com", request.getReplyTo());
    JSONObject data = request.getData();
    assertEquals("Downloader failed", data.getString("subject"));
    assertEquals("<p>2 pairs failed</p>", data.getString("body"));
  }

  @Test
  public void sendSplitsCommaSeparatedRecipients() throws Exception {
    EmailInfo email = emailBuilder()
        .setRecipientTO("one@example.com, two@example.com")
        .build();

    EmailProviderRequest request = captureRequest(configuredAdapter(), email);

    assertEquals(2, request.getRecipients().getTo().size());
    assertTrue(request.getRecipients().getTo().contains("one@example.com"));
    assertTrue(request.getRecipients().getTo().contains("two@example.com"));
  }

  @Test
  public void sendOmitsCcWhenTheProviderCannotDeliverIt() throws Exception {
    EmailProviderAdapter adapter = configuredAdapter();
    when(adapter.supportsCcChannel()).thenReturn(false);
    EmailInfo email = emailBuilder().setRecipientCC("copy@example.com").build();

    EmailProviderRequest request = captureRequest(adapter, email);

    assertTrue(request.getRecipients().getCc().isEmpty());
  }

  @Test
  public void sendLeavesReplyToUnsetWhenAbsent() throws Exception {
    EmailProviderRequest request = captureRequest(configuredAdapter(), emailBuilder().build());
    assertNull(request.getReplyTo());
  }

  @Test
  public void sendThrowsWhenTheProviderRejectsTheMessage() throws Exception {
    EmailProviderAdapter adapter = configuredAdapter();
    when(adapter.send(any(EmailProviderRequest.class)))
        .thenReturn(new EmailProviderResponse(502, "gateway error"));
    GoProviderEmailSender sender = new GoProviderEmailSender(adapter);
    EmailSendContext context = EmailSendContext.create(null, null, emailBuilder().build());

    try {
      sender.send(context);
      fail("Expected the provider rejection to surface as an exception");
    } catch (OBException expected) {
      assertTrue(expected.getMessage().contains("502"));
    }
  }

  @Test
  public void sendPropagatesTransportFailuresWithoutRetrying() throws Exception {
    EmailProviderAdapter adapter = configuredAdapter();
    when(adapter.send(any(EmailProviderRequest.class)))
        .thenThrow(new IOException("connection reset"));
    GoProviderEmailSender sender = new GoProviderEmailSender(adapter);
    EmailSendContext context = EmailSendContext.create(null, null, emailBuilder().build());

    try {
      sender.send(context);
      fail("Expected the transport failure to propagate without an SMTP retry");
    } catch (IOException expected) {
      assertEquals("connection reset", expected.getMessage());
    }
  }
```

- [ ] **Step 7: Run the delivery tests to verify they fail**

```bash
./gradlew test --tests "com.etendoerp.go.schemaforge.email.spi.GoProviderEmailSenderTest"
```

Expected: the six new tests FAIL with `UnsupportedOperationException: Not implemented yet`.

- [ ] **Step 8: Implement delivery**

Replace the `send` stub, adding imports `java.util.ArrayList`, `java.util.Arrays`,
`java.util.List`, `org.codehaus.jettison.json.JSONObject`,
`org.openbravo.base.exception.OBException`,
`com.etendoerp.go.schemaforge.email.DefaultDocumentSendEmailContract`,
`com.etendoerp.go.schemaforge.email.EmailProviderRequest`,
`com.etendoerp.go.schemaforge.email.EmailProviderResponse` and
`com.etendoerp.go.schemaforge.email.EmailRecipientSet`:

```java
  private static final String FIELD_SUBJECT = "subject";
  private static final String FIELD_BODY = "body";

  /**
   * Submits the message through the provider gateway using the bring-your-own-content
   * template. A non-successful provider response raises an exception: the dispatcher does not
   * retry through another transport, which is what keeps a transient gateway failure from
   * turning into a double send.
   *
   * @param context the send context carrying the resolved message
   * @throws Exception when the provider rejects the message or the transport fails
   */
  @Override
  public void send(EmailSendContext context) throws Exception {
    EmailInfo email = context.getEmail();
    if (email == null) {
      throw new OBException("No email to send in the provider send context");
    }
    JSONObject data = new JSONObject();
    data.put(FIELD_SUBJECT, email.getSubject());
    data.put(FIELD_BODY, email.getContent());

    List<String> to = splitAddresses(email.getRecipientTO());
    List<String> cc = providerAdapter.supportsCcChannel()
        ? splitAddresses(email.getRecipientCC())
        : new ArrayList<>();

    // CONTENT_TEMPLATE is the provider's bring-your-own-content template. Referenced rather
    // than duplicated as a literal so the two cannot drift apart.
    EmailProviderRequest request = new EmailProviderRequest(EmailRecipientSet.of(to, cc),
        DefaultDocumentSendEmailContract.CONTENT_TEMPLATE, data, email.getReplyTo());

    EmailProviderResponse response = providerAdapter.send(request);
    if (!response.isSuccessful()) {
      throw new OBException("Email provider rejected the message with status "
          + response.getStatusCode());
    }
  }

  /**
   * Splits a core address field into individual addresses. Core stores TO/CC/BCC as
   * comma-separated strings.
   *
   * @param addresses raw comma-separated address field, may be {@code null}
   * @return the individual non-blank addresses, never {@code null}
   */
  private static List<String> splitAddresses(String addresses) {
    List<String> result = new ArrayList<>();
    if (StringUtils.isBlank(addresses)) {
      return result;
    }
    for (String candidate : Arrays.asList(addresses.split(","))) {
      String trimmed = StringUtils.trimToNull(candidate);
      if (trimmed != null) {
        result.add(trimmed);
      }
    }
    return result;
  }
```

- [ ] **Step 9: Run the full test class to verify everything passes**

```bash
./gradlew test --tests "com.etendoerp.go.schemaforge.email.spi.GoProviderEmailSenderTest"
```

Expected: all 15 tests PASS.

- [ ] **Step 10: Check for regressions in the email package**

```bash
./gradlew test --tests "com.etendoerp.go.schemaforge.email.*"
```

Expected: PASS. Nothing in that package is modified, so a failure here means an unexpected
interaction — investigate before continuing.

- [ ] **Step 11: Hand off for commit**

Files to stage:

```
modules/com.etendoerp.go/src/com/etendoerp/go/schemaforge/email/spi/GoProviderEmailSender.java
modules/com.etendoerp.go/src-test/src/com/etendoerp/go/schemaforge/email/spi/GoProviderEmailSenderTest.java
```

Suggested message (fill in the ticket, 74 chars as written):

```
Feature ETP-<ticket>: Add GO provider email sender to the core SPI
```

---

## Task 2: Documentation

**Files:**
- Modify: `docs/transactional-email-contracts.md`

**Interfaces:**
- Consumes: the class delivered in Task 1.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Locate the insertion point**

```bash
grep -n "^## " modules/com.etendoerp.go/docs/transactional-email-contracts.md
```

Expected outline: `Endpoint`, `Runtime Components`, `Authorization and Recipient Resolution`,
`Contract Implementation Rules`, `Anti-Abuse, Idempotency, and Audit`, `Auth Flow Entrypoints`,
`Built-In v1 Contracts`, `Provider Configuration`, `Response Statuses in This Layer`.

Insert the new `##` section **immediately before `## Authorization and Recipient Resolution`**,
so it sits directly after `## Runtime Components` — this is a runtime transport, and that is
where the reader is already thinking about components.

- [ ] **Step 2: Add the section**

Insert this content verbatim at that point:

```markdown
## Core ERP emails through the provider (`GoProviderEmailSender`)

Core's `EmailManager` routes every send through `EmailSenderDispatcher`
(`com.etendoerp.email.spi`, added by ETP-4216), which picks the highest-priority sender
reporting itself configured. `GoProviderEmailSender` is this module's contribution to that
SPI: it carries core ERP emails over the provider gateway when SMTP does not apply.

It is a **fallback, not an override**. It reports itself configured only when all of the
following hold:

1. the provider is configured (`etendo.go.email.provider.enabled`, `.baseUrl`, `.apiKey`);
2. the send context carries no SMTP configuration, resolved or otherwise;
3. the message has no attachments and no BCC — the provider payload has no slot for either,
   so it declines rather than dropping them, and the dispatcher falls back to SMTP.

A `null` email means the dispatcher is probing for capability
(`hasAlternativeSenderConfigured()`), and the sender answers `true` so callers get past their
pre-send guard.

Priority is `50`: below `TbaiEmailSender`'s `100`, so TicketBAI keeps delivering its own
rejection alert through its own mailbox, and above `DefaultSmtpEmailSender`'s
`Integer.MIN_VALUE`.

Delivery uses the `custom` template with `data.subject` and `data.body` — the
bring-your-own-content template listed in *Provider template allowlist* above. The provider
sends from its own verified identity, so core's `from`/`fromName` are ignored, as are
`sentDate` and `headerExtras`.

**Practical consequence:** the `com.smf.currency.conversionrate` failure alert now reaches its
recipient on a System-scheduled downloader, where the SMTP cascade resolves nothing because it
filters by client `0`. That module required no changes — the dispatcher selects the transport
for it.

Environments with SMTP configured, or without the provider configured, are unaffected.
```

- [ ] **Step 3: Verify the document renders and links resolve**

```bash
grep -n "GoProviderEmailSender" modules/com.etendoerp.go/docs/transactional-email-contracts.md
```

Expected: the new section is present. Confirm no duplicate heading was introduced.

- [ ] **Step 4: Hand off for commit**

```
modules/com.etendoerp.go/docs/transactional-email-contracts.md
```

```
Feature ETP-<ticket>: Document the GO provider email sender
```

---

## Task 3: End-to-end verification in a local environment

No unit test covers CDI discovery or a real gateway call. This task is manual and its Gradle
and Tomcat steps belong to the human.

**Files:** none.

**Interfaces:**
- Consumes: the deployed class from Task 1.
- Produces: evidence the alert is delivered.

- [ ] **Step 1: Confirm the preconditions in the database**

```bash
export PGPASSWORD=$(grep '^bbdd.password=' gradle.properties | cut -d= -f2)
psql -h localhost -p 5433 -U tad -d etendo -X -A -F' | ' -c \
  "SELECT (SELECT count(*) FROM c_poc_configuration) AS smtp_configs,
          (SELECT servicealertemail FROM smfcapi_currency_apiconfig
            WHERE service_selected='Y') AS alert_recipient;"
```

Expected: `smtp_configs` is `0` (otherwise the sender declines by design and this test proves
nothing) and `alert_recipient` is a non-null address.

- [ ] **Step 2: Confirm the provider is enabled**

```bash
grep '^etendo.go.email.provider' gradle.properties
```

Expected: `enabled=true` plus a `baseUrl` and an `apiKey`.

- [ ] **Step 3: Build and deploy (human runs this)**

```bash
./gradlew smartbuild
```

Then restart Tomcat. Required because CDI discovers the bean at deployment.

- [ ] **Step 4: Confirm the dispatcher selects the new sender**

Enable DEBUG for `com.etendoerp.email.spi` and watch the log while triggering a send:

```bash
tail -f $CATALINA_HOME/logs/catalina.out | grep -i "Dispatching email send"
```

Expected: `Dispatching email send to com.etendoerp.go.schemaforge.email.spi.GoProviderEmailSender`.

If a different sender is chosen, isolate whether the fault is in this class or in the wiring
before debugging the class: install the throwaway `com.etendoerp.email.dummysender` module (a
third `EmailSender` that logs under a `[DUMMY-SENDER]` prefix instead of sending) with its
`ETDUMMY_EmailSenderEnabled` preference set to `Y`, and repeat this step. If the dummy is
selected but this sender is not, the problem is in `isConfigured` — most likely condition 2,
meaning the context carries an SMTP config you did not expect. If neither is selected, the
problem is CDI discovery, not this class. Uninstall the dummy afterwards.

- [ ] **Step 5: Force a failing downloader run**

The alert only fires when `pairsFailed > 0` (`ConversionRateDownloader.java:131`), so a
successful run proves nothing. Make one currency pair fail — for example point the selected
converter's `service_url` at an unreachable host, keeping at least one valid pair if you want
to exercise `PARTIAL` rather than `FAILED`:

```bash
psql -h localhost -p 5433 -U tad -d etendo -c \
  "UPDATE smfcapi_currency_apiconfig SET service_url='http://127.0.0.1:9/none'
    WHERE service_selected='Y';"
```

Run the Conversion Rate Downloader from *Process Request*, then restore the URL afterwards.

- [ ] **Step 6: Verify the outcome**

```bash
psql -h localhost -p 5433 -U tad -d etendo -X -A -F' | ' -c \
  "SELECT sync_date, status, pairs_updated, pairs_failed FROM smfcr_sync_log
    ORDER BY sync_date DESC LIMIT 1;"
```

Expected: a `FAILED` or `PARTIAL` row, the recipient's inbox has the alert, and
`catalina.out` shows `Conversion rate sync alert sent to ...`. The absence of
`No email configuration (SMTP cascade or SPI sender) available` is the specific regression
signal — that message means the sender was not selected.

- [ ] **Step 7: Record the evidence**

Append a short smoke-test note to the design doc (status, timestamp, whether the email
arrived), matching the existing `docs/ETP-4139-local-smoke-2026-06-01.md` precedent. Hand off
for commit:

```
Feature ETP-<ticket>: Record local smoke test for the provider email sender
```

---

## Rollout gate

Before enabling the provider in any shared environment, resolve the two questions from the
design doc's *Rollout risks*: whether SES is in sandbox mode (which confines delivery to
verified addresses), and whether that environment's database holds real customer addresses.
Emails that currently die silently will start being delivered — that is the point of the
change, and also its main operational risk.
