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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/** Entity-specific selector filters derived from validated context parameters. */
public final class ContextParamSelectorPolicy {

  private static final String ENTITY_BUSINESS_PARTNER = "BusinessPartner";
  private static final String ENTITY_PRODUCT_BY_PRICE_AND_WAREHOUSE =
      "ProductByPriceAndWarehouse";

  private ContextParamSelectorPolicy() {
  }

  public static String resolveFilter(String entityName, Map<String, String> contextParams, String alias) {
    if (contextParams == null || contextParams.isEmpty() || entityName == null) {
      return null;
    }
    String effectiveAlias = StringUtils.isNotBlank(alias) ? alias : "e";
    if (ENTITY_BUSINESS_PARTNER.equals(entityName)) {
      return resolveBusinessPartnerFilter(contextParams, effectiveAlias);
    }
    if (ENTITY_PRODUCT_BY_PRICE_AND_WAREHOUSE.equals(entityName)) {
      return resolveProductByPriceFilter(contextParams, effectiveAlias);
    }
    return null;
  }

  private static String resolveBusinessPartnerFilter(Map<String, String> contextParams,
      String alias) {
    List<String> conditions = new ArrayList<>();
    addBooleanEqualsCondition(conditions, alias + ".customer", contextParams.get("isCustomer"));
    addBooleanEqualsCondition(conditions, alias + ".vendor", contextParams.get("isVendor"));
    return conditions.isEmpty() ? null : String.join(" AND ", conditions);
  }

  private static String resolveProductByPriceFilter(Map<String, String> contextParams,
      String alias) {
    String priceListId = contextParams.get("priceList");
    if (StringUtils.isNotBlank(priceListId) && priceListId.matches("[A-Za-z0-9\\-]+")) {
      return alias + ".productPrice.priceListVersion.priceList.id = '" + priceListId + "'";
    }
    String isSOTrx = contextParams.get("isSOTrx");
    if ("Y".equalsIgnoreCase(isSOTrx)) {
      return alias + ".productPrice.priceListVersion.priceList.salesPriceList = true";
    }
    if ("N".equalsIgnoreCase(isSOTrx)) {
      return alias + ".productPrice.priceListVersion.priceList.salesPriceList = false";
    }
    return null;
  }

  private static void addBooleanEqualsCondition(List<String> conditions, String fieldExpr,
      String flag) {
    if ("Y".equalsIgnoreCase(flag)) {
      conditions.add(fieldExpr + " = true");
    } else if ("N".equalsIgnoreCase(flag)) {
      conditions.add(fieldExpr + " = false");
    }
  }
}
