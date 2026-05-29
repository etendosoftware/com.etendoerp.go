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

public final class EmailContractCommandSupport {

  public static final String FIELD_ACCOUNT_ID = "accountId";
  public static final String FIELD_CLIENT_ID = "clientId";
  public static final String FIELD_DATE = "date";
  public static final String FIELD_IDEMPOTENCY_KEY = "idempotencyKey";
  public static final String FIELD_IP = "ip";
  public static final String FIELD_LINK = "link";
  public static final String FIELD_LOGIN_EVENT_ID = "loginEventId";
  public static final String FIELD_RECORD_ID = "recordId";
  public static final String FIELD_RECIPIENT = "recipient";
  public static final String FIELD_TENANT_ID = "tenantId";
  public static final String FIELD_USER_ID = "userId";
  public static final String FIELD_VERSION = "version";
  public static final String VERSION = "v1";

  private static final Pattern EMAIL_PATTERN = Pattern.compile(
      "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);

  private EmailContractCommandSupport() {
  }

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

  public static String text(EmailContractCommand command, String field) {
    if (command == null) {
      return null;
    }
    JSONObject body = command.getBody();
    return body == null ? null : StringUtils.trimToNull(body.optString(field));
  }

  public static String firstNonBlank(String first, String second) {
    String normalized = StringUtils.trimToNull(first);
    return normalized == null ? StringUtils.trimToNull(second) : normalized;
  }

  public static boolean isValidEmail(String email) {
    String normalized = StringUtils.trimToNull(email);
    return normalized != null && EMAIL_PATTERN.matcher(normalized).matches();
  }

  public static boolean isHttpUrl(String value) {
    String normalized = StringUtils.trimToNull(value);
    return normalized != null
        && (StringUtils.startsWithIgnoreCase(normalized, "https://")
        || StringUtils.startsWithIgnoreCase(normalized, "http://"));
  }

  public static EmailRecipientResolution invalidRecipient() {
    return EmailRecipientResolution.rejected(400, "Email recipient is invalid");
  }

  public static EmailDeliveryPolicy deliveryPolicy(String idempotencyKey,
      EmailThrottleRule... throttleRules) {
    return EmailDeliveryPolicy.of(idempotencyKey, Arrays.asList(throttleRules));
  }

  public static String idempotencyKey(String contractName, String tenantId, String recordId) {
    String normalizedTenant = StringUtils.defaultIfBlank(tenantId, "global");
    return contractName + ":" + normalizedTenant + ":" + recordId + ":" + VERSION;
  }
}
