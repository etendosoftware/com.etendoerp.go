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
 * ETP-5581 — the one gate of the {@value GoFeatureFlags#FLAG_BANK_BOUNCE_SUGGESTIONS} flag.
 * Retiring the flag is a grep for this class. It is the <b>only</b> reader of the flag: never
 * evaluate the key anywhere else.
 *
 * <p><b>Per account, per request.</b> The flag is evaluated for the {@code ETGO_ACCOUNT} email of
 * the request user, resolved exactly as {@code PortalLinkPolicy} resolves it
 * ({@link GoAccountResolver#currentAccountEmail()}: admin mode, {@code null} on any failure). No
 * account, no email or a failed lookup reads <b>off</b>, never on. The answer is never cached in a
 * static: a targeting rule changed on the hosted control plane applies to the next request.
 *
 * <p><b>Toggle points.</b> {@code ReconciliationHandler.buildAutoMatch} and
 * {@code buildPendingLines} (bounce pairs and the {@code bounce} state),
 * {@code ReconciliationHandler.applySuggestions} ({@code bouncePair} groups) and
 * {@code BankBouncePairService.discardPair}. {@code restorePair} is deliberately not gated.
 */
public final class BankBounceSuggestionsFlag {

  /** Utility class: static members only, never instantiated. */
  private BankBounceSuggestionsFlag() {
  }

  /**
   * Whether bounce-pair suggestions are on for the account of the user making the request. Never
   * throws: an unconfigured flag, an unreachable control plane and any evaluation error read
   * {@code false}.
   *
   * @return {@code true} only when the flag positively resolves true for that account
   */
  public static boolean isEnabled() {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_BANK_BOUNCE_SUGGESTIONS,
        FeatureFlagContext.forAccount(GoAccountResolver.currentAccountEmail()));
  }
}
