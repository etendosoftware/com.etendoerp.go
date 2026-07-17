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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
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
 * Shared base for order-type header handlers (Sales Order, Purchase Order).
 *
 * <p>The {@code afterHandle} post-hook appends {@code hasLinkedDocuments} to every
 * record in GET responses. Single-record GETs use a LIMIT 1 query; list GETs
 * use a single batch IN query to avoid N+1. Subclasses only need to implement
 * {@code handle()} with their window-specific action dispatching.
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
  private static final String FIELD_ORDER_DATE = "orderDate";
  private static final String FIELD_ACCOUNTING_DATE = "accountingDate";

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
   * @param context the current NeoContext
   */
  static void mirrorAccountingDate(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && ("POST".equals(context.getHttpMethod()) || "PUT".equals(context.getHttpMethod()))) {
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

  private void checkExchangeRateWarning(JSONObject body, JSONObject requestBody,
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
        && !hasConversionRate(orgCurrencyId, docCurrencyId, orderDate)) {
      appendMessage(body, "WARNING", "noExchangeRateAvailable");
      log.debug("[ETP-4027] No conversion rate warning added (currency={})", docCurrencyId);
    }
  }

  /**
   * Checks whether a {@code C_Conversion_Rate} row exists for the given currency pair
   * and date, scoped to the current client and org (including global org '0').
   *
   * @return {@code true} if a rate exists (safe default on error)
   */
  private boolean hasConversionRate(String fromCurrencyId, String toCurrencyId,
      String dateStr) {
    try {
      java.time.LocalDate localDate = java.time.LocalDate.parse(dateStr.substring(0, 10));
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      String orgId = OBContext.getOBContext().getCurrentOrganization().getId();

      String sql =
          "SELECT 1 FROM c_conversion_rate"
        + " WHERE c_currency_id = ?"
        + " AND c_currency_id_to = ?"
        + " AND isactive = 'Y'"
        + " AND ad_client_id = ?"
        + " AND (ad_org_id = '0' OR ad_org_id = ?)"
        + " AND validfrom <= ?"
        + " AND (validto IS NULL OR validto >= ?)"
        + " LIMIT 1";
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, fromCurrencyId);
        ps.setString(2, toCurrencyId);
        ps.setString(3, clientId);
        ps.setString(4, orgId);
        ps.setDate(5, java.sql.Date.valueOf(localDate));
        ps.setDate(6, java.sql.Date.valueOf(localDate));
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next();
        }
      }
    } catch (Exception e) {
      log.warn("[ETP-4027] hasConversionRate check failed (assuming rate exists): {}", e.getMessage());
      return true; // fail-open: avoid blocking when DB check fails
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
      if (context.getRecordId() != null) {
        dataArr.getJSONObject(0).put("hasLinkedDocuments", checkLinkedDocuments(context.getRecordId()));
      } else {
        annotateListWithLinkedDocuments(dataArr);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error computing hasLinkedDocuments (id={})", context.getRecordId(), e);
      return null;
    }
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
