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

import java.util.regex.Pattern;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * NeoHandler for the Goods Receipt line entity ({@code goodsReceiptLine}).
 *
 * <p>Extends {@link AbstractInOutLineHandler} for the behavior shared with Goods Shipment
 * (invoice-line linking, order/invoice qty and product-code enrichment on GET), and adds two
 * receipt-only fixes (ETP-4671):
 *
 * <h3>POST (create) — pre-hook: default {@code storageBin} to the warehouse's default locator</h3>
 * {@code M_InOutLine.M_Locator_ID} (the {@code storageBin} field) is declared in
 * {@code decisions.json} with {@code form: false} — it is never shown to the user in this
 * window. Its raw AD default ({@code @OnHandLocatorDefault@}) resolves to the locator where the
 * product <em>already</em> has stock, which is the right idea for Goods Shipment (you can only
 * ship from where stock exists) but wrong for a purchase receipt: a brand-new product with zero
 * on-hand stock resolves to nothing, {@code M_Locator_ID} stays {@code NULL}, and the classic
 * {@code M_INOUT_POST} completion procedure then rejects the document with
 * {@code InoutLineWithoutLocator} — regardless of {@code IsSOTrx}. This hook defaults the
 * locator to the receiving warehouse's own default active {@code M_Locator} (the same "default
 * locator for a warehouse" concept {@link InventoryLineHandler} already uses for Physical
 * Inventory), so confirmation succeeds for unstocked products too. An explicit
 * user/import-supplied {@code storageBin} is never overridden.
 *
 * <h3>Callout post-hook: strip stock-derived {@code movementQuantity}</h3>
 * The classic {@code SL_InOutLine_Product} callout (shared by every {@code M_InOutLine}-based
 * window, per its own source comment) echoes back the product selector's on-hand-stock
 * auxiliary value as the new {@code movementQuantity} whenever the line is not created from an
 * order import. That default makes sense for a shipment (pick from what's in stock) but not for
 * a receipt, where the quantity being received has nothing to do with what is already on hand.
 * This hook removes {@code movementQuantity} from the callout response so the frontend keeps the
 * row's own value ({@code decisions.json} defaults it to {@code 1}, editable by the user)
 * instead of silently overwriting it with the stock quantity.
 */
@Named("goodsReceiptLineHandler")
public class GoodsReceiptLineHandler extends AbstractInOutLineHandler {

  private static final Logger log = LogManager.getLogger(GoodsReceiptLineHandler.class);
  private static final String FIELD_STORAGE_BIN = "storageBin";
  private static final String FIELD_MOVEMENT_QUANTITY = "movementQuantity";
  private static final String PARAM_PARENT_ID = "parentId";
  // Matches an unresolved raw AD default literal such as "@OnHandLocatorDefault@". The
  // generated frontend's addLineFields.hidden merge (DetailView.jsx) copies a hidden field's
  // contract-level defaultValue verbatim into the create payload whenever the row never
  // touched it — with no guard against tokens that were never actually resolved client-side.
  // A value shaped like this is never a real Locator id, so it must be treated as absent.
  private static final Pattern UNRESOLVED_TOKEN = Pattern.compile("^@[^@]+@$");

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse parentResult = super.handle(context);
    if (parentResult != null) {
      return parentResult;
    }
    if (context != null && NeoEndpointType.CRUD.equals(context.getEndpointType())
        && "POST".equalsIgnoreCase(context.getHttpMethod())) {
      try {
        injectDefaultLocatorIfMissing(context.getRequestBody());
      } catch (Exception e) {
        log.warn("[GoodsReceiptLineHandler] Could not default storageBin: {}", e.getMessage(), e);
      }
    }
    return null;
  }

  /**
   * Sets {@code storageBin} to the receiving warehouse's default locator when the create
   * request did not already supply a REAL one. Treats both a genuinely absent/blank value and
   * an unresolved {@code @Token@}-shaped literal (see {@link #UNRESOLVED_TOKEN}) as "missing" —
   * confirmed live (ETP-4671): the frontend sends the literal string
   * {@code "@OnHandLocatorDefault@"} for a manually-added line whose product has no prior
   * stock, and that string is never a real Locator id. Never overrides a genuine explicit value
   * coming from the user or from the "Import from Purchase Order" flow.
   */
  private void injectDefaultLocatorIfMissing(JSONObject body) throws Exception {
    if (body == null) {
      return;
    }
    String existing = body.optString(FIELD_STORAGE_BIN, null);
    if (StringUtils.isNotBlank(existing) && !UNRESOLVED_TOKEN.matcher(existing).matches()) {
      return;
    }
    String parentId = body.optString(PARAM_PARENT_ID, "");
    if (parentId.isEmpty()) {
      return;
    }
    ShipmentInOut header = OBDal.getInstance().get(ShipmentInOut.class, parentId);
    if (header == null || header.getWarehouse() == null) {
      return;
    }
    String locatorId = resolveDefaultLocatorForWarehouse(header.getWarehouse().getId());
    if (locatorId != null) {
      body.put(FIELD_STORAGE_BIN, locatorId);
      log.debug("[GoodsReceiptLineHandler] POST: defaulted storageBin={} warehouse={}",
          locatorId, header.getWarehouse().getId());
    }
  }

  /**
   * Returns the default active {@code M_Locator} for a warehouse, or {@code null} when none is
   * configured. Mirrors {@link InventoryLineHandler}'s locator lookup, scoped down to just the
   * id since the receipt line only needs {@code storageBin}.
   */
  @SuppressWarnings("unchecked")
  private static String resolveDefaultLocatorForWarehouse(String warehouseId) {
    try {
      Locator locator = (Locator) OBDal.getInstance().createCriteria(Locator.class)
          .add(Restrictions.eq(Locator.PROPERTY_WAREHOUSE + ".id", warehouseId))
          .add(Restrictions.eq(Locator.PROPERTY_DEFAULT, true))
          .add(Restrictions.eq(Locator.PROPERTY_ACTIVE, true))
          .addOrder(Order.asc(Locator.PROPERTY_SEARCHKEY))
          .setMaxResults(1)
          .uniqueResult();
      return locator != null ? locator.getId() : null;
    } catch (Exception e) {
      log.debug("[GoodsReceiptLineHandler] Could not resolve default locator for warehouse {}: {}",
          warehouseId, e.getMessage());
      return null;
    }
  }

  /**
   * Strips the stock-derived {@code movementQuantity} update that {@code SL_InOutLine_Product}
   * returns on product selection (see class Javadoc). Mutates {@code previousResult} in place —
   * the same convention {@link InventoryLineHandler#afterCallout} uses to override values — and
   * returns {@code null} so the dispatcher's additive-only merge never runs.
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    if (context == null || !NeoEndpointType.CALLOUT.equals(context.getEndpointType())) {
      return null;
    }
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    JSONObject updates = previous.getBody().optJSONObject("updates");
    if (updates != null && updates.has(FIELD_MOVEMENT_QUANTITY)) {
      updates.remove(FIELD_MOVEMENT_QUANTITY);
      log.debug("[GoodsReceiptLineHandler] Stripped stock-derived movementQuantity from callout "
          + "response (ETP-4671)");
    }
    return null;
  }
}
