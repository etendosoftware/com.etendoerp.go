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

import java.util.List;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;


/**
 * NEO Handler for the {@code price} entity (M_ProductPrice tab).
 *
 * <p>Handles two request types:
 * <ul>
 *   <li><b>GET list</b>: Enriches each row with {@code priceListVersion$salesPriceList}
 *       (boolean from M_PriceList.IsSalesPriceList) so the frontend can classify
 *       prices as sales vs purchase.</li>
 *   <li><b>POST create</b>: Injects missing defaults ({@code product}, {@code priceLimit},
 *       {@code priceListVersion}) before the generic service processes the request.</li>
 * </ul>
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'productPriceHandler'} on the
 * {@code ETGO_SF_ENTITY} record for the {@code price} entity.
 */
@Named("productPriceHandler")
public class ProductPriceHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ProductPriceHandler.class);
  private static final String PRICE_LIMIT = "priceLimit";
  private static final String PRICE_LIST_VERSION_FIELD = "priceListVersion";
  private static final String PRICE_LIST_VERSION_COLUMN = "M_PriceList_Version_ID";
  private static final String PRODUCT_FIELD = "product";
  private static final String STANDARD_PRICE_FIELD = "standardPrice";
  private static final String LIST_PRICE_FIELD = "listPrice";

  private static final String PRICE_LIST_SQL = ""
      + "SELECT "
      + "  pp.m_productprice_id            AS id, "
      + "  pp.m_product_id                 AS product_id, "
      + "  pp.m_pricelist_version_id       AS plv_id, "
      + "  plv.name                        AS plv_name, "
      + "  COALESCE(pp.pricestd, 0)        AS standard_price, "
      + "  COALESCE(pp.pricelist, 0)       AS list_price, "
      + "  COALESCE(pp.pricelimit, 0)      AS price_limit, "
      + "  COALESCE(pp.algorithm, 'S')     AS algo_code, "
      + "  pl.issopricelist                AS is_sales, "
      + "  pl.name                         AS price_list_name, "
      + "  pp.m_product_id || ' - ' || plv.name AS identifier, "
      + "  c.cursymbol                     AS currency_symbol, "
      + "  c.iso_code                      AS currency_iso "
      + "FROM m_productprice pp "
      + "JOIN m_pricelist_version plv ON plv.m_pricelist_version_id = pp.m_pricelist_version_id "
      + "JOIN m_pricelist pl          ON pl.m_pricelist_id = plv.m_pricelist_id "
      + "LEFT JOIN c_currency c       ON c.c_currency_id = pl.c_currency_id "
      + "WHERE pp.m_product_id = :productId "
      + "  AND pp.isactive = 'Y' "
      + "ORDER BY pl.issopricelist DESC, plv.name";

  @Override
  public NeoResponse handle(NeoContext ctx) {
    if (ctx.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }

    // GET list: return enriched rows with salesPriceList flag
    if ("GET".equals(ctx.getHttpMethod()) && StringUtils.isBlank(ctx.getRecordId())) {
      return handleGetList(ctx);
    }

    // POST create: enrich defaults
    if ("POST".equals(ctx.getHttpMethod())) {
      return handlePost(ctx);
    }

    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext ctx) {
    if (ctx == null || ctx.getEndpointType() != NeoEndpointType.SELECTOR) {
      return null;
    }

    String fieldName = ctx.getFieldName();
    if (!PRICE_LIST_VERSION_FIELD.equalsIgnoreCase(fieldName)
        && !PRICE_LIST_VERSION_COLUMN.equalsIgnoreCase(fieldName)) {
      return null;
    }

    NeoResponse previous = ctx.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }

    JSONArray items = previous.getBody().optJSONArray("items");
    if (items == null) {
      return null;
    }

    try {
      OBContext.setAdminMode();
      try {
        for (int i = 0; i < items.length(); i++) {
          enrichSelectorItem(items.optJSONObject(i));
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.debug("Could not enrich price list version selector response: {}", e.getMessage());
      return null;
    }

    return previous;
  }

  private void enrichSelectorItem(JSONObject item) throws Exception {
    if (item == null) {
      return;
    }
    String versionId = item.optString("id", null);
    if (StringUtils.isBlank(versionId)) {
      return;
    }
    PriceListVersion plv = OBDal.getInstance().get(PriceListVersion.class, versionId);
    if (plv == null || plv.getPriceList() == null) {
      return;
    }
    PriceList pl = plv.getPriceList();
    boolean isSales = Boolean.TRUE.equals(pl.isSalesPriceList());
    item.put("salesPriceList", isSales);
    item.put("priceListVersion$salesPriceList", isSales);
    item.put("priceList", pl.getId());
    item.put("priceList$_identifier", pl.getIdentifier());
  }

  /**
   * Handle GET list requests: query M_ProductPrice with JOINs to M_PriceList
   * to include the salesPriceList flag.
   */
  private NeoResponse handleGetList(NeoContext ctx) {
    String parentId = ctx.getQueryParams() != null ? ctx.getQueryParams().get("parentId") : null;
    if (StringUtils.isBlank(parentId)) {
      return null;
    }

    try {
      OBContext.setAdminMode();
      try {
        NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(PRICE_LIST_SQL);
        query.setParameter("productId", parentId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = (List<Object[]>) query.list();

        JSONArray data = new JSONArray();
        for (Object[] row : rows) {
          JSONObject item = new JSONObject();
          item.put("id",                               row[0]);
          item.put(PRODUCT_FIELD,                      row[1]);
          item.put(PRICE_LIST_VERSION_FIELD,           row[2]);
          item.put("priceListVersion$_identifier",     row[3]);
          item.put(STANDARD_PRICE_FIELD,               ProductHandlerUtils.toBigDecimal(row[4]));
          item.put(LIST_PRICE_FIELD,                   ProductHandlerUtils.toBigDecimal(row[5]));
          item.put(PRICE_LIMIT,                        ProductHandlerUtils.toBigDecimal(row[6]));
          String algoCode = row[7] != null ? String.valueOf(row[7]) : "S";
          item.put("algorithm",                        algoCode);
          item.put("algorithm$_identifier",            "S".equals(algoCode) ? "Standard" : algoCode);
          item.put("priceListVersion$salesPriceList",  "Y".equals(String.valueOf(row[8])));
          item.put("priceList$_identifier",            row[9]);
          item.put("_identifier",                      row[10]);
          item.put("currencySymbol",                   row[11] != null ? String.valueOf(row[11]) : "€");
          item.put("currencyIso",                      row[12] != null ? String.valueOf(row[12]) : "EUR");
          item.put("_entityName",                      "PricingProductPrice");
          data.put(item);
        }

        return ProductHandlerUtils.buildListResponse(data);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error fetching prices with salesPriceList for product {}: {}", parentId, e.getMessage(), e);
      return NeoResponse.error(500, "Error fetching price data");
    }
  }

  /**
   * Handle POST (create) requests: inject missing defaults before the generic service.
   */
  private NeoResponse handlePost(NeoContext ctx) {
    JSONObject body = ctx.getRequestBody();
    if (body == null) {
      return null;
    }

    try {
      String parentId = ctx.getQueryParams() != null
          ? (String) ctx.getQueryParams().get("parentId") : null;
      if (StringUtils.isNotBlank(parentId) && !body.has(PRODUCT_FIELD)) {
        body.put(PRODUCT_FIELD, parentId);
      }

      if (!body.has(PRICE_LIMIT)) {
        if (body.has(LIST_PRICE_FIELD)) {
          body.put(PRICE_LIMIT, body.opt(LIST_PRICE_FIELD));
        } else if (body.has(STANDARD_PRICE_FIELD)) {
          body.put(PRICE_LIMIT, body.opt(STANDARD_PRICE_FIELD));
        }
      }

      if (!body.has(PRICE_LIST_VERSION_FIELD)) {
        String versionId = resolveDefaultSalesPriceListVersionId(ctx.getObContext());
        if (StringUtils.isNotBlank(versionId)) {
          body.put(PRICE_LIST_VERSION_FIELD, versionId);
        }
      }
    } catch (Exception e) {
      log.warn("Could not enrich ProductPrice defaults: {}", e.getMessage());
    }

    return null;
  }

  /**
   * Resolve the ID of the most recent active sales price list version for the
   * current client/org using OBDal — org-specific versions take priority over
   * shared (org=0) ones.
   */
  private String resolveDefaultSalesPriceListVersionId(OBContext obContext) {
    if (obContext == null || obContext.getCurrentClient() == null) {
      return null;
    }

    String clientId = obContext.getCurrentClient().getId();
    String orgId = obContext.getCurrentOrganization() != null
        ? obContext.getCurrentOrganization().getId() : "0";

    try {
      OBContext.setAdminMode();
      try {
        // Try org-specific version first; if not found, fall back to shared org=0
        String[] orgsToTry = "0".equals(orgId)
            ? new String[]{ "0" }
            : new String[]{ orgId, "0" };

        for (String targetOrg : orgsToTry) {
          OBCriteria<PriceListVersion> crit = OBDal.getInstance()
              .createCriteria(PriceListVersion.class);
          crit.add(Restrictions.eq(PriceListVersion.PROPERTY_CLIENT + ".id", clientId));
          crit.add(Restrictions.eq(PriceListVersion.PROPERTY_ORGANIZATION + ".id", targetOrg));
          crit.add(Restrictions.eq(PriceListVersion.PROPERTY_ACTIVE, true));
          crit.createAlias(PriceListVersion.PROPERTY_PRICELIST, "pl");
          crit.add(Restrictions.eq("pl." + PriceList.PROPERTY_ACTIVE, true));
          crit.add(Restrictions.eq("pl." + PriceList.PROPERTY_SALESPRICELIST, true));
          crit.addOrder(Order.desc(PriceListVersion.PROPERTY_VALIDFROMDATE));
          crit.setMaxResults(1);

          List<PriceListVersion> results = crit.list();
          if (!results.isEmpty()) {
            return results.get(0).getId();
          }
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("Could not resolve default sales price list version: {}", e.getMessage());
    }

    return null;
  }

}
