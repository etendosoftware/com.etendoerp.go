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

import java.time.Instant;

/**
 * ETP-5351 — the tenant already has an open support access (one per tenant). Carries who holds it
 * and until when, so the Classic process can say "X está dentro hasta HH:MM".
 */
public class SupportTenantBusyException extends SupportAccessException {

  private static final long serialVersionUID = 1L;

  /** Stable code of this refusal. */
  public static final String CODE_TENANT_BUSY = "SUPPORT_TENANT_BUSY";

  private final String accessId;
  private final String operatorUserId;
  private final String operatorName;
  private final transient Instant busyUntil;

  /**
   * Creates the exception from the access that holds the tenant.
   *
   * @param holder the open access; its operator and derived session expiry are reported
   */
  public SupportTenantBusyException(SupportAccessRecord holder) {
    super(CODE_TENANT_BUSY, "The tenant already has an open support access");
    this.accessId = holder == null ? null : holder.getId();
    this.operatorUserId = holder == null ? null : holder.getOperatorUserId();
    this.operatorName = holder == null ? null : holder.getOperatorName();
    this.busyUntil = holder == null ? null : holder.getSessionExpiresAt();
  }

  /** @return the {@code ETGO_SUPPORT_ACCESS_ID} holding the tenant, or {@code null} if unknown */
  public String getAccessId() {
    return accessId;
  }

  /** @return the {@code AD_User_ID} of the operator inside, or {@code null} if unknown */
  public String getOperatorUserId() {
    return operatorUserId;
  }

  /** @return the display name of the operator inside, or {@code null} if unknown */
  public String getOperatorName() {
    return operatorName;
  }

  /** @return until when the tenant is held, or {@code null} if unknown */
  public Instant getBusyUntil() {
    return busyUntil;
  }
}
