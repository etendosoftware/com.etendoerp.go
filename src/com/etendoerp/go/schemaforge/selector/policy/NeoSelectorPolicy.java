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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Selector policy facade for business-specific selector behavior.
 */
public final class NeoSelectorPolicy {
  private static final SelectorPolicyRegistry REGISTRY = new SelectorPolicyRegistry(
      List.of(new ContextParamSelectorPolicy(), new FinancialAccountPaymentMethodSelectorPolicy(),
          new CurrencyIsoAllowlistSelectorPolicy(), new GoodsMovementProductSelectorPolicy(),
          new ProductCategorySystemFlagSelectorPolicy()),
      List.of(new ProductPriceSelectorPolicy(), new InventoryProductSelectorPolicy(),
          new InvoiceLineTaxSifSelectorPolicy(), new ProductSystemCategorySelectorPolicy()));


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
   * Resolve every virtual column a wrapper entity exposes from its backing table.
   *
   * <p>ETP-5368. Wider than {@link #resolveVirtualSelectorColumn}, which answers only for the FK
   * columns that have a selector to query: this one is what a schema builder needs, since an agent
   * cannot send a field it was never told exists.
   *
   * @param entity source Schema Forge entity
   * @return the backing columns, in declaration order; empty when no wrapper policy applies
   */
  public static List<Column> resolveVirtualColumns(SFEntity entity) {
    return AddressVirtualSelectorPolicy.resolveVirtualColumns(entity);
  }

  /**
   * Whether the entity exposes virtual columns from a backing table.
   *
   * @param entity source Schema Forge entity
   * @return {@code true} when a wrapper policy applies to this entity
   */
  public static boolean hasVirtualColumns(SFEntity entity) {
    return AddressVirtualSelectorPolicy.isAddressWrapper(entity);
  }

  /**
   * The entity's own fields that a wrapper handler resolves server-side, by published field name.
   *
   * @param entity source Schema Forge entity
   * @return the field names; empty when no wrapper policy applies
   */
  public static Set<String> serverResolvedFieldNames(SFEntity entity) {
    return AddressVirtualSelectorPolicy.serverResolvedFieldNames(entity);
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
