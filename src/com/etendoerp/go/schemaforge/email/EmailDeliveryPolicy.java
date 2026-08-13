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
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;

/**
 * Anti-abuse policy selected by an email contract for one send attempt.
 */
public final class EmailDeliveryPolicy {

  private static final EmailDeliveryPolicy EMPTY = new EmailDeliveryPolicy(null,
      Collections.emptyList(), false);

  private final String idempotencyKey;
  private final List<EmailThrottleRule> throttleRules;
  private final boolean serverDerivedIdempotency;

  private EmailDeliveryPolicy(String idempotencyKey, List<EmailThrottleRule> throttleRules,
      boolean serverDerivedIdempotency) {
    this.idempotencyKey = StringUtils.trimToNull(idempotencyKey);
    this.throttleRules = Collections.unmodifiableList(new ArrayList<>(throttleRules));
    this.serverDerivedIdempotency = serverDerivedIdempotency;
  }

  /**
   * Creates an empty policy with command-level idempotency fallback only.
   *
   * @return empty delivery policy
   */
  public static EmailDeliveryPolicy empty() {
    return EMPTY;
  }

  /**
   * Creates a policy with explicit idempotency and throttle rules.
   *
   * @param idempotencyKey optional idempotency key selected by the contract
   * @param throttleRules throttle rules selected by the contract
   * @return delivery policy
   */
  public static EmailDeliveryPolicy of(String idempotencyKey,
      List<EmailThrottleRule> throttleRules) {
    return new EmailDeliveryPolicy(idempotencyKey,
        throttleRules == null ? Collections.emptyList() : throttleRules, false);
  }

  /**
   * Creates a policy whose idempotency key is server-derived; any caller-supplied key is ignored.
   *
   * @param idempotencyKey server-derived idempotency key
   * @param throttleRules throttle rules selected by the contract
   * @return delivery policy with server-derived idempotency
   */
  public static EmailDeliveryPolicy serverDerived(String idempotencyKey,
      List<EmailThrottleRule> throttleRules) {
    return new EmailDeliveryPolicy(idempotencyKey,
        throttleRules == null ? Collections.emptyList() : throttleRules, true);
  }

  /**
   * Indicates whether the idempotency key is server-derived and the caller key must be ignored.
   *
   * @return {@code true} when the caller-supplied idempotency key is ignored
   */
  public boolean isServerDerivedIdempotency() {
    return serverDerivedIdempotency;
  }

  /**
   * Resolves the idempotency key for this send attempt.
   *
   * @param context resolved send context
   * @return explicit policy key or command body idempotency key
   */
  public String resolveIdempotencyKey(EmailSendContext context) {
    if (idempotencyKey != null) {
      return idempotencyKey;
    }
    if (serverDerivedIdempotency || context == null || context.getCommand() == null) {
      return null;
    }
    JSONObject body = context.getCommand().getBody();
    return body == null ? null : StringUtils.trimToNull(body.optString(
        EmailContractCommandSupport.FIELD_IDEMPOTENCY_KEY));
  }

  /**
   * Returns the throttle rules to apply before provider submission.
   *
   * @return immutable throttle rule list
   */
  public List<EmailThrottleRule> getThrottleRules() {
    return throttleRules;
  }
}
