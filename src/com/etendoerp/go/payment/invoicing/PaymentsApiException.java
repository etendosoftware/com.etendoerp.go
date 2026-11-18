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

package com.etendoerp.go.payment.invoicing;

/**
 * A payments API call refused with a status and a code the caller branches on (ETP-6322). The
 * message is English, for logs. {@code field} names the body or query field at fault, when one is.
 */
public class PaymentsApiException extends Exception {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;
  private final String field;

  /**
   * Creates a refusal.
   *
   * @param status  the HTTP status
   * @param code    the stable error code
   * @param field   the field at fault, or null
   * @param message an English explanation
   */
  public PaymentsApiException(int status, String code, String field, String message) {
    super(message);
    this.status = status;
    this.code = code;
    this.field = field;
  }

  /** @return the HTTP status */
  public int status() {
    return status;
  }

  /** @return the stable error code */
  public String code() {
    return code;
  }

  /** @return the field at fault, or null */
  public String field() {
    return field;
  }
}
