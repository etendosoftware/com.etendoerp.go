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
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * Commercial document line defaults and synthetic callout fields.
 */
final class NeoCommercialLinePolicy {

  private static final Logger log = LogManager.getLogger(NeoCommercialLinePolicy.class);
  private static final String VALUE_KEY = "value";
  private static final String FIELD_GROSS_UNIT_PRICE = "grossUnitPrice";
  private static final String FIELD_INVOICED_QTY = "invoicedQuantity";
  private static final String FIELD_ORDERED_QTY = "orderedQuantity";
  private static final String FIELD_UNIT_PRICE = "unitPrice";
  private static final String FIELD_LINE_NET_AMOUNT = "lineNetAmount";
  private static final String FIELD_LINE_GROSS_AMOUNT = "lineGrossAmount";
  private static final String FIELD_GROSS_AMOUNT = "grossAmount";
  private static final String FIELD_TAX = "tax";

  private NeoCommercialLinePolicy() {
  }

  /**
   * Applies the three commercial-amount injectors in dependency order. Every write path
   * (POST create, PATCH/PUT update, and the {@code /batch} channel that funnels through
   * create) must go through here rather than calling the injectors individually.
   *
   * <p><b>The order is load-bearing:</b> {@link #injectGrossAmountIfMissing} derives the gross
   * from {@code lineNetAmount} in the body, so {@link #injectLineNetAmountIfMissing} has to
   * populate it FIRST. {@link #injectLineGrossAmountIfMissing} is independent (it recomputes
   * its own base from {@code orderedQuantity} × {@code unitPrice}) and its position is free.
   *
   * <p>ETP-4855: the create path used to run gross-first while the update path ran net-first.
   * With gross-first, any client that omitted both amounts — the OCR {@code /batch} ingest, the
   * MCP write path, the line import modal — hit the NaN guard in
   * {@link #resolveGrossAmount(double, double, double, String)} and persisted
   * {@code LINE_GROSS_AMOUNT = 0}, so the line "Total" column rendered as 0 even though
   * {@code LINENETAMT} and the header totals were correct. Keeping the sequence in one place
   * is what stops the two call sites from drifting apart again.
   */
  static void injectCommercialAmounts(JSONObject body) {
    injectLineNetAmountIfMissing(body);
    injectGrossAmountIfMissing(body);
    injectLineGrossAmountIfMissing(body);
  }

  static void injectGrossAmountIfMissing(JSONObject body) {
    if (body == null) {
      return;
    }
    double qty;
    try {
      qty = Double.parseDouble(body.optString(FIELD_INVOICED_QTY, "0"));
    } catch (NumberFormatException e) {
      return;
    }
    if (qty == 0) {
      forceZeroIfEntityFieldPresent(body, FIELD_INVOICED_QTY, FIELD_GROSS_AMOUNT, qty);
      return;
    }
    double baseNetAmt = body.optDouble(FIELD_LINE_NET_AMOUNT, 0);
    String taxId = body.optString(FIELD_TAX, "");
    if (baseNetAmt > 0 && taxId.isEmpty()) {
      return;
    }
    double computed = resolveGrossAmount(body.optDouble(FIELD_GROSS_UNIT_PRICE, 0), qty, baseNetAmt, taxId);
    if (Double.isNaN(computed)) {
      return;
    }
    try {
      body.put(FIELD_GROSS_AMOUNT, computed);
      log.debug("[NEO-LINE-POLICY] Computed grossAmount={} (qty={}, tax={})", computed, qty, taxId);
    } catch (Exception e) {
      log.debug("Could not set grossAmount: {}", e.getMessage());
    }
  }

  static void injectLineGrossAmountIfMissing(JSONObject body) {
    if (body == null) {
      return;
    }
    // Client-side computation is the source of truth: if the frontend already sent a non-zero
    // lineGrossAmount, trust it and skip the server-side fallback entirely.
    double clientValue = body.optDouble(FIELD_LINE_GROSS_AMOUNT, 0);
    if (clientValue != 0) {
      log.debug("[NEO-LINE-POLICY] lineGrossAmount={} supplied by client, skipping server injection",
          clientValue);
      return;
    }
    double qty;
    try {
      qty = Double.parseDouble(body.optString(FIELD_ORDERED_QTY, "0"));
    } catch (NumberFormatException e) {
      return;
    }
    double unitPrice = body.optDouble(FIELD_UNIT_PRICE, 0);
    if (qty == 0 || unitPrice == 0) {
      // ETP-4727: editing orderedQuantity or unitPrice to exactly 0 on an EXISTING line is
      // deterministic (qty × price with either factor at 0 is always 0). See
      // forceZeroIfEntityFieldPresent's javadoc for why this fallback is needed at all and why
      // it's gated on the entity-specific quantity field rather than unitPrice.
      forceZeroIfEntityFieldPresent(body, FIELD_ORDERED_QTY, FIELD_LINE_GROSS_AMOUNT, qty);
      return;
    }
    // unitPrice (PriceActual) = PriceList × (1 − discount/100): already post-discount.
    // Do NOT apply discountFactor again — that would double the discount.
    // ETP-4567: a negative unitPrice is a legitimate line (frontend now allows negative
    // qty/price) — the branch above only catches the genuinely-zero case.
    double baseNetAmt = unitPrice * qty;
    String taxId = body.optString(FIELD_TAX, "");
    double computed = resolveGrossAmount(body.optDouble(FIELD_GROSS_UNIT_PRICE, 0), qty, baseNetAmt, taxId);
    if (Double.isNaN(computed)) {
      return;
    }
    try {
      double rounded = BigDecimal.valueOf(computed)
          .setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
      body.put(FIELD_LINE_GROSS_AMOUNT, rounded);
      log.debug("[NEO-LINE-POLICY] Computed lineGrossAmount={} (qty={}, unitPrice={}, tax={})",
          rounded, qty, unitPrice, taxId);
    } catch (Exception e) {
      log.debug("Could not set lineGrossAmount: {}", e.getMessage());
    }
  }

  static void injectLineNetAmountIfMissing(JSONObject body) {
    if (body == null) {
      return;
    }
    double qty;
    try {
      qty = Double.parseDouble(body.optString(FIELD_INVOICED_QTY, "0"));
    } catch (NumberFormatException e) {
      return;
    }
    double unitPrice = body.optDouble(FIELD_UNIT_PRICE, 0);
    if (qty == 0 || unitPrice == 0) {
      // ETP-4727: see injectLineGrossAmountIfMissing above for the full rationale — a direct
      // edit of invoicedQuantity or unitPrice to 0 on an existing line is deterministic and must
      // zero lineNetAmount, not leave it untouched in a partial PATCH.
      forceZeroIfEntityFieldPresent(body, FIELD_INVOICED_QTY, FIELD_LINE_NET_AMOUNT, qty);
      return;
    }
    try {
      double computed = qty * unitPrice;
      body.put(FIELD_LINE_NET_AMOUNT, computed);
      log.debug("[NEO-LINE-POLICY] Set lineNetAmount={} from qty={} × unitPrice={}",
          computed, qty, unitPrice);
    } catch (Exception e) {
      log.debug("Could not compute lineNetAmount: {}", e.getMessage());
    }
  }

  /**
   * ETP-4727 (backend counterpart of the frontend fix in useLineGrossAmount.js): forces
   * {@code amountField} to 0 in {@code body} when the entity-specific quantity field
   * ({@code qtyFieldKey} — {@code orderedQuantity} for order lines, {@code invoicedQuantity} for
   * invoice lines) is present in the update.
   *
   * <p>Without this, editing a qty/price field to exactly 0 on an EXISTING line silently left
   * the amount field untouched: NEO's update is a partial PATCH, so a field never written to the
   * body is left as-is in the DB, and the stale pre-edit amount survived even though the
   * frontend had already computed and sent 0 — {@code filterWriteRequest} strips the amount
   * field as read-only before any injector ever sees the client's value, so this fallback is the
   * only place left that can still get it right.
   *
   * <p>Gated on {@code qtyFieldKey} rather than {@code unitPrice}: unitPrice is the shared
   * PriceActual column both order and invoice bodies send on every full-row PATCH, so its mere
   * presence can't tell an order body from an invoice body apart (see the ETP-4855 order/invoice
   * isolation tests). A PATCH that never mentions the entity's own quantity field — an
   * invoice-only body reaching the order injector, or vice versa, or any update that only
   * touches an unrelated field — must not clobber an amount computed from values it doesn't
   * carry.
   */
  private static void forceZeroIfEntityFieldPresent(JSONObject body, String qtyFieldKey,
      String amountField, double qty) {
    if (!body.has(qtyFieldKey)) {
      return;
    }
    try {
      body.put(amountField, 0);
      log.debug("[NEO-LINE-POLICY] Forced {}=0 (qty={}, explicitly edited)", amountField, qty);
    } catch (Exception e) {
      log.debug("Could not zero {}: {}", amountField, e.getMessage());
    }
  }

  static void normalizeOrderLineSelectorPriceMapping(JSONObject body, boolean priceIncludesTax,
      String priceListIdentifier) {
    if (body == null || priceIncludesTax || body.optDouble(FIELD_GROSS_UNIT_PRICE, -1) <= 0) {
      return;
    }
    try {
      body.put(FIELD_GROSS_UNIT_PRICE, 0);
      log.debug(
          "[NEO-LINE-POLICY] Net price list '{}' — reset grossUnitPrice to 0 on new line",
          priceListIdentifier);
    } catch (Exception e) {
      log.warn("Could not reset grossUnitPrice: {}", e.getMessage());
    }
  }


  static void injectProductDerivedUomIfMissing(JSONObject body) {
    if (body == null) {
      return;
    }
    String productId = body.optString("product", "");
    if (productId.isEmpty()) {
      return;
    }
    String existingUom = body.optString("uOM", "");
    if (!existingUom.isEmpty()) {
      return;
    }
    try {
      String sql = "SELECT C_UOM_ID FROM M_PRODUCT WHERE M_PRODUCT_ID = ?";
      try (PreparedStatement ps = OBDal.getInstance().getConnection(false).prepareStatement(sql)) {
        ps.setString(1, productId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String uomId = rs.getString(1);
            if (uomId != null && !uomId.isEmpty()) {
              body.put("uOM", uomId);
              log.debug("[NEO-LINE-POLICY] Injected product-derived uOM={} for product={}", uomId, productId);
            }
          }
        }
      }
    } catch (Exception e) {
      log.debug("Could not inject product-derived UOM for product {}: {}", productId, e.getMessage());
    }
  }

  static void injectTaxRateIfPresent(JSONObject updates) {
    try {
      JSONObject taxUpdate = updates.optJSONObject("tax");
      if (taxUpdate == null) {
        return;
      }
      String taxId = taxUpdate.optString(VALUE_KEY);
      if (StringUtils.isBlank(taxId) || "null".equals(taxId)) {
        return;
      }
      TaxRate taxEntity = OBDal.getInstance().get(TaxRate.class, taxId);
      if (taxEntity == null || taxEntity.getRate() == null) {
        return;
      }
      JSONObject rateUpdate = new JSONObject();
      rateUpdate.put(VALUE_KEY, taxEntity.getRate().doubleValue());
      updates.put("taxRate", rateUpdate);
    } catch (Exception e) {
      log.debug("Could not inject tax rate into callout response: {}", e.getMessage());
    }
  }

  private static double resolveGrossAmount(double grossUnitPrice, double qty, double baseNetAmt,
      String taxId) {
    if (grossUnitPrice > 0) {
      return grossUnitPrice * qty;
    }
    // ETP-4567: baseNetAmt is legitimately negative for a negative-qty/price line
    // (the frontend now allows both). Only exact zero is indeterminate.
    if (baseNetAmt == 0) {
      return Double.NaN;
    }
    double rate = (taxId == null || taxId.isEmpty()) ? 0 : fetchTaxRate(taxId);
    return baseNetAmt * (1.0 + rate / 100.0);
  }

  private static double fetchTaxRate(String taxId) {
    String sql = "SELECT rate FROM c_tax WHERE c_tax_id = ?";
    try (PreparedStatement ps = OBDal.getInstance().getConnection(false).prepareStatement(sql)) {
      ps.setString(1, taxId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getDouble(1);
        }
      }
    } catch (Exception e) {
      log.debug("Could not fetch tax rate for taxId={}: {}", taxId, e.getMessage());
    }
    return 0;
  }
}
