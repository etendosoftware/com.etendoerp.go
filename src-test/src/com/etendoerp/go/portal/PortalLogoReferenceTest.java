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

package com.etendoerp.go.portal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;

import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;

/**
 * Unit tests for {@link PortalLogoReference}, the signed, token-free reference of a tenant's logo
 * in the portal emails (ETP-6209): it round-trips its tenant, and nothing that was not signed by
 * this server for this purpose resolves — in particular a download token is never a logo reference
 * and a logo reference is never a download token.
 *
 * <p>The secret is the email signing secret of {@link DocumentDownloadTokenService}, set here as a
 * system property; the no-secret case also mocks Openbravo.properties empty (environment variables
 * are assumed absent, as in the token service's own tests).
 *
 * @covers com.etendoerp.go.portal.PortalLogoReference
 */
public class PortalLogoReferenceTest {

  private static final String CLIENT_ID = "CLIENT-1";
  private static final String ORG_ID = "ORG-1";

  private String previousSecret;
  private String previousBaseUrl;

  @Before
  public void setUp() {
    previousSecret = System.getProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET);
    previousBaseUrl = System.getProperty(DocumentDownloadTokenService.PROP_DOWNLOAD_BASE_URL);
    System.setProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET, "logo-secret");
  }

  @After
  public void tearDown() {
    restore(DocumentDownloadTokenService.PROP_TOKEN_SECRET, previousSecret);
    restore(DocumentDownloadTokenService.PROP_DOWNLOAD_BASE_URL, previousBaseUrl);
  }

  @Test
  public void aReferenceResolvesBackToItsTenantAndOrganization() {
    // Columns: organization signed, organization resolved.
    String[][] cases = { { ORG_ID, ORG_ID }, { null, null }, { "  ", null } };
    for (String[] c : cases) {
      String reference = PortalLogoReference.reference(CLIENT_ID, c[0])
          .orElseThrow(() -> new AssertionError("a configured secret signs a reference"));

      String[] tenant = PortalLogoReference.resolve(reference)
          .orElseThrow(() -> new AssertionError("its own reference must resolve: " + reference));
      assertArrayEquals("org " + c[0], new String[] { CLIENT_ID, c[1] }, tenant);
    }
  }

  @Test
  public void nothingThisServerDidNotSignForTheLogoResolves() {
    String reference = PortalLogoReference.reference(CLIENT_ID, ORG_ID).orElse(null);
    int dot = reference.lastIndexOf('.');
    String payload = reference.substring(0, dot);
    String signature = reference.substring(dot + 1);
    String otherTenant = encode("CLIENT-2|" + ORG_ID);
    String lastChar = signature.substring(signature.length() - 1);
    String flipped = signature.substring(0, signature.length() - 1)
        + ("A".equals(lastChar) ? "B" : "A");

    String[][] cases = {
        { "tampered payload", otherTenant + "." + signature },
        { "tampered signature", payload + "." + flipped },
        { "missing signature", payload + "." },
        { "no payload", "." + signature },
        { "no dot", payload + signature },
        { "empty", "" },
        { "null", null },
        { "signed for another purpose", signedFor("kpi-third-party:v1", payload) },
        { "signed payload that is not base64url", signedFor(PortalLogoReference.PURPOSE, "!!!") },
        { "signed payload without the separator", signedFor(PortalLogoReference.PURPOSE,
            encode(CLIENT_ID)) },
        { "signed payload with a blank client", signedFor(PortalLogoReference.PURPOSE,
            encode("|" + ORG_ID)) },
        { "a download token", downloadToken() },
    };
    for (String[] c : cases) {
      assertFalse(c[0], PortalLogoReference.resolve(c[1]).isPresent());
    }
  }

  @Test
  public void aLogoReferenceIsNeverAcceptedAsADownloadToken() {
    String reference = PortalLogoReference.reference(CLIENT_ID, ORG_ID).orElse(null);

    assertFalse(DocumentDownloadTokenService.validate(reference).isPresent());
    assertFalse(DocumentDownloadTokenService
        .validate(PortalLogoReference.PURPOSE + ":" + reference).isPresent());
    assertTrue("the download token itself still validates",
        DocumentDownloadTokenService.validate(downloadToken()).isPresent());
  }

  @Test
  public void withoutTheSigningSecretThereIsNoReferenceAndNothingResolves() {
    String reference = PortalLogoReference.reference(CLIENT_ID, ORG_ID).orElse(null);
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    try (MockedStatic<OBPropertiesProvider> properties = mockStatic(OBPropertiesProvider.class)) {
      properties.when(OBPropertiesProvider::getInstance).thenReturn(provider);
      System.clearProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET);

      assertFalse(PortalLogoReference.reference(CLIENT_ID, ORG_ID).isPresent());
      assertFalse(PortalLogoReference.resolve(reference).isPresent());
    }
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private static String signedFor(String purpose, String payload) {
    return payload + "." + DocumentDownloadTokenService.signDetached(purpose, payload)
        .orElseThrow(() -> new AssertionError("a configured secret signs"));
  }

  /** A real download token, from a link built with the same secret. */
  private static String downloadToken() {
    System.setProperty(DocumentDownloadTokenService.PROP_DOWNLOAD_BASE_URL,
        "https://go.example.test/sws/neo/document-download");
    String link = DocumentDownloadTokenService.createDownloadLink("sales-invoice-send",
        "sales-invoice", "INV-1", CLIENT_ID, "sales-invoice-send:" + CLIENT_ID + ":INV-1:v1")
        .orElseThrow(() -> new AssertionError("a configured secret and base URL build a link"));
    return link.substring(link.lastIndexOf('/') + 1);
  }

  private static String encode(String text) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  private static void restore(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }
}
