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

import java.sql.SQLException;
import java.util.List;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Account;

final class EtendoGoJwtDalHelper {

  private static final String ZERO_ID = "0";
  private static final String STAR_ORGANIZATION_VALUE = "*";
  private static final String SYSTEM_USER_ID = "100";
  private static final String PARAM_EMAIL = "email";
  private static final String PARAM_TOKEN = "token";
  private static final String PARAM_ACCOUNT_EMAIL = "accountEmail";
  private static final String PARAM_ACCOUNT_PREFIX = "accountPrefix";
  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_CURRENCY_ISO = "currencyIso";
  private static final String PARAM_STAR_VALUE = "starValue";
  private static final String FIELD_CLIENT_ID = "clientId";
  private static final String FIELD_CLIENT_NAME = "clientName";
  private static final String FIELD_ORG_ID = "orgId";
  private static final String FIELD_ORG_NAME = "orgName";
  private static final String FIELD_ADMIN_USER_ID = "adminUserId";
  private static final String FIELD_ADMIN_USER = "adminUser";

  private EtendoGoJwtDalHelper() {
  }

  static Account findActiveAccountByEmail(String email) {
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where lower(account.email) = :" + PARAM_EMAIL + " and account.active = true");
    query.setNamedParameter(PARAM_EMAIL, email.toLowerCase());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  static Account findActiveAccountByToken(String token) {
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where account.sessionToken = :" + PARAM_TOKEN + " and account.active = true");
    query.setNamedParameter(PARAM_TOKEN, token);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  static Account createAccount(String email, String passwordHash, String name, String sessionToken)
      throws SQLException {
    Account account = OBProvider.getInstance().get(Account.class);
    account.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
    account.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
    account.setEmail(email);
    account.setPasswordHash(passwordHash);
    account.setName(name);
    account.setSessionToken(sessionToken);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
    return account;
  }

  static void updateSessionToken(Account account, String sessionToken) throws SQLException {
    account.setSessionToken(sessionToken);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
  }

  static List<User> findEnvironmentUsersByAccountEmail(String accountEmail) {
    OBQuery<User> query = OBDal.getInstance().createQuery(User.class,
        "as user where (user.username = :" + PARAM_ACCOUNT_EMAIL
            + " or user.username like :" + PARAM_ACCOUNT_PREFIX + ") "
            + "and user.active = true and user.client.active = true and user.client.id <> '0' "
            + "order by user.client.creationDate, user.creationDate");
    query.setNamedParameter(PARAM_ACCOUNT_EMAIL, accountEmail);
    query.setNamedParameter(PARAM_ACCOUNT_PREFIX, accountEmail + "+%");
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.list();
  }

  static List<Organization> findNonStarOrganizations(String clientId) {
    OBQuery<Organization> query = OBDal.getInstance().createQuery(Organization.class,
        "as organization where organization.client.id = :" + PARAM_CLIENT_ID
            + " and organization.searchKey <> :" + PARAM_STAR_VALUE
            + " order by organization.creationDate");
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setNamedParameter(PARAM_STAR_VALUE, STAR_ORGANIZATION_VALUE);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.list();
  }

  static JSONObject buildEnvironmentJson(Client client, Organization organization, User environmentUser)
      throws JSONException {
    JSONObject env = new JSONObject();
    env.put(FIELD_CLIENT_ID, client.getId());
    env.put(FIELD_CLIENT_NAME, client.getName());
    env.put(FIELD_ORG_ID, organization != null ? organization.getId() : JSONObject.NULL);
    env.put(FIELD_ORG_NAME, organization != null ? organization.getName() : JSONObject.NULL);
    env.put(FIELD_ADMIN_USER_ID, environmentUser.getId());
    env.put(FIELD_ADMIN_USER, environmentUser.getUsername());
    return env;
  }

  static Currency findCurrencyByIsoCode(String currencyIso) {
    OBQuery<Currency> query = OBDal.getInstance().createQuery(Currency.class,
        "as currency where upper(currency.iSOCode) = :" + PARAM_CURRENCY_ISO);
    query.setNamedParameter(PARAM_CURRENCY_ISO, currencyIso.toUpperCase());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  static UserRoles findClientAdminUserRole(String clientId) {
    OBQuery<UserRoles> query = OBDal.getInstance().createQuery(UserRoles.class,
        "as userRole where userRole.role.client.id = :" + PARAM_CLIENT_ID
            + " and userRole.userContact.id <> '" + SYSTEM_USER_ID + "'"
            + " order by userRole.role.creationDate");
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    List<UserRoles> userRoles = query.list();
    return userRoles.isEmpty() ? null : userRoles.get(0);
  }

  static Organization findFirstOrganization(String clientId) {
    OBQuery<Organization> query = OBDal.getInstance().createQuery(Organization.class,
        "as organization where organization.client.id = :" + PARAM_CLIENT_ID
            + " and organization.searchKey <> :" + PARAM_STAR_VALUE
            + " order by organization.creationDate");
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setNamedParameter(PARAM_STAR_VALUE, STAR_ORGANIZATION_VALUE);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    List<Organization> organizations = query.list();
    return organizations.isEmpty() ? null : organizations.get(0);
  }

  private static void flushAndCommitDalChanges() throws SQLException {
    OBDal.getInstance().flush();
    OBDal.getInstance().getConnection().commit();
  }
}
