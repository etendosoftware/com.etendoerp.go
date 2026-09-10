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

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyException;
import org.openbravo.erpCommon.utility.PropertyNotFoundException;

import com.etendoerp.go.common.PublicUrlResolver;

/**
 * The single gate that decides whether a sales-invoice email carries a portal link, and the builder
 * of the link itself.
 *
 * <p><b>The gate does not protect the portal.</b> The {@code /portal/:token} route, the three
 * {@code /sws/portal/*} endpoints, the table and the revoke action all ship unconditionally — what
 * protects them is the token (plan §5). The gate decides one thing only: whether an invoice email
 * carries the link. See plan §2.5.
 *
 * <p><b>One gate, and it belongs to the sender.</b> {@value #PREFERENCE_ATTRIBUTE} in
 * {@code AD_Preference} is the whole condition: the capability itself is always on, and the only
 * question asked per send is whether <em>this sender</em> is configured to emit portal links. There
 * is no environment-level condition on top of it, so nothing has to be configured twice.
 *
 * <p><b>It is deliberately not a feature flag.</b> A permanent per-sender capability was never a
 * flag's job, and the flag machinery could not express it anyway:
 * {@code PropertiesFeatureProvider} ignores the evaluation context — it serves environment-level
 * rollout, not per-user targeting — and per-user flag targeting needs the hosted control plane,
 * which is blocked by the still-open {@code targeting-key-divergence} precondition. Per-tenant
 * control comes free from the same mechanism: Openbravo resolves preferences most-specific-first
 * (user → role → org → client → system), so the preference set at <b>client</b> level enables every
 * sender in the tenant and at <b>user</b> level enables one person.
 */
public final class PortalLinkPolicy {

  /**
   * {@code AD_Preference} attribute that opts one sender (or role, org, or whole tenant) into
   * sending portal links. Any value Openbravo reads back as set enables it; absence disables it.
   */
  public static final String PREFERENCE_ATTRIBUTE = "ETGO_BPPortalLinkEnabled";

  /** Path the SPA registers for the public portal route. */
  static final String PORTAL_ROUTE = "portal";

  private static final Logger log = LogManager.getLogger(PortalLinkPolicy.class);

  private PortalLinkPolicy() {
  }

  /**
   * Evaluates the gate.
   *
   * @return {@code true} when the current sender is configured to send portal links
   */
  public static boolean isLinkEnabled() {
    return isEnabledForCurrentSender();
  }

  /**
   * The per-sender {@code AD_Preference}, read from {@link OBContext} exactly as
   * {@code NeoFavoritesService} reads the navigator favourites.
   *
   * <p><b>{@code isListProperty} is {@code false}, and that is load-bearing.</b> It is what makes
   * Openbravo store and resolve the key in {@code AD_Preference.Attribute}. {@code TenantPlanService}
   * documents the inverse mistake as a real failure mode: a key written to {@code Property} instead
   * is never found, and every configured sender would silently read back as not configured.
   *
   * <p><b>Not set ⇒ no link.</b> {@link PropertyNotFoundException} is the normal "not configured"
   * answer, not an error: the capability is opt-in, so an absent preference is the safe default and
   * the reason no second gate is needed to keep links from going out unasked.
   *
   * @return {@code true} when a preference row resolves for the current user/role/org/client
   */
  static boolean isEnabledForCurrentSender() {
    OBContext ctx = OBContext.getOBContext();
    if (ctx == null) {
      return false;
    }
    try {
      String value = Preferences.getPreferenceValue(PREFERENCE_ATTRIBUTE, false,
          ctx.getCurrentClient(), ctx.getCurrentOrganization(),
          ctx.getUser(), ctx.getRole(), null);
      return isAffirmative(value);
    } catch (PropertyNotFoundException e) {
      return false;
    } catch (PropertyException e) {
      // A malformed or ambiguous preference is not a reason to fail an invoice send; it is a reason
      // not to add a link to it.
      log.warn("Could not read {}, treating the portal link as disabled: {}", PREFERENCE_ATTRIBUTE,
          e.getMessage());
      return false;
    }
  }

  /**
   * Builds the customer-facing portal URL for a token.
   *
   * <p>The base URL is the tenant's own already-deployed app shell
   * ({@code etendo.go.app.baseUrl}), which is what makes "portal per tenant" fall out of the
   * existing deployment with no extra mechanism. With no configured app base URL there is no link
   * to build — the request that would need one has no {@code HttpServletRequest} to derive it from,
   * since it originates in the invoice-send path rather than in a browser navigation.
   *
   * @param token the opaque token
   * @return the absolute link, or empty when the token or the configured app base URL is missing
   */
  public static Optional<String> buildLink(String token) {
    String normalizedToken = StringUtils.trimToNull(token);
    if (normalizedToken == null) {
      return Optional.empty();
    }
    String baseUrl = PublicUrlResolver.resolveConfiguredAppBaseUrl();
    if (StringUtils.isBlank(baseUrl)) {
      log.warn("A portal link was requested but {} is not configured; no link will be sent",
          PublicUrlResolver.APP_BASE_URL_PROPERTY);
      return Optional.empty();
    }
    return Optional.ofNullable(
        PublicUrlResolver.appendPath(baseUrl, PORTAL_ROUTE + "/" + normalizedToken));
  }

  /**
   * Reads a preference value as a boolean, accepting every spelling an Etendo operator plausibly
   * types ({@code true}, {@code Y}, {@code yes}, {@code 1}, in any case), so enabling a sender never
   * fails on the wording. A row that exists with an empty value counts as enabled: the operator
   * created it on purpose.
   */
  private static boolean isAffirmative(String value) {
    String normalized = StringUtils.trimToNull(value);
    if (normalized == null) {
      return true;
    }
    return "true".equalsIgnoreCase(normalized) || "y".equalsIgnoreCase(normalized)
        || "yes".equalsIgnoreCase(normalized) || "1".equals(normalized);
  }
}
