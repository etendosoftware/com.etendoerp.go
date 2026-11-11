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
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
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
 * @covers com.etendoerp.go.schemaforge.OrderInvoicePending
 * @covers com.etendoerp.go.schemaforge.OrderImportListing
 * @covers com.etendoerp.go.schemaforge.AbstractLineImportHandler
 * @covers com.etendoerp.go.schemaforge.NeoQuotaCreateCheck
 * @covers com.etendoerp.go.schemaforge.AbstractInvoiceLineImportHandler
 * @covers com.etendoerp.go.schemaforge.InvoiceGlobalDiscountRule
 * @covers com.etendoerp.go.schemaforge.OrderImportRequest
 * @covers com.etendoerp.go.schemaforge.ImportListing
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
    /** Further orders a multi-order import can name, by id. */
    final Map<String, Order> otherOrders = new HashMap<>();
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
      return order != null && order.getId().equals(id) ? order : otherOrders.get(id);
    }

    @Override
    protected void lockOrderLines(Collection<String> ids) throws SQLException {
      calls.add("lock");
    }

    /** How many times the draft quantities were read (ETP-5828: batched on sales only). */
    int draftReads;

    @Override
    protected Map<String, BigDecimal> draftInvoicedQuantities(Collection<String> ids,
        boolean sales) {
      calls.add("drafts");
      draftReads++;
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
          // Active, as a real new line: so a check run after creation would count it.
          when(created.isActive()).thenReturn(true);
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

    /** How many candidates the last {@code availableOrders} asked the scan for. */
    Integer lastScan;

    @Override
    protected List<Order> findCandidateOrders(String partnerId, boolean sales, String cur,
        int scan) {
      lastScan = scan;
      return candidates;
    }

    /** Where in the scan the last paged {@code availableOrders} started (ETP-6099). */
    Integer lastOffset;

    /** The page from {@code offset} on: the double hands back {@link #candidates} as that page. */
    @Override
    protected List<Order> findCandidateOrders(String partnerId, boolean sales, String cur,
        int offset, int scan) {
      lastOffset = offset;
      lastScan = scan;
      return candidates;
    }

    /** The order lines THIS invoice already has a line of. */
    Set<String> linesOnInvoice = new HashSet<>();

    @Override
    protected Set<String> orderLinesOnInvoice(String invoiceId) {
      calls.add("linesOnInvoice");
      return linesOnInvoice;
    }

    /** The OTHER drafts holding each order line (ETP-5828 QA UI-4); empty: none. */
    final Map<String, List<OrderInvoicePending.DraftInvoiceRef>> holders = new HashMap<>();
    /** The invoice the last holders read left out, and the order lines it asked about. */
    String holdersExcept;
    List<String> holdersAsked;

    @Override
    protected Map<String, List<OrderInvoicePending.DraftInvoiceRef>> draftInvoicesHolding(
        Collection<String> orderLineIds, boolean sales, String exceptInvoiceId) {
      holdersExcept = exceptInvoiceId;
      holdersAsked = new ArrayList<>(orderLineIds);
      return holders;
    }
  }

  /** The purchase header's policy: offers pendingBasis "classic". */
  private static final RectificativeImportPolicy PURCHASE_POLICY =
      RectificativeImportPolicy.forHeader(type -> "STANDARD", false);
  /** The sales header's policy: no classic basis. */
  private static final RectificativeImportPolicy SALES_POLICY =
      RectificativeImportPolicy.forHeader(type -> "STANDARD", true);

  /** {@link #run} with the policy the header customization would have stored on the request. */
  private static NeoResponse runWithPolicy(Probe probe, String action, String body,
      RectificativeImportPolicy policy) throws Exception {
    NeoContext context = action(action, "i1", body);
    context.setAttribute(RectificativeImportPolicy.ATTRIBUTE, policy);
    return probe.handle(context);
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

  /** The same order and lines on the sales side: a draft sales invoice and a sales order. */
  private Probe salesScenario() {
    Probe probe = new Probe();
    probe.invoice = draftInvoice("i1", true);
    OrderLine agua = orderLine("ol1", 10, product("p1", "Agua", true), "10", "0", "2");
    OrderLine pan = orderLine("ol2", 20, product("p2", "Pan", false), "5", "0", "0");
    probe.order = order("o1", true, agua, pan);
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

  /**
   * ETP-5051 — the lines this import added are checked against the plan quotas before the answer
   * ({@code AbstractLineImportHandler#requireQuotaAllowed}): at a {@code block} limit the
   * order-lines import into an invoice answers 403 {@code QUOTA_EXCEEDED} and the whole import is rolled back.
   */
  @Test
  public void importOrderLines_overABlockQuota_answers403AndRollsBackTheImport() throws Exception {
    Probe probe = scenario();

    NeoResponse response;
    try (MockedStatic<NeoQuotaCreateCheck> quota = QuotaRefusalTestSupport.everyCreateRefused()) {
      response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

      quota.verify(() -> NeoQuotaCreateCheck.requireAllowed(
          org.mockito.ArgumentMatchers.eq(InvoiceLine.ENTITY_NAME),
          org.mockito.ArgumentMatchers.argThat(ids -> ids != null && ids.size() == 2)));
    }

    QuotaRefusalTestSupport.assertQuotaExceeded(response);
    verify(dal).rollbackAndClose();
    verify(dal, org.mockito.Mockito.never()).commitAndClose();
  }


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
    assertEquals("the direction-neutral name says the same", "o1", linked.getString("order"));

    probe = scenario();
    when(probe.invoice.getSalesOrder()).thenReturn(null);
    JSONObject mixed = data(run(probe, "importOrderLines", "{\"orderId\":\"o1\"}"));
    assertFalse(mixed.getBoolean("headerLinked"));
    assertFalse(mixed.has("salesOrder"));
    assertFalse(mixed.has("order"));
  }

  private static final String CARRIED = "carried";
  private static final String UNTOUCHED = "untouched";
  private static final String CONFLICT_B = ImportOrderLinesIntoInvoiceHandler
      .CONFLICT_INVOICE_OTHER_DISCOUNT;
  private static final String CONFLICT_C = ImportOrderLinesIntoInvoiceHandler
      .CONFLICT_INVOICE_LINES_WITHOUT_DISCOUNT;

  /**
   * What the import does with the order's global discount, judged on the invoice BEFORE the call:
   * which lines count as "the invoice had lines" (the total-discount line and inactive lines do not,
   * a line without product does; the lines this import creates never do), and that a conflict
   * creates nothing while {@code carryGlobalDiscount:false} imports and leaves the header alone.
   * The decision itself is {@link #discountConflict_decidesWhetherTheOrdersDiscountCanBeCarried};
   * these rows are what only the full handler shows. One sales row proves the same rule reaches a
   * sales invoice.
   */
  @Test
  public void importOrderLines_carriesTheOrderDiscount_refusesAConflict_orSkipsItOnRequest()
      throws Exception {
    Product sal = product("p9", "Sal", false);
    InvoiceLine productLine = existingLine("pre-product", sal, true);
    Product discountProduct = product(TotalDiscountService.DISCOUNT_PRODUCT_ID, "Descuento",
        false);
    InvoiceLine discountLine = existingLine("pre-discount", discountProduct, true);
    Product te = product("p10", "Té", false);
    InvoiceLine inactiveLine = existingLine("pre-inactive", te, false);
    InvoiceLine noProductLine = existingLine("pre-no-product", null, true);
    InvoiceLine sameRateLine = existingLine("pre-same", sal, true);

    assertDiscountOutcome("empty invoice, no %: the lines of this import do not count",
        false, null, "10", null, CARRIED);
    assertDiscountOutcome("a product line already there, no %", false, null, "10", null,
        CONFLICT_C, productLine);
    assertDiscountOutcome("only the total-discount line, no %", false, null, "10", null, CARRIED,
        discountLine);
    assertDiscountOutcome("only an inactive line, no %", false, null, "10", null, CARRIED,
        inactiveLine);
    assertDiscountOutcome("a line without product, no %", false, null, "10", null, CONFLICT_C,
        noProductLine);
    assertDiscountOutcome("the same %, with lines", false, null, "10", "10", CARRIED,
        sameRateLine);
    assertDiscountOutcome("another %", false, null, "10", "5", CONFLICT_B);
    assertDiscountOutcome("order at 0 %", false, null, "0", null, UNTOUCHED);
    assertDiscountOutcome("order without %", false, null, null, null, UNTOUCHED);
    assertDiscountOutcome("order at 0 %, invoice at 5 %", false, null, "0", "5", CONFLICT_B);
    assertDiscountOutcome("carry=false, order at 0 %, invoice at 5 %", false, false, "0", "5",
        UNTOUCHED);
    assertDiscountOutcome("sales: empty invoice, no %", true, null, "10", null, CARRIED);
    assertDiscountOutcome("carry=false, a product line already there", false, false, "10", null,
        UNTOUCHED, existingLine("pre-product-2", sal, true));
    assertDiscountOutcome("carry=false, another %", false, false, "10", "5", UNTOUCHED);
    assertDiscountOutcome("carry=false, empty invoice: not carried even when it could be",
        false, false, "10", null, UNTOUCHED);
  }

  /** A line the draft invoice already holds before the import. */
  private static InvoiceLine existingLine(String id, Product product, boolean active) {
    InvoiceLine line = invoiceLine(id, product, "1");
    when(line.isActive()).thenReturn(active);
    return line;
  }

  /**
   * Imports the whole order onto the scenario's invoice and checks the outcome: {@link #CARRIED}
   * (imported, header set to the order's %), {@link #UNTOUCHED} (imported, header never set), or a
   * conflict code (422 {@code globalDiscountConflict}, nothing created, header never set).
   *
   * @param carry {@code carryGlobalDiscount} to send, {@code null} to omit it (the default)
   */
  private void assertDiscountOutcome(String label, boolean sales, Boolean carry, String orderPct,
      String invoicePct, String expected, InvoiceLine... existing) throws Exception {
    Probe probe = scenario();
    when(probe.invoice.isSalesTransaction()).thenReturn(sales);
    when(probe.order.isSalesTransaction()).thenReturn(sales);
    BigDecimal orderDiscount = orderPct == null ? null : bd(orderPct);
    BigDecimal invoiceDiscount = invoicePct == null ? null : bd(invoicePct);
    when(probe.order.getEtgoTotalDiscount()).thenReturn(orderDiscount);
    when(probe.invoice.getEtgoTotalDiscount()).thenReturn(invoiceDiscount);
    probe.invoice.getInvoiceLineList().addAll(List.of(existing));
    String body = carry == null ? "{\"orderId\":\"o1\"}"
        : "{\"orderId\":\"o1\",\"carryGlobalDiscount\":" + carry + "}";

    NeoResponse response = run(probe, "importOrderLines", body);

    try {
      if (CARRIED.equals(expected) || UNTOUCHED.equals(expected)) {
        assertEquals("the import goes through", 2, data(response).getInt("importedCount"));
      } else {
        assertEquals(422, response.getHttpStatus());
        assertEquals("globalDiscountConflict", errorCode(response));
        assertEquals(expected, response.getBody().getJSONObject("error").getString("conflict"));
        assertTrue("a conflict creates nothing", probe.batches.isEmpty());
      }
      if (CARRIED.equals(expected)) {
        verify(probe.invoice).setEtgoTotalDiscount(orderDiscount);
      } else {
        verify(probe.invoice, never()).setEtgoTotalDiscount(any());
      }
      if (CARRIED.equals(expected) || UNTOUCHED.equals(expected)) {
        assertGlobalDiscountReported(data(response), expected, carry, orderDiscount);
      }
    } catch (AssertionError e) {
      throw new AssertionError(label, e);
    }
  }

  /**
   * The answer says what the popup's toast says: the % the invoice now holds, or why nothing was
   * carried (carryGlobalDiscount:false wins over "the orders have none").
   */
  private static void assertGlobalDiscountReported(JSONObject data, String expected, Boolean carry,
      BigDecimal orderDiscount) throws Exception {
    JSONObject outcome = data.getJSONObject(ImportOrderLinesIntoInvoiceHandler.KEY_GLOBAL_DISCOUNT);
    if (CARRIED.equals(expected)) {
      assertTrue(outcome.getBoolean("applied"));
      assertEquals(0, orderDiscount.compareTo(new BigDecimal(outcome.getString("percent"))));
      // Reported without trailing zeros nor exponent (ETP-5828): "10", "12.5", never "10.0000".
      String reported = outcome.get("percent").toString();
      assertFalse(reported, reported.matches(".*\\.\\d*0$") || reported.contains("E"));
      assertTrue(outcome.isNull("reason"));
      return;
    }
    assertFalse(outcome.getBoolean("applied"));
    assertTrue(outcome.isNull("percent"));
    assertEquals(Boolean.FALSE.equals(carry) ? InvoiceGlobalDiscountRule.REASON_NOT_REQUESTED
        : InvoiceGlobalDiscountRule.REASON_NO_ORDER_DISCOUNT, outcome.getString("reason"));
  }

  /**
   * The pure decision, one row per case: nothing to carry, (a) the orders differ (0 or none counts
   * as different), (b) the invoice has another %, (c) it has lines without a %, else carry.
   */
  @Test
  public void discountConflict_decidesWhetherTheOrdersDiscountCanBeCarried() {
    String a = ImportOrderLinesIntoInvoiceHandler.CONFLICT_ORDERS_DIFFER;
    Object[][] rows = {
        // label, order discounts, invoice discount, hadLines, expected conflict
        { "two orders at 10, empty invoice: carry", pcts("10", "10"), null, false, null },
        { "10 and 0: the orders differ", pcts("10", "0"), null, false, a },
        { "10 and none: the orders differ", pcts("10", null), null, false, a },
        { "10 and 5: the orders differ", pcts("10", "5"), null, false, a },
        { "orders differ wins over the invoice's state", pcts("10", "5"), bd("3"), true, a },
        { "invoice at 5, orders at 10", pcts("10"), bd("5"), false, CONFLICT_B },
        { "lines and no % on the invoice", pcts("10"), null, true, CONFLICT_C },
        { "lines and an invoice at 0 %", pcts("10"), bd("0"), true, CONFLICT_C },
        { "same % on the invoice, with lines: carry", pcts("10"), bd("10.00"), true, null },
        { "orders without %, invoice at 5 with lines", pcts("0", null), bd("5"), true,
            CONFLICT_B },
        { "orders and invoice without %, with lines: nothing", pcts("0", null), null, true,
            null },
        { "a negative order % counts as 0", pcts("-5", "0"), null, true, null },
        { "same % once rounded to 4 decimals: carry", pcts("10.00004", "10"), bd("9.99996"),
            true, null },
        { "a difference at the 4th decimal: the orders differ", pcts("10.0001", "10"), null,
            false, a },
    };
    for (Object[] row : rows) {
      @SuppressWarnings("unchecked")
      List<BigDecimal> orders = (List<BigDecimal>) row[1];
      assertEquals((String) row[0], row[4], InvoiceGlobalDiscountRule.discountConflict(
          orders, (BigDecimal) row[2], (Boolean) row[3]));
    }
  }

  private static List<BigDecimal> pcts(String... values) {
    List<BigDecimal> list = new ArrayList<>();
    for (String value : values) {
      list.add(value == null ? null : bd(value));
    }
    return list;
  }

  // ── several orders ────────────────────────────────────────────────────────

  /** A second completed order of the scenario's partner: Sal 4, registered with the probe. */
  private static Order secondOrder(Probe probe, String discount) {
    OrderLine sal = orderLine("ol3", 10, product("p3", "Sal", false), "4", "0", "0");
    Order second = order("o2", false, sal);
    BigDecimal pct = discount == null ? null : bd(discount);
    when(second.getEtgoTotalDiscount()).thenReturn(pct);
    probe.lineById.put("ol3", sal);
    probe.otherOrders.put("o2", second);
    return second;
  }

  /**
   * {@code orders:[...]}: the lines of both orders in one call, the lines of all of them locked in
   * one pass, and their shared % carried. The header link is set by the native process, which the
   * probe does not run, so it is not asserted here.
   */
  @Test
  public void importOrderLines_severalOrders_importsTheLinesOfAll_inOneCall() throws Exception {
    Probe probe = scenario();
    when(probe.order.getEtgoTotalDiscount()).thenReturn(bd("10"));
    secondOrder(probe, "10");

    NeoResponse response = run(probe, "importOrderLines", "{\"orders\":[{\"orderId\":\"o1\"},"
        + "{\"orderId\":\"o2\",\"lines\":[{\"sourceLineId\":\"ol3\",\"quantity\":3}]}]}");

    assertEquals(200, response.getHttpStatus());
    JSONObject data = data(response);
    assertEquals(3, data.getInt("importedCount"));
    List<String> sources = new ArrayList<>();
    for (int i = 0; i < data.getJSONArray("lines").length(); i++) {
      sources.add(data.getJSONArray("lines").getJSONObject(i).getString("sourceLineId"));
    }
    assertEquals(List.of("ol1", "ol2", "ol3"), sources);
    assertEquals(0, bd("3").compareTo(qtyOf(probe.batches.get(1).getJSONObject(0))));
    assertEquals("one lock pass for every order", 1,
        probe.calls.stream().filter("lock"::equals).count());
    verify(probe.invoice).setEtgoTotalDiscount(bd("10"));
  }

  /**
   * A conflict answers what the caller needs to ask the user: each order's %, the invoice's state
   * before the call and how to import anyway; and it creates nothing.
   */
  @Test
  public void importOrderLines_aDiscountConflict_answersWhatToAskTheUser_andCreatesNothing()
      throws Exception {
    Probe probe = scenario();
    // Stored at scale 4, as the column holds it: the answer must still read "10".
    when(probe.order.getEtgoTotalDiscount()).thenReturn(bd("10.0000"));
    secondOrder(probe, null);

    NeoResponse response = run(probe, "importOrderLines",
        "{\"orders\":[{\"orderId\":\"o1\"},{\"orderId\":\"o2\"}]}");

    assertEquals(422, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("globalDiscountConflict", error.getString("code"));
    assertEquals(ImportOrderLinesIntoInvoiceHandler.CONFLICT_ORDERS_DIFFER,
        error.getString("conflict"));
    JSONArray orders = error.getJSONArray("orders");
    assertEquals(2, orders.length());
    assertEquals("o1", orders.getJSONObject(0).getString("orderId"));
    assertEquals("PO-o1", orders.getJSONObject(0).getString("documentNo"));
    assertEquals(0, bd("10").compareTo(
        new BigDecimal(orders.getJSONObject(0).getString("globalDiscount"))));
    assertEquals("o2", orders.getJSONObject(1).getString("orderId"));
    assertEquals("no % reads as 0", 0, BigDecimal.ZERO.compareTo(
        new BigDecimal(orders.getJSONObject(1).getString("globalDiscount"))));
    // Same format as the 200 answers (ETP-5828): no trailing zeros, no exponent.
    assertEquals("10", orders.getJSONObject(0).get("globalDiscount").toString());
    assertEquals("0", orders.getJSONObject(1).get("globalDiscount").toString());
    JSONObject invoice = error.getJSONObject("invoice");
    assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(invoice.getString("totalDiscount"))));
    assertEquals("0", invoice.get("totalDiscount").toString());
    assertFalse(invoice.getBoolean("hadLines"));
    assertTrue(error.getString("hint"), error.getString("hint").contains("carryGlobalDiscount:false"));
    assertTrue(probe.batches.isEmpty());
    verify(probe.invoice, never()).setEtgoTotalDiscount(any());
  }

  /** The invoice's own % in a conflict is reported like the orders' ones: 12.5, not 12.5000. */
  @Test
  public void importOrderLines_aDiscountConflict_reportsTheInvoicePercentWithoutTrailingZeros()
      throws Exception {
    Probe probe = scenario();
    BigDecimal orderDiscount = bd("10.0000");
    BigDecimal invoiceDiscount = bd("12.5000");
    when(probe.order.getEtgoTotalDiscount()).thenReturn(orderDiscount);
    when(probe.invoice.getEtgoTotalDiscount()).thenReturn(invoiceDiscount);

    NeoResponse response = run(probe, "importOrderLines", "{\"orders\":[{\"orderId\":\"o1\"}]}");

    assertEquals(422, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(CONFLICT_B, error.getString("conflict"));
    JSONObject order = error.getJSONArray("orders").getJSONObject(0);
    assertEquals("10", order.get("globalDiscount").toString());
    assertEquals("12.5", error.getJSONObject("invoice").get("totalDiscount").toString());
  }

  /**
   * One transaction for every order: an order refused while planning (here nothing pending) leaves
   * nothing created for the others and the header's discount untouched, and the refusal names the
   * order that failed.
   */
  @Test
  public void importOrderLines_severalOrders_oneRefused_createsNothingAndNamesTheOrder()
      throws Exception {
    Probe probe = scenario();
    when(probe.order.getEtgoTotalDiscount()).thenReturn(bd("10"));
    secondOrder(probe, "10");
    doReturn(bd("4")).when(probe.lineById.get("ol3")).getInvoicedQuantity();

    NeoResponse response = run(probe, "importOrderLines",
        "{\"orders\":[{\"orderId\":\"o1\"},{\"orderId\":\"o2\"}]}");

    assertEquals(422, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("importNothingPending", error.getString("code"));
    assertTrue(error.getString("message"), error.getString("message").contains("PO-o2"));
    assertEquals("o2", error.getString("orderId"));
    assertEquals("PO-o2", error.getString("documentNo"));
    assertTrue("nothing created for o1 either", probe.batches.isEmpty());
    verify(probe.invoice, never()).setEtgoTotalDiscount(any());
  }

  /** A request whose shape is not understood is a 400 before any order is loaded or locked. */
  @Test
  public void importOrderLines_aMalformedOrdersRequest_is400AndTouchesNothing() throws Exception {
    StringBuilder tooMany = new StringBuilder("{\"orders\":[");
    for (int i = 0; i <= 50; i++) {
      tooMany.append(i == 0 ? "" : ",").append("{\"orderId\":\"o").append(i).append("\"}");
    }
    tooMany.append("]}");
    String[][] rows = {
        { "orderId and orders", "{\"orderId\":\"o1\",\"orders\":[{\"orderId\":\"o1\"}]}" },
        { "top-level lines and orders", "{\"lines\":[{\"sourceLineId\":\"ol1\",\"quantity\":1}],"
            + "\"orders\":[{\"orderId\":\"o1\"}]}" },
        { "empty orders", "{\"orders\":[]}" },
        { "orders not an array", "{\"orders\":\"o1\"}" },
        { "more than 50 orders", tooMany.toString() },
        { "an entry without orderId", "{\"orders\":[{\"lines\":[]}]}" },
        { "an entry that is not an object", "{\"orders\":[\"o1\"]}" },
        { "the same order twice", "{\"orders\":[{\"orderId\":\"o1\"},{\"orderId\":\"o1\"}]}" },
        { "carryGlobalDiscount not a boolean", "{\"orderId\":\"o1\",\"carryGlobalDiscount\":\"yes\"}" },
    };
    for (String[] row : rows) {
      Probe probe = scenario();

      NeoResponse response = run(probe, "importOrderLines", row[1]);

      assertEquals(row[0], 400, response.getHttpStatus());
      assertEquals(row[0], "importInvalidLine", errorCode(response));
      assertTrue(row[0], probe.calls.isEmpty());
    }
    // MCP clients fill optional parameters with null: a null key is absent, never "both shapes".
    NeoResponse nulls = run(scenario(), "importOrderLines", "{\"orderId\":null,\"lines\":null,"
        + "\"orders\":[{\"orderId\":\"o1\"}],\"carryGlobalDiscount\":null}");
    assertEquals("null keys count as absent", 200, nulls.getHttpStatus());
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
    assertEquals("an order without a global discount reads 0", 0, BigDecimal.ZERO.compareTo(
        new BigDecimal(rows.getJSONObject(0).getString("globalDiscount"))));
  }

  /**
   * Each row carries the order's global discount %, the popup's "Dto. global X %" tag, as the
   * API reports it: rounded at scale 4 for comparisons but serialised without trailing zeros
   * (ETP-5828) — {@code 12.5}, {@code 10}, never {@code 12.5000} nor {@code 1E+1}.
   */
  @Test
  public void availableOrders_reportsEachOrdersGlobalDiscount() throws Exception {
    Object[][] cases = {
        { "12.5000", "12.5" }, { "10.0000", "10" }, { "7.12345", "7.1235" }, { "0", "0" },
        { "-3", "0" },
    };
    for (Object[] row : cases) {
      Probe probe = scenario();
      BigDecimal stored = bd((String) row[0]);
      when(probe.order.getEtgoTotalDiscount()).thenReturn(stored);
      probe.candidates = List.of(probe.order);

      JSONObject first = run(probe, "availableOrders", null).getBody().getJSONObject("response")
          .getJSONArray("data").getJSONObject(0);

      assertEquals((String) row[0], row[1], first.get("globalDiscount").toString());
    }
  }

  /** Prices the scenario's lines and gives its currency 2 decimals, as the popup sees them. */
  private static void pricedLines(Probe probe) {
    OrderLine agua = probe.lineById.get("ol1");
    OrderLine pan = probe.lineById.get("ol2");
    when(agua.getUnitPrice()).thenReturn(bd("47.50"));
    when(agua.getDiscount()).thenReturn(bd("10.0000"));
    when(pan.getUnitPrice()).thenReturn(bd("20"));
    Currency currency = probe.order.getCurrency();
    when(currency.getStandardPrecision()).thenReturn(2L);
  }

  /**
   * Each line carries what the import popup shows for it (ETP-5828, UI/MCP parity): the net unit
   * price, the line discount as a % without trailing zeros, and the amount, price x pending.
   */
  @Test
  public void availableOrderLines_reportsThePendingAfterDrafts() throws Exception {
    Probe probe = scenario();
    pricedLines(probe);
    probe.drafts.put("ol1", bd("3"));

    JSONArray rows = run(probe, "availableOrderLines", "{\"orderId\":\"o1\"}").getBody()
        .getJSONObject("response").getJSONArray("data");

    assertEquals(List.of("ol1", "ol2"), ids(rows));
    JSONObject agua = rows.getJSONObject(0);
    assertEquals(5, agua.getInt("pendingQuantity"));
    assertEquals(0, bd("47.50").compareTo(new BigDecimal(agua.getString("unitPrice"))));
    assertEquals("10", agua.get("discount").toString());
    assertEquals("47.50 x 5", "237.50", agua.get("amount").toString());
    JSONObject pan = rows.getJSONObject(1);
    assertEquals("no line discount reads 0", "0", pan.get("discount").toString());
    assertEquals("20 x 5", "100.00", pan.get("amount").toString());
    assertNull(probe.nativeSelection);
  }

  /**
   * ETP-5828 QA UI-4: a line other draft invoices hold names them ({@code inDraftInvoices}, this
   * invoice left out), so the popup says «en factura borrador X» and the agent can tell the user
   * the same; a line no other draft holds carries no such key. Both directions.
   */
  @Test
  public void availableOrderLines_namesTheOtherDraftsHoldingALine() throws Exception {
    for (boolean isSales : new boolean[] { true, false }) {
      Probe probe = isSales ? salesScenario() : scenario();
      probe.drafts.put("ol2", bd("5"));
      probe.holders.put("ol2", List.of(
          new OrderInvoicePending.DraftInvoiceRef("inv-a", "FV1000002"),
          new OrderInvoicePending.DraftInvoiceRef("inv-b", "FV1000003")));
      String side = isSales ? "sales" : "purchase";

      JSONArray rows = run(probe, "availableOrderLines",
          "{\"orderId\":\"o1\",\"includeCovered\":true}")
          .getBody().getJSONObject("response").getJSONArray("data");

      assertEquals(side, "i1", probe.holdersExcept);
      assertEquals(side + ": only the listed lines are looked up", List.of("ol1", "ol2"),
          probe.holdersAsked);
      assertFalse(side + ": no other draft holds ol1",
          rows.getJSONObject(0).has("inDraftInvoices"));
      JSONArray held = rows.getJSONObject(1).getJSONArray("inDraftInvoices");
      assertEquals(side, 2, held.length());
      assertEquals(side, "inv-a", held.getJSONObject(0).getString("id"));
      assertEquals(side, "FV1000002", held.getJSONObject(0).getString("documentNo"));
      assertEquals(side, "FV1000003", held.getJSONObject(1).getString("documentNo"));
    }
  }

  /**
   * The holders read: drafts of the direction, the target left out, only the session's client and
   * the role's readable organizations (their document number reaches the caller); nothing is read
   * for no line or no readable organization.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void draftHolders_readsTheOtherDraftsOfTheDirection_inTheReadableOrgs() {
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(dal.getSession()).thenReturn(session);
    org.hibernate.query.Query<Object[]> query = mock(org.hibernate.query.Query.class);
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    when(session.createQuery(hql.capture(), Mockito.eq(Object[].class))).thenReturn(query);
    when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(query);
    when(query.setParameterList(Mockito.anyString(), Mockito.anyCollection())).thenReturn(query);
    List<Object[]> found = List.<Object[]>of(new Object[] { "ol1", "inv-a", "FV1" },
        new Object[] { "ol1", "inv-b", "FV2" });
    when(query.list()).thenReturn(found);
    List<String> orgs = List.of("org-1", "org-2");

    Map<String, List<OrderInvoicePending.DraftInvoiceRef>> holders =
        OrderInvoicePending.draftHolders(List.of("ol1"), true, "i1", "client-1", orgs);

    assertEquals(2, holders.get("ol1").size());
    assertEquals("FV2", holders.get("ol1").get(1).documentNo);
    Mockito.verify(query).setParameter("inv", "i1");
    Mockito.verify(query).setParameter("sot", true);
    Mockito.verify(query).setParameter("client", "client-1");
    Mockito.verify(query).setParameterList("orgs", orgs);
    assertTrue(hql.getValue(), hql.getValue().contains("i.documentStatus = 'DR'")
        && hql.getValue().contains("i.id <> :inv")
        && hql.getValue().contains("i.client.id = :client")
        && hql.getValue().contains("i.organization.id in (:orgs)"));
    assertTrue("no line, no query",
        OrderInvoicePending.draftHolders(List.of(), true, "i1", "client-1", orgs).isEmpty());
    assertTrue("no readable organization, no query",
        OrderInvoicePending.draftHolders(List.of("ol1"), true, "i1", "client-1", List.of())
            .isEmpty());
    Mockito.verify(session, Mockito.times(1)).createQuery(Mockito.anyString(),
        Mockito.eq(Object[].class));
  }

  /**
   * The order's amount is the popup row: the plain sum of its pending lines' amounts, never
   * reduced by the order's global discount.
   */
  @Test
  public void availableOrders_reportsTheOrderAmountAsThePopupRow() throws Exception {
    Probe probe = scenario();
    pricedLines(probe);
    when(probe.order.getEtgoTotalDiscount()).thenReturn(bd("10"));
    probe.candidates = List.of(probe.order);

    JSONObject first = run(probe, "availableOrders", null).getBody().getJSONObject("response")
        .getJSONArray("data").getJSONObject(0);

    assertEquals("47.50 x 8 + 20 x 5, no global discount", "480.00",
        first.get("amount").toString());
    assertEquals("10", first.get("globalDiscount").toString());
  }

  // ── contracts ─────────────────────────────────────────────────────────────

  /**
   * importOrderLines takes orderId (now optional: orders may replace it), orders and
   * carryGlobalDiscount (a boolean); the one-or-the-other rule is the handler's 400, not the
   * contract's.
   */
  @Test
  public void contracts_declareTheThreeActions_withOptionalLines() throws Exception {
    for (boolean sales : new boolean[] { false, true }) {
      Map<String, NeoActionContract> contracts = ImportOrderLinesIntoInvoiceHandler.contracts(sales);
      assertEquals(List.of("availableOrders", "availableOrderLines", "importOrderLines"),
          List.copyOf(contracts.keySet()));
      assertNull(NeoActionContract.validate(contracts, "importOrderLines",
          new JSONObject().put("orderId", "o1")));
      assertNull("orderId is optional", NeoActionContract.validate(contracts, "importOrderLines",
          new JSONObject()));
      assertNull(NeoActionContract.validate(contracts, "importOrderLines", new JSONObject(
          "{\"orders\":[{\"orderId\":\"o1\"}],\"carryGlobalDiscount\":false}")));
      assertEquals(422, NeoActionContract.validate(contracts, "importOrderLines",
          new JSONObject().put("orderId", "o1").put("carryGlobalDiscount", "yes"))
          .getHttpStatus());
      assertEquals(422, NeoActionContract.validate(contracts, "availableOrderLines",
          new JSONObject()).getHttpStatus());
      assertNull(NeoActionContract.validate(contracts, "availableOrders", new JSONObject()));
      // ETP-6099: the listing parameters are declared on both directions (ETP-5828: sales only)
      assertNull(sales + " availableOrders", NeoActionContract.validate(contracts,
          "availableOrders", new JSONObject().put("limit", 500).put("offset", 50)
              .put("includeCovered", true).put("detail", true)));
      assertNull(sales + " availableOrderLines", NeoActionContract.validate(contracts,
          "availableOrderLines", new JSONObject().put("orderId", "o1")
              .put("includeCovered", true).put("detail", true)));
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

    // ETP-6099: the scan reads max(scanLimit, limit), so the page is narrowed with it
    probe.rowLimit = 2;
    probe.scanLimit = 2;
    assertTrue("a scan that hit its limit may have hidden orders",
        run(probe, "availableOrders", null).getBody().getJSONObject("response")
            .getBoolean("hasMore"));
    assertFalse(run(probe, "availableOrderLines", "{\"orderId\":\"o1\"}").getBody()
        .getJSONObject("response").getBoolean("hasMore"));
  }

  /**
   * ETP-5828 (sales) and ETP-6099 (purchase too, its popup lists here now): the caller may ask for
   * a longer page ({@code limit}, 1-1000) and the scan reads at least that many candidates;
   * anything else is a 400. Absent, both keep 50 rows / 200.
   */
  @Test
  public void availableOrders_honoursLimitOnBothDirections() throws Exception {
    for (boolean isSales : new boolean[] { true, false }) {
      Probe probe = isSales ? salesScenario() : scenario();
      OrderLine extra = orderLine("olx", 10, product("px", "Extra", false), "3", "0", "0");
      Order second = order("o2", isSales, extra);
      probe.candidates = List.of(probe.order, second);
      String side = isSales ? "sales" : "purchase";

      JSONObject one = run(probe, "availableOrders", "{\"limit\":1}").getBody()
          .getJSONObject("response");
      assertEquals(side, 1, one.getJSONArray("data").length());
      assertTrue(side, one.getBoolean("hasMore"));
      assertEquals(side, 1, one.getInt("limit"));
      assertEquals(side + ": never fewer candidates than the default scan", 200,
          one.getInt("scanLimit"));

      JSONObject wide = run(probe, "availableOrders", "{\"limit\":1000}").getBody()
          .getJSONObject("response");
      assertEquals(side, 2, wide.getJSONArray("data").length());
      assertEquals(side, 1000, wide.getInt("limit"));
      assertEquals(side, Integer.valueOf(1000), probe.lastScan);
      assertEquals(side + ": absent keeps the default page", 50,
          run(probe, "availableOrders", null).getBody().getJSONObject("response").getInt("limit"));
      for (String bad : new String[] { "0", "1001", "2.5", "\"many\"" }) {
        NeoResponse refused = run(probe, "availableOrders", "{\"limit\":" + bad + "}");
        assertEquals(side + " " + bad, 400, refused.getHttpStatus());
        assertEquals(side + " " + bad, "importInvalidParameter", errorCode(refused));
      }
    }
  }

  /**
   * ETP-6099: without limit nor offset the page carries no paging keys (the MCP answer is
   * unchanged); with them it echoes offset and answers nextOffset — the scan position after the
   * last candidate examined while hasMore, null once the scan is complete.
   */
  @Test
  public void availableOrders_paging_echoesOffsetAndAnswersNextOffset() throws Exception {
    Probe probe = scenario();
    OrderLine done = orderLine("ol7", 10, product("p7", "Hecho", false), "2", "0", "2");
    Order finished = order("o8", false, done);
    OrderLine extra = orderLine("olx", 10, product("px", "Extra", false), "3", "0", "0");
    Order second = order("o2", false, extra);
    probe.candidates = List.of(finished, probe.order, second);

    JSONObject plain = run(probe, "availableOrders", null).getBody().getJSONObject("response");
    assertFalse(plain.has("offset"));
    assertFalse(plain.has("nextOffset"));

    JSONObject first = run(probe, "availableOrders", "{\"limit\":1}").getBody()
        .getJSONObject("response");
    assertEquals(List.of("o1"), ids(first.getJSONArray("data")));
    assertEquals(0, first.getInt("offset"));
    assertEquals("stopped at the row limit after examining 2 candidates", 2,
        first.getInt("nextOffset"));
    assertNull("offset 0 reads the scan from its start", probe.lastOffset);

    JSONObject later = run(probe, "availableOrders", "{\"offset\":10}").getBody()
        .getJSONObject("response");
    assertEquals(Integer.valueOf(10), probe.lastOffset);
    assertEquals(Integer.valueOf(200), probe.lastScan);
    assertEquals(10, later.getInt("offset"));
    assertFalse(later.getBoolean("hasMore"));
    assertTrue("a complete scan answers nextOffset null", later.isNull("nextOffset"));

    probe.rowLimit = 2;
    probe.scanLimit = 3;
    JSONObject fullScan = run(probe, "availableOrders", "{\"offset\":4}").getBody()
        .getJSONObject("response");
    assertEquals(2, fullScan.getJSONArray("data").length());
    assertTrue("a scan that read its limit may have left candidates", fullScan.getBoolean("hasMore"));
    assertEquals("offset + every candidate examined", 7, fullScan.getInt("nextOffset"));
  }

  /**
   * ETP-6099: includeCovered also lists an order whose pending before drafts is entirely held by
   * draft invoices, at pendingLines 0 and amount 0; a fully invoiced order stays out either way.
   * detail adds creationDate, absent without it.
   */
  @Test
  public void availableOrders_includeCoveredAndDetail() throws Exception {
    for (boolean isSales : new boolean[] { true, false }) {
      Probe probe = isSales ? salesScenario() : scenario();
      OrderLine done = orderLine("ol7", 10, product("p7", "Hecho", false), "2", "0", "2");
      Order finished = order("o8", isSales, done);
      probe.candidates = List.of(probe.order, finished);
      probe.drafts.put("ol1", bd("8"));
      probe.drafts.put("ol2", bd("5"));
      java.util.Date created = java.util.Date.from(java.time.Instant.parse("2026-11-05T09:12:33Z"));
      when(probe.order.getCreationDate()).thenReturn(created);
      String side = isSales ? "sales" : "purchase";

      assertEquals(side, List.of(), ids(run(probe, "availableOrders", null).getBody()
          .getJSONObject("response").getJSONArray("data")));

      JSONArray rows = run(probe, "availableOrders", "{\"includeCovered\":true}").getBody()
          .getJSONObject("response").getJSONArray("data");
      assertEquals(side, List.of("o1"), ids(rows));
      JSONObject covered = rows.getJSONObject(0);
      assertEquals(side, 0, covered.getInt("pendingLines"));
      assertEquals(side, 0, BigDecimal.ZERO.compareTo(new BigDecimal(covered.getString("amount"))));
      assertFalse(side + ": no detail, no creationDate", covered.has("creationDate"));

      JSONObject detailed = run(probe, "availableOrders",
          "{\"includeCovered\":true,\"detail\":true}").getBody().getJSONObject("response")
          .getJSONArray("data").getJSONObject(0);
      assertEquals(side, "2026-11-05T09:12:33Z", detailed.getString("creationDate"));
    }
  }

  /**
   * ETP-5828: the batched draft read of availableOrders is sales-only. Three candidates with
   * something pending and a page of 2: purchase reads the drafts once per order it scans until the
   * page fills (2, as before), sales once for all of them.
   */
  @Test
  public void availableOrders_readsTheDraftsOnceOnSales_perScannedOrderOnPurchase()
      throws Exception {
    Probe purchase = scenario();
    purchase.rowLimit = 2;
    purchase.candidates = List.of(purchase.order,
        order("o2", false, orderLine("ol5", 10, product("p5", "Uno", false), "3", "0", "0")),
        order("o3", false, orderLine("ol6", 10, product("p6", "Dos", false), "3", "0", "0")));
    assertEquals(2, run(purchase, "availableOrders", null).getBody().getJSONObject("response")
        .getJSONArray("data").length());
    assertEquals("purchase: lazily, one read per scanned order", 2, purchase.draftReads);

    Probe sales = salesScenario();
    sales.candidates = List.of(sales.order,
        order("o2", true, orderLine("ol5", 10, product("p5", "Uno", false), "3", "0", "0")),
        order("o3", true, orderLine("ol6", 10, product("p6", "Dos", false), "3", "0", "0")));
    assertEquals(3, run(sales, "availableOrders", null).getBody().getJSONObject("response")
        .getJSONArray("data").length());
    assertEquals("sales: one batched read for every candidate", 1, sales.draftReads);
  }

  /**
   * ETP-5828 (sales) and ETP-6099 (purchase too): with includeCovered the invoice also gets the
   * lines with nothing left, at 0 — the popup greys them out and so reads every line it shows from
   * this one call. Without it they stay out. A non-boolean includeCovered / detail is a 400
   * importInvalidParameter on both directions.
   */
  @Test
  public void availableOrderLines_includeCovered_addsTheCoveredLinesAtZero_onBothDirections()
      throws Exception {
    String withFlag = "{\"orderId\":\"o1\",\"includeCovered\":true}";
    for (boolean isSales : new boolean[] { true, false }) {
      Probe probe = isSales ? salesScenario() : scenario();
      probe.drafts.put("ol2", bd("5"));
      String side = isSales ? "sales" : "purchase";

      JSONArray rows = run(probe, "availableOrderLines", withFlag).getBody()
          .getJSONObject("response").getJSONArray("data");
      assertEquals(side, List.of("ol1", "ol2"), ids(rows));
      assertEquals(side, 0, rows.getJSONObject(1).getInt("pendingQuantity"));
      assertEquals(side, "Pan", rows.getJSONObject(1).getString("product$_identifier"));
      assertEquals(side, List.of("ol1"), ids(run(probe, "availableOrderLines",
          "{\"orderId\":\"o1\"}").getBody().getJSONObject("response").getJSONArray("data")));
      // The listing flags go through ImportListing on both directions: a non-boolean is a 400
      // importInvalidParameter (only importOrderLines' own flags answer importInvalidLine).
      for (String bad : new String[] { "\"includeCovered\":\"yes\"", "\"detail\":1" }) {
        NeoResponse refused = run(probe, "availableOrderLines", "{\"orderId\":\"o1\"," + bad + "}");
        assertEquals(side + " " + bad, 400, refused.getHttpStatus());
        assertEquals(side + " " + bad, "importInvalidParameter", errorCode(refused));
      }
    }
  }

  /**
   * ETP-6099: detail adds what the purchase popup's client-side import body needs (list / gross
   * price, tax, description, ordered and invoiced quantities), JSON null where the line has none;
   * without detail the row keeps exactly its ETP-5828 keys.
   */
  @Test
  public void availableOrderLines_detail_addsTheLineKeys_onlyWhenAsked() throws Exception {
    Probe probe = scenario();
    pricedLines(probe);
    OrderLine agua = probe.lineById.get("ol1");
    org.openbravo.model.financialmgmt.tax.TaxRate tax =
        mock(org.openbravo.model.financialmgmt.tax.TaxRate.class);
    when(tax.getId()).thenReturn("t1");
    when(agua.getListPrice()).thenReturn(bd("50"));
    when(agua.getTax()).thenReturn(tax);
    when(agua.getDescription()).thenReturn("Botella");

    JSONArray rows = run(probe, "availableOrderLines", "{\"orderId\":\"o1\",\"detail\":true}")
        .getBody().getJSONObject("response").getJSONArray("data");
    JSONObject first = rows.getJSONObject(0);
    assertEquals(0, bd("50").compareTo(new BigDecimal(first.getString("listPrice"))));
    assertTrue("no gross price on the line", first.isNull("grossUnitPrice"));
    assertEquals("t1", first.getString("tax"));
    assertEquals("Botella", first.getString("description"));
    assertEquals(0, bd("10").compareTo(new BigDecimal(first.getString("orderedQuantity"))));
    assertEquals(0, bd("2").compareTo(new BigDecimal(first.getString("invoicedQuantity"))));
    JSONObject second = rows.getJSONObject(1);
    assertTrue(second.isNull("tax"));
    assertTrue(second.isNull("description"));

    JSONObject plain = run(probe, "availableOrderLines", "{\"orderId\":\"o1\"}").getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);
    for (String key : new String[] { "listPrice", "grossUnitPrice", "tax", "description",
        "orderedQuantity", "invoicedQuantity" }) {
      assertFalse(key + " only under detail", plain.has(key));
    }
    assertTrue(plain.has("unitPrice") && plain.has("discount") && plain.has("amount"));
  }

  /**
   * ETP-5828: importOrderInto, what the sales order's "Generar factura" reuses when drafts hold
   * part of the order, plans net of the drafts on the invoice it is handed and decides no global
   * discount (the caller carries the order's %).
   */
  @Test
  public void importOrderInto_createsWhatIsLeftNetOfDrafts_withoutTheDiscountDecision()
      throws Exception {
    Probe probe = salesScenario();
    probe.drafts.put("ol1", bd("3"));

    List<DocumentLineImport.ImportedLine> created =
        probe.importOrderInto(probe.invoice, probe.order, Map.of());

    assertEquals(2, created.size());
    assertEquals(0, bd("5").compareTo(
        new BigDecimal(probe.nativeSelection.getJSONObject(0).getString("orderedQuantity"))));
    assertEquals(0, bd("5").compareTo(
        new BigDecimal(probe.nativeSelection.getJSONObject(1).getString("orderedQuantity"))));
    verify(probe.invoice, never()).setEtgoTotalDiscount(any());
  }

  /**
   * ETP-5828: "Generar factura" on an order partly held by drafts uses the no-draft quantity rule.
   * Agua: ordered 10, invoiced 2, a draft holds 2 (net 6), shipped 6 of which the draft already
   * holds 2 (shipment pending 4): only the 4 shipped, never the 4 unshipped. Pan has no shipments:
   * its net pending, 5. With the shipped part all held by drafts, Agua has no shipment left to
   * take, so it is invoiced for its net pending (6, the unshipped rest) like a line without
   * shipments.
   */
  @Test
  public void shipmentScopedPending_invoicesOnlyTheShippedPartOfAShippedLine() {
    Probe probe = salesScenario();
    probe.drafts.put("ol1", bd("2"));
    movement(probe, "m1", "6", "4", "6");

    Map<String, BigDecimal> scoped = probe.shipmentScopedPending(probe.order);
    assertEquals(List.of("ol1", "ol2"), List.copyOf(scoped.keySet()));
    assertEquals(0, bd("4").compareTo(scoped.get("ol1")));
    assertEquals(0, bd("5").compareTo(scoped.get("ol2")));

    probe.movementPending.put("m1", bd("0"));
    Map<String, BigDecimal> allHeld = probe.shipmentScopedPending(probe.order);
    assertEquals(List.of("ol1", "ol2"), List.copyOf(allHeld.keySet()));
    assertEquals(0, bd("6").compareTo(allHeld.get("ol1")));
    assertEquals(0, bd("5").compareTo(allHeld.get("ol2")));
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

  // ── server basis: the lines already on THIS invoice (ETP-6099) ────────────

  /** The rows of a server-basis availableOrders answer. */
  private static JSONArray serverOrders(Probe probe, String body) throws Exception {
    return run(probe, "availableOrders", body).getBody().getJSONObject("response")
        .getJSONArray("data");
  }

  /**
   * With detail, a pending line THIS invoice already holds is not selectable: it counts in neither
   * pendingLines nor amount, on both directions (sales reads the drafts batched, purchase lazily).
   */
  @Test
  public void availableOrders_detail_leavesOutTheLinesAlreadyOnThisInvoice() throws Exception {
    for (boolean isSales : new boolean[] { true, false }) {
      Probe probe = isSales ? salesScenario() : scenario();
      pricedLines(probe);
      probe.linesOnInvoice = Set.of("ol2");
      probe.candidates = List.of(probe.order);
      String side = isSales ? "sales" : "purchase";

      JSONArray rows = serverOrders(probe, "{\"detail\":true}");

      assertEquals(side, List.of("o1"), ids(rows));
      JSONObject o1 = rows.getJSONObject(0);
      assertEquals(side + ": Pan is on this invoice, only Agua is selectable", 1,
          o1.getInt("pendingLines"));
      assertEquals(side + ": 47.50 x 8, Pan's 20 x 5 left out", "380.00",
          o1.get("amount").toString());
      assertEquals(side, 1, probe.calls.stream().filter("linesOnInvoice"::equals).count());
    }
  }

  /**
   * With detail, an order whose every pending line is already on THIS invoice is out of the plain
   * answer, and with includeCovered it is listed at pendingLines 0 and amount 0.
   */
  @Test
  public void availableOrders_detail_anOrderEntirelyOnThisInvoice_onlyWithIncludeCovered()
      throws Exception {
    Probe probe = salesScenario();
    pricedLines(probe);
    probe.linesOnInvoice = Set.of("ol1", "ol2");
    probe.candidates = List.of(probe.order);

    assertEquals(List.of(), ids(serverOrders(probe, "{\"detail\":true}")));

    JSONArray rows = serverOrders(probe, "{\"detail\":true,\"includeCovered\":true}");
    assertEquals(List.of("o1"), ids(rows));
    assertEquals(0, rows.getJSONObject(0).getInt("pendingLines"));
    assertEquals(0, BigDecimal.ZERO.compareTo(
        new BigDecimal(rows.getJSONObject(0).getString("amount"))));
  }

  /** Without detail the answer is the pre-ETP-6099 one: the invoice's own lines are not read. */
  @Test
  public void availableOrders_withoutDetail_countsEveryPendingLine_andReadsNoInvoiceLines()
      throws Exception {
    for (boolean isSales : new boolean[] { true, false }) {
      Probe probe = isSales ? salesScenario() : scenario();
      pricedLines(probe);
      probe.linesOnInvoice = Set.of("ol2");
      probe.candidates = List.of(probe.order);
      String side = isSales ? "sales" : "purchase";

      JSONObject o1 = serverOrders(probe, null).getJSONObject(0);

      assertEquals(side, 2, o1.getInt("pendingLines"));
      assertEquals(side + ": 47.50 x 8 + 20 x 5", "480.00", o1.get("amount").toString());
      assertFalse(side, probe.calls.contains("linesOnInvoice"));
    }
  }

  /**
   * Server availableOrderLines with detail marks importedHere per line; a line on THIS invoice is
   * still listed with its pending (the popup greys it out). Without detail the key is absent and
   * the invoice's lines are not read.
   */
  @Test
  public void availableOrderLines_detail_marksImportedHere_onlyWhenAsked() throws Exception {
    for (boolean isSales : new boolean[] { true, false }) {
      Probe probe = isSales ? salesScenario() : scenario();
      probe.linesOnInvoice = Set.of("ol2");
      String side = isSales ? "sales" : "purchase";

      JSONArray rows = run(probe, "availableOrderLines", "{\"orderId\":\"o1\",\"detail\":true}")
          .getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(side, List.of("ol1", "ol2"), ids(rows));
      assertFalse(side, rows.getJSONObject(0).getBoolean("importedHere"));
      assertTrue(side, rows.getJSONObject(1).getBoolean("importedHere"));
      assertEquals(side + ": Pan keeps its pending", 5,
          rows.getJSONObject(1).getInt("pendingQuantity"));

      probe.calls.clear();
      JSONArray plain = run(probe, "availableOrderLines", "{\"orderId\":\"o1\"}").getBody()
          .getJSONObject("response").getJSONArray("data");
      assertEquals(side, List.of("ol1", "ol2"), ids(plain));
      assertFalse(side, plain.getJSONObject(0).has("importedHere"));
      assertFalse(side, plain.getJSONObject(1).has("importedHere"));
      assertFalse(side, probe.calls.contains("linesOnInvoice"));
    }
  }

  /**
   * selectableOnly: an empty set (no detail) or an empty pending hands the same map back; otherwise
   * a copy without exactly the keys in the set, leaving the input untouched.
   */
  @Test
  public void selectableOnly_removesOnlyTheLinesOnThisInvoice() {
    Map<String, BigDecimal> pending = new LinkedHashMap<>();
    pending.put("ol1", bd("8"));
    pending.put("ol2", bd("5"));
    Object[][] cases = {
        { Set.of(), List.of("ol1", "ol2") },
        { Set.of("ol2"), List.of("ol1") },
        { Set.of("ol9"), List.of("ol1", "ol2") },
        { Set.of("ol1", "ol2"), List.of() },
    };
    for (Object[] c : cases) {
      @SuppressWarnings("unchecked")
      Set<String> here = (Set<String>) c[0];

      Map<String, BigDecimal> selectable =
          ImportOrderLinesIntoInvoiceHandler.selectableOnly(pending, here);

      assertEquals(here.toString(), c[1], new ArrayList<>(selectable.keySet()));
      assertEquals(here + ": the input is untouched", List.of("ol1", "ol2"),
          new ArrayList<>(pending.keySet()));
    }
    assertSame("no lines on the invoice: the same map", pending,
        ImportOrderLinesIntoInvoiceHandler.selectableOnly(pending, Set.of()));
    Map<String, BigDecimal> none = Map.of();
    assertSame("nothing pending: the same map", none,
        ImportOrderLinesIntoInvoiceHandler.selectableOnly(none, Set.of("ol1")));
    assertEquals(0, bd("8").compareTo(
        ImportOrderLinesIntoInvoiceHandler.selectableOnly(pending, Set.of("ol2")).get("ol1")));
  }

  // ── pendingBasis (ETP-6099 follow-up, classic removed by ETP-5828 QA MCP-2) ─────────

  /** A purchase listing page as the purchase header's request would carry it. */
  private static JSONObject purchasePage(Probe probe, String action, String body)
      throws Exception {
    return runWithPolicy(probe, action, body, PURCHASE_POLICY).getBody()
        .getJSONObject("response");
  }

  /**
   * Without pendingBasis, or with "server", the purchase listings answer the server rule (drafts
   * deducted), the one the purchase popup, the sales popup and the MCP all list with.
   */
  @Test
  public void serverPendingBasis_isTheDefault_onTheOrderActions() throws Exception {
    for (String body : new String[] { "{\"orderId\":\"o1\"}",
        "{\"orderId\":\"o1\",\"pendingBasis\":\"server\"}" }) {
      Probe probe = scenario();
      probe.drafts.put("ol1", bd("3"));
      probe.candidates = List.of(probe.order);

      JSONArray lines = purchasePage(probe, "availableOrderLines", body).getJSONArray("data");
      assertEquals(body, 5, lines.getJSONObject(0).getInt("pendingQuantity"));
      assertFalse(body, lines.getJSONObject(0).has("importedHere"));

      JSONArray orders = purchasePage(probe, "availableOrders", body).getJSONArray("data");
      assertEquals(body, List.of("o1"), ids(orders));
      assertFalse(body, probe.calls.contains("linesOnInvoice"));
    }
  }

  /**
   * ETP-5828 QA MCP-2: the purchase popup lists with the server rule, so no order action offers
   * "classic" any more, not even on the purchase header (the receipt listings still do): it is a
   * 400 importInvalidParameter before anything runs, like on the sales header or an import.
   */
  @Test
  public void classicPendingBasis_isNotOfferedByTheOrderActions_is400AndNothingRuns()
      throws Exception {
    Object[][] cases = {
        { false, "availableOrders", PURCHASE_POLICY },
        { false, "availableOrderLines", PURCHASE_POLICY },
        { true, "availableOrders", SALES_POLICY },
        { true, "availableOrderLines", SALES_POLICY },
        { false, "importOrderLines", PURCHASE_POLICY },
        { false, "availableOrders", RectificativeImportPolicy.NONE },
        { false, "availableOrders", null },
    };
    for (Object[] c : cases) {
      Probe probe = (Boolean) c[0] ? salesScenario() : scenario();
      probe.candidates = List.of(probe.order);
      String label = c[1] + " / " + c[2];

      NeoResponse refused = runWithPolicy(probe, (String) c[1],
          "{\"orderId\":\"o1\",\"pendingBasis\":\"classic\"}",
          (RectificativeImportPolicy) c[2]);

      assertEquals(label, 400, refused.getHttpStatus());
      assertEquals(label, "importInvalidParameter", errorCode(refused));
      assertEquals(label, List.of(), probe.calls);
      assertNull(label, probe.lastScan);
      assertTrue(label, probe.batches.isEmpty());
    }
  }

  /** An unknown pendingBasis is a 400 too. */
  @Test
  public void anUnknownPendingBasis_is400() throws Exception {
    Probe probe = scenario();
    probe.candidates = List.of(probe.order);

    NeoResponse refused = runWithPolicy(probe, "availableOrders",
        "{\"pendingBasis\":\"Classic\"}", PURCHASE_POLICY);

    assertEquals(400, refused.getHttpStatus());
    assertEquals("importInvalidParameter", errorCode(refused));
    assertNull(probe.lastScan);
  }
}
