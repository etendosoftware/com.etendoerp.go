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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.Invitation;

/**
 * Unit tests for {@link DebugInvitationBypassService} (ETP-4830, item #4). Mirrors
 * {@link CompanyInvitationServiceTest}'s conventions: {@link OBDal} and every DAL helper this
 * class delegates to are Mockito static mocks, so no real DB access ever happens.
 *
 * <p>The flag-off rejection path itself is NOT tested here — this class performs no gating of
 * its own by design (see its class javadoc), so that critical test lives in
 * {@code NeoPseudoSpecDispatcherTest#debugInvitationBypassIsA404WhenFlagIsOff} instead, one layer
 * up, where the gate actually lives.</p>
 */
class DebugInvitationBypassServiceTest {

  private static final String EMAIL = "tester@example.com";

  // -------------------------------------------------------------------------
  // forceAccept
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("forceAccept rejects a blank email with no AdUserId to resolve one from")
  void forceAcceptRejectsBlankEmail() throws Exception {
    JSONObject response = new DebugInvitationBypassService().forceAccept("  ", null, null);

    assertFalse(response.getBoolean("success"));
    assertTrue(response.getString("message").contains("Email is required"));
  }

  @Test
  @DisplayName("forceAccept creates a new account (reusing EtendoGoJwtDalHelper.createAccount) when none exists")
  void forceAcceptCreatesAccountWhenNoneExists() throws Exception {
    Account created = mock(Account.class);
    when(created.getId()).thenReturn("acct-new");

    try (MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
         MockedStatic<CompanyInvitationDalHelper> invitationDal = mockStatic(CompanyInvitationDalHelper.class);
         MockedStatic<CompanyInvitationService> companyService = mockStatic(CompanyInvitationService.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail(EMAIL)).thenReturn(null);
      dal.when(() -> EtendoGoJwtDalHelper.createAccount(
          eq(EMAIL), anyString(), anyString(), anyString())).thenReturn(created);
      companyService.when(CompanyInvitationService::generateToken).thenReturn("tok-12345678901234567890");
      invitationDal.when(() -> CompanyInvitationDalHelper.findInvitationsForEmail(EMAIL))
          .thenReturn(Collections.emptyList());
      OBDal dalInstance = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dalInstance);

      JSONObject response = new DebugInvitationBypassService().forceAccept(EMAIL, null, null);

      assertTrue(response.getBoolean("success"));
      assertTrue(response.getBoolean("accountCreated"));
      assertEquals("acct-new", response.getString("accountId"));
      assertTrue(response.getString("temporaryPassword").length() >= 8);
      assertEquals(JSONObject.NULL, response.get("invitationId"));
      // No duplicated account-provisioning logic: the account primitive itself is called exactly
      // once, through the shared helper — never re-implemented locally.
      dal.verify(() -> EtendoGoJwtDalHelper.createAccount(
          eq(EMAIL), anyString(), anyString(), anyString()));
    }
  }

  @Test
  @DisplayName("forceAccept reuses an existing active account without creating a second one")
  void forceAcceptReusesExistingActiveAccount() throws Exception {
    Account existing = mock(Account.class);
    when(existing.getId()).thenReturn("acct-existing");
    when(existing.isActive()).thenReturn(true);
    when(existing.get(Account.PROPERTY_STATUS)).thenReturn("active");

    try (MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
         MockedStatic<CompanyInvitationDalHelper> invitationDal = mockStatic(CompanyInvitationDalHelper.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail(EMAIL)).thenReturn(existing);
      invitationDal.when(() -> CompanyInvitationDalHelper.findInvitationsForEmail(EMAIL))
          .thenReturn(Collections.emptyList());
      OBDal dalInstance = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dalInstance);

      JSONObject response = new DebugInvitationBypassService().forceAccept(EMAIL, null, null);

      assertTrue(response.getBoolean("success"));
      assertFalse(response.getBoolean("accountCreated"));
      assertEquals("acct-existing", response.getString("accountId"));
      assertFalse(response.has("temporaryPassword"));
      dal.verify(() -> EtendoGoJwtDalHelper.createAccount(
          anyString(), anyString(), anyString(), anyString()), never());
    }
  }

  @Test
  @DisplayName("forceAccept flips a matching open invitation to ACCEPTED and links the account")
  void forceAcceptAcceptsMatchingInvitation() throws Exception {
    Account existing = mock(Account.class);
    when(existing.getId()).thenReturn("acct-existing");
    when(existing.isActive()).thenReturn(true);
    when(existing.get(Account.PROPERTY_STATUS)).thenReturn("active");

    Invitation invitation = mock(Invitation.class);
    when(invitation.getId()).thenReturn("inv-1");
    when(invitation.getStatus()).thenReturn("SENT");

    try (MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
         MockedStatic<CompanyInvitationDalHelper> invitationDal = mockStatic(CompanyInvitationDalHelper.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail(EMAIL)).thenReturn(existing);
      invitationDal.when(() -> CompanyInvitationDalHelper.findInvitationsForEmail(EMAIL))
          .thenReturn(List.of(invitation));
      OBDal dalInstance = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dalInstance);

      DebugInvitationBypassService service = new DebugInvitationBypassService();
      JSONObject response = service.forceAccept(EMAIL, null, null);

      assertTrue(response.getBoolean("success"));
      assertEquals("inv-1", response.getString("invitationId"));
      verify(invitation).setEtgoAccount(existing);
      verify(invitation).setStatus("ACCEPTED");
      verify(dalInstance).save(invitation);
    }
  }

  @Test
  @DisplayName("forceAccept called twice on an already-ACCEPTED invitation is idempotent — "
      + "no exception, no re-mutation of the invitation row (ETP-4830 QA edge case)")
  void forceAcceptOnAlreadyAcceptedInvitationIsIdempotent() throws Exception {
    Account existing = mock(Account.class);
    when(existing.getId()).thenReturn("acct-existing");
    when(existing.isActive()).thenReturn(true);
    when(existing.get(Account.PROPERTY_STATUS)).thenReturn("active");

    Invitation invitation = mock(Invitation.class);
    when(invitation.getId()).thenReturn("inv-1");
    // Simulates the state left behind by a first, successful forceAccept call.
    when(invitation.getStatus()).thenReturn("ACCEPTED");

    try (MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
         MockedStatic<CompanyInvitationDalHelper> invitationDal = mockStatic(CompanyInvitationDalHelper.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail(EMAIL)).thenReturn(existing);
      invitationDal.when(() -> CompanyInvitationDalHelper.findInvitationsForEmail(EMAIL))
          .thenReturn(List.of(invitation));
      OBDal dalInstance = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dalInstance);

      DebugInvitationBypassService service = new DebugInvitationBypassService();
      JSONObject response = service.forceAccept(EMAIL, null, null);

      assertTrue(response.getBoolean("success"));
      assertEquals("inv-1", response.getString("invitationId"));
      assertEquals("ACCEPTED", response.getString("invitationStatus"));
      // The already-ACCEPTED branch must not re-link or re-mutate the invitation row.
      verify(invitation, never()).setEtgoAccount(existing);
      verify(invitation, never()).setStatus(anyString());
      // No second account is created either — the existing active one is reused as-is.
      dal.verify(() -> EtendoGoJwtDalHelper.createAccount(
          anyString(), anyString(), anyString(), anyString()), never());
    }
  }

  @Test
  @DisplayName("forceAccept resolves the email from AdUserId when Email is blank")
  void forceAcceptResolvesEmailFromAdUserId() throws Exception {
    User user = mock(User.class);
    when(user.getEmail()).thenReturn("resolved@example.com");
    Account existing = mock(Account.class);
    when(existing.getId()).thenReturn("acct-1");
    when(existing.isActive()).thenReturn(true);
    when(existing.get(Account.PROPERTY_STATUS)).thenReturn("active");

    try (MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
         MockedStatic<CompanyInvitationDalHelper> invitationDal = mockStatic(CompanyInvitationDalHelper.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      when(dalInstance.get(User.class, "user-1")).thenReturn(user);
      obDal.when(OBDal::getInstance).thenReturn(dalInstance);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("resolved@example.com"))
          .thenReturn(existing);
      invitationDal.when(() -> CompanyInvitationDalHelper.findInvitationsForEmail("resolved@example.com"))
          .thenReturn(Collections.emptyList());

      JSONObject response = new DebugInvitationBypassService().forceAccept(null, "user-1", null);

      assertTrue(response.getBoolean("success"));
      assertEquals("resolved@example.com", response.getString("email"));
    }
  }

  // -------------------------------------------------------------------------
  // forceStatus
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("forceStatus rejects a status outside the ETGO_INVITATION.STATUS enum")
  void forceStatusRejectsInvalidStatus() throws Exception {
    JSONObject response = new DebugInvitationBypassService()
        .forceStatus(null, EMAIL, "NOT_A_REAL_STATUS");

    assertFalse(response.getBoolean("success"));
    assertTrue(response.getString("message").contains("Status must be one of"));
  }

  @Test
  @DisplayName("forceStatus resolves the invitation directly by id, skipping the email lookup")
  void forceStatusResolvesByInvitationId() throws Exception {
    Invitation invitation = mock(Invitation.class);
    when(invitation.getId()).thenReturn("inv-1");
    when(invitation.getEmail()).thenReturn(EMAIL);
    when(invitation.getStatus()).thenReturn("SENT");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      when(dalInstance.get(Invitation.class, "inv-1")).thenReturn(invitation);
      obDal.when(OBDal::getInstance).thenReturn(dalInstance);

      JSONObject response = new DebugInvitationBypassService().forceStatus("inv-1", null, "sent");

      assertTrue(response.getBoolean("success"));
      verify(invitation).setStatus("SENT");
      verify(dalInstance, never()).get(eq(User.class), anyString());
    }
  }

  @Test
  @DisplayName("forceStatus resolves the latest invitation by email when no id is given")
  void forceStatusResolvesByEmail() throws Exception {
    Invitation invitation = mock(Invitation.class);
    when(invitation.getId()).thenReturn("inv-2");
    when(invitation.getEmail()).thenReturn(EMAIL);
    when(invitation.getStatus()).thenReturn("PENDING");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<CompanyInvitationDalHelper> invitationDal = mockStatic(CompanyInvitationDalHelper.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dalInstance);
      invitationDal.when(() -> CompanyInvitationDalHelper.findInvitationsForEmail(EMAIL))
          .thenReturn(List.of(invitation));

      JSONObject response = new DebugInvitationBypassService()
          .forceStatus(null, EMAIL, "DELIVERY_FAILED");

      assertTrue(response.getBoolean("success"));
      verify(invitation).setStatus("DELIVERY_FAILED");
    }
  }

  @Test
  @DisplayName("forceStatus fails cleanly when no invitation matches")
  void forceStatusFailsWhenNoInvitationMatches() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<CompanyInvitationDalHelper> invitationDal = mockStatic(CompanyInvitationDalHelper.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dalInstance);
      invitationDal.when(() -> CompanyInvitationDalHelper.findInvitationsForEmail(EMAIL))
          .thenReturn(Collections.emptyList());

      JSONObject response = new DebugInvitationBypassService().forceStatus(null, EMAIL, "SENT");

      assertFalse(response.getBoolean("success"));
      assertEquals("No matching invitation found", response.getString("message"));
    }
  }
}
