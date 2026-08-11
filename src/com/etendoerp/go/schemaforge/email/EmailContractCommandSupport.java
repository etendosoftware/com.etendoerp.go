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

import java.util.Arrays;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;

/**
 * Shared helpers for validating and reading transactional email contract commands.
 */
public final class EmailContractCommandSupport {

  public static final String FIELD_ACCOUNT_ID = "accountId";
  public static final String FIELD_CLIENT_ID = "clientId";
  public static final String FIELD_DATE = "date";
  public static final String FIELD_IDEMPOTENCY_KEY = "idempotencyKey";
  public static final String FIELD_IP = "ip";
  public static final String FIELD_LANGUAGE = "language";
  public static final String FIELD_LINK = "link";
  public static final String FIELD_LOGIN_EVENT_ID = "loginEventId";
  public static final String FIELD_MESSAGE_EDITS = "messageEdits";
  public static final String FIELD_RECORD_ID = "recordId";
  public static final String FIELD_RECIPIENT = "recipient";
  public static final String FIELD_RECIPIENT_EDITS = "recipientEdits";
  public static final String FIELD_TENANT_ID = "tenantId";
  public static final String FIELD_USER_ID = "userId";
  public static final String FIELD_VERSION = "version";
  public static final String VERSION = "v1";

  private static final Pattern EMAIL_PATTERN = Pattern.compile(
      "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);

  private EmailContractCommandSupport() {
  }

  /**
   * Validates contract version and required command fields.
   *
   * @param command contract command received by the service
   * @param requiredFields body fields that must be present and non-blank
   * @return allowed result when the command is valid, otherwise a rejection
   */
  public static EmailAuthorizationResult validateCommand(EmailContractCommand command,
      String... requiredFields) {
    String version = text(command, FIELD_VERSION);
    if (version != null && !VERSION.equals(version)) {
      return EmailAuthorizationResult.rejected(400, "Unsupported email contract version");
    }
    for (String field : requiredFields) {
      if (text(command, field) == null) {
        return EmailAuthorizationResult.rejected(400,
            "Missing required email contract field: " + field);
      }
    }
    return EmailAuthorizationResult.allowed();
  }

  /**
   * Reads a normalized text field from the command body.
   *
   * @param command contract command received by the service
   * @param field body field name
   * @return trimmed value or {@code null}
   */
  public static String text(EmailContractCommand command, String field) {
    if (command == null) {
      return null;
    }
    JSONObject body = command.getBody();
    return body == null ? null : StringUtils.trimToNull(body.optString(field));
  }

  /**
   * Returns the first non-blank value from two candidates.
   *
   * @param first first candidate value
   * @param second fallback candidate value
   * @return normalized value or {@code null}
   */
  public static String firstNonBlank(String first, String second) {
    String normalized = StringUtils.trimToNull(first);
    return normalized == null ? StringUtils.trimToNull(second) : normalized;
  }

  /**
   * Checks whether a value looks like a syntactically valid email address.
   *
   * @param email candidate email address
   * @return {@code true} when the value matches the contract email pattern
   */
  public static boolean isValidEmail(String email) {
    String normalized = StringUtils.trimToNull(email);
    return normalized != null && EMAIL_PATTERN.matcher(normalized).matches();
  }

  /**
   * Checks whether a value is an absolute HTTP or HTTPS URL.
   *
   * @param value candidate URL
   * @return {@code true} when the value starts with {@code http://} or {@code https://}
   */
  public static boolean isHttpUrl(String value) {
    String normalized = StringUtils.trimToNull(value);
    return normalized != null
        && (StringUtils.startsWithIgnoreCase(normalized, "https://")
        || StringUtils.startsWithIgnoreCase(normalized, "http://"));
  }

  /**
   * Rejects a command carrying {@code recipientEdits} for contracts outside the document-send
   * family (and document contracts with editing disabled).
   *
   * @param command contract command received by the service
   * @return rejection when {@code recipientEdits} is present, otherwise an allowed result
   */
  public static EmailAuthorizationResult rejectRecipientEditsIfPresent(
      EmailContractCommand command) {
    JSONObject body = command == null ? null : command.getBody();
    if (body != null && body.has(FIELD_RECIPIENT_EDITS)) {
      return EmailAuthorizationResult.rejected(400,
          "recipientEdits is not accepted by this contract");
    }
    return EmailAuthorizationResult.allowed();
  }

  /**
   * Rejects a command carrying {@code messageEdits} for contracts outside the document-send family.
   * Auth and notice contracts own their copy entirely; a caller must never be able to author their
   * subject or body (ETP-4717).
   *
   * @param command contract command received by the service
   * @return rejection when {@code messageEdits} is present, otherwise an allowed result
   */
  public static EmailAuthorizationResult rejectMessageEditsIfPresent(
      EmailContractCommand command) {
    JSONObject body = command == null ? null : command.getBody();
    if (body != null && body.has(FIELD_MESSAGE_EDITS)) {
      return EmailAuthorizationResult.rejected(400,
          "messageEdits is not accepted by this contract");
    }
    return EmailAuthorizationResult.allowed();
  }

  /**
   * Builds the standard rejection used when a server-resolved recipient is invalid.
   *
   * @return recipient rejection result
   */
  public static EmailRecipientResolution invalidRecipient() {
    return EmailRecipientResolution.rejected(400, "Email recipient is invalid");
  }

  /**
   * Builds a delivery policy from an idempotency key and throttle rules.
   *
   * @param idempotencyKey idempotency key for the email event
   * @param throttleRules throttle rules applied before delivery
   * @return delivery policy
   */
  public static EmailDeliveryPolicy deliveryPolicy(String idempotencyKey,
      EmailThrottleRule... throttleRules) {
    return EmailDeliveryPolicy.of(idempotencyKey, Arrays.asList(throttleRules));
  }

  /**
   * Builds the standard versioned idempotency key for contract deliveries.
   *
   * @param contractName email contract name
   * @param tenantId tenant id, or global when blank
   * @param recordId trusted record id for the delivery
   * @return versioned idempotency key
   */
  public static String idempotencyKey(String contractName, String tenantId, String recordId) {
    String normalizedTenant = StringUtils.defaultIfBlank(tenantId, "global");
    return contractName + ":" + normalizedTenant + ":" + recordId + ":" + VERSION;
  }
}
