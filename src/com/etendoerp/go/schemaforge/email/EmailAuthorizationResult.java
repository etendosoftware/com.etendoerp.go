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
 * Result of contract-specific authorization.
 */
public final class EmailAuthorizationResult {

  private static final EmailAuthorizationResult ALLOWED =
      new EmailAuthorizationResult(true, 200, null);

  private final boolean authorized;
  private final int httpStatus;
  private final String message;

  private EmailAuthorizationResult(boolean authorized, int httpStatus, String message) {
    this.authorized = authorized;
    this.httpStatus = httpStatus;
    this.message = message;
  }

  /**
   * Creates an allowed authorization result.
   *
   * @return allowed result
   */
  public static EmailAuthorizationResult allowed() {
    return ALLOWED;
  }

  /**
   * Creates a rejected authorization result.
   *
   * @param httpStatus HTTP status for the rejection
   * @param message client-visible rejection message
   * @return rejected result
   */
  public static EmailAuthorizationResult rejected(int httpStatus, String message) {
    return new EmailAuthorizationResult(false, httpStatus, message);
  }

  /**
   * Indicates whether the contract command is authorized.
   *
   * @return {@code true} when execution may continue
   */
  public boolean isAllowed() {
    return authorized;
  }

  /**
   * Returns the HTTP status to use when authorization is rejected.
   *
   * @return rejection HTTP status
   */
  public int getHttpStatus() {
    return httpStatus;
  }

  /**
   * Returns the authorization rejection message.
   *
   * @return client-visible rejection message
   */
  public String getMessage() {
    return message;
  }
}
