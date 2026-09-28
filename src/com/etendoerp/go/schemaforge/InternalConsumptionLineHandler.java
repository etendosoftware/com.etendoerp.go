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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
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
 * <p>Callout post-hook (ETP-5445): strips the stock-derived {@code movementQuantity} the classic
 * product callout echoes back, only when the product field triggered it — see
 * {@link #afterCallout(NeoContext)}.
 *
 * <p>The class (and its {@code JAVA_QUALIFIER = 'internalConsumptionLineHandler'}
 * registration on ETGO_SF_ENTITY record {@code 1EB67B71AE6445F787649951DFAEE661}) is kept so the
 * existing DB configuration keeps resolving to a valid bean.
 */
@Named("internalConsumptionLineHandler")
public class InternalConsumptionLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(InternalConsumptionLineHandler.class);

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
    return ServiceProductGuard.rejectIfServiceProduct(body, isPatch,
        () -> resolvePersistedProductId(context.getRecordId()));
  }

  /**
   * AD column of the product FK on {@code M_Internal_ConsumptionLine}, the only field whose
   * callout ({@code SL_Internal_Consumption_Product}) copies on-hand stock into
   * {@code movementQuantity}.
   */
  private static final String PRODUCT_COLUMN = "M_Product_ID";

  /**
   * Strips the stock-derived {@code movementQuantity} the classic
   * {@code SL_Internal_Consumption_Product} callout echoes back on product selection — core copies
   * the selected product's on-hand stock ({@code inpmProductId_QTY}) straight into
   * {@code inpmovementqty} ({@code SL_Internal_Consumption_Product.java:76}), overwriting whatever
   * the user typed (ETP-5445). Same protection {@link GoodsReceiptLineHandler} (ETP-4671),
   * {@link GoodsShipmentLineHandler} (ETP-5062) and {@link ReturnMaterialReceiptLineHandler}
   * (ETP-5336) already have. The consumed quantity is what the user declares, never the stock on
   * hand. See {@link NeoHandlerUtils#stripStockDerivedMovementQuantity} for the full rationale.
   *
   * <p>Gated to the PRODUCT trigger only. Unlike the {@code M_InOutLine} windows, this line has a
   * second callout that legitimately writes {@code movementQuantity}:
   * {@code SL_Internal_Consumption_Conversion}, attached to {@code M_Product_Uom_Id} and
   * {@code QuantityOrder}, which converts the second-UOM quantity into the base-UOM movement
   * quantity. Stripping that result would silently drop the conversion, so any trigger other than
   * the product field keeps the callout response untouched. The shared helper is deliberately not
   * changed — its other callers have no such conversion callout.</p>
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    if (context == null || !isProductTrigger(context.getRequestBody())) {
      return null;
    }
    NeoHandlerUtils.stripStockDerivedMovementQuantity(context, log);
    return null;
  }

  /**
   * True when the callout request was triggered by the product field. The request's
   * {@code field} is normally the DAL property name ({@code "product"}), but
   * {@code NeoCalloutService#resolveCallout} also accepts the DB column name, the clean REST name
   * and the classic {@code inp} name (all case-insensitive), so the same forms are accepted here
   * to stay in step with which callout actually ran.
   */
  static boolean isProductTrigger(JSONObject requestBody) {
    String field = requestBody != null ? StringUtils.trimToNull(requestBody.optString("field", null)) : null;
    if (field == null) {
      return false;
    }
    return InternalConsumptionLine.PROPERTY_PRODUCT.equalsIgnoreCase(field)
        || PRODUCT_COLUMN.equalsIgnoreCase(field)
        || NeoCalloutService.toInpName(PRODUCT_COLUMN).equalsIgnoreCase(field);
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    // Warehouse-name enrichment is now handled generically for all locator FKs.
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
