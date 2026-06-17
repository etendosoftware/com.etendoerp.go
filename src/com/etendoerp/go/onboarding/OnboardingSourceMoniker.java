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
package com.etendoerp.go.onboarding;

/**
 * Single source of truth for the template-client monikers baked into the GOClient sample data names
 * ("Esquema GO", "Arbol de cuentas GO", "GOClient Calendar", "GOOrg Calendar", ...).
 *
 * <p>Onboarding replaces these monikers with the new tenant's client name so every imported artifact
 * (accounting schema, account elements, calendar, ...) is branded with the tenant instead of "GO",
 * mirroring the R1/R3 data-fixes. The list is shared so the accounting wiring (Gap A1) and the
 * period-control wiring (Gap C1) can never drift apart.
 */
final class OnboardingSourceMoniker {

  /**
   * Source monikers in match order. The longer moniker is listed first so it is matched before its
   * "GO" prefix (e.g. "GOOrg" is not partially replaced into "&lt;client&gt;Org").
   */
  private static final String[] MONIKERS = { "GOOrg", "GO" };

  private OnboardingSourceMoniker() {
  }

  /**
   * Replaces every source moniker in {@code value} with the tenant's client name. Returns
   * {@code value} unchanged when it is null or empty.
   */
  static String replace(String value, String clientName) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    String result = value;
    for (String moniker : MONIKERS) {
      result = result.replace(moniker, clientName);
    }
    return result;
  }
}
