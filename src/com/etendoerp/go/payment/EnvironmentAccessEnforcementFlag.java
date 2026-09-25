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

package com.etendoerp.go.payment;

import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * ETP-5047 — the one gate of the environment-access kill switch (flag
 * {@value GoFeatureFlags#FLAG_ENVIRONMENT_ACCESS_ENFORCEMENT_OFF}).
 *
 * <p>Only {@link EnvironmentAccessGuard} calls {@link #isEnforcementSwitchedOff(String)}, so every
 * entry point that refuses a blocked tenant — NEO, MCP, the {@code JwtAuthUtils} servlets, the
 * legacy environment login — obeys the same switch. Retiring the flag is a grep for this class.
 *
 * <p><b>Enforcement is the default.</b> {@link GoFeatureFlags#isEnabled} answers {@code false}
 * for an unconfigured flag, an unreachable control plane and any evaluation error, and
 * {@code false} here means "not switched off". Only a value that resolves to {@code true} stops
 * the refusal — locally {@code true}, {@code Y}, {@code yes} or {@code 1} (case insensitive,
 * trimmed); see {@link GoFeatureFlags#FLAG_ENVIRONMENT_ACCESS_ENFORCEMENT_OFF}.
 */
public final class EnvironmentAccessEnforcementFlag {

  private EnvironmentAccessEnforcementFlag() {
  }

  /**
   * @param clientId the tenant whose access is being decided, published as
   *     {@link FeatureFlagContext#ATTRIBUTE_CLIENT_ID} so a targeting rule can name one tenant;
   *     may be null
   * @return {@code true} only when the control plane positively switches enforcement off
   */
  public static boolean isEnforcementSwitchedOff(String clientId) {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_ENVIRONMENT_ACCESS_ENFORCEMENT_OFF,
        FeatureFlagContext.forAccount(null).with(FeatureFlagContext.ATTRIBUTE_CLIENT_ID, clientId));
  }
}
