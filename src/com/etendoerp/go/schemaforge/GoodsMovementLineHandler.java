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

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.materialmgmt.transaction.InternalMovementLine;

/**
 * Validation pre-hook for the Goods Movement <b>line</b> entity (ETP-4606).
 *
 * <p>The window is served by generic NEO Headless CRUD; this handler is registered as a thin
 * pre-hook via {@code @Named("goodsMovementLineHandler")} (matching
 * {@code ETGO_SF_ENTITY.Java_Qualifier}) and runs <b>before</b> the generic CRUD.
 *
 * <p>Business rule enforced (write methods only): a line cannot reference a {@code Product}
 * whose Type is Service ({@code productType == "S"}) — service products are not stockable and
 * must never generate an inventory movement. Rejected with HTTP 400 and a clear, translatable
 * {@code ETGO_ProductNotStockable} message.
 *
 * <p>This is a defense-in-depth check: the corresponding product selector is also filtered
 * (see {@code selector.policy}) so the UI never offers a Service product in the first place,
 * but any flow that still attempts to persist one (API call, bulk import, stale form state)
 * is blocked here.
 */
@Named("goodsMovementLineHandler")
public class GoodsMovementLineHandler extends AbstractNeoHandler {

  private static final Logger log = LogManager.getLogger(GoodsMovementLineHandler.class);

  private static final String SPEC = "goodsMovementLineHandler";

  @Override
  public NeoResponse handle(NeoContext context) {
    return runWriteHook(context, SPEC, log, body -> validateWrite(context, body));
  }

  /**
   * Validates a create (POST) or update (PUT) / inline patch (PATCH) before generic CRUD.
   * Returns {@code null} when the request is valid (CRUD proceeds), or a {@link NeoResponse}
   * error to reject it. See {@link ServiceProductGuard} for the shared Service-product rule.
   */
  NeoResponse validateWrite(NeoContext context, JSONObject body) {
    boolean isPatch = METHOD_PATCH.equals(context.getHttpMethod());
    return ServiceProductGuard.rejectIfServiceProduct(body, isPatch,
        () -> resolvePersistedProductId(context.getRecordId()));
  }

  /** Resolves the product already persisted on an existing movement line, for PATCH requests. */
  private static String resolvePersistedProductId(String lineId) {
    if (StringUtils.isBlank(lineId)) {
      return null;
    }
    InternalMovementLine line = OBDal.getInstance().get(InternalMovementLine.class, lineId);
    if (line == null || line.getProduct() == null) {
      return null;
    }
    return line.getProduct().getId();
  }
}
