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

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.schemaforge.util.OwnerSupport;

/**
 * ETP-5734 — the per-company details the "Mis empresas" page shows, appended to a
 * {@code GET /sws/go/environments?details=true} entry.
 *
 * <p>Opt-in on purpose: the plain environment list is read on every app load (side menu, top bar,
 * plan resolution), and each detail costs extra queries per tenant. Only the page that renders
 * them asks for them.
 *
 * <p>What is appended depends on the account's relationship with the company:
 * <ul>
 *   <li>Owner: {@code userCount}.</li>
 *   <li>Invited: {@code ownerName} and the account's role in that company — {@code roleName},
 *       {@code roleIsClientAdmin} and {@code roleTemplateNames}, the same three inputs the web
 *       client's user menu already turns into a localized role label (ETP-5329).</li>
 * </ul>
 *
 * <p>Every detail is best effort: a failure is logged and leaves the field out, so the company is
 * still listed and can still be entered.
 */
final class EnvironmentDetails {

  private static final Logger log = LogManager.getLogger(EnvironmentDetails.class);

  static final String FIELD_USER_COUNT = "userCount";
  static final String FIELD_OWNER_NAME = "ownerName";
  static final String FIELD_ROLE_NAME = "roleName";
  static final String FIELD_ROLE_IS_CLIENT_ADMIN = "roleIsClientAdmin";
  static final String FIELD_ROLE_TEMPLATE_NAMES = "roleTemplateNames";

  /**
   * Counts the users the Users window lists for the client: active users that are either the
   * tenant owner or were created through an invitation. The same structural rule as
   * {@code UserRoleAssignmentHandler#buildContactOnlyExclusion} (ETP-5411), so a business-partner
   * contact row never counts as a user.
   */
  private static final String SQL_COUNT_USERS = "SELECT count(*) FROM ad_user u "
      + "WHERE u.ad_client_id = :clientId AND u.isactive = 'Y' "
      + "AND (u.em_etgo_is_owner = 'Y' "
      + "OR EXISTS (SELECT 1 FROM etgo_invitation i WHERE i.ad_user_id = u.ad_user_id))";

  private EnvironmentDetails() {
  }

  /**
   * Builds the details for one company. The caller merges them into every entry of that company
   * (one per organization), so they are computed once per client.
   *
   * @param clientId the company's {@code AD_Client_ID}
   * @param environmentUser the account's {@code AD_User} in that company
   * @param owner whether the account owns the company
   * @return the detail fields, never {@code null}
   */
  static JSONObject resolve(String clientId, User environmentUser, boolean owner) {
    JSONObject details = new JSONObject();
    if (owner) {
      putUserCount(details, clientId);
    } else {
      putOwnerName(details, clientId);
      putRole(details, environmentUser);
    }
    return details;
  }

  /**
   * Copies every field of {@code details} into {@code env}.
   *
   * @param env the environment entry being built
   * @param details the output of {@link #resolve}
   * @throws JSONException when a value cannot be written
   */
  static void merge(JSONObject env, JSONObject details) throws JSONException {
    JSONArray names = details.names();
    if (names == null) {
      return;
    }
    for (int i = 0; i < names.length(); i++) {
      String name = names.getString(i);
      env.put(name, details.get(name));
    }
  }

  private static void putUserCount(JSONObject details, String clientId) {
    try {
      NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(SQL_COUNT_USERS);
      query.setParameter("clientId", clientId);
      Object count = query.uniqueResult();
      if (count instanceof Number) {
        details.put(FIELD_USER_COUNT, ((Number) count).intValue());
      }
    } catch (RuntimeException | JSONException e) {
      log.warn("Could not count the users of client {}", clientId, e);
    }
  }

  private static void putOwnerName(JSONObject details, String clientId) {
    try {
      String ownerId = OwnerSupport.findOwnerUserId(clientId);
      User owner = ownerId == null ? null : OBDal.getInstance().get(User.class, ownerId);
      if (owner != null && owner.getName() != null) {
        details.put(FIELD_OWNER_NAME, owner.getName());
      }
    } catch (RuntimeException | JSONException e) {
      log.warn("Could not resolve the owner of client {}", clientId, e);
    }
  }

  private static void putRole(JSONObject details, User environmentUser) {
    try {
      Role role = environmentUser.getDefaultRole();
      if (role == null) {
        return;
      }
      details.put(FIELD_ROLE_NAME, role.getName());
      details.put(FIELD_ROLE_IS_CLIENT_ADMIN, Boolean.TRUE.equals(role.isClientAdmin()));
      List<String> templateNames =
          EtendoGoJwtSupport.resolveEffectiveRoleNames(environmentUser.getId());
      details.put(FIELD_ROLE_TEMPLATE_NAMES, new JSONArray(templateNames));
    } catch (RuntimeException | JSONException e) {
      log.warn("Could not resolve the role of user {}", environmentUser.getId(), e);
    }
  }
}
