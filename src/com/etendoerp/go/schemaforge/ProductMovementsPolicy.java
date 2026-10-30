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

import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * The single gate of the Product window's "Movements" tab (ETP-5889): is it switched on for the
 * account of the user making the request.
 *
 * <p><b>One flag, one evaluator.</b> It evaluates {@link GoFeatureFlags#FLAG_PRODUCT_MOVEMENTS_TAB}
 * with the account email as targeting key, so a rule on the hosted control plane (Datadog Feature
 * Flags, ConfigCat as the fallback) names the accounts that get the tab. The browser never evaluates
 * the flag: it reads the answer as {@code capabilities.productMovements} off
 * {@code SFWindowAccessMap}, and the {@link ProductTransactionsHandler} reads it here to decide
 * whether to do the extra work that tab needs.
 *
 * <p><b>A request with no account behind it is evaluated account-less</b> (no targeting key, no email):
 * it matches no per-account rule and gets the flag's default value, which is off unless the flag is on
 * for everyone (the hosted default {@code true}, or the local {@code ETGO_FLAG_PRODUCT_MOVEMENTS_TAB}
 * property). Any failure resolving the account or the tenant is left out of the context; it never fails
 * the request. The tenant ({@link FeatureFlagContext#ATTRIBUTE_CLIENT_ID}) is published so a rule can
 * also name a tenant: this backend is the only evaluator, so such a rule needs no counterpart in the
 * browser.
 *
 * <p>Only behaviour is gated. The computed columns of {@code M_Transaction}, their triggers and the NEO
 * configuration ship unconditionally.
 */
public final class ProductMovementsPolicy {

  private ProductMovementsPolicy() {
  }

  /**
   * Evaluates the gate for the request being served: the {@code ETGO_ACCOUNT} of the user bound to the
   * current {@link OBContext} ({@link GoAccountResolver#currentAccountEmail()}, the resolver every
   * per-account flag shares) and the current tenant. Used where the request itself is the account, such
   * as the {@link ProductTransactionsHandler}.
   *
   * @return {@code true} only when the flag positively resolves true for that account
   */
  public static boolean isEnabled() {
    return isEnabledFor(GoAccountResolver.currentAccountEmail());
  }

  /**
   * Evaluates the gate for an account the caller has already resolved, so it is not resolved twice:
   * {@code SFWindowAccessMap} resolves it once, before admin mode, for every capability it publishes.
   *
   * @param accountEmail the {@code ETGO_ACCOUNT} email the flag is targeted on; blank means no
   *     account, which no per-account rule matches
   * @return {@code true} only when the flag positively resolves true for that account
   */
  public static boolean isEnabledFor(String accountEmail) {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_PRODUCT_MOVEMENTS_TAB,
        FeatureFlagContext.forAccount(accountEmail)
            .with(FeatureFlagContext.ATTRIBUTE_CLIENT_ID, currentClientId()));
  }

  /** The tenant of the current {@link OBContext}, or {@code null} when there is none. */
  private static String currentClientId() {
    try {
      OBContext context = OBContext.getOBContext();
      return context == null || context.getCurrentClient() == null
          ? null
          : context.getCurrentClient().getId();
    } catch (RuntimeException e) {
      return null; // targeting enrichment must never fail the request
    }
  }
}
