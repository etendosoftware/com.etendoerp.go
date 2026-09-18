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

package com.etendoerp.go.portal;

/**
 * The scope one validated portal token is allowed to see: exactly one tenant and exactly one
 * Business Partner.
 *
 * <p><b>This type exists to make security invariant 1 of the plan structural rather than
 * remembered.</b> Its only constructor is package-private and its only caller is
 * {@link PortalAccessService#validate}, which reads every field off the validated
 * {@code etgo_portal_access} row. No endpoint can therefore obtain a client id or a Business
 * Partner id from anywhere else — there is no setter, no builder, and no constructor a request
 * parameter could reach. The IDOR surface is closed by construction, not by review.
 */
public final class PortalSession {

  private final String accessId;
  private final String clientId;
  private final String businessPartnerId;
  private final String businessPartnerName;
  private final String tenantName;

  PortalSession(String accessId, String clientId, String businessPartnerId,
      String businessPartnerName, String tenantName) {
    this.accessId = accessId;
    this.clientId = clientId;
    this.businessPartnerId = businessPartnerId;
    this.businessPartnerName = businessPartnerName;
    this.tenantName = tenantName;
  }

  /**
   * @return the {@code etgo_portal_access} row this session was validated from
   */
  public String getAccessId() {
    return accessId;
  }

  /**
   * @return the tenant every query of this session must be filtered by
   */
  public String getClientId() {
    return clientId;
  }

  /**
   * @return the Business Partner every query of this session must be filtered by
   */
  public String getBusinessPartnerId() {
    return businessPartnerId;
  }

  /**
   * @return the Business Partner's display name, for the portal greeting
   */
  public String getBusinessPartnerName() {
    return businessPartnerName;
  }

  /**
   * @return the tenant's display name, for the portal greeting
   */
  public String getTenantName() {
    return tenantName;
  }
}
