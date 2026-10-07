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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.session.GoSessionRecord;

/**
 * Unit tests for {@link SupportAccessGuard} (ETP-5351): pure checks, no database.
 */
class SupportAccessGuardTest {

  private static final String CLIENT_A = "4028E6C72959682B01295A070852010D";
  private static final String CLIENT_B = "23C59575B9CF467C9620760EB255B389";

  @Test
  void supportUserIdIsDeterministicUpperHexAndPerClient() {
    String id = SupportAccessGuard.supportUserIdFor(CLIENT_A);
    assertEquals(id, SupportAccessGuard.supportUserIdFor(CLIENT_A));
    assertEquals(32, id.length());
    assertTrue(id.matches("[0-9A-F]{32}"));
    assertNotEquals(id, SupportAccessGuard.supportUserIdFor(CLIENT_B));
  }

  @Test
  void supportUserIdRequiresAClient() {
    assertThrows(IllegalArgumentException.class, () -> SupportAccessGuard.supportUserIdFor(" "));
  }

  @Test
  void supportUserIsRecognisedOnlyInItsOwnClient() {
    String supportUser = SupportAccessGuard.supportUserIdFor(CLIENT_A);
    assertTrue(SupportAccessGuard.isSupportUser(supportUser, CLIENT_A));
    assertFalse(SupportAccessGuard.isSupportUser(supportUser, CLIENT_B));
    assertFalse(SupportAccessGuard.isSupportUser("100", CLIENT_A));
    assertFalse(SupportAccessGuard.isSupportUser(null, CLIENT_A));
    assertFalse(SupportAccessGuard.isSupportUser(supportUser, null));
  }

  @Test
  void supportAccountIsMatchedByIdOnly() {
    Account support = mock(Account.class);
    when(support.getId()).thenReturn(SupportAccessGuard.SUPPORT_ACCOUNT_ID);
    Account sameEmailOtherId = mock(Account.class);
    when(sameEmailOtherId.getId()).thenReturn("ACC1");
    when(sameEmailOtherId.getEmail()).thenReturn(SupportAccessGuard.SUPPORT_ACCOUNT_EMAIL);

    assertTrue(SupportAccessGuard.isSupportAccount(support));
    assertFalse(SupportAccessGuard.isSupportAccount(sameEmailOtherId),
        "matching by email would hand the support role to whoever registered it first");
    assertFalse(SupportAccessGuard.isSupportAccount(null));
  }

  @Test
  void supportSessionIsMarkedOrOwnedByTheTechnicalAccount() {
    GoSessionRecord marked = new GoSessionRecord();
    marked.setAccountId("ACC1");
    marked.setSupportAccessId("ACCESS1");
    GoSessionRecord owned = new GoSessionRecord();
    owned.setAccountId(SupportAccessGuard.SUPPORT_ACCOUNT_ID);
    GoSessionRecord ordinary = new GoSessionRecord();
    ordinary.setAccountId("ACC1");

    assertTrue(SupportAccessGuard.isSupportSession(marked));
    assertTrue(SupportAccessGuard.isSupportSession(owned));
    assertFalse(SupportAccessGuard.isSupportSession(ordinary));
    assertFalse(SupportAccessGuard.isSupportSession(null));
  }

  @Test
  void supportEmailIgnoresCaseAndBlanks() {
    assertTrue(SupportAccessGuard.isSupportEmail(" Soporte@Etendo-Go.INVALID "));
    assertFalse(SupportAccessGuard.isSupportEmail("soporte@etendo-go.com"));
    assertFalse(SupportAccessGuard.isSupportEmail(null));
  }

  @Test
  void accountSurfaceKeepsReadsButNotTheCredentialMintingOnes() {
    assertTrue(SupportAccessGuard.isAllowedOnAccountSurface("GET", "/me"));
    assertTrue(SupportAccessGuard.isAllowedOnAccountSurface("GET", "/environments/"));
    assertFalse(SupportAccessGuard.isAllowedOnAccountSurface("GET", "/login"));
    assertFalse(SupportAccessGuard.isAllowedOnAccountSurface("GET", "/login/"));
  }

  @Test
  void accountSurfaceRefusesEveryWriteButTheAllowlist() {
    assertTrue(SupportAccessGuard.isAllowedOnAccountSurface("POST", "/onboarding/first-steps"));
    for (String route : new String[] { "/change-password", "/auth-methods/remove",
        "/company-invitations", "/company-invitations/accept", "/onboarding", "/onboarding/draft",
        "/billing/purchases", "/checkout/sessions", "/verify-email/resend",
        "/demo-data-transfer/retry" }) {
      assertFalse(SupportAccessGuard.isAllowedOnAccountSurface("POST", route), route);
    }
    assertFalse(SupportAccessGuard.isAllowedOnAccountSurface("DELETE", "/whatever"));
  }
}
