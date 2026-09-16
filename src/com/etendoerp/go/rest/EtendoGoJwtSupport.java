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

package com.etendoerp.go.rest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.data.Account;

/** Shared JWT and environment-role helpers used by the Etendo Go servlet. */
public final class EtendoGoJwtSupport {

  private static final Logger log = LogManager.getLogger(EtendoGoJwtSupport.class);
  private static final String STAR_ORG_VALUE = "*";
  private static final String SYSTEM_ORG_ID = "0";
  private static final String SQL_FIND_ROLE_LIST_BY_USER =
      "SELECT r.ad_role_id AS role_id, r.name AS role_name, "
          + "o.ad_org_id AS org_id, o.name AS org_name "
          + "FROM ad_user_roles ur "
          + "JOIN ad_role r ON ur.ad_role_id = r.ad_role_id "
          + "LEFT JOIN ad_role_orgaccess roa ON r.ad_role_id = roa.ad_role_id "
          + "AND roa.isactive = 'Y' "
          + "LEFT JOIN ad_org o ON roa.ad_org_id = o.ad_org_id AND o.isactive = 'Y' "
          + "WHERE ur.ad_user_id = :userId AND ur.isactive = 'Y' AND r.isactive = 'Y' "
          + "ORDER BY r.created, o.name";

  private EtendoGoJwtSupport() {
  }

  static String requireAccountEmail(String token) {
    Account account = EtendoGoJwtDalHelper.findActiveAccountByToken(token);
    return account == null ? null : account.getEmail();
  }

  static boolean isEnvironmentUserOwnedByAccount(String accountEmail, String userId) {
    if (accountEmail == null || userId == null) {
      return false;
    }
    User user = OBDal.getInstance().get(User.class, userId);
    if (user == null || !Boolean.TRUE.equals(user.isActive())) {
      return false;
    }
    String username = user.getUsername();
    return accountEmail.equalsIgnoreCase(user.getEmail())
        || accountEmail.equalsIgnoreCase(username)
        || (username != null && username.startsWith(accountEmail + "+"));
  }

  /**
   * Loads the given user's assignable roles (each with its available organizations) as a
   * {@link RoleListData}, resolving the same {@code AD_Role}/{@code AD_Role_OrgAccess} rows
   * {@code EtendoGoJwtServlet}'s login flow already builds for the {@code roleList} it returns.
   *
   * <p>ETP-5329 — the entry whose {@code id} matches {@code userId}'s actual personal/default
   * role (see {@link User#getDefaultRole()}) also carries {@code effectiveRoleNames}: the names
   * of the template roles composed into that personal role via {@code AD_Role_Inheritance}
   * (resolved through {@link UserRoleCompositionService#getAppliedTemplateRoleIds(String)}), in
   * the same order the service returns them. This is attached ONLY to the matching entry, never
   * to every {@code roleList} row unconditionally — the rare pre-existing multi-{@code
   * AD_User_Roles}-row anomaly (ETP-4604) means a non-default entry could otherwise be shown with
   * a composed-template list that does not actually apply to it. Resolution runs only after the
   * role rows have loaded successfully — there is no reason to resolve template names when the
   * underlying role query already failed.
   *
   * @param userId the {@code AD_User_ID} whose roles are being resolved
   * @return the resolved role list, never {@code null}
   * @throws JSONException if the underlying role/organization JSON cannot be built
   */
  public static RoleListData loadRoleListData(String userId) throws JSONException {
    try {
      List<Object[]> rows = loadRoleRows(userId);
      if (rows.isEmpty()) {
        // No roles at all -> nothing to attach effectiveRoleNames to; skip resolving it.
        return buildRoleListData(rows, null, Collections.emptyList());
      }
      // resolveDefaultRoleId and resolveEffectiveRoleNames each independently OBDal.get() the
      // same User by userId (the latter indirectly, inside getAppliedTemplateRoleIds). This is
      // intentional/known: Hibernate's session-level identity cache dedups the second get() for
      // the same PK, so there is no extra round-trip to short-circuit here.
      String defaultRoleId = resolveDefaultRoleId(userId);
      List<String> effectiveRoleNames = resolveEffectiveRoleNames(userId);
      return buildRoleListData(rows, defaultRoleId, effectiveRoleNames);
    } catch (OBException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new OBException("Error loading role list data for user: " + userId, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Object[]> loadRoleRows(String userId) {
    NativeQuery<Object[]> query = OBDal.getInstance()
        .getSession()
        .createNativeQuery(SQL_FIND_ROLE_LIST_BY_USER);
    query.setParameter("userId", userId);
    return query.list();
  }

  private static String resolveDefaultRoleId(String userId) {
    User user = OBDal.getInstance().get(User.class, userId);
    if (user == null || user.getDefaultRole() == null) {
      return null;
    }
    return user.getDefaultRole().getId();
  }

  /**
   * ETP-5329 — the template role names currently composed into {@code userId}'s personal role,
   * in {@code AD_Role_Inheritance.Seqno} order. Empty when the user has no personal role yet or
   * no templates applied ({@link UserRoleCompositionService#getAppliedTemplateRoleIds(String)}
   * documents both as returning an empty list, never {@code null}).
   */
  private static List<String> resolveEffectiveRoleNames(String userId) {
    List<String> templateRoleIds = new UserRoleCompositionService()
        .getAppliedTemplateRoleIds(userId);
    if (templateRoleIds.isEmpty()) {
      return Collections.emptyList();
    }
    Map<String, String> namesById = fetchRoleNames(templateRoleIds);
    List<String> names = new ArrayList<>();
    for (String templateRoleId : templateRoleIds) {
      String name = namesById.get(templateRoleId);
      if (name != null) {
        names.add(name);
      } else {
        // ETP-4604-style anomaly: a template role id with no matching (active) Role row —
        // deleted or renamed out from under AD_Role_Inheritance. Log it instead of silently
        // shrinking the effectiveRoleNames array.
        log.warn("Template role id {} for user {} has no matching Role name; skipping it in "
            + "effectiveRoleNames", templateRoleId, userId);
      }
    }
    return names;
  }

  /**
   * Bulk-fetches role names for {@code roleIds} in one query — mirrors the batching pattern in
   * {@code UserRoleCompositionService#fetchCandidateDefaultRoles}.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, String> fetchRoleNames(List<String> roleIds) {
    OBCriteria<Role> criteria = OBDal.getInstance().createCriteria(Role.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.in(Role.PROPERTY_ID, roleIds));
    Map<String, String> namesById = new LinkedHashMap<>();
    for (Role role : (List<Role>) criteria.list()) {
      namesById.put(role.getId(), role.getName());
    }
    return namesById;
  }

  private static RoleListData buildRoleListData(List<Object[]> rows, String defaultRoleId,
      List<String> effectiveRoleNames) throws JSONException {
    String firstRoleId = null;
    Map<String, JSONObject> rolesById = new LinkedHashMap<>();
    for (Object[] row : rows) {
      String roleId = stringValue(row[0]);
      JSONObject roleObj = rolesById.get(roleId);
      if (roleObj == null) {
        boolean isDefaultRole = roleId != null && roleId.equals(defaultRoleId);
        roleObj = buildRoleJson(roleId, stringValue(row[1]),
            isDefaultRole ? effectiveRoleNames : null);
        rolesById.put(roleId, roleObj);
        if (firstRoleId == null) {
          firstRoleId = roleId;
        }
      }

      String orgId = stringValue(row[2]);
      if (orgId != null) {
        roleObj.getJSONArray("orgList").put(buildOrganizationJson(orgId, stringValue(row[3])));
      }
    }

    JSONArray roleArray = new JSONArray();
    for (JSONObject roleObj : rolesById.values()) {
      roleArray.put(roleObj);
    }
    return new RoleListData(firstRoleId, roleArray);
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  static String findClientIdByName(String clientName) {
    OBQuery<Client> query = OBDal.getInstance().createQuery(Client.class,
        "as client where client.name = :clientName and client.active = true");
    query.setNamedParameter("clientName", clientName);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    Client client = query.uniqueResult();
    return client == null ? null : client.getId();
  }

  /**
   * Builds a unique ERP username while preserving the platform account email as the identity.
   * This is public so admin-created users follow the same cross-client convention as onboarding.
   *
   * <p>The result fits the AD user username limit by trimming only the disambiguating company
   * suffix when necessary.
   *
   * @param accountEmail platform account email used as the identity
   * @param clientName company name used to disambiguate the username
   * @return a unique ERP username candidate
   */
  public static String buildClientUsername(String accountEmail, String clientName) {
    if (findActiveUserByUsername(accountEmail) == null) {
      return accountEmail;
    }
    String safeClientName = (clientName != null) ? clientName.toLowerCase().replaceAll("[^a-z0-9]", "") : "";
    int suffixRoom = OnboardingFieldLimits.EMAIL - (accountEmail.length() + 1);
    if (suffixRoom <= 0) {
      // No room for a suffix at all: the email alone fills the column. Returning it unchanged
      // keeps the value storable; the duplicate-username check upstream still guards uniqueness.
      return accountEmail;
    }
    if (safeClientName.length() > suffixRoom) {
      safeClientName = safeClientName.substring(0, suffixRoom);
    }
    return accountEmail + "+" + safeClientName;
  }

  static String findStarOrgId(String clientId) {
    Organization organization = findStarOrganization(clientId);
    return organization == null ? SYSTEM_ORG_ID : organization.getId();
  }

  static boolean organizationExists(String clientId) {
    OBQuery<Organization> query = OBDal.getInstance().createQuery(Organization.class,
        "as organization where organization.client.id = :clientId"
            + " and organization.searchKey <> :starOrgValue"
            + " and organization.active = true");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("starOrgValue", STAR_ORG_VALUE);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult() != null;
  }

  private static JSONObject buildRoleJson(String roleId, String roleName,
      List<String> effectiveRoleNames) throws JSONException {
    JSONObject roleObj = new JSONObject();
    roleObj.put("id", roleId);
    roleObj.put("name", roleName);
    roleObj.put("orgList", new JSONArray());
    if (effectiveRoleNames != null && !effectiveRoleNames.isEmpty()) {
      roleObj.put("effectiveRoleNames", new JSONArray(effectiveRoleNames));
    }
    return roleObj;
  }

  private static JSONObject buildOrganizationJson(String orgId, String orgName) throws JSONException {
    JSONObject orgObj = new JSONObject();
    orgObj.put("id", orgId);
    orgObj.put("name", orgName);
    return orgObj;
  }

  /**
   * Sets the display name of the client admin user (looked up by username) to the
   * given full name. No-op when the name is blank or the user is not found. The
   * change is saved on the current DAL transaction (committed by the caller).
   */
  static void applyClientAdminDisplayName(String username, String fullName) {
    if (fullName == null || fullName.isBlank()) {
      return;
    }
    User user = findActiveUserByUsername(username);
    if (user != null) {
      user.setName(fullName);
      OBDal.getInstance().save(user);
    }
  }

  /**
   * ETP-5019 — {@link org.openbravo.erpCommon.businessUtility.InitialSetupUtility#insertUser}
   * (core Etendo, {@code InitialClientSetup}'s underlying primitive) sets {@code Name},
   * {@code Description}, and {@code Username} on the newly-created client-admin {@code AD_User}
   * but never {@code Email} — so the owner's Email field is genuinely {@code NULL} in the DB
   * after onboarding, not a frontend display bug (confirmed by direct query: every
   * {@code EM_ETGO_Is_Owner='Y'} row has {@code email IS NULL}). Backfills it from the real
   * login/registration email (the {@code accountEmail} the founder verified during onboarding —
   * NOT {@code username}, which may carry a client-name suffix via {@link
   * #buildClientUsername}), the same fire-and-forget-save pattern {@link
   * #applyClientAdminDisplayName} already uses for {@code Name}. No-op when the email is blank
   * or the user is not found. The change is saved on the current DAL transaction (committed by
   * the caller).
   */
  static void applyClientAdminEmail(String username, String email) {
    if (email == null || email.isBlank()) {
      return;
    }
    User user = findActiveUserByUsername(username);
    if (user != null) {
      user.setEmail(email);
      OBDal.getInstance().save(user);
    }
  }

  private static User findActiveUserByUsername(String username) {
    OBQuery<User> query = OBDal.getInstance().createQuery(User.class,
        "as user where user.username = :username and user.active = true");
    query.setNamedParameter("username", username);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  private static Organization findStarOrganization(String clientId) {
    OBQuery<Organization> query = OBDal.getInstance().createQuery(Organization.class,
        "as organization where organization.client.id = :clientId"
            + " and organization.searchKey = :starOrgValue"
            + " and organization.active = true");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("starOrgValue", STAR_ORG_VALUE);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  /**
   * The resolved set of roles (and their available organizations) a user is currently
   * assignable to, plus a convenience pointer to the first one — the same shape both the
   * login flow ({@code EtendoGoJwtServlet}) and the silent-refresh webhook
   * ({@code SFRefreshToken}) need for their own {@code roleList} responses.
   */
  public static final class RoleListData {
    private final String firstRoleId;
    private final JSONArray roleArray;

    /**
     * Builds an immutable {@link RoleListData} snapshot.
     *
     * @param firstRoleId the first resolved role's {@code AD_Role_ID}, or {@code null}
     * @param roleArray the full resolved role list
     */
    public RoleListData(String firstRoleId, JSONArray roleArray) {
      this.firstRoleId = firstRoleId;
      this.roleArray = roleArray;
    }

    /** @return the first resolved role's {@code AD_Role_ID}, or {@code null} if none resolved */
    public String getFirstRoleId() {
      return firstRoleId;
    }

    /** @return the full resolved role list, each entry carrying its available organizations */
    public JSONArray getRoleArray() {
      return roleArray;
    }
  }
}
