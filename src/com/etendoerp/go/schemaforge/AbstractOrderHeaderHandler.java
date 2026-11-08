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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
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
 * every GET record (ETP-5295) — see {@link OrderPendingDocsAnnotator#annotatePendingDocuments}. Both use neutral names
 * across sales and purchase, and both take exactly three batched queries per response
 * regardless of page size, so the row kebab menu can reproduce the detail form's "Gestionar"
 * decision without a single extra HTTP request.
 *
 * <p>The same post-hook also adjusts {@code grandTotalAmount} via
 * {@link OrderDraftTotalDiscount#applyToRecord}
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
  private static final String FIELD_ID = "id";
  private static final String FIELD_ORDER_DATE = "orderDate";
  private static final String FIELD_ACCOUNTING_DATE = "accountingDate";
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
   * The concrete handler's own {@link DocumentCurrencyRate} (ETP-5840), built with the document's
   * {@link DocumentCurrencyRate.RateType} — the reference date and the name it is shown under are the
   * entity's, so each subclass declares them. One instance per handler instance: it carries a
   * deferral from {@code handle()} to {@code afterHandle()} of the same request.
   */
  protected abstract DocumentCurrencyRate currencyRate();

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
   * Runs {@link PartnerLocationDefaults#fillOrderLocations} before MCP {@code etendo_create}'s
   * mandatory-field check
   * (ETP-5852). Until then only the pre-hook filled it, which runs after that check, so an agent got
   * a 422 for an address the form would have selected by itself (the divergence §4.12.9 declared).
   * Idempotent with the call in each subclass's {@code handle()}.
   */
  @Override
  public void resolveServerCreateFields(NeoContext context) {
    PartnerLocationDefaults.fillOrderLocations(context);
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

  /** Package-private: also read by {@link DocumentRateGuards} (ETP-5840). */
  static boolean isCompleteAction(NeoContext context) {
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

  /** Package-private: also read by {@link DocumentRateGuards} (ETP-5840). */
  static boolean isActionDocumentActionComplete(NeoContext context) {
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
    if (!isDocActionButtonName(context)) {
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

  /**
   * Whether the request names the DocAction button the way {@link #syncTotalDiscountOnDocAction}
   * serves it: by its column ({@code DocAction}, what the SPA's quotation modal posts) or — on an
   * MCP call only — by its field name, which is what {@code etendo_action} reaches a customization
   * under whichever spelling the agent typed (ETP-5710). A completion by field name is already
   * recalculated by {@link #applyTotalDiscountBeforeComplete}, so it is left to that. REST is
   * untouched: only the MCP origin widens the match.
   */
  private static boolean isDocActionButtonName(NeoContext context) {
    String name = context.getFieldName();
    if ("DocAction".equals(name)) {
      return true;
    }
    return context.isMcpOrigin() && FIELD_DOCUMENT_ACTION.equals(name)
        && !isActionDocumentActionComplete(context);
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
   *   <li><b>Exchange rate (ETP-5840).</b> When the user changes the {@code currency} or the
   *       {@code orderDate}, the recalculated org→document rate for the order date is returned in
   *       {@code updates.eTGOCurrencyRate} ({@code {"value": null}} when no rate is configured), so
   *       the form shows it before saving — see {@link DocumentCurrencyRate#applyToCallout}. It
   *       replaced the untranslated {@code noExchangeRateAvailable} warning.</li>
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
      JSONObject formState = fields.formState();
      currencyRate().applyToCallout(fields,
          formState != null ? formState.optString(FIELD_ID, null) : null);
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

  /**
   * Whether this header's invoice action invoices what is pending NET of the draft invoices
   * (ETP-5828, {@link OrderInvoicePending}): then {@code needsInvoiceDoc} stays {@code true} while
   * a draft leaves something to invoice, instead of turning {@code false} at the first draft.
   *
   * <p>Defaults to {@code false}, the ETP-5295 rule. The customization whose invoice action
   * follows the net rule overrides it.
   */
  protected boolean invoicePendingNetOfDrafts() {
    return false;
  }

  /** The single-record GET key of {@link #pendingInvoiceAmount} (ETP-5828). */
  static final String FIELD_PENDING_INVOICE_AMOUNT = "pendingInvoiceAmount";

  /**
   * The amount this header's invoice action would invoice now, for the manage-documents modal
   * (ETP-5828), or {@code null} when the header does not compute one. Defaults to {@code null}:
   * only the customization whose action follows the net-of-drafts rule overrides it. Annotated on
   * the SINGLE-record GET only (one order, a few queries), never on a list page.
   *
   * @param orderId the completed order
   * @return the gross amount, or {@code null}
   */
  protected BigDecimal pendingInvoiceAmount(String orderId) {
    return null;
  }

  /**
   * Redirects a list filter on each status percentage {@link #applyCorrectedStatusPercentages}
   * rewrites to its stored computed twin (ETP-5705).
   *
   * <p>The three virtual columns are rewritten after the query, so a filter on them matched the
   * raw core value: sales orders shown at {@code deliveryStatus = 100} matched
   * {@code deliveryStatus < 100}. The stored twins ({@code EM_ETGO_Invoice_Status},
   * {@code EM_ETGO_Delivery_Status}, {@code EM_ETGO_Deliv_Status_Purchase}) are computed by
   * {@code ETGO_GET_ORDER_INVOICE_STATUS} / {@code ETGO_GET_ORDER_DELIVERY_STATUS} /
   * {@code ETGO_GET_PO_DELIVERY_STATUS}, which apply exactly the exclusion and the cancelled /
   * zero-quantity guards that {@link #batchComputeStatusPercentages} applies, so they hold the
   * value this handler displays. They are {@code NULL} on an order that never had a line (the
   * refresh is triggered by {@code C_OrderLine}); the shared filter falls back to the virtual
   * value there, which is {@code 0} — again what is displayed.</p>
   *
   * @param context the read context (unused: the mapping is the same for every read)
   * @return the three displayed properties, each mapped to its stored twin
   */
  @Override
  public Map<String, String> filterAliases(NeoContext context) {
    return Map.of(
        FIELD_INVOICE_STATUS, Order.PROPERTY_ETGOINVOICESTATUS,
        FIELD_DELIVERY_STATUS, Order.PROPERTY_ETGODELIVERYSTATUS,
        FIELD_DELIVERY_STATUS_PURCHASE, Order.PROPERTY_ETGODELIVSTATUSPURCHASE);
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    // ETP-5840: a create whose currency or order date only the create cascade derived.
    NeoResponse rateRefusal = currencyRate().afterWrite(context);
    if (rateRefusal != null) {
      return rateRefusal;
    }
    syncLineCurrenciesOnCurrencyPatch(context);
    if (!"GET".equals(context.getHttpMethod())) {
      OrderDraftTotalDiscount.applyToMcpUpdate(context, this::getTotalDiscountService);
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
      boolean changed = false;
      if (dataArr != null && dataArr.length() > 0) {
        annotateGetPage(dataArr, context.getRecordId());
        changed = true;
      }
      // ETP-5757: whole-set footer totals, also for an empty page. Last on purpose: a failed
      // grouped query aborts the PG transaction, so nothing may query after it.
      changed |= DocumentListTotals.injectInto(context, false);
      return changed ? NeoResponse.ok(body) : null;
    } catch (Exception e) {
      log.error("Error post-processing order header GET response (id={})", context.getRecordId(), e);
      return null;
    }
  }

  /**
   * Every annotation of a non-empty GET page, in the order the reads must run: the draft total
   * discount, the linked documents, the pending-document flags
   * ({@link OrderPendingDocsAnnotator}), the corrected percentages and, on a single record, the
   * pending-invoice amount — after the batch reads, so a failure there cannot spoil them (ETP-5828).
   *
   * @param dataArr  the page's records
   * @param recordId the id of a single-record GET, {@code null} for a list
   */
  private void annotateGetPage(JSONArray dataArr, String recordId) throws Exception {
    for (int i = 0; i < dataArr.length(); i++) {
      OrderDraftTotalDiscount.applyToRecord(dataArr.getJSONObject(i),
          this::getTotalDiscountService);
    }
    if (recordId != null) {
      dataArr.getJSONObject(0).put("hasLinkedDocuments", checkLinkedDocuments(recordId));
    } else {
      annotateListWithLinkedDocuments(dataArr);
    }
    OrderPendingDocsAnnotator.annotatePendingDocuments(dataArr, isSalesTransaction(),
        invoicePendingNetOfDrafts());
    applyCorrectedStatusPercentages(dataArr);
    if (recordId != null) {
      OrderPendingDocsAnnotator.annotatePendingInvoiceAmount(dataArr.getJSONObject(0), recordId,
          this::pendingInvoiceAmount);
    }
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

  /**
   * The single-record {@code hasLinkedDocuments} annotation. Degrades to {@code false} on a DB
   * failure: a GET must never fail because of this flag.
   */
  private boolean checkLinkedDocuments(String orderId) {
    try {
      return hasLinkedDocuments(orderId);
    } catch (Exception e) {
      log.error("DB error querying linked documents for order {}", orderId, e);
      return false;
    }
  }

  /**
   * Whether the order has a linked shipment/receipt or invoice: an active {@code C_Invoice} or
   * {@code M_InOut} whose header {@code C_Order_ID} is the order, in any document status.
   *
   * <p>This is the one definition of "linked documents" behind both the {@code hasLinkedDocuments}
   * flag the SPA hides Reactivate with and {@link OrderReactivationGuard}, which refuses the same
   * action in the backend (ETP-5704) — so the two can never disagree. Unlike
   * {@link #checkLinkedDocuments} it lets a DB failure propagate: the guard must fail closed, while
   * the GET annotation must not fail at all.
   *
   * @param orderId the {@code C_Order_ID} to look up
   * @return {@code true} when at least one linked document exists
   * @throws SQLException when the lookup cannot be run
   */
  static boolean hasLinkedDocuments(String orderId) throws SQLException {
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
    }
  }

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
