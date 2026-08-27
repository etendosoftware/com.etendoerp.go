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

package com.etendoerp.go.common;

import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

/**
 * Shared "exact org, then client-wide" active-warehouse fallback lookup (ETP-4894/ETP-4999).
 *
 * <p>Extracted from the identical logic independently written in both {@code
 * OnboardingAdminIdentityService#findFirstActiveWarehouse} and {@code
 * PersonalRoleAccessProvisioningService#findFirstActiveWarehouse} during the ETP-4894
 * investigation — SonarQube flagged the pair as a duplicated block (New Duplicated Lines
 * Density) once both call sites existed; new callers should depend on this instead of adding
 * a third copy.</p>
 *
 * <p>Prefers a warehouse scoped exactly to {@code organization}; falls back to any active
 * warehouse belonging to {@code client} (any organization) when none exists at that exact
 * organization — a warehouse created by onboarding, or one that predates the tenant's real
 * business organization, is commonly scoped to the client's root organization instead of the
 * specific organization a session or role ends up defaulting to.</p>
 */
public final class WarehouseLookupHelper {

  private WarehouseLookupHelper() {
  }

  /**
   * Finds the first active warehouse for the given organization, falling back to any active
   * warehouse of the client when none is scoped to that exact organization.
   *
   * @param client       the tenant's {@code AD_Client}
   * @param organization the organization to prefer an exact warehouse match for
   * @return the first active warehouse scoped to {@code organization}, or else the first active
   * warehouse scoped to {@code client} (any organization); {@code null} when neither exists
   */
  public static Warehouse findFirstActiveWarehouse(Client client, Organization organization) {
    Warehouse warehouse = findFirstActiveWarehouseMatching(Warehouse.PROPERTY_ORGANIZATION, organization);
    return warehouse != null ? warehouse
        : findFirstActiveWarehouseMatching(Warehouse.PROPERTY_CLIENT, client);
  }

  @SuppressWarnings("unchecked")
  private static Warehouse findFirstActiveWarehouseMatching(String property, Object value) {
    OBCriteria<Warehouse> criteria = OBDal.getInstance().createCriteria(Warehouse.class);
    criteria.add(Restrictions.eq(property, value));
    criteria.add(Restrictions.eq(Warehouse.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (Warehouse) criteria.uniqueResult();
  }
}
