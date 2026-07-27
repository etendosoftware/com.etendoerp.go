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

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.system.Client;

/**
 * ETP-4515 (Phase 7, folded into ETP-4520) — clones GOClient's Finance/Sales/Purchasing/Inventory
 * roles onto a freshly onboarded tenant when missing.
 *
 * <p>This is the preventive (onboarding-time) counterpart of the corrective data-fix {@code
 * cli/src/data-fixes/sql/20260727T114306Z__R16-tenant-roles-and-webhook-access.sql} in
 * {@code etendo_schema_forge} (role clone + {@code AD_Window_Access} backfill; that data-fix's
 * webhook-grant step, and this service's own former webhook-grant step, are both obsolete now that
 * {@code SFListMenu}/{@code SFWindowAccessMap}/{@code SFRolesOverview} are reached through the NEO
 * pseudo-spec bridge instead of the Webhooks module — see {@code neo-headless.md} §4.10–4.11). See
 * {@code santo_roles_handoff_phase7.md} and {@code docs/etendo-ad/onboarding-gaps.md} §H2 in the
 * functional repo for the full gap writeup.
 *
 * <p>Without this, a real onboarded tenant has exactly one role (its auto-created admin role) —
 * the ETP-4512 "assign one of 5 predefined roles" UI has nothing else to offer, and role-based
 * access segmentation is impossible for that tenant.
 *
 * <p>Window ids are copied as-is from GOClient's {@code AD_Window_Access} rows: {@code AD_Window}
 * is a system-level entity ({@code ad_client_id = '0'} for every row), so a window id means the
 * same thing on every tenant. {@code EM_ETGO_Show_Acct_Fields} is not yet a typed DAL property
 * (added straight to the physical table, see {@code neo-headless.md} §7/§8b), so it is read from
 * and written to GOClient's/the new role's row via native SQL, mirroring {@code
 * SFWindowAccessMap.resolveShowAccountingFields}.
 *
 * <p>Deliberately does NOT require an organization id (unlike, e.g., {@link
 * OnboardingPeriodControlService}): roles are client-wide ({@code AD_Role.AD_Org_ID} is always
 * {@code '0'} on every GOClient template row), so this step can run as soon as the client itself
 * exists — no organization needed yet.
 */
public class OnboardingRoleProvisioningService extends OnboardingContextSupport {

  private static final Logger log = LogManager.getLogger(OnboardingRoleProvisioningService.class);

  /** GOClient's fixed {@code AD_Client_ID} — the reference client throughout this epic. */
  private static final String GOCLIENT_ID = "802509E12436405C86BA1FD5B1DF508C";

  /** The 4 non-admin roles cloned from GOClient; the 5th (admin) already exists on every tenant. */
  private static final String[] ROLE_NAMES = { "Finance", "Sales", "Purchasing", "Inventory" };

  /** {@code AD_Org_ID} used for the cloned roles — the "*" org. */
  private static final String STAR_ORG_ID = "0";

  /**
   * Clones the 4 missing GOClient roles (with their {@code AD_Window_Access}) onto {@code
   * clientId}. Idempotent: safe to call on every onboarding run (including a resumed/retried one).
   *
   * @param clientId    target client identifier
   * @param adminUserId administrator user for DAL context
   * @param adminRoleId administrator role for DAL context
   */
  public void wire(String clientId, String adminUserId, String adminRoleId) {
    requirePresent(clientId, "client");
    requirePresent(adminUserId, "admin user");
    requirePresent(adminRoleId, "admin role");
    OBContext previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, STAR_ORG_ID);
    try {
      enterAdminMode();
      try {
        for (String roleName : ROLE_NAMES) {
          ensureRoleCloned(clientId, roleName);
        }
        flushChanges();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  /**
   * Clones {@code roleName} from GOClient onto {@code clientId} — role attributes,
   * {@code EM_ETGO_Show_Acct_Fields}, and {@code AD_Window_Access} — unless the tenant already
   * has an active role of that exact name.
   */
  private void ensureRoleCloned(String clientId, String roleName) {
    if (resolveRoleByName(clientId, roleName) != null) {
      log.debug("Role '{}' already exists for client {}; skipping clone", roleName, clientId);
      return;
    }
    Role source = resolveRoleByName(GOCLIENT_ID, roleName);
    if (source == null) {
      throw new OBException("GOClient template role not found: " + roleName);
    }
    Role clone = cloneRoleAttributes(clientId, source);
    flushChanges();
    writeShowAcctFields(clone.getId(), readShowAcctFields(source.getId()));
    cloneWindowAccess(clientId, source, clone);
  }

  /** Seam for tests: resolves an active role by (client, name), or {@code null} if none exists. */
  protected Role resolveRoleByName(String clientId, String roleName) {
    OBCriteria<Role> criteria = OBDal.getInstance().createCriteria(Role.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Role.PROPERTY_CLIENT + ".id", clientId));
    criteria.add(Restrictions.eq(Role.PROPERTY_NAME, roleName));
    criteria.add(Restrictions.eq(Role.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (Role) criteria.uniqueResult();
  }

  /**
   * Seam for tests: builds and saves the cloned {@link Role}, copying every attribute that
   * differs across GOClient's own 4 template roles. {@code AD_Org_ID}, currency, tree menu and
   * amount-approval are left at their entity defaults (NULL/0) since every GOClient template row
   * shares those same NULL/0 values.
   */
  protected Role cloneRoleAttributes(String clientId, Role source) {
    Client client = OBDal.getInstance().get(Client.class, clientId);
    Role role = OBProvider.getInstance().get(Role.class);
    role.setNewOBObject(true);
    role.setClient(client);
    role.setOrganization(resolveOrganization(STAR_ORG_ID));
    role.setActive(true);
    role.setName(source.getName());
    role.setDescription(source.getDescription());
    role.setUserLevel(source.getUserLevel());
    role.setManual(source.isManual());
    role.setProcessNow(source.isProcessNow());
    role.setClientAdmin(source.isClientAdmin());
    role.setAdvanced(source.isAdvanced());
    role.setRestrictbackend(source.isRestrictbackend());
    role.setForPortalUsers(source.isForPortalUsers());
    role.setPortalAdmin(source.isPortalAdmin());
    role.setWebServiceEnabled(source.isWebServiceEnabled());
    role.setTemplate(source.isTemplate());
    role.setRecalculatePermissions(source.isRecalculatePermissions());
    OBDal.getInstance().save(role);
    return role;
  }

  /**
   * Seam for tests: reads {@code EM_ETGO_Show_Acct_Fields} for {@code roleId} via native SQL.
   * Result type is deliberately {@code Object}, not {@code String}: Hibernate maps a
   * PostgreSQL {@code char(1)} column to {@link Character} for a plain scalar native query (no
   * explicit {@code addScalar} type), and a generics-erased {@code List<String>.get(0)} would
   * throw {@code ClassCastException: Character cannot be cast to String} on any real row —
   * exactly the failure hit onboarding a real tenant, since this method is only exercised live
   * once a role actually exists to read the column from.
   */
  @SuppressWarnings("unchecked")
  protected String readShowAcctFields(String roleId) {
    Session session = OBDal.getInstance().getSession();
    NativeQuery<Object> query = session.createNativeQuery(
        "SELECT em_etgo_show_acct_fields FROM ad_role WHERE ad_role_id = :roleId");
    query.setParameter("roleId", roleId);
    List<Object> results = query.getResultList();
    return results.isEmpty() || results.get(0) == null ? "N" : results.get(0).toString();
  }

  /** Seam for tests: writes {@code EM_ETGO_Show_Acct_Fields} for {@code roleId} via native SQL. */
  protected void writeShowAcctFields(String roleId, String value) {
    Session session = OBDal.getInstance().getSession();
    session.createNativeQuery(
        "UPDATE ad_role SET em_etgo_show_acct_fields = :value WHERE ad_role_id = :roleId")
        .setParameter("value", value)
        .setParameter("roleId", roleId)
        .executeUpdate();
  }

  /**
   * Backfills {@code AD_Window_Access} on {@code targetRole} to match every active row
   * {@code sourceRole} has. Window ids are copied as-is (system-level, {@code ad_client_id = '0'}
   * for every {@code AD_Window} row).
   */
  private void cloneWindowAccess(String clientId, Role sourceRole, Role targetRole) {
    for (WindowAccess sourceAccess : resolveActiveWindowAccess(sourceRole)) {
      createWindowAccess(clientId, targetRole, sourceAccess);
    }
  }

  /** Seam for tests: resolves every active {@code AD_Window_Access} row of {@code role}. */
  @SuppressWarnings("unchecked")
  protected List<WindowAccess> resolveActiveWindowAccess(Role role) {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));
    return criteria.list();
  }

  /** Seam for tests: builds and saves one cloned {@link WindowAccess} row. */
  protected void createWindowAccess(String clientId, Role targetRole, WindowAccess source) {
    Client client = OBDal.getInstance().get(Client.class, clientId);
    WindowAccess access = OBProvider.getInstance().get(WindowAccess.class);
    access.setNewOBObject(true);
    access.setClient(client);
    access.setOrganization(resolveOrganization(STAR_ORG_ID));
    access.setActive(true);
    access.setRole(targetRole);
    access.setWindow(source.getWindow());
    access.setEditableField(source.isEditableField());
    OBDal.getInstance().save(access);
  }

  @Override
  protected String contextSubject() {
    return "role provisioning";
  }
}
