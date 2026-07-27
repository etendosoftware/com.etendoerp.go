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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.transaction.InternalConsumptionLine;

/**
 * NeoHandler for the {@code internalConsumptionLine} entity.
 *
 * <p>Historically this handler rewrote the storage bin (M_Locator_ID) selector labels to display
 * the parent warehouse name. That behavior is now generic and applies to every locator FK across
 * all windows, implemented in the shared selector pipeline
 * ({@code NeoSelectorService} → {@code NeoLocatorSelectorHelper}) and CRUD pipeline
 * ({@code NeoCrudHandler} → {@code NeoLocatorIdentifierHelper}).
 *
 * <p>The remaining responsibility is the write pre-hook added for ETP-4606: a line cannot
 * reference a Service-type {@code Product} — service products are not stockable and must never
 * generate an inventory movement. Defense-in-depth: the corresponding product selector is also
 * filtered (see {@code selector.policy.GoodsMovementProductSelectorPolicy}), but any flow that
 * still attempts to persist one (API call, bulk import, stale form state) is blocked here.
 *
 * <p>The class (and its {@code JAVA_QUALIFIER = 'internalConsumptionLineHandler'}
 * registration on ETGO_SF_ENTITY record {@code 1EB67B71AE6445F787649951DFAEE661}) is kept so the
 * existing DB configuration keeps resolving to a valid bean.
 */
@Named("internalConsumptionLineHandler")
public class InternalConsumptionLineHandler implements NeoHandler {

  private static final String PRODUCT_TYPE_SERVICE = "S";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    boolean isPost = "POST".equalsIgnoreCase(method);
    boolean isPatch = "PATCH".equalsIgnoreCase(method);
    if (!isPost && !isPatch) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    String productId = resolveProductId(body);
    if (productId == null && isPatch) {
      productId = resolvePersistedProductId(context.getRecordId());
    }
    if (productId == null) {
      return null;
    }
    Product product = OBDal.getInstance().get(Product.class, productId);
    if (product == null) {
      return null;
    }
    if (PRODUCT_TYPE_SERVICE.equals(product.getProductType())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, OBMessageUtils.messageBD("ETGO_ProductNotStockable"));
    }
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    // Warehouse-name enrichment is now handled generically for all locator FKs.
    return null;
  }

  /** Reads the product id from the request body, whether sent as an object or a plain id string. */
  private static String resolveProductId(JSONObject body) {
    Object productVal = body.opt("product");
    if (productVal instanceof JSONObject) {
      return StringUtils.trimToNull(((JSONObject) productVal).optString("id"));
    }
    if (productVal instanceof String) {
      return StringUtils.trimToNull((String) productVal);
    }
    return null;
  }

  /** Resolves the product already persisted on an existing consumption line, for PATCH requests. */
  private static String resolvePersistedProductId(String lineId) {
    if (StringUtils.isBlank(lineId)) {
      return null;
    }
    InternalConsumptionLine line = OBDal.getInstance().get(InternalConsumptionLine.class, lineId);
    if (line == null || line.getProduct() == null) {
      return null;
    }
    return line.getProduct().getId();
  }
}
