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

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;
import org.openbravo.model.pricing.pricelist.ProductPrice;

import com.etendoerp.go.schemaforge.util.NeoDateFormat;


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
  private static final String FIELD_UPDATED = "updated";

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
      + "  c.iso_code                      AS currency_iso, "
      + "  pl.isdefault                    AS is_default, "
      + "  plv.validfrom                   AS valid_from_date, "
      + "  pp.updated                      AS updated "
      + "FROM m_productprice pp "
      + "JOIN m_pricelist_version plv ON plv.m_pricelist_version_id = pp.m_pricelist_version_id "
      + "JOIN m_pricelist pl          ON pl.m_pricelist_id = plv.m_pricelist_id "
      + "LEFT JOIN c_currency c       ON c.c_currency_id = pl.c_currency_id "
      + "WHERE pp.m_product_id = :productId "
      + "  AND pp.isactive = 'Y' "
      // Insertion order (oldest first) so newly added price lists append at the
      // bottom of the section instead of jumping to the top.
      + "ORDER BY pl.issopricelist DESC, pp.created ASC, plv.name";

  @Override
  public NeoResponse handle(NeoContext ctx) {
    if (ctx == null || ctx.getEndpointType() != NeoEndpointType.CRUD) {
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
    // ETP-5245: the products import picks the tariff to write an imported price on straight from
    // this selector, and until now it could only tell sales from purchase — so it took whichever
    // version happened to come first. Exposing the tenant's own default flag lets it agree with
    // PriceListVersionResolver, PriceListPicker and the ETGO_PRODUCT_*_PRICE computed columns
    // about which tariff is "the" one.
    boolean isDefault = Boolean.TRUE.equals(pl.isDefault());
    item.put("default", isDefault);
    item.put("priceListVersion$default", isDefault);
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
          data.put(toPriceJson(row));
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

  /** Row shape matches {@link #PRICE_LIST_SQL} column order 1:1. */
  private static JSONObject toPriceJson(Object[] row) throws Exception {
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
    // issopricelist is a CHAR(1) Etendo boolean — 'Y'/'N', not a Java boolean
    item.put("priceListVersion$salesPriceList",  "Y".equals(String.valueOf(row[8])));
    item.put("priceList$_identifier",            row[9]);
    item.put("_identifier",                      row[10]);
    item.put("currencySymbol",                   row[11] != null ? String.valueOf(row[11]) : null);
    item.put("currencyIso",                      row[12] != null ? String.valueOf(row[12]) : null);
    // isdefault (M_PriceList) — CHAR(1) 'Y'/'N'; lets the frontend pick the
    // default sales/purchase price list when a product has several.
    item.put("priceListVersion$default",         "Y".equals(String.valueOf(row[13])));
    // validfromdate (M_PriceList_Version) — tiebreaker: among defaults the
    // frontend keeps the most recent version (<= today).
    item.put("priceListVersion$validFromDate",   row[14] != null ? String.valueOf(row[14]) : null);
    item.put("_entityName",                      "PricingProductPrice");
    // ETP-5203: row[15] (updated) is mandatory for every PUT/PATCH by
    // NeoCrudHandler#validateUpdateRequest (ETP-5073) — omitting it left the
    // Product window's Price tab with no way to echo the value back, so every
    // edit 400'd with missing_updated. Canonicalized through NeoDateFormat since a
    // native-SQL Timestamp prints in the raw Postgres shape, mirroring
    // ChartOfAccountsHandler#toAccountJson.
    String rawUpdated = row[15] != null ? String.valueOf(row[15]) : null;
    String canonicalUpdated = rawUpdated != null ? NeoDateFormat.toCanonical(rawUpdated, true) : null;
    String updatedValue = canonicalUpdated != null ? canonicalUpdated : rawUpdated;
    item.put(FIELD_UPDATED, updatedValue != null ? updatedValue : JSONObject.NULL);
    return item;
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

      NeoResponse upserted = updateInsteadOfDuplicating(body);
      if (upserted != null) {
        return upserted;
      }
    } catch (Exception e) {
      log.warn("Could not enrich ProductPrice defaults: {}", e.getMessage());
    }

    return null;
  }

  /**
   * Turns a POST for a tariff the product is already priced on into an update of that row.
   *
   * <p>{@code M_ProductPrice} is unique on {@code (M_PriceList_Version_ID, M_Product_ID)}, so a
   * second POST for the same pair used to fail with a raw constraint violation. Since ETP-5245
   * that pair is much easier to hit: {@code ProductDefaultsHandler} seeds a zero-priced row on
   * each default tariff the moment a product is created, and the products import then posts the
   * real price for the very same tariff in the same {@code /batch} call — the product operation
   * runs first, so the seeded row is always already there. Updating is also what the user means
   * either way: naming a tariff twice is "set the price on this tariff", not "add a duplicate".
   *
   * <p>Returning a non-null response short-circuits the default CRUD
   * ({@code NeoServletSupport#handleWithHooks}), so the insert never runs. The payload is read
   * back through {@link #PRICE_LIST_SQL} so the caller gets exactly the same row shape a GET
   * would return.
   *
   * @param body the enriched request body, already carrying product and price list version
   * @return the updated row wrapped in a NEO list response, or {@code null} when there is no
   *     existing row and the normal insert should proceed
   */
  private NeoResponse updateInsteadOfDuplicating(JSONObject body) {
    String productId = body.optString(PRODUCT_FIELD, null);
    String versionId = body.optString(PRICE_LIST_VERSION_FIELD, null);
    if (StringUtils.isBlank(productId) || StringUtils.isBlank(versionId)) {
      return null;
    }

    try {
      OBContext.setAdminMode();
      try {
        ProductPrice existing = ProductHandlerUtils.findExistingPrice(productId, versionId);
        if (existing == null) {
          return null;
        }
        // The row may have been deactivated by hand. Someone posting a price for that tariff
        // means it back in use, and leaving it inactive would keep it invisible while still
        // holding the unique pair.
        existing.setActive(true);
        applyPrice(body, STANDARD_PRICE_FIELD, existing::setStandardPrice);
        applyPrice(body, LIST_PRICE_FIELD, existing::setListPrice);
        applyPrice(body, PRICE_LIMIT, existing::setPriceLimit);
        OBDal.getInstance().save(existing);
        OBDal.getInstance().flush();
        return readBackPrice(productId, existing.getId());
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Could not update the existing price of product {} on version {}: {}",
          productId, versionId, e.getMessage(), e);
      return NeoResponse.error(500, "Error updating product price");
    }
  }

  /**
   * Copies one price field from the request body onto the existing row, leaving it untouched when
   * the caller did not send that field.
   *
   * @param body the request body
   * @param field the field name to read
   * @param setter the setter to apply the parsed amount to
   */
  private static void applyPrice(JSONObject body, String field, Consumer<BigDecimal> setter) {
    if (!body.has(field) || body.isNull(field)) {
      return;
    }
    String raw = body.optString(field, null);
    if (StringUtils.isBlank(raw)) {
      return;
    }
    try {
      setter.accept(new BigDecimal(raw.trim()));
    } catch (NumberFormatException e) {
      log.warn("Ignoring unparseable {} value '{}' on product price update", field, raw);
    }
  }

  /**
   * Re-reads one price row through {@link #PRICE_LIST_SQL} so the response shape matches the GET.
   *
   * @param productId the product the row belongs to
   * @param priceId the {@code M_ProductPrice_ID} to return
   * @return the row wrapped in a NEO list response
   * @throws Exception when the row cannot be serialised
   */
  private NeoResponse readBackPrice(String productId, String priceId) throws Exception {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(PRICE_LIST_SQL);
    query.setParameter("productId", productId);
    @SuppressWarnings("unchecked")
    List<Object[]> rows = (List<Object[]>) query.list();
    JSONArray data = new JSONArray();
    for (Object[] row : rows) {
      if (priceId.equals(String.valueOf(row[0]))) {
        data.put(toPriceJson(row));
      }
    }
    return ProductHandlerUtils.buildListResponse(data);
  }

  /**
   * Resolve the ID of the sales price list version a new product price lands on when the caller
   * did not name one.
   *
   * <p>Delegates to {@link PriceListVersionResolver#resolveDefaultVersionId(OBContext, boolean)},
   * which prefers the list explicitly flagged {@code IsDefault} and only then falls back to the
   * newest {@code ValidFromDate}. Before ETP-5245 this method ran that fallback alone, so it
   * silently ignored the tenant's own choice of default tariff.
   *
   * @param obContext the context whose client/organisation scope the lookup runs in
   * @return the default sales price list version id, or {@code null} if there is none
   */
  private String resolveDefaultSalesPriceListVersionId(OBContext obContext) {
    return PriceListVersionResolver.resolveDefaultVersionId(obContext, true);
  }

}
