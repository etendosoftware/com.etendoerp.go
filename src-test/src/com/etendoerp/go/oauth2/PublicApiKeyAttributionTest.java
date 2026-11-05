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

package com.etendoerp.go.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.usageevents.PublicApiCaller;

/**
 * Unit specs for {@link PublicApiKeyAttribution}: which key and tenant a request belongs to, from a
 * validated token identity, a refused token or a key row at the token exchange, and the request
 * attribute that carries the answer to the NEO usage counter.
 *
 * <p>New class: no existing test covers it. {@code OAuth2FilterTest} was considered, but it tests the
 * servlet filter and the token lookup, not this attribution policy.</p>
 *
 * @covers com.etendoerp.go.oauth2.PublicApiKeyAttribution
 */
class PublicApiKeyAttributionTest {

  private static Map<String, String> identity(String publicKey, String clientId,
      String roleClientId) {
    Map<String, String> m = new HashMap<>();
    m.put(OAuth2Filter.ATTR_PUBLIC_API_KEY, publicKey);
    m.put(OAuth2Filter.ATTR_CLIENT_ID, clientId);
    m.put(OAuth2Filter.ATTR_ROLE_CLIENT_ID, roleClientId);
    m.put(OAuth2Filter.ATTR_ORG_ID, "ORG1");
    m.put(OAuth2Filter.ATTR_USER_ID, "USER1");
    m.put(OAuth2Filter.ATTR_ROLE_ID, "ROLE1");
    m.put(OAuth2Filter.ATTR_KEY_ID, "key-public");
    m.put(OAuth2Filter.ATTR_SCOPES, "etendo:read");
    return m;
  }

  // ── fromIdentity ───────────────────────────────────────────────────────

  @Test
  void aPublicKeyIdentityBecomesACallerWithTheKeyClientIdentifier() {
    PublicApiCaller caller =
        PublicApiKeyAttribution.fromIdentity(identity("true", "TENANT", "ROLECLI"));

    assertEquals(new PublicApiCaller("TENANT", "ORG1", "USER1", "ROLE1", "key-public"), caller);
  }

  @Test
  void aNonPublicNullOrUnmarkedIdentityIsNotAttributed() {
    assertNull(PublicApiKeyAttribution.fromIdentity(null));
    assertNull(PublicApiKeyAttribution.fromIdentity(identity("false", "TENANT", "ROLECLI")));
    assertNull(PublicApiKeyAttribution.fromIdentity(identity(null, "TENANT", "ROLECLI")));
    Map<String, String> noFlag = identity("true", "TENANT", "ROLECLI");
    noFlag.remove(OAuth2Filter.ATTR_PUBLIC_API_KEY);
    assertNull(PublicApiKeyAttribution.fromIdentity(noFlag));
  }

  @Test
  void theTenantFallsBackToTheRolesClientWhenTheOwnerOrgIsStar() {
    assertEquals("ROLECLI",
        PublicApiKeyAttribution.fromIdentity(identity("true", "0", "ROLECLI")).clientId());
    assertEquals("ROLECLI",
        PublicApiKeyAttribution.fromIdentity(identity("true", null, "ROLECLI")).clientId());
    assertEquals("ROLECLI",
        PublicApiKeyAttribution.fromIdentity(identity("true", " ", "ROLECLI")).clientId());
  }

  @Test
  void theOwnerOrgsClientWinsOverTheRolesClient() {
    assertEquals("TENANT",
        PublicApiKeyAttribution.fromIdentity(identity("true", "TENANT", "ROLECLI")).clientId());
  }

  @Test
  void theSystemClientIsNeverATenant() {
    // ETP-6057 (L4): a System-role key on org * ends the chain on "0", which is no tenant.
    assertNull(PublicApiKeyAttribution.tenant("0", null));
    assertNull(PublicApiKeyAttribution.tenant("0", " "));
    assertNull(PublicApiKeyAttribution.tenant("0", "0"));
    assertNull(PublicApiKeyAttribution.tenant(null, null));
  }

  // ── attach / of ────────────────────────────────────────────────────────

  @Test
  void attachStoresTheCallerOnTheRequestAndOfReadsItBack() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    PublicApiCaller caller = new PublicApiCaller("C", "O", "U", "R", "K");
    when(request.getAttribute(PublicApiKeyAttribution.REQUEST_ATTRIBUTE)).thenReturn(caller);

    PublicApiKeyAttribution.attach(request, caller);

    verify(request).setAttribute(PublicApiKeyAttribution.REQUEST_ATTRIBUTE, caller);
    assertSame(caller, PublicApiKeyAttribution.of(request));
  }

  @Test
  void attachIsANoOpForANullRequestOrCaller() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    PublicApiKeyAttribution.attach(request, null);
    PublicApiKeyAttribution.attach(null, new PublicApiCaller("C", "O", "U", "R", "K"));

    verify(request, never()).setAttribute(anyString(), any());
  }

  @Test
  void ofIsNullForNoRequestNoAttributeOrAForeignAttribute() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    assertNull(PublicApiKeyAttribution.of(null));
    assertNull(PublicApiKeyAttribution.of(request));
    when(request.getAttribute(PublicApiKeyAttribution.REQUEST_ATTRIBUTE)).thenReturn("not-a-caller");
    assertNull(PublicApiKeyAttribution.of(request));
  }

  @Test
  void theTenantChainEndsOnNullWhenOrgAndRoleAreBothTheSystemClient() {
    assertNull(PublicApiKeyAttribution.tenant("0", "0"));
    assertNull(PublicApiKeyAttribution.ofKeyRow("k", "neo:public-api-key", "O", "0", "0", "U", "R")
        .clientId());
    assertNull(PublicApiKeyAttribution.fromIdentity(identity("true", "0", "0")).clientId());
  }

  @Test
  void aBlankOwnerOrgClientFallsBackToTheRolesTenant() {
    assertEquals("TENANT", PublicApiKeyAttribution.tenant(" ", "TENANT"));
    assertEquals("TENANT", PublicApiKeyAttribution.tenant(null, "TENANT"));
    assertEquals("TENANT", PublicApiKeyAttribution.tenant("", "TENANT"));
  }

  @Test
  void theTokenPathAndTheKeyRowPathAnswerTheSameForTheSameInputs() {
    String[] clients = { null, "", " ", "0", "REAL" };
    for (String orgClient : clients) {
      for (String roleClient : clients) {
        String viaToken = PublicApiKeyAttribution
            .fromIdentity(identity("true", orgClient, roleClient)).clientId();
        String viaRow = PublicApiKeyAttribution.ofKeyRow("key-public", "neo:public-api-key",
            "ORG1", orgClient, roleClient, "USER1", "ROLE1").clientId();

        assertEquals(viaToken, viaRow, "org=" + orgClient + " role=" + roleClient);
      }
    }
  }

  // ── ofKeyRow ───────────────────────────────────────────────────────────

  @Test
  void aKeyRowOfAPublicKeyGetsTheOwnerOrgsClientAndItsIdentifier() {
    PublicApiCaller caller = PublicApiKeyAttribution.ofKeyRow("key-public",
        "neo:public-api-key etendo:read", "OWNERORG", "ORGCLI", "ROLECLI", "USER1", "ROLE1");

    assertEquals(new PublicApiCaller("ORGCLI", "OWNERORG", "USER1", "ROLE1", "key-public"), caller);
  }

  @Test
  void aKeyRowWhoseOwnerOrgIsStarUsesTheRolesClient() {
    assertEquals("ROLECLI", PublicApiKeyAttribution.ofKeyRow("key-public", "neo:public-api-key",
        "OWNERORG", "0", "ROLECLI", "USER1", "ROLE1").clientId());
  }

  @Test
  void aKeyRowOfANonPublicKeyIsNotAttributedAndNeverTouchesTheDatabase() {
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      assertNull(PublicApiKeyAttribution.ofKeyRow("k", "neo:read", "O", "OC", "RC", "U", "R"));
      assertNull(PublicApiKeyAttribution.ofKeyRow("k", null, "O", "OC", "RC", "U", "R"));
      dalStatic.verifyNoInteractions();
    }
  }

  @Test
  void aPublicKeyRowNeverTouchesTheDatabaseEither() {
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      assertEquals("OC", PublicApiKeyAttribution.ofKeyRow("k", "neo:public-api-key", "O", "OC",
          "RC", "U", "R").clientId());
      dalStatic.verifyNoInteractions();
    }
  }

  @Test
  void aKeyRowWithNoTenantKeepsNullClient() {
    PublicApiCaller caller = PublicApiKeyAttribution.ofKeyRow("key-public", "neo:public-api-key",
        "OWNERORG", null, null, "USER1", "ROLE1");

    assertNull(caller.clientId());
    assertEquals("key-public", caller.keyId());
  }
}
