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

import java.util.Map;

import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Selector policy facade for business-specific selector behavior.
 */
final class NeoSelectorPolicy {

  private NeoSelectorPolicy() {
  }

  static String resolveReferenceOverrideFilter(String referenceSearchKeyId) {
    return ReferenceOverrideSelectorPolicy.resolveFilter(referenceSearchKeyId);
  }

  static String resolveContextParamFilter(String entityName,
      Map<String, String> contextParams, String alias) {
    return ContextParamSelectorPolicy.resolveFilter(entityName, contextParams, alias);
  }

  static Column resolveVirtualSelectorColumn(SFEntity entity, String columnName) {
    return AddressVirtualSelectorPolicy.resolveVirtualSelectorColumn(entity, columnName);
  }

  static NeoResponse enrichProductSelectorWithPrices(NeoResponse response, String priceListId) {
    return ProductPriceSelectorPolicy.enrichProductSelectorWithPrices(response, priceListId);
  }
}
