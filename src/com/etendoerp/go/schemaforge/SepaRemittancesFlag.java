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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.List;

import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * ETP-6148 — the one gate of the {@value GoFeatureFlags#FLAG_SEPA_REMITTANCES} flag. Retiring the
 * flag is a grep for this class. It is the <b>only</b> reader of the flag: never evaluate the key
 * anywhere else.
 *
 * <p><b>Evaluate at request entry.</b> {@link #isEnabled()} resolves the account from the
 * {@code OBContext} user, which exists only while the request runs as the account owner. The DAL
 * observers that maintain the remittance columns never read the flag: they cannot see a per-account
 * flag, and they only act on remittance data that exists where the flag was on.
 *
 * <p><b>Off is the default.</b> {@link GoFeatureFlags#isEnabled} answers {@code false} for an
 * unconfigured flag, an unreachable control plane and any evaluation error; no method throws.
 *
 * <p>The flag gates behaviour, not schema: the {@code EM_ETGO_*} columns of the financial account,
 * the payment method and the remittance tables, their lists and messages ship unconditionally.
 */
public final class SepaRemittancesFlag {

  /** The {@code error.code} of the refusal a gated endpoint answers while the flag is off. */
  static final String CODE_FEATURE_DISABLED = "feature_disabled";

  /** The {@code AD_MESSAGE} of that refusal. */
  static final String MSG_DISABLED = "ETGO_SepaRemittancesDisabled";

  private SepaRemittancesFlag() {
  }

  /**
   * The refusal every gated endpoint answers while the flag is off: {@code 403} with
   * {@code error.code = feature_disabled} and {@code messageKeys = [ETGO_SepaRemittancesDisabled]},
   * the same envelope as the invoice inbox's.
   *
   * @return the 403 response
   */
  static NeoResponse featureDisabled() {
    return NeoErrorResponses.toResponse(403, CODE_FEATURE_DISABLED,
        NeoMessageText.text(MSG_DISABLED), List.of(MSG_DISABLED));
  }

  /**
   * Whether SEPA remittances are enabled for the current request user and tenant. Targeted on the
   * {@code ETGO_ACCOUNT} email resolved by {@link GoAccountResolver#currentAccountEmail()} plus the
   * tenant of the current {@link OBContext}. Call it at request entry only.
   *
   * @return {@code true} only when the flag resolves to on; {@code false} otherwise
   */
  public static boolean isEnabled() {
    return isEnabled(GoAccountResolver.currentAccountEmail(), currentClientId());
  }

  /**
   * Whether SEPA remittances are enabled for an explicit account and tenant, for callers that do
   * not run as the account owner.
   *
   * @param accountEmail the account email captured at request entry; blank is omitted, so an email
   *     rule does not match and the default stays off
   * @param clientId the tenant, published as {@link FeatureFlagContext#ATTRIBUTE_CLIENT_ID};
   *     blank is omitted
   * @return {@code true} only when the flag resolves to on; {@code false} otherwise
   */
  public static boolean isEnabled(String accountEmail, String clientId) {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_SEPA_REMITTANCES,
        FeatureFlagContext.forAccount(accountEmail)
            .with(FeatureFlagContext.ATTRIBUTE_CLIENT_ID, clientId));
  }

  /**
   * Whether SEPA remittances are enabled for an account the caller has already resolved, on the
   * current request's tenant. Used by {@code SFWindowAccessMap}, which resolves the account once for
   * every capability it publishes and republishes this answer as
   * {@code capabilities.sepaRemittances}, so the browser never evaluates the flag itself.
   *
   * @param accountEmail the {@code ETGO_ACCOUNT} email the flag is targeted on; blank means no
   *     account, which no per-account rule matches
   * @return {@code true} only when the flag resolves to on; {@code false} otherwise
   */
  public static boolean isEnabledFor(String accountEmail) {
    return isEnabled(accountEmail, currentClientId());
  }

  /** The tenant of the current {@link OBContext}, or {@code null} when there is none. */
  private static String currentClientId() {
    try {
      OBContext obContext = OBContext.getOBContext();
      return obContext == null || obContext.getCurrentClient() == null
          ? null
          : obContext.getCurrentClient().getId();
    } catch (RuntimeException e) {
      return null; // targeting enrichment must never fail the request
    }
  }
}
