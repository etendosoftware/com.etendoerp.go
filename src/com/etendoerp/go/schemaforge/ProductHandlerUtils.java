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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.ProductPrice;

/**
 * Shared utilities for product-related NEO handlers.
 */
final class ProductHandlerUtils {

  private ProductHandlerUtils() {
  }

  /**
   * Build a standard NEO list response wrapping a data array.
   */
  static NeoResponse buildListResponse(JSONArray data) {
    try {
      JSONArray safeData = (data != null) ? data : new JSONArray();
      int size = safeData.length();
      JSONObject inner = new JSONObject();
      inner.put("data",      safeData);
      inner.put("startRow",  0);
      inner.put("endRow",    size);
      inner.put("totalRows", size);
      inner.put("status",    0);
      JSONObject body = new JSONObject();
      body.put("response", inner);
      return NeoResponse.ok(body);
    } catch (JSONException e) {
      return NeoResponse.error(500, "Error building list response");
    }
  }

  /**
   * Safely convert a raw SQL result value to {@link BigDecimal}.
   * Returns {@link BigDecimal#ZERO} for null or unparseable values.
   */
  static BigDecimal toBigDecimal(Object val) {
    if (val == null) {
      return BigDecimal.ZERO;
    }
    if (val instanceof BigDecimal) {
      return (BigDecimal) val;
    }
    try {
      return new BigDecimal(val.toString());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  /**
   * Finds the active {@link ProductPrice} a product already has on one price list version, or
   * {@code null} when it has none.
   *
   * <p>{@code M_ProductPrice} carries a unique constraint on
   * {@code (M_PriceList_Version_ID, M_Product_ID)}, so every writer has to look before it
   * inserts. Two of them do, for different reasons: {@code ProductDefaultsHandler} seeds the
   * zero-priced rows on the default tariffs when a product is created, and
   * {@code ProductPriceHandler} turns a POST for an already-priced tariff into an update instead
   * of letting the constraint blow up. Sharing one lookup keeps the two in agreement about what
   * "already priced" means.
   *
   * <p>Inactive rows count. {@code M_PRODUCTPRICE_PRICELIST_VE_UN} spans
   * {@code (M_PriceList_Version_ID, M_Product_ID)} and nothing else, so a deactivated row still
   * occupies the pair — skipping it would report the pair as free and the insert would then hit
   * the constraint, which is the exact failure this lookup exists to prevent.
   *
   * <p>Runs under the caller's context — callers that need to see rows outside the current
   * organisation are responsible for wrapping it in admin mode.
   *
   * @param productId the {@code M_Product_ID} to look up
   * @param priceListVersionId the {@code M_PriceList_Version_ID} to look up
   * @return the existing active price row, or {@code null}
   */
  static ProductPrice findExistingPrice(String productId, String priceListVersionId) {
    if (productId == null || priceListVersionId == null) {
      return null;
    }
    OBCriteria<ProductPrice> crit = OBDal.getInstance().createCriteria(ProductPrice.class);
    crit.setFilterOnActive(false);
    crit.add(Restrictions.eq(ProductPrice.PROPERTY_PRODUCT + ".id", productId));
    crit.add(Restrictions.eq(ProductPrice.PROPERTY_PRICELISTVERSION + ".id", priceListVersionId));
    crit.setMaxResults(1);
    List<ProductPrice> results = crit.list();
    return results.isEmpty() ? null : results.get(0);
  }
}
