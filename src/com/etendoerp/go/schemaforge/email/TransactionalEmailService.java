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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Executes transactional email contracts and delegates provider submission to a
 * backend-only adapter.
 */
public class TransactionalEmailService {

  private static final Logger log = LogManager.getLogger(TransactionalEmailService.class);

  public static final String STATUS_SENT = "SENT";
  public static final String STATUS_VALIDATION_FAILED = "VALIDATION_FAILED";
  public static final String STATUS_PROVIDER_FAILED = "PROVIDER_FAILED";
  public static final String STATUS_UNAUTHORIZED = "UNAUTHORIZED";
  public static final String STATUS_DUPLICATE = "DUPLICATE";
  public static final String STATUS_THROTTLED = "THROTTLED";
  public static final String STATUS_SUPPRESSED = "SUPPRESSED";
  public static final String STATUS_NO_RECIPIENT = "NO_RECIPIENT";
  static final int HTTP_UNPROCESSABLE_ENTITY = 422;

  private static final String MESSAGE_RECIPIENT_NOT_RESOLVED =
      "Email contract did not resolve a recipient";
  private static final String FIELD_DUPLICATE = "duplicate";
  private static final String FIELD_PROVIDER_STATUS = "providerStatus";
  private static final String FIELD_RETRY_AFTER_SECONDS = "retryAfterSeconds";
  private static final String MESSAGE_DUPLICATE =
      "Duplicate email request suppressed by idempotency key";
  private static final String MESSAGE_THROTTLED =
      "Transactional email throttle limit exceeded";
  private static final Object[] IDEMPOTENCY_LOCKS = createIdempotencyLocks();
  static final int HTTP_TOO_MANY_REQUESTS = 429;
  private static final Set<String> FORBIDDEN_COMMAND_FIELDS =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
          "to", "template", "data", "from", "sender", "fromEmail", "replyTo",
          "apiKey", "x-api-key")));
  private static final Set<String> CALLER_RECIPIENT_FIELDS =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
          "recipient", "recipients", "email", "emailAddress")));

  private final EmailContractRegistry contractRegistry;
  private final EmailProviderAdapter providerAdapter;
  private final EmailSafetyStore safetyStore;
  private final EmailObservabilitySink observabilitySink;

  /**
   * Creates the default executor with runtime provider configuration.
   */
  public TransactionalEmailService() {
    this(DefaultEmailContractRegistry.createDefault(), new ApiGatewayEmailProviderAdapter(),
        new DalEmailSafetyStore(), new LogEmailObservabilitySink());
  }

  /**
   * Creates an executor with explicit registry and provider adapter dependencies.
   *
   * @param contractRegistry registry that resolves server-side email contracts
   * @param providerAdapter backend-only provider adapter
   */
  public TransactionalEmailService(EmailContractRegistry contractRegistry,
      EmailProviderAdapter providerAdapter) {
    this(contractRegistry, providerAdapter, new InMemoryEmailSafetyStore(),
        new LogEmailObservabilitySink());
  }

  /**
   * Creates an executor with explicit registry, provider adapter, and safety store.
   *
   * @param contractRegistry registry that resolves server-side email contracts
   * @param providerAdapter backend-only provider adapter
   * @param safetyStore anti-abuse, idempotency, kill-switch, and audit store
   */
  public TransactionalEmailService(EmailContractRegistry contractRegistry,
      EmailProviderAdapter providerAdapter, EmailSafetyStore safetyStore) {
    this(contractRegistry, providerAdapter, safetyStore, new LogEmailObservabilitySink());
  }

  /**
   * Creates an executor with explicit registry, provider adapter, safety store,
   * and observability sink.
   *
   * @param contractRegistry registry that resolves server-side email contracts
   * @param providerAdapter backend-only provider adapter
   * @param safetyStore anti-abuse, idempotency, kill-switch, and audit store
   * @param observabilitySink redacted event sink for metrics/logging
   */
  TransactionalEmailService(EmailContractRegistry contractRegistry,
      EmailProviderAdapter providerAdapter, EmailSafetyStore safetyStore,
      EmailObservabilitySink observabilitySink) {
    this.contractRegistry = Objects.requireNonNull(contractRegistry,
        "Email contract registry cannot be null");
    this.providerAdapter = Objects.requireNonNull(providerAdapter,
        "Email provider adapter cannot be null");
    this.safetyStore = Objects.requireNonNull(safetyStore, "Email safety store cannot be null");
    this.observabilitySink = Objects.requireNonNull(observabilitySink,
        "Email observability sink cannot be null");
  }

  /**
   * Executes a named email contract command.
   *
   * @param contractName stable contract name from the route
   * @param commandBody contract-specific command body
   * @return NEO response with contract status and provider metadata when sent
   */
  /**
   * Answers the subject and message a contract would send if the operator edits nothing (ETP-5003).
   *
   * <p>Read-only, but authorized exactly like a send: the defaults name the document and its
   * business partner, so a caller who may not send the email may not read them either.</p>
   *
   * @param contractName the contract to ask
   * @param commandBody the command, carrying at least the record id
   * @return the defaults, or an error response mirroring the send authorization
   */
  public NeoResponse messageDefaults(String contractName, JSONObject commandBody) {
    String normalizedContract = StringUtils.trimToNull(contractName);
    if (normalizedContract == null || commandBody == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Email contract name and command body are required");
    }
    Optional<EmailContract> contract = contractRegistry.find(normalizedContract);
    if (!contract.isPresent()) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Unknown email contract");
    }
    EmailContractCommand command = new EmailContractCommand(normalizedContract, commandBody);
    EmailAuthorizationResult authorization = contract.get().authorize(command);
    if (!authorization.isAllowed()) {
      return NeoResponse.error(authorization.getHttpStatus(), authorization.getMessage());
    }
    Optional<EmailMessageDefaults> defaults = contract.get().messageDefaults(command);
    if (!defaults.isPresent()) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Email contract does not expose editable defaults");
    }
    try {
      JSONObject data = new JSONObject();
      data.put("subject", defaults.get().getSubject());
      data.put("message", defaults.get().getMessage());
      return NeoResponse.ok(data);
    } catch (JSONException e) {
      log.error("Could not serialize email defaults for [{}]", normalizedContract, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not build email defaults");
    }
  }

  public NeoResponse send(String contractName, JSONObject commandBody) {
    long startedAtNanos = System.nanoTime();
    String normalizedContract = StringUtils.trimToNull(contractName);
    if (normalizedContract == null) {
      return observedResponse(startedAtNanos, null, null,
          ResponseOutcome.of(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED, null,
              "Email contract name is required", null));
    }
    if (commandBody == null) {
      return observedResponse(startedAtNanos, null, null,
          ResponseOutcome.of(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED,
              normalizedContract, "Email contract command body is required", null));
    }

    String forbiddenField = findForbiddenProviderField(commandBody);
    if (forbiddenField != null) {
      return observedResponse(startedAtNanos, null, null,
          ResponseOutcome.of(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED,
              normalizedContract,
              "Email contract commands cannot include provider field: " + forbiddenField, null));
    }

    Optional<EmailContract> contract = contractRegistry.find(normalizedContract);
    if (!contract.isPresent()) {
      return observedResponse(startedAtNanos, null, null,
          ResponseOutcome.of(HttpServletResponse.SC_NOT_FOUND, STATUS_VALIDATION_FAILED,
              normalizedContract, "Unknown email contract", null));
    }

    EmailContractCommand command = new EmailContractCommand(normalizedContract, commandBody);

    String callerRecipientField = findCallerRecipientField(commandBody);
    if (callerRecipientField != null && !contract.get().allowsCallerProvidedRecipients()) {
      return observedResponse(startedAtNanos, command, null,
          ResponseOutcome.of(HttpServletResponse.SC_FORBIDDEN, STATUS_UNAUTHORIZED,
              normalizedContract,
              "Email contract does not allow caller-provided recipient field: "
                  + callerRecipientField,
              null));
    }

    EmailAuthorizationResult authorization = contract.get().authorize(command);
    if (!authorization.isAllowed()) {
      return observedResponse(startedAtNanos, command, null,
          ResponseOutcome.of(authorization.getHttpStatus(),
              authorizationFailureStatus(authorization.getHttpStatus()), normalizedContract,
              authorization.getMessage(), null));
    }

    EmailRecipientResolution recipient = contract.get().resolveRecipient(command);
    NeoResponse recipientRejection = validateRecipientStep(startedAtNanos, command, recipient,
        contract.get(), normalizedContract);
    if (recipientRejection != null) {
      return recipientRejection;
    }

    EmailContractResolution resolution = contract.get().resolve(command, recipient);
    Objects.requireNonNull(resolution, "Email contract resolution cannot be null");

    if (!resolution.isReady()) {
      return observedResponse(startedAtNanos, command, null,
          ResponseOutcome.of(resolution.getHttpStatus(), resolution.getStatus(),
              normalizedContract, resolution.getMessage(), null));
    }

    EmailProviderRequest providerRequest = resolution.getProviderRequest();
    NeoResponse providerValidation = validateResolvedProviderRequest(normalizedContract,
        command, recipient, providerRequest, startedAtNanos);
    if (providerValidation != null) {
      return providerValidation;
    }

    EmailSendContext sendContext = new EmailSendContext(command, recipient, providerRequest);

    NeoResponse capabilityRejection = validateProviderCapabilities(normalizedContract, command,
        providerRequest, startedAtNanos);
    if (capabilityRejection != null) {
      return capabilityRejection;
    }
    NeoResponse suppressionRejection = enforceRecipientSuppression(startedAtNanos, sendContext,
        providerRequest);
    if (suppressionRejection != null) {
      return suppressionRejection;
    }

    EmailDeliveryPolicy deliveryPolicy = Objects.requireNonNull(
        contract.get().deliveryPolicy(command, recipient, providerRequest),
        "Email delivery policy cannot be null");
    return enforceSafetyAndSubmit(startedAtNanos, sendContext, deliveryPolicy);
  }

  private NeoResponse validateRecipientStep(long startedAtNanos, EmailContractCommand command,
      EmailRecipientResolution recipient, EmailContract contract, String normalizedContract) {
    if (recipient != null && recipient.isNoRecipient()) {
      return observedResponse(startedAtNanos, command, null,
          ResponseOutcome.of(HTTP_UNPROCESSABLE_ENTITY, STATUS_NO_RECIPIENT, normalizedContract,
              recipient.getMessage(), null));
    }
    String recipientError = validateRecipientResolution(recipient, contract);
    if (recipientError != null) {
      int status = recipient != null && !recipient.isResolved() ? recipient.getHttpStatus()
          : HttpServletResponse.SC_BAD_REQUEST;
      return observedResponse(startedAtNanos, command, null,
          ResponseOutcome.of(status, STATUS_VALIDATION_FAILED, normalizedContract, recipientError,
              null));
    }
    return null;
  }

  private static String authorizationFailureStatus(int httpStatus) {
    return httpStatus == HttpServletResponse.SC_FORBIDDEN ? STATUS_UNAUTHORIZED
        : STATUS_VALIDATION_FAILED;
  }

  private NeoResponse enforceSafetyAndSubmit(long startedAtNanos, EmailSendContext sendContext,
      EmailDeliveryPolicy deliveryPolicy) {
    String idempotencyKey = deliveryPolicy.resolveIdempotencyKey(sendContext);
    if (StringUtils.isBlank(idempotencyKey)) {
      return enforceSafetyAndSubmitLocked(startedAtNanos, sendContext, deliveryPolicy,
          idempotencyKey);
    }
    synchronized (idempotencyLock(sendContext, idempotencyKey)) {
      return enforceSafetyAndSubmitLocked(startedAtNanos, sendContext, deliveryPolicy,
          idempotencyKey);
    }
  }

  private NeoResponse enforceSafetyAndSubmitLocked(long startedAtNanos,
      EmailSendContext sendContext, EmailDeliveryPolicy deliveryPolicy, String idempotencyKey) {
    NeoResponse safetyResponse = enforceSafetyChecks(startedAtNanos, sendContext, deliveryPolicy,
        idempotencyKey);
    if (safetyResponse != null) {
      return safetyResponse;
    }
    return submitProviderRequest(startedAtNanos, sendContext, idempotencyKey);
  }

  private static Object idempotencyLock(EmailSendContext sendContext, String idempotencyKey) {
    String lockKey = sendContext.getContractName() + "|" + sendContext.getTenantId() + "|"
        + idempotencyKey;
    return IDEMPOTENCY_LOCKS[Math.floorMod(lockKey.hashCode(), IDEMPOTENCY_LOCKS.length)];
  }

  private static Object[] createIdempotencyLocks() {
    Object[] locks = new Object[256];
    for (int i = 0; i < locks.length; i++) {
      locks[i] = new Object();
    }
    return locks;
  }

  private static String findForbiddenProviderField(JSONObject commandBody) {
    for (String field : FORBIDDEN_COMMAND_FIELDS) {
      if (commandBody.has(field)) {
        return field;
      }
    }
    return null;
  }

  private static String findCallerRecipientField(JSONObject commandBody) {
    for (String field : CALLER_RECIPIENT_FIELDS) {
      if (commandBody.has(field)) {
        return field;
      }
    }
    return null;
  }

  private static String validateRecipientResolution(EmailRecipientResolution recipient,
      EmailContract contract) {
    if (recipient == null) {
      return MESSAGE_RECIPIENT_NOT_RESOLVED;
    }
    if (!recipient.isResolved()) {
      return recipient.getMessage();
    }
    if (StringUtils.isBlank(recipient.getRecipient())) {
      return MESSAGE_RECIPIENT_NOT_RESOLVED;
    }
    if (recipient.isCallerProvided() && !contract.allowsCallerProvidedRecipients()) {
      return "Email contract does not allow caller-provided recipients";
    }
    return null;
  }

  private static String validateProviderRequest(EmailProviderRequest providerRequest) {
    if (providerRequest == null) {
      return "Email contract did not resolve a provider request";
    }
    if (StringUtils.isBlank(providerRequest.getRecipient())) {
      return MESSAGE_RECIPIENT_NOT_RESOLVED;
    }
    if (StringUtils.isBlank(providerRequest.getTemplate())) {
      return "Email contract did not resolve a template";
    }
    return null;
  }

  private NeoResponse validateResolvedProviderRequest(String normalizedContract,
      EmailContractCommand command, EmailRecipientResolution recipient,
      EmailProviderRequest providerRequest, long startedAtNanos) {
    String providerRequestError = validateProviderRequest(providerRequest);
    if (providerRequestError != null) {
      return observedResponse(startedAtNanos, command, null,
          ResponseOutcome.of(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED,
              normalizedContract, providerRequestError, null));
    }
    if (!recipient.getRecipient().equals(providerRequest.getRecipient())) {
      return observedResponse(startedAtNanos, command, null,
          ResponseOutcome.of(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED,
              normalizedContract,
              "Email contract provider request recipient must match recipient resolution", null));
    }
    return null;
  }

  private NeoResponse validateProviderCapabilities(String normalizedContract,
      EmailContractCommand command, EmailProviderRequest providerRequest, long startedAtNanos) {
    EmailRecipientSet recipients = providerRequest.getRecipients();
    if (recipients.totalCount() > 1 && !providerAdapter.supportsMultipleRecipients()) {
      return observedResponse(startedAtNanos, command, null,
          ResponseOutcome.of(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED,
              normalizedContract,
              "Email provider does not support multiple recipients", null));
    }
    if (!recipients.getCc().isEmpty() && !providerAdapter.supportsCcChannel()) {
      return observedResponse(startedAtNanos, command, null,
          ResponseOutcome.of(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED,
              normalizedContract,
              "Email provider does not support CC recipients", null));
    }
    return null;
  }

  private NeoResponse enforceRecipientSuppression(long startedAtNanos, EmailSendContext context,
      EmailProviderRequest providerRequest) {
    String tenantId = context.getTenantId();
    EmailRecipientSet recipients = providerRequest.getRecipients();
    List<String> all = new ArrayList<>(recipients.getTo());
    all.addAll(recipients.getCc());
    for (String address : all) {
      if (safetyStore.isRecipientSuppressed(tenantId, address)) {
        String message = "Email recipient is suppressed";
        recordAudit(context, null, HttpServletResponse.SC_FORBIDDEN, STATUS_SUPPRESSED, message,
            null, false);
        return observedResponse(startedAtNanos, context.getCommand(), context,
            ResponseOutcome.of(HttpServletResponse.SC_FORBIDDEN, STATUS_SUPPRESSED,
                context.getContractName(), message, null,
                ObservationFields.killSwitch(EmailThrottleRule.SCOPE_RECIPIENT)));
      }
    }
    return null;
  }

  private NeoResponse enforceSafetyChecks(long startedAtNanos, EmailSendContext context,
      EmailDeliveryPolicy deliveryPolicy, String idempotencyKey) {
    EmailKillSwitchResult killSwitch = safetyStore.checkKillSwitch(context);
    if (!killSwitch.isAllowed()) {
      recordAudit(context, idempotencyKey, HttpServletResponse.SC_FORBIDDEN, STATUS_SUPPRESSED,
          killSwitch.getMessage(), null, false);
      return observedResponse(startedAtNanos, context.getCommand(), context,
          ResponseOutcome.of(HttpServletResponse.SC_FORBIDDEN, STATUS_SUPPRESSED,
              context.getContractName(), killSwitch.getMessage(), suppressionExtra(killSwitch),
              ObservationFields.killSwitch(killSwitch.getScope())));
    }

    Optional<EmailAuditRecord> duplicate = safetyStore.findSentByIdempotencyKey(context,
        idempotencyKey);
    if (duplicate.isPresent()) {
      EmailAuditRecord prior = duplicate.get();
      recordAudit(context, idempotencyKey, HttpServletResponse.SC_OK, STATUS_DUPLICATE,
          MESSAGE_DUPLICATE, prior.getProviderStatus(), true);
      return observedResponse(startedAtNanos, context.getCommand(), context,
          ResponseOutcome.of(HttpServletResponse.SC_OK, STATUS_DUPLICATE,
              context.getContractName(), MESSAGE_DUPLICATE, duplicateExtra(prior),
              ObservationFields.duplicate(prior.getProviderStatus())));
    }

    EmailThrottleResult throttle = safetyStore.checkAndIncrement(context,
        deliveryPolicy.getThrottleRules());
    if (!throttle.isAllowed()) {
      recordAudit(context, idempotencyKey, HTTP_TOO_MANY_REQUESTS, STATUS_THROTTLED,
          MESSAGE_THROTTLED, null, false);
      return observedResponse(startedAtNanos, context.getCommand(), context,
          ResponseOutcome.of(HTTP_TOO_MANY_REQUESTS, STATUS_THROTTLED,
              context.getContractName(), MESSAGE_THROTTLED, throttleExtra(throttle),
              ObservationFields.throttle(throttle.getScope())));
    }
    return null;
  }

  private NeoResponse submitProviderRequest(long startedAtNanos, EmailSendContext context,
      String idempotencyKey) {
    if (!providerAdapter.isConfigured()) {
      recordAudit(context, idempotencyKey, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          STATUS_PROVIDER_FAILED, "Transactional email provider is not configured", null, false);
      return observedResponse(startedAtNanos, context.getCommand(), context,
          ResponseOutcome.of(HttpServletResponse.SC_SERVICE_UNAVAILABLE, STATUS_PROVIDER_FAILED,
              context.getContractName(), "Transactional email provider is not configured", null,
              ObservationFields.providerFailure(null, null, "ProviderNotConfigured")));
    }

    long providerStartedAtNanos = System.nanoTime();
    try {
      EmailProviderResponse providerResponse = providerAdapter.send(context.getProviderRequest());
      if (providerResponse == null) {
        throw new IOException("Provider adapter returned null response");
      }
      long providerDurationMillis = elapsedMillis(providerStartedAtNanos);
      if (providerResponse.isSuccessful()) {
        JSONObject extra = new JSONObject();
        extra.put(FIELD_PROVIDER_STATUS, providerResponse.getStatusCode());
        extra.put(FIELD_DUPLICATE, false);
        extra.put(FIELD_RETRY_AFTER_SECONDS, JSONObject.NULL);
        recordAudit(context, idempotencyKey, HttpServletResponse.SC_OK, STATUS_SENT, null,
            providerResponse.getStatusCode(), false);
        return observedResponse(startedAtNanos, context.getCommand(), context,
            ResponseOutcome.of(HttpServletResponse.SC_OK, STATUS_SENT, context.getContractName(),
                null, extra, ObservationFields.provider(providerResponse.getStatusCode(),
                    providerDurationMillis)));
      }
      recordAudit(context, idempotencyKey, HttpServletResponse.SC_BAD_GATEWAY,
          STATUS_PROVIDER_FAILED, "Transactional email provider rejected the request",
          providerResponse.getStatusCode(), false);
      return observedResponse(startedAtNanos, context.getCommand(), context,
          ResponseOutcome.of(HttpServletResponse.SC_BAD_GATEWAY, STATUS_PROVIDER_FAILED,
              context.getContractName(), "Transactional email provider rejected the request", null,
              ObservationFields.providerFailure(providerResponse.getStatusCode(),
                  providerDurationMillis, "ProviderRejected")));
    } catch (IOException | JSONException e) {
      log.error("Error communicating with the email provider for contract [{}]",
          context.getContractName(), e);
      long providerDurationMillis = elapsedMillis(providerStartedAtNanos);
      recordAudit(context, idempotencyKey, HttpServletResponse.SC_BAD_GATEWAY,
          STATUS_PROVIDER_FAILED, "Transactional email provider is unavailable", null, false);
      return observedResponse(startedAtNanos, context.getCommand(), context,
          ResponseOutcome.of(HttpServletResponse.SC_BAD_GATEWAY, STATUS_PROVIDER_FAILED,
              context.getContractName(), "Transactional email provider is unavailable", null,
              ObservationFields.providerFailure(null, providerDurationMillis, e.getClass()
                  .getSimpleName())));
    }
  }

  private void recordAudit(EmailSendContext context, String idempotencyKey, int httpStatus,
      String status, String message, Integer providerStatus, boolean duplicate) {
    safetyStore.recordAudit(EmailAuditRecord.create(context, idempotencyKey, httpStatus, status,
        message, providerStatus, duplicate));
  }

  private NeoResponse observedResponse(long startedAtNanos, EmailContractCommand command,
      EmailSendContext context, ResponseOutcome outcome) {
    observe(startedAtNanos, command, context, outcome);
    return contractResponse(outcome.httpStatus, outcome.status, outcome.contractName,
        outcome.message, outcome.extra);
  }

  private void observe(long startedAtNanos, EmailContractCommand command, EmailSendContext context,
      ResponseOutcome outcome) {
    // The event builder extracts only whitelisted metadata from command/context.
    EmailObservabilityEvent event = EmailObservabilityEvent.builder(outcome.status,
        outcome.httpStatus)
        .contractName(outcome.contractName)
        .command(command)
        .context(context)
        .message(outcome.message)
        .providerStatus(outcome.fields.providerStatus)
        .providerDurationMillis(outcome.fields.providerDurationMillis)
        .duplicate(outcome.fields.duplicate)
        .throttleScope(outcome.fields.throttleScope)
        .killSwitchScope(outcome.fields.killSwitchScope)
        .errorClass(outcome.fields.errorClass)
        .durationMillis(elapsedMillis(startedAtNanos))
        .build();
    observabilitySink.emit(event);
  }

  private static long elapsedMillis(long startedAtNanos) {
    return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedAtNanos));
  }

  private static JSONObject duplicateExtra(EmailAuditRecord prior) {
    try {
      JSONObject extra = new JSONObject();
      extra.put(FIELD_DUPLICATE, true);
      extra.put(FIELD_RETRY_AFTER_SECONDS, JSONObject.NULL);
      extra.put(FIELD_PROVIDER_STATUS, prior.getProviderStatus() == null ? JSONObject.NULL
          : prior.getProviderStatus());
      return extra;
    } catch (JSONException e) {
      log.debug("Could not build duplicate email response metadata: {}", e.getMessage(), e);
      return null;
    }
  }

  private static JSONObject throttleExtra(EmailThrottleResult throttle) {
    try {
      JSONObject extra = new JSONObject();
      extra.put(FIELD_DUPLICATE, false);
      extra.put(FIELD_RETRY_AFTER_SECONDS, throttle.getRetryAfterSeconds());
      extra.put("throttleScope", throttle.getScope());
      return extra;
    } catch (JSONException e) {
      log.debug("Could not build throttled email response metadata: {}", e.getMessage(), e);
      return null;
    }
  }

  private static JSONObject suppressionExtra(EmailKillSwitchResult killSwitch) {
    try {
      JSONObject extra = new JSONObject();
      extra.put(FIELD_DUPLICATE, false);
      extra.put(FIELD_RETRY_AFTER_SECONDS, JSONObject.NULL);
      extra.put("killSwitchScope", killSwitch.getScope());
      return extra;
    } catch (JSONException e) {
      log.debug("Could not build suppressed email response metadata: {}", e.getMessage(), e);
      return null;
    }
  }

  private static NeoResponse contractResponse(int httpStatus, String status, String contractName,
      String message, JSONObject extra) {
    try {
      JSONObject data = new JSONObject();
      data.put("status", status);
      data.put("contract", contractName == null ? JSONObject.NULL : contractName);
      if (message != null) {
        data.put("message", message);
      }
      if (extra != null) {
        for (java.util.Iterator<?> keys = extra.keys(); keys.hasNext();) {
          String key = String.valueOf(keys.next());
          data.put(key, extra.opt(key));
        }
      }

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return new NeoResponse(httpStatus, wrapper);
    } catch (JSONException e) {
      return NeoResponse.error(httpStatus, message == null ? status : message);
    }
  }

  private static final class ObservationFields {
    private final Integer providerStatus;
    private final Long providerDurationMillis;
    private final boolean duplicate;
    private final String throttleScope;
    private final String killSwitchScope;
    private final String errorClass;

    private ObservationFields(Integer providerStatus, Long providerDurationMillis,
        boolean duplicate, String throttleScope, String killSwitchScope, String errorClass) {
      this.providerStatus = providerStatus;
      this.providerDurationMillis = providerDurationMillis;
      this.duplicate = duplicate;
      this.throttleScope = throttleScope;
      this.killSwitchScope = killSwitchScope;
      this.errorClass = errorClass;
    }

    private static ObservationFields none() {
      return new ObservationFields(null, null, false, null, null, null);
    }

    private static ObservationFields duplicate(Integer providerStatus) {
      return new ObservationFields(providerStatus, null, true, null, null, null);
    }

    private static ObservationFields throttle(String throttleScope) {
      return new ObservationFields(null, null, false, throttleScope, null, null);
    }

    private static ObservationFields killSwitch(String killSwitchScope) {
      return new ObservationFields(null, null, false, null, killSwitchScope, null);
    }

    private static ObservationFields provider(Integer providerStatus, Long providerDurationMillis) {
      return new ObservationFields(providerStatus, providerDurationMillis, false, null, null,
          null);
    }

    private static ObservationFields providerFailure(Integer providerStatus,
        Long providerDurationMillis, String errorClass) {
      return new ObservationFields(providerStatus, providerDurationMillis, false, null, null,
          errorClass);
    }
  }

  private static final class ResponseOutcome {
    private final int httpStatus;
    private final String status;
    private final String contractName;
    private final String message;
    private final JSONObject extra;
    private final ObservationFields fields;

    private ResponseOutcome(int httpStatus, String status, String contractName, String message,
        JSONObject extra, ObservationFields fields) {
      this.httpStatus = httpStatus;
      this.status = status;
      this.contractName = contractName;
      this.message = message;
      this.extra = extra;
      this.fields = fields == null ? ObservationFields.none() : fields;
    }

    private static ResponseOutcome of(int httpStatus, String status, String contractName,
        String message, JSONObject extra) {
      return of(httpStatus, status, contractName, message, extra, ObservationFields.none());
    }

    private static ResponseOutcome of(int httpStatus, String status, String contractName,
        String message, JSONObject extra, ObservationFields fields) {
      return new ResponseOutcome(httpStatus, status, contractName, message, extra, fields);
    }
  }
}
