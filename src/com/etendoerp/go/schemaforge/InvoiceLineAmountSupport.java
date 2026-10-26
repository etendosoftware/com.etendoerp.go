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
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.InvoiceLine;

/**
 * Price and amount derivation of a written invoice line, for the customizations that call it
 * explicitly: {@link SalesInvoiceLineHandler} (ETP-5528) and {@link PurchaseInvoiceLineHandler}
 * (ETP-5710).
 *
 * <p><b>Entity knowledge, called explicitly.</b> The keys here are {@code C_InvoiceLine}'s:
 * {@code invoicedQuantity}, {@code unitPrice}, {@code listPrice}, {@code etgoDiscount},
 * {@code lineNetAmount} ({@code LineNetAmt}) and {@code grossAmount} ({@code Line_Gross_Amount}).
 * It is reached only from those customizations (ETP-5415 T12), never from a shared write path, and
 * no entity is selected here by a property-name guard. Value rules shared with the order lines live
 * in {@link LineAmountSupport}; the order-line counterpart of the discount rule is
 * {@link OrderLineDiscountSupport}.
 *
 * <p><b>Why a line needs this at all.</b> Nothing on the server turns quantity, price and discount
 * into the line's amounts: the SPA computes them client side, and {@code etendo_create} /
 * {@code etendo_update} never reach {@code NeoCrudHandler#executePostCreate}, where the REST create
 * gets them. {@code EM_Etgo_Discount} has no callout and no database function reads it. A line an
 * agent wrote therefore persisted {@code LineNetAmt 0} (or the undiscounted amount) and the header
 * totals, the tax rows and the payment plan summed those zeros (ETP-5710).
 *
 * <p><b>The discount rule</b> (the form's own: {@code unitPrice = listPrice × (1 − etgoDiscount /
 * 100)}, {@code computeUnitPriceForPost}). Applied only when the line carries a non-zero discount
 * and no non-zero {@code grossUnitPrice} (an explicit gross price wins, as on order lines). On a
 * <b>create</b>, with {@code L} the list price and {@code U} the unit price in the body:
 * <ul>
 *   <li>{@code U} absent or 0, {@code L} non-zero → {@code U = L × (1 − d/100)};</li>
 *   <li>{@code U == L} (non-zero) → the discount has not been applied to it yet:
 *       {@code U = L × (1 − d/100)}. This is what {@code etendo_create} / {@code etendo_batch}
 *       carry once the shared price injector has written the price list's price;</li>
 *   <li>{@code U != L}, both non-zero → {@code U} is a price the caller chose, and it wins. This
 *       is the SPA's body (it sends the already-discounted {@code U} together with {@code L}), so
 *       the discount is never applied twice;</li>
 *   <li>{@code L} absent or 0, {@code U} non-zero → there is no list price to discount from, so the
 *       price sent is the list price: {@code L = U}, {@code U = U × (1 − d/100)} (10 × 25 at 2 %
 *       → 24.50, net 245.00). An agent that has only a price and a discount to give gets what it
 *       asked for;</li>
 *   <li>both 0 → nothing to discount.</li>
 * </ul>
 * On an <b>update</b> the patch is overlaid on the stored line and the price is re-derived only
 * when the patch itself re-prices the line — it carries {@code etgoDiscount} or {@code listPrice}
 * without a {@code unitPrice} (→ {@code U = L × (1 − d/100)}), or a {@code unitPrice} equal to the
 * non-zero list price. A patch that sends another {@code unitPrice}, or none of these keys, leaves
 * the price alone: the "price is the list price" reading above is create-only, because a stored
 * discounted price with no list price (a legacy line) would otherwise be discounted again by an
 * unrelated edit. The derived price is rounded to 6 decimals, as the SPA does. Accepted edge case:
 * a caller that deliberately sends {@code unitPrice == listPrice} together with a discount gets the
 * discount applied.
 *
 * <p><b>The price is the list price.</b> A line with a price, no discount and no list price gets
 * {@code listPrice = unitPrice} ({@link #alignListPriceWithPrice}): the grid's "Precio" column is
 * {@code listPrice}, and the form stores the price the user typed there. "No list price" is absent
 * or 0 (an explicit {@code listPrice: 0} counts as absent); a non-zero list price the caller sent or
 * the defaults cascade resolved is never overwritten.
 *
 * <p><b>An update without a discount</b> (ETP-5703, "work as the UI does"). The form's "Precio" is
 * {@code listPrice}; {@code unitPrice} is not a form input, and with no discount the SPA always
 * posts {@code unitPrice = listPrice}. So when the line's final discount is 0 (or null) and there is
 * no non-zero {@code grossUnitPrice}, a patch that carries only one of the two prices sets the other
 * to it: {@code unitPrice} alone → {@code listPrice = unitPrice} (MCP {@code {unitPrice: 1.25}} on
 * 3 × 1.50 now stores list 1.25, net 3.75, as editing Precio does); {@code listPrice} alone →
 * {@code unitPrice = listPrice}; {@code etgoDiscount} alone, taken to 0 → {@code unitPrice} = the
 * stored non-zero list price. A patch that sends both prices, or one already equal to the other —
 * every SPA body — is left alone, and so is a {@code unitPrice} equal to the one stored (re-sending
 * the same price is not a re-price, as in the rule above).
 *
 * <p><b>A new net price on a discounted line</b> (ETP-5703). With a final discount {@code d}
 * (0 &lt; d &lt; 100) and a patch carrying {@code unitPrice} but neither {@code listPrice} nor
 * {@code etgoDiscount}, a price that is not (within half a cent) what the stored list price and
 * discount already give keeps the discount and the caller's price, and the list price is worked
 * back: {@code listPrice = unitPrice / (1 − d/100)}, 6 decimals (list 1.50, 10 %,
 * {@code unitPrice 1.25} → list 1.388889, net of 3 units 3.75). The SPA body (exactly the computed
 * price), a 100 % discount, a line with no list price and a price equal to the list price are left
 * as before.
 *
 * <p><b>The amounts.</b> {@link NeoCommercialLinePolicy#injectCommercialAmounts} over the line's
 * final quantity, price, gross price and tax, rounded to the invoice currency's standard precision
 * ({@link NeoCurrencyPrecision}, ETP-5703). {@code lineNetAmount} needs no tax; {@code grossAmount} is written only when the line
 * has one (a REST or batch create reaches its pre-hook before the create cascade resolves it, and
 * {@code executePostCreate} derives the gross afterwards).
 */
final class InvoiceLineAmountSupport {

  private static final Logger log = LogManager.getLogger(InvoiceLineAmountSupport.class);

  static final String FIELD_INVOICED_QTY = InvoiceLine.PROPERTY_INVOICEDQUANTITY;
  static final String FIELD_UNIT_PRICE = InvoiceLine.PROPERTY_UNITPRICE;
  static final String FIELD_LIST_PRICE = InvoiceLine.PROPERTY_LISTPRICE;
  static final String FIELD_DISCOUNT = InvoiceLine.PROPERTY_ETGODISCOUNT;
  static final String FIELD_GROSS_UNIT_PRICE = InvoiceLine.PROPERTY_GROSSUNITPRICE;
  static final String FIELD_TAX = InvoiceLine.PROPERTY_TAX;
  static final String FIELD_LINE_NET_AMOUNT = InvoiceLine.PROPERTY_LINENETAMOUNT;
  static final String FIELD_GROSS_AMOUNT = InvoiceLine.PROPERTY_GROSSAMOUNT;
  /** {@code C_InvoiceLine.TaxBaseAmt}: the tax base, the net after the discount. */
  static final String FIELD_TAXABLE_AMOUNT = InvoiceLine.PROPERTY_TAXABLEAMOUNT;

  /** The scale of a derived unit price: the one the SPA posts ({@code toFixed(6)}). */
  static final int PRICE_SCALE = 6;

  /** {@code error.code} of the 422 returned for a discount outside 0..100. */
  static final String ERROR_INVALID_DISCOUNT = "invalidLineDiscount";
  static final int MIN_DISCOUNT = 0;
  static final int MAX_DISCOUNT = 100;
  private static final int SC_UNPROCESSABLE = 422;

  private static final String METHOD_POST = "POST";
  private static final String METHOD_PATCH = "PATCH";
  private static final String METHOD_PUT = "PUT";
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  /** Absorbs the SPA's {@code toFixed(6)} float rounding of a computed price. */
  private static final BigDecimal HALF_CENT = new BigDecimal("0.005");

  /** The inputs {@link NeoCommercialLinePolicy#injectCommercialAmounts} reads for an invoice line. */
  private static final List<String> AMOUNT_INPUTS = Arrays.asList(FIELD_INVOICED_QTY,
      FIELD_UNIT_PRICE, FIELD_GROSS_UNIT_PRICE, FIELD_TAX);

  /** A patch that carries none of these cannot change an amount of the line. */
  private static final List<String> AMOUNT_TRIGGERS = Arrays.asList(FIELD_INVOICED_QTY,
      FIELD_UNIT_PRICE, FIELD_LIST_PRICE, FIELD_DISCOUNT, FIELD_GROSS_UNIT_PRICE, FIELD_TAX);

  /** The amounts it derives for one, in the order it derives them (net first, gross from it). */
  static final List<String> AMOUNT_OUTPUTS = Arrays.asList(FIELD_LINE_NET_AMOUNT,
      FIELD_GROSS_AMOUNT);

  private InvoiceLineAmountSupport() {
  }

  /**
   * The one call an invoice-line customization makes in its pre-hook: derives on a create
   * ({@link #deriveAmountsOnCreate}) or recalculates on an update ({@link #deriveAmountsOnUpdate}),
   * and does nothing for any other request.
   */
  static NeoResponse deriveAmountsOnWrite(NeoContext ctx) {
    NeoResponse refusal = refuseIfDiscountOutOfRange(ctx);
    if (refusal != null) {
      return refusal;
    }
    deriveAmountsOnCreate(ctx);
    deriveAmountsOnUpdate(ctx);
    return null;
  }

  /**
   * Refuses a create or update whose body carries an {@code etgoDiscount} outside
   * [{@value #MIN_DISCOUNT}, {@value #MAX_DISCOUNT}]: above 100 the derived price would be negative
   * and below 0 it would act as a markup. The SPA caps the field; REST and MCP did not. 0 and 100
   * are valid (100 gives a price of 0); an absent or null discount is left alone.
   *
   * @return a 422 with {@code error.code = invalidLineDiscount}, or {@code null} to proceed
   */
  static NeoResponse refuseIfDiscountOutOfRange(NeoContext ctx) {
    if (ctx == null || !NeoEndpointType.CRUD.equals(ctx.getEndpointType())) {
      return null;
    }
    String method = ctx.getHttpMethod();
    boolean write = METHOD_POST.equals(method) || METHOD_PATCH.equals(method)
        || METHOD_PUT.equals(method);
    JSONObject body = ctx.getRequestBody();
    if (!write || body == null) {
      return null;
    }
    BigDecimal discount = LineAmountSupport.decimal(body.opt(FIELD_DISCOUNT));
    if (discount == null || (discount.signum() >= 0 && discount.compareTo(HUNDRED) <= 0)) {
      return null;
    }
    return invalidDiscount();
  }

  private static NeoResponse invalidDiscount() {
    String message = "The line discount must be between 0 and 100 (percent).";
    try {
      JSONObject error = new JSONObject();
      error.put("status", SC_UNPROCESSABLE);
      error.put("code", ERROR_INVALID_DISCOUNT);
      error.put("message", message);
      return NeoResponse.error(SC_UNPROCESSABLE, new JSONObject().put("error", error));
    } catch (JSONException e) {
      return NeoResponse.error(SC_UNPROCESSABLE, message);
    }
  }

  /**
   * Applies the discount rule and derives {@code lineNetAmount} and {@code grossAmount} on every
   * create of an invoice line, calling {@link NeoCommercialLinePolicy#injectCommercialAmounts}
   * explicitly (T12) over the line's quantity, price, gross price and tax.
   *
   * <p><b>Why here.</b> {@code etendo_create} is a separate pipeline from the REST create path and
   * never reaches {@code NeoCrudHandler#executePostCreate}, where every other channel gets these
   * amounts. Without this a line an agent created persisted {@code LineNetAmt = 0} and
   * {@code Line_Gross_Amount = 0} (Fernet at 18, qty 10, 21 %: {@code etendo_batch} 180 / 217.8,
   * {@code etendo_create} 0 / 0). The fix lives in these customizations, not in the shared MCP path,
   * so no other entity changes.
   *
   * <p><b>Sees the server-resolved price.</b> On {@code etendo_create} and {@code etendo_batch} the
   * shared price injector has already written {@code unitPrice} / {@code listPrice} from the
   * parent's price list when this pre-hook runs (it runs after {@code injectIfMissing} in both
   * {@code handleCreate} and {@code preprocessBatchOperation}), so a line created with only a
   * product and a quantity is priced here too.
   *
   * <p><b>Never overwrites the caller.</b> Each amount is written only when
   * {@link LineAmountSupport#isStaleAmount} judges the body's value server-derived — absent, zero,
   * or already equal (at 2 decimals) to what the policy derives, or, when a discount changed the
   * price, to what it derives from the price BEFORE the discount (the amount the defaults cascade
   * computed from the undiscounted price) — and only when the derived value is non-zero, so a line
   * without a price or quantity is left for the rest of the pipeline. The derivation reads a view
   * that carries only the inputs, never the body's amounts: a stale non-zero amount would otherwise
   * read as the caller's.
   *
   * <p><b>No double computation.</b> {@code executePostCreate} runs the same function over the same
   * inputs afterwards on the REST and batch channels and writes the same values, so the pre-hook's
   * write changes nothing there.
   *
   * <p><b>Typed values.</b> The amounts are written as {@link BigDecimal}, the DAL type of both
   * columns, rounded to the invoice currency's standard precision ({@link NeoCurrencyPrecision},
   * ETP-5703), exactly what {@code etendo_batch} persists for the same line.
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
    if (body == null) {
      return;
    }
    try {
      IntSupplier scale = NeoCurrencyPrecision.forWrite(ctx.getAdTab(), body, null);
      alignListPriceWithPrice(body, body, true, null);
      JSONObject undiscounted = amountsOf(amountInputs(body), scale);
      boolean discounted = applyDiscount(body, body, true, null);
      JSONObject derived = amountsOf(amountInputs(body), scale);
      zeroGrossFollowsZeroNet(derived);
      boolean withGross = hasTax(body);
      for (String out : AMOUNT_OUTPUTS) {
        if (!FIELD_GROSS_AMOUNT.equals(out) || withGross) {
          writeIfStale(body, out, derived.opt(out), discounted ? undiscounted.opt(out) : null,
              discounted, scale);
        }
      }
      writeTaxableAmount(body, netToStore(body, derived), scale);
    } catch (Exception e) {
      log.warn("[INVOICE-LINE-AMOUNTS] Could not derive the line amounts on {} {}: {}",
          ctx.getSpecName(), ctx.getEntityName(), e.getMessage());
    }
  }

  /**
   * Recalculates the line on a PATCH / PUT that changes something its amounts depend on — quantity,
   * price, list price, discount, gross price or tax — over the stored line overlaid with the patch
   * (the same shape as {@code OrderLineDiscountSupport.updateFormState}), and writes back the
   * price (see the class javadoc for when it is re-derived) and the amounts the caller did not
   * send. A sparse update reaches no derivation that sees the whole line, so without this a line
   * whose quantity was patched from 10 to 20 kept the amount of 10 and the header total stayed
   * where it was (ETP-5710).
   *
   * <p>An amount the patch carries is the caller's and is kept. A patch with none of the inputs, a
   * line that cannot be read or a request that is not a CRUD update is left untouched. Total:
   * never throws.
   *
   * @param ctx the pre-hook context; its request body is mutated in place
   */
  static void deriveAmountsOnUpdate(NeoContext ctx) {
    if (ctx == null || !NeoEndpointType.CRUD.equals(ctx.getEndpointType())
        || !(METHOD_PATCH.equals(ctx.getHttpMethod()) || METHOD_PUT.equals(ctx.getHttpMethod()))
        || StringUtils.isBlank(ctx.getRecordId())) {
      return;
    }
    JSONObject body = ctx.getRequestBody();
    if (body == null || AMOUNT_TRIGGERS.stream().noneMatch(body::has)) {
      return;
    }
    try {
      JSONObject state = storedInputs(ctx.getRecordId());
      if (state == null) {
        return;
      }
      IntSupplier scale = NeoCurrencyPrecision.forWrite(ctx.getAdTab(), body, ctx.getRecordId());
      JSONObject sent = new JSONObject(body.toString());
      BigDecimal storedUnit = LineAmountSupport.decimal(state.opt(FIELD_UNIT_PRICE));
      overlay(state, body);
      alignListPriceWithPrice(body, state, false, storedUnit);
      if (applyDiscount(body, state, false, storedUnit)) {
        state.put(FIELD_UNIT_PRICE, body.get(FIELD_UNIT_PRICE));
      }
      JSONObject derived = amountsOf(amountInputs(state), scale);
      zeroGrossFollowsZeroNet(derived);
      for (String out : AMOUNT_OUTPUTS) {
        BigDecimal value = LineAmountSupport.decimal(derived.opt(out));
        if (value != null && !sent.has(out)) {
          body.put(out, value.setScale(scale.getAsInt(), RoundingMode.HALF_UP));
        }
      }
      if (!sent.has(FIELD_TAXABLE_AMOUNT)) {
        writeTaxableAmount(body, netToStore(body, derived), scale);
      }
    } catch (Exception | LinkageError e) {
      log.warn("[INVOICE-LINE-AMOUNTS] Could not recalculate the line {} on {} {}: {}",
          ctx.getRecordId(), ctx.getSpecName(), ctx.getEntityName(), e.getMessage());
    }
  }

  /**
   * The discount rule of the class javadoc: rewrites {@code unitPrice} in {@code patch} (and, on a
   * create that had no list price, {@code listPrice}) when the line's discount is not reflected in
   * its price yet. On an update of a line without a discount it aligns the two prices instead
   * ({@link #alignUndiscountedPrice}).
   *
   * @param patch  the request body; mutated in place
   * @param state  the line as it will be stored — {@code patch} itself on a create, the stored line
   *               overlaid with the patch on an update
   * @param create whether this is a create
   * @param storedUnit the price the line had before the patch ({@code null} on a create or when it
   *               had none)
   * @return {@code true} when {@code unitPrice} was rewritten
   */
  static boolean applyDiscount(JSONObject patch, JSONObject state, boolean create,
      BigDecimal storedUnit) throws JSONException {
    if (!LineAmountSupport.isZero(state.opt(FIELD_GROSS_UNIT_PRICE))) {
      return false;
    }
    BigDecimal discount = LineAmountSupport.decimal(state.opt(FIELD_DISCOUNT));
    if (discount == null || discount.signum() == 0) {
      return !create && alignUndiscountedPrice(patch, state, storedUnit);
    }
    BigDecimal list = nonZero(state.opt(FIELD_LIST_PRICE));
    BigDecimal unit = nonZero(state.opt(FIELD_UNIT_PRICE));
    if (!create && backDeriveListPrice(patch, state, discount, list, unit)) {
      return false;
    }
    BigDecimal base = create ? createBase(patch, list, unit) : updateBase(patch, list, unit);
    if (base == null) {
      return false;
    }
    BigDecimal factor = HUNDRED.subtract(discount);
    patch.put(FIELD_UNIT_PRICE, base.multiply(factor).divide(HUNDRED, PRICE_SCALE,
        RoundingMode.HALF_UP));
    return true;
  }

  /**
   * The update rule of a line without a discount (ETP-5703): the form's "Precio" is
   * {@code listPrice}, and with no discount the SPA always posts {@code unitPrice = listPrice}
   * ({@code computeUnitPriceForPost}), so the two are the same price. A patch that sends only one
   * of them sets the other to it:
   * <ul>
   *   <li>{@code unitPrice} without {@code listPrice} → {@code listPrice = unitPrice} (0 included,
   *       as the form saves a Precio of 0);</li>
   *   <li>{@code listPrice} without {@code unitPrice} → {@code unitPrice = listPrice};</li>
   *   <li>{@code etgoDiscount} alone (taken to 0) → {@code unitPrice} = the stored non-zero list
   *       price; a legacy line with no list price keeps its price.</li>
   * </ul>
   * A patch that sends both, or one already equal to the other (every SPA body), is left alone; so
   * is a {@code unitPrice} equal to {@code storedUnit} — re-sending the same price is not a
   * re-price, the rule of {@link #alignListPriceWithPrice} (a legacy line with list 100 / price 90
   * keeps its list price on {@code {unitPrice: 90}}).
   *
   * @param storedUnit the price the line had before the patch, or {@code null}
   * @return {@code true} when {@code unitPrice} was rewritten
   */
  private static boolean alignUndiscountedPrice(JSONObject patch, JSONObject state,
      BigDecimal storedUnit) throws JSONException {
    boolean sentUnit = patch.has(FIELD_UNIT_PRICE) && !patch.isNull(FIELD_UNIT_PRICE);
    boolean sentList = patch.has(FIELD_LIST_PRICE) && !patch.isNull(FIELD_LIST_PRICE);
    BigDecimal unit = LineAmountSupport.decimal(state.opt(FIELD_UNIT_PRICE));
    if (sentUnit && !sentList) {
      if (unit != null && !sameValue(unit, storedUnit)
          && !sameValue(unit, state.opt(FIELD_LIST_PRICE))) {
        patch.put(FIELD_LIST_PRICE, unit);
        state.put(FIELD_LIST_PRICE, unit);
      }
      return false;
    }
    if (sentUnit || !(sentList || patch.has(FIELD_DISCOUNT))) {
      return false;
    }
    BigDecimal list = sentList ? LineAmountSupport.decimal(state.opt(FIELD_LIST_PRICE))
        : nonZero(state.opt(FIELD_LIST_PRICE));
    if (list == null || sameValue(list, state.opt(FIELD_UNIT_PRICE))) {
      return false;
    }
    patch.put(FIELD_UNIT_PRICE, list);
    return true;
  }

  /**
   * The update rule of a line WITH a discount whose patch sends a new net price (ETP-5703): a
   * {@code unitPrice} with no {@code listPrice} and no {@code etgoDiscount} key keeps the discount
   * and the caller's price, and the list price is worked back from them:
   * {@code listPrice = unitPrice / (1 − d/100)}, rounded to {@value #PRICE_SCALE} decimals
   * (list 1.50, 10 %, {@code unitPrice 1.25} → list 1.388889, net of 3 units 3.75).
   *
   * <p>Left alone (as before): a price within half a cent of what the stored list price and
   * discount already give — the SPA's body, which sends exactly that computed price (the tolerance
   * of {@code OrderLineDiscountSupport.shouldDeriveDiscountFromPrice}); a price equal to the list
   * price (the discount rule applies it); a 100 % discount (nothing to work back from); a line with
   * no list price, or a price of 0.
   *
   * @return {@code true} when {@code listPrice} was back-derived
   */
  private static boolean backDeriveListPrice(JSONObject patch, JSONObject state,
      BigDecimal discount, BigDecimal list, BigDecimal unit) throws JSONException {
    boolean sentUnit = patch.has(FIELD_UNIT_PRICE) && !patch.isNull(FIELD_UNIT_PRICE);
    if (!sentUnit || patch.has(FIELD_LIST_PRICE) || patch.has(FIELD_DISCOUNT) || list == null
        || unit == null || unit.compareTo(list) == 0 || discount.compareTo(HUNDRED) >= 0) {
      return false;
    }
    BigDecimal factor = HUNDRED.subtract(discount);
    BigDecimal current = list.multiply(factor).divide(HUNDRED, PRICE_SCALE, RoundingMode.HALF_UP);
    if (unit.subtract(current).abs().compareTo(HALF_CENT) < 0) {
      return false;
    }
    BigDecimal derived = unit.multiply(HUNDRED).divide(factor, PRICE_SCALE, RoundingMode.HALF_UP);
    patch.put(FIELD_LIST_PRICE, derived);
    state.put(FIELD_LIST_PRICE, derived);
    return true;
  }

  private static boolean sameValue(BigDecimal value, Object other) {
    BigDecimal decimal = LineAmountSupport.decimal(other);
    return decimal != null && decimal.compareTo(value) == 0;
  }

  /**
   * The price is the list price when the line has no list price and no discount (ETP-5710
   * follow-up): sets {@code listPrice = unitPrice} in {@code patch}.
   *
   * <p>The grid's "Precio" column is {@code listPrice}, and the form edits it and derives
   * {@code unitPrice} from it, so a form-created line at 50 stores {@code listPrice 50}. An agent
   * that sent only {@code unitPrice 50} left {@code listPrice 0} — {@code McpLinePriceInjector}
   * abstains when the price is not the product's standard one — so the amounts were right (100 /
   * 121.00) and the grid showed 0,00. A list price the caller sent, or one the defaults cascade
   * resolved (non-zero), is never overwritten, and a line with a discount follows the discount rule
   * ({@link #applyDiscount}) instead. An explicit gross price leaves the line alone, as the
   * discount rule does. "No list price" means <b>absent or 0</b>: an explicit {@code listPrice: 0}
   * is read as absent (a list price of 0 is never a price the form would store for a priced line)
   * and is replaced by the price.
   *
   * <p>On an <b>update</b> it only applies when the patch itself carries a non-null
   * {@code unitPrice} that DIFFERS from the stored one and no non-zero {@code listPrice}: re-sending
   * the same price changes nothing (a legacy or copied line with list 100 / price 90 keeps its list
   * price on {@code {unitPrice:90}}, as the form only touches it when "Precio" is edited). The form
   * has no "edit the unit price" —
   * the user edits "Precio" ({@code listPrice}) and the unit price is derived from it — so repricing
   * a line with no discount moves the list price with it, whatever list price it had stored (a line
   * created at 50 / list 50 and repriced to 60 ends at 60 / list 60). A patch that does not
   * re-price the line never invents a list price, and a line with a discount (stored or sent) keeps
   * the discount rule above. Lines already stored with {@code listPrice 0} are not repaired by
   * this; they change only when their price is edited.
   *
   * @param patch  the request body; mutated in place
   * @param state  the line as it will be stored ({@code patch} itself on a create); also receives
   *               the list price when it is written
   * @param create whether this is a create
   * @param storedUnit the price the line had before the patch ({@code null} on a create or when it
   *               had none): on an update the price must DIFFER from it to count as a re-price
   * @return {@code true} when {@code listPrice} was written
   */
  static boolean alignListPriceWithPrice(JSONObject patch, JSONObject state, boolean create,
      BigDecimal storedUnit) throws JSONException {
    // Same rule as InvoiceFromOrderSupport#derivedListPrice, there over a typed copied line.
    BigDecimal unit = nonZero(state.opt(FIELD_UNIT_PRICE));
    BigDecimal discount = LineAmountSupport.decimal(state.opt(FIELD_DISCOUNT));
    BigDecimal sent = LineAmountSupport.decimal(patch.opt(FIELD_UNIT_PRICE));
    boolean rePriced = create
        || (sent != null && (storedUnit == null || sent.compareTo(storedUnit) != 0));
    if (unit == null || !rePriced || nonZero(patch.opt(FIELD_LIST_PRICE)) != null
        || (discount != null && discount.signum() != 0)
        || !LineAmountSupport.isZero(state.opt(FIELD_GROSS_UNIT_PRICE))) {
      return false;
    }
    patch.put(FIELD_LIST_PRICE, unit);
    state.put(FIELD_LIST_PRICE, unit);
    return true;
  }

  /** The price to discount on a create, or {@code null} when the price is the caller's. */
  private static BigDecimal createBase(JSONObject body, BigDecimal list, BigDecimal unit)
      throws JSONException {
    if (unit == null) {
      return list;
    }
    if (list == null) {
      body.put(FIELD_LIST_PRICE, unit);
      return unit;
    }
    return unit.compareTo(list) == 0 ? list : null;
  }

  /** The price to discount on an update, or {@code null} when the patch does not re-price. */
  private static BigDecimal updateBase(JSONObject patch, BigDecimal list, BigDecimal unit) {
    if (list == null) {
      return null;
    }
    if (patch.has(FIELD_UNIT_PRICE) && !patch.isNull(FIELD_UNIT_PRICE)) {
      return unit != null && unit.compareTo(list) == 0 ? list : null;
    }
    return patch.has(FIELD_DISCOUNT) || patch.has(FIELD_LIST_PRICE) ? list : null;
  }

  /**
   * The net amount the line will be stored with: the body's {@code lineNetAmount} when it carries
   * one (the caller's, or one the amount writes above just set), otherwise the derived net.
   *
   * @param body    the request body, after the amount writes
   * @param derived the amounts the policy derived
   * @return the net to persist, or {@code null} when there is none
   */
  private static Object netToStore(JSONObject body, JSONObject derived) {
    if (LineAmountSupport.decimal(body.opt(FIELD_LINE_NET_AMOUNT)) != null) {
      return body.opt(FIELD_LINE_NET_AMOUNT);
    }
    return derived.opt(FIELD_LINE_NET_AMOUNT);
  }

  /**
   * Sets {@code taxableAmount} ({@code TaxBaseAmt}) to the line's final net amount, the core
   * semantics ({@code UpdateTax.updateTaxableAmount}: the net after the line discount). Unlike
   * {@link #writeIfStale} it overwrites whatever the body carries and writes a net of exactly 0
   * (100 % discount, free line): only the SPA's callout filled it, before the discount, so a line
   * written through REST or MCP kept 0 or the undiscounted quantity x price. A sign is kept as is
   * (returns). Nothing is written when there is no net to copy.
   *
   * @param body  the request body; mutated in place
   * @param net   the final net amount of the line, any JSON number or string
   * @param scale the invoice currency's standard precision
   */
  private static void writeTaxableAmount(JSONObject body, Object net, IntSupplier scale)
      throws JSONException {
    BigDecimal value = LineAmountSupport.decimal(net);
    if (value != null) {
      body.put(FIELD_TAXABLE_AMOUNT, value.setScale(scale.getAsInt(), RoundingMode.HALF_UP));
    }
  }

  /**
   * The policy leaves {@code grossAmount} indeterminate when the net is 0 (it cannot tell a missing
   * price from a free line), so a line whose net derived to exactly 0 — a 100 % discount, a free
   * line, a quantity edited to 0 — would keep its previous gross. A net of 0 with no explicit gross
   * price means a gross of 0.
   */
  private static void zeroGrossFollowsZeroNet(JSONObject derived) throws JSONException {
    BigDecimal net = LineAmountSupport.decimal(derived.opt(FIELD_LINE_NET_AMOUNT));
    if (net != null && net.signum() == 0 && !derived.has(FIELD_GROSS_AMOUNT)
        && LineAmountSupport.isZero(derived.opt(FIELD_GROSS_UNIT_PRICE))) {
      derived.put(FIELD_GROSS_AMOUNT, 0);
    }
  }

  /**
   * The amounts the policy derives over {@code inputs}, never the stored ones, rounded to
   * {@code scale} decimals.
   */
  private static JSONObject amountsOf(JSONObject inputs, IntSupplier scale) {
    NeoCommercialLinePolicy.injectCommercialAmounts(inputs, scale);
    return inputs;
  }

  /**
   * Writes {@code derived} into {@code key} when it is a non-zero number and the body's current
   * value is stale ({@link LineAmountSupport#isStaleAmount}) or equal to {@code beforeDiscount}.
   * A derived 0 is written only when {@code zeroIsDeliberate} — the discount itself took the price
   * to 0 (100 %), so the amount is 0 on purpose and an amount of the undiscounted price must not
   * survive. Without a discount a derived 0 means "no price or quantity yet" and is left for the
   * rest of the pipeline. The value written is rounded to {@code scale} decimals, the invoice
   * currency's precision (ETP-5703).
   */
  static void writeIfStale(JSONObject body, String key, Object derived, Object beforeDiscount,
      boolean zeroIsDeliberate, IntSupplier scale) throws JSONException {
    BigDecimal value = LineAmountSupport.decimal(derived);
    if (value == null || (value.signum() == 0 && !zeroIsDeliberate)) {
      return;
    }
    Object current = body.opt(key);
    if (LineAmountSupport.isStaleAmount(current, value)
        || LineAmountSupport.sameAmountAtScale(current, beforeDiscount)) {
      body.put(key, value.setScale(scale.getAsInt(), RoundingMode.HALF_UP));
    }
  }

  private static boolean hasTax(JSONObject body) {
    return !body.isNull(FIELD_TAX) && StringUtils.isNotBlank(body.optString(FIELD_TAX, null));
  }

  private static BigDecimal nonZero(Object value) {
    BigDecimal decimal = LineAmountSupport.decimal(value);
    return decimal == null || decimal.signum() == 0 ? null : decimal;
  }

  /** The amount inputs of the line, never the amounts themselves. */
  private static JSONObject amountInputs(JSONObject source) throws JSONException {
    JSONObject view = new JSONObject();
    for (String key : AMOUNT_INPUTS) {
      Object value = source.opt(key);
      if (value != null && !JSONObject.NULL.equals(value)) {
        view.put(key, value);
      }
    }
    return view;
  }

  /** {@code patch} over {@code state}, key by key. */
  private static void overlay(JSONObject state, JSONObject patch) throws JSONException {
    java.util.Iterator<String> keys = patch.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      state.put(key, patch.get(key));
    }
  }

  /**
   * The stored line's inputs, in the shape of a body (DAL property names, a foreign key as its id).
   *
   * @return the inputs, or {@code null} when the line cannot be read
   */
  private static JSONObject storedInputs(String lineId) throws JSONException {
    InvoiceLine line = OBDal.getInstance().get(InvoiceLine.class, lineId);
    if (line == null) {
      return null;
    }
    JSONObject state = new JSONObject();
    putIfPresent(state, FIELD_INVOICED_QTY, line.getInvoicedQuantity());
    putIfPresent(state, FIELD_UNIT_PRICE, line.getUnitPrice());
    putIfPresent(state, FIELD_LIST_PRICE, line.getListPrice());
    putIfPresent(state, FIELD_DISCOUNT, line.getEtgoDiscount());
    putIfPresent(state, FIELD_GROSS_UNIT_PRICE, line.getGrossUnitPrice());
    if (line.getTax() != null) {
      state.put(FIELD_TAX, line.getTax().getId());
    }
    return state;
  }

  private static void putIfPresent(JSONObject target, String key, BigDecimal value)
      throws JSONException {
    if (value != null) {
      target.put(key, value);
    }
  }
}
