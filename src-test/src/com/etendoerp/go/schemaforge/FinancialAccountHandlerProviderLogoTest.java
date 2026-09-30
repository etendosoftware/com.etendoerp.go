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

package com.etendoerp.go.schemaforge;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * ETP-5521 — the {@code providerLogoUrl} sanitizer behind
 * {@link FinancialAccountHandler#validateAndEnrichCreate}: the client-supplied logo is accepted
 * only as an https URL on the Salt Edge CDN host (no user-info, default port, at most 255 chars
 * after trimming, host compared case-insensitively). Anything else — http, {@code javascript:},
 * other or look-alike hosts, IP literals, over-long, blank, non-string — is sanitized to
 * {@code null} and never triggers the fill-only provider lookup.
 *
 * <p>Split from {@link FinancialAccountHandlerProviderTest} to keep both under the Sonar
 * 35-method-per-class limit; fixtures in {@link FinancialAccountProviderTestSupport}.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountHandlerProviderLogoTest extends FinancialAccountProviderTestSupport {

  /**
   * ETP-5521: a valid Salt Edge CDN {@code providerLogoUrl} for a provider not yet in the catalog
   * is passed through to the 4-arg upsert, and the transient key is stripped.
   */
  @Test
  public void testCreateBankWithHttpsLogoPassesLogoToUpsert() throws Exception {
    assertUpsertedWithLogo(bankBodyWithLogo(SANTANDER_LOGO), SANTANDER_LOGO);
  }

  /** ETP-5521: the https scheme check is case-insensitive ({@code HTTPS://} is accepted). */
  @Test
  public void testCreateBankWithUppercaseHttpsLogoIsAccepted() throws Exception {
    String logo = "HTTPS://" + TRUSTED_LOGO_HOST + "/logo.png";
    assertUpsertedWithLogo(bankBodyWithLogo(logo), logo);
  }

  /** ETP-5521: a logo of exactly 255 chars is still accepted (the limit is inclusive). */
  @Test
  public void testCreateBankWithLogoAtMaxLengthIsAccepted() throws Exception {
    String logo = logoOfLength(MAX_LOGO_LENGTH);
    assertUpsertedWithLogo(bankBodyWithLogo(logo), logo);
  }

  /** ETP-5521: surrounding whitespace is trimmed; the trimmed URL reaches the upsert. */
  @Test
  public void testCreateBankWithPaddedLogoPassesTrimmedValue() throws Exception {
    String trimmed = "https://" + TRUSTED_LOGO_HOST + "/x.svg";
    assertUpsertedWithLogo(bankBodyWithLogo("  " + trimmed + "  "), trimmed);
  }

  /**
   * ETP-5521 QA: the host match is case-insensitive (DNS hosts are), so an upper-case host
   * passes.
   */
  @Test
  public void testCreateBankWithMixedCaseHostLogoIsAccepted() throws Exception {
    String logo = "https://D1UUJ3MI6RZWPM.cloudfront.net/x.svg";
    assertUpsertedWithLogo(bankBodyWithLogo(logo), logo);
  }

  /** ETP-5521 QA: an explicit default port ({@code :443}) on the trusted host is accepted. */
  @Test
  public void testCreateBankWithExplicitDefaultPortLogoIsAccepted() throws Exception {
    String logo = "https://" + TRUSTED_LOGO_HOST + ":443/x.svg";
    assertUpsertedWithLogo(bankBodyWithLogo(logo), logo);
  }

  /**
   * ETP-5521 QA: the length limit is checked after trimming — a 255-char URL wrapped in
   * whitespace (raw length above 255) is still accepted, trimmed.
   */
  @Test
  public void testCreateBankWithPaddedLogoAtMaxLengthIsAccepted() throws Exception {
    String logo = logoOfLength(MAX_LOGO_LENGTH);
    assertUpsertedWithLogo(bankBodyWithLogo("   " + logo + "   "), logo);
  }

  /** ETP-5521: a plain {@code http://} logo on the trusted host is rejected — https only. */
  @Test
  public void testCreateBankWithHttpLogoSanitizesToNull() throws Exception {
    assertLogoRejected("http://" + TRUSTED_LOGO_HOST + "/logo.png");
  }

  /** ETP-5521: a {@code javascript:} URL is never persisted as a logo. */
  @Test
  public void testCreateBankWithJavascriptLogoSanitizesToNull() throws Exception {
    assertLogoRejected("javascript:alert(1)");
  }

  /** ETP-5521: a logo longer than 255 chars (the column size) is sanitized to null. */
  @Test
  public void testCreateBankWithOverLongLogoSanitizesToNull() throws Exception {
    assertLogoRejected(logoOfLength(MAX_LOGO_LENGTH + 1));
  }

  /** ETP-5521: a blank logo is sanitized to null. */
  @Test
  public void testCreateBankWithBlankLogoSanitizesToNull() throws Exception {
    assertLogoRejected("   ");
  }

  /** ETP-5521: an https logo on a host outside the Salt Edge CDN allowlist is dropped. */
  @Test
  public void testCreateBankWithNonAllowlistedHostSanitizesToNull() throws Exception {
    assertLogoRejected("https://cdn.example.com/logo.png");
  }

  /** ETP-5521: the user-info trick ({@code trusted.host@evil.tld}) resolves to evil.tld → null. */
  @Test
  public void testCreateBankWithUserInfoLogoSanitizesToNull() throws Exception {
    assertLogoRejected("https://" + TRUSTED_LOGO_HOST + "@evil.tld/x.png");
  }

  /** ETP-5521: a look-alike host that merely starts with the trusted one is dropped. */
  @Test
  public void testCreateBankWithLookAlikeHostSanitizesToNull() throws Exception {
    assertLogoRejected("https://" + TRUSTED_LOGO_HOST + ".evil.tld/x.png");
  }

  /** ETP-5521: a non-default port on the trusted host is dropped. */
  @Test
  public void testCreateBankWithNonDefaultPortSanitizesToNull() throws Exception {
    assertLogoRejected("https://" + TRUSTED_LOGO_HOST + ":8443/x.png");
  }

  /**
   * ETP-5521 QA: host variants that are not exactly the allowlisted host are all dropped —
   * trailing-dot FQDN, IPv4 and IPv6 literals, scheme-relative, empty host, a backslash
   * authority trick and an unparsable URL.
   */
  @Test
  public void testCreateBankWithHostVariantsSanitizesToNull() throws Exception {
    String[] rejected = {
        "https://" + TRUSTED_LOGO_HOST + "./x.svg",
        "https://13.224.0.1/x.svg",
        "https://[::1]/x.svg",
        "//" + TRUSTED_LOGO_HOST + "/x.svg",
        "https:///x.svg",
        "https://" + TRUSTED_LOGO_HOST + "\\@evil.tld/x.svg",
        "https://" + TRUSTED_LOGO_HOST + "/a b.svg",
    };
    for (String raw : rejected) {
      assertLogoRejected(raw);
    }
  }

  /**
   * ETP-5521 QA: a non-string {@code providerLogoUrl} (number, boolean, object, array, JSON null)
   * never reaches the upsert and never fails the create.
   */
  @Test
  public void testCreateBankWithNonStringLogoSanitizesToNull() throws Exception {
    Object[] rejected = {
        42, Boolean.TRUE, new JSONObject().put("href", SANTANDER_LOGO),
        new JSONArray().put(SANTANDER_LOGO), JSONObject.NULL,
    };
    for (Object raw : rejected) {
      JSONObject body = validCreateBody().put(PROVIDER_CODE, SANTANDER_CODE)
          .put(PROVIDER_NAME, SANTANDER_NAME)
          .put(PROVIDER_LOGO_URL, raw);
      assertUpsertedWithLogo(body, null);
    }
    verify(enricher, never()).findExistingProvider(anyString());
  }
}
