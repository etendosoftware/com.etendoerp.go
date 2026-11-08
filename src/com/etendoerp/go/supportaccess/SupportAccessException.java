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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess;

import org.openbravo.base.exception.OBException;

/**
 * ETP-5351 — a support access request refused for a reason the caller can show: the Classic
 * process (T3) turns {@link #getCode()} into a message for the operator.
 */
public class SupportAccessException extends OBException {

  private static final long serialVersionUID = 1L;

  /** The operator gave no reason, or a too short one. */
  public static final String CODE_REASON_REQUIRED = "SUPPORT_REASON_REQUIRED";
  /** The duration is not positive or exceeds {@code ETGO_SupportSessionMaxMinutes}. */
  public static final String CODE_DURATION_INVALID = "SUPPORT_DURATION_INVALID";
  /** The tenant does not exist, is inactive, is System, the template or an unclaimed pool one. */
  public static final String CODE_TARGET_NOT_ELIGIBLE = "SUPPORT_TARGET_NOT_ELIGIBLE";
  /** The operator does not hold the "Soporte Etendo GO" role. */
  public static final String CODE_OPERATOR_NOT_ALLOWED = "SUPPORT_OPERATOR_NOT_ALLOWED";
  /** The tenant has no active client-admin role to act with. */
  public static final String CODE_NO_ADMIN_ROLE = "SUPPORT_NO_ADMIN_ROLE";
  /** The technical support account is missing or inactive (the seed did not run). */
  public static final String CODE_SUPPORT_ACCOUNT_MISSING = "SUPPORT_ACCOUNT_MISSING";
  /** The access does not exist or is already closed. */
  public static final String CODE_ACCESS_NOT_OPEN = "SUPPORT_ACCESS_NOT_OPEN";

  private final String code;

  /**
   * Creates the exception.
   *
   * @param code    one of the {@code CODE_*} constants, stable for callers
   * @param message developer-facing English message
   */
  public SupportAccessException(String code, String message) {
    super(message);
    this.code = code;
  }

  /**
   * The stable, machine-readable reason.
   *
   * @return the code
   */
  public String getCode() {
    return code;
  }
}
