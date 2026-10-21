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

import static com.etendoerp.go.schemaforge.LineImportTestSupport.action;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.bd;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.currency;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.data;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.draftInvoice;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.errorCode;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.ids;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.invoiceLine;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.order;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.orderLine;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.partner;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.product;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link ImportOrderLinesIntoInvoiceHandler} and the frame it shares with the
 * receipt / shipment import ({@link AbstractInvoiceLineImportHandler}) (ETP-5713): the order import
 * of a draft purchase / sales invoice. The DAL and the native line-creation process are seams,
 * replaced here by a recording double that creates one invoice line per entry it is handed.
 *
 * @covers com.etendoerp.go.schemaforge.ImportOrderLinesIntoInvoiceHandler
 * @covers com.etendoerp.go.schemaforge.AbstractInvoiceLineImportHandler
 */
public class ImportOrderLinesIntoInvoiceHandlerTest {

  private MockedStatic<OBDal> dalMock;
  private OBDal dal;

  @Before
  public void mockDal() {
    dalMock = Mockito.mockStatic(OBDal.class);
    dal = mock(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
  }

  @After
  public void releaseDal() {
    dalMock.close();
  }

  /** Vetoed: Weld scans the test classes, and a bean subclass would make the injection ambiguous. */
  @javax.enterprise.inject.Vetoed
  private static final class Probe extends ImportOrderLinesIntoInvoiceHandler {
    Invoice invoice;
    Order order;
    Map<String, BigDecimal> drafts = new HashMap<>();
    /** Completed movement lines of each order line, oldest first. */
    Map<String, List<ShipmentInOutLine>> movements = new HashMap<>();
    /** Pending MAGNITUDE per movement line (drafts included), as the receipt cap answers it. */
    Map<String, BigDecimal> movementPending = new HashMap<>();
    /** What the native process still sees per movement line, by order line. */
    Map<String, Map<String, BigDecimal>> nativeRemaining = new HashMap<>();
    final List<JSONArray> batches = new ArrayList<>();
    final Map<String, ShipmentInOutLine> movementById = new HashMap<>();
    final Map<Integer, List<InvoiceLine>> createdByBatch = new HashMap<>();
    List<Order> candidates = new ArrayList<>();
    /** When set, the native double creates this quantity instead of the one it was asked for. */
    BigDecimal createdInstead;
    /** When set, the double ignores setGoodsShipmentLine(null): a skipped unmark. */
    boolean ignoreUnmark;
    int scanLimit = 200;
    int rowLimit = 50;

    final List<String> calls = new ArrayList<>();
    JSONArray nativeSelection;
    Class<?> nativeFrom;
    final Map<String, OrderLine> lineById = new LinkedHashMap<>();
    boolean rolledBack;

    @Override
    protected void enterAdminMode() {
      // no OBContext in a unit test
    }

    @Override
    protected void leaveAdminMode() {
      // no OBContext in a unit test
    }

    @Override
    protected void rollback() {
      rolledBack = true;
    }

    @Override
    protected Invoice loadInvoice(String id) {
      return invoice;
    }

    @Override
    protected Order loadOrder(String id) {
      return order != null && order.getId().equals(id) ? order : null;
    }

    @Override
    protected void lockOrderLines(Collection<String> ids) throws SQLException {
      calls.add("lock");
    }

    @Override
    protected Map<String, BigDecimal> draftInvoicedQuantities(Collection<String> ids,
        boolean sales) {
      calls.add("drafts");
      return drafts;
    }

    @Override
    protected List<ShipmentInOutLine> movementLinesOf(OrderLine line) {
      return movements.getOrDefault(line.getId(), new ArrayList<>());
    }

    @Override
    protected Map<String, BigDecimal> movementPending(String inOutId) {
      calls.add("movementPending");
      return movementPending;
    }

    @Override
    protected Map<String, BigDecimal> nativeRelatedRemaining(OrderLine line, boolean sales) {
      return nativeRemaining.getOrDefault(line.getId(), new HashMap<>());
    }

    @Override
    protected void lockInOutLines(Collection<String> ids) throws SQLException {
      calls.add("lockInOut");
    }

    @Override
    protected String sessionClientId() {
      return LineImportTestSupport.CLIENT;
    }

    @Override
    protected void refreshOrderLines(Collection<OrderLine> lines) {
      calls.add("refresh");
    }

    @Override
    protected int scanLimit() {
      return scanLimit;
    }

    @Override
    protected int rowLimit() {
      return rowLimit;
    }

    @Override
    protected void createNativeLines(JSONArray selected, Invoice target,
        Class<? extends BaseOBObject> from) {
      calls.add("native");
      batches.add(selected);
      if (nativeSelection == null) {
        nativeSelection = selected;
      }
      nativeFrom = from;
      try {
        for (int i = 0; i < selected.length(); i++) {
          JSONObject entry = selected.getJSONObject(i);
          OrderLine source = lineById.get(entry.getString("id"));
          BigDecimal qty = createdInstead != null ? createdInstead
              : new BigDecimal(entry.getString("orderedQuantity"));
          InvoiceLine created = invoiceLine("il-" + target.getInvoiceLineList().size(),
              source.getProduct(), qty.toPlainString());
          when(created.getSalesOrderLine()).thenReturn(source);
          java.util.concurrent.atomic.AtomicReference<ShipmentInOutLine> link =
              new java.util.concurrent.atomic.AtomicReference<>(entry.has("shipmentInOutLine")
                  ? movementById.get(entry.getString("shipmentInOutLine")) : null);
          when(created.getGoodsShipmentLine()).thenAnswer(inv -> link.get());
          doAnswer(inv -> {
            if (!ignoreUnmark) {
              link.set(inv.getArgument(0));
            }
            return null;
          }).when(created).setGoodsShipmentLine(any());
          target.getInvoiceLineList().add(created);
          createdByBatch.computeIfAbsent(batches.size() - 1, k -> new ArrayList<>()).add(created);
        }
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    protected List<Order> findCandidateOrders(String partnerId, boolean sales, String cur) {
      return candidates;
    }
  }

  /** A purchase draft invoice and a completed order: Agua 10 (2 invoiced), Pan 5. */
  private Probe scenario() {
    Probe probe = new Probe();
    probe.invoice = draftInvoice("i1", false);
    OrderLine agua = orderLine("ol1", 10, product("p1", "Agua", true), "10", "0", "2");
    OrderLine pan = orderLine("ol2", 20, product("p2", "Pan", false), "5", "0", "0");
    probe.order = order("o1", false, agua, pan);
    probe.lineById.put("ol1", agua);
    probe.lineById.put("ol2", pan);
    probe.invoiceFromOrderSupport = mock(InvoiceFromOrderSupport.class);
    return probe;
  }

  private static NeoResponse run(Probe probe, String action, String body) throws Exception {
    return probe.handle(action(action, "i1", body));
  }

  // ── what is pending ───────────────────────────────────────────────────────

  @Test
  public void pendingToInvoice_isOrderedMinusInvoicedMinusDrafts() {
    Probe probe = scenario();
    probe.drafts.put("ol1", bd("3"));

    Map<String, BigDecimal> pending = probe.pendingToInvoice(probe.order, false);

    assertEquals(0, bd("5").compareTo(pending.get("ol1")));
    assertEquals(0, bd("5").compareTo(pending.get("ol2")));
  }

  /** The synthetic total-discount line, an over-invoiced line and a covered line never pend. */
  @Test
  public void pendingToInvoice_leavesOutTheDiscountLine_theOverInvoiced_andTheCovered() {
    Probe probe = scenario();
    OrderLine discount = orderLine("ol3", 30, product(TotalDiscountService.DISCOUNT_PRODUCT_ID,
        "Descuento", false), "1", "0", "0");
    OrderLine over = orderLine("ol4", 40, product("p4", "Sal", false), "2", "0", "4");
    OrderLine covered = orderLine("ol5", 50, product("p5", "Té", false), "4", "0", "0");
    Order bigger = order("o2", false, probe.lineById.get("ol1"), discount, over, covered);
    probe.drafts.put("ol5", bd("6"));

    Map<String, BigDecimal> pending = probe.pendingToInvoice(bigger, false);

    assertEquals(List.of("ol1"), List.copyOf(pending.keySet()));
  }

  @Test
  public void pendingToInvoice_aNegativeLine_pendsNegative() {
    Probe probe = scenario();
    OrderLine negative = orderLine("ol6", 60, product("p6", "Devolución", false), "-3", "0", "0");
    Order withNegative = order("o3", false, negative);
    probe.drafts.put("ol6", bd("-1"));

    Map<String, BigDecimal> pending = probe.pendingToInvoice(withNegative, false);

    assertEquals(0, bd("-2").compareTo(pending.get("ol6")));
  }

  @Test
  public void afterDrafts_dropsALineADraftCoversOrOverCovers() {
    Map<String, BigDecimal> base = new LinkedHashMap<>();
    base.put("a", bd("5"));
    base.put("b", bd("5"));
    base.put("c", bd("5"));
    Map<String, BigDecimal> drafts = new HashMap<>();
    drafts.put("a", bd("2"));
    drafts.put("b", bd("5"));
    drafts.put("c", bd("9"));

    Map<String, BigDecimal> left = ImportOrderLinesIntoInvoiceHandler.afterDrafts(base, drafts);

    assertEquals(List.of("a"), List.copyOf(left.keySet()));
    assertEquals(0, bd("3").compareTo(left.get("a")));
  }

  // ── the happy paths ───────────────────────────────────────────────────────

  /** Without lines: every pending line, in full, through the native process, on the draft. */
  @Test
  public void importOrderLines_withoutLines_importsEverythingPendingThroughTheNativeProcess()
      throws Exception {
    Probe probe = scenario();

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(200, response.getHttpStatus());
    assertSame(OrderLine.class, probe.nativeFrom);
    assertEquals(2, probe.nativeSelection.length());
    assertEquals("ol1", probe.nativeSelection.getJSONObject(0).getString("id"));
    assertEquals(0, bd("8").compareTo(
        new BigDecimal(probe.nativeSelection.getJSONObject(0).getString("orderedQuantity"))));
    assertEquals(0, bd("5").compareTo(
        new BigDecimal(probe.nativeSelection.getJSONObject(1).getString("orderedQuantity"))));
    JSONObject data = data(response);
    assertEquals(2, data.getInt("importedCount"));
    assertEquals("ol1", data.getJSONArray("lines").getJSONObject(0).getString("sourceLineId"));
    assertEquals("Agua", data.getJSONArray("lines").getJSONObject(0).getString("productName"));
  }

  @Test
  public void importOrderLines_explicitPartialQuantity_isHonouredExactly() throws Exception {
    Probe probe = scenario();

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol2\",\"quantity\":2}]}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(1, probe.nativeSelection.length());
    assertEquals("ol2", probe.nativeSelection.getJSONObject(0).getString("id"));
    assertEquals(0, bd("2").compareTo(
        new BigDecimal(probe.nativeSelection.getJSONObject(0).getString("orderedQuantity"))));
  }

  /** The new lines — and only them — get their discount percentage and are linked to inouts. */
  @Test
  public void importOrderLines_postProcessesOnlyTheNewLines() throws Exception {
    Probe probe = scenario();
    InvoiceLine existing = invoiceLine("old", product("p9", "Viejo", false), "1");
    probe.invoice.getInvoiceLineList().add(existing);

    run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> scoped = ArgumentCaptor.forClass(Set.class);
    verify(probe.invoiceFromOrderSupport).copyLineDiscountsFromOrder(Mockito.eq(probe.invoice),
        scoped.capture());
    assertEquals(Set.of("il-1", "il-2"), scoped.getValue());
    verify(probe.invoiceFromOrderSupport).ensureLineGrossAmounts(probe.invoice,
        Set.of("il-1", "il-2"));
    // ETP-5714: an imported line carries the list price the form would store, only for the new lines
    verify(probe.invoiceFromOrderSupport).alignLineListPrices(probe.invoice,
        Set.of("il-1", "il-2"));
  }

  // ── the cap ───────────────────────────────────────────────────────────────

  /** Pending is 8 (10 - 2 invoiced): 9 is over the cap, and nothing is created. */
  @Test
  public void importOrderLines_overTheCap_is422AndCreatesNothing() throws Exception {
    Probe probe = scenario();

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol2\",\"quantity\":1},{\"sourceLineId\":\"ol1\",\"quantity\":9}]}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importQuantityExceedsPending", errorCode(response));
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("Agua", error.getString("product"));
    assertEquals(9, error.getInt("requested"));
    assertEquals(8, error.getInt("pending"));
    assertFalse(probe.calls.contains("native"));
    assertNull(probe.nativeSelection);
  }

  /** Drafts count, which the one-step createPurchaseInvoice does not need but this must. */
  @Test
  public void importOrderLines_draftsAreCounted() throws Exception {
    Probe probe = scenario();
    probe.drafts.put("ol1", bd("6"));

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":3}]}");

    assertEquals(422, response.getHttpStatus());
    assertEquals(2, response.getBody().getJSONObject("error").getInt("pending"));
    assertNull(probe.nativeSelection);
  }

  @Test
  public void importOrderLines_aLineAlreadyInTheTargetCanComeAgainUnderTheCap() throws Exception {
    Probe probe = scenario();
    // 3 already imported into this very draft: the drafts total includes them
    probe.drafts.put("ol1", bd("3"));

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":5}]}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, bd("5").compareTo(
        new BigDecimal(probe.nativeSelection.getJSONObject(0).getString("orderedQuantity"))));
  }

  @Test
  public void importOrderLines_locksTheOrderLinesBeforeReadingTheDrafts() throws Exception {
    Probe probe = scenario();

    run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertTrue(probe.calls.indexOf("lock") >= 0);
    assertTrue(probe.calls.indexOf("lock") < probe.calls.indexOf("drafts"));
    assertTrue(probe.calls.indexOf("drafts") < probe.calls.indexOf("native"));
  }

  // ── deliveries: the quantity goes to the movement lines first ─────────────

  /** A completed receipt line of {@code ol1}, registered with the probe. */
  private static ShipmentInOutLine movement(Probe probe, String id, String qty, String pending,
      String nativeRemaining) {
    OrderLine ol = probe.lineById.get("ol1");
    ShipmentInOutLine line = LineImportTestSupport.inOutLine(id, 10, ol.getProduct(), qty);
    ShipmentInOut doc = LineImportTestSupport.completedInOut("rc-" + id, false, line);
    doReturn(doc).when(line).getShipmentReceipt();
    probe.movements.computeIfAbsent("ol1", k -> new ArrayList<>()).add(line);
    probe.movementById.put(id, line);
    if (pending != null) {
      probe.movementPending.put(id, bd(pending));
    }
    if (nativeRemaining != null) {
      probe.nativeRemaining.computeIfAbsent("ol1", k -> new HashMap<>()).put(id,
          bd(nativeRemaining));
    }
    return line;
  }

  private static BigDecimal qtyOf(JSONObject entry) throws Exception {
    return new BigDecimal(entry.getString("orderedQuantity"));
  }

  /**
   * The normal purchase flow: ordered 10 (2 invoiced, 8 pending), 6 received and not invoiced. The
   * 6 go to the receipt line, bound to it so the receipt shows as invoiced; the 2 that were ordered
   * but not delivered are a plain order-line entry in a SECOND native call, once the receipt line is
   * fully taken, so the native process does not swap it for the receipt again.
   */
  @Test
  public void importOrderLines_withDeliveredAndUndelivered_boundFirstThenPlainRemainder()
      throws Exception {
    Probe probe = scenario();
    movement(probe, "m1", "6", "6", "6");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":8}]}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(2, probe.batches.size());
    JSONObject bound = probe.batches.get(0).getJSONObject(0);
    assertEquals("ol1", bound.getString("id"));
    assertEquals("m1", bound.getString("shipmentInOutLine"));
    assertEquals(0, bd("6").compareTo(qtyOf(bound)));
    JSONObject plain = probe.batches.get(1).getJSONObject(0);
    assertEquals("ol1", plain.getString("id"));
    assertEquals("a bound marker keeps the native process from swapping the remainder for the "
        + "receipt line; it is removed again once the line exists", "m1",
        plain.getString("shipmentInOutLine"));
    verify(probe.createdByBatch.get(1).get(0)).setGoodsShipmentLine(null);
    assertEquals(0, bd("2").compareTo(qtyOf(plain)));
    assertEquals(2, data(response).getInt("importedCount"));
  }

  /** Less than what was delivered: only the bound entry, for exactly the requested quantity. */
  @Test
  public void importOrderLines_lessThanDelivered_isOneBoundEntryOfTheRequestedQuantity()
      throws Exception {
    Probe probe = scenario();
    movement(probe, "m1", "6", "6", "6");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":4}]}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(1, probe.batches.size());
    assertEquals(1, probe.batches.get(0).length());
    JSONObject bound = probe.batches.get(0).getJSONObject(0);
    assertEquals("m1", bound.getString("shipmentInOutLine"));
    assertEquals(0, bd("4").compareTo(qtyOf(bound)));
  }

  /** Several receipt lines: filled oldest first, each only up to its own pending. */
  @Test
  public void importOrderLines_spreadsOverTheMovementLinesOldestFirst() throws Exception {
    Probe probe = scenario();
    movement(probe, "m1", "3", "3", "3");
    movement(probe, "m2", "5", "5", "5");

    run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":6}]}");

    JSONArray entries = probe.batches.get(0);
    assertEquals(2, entries.length());
    assertEquals("m1", entries.getJSONObject(0).getString("shipmentInOutLine"));
    assertEquals(0, bd("3").compareTo(qtyOf(entries.getJSONObject(0))));
    assertEquals("m2", entries.getJSONObject(1).getString("shipmentInOutLine"));
    assertEquals(0, bd("3").compareTo(qtyOf(entries.getJSONObject(1))));
    assertEquals(1, probe.batches.size());
  }

  /** Lines omitted: everything pending of the order is distributed the same way. */
  @Test
  public void importOrderLines_withoutLines_distributesEveryPendingLine() throws Exception {
    Probe probe = scenario();
    movement(probe, "m1", "6", "6", "6");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(200, response.getHttpStatus());
    assertEquals("m1", probe.batches.get(0).getJSONObject(0).getString("shipmentInOutLine"));
    JSONArray plain = probe.batches.get(1);
    assertEquals("ol1", plain.getJSONObject(0).getString("id"));
    assertEquals(0, bd("2").compareTo(qtyOf(plain.getJSONObject(0))));
    assertEquals("m1", plain.getJSONObject(0).getString("shipmentInOutLine"));
    assertEquals("ol2", plain.getJSONObject(1).getString("id"));
    assertFalse("an order line without deliveries needs no marker",
        plain.getJSONObject(1).has("shipmentInOutLine"));
  }

  /** A movement line that no invoice or draft covers is never offered twice: pending 0 skips it. */
  @Test
  public void importOrderLines_aMovementLineAlreadyInvoiced_isSkippedAndTheQuantityIsPlain()
      throws Exception {
    Probe probe = scenario();
    movement(probe, "m1", "6", null, null);

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":3}]}");

    assertEquals(200, response.getHttpStatus());
    JSONObject entry = probe.batches.get(0).getJSONObject(0);
    assertFalse(entry.has("shipmentInOutLine"));
    assertEquals(0, bd("3").compareTo(qtyOf(entry)));
  }

  /**
   * LIVE BUG (purchase): order of 10, receipt of 6, the receipt already invoiced by ANOTHER DRAFT
   * invoice. The receipt line has nothing left for us (6 - 0 - 6 = 0) but the native process, which
   * ignores drafts, still sees it as not invoiced and would swap the plain entry of 4 for the
   * receipt's 6. The pending of the order line is 10 - 0 - 6 = 4: one line of 4, linked to the
   * order line ONLY (the marker that hides the receipt from the native process is removed again).
   */
  @Test
  public void importOrderLines_receiptFullyCoveredByAnotherDraft_importsTheRestAgainstTheOrderLine()
      throws Exception {
    Probe probe = scenario();
    doReturn(BigDecimal.ZERO).when(probe.lineById.get("ol1")).getInvoicedQuantity();
    probe.drafts.put("ol1", bd("6"));
    movement(probe, "m1", "6", "0", "6");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":4}]}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(1, probe.batches.size());
    JSONObject entry = probe.batches.get(0).getJSONObject(0);
    assertEquals(0, bd("4").compareTo(qtyOf(entry)));
    assertEquals("m1", entry.getString("shipmentInOutLine"));
    InvoiceLine line = probe.createdByBatch.get(0).get(0);
    verify(line).setGoodsShipmentLine(null);
    assertEquals(1, data(response).getInt("importedCount"));
  }

  /** The same, asking for everything pending: the 4 that are not in any draft. */
  @Test
  public void importOrderLines_receiptCoveredByAnotherDraft_withoutLines_importsThePendingRest()
      throws Exception {
    Probe probe = scenario();
    doReturn(BigDecimal.ZERO).when(probe.lineById.get("ol1")).getInvoicedQuantity();
    probe.drafts.put("ol1", bd("6"));
    probe.order.getOrderLineList().remove(probe.lineById.get("ol2"));
    movement(probe, "m1", "6", "0", "6");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, bd("4").compareTo(qtyOf(probe.batches.get(0).getJSONObject(0))));
  }

  /** Drafts count: a second import of the same remainder finds nothing pending. */
  @Test
  public void importOrderLines_theSameRemainderTwice_secondFindsNothingPending() throws Exception {
    Probe probe = scenario();
    doReturn(BigDecimal.ZERO).when(probe.lineById.get("ol1")).getInvoicedQuantity();
    probe.order.getOrderLineList().remove(probe.lineById.get("ol2"));
    probe.drafts.put("ol1", bd("10"));
    movement(probe, "m1", "6", "0", "6");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importNothingPending", errorCode(response));
    assertTrue(probe.batches.isEmpty());
  }

  /** The sales mirror: a shipment covered by a draft sales invoice, then the order import. */
  @Test
  public void importOrderLines_sales_shipmentCoveredByAnotherDraft_importsTheRest()
      throws Exception {
    Probe probe = scenario();
    doReturn(BigDecimal.ZERO).when(probe.lineById.get("ol1")).getInvoicedQuantity();
    when(probe.invoice.isSalesTransaction()).thenReturn(true);
    when(probe.order.isSalesTransaction()).thenReturn(true);
    probe.drafts.put("ol1", bd("6"));
    movement(probe, "m1", "6", "0", "6");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":4}]}");

    assertEquals(200, response.getHttpStatus());
    verify(probe.createdByBatch.get(0).get(0)).setGoodsShipmentLine(null);
  }

  /**
   * A receipt line only partly covered by a draft still takes what is free of it, and the rest is
   * invoiced against the order line alone: nothing is refused any more.
   */
  @Test
  public void importOrderLines_aMovementLinePartlyCoveredElsewhere_takesItsFreePartThenTheRest()
      throws Exception {
    Probe probe = scenario();
    movement(probe, "m1", "6", "2", "6");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":5}]}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(2, probe.batches.size());
    JSONObject bound = probe.batches.get(0).getJSONObject(0);
    assertEquals("m1", bound.getString("shipmentInOutLine"));
    assertEquals(0, bd("2").compareTo(qtyOf(bound)));
    assertEquals(0, bd("3").compareTo(qtyOf(probe.batches.get(1).getJSONObject(0))));
    verify(probe.createdByBatch.get(1).get(0)).setGoodsShipmentLine(null);
    verify(probe.createdByBatch.get(0).get(0), never()).setGoodsShipmentLine(null);
  }

  /** A receipt line of another product cannot take quantity: all of it is against the order line. */
  @Test
  public void importOrderLines_aMovementLineOfAnotherProduct_isNeverUsed() throws Exception {
    Probe probe = scenario();
    ShipmentInOutLine other = movement(probe, "m1", "6", "6", "6");
    doReturn(product("p-other", "Otro", false)).when(other).getProduct();

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":4}]}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(1, probe.batches.size());
    assertEquals(0, bd("4").compareTo(qtyOf(probe.batches.get(0).getJSONObject(0))));
    verify(probe.createdByBatch.get(0).get(0)).setGoodsShipmentLine(null);
  }

  /** Movement lines are locked (with the order lines) before their pending is read. */
  @Test
  public void importOrderLines_locksTheMovementLinesBeforeReadingTheirPending() throws Exception {
    Probe probe = scenario();
    movement(probe, "m1", "6", "6", "6");

    run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":4}]}");

    assertTrue(probe.calls.indexOf("lock") < probe.calls.indexOf("lockInOut"));
    assertTrue(probe.calls.indexOf("lockInOut") < probe.calls.indexOf("movementPending"));
    assertTrue(probe.calls.indexOf("movementPending") < probe.calls.indexOf("native"));
  }

  // ── both caps count the same invoice lines (no double invoicing) ──────────

  /**
   * Order to invoice then receipt to invoice: the bound entry carries the movement line, so the
   * native process sets {@code goodsShipmentLine} on the draft line and the receipt cap
   * ({@code NeoInvoiceSupport}, drafts included, by {@code m_inoutline_id}) already counts it. The
   * entry is also an order-line entry, so the order cap counts it too.
   */
  @Test
  public void importOrderLines_theDraftLineCarriesBothLinks_soTheReceiptCapCountsIt()
      throws Exception {
    Probe probe = scenario();
    ShipmentInOutLine m1 = movement(probe, "m1", "6", "6", "6");

    run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":6}]}");

    InvoiceLine created = probe.invoice.getInvoiceLineList().get(0);
    assertSame(m1, created.getGoodsShipmentLine());
    assertSame(probe.lineById.get("ol1"), created.getSalesOrderLine());
  }

  /**
   * Receipt to invoice then order to invoice: the order cap sums EVERY draft invoice line of the
   * order line, whatever created it and whether or not it is bound to a movement line, so lines
   * from {@code importReceiptLines} (which the native process links to their order line) reduce
   * the order's pending too.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void draftInvoicedQuantities_countsEveryDraftLineOfTheOrderLine_boundOrNot() {
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(dal.getSession()).thenReturn(session);
    org.hibernate.query.Query<Object[]> query = mock(org.hibernate.query.Query.class);
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    when(session.createQuery(hql.capture(), Mockito.eq(Object[].class))).thenReturn(query);
    when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(query);
    when(query.setParameterList(Mockito.anyString(), Mockito.anyCollection())).thenReturn(query);
    when(query.list()).thenReturn(List.<Object[]>of(new Object[] { "ol1", bd("3") },
        new Object[] { "ol1", bd("2") }));

    Map<String, BigDecimal> held = new ImportOrderLinesIntoInvoiceHandler()
        .draftInvoicedQuantities(List.of("ol1"), false);

    assertEquals(0, bd("5").compareTo(held.get("ol1")));
    assertTrue(hql.getValue(), hql.getValue().contains("i.documentStatus = 'DR'")
        && hql.getValue().contains("il.salesOrderLine.id in :ids"));
    assertFalse("bound or not, a draft line of the order line counts",
        hql.getValue().contains("goodsShipmentLine"));
  }

  // ── the preconditions ─────────────────────────────────────────────────────

  @Test
  public void importOrderLines_aTargetThatIsNotADraft_isRefused() throws Exception {
    Probe probe = scenario();
    when(probe.invoice.getDocumentStatus()).thenReturn("CO");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importTargetNotDraft", errorCode(response));
    assertTrue(probe.calls.isEmpty());
  }

  @Test
  public void importOrderLines_aTargetWithoutPriceList_isRefused() throws Exception {
    Probe probe = scenario();
    when(probe.invoice.getPriceList()).thenReturn(null);

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importPriceListRequired", errorCode(response));
  }

  @Test
  public void importOrderLines_aSourceThatIsNotCompleted_isRefused() throws Exception {
    Probe probe = scenario();
    when(probe.order.getDocumentStatus()).thenReturn("DR");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals("importSourceNotCompleted", errorCode(response));
    assertNull(probe.nativeSelection);
  }

  @Test
  public void importOrderLines_anotherPartnerCurrencyOrDirection_isRefused() throws Exception {
    Probe probe = scenario();
    doReturn(partner("other")).when(probe.order).getBusinessPartner();
    assertEquals("importBusinessPartnerMismatch",
        errorCode(run(probe, "importOrderLines", "{\"orderId\":\"o1\"}")));

    probe = scenario();
    doReturn(currency("usd")).when(probe.order).getCurrency();
    assertEquals("importCurrencyMismatch",
        errorCode(run(probe, "importOrderLines", "{\"orderId\":\"o1\"}")));

    probe = scenario();
    when(probe.order.isSalesTransaction()).thenReturn(true);
    assertEquals("importDirectionMismatch",
        errorCode(run(probe, "importOrderLines", "{\"orderId\":\"o1\"}")));
    assertNull(probe.nativeSelection);
  }

  @Test
  public void importOrderLines_missingTargetOrOrder_is404() throws Exception {
    Probe probe = scenario();
    probe.invoice = null;
    assertEquals(404, run(probe, "importOrderLines", "{\"orderId\":\"o1\"}").getHttpStatus());

    probe = scenario();
    assertEquals(404, run(probe, "importOrderLines", "{\"orderId\":\"zz\"}").getHttpStatus());
    assertEquals(400, run(probe, "importOrderLines", "{}").getHttpStatus());
  }

  // ── header ────────────────────────────────────────────────────────────────

  /** The invoice's order reference is the native hook's work: the response reports it. */
  @Test
  public void importOrderLines_reportsTheOrderReferenceTheNativeProcessLeftOnTheInvoice()
      throws Exception {
    Probe probe = scenario();
    when(probe.invoice.getSalesOrder()).thenReturn(probe.order);

    JSONObject linked = data(run(probe, "importOrderLines", "{\"orderId\":\"o1\"}"));

    assertTrue(linked.getBoolean("headerLinked"));
    assertEquals("o1", linked.getString("salesOrder"));

    probe = scenario();
    when(probe.invoice.getSalesOrder()).thenReturn(null);
    JSONObject mixed = data(run(probe, "importOrderLines", "{\"orderId\":\"o1\"}"));
    assertFalse(mixed.getBoolean("headerLinked"));
    assertFalse(mixed.has("salesOrder"));
  }

  @Test
  public void importOrderLines_carriesThePositiveOrderDiscount_onlyWhenTheInvoiceHasNone()
      throws Exception {
    Probe probe = scenario();
    when(probe.order.getEtgoTotalDiscount()).thenReturn(bd("10"));
    when(probe.invoice.getEtgoTotalDiscount()).thenReturn(null);
    run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");
    verify(probe.invoice).setEtgoTotalDiscount(bd("10"));

    probe = scenario();
    when(probe.order.getEtgoTotalDiscount()).thenReturn(bd("10"));
    when(probe.invoice.getEtgoTotalDiscount()).thenReturn(bd("5"));
    run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");
    verify(probe.invoice, never()).setEtgoTotalDiscount(any());

    probe = scenario();
    when(probe.order.getEtgoTotalDiscount()).thenReturn(BigDecimal.ZERO);
    run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");
    verify(probe.invoice, never()).setEtgoTotalDiscount(any());
  }

  // ── what the native process produced ──────────────────────────────────────

  /** If the lines created do not add up to the plan, everything is rolled back. */
  @Test
  public void importOrderLines_whenTheNativeLinesDoNotAddUp_rollsBackAndRefuses()
      throws Exception {
    Probe probe = scenario();
    probe.createdInstead = bd("1");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importQuantityNotHonoured", errorCode(response));
    assertTrue(probe.rolledBack);
  }

  /** The invoice is never completed by an import: nothing but line creation touches status. */
  @Test
  public void importOrderLines_neverTouchesTheInvoiceStatus() throws Exception {
    Probe probe = scenario();

    run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    verify(probe.invoice, never()).setDocumentStatus(any());
    verify(probe.invoice, never()).setDocumentAction(any());
  }

  // ── discovery ─────────────────────────────────────────────────────────────

  @Test
  public void availableOrders_listsOnlyOrdersWithSomethingToInvoice() throws Exception {
    Probe probe = scenario();
    OrderLine done = orderLine("ol7", 10, product("p7", "Hecho", false), "2", "0", "2");
    Order finished = order("o9", false, done);
    probe.candidates = List.of(probe.order, finished);

    JSONArray rows = run(probe, "availableOrders", null).getBody().getJSONObject("response")
        .getJSONArray("data");

    assertEquals(List.of("o1"), ids(rows));
    assertEquals(2, rows.getJSONObject(0).getInt("pendingLines"));
  }

  @Test
  public void availableOrderLines_reportsThePendingAfterDrafts() throws Exception {
    Probe probe = scenario();
    probe.drafts.put("ol1", bd("3"));

    JSONArray rows = run(probe, "availableOrderLines", "{\"orderId\":\"o1\"}").getBody()
        .getJSONObject("response").getJSONArray("data");

    assertEquals(List.of("ol1", "ol2"), ids(rows));
    assertEquals(5, rows.getJSONObject(0).getInt("pendingQuantity"));
    assertNull(probe.nativeSelection);
  }

  // ── contracts ─────────────────────────────────────────────────────────────

  @Test
  public void contracts_declareTheThreeActions_withOptionalLines() throws Exception {
    for (boolean sales : new boolean[] { false, true }) {
      Map<String, NeoActionContract> contracts = ImportOrderLinesIntoInvoiceHandler.contracts(sales);
      assertEquals(List.of("availableOrders", "availableOrderLines", "importOrderLines"),
          List.copyOf(contracts.keySet()));
      assertNull(NeoActionContract.validate(contracts, "importOrderLines",
          new JSONObject().put("orderId", "o1")));
      assertEquals(422, NeoActionContract.validate(contracts, "importOrderLines",
          new JSONObject()).getHttpStatus());
      assertEquals(422, NeoActionContract.validate(contracts, "availableOrderLines",
          new JSONObject()).getHttpStatus());
      assertNull(NeoActionContract.validate(contracts, "availableOrders", new JSONObject()));
    }
  }

  @Test
  public void contracts_nameTheOneStepAlternativeAndTheCompletionOfTheirSide() {
    String purchase = ImportOrderLinesIntoInvoiceHandler.contracts(false).get("importOrderLines")
        .getDescription();
    assertTrue(purchase, purchase.contains("createPurchaseInvoice")
        && purchase.contains("WITHOUT completing") && purchase.contains("covered by another draft"));
    String sales = ImportOrderLinesIntoInvoiceHandler.contracts(true).get("importOrderLines")
        .getDescription();
    assertTrue(sales, sales.contains("createDraftInvoice") && sales.contains("shipment line"));
  }

  // ── review fixes ──────────────────────────────────────────────────────────

  /** B1: a return order is credited through its own flow; it is not importable into an invoice. */
  @Test
  public void importOrderLines_aReturnOrder_isRefusedAsDirectionMismatch() throws Exception {
    Probe probe = scenario();
    when(probe.order.getTransactionDocument().isReturn()).thenReturn(true);

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importDirectionMismatch", errorCode(response));
    assertTrue(response.getBody().getJSONObject("error").getString("message").contains("return"));
    assertTrue(probe.batches.isEmpty());
  }

  @Test
  public void availableOrders_leavesOutReturnOrders_andTheirLinesAreRefused() throws Exception {
    Probe probe = scenario();
    OrderLine returned = orderLine("ol9", 10, product("p9", "Dev", false), "2", "0", "0");
    Order returnOrder = order("o9", false, returned);
    when(returnOrder.getTransactionDocument().isReturn()).thenReturn(true);
    probe.candidates = List.of(returnOrder, probe.order);

    JSONArray rows = run(probe, "availableOrders", null).getBody().getJSONObject("response")
        .getJSONArray("data");

    assertEquals(List.of("o1"), ids(rows));
    probe.order = returnOrder;
    assertEquals(422, run(probe, "availableOrderLines", "{\"orderId\":\"o9\"}").getHttpStatus());
  }

  /** W3: the quantities of the cap are read after the lock, from freshly refreshed order lines. */
  @Test
  public void importOrderLines_refreshesTheOrderLinesAfterLockingAndBeforeReadingTheCap()
      throws Exception {
    Probe probe = scenario();

    run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertTrue(probe.calls.indexOf("lock") < probe.calls.indexOf("refresh"));
    assertTrue(probe.calls.indexOf("refresh") < probe.calls.indexOf("drafts"));
  }

  /** W6: discovery says when it left something out. */
  @Test
  public void availableOrders_reportsHasMoreAndTheLimitsUsed() throws Exception {
    Probe probe = scenario();
    probe.rowLimit = 1;
    probe.scanLimit = 5;
    OrderLine extra = orderLine("olx", 10, product("px", "Extra", false), "3", "0", "0");
    Order second = order("o2", false, extra);
    probe.candidates = List.of(probe.order, second);

    JSONObject truncated = run(probe, "availableOrders", null).getBody().getJSONObject("response");
    assertEquals(1, truncated.getJSONArray("data").length());
    assertTrue(truncated.getBoolean("hasMore"));
    assertEquals(1, truncated.getInt("limit"));
    assertEquals(5, truncated.getInt("scanLimit"));

    probe.rowLimit = 50;
    JSONObject complete = run(probe, "availableOrders", null).getBody().getJSONObject("response");
    assertEquals(2, complete.getJSONArray("data").length());
    assertFalse(complete.getBoolean("hasMore"));

    probe.scanLimit = 2;
    assertTrue("a scan that hit its limit may have hidden orders",
        run(probe, "availableOrders", null).getBody().getJSONObject("response")
            .getBoolean("hasMore"));
    assertFalse(run(probe, "availableOrderLines", "{\"orderId\":\"o1\"}").getBody()
        .getJSONObject("response").getBoolean("hasMore"));
  }

  /** N2: never claim a link to an order none of the imported lines belongs to. */
  @Test
  public void importOrderLines_aHeaderLinkedToAnotherOrder_isReportedAsItIs() throws Exception {
    Probe probe = scenario();
    Order other = order("other", false);
    when(probe.invoice.getSalesOrder()).thenReturn(other);

    JSONObject data = data(run(probe, "importOrderLines", "{\"orderId\":\"o1\"}"));

    assertFalse("none of the imported lines belongs to the linked order",
        data.getBoolean("headerLinked"));
    assertEquals("the header's current order, as it is", "other", data.getString("salesOrder"));
  }

  /** Marker: a skipped unmark is loud, never silent. */
  @Test
  public void importOrderLines_aRemainderLineKeepingItsMarkerLink_isRolledBackAndRefused()
      throws Exception {
    Probe probe = scenario();
    probe.drafts.put("ol1", bd("6"));
    doReturn(BigDecimal.ZERO).when(probe.lineById.get("ol1")).getInvoicedQuantity();
    movement(probe, "m1", "6", "0", "6");
    probe.ignoreUnmark = true;

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":4}]}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importQuantityNotHonoured", errorCode(response));
    assertTrue(probe.rolledBack);
  }

  /** Cross-tenant: a document of another client does not exist for the caller. */
  @Test
  public void importOrderLines_aTargetOrOrderOfAnotherClient_isNotFound() throws Exception {
    Probe probe = scenario();
    doReturn(LineImportTestSupport.client("other")).when(probe.invoice).getClient();
    NeoResponse foreignTarget = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");
    assertEquals(404, foreignTarget.getHttpStatus());
    assertEquals("Invoice not found: i1", foreignTarget.getBody().getJSONObject("error")
        .getString("message"));

    probe = scenario();
    doReturn(LineImportTestSupport.client("other")).when(probe.order).getClient();
    assertEquals(404, run(probe, "importOrderLines", "{\"orderId\":\"o1\"}").getHttpStatus());
    assertEquals(404, run(probe, "availableOrderLines", "{\"orderId\":\"o1\"}").getHttpStatus());
    assertTrue(probe.batches.isEmpty());
  }

  /** Marker: the movement line used as a marker is picked deterministically (ORDER BY). */
  @Test
  @SuppressWarnings("unchecked")
  public void nativeRelatedRemaining_isOrderedSoTheMarkerIsDeterministic() {
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(dal.getSession()).thenReturn(session);
    org.hibernate.query.Query<Object[]> query = mock(org.hibernate.query.Query.class);
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    when(session.createQuery(hql.capture(), Mockito.eq(Object[].class))).thenReturn(query);
    when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(query);
    when(query.list()).thenReturn(List.<Object[]>of(new Object[] { "m1", bd("6") }));
    OrderLine line = orderLine("ol1", 10, product("p1", "Agua", true), "10", "0", "0");

    ImportOrderLinesIntoInvoiceHandler handler = new ImportOrderLinesIntoInvoiceHandler();
    handler.nativeRelatedRemaining(line, false);
    handler.nativeRelatedRemaining(line, true);

    for (String statement : hql.getAllValues()) {
      assertTrue(statement, statement.trim().endsWith("order by il.id"));
    }
    assertEquals(2, hql.getAllValues().size());
  }
}
