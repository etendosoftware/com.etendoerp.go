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
package com.etendoerp.go.roles;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleOrganization;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

/**
 * ETP-4830 items #6.1/#6.2 — extracted out of {@link UserRoleCompositionService} (SonarQube
 * S1448, that class was over the 35-method limit) to keep this one responsibility — finishing
 * the setup of a freshly-minted {@link Role} returned by {@code createPersonalRole} — separate
 * from role/inheritance composition itself. Purely behavior-preserving: every method here is the
 * exact body moved verbatim from {@code UserRoleCompositionService}, called from the same single
 * call site ({@code UserRoleCompositionService#createPersonalRole}), with no logic changes.
 *
 * <p>Covers three cohesive pieces of a brand-new personal role's setup: (1) a unique display
 * name ({@link #buildPersonalRoleName}); (2) {@code AD_Role_OrgAccess} grants so the role can
 * actually operate in an organization ({@link #createOrgAccess}); (3) the owning user's own
 * {@code Default_*} fields ({@link #applyUserDefaults}).</p>
 */
class PersonalRoleAccessProvisioningService {

  private static final String PERSONAL_ROLE_NAME_PREFIX = "Personal – ";

  /**
   * {@code AD_Role.Name} is unique per {@code (AD_Client_ID, Name)} — the user's display name
   * (falling back to username, then id) is unique enough in practice, but a numeric suffix is
   * appended on an actual collision rather than failing the whole composition over a duplicate
   * display name.
   */
  String buildPersonalRoleName(User user) {
    String base = StringUtils.trimToNull(user.getName());
    if (base == null) {
      base = StringUtils.trimToNull(user.getUsername());
    }
    if (base == null) {
      base = user.getId();
    }
    String candidate = PERSONAL_ROLE_NAME_PREFIX + base;
    String name = truncate(candidate, 60);
    int suffix = 2;
    while (roleNameExists(user, name)) {
      String suffixed = candidate + " (" + suffix + ")";
      name = truncate(suffixed, 60);
      suffix++;
    }
    return name;
  }

  private boolean roleNameExists(User user, String name) {
    OBCriteria<Role> criteria = OBDal.getInstance().createCriteria(Role.class);
    criteria.add(Restrictions.eq(Role.PROPERTY_CLIENT + ".id", user.getClient().getId()));
    criteria.add(Restrictions.eq(Role.PROPERTY_NAME, name));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }

  private static String truncate(String value, int maxLength) {
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }

  /**
   * Grants {@code role} access to {@code user}'s own organization plus the wildcard {@code '*'}
   * (ETP-4830 item #6.1) — without this, a freshly-minted personal role has zero
   * {@code AD_Role_OrgAccess} rows and cannot actually operate in any organization, regardless of
   * its {@code AD_Window_Access}/{@code AD_Role_Inheritance} grants. Mirrors the pattern an
   * already-correctly-configured role has in production (confirmed against real tenant data: one
   * row for the role's real organization, one for {@code '*'}). Skips the duplicate when {@code
   * user.getOrganization()} IS the wildcard org already (nothing meaningful to add twice).
   */
  void createOrgAccess(Role role, User user, Organization starOrg) {
    Organization userOrg = user.getOrganization();
    if (userOrg != null && !starOrg.getId().equals(userOrg.getId())) {
      saveOrgAccess(role, userOrg);
    }
    saveOrgAccess(role, starOrg);
  }

  private void saveOrgAccess(Role role, Organization organization) {
    RoleOrganization access = OBProvider.getInstance().get(RoleOrganization.class);
    access.setNewOBObject(true);
    access.setClient(role.getClient());
    access.setOrganization(organization);
    access.setRole(role);
    access.setActive(true);
    access.setOrgAdmin(false);
    OBDal.getInstance().save(access);
  }

  /**
   * Sets the newly-created user's own default-* fields to real, tenant-scoped values (ETP-4830
   * item #6.2) — confirmed via real tenant data that these were otherwise left at whatever
   * generic {@code AD_User} defaulting produces, which is NOT tenant-scoped: every test user
   * checked had {@code Default_Ad_Client_ID} pointing at a DIFFERENT tenant's client entirely,
   * {@code Default_Ad_Org_ID} at the wildcard org, and {@code EM_SMFSWS_Default_WS_Role_ID}
   * (Default role for web services) unset. {@code Default_Ad_Role_ID} is intentionally NOT
   * touched here — every existing caller of {@code UserRoleCompositionService#createPersonalRole}
   * already sets it right after this method returns, immediately following the exact same "a
   * role was just resolved for this user" moment.
   */
  void applyUserDefaults(User user, Role role) {
    user.setDefaultClient(user.getClient());
    Organization userOrg = user.getOrganization();
    if (userOrg != null) {
      user.setDefaultOrganization(userOrg);
      Warehouse warehouse = findFirstActiveWarehouse(user.getClient(), userOrg);
      if (warehouse != null) {
        user.setDefaultWarehouse(warehouse);
      }
    }
    user.setSmfswsDefaultWsRole(role);
    OBDal.getInstance().save(user);
  }

  /**
   * Prefers a warehouse scoped exactly to {@code organization}; falls back to any active
   * warehouse belonging to {@code client} (any organization) when none exists at that exact
   * organization (ETP-4999). Confirmed against real tenant data: a single-warehouse tenant
   * commonly has its one {@code M_Warehouse} row attached to the client's root ({@code '*'},
   * {@code AD_Org_ID = '0'}) organization, not the specific business organization a newly-invited
   * user's role operates in — an exact-organization-only match left {@code Default_M_Warehouse_ID}
   * null for the majority of such users. {@code SecureWebServicesUtils#getOrganizationWarehouses}
   * (the core SWS login/environment-switch path that ultimately needs this default) only walks
   * DOWN the org tree from the selected organization, never up to a parent/root org, so a
   * root-scoped warehouse is otherwise invisible there too — a null {@code Default_M_Warehouse_ID}
   * on a user whose selected organization has no warehouse of its own then throws
   * {@code SMFSWS_OrgHasNoRole} ("the selected organization has no warehouses") on login.
   */
  private Warehouse findFirstActiveWarehouse(Client client, Organization organization) {
    Warehouse warehouse = findFirstActiveWarehouseMatching(Warehouse.PROPERTY_ORGANIZATION, organization);
    return warehouse != null ? warehouse : findFirstActiveWarehouseMatching(Warehouse.PROPERTY_CLIENT, client);
  }

  @SuppressWarnings("unchecked")
  private Warehouse findFirstActiveWarehouseMatching(String property, Object value) {
    OBCriteria<Warehouse> criteria = OBDal.getInstance().createCriteria(Warehouse.class);
    criteria.add(Restrictions.eq(property, value));
    criteria.add(Restrictions.eq(Warehouse.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (Warehouse) criteria.uniqueResult();
  }
}
