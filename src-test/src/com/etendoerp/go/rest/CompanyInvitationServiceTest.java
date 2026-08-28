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

import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.Invitation;

class CompanyInvitationServiceTest {

  @Test
  @DisplayName("token hashing produces reproducible sha256 base64 string")
  void testHashToken() {
    String token = "test-token-12345";
    String hash1 = CompanyInvitationService.hashToken(token);
    String hash2 = CompanyInvitationService.hashToken(token);
    assertNotNull(hash1);
    assertEquals(hash1, hash2);
  }

  @Test
  @DisplayName("maskEmail masks local part correctly")
  void testMaskEmail() {
    assertEquals("j***e@example.com", CompanyInvitationService.maskEmail("jane@example.com"));
    assertEquals("a***b@test.com", CompanyInvitationService.maskEmail("ab@test.com"));
    assertEquals("a***@test.com", CompanyInvitationService.maskEmail("a@test.com"));
    assertEquals("invalid", CompanyInvitationService.maskEmail("invalid"));
  }

  @Test
  @DisplayName("createInvitation rejects missing or invalid email")
  void testCreateInvitationValidation() throws Exception {
    CompanyInvitationService service = new CompanyInvitationService();
    Account account = mock(Account.class);
    JSONObject response1 = service.createInvitation(account, "", "https://app.test", "en_US");
    assertTrue(response1.optBoolean("error"));
    assertEquals("MISSING_EMAIL", response1.optString("code"));

    JSONObject response2 = service.createInvitation(account, "not-an-email", "https://app.test", "en_US");
    assertTrue(response2.optBoolean("error"));
    assertEquals("INVALID_EMAIL_FORMAT", response2.optString("code"));
  }

  @Test
  @DisplayName("createInvitation rejects missing auth token")
  void testCreateInvitationUnauthenticated() throws Exception {
    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject response = service.createInvitation(null, "user@example.com", "https://app.test", "en_US");
    assertTrue(response.optBoolean("error"));
    assertEquals("UNAUTHORIZED", response.optString("code"));
  }

  /**
   * Companion to the {@link CompanyInvitationDalHelper#findInviterHomeUser} unit coverage
   * (ETP-4999 fix, Mystery #1): proves the caller side of the contract — when the platform
   * account has no home {@code AD_User} at all (the DAL helper's {@code null} branch, e.g. its
   * very first-ever {@code AD_User} was created by someone else inviting it before the account
   * ever onboarded its own company), {@link CompanyInvitationService#resolveInviter} must resolve
   * to a clean 403 rather than propagate a {@code NullPointerException} out of {@code
   * createInvitationForInviter}'s {@code inviter == null} guard.
   */
  @Test
  @DisplayName("createInvitation resolves a clean 403 FORBIDDEN, not an NPE, when the account has "
      + "no home AD_User (findInviterHomeUser returns null — ETP-4999)")
  void testCreateInvitationHandlesUnresolvedInviterGracefully() throws Exception {
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("ghost@example.com");

    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findInviterHomeUser("ghost@example.com"))
          .thenReturn(null);

      CompanyInvitationService service = new CompanyInvitationService();
      JSONObject response = service.createInvitation(account, "invitee@example.com",
          "https://app.test", "en_US");

      assertTrue(response.optBoolean("error"));
      assertEquals("FORBIDDEN", response.optString("code"));
    }
  }

  @Test
  @DisplayName("account invitation list requires authentication")
  void testListInvitationsRequiresAuthentication() throws Exception {
    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject response = service.listInvitationsForAccount(null);

    assertTrue(response.optBoolean("error"));
    assertEquals("AUTHENTICATION_REQUIRED", response.optString("code"));
  }

  @Test
  void testListInvitationsRejectsAccountWithoutEmail() throws Exception {
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn(" ");

    JSONObject response = new CompanyInvitationService().listInvitationsForAccount(account);

    assertTrue(response.optBoolean("error"));
    assertEquals("AUTHENTICATION_REQUIRED", response.optString("code"));
  }

  @Test
  @DisplayName("account invitation list is email-scoped and includes invitations from multiple clients")
  void testListInvitationsForAccountIsEmailScopedAcrossClients() throws Exception {
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("Member@Example.com");

    Client firstClient = mock(Client.class);
    when(firstClient.getName()).thenReturn("First Company");
    Client secondClient = mock(Client.class);
    when(secondClient.getName()).thenReturn("Second Company");

    Invitation firstInvitation = mock(Invitation.class);
    when(firstInvitation.getId()).thenReturn("invitation-1");
    when(firstInvitation.getEmail()).thenReturn("member@example.com");
    when(firstInvitation.getStatus()).thenReturn("SENT");
    when(firstInvitation.getClient()).thenReturn(firstClient);

    Invitation secondInvitation = mock(Invitation.class);
    when(secondInvitation.getId()).thenReturn("invitation-2");
    when(secondInvitation.getEmail()).thenReturn("member@example.com");
    when(secondInvitation.getStatus()).thenReturn("PENDING");
    when(secondInvitation.getClient()).thenReturn(secondClient);

    OBDal dal = mock(OBDal.class);
    OBQuery<Invitation> query = mock(OBQuery.class);
    when(dal.createQuery(eq(Invitation.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(firstInvitation, secondInvitation));

    try (MockedStatic<OBDal> obDal = org.mockito.Mockito.mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      JSONObject response = new CompanyInvitationService().listInvitationsForAccount(account);

      assertFalse(response.optBoolean("error"));
      assertEquals(2, response.getJSONArray("invitations").length());
      assertEquals("First Company",
          response.getJSONArray("invitations").getJSONObject(0).getString("clientName"));
      assertEquals("Second Company",
          response.getJSONArray("invitations").getJSONObject(1).getString("clientName"));
      verify(dal).createQuery(eq(Invitation.class),
          org.mockito.ArgumentMatchers.contains("lower(i.email) = :email"));
      verify(query).setNamedParameter("email", "member@example.com");
      verify(query).setFilterOnReadableClients(false);
      verify(query).setFilterOnReadableOrganization(false);
    }
  }

  @Test
  @DisplayName("resolveInvitation rejects blank token")
  void testResolveInvitationBlank() throws Exception {
    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject response = service.resolveInvitation("   ");
    assertTrue(response.optBoolean("error"));
    assertEquals("MISSING_TOKEN", response.optString("code"));
  }

  @Test
  @DisplayName("acceptExistingAccount rejects blank token")
  void testAcceptBlankToken() throws Exception {
    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject response = service.acceptExistingAccount("", "");
    assertTrue(response.optBoolean("error"));
    assertEquals("MISSING_TOKEN", response.optString("code"));
  }

  @Test
  @DisplayName("existing-account acceptance requires an authenticated session")
  void testAcceptRequiresAuthenticatedAccount() throws Exception {
    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject response = service.acceptExistingAccount("invitation-token", "");
    assertTrue(response.optBoolean("error"));
    assertEquals("AUTHENTICATION_REQUIRED", response.optString("code"));
  }

  @Test
  @DisplayName("registerAndAccept rejects weak password and missing name")
  void testRegisterAndAcceptValidation() throws Exception {
    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject response1 = service.registerAndAccept("token", "", "StrongPass1!");
    assertTrue(response1.optBoolean("error"));
    assertEquals("MISSING_NAME", response1.optString("code"));

    JSONObject response2 = service.registerAndAccept("token", "Name", "weak");
    assertTrue(response2.optBoolean("error"));
    assertEquals("WEAK_PASSWORD", response2.optString("code"));
  }

  // ─── registerAndAcceptInAdminMode (ETP-4830 lazy-init reordering fix) ────────

  /**
   * Regression test for the reported {@code LazyInitializationException}: before the fix,
   * {@code registerAndAcceptInAdminMode} created/updated the {@code etgo_account} FIRST and only
   * checked {@code invitation.getUser()} afterward. For a brand-new account, that account
   * creation calls {@link EtendoGoJwtDalHelper#createAccount} which commits and CLOSES the
   * Hibernate session, so the later {@code invitation.getUser()} touched an orphaned lazy proxy.
   * The fix moves the user/role validation before any account mutation, so an invalid invitation
   * user must now be rejected WITHOUT ever calling {@code findActiveAccountByEmail} or
   * {@code createAccount}.
   */
  @Test
  @DisplayName("registerAndAccept rejects an inactive invitation user before touching the account "
      + "(ETP-4830: validation must run before the session-closing createAccount call)")
  void testRegisterAndAcceptRejectsInvalidUserBeforeCreatingAccount() throws Exception {
    Invitation invitation = mock(Invitation.class);
    when(invitation.getStatus()).thenReturn("SENT");
    when(invitation.getExpiresAt()).thenReturn(new Date(System.currentTimeMillis() + 86_400_000L));
    Client client = mock(Client.class);
    when(client.getName()).thenReturn("Acme");
    when(invitation.getClient()).thenReturn(client);
    when(invitation.getEmail()).thenReturn("invitee@example.com");
    Organization org = mock(Organization.class);
    when(invitation.getOrganization()).thenReturn(org);

    User user = mock(User.class);
    when(user.isActive()).thenReturn(false); // inactive -> must be rejected
    when(invitation.getUser()).thenReturn(user);

    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
            mockStatic(CompanyInvitationDalHelper.class);
        MockedStatic<EtendoGoJwtDalHelper> jwtHelperMock =
            mockStatic(EtendoGoJwtDalHelper.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findInvitationByTokenHash(anyString()))
          .thenReturn(invitation);

      CompanyInvitationService service = new CompanyInvitationService();
      JSONObject response = service.registerAndAccept("token", "Jane Doe", "StrongPass1!");

      assertTrue(response.optBoolean("error"));
      assertEquals("INVITATION_USER_CONFIGURATION_INVALID", response.optString("code"));
      // The whole point of the reordering: an invalid invitation user is rejected BEFORE the
      // account is ever looked up or created.
      jwtHelperMock.verify(
          () -> EtendoGoJwtDalHelper.findActiveAccountByEmail(anyString()), never());
      jwtHelperMock.verify(() -> EtendoGoJwtDalHelper.createAccount(anyString(), anyString(),
          anyString(), anyString()), never());
      verify(invitation, never()).setEtgoAccount(any());
    }
  }

  /**
   * Happy-path companion to the test above: once the invitation user IS valid, the new-account
   * branch (the one that calls {@link EtendoGoJwtDalHelper#createAccount}, closing the session)
   * must still run to completion and produce a success response — proving the reordering didn't
   * just move the bug, it actually resolved it for the real "create a brand-new account" path
   * that triggered the original stack trace.
   */
  @Test
  @DisplayName("registerAndAccept creates a new account end-to-end once the invitation user is valid")
  void testRegisterAndAcceptCreatesAccountAfterValidatingUser() throws Exception {
    Invitation invitation = mock(Invitation.class);
    when(invitation.getStatus()).thenReturn("SENT");
    when(invitation.getExpiresAt()).thenReturn(new Date(System.currentTimeMillis() + 86_400_000L));
    Client client = mock(Client.class);
    when(client.getName()).thenReturn("Acme");
    when(invitation.getClient()).thenReturn(client);
    when(invitation.getEmail()).thenReturn("invitee@example.com");
    Organization org = mock(Organization.class);
    when(invitation.getOrganization()).thenReturn(org);

    User user = mock(User.class);
    when(user.isActive()).thenReturn(true);
    when(invitation.getUser()).thenReturn(user);

    Account createdAccount = mock(Account.class);
    when(createdAccount.getId()).thenReturn("account-1");
    when(createdAccount.getEmail()).thenReturn("invitee@example.com");
    when(createdAccount.getName()).thenReturn("Jane Doe");

    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
            mockStatic(CompanyInvitationDalHelper.class);
        MockedStatic<EtendoGoJwtDalHelper> jwtHelperMock =
            mockStatic(EtendoGoJwtDalHelper.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findInvitationByTokenHash(anyString()))
          .thenReturn(invitation);
      jwtHelperMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("invitee@example.com"))
          .thenReturn(null);
      jwtHelperMock.when(() -> EtendoGoJwtDalHelper.createAccount(eq("invitee@example.com"),
          anyString(), eq("Jane Doe"), anyString())).thenReturn(createdAccount);

      CompanyInvitationService service = new CompanyInvitationService();
      JSONObject response = service.registerAndAccept("token", "Jane Doe", "StrongPass1!");

      assertFalse(response.optBoolean("error"));
      assertEquals("account-1", response.getJSONObject("account").getString("id"));
      assertEquals("Acme", response.getString("clientName"));
      assertNotNull(response.getString("token"));

      verify(invitation).getUser();
      // ETP-4830: accept must succeed for a roleless-but-active invitation user — the
      // active-role-for-organization check is no longer part of the accept gate.
      dalHelperMock.verify(
          () -> CompanyInvitationDalHelper.hasActiveRoleForOrganization(any(), any()), never());
      jwtHelperMock.verify(() -> EtendoGoJwtDalHelper.createAccount(eq("invitee@example.com"),
          anyString(), eq("Jane Doe"), anyString()));
      verify(invitation).setEtgoAccount(createdAccount);
      verify(invitation).setStatus("ACCEPTED");
    }
  }

  // ─── acceptExistingAccountInAdminMode (ETP-4830: role check removed from accept) ─────

  /**
   * Confirms the fix for the design conflict found in manual testing: accepting an invitation
   * must succeed for a roleless-but-active invitation user. An admin-created {@code AD_User} has
   * zero roles at invite time by construction (role assignment happens later, independently, via
   * the "Roles del usuario" tab), so gating accept on a pre-existing role made every such
   * invitation permanently un-acceptable with a 409 {@code INVITATION_USER_CONFIGURATION_INVALID}.
   */
  @Test
  @DisplayName("acceptExistingAccount succeeds for a roleless-but-active invitation user")
  void testAcceptExistingAccountSucceedsForRolelessActiveUser() throws Exception {
    Invitation invitation = mock(Invitation.class);
    when(invitation.getStatus()).thenReturn("SENT");
    when(invitation.getExpiresAt()).thenReturn(new Date(System.currentTimeMillis() + 86_400_000L));
    Client client = mock(Client.class);
    when(client.getName()).thenReturn("Acme");
    when(invitation.getClient()).thenReturn(client);
    when(invitation.getEmail()).thenReturn("invitee@example.com");
    Organization org = mock(Organization.class);
    when(invitation.getOrganization()).thenReturn(org);

    User user = mock(User.class);
    when(user.isActive()).thenReturn(true); // active but no role stubbed anywhere
    when(invitation.getUser()).thenReturn(user);

    Account platformAccount = mock(Account.class);
    when(platformAccount.getId()).thenReturn("account-1");
    when(platformAccount.getEmail()).thenReturn("invitee@example.com");

    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
            mockStatic(CompanyInvitationDalHelper.class);
        MockedStatic<EtendoGoJwtDalHelper> jwtHelperMock =
            mockStatic(EtendoGoJwtDalHelper.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findInvitationByTokenHash(anyString()))
          .thenReturn(invitation);
      jwtHelperMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("invitee@example.com"))
          .thenReturn(platformAccount);
      jwtHelperMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("session-token"))
          .thenReturn(platformAccount);

      CompanyInvitationService service = new CompanyInvitationService();
      JSONObject response = service.acceptExistingAccount("invitation-token", "session-token");

      assertFalse(response.optBoolean("error"));
      assertEquals("Acme", response.getString("clientName"));
      dalHelperMock.verify(
          () -> CompanyInvitationDalHelper.hasActiveRoleForOrganization(any(), any()), never());
      verify(invitation).setEtgoAccount(platformAccount);
      verify(invitation).setStatus("ACCEPTED");
    }
  }

  @Test
  @DisplayName("acceptExistingAccount still rejects an inactive invitation user")
  void testAcceptExistingAccountRejectsInactiveUser() throws Exception {
    Invitation invitation = mock(Invitation.class);
    when(invitation.getStatus()).thenReturn("SENT");
    when(invitation.getExpiresAt()).thenReturn(new Date(System.currentTimeMillis() + 86_400_000L));
    Client client = mock(Client.class);
    when(client.getName()).thenReturn("Acme");
    when(invitation.getClient()).thenReturn(client);
    when(invitation.getEmail()).thenReturn("invitee@example.com");
    Organization org = mock(Organization.class);
    when(invitation.getOrganization()).thenReturn(org);

    User user = mock(User.class);
    when(user.isActive()).thenReturn(false); // inactive -> must still be rejected
    when(invitation.getUser()).thenReturn(user);

    Account platformAccount = mock(Account.class);
    when(platformAccount.getId()).thenReturn("account-1");
    when(platformAccount.getEmail()).thenReturn("invitee@example.com");

    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
            mockStatic(CompanyInvitationDalHelper.class);
        MockedStatic<EtendoGoJwtDalHelper> jwtHelperMock =
            mockStatic(EtendoGoJwtDalHelper.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findInvitationByTokenHash(anyString()))
          .thenReturn(invitation);
      jwtHelperMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("invitee@example.com"))
          .thenReturn(platformAccount);
      jwtHelperMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("session-token"))
          .thenReturn(platformAccount);

      CompanyInvitationService service = new CompanyInvitationService();
      JSONObject response = service.acceptExistingAccount("invitation-token", "session-token");

      assertTrue(response.optBoolean("error"));
      assertEquals("INVITATION_USER_CONFIGURATION_INVALID", response.optString("code"));
      verify(invitation, never()).setEtgoAccount(any());
    }
  }

  @Test
  @DisplayName("acceptExistingAccount rejects a signed-in account that does not match the invitation email")
  void testAcceptExistingAccountRejectsMismatchedAccount() throws Exception {
    Invitation invitation = mock(Invitation.class);
    when(invitation.getStatus()).thenReturn("SENT");
    when(invitation.getExpiresAt()).thenReturn(new Date(System.currentTimeMillis() + 86_400_000L));
    when(invitation.getClient()).thenReturn(mock(Client.class));
    when(invitation.getEmail()).thenReturn("invitee@example.com");

    Account otherAccount = mock(Account.class);
    when(otherAccount.getEmail()).thenReturn("someone-else@example.com");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
            mockStatic(CompanyInvitationDalHelper.class);
        MockedStatic<EtendoGoJwtDalHelper> jwtHelperMock =
            mockStatic(EtendoGoJwtDalHelper.class)) {
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findInvitationByTokenHash(anyString()))
          .thenReturn(invitation);
      jwtHelperMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("session-token"))
          .thenReturn(otherAccount);

      CompanyInvitationService service = new CompanyInvitationService();
      JSONObject response = service.acceptExistingAccount("invitation-token", "session-token");

      assertTrue(response.optBoolean("error"));
      assertEquals("INVITATION_ACCOUNT_MISMATCH", response.optString("code"));
      verify(invitation, never()).getUser();
    }
  }

  // ─── createInvitationForNewlyCreatedUser (ETP-4830) ──────────────────────────

  @Test
  @DisplayName("createInvitationForNewlyCreatedUser rejects a blank email")
  void testCreateInvitationForNewlyCreatedUserRejectsBlankEmail() throws Exception {
    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject response = service.createInvitationForNewlyCreatedUser(mock(OBContext.class), "  ",
        "https://app.test", "en_US");
    assertTrue(response.optBoolean("error"));
    assertEquals("MISSING_EMAIL", response.optString("code"));
  }

  @Test
  @DisplayName("createInvitationForNewlyCreatedUser rejects a null OBContext as an unprivileged inviter")
  void testCreateInvitationForNewlyCreatedUserRejectsNullContext() throws Exception {
    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject response = service.createInvitationForNewlyCreatedUser(null,
        "new.user@example.com", "https://app.test", "en_US");
    assertTrue(response.optBoolean("error"));
    assertEquals("FORBIDDEN", response.optString("code"));
  }

  @Test
  @DisplayName("createInvitationForNewlyCreatedUser rejects the System client (id '0')")
  void testCreateInvitationForNewlyCreatedUserRejectsSystemClient() throws Exception {
    Client systemClient = mock(Client.class);
    when(systemClient.getId()).thenReturn("0");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(systemClient);

    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject response = service.createInvitationForNewlyCreatedUser(obContext,
        "new.user@example.com", "https://app.test", "en_US");
    assertTrue(response.optBoolean("error"));
    assertEquals("FORBIDDEN", response.optString("code"));
  }

  @Test
  @DisplayName("createInvitationForNewlyCreatedUser resolves the inviter from OBContext and "
      + "skips the active-role check that createInvitation enforces")
  void testCreateInvitationForNewlyCreatedUserSkipsRoleCheck() throws Exception {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    Organization org = mock(Organization.class);
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getCurrentOrganization()).thenReturn(org);

    User invitedUser = mock(User.class);
    Invitation savedInvitation = mock(Invitation.class);
    when(savedInvitation.getEmail()).thenReturn("new.user@example.com");
    when(savedInvitation.getStatus()).thenReturn("SENT");

    TransactionalAuthEmailSender sender = mock(TransactionalAuthEmailSender.class);
    when(sender.sendCompanyInvitation(any(), any(), any())).thenReturn(true);

    OBDal dal = mock(OBDal.class);
    OBProvider provider = mock(OBProvider.class);
    when(provider.get(Invitation.class)).thenReturn(savedInvitation);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class);
        MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
            mockStatic(CompanyInvitationDalHelper.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findUserForClientEmail(client,
          "new.user@example.com")).thenReturn(invitedUser);
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findOpenInvitation("client-1",
          "new.user@example.com")).thenReturn(null);

      CompanyInvitationService service = new CompanyInvitationService(sender);
      JSONObject response = service.createInvitationForNewlyCreatedUser(obContext,
          "New.User@Example.com", "https://app.test", "en_US");

      assertFalse(response.optBoolean("error"));
      assertEquals("SENT", response.getJSONObject("invitation").getString("status"));
      // The whole point of this entry point (ETP-4830): a freshly admin-created AD_User has no
      // role yet, so the active-role-for-organization check must never even run.
      dalHelperMock.verify(() -> CompanyInvitationDalHelper.hasActiveRoleForOrganization(any(),
          any()), never());
    }
  }

  @Test
  @DisplayName("createInvitationForNewlyCreatedUser still 400s when the created user cannot be "
      + "resolved back by email")
  void testCreateInvitationForNewlyCreatedUserRequiresResolvableUser() throws Exception {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getCurrentOrganization()).thenReturn(mock(Organization.class));

    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findUserForClientEmail(client,
          "ghost@example.com")).thenReturn(null);

      CompanyInvitationService service = new CompanyInvitationService();
      JSONObject response = service.createInvitationForNewlyCreatedUser(obContext,
          "ghost@example.com", "https://app.test", "en_US");

      assertTrue(response.optBoolean("error"));
      assertEquals("INVITED_USER_NOT_FOUND", response.optString("code"));
    }
  }

  // ─── findLatestInvitationStatus (ETP-4830) ────────────────────────────────────

  @Test
  @DisplayName("findLatestInvitationStatus returns null without querying for a blank clientId or email")
  void testFindLatestInvitationStatusRejectsBlankArguments() {
    assertNull(CompanyInvitationService.findLatestInvitationStatus(null, "user@example.com"));
    assertNull(CompanyInvitationService.findLatestInvitationStatus("client-1", " "));
  }

  @Test
  @DisplayName("findLatestInvitationStatus returns the latest invitation's status")
  void testFindLatestInvitationStatusReturnsStatus() {
    Invitation invitation = mock(Invitation.class);
    when(invitation.getStatus()).thenReturn("ACCEPTED");

    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findLatestInvitation("client-1",
          "user@example.com")).thenReturn(invitation);

      assertEquals("ACCEPTED",
          CompanyInvitationService.findLatestInvitationStatus("client-1", "user@example.com"));
    }
  }

  @Test
  @DisplayName("findLatestInvitationStatus returns null when no invitation was ever sent")
  void testFindLatestInvitationStatusReturnsNullWhenNoneExists() {
    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findLatestInvitation("client-1",
          "user@example.com")).thenReturn(null);

      assertNull(CompanyInvitationService.findLatestInvitationStatus("client-1",
          "user@example.com"));
    }
  }

  // ─── findLatestInvitationStatus live-computed EXPIRED (ETP-4830) ──────────────
  //
  // Nothing in the codebase ever writes STATUS_EXPIRED to the DB column (verified via grep
  // before this fix) — a PENDING/SENT invitation whose deadline has passed stayed PENDING/SENT
  // forever, so the grid/detail pill kept showing "pending" indefinitely for a dead invite even
  // though accept-time already correctly rejected it via isClosedInvitation's own expiresAt
  // check. These tests lock in the read-time fallback that closes that gap.

  private static Invitation invitationWith(String status, Date expiresAt) {
    Invitation invitation = mock(Invitation.class);
    when(invitation.getStatus()).thenReturn(status);
    when(invitation.getExpiresAt()).thenReturn(expiresAt);
    return invitation;
  }

  @Test
  @DisplayName("findLatestInvitationStatus reports EXPIRED for a PENDING invitation past its deadline")
  void testFindLatestInvitationStatusComputesExpiredForPastPending() {
    Invitation invitation = invitationWith("PENDING", new Date(System.currentTimeMillis() - 1000));

    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findLatestInvitation("client-1",
          "user@example.com")).thenReturn(invitation);

      assertEquals("EXPIRED",
          CompanyInvitationService.findLatestInvitationStatus("client-1", "user@example.com"));
    }
  }

  @Test
  @DisplayName("findLatestInvitationStatus reports EXPIRED for a SENT invitation past its deadline")
  void testFindLatestInvitationStatusComputesExpiredForPastSent() {
    Invitation invitation = invitationWith("SENT", new Date(System.currentTimeMillis() - 1000));

    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findLatestInvitation("client-1",
          "user@example.com")).thenReturn(invitation);

      assertEquals("EXPIRED",
          CompanyInvitationService.findLatestInvitationStatus("client-1", "user@example.com"));
    }
  }

  @Test
  @DisplayName("findLatestInvitationStatus keeps PENDING when the deadline has not passed yet")
  void testFindLatestInvitationStatusKeepsPendingBeforeDeadline() {
    Invitation invitation = invitationWith("PENDING", new Date(System.currentTimeMillis() + 60000));

    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findLatestInvitation("client-1",
          "user@example.com")).thenReturn(invitation);

      assertEquals("PENDING",
          CompanyInvitationService.findLatestInvitationStatus("client-1", "user@example.com"));
    }
  }

  @Test
  @DisplayName("findLatestInvitationStatus keeps PENDING when expiresAt is null")
  void testFindLatestInvitationStatusKeepsPendingWhenExpiresAtNull() {
    Invitation invitation = invitationWith("PENDING", null);

    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findLatestInvitation("client-1",
          "user@example.com")).thenReturn(invitation);

      assertEquals("PENDING",
          CompanyInvitationService.findLatestInvitationStatus("client-1", "user@example.com"));
    }
  }

  @Test
  @DisplayName("findLatestInvitationStatus does not override a REVOKED status past its deadline")
  void testFindLatestInvitationStatusDoesNotOverrideRevoked() {
    Invitation invitation = invitationWith("REVOKED", new Date(System.currentTimeMillis() - 1000));

    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findLatestInvitation("client-1",
          "user@example.com")).thenReturn(invitation);

      assertEquals("REVOKED",
          CompanyInvitationService.findLatestInvitationStatus("client-1", "user@example.com"));
    }
  }

  @Test
  @DisplayName("findLatestInvitationStatus does not override an ACCEPTED status past its deadline")
  void testFindLatestInvitationStatusDoesNotOverrideAccepted() {
    Invitation invitation = invitationWith("ACCEPTED", new Date(System.currentTimeMillis() - 1000));

    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findLatestInvitation("client-1",
          "user@example.com")).thenReturn(invitation);

      assertEquals("ACCEPTED",
          CompanyInvitationService.findLatestInvitationStatus("client-1", "user@example.com"));
    }
  }

  // ─── resendInvitation (ETP-4830 item #2) ───────────────────────────────────

  @Test
  @DisplayName("resendInvitation rejects a null OBContext as an unprivileged inviter")
  void testResendInvitationRejectsNullContext() throws Exception {
    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject response = service.resendInvitation(null, "user-1", "https://app.test", "en_US");
    assertTrue(response.optBoolean("error"));
    assertEquals("FORBIDDEN", response.optString("code"));
  }

  @Test
  @DisplayName("resendInvitation rejects the System client (id '0')")
  void testResendInvitationRejectsSystemClient() throws Exception {
    Client systemClient = mock(Client.class);
    when(systemClient.getId()).thenReturn("0");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(systemClient);

    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject response = service.resendInvitation(obContext, "user-1", "https://app.test", "en_US");
    assertTrue(response.optBoolean("error"));
    assertEquals("FORBIDDEN", response.optString("code"));
  }

  @Test
  @DisplayName("resendInvitation rejects a blank AD_User_ID")
  void testResendInvitationRejectsBlankUserId() throws Exception {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);

    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject response = service.resendInvitation(obContext, " ", "https://app.test", "en_US");
    assertTrue(response.optBoolean("error"));
    assertEquals("MISSING_USER_ID", response.optString("code"));
  }

  @Test
  @DisplayName("resendInvitation rejects a user that does not belong to the inviter's client")
  void testResendInvitationRejectsUserFromAnotherClient() throws Exception {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);

    Client otherClient = mock(Client.class);
    when(otherClient.getId()).thenReturn("client-2");
    User user = mock(User.class);
    when(user.getClient()).thenReturn(otherClient);

    OBDal dal = mock(OBDal.class);
    when(dal.get(User.class, "user-1")).thenReturn(user);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      CompanyInvitationService service = new CompanyInvitationService();
      JSONObject response = service.resendInvitation(obContext, "user-1", "https://app.test", "en_US");
      assertTrue(response.optBoolean("error"));
      assertEquals("USER_NOT_FOUND", response.optString("code"));
    }
  }

  @Test
  @DisplayName("resendInvitation rejects a user with no email on file")
  void testResendInvitationRejectsUserWithoutEmail() throws Exception {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);

    User user = mock(User.class);
    when(user.getClient()).thenReturn(client);
    when(user.getEmail()).thenReturn(" ");

    OBDal dal = mock(OBDal.class);
    when(dal.get(User.class, "user-1")).thenReturn(user);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      CompanyInvitationService service = new CompanyInvitationService();
      JSONObject response = service.resendInvitation(obContext, "user-1", "https://app.test", "en_US");
      assertTrue(response.optBoolean("error"));
      assertEquals("MISSING_EMAIL", response.optString("code"));
    }
  }

  @Test
  @DisplayName("resendInvitation rejects when no invitation has ever been sent to this user")
  void testResendInvitationRejectsWhenNoInvitationExists() throws Exception {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);

    User user = mock(User.class);
    when(user.getClient()).thenReturn(client);
    when(user.getEmail()).thenReturn("user@example.com");

    OBDal dal = mock(OBDal.class);
    when(dal.get(User.class, "user-1")).thenReturn(user);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
            mockStatic(CompanyInvitationDalHelper.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findLatestInvitation("client-1",
          "user@example.com")).thenReturn(null);

      CompanyInvitationService service = new CompanyInvitationService();
      JSONObject response = service.resendInvitation(obContext, "user-1", "https://app.test", "en_US");
      assertTrue(response.optBoolean("error"));
      assertEquals("NO_INVITATION_TO_RESEND", response.optString("code"));
    }
  }

  @Test
  @DisplayName("resendInvitation rejects a REVOKED invitation — must not be silently resurrected")
  void testResendInvitationRejectsRevoked() throws Exception {
    JSONObject response = runResendInvitationWithExistingStatus("REVOKED", null);
    assertTrue(response.optBoolean("error"));
    assertEquals("INVITATION_NOT_RESENDABLE", response.optString("code"));
  }

  @Test
  @DisplayName("resendInvitation rejects an ACCEPTED invitation — nothing left to resend")
  void testResendInvitationRejectsAccepted() throws Exception {
    JSONObject response = runResendInvitationWithExistingStatus("ACCEPTED", null);
    assertTrue(response.optBoolean("error"));
    assertEquals("INVITATION_NOT_RESENDABLE", response.optString("code"));
  }

  @Test
  @DisplayName("resendInvitation revokes a still-open PENDING invitation before minting a new one")
  void testResendInvitationRevokesStillOpenInvitationBeforeReissuing() throws Exception {
    Invitation latest = invitationWith("PENDING", new Date(System.currentTimeMillis() + 60000));

    JSONObject response = runResendInvitationSuccess(latest);

    assertFalse(response.optBoolean("error"));
    verify(latest).setStatus("REVOKED");
    assertEquals("SENT", response.getJSONObject("invitation").getString("status"));
  }

  @Test
  @DisplayName("resendInvitation does not revoke an already-EXPIRED invitation before minting a new one")
  void testResendInvitationDoesNotRevokeAlreadyExpiredInvitation() throws Exception {
    Invitation latest = invitationWith("EXPIRED", new Date(System.currentTimeMillis() - 1000));

    JSONObject response = runResendInvitationSuccess(latest);

    assertFalse(response.optBoolean("error"));
    verify(latest, never()).setStatus(anyString());
    assertEquals("SENT", response.getJSONObject("invitation").getString("status"));
  }

  @Test
  @DisplayName("resendInvitation reissues a DELIVERY_FAILED invitation without revoking anything")
  void testResendInvitationReissuesDeliveryFailedInvitation() throws Exception {
    Invitation latest = invitationWith("DELIVERY_FAILED", null);

    JSONObject response = runResendInvitationSuccess(latest);

    assertFalse(response.optBoolean("error"));
    verify(latest, never()).setStatus(anyString());
    assertEquals("SENT", response.getJSONObject("invitation").getString("status"));
  }

  /** Runs resendInvitation for a source invitation whose status makes it ineligible. */
  private JSONObject runResendInvitationWithExistingStatus(String status, Date expiresAt)
      throws Exception {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);

    User user = mock(User.class);
    when(user.getClient()).thenReturn(client);
    when(user.getEmail()).thenReturn("user@example.com");

    Invitation latest = invitationWith(status, expiresAt);

    OBDal dal = mock(OBDal.class);
    when(dal.get(User.class, "user-1")).thenReturn(user);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
            mockStatic(CompanyInvitationDalHelper.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findLatestInvitation("client-1",
          "user@example.com")).thenReturn(latest);

      CompanyInvitationService service = new CompanyInvitationService();
      return service.resendInvitation(obContext, "user-1", "https://app.test", "en_US");
    }
  }

  /**
   * Runs resendInvitation through a full eligible/success path — inviter, target user, source
   * invitation lookup, then the mint-and-send mechanics shared with {@code createInvitation}.
   */
  private JSONObject runResendInvitationSuccess(Invitation latest) throws Exception {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    Organization org = mock(Organization.class);
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getCurrentOrganization()).thenReturn(org);

    User user = mock(User.class);
    when(user.getClient()).thenReturn(client);
    when(user.getEmail()).thenReturn("user@example.com");

    Invitation freshInvitation = mock(Invitation.class);
    when(freshInvitation.getEmail()).thenReturn("user@example.com");
    when(freshInvitation.getStatus()).thenReturn("SENT");

    TransactionalAuthEmailSender sender = mock(TransactionalAuthEmailSender.class);
    when(sender.sendCompanyInvitation(any(), any(), any())).thenReturn(true);

    OBDal dal = mock(OBDal.class);
    when(dal.get(User.class, "user-1")).thenReturn(user);
    OBProvider provider = mock(OBProvider.class);
    when(provider.get(Invitation.class)).thenReturn(freshInvitation);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class);
        MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
            mockStatic(CompanyInvitationDalHelper.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findLatestInvitation("client-1",
          "user@example.com")).thenReturn(latest);

      CompanyInvitationService service = new CompanyInvitationService(sender);
      return service.resendInvitation(obContext, "user-1", "https://app.test", "en_US");
    }
  }
}
