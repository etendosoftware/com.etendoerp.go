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

import java.math.BigDecimal;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.materialmgmt.transaction.InternalMovementLine;

/**
 * Validation pre-hook for the Goods Movement <b>line</b> entity (ETP-4606, ETP-5037).
 *
 * <p>The window is served by generic NEO Headless CRUD; this handler is registered as a thin
 * pre-hook via {@code @Named("goodsMovementLineHandler")} (matching
 * {@code ETGO_SF_ENTITY.Java_Qualifier}) and runs <b>before</b> the generic CRUD.
 *
 * <p>Business rules enforced (write methods only):
 * <ul>
 *   <li>a line cannot reference a {@code Product} whose Type is Service
 *   ({@code productType == "S"}) — service products are not stockable and must never generate
 *   an inventory movement. Rejected with HTTP 400 and a clear, translatable
 *   {@code ETGO_ProductNotStockable} message.</li>
 *   <li>a line's quantity cannot exceed the on-hand stock at the selected source storage bin
 *   (ETP-5037), unless that bin's {@code M_InventoryStatus} allows overissue — the same
 *   condition classic core's {@code M_Check_Stock} applies at completion time. Rejected with
 *   HTTP 400 and a descriptive, translatable {@code ETGO_InsufficientStockLine} message naming
 *   the product, warehouse, available and requested quantities. Because this runs on every
 *   create/update, editing an existing line's source warehouse re-triggers the same check with
 *   no extra frontend wiring.</li>
 * </ul>
 *
 * <p>These are defense-in-depth checks: the corresponding product selector is also filtered
 * (see {@code selector.policy}) so the UI never offers a Service product in the first place,
 * but any flow that still attempts to persist one (API call, bulk import, stale form state)
 * is blocked here.
 */
@Named("goodsMovementLineHandler")
public class GoodsMovementLineHandler extends AbstractNeoHandler {

  private static final Logger log = LogManager.getLogger(GoodsMovementLineHandler.class);

  /**
   * {@code runWriteHook} compares this against {@code NeoContext.getSpecName()}, which is the
   * ETGO_SF_SPEC name resolved from the URL ({@code /sws/neo/goods-movements/movementLine}) —
   * NOT this handler's own {@code @Named}/{@code Java_Qualifier} bean name. Using the qualifier
   * here (as originally written) never matched, so the hook silently no-op'd on every request:
   * neither the Service-product guard (ETP-4606) nor stock validation (ETP-5037) ever actually
   * ran. Confirmed live via {@code goods-movements} in {@code ETGO_SF_SPEC}.
   */
  private static final String SPEC = "goods-movements";

  @Override
  public NeoResponse handle(NeoContext context) {
    return runWriteHook(context, SPEC, log, body -> validateWrite(context, body));
  }

  /**
   * Validates a create (POST) or update (PUT) / inline patch (PATCH) before generic CRUD.
   * Returns {@code null} when the request is valid (CRUD proceeds), or a {@link NeoResponse}
   * error to reject it. See {@link ServiceProductGuard} and {@link StockAvailabilityGuard} for
   * the shared rules applied here.
   */
  NeoResponse validateWrite(NeoContext context, JSONObject body) {
    boolean isPatch = METHOD_PATCH.equals(context.getHttpMethod());
    String lineId = context.getRecordId();
    NeoResponse serviceProductRejection = ServiceProductGuard.rejectIfServiceProduct(body, isPatch,
        () -> resolvePersistedProductId(lineId));
    if (serviceProductRejection != null) {
      return serviceProductRejection;
    }
    return StockAvailabilityGuard.rejectIfInsufficientStock(body, isPatch,
        () -> resolvePersistedProductId(lineId), () -> resolvePersistedStorageBinId(lineId),
        () -> resolvePersistedQuantity(lineId));
  }

  /** Resolves the product already persisted on an existing movement line, for PATCH requests. */
  private static String resolvePersistedProductId(String lineId) {
    InternalMovementLine line = resolvePersistedLine(lineId);
    return line == null || line.getProduct() == null ? null : line.getProduct().getId();
  }

  /** Resolves the source storage bin already persisted on an existing line, for PATCH requests. */
  private static String resolvePersistedStorageBinId(String lineId) {
    InternalMovementLine line = resolvePersistedLine(lineId);
    return line == null || line.getStorageBin() == null ? null : line.getStorageBin().getId();
  }

  /** Resolves the quantity already persisted on an existing line, for PATCH requests. */
  private static BigDecimal resolvePersistedQuantity(String lineId) {
    InternalMovementLine line = resolvePersistedLine(lineId);
    return line == null ? null : line.getMovementQuantity();
  }

  private static InternalMovementLine resolvePersistedLine(String lineId) {
    if (StringUtils.isBlank(lineId)) {
      return null;
    }
    return OBDal.getInstance().get(InternalMovementLine.class, lineId);
  }
}
