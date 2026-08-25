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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.SystemCategoryIds;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * ETP-4967: excludes products classified under a category flagged
 * {@code EM_Etgo_IsSystemCategory = 'Y'} (e.g. {@code ETGO_DTO}, category "Discounts") from
 * EVERY product selector in the app — order/invoice/shipment/receipt/goods-movement lines, and
 * any other window that searches for a product to pick.
 *
 * <p>Unlike {@link GoodsMovementProductSelectorPolicy} (scoped to specific stockable-only source
 * entities) or {@link InventoryProductSelectorPolicy} (scoped to Physical Inventory), this policy
 * applies unconditionally to any {@code Product}-family selector — matching
 * {@link GoodsMovementProductSelectorPolicy}'s own note that {@code entityName == "Product"} is
 * the generic DAL entity name shared by every product selector in the app.
 *
 * <p>Implemented as a {@link SelectorEnrichmentPolicy} (post-filters the already-executed
 * selector's {@code items}) rather than a {@link SelectorContextPolicy} (pre-filter HQL): the
 * HQL path would need to traverse {@code e.productCategory.etgoIssystemcategory}, chaining two
 * levels of uncertainty (a FK association property, then a dynamically-mapped extension column)
 * neither of which has been proven live. Post-filtering by id, resolved via the same plain-SQL
 * approach as {@link SystemCategoryIds} and {@link ComboRowSelectorPolicy}, avoids that risk
 * entirely — same reasoning throughout this ticket for every {@code EM_} column read.
 *
 * <p>Accepted limitation, same as {@link ComboRowSelectorPolicy}: filtering happens after the
 * underlying query already paginated, so a page that includes a hidden product can come back
 * short of the requested page size. {@code totalCount} IS adjusted here (unlike
 * {@code ComboRowSelectorPolicy}, which leaves it alone) because the "N productos" footer the
 * frontend renders from it is directly visible to the end user in this selector's UI.
 */
public final class ProductSystemCategorySelectorPolicy implements SelectorEnrichmentPolicy {

  private static final Logger log = LogManager.getLogger(ProductSystemCategorySelectorPolicy.class);
  private static final String ENTITY_PRODUCT_PREFIX = "Product";
  private static final String FIELD_ID = "id";
  private static final String FIELD_TOTAL_COUNT = "totalCount";

  public ProductSystemCategorySelectorPolicy() {
    // Stateless policy; public constructor supports registry composition without CDI.
  }

  @Override
  public boolean supports(SelectorMeta meta, Map<String, String> contextParams) {
    return meta != null && meta.entityName != null && meta.entityName.startsWith(ENTITY_PRODUCT_PREFIX);
  }

  @Override
  public NeoResponse enrich(NeoResponse response, SelectorMeta meta,
      Map<String, String> contextParams) {
    if (response == null || response.getBody() == null) {
      return response;
    }
    try {
      JSONObject body = response.getBody();
      JSONArray items = body.optJSONArray("items");
      if (items == null || items.length() == 0) {
        return response;
      }
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      Set<String> hiddenCategoryIds = SystemCategoryIds.resolve(clientId);
      if (hiddenCategoryIds.isEmpty()) {
        return response;
      }
      List<String> productIds = extractProductIds(items);
      if (productIds.isEmpty()) {
        return response;
      }
      Set<String> hiddenProductIds = resolveHiddenProductIds(productIds, hiddenCategoryIds);
      if (hiddenProductIds.isEmpty()) {
        return response;
      }
      JSONArray filtered = new JSONArray();
      for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (item != null && !hiddenProductIds.contains(item.optString(FIELD_ID, ""))) {
          filtered.put(item);
        }
      }
      body.put("items", filtered);
      if (body.has(FIELD_TOTAL_COUNT)) {
        int removed = items.length() - filtered.length();
        body.put(FIELD_TOTAL_COUNT, Math.max(0, body.optInt(FIELD_TOTAL_COUNT, 0) - removed));
      }
    } catch (Exception e) {
      log.warn("[ProductSystemCategorySelectorPolicy] Failed to filter product selector: {}",
          e.getMessage(), e);
    }
    return response;
  }

  private static List<String> extractProductIds(JSONArray items) {
    List<String> ids = new ArrayList<>(items.length());
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      String id = item != null ? item.optString(FIELD_ID, null) : null;
      if (StringUtils.isNotBlank(id)) {
        ids.add(id);
      }
    }
    return ids;
  }

  /**
   * Of {@code productIds}, which ones belong to one of {@code hiddenCategoryIds}. Plain SQL for
   * the same reason {@link SystemCategoryIds} uses it: {@code EM_Etgo_IsSystemCategory} is not
   * mapped as an entity property this code can depend on.
   */
  private static Set<String> resolveHiddenProductIds(List<String> productIds,
      Set<String> hiddenCategoryIds) {
    Set<String> hidden = new HashSet<>();
    String sql = "SELECT m_product_id FROM m_product "
        + "WHERE m_product_id = ANY(?) AND m_product_category_id = ANY(?)";
    try {
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setArray(1, conn.createArrayOf("varchar", productIds.toArray()));
        ps.setArray(2, conn.createArrayOf("varchar", hiddenCategoryIds.toArray()));
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            hidden.add(rs.getString(1));
          }
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve hidden products among selector results: {}", e.getMessage());
    }
    return hidden;
  }
}
