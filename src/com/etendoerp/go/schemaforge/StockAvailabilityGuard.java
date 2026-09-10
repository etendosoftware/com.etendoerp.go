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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.onhandquantity.InventoryStatus;

/**
 * Shared write-side guard for a Goods Movement line whose requested quantity would exceed the
 * on-hand stock at the selected source storage bin (ETP-5037).
 *
 * <p>Runs on every line create/update — including an inline edit that only changes the source
 * warehouse or the quantity — so no separate "revalidate on warehouse change" logic is needed on
 * the backend: saving the edited line re-triggers this same check.
 *
 * <p>Mirrors the exact condition classic core's {@code M_Check_Stock} PL/pgSQL function applies
 * at document-completion time (see {@code src-db/database/model/functions/M_CHECK_STOCK.xml}):
 * negative stock is only blocked at a locator whose {@code M_InventoryStatus.overissue = 'N'}.
 * {@code AD_ClientInfo.allowNegativeStock} is <b>not</b> consulted by that function — it is used
 * elsewhere (average costing, {@code CostingUtils.java}) and is deliberately not read here either,
 * to stay consistent with what actually gates a movement today. Skipped entirely when the source
 * locator's inventory status allows overissue (the default "Undefined-OverIssue" status most
 * simplified GO warehouses use).
 *
 * <p>This is the single-line counterpart to {@link GoodsMovementProcessGuard}'s aggregate check,
 * which catches the cumulative case (several lines, each individually valid, that together
 * exceed stock at the same source locator) when the document is processed.
 */
final class StockAvailabilityGuard {

  private static final Logger log = LogManager.getLogger(StockAvailabilityGuard.class);

  private static final String FIELD_PRODUCT = "product";
  private static final String FIELD_STORAGE_BIN = "storageBin";
  private static final String FIELD_QUANTITY = "movementQuantity";

  private static final String SQL_ON_HAND =
      "SELECT COALESCE(SUM(qtyonhand), 0) FROM m_storage_detail "
      + "WHERE m_product_id = ? AND m_locator_id = ? AND isactive = 'Y'";

  private StockAvailabilityGuard() {
    // Static utility, not instantiable.
  }

  /** Reads an id field sent either as a plain string or as {@code {"id": "..."}}. */
  private static String resolveId(JSONObject body, String field) {
    Object value = body.opt(field);
    if (value instanceof JSONObject) {
      return StringUtils.trimToNull(((JSONObject) value).optString("id"));
    }
    if (value instanceof String) {
      return StringUtils.trimToNull((String) value);
    }
    return null;
  }

  private static BigDecimal resolveQuantity(JSONObject body) {
    if (!body.has(FIELD_QUANTITY) || body.isNull(FIELD_QUANTITY)) {
      return null;
    }
    try {
      Object value = body.opt(FIELD_QUANTITY);
      String raw = value instanceof Number ? value.toString()
          : StringUtils.trimToNull(body.optString(FIELD_QUANTITY, ""));
      return raw == null ? null : new BigDecimal(raw);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Rejects the request with HTTP 400 when the resolved product/source-bin/quantity trio
   * exceeds the on-hand stock at that bin. Returns {@code null} when valid, when the source
   * locator's inventory status allows overissue, or when the request does not carry enough
   * information to check (e.g. a PATCH touching neither product, storage bin nor quantity, with
   * no persisted fallback available).
   *
   * @param body the request body (POST or PATCH)
   * @param isPatch whether this is a PATCH request
   * @param persistedProductId supplies the persisted line's product id, used as a PATCH
   *     fallback when the body carries no {@code product} field
   * @param persistedStorageBinId supplies the persisted line's source-bin id, used as a PATCH
   *     fallback when the body carries no {@code storageBin} field
   * @param persistedQuantity supplies the persisted line's quantity, used as a PATCH fallback
   *     when the body carries no {@code movementQuantity} field
   */
  static NeoResponse rejectIfInsufficientStock(JSONObject body, boolean isPatch,
      Supplier<String> persistedProductId, Supplier<String> persistedStorageBinId,
      Supplier<BigDecimal> persistedQuantity) {
    String productId = resolveId(body, FIELD_PRODUCT);
    if (productId == null && isPatch) {
      productId = persistedProductId.get();
    }
    String storageBinId = resolveId(body, FIELD_STORAGE_BIN);
    if (storageBinId == null && isPatch) {
      storageBinId = persistedStorageBinId.get();
    }
    BigDecimal quantity = resolveQuantity(body);
    if (quantity == null && isPatch) {
      quantity = persistedQuantity.get();
    }
    if (productId == null || storageBinId == null || quantity == null
        || quantity.compareTo(BigDecimal.ZERO) <= 0) {
      // Nothing to compare yet, or a partial patch that touches neither product, source bin
      // nor quantity: let the generic CRUD / other validations handle it.
      return null;
    }
    Product product = OBDal.getInstance().get(Product.class, productId);
    Locator locator = OBDal.getInstance().get(Locator.class, storageBinId);
    if (product == null || locator == null) {
      // Unknown FK: let the generic CRUD produce the canonical FK error.
      return null;
    }
    if (allowsOverissue(locator)) {
      return null;
    }
    BigDecimal available = onHandQuantity(productId, storageBinId);
    if (available == null || available.compareTo(quantity) >= 0) {
      return null;
    }
    return insufficientStockResponse(product, locator, available, quantity);
  }

  /**
   * True when the locator's inventory status allows overissue (negative stock), the same
   * condition {@code M_Check_Stock} checks via {@code M_InventoryStatus.overissue = 'Y'}.
   * Package-visible: also reused by {@link GoodsMovementProcessGuard} for the aggregate check.
   */
  static boolean allowsOverissue(Locator locator) {
    InventoryStatus status = locator.getInventoryStatus();
    return status != null && Boolean.TRUE.equals(status.isOverissue());
  }

  /**
   * Sums {@code M_Storage_Detail.qtyonhand} for the given product/locator. Returns {@code null}
   * (fail-open: the write proceeds) if the lookup itself fails — the classic completion-time
   * check remains the final backstop regardless.
   *
   * <p>Package-visible: also reused by {@link GoodsMovementProcessGuard} for the aggregate check.
   */
  static BigDecimal onHandQuantity(String productId, String locatorId) {
    try {
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(SQL_ON_HAND)) {
        ps.setString(1, productId);
        ps.setString(2, locatorId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
        }
      }
    } catch (Exception e) {
      log.warn("[GOODS-MOVEMENTS] Could not read on-hand stock for product {} at locator {}: {}",
          productId, locatorId, e.getMessage(), e);
      return null;
    }
  }

  /**
   * Builds the translatable "quantity exceeds available stock" message used by the line-save
   * rejection below.
   */
  private static String buildInsufficientStockMessage(Product product, Locator locator,
      BigDecimal available, BigDecimal requested) {
    String warehouseName = warehouseNameOf(locator);
    String productLabel = productLabelOf(product);
    String template = OBMessageUtils.messageBD("ETGO_InsufficientStockLine");
    return template
        .replace("@requested@", requested.stripTrailingZeros().toPlainString())
        .replace("@product@", productLabel)
        .replace("@warehouse@", warehouseName)
        .replace("@available@", available.stripTrailingZeros().toPlainString());
  }

  private static String warehouseNameOf(Locator locator) {
    return locator.getWarehouse() != null ? locator.getWarehouse().getName() : locator.getIdentifier();
  }

  private static String productLabelOf(Product product) {
    return StringUtils.defaultIfBlank(product.getName(), product.getSearchKey());
  }

  private static NeoResponse insufficientStockResponse(Product product, Locator locator,
      BigDecimal available, BigDecimal requested) {
    String message = buildInsufficientStockMessage(product, locator, available, requested);
    try {
      JSONObject details = new JSONObject();
      details.put("productId", product.getId());
      details.put("productCode", productLabelOf(product));
      details.put("productName", product.getName());
      details.put("locatorId", locator.getId());
      details.put("warehouseName", warehouseNameOf(locator));
      details.put("available", available.stripTrailingZeros().toPlainString());
      details.put("requested", requested.stripTrailingZeros().toPlainString());

      JSONObject body = new JSONObject();
      body.put("status", "error");
      body.put("code", "INSUFFICIENT_STOCK");
      body.put("message", message);
      body.put("details", details);
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, body);
    } catch (JSONException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, message);
    }
  }

}
