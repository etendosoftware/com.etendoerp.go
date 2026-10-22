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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.etendoerp.go.schemaforge.NeoCurrencyTotals.Converted;
import com.etendoerp.go.schemaforge.NeoCurrencyTotals.Subtotal;
import com.etendoerp.go.schemaforge.NeoCurrencyTotals.TargetCurrency;

/**
 * Whole-set footer totals for the order and invoice list views (ETP-5757). The grid used to add up
 * the visible rows client-side, mixing currencies. This runs ONE grouped query over the same
 * filters as the list (see {@link NeoListAggregateQuery}), then folds each column into the
 * organization currency at today's rate ({@link NeoCurrencyTotals}), exactly like the payments
 * sidebar (ETP-5778).
 *
 * <p>Result, at {@code response.listTotals}:
 * <pre>
 * {orgCurrencyIso, rowCount,
 *  columns: {grandTotalAmount: {amount, iso, approximate, missingRateCurrencies},
 *            outstandingAmount: {...}}}   // outstandingAmount: invoices only
 * </pre>
 *
 * <p>Draft documents with a pending {@code etgoTotalDiscount} percentage show a discounted total in
 * the grid (the GET-time rewrite of {@code applyTotalDiscountToRecord}), so the aggregate applies
 * the same rule per row in HQL, so the footer adds up to the rows above it. Rounding uses PostgreSQL
 * {@code round(numeric, 2)} (half away from zero) while the grid uses Java half-up; they can differ
 * by one cent only for negative exact halves or floating-point edge cases.
 *
 * <p>Never throws: a failure is logged and the list response is left as it was.
 */
public class DocumentListTotals {

  private static final Logger log = LoggerFactory.getLogger(DocumentListTotals.class);

  /** Key written under {@code response}. */
  public static final String KEY_LIST_TOTALS = "listTotals";

  static final String COLUMN_GRAND_TOTAL = "grandTotalAmount";
  static final String COLUMN_OUTSTANDING = "outstandingAmount";

  private static final String KEY_RESPONSE = "response";
  private static final String ALIAS = "e";

  private final boolean invoices;

  /**
   * Creates the totals for one document kind.
   *
   * @param invoices {@code true} for {@code Invoice} lists (adds the outstanding column),
   *     {@code false} for {@code Order} lists
   */
  public DocumentListTotals(boolean invoices) {
    this.invoices = invoices;
  }

  /**
   * Adds {@code response.listTotals} to the previous result of a list GET, also when the page is
   * empty. Call it LAST in {@code afterHandle}: a failed grouped query aborts the PG transaction,
   * so nothing may query after it. Never throws.
   *
   * @param context the current NeoContext
   * @param invoices {@code true} for invoice lists, {@code false} for order lists
   * @return whether the totals were added (the caller then returns the previous body)
   */
  static boolean injectInto(NeoContext context, boolean invoices) {
    if (!"GET".equals(context.getHttpMethod()) || context.getRecordId() != null) {
      return false;
    }
    NeoResponse prev = context.getPreviousResult();
    return prev != null && prev.getBody() != null
        && create(invoices).inject(context, prev.getBody());
  }

  /** Package-private seam: builds the totals for one document kind. */
  static DocumentListTotals create(boolean invoices) {
    return new DocumentListTotals(invoices);
  }

  /**
   * Adds {@code response.listTotals} to a list GET body.
   *
   * @param context the request context
   * @param body the list response body
   * @return whether the totals were added; {@code false} when this request has no aggregate query
   *     (MCP, non-default list) or anything failed
   */
  public boolean inject(NeoContext context, JSONObject body) {
    try {
      JSONObject wrapper = body == null ? null : body.optJSONObject(KEY_RESPONSE);
      NeoListAggregateQuery query = NeoListAggregateQuery.of(context);
      if (wrapper == null || query == null) {
        return false;
      }
      wrapper.put(KEY_LIST_TOTALS, build(loadRows(query), resolveOrgCurrency(),
          new NeoCurrencyTotals(this::lookupRate)));
      return true;
    } catch (Exception e) {
      log.warn("Could not build the document list totals: {}", e.getMessage());
      return false;
    }
  }

  /** Server-side constant select clause, alias {@code e}; the column order is {@link #build}'s. */
  String selectClause() {
    StringBuilder select = new StringBuilder("e.currency.id, e.currency.iSOCode, count(e.id), ")
        .append("sum(").append(effective(Order.PROPERTY_GRANDTOTALAMOUNT)).append(")");
    if (invoices) {
      select.append(", sum(").append(effective(Invoice.PROPERTY_OUTSTANDINGAMOUNT)).append(")");
    }
    return select.toString();
  }

  /**
   * HQL for the amount as the grid shows it: a draft with a positive total-discount percentage and
   * no active discount line gets {@code amount * (1 - pct / 100)}, rounded to two decimals.
   */
  String effective(String amountProperty) {
    String amount = ALIAS + "." + amountProperty;
    String pct = ALIAS + "." + Order.PROPERTY_ETGOTOTALDISCOUNT;
    String line = invoices ? "InvoiceLine" : "OrderLine";
    String parent = invoices ? InvoiceLine.PROPERTY_INVOICE : OrderLine.PROPERTY_SALESORDER;
    return "case when " + ALIAS + "." + Order.PROPERTY_PROCESSED + " = false and " + pct + " > 0"
        + " and not exists (select 1 from " + line + " dl where dl." + parent + ".id = " + ALIAS
        + ".id and dl.active = true and dl.product.id = '" + TotalDiscountService.DISCOUNT_PRODUCT_ID
        + "') then round(" + amount + " * (1 - " + pct + " / 100), 2) else " + amount + " end";
  }

  /** Package-private seam: runs the grouped query over the list's filtered set. */
  List<Object[]> loadRows(NeoListAggregateQuery query) {
    return query.groupBy(selectClause(), "e.currency.id, e.currency.iSOCode");
  }

  /** Package-private seam: the organization's functional currency, or {@code null}. */
  TargetCurrency resolveOrgCurrency() {
    return NeoCurrencyTotals.resolveOrgCurrency();
  }

  /** Package-private seam: today's multiply rate between two currency ids, or {@code null}. */
  BigDecimal lookupRate(String fromCurrencyId, String toCurrencyId) {
    return NeoCurrencyTotals.todayMultiplyRate(fromCurrencyId, toCurrencyId);
  }

  /**
   * Builds the {@code listTotals} object from the grouped rows
   * ({@code currencyId, iso, count, grand[, outstanding]}).
   *
   * @param rows one row per currency
   * @param org the organization currency, or {@code null} when unresolvable (raw sums)
   * @param converter folds subtotals into {@code org}
   */
  JSONObject build(List<Object[]> rows, TargetCurrency org, NeoCurrencyTotals converter)
      throws JSONException {
    long rowCount = 0;
    Map<String, List<Subtotal>> columns = new LinkedHashMap<>();
    columns.put(COLUMN_GRAND_TOTAL, new ArrayList<>());
    if (invoices) {
      columns.put(COLUMN_OUTSTANDING, new ArrayList<>());
    }
    for (Object[] row : rows) {
      String currencyId = (String) row[0];
      String iso = (String) row[1];
      rowCount += ((Number) row[2]).longValue();
      columns.get(COLUMN_GRAND_TOTAL).add(new Subtotal(currencyId, iso, (BigDecimal) row[3]));
      if (invoices) {
        columns.get(COLUMN_OUTSTANDING).add(new Subtotal(currencyId, iso, (BigDecimal) row[4]));
      }
    }

    JSONObject columnsJson = new JSONObject();
    String iso = org == null ? null : org.iso;
    for (Map.Entry<String, List<Subtotal>> column : columns.entrySet()) {
      Converted converted = converter.convert(column.getValue(), org);
      if (iso == null) {
        iso = converted.iso;
      }
      columnsJson.put(column.getKey(), column(converted));
    }
    JSONObject totals = new JSONObject();
    totals.put("orgCurrencyIso", iso == null ? JSONObject.NULL : iso);
    totals.put("rowCount", rowCount);
    totals.put("columns", columnsJson);
    return totals;
  }

  private static JSONObject column(Converted converted) throws JSONException {
    JSONObject column = new JSONObject();
    column.put("amount", converted.total);
    column.put("iso", converted.iso == null ? JSONObject.NULL : converted.iso);
    column.put("approximate", converted.approximate);
    JSONArray missing = new JSONArray();
    converted.missingRateCurrencies.forEach(missing::put);
    column.put("missingRateCurrencies", missing);
    return column;
  }
}
