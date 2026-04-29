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
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Selector policies that are specific to business concepts rather than selector execution.
 */
final class NeoSelectorPolicy {

  private static final Logger log = LogManager.getLogger(NeoSelectorPolicy.class);
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


  private NeoSelectorPolicy() {
  }

  static String resolveReferenceOverrideFilter(String referenceSearchKeyId) {
    if (referenceSearchKeyId == null) {
      return null;
    }
    return REFERENCE_OVERRIDE_FILTERS.get(referenceSearchKeyId);
  }

  static String resolveContextParamFilter(String entityName,
      Map<String, String> contextParams, String alias) {
    if (contextParams == null || contextParams.isEmpty() || entityName == null) {
      return null;
    }
    String a = (alias != null && !alias.isEmpty()) ? alias : "e";

    if ("BusinessPartner".equals(entityName)) {
      List<String> conditions = new ArrayList<>();
      String isCustomer = contextParams.get("isCustomer");
      if ("Y".equalsIgnoreCase(isCustomer)) {
        conditions.add(a + ".customer = true");
      } else if ("N".equalsIgnoreCase(isCustomer)) {
        conditions.add(a + ".customer = false");
      }
      String isVendor = contextParams.get("isVendor");
      if ("Y".equalsIgnoreCase(isVendor)) {
        conditions.add(a + ".vendor = true");
      } else if ("N".equalsIgnoreCase(isVendor)) {
        conditions.add(a + ".vendor = false");
      }
      return conditions.isEmpty() ? null : String.join(" AND ", conditions);
    }

    if ("ProductByPriceAndWarehouse".equals(entityName)) {
      String priceListId = contextParams.get("priceList");
      if (StringUtils.isNotBlank(priceListId) && priceListId.matches("[A-Za-z0-9\\-]+")) {
        return a + ".productPrice.priceListVersion.priceList.id = '" + priceListId + "'";
      }
      String isSOTrx = contextParams.get("isSOTrx");
      if ("Y".equalsIgnoreCase(isSOTrx)) {
        return a + ".productPrice.priceListVersion.priceList.salesPriceList = true";
      } else if ("N".equalsIgnoreCase(isSOTrx)) {
        return a + ".productPrice.priceListVersion.priceList.salesPriceList = false";
      }
    }

    return null;
  }

  static Column resolveVirtualSelectorColumn(SFEntity entity, String columnName) {
    if (entity == null || StringUtils.isBlank(columnName)) {
      return null;
    }

    org.openbravo.model.ad.ui.Tab tab = entity.getADTab();
    String tableName = tab != null && tab.getTable() != null
        ? tab.getTable().getDBTableName()
        : null;

    boolean isBPartnerLocationWrapper = "C_BPartner_Location".equalsIgnoreCase(tableName)
        || "locationAddress".equals(entity.getName());
    boolean isLocationVirtualColumn = "C_Country_ID".equalsIgnoreCase(columnName)
        || "C_Region_ID".equalsIgnoreCase(columnName);
    if (!isBPartnerLocationWrapper || !isLocationVirtualColumn) {
      return null;
    }

    OBCriteria<Column> criteria = OBDal.getInstance().createCriteria(Column.class);
    criteria.createAlias(Column.PROPERTY_TABLE, "tbl");
    criteria.add(Restrictions.eq("tbl.dBTableName", "C_Location"));
    criteria.add(Restrictions.eq(Column.PROPERTY_DBCOLUMNNAME, columnName));
    criteria.setMaxResults(1);

    List<Column> results = criteria.list();
    if (results.isEmpty()) {
      return null;
    }
    log.debug("Resolved virtual selector column {} for entity {}", columnName, entity.getName());
    return results.get(0);
  }

  static NeoResponse enrichProductSelectorWithPrices(NeoResponse response, String priceListId) {
    if (response == null || response.getBody() == null || StringUtils.isBlank(priceListId)) {
      return response;
    }
    try {
      JSONArray items = response.getBody().optJSONArray("items");
      if (items == null || items.length() == 0) {
        return response;
      }

      Set<String> seenIds = new LinkedHashSet<>();
      JSONArray deduplicatedItems = new JSONArray();
      for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.getJSONObject(i);
        String itemId = item.optString("id");
        if (StringUtils.isNotBlank(itemId) && seenIds.add(itemId)) {
          deduplicatedItems.put(item);
        }
      }
      if (deduplicatedItems.length() < items.length()) {
        response.getBody().put("items", deduplicatedItems);
        items = deduplicatedItems;
      }

      List<String> productIds = new ArrayList<>(seenIds);
      if (productIds.isEmpty()) {
        return response;
      }

      StringBuilder inClause = new StringBuilder();
      for (int i = 0; i < productIds.size(); i++) {
        if (i > 0) {
          inClause.append(", ");
        }
        inClause.append(":pid").append(i);
      }

      String sql = "SELECT pp.m_product_id,"
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

      if (priceMap.isEmpty()) {
        return response;
      }

      for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.getJSONObject(i);
        Object[] cols = priceMap.get(item.optString("id"));
        if (cols != null) {
          item.put("standardPrice", cols[1]);
          item.put("listPrice", cols[2]);
          item.put("isTaxIncluded", "Y".equals(String.valueOf(cols[3])));
        }
      }

    } catch (Exception e) {
      log.warn("Failed to enrich product selector with prices for priceList {}: {}",
          priceListId, e.getMessage());
    }
    return response;
  }
}
