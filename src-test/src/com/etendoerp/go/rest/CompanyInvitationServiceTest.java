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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;

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
}
