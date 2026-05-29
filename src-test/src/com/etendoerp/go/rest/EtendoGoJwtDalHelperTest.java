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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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

import com.etendoerp.go.schemaforge.data.Account;

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
    @DisplayName("all seven fields are populated")
    void allSevenFieldsPopulated() throws Exception {
      when(client.getId()).thenReturn("C-2");
      when(client.getName()).thenReturn("Client Two");
      when(organization.getId()).thenReturn("O-2");
      when(organization.getName()).thenReturn("Org Two");
      when(environmentUser.getId()).thenReturn("U-2");
      when(environmentUser.getUsername()).thenReturn("user@two.com");
      when(environmentUser.getName()).thenReturn("User Two");

      JSONObject result = EtendoGoJwtDalHelper.buildEnvironmentJson(client, organization, environmentUser);

      assertEquals(7, result.length());
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
}
