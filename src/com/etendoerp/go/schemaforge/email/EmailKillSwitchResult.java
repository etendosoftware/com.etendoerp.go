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
 * Result of checking transactional email kill switches.
 */
public final class EmailKillSwitchResult {

  private static final EmailKillSwitchResult ALLOWED = new EmailKillSwitchResult(true, null, null,
      null);

  private final boolean requestAllowed;
  private final String scope;
  private final String key;
  private final String message;

  private EmailKillSwitchResult(boolean requestAllowed, String scope, String key,
      String message) {
    this.requestAllowed = requestAllowed;
    this.scope = scope;
    this.key = key;
    this.message = message;
  }

  /**
   * Creates an allowed kill-switch result.
   *
   * @return allowed result
   */
  public static EmailKillSwitchResult allowed() {
    return ALLOWED;
  }

  /**
   * Creates a suppressed kill-switch result.
   *
   * @param scope kill-switch scope
   * @param key kill-switch key
   * @param message client-visible suppression message
   * @return suppressed result
   */
  public static EmailKillSwitchResult suppressed(String scope, String key, String message) {
    return new EmailKillSwitchResult(false, scope, key, message);
  }

  /**
   * Indicates whether the request can continue.
   *
   * @return {@code true} when no kill switch applies
   */
  public boolean isAllowed() {
    return requestAllowed;
  }

  /**
   * Returns the kill-switch scope.
   *
   * @return scope name
   */
  public String getScope() {
    return scope;
  }

  /**
   * Returns the kill-switch key.
   *
   * @return scope key
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns the suppression message.
   *
   * @return client-visible suppression message
   */
  public String getMessage() {
    return message;
  }
}
