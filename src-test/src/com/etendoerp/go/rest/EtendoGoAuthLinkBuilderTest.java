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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Covers the mailed auth links. Every case uses the explicit-base-URL overload so the assertions do
 * not depend on whether the machine running the suite happens to have the app base URL configured.
 */
public class EtendoGoAuthLinkBuilderTest {

  private static final String BASE_URL = "https://app.example.test";

  @Test
  public void verifyEmailLinkAppendsTheTokenToTheOnboardingRoute() {
    assertEquals("https://app.example.test/onboarding?verifyToken=abc123",
        EtendoGoAuthLinkBuilder.verifyEmailLink("abc123", BASE_URL));
  }

  @Test
  public void verifyEmailLinkUrlEncodesTheToken() {
    // The token is Base64url, but nothing guarantees a caller will not hand over padding or a
    // slash, and an unencoded one would silently truncate the query parameter.
    assertEquals("https://app.example.test/onboarding?verifyToken=a%2Bb%2Fc%3D",
        EtendoGoAuthLinkBuilder.verifyEmailLink("a+b/c=", BASE_URL));
  }

  @Test
  public void verifyEmailLinkIsNullWithoutAConfiguredBaseUrl() {
    // ETP-4798 relies on this: no link means no verification token is stored, so the account is
    // left ungated instead of locked out of an environment it can never create.
    assertNull(EtendoGoAuthLinkBuilder.verifyEmailLink("abc123", null));
    assertNull(EtendoGoAuthLinkBuilder.verifyEmailLink("abc123", " "));
  }

  @Test
  public void verifyEmailLinkIsNullWithoutAToken() {
    assertNull(EtendoGoAuthLinkBuilder.verifyEmailLink(null, BASE_URL));
    assertNull(EtendoGoAuthLinkBuilder.verifyEmailLink("  ", BASE_URL));
  }

  @Test
  public void resetPasswordLinkStillUsesItsOwnParameter() {
    // The two flows share one builder now; this guards the shared helper from swapping the names.
    assertEquals("https://app.example.test/onboarding?resetToken=xyz",
        EtendoGoAuthLinkBuilder.resetPasswordLink("xyz", BASE_URL));
  }
}
