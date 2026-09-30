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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.ProviderCatalogUtils;

/**
 * Salt Edge provider enrichment of an offline financial-account create (ETP-5521), extracted from
 * {@link FinancialAccountHandler} so the handler stays under Sonar's method-count ceiling.
 *
 * <p>When the create body carries the provider picked in the New Account wizard's bank picker, the
 * provider is upserted into the {@code PSD2_PROVIDER} catalog and its id is injected under the
 * {@code psd2Provider} FK property, so the generic CRUD links it — the same mechanism used for
 * {@code country}. The account stays offline; the FK is metadata a later bank connect uses to
 * preselect that bank.
 *
 * <p>An instance (not a static helper) so {@link #findExistingProvider} is a plain seam a unit test
 * can stub on a spy, without static mocking.
 */
final class FinancialAccountProviderEnricher {

  private static final Logger log = LogManager.getLogger(FinancialAccountProviderEnricher.class);

  /** Salt Edge provider chosen at offline creation (optional); persisted so a later bank connect
   *  can preselect that bank. {@link #FIELD_PSD2_PROVIDER} is the DAL FK property the generic CRUD
   *  resolves by id. */
  private static final String FIELD_PROVIDER_CODE = "providerCode";
  private static final String FIELD_PROVIDER_NAME = "providerName";
  private static final String FIELD_PSD2_PROVIDER = "psd2Provider";
  /** Size of {@code PSD2_PROVIDER.LOGO_URL}; a longer logo URL is dropped, never truncated. */
  private static final int MAX_PROVIDER_LOGO_URL_LENGTH = 255;
  private static final String HTTPS_SCHEME = "https";
  private static final int HTTPS_DEFAULT_PORT = 443;
  /** Hosts a client-supplied provider logo may point at: the Salt Edge logo CDN, as observed in
   *  every stored {@code PSD2_PROVIDER.LOGO_URL} ({@code /logos/providers/<cc>/<code>.svg}). A
   *  logo on any other host is dropped — {@code PSD2_PROVIDER} is shared across tenants, so an
   *  arbitrary host would let one tenant plant an image (or tracking pixel) for everyone. */
  private static final Set<String> TRUSTED_PROVIDER_LOGO_HOSTS =
      Set.of("d1uuj3mi6rzwpm.cloudfront.net");

  /**
   * When the offline create carries a Salt Edge provider (bank and card accounts —
   * {@link #supportsProvider}; cash ignores it), upsert the provider record and inject its id
   * under the {@code psd2Provider} FK property.
   *
   * <p>The optional {@code providerLogoUrl} (ETP-5521) is a client-supplied value written to the
   * {@code PSD2_PROVIDER} catalog, which is shared across tenants by provider code. It is therefore
   * trusted only to <em>fill a missing logo</em>: it must pass {@link #sanitizeProviderLogoUrl}
   * (https on a Salt Edge CDN host, at most 255 chars) and it is passed to the upsert only when
   * the provider does not exist yet or has no stored logo ({@link #fillOnlyLogo}). It never
   * replaces an existing logo — refreshing logos is {@code SyncBankProviders}' job. An unusable
   * logo is dropped, never a reason to fail the create.
   *
   * <p>The transient {@code providerCode}/{@code providerName}/{@code providerLogoUrl} keys are
   * always removed (also for cash accounts or without a provider code) so they are not treated
   * as entity properties.
   *
   * @param body
   *     the create request body, mutated in place
   * @param type
   *     the already-normalized account type ({@code B}, {@code C} or {@code CA})
   */
  void enrichProvider(JSONObject body, String type) throws JSONException {
    String providerCode = body.optString(FIELD_PROVIDER_CODE, "").trim();
    if (supportsProvider(type) && StringUtils.isNotBlank(providerCode)) {
      String providerName = body.optString(FIELD_PROVIDER_NAME, providerCode).trim();
      String logoUrl = fillOnlyLogo(providerCode,
          sanitizeProviderLogoUrl(
              body.optString(FinancialAccountHandler.FIELD_PROVIDER_LOGO_URL, null)));
      Provider provider = ProviderCatalogUtils.upsertProvider(providerCode, providerName, null,
          logoUrl);
      OBDal.getInstance().flush();
      body.put(FIELD_PSD2_PROVIDER, provider.getId());
    }
    stripTransientProviderKeys(body);
  }

  /** DAL seam (stubbed in unit tests): the catalog row for {@code providerCode}, or null. */
  Provider findExistingProvider(String providerCode) {
    return FinancialAccountBankConnectionSupport.findProviderByCode(providerCode);
  }

  /**
   * Removes the create-only provider keys so the generic CRUD never sees them as properties. Also
   * called on update, where the keys are ignored entirely.
   */
  static void stripTransientProviderKeys(JSONObject body) {
    body.remove(FIELD_PROVIDER_CODE);
    body.remove(FIELD_PROVIDER_NAME);
    body.remove(FinancialAccountHandler.FIELD_PROVIDER_LOGO_URL);
  }

  /**
   * Bank and card accounts (ETP-5521) both go through the wizard's bank picker and can remember
   * their Salt Edge provider; cash accounts have no bank, so they never link one.
   */
  private static boolean supportsProvider(String type) {
    return FinancialAccountHandler.TYPE_BANK.equals(type)
        || FinancialAccountHandler.TYPE_CARD.equals(type);
  }

  /**
   * Returns {@code logoUrl} only when it may fill the catalog row: the provider is not registered
   * yet, or its stored logo is blank. Otherwise {@code null}, which the upsert reads as "leave the
   * stored logo untouched". Skips the lookup entirely when there is no usable logo.
   */
  private String fillOnlyLogo(String providerCode, String logoUrl) {
    if (logoUrl == null) {
      return null;
    }
    Provider existing = findExistingProvider(providerCode);
    return existing == null || StringUtils.isBlank(existing.getLogoURL()) ? logoUrl : null;
  }

  /**
   * Accepts a provider logo URL only when it is non-blank, at most 255 chars (the size of
   * {@code PSD2_PROVIDER.LOGO_URL}) and a trusted Salt Edge CDN URL
   * ({@link #isTrustedLogoUri}). Anything else yields {@code null}; a rejected non-blank logo is
   * logged at debug level by its host only, never the full URL.
   *
   * @param raw
   *     the logo URL sent by the client, possibly {@code null}
   * @return the trimmed URL when acceptable, otherwise {@code null}
   */
  private static String sanitizeProviderLogoUrl(String raw) {
    String logoUrl = StringUtils.trimToNull(raw);
    if (logoUrl == null) {
      return null;
    }
    if (logoUrl.length() <= MAX_PROVIDER_LOGO_URL_LENGTH && isTrustedLogoUri(logoUrl)) {
      return logoUrl;
    }
    log.debug("Dropped client-supplied provider logo (ETP-5521); host: {}", logoHostForLog(logoUrl));
    return null;
  }

  /** The host of {@code url} for a log line — {@code "none"} or {@code "unparsable"} otherwise. */
  private static String logoHostForLog(String url) {
    try {
      String host = new URI(url).getHost();
      return host != null ? host : "none";
    } catch (URISyntaxException e) {
      return "unparsable";
    }
  }

  /**
   * Parses {@code url} with {@link URI} (never string prefix checks) and accepts it only when the
   * scheme is https (case-insensitive), there is no user-info (rejects
   * {@code https://trusted.host@evil.tld}), the port is the default one and the host is in
   * {@link #TRUSTED_PROVIDER_LOGO_HOSTS}. A malformed URL is rejected.
   */
  private static boolean isTrustedLogoUri(String url) {
    try {
      URI uri = new URI(url);
      String host = uri.getHost();
      int port = uri.getPort();
      return HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
          && (port == -1 || port == HTTPS_DEFAULT_PORT) && host != null
          && TRUSTED_PROVIDER_LOGO_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    } catch (URISyntaxException e) {
      return false;
    }
  }
}
