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

  private NeoCommercialLinePolicy() {
  }

  static void injectGrossAmountIfMissing(JSONObject body) {
    if (body == null) {
      return;
    }
    double qty;
    try {
      qty = Double.parseDouble(body.optString("invoicedQuantity", "0"));
    } catch (NumberFormatException e) {
      return;
    }
    if (qty == 0) {
      return;
    }
    double baseNetAmt = body.optDouble("lineNetAmount", 0);
    String taxId = body.optString("tax", "");
    if (baseNetAmt > 0 && taxId.isEmpty()) {
      return;
    }
    double computed = resolveGrossAmount(body.optDouble(FIELD_GROSS_UNIT_PRICE, 0), qty, baseNetAmt, taxId);
    if (Double.isNaN(computed)) {
      return;
    }
    try {
      body.put("grossAmount", computed);
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
    double clientValue = body.optDouble("lineGrossAmount", 0);
    if (clientValue != 0) {
      log.debug("[NEO-LINE-POLICY] lineGrossAmount={} supplied by client, skipping server injection",
          clientValue);
      return;
    }
    double qty;
    try {
      qty = Double.parseDouble(body.optString("orderedQuantity", "0"));
    } catch (NumberFormatException e) {
      return;
    }
    if (qty == 0) {
      return;
    }
    double unitPrice = body.optDouble("unitPrice", 0);
    // unitPrice (PriceActual) = PriceList × (1 − discount/100): already post-discount.
    // Do NOT apply discountFactor again — that would double the discount.
    double baseNetAmt = unitPrice > 0 ? unitPrice * qty : 0;
    String taxId = body.optString("tax", "");
    double computed = resolveGrossAmount(body.optDouble(FIELD_GROSS_UNIT_PRICE, 0), qty, baseNetAmt, taxId);
    if (Double.isNaN(computed)) {
      return;
    }
    try {
      double rounded = BigDecimal.valueOf(computed)
          .setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
      body.put("lineGrossAmount", rounded);
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
      qty = Double.parseDouble(body.optString("invoicedQuantity", "0"));
    } catch (NumberFormatException e) {
      return;
    }
    if (qty == 0) {
      return;
    }
    double unitPrice = body.optDouble("unitPrice", 0);
    if (unitPrice == 0) {
      return;
    }
    try {
      double computed = qty * unitPrice;
      body.put("lineNetAmount", computed);
      log.debug("[NEO-LINE-POLICY] Set lineNetAmount={} from qty={} × unitPrice={}",
          computed, qty, unitPrice);
    } catch (Exception e) {
      log.debug("Could not compute lineNetAmount: {}", e.getMessage());
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
    if (baseNetAmt <= 0) {
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
