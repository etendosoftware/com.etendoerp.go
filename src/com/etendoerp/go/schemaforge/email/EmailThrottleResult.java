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

/**
 * Result of checking throttle rules for one send attempt.
 */
public final class EmailThrottleResult {

  private static final EmailThrottleResult ALLOWED = new EmailThrottleResult(true, null, null, 0);

  private final boolean requestAllowed;
  private final String scope;
  private final String key;
  private final int retryAfterSeconds;

  private EmailThrottleResult(boolean requestAllowed, String scope, String key,
      int retryAfterSeconds) {
    this.requestAllowed = requestAllowed;
    this.scope = scope;
    this.key = key;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /**
   * Creates an allowed result.
   *
   * @return allowed throttle result
   */
  public static EmailThrottleResult allowed() {
    return ALLOWED;
  }

  /**
   * Creates a throttled result.
   *
   * @param scope throttle scope
   * @param key throttle key
   * @param retryAfterSeconds seconds until the window resets
   * @return throttled result
   */
  public static EmailThrottleResult throttled(String scope, String key, int retryAfterSeconds) {
    return new EmailThrottleResult(false, scope, key, Math.max(1, retryAfterSeconds));
  }

  /**
   * Indicates whether the request can continue.
   *
   * @return {@code true} when not throttled
   */
  public boolean isAllowed() {
    return requestAllowed;
  }

  /**
   * Returns the throttle scope that rejected the request.
   *
   * @return throttle scope
   */
  public String getScope() {
    return scope;
  }

  /**
   * Returns the throttle key that rejected the request.
   *
   * @return throttle key
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns the retry-after value.
   *
   * @return retry-after seconds
   */
  public int getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
