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

import java.util.Objects;

/**
 * Throttle rule applied to one anti-abuse scope.
 */
public final class EmailThrottleRule {

  public static final String SCOPE_GLOBAL = "GLOBAL";
  public static final String SCOPE_TENANT = "TENANT";
  public static final String SCOPE_USER = "USER";
  public static final String SCOPE_TEMPLATE = "TEMPLATE";
  public static final String SCOPE_RECIPIENT = "RECIPIENT";
  public static final String SCOPE_DOMAIN = "DOMAIN";
  public static final String SCOPE_RECORD = "RECORD";

  private final String scope;
  private final int maxAttempts;
  private final int windowSeconds;

  private EmailThrottleRule(String scope, int maxAttempts, int windowSeconds) {
    this.scope = Objects.requireNonNull(scope, "Email throttle scope cannot be null");
    this.maxAttempts = Math.max(1, maxAttempts);
    this.windowSeconds = Math.max(1, windowSeconds);
  }

  /**
   * Creates a global throttle rule.
   *
   * @param maxAttempts max attempts in the window
   * @param windowSeconds window size in seconds
   * @return throttle rule
   */
  public static EmailThrottleRule global(int maxAttempts, int windowSeconds) {
    return new EmailThrottleRule(SCOPE_GLOBAL, maxAttempts, windowSeconds);
  }

  /**
   * Creates a per-tenant throttle rule.
   *
   * @param maxAttempts max attempts in the window
   * @param windowSeconds window size in seconds
   * @return throttle rule
   */
  public static EmailThrottleRule perTenant(int maxAttempts, int windowSeconds) {
    return new EmailThrottleRule(SCOPE_TENANT, maxAttempts, windowSeconds);
  }

  /**
   * Creates a per-user throttle rule.
   *
   * @param maxAttempts max attempts in the window
   * @param windowSeconds window size in seconds
   * @return throttle rule
   */
  public static EmailThrottleRule perUser(int maxAttempts, int windowSeconds) {
    return new EmailThrottleRule(SCOPE_USER, maxAttempts, windowSeconds);
  }

  /**
   * Creates a per-template throttle rule.
   *
   * @param maxAttempts max attempts in the window
   * @param windowSeconds window size in seconds
   * @return throttle rule
   */
  public static EmailThrottleRule perTemplate(int maxAttempts, int windowSeconds) {
    return new EmailThrottleRule(SCOPE_TEMPLATE, maxAttempts, windowSeconds);
  }

  /**
   * Creates a per-recipient throttle rule.
   *
   * @param maxAttempts max attempts in the window
   * @param windowSeconds window size in seconds
   * @return throttle rule
   */
  public static EmailThrottleRule perRecipient(int maxAttempts, int windowSeconds) {
    return new EmailThrottleRule(SCOPE_RECIPIENT, maxAttempts, windowSeconds);
  }

  /**
   * Creates a per-recipient-domain throttle rule.
   *
   * @param maxAttempts max attempts in the window
   * @param windowSeconds window size in seconds
   * @return throttle rule
   */
  public static EmailThrottleRule perDomain(int maxAttempts, int windowSeconds) {
    return new EmailThrottleRule(SCOPE_DOMAIN, maxAttempts, windowSeconds);
  }

  /**
   * Creates a per-business-record throttle rule.
   *
   * @param maxAttempts max attempts in the window
   * @param windowSeconds window size in seconds
   * @return throttle rule
   */
  public static EmailThrottleRule perRecord(int maxAttempts, int windowSeconds) {
    return new EmailThrottleRule(SCOPE_RECORD, maxAttempts, windowSeconds);
  }

  /**
   * Returns the throttle scope.
   *
   * @return scope name
   */
  public String getScope() {
    return scope;
  }

  /**
   * Returns the max attempts allowed inside the window.
   *
   * @return max attempts
   */
  public int getMaxAttempts() {
    return maxAttempts;
  }

  /**
   * Returns the throttle window size.
   *
   * @return window size in seconds
   */
  public int getWindowSeconds() {
    return windowSeconds;
  }

  String resolveKey(EmailSendContext context) {
    switch (scope) {
      case SCOPE_GLOBAL:
        return "global";
      case SCOPE_TENANT:
        return context.getTenantId();
      case SCOPE_USER:
        return context.getUserId();
      case SCOPE_TEMPLATE:
        return context.getTemplate();
      case SCOPE_RECIPIENT:
        return context.getRecipientAddress();
      case SCOPE_DOMAIN:
        return context.getRecipientDomain();
      case SCOPE_RECORD:
        return context.getRecordId();
      default:
        return null;
    }
  }
}
