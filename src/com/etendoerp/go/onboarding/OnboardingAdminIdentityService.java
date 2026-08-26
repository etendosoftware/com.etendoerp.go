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
package com.etendoerp.go.onboarding;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

/**
 * Wires the onboarding admin's own session-default fields to the tenant's REAL business
 * organization (ETP-4999, gap M1) — closes a gap left by the split between {@code
 * InitialClientSetup} (creates the client/admin-user/admin-role scoped ONLY to the client's
 * root/wildcard organization, {@code AD_Org_ID = '0'} — the only org that exists at that point,
 * called from {@code EtendoGoJwtServlet#createClient} → {@code resolveOrCreateClient}) and {@code
 * EtendoGoJwtServlet#createOrganization} (creates the tenant's real business organization
 * SEPARATELY, afterward). Nothing in the provisioning chain between those two calls ever
 * re-points the admin's session defaults at the org that actually ends up being the tenant's
 * home.
 *
 * <p><b>What this fixes.</b> {@code AD_User.Default_Ad_Client_ID}/{@code Default_Ad_Org_ID}/
 * {@code Default_M_Warehouse_ID}/{@code EM_SMFSWS_Default_WS_Role_ID} are all left {@code NULL}
 * by {@code InitialClientSetup} — confirmed live across dozens of self-registered tenants, zero
 * exceptions. {@code SecureWebServicesUtils.generateToken()}'s {@code getWarehouse()} fallback
 * chain ultimately throws {@code SMFSWS_OrgHasNoRole} ("the selected organization has no
 * warehouses") once the target org is explicitly the real business org (an environment-switch,
 * or any login where {@code Default_Ad_Org_ID} would otherwise disambiguate a role with multiple
 * {@code AD_Role_OrgAccess} grants) and neither an explicit warehouse nor {@code
 * Default_M_Warehouse_ID} is available — because {@code
 * SecureWebServicesUtils#getOrganizationWarehouses} only walks DOWN the org tree from the
 * selected org, never up to a parent/root org, and the warehouse created during onboarding (see
 * {@code OnboardingDatasetImportService}) is itself scoped to the root org {@code '0'}, not this
 * tenant's real business org.
 *
 * <p><b>What this deliberately does NOT touch: {@code AD_User_Roles}.</b> An earlier version of
 * this fix also tried to re-point the admin's {@code AD_User_Roles.AD_Org_ID} from {@code '0'} to
 * the real org, to fix an unrelated self-invite 400 ({@code INVITED_USER_NO_ROLE}). That was
 * WRONG: core only ever allows {@code AD_User_Roles} to hold instances at the root/wildcard
 * organization — attempting anything else throws {@code "Entity ADUserRoles may only have
 * instances with organization *"} (confirmed live: broke provisioning for EVERY new self-
 * registered tenant, not just the original edge case, and was reverted). The {@code
 * INVITED_USER_NO_ROLE} bug is fixed at its real source instead — {@code
 * CompanyInvitationDalHelper#hasActiveRoleForOrganization} now checks the role's {@code
 * AD_Role_OrgAccess} grants, not {@code AD_User_Roles.organization} (which can never be anything
 * but {@code '0'} for ANY tenant) — see that method's own javadoc.
 *
 * <p>Idempotent and safe to call unconditionally on every onboarding pass (reconcile model,
 * ETP-4428): existing non-null {@code Default_*} fields are preserved (never overwritten — a
 * later legitimate manual change, e.g. via Classic, is never clobbered by a retried onboarding
 * call).</p>
 *
 * <p>Lockstep corrective twin for already-onboarded tenants: {@code
 * 20260826T120000Z__R26-admin-identity-real-org.sql} in {@code
 * schema_forge/cli/src/data-fixes/sql/}.</p>
 */
public class OnboardingAdminIdentityService {

  private static final Logger log = LogManager.getLogger(OnboardingAdminIdentityService.class);

  /**
   * Wires {@code adminUserId}'s session defaults to the real business organization {@code orgId}.
   *
   * @param clientId    the tenant's {@code AD_Client_ID}
   * @param orgId       the tenant's real business organization (NOT the root/wildcard {@code '0'})
   * @param adminUserId the onboarding admin's {@code AD_User_ID}
   * @param adminRoleId the onboarding admin's {@code AD_Role_ID} (the tenant-wide Admin role
   *                    created by {@code InitialClientSetup}, home-scoped at org {@code '0'})
   */
  public void wireAdminIdentity(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    User user = OBDal.getInstance().get(User.class, adminUserId);
    Role role = OBDal.getInstance().get(Role.class, adminRoleId);
    Organization org = OBDal.getInstance().get(Organization.class, orgId);
    Client client = OBDal.getInstance().get(Client.class, clientId);
    if (user == null || role == null || org == null || client == null) {
      throw new OBException("wireAdminIdentity: missing user/role/org/client for client "
          + clientId + " (user=" + adminUserId + ", role=" + adminRoleId + ", org=" + orgId + ")");
    }

    applySessionDefaults(user, client, org, role);

    OBDal.getInstance().flush();
    log.info("Wired admin session defaults for user '{}' (client '{}') to org '{}'", adminUserId,
        clientId, orgId);
  }

  /**
   * Fills only the {@code Default_*} fields that are still {@code null} — never overwrites a
   * value already present, so a resumed/retried onboarding pass (ETP-4428 reconcile model) is a
   * true no-op once these converge, in lockstep with the corrective {@code .sql}'s own
   * {@code COALESCE}-guarded columns.
   */
  private void applySessionDefaults(User user, Client client, Organization org, Role role) {
    if (user.getDefaultClient() == null) {
      user.setDefaultClient(client);
    }
    if (user.getDefaultOrganization() == null) {
      user.setDefaultOrganization(org);
    }
    if (user.getDefaultWarehouse() == null) {
      Warehouse warehouse = findFirstActiveWarehouse(client, org);
      if (warehouse != null) {
        user.setDefaultWarehouse(warehouse);
      }
    }
    if (user.getSmfswsDefaultWsRole() == null) {
      user.setSmfswsDefaultWsRole(role);
    }
    OBDal.getInstance().save(user);
  }

  /**
   * Prefers a warehouse scoped exactly to {@code organization}; falls back to any active
   * warehouse belonging to {@code client} (any organization) when none exists at that exact
   * organization. Same "exact org, then client-wide" DESIGN as {@code
   * PersonalRoleAccessProvisioningService#findFirstActiveWarehouse} (ETP-4999's sibling fix for
   * the NEO-invite path) — re-implemented here, in this different package, rather than reached
   * into cross-package, since that class is package-private and deliberately not touched by this
   * ticket: the warehouse created during onboarding is itself scoped to the root org {@code '0'},
   * not this tenant's real business org, so an exact-org-only lookup would leave {@code
   * Default_M_Warehouse_ID} null for every single-warehouse tenant onboarded this way.
   */
  private Warehouse findFirstActiveWarehouse(Client client, Organization organization) {
    Warehouse warehouse = findFirstActiveWarehouseMatching(Warehouse.PROPERTY_ORGANIZATION, organization);
    return warehouse != null ? warehouse
        : findFirstActiveWarehouseMatching(Warehouse.PROPERTY_CLIENT, client);
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
