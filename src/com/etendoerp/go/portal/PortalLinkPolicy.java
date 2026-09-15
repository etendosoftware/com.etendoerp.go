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
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.schemaforge.data.Account;

/**
 * The single gate that decides whether a sales-invoice email carries a portal link, and the builder
 * of the link itself.
 *
 * <p><b>The gate does not protect the portal.</b> The {@code /portal/:token} route, the three
 * {@code /sws/portal/*} endpoints, the table and the revoke action all ship unconditionally — what
 * protects them is the token (plan §5). The gate decides one thing only: whether an invoice email
 * carries the link. See plan §2.5.
 *
 * <p><b>One gate: the {@link GoFeatureFlags#FLAG_BP_PORTAL_LINK} flag, evaluated for the account
 * sending the invoice.</b> Enabling one person is a ConfigCat targeting rule on that account's
 * email; with nothing configured the answer is {@code false} for everyone. There is no second
 * condition to satisfy, and deliberately so — the feature was briefly built with an environment
 * flag AND a per-sender {@code AD_Preference}, which meant nothing worked until two unrelated
 * things were configured.
 *
 * <p>With no ConfigCat SDK key the flag degrades to a plain per-environment boolean, so a shared
 * environment needs ConfigCat configured before the link is switched on for anyone. A dev box has
 * one user, so the boolean is enough there.
 *
 * <p><b>The identity is the {@code ETGO_ACCOUNT} email, not {@code AD_User.email}.</b> That is not
 * interchangeable: onboarding never writes {@code AD_User.email} — {@code InitialSetupUtility}
 * only writes {@code username} — so targeting on the AD user's email would read {@code null} for
 * essentially every Etendo Go user and the flag would never match anyone. The account is therefore
 * resolved from the AD username through {@link GoAccountResolver}, which is also what handles the
 * {@code <accountEmail>+<clientName>} username a second environment gets. Same resolution
 * {@code NeoSessionService} uses to put the account identity on a session.
 *
 * <p>No account, no email on it, or any failure resolving it ⇒ <b>no link</b>. There is no fallback
 * to "allow": every unknown answers the same way an unlisted account does.
 */
public final class PortalLinkPolicy {

  /** Path the SPA registers for the public portal route. */
  static final String PORTAL_ROUTE = "portal";

  private static final Logger log = LogManager.getLogger(PortalLinkPolicy.class);

  private PortalLinkPolicy() {
  }

  /**
   * Evaluates the gate: is the portal link switched on for the account sending this invoice.
   *
   * @return {@code true} only when the flag positively resolves true for the sending account
   */
  public static boolean isLinkEnabled() {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_BP_PORTAL_LINK,
        FeatureFlagContext.forAccount(currentAccountEmail()));
  }

  /**
   * Resolves the {@code ETGO_ACCOUNT} email of the user sending the invoice, which is the flag's
   * targeting key.
   *
   * <p>Runs in admin mode because {@code ETGO_ACCOUNT} is a platform table the invoice sender's own
   * role has no reason to be able to read, and the account being looked up is the sender's own.
   *
   * @return the account email, or {@code null} when no active account resolves — which the caller
   *     turns into "no link", never into "allow"
   */
  private static String currentAccountEmail() {
    try {
      OBContext.setAdminMode(true);
      OBContext ctx = OBContext.getOBContext();
      User user = ctx == null ? null : ctx.getUser();
      if (user == null) {
        return null;
      }
      return GoAccountResolver.findAccountByUsername(user.getUsername())
          .map(Account::getEmail)
          .orElse(null);
    } catch (RuntimeException e) {
      // Resolving the sender must never fail an invoice send; it is only a reason not to add a link.
      log.warn("Could not resolve the sending account, treating the portal link as disabled: {}",
          e.getMessage(), e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
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
}
