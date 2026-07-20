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
package com.etendoerp.go.schemaforge.selector.policy;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * Restricts every {@code Currency} TableDir selector (e.g. the financial account creation form's
 * currency picker) to the currencies the simplified UI supports: Euro, US Dollar and Pound
 * Sterling.
 *
 * <p>{@code C_Currency_ID} columns are plain TableDir references with no OBUISEL selector and no
 * reference search key, so neither the dictionary {@code HQLWhereClause} hook nor
 * {@link ReferenceOverrideSelectorPolicy} ever fires for them. This context policy is the only
 * server-side hook that reaches a TableDir target, keyed by the resolved target entity name
 * ({@code Currency}) rather than the source column.</p>
 *
 * <p>Unlike context-dependent policies (e.g. {@link FinancialAccountPaymentMethodSelectorPolicy}),
 * this filter is unconditional: it does not read {@code contextParams} and applies to every
 * Currency selector regardless of caller.</p>
 */
public final class CurrencyIsoAllowlistSelectorPolicy implements SelectorContextPolicy {

  private static final String ENTITY_CURRENCY = "Currency";
  private static final String ALLOWED_ISO_FILTER = ".iSOCode in ('EUR', 'USD', 'GBP')";

  public CurrencyIsoAllowlistSelectorPolicy() {
    // Stateless policy; public constructor supports registry composition without CDI.
  }

  @Override
  public boolean supports(String entityName) {
    return ENTITY_CURRENCY.equals(entityName);
  }

  @Override
  public String resolveFilter(String entityName, Map<String, String> contextParams, String alias) {
    if (!ENTITY_CURRENCY.equals(entityName)) {
      return null;
    }
    String effectiveAlias = StringUtils.isNotBlank(alias) ? alias : "e";
    return effectiveAlias + ALLOWED_ISO_FILTER;
  }
}
