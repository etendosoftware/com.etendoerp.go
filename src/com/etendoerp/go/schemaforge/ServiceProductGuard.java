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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.function.Supplier;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.plm.Product;

/**
 * Shared write-side guard for entities representing a physical inventory movement/count
 * (ETP-4606): Goods Movement lines, Physical Inventory lines and Internal Consumption lines.
 *
 * <p>Business rule: a line cannot reference a {@code Product} whose Type is Service
 * ({@code productType == "S"}) — service products are not stockable and must never generate an
 * inventory movement. Rejected with HTTP 400 and a clear, translatable
 * {@code ETGO_ProductNotStockable} message.
 *
 * <p>This is a defense-in-depth check: the corresponding product selector is also filtered
 * (see {@code selector.policy.GoodsMovementProductSelectorPolicy}) so the UI never offers a
 * Service product in the first place, but any flow that still attempts to persist one (API
 * call, bulk import, stale form state) is blocked here.
 */
final class ServiceProductGuard {

  private static final String PRODUCT_TYPE_SERVICE = "S";
  private static final String FIELD_PRODUCT = "product";

  private ServiceProductGuard() {
    // Static utility, not instantiable.
  }

  /** Reads the product id from the request body, whether sent as an object or a plain id string. */
  static String resolveProductId(JSONObject body) {
    Object productVal = body.opt(FIELD_PRODUCT);
    if (productVal instanceof JSONObject) {
      return StringUtils.trimToNull(((JSONObject) productVal).optString("id"));
    }
    if (productVal instanceof String) {
      return StringUtils.trimToNull((String) productVal);
    }
    return null;
  }

  /**
   * Rejects the request with HTTP 400 when it references a Service-type {@code Product}.
   * Returns {@code null} when the request is valid and the caller's pre-hook should proceed.
   *
   * @param body the request body (POST or PATCH)
   * @param isPatch whether this is a PATCH request
   * @param persistedProductIdResolver resolves the product already persisted on the line, used
   *     as a fallback on PATCH requests whose body carries no product field at all (e.g. only
   *     the quantity changed). Only invoked when needed, since it may hit the DB.
   */
  static NeoResponse rejectIfServiceProduct(JSONObject body, boolean isPatch,
      Supplier<String> persistedProductIdResolver) {
    String productId = resolveProductId(body);
    if (productId == null && isPatch) {
      productId = persistedProductIdResolver.get();
    }
    if (productId == null) {
      // No product referenced by this request: let the generic CRUD / other validations
      // handle the missing-required-field case.
      return null;
    }
    Product product = OBDal.getInstance().get(Product.class, productId);
    if (product == null) {
      // Unknown product id: let the generic CRUD produce the canonical FK error.
      return null;
    }
    if (PRODUCT_TYPE_SERVICE.equals(product.getProductType())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, OBMessageUtils.messageBD("ETGO_ProductNotStockable"));
    }
    return null;
  }
}
