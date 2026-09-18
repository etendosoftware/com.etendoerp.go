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

import java.math.BigDecimal;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * THE sign policy for return-document line quantities — one rule, both return windows.
 *
 * <p><b>Stored NEGATIVE, exposed POSITIVE.</b>
 *
 * <h3>Why negative in the DB (ETP-5313)</h3>
 * Core {@code M_INOUT_POST} negates {@code MovementQty} and {@code QuantityOrder} whenever the
 * document's {@code MovementType} ends in {@code '-'} (see
 * {@code src-db/database/model/functions/M_INOUT_POST.xml}). A SALES return
 * ({@code IsSOTrx='Y'}) must INCREASE stock, and its {@code MovementType} is always
 * {@code 'C-'}, so the stored quantity has to be negative for the posted transaction to come
 * out positive. A PURCHASE return ({@code IsSOTrx='N'}) gets {@code 'V+'}, is NOT negated by
 * {@code M_INOUT_POST}, and must DECREASE stock — so it needs a negative stored quantity too.
 * Both sides therefore land on the same rule.
 *
 * <p><b>Changing {@code MovementType} is not an option.</b> Core trigger
 * {@code M_INOUT_TRG_PROV} (BEFORE INSERT OR UPDATE, FOR EACH ROW) rewrites
 * {@code MovementType} on every single write purely from {@code IsSOTrx}
 * ({@code 'N' → 'V+'}, otherwise {@code 'C-'}) and ignores {@code C_DocType.IsReturn}
 * entirely. {@code 'C+'} / {@code 'V-'} are unreachable, which also makes any
 * {@code setMovementType("C-")} in the document factories decorative. The stored sign is the
 * only lever we actually control. Etendo Classic solves it the same way — see
 * {@code RMInOutPickEditLines#createInOutLine}, which persists {@code qtyReceived.negate()}.
 *
 * <h3>Why positive in every response</h3>
 * The functional contract (Confluence FD) is that the user-facing line quantity
 * ("Cant. a devolver" / "Return Qty") is ALWAYS positive, in BOTH windows. The DB sign is an
 * implementation detail forced on us by the trigger above and must never leak to the UI.
 *
 * <h3>Idempotency</h3>
 * Both directions are expressed as {@code abs()}-based normalisations rather than
 * {@code negate()} flips, so applying them twice is harmless. That is deliberate: the same
 * quantity crosses several layers (action handler → shared import helper → CRUD handler) and a
 * caller that already normalised the value must not flip it back. It is the same reasoning
 * behind {@code ReturnShipmentUtils.addReturnInvoiceLines}' {@code abs().negate()} (ETP-4737).
 *
 * <p><b>Not covered here:</b> the rectificative invoice line, which keeps NEGATIVE quantities
 * by functional decision — that lives in {@code ReturnShipmentUtils.addReturnInvoiceLines}.
 */
final class ReturnLineQuantityPolicy {

  static final String FIELD_MOVEMENT_QUANTITY = "movementQuantity";

  private ReturnLineQuantityPolicy() {
  }

  /**
   * Normalises a quantity to the sign it must carry in the database: negative.
   * Idempotent; {@code null} and zero pass through unchanged.
   */
  static BigDecimal toStoredQuantity(BigDecimal qty) {
    return qty == null ? null : qty.abs().negate();
  }

  /**
   * Normalises a quantity to the sign the frontend must see: positive.
   * Idempotent; {@code null} and zero pass through unchanged.
   */
  static BigDecimal toDisplayQuantity(BigDecimal qty) {
    return qty == null ? null : qty.abs();
  }

  /**
   * Applies the stored (negative) sign to {@code movementQuantity} in a CRUD write body.
   * No-op when the body is {@code null} or carries no {@code movementQuantity}.
   *
   * @param body the POST/PUT/PATCH request body, mutated in place
   * @param log  the calling handler's logger, used when the value is not a number
   */
  static void applyStoredSignToWriteBody(JSONObject body, Logger log) {
    if (body == null || !body.has(FIELD_MOVEMENT_QUANTITY)
        || body.isNull(FIELD_MOVEMENT_QUANTITY)) {
      return;
    }
    try {
      BigDecimal qty = new BigDecimal(body.get(FIELD_MOVEMENT_QUANTITY).toString());
      body.put(FIELD_MOVEMENT_QUANTITY, toStoredQuantity(qty));
    } catch (Exception e) {
      log.warn("Could not apply the return-line stored sign to movementQuantity: {}",
          e.getMessage());
    }
  }

  /**
   * Applies the display (positive) sign to {@code movementQuantity} on every record of a
   * response data array. No-op when the array is {@code null}.
   *
   * @param dataArr the {@code response.data} array, mutated in place
   * @param log     the calling handler's logger, used when a value is not a number
   */
  static void applyDisplaySignToRecords(JSONArray dataArr, Logger log) {
    if (dataArr == null) {
      return;
    }
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject rec = dataArr.optJSONObject(i);
      if (rec != null) {
        applyDisplaySignToRecord(rec, log);
      }
    }
  }

  /**
   * Applies the display (positive) sign to {@code movementQuantity} on a single record.
   * No-op when the record carries no {@code movementQuantity}.
   */
  static void applyDisplaySignToRecord(JSONObject rec, Logger log) {
    Object raw = rec.opt(FIELD_MOVEMENT_QUANTITY);
    if (raw == null || JSONObject.NULL.equals(raw)) {
      return;
    }
    try {
      BigDecimal qty = new BigDecimal(raw.toString());
      rec.put(FIELD_MOVEMENT_QUANTITY, toDisplayQuantity(qty));
    } catch (Exception e) {
      log.warn("Could not apply the return-line display sign to movementQuantity: {}",
          e.getMessage());
    }
  }
}
