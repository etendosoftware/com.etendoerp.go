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
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.ProductPrice;
import org.openbravo.service.json.DataResolvingMode;
import org.openbravo.service.json.DataToJsonConverter;

import com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper;

/**
 * Line-price rules of the sales order and sales quotation lines, for the two customizations that
 * call it: {@link SalesOrderLineHandler} and {@link SalesQuotationLineHandler} (ETP-5528).
 *
 * <p><b>Entity knowledge, called explicitly.</b> Everything here is specific to {@code C_OrderLine}
 * — its {@code orderedQuantity}, its {@code salesOrder} parent, its {@code standardPrice}, the core
 * {@code SL_Order_Amt} callout on its {@code discount} column. That is why it is reached only from
 * the two customizations that call it (ETP-5415 T12), never from a shared write path: no entity is
 * selected here by a property-name guard. Purchase-order lines stay on the plain
 * {@link OrderLineHandler} and never get here. {@code McpLinePriceInjector} stays what it was — a
 * shared MCP compensation for every commercial line, tolerated until migration M4; nothing here
 * changes it or depends on its internals, only on the value it leaves in {@code unitPrice}.
 *
 * <p><b>Three rules, all applied in the pre-hook, in this order.</b>
 * <ol>
 *   <li>{@link #setStandardPriceOnCreate}: a created line gets the standard price of its product in
 *       the parent order's price list — the same value the form persists, whatever unit price the
 *       caller sent.</li>
 *   <li>{@link #applyDiscount}: a discount the price does not reflect yet is turned into a price by
 *       re-firing {@code SL_Order_Amt} for {@code discount}, the only code that does that.</li>
 *   <li>{@link #deriveAmountsOnCreate(NeoContext)}: every created line gets its
 *       {@code lineGrossAmount} from {@link NeoCommercialLinePolicy#injectCommercialAmounts}, which
 *       {@code neo_create} never reaches through the shared path.</li>
 * </ol>
 * The value rule that decides whether an amount in the body is the caller's or a stale
 * server-derived one lives in {@link LineAmountSupport}, shared with the sales invoice line
 * customization ({@link InvoiceLineAmountSupport}).
 *
 * <p><b>When the discount is applied — the form's own rule.</b> The form always sends
 * {@code unitPrice = listPrice × (1 − discount / 100)} ({@code computeUnitPriceForPost}). The body
 * therefore asks for the discount to be applied when it carries a {@code discount} and either
 * <ul>
 *   <li>no {@code unitPrice} at all — any discount, 0 included (0 restores the list price), or</li>
 *   <li>a non-zero {@code discount} with {@code unitPrice} equal to the UNDISCOUNTED list price —
 *       the price the discount has not been applied to yet. This is what {@code neo_create} and
 *       {@code neo_batch} carry once {@code McpLinePriceInjector} has written the list's price,
 *       and it is how a body that did not come from the form can be recognised without knowing
 *       which keys the caller sent.</li>
 * </ul>
 * Any other {@code unitPrice} is a price the caller chose and it wins. A non-zero
 * {@code grossUnitPrice} is likewise an explicit price (tax-included lists) and wins. Accepted edge
 * case: a caller that deliberately sends {@code unitPrice == listPrice} together with a non-zero
 * discount gets the discount applied — exactly what the form does with those two values.
 *
 * <p><b>{@code standardPrice} is the standard price, as in the form.</b> On a discount
 * {@code SL_Order_Amt} publishes {@code inppricestd} (and, on a tax-included list,
 * {@code inpgrosspricestd}) with the DISCOUNTED price, and the {@code unitPrice} it publishes
 * cascades into a second run that does the same. The form ends with the undiscounted standard
 * price (list 18, discount 5 persists {@code PriceStd 18, PriceActual 17.10}), so the re-fire runs
 * with {@code standardPrice} and {@code baseGrossUnitPrice} SUPPRESSED: the chain may neither
 * overwrite nor populate them, whatever the body carries. On INPUT, though, the callout is given the
 * line's current price as its standard price ({@link #alignStandardPriceWithCurrentPrice}), or it
 * would misread the line's current discount.
 *
 * <p><b>Not reachable from here:</b> the IMP-45 {@code supersededDefaults} entry {@code neo_create}
 * attaches for {@code discount} (the defaults cascade proposed 0, the caller's 5 was kept). It is
 * recorded on the router's own cascade context, not on the hook context this class receives, so it
 * is still reported although the discount is applied — declared in {@code neo-headless.md}
 * §4.12.20.
 */
final class OrderLineDiscountSupport {

  private static final Logger log = LogManager.getLogger(OrderLineDiscountSupport.class);

  static final String FIELD_DISCOUNT = "discount";
  static final String FIELD_UNIT_PRICE = "unitPrice";
  static final String FIELD_GROSS_UNIT_PRICE = "grossUnitPrice";
  static final String FIELD_LIST_PRICE = "listPrice";
  static final String FIELD_STANDARD_PRICE = OrderLine.PROPERTY_STANDARDPRICE;
  static final String FIELD_GROSS_STANDARD_PRICE = OrderLine.PROPERTY_BASEGROSSUNITPRICE;
  static final String FIELD_ORDERED_QTY = "orderedQuantity";
  static final String FIELD_TAX = "tax";
  static final String FIELD_PRODUCT = "product";
  static final String FIELD_SALES_ORDER = "salesOrder";
  static final String FIELD_PARENT_ID = "parentId";
  static final String FIELD_LINE_NET_AMOUNT = "lineNetAmount";
  static final String FIELD_LINE_GROSS_AMOUNT = "lineGrossAmount";

  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  /** The amounts {@link NeoCommercialLinePolicy#injectCommercialAmounts} derives for an order line. */
  private static final List<String> AMOUNT_OUTPUTS = Arrays.asList(FIELD_LINE_NET_AMOUNT,
      FIELD_LINE_GROSS_AMOUNT);

  /**
   * The standard prices the discount re-fire may not write: the form keeps them undiscounted. The
   * gross one is {@code C_OrderLine.GrossPriceStd}, DAL {@code baseGrossUnitPrice}.
   */
  static final Set<String> SUPPRESSED_ON_REFIRE = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList(FIELD_STANDARD_PRICE, FIELD_GROSS_STANDARD_PRICE)));

  private OrderLineDiscountSupport() {
  }

  /**
   * Sets {@code standardPrice} on a create to the standard price of the product in the parent
   * order's price list at the order date — the call core makes for a line
   * ({@link FinancialUtils#getProductPrice}), and the same one {@code McpLinePriceInjector} makes
   * for {@code unitPrice}. That is what the form persists, whatever unit price the user typed.
   * Without it a line created through {@code neo_create} or {@code neo_batch} persisted
   * {@code PriceStd 0} — or, with an explicit {@code unitPrice}, the price the defaults cascade
   * copied from it ({@code SL_Order_Amt} on {@code unitPrice} publishes {@code inppricestd}).
   *
   * <p><b>Why overwriting is safe.</b> {@code standardPrice} is published read-only
   * ({@code ETGO_SF_FIELD.IsReadOnly = Y}, visibility {@code system}) on both sales-order/lines and
   * sales-quotation/quotationLine, so it is never a value the caller chose: MCP refuses it, and the
   * form sends the product callout's value, which is this same price-list standard price.
   * {@code unitPrice} is never touched. {@code listPrice} is NOT set here: it is editable on both
   * entities, so a value in the body may be the caller's.
   *
   * <p>Abstains, leaving the body untouched, when the product or the parent order cannot be read,
   * when the order has no price list, when the list includes tax (its standard price is gross and
   * {@code standardPrice} is net — deriving the net side is {@code SL_Order_Product}'s job), or when
   * the product has no price in that list. Total: never throws.
   *
   * @param ctx the pre-hook context; its request body is mutated in place
   */
  static void setStandardPriceOnCreate(NeoContext ctx) {
    if (ctx == null || !NeoEndpointType.CRUD.equals(ctx.getEndpointType())
        || !METHOD_POST.equals(ctx.getHttpMethod())) {
      return;
    }
    JSONObject body = ctx.getRequestBody();
    if (body == null) {
      return;
    }
    try {
      BigDecimal standardPrice = resolveStandardPrice(body);
      if (standardPrice != null) {
        body.put(FIELD_STANDARD_PRICE, standardPrice);
      }
    } catch (Exception e) {
      log.warn("[ORDER-LINE-PRICE] Could not fill the standard price on {} {}: {}",
          ctx.getSpecName(), ctx.getEntityName(), e.getMessage());
    }
  }

  /**
   * @return the price list's standard price for the line's product, or {@code null} to abstain
   */
  static BigDecimal resolveStandardPrice(JSONObject body) {
    String productId = body.optString(FIELD_PRODUCT, null);
    String orderId = parentOrderId(body);
    if (StringUtils.isBlank(productId) || StringUtils.isBlank(orderId)) {
      return null;
    }
    Product product = OBDal.getInstance().get(Product.class, productId);
    Order order = OBDal.getInstance().get(Order.class, orderId);
    if (product == null || order == null || order.getOrderDate() == null) {
      return null;
    }
    PriceList priceList = order.getPriceList();
    if (priceList == null || Boolean.TRUE.equals(priceList.isPriceIncludesTax())) {
      return null;
    }
    ProductPrice productPrice = FinancialUtils.getProductPrice(product, order.getOrderDate(),
        Boolean.TRUE.equals(order.isSalesTransaction()), priceList, false);
    return productPrice != null ? productPrice.getStandardPrice() : null;
  }

  /**
   * The parent order of a create body: {@code salesOrder} on {@code neo_create} (its
   * {@code parentId} is already resolved into the FK), {@code parentId} on REST and batch creates.
   */
  private static String parentOrderId(JSONObject body) {
    String salesOrder = body.optString(FIELD_SALES_ORDER, null);
    if (StringUtils.isNotBlank(salesOrder) && !"null".equals(salesOrder)) {
      return salesOrder;
    }
    return body.optString(FIELD_PARENT_ID, null);
  }

  /**
   * Re-fires the {@code discount} callout for a create or update whose price does not reflect its
   * discount yet (see the class javadoc for the rule), and writes back the derived price and
   * amounts.
   *
   * <p>Total: never throws. A body that does not qualify, a line that cannot be read, or a callout
   * that fails leaves the body exactly as it was.
   *
   * @param ctx the pre-hook context; its request body is mutated in place
   */
  static void applyDiscount(NeoContext ctx) {
    if (ctx == null || !NeoEndpointType.CRUD.equals(ctx.getEndpointType())) {
      return;
    }
    String method = ctx.getHttpMethod();
    boolean create = METHOD_POST.equals(method);
    boolean update = METHOD_PUT.equals(method) || METHOD_PATCH.equals(method);
    JSONObject body = ctx.getRequestBody();
    // Cheap pre-check before any read: without a discount, or with an explicit gross price, the
    // full rule can never hold.
    if ((!create && !update) || !hasValue(body, FIELD_DISCOUNT)
        || sentExplicitGrossPrice(body)) {
      return;
    }
    // Every write channel builds its hook context with the tab, and NeoCalloutService reads it off
    // the context rather than off the argument, so a context without one cannot run the callout.
    Tab adTab = ctx.getAdTab();
    if (adTab == null) {
      return;
    }
    try {
      JSONObject formState = create ? createFormState(body)
          : updateFormState(ctx.getRecordId(), body);
      if (formState == null || isZero(formState.opt(FIELD_LIST_PRICE))) {
        // No list price: the callout's discount branch has nothing to discount from and would
        // publish a price of 0. Abstain rather than write that over whatever comes later.
        return;
      }
      if (!shouldApplyDiscount(body, formState.opt(FIELD_LIST_PRICE))) {
        return;
      }
      alignStandardPriceWithCurrentPrice(formState);
      JSONObject before = new JSONObject(body.toString());
      Set<String> staleAmounts = create ? serverDerivedAmounts(before)
          : Collections.<String>emptySet();
      NeoDefaultsCascadeHelper.executeCalloutsForTriggerFields(ctx, adTab, formState, body,
          Collections.singletonList(FIELD_DISCOUNT), protectedFields(body, staleAmounts),
          SUPPRESSED_ON_REFIRE);
      if (update) {
        deriveAmountsOnUpdate(body, formState, before);
      } else {
        dropStaleGrossAfterRefire(body, staleAmounts);
      }
      coerceDerivedValues(body, before);
    } catch (Exception e) {
      log.warn("[ORDER-LINE-DISCOUNT] Could not apply the discount on {} {}: {}",
          ctx.getSpecName(), ctx.getEntityName(), e.getMessage());
    }
  }

  /**
   * Makes the callout's INPUT standard price the line's current effective price, so
   * {@code SL_Order_Amt} measures the discount the line really has now.
   *
   * <p>Its {@code inpdiscount} branch only acts when the new discount differs from
   * {@code (priceList − priceStd) / priceList} — the discount it infers from {@code inppricestd}
   * ({@code grossPriceList} / {@code inpgrosspricestd} on a tax-included list). This customization
   * keeps the stored {@code standardPrice} undiscounted (18), so on a line at discount 5 that
   * inference read 0, and an update to discount 0 compared 0 with 0 and changed nothing: the line
   * kept {@code PriceActual 17.10} with discount 0. Feeding the current {@code unitPrice} (and a
   * non-zero {@code grossUnitPrice}) instead makes the inference the line's real discount. Only
   * {@code formState} is touched: {@code standardPrice} and {@code baseGrossUnitPrice} stay
   * suppressed on output, so what is persisted is unchanged. With no {@code unitPrice} (a create
   * that sent none) the standard price is left as it is.
   */
  static void alignStandardPriceWithCurrentPrice(JSONObject formState) throws JSONException {
    Object unitPrice = formState.opt(FIELD_UNIT_PRICE);
    if (decimal(unitPrice) != null) {
      formState.put(FIELD_STANDARD_PRICE, unitPrice);
    }
    Object grossUnitPrice = formState.opt(FIELD_GROSS_UNIT_PRICE);
    if (!isZero(grossUnitPrice)) {
      formState.put(FIELD_GROSS_STANDARD_PRICE, grossUnitPrice);
    }
  }

  /**
   * The form's rule (see the class javadoc): the body carries a discount, no explicit gross price,
   * and either no {@code unitPrice}, or a non-zero discount with {@code unitPrice} equal to the
   * undiscounted {@code listPrice}. Amounts compare by value, scale-insensitive
   * ({@code 18 == 18.00}).
   *
   * @param body      the write body
   * @param listPrice the line's list price — the body's on a create, the stored line overlaid with
   *                  the patch on an update
   */
  static boolean shouldApplyDiscount(JSONObject body, Object listPrice) {
    if (!hasValue(body, FIELD_DISCOUNT) || sentExplicitGrossPrice(body)) {
      return false;
    }
    if (!hasValue(body, FIELD_UNIT_PRICE)) {
      return true;
    }
    return !isZero(body.opt(FIELD_DISCOUNT)) && sameAmount(body.opt(FIELD_UNIT_PRICE), listPrice);
  }

  /**
   * A non-zero {@code grossUnitPrice} is a price the caller chose. A zero one is not: it is what a
   * net price list carries ({@code NeoCommercialLinePolicy.normalizeOrderLineSelectorPriceMapping}
   * zeroes it) and what the defaults pass injects.
   */
  static boolean sentExplicitGrossPrice(JSONObject body) {
    return hasValue(body, FIELD_GROSS_UNIT_PRICE) && !isZero(body.opt(FIELD_GROSS_UNIT_PRICE));
  }

  /**
   * Keys the callout may not overwrite: everything in the body except the price the rule just
   * decided to re-derive — {@code unitPrice} (absent, or equal to the undiscounted list price), a
   * zero {@code grossUnitPrice} — and, on a create, the amounts judged server-derived by
   * {@link #serverDerivedAmounts}.
   */
  static Set<String> protectedFields(JSONObject body, Set<String> staleAmounts) {
    Set<String> fields = new HashSet<>();
    Iterator<String> keys = body.keys();
    while (keys.hasNext()) {
      fields.add(keys.next());
    }
    fields.remove(FIELD_UNIT_PRICE);
    if (!sentExplicitGrossPrice(body)) {
      fields.remove(FIELD_GROSS_UNIT_PRICE);
    }
    fields.removeAll(staleAmounts);
    return fields;
  }

  /**
   * On a create the body cannot say whether an amount was sent by the caller or derived by the
   * server from the undiscounted price ({@code neo_create} runs the defaults cascade before the
   * pre-hook). It is judged by value: an amount is
   * server-derived when it is absent, zero, or equal (at 2 decimals) to what the undiscounted price
   * yields — {@code orderedQuantity × unitPrice} for {@code lineNetAmount} ({@code SL_Order_Amt}'s
   * formula on a net list), {@link NeoCommercialLinePolicy#injectCommercialAmounts} over the same
   * inputs for {@code lineGrossAmount}. Any other value is the caller's and is kept. Accepted edge
   * case: a caller that sends exactly the undiscounted amount together with a discount gets it
   * recomputed.
   */
  static Set<String> serverDerivedAmounts(JSONObject body) throws JSONException {
    Set<String> derived = new HashSet<>();
    BigDecimal qty = decimal(body.opt(FIELD_ORDERED_QTY));
    BigDecimal price = decimal(body.opt(FIELD_UNIT_PRICE));
    Object net = body.opt(FIELD_LINE_NET_AMOUNT);
    if (isZero(net) || (qty != null && price != null
        && LineAmountSupport.sameAmountAtScale(net, qty.multiply(price)))) {
      derived.add(FIELD_LINE_NET_AMOUNT);
    }
    JSONObject view = amountInputs(body, null);
    NeoCommercialLinePolicy.injectCommercialAmounts(view);
    Object gross = body.opt(FIELD_LINE_GROSS_AMOUNT);
    if (LineAmountSupport.isStaleAmount(gross, view.opt(FIELD_LINE_GROSS_AMOUNT))) {
      derived.add(FIELD_LINE_GROSS_AMOUNT);
    }
    return derived;
  }

  /**
   * On create the body is the whole line. The only thing missing for the callout is the parent
   * order, which a REST or batch create still carries as {@code parentId} at this point.
   */
  static JSONObject createFormState(JSONObject body) throws JSONException {
    JSONObject formState = new JSONObject(body.toString());
    if (!formState.has(FIELD_SALES_ORDER) && StringUtils.isNotBlank(
        formState.optString(FIELD_PARENT_ID, null))) {
      formState.put(FIELD_SALES_ORDER, formState.getString(FIELD_PARENT_ID));
    }
    return formState;
  }

  /**
   * On update the body is a patch, so the callout would read zeros for quantity, list price and
   * tax. It reads the stored line overlaid with the patch instead, in the same flat shape a read
   * returns (DAL property names, a foreign key as its id).
   *
   * @return the overlay, or {@code null} when the line cannot be read
   */
  static JSONObject updateFormState(String recordId, JSONObject patch) throws JSONException {
    if (StringUtils.isBlank(recordId)) {
      return null;
    }
    BaseOBObject line = OBDal.getInstance().get(OrderLine.class, recordId);
    if (line == null) {
      return null;
    }
    DataToJsonConverter converter = OBProvider.getInstance().get(DataToJsonConverter.class);
    JSONObject formState = converter.toJsonObject(line, DataResolvingMode.FULL);
    Iterator<String> keys = patch.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      formState.put(key, patch.get(key));
    }
    return formState;
  }

  /**
   * A sparse update reaches no amount derivation that sees the whole line, so derive
   * {@code lineNetAmount} / {@code lineGrossAmount} here over the line's final quantity, price and
   * tax, and write back the ones the caller did not send.
   *
   * <p>The view carries only those inputs, never the stored amounts: a stale non-zero
   * {@code lineGrossAmount} would otherwise read as a client-supplied one and be kept.
   */
  static void deriveAmountsOnUpdate(JSONObject body, JSONObject formState, JSONObject sentBody)
      throws JSONException {
    JSONObject view = amountInputs(body, formState);
    NeoCommercialLinePolicy.injectCommercialAmounts(view);
    for (String out : AMOUNT_OUTPUTS) {
      if (view.has(out) && !sentBody.has(out)) {
        body.put(out, view.get(out));
      }
    }
  }

  /**
   * After a re-fire on create, {@code lineNetAmount} is already the callout's (it was left
   * unprotected when judged server-derived). {@code lineGrossAmount} is not — {@code SL_Order_Amt}
   * publishes {@code grossUnitPrice × qty}, 0 on a net list, or the undiscounted gross on a
   * tax-included one — so when it was judged server-derived it is dropped here, and
   * {@link #deriveAmountsOnCreate(NeoContext)} derives it over the final price right after.
   */
  static void dropStaleGrossAfterRefire(JSONObject body, Set<String> staleAmounts) {
    if (staleAmounts.contains(FIELD_LINE_GROSS_AMOUNT)) {
      body.remove(FIELD_LINE_GROSS_AMOUNT);
    }
  }

  /**
   * Derives {@code lineGrossAmount} on EVERY create of a sales order or quotation line, with or
   * without a discount, calling {@link NeoCommercialLinePolicy#injectCommercialAmounts} explicitly
   * (T12) over the line's final quantity, price, gross price and tax. Runs after
   * {@link #applyDiscount}, so it reads the discounted price.
   *
   * <p><b>Why here.</b> {@code neo_create} is a separate pipeline from the REST create path and
   * never reaches {@code NeoCrudHandler#executePostCreate}, where every other channel gets these
   * amounts. Without this a line an agent created kept {@code LINE_GROSS_AMOUNT = 0} on a net price
   * list ({@code SL_Order_Amt} publishes {@code grossUnitPrice × qty}, which is 0 there). The fix
   * lives in these two customizations, not in the shared MCP path, so no other entity changes.
   *
   * <p><b>Never overwrites the caller.</b> Judged by value, like
   * {@link #serverDerivedAmounts}: a {@code lineGrossAmount} that is absent, zero, or already equal
   * (at 2 decimals) to what the policy derives is written; any other value is the caller's and is
   * kept. {@code lineNetAmount} is not touched: the policy only derives it from
   * {@code invoicedQuantity}, which an order-line view does not carry — it comes from the callout.
   *
   * <p><b>No double computation.</b> Without a {@code tax} in the body — a REST or batch create
   * reaches its pre-hook before the create cascade resolves one — this abstains rather than derive
   * at a 0 % rate, and {@code executePostCreate} derives it after its cascade, as it always has.
   * With a tax, the value written here is non-zero, so {@code executePostCreate}'s own
   * {@code injectCommercialAmounts} keeps it (it only fills a zero), and it is the same function over
   * the same inputs anyway. Total: never throws.
   *
   * @param ctx the pre-hook context; its request body is mutated in place
   */
  static void deriveAmountsOnCreate(NeoContext ctx) {
    if (ctx == null || !NeoEndpointType.CRUD.equals(ctx.getEndpointType())
        || !METHOD_POST.equals(ctx.getHttpMethod())) {
      return;
    }
    JSONObject body = ctx.getRequestBody();
    if (body == null || !hasValue(body, FIELD_TAX)) {
      return;
    }
    try {
      JSONObject view = amountInputs(body, null);
      NeoCommercialLinePolicy.injectCommercialAmounts(view);
      if (!view.has(FIELD_LINE_GROSS_AMOUNT)) {
        return;
      }
      Object current = body.opt(FIELD_LINE_GROSS_AMOUNT);
      if (LineAmountSupport.isStaleAmount(current, view.opt(FIELD_LINE_GROSS_AMOUNT))) {
        body.put(FIELD_LINE_GROSS_AMOUNT, view.get(FIELD_LINE_GROSS_AMOUNT));
      }
    } catch (Exception e) {
      log.warn("[ORDER-LINE-AMOUNTS] Could not derive the line amounts on {} {}: {}",
          ctx.getSpecName(), ctx.getEntityName(), e.getMessage());
    }
  }

  /**
   * The amount inputs of a line — quantity, price, gross price and tax — read from {@code body},
   * falling back to {@code fallback} (the stored line on an update). Never the amounts themselves.
   */
  private static JSONObject amountInputs(JSONObject body, JSONObject fallback)
      throws JSONException {
    JSONObject view = new JSONObject();
    for (String key : Arrays.asList(FIELD_ORDERED_QTY, FIELD_UNIT_PRICE, FIELD_GROSS_UNIT_PRICE,
        FIELD_TAX)) {
      Object value = body.has(key) || fallback == null ? body.opt(key) : fallback.opt(key);
      if (value != null && !JSONObject.NULL.equals(value)) {
        view.put(key, value);
      }
    }
    return view;
  }

  /**
   * The callout publishes every value as a string. The REST paths coerce the body after the
   * pre-hook, but {@code neo_create} and {@code neo_update} coerced it before, so the values written
   * here are coerced here — only those, so nothing the caller sent is touched a second time.
   */
  private static void coerceDerivedValues(JSONObject body, JSONObject before)
      throws JSONException {
    JSONObject derived = new JSONObject();
    Iterator<String> keys = body.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = body.get(key);
      if (value instanceof String && !value.equals(before.opt(key))) {
        derived.put(key, value);
      }
    }
    if (derived.length() == 0) {
      return;
    }
    NeoTypeCoercionHelper.coerceTypes(derived, OrderLine.ENTITY_NAME);
    Iterator<String> derivedKeys = derived.keys();
    while (derivedKeys.hasNext()) {
      String key = derivedKeys.next();
      body.put(key, derived.get(key));
    }
  }

  private static boolean hasValue(JSONObject body, String key) {
    return body != null && body.has(key) && !body.isNull(key);
  }

  /** Scale-insensitive equality of two amounts; {@code false} when either is not a number. */
  static boolean sameAmount(Object a, Object b) {
    BigDecimal x = decimal(a);
    BigDecimal y = decimal(b);
    return x != null && y != null && x.compareTo(y) == 0;
  }

  private static BigDecimal decimal(Object value) {
    return LineAmountSupport.decimal(value);
  }

  /** Absent, null, zero, or not a number all read as zero ({@link LineAmountSupport#isZero}). */
  static boolean isZero(Object value) {
    return LineAmountSupport.isZero(value);
  }
}
