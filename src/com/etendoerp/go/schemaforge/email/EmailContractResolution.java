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
 * Result of resolving an email contract command.
 */
public final class EmailContractResolution {

  private final boolean ready;
  private final int httpStatus;
  private final String status;
  private final String message;
  private final EmailProviderRequest providerRequest;

  private EmailContractResolution(boolean ready, int httpStatus, String status, String message,
      EmailProviderRequest providerRequest) {
    this.ready = ready;
    this.httpStatus = httpStatus;
    this.status = status;
    this.message = message;
    this.providerRequest = providerRequest;
  }

  /**
   * Creates a successful contract resolution ready for provider submission.
   *
   * @param providerRequest provider request resolved by the contract
   * @return ready contract resolution
   */
  public static EmailContractResolution ready(EmailProviderRequest providerRequest) {
    return new EmailContractResolution(true, 200, TransactionalEmailService.STATUS_SENT, null,
        Objects.requireNonNull(providerRequest, "Email provider request cannot be null"));
  }

  /**
   * Creates a rejected contract resolution with a client-visible status.
   *
   * @param httpStatus HTTP status to return
   * @param status contract response status
   * @param message client-visible rejection message
   * @return rejected contract resolution
   */
  public static EmailContractResolution rejected(int httpStatus, String status, String message) {
    return new EmailContractResolution(false, httpStatus, status, message, null);
  }

  public boolean isReady() {
    return ready;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public String getStatus() {
    return status;
  }

  public String getMessage() {
    return message;
  }

  public EmailProviderRequest getProviderRequest() {
    return providerRequest;
  }
}
