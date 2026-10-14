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
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.InvoiceLine;

/**
 * Amount derivation of invoice lines, for the customizations that call it explicitly:
 * {@link #deriveAmountsOnCreate} from {@link SalesInvoiceLineHandler} (ETP-5528, sales lines only)
 * and {@link #deriveAmountsOnUpdate} from {@link InvoiceLineHandler} (ETP-5703, sales and purchase
 * lines — every entity bound to that customization is a {@code C_InvoiceLine}).
 *
 * <p><b>Entity knowledge, called explicitly.</b> The keys here are {@code C_InvoiceLine}'s:
 * {@code invoicedQuantity}, {@code lineNetAmount} ({@code LineNetAmt}) and {@code grossAmount}
 * ({@code Line_Gross_Amount}). It is reached only from those customizations (ETP-5415 T12), never
 * from a shared write path, and no entity is selected here by a property-name guard.
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
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  /** The inputs whose change re-derives the amounts of an existing line (ETP-5703). */
  private static final List<String> QUANTITY_AND_PRICE = Arrays.asList(FIELD_INVOICED_QTY,
      FIELD_UNIT_PRICE, FIELD_GROSS_UNIT_PRICE);

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
   * both columns. The value is the policy's own, rounded to the invoice currency's standard
   * precision (ETP-5703), exactly what {@code etendo_batch} persists for the same line.
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
      NeoCommercialLinePolicy.injectCommercialAmounts(view,
          NeoCurrencyPrecision.forWrite(ctx.getAdTab(), body, null));
      for (String out : AMOUNT_OUTPUTS) {
        writeIfStale(body, out, view.opt(out));
      }
    } catch (Exception e) {
      log.warn("[INVOICE-LINE-AMOUNTS] Could not derive the line amounts on {} {}: {}",
          ctx.getSpecName(), ctx.getEntityName(), e.getMessage());
    }
  }

  /**
   * Re-derives {@code lineNetAmount} and {@code grossAmount} on an update of an invoice line that
   * changes its quantity or price (ETP-5703) — sales and purchase lines alike, called from
   * {@link InvoiceLineHandler#handle}.
   *
   * <p><b>Why here.</b> {@code etendo_update} is a separate pipeline from the REST PATCH and never
   * reaches {@code NeoCrudHandler#executeUpdate}, where the REST channel re-derives these amounts;
   * an agent that changed a line's quantity kept the old amounts. It does dispatch this
   * customization's pre-hook (with {@code PUT}), so the derivation lives here and the shared MCP
   * path is unchanged.
   *
   * <p><b>The rule.</b>
   * <ul>
   *   <li>Only when the body sets {@code invoicedQuantity}, {@code unitPrice} or
   *       {@code grossUnitPrice} to a value different from the stored line's; otherwise nothing is
   *       read or written beyond that comparison.</li>
   *   <li>The inputs are the stored line overlaid with the body (an update is sparse), so a patch
   *       of the quantity alone is priced with the stored price and tax.</li>
   *   <li>The policy's function and rounding — {@link NeoCommercialLinePolicy#injectCommercialAmounts}
   *       with the invoice currency's precision ({@link NeoCurrencyPrecision}).</li>
   *   <li>An amount the caller sent is kept unless {@link LineAmountSupport#isStaleAmount} judges it
   *       server-derived (zero, or equal at 2 decimals to the derived value). A zero result IS
   *       written: a quantity set to 0 zeroes the amounts.</li>
   * </ul>
   *
   * <p><b>Other channels.</b> On REST PATCH this runs too, but changes nothing persisted: the
   * amounts are read-only for the client, so {@code filterWriteRequest} strips them and
   * {@code executeUpdate} re-derives both, unconditionally, with the same function and rounding.
   * REST {@code /batch} and {@code etendo_batch} only create.
   *
   * <p>Total: never throws.
   *
   * @param ctx the pre-hook context; its request body is mutated in place
   */
  static void deriveAmountsOnUpdate(NeoContext ctx) {
    if (ctx == null || !NeoEndpointType.CRUD.equals(ctx.getEndpointType())
        || !(METHOD_PUT.equals(ctx.getHttpMethod()) || METHOD_PATCH.equals(ctx.getHttpMethod()))
        || StringUtils.isBlank(ctx.getRecordId())) {
      return;
    }
    JSONObject body = ctx.getRequestBody();
    if (body == null || QUANTITY_AND_PRICE.stream().noneMatch(body::has)) {
      return;
    }
    try {
      InvoiceLine stored = OBDal.getInstance().get(InvoiceLine.class, ctx.getRecordId());
      if (stored == null || !changesQuantityOrPrice(body, stored)) {
        return;
      }
      JSONObject view = updateInputs(body, stored);
      NeoCommercialLinePolicy.injectCommercialAmounts(view,
          NeoCurrencyPrecision.forWrite(ctx.getAdTab(), body, ctx.getRecordId()));
      for (String out : AMOUNT_OUTPUTS) {
        writeUnlessCallers(body, out, view.opt(out));
      }
    } catch (Exception | LinkageError e) {
      log.warn("[INVOICE-LINE-AMOUNTS] Could not re-derive the line amounts on {} {} {}: {}",
          ctx.getSpecName(), ctx.getEntityName(), ctx.getRecordId(), e.getMessage());
    }
  }

  /** {@code true} when the body sets a quantity or price different from the stored line's. */
  static boolean changesQuantityOrPrice(JSONObject body, InvoiceLine stored) {
    return differs(body, FIELD_INVOICED_QTY, stored.getInvoicedQuantity())
        || differs(body, FIELD_UNIT_PRICE, stored.getUnitPrice())
        || differs(body, FIELD_GROSS_UNIT_PRICE, stored.getGrossUnitPrice());
  }

  private static boolean differs(JSONObject body, String key, BigDecimal stored) {
    if (!body.has(key)) {
      return false;
    }
    BigDecimal sent = LineAmountSupport.decimal(body.opt(key));
    return sent != null && (stored == null || sent.compareTo(stored) != 0);
  }

  /** The stored line's amount inputs overlaid with the body's. */
  private static JSONObject updateInputs(JSONObject body, InvoiceLine stored)
      throws JSONException {
    JSONObject view = amountInputs(body);
    putIfAbsent(view, FIELD_INVOICED_QTY, stored.getInvoicedQuantity());
    putIfAbsent(view, FIELD_UNIT_PRICE, stored.getUnitPrice());
    putIfAbsent(view, FIELD_GROSS_UNIT_PRICE, stored.getGrossUnitPrice());
    putIfAbsent(view, FIELD_TAX, stored.getTax() != null ? stored.getTax().getId() : null);
    return view;
  }

  private static void putIfAbsent(JSONObject view, String key, Object value)
      throws JSONException {
    if (!view.has(key) && value != null) {
      view.put(key, value);
    }
  }

  /**
   * Writes {@code derived} into {@code key}, zero included, unless the body carries a value the
   * caller chose ({@link LineAmountSupport#isStaleAmount} false).
   */
  private static void writeUnlessCallers(JSONObject body, String key, Object derived)
      throws JSONException {
    BigDecimal value = LineAmountSupport.decimal(derived);
    if (value == null) {
      return;
    }
    if (body.has(key) && !LineAmountSupport.isStaleAmount(body.opt(key), value)) {
      return;
    }
    body.put(key, value);
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
