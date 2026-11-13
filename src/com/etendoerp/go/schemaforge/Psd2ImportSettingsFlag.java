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

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * ETP-6151 — the one gate of the {@value GoFeatureFlags#FLAG_PSD2_STATEMENT_IMPORT_SETTINGS} flag.
 * Retiring the flag is a grep for this class. It is the <b>only</b> reader of the flag in the
 * backend: never evaluate the key anywhere else.
 *
 * <p><b>Per account, per request.</b> The flag is evaluated for the {@code ETGO_ACCOUNT} email of
 * the request user, resolved exactly as {@code PortalLinkPolicy} resolves it
 * ({@link GoAccountResolver#currentAccountEmail()}: admin mode, {@code null} on any failure). No
 * account, no email or a failed lookup reads <b>off</b>, never on. The answer is never cached in a
 * static: a targeting rule changed on the hosted control plane applies to the next request.
 *
 * <p><b>Toggle points</b> (each action entry evaluates it ONCE and hands the boolean down):
 * {@code FinancialAccountBankConnectionHandler} {@code link}, {@code createAndLink},
 * {@code import-settings} and {@code accounts}; {@code FinancialAccountHandler}
 * {@code validateAndEnrichUpdate}; {@code BankStatementsHandler.handleDelete}
 * ({@link BankStatementDeletion}). With the flag off every one of them answers as before ETP-6151.
 */
public final class Psd2ImportSettingsFlag {

  /** Utility class: static members only, never instantiated. */
  private Psd2ImportSettingsFlag() {
  }

  /**
   * Whether the PSD2 statement import settings behaviour is on for the account of the user making
   * the request. Never throws: an unconfigured flag, an unreachable control plane and any
   * evaluation error read {@code false}.
   *
   * @return {@code true} only when the flag positively resolves true for that account
   */
  public static boolean isEnabled() {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_PSD2_STATEMENT_IMPORT_SETTINGS,
        FeatureFlagContext.forAccount(GoAccountResolver.currentAccountEmail()));
  }
}
