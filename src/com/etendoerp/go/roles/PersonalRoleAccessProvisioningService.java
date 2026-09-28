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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleOrganization;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

import com.etendoerp.go.common.WarehouseLookupHelper;

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

  /** {@code AD_Role.Name} is {@code NVARCHAR(60)}. */
  private static final int ROLE_NAME_MAX_LENGTH = 60;

  /**
   * ETP-5502 — cap on collision-suffix attempts in {@link #buildPersonalRoleName(User)}, so a
   * pathological client throws a clear error instead of hanging the request.
   */
  static final int MAX_NAME_ATTEMPTS = 1000;

  private static final Pattern COLLISION_SUFFIX = Pattern.compile("^.* \\((\\d+)\\)$");

  /**
   * {@code AD_Role.Name} is unique per {@code (AD_Client_ID, Name)} — the user's display name
   * (falling back to username, then id) is unique enough in practice, but a numeric suffix is
   * appended on an actual collision rather than failing the whole composition over a duplicate
   * display name.
   *
   * <p>ETP-5502: the suffix used to be appended BEFORE truncating to 60 characters, so for a
   * base name of 47+ characters every suffix was cut off to the same string and this loop never
   * ended. Names now come from {@link #personalRoleName(String, int)}, which always keeps the
   * suffix, and the number of attempts is capped.</p>
   *
   * @throws OBException if {@link #MAX_NAME_ATTEMPTS} names are all taken
   */
  String buildPersonalRoleName(User user) {
    String base = personalRoleNameSource(user);
    for (int n = 1; n <= MAX_NAME_ATTEMPTS; n++) {
      String name = personalRoleName(base, n);
      if (!roleNameExists(user, name)) {
        return name;
      }
    }
    throw new OBException("Could not find a free personal role name for user " + user.getId()
        + " after " + MAX_NAME_ATTEMPTS + " attempts");
  }

  /**
   * The text a user's personal role is named after: their trimmed name, else trimmed username,
   * else their id. The backfill data-fix (etendo_schema_forge {@code cli/src/data-fixes/}) mirrors
   * this rule in SQL — keep both in lockstep.
   */
  String personalRoleNameSource(User user) {
    String base = StringUtils.trimToNull(user.getName());
    if (base == null) {
      base = StringUtils.trimToNull(user.getUsername());
    }
    if (base == null) {
      base = user.getId();
    }
    return base;
  }

  /**
   * ETP-5502 — the {@code n}-th candidate personal role name for {@code base}: {@code n <= 1} is
   * {@code "Personal – " + base} cut to 60 characters (unchanged from before, so existing roles
   * keep matching); {@code n >= 2} cuts the base first and then appends {@code " (n)"}, so the
   * suffix always fits. The single source of both creation ({@link #buildPersonalRoleName}) and
   * lookup ({@link #isPersonalRoleNameFor}) so the two can never drift.
   */
  static String personalRoleName(String base, int n) {
    String full = PERSONAL_ROLE_NAME_PREFIX + base;
    if (n <= 1) {
      return truncate(full, ROLE_NAME_MAX_LENGTH);
    }
    String suffix = " (" + n + ")";
    return truncate(full, ROLE_NAME_MAX_LENGTH - suffix.length()) + suffix;
  }

  /**
   * ETP-5502 — true when {@code roleName} is one of the names {@link #personalRoleName(String,
   * int)} builds for {@code base}: the unsuffixed one or any {@code " (n)"} variant, {@code n >=
   * 2}. Legacy 57-character names built by the old suffix-then-truncate code ({@code "… (2"}) do
   * not match; the owner backfill covers those.
   */
  static boolean isPersonalRoleNameFor(String base, String roleName) {
    if (roleName == null) {
      return false;
    }
    if (roleName.equals(personalRoleName(base, 1))) {
      return true;
    }
    Matcher matcher = COLLISION_SUFFIX.matcher(roleName);
    if (!matcher.matches()) {
      return false;
    }
    String digits = matcher.group(1);
    if (digits.length() > 9) {
      return false;
    }
    int n = Integer.parseInt(digits);
    return n >= 2 && roleName.equals(personalRoleName(base, n));
  }

  /**
   * ETP-5502 — active, non-template, non-client-admin roles of {@code user}'s client, earliest
   * first: the candidates demote may restore. Callers add the owner/name restriction.
   */
  static OBCriteria<Role> personalRoleCandidateCriteria(User user) {
    OBCriteria<Role> criteria = OBDal.getInstance().createCriteria(Role.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Role.PROPERTY_CLIENT + ".id", user.getClient().getId()));
    criteria.add(Restrictions.eq(Role.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.eq(Role.PROPERTY_TEMPLATE, false));
    criteria.add(Restrictions.eq(Role.PROPERTY_CLIENTADMIN, false));
    criteria.addOrder(Order.asc(Role.PROPERTY_CREATIONDATE));
    return criteria;
  }

  /**
   * ETP-5502 — a personal role is always created after its user, so one older than {@code user}
   * belonged to someone else (typically a deleted namesake).
   */
  static boolean isOlderThan(Role role, User user) {
    return role.getCreationDate() != null && user.getCreationDate() != null
        && role.getCreationDate().before(user.getCreationDate());
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
      Warehouse warehouse = WarehouseLookupHelper.findFirstActiveWarehouse(user.getClient(), userOrg);
      if (warehouse != null) {
        user.setDefaultWarehouse(warehouse);
      }
    }
    user.setSmfswsDefaultWsRole(role);
    OBDal.getInstance().save(user);
  }
}
