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

import java.util.HashMap;
import java.util.Map;

/** Reference-id based selector filter overrides. */
final class ReferenceOverrideSelectorPolicy {

  private static final Map<String, String> REFERENCE_OVERRIDE_FILTERS;

  static {
    Map<String, String> overrides = new HashMap<>();
    overrides.put("166", "e.salesPriceList = true");
    overrides.put("800031", "e.salesPriceList = false");
    overrides.put("EED0EF97D4A7421687F3B365D009E7A6",
        "exists (select 1 from FinancialMgmtFinAccPaymentMethod fapm"
            + " where fapm.paymentMethod = e and fapm.active = true)");
    overrides.put("DF1CEA94B3564A33AFDB37C07E1CE353",
        "exists (select 1 from FinancialMgmtFinAccPaymentMethod fapm"
            + " where fapm.account = e and fapm.active = true)");
    REFERENCE_OVERRIDE_FILTERS = java.util.Collections.unmodifiableMap(overrides);
  }

  private ReferenceOverrideSelectorPolicy() {
  }

  static String resolveFilter(String referenceSearchKeyId) {
    if (referenceSearchKeyId == null) {
      return null;
    }
    return REFERENCE_OVERRIDE_FILTERS.get(referenceSearchKeyId);
  }
}
