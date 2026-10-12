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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.schemaforge.data.Invitation;

/**
 * Unit tests for {@link CompanyInvitationEmailCorrection} — the invitation lookups and the revoke
 * behind the {@code user} email-correction window.
 *
 * @covers com.etendoerp.go.rest.CompanyInvitationEmailCorrection
 */
class CompanyInvitationEmailCorrectionTest {

  @Test
  @DisplayName("Only EXPIRED and DELIVERY_FAILED let an admin correct the invitee's email")
  void isEmailCorrectableStatusAcceptsOnlyExpiredAndDeliveryFailed() {
    assertTrue(CompanyInvitationEmailCorrection.isEmailCorrectableStatus("EXPIRED"));
    assertTrue(CompanyInvitationEmailCorrection.isEmailCorrectableStatus("DELIVERY_FAILED"));
    for (String status : Arrays.asList("PENDING", "SENT", "ACCEPTED", "REVOKED", null)) {
      assertFalse(CompanyInvitationEmailCorrection.isEmailCorrectableStatus(status), String.valueOf(status));
    }
  }

  @Test
  @DisplayName("hasInvitationForUser never queries without a client and a user")
  void hasInvitationForUserSkipsTheQueryForBlankArguments() {
    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      assertFalse(CompanyInvitationEmailCorrection.hasInvitationForUser(null, "user-1"));
      assertFalse(CompanyInvitationEmailCorrection.hasInvitationForUser("client-1", ""));
      dalHelperMock.verify(
          () -> CompanyInvitationDalHelper.existsInvitationForUser(anyString(), anyString()),
          never());

      dalHelperMock.when(() -> CompanyInvitationDalHelper.existsInvitationForUser("client-1",
          "user-1")).thenReturn(true);
      assertTrue(CompanyInvitationEmailCorrection.hasInvitationForUser("client-1", "user-1"));
    }
  }

  @Test
  @DisplayName("hasAcceptedInvitation never queries without a client, a user and an email")
  void hasAcceptedInvitationSkipsTheQueryForBlankArguments() {
    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      assertFalse(CompanyInvitationEmailCorrection.hasAcceptedInvitation(null, "user-1", "a@example.com"));
      assertFalse(CompanyInvitationEmailCorrection.hasAcceptedInvitation("client-1", null, "a@example.com"));
      assertFalse(CompanyInvitationEmailCorrection.hasAcceptedInvitation("client-1", "user-1", " "));
      dalHelperMock.verify(() -> CompanyInvitationDalHelper.existsAcceptedInvitation(anyString(),
          anyString(), anyString()), never());

      dalHelperMock.when(() -> CompanyInvitationDalHelper.existsAcceptedInvitation("client-1",
          "user-1", "a@example.com")).thenReturn(true);
      assertTrue(
          CompanyInvitationEmailCorrection.hasAcceptedInvitation("client-1", "user-1", "a@example.com"));
    }
  }

  @Test
  @DisplayName("latestInvitationBelongsTo is true only for this user's own latest invitation")
  void latestInvitationBelongsToChecksTheOwnerOfTheLatestInvitation() {
    User owner = mock(User.class);
    when(owner.getId()).thenReturn("user-1");
    Invitation latest = mock(Invitation.class);
    when(latest.getUser()).thenReturn(owner);

    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findLatestInvitation("client-1",
          "new@example.com")).thenReturn(latest);

      assertTrue(CompanyInvitationEmailCorrection.latestInvitationBelongsTo("client-1",
          "New@Example.com", "user-1"));
      assertFalse(CompanyInvitationEmailCorrection.latestInvitationBelongsTo("client-1",
          "new@example.com", "other-user"));
      assertFalse(CompanyInvitationEmailCorrection.latestInvitationBelongsTo("client-1",
          "none@example.com", "user-1"));
      assertFalse(CompanyInvitationEmailCorrection.latestInvitationBelongsTo(null,
          "new@example.com", "user-1"));
    }
  }

  @Test
  @DisplayName("revokeSupersededInvitations revokes only open old-address invitations")
  void revokeSupersededInvitationsRevokesOnlyOldAddressInvitationsStillUsable() {
    Invitation expiredOld = invitationForEmail("old@example.com", "SENT");
    Invitation failedOld = invitationForEmail("old@example.com", "DELIVERY_FAILED");
    Invitation acceptedOld = invitationForEmail("old@example.com", "ACCEPTED");
    Invitation revokedOld = invitationForEmail("old@example.com", "REVOKED");
    Invitation freshNew = invitationForEmail("New@Example.com", "SENT");
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
            mockStatic(CompanyInvitationDalHelper.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      dalHelperMock.when(() -> CompanyInvitationDalHelper.findInvitationsForUser("client-1",
          "user-1")).thenReturn(
              Arrays.asList(expiredOld, failedOld, acceptedOld, revokedOld, freshNew));

      int revoked = CompanyInvitationEmailCorrection.revokeSupersededInvitations("client-1", "user-1",
          "new@example.com");

      assertEquals(2, revoked);
      verify(expiredOld).setStatus("REVOKED");
      verify(failedOld).setStatus("REVOKED");
      verify(acceptedOld, never()).setStatus(anyString());
      verify(revokedOld, never()).setStatus(anyString());
      verify(freshNew, never()).setStatus(anyString());
      verify(dal).flush();
    }
  }

  @Test
  @DisplayName("revokeSupersededInvitations is a no-op without a current email")
  void revokeSupersededInvitationsSkipsWithoutCurrentEmail() {
    try (MockedStatic<CompanyInvitationDalHelper> dalHelperMock =
        mockStatic(CompanyInvitationDalHelper.class)) {
      assertEquals(0,
          CompanyInvitationEmailCorrection.revokeSupersededInvitations("client-1", "user-1", null));
      dalHelperMock.verify(
          () -> CompanyInvitationDalHelper.findInvitationsForUser(anyString(), anyString()),
          never());
    }
  }

  private static Invitation invitationForEmail(String email, String status) {
    Invitation invitation = mock(Invitation.class);
    when(invitation.getEmail()).thenReturn(email);
    when(invitation.getStatus()).thenReturn(status);
    return invitation;
  }
}
