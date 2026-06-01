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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

/**
 * Redacted terminal event emitted by the transactional email executor.
 */
final class EmailObservabilityEvent {

  /**
   * Counter for all terminal email send outcomes.
   */
  static final String METRIC_SEND_TOTAL = "sf_email_send_total";

  /**
   * Histogram for provider call duration.
   */
  static final String METRIC_PROVIDER_DURATION_SECONDS =
      "sf_email_provider_duration_seconds";

  /**
   * Counter for throttle-blocked email attempts.
   */
  static final String METRIC_THROTTLE_TOTAL = "sf_email_throttle_total";

  /**
   * Counter for idempotent duplicate email attempts.
   */
  static final String METRIC_DUPLICATE_TOTAL = "sf_email_duplicate_total";

  /**
   * Counter for suppressed email attempts.
   */
  static final String METRIC_SUPPRESSION_TOTAL = "sf_email_suppression_total";

  /**
   * Counter for kill switch email suppressions.
   */
  static final String METRIC_KILL_SWITCH_TOTAL = "sf_email_kill_switch_total";

  /**
   * Counter for provider error outcomes.
   */
  static final String METRIC_PROVIDER_ERROR_TOTAL = "sf_email_provider_error_total";

  private final String contractName;
  private final String version;
  private final String tenantId;
  private final String userId;
  private final String recordId;
  private final String template;
  private final String recipientDomain;
  private final String recipientHash;
  private final int httpStatus;
  private final String status;
  private final String message;
  private final Integer providerStatus;
  private final Long providerDurationMillis;
  private final boolean duplicate;
  private final String throttleScope;
  private final String killSwitchScope;
  private final String errorClass;
  private final long durationMillis;
  private final List<String> metricNames;

  private EmailObservabilityEvent(Builder builder) {
    this.contractName = builder.contractName;
    this.version = builder.version;
    this.tenantId = builder.tenantId;
    this.userId = builder.userId;
    this.recordId = builder.recordId;
    this.template = builder.template;
    this.recipientDomain = builder.recipientDomain;
    this.recipientHash = builder.recipientHash;
    this.httpStatus = builder.httpStatus;
    this.status = builder.status;
    this.message = builder.message;
    this.providerStatus = builder.providerStatus;
    this.providerDurationMillis = builder.providerDurationMillis;
    this.duplicate = builder.duplicate;
    this.throttleScope = builder.throttleScope;
    this.killSwitchScope = builder.killSwitchScope;
    this.errorClass = builder.errorClass;
    this.durationMillis = builder.durationMillis;
    this.metricNames = Collections.unmodifiableList(resolveMetricNames());
  }

  /**
   * Creates an event builder.
   *
   * @param status terminal executor status
   * @param httpStatus response HTTP status
   * @return event builder
   */
  static Builder builder(String status, int httpStatus) {
    return new Builder(status, httpStatus);
  }

  /**
   * Returns the contract name.
   *
   * @return contract name
   */
  String getContractName() {
    return contractName;
  }

  /**
   * Returns the command version.
   *
   * @return command version
   */
  String getVersion() {
    return version;
  }

  /**
   * Returns the tenant or client id.
   *
   * @return tenant or client id
   */
  String getTenantId() {
    return tenantId;
  }

  /**
   * Returns the user id.
   *
   * @return user id
   */
  String getUserId() {
    return userId;
  }

  /**
   * Returns the business record id.
   *
   * @return business record id
   */
  String getRecordId() {
    return recordId;
  }

  /**
   * Returns the provider template.
   *
   * @return provider template
   */
  String getTemplate() {
    return template;
  }

  /**
   * Returns the recipient domain.
   *
   * @return recipient domain
   */
  String getRecipientDomain() {
    return recipientDomain;
  }

  /**
   * Returns the stable redacted recipient hash.
   *
   * @return recipient hash
   */
  String getRecipientHash() {
    return recipientHash;
  }

  /**
   * Returns the executor HTTP status.
   *
   * @return HTTP status
   */
  int getHttpStatus() {
    return httpStatus;
  }

  /**
   * Returns the executor status.
   *
   * @return executor status
   */
  String getStatus() {
    return status;
  }

  /**
   * Returns the safe outcome message.
   *
   * @return safe outcome message
   */
  String getMessage() {
    return message;
  }

  /**
   * Returns the provider HTTP status when a provider response exists.
   *
   * @return provider HTTP status
   */
  Integer getProviderStatus() {
    return providerStatus;
  }

  /**
   * Returns the provider call duration in milliseconds.
   *
   * @return provider duration in milliseconds
   */
  Long getProviderDurationMillis() {
    return providerDurationMillis;
  }

  /**
   * Indicates whether the outcome is an idempotent duplicate.
   *
   * @return {@code true} for duplicate outcomes
   */
  boolean isDuplicate() {
    return duplicate;
  }

  /**
   * Returns the throttle scope that blocked the request.
   *
   * @return throttle scope
   */
  String getThrottleScope() {
    return throttleScope;
  }

  /**
   * Returns the kill switch scope that suppressed the request.
   *
   * @return kill switch scope
   */
  String getKillSwitchScope() {
    return killSwitchScope;
  }

  /**
   * Returns the generic provider or configuration error class.
   *
   * @return error class
   */
  String getErrorClass() {
    return errorClass;
  }

  /**
   * Returns the end-to-end executor duration in milliseconds.
   *
   * @return executor duration in milliseconds
   */
  long getDurationMillis() {
    return durationMillis;
  }

  /**
   * Returns the metric names represented by this terminal event.
   *
   * @return immutable metric name list
   */
  List<String> getMetricNames() {
    return metricNames;
  }

  /**
   * Checks whether this event maps to a metric name.
   *
   * @param metricName metric name
   * @return {@code true} when the metric is present
   */
  boolean hasMetric(String metricName) {
    return metricNames.contains(metricName);
  }

  private List<String> resolveMetricNames() {
    List<String> names = new ArrayList<>();
    names.add(METRIC_SEND_TOTAL);
    if (providerDurationMillis != null) {
      names.add(METRIC_PROVIDER_DURATION_SECONDS);
    }
    if (TransactionalEmailService.STATUS_THROTTLED.equals(status)) {
      names.add(METRIC_THROTTLE_TOTAL);
    }
    if (TransactionalEmailService.STATUS_DUPLICATE.equals(status)) {
      names.add(METRIC_DUPLICATE_TOTAL);
    }
    if (TransactionalEmailService.STATUS_SUPPRESSED.equals(status)) {
      names.add(METRIC_SUPPRESSION_TOTAL);
    }
    if (killSwitchScope != null) {
      names.add(METRIC_KILL_SWITCH_TOTAL);
    }
    if (TransactionalEmailService.STATUS_PROVIDER_FAILED.equals(status)) {
      names.add(METRIC_PROVIDER_ERROR_TOTAL);
    }
    return names;
  }

  /**
   * Builder for redacted email observability events.
   */
  static final class Builder {
    private String contractName;
    private String version;
    private String tenantId;
    private String userId;
    private String recordId;
    private String template;
    private String recipientDomain;
    private String recipientHash;
    private final int httpStatus;
    private final String status;
    private String message;
    private Integer providerStatus;
    private Long providerDurationMillis;
    private boolean duplicate;
    private String throttleScope;
    private String killSwitchScope;
    private String errorClass;
    private long durationMillis;

    private Builder(String status, int httpStatus) {
      this.status = StringUtils.trimToNull(status);
      this.httpStatus = httpStatus;
    }

    Builder contractName(String value) {
      this.contractName = StringUtils.trimToNull(value);
      return this;
    }

    Builder command(EmailContractCommand command) {
      if (command == null) {
        return this;
      }
      contractName(command.getContractName());
      JSONObjectReader reader = new JSONObjectReader(command);
      this.version = reader.text("version");
      String commandTenantId = reader.text("tenantId");
      this.tenantId = StringUtils.isNotBlank(commandTenantId) ? commandTenantId
          : reader.text("clientId");
      this.userId = reader.text("userId");
      this.recordId = reader.text("recordId");
      return this;
    }

    Builder context(EmailSendContext context) {
      if (context == null) {
        return this;
      }
      command(context.getCommand());
      this.template = StringUtils.trimToNull(context.getTemplate());
      this.recipientDomain = StringUtils.trimToNull(context.getRecipientDomain());
      this.recipientHash = recipientHash(context.getRecipientAddress());
      return this;
    }

    private static String recipientHash(String recipient) {
      String normalized = StringUtils.trimToNull(recipient);
      if (normalized == null) {
        return null;
      }
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(normalized.toLowerCase(Locale.ROOT)
            .getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder(hash.length * 2);
        for (byte item : hash) {
          int unsignedByte = item & 0xff;
          value.append(Character.forDigit((unsignedByte >>> 4) & 0xf, 16));
          value.append(Character.forDigit(unsignedByte & 0xf, 16));
        }
        return value.toString();
      } catch (NoSuchAlgorithmException e) {
        throw new OBException("SHA-256 is required for email recipient hashing", e);
      }
    }

    Builder message(String value) {
      this.message = StringUtils.trimToNull(value);
      return this;
    }

    Builder providerStatus(Integer value) {
      this.providerStatus = value;
      return this;
    }

    Builder providerDurationMillis(Long value) {
      this.providerDurationMillis = value;
      return this;
    }

    Builder duplicate(boolean value) {
      this.duplicate = value;
      return this;
    }

    Builder throttleScope(String value) {
      this.throttleScope = StringUtils.trimToNull(value);
      return this;
    }

    Builder killSwitchScope(String value) {
      this.killSwitchScope = StringUtils.trimToNull(value);
      return this;
    }

    Builder errorClass(String value) {
      this.errorClass = StringUtils.trimToNull(value);
      return this;
    }

    Builder durationMillis(long value) {
      this.durationMillis = Math.max(0L, value);
      return this;
    }

    EmailObservabilityEvent build() {
      return new EmailObservabilityEvent(this);
    }
  }

  private static final class JSONObjectReader {
    private final EmailContractCommand command;

    private JSONObjectReader(EmailContractCommand command) {
      this.command = command;
    }

    private String text(String field) {
      JSONObject body = command.getBody();
      if (body == null) {
        return null;
      }
      return StringUtils.trimToNull(body.optString(field));
    }
  }
}
