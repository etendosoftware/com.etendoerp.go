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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.service.json.JsonConstants;

/**
 * Runs the grouped HQL of {@link DocumentListTotals} against a real database, through the same
 * {@link NeoListAggregateQuery} the order/invoice list GETs use, and checks every per-currency sum
 * against a Java computation over the same documents read with plain DAL calls. The unit test
 * {@code DocumentListTotalsTest} only sees the HQL as text; here Hibernate has to parse, bind and
 * run it (the {@code case when ... not exists (...)} discount rule, the currency grouping, the
 * invoice-only outstanding column).
 *
 * <p>Fixture: the QA Testing sample data (orders and invoices, sales and purchase, EUR and MXN)
 * and, for the discount branch, the Etendo GO "Discount" service product of the GOClient sample
 * data ({@link TotalDiscountService#DISCOUNT_PRODUCT_ID}). Expected values are computed from
 * whatever the database holds at test time, never hard-coded. The discount cases temporarily edit
 * sample DRAFT documents inside the test transaction, which {@link #tearDown()} rolls back.
 *
 * <p>Extends WeldBaseTest because the grouping goes through {@code DefaultJsonDataService}, whose
 * static initialiser needs the Weld bean manager.
 *
 * @covers com.etendoerp.go.schemaforge.DocumentListTotals
 */
public class DocumentListTotalsIntegrationTest extends WeldBaseTest {

  /** QA Testing sample data, C_ORDER.xml: draft sales order (one line, no discount). */
  private static final String DRAFT_SALES_ORDER = "8B53B7E6CF3B4D8D9BCF3A49EED6FCB4";
  /** QA Testing sample data, C_ORDER.xml: draft purchase order (one line). */
  private static final String DRAFT_PURCHASE_ORDER = "2C9CEDC0761A41DCB276A5124F8AAA90";
  /** QA Testing sample data, C_INVOICE.xml: draft purchase invoice (one line). */
  private static final String DRAFT_PURCHASE_INVOICE = "9D0F6E57E59247F6AB6D063951811F51";

  private static final BigDecimal TEN_PERCENT = new BigDecimal("10");

  @Before
  public void setUpAdminContext() {
    setQAAdminContext();
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
  }

  // ── plumbing ──────────────────────────────────────────────────────────────

  private static NeoContext listContext(String entityName, String where) {
    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.ENTITYNAME, entityName);
    params.put(JsonConstants.NO_ACTIVE_FILTER, "true");
    params.put(JsonConstants.USE_ALIAS, "true");
    params.put(JsonConstants.STARTROW_PARAMETER, "0");
    params.put(JsonConstants.ENDROW_PARAMETER, "100");
    if (where != null) {
      params.put(JsonConstants.WHERE_AND_FILTER_CLAUSE, where);
    }
    NeoContext ctx = NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .build();
    NeoListAggregateQuery.remember(ctx, params);
    return ctx;
  }

  /** currencyId -> {count, grand, outstanding} straight from the HQL under test. */
  private static Map<String, BigDecimal[]> aggregate(boolean invoices, String where) {
    NeoContext ctx = listContext(invoices ? Invoice.ENTITY_NAME : Order.ENTITY_NAME, where);
    Map<String, BigDecimal[]> out = new TreeMap<>();
    for (Object[] row : new DocumentListTotals(invoices).loadRows(NeoListAggregateQuery.of(ctx))) {
      out.put((String) row[0], new BigDecimal[] { new BigDecimal(((Number) row[2]).longValue()),
          (BigDecimal) row[3], invoices ? (BigDecimal) row[4] : BigDecimal.ZERO });
    }
    return out;
  }

  private static void assertSameGroups(Map<String, BigDecimal[]> expected,
      Map<String, BigDecimal[]> actual) {
    assertEquals(expected.keySet(), actual.keySet());
    for (Map.Entry<String, BigDecimal[]> e : expected.entrySet()) {
      BigDecimal[] a = actual.get(e.getKey());
      for (int i = 0; i < a.length; i++) {
        assertEquals(e.getKey() + " column " + i + ": expected " + e.getValue()[i] + " got " + a[i],
            0, e.getValue()[i].compareTo(a[i]));
      }
    }
  }

  /** The grid rule (applyTotalDiscountToRecord) as plain Java, rounding half-up. */
  private static BigDecimal effective(BigDecimal amount, boolean processed, BigDecimal pct,
      boolean hasDiscountLine) {
    if (!processed && pct != null && pct.signum() > 0 && !hasDiscountLine) {
      return amount.multiply(new BigDecimal(100).subtract(pct)).divide(new BigDecimal(100))
          .setScale(2, RoundingMode.HALF_UP);
    }
    return amount;
  }

  private static boolean hasActiveDiscountLine(Order order) {
    for (OrderLine line : order.getOrderLineList()) {
      if (line.isActive() && TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(line.getProduct().getId())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasActiveDiscountLine(Invoice invoice) {
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      if (line.isActive() && TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(line.getProduct().getId())) {
        return true;
      }
    }
    return false;
  }

  /** Expected per-currency {count, grand, outstanding} over the orders matching {@code keep}. */
  private static Map<String, BigDecimal[]> expectedOrders(java.util.function.Predicate<Order> keep) {
    Map<String, BigDecimal[]> out = new TreeMap<>();
    for (Order o : OBDal.getInstance().createCriteria(Order.class).setFilterOnActive(false).list()) {
      if (!keep.test(o)) {
        continue;
      }
      BigDecimal[] acc = out.computeIfAbsent(o.getCurrency().getId(),
          k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
      acc[0] = acc[0].add(BigDecimal.ONE);
      acc[1] = acc[1].add(effective(o.getGrandTotalAmount(), o.isProcessed(),
          o.getEtgoTotalDiscount(), hasActiveDiscountLine(o)));
    }
    return out;
  }

  private static Map<String, BigDecimal[]> expectedInvoices(
      java.util.function.Predicate<Invoice> keep) {
    Map<String, BigDecimal[]> out = new TreeMap<>();
    for (Invoice i : OBDal.getInstance().createCriteria(Invoice.class).setFilterOnActive(false)
        .list()) {
      if (!keep.test(i)) {
        continue;
      }
      BigDecimal[] acc = out.computeIfAbsent(i.getCurrency().getId(),
          k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
      boolean discountLine = hasActiveDiscountLine(i);
      acc[0] = acc[0].add(BigDecimal.ONE);
      acc[1] = acc[1].add(effective(i.getGrandTotalAmount(), i.isProcessed(),
          i.getEtgoTotalDiscount(), discountLine));
      acc[2] = acc[2].add(effective(i.getOutstandingAmount(), i.isProcessed(),
          i.getEtgoTotalDiscount(), discountLine));
    }
    return out;
  }

  /**
   * Edits sample drafts in admin mode: the QA admin role may not write them directly, and the
   * discount service product belongs to another client. Rolled back by {@link #tearDown()}.
   */
  private static void editInAdminMode(Runnable edit) {
    OBContext.setAdminMode(true);
    try {
      edit.run();
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static long totalCount(Map<String, BigDecimal[]> groups) {
    long count = 0;
    for (BigDecimal[] group : groups.values()) {
      count += group[0].longValue();
    }
    return count;
  }

  private static Product discountProduct() {
    Product product = OBDal.getInstance().get(Product.class,
        TotalDiscountService.DISCOUNT_PRODUCT_ID);
    assertNotNull("GOClient sample data must provide the Discount service product", product);
    return product;
  }

  // ── invoices ──────────────────────────────────────────────────────────────

  @Test
  public void invoiceGroupsEqualAJavaComputationOverTheSameInvoices() {
    Map<String, BigDecimal[]> expected = expectedInvoices(i -> true);
    assertFalse("the test client has no invoices to aggregate", expected.isEmpty());

    assertSameGroups(expected, aggregate(true, null));
  }

  @Test
  public void invoiceWhereClauseNarrowsTheGroupsToSalesInvoices() {
    Map<String, BigDecimal[]> sales = expectedInvoices(Invoice::isSalesTransaction);
    assertFalse(sales.isEmpty());
    assertTrue("the filter must actually exclude something",
        totalCount(expectedInvoices(i -> true)) > totalCount(sales));

    assertSameGroups(sales, aggregate(true, "(e.salesTransaction=true)"));
  }

  @Test
  public void invoicesInSeveralCurrenciesStaySeparateGroups() {
    Map<String, BigDecimal[]> actual = aggregate(true, null);

    assertTrue("QA Testing sample invoices span EUR and MXN", actual.size() >= 2);
    for (Map.Entry<String, BigDecimal[]> e : actual.entrySet()) {
      assertTrue("count of " + e.getKey(), e.getValue()[0].signum() > 0);
    }
  }

  // ── orders ────────────────────────────────────────────────────────────────

  @Test
  public void orderGroupsEqualAJavaComputationOverTheSameOrders() {
    Map<String, BigDecimal[]> expected = expectedOrders(o -> true);
    assertFalse("the test client has no orders to aggregate", expected.isEmpty());

    assertSameGroups(expected, aggregate(false, null));
  }

  @Test
  public void orderWhereClauseNarrowsTheGroupsToSalesOrders() {
    assertSameGroups(expectedOrders(Order::isSalesTransaction),
        aggregate(false, "(e.salesTransaction=true)"));
  }

  @Test
  public void ordersInSeveralCurrenciesStaySeparateGroups() {
    assertTrue("QA Testing sample orders span EUR and MXN", aggregate(false, null).size() >= 2);
  }

  // ── discount rule ─────────────────────────────────────────────────────────

  @Test
  public void draftOrderWithDiscountPercentageAndNoDiscountLineIsAggregatedDiscounted() {
    Order draft = OBDal.getInstance().get(Order.class, DRAFT_SALES_ORDER);
    assertNotNull(draft);
    assertFalse(draft.isProcessed());
    assertFalse(hasActiveDiscountLine(draft));
    Map<String, BigDecimal[]> before = aggregate(false, null);

    editInAdminMode(() -> draft.setEtgoTotalDiscount(TEN_PERCENT));

    Map<String, BigDecimal[]> after = aggregate(false, null);
    BigDecimal cut = draft.getGrandTotalAmount()
        .subtract(effective(draft.getGrandTotalAmount(), false, TEN_PERCENT, false));
    assertTrue("the draft must lose something", cut.signum() > 0);
    String currency = draft.getCurrency().getId();
    assertEquals(0, before.get(currency)[1].subtract(cut).compareTo(after.get(currency)[1]));
    assertSameGroups(expectedOrders(o -> true), after);
  }

  @Test
  public void draftOrderWithDiscountPercentageButAnActiveDiscountLineIsNotDiscountedAgain() {
    Order draft = OBDal.getInstance().get(Order.class, DRAFT_PURCHASE_ORDER);
    assertNotNull(draft);
    assertFalse(draft.isProcessed());
    Map<String, BigDecimal[]> before = aggregate(false, null);

    editInAdminMode(() -> {
      draft.setEtgoTotalDiscount(TEN_PERCENT);
      draft.getOrderLineList().get(0).setProduct(discountProduct());
    });

    assertTrue(hasActiveDiscountLine(draft));
    Map<String, BigDecimal[]> after = aggregate(false, null);
    assertSameGroups(before, after);
    assertSameGroups(expectedOrders(o -> true), after);
  }

  @Test
  public void draftInvoiceWithDiscountPercentageAndNoDiscountLineIsAggregatedDiscounted() {
    Invoice draft = OBDal.getInstance().get(Invoice.class, DRAFT_PURCHASE_INVOICE);
    assertNotNull(draft);
    assertFalse(draft.isProcessed());
    assertFalse(hasActiveDiscountLine(draft));
    Map<String, BigDecimal[]> before = aggregate(true, null);

    editInAdminMode(() -> draft.setEtgoTotalDiscount(TEN_PERCENT));

    Map<String, BigDecimal[]> after = aggregate(true, null);
    String currency = draft.getCurrency().getId();
    BigDecimal grandCut = draft.getGrandTotalAmount()
        .subtract(effective(draft.getGrandTotalAmount(), false, TEN_PERCENT, false));
    assertTrue("the draft must lose something", grandCut.signum() > 0);
    assertEquals(0, before.get(currency)[1].subtract(grandCut).compareTo(after.get(currency)[1]));
    BigDecimal outstandingCut = draft.getOutstandingAmount()
        .subtract(effective(draft.getOutstandingAmount(), false, TEN_PERCENT, false));
    assertEquals(0,
        before.get(currency)[2].subtract(outstandingCut).compareTo(after.get(currency)[2]));
    assertSameGroups(expectedInvoices(i -> true), after);
  }

  @Test
  public void draftInvoiceWithDiscountPercentageButAnActiveDiscountLineIsNotDiscountedAgain() {
    Invoice draft = OBDal.getInstance().get(Invoice.class, DRAFT_PURCHASE_INVOICE);
    assertNotNull(draft);
    assertFalse(draft.isProcessed());
    Map<String, BigDecimal[]> before = aggregate(true, null);

    editInAdminMode(() -> {
      draft.setEtgoTotalDiscount(TEN_PERCENT);
      draft.getInvoiceLineList().get(0).setProduct(discountProduct());
    });

    assertTrue(hasActiveDiscountLine(draft));
    Map<String, BigDecimal[]> after = aggregate(true, null);
    assertSameGroups(before, after);
    assertSameGroups(expectedInvoices(i -> true), after);
  }

  @Test
  public void processedDocumentsIgnoreTheDiscountPercentage() {
    List<Order> processed = new ArrayList<>();
    for (Order o : OBDal.getInstance().createCriteria(Order.class).setFilterOnActive(false).list()) {
      if (o.isProcessed()) {
        processed.add(o);
      }
    }
    assertFalse(processed.isEmpty());
    Map<String, BigDecimal[]> before = aggregate(false, null);

    editInAdminMode(() -> processed.get(0).setEtgoTotalDiscount(TEN_PERCENT));

    assertSameGroups(before, aggregate(false, null));
  }

  // ── end to end ────────────────────────────────────────────────────────────

  @Test
  public void injectWritesListTotalsWithTheWholeSetRowCountAndBothInvoiceColumns() throws Exception {
    long expectedCount = totalCount(expectedInvoices(i -> true));
    NeoContext ctx = listContext(Invoice.ENTITY_NAME, null);
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new org.codehaus.jettison.json.JSONArray()));

    assertTrue(new DocumentListTotals(true).inject(ctx, body));

    JSONObject totals = body.getJSONObject("response").getJSONObject(DocumentListTotals.KEY_LIST_TOTALS);
    assertEquals(expectedCount, totals.getLong("rowCount"));
    JSONObject columns = totals.getJSONObject("columns");
    assertTrue(columns.has(DocumentListTotals.COLUMN_GRAND_TOTAL));
    assertTrue(columns.has(DocumentListTotals.COLUMN_OUTSTANDING));
  }

  @Test
  public void injectOnOrdersWritesOnlyTheGrandTotalColumn() throws Exception {
    NeoContext ctx = listContext(Order.ENTITY_NAME, null);
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new org.codehaus.jettison.json.JSONArray()));

    assertTrue(new DocumentListTotals(false).inject(ctx, body));

    JSONObject columns = body.getJSONObject("response")
        .getJSONObject(DocumentListTotals.KEY_LIST_TOTALS).getJSONObject("columns");
    assertTrue(columns.has(DocumentListTotals.COLUMN_GRAND_TOTAL));
    assertFalse(columns.has(DocumentListTotals.COLUMN_OUTSTANDING));
  }
}
