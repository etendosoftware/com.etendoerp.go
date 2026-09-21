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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * Shared base for order-type header handlers (Sales Order, Purchase Order, Sales Quotation).
 *
 * <p>The {@code afterHandle} post-hook appends {@code hasLinkedDocuments} to every
 * record in GET responses. Single-record GETs use a LIMIT 1 query; list GETs
 * use a single batch IN query to avoid N+1. Subclasses only need to implement
 * {@code handle()} with their window-specific action dispatching.
 *
 * <p>The same post-hook also appends {@code needsPrimaryDoc} and {@code needsInvoiceDoc} to
 * every GET record (ETP-5295) — see {@link #annotatePendingDocuments}. Both use neutral names
 * across sales and purchase, and both take exactly three batched queries per response
 * regardless of page size, so the row kebab menu can reproduce the detail form's "Gestionar"
 * decision without a single extra HTTP request.
 *
 * <p>The same post-hook also adjusts {@code grandTotalAmount} via {@link #applyTotalDiscountToRecord}
 * for draft documents carrying a pending total discount — see that method's Javadoc for details.
 * Subclasses must implement {@link #getTotalDiscountService()} to expose their injected
 * {@link TotalDiscountService} for this purpose.
 *
 * <p>It also corrects {@code invoiceStatus}/{@code deliveryStatus}/{@code deliveryStatusPurchase}
 * via {@link #applyCorrectedStatusPercentages} (ETP-5317) — see that method's Javadoc for why the
 * core-computed values are wrong on documents carrying a Total Discount line.
 *
 * <p>The static helper {@link #applyTotalDiscountBeforeComplete(NeoContext, TotalDiscountService, boolean)}
 * is called from the pre-hook ({@code handle()}) of each header subclass. It creates the discount
 * line just before the Complete action (documentAction=CO) is processed by the CRUD layer, so the
 * discount line reflects the final set of product lines and is included in the completed document.
 */
public abstract class AbstractOrderHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(AbstractOrderHeaderHandler.class);
  private static final String FIELD_DOCUMENT_ACTION = "documentAction";
  private static final String FIELD_CURRENCY = "currency";
  private static final String FIELD_PRICE_LIST = "priceList";
  private static final String FIELD_VALUE = "value";
  private static final String DOC_TYPE_ORDER = "order";
  private static final String DOC_TYPE_INVOICE = "invoice";
  private static final String FIELD_PROCESSED = "processed";
  private static final String FIELD_TOTAL_DISCOUNT_PCT = "etgoTotalDiscount";
  private static final String FIELD_GRAND_TOTAL_AMOUNT = "grandTotalAmount";
  private static final String FIELD_ID = "id";
  private static final String FIELD_ORDER_DATE = "orderDate";
  private static final String FIELD_ACCOUNTING_DATE = "accountingDate";
  /**
   * Deliberately neutral names (ETP-5295): the SAME two keys are annotated for sales and for
   * purchase, so the shared frontend hook that reads them ({@code useOrderWindow.jsx}) needs no
   * per-window parameterization. "Primary doc" is the goods shipment for a sales order and the
   * goods receipt for a purchase order — both rows of {@code M_InOut}.
   */
  private static final String FIELD_NEEDS_PRIMARY_DOC = "needsPrimaryDoc";
  private static final String FIELD_NEEDS_INVOICE_DOC = "needsInvoiceDoc";
  private static final String DOC_STATUS_DRAFT = "DR";
  private static final String DOC_STATUS_COMPLETED = "CO";
  private static final String FIELD_INVOICE_STATUS = "invoiceStatus";
  private static final String FIELD_DELIVERY_STATUS = "deliveryStatus";
  private static final String FIELD_DELIVERY_STATUS_PURCHASE = "deliveryStatusPurchase";

  /**
   * Supplies the concrete handler's own {@code @Inject}-ed {@link TotalDiscountService} so the
   * shared GET-enrichment loop in {@link #afterHandle} can adjust {@code grandTotalAmount}
   * without this abstract class holding a CDI-injected field itself — consistent with the rest
   * of this class, where collaborators are always passed in rather than injected here (see
   * {@link #applyTotalDiscountBeforeComplete}).
   */
  protected abstract TotalDiscountService getTotalDiscountService();

  /**
   * Mirrors the single visible {@code orderDate} field into the hidden {@code accountingDate}
   * field on the request body, unconditionally, before the default CRUD path persists it
   * (ETP-4531 — unified date). The user never sees or edits accountingDate directly; whatever
   * value is saved for orderDate (create or update) must also become the order's accounting
   * date.
   *
   * <p>Call at the very top of each subclass's {@code handle()} override, before any other
   * logic.
   *
   * <p><b>ETP-4531 fix:</b> must fire on {@code PATCH} as well as {@code POST}/{@code PUT} — the
   * live React UI ({@code useEntity.js#getMethod}) always sends {@code PATCH} (a sparse,
   * changed-fields-only body) when saving an edit to an EXISTING order; it never sends a full
   * {@code PUT}. See {@link NeoHandlerUtils#isWriteMethod}.
   *
   * @param context the current NeoContext
   */
  static void mirrorAccountingDate(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      NeoHandlerUtils.mirrorFieldValue(context.getRequestBody(), FIELD_ORDER_DATE, FIELD_ACCOUNTING_DATE);
    }
  }

  /**
   * Creates (or re-creates) the total discount line immediately before the Complete action
   * (documentAction=CO) is processed by the default handler.
   *
   * <p>Must be called at the top of {@code handle()} in every header subclass that supports
   * total discount. It intercepts two paths:
   * <ul>
   *   <li><b>CRUD PATCH/PUT</b> — body contains {@code { documentAction: "CO" }}</li>
   *   <li><b>ACTION POST /documentAction</b> — frontend confirm button sends
   *       POST to {@code /action/documentAction} with body
   *       {@code { fieldValues: { documentAction: "CO" } }}</li>
   * </ul>
   *
   * <p>Document types that use a different action endpoint (e.g. quotations via
   * {@code DocAction}) must call {@link #syncTotalDiscountOnDocAction} explicitly
   * in their own {@code handle()} implementation.
   *
   * @param context   the current NeoContext
   * @param service   the TotalDiscountService CDI bean injected by the subclass
   * @param isInvoice {@code true} for invoice documents, {@code false} for order documents
   */
  static void applyTotalDiscountBeforeComplete(NeoContext context, TotalDiscountService service,
      boolean isInvoice) {
    if (service == null) {
      return;
    }
    String recordId = context.getRecordId();
    if (recordId == null || recordId.isEmpty()) {
      return;
    }
    if (!isCompleteAction(context)) {
      return;
    }
    String docType = isInvoice ? DOC_TYPE_INVOICE : DOC_TYPE_ORDER;
    try {
      log.info("Recalculating total discount before complete for {} id={}", docType, recordId);
      service.recalculate(recordId, isInvoice);
    } catch (Exception e) {
      log.error("Error recalculating total discount before complete for {} id={}", docType, recordId, e);
    }
  }

  private static boolean isCompleteAction(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return isCrudComplete(context);
    }
    return NeoEndpointType.ACTION.equals(context.getEndpointType())
        && isActionDocumentActionComplete(context);
  }

  private static boolean isCrudComplete(NeoContext context) {
    String method = context.getHttpMethod();
    if (!"PATCH".equals(method) && !"PUT".equals(method)) {
      return false;
    }
    JSONObject body = context.getRequestBody();
    return body != null && "CO".equals(body.optString(FIELD_DOCUMENT_ACTION, ""));
  }

  private static boolean isActionDocumentActionComplete(NeoContext context) {
    if (!FIELD_DOCUMENT_ACTION.equals(context.getFieldName())) {
      return false;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return false;
    }
    // Two body formats: root-level docAction (useDocumentAction hook)
    // or nested fieldValues.documentAction (handleSaveAndProcess path).
    JSONObject fieldValues = body.optJSONObject("fieldValues");
    String docAction = fieldValues != null
        ? fieldValues.optString(FIELD_DOCUMENT_ACTION, "")
        : body.optString("docAction", body.optString(FIELD_DOCUMENT_ACTION, ""));
    return "CO".equals(docAction);
  }

  /**
   * Syncs the total discount line when a {@code DocAction} process-button request is received.
   *
   * <p>The quotation {@code SendToEvaluationModal} sends
   * {@code POST /action/DocAction { fieldValues: {} }} without an explicit {@code docAction}
   * value. This helper delegates to {@link TotalDiscountService#recalculate} unconditionally:
   * when {@code pct > 0} (CO path) it creates the discount line; when the document is
   * reopened (RE path) it cleans up any stale line.
   *
   * <p>Call this from {@code handle()} only in handlers whose window uses the {@code DocAction}
   * button path to complete/evaluate the document — currently only
   * {@link SalesQuotationHeaderHandler}.
   *
   * @param context   the current NeoContext
   * @param service   the TotalDiscountService CDI bean injected by the subclass
   * @param isInvoice {@code true} for invoice documents, {@code false} for order documents
   */
  static void syncTotalDiscountOnDocAction(NeoContext context, TotalDiscountService service,
      boolean isInvoice) {
    if (service == null) {
      return;
    }
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return;
    }
    if (!"DocAction".equals(context.getFieldName())) {
      return;
    }
    String recordId = context.getRecordId();
    if (recordId == null || recordId.isEmpty()) {
      return;
    }
    String docType = isInvoice ? DOC_TYPE_INVOICE : DOC_TYPE_ORDER;
    try {
      log.info("Syncing total discount on DocAction for {} id={}", docType, recordId);
      service.recalculate(recordId, isInvoice);
    } catch (Exception e) {
      log.error("Error syncing total discount on DocAction for {} id={}", docType, recordId, e);
    }
  }

  // -----------------------------------------------------------------------
  // Currency / price-list / exchange-rate hooks (ETP-4027)
  // -----------------------------------------------------------------------

  /**
   * Post-callout hook shared by all order-header handlers.
   *
   * <p>Three behaviors, evaluated in order:
   * <ol>
   *   <li><b>Block callout-driven currency updates.</b> When a callout (e.g.
   *       {@code SL_Order_PriceList} or {@code SE_Order_BPartner}) pushes a
   *       {@code currency} key in its {@code updates} map, we remove it. Currency
   *       is only changed by the user directly.</li>
   *   <li><b>Price list fallback.</b> When {@code SE_Order_BPartner} returns a
   *       {@code priceList} whose {@code M_PriceList.IsActive = 'N'}, replace it
   *       with the client's first active price list of the correct type and append
   *       a WARNING message.</li>
   *   <li><b>Exchange rate warning.</b> When the user directly changes
   *       {@code currency}, check whether a {@code C_Conversion_Rate} row exists
   *       for (docCurrency → orgCurrency, orderDate). If none exists, append a
   *       WARNING message so the user can create the rate before confirming.</li>
   * </ol>
   *
   * <p>All mutations are applied directly to the callout response body so they
   * survive even if the handler returns {@code null}. The dispatcher merges only
   * {@code updates}/{@code combos} from the returned response; messages and
   * removals must be applied in-place.
   *
   * @param context callout context; {@code previousResult} carries the callout response
   * @return {@code null} — mutations are applied in-place on the body
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    try {
      NeoHandlerUtils.CalloutFields fields = NeoHandlerUtils.extractCalloutFields(context);
      if (fields == null) {
        return null;
      }
      blockCalloutCurrencyUpdate(fields.updates(), fields.triggerField());
      if ("businessPartner".equals(fields.triggerField()) && fields.updates() != null
          && fields.updates().has(FIELD_PRICE_LIST)) {
        applyPriceListFallbackIfNeeded(fields.body(), fields.updates());
      }
      checkExchangeRateWarning(fields.body(), fields.requestBody(), fields.formState(), fields.triggerField());
    } catch (Exception e) {
      log.warn("[ETP-4027] afterCallout failed (non-fatal): {}", e.getMessage());
    }
    return null; // mutations applied in-place; dispatcher merges nothing extra
  }

  private static void blockCalloutCurrencyUpdate(JSONObject updates, String triggerField) {
    if (updates != null && updates.has(FIELD_CURRENCY) && !FIELD_CURRENCY.equals(triggerField)) {
      updates.remove(FIELD_CURRENCY);
      log.debug("[ETP-4027] Removed callout-driven currency update (trigger={})", triggerField);
    }
  }

  /**
   * Appends a {@code WARNING} to the callout body when the user directly changes the order's
   * currency and no rate is available for (orgCurrency → docCurrency, orderDate).
   *
   * <p>ETP-4838: rate availability is resolved by {@link NeoExchangeRateService#hasRate} — the same
   * lookup {@code GET /sws/neo/validate-exchange-rate} serves the frontend — so the callout warning
   * and the frontend's own pre-check can never disagree.
   */
  private static void checkExchangeRateWarning(JSONObject body, JSONObject requestBody,
      JSONObject formState, String triggerField) {
    if (formState == null) {
      return;
    }
    if (!FIELD_CURRENCY.equals(triggerField) && !"currencyid".equals(triggerField)) {
      return;
    }
    // Use requestBody.value (the newly selected currency) instead of formState.currency,
    // which may still carry the previous value when the callout fires.
    String docCurrencyId = requestBody != null ? requestBody.optString(FIELD_VALUE, "") : "";
    if (docCurrencyId.isEmpty()) {
      docCurrencyId = formState.optString("currencyid", "");
    }
    String orderDate = formState.optString("orderDate", "");
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(orgId);
    if (!docCurrencyId.isEmpty() && orgCurrencyId != null
        && !docCurrencyId.equals(orgCurrencyId) && !orderDate.isEmpty()
        && !NeoExchangeRateService.hasRate(orgCurrencyId, docCurrencyId, orderDate)) {
      appendMessage(body, "WARNING", "noExchangeRateAvailable");
      log.debug("[ETP-4027] No conversion rate warning added (currency={})", docCurrencyId);
    }
  }

  /**
   * Replaces an inactive price list in the callout {@code updates} with the
   * client's first active price list of the matching type (sales or purchase).
   * Appends a WARNING message if a fallback is applied.
   */
  private void applyPriceListFallbackIfNeeded(JSONObject body, JSONObject updates) {
    try {
      // Extract the priceList id from updates (may be a nested object or a plain string)
      String priceListId = extractPriceListId(updates);
      if (priceListId == null || priceListId.isEmpty()) {
        return;
      }
      PriceList pl = OBDal.getInstance().get(PriceList.class, priceListId);
      if (pl == null || pl.isActive()) {
        return; // active (or unknown) — nothing to do
      }

      String defaultId = findDefaultActivePriceList();
      if (defaultId == null) {
        return;
      }

      // Replace in updates (preserve object wrapper format if present)
      Object existing = updates.get(FIELD_PRICE_LIST);
      if (existing instanceof JSONObject existingObj) {
        existingObj.put(FIELD_VALUE, defaultId);
        existingObj.remove("identifier");
      } else {
        updates.put(FIELD_PRICE_LIST, defaultId);
      }
      appendMessage(body, "WARNING", "priceListFallbackAlert");
      log.debug("[ETP-4027] Replaced inactive priceList {} with default {}", priceListId, defaultId);
    } catch (Exception e) {
      log.warn("[ETP-4027] applyPriceListFallbackIfNeeded failed (non-fatal): {}", e.getMessage());
    }
  }

  private String extractPriceListId(JSONObject updates) {
    try {
      Object raw = updates.get(FIELD_PRICE_LIST);
      if (raw instanceof JSONObject rawObj) {
        return rawObj.optString(FIELD_VALUE, null);
      }
      return updates.optString(FIELD_PRICE_LIST, null);
    } catch (Exception e) {
      return null;
    }
  }

  private String findDefaultActivePriceList() {
    try {
      // OBCriteria automatically scopes to the current client via DAL security
      OBCriteria<PriceList> crit = OBDal.getInstance().createCriteria(PriceList.class);
      crit.add(Restrictions.eq(PriceList.PROPERTY_ACTIVE, true));
      crit.add(Restrictions.eq(PriceList.PROPERTY_SALESPRICELIST, isSalesTransaction()));
      crit.setMaxResults(1);
      List<PriceList> results = crit.list();
      return results.isEmpty() ? null : results.get(0).getId();
    } catch (Exception e) {
      log.warn("[ETP-4027] findDefaultActivePriceList failed: {}", e.getMessage());
      return null;
    }
  }

  private static void appendMessage(JSONObject body, String type, String text) {
    try {
      JSONArray messages = body.optJSONArray("messages");
      if (messages == null) {
        messages = new JSONArray();
        body.put("messages", messages);
      }
      JSONObject msg = new JSONObject();
      msg.put("type", type);
      msg.put("text", text);
      messages.put(msg);
    } catch (Exception e) {
      log.warn("[ETP-4027] appendMessage failed: {}", e.getMessage());
    }
  }

  /**
   * Returns {@code true} if this handler is for a sales transaction
   * (used to select the matching price list type on fallback).
   *
   * <p>Defaults to {@code true}. Override in purchase-order handlers.
   */
  protected boolean isSalesTransaction() {
    return true;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    syncLineCurrenciesOnCurrencyPatch(context);
    if (!"GET".equals(context.getHttpMethod())) {
      return null;
    }
    NeoResponse previousResult = context.getPreviousResult();
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = previousResult.getBody();
      JSONObject responseWrapper = body.optJSONObject("response");
      if (responseWrapper == null) {
        return null;
      }
      JSONArray dataArr = responseWrapper.optJSONArray("data");
      if (dataArr == null || dataArr.length() == 0) {
        return null;
      }
      for (int i = 0; i < dataArr.length(); i++) {
        applyTotalDiscountToRecord(dataArr.getJSONObject(i));
      }
      applyCorrectedStatusPercentages(dataArr);
      if (context.getRecordId() != null) {
        dataArr.getJSONObject(0).put("hasLinkedDocuments", checkLinkedDocuments(context.getRecordId()));
      } else {
        annotateListWithLinkedDocuments(dataArr);
      }
      annotatePendingDocuments(dataArr);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error post-processing order header GET response (id={})", context.getRecordId(), e);
      return null;
    }
  }

  /**
   * Adjusts {@code grandTotalAmount} for a draft order/quotation carrying a positive
   * {@code etgoTotalDiscount} percentage that has not yet been materialized as a real line —
   * the same GET-time compensation {@code SalesInvoiceHeaderHandler} already applies for
   * invoices (list view and preview cards otherwise showed the raw undiscounted total while
   * the document was still in draft, since the discount is only materialized into a real line
   * at Complete time via {@link TotalDiscountService}).
   *
   * <p>No-op when the document is processed (the DB total already reflects the discount),
   * when no discount percentage is set, or when the discount is already a real line
   * ({@code hasDiscountLine} — avoids double-counting when e.g. an order was created carrying
   * an already-materialized discount). Mirrors {@code AbstractInvoiceHeaderHandler}'s exact
   * guard order: {@link #getTotalDiscountService()} is only dereferenced when {@code id} is
   * present.
   *
   * <p>Unlike the invoice version, there is no {@code outstandingAmount} to adjust here —
   * orders/quotations do not expose that field.
   */
  private void applyTotalDiscountToRecord(JSONObject order) throws Exception {
    if (order.optBoolean(FIELD_PROCESSED, false)) {
      return;
    }
    double discountPct = order.optDouble(FIELD_TOTAL_DISCOUNT_PCT, 0.0);
    if (discountPct <= 0.0) {
      return;
    }
    String orderId = order.optString(FIELD_ID, null);
    if (orderId != null && getTotalDiscountService().hasDiscountLine(orderId, false)) {
      return;
    }
    double factor = 1.0 - discountPct / 100.0;
    double grand = order.optDouble(FIELD_GRAND_TOTAL_AMOUNT, 0.0);
    order.put(FIELD_GRAND_TOTAL_AMOUNT, NeoHandlerUtils.roundHalfUp(grand * factor));
  }

  /**
   * Immutable holder for the three corrected status percentages of one order, as computed by
   * {@link #batchComputeStatusPercentages}.
   */
  private static final class StatusPercentages {
    private static final StatusPercentages ZERO = new StatusPercentages(0, 0, 0);

    final long invoiceStatus;
    final long deliveryStatus;
    final long deliveryStatusPurchase;

    StatusPercentages(long invoiceStatus, long deliveryStatus, long deliveryStatusPurchase) {
      this.invoiceStatus = invoiceStatus;
      this.deliveryStatus = deliveryStatus;
      this.deliveryStatusPurchase = deliveryStatusPurchase;
    }
  }

  /**
   * Overwrites {@code invoiceStatus} / {@code deliveryStatus} / {@code deliveryStatusPurchase}
   * (ETP-5317) on every record with a value that correctly excludes the Total Discount dummy
   * line ({@code M_Product_ID = ETGO_DTO}) from the invoiced/delivered/reserved-quantity
   * percentage.
   *
   * <p>Those three fields are core {@code C_Order} virtual columns (SQLLOGIC-computed,
   * {@code AD_MODULE_ID=0}). Their formula excludes lines via
   * {@code C_Order_Discount_ID IS NULL} — the classic pricing-engine discount-schema link,
   * which {@link TotalDiscountService} never sets on the line it creates for the Total
   * Discount feature. As a result the dummy line's {@code qtyordered} inflates the
   * denominator while its {@code qtyinvoiced}/{@code qtydelivered}/{@code qtyreserved} never
   * reach it, permanently under-reporting the percentage on any order/quotation carrying a
   * Total Discount. The order header form badges ({@code PurchaseOrderDraftChips.jsx} /
   * {@code OrderDraftChips.jsx}) already get this right because they compute their own
   * percentage client-side from the {@code /lines} endpoint, which excludes the dummy line by
   * product id via {@link DiscountLineFilter}. This method applies that same correct exclusion
   * criterion to the values coming from the core computed columns, so the list/grid and the
   * header badge agree. Core is not modified — the raw (buggy) value already computed by the
   * database is simply overwritten in the JSON response before it reaches the client.
   *
   * <p>Only overwrites a field when the record already carries that key, so records fetched
   * without one of the three properties selected are left untouched.
   */
  private void applyCorrectedStatusPercentages(JSONArray dataArr) throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < dataArr.length(); i++) {
      String id = dataArr.getJSONObject(i).optString(FIELD_ID, null);
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    Map<String, StatusPercentages> corrected = batchComputeStatusPercentages(ids);
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject rec = dataArr.getJSONObject(i);
      String id = rec.optString(FIELD_ID, null);
      if (id == null) {
        continue;
      }
      StatusPercentages pct = corrected.getOrDefault(id, StatusPercentages.ZERO);
      if (rec.has(FIELD_INVOICE_STATUS)) {
        rec.put(FIELD_INVOICE_STATUS, pct.invoiceStatus);
      }
      if (rec.has(FIELD_DELIVERY_STATUS)) {
        rec.put(FIELD_DELIVERY_STATUS, pct.deliveryStatus);
      }
      if (rec.has(FIELD_DELIVERY_STATUS_PURCHASE)) {
        rec.put(FIELD_DELIVERY_STATUS_PURCHASE, pct.deliveryStatusPurchase);
      }
    }
  }

  /**
   * Computes the corrected invoiced/delivered/reserved percentages for a batch of order ids in
   * a single grouped query (mirrors {@link #batchCheckLinkedDocuments}'s one-query-per-page
   * shape, avoiding N+1 for list GETs).
   *
   * <p>Mirrors the core {@code InvoiceStatus} / {@code DeliveryStatus} /
   * {@code DeliveryStatusPurchase} SQLLOGIC exactly (same guard: 0 when the order has no
   * lines, is cancelled, or is a cancelled-replacement order), only adding the
   * {@code M_Product_ID <> ETGO_DTO} exclusion those formulas are missing. An order id absent
   * from the result set (e.g. every line was the discount line) is treated as
   * {@link StatusPercentages#ZERO} by the caller, matching the "no orderable quantity" branch
   * of the original formula.
   */
  // placeholders contains only "?" literals — all values are bound via setString(); no injection risk.
  @SuppressWarnings("java:S2077")
  private Map<String, StatusPercentages> batchComputeStatusPercentages(List<String> ids) {
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT ol.c_order_id, o.iscancelled, o.cancelledorder_id, "
            + "COALESCE(SUM(ABS(ol.qtyordered)), 0) AS qty_ordered, "
            + "COALESCE(SUM(ABS(ol.qtyinvoiced)), 0) AS qty_invoiced, "
            + "COALESCE(SUM(ABS(ol.qtydelivered)), 0) AS qty_delivered, "
            + "COALESCE(SUM(ABS(ol.qtyreserved)), 0) AS qty_reserved "
            + "FROM c_orderline ol "
            + "JOIN c_order o ON o.c_order_id = ol.c_order_id "
            + "WHERE ol.c_order_id IN (" + placeholders + ") "
            + "  AND ol.c_order_discount_id IS NULL "
            + "  AND ol.m_product_id <> ? "
            + "GROUP BY ol.c_order_id, o.iscancelled, o.cancelledorder_id";
    Map<String, StatusPercentages> result = new HashMap<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int idx = 1;
      for (String id : ids) {
        ps.setString(idx++, id);
      }
      ps.setString(idx, TotalDiscountService.DISCOUNT_PRODUCT_ID);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String orderId = rs.getString(1);
          boolean cancelled = "Y".equals(rs.getString(2)) || rs.getString(3) != null;
          long qtyOrdered = rs.getLong(4);
          long qtyInvoiced = rs.getLong(5);
          long qtyDelivered = rs.getLong(6);
          long qtyReserved = rs.getLong(7);
          result.put(orderId, new StatusPercentages(
              calculatePercentage(qtyInvoiced, qtyOrdered, cancelled),
              calculatePercentage(qtyDelivered, qtyOrdered, cancelled),
              calculatePercentage(qtyReserved, qtyOrdered, cancelled)));
        }
      }
    } catch (Exception e) {
      log.error("DB error computing corrected status percentages", e);
    }
    return result;
  }

  /**
   * Pure percentage calculation shared by every row of {@link #batchComputeStatusPercentages} —
   * package-private so it can be unit-tested without a database connection.
   *
   * @return {@code 0} when there is nothing orderable to divide by or the order is cancelled,
   *         otherwise {@code round(numerator / denominator * 100)}
   */
  static long calculatePercentage(long numerator, long denominator, boolean cancelled) {
    if (denominator == 0 || cancelled) {
      return 0;
    }
    return Math.round(numerator * 100.0 / denominator);
  }

  private void annotateListWithLinkedDocuments(JSONArray dataArr) throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < dataArr.length(); i++) {
      String id = dataArr.getJSONObject(i).optString("id", null);
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    Set<String> withLinked = batchCheckLinkedDocuments(ids);
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject rec = dataArr.getJSONObject(i);
      String id = rec.optString("id", null);
      rec.put("hasLinkedDocuments", id != null && withLinked.contains(id));
    }
  }

  // placeholders contains only "?" literals — all values are bound via setString(); no injection risk.
  @SuppressWarnings("java:S2077")
  private Set<String> batchCheckLinkedDocuments(List<String> ids) {
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT DISTINCT C_Order_ID FROM C_Invoice WHERE C_Order_ID IN (" + placeholders + ") AND IsActive = 'Y' " +
        "UNION " +
        "SELECT DISTINCT C_Order_ID FROM M_InOut   WHERE C_Order_ID IN (" + placeholders + ") AND IsActive = 'Y'";
    Set<String> result = new HashSet<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int idx = 1;
      for (String id : ids) ps.setString(idx++, id);
      for (String id : ids) ps.setString(idx++, id);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString(1));
        }
      }
    } catch (Exception e) {
      log.error("DB error in batch linked-documents check", e);
    }
    return result;
  }

  private boolean checkLinkedDocuments(String orderId) {
    String sql =
        "SELECT 1 FROM C_Invoice WHERE C_Order_ID = ? AND IsActive = 'Y' " +
        "UNION ALL " +
        "SELECT 1 FROM M_InOut   WHERE C_Order_ID = ? AND IsActive = 'Y' " +
        "LIMIT 1";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, orderId);
      ps.setString(2, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (Exception e) {
      log.error("DB error querying linked documents for order {}", orderId, e);
      return false;
    }
  }

  /**
   * Annotates {@code needsPrimaryDoc} and {@code needsInvoiceDoc} on every record of a GET
   * response — list AND single-record (ETP-5295).
   *
   * <p><b>Why this exists.</b> The "Gestionar envío/factura" (sales) / "Gestionar
   * recepción/factura" (purchase) entry in the list-view row kebab menu used to derive its
   * visibility and label from the {@code DeliveryStatus}/{@code InvoiceStatus} percent columns,
   * while the "Gestionar" button on the detail form derives it from the real documents. The two
   * disagreed. Rather than make the kebab issue three extra HTTP requests per row, the real
   * derivation is computed here, server-side, once per page, so the frontend can replicate the
   * form's formula synchronously with ZERO extra requests.
   *
   * <p><b>The formula</b> (a literal transcription of the detail form's — see
   * {@code artifacts/sales-order/custom/OrderCreateInvoice.jsx} and
   * {@code artifacts/purchase-order/custom/PurchaseOrderActions.jsx} in {@code etendo_schema_forge}):
   * <pre>
   *   qtyPending      = SUM(C_OrderLine.QtyOrdered) - SUM(C_OrderLine.QtyDelivered)
   *   needsPrimaryDoc = qtyPending != 0 AND no linked M_InOut in DocStatus 'DR'
   *
   *   totalPending    = order GrandTotal - SUM(GrandTotal of LINKED invoices in DocStatus 'CO')
   *   needsInvoiceDoc = totalPending != 0 AND no LINKED invoice in DocStatus 'DR'
   * </pre>
   *
   * <p><b>Both flags are annotated unconditionally, for every document status.</b> The form only
   * evaluates them once the order is completed, and the kebab already gates its own entry on
   * {@code status === 'CO'}; keeping the backend status-agnostic means the annotation does not
   * have to re-read {@code documentStatus} and stays a pure function of the order's documents.
   *
   * <p><b>Degradation.</b> A DB failure annotates both flags {@code false} (menu entry hidden)
   * rather than leaving them absent or defaulting to {@code true}: a spuriously hidden shortcut
   * is recoverable from the detail form, a spuriously shown one sends the user into an empty
   * "manage" modal. The parent GET is never failed.
   */
  private void annotatePendingDocuments(JSONArray dataArr) throws Exception {
    List<String> ids = collectPendingDocsCandidateIds(dataArr);
    if (ids.isEmpty()) {
      return;
    }
    Map<String, OrderQuantities> quantities;
    Set<String> withDraftPrimaryDoc;
    Map<String, LinkedInvoiceTotals> invoiceTotals;
    try {
      // Sales ('Y') vs purchase ('N') comes from the SAME switch the price-list fallback uses,
      // so a subclass never has to know about this annotation to be classified correctly.
      String soTrx = isSalesTransaction() ? "Y" : "N";
      quantities = batchFetchOrderedVsDelivered(ids);
      withDraftPrimaryDoc = batchFindOrdersWithDraftPrimaryDoc(ids, soTrx);
      invoiceTotals = batchFetchLinkedInvoiceTotals(ids, soTrx);
    } catch (Exception e) {
      log.error("DB error computing needsPrimaryDoc/needsInvoiceDoc for {} order(s)", ids.size(), e);
      denyPendingDocumentFlags(dataArr);
      return;
    }
    for (int i = 0; i < dataArr.length(); i++) {
      annotatePendingDocumentsOnRecord(
          dataArr.getJSONObject(i), quantities, withDraftPrimaryDoc, invoiceTotals);
    }
  }

  /**
   * The non-empty {@code id} of every record of the page, in page order — the exact id set the
   * three batch queries are built from. A record without a usable id contributes nothing, so a
   * page where NO record has one yields an empty list and no query is issued at all.
   */
  private List<String> collectPendingDocsCandidateIds(JSONArray dataArr) throws JSONException {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < dataArr.length(); i++) {
      String id = dataArr.getJSONObject(i).optString(FIELD_ID, null);
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    return ids;
  }

  /**
   * Annotates both flags {@code false} on every record of the page — the degraded answer used
   * when the batch queries could not be run. See {@link #annotatePendingDocuments} for why the
   * flags are set to {@code false} rather than left absent.
   */
  private void denyPendingDocumentFlags(JSONArray dataArr) throws JSONException {
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject rec = dataArr.getJSONObject(i);
      rec.put(FIELD_NEEDS_PRIMARY_DOC, false);
      rec.put(FIELD_NEEDS_INVOICE_DOC, false);
    }
  }

  /**
   * Applies both flags to ONE record, reading only the already-fetched batch results — no query
   * of its own. A record without a usable id is in none of them and gets the same
   * {@code false}/{@code false} degraded answer a DB failure would produce.
   */
  private void annotatePendingDocumentsOnRecord(JSONObject rec,
      Map<String, OrderQuantities> quantities, Set<String> withDraftPrimaryDoc,
      Map<String, LinkedInvoiceTotals> invoiceTotals) throws JSONException {
    String id = rec.optString(FIELD_ID, null);
    if (id == null || id.isEmpty()) {
      rec.put(FIELD_NEEDS_PRIMARY_DOC, false);
      rec.put(FIELD_NEEDS_INVOICE_DOC, false);
      return;
    }
    OrderQuantities qty = quantities.get(id);
    BigDecimal ordered = qty != null ? qty.ordered() : BigDecimal.ZERO;
    BigDecimal delivered = qty != null ? qty.delivered() : BigDecimal.ZERO;
    rec.put(FIELD_NEEDS_PRIMARY_DOC,
        ordered.compareTo(delivered) != 0 && !withDraftPrimaryDoc.contains(id));

    LinkedInvoiceTotals totals = invoiceTotals.get(id);
    BigDecimal invoiced = totals != null ? totals.completedTotal() : BigDecimal.ZERO;
    boolean hasDraftInvoice = totals != null && totals.hasDraft();
    // The order total is read from the JSON record, not re-queried, so it is the SAME number
    // the form sees: applyTotalDiscountToRecord() has already run over this array and may have
    // adjusted grandTotalAmount for a draft carrying a not-yet-materialized total discount.
    BigDecimal totalOrder = BigDecimal.valueOf(rec.optDouble(FIELD_GRAND_TOTAL_AMOUNT, 0.0));
    rec.put(FIELD_NEEDS_INVOICE_DOC, totalOrder.compareTo(invoiced) != 0 && !hasDraftInvoice);
  }

  /**
   * One query, all ids: ordered vs delivered quantity per order.
   *
   * <p>An order with no active lines is simply absent from the result and is then treated as
   * {@code 0 - 0 = 0} pending — the same answer the form reaches from an empty {@code orderLines}
   * array.
   */
  // placeholders contains only "?" literals — all values are bound via setString(); no injection risk.
  @SuppressWarnings("java:S2077")
  private Map<String, OrderQuantities> batchFetchOrderedVsDelivered(List<String> ids) throws SQLException {
    String sql =
        "SELECT ol.C_Order_ID, COALESCE(SUM(ol.QtyOrdered), 0), COALESCE(SUM(ol.QtyDelivered), 0) " +
        "FROM C_OrderLine ol " +
        "WHERE ol.C_Order_ID IN (" + placeholders(ids) + ") AND ol.IsActive = 'Y' " +
        "GROUP BY ol.C_Order_ID";
    Map<String, OrderQuantities> result = new HashMap<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int idx = 1;
      for (String id : ids) {
        ps.setString(idx++, id);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.put(rs.getString(1),
              new OrderQuantities(zeroIfNull(rs.getBigDecimal(2)), zeroIfNull(rs.getBigDecimal(3))));
        }
      }
    }
    return result;
  }

  /**
   * One query, all ids: which orders already have a DRAFT shipment (sales) / goods receipt
   * (purchase). Mirrors the form, which lists {@code M_InOut} by {@code C_Order_ID} and keeps the
   * rows in {@code DocStatus = 'DR'}.
   */
  // placeholders contains only "?" literals — all values are bound via setString(); no injection risk.
  @SuppressWarnings("java:S2077")
  private Set<String> batchFindOrdersWithDraftPrimaryDoc(List<String> ids, String soTrx) throws SQLException {
    String sql =
        "SELECT DISTINCT io.C_Order_ID FROM M_InOut io " +
        "WHERE io.C_Order_ID IN (" + placeholders(ids) + ") AND io.IsActive = 'Y' " +
        "AND io.DocStatus = ? AND io.IsSOTrx = ?";
    Set<String> result = new HashSet<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int idx = 1;
      for (String id : ids) {
        ps.setString(idx++, id);
      }
      ps.setString(idx++, DOC_STATUS_DRAFT);
      ps.setString(idx, soTrx);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString(1));
        }
      }
    }
    return result;
  }

  /**
   * One query, all ids: per order, the summed {@code GrandTotal} of its COMPLETED linked invoices
   * and whether any linked invoice is still a DRAFT.
   *
   * <p><b>"Linked" means the union of TWO paths</b>, exactly as
   * {@code CreateDraftInvoiceHandler#handleList} — the very list the detail form reads — defines
   * it: (1) through the invoice lines ({@code C_InvoiceLine.C_OrderLine_ID → C_OrderLine.C_Order_ID}),
   * which covers invoices created from the classic Etendo UI and every partial-invoicing-by-lines
   * flow, and (2) directly through {@code C_Invoice.C_Order_ID}, which covers the edge case of an
   * invoice created by our own action that has no lines yet. The {@code UNION} deduplicates by
   * {@code (order, invoice)}, so an invoice reachable through both paths is counted once — the
   * same "merge both lists, deduplicate by id" that handler does in Java. Using only
   * {@code C_Invoice.C_Order_ID} here (as the narrower, unrelated
   * {@link #batchCheckLinkedDocuments} does) would make these flags disagree with the form in
   * precisely the partial-invoicing cases this ticket is about.
   *
   * <p><b>Known pre-existing defect, replicated deliberately (ETP-5295).</b> When ONE invoice
   * groups lines coming from SEVERAL orders, its FULL {@code GrandTotal} is counted against EACH
   * of those orders, inflating the invoiced total and making {@code needsInvoiceDoc} read
   * {@code false} too early. The frontend form has exactly this bug today, and the goal of this
   * annotation is bit-for-bit parity with the form — fixing it here alone would replace one
   * disagreement with another. It is tracked separately; do NOT "fix" it in this method without
   * fixing the form in the same change.
   */
  // placeholders contains only "?" literals — all values are bound via setString(); no injection risk.
  @SuppressWarnings("java:S2077")
  private Map<String, LinkedInvoiceTotals> batchFetchLinkedInvoiceTotals(List<String> ids, String soTrx)
      throws SQLException {
    String ph = placeholders(ids);
    String sql =
        "SELECT li.order_id, li.doc_status, COALESCE(SUM(li.grand_total), 0) FROM ( " +
        "SELECT ol.C_Order_ID AS order_id, i.C_Invoice_ID AS invoice_id, " +
        "       i.DocStatus AS doc_status, i.GrandTotal AS grand_total " +
        "  FROM C_Invoice i " +
        "  JOIN C_InvoiceLine il ON il.C_Invoice_ID = i.C_Invoice_ID AND il.IsActive = 'Y' " +
        "  JOIN C_OrderLine ol ON ol.C_OrderLine_ID = il.C_OrderLine_ID AND ol.IsActive = 'Y' " +
        " WHERE ol.C_Order_ID IN (" + ph + ") AND i.IsActive = 'Y' AND i.IsSOTrx = ? " +
        "UNION " +
        "SELECT i.C_Order_ID AS order_id, i.C_Invoice_ID AS invoice_id, " +
        "       i.DocStatus AS doc_status, i.GrandTotal AS grand_total " +
        "  FROM C_Invoice i " +
        " WHERE i.C_Order_ID IN (" + ph + ") AND i.IsActive = 'Y' AND i.IsSOTrx = ? " +
        ") li WHERE li.doc_status IN (?, ?) GROUP BY li.order_id, li.doc_status";
    Map<String, BigDecimal> completedTotals = new HashMap<>();
    Set<String> withDraft = new HashSet<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int idx = 1;
      for (String id : ids) {
        ps.setString(idx++, id);
      }
      ps.setString(idx++, soTrx);
      for (String id : ids) {
        ps.setString(idx++, id);
      }
      ps.setString(idx++, soTrx);
      ps.setString(idx++, DOC_STATUS_COMPLETED);
      ps.setString(idx, DOC_STATUS_DRAFT);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String orderId = rs.getString(1);
          if (DOC_STATUS_COMPLETED.equals(rs.getString(2))) {
            completedTotals.put(orderId, zeroIfNull(rs.getBigDecimal(3)));
          } else {
            withDraft.add(orderId);
          }
        }
      }
    }
    Map<String, LinkedInvoiceTotals> result = new HashMap<>();
    Set<String> touched = new HashSet<>(completedTotals.keySet());
    touched.addAll(withDraft);
    for (String orderId : touched) {
      result.put(orderId, new LinkedInvoiceTotals(
          completedTotals.getOrDefault(orderId, BigDecimal.ZERO), withDraft.contains(orderId)));
    }
    return result;
  }

  private static String placeholders(List<String> ids) {
    return ids.stream().map(id -> "?").collect(Collectors.joining(","));
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }

  /**
   * Aggregated line quantities of one order. Compared with {@code compareTo} rather than
   * subtracted into a {@code double}: the form's {@code qtyOrdered - qtyDelivered !== 0} runs on
   * JS floats, where an exactly-delivered order can leave a sub-unit residue and light the menu
   * entry up for nothing. Exact decimal comparison answers the question the form is asking.
   */
  private record OrderQuantities(BigDecimal ordered, BigDecimal delivered) { }

  /** Aggregated totals over the invoices linked to one order — see {@link #batchFetchLinkedInvoiceTotals}. */
  private record LinkedInvoiceTotals(BigDecimal completedTotal, boolean hasDraft) { }

  /**
   * After a successful header PATCH, aligns all order-line currencies to the saved
   * header currency. This runs whenever the PATCH body contains a {@code currency} field.
   *
   * <p>Rationale (ETP-4027): when the user changes the header currency and saves, every
   * existing line must reflect that choice — the user consciously accepts that the whole
   * order moves to the new currency. Line amounts are left unchanged; only
   * {@code C_CURRENCY_ID} is updated on mismatched lines.
   *
   * <p>No-op when no lines are mismatched (ordinary saves without a currency change).
   */
  private void syncLineCurrenciesOnCurrencyPatch(NeoContext context) {
    String method = context.getHttpMethod();
    if (!"PATCH".equals(method) && !"PUT".equals(method)) {
      return;
    }
    JSONObject reqBody = context.getRequestBody();
    if (reqBody == null || !reqBody.has(FIELD_CURRENCY)) {
      return;
    }
    String recordId = context.getRecordId();
    if (recordId == null || recordId.isEmpty()) {
      return;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Order order = OBDal.getInstance().get(Order.class, recordId);
        if (order == null || order.getCurrency() == null) {
          return;
        }
        Currency headerCurrency = order.getCurrency();
        String headerCurrencyId = headerCurrency.getId();
        int updated = 0;
        for (OrderLine line : order.getOrderLineList()) {
          if (line.getCurrency() == null
              || !headerCurrencyId.equals(line.getCurrency().getId())) {
            line.setCurrency(headerCurrency);
            OBDal.getInstance().save(line);
            updated++;
          }
        }
        if (updated > 0) {
          OBDal.getInstance().flush();
          log.info("[ETP-4027] Synced {} order-line currencies → {} on order {}",
              updated, headerCurrencyId, recordId);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("[ETP-4027] syncLineCurrenciesOnCurrencyPatch failed for {}: {}",
          recordId, e.getMessage());
    }
  }
}
