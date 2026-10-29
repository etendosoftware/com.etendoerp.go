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
import static com.etendoerp.go.schemaforge.LineImportTestSupport.draftInOut;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.errorCode;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.ids;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.order;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.orderLine;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.partner;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.product;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import com.etendoerp.go.schemaforge.InOutLineFromOrderFactory.PendingOrderLine;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link ImportOrderLinesIntoInOutHandler} (ETP-5713): the order import of a draft
 * goods receipt / goods shipment. The DAL reads and writes are seams of the handler, replaced here
 * by a recording double, so each test states a scenario and what the handler did with it: what it
 * refused, what it created, in which order it locked and read.
 *
 * @covers com.etendoerp.go.schemaforge.ImportOrderLinesIntoInOutHandler
 * @covers com.etendoerp.go.schemaforge.AbstractLineImportHandler
 * @covers com.etendoerp.go.schemaforge.NeoQuotaCreateCheck
 */
public class ImportOrderLinesIntoInOutHandlerTest {

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

  /** The handler with every DAL touch replaced; records what it was asked to do. */
  /** Vetoed: Weld scans the test classes, and a bean subclass would make the injection ambiguous. */
  @javax.enterprise.inject.Vetoed
  private static final class Probe extends ImportOrderLinesIntoInOutHandler {
    ShipmentInOut target;
    Order order;
    List<PendingOrderLine> pending = new ArrayList<>();
    Set<String> coveringDrafts = new LinkedHashSet<>();
    Set<String> lineOrderIds = new LinkedHashSet<>();
    List<Order> candidates = new ArrayList<>();
    Locator bin = mock(Locator.class);
    SQLException lockFailure;

    final List<String> calls = new ArrayList<>();
    final List<Object[]> created = new ArrayList<>();
    boolean rolledBack;
    int createdSeq;

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

    int scanLimit = 200;
    int rowLimit = 50;

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
    protected ShipmentInOut loadTarget(String id) {
      return target;
    }

    @Override
    protected Order loadOrder(String id) {
      return order != null && order.getId().equals(id) ? order : null;
    }

    @Override
    protected void lockOrderLines(Collection<String> ids) throws SQLException {
      calls.add("lock");
      if (lockFailure != null) {
        throw lockFailure;
      }
    }

    @Override
    protected List<PendingOrderLine> collectPending(Order o, boolean sales, Set<String> covering) {
      calls.add("pending");
      covering.addAll(coveringDrafts);
      return pending;
    }

    @Override
    protected Locator resolveBin(ShipmentInOut t) {
      calls.add("bin");
      return bin;
    }

    @Override
    protected long nextLineNo(String targetId) {
      return 10L;
    }

    @Override
    protected ShipmentInOutLine createLine(ShipmentInOut t, OrderLine orderLine, Locator b,
        long lineNo, BigDecimal quantity) {
      calls.add("create");
      created.add(new Object[] { orderLine.getId(), quantity, lineNo, b });
      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getId()).thenReturn("new-" + (++createdSeq));
      return line;
    }

    @Override
    protected Set<String> orderIdsOfActiveLines(String targetId) {
      calls.add("orders");
      return lineOrderIds;
    }

    @Override
    protected List<Order> findCandidateOrders(String partnerId, boolean sales, String currency) {
      return candidates;
    }
  }

  private static final Locator NO_BIN = null;

  /** A purchase receipt in draft, an order of two lines (Agua 10 / Pan 5), all of it pending. */
  private Probe purchaseScenario() {
    Probe probe = new Probe();
    probe.target = draftInOut("r1", false);
    OrderLine agua = orderLine("ol1", 10, product("p1", "Agua", true), "10", "0", "0");
    OrderLine pan = orderLine("ol2", 20, product("p2", "Pan", false), "5", "0", "0");
    probe.order = order("o1", false, agua, pan);
    probe.pending = List.of(new PendingOrderLine(agua, bd("10")),
        new PendingOrderLine(pan, bd("5")));
    probe.lineOrderIds.add("o1");
    when(dal.get(Order.class, "o1")).thenReturn(probe.order);
    return probe;
  }

  private static NeoResponse run(Probe probe, String action, String body) throws Exception {
    return probe.handle(action(action, "r1", body));
  }

  // ── routing ───────────────────────────────────────────────────────────────

  @Test
  public void handle_ignoresWhatIsNotItsOwn() throws Exception {
    Probe probe = purchaseScenario();
    assertNull(probe.handle(action("createPurchaseInvoice", "r1", "{}")));
    assertNull(probe.handle(NeoContext.builder().endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST").fieldName("importOrderLines").recordId("r1").build()));
    assertNull(probe.handle(NeoContext.builder().endpointType(NeoEndpointType.ACTION)
        .httpMethod("GET").fieldName("importOrderLines").recordId("r1").build()));
    assertTrue(probe.calls.isEmpty());
  }

  @Test
  public void handle_aBlankRecordId_is400() throws Exception {
    Probe probe = purchaseScenario();
    NeoResponse response = probe.handle(action("importOrderLines", " ", "{\"orderId\":\"o1\"}"));
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handle_aMissingTarget_is404() throws Exception {
    Probe probe = purchaseScenario();
    probe.target = null;
    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");
    assertEquals(404, response.getHttpStatus());
    assertEquals("importDocumentNotFound", errorCode(response));
  }

  @Test
  public void handle_aMissingOrderId_is400AndNothingIsRead() throws Exception {
    Probe probe = purchaseScenario();
    NeoResponse response = run(probe, "importOrderLines", "{}");
    assertEquals(400, response.getHttpStatus());
    assertTrue(probe.calls.isEmpty());
  }

  @Test
  public void handle_anUnknownOrder_is404() throws Exception {
    Probe probe = purchaseScenario();
    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"nope\"}");
    assertEquals(404, response.getHttpStatus());
  }

  // ── the happy paths ───────────────────────────────────────────────────────

  /**
   * ETP-5051 — the lines this import added are checked against the plan quotas before the answer
   * ({@code AbstractLineImportHandler#requireQuotaAllowed}): at a {@code block} limit the
   * order-lines import into a movement answers 403 {@code QUOTA_EXCEEDED} and the whole import is rolled back.
   */
  @Test
  public void importOrderLines_overABlockQuota_answers403AndRollsBackTheImport() throws Exception {
    Probe probe = purchaseScenario();

    NeoResponse response;
    try (MockedStatic<NeoQuotaCreateCheck> quota = QuotaRefusalTestSupport.everyCreateRefused()) {
      response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

      quota.verify(() -> NeoQuotaCreateCheck.requireAllowed(
          org.mockito.ArgumentMatchers.eq(ShipmentInOutLine.ENTITY_NAME),
          org.mockito.ArgumentMatchers.argThat(ids -> ids != null && ids.size() == 2)));
    }

    QuotaRefusalTestSupport.assertQuotaExceeded(response);
    verify(dal).rollbackAndClose();
    verify(dal, org.mockito.Mockito.never()).commitAndClose();
  }


  /** No lines: every pending line is imported in full, in order, linked to its order line. */
  @Test
  public void importOrderLines_withoutLines_importsEverythingPending() throws Exception {
    Probe probe = purchaseScenario();

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(2, probe.created.size());
    assertEquals("ol1", probe.created.get(0)[0]);
    assertEquals(0, bd("10").compareTo((BigDecimal) probe.created.get(0)[1]));
    assertEquals(10L, probe.created.get(0)[2]);
    assertEquals("ol2", probe.created.get(1)[0]);
    assertEquals(0, bd("5").compareTo((BigDecimal) probe.created.get(1)[1]));
    assertEquals(20L, probe.created.get(1)[2]);
    JSONObject data = data(response);
    assertEquals(2, data.getInt("importedCount"));
    JSONArray lines = data.getJSONArray("lines");
    assertEquals("new-1", lines.getJSONObject(0).getString("id"));
    assertEquals("ol1", lines.getJSONObject(0).getString("sourceLineId"));
    assertEquals("p1", lines.getJSONObject(0).getString("product"));
    assertEquals("Agua", lines.getJSONObject(0).getString("productName"));
  }

  @Test
  public void importOrderLines_explicitPartialQuantity_createsExactlyThat() throws Exception {
    Probe probe = purchaseScenario();

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":3}]}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(1, probe.created.size());
    assertEquals("ol1", probe.created.get(0)[0]);
    assertEquals(0, bd("3").compareTo((BigDecimal) probe.created.get(0)[1]));
    assertEquals(1, data(response).getInt("importedCount"));
  }

  /** The same line twice in one request is one line of the summed quantity, capped as one. */
  @Test
  public void importOrderLines_aLineListedTwice_isSummed() throws Exception {
    Probe probe = purchaseScenario();

    run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol2\",\"quantity\":2},{\"sourceLineId\":\"ol2\",\"quantity\":3}]}");

    assertEquals(1, probe.created.size());
    assertEquals(0, bd("5").compareTo((BigDecimal) probe.created.get(0)[1]));
  }

  /** Importing the same line again is allowed while it stays under what is pending. */
  @Test
  public void importOrderLines_theSameLineAgain_isAllowedWhileUnderTheCap() throws Exception {
    Probe probe = purchaseScenario();
    // after a first import of 6 of the 10, the drafts (the target's own lines included) leave 4
    OrderLine agua = probe.pending.get(0).getOrderLine();
    probe.pending = List.of(new PendingOrderLine(agua, bd("4")));

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":4}]}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, bd("4").compareTo((BigDecimal) probe.created.get(0)[1]));
  }

  // ── the cap ───────────────────────────────────────────────────────────────

  /** Over the cap on ONE line: 422 naming product, requested and pending — and NOTHING created. */
  @Test
  public void importOrderLines_overTheCap_is422AndCreatesNothing() throws Exception {
    Probe probe = purchaseScenario();

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol2\",\"quantity\":2},{\"sourceLineId\":\"ol1\",\"quantity\":11}]}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importQuantityExceedsPending", errorCode(response));
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("Agua", error.getString("product"));
    assertEquals(11, error.getInt("requested"));
    assertEquals(10, error.getInt("pending"));
    assertTrue("nothing may be created, not even the valid first line", probe.created.isEmpty());
    assertFalse(probe.calls.contains("create"));
    assertFalse("a refusal before any write needs no rollback", probe.rolledBack);
  }

  /** Drafts count as taken: what the factory leaves pending is the cap, not the order's quantity. */
  @Test
  public void importOrderLines_draftsAreCounted_theCapIsWhatTheyLeave() throws Exception {
    Probe probe = purchaseScenario();
    OrderLine agua = probe.pending.get(0).getOrderLine();
    probe.pending = List.of(new PendingOrderLine(agua, bd("2")));

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":3}]}");

    assertEquals(422, response.getHttpStatus());
    assertEquals(2, response.getBody().getJSONObject("error").getInt("pending"));
    assertTrue(probe.created.isEmpty());
  }

  @Test
  public void importOrderLines_aLineWithNothingPending_isRefusedWithPendingZero()
      throws Exception {
    Probe probe = purchaseScenario();
    probe.pending = List.of(probe.pending.get(1));

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol1\",\"quantity\":1}]}");

    assertEquals(422, response.getHttpStatus());
    assertEquals(0, response.getBody().getJSONObject("error").getInt("pending"));
  }

  @Test
  public void importOrderLines_everythingCoveredByDrafts_isRefusedNamingTheDrafts()
      throws Exception {
    Probe probe = purchaseScenario();
    probe.pending = new ArrayList<>();
    probe.coveringDrafts.add("D-100");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importNothingPending", errorCode(response));
    assertTrue(response.getBody().getJSONObject("error").getString("message").contains("D-100"));
    assertTrue(probe.created.isEmpty());
  }

  @Test
  public void importOrderLines_aLineOfAnotherOrder_isRefused() throws Exception {
    Probe probe = purchaseScenario();

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"foreign\",\"quantity\":1}]}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importLineNotInDocument", errorCode(response));
  }

  /** Concurrency: the order lines are locked BEFORE the pending total is read. */
  @Test
  public void importOrderLines_locksTheOrderLinesBeforeReadingThePending() throws Exception {
    Probe probe = purchaseScenario();

    run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertTrue(probe.calls.indexOf("lock") >= 0);
    assertTrue("lock first, read second",
        probe.calls.indexOf("lock") < probe.calls.indexOf("pending"));
    assertTrue(probe.calls.indexOf("pending") < probe.calls.indexOf("create"));
  }

  // ── the preconditions ─────────────────────────────────────────────────────

  @Test
  public void importOrderLines_aTargetThatIsNotADraft_isRefusedBeforeAnythingIsRead()
      throws Exception {
    Probe probe = purchaseScenario();
    when(probe.target.getDocumentStatus()).thenReturn("CO");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importTargetNotDraft", errorCode(response));
    assertTrue(probe.calls.isEmpty());
  }

  @Test
  public void importOrderLines_aSourceThatIsNotCompleted_isRefused() throws Exception {
    Probe probe = purchaseScenario();
    when(probe.order.getDocumentStatus()).thenReturn("DR");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importSourceNotCompleted", errorCode(response));
    assertTrue(probe.created.isEmpty());
  }

  @Test
  public void importOrderLines_anOrderOfAnotherPartner_isRefused() throws Exception {
    Probe probe = purchaseScenario();
    doReturn(partner("other")).when(probe.order).getBusinessPartner();

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importBusinessPartnerMismatch", errorCode(response));
    assertTrue(probe.created.isEmpty());
  }

  @Test
  public void importOrderLines_anOrderInAnotherCurrency_isRefused() throws Exception {
    Probe probe = purchaseScenario();
    doReturn(currency("usd")).when(probe.order).getCurrency();

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importCurrencyMismatch", errorCode(response));
    assertTrue(probe.created.isEmpty());
  }

  @Test
  public void importOrderLines_aSalesOrderIntoAReceipt_isRefused() throws Exception {
    Probe probe = purchaseScenario();
    when(probe.order.isSalesTransaction()).thenReturn(true);

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importDirectionMismatch", errorCode(response));
  }

  /** The sales mirror: a shipment takes a sales order. */
  @Test
  public void importOrderLines_aSalesOrderIntoAShipment_isImported() throws Exception {
    Probe probe = purchaseScenario();
    when(probe.target.isSalesTransaction()).thenReturn(true);
    when(probe.order.isSalesTransaction()).thenReturn(true);

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(2, probe.created.size());
  }

  // ── storage bin ───────────────────────────────────────────────────────────

  @Test
  public void importOrderLines_aStockableLine_getsTheWarehouseBin() throws Exception {
    Probe probe = purchaseScenario();

    run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertTrue(probe.calls.contains("bin"));
    assertEquals(probe.bin, probe.created.get(0)[3]);
  }

  @Test
  public void importOrderLines_onlyServiceLines_needNoBin() throws Exception {
    Probe probe = purchaseScenario();
    probe.pending = List.of(probe.pending.get(1));

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(200, response.getHttpStatus());
    assertFalse(probe.calls.contains("bin"));
    assertEquals(NO_BIN, probe.created.get(0)[3]);
  }

  @Test
  public void importOrderLines_aStockableLineWithoutAnyBin_isRefusedAndNothingIsCreated()
      throws Exception {
    Probe probe = purchaseScenario();
    probe.bin = null;

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importNoStorageBin", errorCode(response));
    assertTrue(probe.created.isEmpty());
  }

  // ── the header link ───────────────────────────────────────────────────────

  @Test
  public void importOrderLines_oneOrder_linksTheHeader() throws Exception {
    Probe probe = purchaseScenario();

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    verify(probe.target).setSalesOrder(probe.order);
    JSONObject data = data(response);
    assertTrue(data.getBoolean("headerLinked"));
    assertEquals("o1", data.getString("salesOrder"));
  }

  /** Lines from two orders: the header is left without a link. */
  @Test
  public void importOrderLines_twoOrders_leavesTheHeaderEmpty() throws Exception {
    Probe probe = purchaseScenario();
    probe.lineOrderIds.add("o2");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    verify(probe.target, never()).setSalesOrder(any());
    JSONObject data = data(response);
    assertFalse(data.getBoolean("headerLinked"));
    assertFalse(data.has("salesOrder"));
  }

  /** The link the first order set is cleared when a second order joins. */
  @Test
  public void importOrderLines_aSecondOrderJoining_clearsTheLinkTheFirstOneSet() throws Exception {
    Probe probe = purchaseScenario();
    Order first = order("o2", false);
    when(probe.target.getSalesOrder()).thenReturn(first);
    probe.lineOrderIds.add("o2");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    verify(probe.target).setSalesOrder(null);
    assertFalse(data(response).getBoolean("headerLinked"));
  }

  // ── failures ──────────────────────────────────────────────────────────────

  /** An unforeseen failure is rolled back whole and never leaks the text of the error. */
  @Test
  public void importOrderLines_anUnexpectedFailure_isRolledBackAnd500WithoutTheDetail()
      throws Exception {
    Probe probe = purchaseScenario();
    probe.lockFailure = new SQLException("ERROR: deadlock detected on c_orderline");

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(500, response.getHttpStatus());
    String message = response.getBody().getJSONObject("error").getString("message");
    assertFalse(message, message.contains("deadlock"));
    assertTrue(probe.rolledBack);
    assertTrue(probe.created.isEmpty());
  }

  // ── discovery ─────────────────────────────────────────────────────────────

  @Test
  public void availableOrders_listsOnlyTheOrdersWithSomethingPending() throws Exception {
    Probe probe = purchaseScenario();
    probe.candidates = List.of(probe.order);

    NeoResponse response = run(probe, "availableOrders", null);

    assertEquals(200, response.getHttpStatus());
    JSONArray rows = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(List.of("o1"), ids(rows));
    assertEquals(2, rows.getJSONObject(0).getInt("pendingLines"));
    assertEquals("PO-o1", rows.getJSONObject(0).getString("documentNo"));

    probe.pending = new ArrayList<>();
    rows = run(probe, "availableOrders", null).getBody().getJSONObject("response")
        .getJSONArray("data");
    assertEquals(0, rows.length());
  }

  @Test
  public void availableOrderLines_listsThePendingQuantityPerLine() throws Exception {
    Probe probe = purchaseScenario();

    NeoResponse response = run(probe, "availableOrderLines", "{\"orderId\":\"o1\"}");

    JSONArray rows = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(List.of("ol1", "ol2"), ids(rows));
    assertEquals(10, rows.getJSONObject(0).getInt("pendingQuantity"));
    assertEquals("Agua", rows.getJSONObject(0).getString("product$_identifier"));
    assertTrue("reading changes nothing", probe.created.isEmpty());
  }

  @Test
  public void availableOrderLines_anOrderOfAnotherPartner_isRefused() throws Exception {
    Probe probe = purchaseScenario();
    doReturn(partner("other")).when(probe.order).getBusinessPartner();

    NeoResponse response = run(probe, "availableOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(422, response.getHttpStatus());
  }

  @Test
  public void availableOrderLines_aDraftTargetIsNotRequiredToRead() throws Exception {
    Probe probe = purchaseScenario();
    when(probe.target.getDocumentStatus()).thenReturn("CO");

    NeoResponse response = run(probe, "availableOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(200, response.getHttpStatus());
  }

  // ── contracts ─────────────────────────────────────────────────────────────

  @Test
  public void contracts_declareTheThreeActionsInFlowOrder_withOptionalLines() throws Exception {
    for (boolean sales : new boolean[] { false, true }) {
      Map<String, NeoActionContract> contracts = ImportOrderLinesIntoInOutHandler.contracts(sales);
      assertEquals(List.of("availableOrders", "availableOrderLines", "importOrderLines"),
          List.copyOf(contracts.keySet()));
      assertFalse(contracts.get("availableOrders").isMutating());
      assertFalse(contracts.get("availableOrderLines").isMutating());
      assertTrue(contracts.get("importOrderLines").isMutating());
      // lines optional, orderId required
      assertNull(NeoActionContract.validate(contracts, "importOrderLines",
          new JSONObject().put("orderId", "o1")));
      assertNull(NeoActionContract.validate(contracts, "importOrderLines",
          new JSONObject().put("orderId", "o1").put("lines", new JSONArray().put(
              new JSONObject().put("sourceLineId", "a").put("quantity", 1)))));
      assertEquals(422, NeoActionContract.validate(contracts, "importOrderLines",
          new JSONObject()).getHttpStatus());
      assertEquals(422, NeoActionContract.validate(contracts, "importOrderLines",
          new JSONObject().put("orderId", "o1").put("foo", 1)).getHttpStatus());
      assertNull(NeoActionContract.validate(contracts, "availableOrders", new JSONObject()));
    }
  }

  /** The description tells the agent the flow and names the one-step alternative of its side. */
  @Test
  public void contracts_describeTheFlowAndTheOneStepAlternative() {
    String purchase = ImportOrderLinesIntoInOutHandler.contracts(false).get("importOrderLines")
        .getDescription();
    assertTrue(purchase, purchase.contains("availableOrders") && purchase.contains("DRAFT")
        && purchase.contains("createGoodsReceipt") && purchase.contains("NOTHING"));
    String sales = ImportOrderLinesIntoInOutHandler.contracts(true).get("importOrderLines")
        .getDescription();
    assertTrue(sales, sales.contains("createShipment") && sales.contains("goods shipment"));
  }

  // ── review fixes ──────────────────────────────────────────────────────────

  /** W3: the pending is read after the lock, from freshly refreshed order lines. */
  @Test
  public void importOrderLines_refreshesTheOrderLinesAfterLockingAndBeforeReadingThePending()
      throws Exception {
    Probe probe = purchaseScenario();

    run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertTrue(probe.calls.indexOf("lock") < probe.calls.indexOf("refresh"));
    assertTrue(probe.calls.indexOf("refresh") < probe.calls.indexOf("pending"));
  }

  /**
   * W3b: an over-delivered line (ordered 5, delivered 7, "pending" -2) is not pending: the factory
   * keeps its sign flip, so the handler never lists nor imports it as a -2 receipt line.
   */
  @Test
  public void anOverDeliveredLine_isNeitherListedNorImported() throws Exception {
    Probe probe = purchaseScenario();
    OrderLine over = orderLine("ol3", 30, product("p3", "Sal", false), "5", "7", "0");
    probe.order.getOrderLineList().add(over);
    probe.pending = new ArrayList<>(probe.pending);
    probe.pending.add(new PendingOrderLine(over, bd("-2")));

    JSONArray rows = run(probe, "availableOrderLines", "{\"orderId\":\"o1\"}").getBody()
        .getJSONObject("response").getJSONArray("data");
    assertEquals(List.of("ol1", "ol2"), ids(rows));

    NeoResponse imported = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");
    assertEquals(200, imported.getHttpStatus());
    assertEquals(2, probe.created.size());

    NeoResponse explicit = run(probe, "importOrderLines", "{\"orderId\":\"o1\",\"lines\":"
        + "[{\"sourceLineId\":\"ol3\",\"quantity\":-2}]}");
    assertEquals(422, explicit.getHttpStatus());
    assertEquals("importQuantityExceedsPending", errorCode(explicit));
  }

  /** A negative order line (ordered -3, delivered 0) is a legitimate pending of -3. */
  @Test
  public void aNegativeOrderLine_keepsItsNegativePending() throws Exception {
    Probe probe = purchaseScenario();
    OrderLine credit = orderLine("ol4", 40, product("p4", "Dev", false), "-3", "0", "0");
    probe.order.getOrderLineList().add(credit);
    probe.pending = List.of(new PendingOrderLine(credit, bd("-3")));

    NeoResponse response = run(probe, "importOrderLines", "{\"orderId\":\"o1\"}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, bd("-3").compareTo((BigDecimal) probe.created.get(0)[1]));
  }

  /** N2: a header linked to another order is reported as it is, never as linked to this one. */
  @Test
  public void importOrderLines_aHeaderLinkedToAnotherOrder_isReportedAsItIs() throws Exception {
    Probe probe = purchaseScenario();
    Order other = order("other", false);
    when(probe.target.getSalesOrder()).thenReturn(other);

    JSONObject data = data(run(probe, "importOrderLines", "{\"orderId\":\"o1\"}"));

    verify(probe.target, never()).setSalesOrder(any());
    assertFalse(data.getBoolean("headerLinked"));
    assertEquals("other", data.getString("salesOrder"));
  }

  /** Cross-tenant: a document of another client does not exist for the caller. */
  @Test
  public void aTargetOrOrderOfAnotherClient_isNotFound() throws Exception {
    Probe probe = purchaseScenario();
    doReturn(LineImportTestSupport.client("other")).when(probe.target).getClient();
    for (String action : new String[] { "importOrderLines", "availableOrders",
        "availableOrderLines" }) {
      assertEquals(action, 404, run(probe, action, "{\"orderId\":\"o1\"}").getHttpStatus());
    }

    probe = purchaseScenario();
    doReturn(LineImportTestSupport.client("other")).when(probe.order).getClient();
    assertEquals(404, run(probe, "importOrderLines", "{\"orderId\":\"o1\"}").getHttpStatus());
    assertTrue(probe.created.isEmpty());
  }

  /** W6: discovery says when it left something out. */
  @Test
  public void availableOrders_reportsHasMoreAndTheLimitsUsed() throws Exception {
    Probe probe = purchaseScenario();
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
    assertFalse(run(probe, "availableOrders", null).getBody().getJSONObject("response")
        .getBoolean("hasMore"));
    probe.scanLimit = 2;
    assertTrue(run(probe, "availableOrders", null).getBody().getJSONObject("response")
        .getBoolean("hasMore"));
    assertFalse(run(probe, "availableOrderLines", "{\"orderId\":\"o1\"}").getBody()
        .getJSONObject("response").getBoolean("hasMore"));
  }
}
