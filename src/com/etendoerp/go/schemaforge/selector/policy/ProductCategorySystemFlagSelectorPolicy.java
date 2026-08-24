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

/**
 * ETP-4967: excludes {@link org.openbravo.model.common.plm.ProductCategory} rows flagged
 * {@code EM_Etgo_IsSystemCategory = 'Y'} (e.g. "Discounts", which exists only to hold the
 * internal global-discount product {@code ETGO_DTO}) from every generic FK selector that targets
 * {@code ProductCategory} — the category dropdown on the Product window included.
 *
 * <p>Deliberately entity-keyed (not reference-search-key-keyed like
 * {@link ReferenceOverrideSelectorPolicy}): {@code M_Product_Category_ID} on {@code M_Product} is
 * a plain TableDir reference (19) with no {@code AD_Reference_Value} of its own — TableDir
 * resolves its target table by column-name convention, so there is no reference-search-key id to
 * hook into. Filtering unconditionally by target entity, regardless of context params, achieves
 * the same "always hidden from selectors" outcome without one.
 *
 * <p>Chosen over an {@code AD_Val_Rule} reassignment on the column: {@code M_Product_Category_ID}
 * is owned by the Core module, which is not in development / has no active template in this
 * environment — Etendo blocks direct edits to it (AD_Message 20532), and reassigning a column's
 * validation rule has no webhook. This policy achieves the same effect at the NEO Headless layer
 * instead, per this codebase's own convention (see {@code CurrencyOptionsHandler},
 * {@code InventoryProductSelectorPolicy}) of resolving business-specific selector behavior here
 * rather than touching {@code NeoSelectorService} or core AD metadata.
 *
 * <p>{@code EM_Etgo_IsSystemCategory} is a Yes/No (reference 20) column, which Openbravo's dynamic
 * model maps as a Hibernate {@code Boolean} property — same as the core Yes/No columns referenced
 * elsewhere in {@link ReferenceOverrideSelectorPolicy} (e.g. {@code e.salesPriceList = true}).
 * {@code is null} additionally covers any {@code ProductCategory} row from before the column
 * existed, though the column is {@code NOT NULL DEFAULT 'N'} so that should never occur in
 * practice.
 */
public final class ProductCategorySystemFlagSelectorPolicy implements SelectorContextPolicy {

  private static final String ENTITY_PRODUCT_CATEGORY = "ProductCategory";
  private static final String PROPERTY_IS_SYSTEM_CATEGORY = "etgoIssystemcategory";

  @Override
  public boolean supports(String entityName) {
    return ENTITY_PRODUCT_CATEGORY.equals(entityName);
  }

  @Override
  public String resolveFilter(String entityName, Map<String, String> contextParams, String alias) {
    String effectiveAlias = (alias == null || alias.isEmpty()) ? "e" : alias;
    return "(" + effectiveAlias + "." + PROPERTY_IS_SYSTEM_CATEGORY + " = false or "
        + effectiveAlias + "." + PROPERTY_IS_SYSTEM_CATEGORY + " is null)";
  }
}
