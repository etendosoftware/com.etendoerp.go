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

package com.etendoerp.go.schemaforge.email;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

import com.etendoerp.go.common.ConfigPropertyReader;
import com.etendoerp.go.schemaforge.email.render.EmailContent;
import com.etendoerp.go.schemaforge.email.render.EmailEscape;
import com.etendoerp.go.schemaforge.email.render.EmailLayout;
import com.etendoerp.go.schemaforge.email.render.EmailMessages;

/**
 * Base contract for document-send transactional emails resolved from trusted server records.
 */
public class DefaultDocumentSendEmailContract implements EmailContract {

  private static final Logger log = LogManager.getLogger();

  /**
   * Provider template that renders caller-supplied {@code subject}/{@code body} instead of
   * carrying copy of its own.
   */
  public static final String CONTENT_TEMPLATE = "custom";

  /**
   * Provider template used by the document-send family when no contract overrides it.
   * <p>
   * ETP-4786: this was {@code "document"}, a template the provider gateway does not expose. The
   * gateway answered every send with
   * {@code 400 Unknown template 'document'. Available: ['reset-password', 'login-alert',
   * 'invoice', 'custom']}, surfacing in the UI as "the email provider could not send the
   * document". {@code custom} is the bring-your-own-content template, so this contract also
   * supplies {@code subject} and {@code body} (see {@link #buildTemplateData}). Override with
   * {@link #PROP_DOCUMENT_TEMPLATE} once the gateway publishes a branded document template.
   */
  public static final String DEFAULT_TEMPLATE = CONTENT_TEMPLATE;

  public static final String PROP_DOCUMENT_TEMPLATE =
      "etendo.go.email.provider.documentTemplate";
  public static final String ENV_DOCUMENT_TEMPLATE =
      "ETGO_EMAIL_PROVIDER_DOCUMENT_TEMPLATE";

  /**
   * Anti-abuse throttle ceilings, per rolling hour.
   *
   * <p>ETP-5003: these were inline literals, which made a repeated test send indistinguishable from
   * abuse — {@code perRecord} in particular allows only 3 sends of the <b>same</b> document per
   * hour, so re-sending one invoice while checking a template change locks the record out. The
   * defaults below are the production values and are unchanged; each can be raised per environment
   * so a developer never has to edit and recompile this class to unblock themselves.</p>
   *
   * <p>Raising a limit does not carry the old counter over: {@code DalEmailSafetyStore} matches a
   * throttle row on {@code maxAttempts} and {@code windowSeconds} as well as on scope and bucket,
   * so a changed ceiling starts a fresh row at zero.</p>
   */
  static final int DEFAULT_MAX_PER_TENANT = 100;
  static final int DEFAULT_MAX_PER_USER = 50;
  static final int DEFAULT_MAX_PER_RECORD = 3;
  static final int DEFAULT_MAX_PER_RECIPIENT = 20;
  static final int DEFAULT_MAX_PER_DOMAIN = 200;

  /** Global rate is a burst guard rather than a per-actor quota, so it stays fixed. */
  private static final int DEFAULT_MAX_GLOBAL = 2000;
  private static final int GLOBAL_WINDOW_SECONDS = 60;
  private static final int THROTTLE_WINDOW_SECONDS = 3600;

  public static final String PROP_MAX_PER_TENANT = "etendo.go.email.throttle.maxPerTenant";
  public static final String PROP_MAX_PER_USER = "etendo.go.email.throttle.maxPerUser";
  public static final String PROP_MAX_PER_RECORD = "etendo.go.email.throttle.maxPerRecord";
  public static final String PROP_MAX_PER_RECIPIENT = "etendo.go.email.throttle.maxPerRecipient";
  public static final String PROP_MAX_PER_DOMAIN = "etendo.go.email.throttle.maxPerDomain";
  public static final String ENV_MAX_PER_TENANT = "ETGO_EMAIL_THROTTLE_MAX_PER_TENANT";
  public static final String ENV_MAX_PER_USER = "ETGO_EMAIL_THROTTLE_MAX_PER_USER";
  public static final String ENV_MAX_PER_RECORD = "ETGO_EMAIL_THROTTLE_MAX_PER_RECORD";
  public static final String ENV_MAX_PER_RECIPIENT = "ETGO_EMAIL_THROTTLE_MAX_PER_RECIPIENT";
  public static final String ENV_MAX_PER_DOMAIN = "ETGO_EMAIL_THROTTLE_MAX_PER_DOMAIN";

  private static final String DOCUMENT_RECORD_NOT_FOUND = "Email document record was not found";
  private static final String FIELD_SUBJECT = "subject";
  private static final String FIELD_BODY = "body";

  private final String name;
  private final String template;
  private final String documentType;
  private final String documentNumberAlias;
  private final boolean includeAmount;
  private final EmailDocumentRecordResolver documentResolver;

  protected DefaultDocumentSendEmailContract(String name, String documentType,
      EmailDocumentRecordResolver documentResolver) {
    this(name, resolveDefaultTemplate(), documentType, null, false, documentResolver);
  }

  protected DefaultDocumentSendEmailContract(String name, String template, String documentType,
      String documentNumberAlias,
      EmailDocumentRecordResolver documentResolver) {
    this(name, template, documentType, documentNumberAlias, false, documentResolver);
  }

  protected DefaultDocumentSendEmailContract(String name, String template, String documentType,
      String documentNumberAlias, boolean includeAmount,
      EmailDocumentRecordResolver documentResolver) {
    this.name = StringUtils.trimToNull(name);
    this.template = StringUtils.trimToNull(template);
    this.documentType = StringUtils.trimToNull(documentType);
    this.documentNumberAlias = StringUtils.trimToNull(documentNumberAlias);
    this.includeAmount = includeAmount;
    this.documentResolver = Objects.requireNonNull(documentResolver, "documentResolver");
  }

  /**
   * Resolves the provider template used by contracts that do not pin one of their own.
   *
   * @return configured document template, or {@link #DEFAULT_TEMPLATE} when unset
   */
  static String resolveDefaultTemplate() {
    return StringUtils.defaultIfBlank(ConfigPropertyReader.readConfigValue(PROP_DOCUMENT_TEMPLATE,
        ENV_DOCUMENT_TEMPLATE, null), DEFAULT_TEMPLATE);
  }

  /**
   * Reads a throttle ceiling from configuration, falling back to the production default.
   *
   * <p>A malformed or non-positive override is ignored rather than honoured: a typo that parsed as
   * zero would clamp to a single attempt per hour and read as the email system being broken.</p>
   *
   * @param propertyName Openbravo property name
   * @param envName environment variable name
   * @param defaultValue production ceiling used when nothing overrides it
   * @return the configured ceiling, or {@code defaultValue}
   */
  static int maxAttempts(String propertyName, String envName, int defaultValue) {
    String configured = ConfigPropertyReader.readConfigValue(propertyName, envName, null);
    if (StringUtils.isBlank(configured)) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(configured.trim());
      return parsed > 0 ? parsed : defaultValue;
    } catch (NumberFormatException e) {
      log.warn("Ignoring non-numeric email throttle override {}={}, using {}", propertyName,
          configured, defaultValue);
      return defaultValue;
    }
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public EmailAuthorizationResult authorize(EmailContractCommand command) {
    EmailAuthorizationResult validation = EmailContractCommandSupport.validateCommand(command,
        EmailContractCommandSupport.FIELD_RECORD_ID);
    if (!validation.isAllowed()) {
      return validation;
    }
    return resolveDocument(command).isPresent()
        ? EmailAuthorizationResult.allowed()
        : EmailAuthorizationResult.rejected(404, DOCUMENT_RECORD_NOT_FOUND);
  }

  /**
   * Per-contract hook: the document-send family accepts recipient edits by default.
   *
   * @return {@code true} when {@code recipientEdits} is accepted
   */
  protected boolean isRecipientEditingEnabled() {
    return true;
  }

  /**
   * Per-contract hook: maximum number of recipients across the to and cc channels.
   *
   * @return maximum total recipient count
   */
  protected int maxRecipientsTotal() {
    return 10;
  }

  @Override
  public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    Optional<EmailDocumentRecord> document = resolveDocument(command);
    if (!document.isPresent()) {
      return EmailRecipientResolution.rejected(404, DOCUMENT_RECORD_NOT_FOUND);
    }
    Optional<EmailRecipientEdits> edits;
    try {
      edits = EmailRecipientEdits.fromBody(command.getBody());
    } catch (EmailRecipientEdits.InvalidRecipientEditsException e) {
      return EmailRecipientResolution.rejected(400, e.getMessage());
    }
    if (edits.isPresent() && !isRecipientEditingEnabled()) {
      return EmailRecipientResolution.rejected(400,
          "recipientEdits is not accepted by this contract");
    }
    List<String> baseTo = new ArrayList<>();
    String baseEmail = document.get().getRecipientEmail();
    if (EmailContractCommandSupport.isValidEmail(baseEmail)) {
      baseTo.add(baseEmail);
    }
    if (!edits.isPresent()) {
      if (baseTo.isEmpty()) {
        return EmailRecipientResolution.noRecipient("Document has no recipient email");
      }
      return EmailRecipientResolution.serverResolved(baseTo.get(0));
    }
    EmailRecipientSet finalSet = edits.get().applyTo(baseTo);
    if (finalSet.isToEmpty()) {
      return EmailRecipientResolution.noRecipient("Final recipient list is empty");
    }
    if (finalSet.totalCount() > maxRecipientsTotal()) {
      return EmailRecipientResolution.rejected(400,
          "Recipient count exceeds the maximum of " + maxRecipientsTotal());
    }
    return EmailRecipientResolution.serverResolved(finalSet);
  }

  @Override
  public EmailContractResolution resolve(EmailContractCommand command,
      EmailRecipientResolution recipient) {
    Optional<EmailDocumentRecord> document = resolveDocument(command);
    if (!document.isPresent()) {
      return EmailContractResolution.rejected(404,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          DOCUMENT_RECORD_NOT_FOUND);
    }
    Optional<String> downloadLink = resolveDownloadLink(command, document.get());
    if (!downloadLink.isPresent()) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "Document download link is not configured");
    }
    Optional<EmailMessageEdits> messageEdits;
    try {
      messageEdits = EmailMessageEdits.fromBody(command.getBody());
    } catch (EmailMessageEdits.InvalidMessageEditsException e) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED, e.getMessage());
    }
    // ETP-5003 — every document email now renders through the shared layout, so there is no
    // branded-template branch left: an edited send and an untouched one produce the same design.
    // Before this, editing the message silently downgraded a branded invoice to two bare
    // paragraphs.
    String language = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_LANGUAGE);
    if (StringUtils.isBlank(language)) {
      // ETP-5003 — a command with no language silently renders in Spanish (EmailMessages'
      // fallback). That is indistinguishable from a correct Spanish send, so an operator working
      // in English reads English on screen and the customer receives Spanish, with nothing
      // anywhere to explain it. It stays a fallback rather than a rejection — refusing to send an
      // invoice over a missing header field is worse than sending it in the default language — but
      // it must never again be silent.
      log.warn("Email contract {} received no language for record {}; falling back to Spanish. "
          + "The caller should post the operator's locale.", name,
          EmailContractCommandSupport.text(command, EmailContractCommandSupport.FIELD_RECORD_ID));
    }
    try {
      EmailRecipientSet recipients = recipient.getRecipientSet() != null
          ? recipient.getRecipientSet()
          : EmailRecipientSet.singleTo(recipient.getRecipient());
      // ETP-5003 — the gateway always sends from a verified noreply@ address, so without a
      // Reply-To the customer receiving this invoice or order has no way to answer the operator
      // who sent it. Derived from the session, never from the command body.
      return EmailContractResolution.ready(new EmailProviderRequest(recipients, CONTENT_TEMPLATE,
          buildTemplateData(document.get(), downloadLink.get(), language, messageEdits),
          EmailSenderIdentity.resolveReplyTo()));
    } catch (JSONException e) {
      throw new OBException("Could not build document email payload for " + name, e);
    }
  }

  @Override
  public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String recordId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID);
    Optional<EmailDocumentRecord> document = resolveDocument(command);
    String tenantId = document.map(EmailDocumentRecord::getClientId).orElse(null);
    String documentRecordId = resolveEffectiveRecordId(document.orElse(null), recordId);
    EmailRecipientSet finalRecipients = recipient.getRecipientSet() != null
        ? recipient.getRecipientSet()
        : providerRequest.getRecipients();
    return EmailDeliveryPolicy.serverDerived(
        resolveSendIdempotencyKey(tenantId, documentRecordId, finalRecipients,
            messageEditsQuietly(command)),
        java.util.Arrays.asList(
            EmailThrottleRule.perTenant(maxAttempts(PROP_MAX_PER_TENANT, ENV_MAX_PER_TENANT,
                DEFAULT_MAX_PER_TENANT), THROTTLE_WINDOW_SECONDS),
            EmailThrottleRule.perUser(maxAttempts(PROP_MAX_PER_USER, ENV_MAX_PER_USER,
                DEFAULT_MAX_PER_USER), THROTTLE_WINDOW_SECONDS),
            EmailThrottleRule.perRecord(maxAttempts(PROP_MAX_PER_RECORD, ENV_MAX_PER_RECORD,
                DEFAULT_MAX_PER_RECORD), THROTTLE_WINDOW_SECONDS),
            EmailThrottleRule.perRecipient(maxAttempts(PROP_MAX_PER_RECIPIENT,
                ENV_MAX_PER_RECIPIENT, DEFAULT_MAX_PER_RECIPIENT), THROTTLE_WINDOW_SECONDS),
            EmailThrottleRule.perDomain(maxAttempts(PROP_MAX_PER_DOMAIN, ENV_MAX_PER_DOMAIN,
                DEFAULT_MAX_PER_DOMAIN), THROTTLE_WINDOW_SECONDS),
            EmailThrottleRule.global(DEFAULT_MAX_GLOBAL, GLOBAL_WINDOW_SECONDS)));
  }

  private JSONObject buildTemplateData(EmailDocumentRecord document, String downloadLink,
      String language, Optional<EmailMessageEdits> messageEdits) throws JSONException {
    JSONObject data = new JSONObject();
    // Kept beside the rendered content: the gateway logs these for traceability, and they cost
    // nothing now that the copy no longer depends on them.
    data.put("name", StringUtils.defaultIfBlank(document.getRecipientName(), "Customer"));
    data.put("document_type", documentType);
    data.put("document_number", document.getDocumentNumber());
    if (documentNumberAlias != null) {
      data.put(documentNumberAlias, document.getDocumentNumber());
    }
    if (includeAmount) {
      data.put("amount", document.getAmount());
    }
    data.put("download_link", downloadLink);
    data.put(FIELD_SUBJECT, resolveSubject(document, language, messageEdits));
    data.put(FIELD_BODY,
        EmailLayout.render(buildContent(document, downloadLink, language, messageEdits)));
    return data;
  }

  /**
   * Composes the document email from shared layout blocks.
   *
   * <p>An operator-authored message replaces only the introductory paragraph. The download button
   * and its link fallback are always appended after it, because the whole point of the email is
   * the document and an operator rewriting the greeting must not be able to remove it (ETP-4717,
   * reopened).</p>
   */
  private EmailContent buildContent(EmailDocumentRecord document, String downloadLink,
      String language, Optional<EmailMessageEdits> messageEdits) {
    EmailContent.Builder content = EmailContent.builder();
    // toHtmlBody() has already escaped the operator's text and turned newlines into <br>.
    String override = messageEdits.map(EmailMessageEdits::toHtmlBody).orElse(null);
    if (override != null) {
      // ETP-5003 — the operator's text now carries its own greeting, because the send modal shows
      // it in the message box so they can read and edit how the customer is addressed. Composing a
      // second greeting here would print it twice.
      content.paragraphHtml(override);
    } else {
      // Emphasis lives in the catalog as **markers**, the same syntax the operator sees and edits
      // in the send modal, so both paths render bold through one mechanism. Values are escaped
      // before interpolation; applyBold runs over the assembled string afterwards.
      String recipientName = StringUtils.trimToNull(document.getRecipientName());
      if (recipientName != null) {
        content.greetingHtml(EmailEscape.applyBold(EmailMessages.get("document.greeting", language,
            EmailEscape.escapeHtml(recipientName))));
      }
      content.paragraphHtml(EmailEscape.applyBold(EmailMessages.get("document.body", language,
          EmailEscape.escapeHtml(documentTypeLabel(language)),
          EmailEscape.escapeHtml(document.getDocumentNumber()))));
    }
    return content.cta(EmailMessages.get("document.cta", language), downloadLink)
        .linkFallbackText(EmailMessages.get("link.fallback", language))
        .signature(EmailMessages.get("signature", language))
        .build();
  }

  private String resolveSubject(EmailDocumentRecord document, String language,
      Optional<EmailMessageEdits> messageEdits) {
    String override = messageEdits.map(EmailMessageEdits::getSubject).orElse(null);
    return override != null ? override : buildSubject(document, language);
  }

  /**
   * Human-facing label of the document type, read from the message catalog under the contract's own
   * name.
   *
   * <p>It used to be a fixed Spanish string overridden by each subclass, which is how the subject
   * the send modal displays and the subject actually delivered came to disagree under
   * {@code en_US}.</p>
   *
   * @param language the recipient language
   * @return the localized document type label
   */
  protected String documentTypeLabel(String language) {
    // Falls back to the type the contract was constructed with rather than emitting the raw key:
    // a contract added without its catalog entry would otherwise put "foo-send.documentType" in
    // the subject line of a customer's email.
    return StringUtils.defaultIfBlank(
        EmailMessages.getOptional(name + ".documentType", language), documentType);
  }

  /**
   * Builds the default subject, deliberately mirroring the shape the send modal displays
   * ({@code {documentType} #{documentNo} — {businessPartner}}): the modal posts its own derived
   * subject back as the override, so the two must agree.
   */
  private String buildSubject(EmailDocumentRecord document, String language) {
    String label = documentTypeLabel(language);
    String recipientName = StringUtils.trimToNull(document.getRecipientName());
    return recipientName == null
        ? EmailMessages.get("document.subject", language, label, document.getDocumentNumber())
        : EmailMessages.get("document.subject.withRecipient", language, label,
            document.getDocumentNumber(), recipientName);
  }


  private Optional<String> resolveDownloadLink(EmailContractCommand command,
      EmailDocumentRecord document) {
    String configuredLink = document.getDownloadLink();
    if (EmailContractCommandSupport.isHttpUrl(configuredLink)) {
      return Optional.of(configuredLink);
    }
    String recordId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID);
    String documentRecordId = resolveEffectiveRecordId(document, recordId);
    // The download-link token key stays stable per record so re-sends with edited recipients do
    // not mint new tokens; it must not depend on the recipient-set hash.
    String downloadTokenKey = EmailContractCommandSupport.idempotencyKey(name,
        document.getClientId(), documentRecordId);
    if (StringUtils.isAnyBlank(documentRecordId, document.getClientId(), downloadTokenKey)) {
      return Optional.empty();
    }
    return DocumentDownloadTokenService.createDownloadLink(name, inferSpecName(), documentRecordId,
        document.getClientId(), downloadTokenKey);
  }

  /**
   * Server-derived send idempotency key: {@code {contract}:{tenant}:{record}:send:v1:
   * {recipientSetHash}}, with {@code :{contentHash}} appended only when the operator authored the
   * copy. The caller-supplied idempotency key is ignored for document sends.
   * <p>
   * The content hash matters: without it, correcting the message and re-sending to the same
   * recipients produces the same key as the previous send and is answered {@code DUPLICATE}, so the
   * corrected email is never delivered. It is appended conditionally so untouched sends keep
   * exactly the key they had before ETP-4717.
   */
  private String resolveSendIdempotencyKey(String tenantId, String recordId,
      EmailRecipientSet finalRecipients, Optional<EmailMessageEdits> messageEdits) {
    String normalizedTenant = StringUtils.defaultIfBlank(tenantId, "global");
    String key = name + ":" + normalizedTenant + ":" + recordId + ":send:"
        + EmailContractCommandSupport.VERSION + ":" + finalRecipients.recipientSetHash();
    return messageEdits.isPresent() ? key + ":" + messageEdits.get().contentHash() : key;
  }

  /**
   * Re-reads {@code messageEdits} for delivery-policy purposes. {@link #resolve} already rejected
   * malformed payloads with a 400, so a parse failure here cannot reach a real send and degrades to
   * "no edits".
   */
  private Optional<EmailMessageEdits> messageEditsQuietly(EmailContractCommand command) {
    try {
      return EmailMessageEdits.fromBody(command == null ? null : command.getBody());
    } catch (EmailMessageEdits.InvalidMessageEditsException e) {
      return Optional.empty();
    }
  }

  private String resolveEffectiveRecordId(EmailDocumentRecord document, String fallbackRecordId) {
    return EmailContractCommandSupport.firstNonBlank(
        document == null ? null : document.getRecordId(), fallbackRecordId);
  }

  private String inferSpecName() {
    return StringUtils.removeEnd(name, "-send");
  }

  private Optional<EmailDocumentRecord> resolveDocument(EmailContractCommand command) {
    if (documentResolver == null) {
      return Optional.empty();
    }
    return documentResolver.resolve(EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID));
  }
}
