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

import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;
import java.util.List;

/**
 * Selector policy facade for business-specific selector behavior.
 */
public final class NeoSelectorPolicy {
  private static final SelectorPolicyRegistry REGISTRY = new SelectorPolicyRegistry(
      List.of(new ContextParamSelectorPolicy()),
      List.of(new ProductPriceSelectorPolicy()));


  private NeoSelectorPolicy() {
  }

  /**
   * Resolve a hardcoded reference-search-key filter override.
   *
   * @param referenceSearchKeyId AD_Reference_Value identifier used by the selector
   * @return the additional filter clause, or {@code null} when no override applies
   */
  public static String resolveReferenceOverrideFilter(String referenceSearchKeyId) {
    return ReferenceOverrideSelectorPolicy.resolveFilter(referenceSearchKeyId);
  }

  /**
   * Resolve an entity-specific filter from validated selector context parameters.
   *
   * @param entityName target DAL entity name
   * @param contextParams validated selector context parameters
   * @param alias HQL alias used by the selector query
   * @return the derived filter clause, or {@code null} when no policy applies
   */
  public static String resolveContextParamFilter(String entityName,
      Map<String, String> contextParams, String alias) {
    return REGISTRY.resolveContextFilter(entityName, contextParams, alias);
  }

  /**
   * Resolve a virtual selector column exposed by a wrapper entity.
   *
   * @param entity source Schema Forge entity
   * @param columnName requested DB column name
   * @return the backing AD column, or {@code null} when the wrapper policy does not apply
   */
  public static Column resolveVirtualSelectorColumn(SFEntity entity, String columnName) {
    return AddressVirtualSelectorPolicy.resolveVirtualSelectorColumn(entity, columnName);
  }

  /**
   * Enrich selector results through the registered enrichment policies.
   *
   * @param response selector response to enrich
   * @param meta resolved selector metadata
   * @param contextParams validated selector context parameters
   * @return the enriched response after all matching policies have run
   */
  public static NeoResponse enrichSelectorResult(NeoResponse response, SelectorMeta meta,
      Map<String, String> contextParams) {
    return REGISTRY.enrichSelectorResult(response, meta, contextParams);
  }
}
