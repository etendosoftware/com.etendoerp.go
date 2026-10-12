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
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.invoice.InvoiceLine;

/**
 * Amount derivation of a created sales invoice line, for the one customization that calls it:
 * {@link SalesInvoiceLineHandler} (ETP-5528).
 *
 * <p><b>Entity knowledge, called explicitly.</b> The keys here are {@code C_InvoiceLine}'s:
 * {@code invoicedQuantity}, {@code lineNetAmount} ({@code LineNetAmt}) and {@code grossAmount}
 * ({@code Line_Gross_Amount}). It is reached only from that customization (ETP-5415 T12), never
 * from a shared write path, and no entity is selected here by a property-name guard. Purchase
 * invoice lines stay on the plain {@link InvoiceLineHandler} and never get here.
 *
 * <p><b>Amounts only.</b> Prices, the standard price and the line discount ({@code etgoDiscount})
 * are not read for any decision and never written.
 */
final class InvoiceLineAmountSupport {

  private static final Logger log = LogManager.getLogger(InvoiceLineAmountSupport.class);

  static final String FIELD_INVOICED_QTY = InvoiceLine.PROPERTY_INVOICEDQUANTITY;
  static final String FIELD_UNIT_PRICE = InvoiceLine.PROPERTY_UNITPRICE;
  static final String FIELD_GROSS_UNIT_PRICE = InvoiceLine.PROPERTY_GROSSUNITPRICE;
  static final String FIELD_TAX = InvoiceLine.PROPERTY_TAX;
  static final String FIELD_LINE_NET_AMOUNT = InvoiceLine.PROPERTY_LINENETAMOUNT;
  static final String FIELD_GROSS_AMOUNT = InvoiceLine.PROPERTY_GROSSAMOUNT;

  private static final String METHOD_POST = "POST";

  /** The inputs {@link NeoCommercialLinePolicy#injectCommercialAmounts} reads for an invoice line. */
  private static final List<String> AMOUNT_INPUTS = Arrays.asList(FIELD_INVOICED_QTY,
      FIELD_UNIT_PRICE, FIELD_GROSS_UNIT_PRICE, FIELD_TAX);

  /** The amounts it derives for one, in the order it derives them (net first, gross from it). */
  static final List<String> AMOUNT_OUTPUTS = Arrays.asList(FIELD_LINE_NET_AMOUNT,
      FIELD_GROSS_AMOUNT);

  private InvoiceLineAmountSupport() {
  }

  /**
   * Derives {@code lineNetAmount} and {@code grossAmount} on every create of a sales invoice line,
   * calling {@link NeoCommercialLinePolicy#injectCommercialAmounts} explicitly (T12) over the
   * line's quantity, price, gross price and tax.
   *
   * <p><b>Why here.</b> {@code etendo_create} is a separate pipeline from the REST create path and
   * never reaches {@code NeoCrudHandler#executePostCreate}, where every other channel gets these
   * amounts. Without this a line an agent created persisted {@code LineNetAmt = 0} and
   * {@code Line_Gross_Amount = 0} (Fernet at 18, qty 10, 21 %: {@code etendo_batch} 180 / 217.8,
   * {@code etendo_create} 0 / 0). The fix lives in this customization, not in the shared MCP path,
   * so no other entity changes.
   *
   * <p><b>Never overwrites the caller.</b> Each amount is written only when
   * {@link LineAmountSupport#isStaleAmount} judges the body's value server-derived — absent, zero,
   * or already equal (at 2 decimals) to what the policy derives — and only when the derived value
   * is non-zero, so a line without a price or quantity is left for the rest of the pipeline. The
   * derivation reads a view that carries only the inputs, never the body's amounts, the same as the
   * order-line customization: a stale non-zero amount would otherwise read as the caller's.
   *
   * <p><b>No double computation.</b> Without a {@code tax} in the body — a REST or batch create
   * reaches its pre-hook before the create cascade resolves one — this abstains, and
   * {@code executePostCreate} derives both amounts after its cascade, as it always has. With a tax,
   * {@code executePostCreate} runs the same function over the same inputs afterwards and writes the
   * same values, so the pre-hook's write changes nothing on those channels. On {@code etendo_create}
   * the tax is mandatory and already resolved by the defaults cascade when the pre-hook runs.
   *
   * <p><b>Typed values.</b> On {@code etendo_create} the pre-hook runs after
   * {@code coerceFieldTypes}, so the amounts are written as {@link BigDecimal}, the DAL type of
   * both columns. The value is the policy's own, unrounded, exactly what {@code etendo_batch}
   * persists for the same line.
   *
   * <p>Total: never throws.
   *
   * @param ctx the pre-hook context; its request body is mutated in place
   */
  static void deriveAmountsOnCreate(NeoContext ctx) {
    if (ctx == null || !NeoEndpointType.CRUD.equals(ctx.getEndpointType())
        || !METHOD_POST.equals(ctx.getHttpMethod())) {
      return;
    }
    JSONObject body = ctx.getRequestBody();
    if (body == null || body.isNull(FIELD_TAX)
        || StringUtils.isBlank(body.optString(FIELD_TAX, null))) {
      return;
    }
    try {
      JSONObject view = amountInputs(body);
      NeoCommercialLinePolicy.injectCommercialAmounts(view);
      for (String out : AMOUNT_OUTPUTS) {
        writeIfStale(body, out, view.opt(out));
      }
    } catch (Exception e) {
      log.warn("[INVOICE-LINE-AMOUNTS] Could not derive the line amounts on {} {}: {}",
          ctx.getSpecName(), ctx.getEntityName(), e.getMessage());
    }
  }

  /**
   * Writes {@code derived} into {@code key} when it is a non-zero number and the body's current
   * value is stale ({@link LineAmountSupport#isStaleAmount}).
   */
  static void writeIfStale(JSONObject body, String key, Object derived) throws JSONException {
    BigDecimal value = LineAmountSupport.decimal(derived);
    if (value == null || value.signum() == 0) {
      return;
    }
    if (LineAmountSupport.isStaleAmount(body.opt(key), value)) {
      body.put(key, value);
    }
  }

  /** The amount inputs of the line, never the amounts themselves. */
  private static JSONObject amountInputs(JSONObject body) throws JSONException {
    JSONObject view = new JSONObject();
    for (String key : AMOUNT_INPUTS) {
      Object value = body.opt(key);
      if (value != null && !JSONObject.NULL.equals(value)) {
        view.put(key, value);
      }
    }
    return view;
  }
}
