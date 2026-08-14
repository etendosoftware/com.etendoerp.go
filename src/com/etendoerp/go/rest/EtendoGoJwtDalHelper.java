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

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
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

import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.schemaforge.data.Account;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

final class EtendoGoJwtDalHelper {

  private static final String ZERO_ID = "0";
  private static final String STAR_ORGANIZATION_VALUE = "*";
  private static final String SYSTEM_USER_ID = "100";
  private static final String PARAM_EMAIL = "email";
  private static final String PARAM_TOKEN = "token";
  private static final String PARAM_AUTH_PROVIDER = "authProvider";
  private static final String PARAM_EXTERNAL_SUBJECT = "externalSubject";
  private static final String PARAM_RESET_TOKEN_HASH = "resetTokenHash";
  private static final String PARAM_ACCOUNT_EMAIL = "accountEmail";
  private static final String PARAM_ACCOUNT_PREFIX = "accountPrefix";
  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_CURRENCY_ISO = "currencyIso";
  private static final String PARAM_STAR_VALUE = "starValue";
  private static final String PARAM_SYSTEM_USER_ID = "systemUserId";
  private static final String ACTIVE_ACCOUNT_FILTER = " and account.active = true";
  private static final String FIELD_CLIENT_ID = "clientId";
  private static final String FIELD_CLIENT_NAME = "clientName";
  private static final String FIELD_ORG_ID = "orgId";
  private static final String FIELD_ORG_NAME = "orgName";
  private static final String FIELD_ADMIN_USER_ID = "adminUserId";
  private static final String FIELD_ADMIN_USER = "adminUser";
  private static final String FIELD_ADMIN_USER_NAME = "adminUserName";
  private static final String FIELD_PLAN = "plan";
  private static final String PROPERTY_PASSWORD_CHANGED = Account.PROPERTY_PASSWORDCHANGED;
  private static final String PROPERTY_RESET_TOKEN_CONSUMED = Account.PROPERTY_RESETTOKENCONSUMED;
  private static final String PROPERTY_RESET_TOKEN_EXPIRES = Account.PROPERTY_RESETTOKENEXPIRES;
  private static final String PROPERTY_RESET_TOKEN_HASH = Account.PROPERTY_RESETTOKENHASH;
  private static final String PROPERTY_AUTH_PROVIDER = Account.PROPERTY_AUTHPROVIDER;
  private static final String PROPERTY_EXTERNAL_SUBJECT = Account.PROPERTY_EXTERNALSUBJECT;
  private static final String PROPERTY_EXTERNAL_EMAIL = Account.PROPERTY_EXTERNALEMAIL;
  private static final String PROPERTY_LAST_SSO_LOGIN = Account.PROPERTY_LASTSSOLOGIN;
  private static final TenantPlanService TENANT_PLAN_SERVICE = new TenantPlanService();
  // ETP-4829: STATUS distinguishes an account that already owns a usable local password
  // ("active", the default for self-registration/SSO) from one an admin created on a user's
  // behalf, awaiting the ETP-4830 invite-email flow to set a password ("pending"). No login is
  // possible on a pending account — hasLocalPassword() already returns false (passwordHash is
  // null), STATUS exists purely to distinguish this from a not-yet-implemented "SSO account with
  // no local password by design" case in UI-facing account listings.
  private static final String STATUS_ACTIVE = "active";
  private static final String STATUS_PENDING = "pending";
  private static final String PROPERTY_STATUS = Account.PROPERTY_STATUS;

  private EtendoGoJwtDalHelper() {
  }

  static Account findActiveAccountByEmail(String email) {
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where lower(account.email) = :" + PARAM_EMAIL + ACTIVE_ACCOUNT_FILTER);
    query.setNamedParameter(PARAM_EMAIL, email.toLowerCase());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  static Account findActiveAccountByToken(String token) {
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where account.sessionToken = :" + PARAM_TOKEN + ACTIVE_ACCOUNT_FILTER);
    query.setNamedParameter(PARAM_TOKEN, token);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  /** Resolves either an account session token or the active environment JWT to its account. */
  static Account findActiveAccountByBearerToken(String token) {
    Account account = findActiveAccountByToken(token);
    if (account != null || StringUtils.isBlank(token)) return account;
    try {
      DecodedJWT jwt = SecureWebServicesUtils.decodeToken(token);
      String userId = jwt == null ? null : jwt.getClaim("user").asString();
      User user = StringUtils.isBlank(userId) ? null : OBDal.getInstance().get(User.class, userId);
      if (user == null || !Boolean.TRUE.equals(user.isActive())) {
        return null;
      }
      String accountEmail = StringUtils.trimToNull(user.getEmail());
      if (accountEmail == null) accountEmail = StringUtils.trimToNull(user.getUsername());
      account = accountEmail == null ? null : findActiveAccountByEmail(accountEmail);
      if (account == null && StringUtils.isNotBlank(user.getUsername())) {
        account = findActiveAccountByEmail(user.getUsername());
      }
      return account != null && clientBelongsToAccountEmail(user.getClient().getId(), account.getEmail())
          ? account : null;
    } catch (Exception e) {
      return null;
    }
  }

  static Account findActiveAccountBySsoIdentity(String provider, String subject) {
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where account." + PROPERTY_AUTH_PROVIDER + " = :" + PARAM_AUTH_PROVIDER
            + " and account." + PROPERTY_EXTERNAL_SUBJECT + " = :" + PARAM_EXTERNAL_SUBJECT
            + ACTIVE_ACCOUNT_FILTER);
    query.setNamedParameter(PARAM_AUTH_PROVIDER, provider);
    query.setNamedParameter(PARAM_EXTERNAL_SUBJECT, subject);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  static Account createAccount(String email, String passwordHash, String name, String sessionToken) {
    Account account = OBProvider.getInstance().get(Account.class);
    account.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
    account.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
    account.setEmail(email);
    account.setPasswordHash(passwordHash);
    account.setName(name);
    account.setSessionToken(sessionToken);
    account.set(PROPERTY_STATUS, STATUS_ACTIVE);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
    return account;
  }

  /**
   * ETP-4829: creates the {@code etgo_account} row for a user an admin created directly (not via
   * self-service {@code /sws/go/register}). No password is set here — the account is left in the
   * {@code pending} status with a {@code null} password hash, exactly like an SSO-only account
   * (see {@link #hasLocalPassword(Account)}), so it cannot log in until ETP-4830's invite-email
   * flow sets one (e.g. by driving the same {@code password-reset/confirm} path used for a
   * normal reset) and flips the status to {@code active} — that transition is ETP-4830's
   * responsibility, not implemented here. Returns silently with the existing account if one is
   * already registered for this email
   * (e.g. the admin is linking a newly-created {@code AD_User} to a pre-existing platform
   * account) — this must never fail the parent {@code AD_User} save.
   */
  /**
   * ETP-4829: creates an {@code etgo_account} for a user an admin created directly, with a real,
   * immediately-usable password the admin typed on the create form — a temporary workaround for
   * environments where ETP-4830's invite-email flow isn't available yet. {@code passwordHash}
   * must already be produced by {@link PasswordHasher#hash} (this method does not hash or
   * validate strength — see {@link EtendoGoAccountProvisioning#ensureAccountForCreatedUser},
   * which does both before calling this). Unlike {@link #createPendingAccount}, the account is
   * {@code active} immediately: it already has everything a normal local-password account needs
   * to log in. Same duplicate-email handling as {@link #createPendingAccount} — returns the
   * existing account untouched rather than failing the parent {@code AD_User} save.
   */
  static Account createActiveAccount(String email, String passwordHash, String name) {
    Account existing = findActiveAccountByEmail(email);
    if (existing != null) {
      return existing;
    }
    Account account = OBProvider.getInstance().get(Account.class);
    account.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
    account.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
    account.setEmail(email);
    account.setPasswordHash(passwordHash);
    account.setName(name);
    account.setSessionToken(null);
    account.set(PROPERTY_STATUS, STATUS_ACTIVE);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
    return account;
  }

  static Account createPendingAccount(String email, String name) {
    Account existing = findActiveAccountByEmail(email);
    if (existing != null) {
      return existing;
    }
    Account account = OBProvider.getInstance().get(Account.class);
    account.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
    account.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
    account.setEmail(email);
    account.setPasswordHash(null);
    account.setName(name);
    account.setSessionToken(null);
    account.set(PROPERTY_STATUS, STATUS_PENDING);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
    return account;
  }

  static Account createSsoAccount(String email, String name, String provider, String subject,
      String externalEmail, String sessionToken, Date loginAt) {
    Account account = OBProvider.getInstance().get(Account.class);
    account.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
    account.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
    account.setEmail(email);
    account.setPasswordHash(null);
    account.setName(name);
    account.setSessionToken(sessionToken);
    account.set(PROPERTY_AUTH_PROVIDER, provider);
    account.set(PROPERTY_EXTERNAL_SUBJECT, subject);
    account.set(PROPERTY_EXTERNAL_EMAIL, externalEmail);
    account.set(PROPERTY_LAST_SSO_LOGIN, loginAt);
    account.set(PROPERTY_STATUS, STATUS_ACTIVE);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
    return account;
  }

  static void updateSessionToken(Account account, String sessionToken) {
    account.setSessionToken(sessionToken);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
  }

  static boolean hasLocalPassword(Account account) {
    return account != null && StringUtils.isNotBlank(account.getPasswordHash());
  }

  static boolean hasPasswordResetToken(Account account) {
    return account != null && StringUtils.isNotBlank((String) account.get(PROPERTY_RESET_TOKEN_HASH));
  }

  static boolean linkSsoIdentityIfCompatible(Account account, String provider, String subject,
      String externalEmail) {
    String currentProvider = StringUtils.trimToNull((String) account.get(PROPERTY_AUTH_PROVIDER));
    String currentSubject = StringUtils.trimToNull((String) account.get(PROPERTY_EXTERNAL_SUBJECT));
    if (currentProvider == null && currentSubject == null) {
      account.set(PROPERTY_AUTH_PROVIDER, provider);
      account.set(PROPERTY_EXTERNAL_SUBJECT, subject);
      account.set(PROPERTY_EXTERNAL_EMAIL, externalEmail);
      return true;
    }
    return StringUtils.equals(provider, currentProvider) && StringUtils.equals(subject,
        currentSubject);
  }

  static void updateSsoSession(Account account, String externalEmail, String sessionToken,
      Date loginAt) {
    account.setSessionToken(sessionToken);
    account.set(PROPERTY_EXTERNAL_EMAIL, externalEmail);
    account.set(PROPERTY_LAST_SSO_LOGIN, loginAt);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
  }

  static void storePasswordResetToken(Account account, String resetTokenHash, Date expiresAt) {
    account.set(PROPERTY_RESET_TOKEN_HASH, resetTokenHash);
    account.set(PROPERTY_RESET_TOKEN_EXPIRES, expiresAt);
    account.set(PROPERTY_RESET_TOKEN_CONSUMED, null);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
  }

  static PasswordResetTokenState capturePasswordResetToken(Account account) {
    if (account == null) {
      return null;
    }
    return new PasswordResetTokenState((String) account.get(PROPERTY_RESET_TOKEN_HASH),
        (Date) account.get(PROPERTY_RESET_TOKEN_EXPIRES),
        (Date) account.get(PROPERTY_RESET_TOKEN_CONSUMED));
  }

  static void restorePasswordResetToken(Account account, PasswordResetTokenState tokenState) {
    if (account == null || tokenState == null) {
      return;
    }
    account.set(PROPERTY_RESET_TOKEN_HASH, tokenState.resetTokenHash);
    account.set(PROPERTY_RESET_TOKEN_EXPIRES, tokenState.resetTokenExpires);
    account.set(PROPERTY_RESET_TOKEN_CONSUMED, tokenState.resetTokenConsumed);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
  }

  static Account findActiveAccountByResetTokenHash(String resetTokenHash, Date now) {
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where account." + PROPERTY_RESET_TOKEN_HASH + " = :"
            + PARAM_RESET_TOKEN_HASH
            + " and account." + PROPERTY_RESET_TOKEN_EXPIRES + " > :now"
            + " and account." + PROPERTY_RESET_TOKEN_CONSUMED + " is null"
            + " and account.active = true");
    query.setNamedParameter(PARAM_RESET_TOKEN_HASH, resetTokenHash);
    query.setNamedParameter("now", now);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  static void consumePasswordReset(Account account, String passwordHash, Date changedAt) {
    account.setPasswordHash(passwordHash);
    account.setSessionToken(null);
    account.set(PROPERTY_STATUS, STATUS_ACTIVE);
    account.set(PROPERTY_RESET_TOKEN_HASH, null);
    account.set(PROPERTY_RESET_TOKEN_EXPIRES, null);
    account.set(PROPERTY_RESET_TOKEN_CONSUMED, changedAt);
    account.set(PROPERTY_PASSWORD_CHANGED, changedAt);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
  }

  static void changePassword(Account account, String passwordHash, String sessionToken,
      Date changedAt) {
    account.setPasswordHash(passwordHash);
    account.setSessionToken(sessionToken);
    account.set(PROPERTY_RESET_TOKEN_HASH, null);
    account.set(PROPERTY_RESET_TOKEN_EXPIRES, null);
    account.set(PROPERTY_RESET_TOKEN_CONSUMED, changedAt);
    account.set(PROPERTY_PASSWORD_CHANGED, changedAt);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
  }

  static String getOnboardingDraft(Account account) {
    return account == null ? null : account.getOnboardingDraft();
  }

  static void updateOnboardingDraft(Account account, String draftJson) {
    if (account == null) {
      return;
    }
    account.setOnboardingDraft(draftJson);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
  }

  /**
   * Returns {@code true} when the given client is owned by the account identified by
   * {@code accountEmail}, i.e. it has an active user whose username is the account email or an
   * {@code email+suffix} variant of it — the same ownership criterion used by
   * {@link #findEnvironmentUsersByAccountEmail}. Used by onboarding to decide whether an existing
   * same-named client may be resumed (ETP-4428): a name collision with another account's client
   * must never be resumable.
   *
   * <p>The {@code email+suffix} match is a bounded prefix LIKE: the account email is treated as a
   * literal (its {@code %}, {@code _} and {@code \} are escaped and the LIKE declares
   * {@code escape '\'}), so an attacker cannot register a wildcard-bearing email (e.g. {@code "%"})
   * to make the prefix branch match users owned by another account. This is what preserves tenant
   * isolation for the resume decision.</p>
   */
  static boolean clientBelongsToAccountEmail(String clientId, String accountEmail) {
    OBQuery<User> query = OBDal.getInstance().createQuery(User.class,
        "as user where user.client.id = :" + PARAM_CLIENT_ID
            + " and (user.email = :" + PARAM_ACCOUNT_EMAIL
            + " or user.username = :" + PARAM_ACCOUNT_EMAIL
            + " or user.username like :" + PARAM_ACCOUNT_PREFIX + " escape '\\') "
            + "and user.active = true");
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setNamedParameter(PARAM_ACCOUNT_EMAIL, accountEmail);
    query.setNamedParameter(PARAM_ACCOUNT_PREFIX, escapeLikeWildcards(accountEmail) + "+%");
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult() != null;
  }

  static List<User> findEnvironmentUsersByAccountEmail(String accountEmail) {
    OBQuery<User> query = OBDal.getInstance().createQuery(User.class,
        "as user where (user.email = :" + PARAM_ACCOUNT_EMAIL
            + " or user.username = :" + PARAM_ACCOUNT_EMAIL
            + " or user.username like :" + PARAM_ACCOUNT_PREFIX + " escape '\\') "
            + "and user.active = true and user.client.active = true and user.client.id <> '0' "
            + "order by user.client.creationDate, user.creationDate");
    query.setNamedParameter(PARAM_ACCOUNT_EMAIL, accountEmail);
    query.setNamedParameter(PARAM_ACCOUNT_PREFIX, escapeLikeWildcards(accountEmail) + "+%");
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.list();
  }

  /**
   * Escapes the SQL/HQL LIKE wildcards ({@code %} and {@code _}) and the escape character itself
   * ({@code \}) in {@code value} so it can be embedded as a literal fragment in a LIKE pattern.
   * The backslash must be escaped first to avoid double-escaping the wildcard replacements.
   * Callers must pair the escaped value with an {@code escape '\'} clause in the LIKE, otherwise
   * the backslashes are treated as literals and the wildcards are NOT neutralized.
   *
   * @param value
   *     the raw value to embed inside a LIKE pattern
   * @return the value with LIKE wildcards escaped for use with {@code escape '\'}
   */
  private static String escapeLikeWildcards(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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

  /**
   * Counts the distinct tenants (AD_Clients) the account owns, using the same username-match rule
   * as {@link #findEnvironmentUsersByAccountEmail}. This is what the onboarding paywall reads to
   * tell a first (free) tenant from an additional (paid) one.
   *
   * @param accountEmail the authenticated account email
   * @return the number of distinct clients linked to the account
   */
  static int countTenantsOwnedByAccountEmail(String accountEmail) {
    Set<String> clientIds = new HashSet<>();
    for (User environmentUser : findEnvironmentUsersByAccountEmail(accountEmail)) {
      clientIds.add(environmentUser.getClient().getId());
    }
    return clientIds.size();
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
    env.put(FIELD_ADMIN_USER_NAME, environmentUser.getName());
    // Additive since ETP-4686 so the environment picker can badge the plan. Older clients that
    // ignore the field keep working, and a tenant with no plan marker reads back as free.
    env.put(FIELD_PLAN, TENANT_PLAN_SERVICE.resolvePlan(client.getId()));
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
        "as userrole where userrole.role.client.id = :" + PARAM_CLIENT_ID
            + " and userrole.userContact.id <> :" + PARAM_SYSTEM_USER_ID
            + " order by userrole.role.creationDate");
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setNamedParameter(PARAM_SYSTEM_USER_ID, SYSTEM_USER_ID);
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

  private static void flushAndCommitDalChanges() {
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();
  }

  static final class PasswordResetTokenState {
    private final String resetTokenHash;
    private final Date resetTokenExpires;
    private final Date resetTokenConsumed;

    private PasswordResetTokenState(String resetTokenHash, Date resetTokenExpires,
        Date resetTokenConsumed) {
      this.resetTokenHash = resetTokenHash;
      this.resetTokenExpires = resetTokenExpires;
      this.resetTokenConsumed = resetTokenConsumed;
    }
  }
}
