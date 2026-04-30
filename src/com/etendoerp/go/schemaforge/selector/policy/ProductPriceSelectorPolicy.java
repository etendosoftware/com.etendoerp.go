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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/** Enriches product selector rows with price-list information. */
public final class ProductPriceSelectorPolicy implements SelectorEnrichmentPolicy {

  private static final Logger log = LogManager.getLogger(ProductPriceSelectorPolicy.class);

  public ProductPriceSelectorPolicy() {
  }

  @Override
  public boolean supports(SelectorMeta meta, Map<String, String> contextParams) {
    return meta != null
        && ("ProductByPriceAndWarehouse".equals(meta.entityName) || "Product".equals(meta.entityName))
        && contextParams != null
        && contextParams.containsKey("priceList");
  }

  @Override
  /**
   * Enrich selector rows with prices from the active price list.
   *
   * @param response selector response to enrich
   * @param priceListId active price list identifier
   * @return the enriched response, or the original response when enrichment does not apply
   */
  public NeoResponse enrich(NeoResponse response, SelectorMeta meta, Map<String, String> contextParams) {
    return enrichProductSelectorWithPrices(response, contextParams.get("priceList"));
  }

  /**
   * Enrich selector rows with prices from the active price list.
   *
   * @param response selector response to enrich
   * @param priceListId active price list identifier
   * @return the enriched response, or the original response when enrichment does not apply
   */
  public static NeoResponse enrichProductSelectorWithPrices(NeoResponse response, String priceListId) {
    if (response == null || response.getBody() == null || StringUtils.isBlank(priceListId)) {
      return response;
    }
    try {
      JSONArray items = response.getBody().optJSONArray("items");
      if (items == null || items.length() == 0) {
        return response;
      }

      DeduplicatedItems deduplicated = deduplicateProductItems(items);
      if (deduplicated.items.length() < items.length()) {
        response.getBody().put("items", deduplicated.items);
        items = deduplicated.items;
      }
      if (deduplicated.productIds.isEmpty()) {
        return response;
      }

      Map<String, Object[]> priceMap = loadProductPrices(priceListId, deduplicated.productIds);
      if (priceMap.isEmpty()) {
        return response;
      }

      injectPrices(items, priceMap);
    } catch (Exception e) {
      log.warn("Failed to enrich product selector with prices for priceList {}: {}",
          priceListId, e.getMessage());
    }
    return response;
  }

  private static DeduplicatedItems deduplicateProductItems(JSONArray items) throws Exception {
    Set<String> seenIds = new LinkedHashSet<>();
    JSONArray deduplicatedItems = new JSONArray();
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      String itemId = item.optString("id");
      if (StringUtils.isNotBlank(itemId) && seenIds.add(itemId)) {
        deduplicatedItems.put(item);
      }
    }
    return new DeduplicatedItems(deduplicatedItems, new ArrayList<>(seenIds));
  }

  private static Map<String, Object[]> loadProductPrices(String priceListId, List<String> productIds) {
    String sql = buildProductPriceSql(productIds.size());
    @SuppressWarnings("rawtypes")
    NativeQuery nq = OBDal.getInstance().getSession().createNativeQuery(sql);
    nq.setParameter("priceListId", priceListId);
    for (int i = 0; i < productIds.size(); i++) {
      nq.setParameter("pid" + i, productIds.get(i));
    }
    Map<String, Object[]> priceMap = new HashMap<>();
    for (Object row : nq.list()) {
      Object[] cols = (Object[]) row;
      priceMap.put(String.valueOf(cols[0]), cols);
    }
    return priceMap;
  }

  private static String buildProductPriceSql(int productCount) {
    StringBuilder inClause = new StringBuilder();
    for (int i = 0; i < productCount; i++) {
      if (i > 0) {
        inClause.append(", ");
      }
      inClause.append(":pid").append(i);
    }
    return "SELECT pp.m_product_id,"
        + "  COALESCE(pp.pricestd, 0) AS standard_price,"
        + "  COALESCE(pp.pricelist, 0) AS list_price,"
        + "  pl.istaxincluded AS is_tax_included"
        + " FROM m_productprice pp"
        + " JOIN m_pricelist_version plv"
        + "   ON plv.m_pricelist_version_id = pp.m_pricelist_version_id"
        + " JOIN m_pricelist pl"
        + "   ON pl.m_pricelist_id = plv.m_pricelist_id"
        + " WHERE plv.m_pricelist_id = :priceListId"
        + "   AND pp.m_product_id IN (" + inClause + ")"
        + "   AND pp.isactive = 'Y'"
        + "   AND plv.isactive = 'Y'"
        + "   AND plv.validfrom = ("
        + "     SELECT MAX(v.validfrom) FROM m_pricelist_version v"
        + "     WHERE v.m_pricelist_id = :priceListId"
        + "       AND v.isactive = 'Y'"
        + "       AND v.validfrom <= NOW()"
        + "   )";
  }

  private static void injectPrices(JSONArray items, Map<String, Object[]> priceMap) throws Exception {
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      Object[] cols = priceMap.get(item.optString("id"));
      if (cols != null) {
        item.put("standardPrice", cols[1]);
        item.put("listPrice", cols[2]);
        item.put("isTaxIncluded", "Y".equals(String.valueOf(cols[3])));
      }
    }
  }

  private static final class DeduplicatedItems {
    private final JSONArray items;
    private final List<String> productIds;

    private DeduplicatedItems(JSONArray items, List<String> productIds) {
      this.items = items;
      this.productIds = productIds;
    }
  }
}
