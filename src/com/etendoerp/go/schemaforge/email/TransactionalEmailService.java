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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

  private static final String MESSAGE_RECIPIENT_NOT_RESOLVED =
      "Email contract did not resolve a recipient";
  private static final String FIELD_DUPLICATE = "duplicate";
  private static final String FIELD_PROVIDER_STATUS = "providerStatus";
  private static final String FIELD_RETRY_AFTER_SECONDS = "retryAfterSeconds";
  private static final String MESSAGE_DUPLICATE =
      "Duplicate email request suppressed by idempotency key";
  private static final String MESSAGE_THROTTLED =
      "Transactional email throttle limit exceeded";
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

  /**
   * Creates the default executor with runtime provider configuration.
   */
  public TransactionalEmailService() {
    this(DefaultEmailContractRegistry.createDefault(), new ApiGatewayEmailProviderAdapter());
  }

  /**
   * Creates an executor with explicit registry and provider adapter dependencies.
   *
   * @param contractRegistry registry that resolves server-side email contracts
   * @param providerAdapter backend-only provider adapter
   */
  public TransactionalEmailService(EmailContractRegistry contractRegistry,
      EmailProviderAdapter providerAdapter) {
    this(contractRegistry, providerAdapter, new InMemoryEmailSafetyStore());
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
    this.contractRegistry = Objects.requireNonNull(contractRegistry,
        "Email contract registry cannot be null");
    this.providerAdapter = Objects.requireNonNull(providerAdapter,
        "Email provider adapter cannot be null");
    this.safetyStore = Objects.requireNonNull(safetyStore, "Email safety store cannot be null");
  }

  /**
   * Executes a named email contract command.
   *
   * @param contractName stable contract name from the route
   * @param commandBody contract-specific command body
   * @return NEO response with contract status and provider metadata when sent
   */
  public NeoResponse send(String contractName, JSONObject commandBody) {
    String normalizedContract = StringUtils.trimToNull(contractName);
    if (normalizedContract == null) {
      return contractResponse(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED, null,
          "Email contract name is required", null);
    }
    if (commandBody == null) {
      return contractResponse(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED,
          normalizedContract, "Email contract command body is required", null);
    }

    String forbiddenField = findForbiddenProviderField(commandBody);
    if (forbiddenField != null) {
      return contractResponse(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED,
          normalizedContract,
          "Email contract commands cannot include provider field: " + forbiddenField, null);
    }

    Optional<EmailContract> contract = contractRegistry.find(normalizedContract);
    if (!contract.isPresent()) {
      return contractResponse(HttpServletResponse.SC_NOT_FOUND, STATUS_VALIDATION_FAILED,
          normalizedContract, "Unknown email contract", null);
    }

    EmailContractCommand command;
    try {
      command = new EmailContractCommand(normalizedContract, new JSONObject(commandBody.toString()));
    } catch (JSONException e) {
      return contractResponse(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED,
          normalizedContract, "Invalid email contract command", null);
    }

    String callerRecipientField = findCallerRecipientField(commandBody);
    if (callerRecipientField != null && !contract.get().allowsCallerProvidedRecipients()) {
      return contractResponse(HttpServletResponse.SC_FORBIDDEN, STATUS_UNAUTHORIZED,
          normalizedContract,
          "Email contract does not allow caller-provided recipient field: "
              + callerRecipientField,
          null);
    }

    EmailAuthorizationResult authorization = contract.get().authorize(command);
    if (!authorization.isAllowed()) {
      return contractResponse(authorization.getHttpStatus(),
          authorizationFailureStatus(authorization.getHttpStatus()),
          normalizedContract, authorization.getMessage(), null);
    }

    EmailRecipientResolution recipient = contract.get().resolveRecipient(command);
    String recipientError = validateRecipientResolution(recipient, contract.get());
    if (recipientError != null) {
      int status = recipient != null && !recipient.isResolved() ? recipient.getHttpStatus()
          : HttpServletResponse.SC_BAD_REQUEST;
      return contractResponse(status, STATUS_VALIDATION_FAILED, normalizedContract,
          recipientError, null);
    }

    EmailContractResolution resolution = contract.get().resolve(command, recipient);
    Objects.requireNonNull(resolution, "Email contract resolution cannot be null");

    if (!resolution.isReady()) {
      return contractResponse(resolution.getHttpStatus(), resolution.getStatus(),
          normalizedContract, resolution.getMessage(), null);
    }

    EmailProviderRequest providerRequest = resolution.getProviderRequest();
    NeoResponse providerValidation = validateResolvedProviderRequest(normalizedContract,
        recipient, providerRequest);
    if (providerValidation != null) {
      return providerValidation;
    }

    EmailSendContext sendContext = new EmailSendContext(command, recipient, providerRequest);
    EmailDeliveryPolicy deliveryPolicy = Objects.requireNonNull(
        contract.get().deliveryPolicy(command, recipient, providerRequest),
        "Email delivery policy cannot be null");
    return enforceSafetyAndSubmit(sendContext, deliveryPolicy);
  }

  private static String authorizationFailureStatus(int httpStatus) {
    return httpStatus == HttpServletResponse.SC_FORBIDDEN ? STATUS_UNAUTHORIZED
        : STATUS_VALIDATION_FAILED;
  }

  private NeoResponse enforceSafetyAndSubmit(EmailSendContext sendContext,
      EmailDeliveryPolicy deliveryPolicy) {
    String idempotencyKey = deliveryPolicy.resolveIdempotencyKey(sendContext);
    NeoResponse safetyResponse = enforceSafetyChecks(sendContext, deliveryPolicy, idempotencyKey);
    if (safetyResponse != null) {
      return safetyResponse;
    }

    return submitProviderRequest(sendContext, idempotencyKey);
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

  private static NeoResponse validateResolvedProviderRequest(String normalizedContract,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String providerRequestError = validateProviderRequest(providerRequest);
    if (providerRequestError != null) {
      return contractResponse(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED,
          normalizedContract, providerRequestError, null);
    }
    if (!recipient.getRecipient().equals(providerRequest.getRecipient())) {
      return contractResponse(HttpServletResponse.SC_BAD_REQUEST, STATUS_VALIDATION_FAILED,
          normalizedContract,
          "Email contract provider request recipient must match recipient resolution", null);
    }
    return null;
  }

  private NeoResponse enforceSafetyChecks(EmailSendContext context,
      EmailDeliveryPolicy deliveryPolicy, String idempotencyKey) {
    EmailKillSwitchResult killSwitch = safetyStore.checkKillSwitch(context);
    if (!killSwitch.isAllowed()) {
      recordAudit(context, idempotencyKey, HttpServletResponse.SC_FORBIDDEN, STATUS_SUPPRESSED,
          killSwitch.getMessage(), null, false);
      return contractResponse(HttpServletResponse.SC_FORBIDDEN, STATUS_SUPPRESSED,
          context.getContractName(), killSwitch.getMessage(), suppressionExtra(killSwitch));
    }

    Optional<EmailAuditRecord> duplicate = safetyStore.findSentByIdempotencyKey(context,
        idempotencyKey);
    if (duplicate.isPresent()) {
      EmailAuditRecord prior = duplicate.get();
      recordAudit(context, idempotencyKey, HttpServletResponse.SC_OK, STATUS_DUPLICATE,
          MESSAGE_DUPLICATE, prior.getProviderStatus(), true);
      return contractResponse(HttpServletResponse.SC_OK, STATUS_DUPLICATE,
          context.getContractName(), MESSAGE_DUPLICATE, duplicateExtra(prior));
    }

    EmailThrottleResult throttle = safetyStore.checkAndIncrement(context,
        deliveryPolicy.getThrottleRules());
    if (!throttle.isAllowed()) {
      recordAudit(context, idempotencyKey, 429, STATUS_THROTTLED, MESSAGE_THROTTLED, null, false);
      return contractResponse(429, STATUS_THROTTLED, context.getContractName(),
          MESSAGE_THROTTLED, throttleExtra(throttle));
    }
    return null;
  }

  private NeoResponse submitProviderRequest(EmailSendContext context, String idempotencyKey) {
    if (!providerAdapter.isConfigured()) {
      recordAudit(context, idempotencyKey, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          STATUS_PROVIDER_FAILED, "Transactional email provider is not configured", null, false);
      return contractResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, STATUS_PROVIDER_FAILED,
          context.getContractName(), "Transactional email provider is not configured", null);
    }

    try {
      EmailProviderResponse providerResponse = providerAdapter.send(context.getProviderRequest());
      if (providerResponse.isSuccessful()) {
        JSONObject extra = new JSONObject();
        extra.put(FIELD_PROVIDER_STATUS, providerResponse.getStatusCode());
        extra.put(FIELD_DUPLICATE, false);
        extra.put(FIELD_RETRY_AFTER_SECONDS, JSONObject.NULL);
        recordAudit(context, idempotencyKey, HttpServletResponse.SC_OK, STATUS_SENT, null,
            providerResponse.getStatusCode(), false);
        return contractResponse(HttpServletResponse.SC_OK, STATUS_SENT, context.getContractName(),
            null, extra);
      }
      recordAudit(context, idempotencyKey, HttpServletResponse.SC_BAD_GATEWAY,
          STATUS_PROVIDER_FAILED, "Transactional email provider rejected the request",
          providerResponse.getStatusCode(), false);
      return contractResponse(HttpServletResponse.SC_BAD_GATEWAY, STATUS_PROVIDER_FAILED,
          context.getContractName(), "Transactional email provider rejected the request", null);
    } catch (IOException | JSONException e) {
      log.error("Error communicating with the email provider for contract [{}]: {}",
          context.getContractName(), e.getMessage(), e);
      recordAudit(context, idempotencyKey, HttpServletResponse.SC_BAD_GATEWAY,
          STATUS_PROVIDER_FAILED, "Transactional email provider is unavailable", null, false);
      return contractResponse(HttpServletResponse.SC_BAD_GATEWAY, STATUS_PROVIDER_FAILED,
          context.getContractName(), "Transactional email provider is unavailable", null);
    }
  }

  private void recordAudit(EmailSendContext context, String idempotencyKey, int httpStatus,
      String status, String message, Integer providerStatus, boolean duplicate) {
    safetyStore.recordAudit(EmailAuditRecord.create(context, idempotencyKey, httpStatus, status,
        message, providerStatus, duplicate));
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
          data.put(key, extra.get(key));
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
}
