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

final class EmailContractCommandSupport {

  static final String FIELD_ACCOUNT_ID = "accountId";
  static final String FIELD_CLIENT_ID = "clientId";
  static final String FIELD_DATE = "date";
  static final String FIELD_IDEMPOTENCY_KEY = "idempotencyKey";
  static final String FIELD_IP = "ip";
  static final String FIELD_LINK = "link";
  static final String FIELD_LOGIN_EVENT_ID = "loginEventId";
  static final String FIELD_RECORD_ID = "recordId";
  static final String FIELD_RECIPIENT = "recipient";
  static final String FIELD_TENANT_ID = "tenantId";
  static final String FIELD_USER_ID = "userId";
  static final String FIELD_VERSION = "version";
  static final String VERSION = "v1";

  private static final Pattern EMAIL_PATTERN = Pattern.compile(
      "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);

  private EmailContractCommandSupport() {
  }

  static EmailAuthorizationResult validateCommand(EmailContractCommand command,
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

  static String text(EmailContractCommand command, String field) {
    if (command == null) {
      return null;
    }
    JSONObject body = command.getBody();
    return body == null ? null : StringUtils.trimToNull(body.optString(field));
  }

  static String firstNonBlank(String first, String second) {
    String normalized = StringUtils.trimToNull(first);
    return normalized == null ? StringUtils.trimToNull(second) : normalized;
  }

  static boolean isValidEmail(String email) {
    String normalized = StringUtils.trimToNull(email);
    return normalized != null && EMAIL_PATTERN.matcher(normalized).matches();
  }

  static boolean isHttpUrl(String value) {
    String normalized = StringUtils.trimToNull(value);
    return normalized != null
        && (StringUtils.startsWithIgnoreCase(normalized, "https://")
        || StringUtils.startsWithIgnoreCase(normalized, "http://"));
  }

  static EmailRecipientResolution invalidRecipient() {
    return EmailRecipientResolution.rejected(400, "Email recipient is invalid");
  }

  static EmailDeliveryPolicy deliveryPolicy(String idempotencyKey,
      EmailThrottleRule... throttleRules) {
    return EmailDeliveryPolicy.of(idempotencyKey, Arrays.asList(throttleRules));
  }

  static String idempotencyKey(String contractName, String tenantId, String recordId) {
    String normalizedTenant = StringUtils.defaultIfBlank(tenantId, "global");
    return contractName + ":" + normalizedTenant + ":" + recordId + ":" + VERSION;
  }
}
