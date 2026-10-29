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

/**
 * ETP-5351 — the values {@code ETGO_SUPPORT_ACCESS.END_REASON} accepts (its check constraint).
 */
public enum SupportEndReason {
  /** The support session logged out. */
  LOGOUT("logout"),
  /** The support session reached its absolute or idle expiry. */
  EXPIRED("expired"),
  /** An operator closed it from Classic ("Cerrar sesión de soporte"). */
  REVOKED("revoked"),
  /** The pass was never redeemed before it expired. */
  TICKET_EXPIRED("ticket_expired");

  private final String code;

  SupportEndReason(String code) {
    this.code = code;
  }

  /**
   * The value stored in {@code END_REASON}.
   *
   * @return the database code
   */
  public String getCode() {
    return code;
  }
}
