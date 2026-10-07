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
 * ETP-5351 — the "Soporte Etendo" user of one tenant and the context a support session enters
 * with: the client admin role, and the organization and warehouse the tenant's admin uses.
 */
public final class SupportUserContext {

  private final String clientId;
  private final String userId;
  private final String roleId;
  private final String orgId;
  private final String warehouseId;
  private final String language;

  /**
   * Creates the context.
   *
   * @param clientId    the tenant's {@code AD_Client_ID}
   * @param userId      the support user's {@code AD_User_ID}
   * @param roleId      the client admin {@code AD_Role_ID}
   * @param orgId       the default {@code AD_Org_ID}
   * @param warehouseId the default {@code M_Warehouse_ID}, may be null
   * @param language    the default {@code AD_Language}, may be null
   */
  public SupportUserContext(String clientId, String userId, String roleId, String orgId,
      String warehouseId, String language) {
    this.clientId = clientId;
    this.userId = userId;
    this.roleId = roleId;
    this.orgId = orgId;
    this.warehouseId = warehouseId;
    this.language = language;
  }

  /** @return the tenant's {@code AD_Client_ID} */
  public String getClientId() {
    return clientId;
  }

  /** @return the support user's {@code AD_User_ID} */
  public String getUserId() {
    return userId;
  }

  /** @return the client admin {@code AD_Role_ID} */
  public String getRoleId() {
    return roleId;
  }

  /** @return the default {@code AD_Org_ID} */
  public String getOrgId() {
    return orgId;
  }

  /** @return the default {@code M_Warehouse_ID}, may be null */
  public String getWarehouseId() {
    return warehouseId;
  }

  /** @return the default {@code AD_Language}, may be null */
  public String getLanguage() {
    return language;
  }
}
