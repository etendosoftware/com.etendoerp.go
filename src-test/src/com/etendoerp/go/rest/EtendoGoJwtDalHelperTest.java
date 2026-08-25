/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.Invitation;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Unit tests for {@link EtendoGoJwtDalHelper}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EtendoGoJwtDalHelperTest {

  @Mock private OBDal obDal;
  @Mock private OBProvider obProvider;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBProvider> obProviderMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obProviderMock = mockStatic(OBProvider.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (obProviderMock != null) {
      obProviderMock.close();
    }
  }

  @Nested
  @DisplayName("findActiveAccountByEmail")
  class FindActiveAccountByEmail {

    @Mock private OBQuery<Account> query;

    @Test
    @DisplayName("returns account when found")
    void returnsAccountWhenFound() {
      Account expected = mock(Account.class);
      when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(expected);

      Account result = EtendoGoJwtDalHelper.findActiveAccountByEmail("Test@Example.com");

      assertEquals(expected, result);
      verify(query).setNamedParameter("email", "test@example.com");
      verify(query).setFilterOnReadableClients(false);
      verify(query).setFilterOnReadableOrganization(false);
    }

    @Test
    @DisplayName("returns null when not found")
    void returnsNullWhenNotFound() {
      when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(null);

      Account result = EtendoGoJwtDalHelper.findActiveAccountByEmail("missing@example.com");

      assertNull(result);
    }
  }

  @Nested
  @DisplayName("findActiveAccountByToken")
  class FindActiveAccountByToken {

    @Mock private OBQuery<Account> query;

    @Test
    @DisplayName("returns account when token matches")
    void returnsAccountWhenTokenMatches() {
      Account expected = mock(Account.class);
      when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(expected);

      Account result = EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token");

      assertEquals(expected, result);
      verify(query).setNamedParameter("token", "valid-token");
      verify(query).setFilterOnReadableClients(false);
      verify(query).setFilterOnReadableOrganization(false);
    }
  }

  @Nested
  @DisplayName("createAccount")
  class CreateAccount {

    @Mock private Account account;
    @Mock private Client client;
    @Mock private Organization organization;

    @Test
    @DisplayName("creates account with all properties and commits")
    void createsAccountWithAllProperties() {
      when(obProvider.get(Account.class)).thenReturn(account);
      when(obDal.get(Client.class, "0")).thenReturn(client);
      when(obDal.get(Organization.class, "0")).thenReturn(organization);

      Account result = EtendoGoJwtDalHelper.createAccount(
          "user@test.com", "hash123", "Test User", "session-token-1");

      assertEquals(account, result);
      verify(account).setClient(client);
      verify(account).setOrganization(organization);
      verify(account).setEmail("user@test.com");
      verify(account).setPasswordHash("hash123");
      verify(account).setName("Test User");
      verify(account).setSessionToken("session-token-1");
      verify(obDal).save(account);
      verify(obDal).flush();
      verify(obDal).commitAndClose();
    }
  }

  @Nested
  @DisplayName("createActiveAccount")
  class CreateActiveAccount {

    @Mock private Account account;
    @Mock private Client client;
    @Mock private Organization organization;
    @Mock private OBQuery<Account> query;

    @Test
    @DisplayName("creates an active account with the given password hash when none exists yet")
    void createsActiveAccountWithGivenPasswordHash() {
      when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(null);
      when(obProvider.get(Account.class)).thenReturn(account);
      when(obDal.get(Client.class, "0")).thenReturn(client);
      when(obDal.get(Organization.class, "0")).thenReturn(organization);

      Account result = EtendoGoJwtDalHelper.createActiveAccount(
          "admin.set@test.com", "salt:hash", "Admin Set User");

      assertEquals(account, result);
      verify(account).setClient(client);
      verify(account).setOrganization(organization);
      verify(account).setEmail("admin.set@test.com");
      verify(account).setPasswordHash("salt:hash");
      verify(account).setName("Admin Set User");
      verify(account).setSessionToken(null);
      verify(account).set("status", "active");
      verify(obDal).save(account);
      verify(obDal).flush();
      verify(obDal).commitAndClose();
    }

    @Test
    @DisplayName("returns the existing account and creates nothing when email is already registered")
    void returnsExistingAccountWithoutCreatingANewOne() {
      Account existing = mock(Account.class);
      when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(existing);

      Account result = EtendoGoJwtDalHelper.createActiveAccount(
          "already@test.com", "salt:hash", "Someone");

      assertEquals(existing, result);
      verify(obProvider, org.mockito.Mockito.never()).get(Account.class);
      verify(obDal, org.mockito.Mockito.never()).save(any());
    }
  }

  @Nested
  @DisplayName("createPendingAccount")
  class CreatePendingAccount {

    @Mock private Account account;
    @Mock private Client client;
    @Mock private Organization organization;
    @Mock private OBQuery<Account> query;

    @Test
    @DisplayName("creates a pending account with no password when none exists yet")
    void createsPendingAccountWhenNoneExists() {
      when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(null);
      when(obProvider.get(Account.class)).thenReturn(account);
      when(obDal.get(Client.class, "0")).thenReturn(client);
      when(obDal.get(Organization.class, "0")).thenReturn(organization);

      Account result = EtendoGoJwtDalHelper.createPendingAccount("new.user@test.com", "New User");

      assertEquals(account, result);
      verify(account).setClient(client);
      verify(account).setOrganization(organization);
      verify(account).setEmail("new.user@test.com");
      verify(account).setPasswordHash(null);
      verify(account).setName("New User");
      verify(account).setSessionToken(null);
      verify(account).set("status", "pending");
      verify(obDal).save(account);
      verify(obDal).flush();
      verify(obDal).commitAndClose();
    }

    @Test
    @DisplayName("returns the existing account and creates nothing when email is already registered")
    void returnsExistingAccountWithoutCreatingANewOne() {
      Account existing = mock(Account.class);
      when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(existing);

      Account result = EtendoGoJwtDalHelper.createPendingAccount("already@test.com", "Someone");

      assertEquals(existing, result);
      verify(obProvider, org.mockito.Mockito.never()).get(Account.class);
      verify(obDal, org.mockito.Mockito.never()).save(any());
    }
  }

  @Nested
  @DisplayName("updateSessionToken")
  class UpdateSessionToken {

    @Mock private Account account;

    @Test
    @DisplayName("updates token, saves, and commits")
    void updatesTokenSavesAndCommits() {
      EtendoGoJwtDalHelper.updateSessionToken(account, "new-token");

      verify(account).setSessionToken("new-token");
      verify(obDal).save(account);
      verify(obDal).flush();
      verify(obDal).commitAndClose();
    }
  }

  @Nested
  @DisplayName("password reset and change")
  class PasswordResetAndChange {

    @Mock private Account account;
    @Mock private OBQuery<Account> query;

    @Test
    @DisplayName("stores reset token hash with expiry and clears consumed timestamp")
    void storesPasswordResetToken() {
      Date expiresAt = new Date();

      EtendoGoJwtDalHelper.storePasswordResetToken(account, "hash-1", expiresAt);

      verify(account).set("resetTokenHash", "hash-1");
      verify(account).set("resetTokenExpires", expiresAt);
      verify(account).set("resetTokenConsumed", null);
      verify(obDal).save(account);
      verify(obDal).flush();
      verify(obDal).commitAndClose();
    }

    @Test
    @DisplayName("captures and restores previous reset token state")
    void capturesAndRestoresPasswordResetToken() {
      Date expiresAt = new Date();
      Date consumedAt = new Date(expiresAt.getTime() - 1_000);
      when(account.get("resetTokenHash")).thenReturn("previous-hash");
      when(account.get("resetTokenExpires")).thenReturn(expiresAt);
      when(account.get("resetTokenConsumed")).thenReturn(consumedAt);

      EtendoGoJwtDalHelper.PasswordResetTokenState tokenState =
          EtendoGoJwtDalHelper.capturePasswordResetToken(account);
      EtendoGoJwtDalHelper.restorePasswordResetToken(account, tokenState);

      verify(account).set("resetTokenHash", "previous-hash");
      verify(account).set("resetTokenExpires", expiresAt);
      verify(account).set("resetTokenConsumed", consumedAt);
      verify(obDal).save(account);
      verify(obDal).flush();
      verify(obDal).commitAndClose();
    }

    @Test
    @DisplayName("ignores null reset token state inputs")
    void ignoresNullPasswordResetTokenStateInputs() {
      assertNull(EtendoGoJwtDalHelper.capturePasswordResetToken(null));

      EtendoGoJwtDalHelper.restorePasswordResetToken(null, null);
      EtendoGoJwtDalHelper.restorePasswordResetToken(account, null);
    }

    @Test
    @DisplayName("finds active account by unconsumed, unexpired reset token hash")
    void findsActiveAccountByResetTokenHash() {
      Date now = new Date();
      Account expected = mock(Account.class);
      when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(expected);

      Account result = EtendoGoJwtDalHelper.findActiveAccountByResetTokenHash("hash-1", now);

      assertEquals(expected, result);
      verify(query).setNamedParameter("resetTokenHash", "hash-1");
      verify(query).setNamedParameter("now", now);
      verify(query).setFilterOnReadableClients(false);
      verify(query).setFilterOnReadableOrganization(false);
    }

    @Test
    @DisplayName("consumes reset token, changes password, and clears session")
    void consumesPasswordReset() {
      Date changedAt = new Date();

      EtendoGoJwtDalHelper.consumePasswordReset(account, "new-hash", changedAt);

      verify(account).setPasswordHash("new-hash");
      verify(account).setSessionToken(null);
      verify(account).set("status", "active");
      verify(account).set("resetTokenHash", null);
      verify(account).set("resetTokenExpires", null);
      verify(account).set("resetTokenConsumed", changedAt);
      verify(account).set("passwordChanged", changedAt);
      verify(obDal).save(account);
      verify(obDal).flush();
      verify(obDal).commitAndClose();
    }

    @Test
    @DisplayName("changes password and rotates session token")
    void changesPasswordAndRotatesToken() {
      Date changedAt = new Date();

      EtendoGoJwtDalHelper.changePassword(account, "new-hash", "new-token", changedAt);

      verify(account).setPasswordHash("new-hash");
      verify(account).setSessionToken("new-token");
      verify(account).set("resetTokenHash", null);
      verify(account).set("resetTokenExpires", null);
      verify(account).set("resetTokenConsumed", changedAt);
      verify(account).set("passwordChanged", changedAt);
      verify(obDal).save(account);
      verify(obDal).flush();
      verify(obDal).commitAndClose();
    }
  }

  @Nested
  @DisplayName("findEnvironmentUsersByAccountEmail")
  class FindEnvironmentUsersByAccountEmail {

    @Mock private OBQuery<User> query;

    @Test
    @DisplayName("sets email and prefix parameters")
    void setsEmailAndPrefixParameters() {
      List<User> expected = Collections.emptyList();
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(query);
      when(query.list()).thenReturn(expected);

      List<User> result = EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail("user@test.com");

      assertEquals(expected, result);
      verify(query).setNamedParameter("accountEmail", "user@test.com");
      verify(query).setNamedParameter("accountPrefix", "user@test.com+%");
      verify(query).setFilterOnReadableClients(false);
      verify(query).setFilterOnReadableOrganization(false);
    }
  }

  @Nested
  @DisplayName("findNonStarOrganizations")
  class FindNonStarOrganizations {

    @Mock private OBQuery<Organization> query;

    @Test
    @DisplayName("filters by clientId and excludes star organization")
    void filtersByClientIdAndExcludesStar() {
      List<Organization> expected = Collections.emptyList();
      when(obDal.createQuery(eq(Organization.class), anyString())).thenReturn(query);
      when(query.list()).thenReturn(expected);

      List<Organization> result = EtendoGoJwtDalHelper.findNonStarOrganizations("CLIENT-1");

      assertEquals(expected, result);
      verify(query).setNamedParameter("clientId", "CLIENT-1");
      verify(query).setNamedParameter("starValue", "*");
      verify(query).setFilterOnReadableClients(false);
      verify(query).setFilterOnReadableOrganization(false);
    }
  }

  @Nested
  @DisplayName("buildEnvironmentJson")
  class BuildEnvironmentJson {

    @Mock private Client client;
    @Mock private Organization organization;
    @Mock private User environmentUser;

    @Test
    @DisplayName("builds JSON with all fields when org is non-null")
    void buildsJsonWithNonNullOrg() throws Exception {
      when(client.getId()).thenReturn("C-1");
      when(client.getName()).thenReturn("Test Client");
      when(organization.getId()).thenReturn("O-1");
      when(organization.getName()).thenReturn("Test Org");
      when(environmentUser.getId()).thenReturn("U-1");
      when(environmentUser.getUsername()).thenReturn("admin@test.com");
      when(environmentUser.getName()).thenReturn("Admin User");

      JSONObject result = EtendoGoJwtDalHelper.buildEnvironmentJson(client, organization, environmentUser);

      assertNotNull(result);
      assertEquals("C-1", result.getString("clientId"));
      assertEquals("Test Client", result.getString("clientName"));
      assertEquals("O-1", result.getString("orgId"));
      assertEquals("Test Org", result.getString("orgName"));
      assertEquals("U-1", result.getString("adminUserId"));
      assertEquals("admin@test.com", result.getString("adminUser"));
      assertEquals("Admin User", result.getString("adminUserName"));
    }

    @Test
    @DisplayName("puts JSON NULL for org fields when org is null")
    void putsNullForOrgFieldsWhenOrgIsNull() throws Exception {
      when(client.getId()).thenReturn("C-1");
      when(client.getName()).thenReturn("Test Client");
      when(environmentUser.getId()).thenReturn("U-1");
      when(environmentUser.getUsername()).thenReturn("admin@test.com");
      when(environmentUser.getName()).thenReturn("Admin User");

      JSONObject result = EtendoGoJwtDalHelper.buildEnvironmentJson(client, null, environmentUser);

      assertNotNull(result);
      assertEquals("C-1", result.getString("clientId"));
      assertEquals("Test Client", result.getString("clientName"));
      assertTrue(result.isNull("orgId"));
      assertTrue(result.isNull("orgName"));
      assertEquals("U-1", result.getString("adminUserId"));
      assertEquals("admin@test.com", result.getString("adminUser"));
      assertEquals("Admin User", result.getString("adminUserName"));
    }

    @Test
    @DisplayName("all eight fields are populated")
    void allEightFieldsPopulated() throws Exception {
      when(client.getId()).thenReturn("C-2");
      when(client.getName()).thenReturn("Client Two");
      when(organization.getId()).thenReturn("O-2");
      when(organization.getName()).thenReturn("Org Two");
      when(environmentUser.getId()).thenReturn("U-2");
      when(environmentUser.getUsername()).thenReturn("user@two.com");
      when(environmentUser.getName()).thenReturn("User Two");

      JSONObject result = EtendoGoJwtDalHelper.buildEnvironmentJson(client, organization, environmentUser);

      // Seven original fields plus the plan badge added by ETP-4686.
      assertEquals(8, result.length());
    }

    @Test
    @DisplayName("reports the free plan when the tenant carries no plan marker")
    void reportsFreePlanWithoutMarker() throws Exception {
      when(client.getId()).thenReturn("C-3");
      when(client.getName()).thenReturn("Client Three");
      when(environmentUser.getId()).thenReturn("U-3");
      when(environmentUser.getUsername()).thenReturn("user@three.com");
      when(environmentUser.getName()).thenReturn("User Three");

      JSONObject result = EtendoGoJwtDalHelper.buildEnvironmentJson(client, null, environmentUser);

      assertEquals(TenantPlanService.PLAN_FREE, result.getString("plan"));
    }
  }

  @Nested
  @DisplayName("findCurrencyByIsoCode")
  class FindCurrencyByIsoCode {

    @Mock private OBQuery<Currency> query;

    @Test
    @DisplayName("converts ISO code to upper case")
    void convertsIsoCodeToUpperCase() {
      Currency expected = mock(Currency.class);
      when(obDal.createQuery(eq(Currency.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(expected);

      Currency result = EtendoGoJwtDalHelper.findCurrencyByIsoCode("eur");

      assertEquals(expected, result);
      verify(query).setNamedParameter("currencyIso", "EUR");
      verify(query).setFilterOnReadableClients(false);
      verify(query).setFilterOnReadableOrganization(false);
    }

    @Test
    @DisplayName("handles already upper-case ISO code")
    void handlesAlreadyUpperCase() {
      when(obDal.createQuery(eq(Currency.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(null);

      Currency result = EtendoGoJwtDalHelper.findCurrencyByIsoCode("USD");

      assertNull(result);
      verify(query).setNamedParameter("currencyIso", "USD");
    }
  }

  @Nested
  @DisplayName("findClientAdminUserRole")
  class FindClientAdminUserRole {

    @Mock private OBQuery<UserRoles> query;

    @Test
    @DisplayName("returns first user role when found")
    void returnsFirstUserRoleWhenFound() {
      UserRoles expected = mock(UserRoles.class);
      when(obDal.createQuery(eq(UserRoles.class), anyString())).thenReturn(query);
      when(query.list()).thenReturn(List.of(expected));

      UserRoles result = EtendoGoJwtDalHelper.findClientAdminUserRole("CLIENT-1");

      assertEquals(expected, result);
      verify(query).setNamedParameter("clientId", "CLIENT-1");
      verify(query).setNamedParameter("systemUserId", "100");
      verify(query).setMaxResult(1);
      verify(query).setFilterOnReadableClients(false);
      verify(query).setFilterOnReadableOrganization(false);
    }

    @Test
    @DisplayName("returns null when no user roles found")
    void returnsNullWhenEmpty() {
      when(obDal.createQuery(eq(UserRoles.class), anyString())).thenReturn(query);
      when(query.list()).thenReturn(Collections.emptyList());

      UserRoles result = EtendoGoJwtDalHelper.findClientAdminUserRole("CLIENT-2");

      assertNull(result);
    }
  }

  @Nested
  @DisplayName("findFirstOrganization")
  class FindFirstOrganization {

    @Mock private OBQuery<Organization> query;

    @Test
    @DisplayName("returns first organization when found")
    void returnsFirstOrganizationWhenFound() {
      Organization expected = mock(Organization.class);
      when(obDal.createQuery(eq(Organization.class), anyString())).thenReturn(query);
      when(query.list()).thenReturn(List.of(expected));

      Organization result = EtendoGoJwtDalHelper.findFirstOrganization("CLIENT-1");

      assertEquals(expected, result);
      verify(query).setNamedParameter("clientId", "CLIENT-1");
      verify(query).setNamedParameter("starValue", "*");
      verify(query).setMaxResult(1);
      verify(query).setFilterOnReadableClients(false);
      verify(query).setFilterOnReadableOrganization(false);
    }

    @Test
    @DisplayName("returns null when no organizations found")
    void returnsNullWhenEmpty() {
      when(obDal.createQuery(eq(Organization.class), anyString())).thenReturn(query);
      when(query.list()).thenReturn(Collections.emptyList());

      Organization result = EtendoGoJwtDalHelper.findFirstOrganization("CLIENT-2");

      assertNull(result);
    }
  }

  @Nested
  @DisplayName("clientBelongsToAccountEmail (ETP-4428 resume ownership check)")
  class ClientBelongsToAccountEmail {

    @Mock private OBQuery<User> query;

    @Test
    @DisplayName("returns true when an owning user is found for the client")
    void returnsTrueWhenOwningUserFound() {
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(mock(User.class));

      assertTrue(EtendoGoJwtDalHelper.clientBelongsToAccountEmail("CLIENT-1", "user@example.com"));
      verify(query).setNamedParameter("clientId", "CLIENT-1");
      verify(query).setNamedParameter("accountEmail", "user@example.com");
      verify(query).setNamedParameter("accountPrefix", "user@example.com+%");
      verify(query).setMaxResult(1);
    }

    @Test
    @DisplayName("returns false when no owning user matches (name owned by another account)")
    void returnsFalseWhenNoOwningUser() {
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(null);

      assertFalse(EtendoGoJwtDalHelper.clientBelongsToAccountEmail("CLIENT-1", "user@example.com"));
    }

    @Test
    @DisplayName("escapes LIKE wildcards in the prefix so a crafted email cannot match another tenant (ETP-4428 HIGH)")
    void escapesLikeWildcardsInPrefix() {
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(null);

      // A crafted email carrying LIKE wildcards ('_' and '%') must be neutralised in the prefix
      // bind: each wildcard is escaped with '\' (paired with the query's `escape '\'` clause) so it
      // is matched literally and cannot broaden the LIKE into another account's usernames. The
      // exact-equality branch keeps the raw value; only the LIKE prefix is escaped.
      EtendoGoJwtDalHelper.clientBelongsToAccountEmail("CLIENT-1", "a_b%@x.com");

      verify(query).setNamedParameter("accountEmail", "a_b%@x.com");
      verify(query).setNamedParameter("accountPrefix", "a\\_b\\%@x.com+%");
    }
  }

  @Nested
  @DisplayName("email verification state (ETP-4798)")
  class EmailVerificationState {

    @Test
    @DisplayName("a confirmed address reads as verified and not pending")
    void confirmedAddress() {
      Account account = mock(Account.class);
      when(account.get(EtendoGoJwtDalHelper.PROPERTY_EMAIL_VERIFIED)).thenReturn(new Date());
      when(account.get(EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_HASH)).thenReturn("hash");

      assertTrue(EmailVerificationDalHelper.isEmailVerified(account));
      // Still holding the hash — the link stays replayable for idempotency — but nothing is owed.
      assertFalse(EmailVerificationDalHelper.isEmailVerificationPending(account));
    }

    @Test
    @DisplayName("an issued but unused token reads as pending")
    void issuedTokenIsPending() {
      Account account = mock(Account.class);
      when(account.get(EtendoGoJwtDalHelper.PROPERTY_EMAIL_VERIFIED)).thenReturn(null);
      when(account.get(EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_HASH)).thenReturn("hash");

      assertFalse(EmailVerificationDalHelper.isEmailVerified(account));
      assertTrue(EmailVerificationDalHelper.isEmailVerificationPending(account));
    }

    @Test
    @DisplayName("an account that predates the feature is neither verified nor pending")
    void legacyAccountIsNeverGated() {
      // The regression this guards: gating on "not verified" alone would lock every pre-ETP-4798
      // account out of creating an environment the moment this deploys.
      Account account = mock(Account.class);
      when(account.get(EtendoGoJwtDalHelper.PROPERTY_EMAIL_VERIFIED)).thenReturn(null);
      when(account.get(EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_HASH)).thenReturn(null);

      assertFalse(EmailVerificationDalHelper.isEmailVerified(account));
      assertFalse(EmailVerificationDalHelper.isEmailVerificationPending(account));
    }

    @Test
    @DisplayName("a null account is neither verified nor pending")
    void nullAccount() {
      assertFalse(EmailVerificationDalHelper.isEmailVerified(null));
      assertFalse(EmailVerificationDalHelper.isEmailVerificationPending(null));
    }

    @Test
    @DisplayName("storing a token writes the hash and expiry and commits")
    void storeEmailVerifyToken() {
      Account account = mock(Account.class);
      Date expiresAt = new Date();

      EmailVerificationDalHelper.storeEmailVerifyToken(account, "hash", expiresAt);

      verify(account).set(EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_HASH, "hash");
      verify(account).set(EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_EXPIRES, expiresAt);
      verify(obDal).save(account);
    }

    @Test
    @DisplayName("consuming marks the address verified without clearing the token or the session")
    void consumeEmailVerification() {
      Account account = mock(Account.class);
      Date verifiedAt = new Date();

      EmailVerificationDalHelper.consumeEmailVerification(account, verifiedAt);

      verify(account).set(EtendoGoJwtDalHelper.PROPERTY_EMAIL_VERIFIED, verifiedAt);
      // Keeping the hash is what makes a second click on the link answer 200 instead of "invalid".
      verify(account, never()).set(EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_HASH, null);
      // And the user stays signed in — they are usually mid-onboarding when they click.
      verify(account, never()).setSessionToken(any());
      verify(obDal).save(account);
    }

    @Test
    @DisplayName("the lookup only accepts an unexpired token on an active account")
    void findAccountByVerifyTokenHash() {
      @SuppressWarnings("unchecked")
      OBQuery<Account> query = mock(OBQuery.class);
      Account expected = mock(Account.class);
      Date now = new Date();
      when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(expected);

      Account result = EmailVerificationDalHelper.findAccountByVerifyTokenHash("hash", now);

      assertEquals(expected, result);
      verify(query).setNamedParameter("verifyTokenHash", "hash");
      verify(query).setNamedParameter("now", now);
      verify(query).setFilterOnReadableClients(false);
      verify(query).setFilterOnReadableOrganization(false);
    }
  }

  @Nested
  @DisplayName("findActiveAccountByBearerToken")
  class FindActiveAccountByBearerToken {

    private static final String ENVIRONMENT_JWT = "environment-jwt";
    private static final String USER_ID = "USER-1";
    private static final String CLIENT_ID = "CLIENT-1";
    private static final String ACCOUNT_EMAIL = "owner@example.com";

    @Mock private OBDal readOnlyDal;

    /**
     * Builds the environment's {@code AD_User} exactly as onboarding leaves it: {@code email} is
     * null, because {@code InitialSetupUtility.insertUser} never sets it, so the account identity
     * lives entirely in {@code username}.
     */
    private User environmentUser(String username) {
      Client client = mock(Client.class);
      when(client.getId()).thenReturn(CLIENT_ID);
      User user = mock(User.class);
      when(user.isActive()).thenReturn(Boolean.TRUE);
      when(user.getEmail()).thenReturn(null);
      when(user.getUsername()).thenReturn(username);
      when(user.getClient()).thenReturn(client);
      return user;
    }

    /**
     * Stubs the account lookups the resolver performs against {@code dal}: the session-token query
     * always misses (the caller presents an environment JWT, not an account session token), and the
     * by-email query yields {@code account} only for {@code registeredEmail}. Any other email
     * resolves to null, so a passing assertion proves which email the production code actually
     * looked up rather than merely that it looked something up.
     */
    private void stubAccountLookups(OBDal dal, String registeredEmail, Account account) {
      @SuppressWarnings("unchecked")
      OBQuery<Account> bySessionToken = mock(OBQuery.class);
      when(bySessionToken.uniqueResult()).thenReturn(null);

      @SuppressWarnings("unchecked")
      OBQuery<Account> byEmail = mock(OBQuery.class);
      // The helper binds the email before reading the result, so the stub can answer based on what
      // was actually requested — including a second lookup with a different value.
      String[] requestedEmail = new String[1];
      when(byEmail.setNamedParameter(anyString(), any())).thenAnswer(call -> {
        if ("email".equals(call.getArgument(0))) {
          requestedEmail[0] = String.valueOf((Object) call.getArgument(1));
        }
        return byEmail;
      });
      when(byEmail.uniqueResult())
          .thenAnswer(call -> registeredEmail.equalsIgnoreCase(requestedEmail[0]) ? account : null);

      when(dal.createQuery(eq(Account.class), anyString())).thenAnswer(invocation ->
          String.valueOf((Object) invocation.getArgument(1)).contains("sessionToken")
              ? bySessionToken : byEmail);
    }

    /** Stubs the tenant-isolation check: whether the JWT's client is owned by the account. */
    private void stubClientOwnership(boolean owned) {
      @SuppressWarnings("unchecked")
      OBQuery<User> ownershipQuery = mock(OBQuery.class);
      when(ownershipQuery.uniqueResult()).thenReturn(owned ? mock(User.class) : null);
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(ownershipQuery);
    }

    /**
     * Drives the helper with an environment JWT issued for {@code username}, against an account
     * registered under {@code registeredEmail}.
     */
    private Account resolve(String username, String registeredEmail, Account account,
        boolean clientOwned) {
      stubAccountLookups(obDal, registeredEmail, account);
      stubAccountLookups(readOnlyDal, registeredEmail, account);
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);
      // Built before the stubbing call: environmentUser() stubs its own mocks, and Mockito forbids
      // that while an outer when(...) is still open.
      User user = environmentUser(username);
      when(obDal.get(User.class, USER_ID)).thenReturn(user);
      when(account.getEmail()).thenReturn(registeredEmail);
      stubClientOwnership(clientOwned);

      DecodedJWT jwt = mock(DecodedJWT.class);
      Claim userClaim = mock(Claim.class);
      when(userClaim.asString()).thenReturn(USER_ID);
      when(jwt.getClaim("user")).thenReturn(userClaim);

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
        swsMock.when(() -> SecureWebServicesUtils.decodeToken(ENVIRONMENT_JWT)).thenReturn(jwt);
        return EtendoGoJwtDalHelper.findActiveAccountByBearerToken(ENVIRONMENT_JWT);
      }
    }

    @Test
    @DisplayName("resolves the account for a first environment, whose username is the plain email")
    void resolvesFirstEnvironment() {
      Account expected = mock(Account.class);

      assertEquals(expected, resolve(ACCOUNT_EMAIL, ACCOUNT_EMAIL, expected, true));
    }

    @Test
    @DisplayName("resolves the account for a later environment, whose username carries the client suffix")
    void resolvesSuffixedEnvironment() {
      // From the second tenant onwards buildClientUsername names the environment user
      // "<accountEmail>+<clientName>". An exact-match-only lookup misses it and the caller
      // answers 401 for a perfectly valid, freshly issued token.
      Account expected = mock(Account.class);

      assertEquals(expected, resolve(ACCOUNT_EMAIL + "+acmeltd", ACCOUNT_EMAIL, expected, true));
    }

    @Test
    @DisplayName("keeps plus-addressed account emails intact by splitting on the last '+'")
    void resolvesSuffixedEnvironmentForPlusAddressedEmail() {
      // "owner+tag@example.com+acmeltd" must resolve to "owner+tag@example.com". Splitting on the
      // FIRST '+' would corrupt exactly the accounts that use plus-addressing.
      Account expected = mock(Account.class);
      String plusAddressed = "owner+tag@example.com";

      assertEquals(expected,
          resolve(plusAddressed + "+acmeltd", plusAddressed, expected, true));
    }

    @Test
    @DisplayName("refuses a suffixed username whose client belongs to another account")
    void refusesWhenClientBelongsToAnotherAccount() {
      // Stripping the suffix must not become a way past tenant isolation: the JWT's client still
      // has to be owned by the resolved account.
      assertNull(resolve(ACCOUNT_EMAIL + "+acmeltd", ACCOUNT_EMAIL, mock(Account.class), false));
    }

    @Test
    @DisplayName("refuses a username that maps to no account")
    void refusesUnknownAccount() {
      assertNull(resolve("stranger@example.com+acmeltd", ACCOUNT_EMAIL, mock(Account.class), true));
    }

    @Test
    @DisplayName("refuses an inactive environment user")
    void refusesInactiveUser() {
      Account account = mock(Account.class);
      stubAccountLookups(obDal, ACCOUNT_EMAIL, account);
      User inactive = mock(User.class);
      when(inactive.isActive()).thenReturn(Boolean.FALSE);
      when(obDal.get(User.class, USER_ID)).thenReturn(inactive);

      DecodedJWT jwt = mock(DecodedJWT.class);
      Claim userClaim = mock(Claim.class);
      when(userClaim.asString()).thenReturn(USER_ID);
      when(jwt.getClaim("user")).thenReturn(userClaim);

      try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
        swsMock.when(() -> SecureWebServicesUtils.decodeToken(ENVIRONMENT_JWT)).thenReturn(jwt);
        assertNull(EtendoGoJwtDalHelper.findActiveAccountByBearerToken(ENVIRONMENT_JWT));
      }
    }

    @Test
    @DisplayName("prefers the account session token and never decodes it as a JWT")
    void prefersAccountSessionToken() {
      Account expected = mock(Account.class);
      @SuppressWarnings("unchecked")
      OBQuery<Account> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(expected);

      assertEquals(expected, EtendoGoJwtDalHelper.findActiveAccountByBearerToken("account-session"));
    }

    @Test
    @DisplayName("returns null for a blank token without touching the JWT decoder")
    void returnsNullForBlankToken() {
      @SuppressWarnings("unchecked")
      OBQuery<Account> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(null);

      assertNull(EtendoGoJwtDalHelper.findActiveAccountByBearerToken("   "));
    }
  }
}
