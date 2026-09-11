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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.transaction.InternalMovement;
import org.openbravo.model.materialmgmt.transaction.InternalMovementLine;

/**
 * Blocks "Procesar" on a Goods Movement ({@code M_Movement}) when either (a) the cumulative
 * requested quantity for any (product, source storage bin) pair across the document's lines
 * exceeds on-hand stock, or (b) a line has a zero-or-negative quantity that classic core would
 * reject too (ETP-5037).
 *
 * <p>{@link StockAvailabilityGuard} already blocks any single line whose own quantity exceeds
 * stock at save time, so by the time (a) runs here the only remaining failure mode is the
 * cumulative one: two or more lines, individually valid, that together move more than what is
 * available from the same source warehouse.
 *
 * <p>(b) exists because neither {@link StockAvailabilityGuard} nor the cumulative check above
 * ever look at a zero-or-negative quantity — both skip those lines on purpose, leaving them for
 * "the generic CRUD / other validations" (see {@code StockAvailabilityGuard.rejectIfInsufficientStock}).
 * That other validation is classic core's {@code M_Movement_Post} function
 * ({@code src-db/database/model/functions/M_MOVEMENT_POST.xml}), which raises
 * {@code GoodsMovementsWithNegativeQty} naming only the raw line number — poor UX, and the exact
 * gap a QA pass (Emilio Polliotti) found after this guard shipped. This method mirrors that
 * function's own condition (blocks only when the source OR destination locator disallows
 * overissue) so behavior is unchanged; only the message improves, naming the product(s) instead.
 *
 * <p>Intercepts the request before it reaches the classic completion process
 * ({@code M_Movement_Post}/{@code M_Check_Stock}, both left untouched by this fix): returning a
 * non-null {@link NeoResponse} here short-circuits {@code NeoHookDispatcher}'s default action,
 * so the classic PL/pgSQL functions are never invoked when this guard rejects. Mirrors
 * {@code AbstractInvoiceHeaderHandler#validateLineQtyBeforeComplete} /
 * {@code InvoiceCalloutHelper#isInvoiceCompleteAction}.
 */
final class GoodsMovementProcessGuard {

  private static final Logger log = LogManager.getLogger(GoodsMovementProcessGuard.class);

  private static final String FIELD_PROCESS_NOW = "processNow";
  private static final String VALUE_YES = "Y";

  private GoodsMovementProcessGuard() {
    // Static utility, not instantiable.
  }

  /**
   * Returns {@code true} when the current request is a "Procesar" action on a Goods Movement
   * ({@code processNow = Y}), either via a CRUD PATCH/PUT or the named {@code processNow}
   * action endpoint.
   */
  static boolean isProcessMovementAction(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      String method = context.getHttpMethod();
      if (!"PATCH".equals(method) && !"PUT".equals(method)) {
        return false;
      }
      JSONObject body = context.getRequestBody();
      return body != null && VALUE_YES.equals(body.optString(FIELD_PROCESS_NOW, ""));
    }
    if (NeoEndpointType.ACTION.equals(context.getEndpointType())
        && FIELD_PROCESS_NOW.equals(context.getFieldName())) {
      JSONObject body = context.getRequestBody();
      if (body == null) {
        return false;
      }
      JSONObject fieldValues = body.optJSONObject("fieldValues");
      String processNow = fieldValues != null
          ? fieldValues.optString(FIELD_PROCESS_NOW, "")
          : body.optString(FIELD_PROCESS_NOW, "");
      return VALUE_YES.equals(processNow);
    }
    return false;
  }

  /**
   * Validates cumulative stock sufficiency across all of the movement's lines before letting
   * "Procesar" proceed. Returns {@code null} to let the action continue (not a process action,
   * missing record, or nothing insufficient), or a {@link NeoResponse} error listing every
   * offending product/warehouse pair. A group is skipped when its source locator's inventory
   * status allows overissue — see {@link StockAvailabilityGuard#allowsOverissue(Locator)}.
   */
  static NeoResponse validateBeforeProcess(NeoContext context) {
    if (!isProcessMovementAction(context)) {
      return null;
    }
    String movementId = context.getRecordId();
    if (StringUtils.isBlank(movementId)) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      InternalMovement movement = OBDal.getInstance().get(InternalMovement.class, movementId);
      if (movement == null) {
        return null;
      }
      Set<String> zeroOrNegativeQtyProducts = findZeroOrNegativeQtyProducts(movement);
      if (!zeroOrNegativeQtyProducts.isEmpty()) {
        return zeroOrNegativeQtyResponse(zeroOrNegativeQtyProducts);
      }
      Collection<Violation> violations = findViolations(movement);
      return violations.isEmpty() ? null : insufficientStockResponse(violations);
    } catch (Exception e) {
      log.warn("[GOODS-MOVEMENTS] Could not validate cumulative stock before process for {}: {}",
          movementId, e.getMessage(), e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Detects lines whose movement quantity is zero or negative, mirroring the exact condition
   * {@code M_MOVEMENT_POST.xml} applies at classic completion time: a line is only flagged when
   * its source ({@code M_Locator_ID}) or destination ({@code M_LocatorTo_ID}) locator has an
   * inventory status that disallows overissue — the same gate
   * {@link StockAvailabilityGuard#allowsOverissue(Locator)} already checks for the source side
   * of the insufficient-stock case. Returns the offending products' labels (deduplicated,
   * insertion order), empty when nothing is flagged.
   */
  private static Set<String> findZeroOrNegativeQtyProducts(InternalMovement movement) {
    Set<String> productLabels = new LinkedHashSet<>();
    for (InternalMovementLine line : movement.getMaterialMgmtInternalMovementLineList()) {
      Product product = line.getProduct();
      BigDecimal qty = line.getMovementQuantity();
      if (product == null || qty == null || qty.compareTo(BigDecimal.ZERO) > 0) {
        continue;
      }
      Locator source = line.getStorageBin();
      Locator destination = line.getNewStorageBin();
      boolean sourceBlocks = source != null && !StockAvailabilityGuard.allowsOverissue(source);
      boolean destinationBlocks = destination != null
          && !StockAvailabilityGuard.allowsOverissue(destination);
      if (sourceBlocks || destinationBlocks) {
        productLabels.add(StringUtils.defaultIfBlank(product.getName(), product.getSearchKey()));
      }
    }
    return productLabels;
  }

  private static NeoResponse zeroOrNegativeQtyResponse(Set<String> productLabels) {
    try {
      String template = OBMessageUtils.messageBD("ETGO_ZeroOrNegativeQtyProcess");
      String message = template.replace("@products@", String.join(", ", productLabels));

      JSONObject body = new JSONObject();
      body.put("status", "error");
      body.put("code", "ZERO_OR_NEGATIVE_QTY");
      body.put("message", message);
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, body);
    } catch (JSONException e) {
      log.warn("[GOODS-MOVEMENTS] Could not build zero-or-negative-qty process response: {}",
          e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Zero or negative quantity");
    }
  }

  private static Collection<Violation> findViolations(InternalMovement movement) {
    Map<String, BigDecimal> requestedByGroup = new LinkedHashMap<>();
    Map<String, Product> productByGroup = new LinkedHashMap<>();
    Map<String, Locator> locatorByGroup = new LinkedHashMap<>();

    for (InternalMovementLine line : movement.getMaterialMgmtInternalMovementLineList()) {
      Product product = line.getProduct();
      Locator locator = line.getStorageBin();
      BigDecimal qty = line.getMovementQuantity();
      if (product == null || locator == null || qty == null
          || qty.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      String groupKey = product.getId() + "|" + locator.getId();
      requestedByGroup.merge(groupKey, qty, BigDecimal::add);
      productByGroup.putIfAbsent(groupKey, product);
      locatorByGroup.putIfAbsent(groupKey, locator);
    }

    Map<String, Violation> violations = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> entry : requestedByGroup.entrySet()) {
      String groupKey = entry.getKey();
      BigDecimal requested = entry.getValue();
      Product product = productByGroup.get(groupKey);
      Locator locator = locatorByGroup.get(groupKey);
      if (StockAvailabilityGuard.allowsOverissue(locator)) {
        continue;
      }
      BigDecimal available = StockAvailabilityGuard.onHandQuantity(product.getId(), locator.getId());
      if (available != null && available.compareTo(requested) < 0) {
        violations.put(groupKey, new Violation(product, locator, available, requested));
      }
    }
    return violations.values();
  }

  private static NeoResponse insufficientStockResponse(Collection<Violation> violations) {
    try {
      JSONArray details = new JSONArray();
      StringBuilder detailText = new StringBuilder();
      // Named by product, not counted by group: a single product can span several offending
      // lines summed into one (product, locator) group (e.g. two Fernet lines from the same
      // warehouse, individually valid, together over the limit) — a bare count like "1 line(s)"
      // reads as if only one line were at fault, when it is really the combination. Naming the
      // product(s) instead stays correct regardless of how many physical lines contributed.
      Set<String> productLabels = new LinkedHashSet<>();
      for (Violation v : violations) {
        String warehouseName = v.locator.getWarehouse() != null
            ? v.locator.getWarehouse().getName()
            : v.locator.getIdentifier();
        String productLabel = StringUtils.defaultIfBlank(v.product.getName(),v.product.getSearchKey());
        String availableText = v.available.stripTrailingZeros().toPlainString();
        String requestedText = v.requested.stripTrailingZeros().toPlainString();
        productLabels.add(productLabel);

        JSONObject detail = new JSONObject();
        detail.put("productId", v.product.getId());
        detail.put("productCode", productLabel);
        detail.put("productName", v.product.getName());
        detail.put("locatorId", v.locator.getId());
        detail.put("warehouseName", warehouseName);
        detail.put("available", availableText);
        detail.put("requested", requestedText);
        details.put(detail);

        if (detailText.length() > 0) {
          detailText.append("; ");
        }
        detailText.append(productLabel).append(" (").append(warehouseName).append(")")
            .append(": ").append(requestedText).append(" > ").append(availableText);
      }
      String template = OBMessageUtils.messageBD("ETGO_InsufficientStockProcess");
      String message = template
          .replace("@products@", String.join(", ", productLabels))
          .replace("@details@", detailText.toString());

      JSONObject body = new JSONObject();
      body.put("status", "error");
      body.put("code", "INSUFFICIENT_STOCK");
      body.put("message", message);
      body.put("details", details);
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, body);
    } catch (JSONException e) {
      log.warn("[GOODS-MOVEMENTS] Could not build insufficient-stock process response: {}",
          e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Insufficient stock");
    }
  }

  private static final class Violation {
    private final Product product;
    private final Locator locator;
    private final BigDecimal available;
    private final BigDecimal requested;

    private Violation(Product product, Locator locator, BigDecimal available, BigDecimal requested) {
      this.product = product;
      this.locator = locator;
      this.available = available;
      this.requested = requested;
    }
  }
}
