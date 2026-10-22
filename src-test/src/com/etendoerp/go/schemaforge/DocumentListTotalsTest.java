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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

import com.etendoerp.go.schemaforge.NeoCurrencyTotals.TargetCurrency;

/**
 * Guards the whole-set footer totals of the order and invoice lists: the folding into the
 * organization currency, the JSON contract and the HQL that mirrors the draft total-discount
 * rewrite.
 *
 * @covers com.etendoerp.go.schemaforge.DocumentListTotals
 */
public class DocumentListTotalsTest {

  private static final TargetCurrency EUR = new TargetCurrency("eur", "EUR", 2);
  private static final NeoCurrencyTotals CONVERTER = new NeoCurrencyTotals(
      (from, to) -> "usd".equals(from) ? new BigDecimal("0.5") : null);

  private static final DocumentListTotals ORDERS = new DocumentListTotals(false);
  private static final DocumentListTotals INVOICES = new DocumentListTotals(true);

  /** Order row: currencyId, iso, count, grand. */
  private static Object[] orderRow(String cur, String iso, long count, String grand) {
    return new Object[] { cur, iso, count, new BigDecimal(grand) };
  }

  /** Invoice row: currencyId, iso, count, grand, outstanding. */
  private static Object[] invoiceRow(String cur, String iso, long count, String grand,
      String outstanding) {
    return new Object[] { cur, iso, count, new BigDecimal(grand), new BigDecimal(outstanding) };
  }

  private static JSONObject grand(JSONObject totals) throws Exception {
    return totals.getJSONObject("columns").getJSONObject("grandTotalAmount");
  }

  private static void assertAmount(String expected, JSONObject column) throws Exception {
    assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) column.get("amount")));
  }

  @Test
  public void baseCurrencyOnlyIsExactAndNotApproximate() throws Exception {
    JSONObject totals = ORDERS.build(List.<Object[]>of(orderRow("eur", "EUR", 3, "125.50")), EUR,
        CONVERTER);

    JSONObject column = grand(totals);
    assertAmount("125.50", column);
    assertFalse(column.getBoolean("approximate"));
    assertEquals("EUR", column.getString("iso"));
    assertEquals(0, column.getJSONArray("missingRateCurrencies").length());
    assertEquals("EUR", totals.getString("orgCurrencyIso"));
    assertEquals(3, totals.getInt("rowCount"));
  }

  @Test
  public void mixedCurrenciesAreConvertedAndFlaggedApproximate() throws Exception {
    JSONObject totals = ORDERS.build(List.<Object[]>of(orderRow("eur", "EUR", 2, "100.00"),
        orderRow("usd", "USD", 3, "50.00")), EUR, CONVERTER);

    JSONObject column = grand(totals);
    assertAmount("125.00", column);
    assertTrue(column.getBoolean("approximate"));
    assertEquals(5, totals.getInt("rowCount"));
  }

  @Test
  public void foreignCurrencyOnlyIsConvertedAndApproximate() throws Exception {
    JSONObject totals = ORDERS.build(List.<Object[]>of(orderRow("usd", "USD", 1, "62.25")), EUR,
        CONVERTER);

    JSONObject column = grand(totals);
    assertAmount("31.13", column);
    assertTrue(column.getBoolean("approximate"));
    assertEquals("EUR", column.getString("iso"));
  }

  @Test
  public void currencyWithoutRateIsExcludedAndListed() throws Exception {
    JSONObject totals = ORDERS.build(List.<Object[]>of(orderRow("eur", "EUR", 1, "10.00"),
        orderRow("gbp", "GBP", 4, "999.00")), EUR, CONVERTER);

    JSONObject column = grand(totals);
    assertAmount("10.00", column);
    assertFalse(column.getBoolean("approximate"));
    assertEquals("GBP", column.getJSONArray("missingRateCurrencies").getString(0));
    assertEquals(5, totals.getInt("rowCount"));
  }

  @Test
  public void zeroForeignSubtotalNeedsNoRateAndIsNotApproximate() throws Exception {
    JSONObject totals = ORDERS.build(List.<Object[]>of(orderRow("eur", "EUR", 1, "10.00"),
        orderRow("gbp", "GBP", 1, "0.00")), EUR, CONVERTER);

    JSONObject column = grand(totals);
    assertFalse(column.getBoolean("approximate"));
    assertEquals(0, column.getJSONArray("missingRateCurrencies").length());
  }

  @Test
  public void emptySetYieldsZeroedTotals() throws Exception {
    JSONObject totals = ORDERS.build(List.of(), EUR, CONVERTER);

    assertEquals(0, totals.getInt("rowCount"));
    assertAmount("0.00", grand(totals));
    assertFalse(grand(totals).getBoolean("approximate"));
  }

  @Test
  public void invoicesAddAnOutstandingColumnFoldedIndependently() throws Exception {
    JSONObject totals = INVOICES.build(List.<Object[]>of(
        invoiceRow("eur", "EUR", 2, "100.00", "40.00"),
        invoiceRow("usd", "USD", 1, "50.00", "0.00")), EUR, CONVERTER);

    JSONObject columns = totals.getJSONObject("columns");
    assertAmount("125.00", columns.getJSONObject("grandTotalAmount"));
    assertTrue(columns.getJSONObject("grandTotalAmount").getBoolean("approximate"));
    assertAmount("40.00", columns.getJSONObject("outstandingAmount"));
    assertFalse(columns.getJSONObject("outstandingAmount").getBoolean("approximate"));
    assertEquals(3, totals.getInt("rowCount"));
  }

  @Test
  public void ordersHaveNoOutstandingColumn() throws Exception {
    JSONObject totals = ORDERS.build(List.<Object[]>of(orderRow("eur", "EUR", 1, "1.00")), EUR,
        CONVERTER);

    assertFalse(totals.getJSONObject("columns").has("outstandingAmount"));
  }

  @Test
  public void withoutOrgCurrencyTheRawSumIsLabelledWithTheFirstCurrency() throws Exception {
    JSONObject totals = ORDERS.build(List.<Object[]>of(orderRow("usd", "USD", 1, "10.00"),
        orderRow("gbp", "GBP", 1, "5.00")), null, CONVERTER);

    assertAmount("15.00", grand(totals));
    assertEquals("USD", totals.getString("orgCurrencyIso"));
    assertFalse(grand(totals).getBoolean("approximate"));
  }

  @Test
  public void selectClauseSumsTheEffectiveAmountPerCurrency() {
    String orders = ORDERS.selectClause();
    assertTrue(orders.startsWith("e.currency.id, e.currency.iSOCode, count(e.id), sum(case when"));
    assertFalse(orders.contains("outstandingAmount"));
    assertTrue(INVOICES.selectClause().contains("e.outstandingAmount * (1 - e.etgoTotalDiscount / 100)"));
  }

  @Test
  public void effectiveAmountMirrorsTheDraftTotalDiscountRewrite() {
    String hql = ORDERS.effective("grandTotalAmount");

    assertTrue(hql.contains("e.processed = false"));
    assertTrue(hql.contains("e.etgoTotalDiscount > 0"));
    assertTrue(hql.contains("not exists (select 1 from OrderLine dl where dl.salesOrder.id = e.id"
        + " and dl.active = true and dl.product.id = '" + TotalDiscountService.DISCOUNT_PRODUCT_ID
        + "')"));
    assertTrue(hql.contains("then round(e.grandTotalAmount * (1 - e.etgoTotalDiscount / 100), 2)"
        + " else e.grandTotalAmount end"));
  }

  @Test
  public void invoiceDiscountLineLookupUsesTheInvoiceLineEntity() {
    assertTrue(INVOICES.effective("grandTotalAmount")
        .contains("from InvoiceLine dl where dl.invoice.id = e.id"));
  }

  @Test
  public void injectIsANoOpWhenTheRequestHasNoAggregateQuery() throws Exception {
    JSONObject response = new JSONObject();
    JSONObject body = new JSONObject().put("response", response);
    NeoContext ctx = NeoContext.builder().httpMethod("GET").build();

    assertFalse(ORDERS.inject(ctx, body));
    assertFalse(response.has("listTotals"));
  }

  @Test
  public void injectWritesListTotalsUnderTheResponseWrapper() throws Exception {
    JSONObject response = new JSONObject();
    JSONObject body = new JSONObject().put("response", response);
    NeoContext ctx = NeoContext.builder().httpMethod("GET").build();
    NeoListAggregateQuery.remember(ctx, Map.of());

    assertTrue(stub(false, List.<Object[]>of(orderRow("usd", "USD", 2, "62.25"))).inject(ctx, body));

    JSONObject totals = response.getJSONObject("listTotals");
    assertEquals(2, totals.getInt("rowCount"));
    assertAmount("31.13", grand(totals));
  }

  @Test
  public void injectSwallowsAFailingQueryAndLeavesTheResponseIntact() throws Exception {
    JSONObject response = new JSONObject();
    JSONObject body = new JSONObject().put("response", response);
    NeoContext ctx = NeoContext.builder().httpMethod("GET").build();
    NeoListAggregateQuery.remember(ctx, Map.of());

    assertFalse(failing(false).inject(ctx, body));
    assertFalse(response.has("listTotals"));
    assertNull(response.opt("listTotals"));
  }

  /** Totals that read {@code rows} instead of querying; EUR org, USD at 0.5. Shared by the handler tests. */
  static DocumentListTotals stub(boolean invoices, List<Object[]> rows) {
    return new DocumentListTotals(invoices) {
      @Override
      List<Object[]> loadRows(NeoListAggregateQuery query) {
        return rows;
      }

      @Override
      TargetCurrency resolveOrgCurrency() {
        return EUR;
      }

      @Override
      BigDecimal lookupRate(String fromCurrencyId, String toCurrencyId) {
        return "usd".equals(fromCurrencyId) ? new BigDecimal("0.5") : null;
      }
    };
  }

  /** Totals whose grouped query fails, like a timeout. Shared by the handler tests. */
  static DocumentListTotals failing(boolean invoices) {
    return new DocumentListTotals(invoices) {
      @Override
      List<Object[]> loadRows(NeoListAggregateQuery query) {
        throw new IllegalStateException("query timeout");
      }
    };
  }
}
