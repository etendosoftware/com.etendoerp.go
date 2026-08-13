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
 * Filters the Financial Account selector by the selected Payment Method, mirroring the Etendo
 * Classic validation rules on the Business Partner {@code Account} / {@code PO Financial Account}
 * columns.
 *
 * <p>Classic enforces this through an SQL validation rule
 * ({@code Fin_Financial_Account_ID IN (SELECT Fin_Financial_Account_ID FROM Fin_Finacc_Paymentmethod
 * WHERE Fin_Paymentmethod_ID = @Fin_Paymentmethod_ID@)}). That subquery over the M:N link table
 * cannot be reliably translated to HQL by the generic validation-rule fallback, so the equivalent
 * filter is expressed here as an explicit HQL {@code EXISTS} over
 * {@code FinancialMgmtFinAccPaymentMethod}. The payment method id is bound as the named parameter
 * {@code :finAccPaymentMethodId} in {@code NeoSelectorService#executeSelectorQuery}.</p>
 */
public final class FinancialAccountPaymentMethodSelectorPolicy implements SelectorContextPolicy {

  private static final String ENTITY_FINANCIAL_ACCOUNT = "FIN_Financial_Account";
  private static final String PARAM_CUSTOMER_PAYMENT_METHOD = "Fin_Paymentmethod_ID";
  private static final String PARAM_VENDOR_PAYMENT_METHOD = "PO_Paymentmethod_ID";
  private static final String ID_PATTERN = "[A-Za-z0-9\\-]+";

  public FinancialAccountPaymentMethodSelectorPolicy() {
    // Stateless policy; public constructor supports registry composition without CDI.
  }

  @Override
  public boolean supports(String entityName) {
    return ENTITY_FINANCIAL_ACCOUNT.equals(entityName);
  }

  @Override
  public String resolveFilter(String entityName, Map<String, String> contextParams, String alias) {
    if (contextParams == null || contextParams.isEmpty()
        || !ENTITY_FINANCIAL_ACCOUNT.equals(entityName)) {
      return null;
    }
    // Customer side sends Fin_Paymentmethod_ID, vendor side sends PO_Paymentmethod_ID.
    // Only one is present per request; prefer the customer key when both appear.
    String paymentMethodId = resolveParam(contextParams, PARAM_CUSTOMER_PAYMENT_METHOD);
    if (paymentMethodId == null) {
      paymentMethodId = resolveParam(contextParams, PARAM_VENDOR_PAYMENT_METHOD);
    }
    if (paymentMethodId == null || !paymentMethodId.matches(ID_PATTERN)) {
      return null;
    }
    String effectiveAlias = StringUtils.isNotBlank(alias) ? alias : "e";
    return "EXISTS (SELECT 1 FROM FinancialMgmtFinAccPaymentMethod fapm"
        + " WHERE fapm.account.id = " + effectiveAlias + ".id"
        + " AND fapm.paymentMethod.id = :finAccPaymentMethodId"
        + " AND fapm.active = true)";
  }

  private static String resolveParam(Map<String, String> contextParams, String key) {
    String value = contextParams.get(key);
    if (StringUtils.isBlank(value)) {
      value = contextParams.get(key.toLowerCase());
    }
    if (StringUtils.isBlank(value)) {
      value = contextParams.get(key.toUpperCase());
    }
    return StringUtils.trimToNull(value);
  }
}
