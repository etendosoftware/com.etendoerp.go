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

package com.etendoerp.go.oauth2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleOrganization;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.oauth2.OAuth2Servlet.AuthException;
import com.etendoerp.go.oauth2.OAuth2Servlet.PublicApiKeyContext;
import com.etendoerp.go.schemaforge.data.com.etendoerp.go.schemaforge.data.OAuth2Client;

/**
 * Persistence and role checks behind the self-service {@code /oauth2/api-keys} lifecycle.
 *
 * <p>The key itself ({@code ETGO_OAUTH2_CLIENT}) is read and written through the generated DAL
 * entity: its rows live at client and organization {@code 0}, which a System-level table accepts.
 * Its tokens are a different matter. {@code OAuth2Servlet} inserts them by SQL with the tenant
 * organization (so token context resolution can derive the operational client), and
 * {@code ETGO_OAUTH2_TOKEN} is a System-level table too, so the DAL refuses to flush any change to
 * such a row: {@code Entity etgo_oauth2_token may only have instances with organization *}. That
 * made rotate and revoke-tokens answer 500 for every key that had ever issued a token (ETP-5781).
 * Token revocation and deletion therefore run as SQL on the DAL connection, inside the request's
 * transaction, exactly like the administrator endpoints and the refresh-token rotation already do.
 *
 * <p>Package-visible and non-final so the servlet tests can replace it with a mock.
 */
class PublicApiKeyStore {

  /** Revokes every still-valid token of one key; the affected-row count is the answer. */
  static final String SQL_REVOKE_ACTIVE_TOKENS =
      "UPDATE etgo_oauth2_token SET is_revoked = 'Y', updated = now(), updatedby = ? "
      + "WHERE etgo_oauth2_client_id = ? AND is_revoked = 'N'";

  /** Removes every token of one key, revoked or not, before the key itself is deleted. */
  static final String SQL_DELETE_TOKENS =
      "DELETE FROM etgo_oauth2_token WHERE etgo_oauth2_client_id = ?";

  private static final String SYSTEM_ID = "0";
  private static final String PARAM_USER_ID = "userId";
  private static final String PARAM_ROLE_ID = "roleId";
  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_ORG_ID = "orgId";
  private static final String PARAM_OWNER_MARKER = "ownerOrganizationMarker";
  private static final String PARAM_PUBLIC_KEY_MARKER = "publicKeyMarker";

  /**
   * Verifies that the authenticated user, role, client and organization are active and that the
   * role is really held by the user in this tenant and organization.
   *
   * @param context the identity derived from the authenticated request
   * @throws AuthException 403 when the session context or the role assignment is not usable
   */
  void verifyContext(PublicApiKeyContext context) throws AuthException {
    User user = OBDal.getInstance().get(User.class, context.userId);
    Role role = OBDal.getInstance().get(Role.class, context.roleId);
    Client client = OBDal.getInstance().get(Client.class, context.clientId);
    Organization organization = OBDal.getInstance().get(Organization.class, context.orgId);
    if (!hasActiveSession(user, role, client, organization)
        || !roleBelongsToClient(role, context.clientId)) {
      throw new AuthException(HttpServletResponse.SC_FORBIDDEN,
          "Active session context required");
    }
    OBQuery<UserRoles> assignments = OBDal.getInstance().createQuery(UserRoles.class,
        "as assignment where assignment.userContact.id = :userId "
            + "and assignment.role.id = :roleId and assignment.active = true");
    assignments.setNamedParameter(PARAM_USER_ID, context.userId);
    assignments.setNamedParameter(PARAM_ROLE_ID, context.roleId);
    assignments.setFilterOnReadableClients(false);
    assignments.setFilterOnReadableOrganization(false);
    if (!hasActiveRoleAssignment(assignments.list(), context.clientId, context.orgId)
        || (!SYSTEM_ID.equals(context.roleId)
            && !roleHasOrganization(context.roleId, context.orgId))) {
      throw new AuthException(HttpServletResponse.SC_FORBIDDEN,
          "Active role is not available in this tenant");
    }
  }

  /** Returns every key, active or not, owned by the context's user in its organization. */
  List<OAuth2Client> ownedKeys(PublicApiKeyContext context) {
    OBQuery<OAuth2Client> query = OBDal.getInstance().createQuery(OAuth2Client.class,
        "as client where client.scopes like :ownerOrganizationMarker "
            + "and client.userContact.id = :userId and client.scopes like :publicKeyMarker "
            + "order by client.name, client.id");
    bindOwner(query, context);
    return query.list();
  }

  /** Returns the owned key with this id, or null when it does not exist or is not owned. */
  OAuth2Client findOwned(String id, PublicApiKeyContext context) {
    OBQuery<OAuth2Client> query = OBDal.getInstance().createQuery(OAuth2Client.class,
        "as client where client.id = :id and client.scopes like :ownerOrganizationMarker "
            + "and client.userContact.id = :userId "
            + "and client.scopes like :publicKeyMarker");
    query.setNamedParameter("id", id);
    bindOwner(query, context);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  /**
   * Persists a new active key owned by the context's user.
   *
   * @param storedScopes the internal scopes plus the public-key marker; the owner-organization
   *                     marker is appended here
   */
  OAuth2Client create(PublicApiKeyContext context, String name, String clientIdentifier,
      String secretHash, String storedScopes) {
    OAuth2Client client = OBProvider.getInstance().get(OAuth2Client.class);
    User owner = OBDal.getInstance().get(User.class, context.userId);
    // ETGO_OAUTH2_CLIENT is a system-level technical entity. Its AD client must remain 0; the
    // tenant is represented by the owner-organization marker, validated against the
    // authenticated context on every lifecycle operation.
    client.setClient(OBDal.getInstance().get(Client.class, SYSTEM_ID));
    client.setOrganization(OBDal.getInstance().get(Organization.class, SYSTEM_ID));
    client.setActive(true);
    client.setCreatedBy(owner);
    client.setUpdatedBy(owner);
    client.setName(name);
    client.setClientIdentifier(clientIdentifier);
    client.setClientSecretHash(secretHash);
    client.setUserContact(owner);
    client.setRole(OBDal.getInstance().get(Role.class, context.roleId));
    client.setScopes(storedScopes + " "
        + PublicApiKeyPolicy.ownerOrganizationMarker(context.orgId));
    client.setRedirectURIs("[]");
    OBDal.getInstance().save(client);
    OBDal.getInstance().flush();
    return client;
  }

  /** Flushes the changes made to an owned key, stamping who made them. */
  void save(OAuth2Client client, String userId) {
    client.setUpdatedBy(OBDal.getInstance().get(User.class, userId));
    OBDal.getInstance().save(client);
    OBDal.getInstance().flush();
  }

  /**
   * Revokes every still-valid token of a key by SQL (see the class comment for why not DAL).
   *
   * @return how many tokens were revoked by this call
   */
  int revokeTokens(String keyId, String userId) throws SQLException {
    Connection connection = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = connection.prepareStatement(SQL_REVOKE_ACTIVE_TOKENS)) {
      ps.setString(1, userId);
      ps.setString(2, keyId);
      return ps.executeUpdate();
    }
  }

  /** Deletes a key and, first, all of its tokens by SQL (see the class comment for why). */
  void delete(OAuth2Client client) throws SQLException {
    Connection connection = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = connection.prepareStatement(SQL_DELETE_TOKENS)) {
      ps.setString(1, client.getId());
      ps.executeUpdate();
    }
    OBDal.getInstance().remove(client);
    OBDal.getInstance().flush();
  }

  /** Returns whether the role may edit at least one window in this tenant and organization. */
  boolean hasWritableWindowAccess(String roleId, String clientId, String orgId) {
    OBQuery<WindowAccess> query = OBDal.getInstance().createQuery(WindowAccess.class,
        "as access where access.role.id = :roleId and access.active = true "
            + "and access.editableField = true and (access.client.id = :clientId "
            + "or access.client.id = '0') and (access.organization.id = :orgId "
            + "or access.organization.id = '0')");
    query.setNamedParameter(PARAM_ROLE_ID, roleId);
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setNamedParameter(PARAM_ORG_ID, orgId);
    query.setMaxResult(1);
    return !query.list().isEmpty();
  }

  /** Returns whether the role may run at least one process in this tenant and organization. */
  boolean hasProcessAccess(String roleId, String clientId, String orgId) {
    OBQuery<ProcessAccess> query = OBDal.getInstance().createQuery(ProcessAccess.class,
        "as access where access.role.id = :roleId and access.active = true "
            + "and (access.client.id = :clientId or access.client.id = '0') "
            + "and (access.organization.id = :orgId or access.organization.id = '0')");
    query.setNamedParameter(PARAM_ROLE_ID, roleId);
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setNamedParameter(PARAM_ORG_ID, orgId);
    query.setMaxResult(1);
    return !query.list().isEmpty();
  }

  private static void bindOwner(OBQuery<OAuth2Client> query, PublicApiKeyContext context) {
    query.setNamedParameter(PARAM_OWNER_MARKER,
        "%" + PublicApiKeyPolicy.ownerOrganizationMarker(context.orgId) + "%");
    query.setNamedParameter(PARAM_USER_ID, context.userId);
    query.setNamedParameter(PARAM_PUBLIC_KEY_MARKER,
        "%" + PublicApiKeyPolicy.PUBLIC_KEY_SCOPE_MARKER + "%");
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
  }

  private static boolean hasActiveSession(User user, Role role, Client client,
      Organization organization) {
    return user != null && Boolean.TRUE.equals(user.isActive()) && role != null
        && Boolean.TRUE.equals(role.isActive()) && client != null
        && Boolean.TRUE.equals(client.isActive()) && organization != null
        && Boolean.TRUE.equals(organization.isActive());
  }

  private static boolean roleBelongsToClient(Role role, String clientId) {
    return role.getClient() == null || SYSTEM_ID.equals(role.getClient().getId())
        || clientId.equals(role.getClient().getId());
  }

  private static boolean hasActiveRoleAssignment(List<UserRoles> assignments, String clientId,
      String orgId) {
    for (UserRoles assignment : assignments) {
      String assignmentClientId =
          assignment.getClient() == null ? null : assignment.getClient().getId();
      String assignmentOrgId =
          assignment.getOrganization() == null ? null : assignment.getOrganization().getId();
      if ((clientId.equals(assignmentClientId) || SYSTEM_ID.equals(assignmentClientId))
          && (orgId.equals(assignmentOrgId) || SYSTEM_ID.equals(assignmentOrgId))) {
        return true;
      }
    }
    return false;
  }

  private static boolean roleHasOrganization(String roleId, String orgId) {
    OBQuery<RoleOrganization> query = OBDal.getInstance().createQuery(RoleOrganization.class,
        "as access where access.role.id = :roleId and access.active = true "
            + "and (access.organization.id = :orgId or access.organization.id = '0')");
    query.setNamedParameter(PARAM_ROLE_ID, roleId);
    query.setNamedParameter(PARAM_ORG_ID, orgId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return !query.list().isEmpty();
  }
}
