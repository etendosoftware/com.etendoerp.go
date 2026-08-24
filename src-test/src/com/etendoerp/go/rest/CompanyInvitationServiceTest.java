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
      dalHelperMock.when(() -> CompanyInvitationDalHelper.hasActiveRoleForOrganization(user, org))
          .thenReturn(true);
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
      dalHelperMock.verify(() -> CompanyInvitationDalHelper.hasActiveRoleForOrganization(user, org));
      jwtHelperMock.verify(() -> EtendoGoJwtDalHelper.createAccount(eq("invitee@example.com"),
          anyString(), eq("Jane Doe"), anyString()));
      verify(invitation).setEtgoAccount(createdAccount);
      verify(invitation).setStatus("ACCEPTED");
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
}
