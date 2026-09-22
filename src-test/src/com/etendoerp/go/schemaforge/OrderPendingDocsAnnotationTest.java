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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for the ETP-5295 {@code needsPrimaryDoc} / {@code needsInvoiceDoc} GET annotations
 * added by {@code AbstractOrderHeaderHandler.annotatePendingDocuments()}, exercised through the
 * three concrete handlers that inherit them (sales order, purchase order, sales quotation).
 *
 * <p><b>What these flags are.</b> The "Gestionar envío/factura" (sales) / "Gestionar
 * recepción/factura" (purchase) entry used to be decided in two places at once: the list row
 * kebab read the {@code DeliveryStatus}/{@code InvoiceStatus} percent columns, while the detail
 * form derived it from the order's real shipments/receipts, invoices and line quantities. The two
 * disagreed — a DRAFT document already covers the pending work while the percent still reads
 * below 100 — so the kebab offered dead entries and hid live ones. The rule now has one owner:
 * these two annotations, computed here with the detail form's exact formula and read by every
 * frontend surface.
 *
 * <p><b>How the DB is mocked.</b> A GET issues its queries in a FIXED order, each one a single
 * batch statement over all ids on the page:
 * <ol>
 *   <li>{@code hasLinkedDocuments} (pre-existing — single-record LIMIT 1, or one batch IN)</li>
 *   <li>ordered vs delivered quantity per order ({@code C_OrderLine})</li>
 *   <li>orders carrying a DRAFT {@code M_InOut}</li>
 *   <li>linked-invoice totals ({@code C_Invoice}, completed sum + draft presence)</li>
 *   <li>ETP-5317 — corrected {@code invoiceStatus}/{@code deliveryStatus}/
 *       {@code deliveryStatusPurchase} percentages, run last so it never shifts the four
 *       queries above; see {@link AbstractOrderHeaderHandler#applyCorrectedStatusPercentages}</li>
 * </ol>
 * so {@link #stubDb} hands one {@link ResultSet} per {@code executeQuery()} call, in that order.
 * A test that only supplies the first four doesn't need to know about the fifth — {@link #stubDb}
 * hands any call beyond the supplied list an empty cursor.
 * Each {@code ResultSet} is a real cursor over fixture rows rather than a single sticky
 * {@code thenReturn(true)}: queries 2–4 read with {@code while (rs.next())}, and an always-true
 * stub spins forever there.
 */
public class OrderPendingDocsAnnotationTest {

  private static final String NEEDS_PRIMARY = "needsPrimaryDoc";
  private static final String NEEDS_INVOICE = "needsInvoiceDoc";
  private static final String GRAND_TOTAL = "grandTotalAmount";
  private static final String STATUS_COMPLETED = "CO";
  private static final String STATUS_DRAFT = "DR";

  // ── mocked-DB plumbing ────────────────────────────────────────────────────

  /** The two mocks a test needs to reach after the call: the connection and the statement. */
  private record Db(Connection conn, PreparedStatement ps) { }

  /**
   * A {@link ResultSet} mock that walks the given rows. {@code rows[i][col - 1]} is the value of
   * column {@code col} in row {@code i}; a {@code null} cell reads back as SQL NULL, which is the
   * case {@code zeroIfNull()} in the handler exists for.
   */
  private static ResultSet resultSet(Object[]... rows) throws SQLException {
    List<Object[]> data = Arrays.asList(rows);
    ResultSet rs = mock(ResultSet.class);
    AtomicInteger cursor = new AtomicInteger(-1);
    when(rs.next()).thenAnswer(inv -> cursor.incrementAndGet() < data.size());
    when(rs.getString(anyInt())).thenAnswer(inv -> {
      int column = inv.getArgument(0);
      Object cell = data.get(cursor.get())[column - 1];
      return cell == null ? null : String.valueOf(cell);
    });
    when(rs.getBigDecimal(anyInt())).thenAnswer(inv -> {
      int column = inv.getArgument(0);
      Object cell = data.get(cursor.get())[column - 1];
      return cell == null ? null : new BigDecimal(String.valueOf(cell));
    });
    return rs;
  }

  /** A cursor over zero rows — the "query matched nothing" case. */
  private static ResultSet noRows() throws SQLException {
    return resultSet();
  }

  /**
   * Wires {@code OBDal.getInstance().getConnection()} to a mocked connection whose single
   * {@link PreparedStatement} returns {@code queryResults} from consecutive {@code executeQuery()}
   * calls. An element may be a {@link ResultSet} (returned) or a {@link Throwable} (thrown, for
   * the degradation tests). Calls beyond the supplied list get an empty cursor, so a test only
   * has to describe the queries it actually cares about.
   */
  private static Db stubDb(MockedStatic<OBDal> obDalMock, Object... queryResults)
      throws SQLException {
    OBDal dal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    AtomicInteger call = new AtomicInteger();
    when(ps.executeQuery()).thenAnswer(inv -> {
      int i = call.getAndIncrement();
      Object result = i < queryResults.length ? queryResults[i] : noRows();
      if (result instanceof Throwable throwable) {
        throw throwable;
      }
      return result;
    });
    return new Db(conn, ps);
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  private static JSONObject orderRecord(String id, Double grandTotal) throws JSONException {
    JSONObject rec = new JSONObject().put("id", id).put("documentNo", id + "-DOC");
    if (grandTotal != null) {
      rec.put(GRAND_TOTAL, grandTotal);
    }
    return rec;
  }

  private static NeoContext getCtx(String recordId, JSONObject... records) throws JSONException {
    JSONArray data = new JSONArray();
    for (JSONObject rec : records) {
      data.put(rec);
    }
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = NeoContext.builder()
        .specName("sales-order")
        .entityName("header")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .build();
    ctx.setPreviousResult(NeoResponse.ok(body));
    return ctx;
  }

  private static JSONArray dataOf(NeoResponse response) throws JSONException {
    return response.getBody().getJSONObject("response").getJSONArray("data");
  }

  /** Row shape of the ordered-vs-delivered query: {@code (C_Order_ID, SUM qty, SUM delivered)}. */
  private static Object[] qtyRow(String orderId, String ordered, String delivered) {
    return new Object[] { orderId, ordered, delivered };
  }

  /** Row shape of the linked-invoice query: {@code (order_id, DocStatus, SUM GrandTotal)}. */
  private static Object[] invoiceRow(String orderId, String docStatus, String total) {
    return new Object[] { orderId, docStatus, total };
  }

  /** Row shape of the draft-{@code M_InOut} query: {@code (C_Order_ID)}. */
  private static Object[] inOutRow(String orderId) {
    return new Object[] { orderId };
  }

  // ══ needsPrimaryDoc ═══════════════════════════════════════════════════════

  /**
   * Case 1 — pending quantity and no draft shipment: the primary document is still to be created.
   */
  @Test
  public void testNeedsPrimaryDocTrueWhenQtyPendingAndNoDraftShipment() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),                                        // hasLinkedDocuments
          resultSet(qtyRow("order-1", "10", "4")),         // ordered vs delivered
          noRows(),                                        // no draft M_InOut
          noRows());                                       // no linked invoices

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertTrue(dataOf(result).getJSONObject(0).getBoolean(NEEDS_PRIMARY));
    }
  }

  /**
   * Case 2 — same pending quantity, but a DRAFT shipment already covers it. This is the exact
   * disagreement the ticket removed: {@code DeliveryStatus} still reads below 100 here, so the
   * percent-driven kebab used to offer an entry whose modal had nothing to create.
   */
  @Test
  public void testNeedsPrimaryDocFalseWhenDraftShipmentAlreadyCoversPendingQty() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          resultSet(qtyRow("order-1", "10", "4")),
          resultSet(inOutRow("order-1")),                  // a DRAFT M_InOut exists
          noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertFalse(dataOf(result).getJSONObject(0).getBoolean(NEEDS_PRIMARY));
    }
  }

  /**
   * Case 3 — fully delivered: nothing is pending, so the draft-document half of the rule never
   * even gets to matter. Asserted WITH a draft present precisely to pin that the two halves are
   * ANDed and the quantity half is decisive on its own.
   */
  @Test
  public void testNeedsPrimaryDocFalseWhenFullyDeliveredEvenWithDraftShipment() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          resultSet(qtyRow("order-1", "10", "10")),
          resultSet(inOutRow("order-1")),
          noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertFalse(dataOf(result).getJSONObject(0).getBoolean(NEEDS_PRIMARY));
    }
  }

  /**
   * Case 4 — an order with no active lines is simply absent from the grouped quantity query. It
   * must read as {@code 0 - 0 = 0} pending rather than throw on the missing map entry, matching
   * the form's answer from an empty {@code orderLines} array.
   */
  @Test
  public void testNeedsPrimaryDocFalseWhenOrderAbsentFromQuantityResultSet() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          resultSet(qtyRow("some-other-order", "10", "0")), // our id is NOT in the result
          noRows(),
          noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertFalse(dataOf(result).getJSONObject(0).getBoolean(NEEDS_PRIMARY));
    }
  }

  /**
   * Case 5 — partial delivery (most of the quantity shipped, a remainder outstanding) still
   * counts as pending.
   */
  @Test
  public void testNeedsPrimaryDocTrueWhenPartiallyDelivered() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          resultSet(qtyRow("order-1", "10", "9.5")),
          noRows(),
          noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertTrue(dataOf(result).getJSONObject(0).getBoolean(NEEDS_PRIMARY));
    }
  }

  /**
   * Case 6 — OVER-delivered (10 ordered, 12 delivered). The rule is "ordered differs from
   * delivered", not "ordered exceeds delivered", so this reads as pending.
   *
   * <p>Pinned deliberately: {@code compareTo(...) != 0} looks like an oversight next to a
   * {@code > 0} and is easy to "tidy up" into one. It is not an oversight — the frontend form
   * this annotation must match bit-for-bit uses {@code qtyPending !== 0}, and ETP-4567 fixed a
   * real bug caused by the narrower comparison (a clamped pending silently hid the action).
   * Narrowing it here would re-open the disagreement between the kebab and the form.
   */
  @Test
  public void testNeedsPrimaryDocTrueWhenOverDelivered() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          resultSet(qtyRow("order-1", "10", "12")),
          noRows(),
          noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertTrue(dataOf(result).getJSONObject(0).getBoolean(NEEDS_PRIMARY));
    }
  }

  /**
   * A trailing-zero difference ({@code 10} vs {@code 10.00}) is the same quantity. Compared with
   * {@code BigDecimal.compareTo}, not {@code equals}, which would call these two unequal and
   * light the menu entry up on a fully delivered order.
   */
  @Test
  public void testNeedsPrimaryDocFalseWhenQuantitiesDifferOnlyInScale() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          resultSet(qtyRow("order-1", "10", "10.0000")),
          noRows(),
          noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertFalse(dataOf(result).getJSONObject(0).getBoolean(NEEDS_PRIMARY));
    }
  }

  /** A SQL NULL sum (possible on an order with no lines) must read as zero, not throw. */
  @Test
  public void testNeedsPrimaryDocHandlesNullQuantitySums() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          resultSet(new Object[] { "order-1", null, null }),
          noRows(),
          noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertFalse(dataOf(result).getJSONObject(0).getBoolean(NEEDS_PRIMARY));
    }
  }

  // ══ needsInvoiceDoc ═══════════════════════════════════════════════════════

  /** Case 7 — order total exceeds the completed linked invoices and no draft invoice exists. */
  @Test
  public void testNeedsInvoiceDocTrueWhenPartiallyInvoicedAndNoDraft() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          noRows(),
          noRows(),
          resultSet(invoiceRow("order-1", STATUS_COMPLETED, "60")));

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertTrue(dataOf(result).getJSONObject(0).getBoolean(NEEDS_INVOICE));
    }
  }

  /**
   * Case 8 — same numbers, but a DRAFT linked invoice already covers the remainder. The form
   * treats a draft as "already handled" (its topbar chip links to it), so the manage entry must
   * not offer to create a second one.
   */
  @Test
  public void testNeedsInvoiceDocFalseWhenDraftInvoiceCoversRemainder() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          noRows(),
          noRows(),
          resultSet(
              invoiceRow("order-1", STATUS_COMPLETED, "60"),
              invoiceRow("order-1", STATUS_DRAFT, "40")));

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertFalse(dataOf(result).getJSONObject(0).getBoolean(NEEDS_INVOICE));
    }
  }

  /** Case 9 — completed invoices sum exactly to the order total: nothing left to invoice. */
  @Test
  public void testNeedsInvoiceDocFalseWhenFullyInvoiced() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          noRows(),
          noRows(),
          resultSet(invoiceRow("order-1", STATUS_COMPLETED, "100")));

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertFalse(dataOf(result).getJSONObject(0).getBoolean(NEEDS_INVOICE));
    }
  }

  /** Case 10 — a non-zero order with no linked invoice at all is entirely pending. */
  @Test
  public void testNeedsInvoiceDocTrueWhenNoLinkedInvoices() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock, noRows(), noRows(), noRows(), noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 250.75));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertTrue(dataOf(result).getJSONObject(0).getBoolean(NEEDS_INVOICE));
    }
  }

  /**
   * A NEGATIVE order total with nothing invoiced is still a non-zero difference, so it is
   * pending. This is the ETP-4567 case the frontend already fixed (a fully-negative order used to
   * have its pending amount clamped to zero, hiding the action); the annotation must agree with
   * the form there too, or it would reinstate the hidden action from the server side.
   */
  @Test
  public void testNeedsInvoiceDocTrueForNegativeOrderTotal() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock, noRows(), noRows(), noRows(), noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", -450.75));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertTrue(dataOf(result).getJSONObject(0).getBoolean(NEEDS_INVOICE));
    }
  }

  /**
   * Case 11 — documents the CURRENT behaviour when the record carries no {@code grandTotalAmount}
   * at all (a projection that did not select the column). The handler reads it with
   * {@code optDouble(..., 0.0)}, so the order total is taken as ZERO; with nothing invoiced the
   * difference is zero and {@code needsInvoiceDoc} is FALSE — the entry is hidden.
   *
   * <p>That is the safe direction (a hidden shortcut is recoverable from the detail page, a
   * shortcut into an empty modal is the reported bug), but it IS a silent default: if a projection
   * ever stops returning the column, this flag turns off everywhere with no error. Pinned so that
   * the behaviour is a decision on record rather than an accident.
   */
  @Test
  public void testNeedsInvoiceDocFalseWhenGrandTotalAmountIsAbsentFromRecord() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock, noRows(), noRows(), noRows(), noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", null));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      JSONObject rec = dataOf(result).getJSONObject(0);
      assertTrue("the flag must still be present, not omitted", rec.has(NEEDS_INVOICE));
      assertFalse(rec.getBoolean(NEEDS_INVOICE));
    }
  }

  /**
   * Case 12 — ORDERING DEPENDENCY inside {@code afterHandle()}: a draft order carrying an
   * un-materialized total discount has its {@code grandTotalAmount} rewritten by
   * {@code applyTotalDiscountToRecord()} BEFORE the pending flags are computed, and the flags read
   * that adjusted number back off the JSON record rather than re-querying it.
   *
   * <p>Here 100.00 with a 10% pending discount becomes 90.00, and the completed linked invoices
   * sum to exactly 90.00 — so the order is fully invoiced and {@code needsInvoiceDoc} is false.
   * Move {@code annotatePendingDocuments()} above the discount loop and the flag reads the
   * undiscounted 100.00 instead, flips to true, and the user is offered an invoice for a document
   * that has none pending. Nothing else in the suite would notice.
   */
  @Test
  public void testNeedsInvoiceDocUsesGrandTotalAdjustedByTotalDiscount() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          noRows(),
          noRows(),
          resultSet(invoiceRow("order-disc", STATUS_COMPLETED, "90")));

      TotalDiscountService discountService = mock(TotalDiscountService.class);
      when(discountService.hasDiscountLine("order-disc", false)).thenReturn(false);
      SalesOrderHeaderHandler handler = salesHandlerWithDiscountService(discountService);

      JSONObject rec = orderRecord("order-disc", 100.0)
          .put("processed", false)
          .put("etgoTotalDiscount", 10.0);
      NeoContext ctx = getCtx("order-disc", rec);

      NeoResponse result = handler.afterHandle(ctx);

      JSONObject annotated = dataOf(result).getJSONObject(0);
      assertEquals("precondition: the discount must have been applied first",
          90.0, annotated.getDouble(GRAND_TOTAL), 0.005);
      assertFalse("90.00 invoiced against an adjusted 90.00 total leaves nothing pending",
          annotated.getBoolean(NEEDS_INVOICE));
    }
  }

  /**
   * The mirror of the case above: identical setup, but the invoices only sum to the UNDISCOUNTED
   * total. Reading the adjusted 90.00 makes this pending (100 invoiced against a 90 order is a
   * non-zero difference); reading the raw 100.00 would call it settled. Together the two tests
   * pin the ordering from both directions, so neither can pass by accident.
   */
  @Test
  public void testNeedsInvoiceDocDoesNotUseUndiscountedGrandTotal() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          noRows(),
          noRows(),
          resultSet(invoiceRow("order-disc", STATUS_COMPLETED, "100")));

      TotalDiscountService discountService = mock(TotalDiscountService.class);
      when(discountService.hasDiscountLine("order-disc", false)).thenReturn(false);
      SalesOrderHeaderHandler handler = salesHandlerWithDiscountService(discountService);

      JSONObject rec = orderRecord("order-disc", 100.0)
          .put("processed", false)
          .put("etgoTotalDiscount", 10.0);
      NeoContext ctx = getCtx("order-disc", rec);

      NeoResponse result = handler.afterHandle(ctx);

      assertTrue(dataOf(result).getJSONObject(0).getBoolean(NEEDS_INVOICE));
    }
  }

  /**
   * Replaces the CDI-injected {@code totalDiscountService} field with a mock, bypassing CDI in a
   * plain unit-test context — the same reflection trick the sibling handler tests use.
   */
  private static SalesOrderHeaderHandler salesHandlerWithDiscountService(TotalDiscountService svc)
      throws Exception {
    SalesOrderHeaderHandler handler = new SalesOrderHeaderHandler();
    Field field = SalesOrderHeaderHandler.class.getDeclaredField("totalDiscountService");
    field.setAccessible(true);
    field.set(handler, svc);
    return handler;
  }

  // ══ cross-cutting ═════════════════════════════════════════════════════════

  /**
   * Case 13 — THE regression guard. Every one of the three pending-docs queries is a single batch
   * statement over the whole page, so a list GET of N records must issue exactly THREE statements
   * beyond the pre-existing {@code hasLinkedDocuments} batch, plus the one ETP-5317 status-
   * percentage correction batch — five in total — no matter how many rows came back.
   *
   * <p>An N+1 regression here is invisible in every other test in this file (the flags would all
   * still be correct) and invisible in the UI until a customer opens a 200-row order list. Counted
   * against a five-record page so that a per-record implementation would report way more than 5.
   */
  @Test
  public void testListGetIssuesThreeBatchQueriesRegardlessOfRecordCount() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      Db db = stubDb(obDalMock, noRows(), noRows(), noRows(), noRows(), noRows());

      NeoContext ctx = getCtx(null,
          orderRecord("order-1", 100.0), orderRecord("order-2", 100.0),
          orderRecord("order-3", 100.0), orderRecord("order-4", 100.0),
          orderRecord("order-5", 100.0));

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      verify(db.conn(), times(5)).prepareStatement(anyString());
      assertEquals(5, dataOf(result).length());
    }
  }

  /**
   * The flags must be computed per record from ONE shared set of batch results — not copied from
   * the first row. Three records on one page, each in a different state.
   */
  @Test
  public void testListGetAnnotatesEachRecordIndependently() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          resultSet(                                        // order-2 is fully delivered
              qtyRow("order-1", "10", "0"),
              qtyRow("order-2", "10", "10"),
              qtyRow("order-3", "10", "0")),
          resultSet(inOutRow("order-3")),                   // order-3 has a draft shipment
          resultSet(invoiceRow("order-1", STATUS_COMPLETED, "100")));

      NeoContext ctx = getCtx(null,
          orderRecord("order-1", 100.0),
          orderRecord("order-2", 100.0),
          orderRecord("order-3", 100.0));

      JSONArray data = dataOf(new SalesOrderHeaderHandler().afterHandle(ctx));

      assertTrue("order-1: pending qty, no draft", data.getJSONObject(0).getBoolean(NEEDS_PRIMARY));
      assertFalse("order-1: invoiced in full", data.getJSONObject(0).getBoolean(NEEDS_INVOICE));

      assertFalse("order-2: fully delivered", data.getJSONObject(1).getBoolean(NEEDS_PRIMARY));
      assertTrue("order-2: nothing invoiced", data.getJSONObject(1).getBoolean(NEEDS_INVOICE));

      assertFalse("order-3: draft shipment covers it", data.getJSONObject(2).getBoolean(NEEDS_PRIMARY));
      assertTrue("order-3: nothing invoiced", data.getJSONObject(2).getBoolean(NEEDS_INVOICE));
    }
  }

  /**
   * Case 14 — a single-record GET takes the same three batch queries (five statements with the
   * LIMIT-1 {@code hasLinkedDocuments} check and the ETP-5317 status-percentage correction) and
   * annotates both flags on {@code data[0]}. The detail page and the list must be fed by one code
   * path, or the two surfaces can drift again.
   */
  @Test
  public void testSingleRecordGetAnnotatesBothFlagsWithTheSameThreeQueries() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      Db db = stubDb(obDalMock,
          noRows(),
          resultSet(qtyRow("order-1", "10", "0")),
          noRows(),
          noRows(),
          noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      verify(db.conn(), times(5)).prepareStatement(anyString());
      JSONObject rec = dataOf(result).getJSONObject(0);
      assertTrue(rec.has(NEEDS_PRIMARY));
      assertTrue(rec.has(NEEDS_INVOICE));
      assertTrue(rec.getBoolean(NEEDS_PRIMARY));
      assertTrue(rec.getBoolean(NEEDS_INVOICE));
    }
  }

  /**
   * Case 15 — degradation. A failure in ANY of the three queries annotates both flags {@code
   * false} on EVERY record and never fails the parent GET: the order list must still render.
   *
   * <p>{@code false} (hide the entry) rather than absent or {@code true} is the deliberate choice
   * — a hidden shortcut is recoverable from the detail form, a shortcut into an empty "manage"
   * modal is the bug being fixed. Parameterised over which query blows up, because an early
   * failure and a late one take different paths through the method.
   */
  @Test
  public void testAnyQueryFailureAnnotatesBothFlagsFalseOnEveryRecord() throws Exception {
    for (int failingQuery = 1; failingQuery <= 3; failingQuery++) {
      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
        Object[] plan = new Object[] { noRows(), noRows(), noRows(), noRows() };
        plan[failingQuery] = new SQLException("DB down on pending-docs query " + failingQuery);
        stubDb(obDalMock, plan);

        NeoContext ctx = getCtx(null,
            orderRecord("order-1", 100.0), orderRecord("order-2", 100.0));

        NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

        assertNotNull("the parent GET must not be failed by a pending-docs query", result);
        assertEquals(200, result.getHttpStatus());
        JSONArray data = dataOf(result);
        for (int i = 0; i < data.length(); i++) {
          assertFalse(data.getJSONObject(i).getBoolean(NEEDS_PRIMARY));
          assertFalse(data.getJSONObject(i).getBoolean(NEEDS_INVOICE));
        }
      }
    }
  }

  /**
   * Case 16 — an empty {@code data} array short-circuits {@code afterHandle} before any DB work.
   * A "no results" page must not cost four round trips.
   */
  @Test
  public void testEmptyDataArrayIssuesNoQueries() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      Db db = stubDb(obDalMock);

      NeoContext ctx = getCtx(null);
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNull(result);
      verify(db.conn(), never()).prepareStatement(anyString());
    }
  }

  /**
   * Case 17 — a record with a blank id cannot be matched against any batch result, so it is
   * annotated {@code false} instead of throwing, and its siblings on the same page are unaffected.
   */
  @Test
  public void testRecordWithBlankIdIsAnnotatedFalseWithoutAffectingSiblings() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          resultSet(qtyRow("order-1", "10", "0")),
          noRows(),
          noRows());

      JSONObject blank = new JSONObject().put("id", "").put("documentNo", "NO-ID");
      NeoContext ctx = getCtx(null, blank, orderRecord("order-1", 100.0));

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = dataOf(result);
      assertFalse(data.getJSONObject(0).getBoolean(NEEDS_PRIMARY));
      assertFalse(data.getJSONObject(0).getBoolean(NEEDS_INVOICE));
      assertTrue("the sibling with a real id must still be computed normally",
          data.getJSONObject(1).getBoolean(NEEDS_PRIMARY));
    }
  }

  /** A record with no {@code id} key at all behaves the same as a blank one — no NPE. */
  @Test
  public void testRecordWithMissingIdKeyIsAnnotatedFalse() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock, noRows(), noRows(), noRows(), noRows());

      JSONObject noId = new JSONObject().put("documentNo", "NO-ID");
      NeoContext ctx = getCtx(null, noId, orderRecord("order-1", 100.0));

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertFalse(dataOf(result).getJSONObject(0).getBoolean(NEEDS_PRIMARY));
    }
  }

  /**
   * When NO record on the page carries a usable id there is nothing to query, so the method
   * returns before issuing any statement — and, unlike the mixed page above, the records are left
   * WITHOUT the flags rather than annotated {@code false}.
   *
   * <p>That asymmetry is documented here rather than asserted as desirable: the frontend reader
   * treats an absent annotation as "not pending" and hides the entry, which lands on the same
   * user-visible outcome, so the inconsistency is currently harmless. It would stop being
   * harmless the day a caller distinguishes absent from false.
   */
  @Test
  public void testPageWhereNoRecordHasAnIdIssuesNoPendingDocsQueries() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      Db db = stubDb(obDalMock, noRows());

      JSONObject noId = new JSONObject().put("documentNo", "NO-ID");
      NeoContext ctx = getCtx(null, noId);

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = dataOf(result).getJSONObject(0);
      assertFalse(rec.has(NEEDS_PRIMARY));
      assertFalse(rec.has(NEEDS_INVOICE));
      // Only the hasLinkedDocuments batch may have run; none of the three pending-docs queries.
      verify(db.conn(), Mockito.atMost(1)).prepareStatement(anyString());
    }
  }

  /**
   * Case 18 — a non-GET request is not a response to annotate. A PATCH must touch no DB and add
   * no flags, or every line edit would pay for four extra queries.
   */
  @Test
  public void testPatchRequestIsNotAnnotatedAndIssuesNoQueries() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      Db db = stubDb(obDalMock);

      JSONObject body = new JSONObject().put("response", new JSONObject()
          .put("data", new JSONArray().put(orderRecord("order-1", 100.0))));
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("header")
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId("order-1").build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNull(result);
      JSONObject rec = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertFalse(rec.has(NEEDS_PRIMARY));
      assertFalse(rec.has(NEEDS_INVOICE));
      verify(db.conn(), never()).prepareStatement(anyString());
    }
  }

  // ══ sales vs purchase direction (case 19) ═════════════════════════════════

  /**
   * Case 19 — the single most silently-breakable behaviour in this feature. Both the draft
   * {@code M_InOut} query and the linked-invoice query are filtered by {@code IsSOTrx}, bound from
   * {@code isSalesTransaction()}. Get it backwards and a purchase order is measured against
   * SALES shipments and SALES invoices: the queries still succeed, the response shape is
   * unchanged, and the flags are quietly wrong on every purchase order in the system.
   *
   * <p>Asserted over every bound string, because the parameter INDEX of {@code IsSOTrx} shifts
   * with the number of ids on the page — the direction, not its position, is the contract.
   * {@code 'Y'}/{@code 'N'} are unambiguous here: the only other bound values are record ids and
   * the {@code 'DR'}/{@code 'CO'} document statuses.
   */
  @Test
  public void testSalesOrderBindsIsSoTrxYes() throws Exception {
    assertBoundDirection(new SalesOrderHeaderHandler(), "Y", "N");
  }

  /** Case 19, purchase side: {@code IsSOTrx = 'N'} — goods RECEIPTS and PURCHASE invoices. */
  @Test
  public void testPurchaseOrderBindsIsSoTrxNo() throws Exception {
    assertBoundDirection(new PurchaseOrderHeaderHandler(), "N", "Y");
  }

  /** A quotation is a sales document, so it is measured against sales documents. */
  @Test
  public void testSalesQuotationBindsIsSoTrxYes() throws Exception {
    assertBoundDirection(new SalesQuotationHeaderHandler(), "Y", "N");
  }

  private static void assertBoundDirection(NeoHandler handler, String expected, String forbidden)
      throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      Db db = stubDb(obDalMock, noRows(), noRows(), noRows(), noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      assertNotNull(handler.afterHandle(ctx));

      ArgumentCaptor<String> bound = ArgumentCaptor.forClass(String.class);
      verify(db.ps(), Mockito.atLeastOnce()).setString(anyInt(), bound.capture());
      Set<String> values = new HashSet<>(bound.getAllValues());
      assertTrue("expected IsSOTrx='" + expected + "' to be bound; bound values were " + values,
          values.contains(expected));
      assertFalse("IsSOTrx='" + forbidden + "' must never be bound by this handler; bound values were "
          + values, values.contains(forbidden));
    }
  }

  // ══ inheritance smoke tests (case 20) ═════════════════════════════════════

  /**
   * Case 20 — {@code SalesQuotationHeaderHandler} overrides {@code afterHandle} to transfer the
   * currency rate and then delegates to {@code super}, so it inherits these annotations for free.
   * Verifies the delegation still reaches them (and still issues exactly the five statements)
   * rather than short-circuiting on the override.
   */
  @Test
  public void testSalesQuotationInheritsPendingDocsAnnotation() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      Db db = stubDb(obDalMock,
          noRows(),
          resultSet(qtyRow("quot-1", "10", "0")),
          noRows(),
          noRows(),
          noRows());

      NeoContext ctx = getCtx("quot-1", orderRecord("quot-1", 100.0));
      NeoResponse result = new SalesQuotationHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      verify(db.conn(), times(5)).prepareStatement(anyString());
      JSONObject rec = dataOf(result).getJSONObject(0);
      assertTrue(rec.getBoolean(NEEDS_PRIMARY));
      assertTrue(rec.getBoolean(NEEDS_INVOICE));
    }
  }

  /**
   * The purchase handler computes the flags through the same inherited path — only the bound
   * direction differs (see {@link #testPurchaseOrderBindsIsSoTrxNo}). A draft goods RECEIPT
   * suppresses {@code needsPrimaryDoc} exactly as a draft shipment does on the sales side.
   */
  @Test
  public void testPurchaseOrderDraftReceiptSuppressesNeedsPrimaryDoc() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          resultSet(qtyRow("po-1", "10", "2")),
          resultSet(inOutRow("po-1")),
          noRows());

      NeoContext ctx = getCtx("po-1", orderRecord("po-1", 100.0));
      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

      JSONObject rec = dataOf(result).getJSONObject(0);
      assertFalse(rec.getBoolean(NEEDS_PRIMARY));
      assertTrue(rec.getBoolean(NEEDS_INVOICE));
    }
  }

  /**
   * The pre-existing {@code hasLinkedDocuments} annotation must survive alongside the two new
   * ones — the {@code reactivate} row action reads it, and the three now share one GET.
   */
  @Test
  public void testHasLinkedDocumentsStillAnnotatedAlongsideTheNewFlags() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          resultSet(new Object[] { "order-1" }),            // hasLinkedDocuments finds a row
          noRows(), noRows(), noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      JSONObject rec = dataOf(result).getJSONObject(0);
      assertTrue(rec.getBoolean("hasLinkedDocuments"));
      assertTrue(rec.has(NEEDS_PRIMARY));
      assertTrue(rec.has(NEEDS_INVOICE));
    }
  }

  /**
   * The flags are annotated for EVERY document status, draft included — the handler is
   * deliberately status-agnostic and the {@code status === 'CO'} gate lives in the frontend
   * kebab. Pinned so that adding a status filter here (which would look like an optimisation)
   * registers as the behaviour change it is.
   */
  @Test
  public void testFlagsAreAnnotatedForDraftDocumentsToo() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock,
          noRows(),
          resultSet(qtyRow("order-draft", "10", "0")),
          noRows(),
          noRows());

      JSONObject rec = orderRecord("order-draft", 100.0).put("documentStatus", STATUS_DRAFT);
      NeoContext ctx = getCtx("order-draft", rec);

      JSONObject annotated = dataOf(new SalesOrderHeaderHandler().afterHandle(ctx)).getJSONObject(0);
      assertTrue(annotated.getBoolean(NEEDS_PRIMARY));
      assertTrue(annotated.getBoolean(NEEDS_INVOICE));
    }
  }

  /**
   * The annotations must be real JSON booleans, not the AD {@code 'Y'}/{@code 'N'} strings. The
   * frontend reader accepts both shapes, so a silent switch to strings would go unnoticed there —
   * but only until something compares the value with {@code ===}.
   */
  @Test
  public void testFlagsAreWrittenAsJsonBooleans() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubDb(obDalMock, noRows(), noRows(), noRows(), noRows());

      NeoContext ctx = getCtx("order-1", orderRecord("order-1", 100.0));
      JSONObject rec = dataOf(new SalesOrderHeaderHandler().afterHandle(ctx)).getJSONObject(0);

      List<Object> raw = new ArrayList<>();
      raw.add(rec.get(NEEDS_PRIMARY));
      raw.add(rec.get(NEEDS_INVOICE));
      for (Object value : raw) {
        assertTrue("expected a Boolean, got " + value.getClass().getName(),
            value instanceof Boolean);
      }
    }
  }
}
