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
import static com.etendoerp.go.schemaforge.LineImportTestSupport.completedInOut;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.currency;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.data;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.draftInvoice;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.errorCode;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.ids;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.inOutLine;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.invoiceLine;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.partner;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.product;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
 * Unit tests for {@link ImportInOutLinesIntoInvoiceHandler} (ETP-5713): the goods receipt /
 * goods shipment import of a draft purchase / sales invoice. The DAL, the pending-quantity query and
 * the native line creation are seams, replaced here by a recording double that creates one invoice
 * line per entry it is handed.
 *
 * <p>ETP-5876 adds the return actions of a rectificative invoice ({@code availableReturns},
 * {@code availableReturnLines}, {@code importReturnLines}): they credit a completed return in
 * NEGATIVE, against the same pending computation, whichever sign the return stored.
 *
 * @covers com.etendoerp.go.schemaforge.ImportInOutLinesIntoInvoiceHandler
 * @covers com.etendoerp.go.schemaforge.ReturnLineCredit
 */
public class ImportInOutLinesIntoInvoiceHandlerTest {

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
  private static final class Probe extends ImportInOutLinesIntoInvoiceHandler {
    Invoice invoice;
    ShipmentInOut source;
    /** Pending MAGNITUDE per movement line, as NeoInvoiceSupport answers it. */
    Map<String, BigDecimal> pending = new LinkedHashMap<>();
    List<ShipmentInOut> candidates = new ArrayList<>();
    BigDecimal createdInstead;

    final List<String> calls = new ArrayList<>();
    JSONArray nativeSelection;
    Class<?> nativeFrom;
    final Map<String, ShipmentInOutLine> lineById = new LinkedHashMap<>();
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
    protected ShipmentInOut loadSource(String id) {
      return source != null && source.getId().equals(id) ? source : null;
    }

    @Override
    protected void lockInOutLines(Collection<String> ids) throws SQLException {
      calls.add("lock");
    }

    @Override
    protected Map<String, BigDecimal> pendingQuantities(String inOutId) {
      calls.add("pending");
      return pending;
    }

    int scanLimit = 200;
    int rowLimit = 50;

    @Override
    protected String sessionClientId() {
      return LineImportTestSupport.CLIENT;
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
      nativeSelection = selected;
      nativeFrom = from;
      try {
        for (int i = 0; i < selected.length(); i++) {
          JSONObject entry = selected.getJSONObject(i);
          ShipmentInOutLine movement = lineById.get(entry.getString("id"));
          BigDecimal qty = createdInstead != null ? createdInstead
              : new BigDecimal(entry.getString("orderedQuantity"));
          InvoiceLine created = invoiceLine("il-" + i, movement.getProduct(), qty.toPlainString());
          when(created.getGoodsShipmentLine()).thenReturn(movement);
          doReturn(movement.getSalesOrderLine()).when(created).getSalesOrderLine();
          target.getInvoiceLineList().add(created);
        }
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    protected List<ShipmentInOut> findCandidateDocuments(String partnerId, boolean sales,
        String cur) {
      return candidates;
    }

    List<ShipmentInOut> returnCandidates = new ArrayList<>();
    /** What every invoice line of any status holds per return line (the sales modal's sum). */
    Map<String, BigDecimal> credited = new LinkedHashMap<>();
    /** Order lines the sales modal treats as already invoiced. */
    Set<String> blockedOrderLines = new java.util.HashSet<>();
    /** The window's invoiceStatus of the return, in percent. */
    BigDecimal invoiceStatus = BigDecimal.ZERO;
    /** Which window's modal rules the header would pass in. */
    boolean salesModalRules;

    @Override
    protected List<ShipmentInOut> findCandidateReturns(String partnerId, boolean sales) {
      return returnCandidates;
    }

    @Override
    protected Map<String, BigDecimal> creditedQuantities(String inOutId) {
      calls.add("credited");
      return credited;
    }

    @Override
    protected Set<String> blockedOrderLines(String inOutId, String invoiceId) {
      return blockedOrderLines;
    }

    @Override
    protected BigDecimal invoiceStatusOf(String inOutId) {
      return invoiceStatus;
    }

    /** A request carrying the policy the header of this side would store. */
    NeoResponse run(String action, String body) throws Exception {
      NeoContext context = action(action, "i1", body);
      context.setAttribute(RectificativeImportPolicy.ATTRIBUTE,
          new RectificativeImportPolicy(inv -> true, salesModalRules));
      return handle(context);
    }
  }

  /** A purchase draft invoice and a completed receipt: Agua 10 and Pan 5, all of it pending. */
  private Probe purchase() {
    Probe probe = new Probe();
    probe.invoice = draftInvoice("i1", false);
    ShipmentInOutLine agua = inOutLine("rl1", 10, product("p1", "Agua", true), "10");
    ShipmentInOutLine pan = inOutLine("rl2", 20, product("p2", "Pan", false), "5");
    probe.source = completedInOut("rc1", false, agua, pan);
    probe.lineById.put("rl1", agua);
    probe.lineById.put("rl2", pan);
    probe.pending.put("rl1", bd("10"));
    probe.pending.put("rl2", bd("5"));
    probe.invoiceFromOrderSupport = mock(InvoiceFromOrderSupport.class);
    return probe;
  }

  /** The sales mirror: a draft sales invoice and a completed shipment. */
  private Probe sales() {
    Probe probe = purchase();
    when(probe.invoice.isSalesTransaction()).thenReturn(true);
    when(probe.source.isSalesTransaction()).thenReturn(true);
    return probe;
  }

  private static NeoResponse importReceipt(Probe probe, String body) throws Exception {
    return probe.handle(action("importReceiptLines", "i1", body));
  }

  private static NeoResponse importShipment(Probe probe, String body) throws Exception {
    return probe.handle(action("importShipmentLines", "i1", body));
  }

  // ── routing ───────────────────────────────────────────────────────────────

  @Test
  public void handle_ignoresWhatIsNotItsOwn() throws Exception {
    Probe probe = purchase();
    assertNull(probe.handle(action("importOrderLines", "i1", "{}")));
    assertNull(probe.handle(action("createGoodsReceipt", "i1", "{}")));
    assertTrue(probe.calls.isEmpty());
  }

  // ── the happy paths ───────────────────────────────────────────────────────

  /** No lines: everything pending, in full, through the native process with movement lines. */
  @Test
  public void importReceiptLines_withoutLines_importsEverythingPending() throws Exception {
    Probe probe = purchase();

    NeoResponse response = importReceipt(probe, "{\"receiptId\":\"rc1\"}");

    assertEquals(200, response.getHttpStatus());
    assertSame(ShipmentInOutLine.class, probe.nativeFrom);
    assertEquals(2, probe.nativeSelection.length());
    assertEquals("rl1", probe.nativeSelection.getJSONObject(0).getString("id"));
    assertEquals(0, bd("10").compareTo(
        new BigDecimal(probe.nativeSelection.getJSONObject(0).getString("orderedQuantity"))));
    JSONObject data = data(response);
    assertEquals(2, data.getInt("importedCount"));
    assertEquals("rl1", data.getJSONArray("lines").getJSONObject(0).getString("sourceLineId"));
    assertEquals("Agua", data.getJSONArray("lines").getJSONObject(0).getString("productName"));
  }

  @Test
  public void importReceiptLines_explicitPartialQuantity_isHonouredExactly() throws Exception {
    Probe probe = purchase();

    NeoResponse response = importReceipt(probe, "{\"receiptId\":\"rc1\",\"lines\":"
        + "[{\"sourceLineId\":\"rl2\",\"quantity\":2}]}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(1, probe.nativeSelection.length());
    assertEquals("rl2", probe.nativeSelection.getJSONObject(0).getString("id"));
    assertEquals(0, bd("2").compareTo(
        new BigDecimal(probe.nativeSelection.getJSONObject(0).getString("orderedQuantity"))));
  }

  @Test
  public void importShipmentLines_isTheSalesMirror() throws Exception {
    Probe probe = sales();

    NeoResponse response = importShipment(probe, "{\"shipmentId\":\"rc1\"}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(2, data(response).getInt("importedCount"));
    assertSame(ShipmentInOutLine.class, probe.nativeFrom);
  }

  // ── the cap: the REAL pending, not the movement quantity ──────────────────

  /**
   * What the receipt has left after completed AND draft invoices (the target's own lines too) is
   * the pending map: a line absent from it has nothing left and is not offered nor imported.
   */
  @Test
  public void importReceiptLines_withoutLines_skipsWhatInvoicesAndDraftsAlreadyCover()
      throws Exception {
    Probe probe = purchase();
    probe.pending.remove("rl2");
    probe.pending.put("rl1", bd("4"));

    NeoResponse response = importReceipt(probe, "{\"receiptId\":\"rc1\"}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(1, probe.nativeSelection.length());
    assertEquals("rl1", probe.nativeSelection.getJSONObject(0).getString("id"));
    assertEquals(0, bd("4").compareTo(
        new BigDecimal(probe.nativeSelection.getJSONObject(0).getString("orderedQuantity"))));
  }

  /** Over the real pending: 422, and nothing created. The full movement quantity is not the cap. */
  @Test
  public void importReceiptLines_overTheRealPending_is422AndCreatesNothing() throws Exception {
    Probe probe = purchase();
    probe.pending.put("rl1", bd("4"));

    NeoResponse response = importReceipt(probe, "{\"receiptId\":\"rc1\",\"lines\":"
        + "[{\"sourceLineId\":\"rl2\",\"quantity\":1},{\"sourceLineId\":\"rl1\",\"quantity\":5}]}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importQuantityExceedsPending", errorCode(response));
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("Agua", error.getString("product"));
    assertEquals(5, error.getInt("requested"));
    assertEquals(4, error.getInt("pending"));
    assertNull(probe.nativeSelection);
  }

  @Test
  public void importReceiptLines_aFullyInvoicedLine_isRefusedWithPendingZero() throws Exception {
    Probe probe = purchase();
    probe.pending.remove("rl1");

    NeoResponse response = importReceipt(probe, "{\"receiptId\":\"rc1\",\"lines\":"
        + "[{\"sourceLineId\":\"rl1\",\"quantity\":1}]}");

    assertEquals(422, response.getHttpStatus());
    assertEquals(0, response.getBody().getJSONObject("error").getInt("pending"));
    assertNull(probe.nativeSelection);
  }

  @Test
  public void importReceiptLines_everythingAlreadyInvoiced_isRefusedAsNothingPending()
      throws Exception {
    Probe probe = purchase();
    probe.pending.clear();

    NeoResponse response = importReceipt(probe, "{\"receiptId\":\"rc1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importNothingPending", errorCode(response));
  }

  /** The pending query can say more than the line moved; the line never invoices above it. */
  @Test
  public void signedPending_isCappedByTheMovementQuantity_andKeepsTheLineSign() {
    Map<String, ShipmentInOutLine> lines = new LinkedHashMap<>();
    lines.put("a", inOutLine("a", 10, product("p", "Agua", true), "10"));
    lines.put("b", inOutLine("b", 20, product("p", "Agua", true), "-3"));
    lines.put("c", inOutLine("c", 30, product("p", "Agua", true), "0"));
    Map<String, BigDecimal> magnitudes = new LinkedHashMap<>();
    magnitudes.put("a", bd("12"));
    magnitudes.put("b", bd("3"));
    magnitudes.put("c", bd("5"));

    Map<String, BigDecimal> pending = ImportInOutLinesIntoInvoiceHandler.signedPending(lines,
        magnitudes);

    assertEquals(0, bd("10").compareTo(pending.get("a")));
    assertEquals(0, bd("-3").compareTo(pending.get("b")));
    assertFalse("a line that moved nothing has nothing to invoice", pending.containsKey("c"));
  }

  @Test
  public void importReceiptLines_aNegativeLine_isImportedWithItsOwnSign() throws Exception {
    Probe probe = purchase();
    ShipmentInOutLine credit = inOutLine("rl3", 30, product("p3", "Devolución", false), "-3");
    probe.source.getMaterialMgmtShipmentInOutLineList().add(credit);
    probe.lineById.put("rl3", credit);
    probe.pending.put("rl3", bd("3"));

    NeoResponse response = importReceipt(probe, "{\"receiptId\":\"rc1\",\"lines\":"
        + "[{\"sourceLineId\":\"rl3\",\"quantity\":-2}]}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, bd("-2").compareTo(
        new BigDecimal(probe.nativeSelection.getJSONObject(0).getString("orderedQuantity"))));

    Probe wrongSign = purchase();
    wrongSign.source.getMaterialMgmtShipmentInOutLineList().add(credit);
    wrongSign.lineById.put("rl3", credit);
    wrongSign.pending.put("rl3", bd("3"));
    assertEquals(422, importReceipt(wrongSign, "{\"receiptId\":\"rc1\",\"lines\":"
        + "[{\"sourceLineId\":\"rl3\",\"quantity\":2}]}").getHttpStatus());
  }

  @Test
  public void importReceiptLines_locksTheMovementLinesBeforeReadingThePending() throws Exception {
    Probe probe = purchase();

    importReceipt(probe, "{\"receiptId\":\"rc1\"}");

    assertTrue(probe.calls.indexOf("lock") >= 0);
    assertTrue(probe.calls.indexOf("lock") < probe.calls.indexOf("pending"));
    assertTrue(probe.calls.indexOf("pending") < probe.calls.indexOf("native"));
  }

  /** Lines already on the invoice are left alone: the discount copy is scoped to the new ones. */
  @Test
  public void importReceiptLines_postProcessesOnlyTheNewLines() throws Exception {
    Probe probe = purchase();
    probe.invoice.getInvoiceLineList().add(invoiceLine("old", product("p9", "Viejo", false), "1"));

    importReceipt(probe, "{\"receiptId\":\"rc1\"}");

    verify(probe.invoiceFromOrderSupport).copyLineDiscountsFromOrder(probe.invoice,
        Set.of("il-0", "il-1"));
    verify(probe.invoiceFromOrderSupport).ensureLineGrossAmounts(probe.invoice,
        Set.of("il-0", "il-1"));
    verify(probe.invoiceFromOrderSupport).alignLineListPrices(probe.invoice,
        Set.of("il-0", "il-1"));
  }

  // ── the preconditions ─────────────────────────────────────────────────────

  @Test
  public void importReceiptLines_aTargetThatIsNotADraft_isRefused() throws Exception {
    Probe probe = purchase();
    when(probe.invoice.getDocumentStatus()).thenReturn("CO");

    NeoResponse response = importReceipt(probe, "{\"receiptId\":\"rc1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importTargetNotDraft", errorCode(response));
    assertTrue(probe.calls.isEmpty());
  }

  @Test
  public void importReceiptLines_aTargetWithoutPriceList_isRefused() throws Exception {
    Probe probe = purchase();
    when(probe.invoice.getPriceList()).thenReturn(null);

    assertEquals("importPriceListRequired",
        errorCode(importReceipt(probe, "{\"receiptId\":\"rc1\"}")));
  }

  @Test
  public void importReceiptLines_aSourceThatIsNotCompleted_isRefused() throws Exception {
    Probe probe = purchase();
    when(probe.source.getDocumentStatus()).thenReturn("DR");

    assertEquals("importSourceNotCompleted",
        errorCode(importReceipt(probe, "{\"receiptId\":\"rc1\"}")));
    assertNull(probe.nativeSelection);
  }

  @Test
  public void importReceiptLines_anotherPartnerOrCurrency_isRefused() throws Exception {
    Probe probe = purchase();
    doReturn(partner("other")).when(probe.source).getBusinessPartner();
    assertEquals("importBusinessPartnerMismatch",
        errorCode(importReceipt(probe, "{\"receiptId\":\"rc1\"}")));

    probe = purchase();
    doReturn(currency("usd")).when(probe.source).getEtgoCurrency();
    assertEquals("importCurrencyMismatch",
        errorCode(importReceipt(probe, "{\"receiptId\":\"rc1\"}")));
    assertNull(probe.nativeSelection);
  }

  /** A shipment into a purchase invoice is a different side: refused, whichever way it is asked. */
  @Test
  public void importReceiptLines_aSalesDocumentIntoAPurchaseInvoice_isRefused() throws Exception {
    Probe probe = purchase();
    when(probe.source.isSalesTransaction()).thenReturn(true);

    assertEquals("importDirectionMismatch",
        errorCode(importReceipt(probe, "{\"receiptId\":\"rc1\"}")));
  }

  @Test
  public void importShipmentLines_onAPurchaseInvoice_isRefused() throws Exception {
    Probe probe = purchase();

    NeoResponse response = importShipment(probe, "{\"shipmentId\":\"rc1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importDirectionMismatch", errorCode(response));
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("importReceiptLines"));
  }

  @Test
  public void importReceiptLines_aReturnDocument_isRefused() throws Exception {
    Probe probe = purchase();
    when(probe.source.getDocumentType().isReturn()).thenReturn(true);

    NeoResponse response = importReceipt(probe, "{\"receiptId\":\"rc1\"}");

    assertEquals(422, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message").contains("return"));
    assertNull(probe.nativeSelection);
  }

  @Test
  public void importReceiptLines_missingTargetSourceOrId() throws Exception {
    Probe probe = purchase();
    probe.invoice = null;
    assertEquals(404, importReceipt(probe, "{\"receiptId\":\"rc1\"}").getHttpStatus());

    probe = purchase();
    assertEquals(404, importReceipt(probe, "{\"receiptId\":\"zz\"}").getHttpStatus());
    assertEquals(400, importReceipt(probe, "{}").getHttpStatus());
  }

  // ── what the native process produced ──────────────────────────────────────

  @Test
  public void importReceiptLines_whenTheNativeLinesDoNotAddUp_rollsBackAndRefuses()
      throws Exception {
    Probe probe = purchase();
    probe.createdInstead = bd("1");

    NeoResponse response = importReceipt(probe, "{\"receiptId\":\"rc1\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importQuantityNotHonoured", errorCode(response));
    assertTrue(probe.rolledBack);
  }

  @Test
  public void importReceiptLines_reportsTheOrderReferenceOfTheInvoice() throws Exception {
    Probe probe = purchase();
    org.openbravo.model.common.order.Order order = mock(org.openbravo.model.common.order.Order.class);
    when(order.getId()).thenReturn("o1");
    org.openbravo.model.common.order.OrderLine orderLine =
        mock(org.openbravo.model.common.order.OrderLine.class);
    when(orderLine.getSalesOrder()).thenReturn(order);
    when(orderLine.getId()).thenReturn("ol1");
    doReturn(orderLine).when(probe.lineById.get("rl1")).getSalesOrderLine();
    when(probe.invoice.getSalesOrder()).thenReturn(order);

    JSONObject data = data(importReceipt(probe, "{\"receiptId\":\"rc1\"}"));

    assertTrue(data.getBoolean("headerLinked"));
    assertEquals("o1", data.getString("salesOrder"));

    probe = purchase();
    JSONObject unlinked = data(importReceipt(probe, "{\"receiptId\":\"rc1\"}"));
    assertFalse(unlinked.getBoolean("headerLinked"));
  }

  // ── discovery ─────────────────────────────────────────────────────────────

  @Test
  public void availableReceipts_listsOnlyDocumentsWithSomethingPending() throws Exception {
    Probe probe = purchase();
    probe.candidates = List.of(probe.source);

    JSONArray rows = probe.handle(action("availableReceipts", "i1", null)).getBody()
        .getJSONObject("response").getJSONArray("data");

    assertEquals(List.of("rc1"), ids(rows));
    assertEquals(2, rows.getJSONObject(0).getInt("pendingLines"));

    probe.pending.clear();
    rows = probe.handle(action("availableReceipts", "i1", null)).getBody()
        .getJSONObject("response").getJSONArray("data");
    assertEquals(0, rows.length());
  }

  @Test
  public void availableReceiptLines_reportsThePendingNotTheMovement() throws Exception {
    Probe probe = purchase();
    probe.pending.put("rl1", bd("4"));
    probe.pending.remove("rl2");

    JSONArray rows = probe.handle(action("availableReceiptLines", "i1",
        "{\"receiptId\":\"rc1\"}")).getBody().getJSONObject("response").getJSONArray("data");

    assertEquals(List.of("rl1"), ids(rows));
    assertEquals(4, rows.getJSONObject(0).getInt("pendingQuantity"));
    assertNull("reading changes nothing", probe.nativeSelection);
  }

  @Test
  public void availableShipments_isTheSalesMirror_andRejectsAPurchaseInvoice() throws Exception {
    Probe probe = sales();
    probe.candidates = List.of(probe.source);
    JSONArray rows = probe.handle(action("availableShipments", "i1", null)).getBody()
        .getJSONObject("response").getJSONArray("data");
    assertEquals(List.of("rc1"), ids(rows));

    Probe wrongSide = purchase();
    assertEquals(422, wrongSide.handle(action("availableShipments", "i1", null))
        .getHttpStatus());
  }

  @Test
  public void availableReceiptLines_aDraftTargetIsNotRequiredToRead() throws Exception {
    Probe probe = purchase();
    when(probe.invoice.getDocumentStatus()).thenReturn("CO");

    assertEquals(200, probe.handle(action("availableReceiptLines", "i1",
        "{\"receiptId\":\"rc1\"}")).getHttpStatus());
  }

  // ── contracts ─────────────────────────────────────────────────────────────

  @Test
  public void contracts_purchaseSpeaksOfReceipts_salesOfShipments() throws Exception {
    Map<String, NeoActionContract> purchase = ImportInOutLinesIntoInvoiceHandler.contracts(false);
    assertEquals(List.of("availableReceipts", "availableReceiptLines", "importReceiptLines"),
        List.copyOf(purchase.keySet()));
    assertNull(NeoActionContract.validate(purchase, "importReceiptLines",
        new JSONObject().put("receiptId", "rc1")));
    assertEquals(422, NeoActionContract.validate(purchase, "importReceiptLines",
        new JSONObject().put("shipmentId", "rc1")).getHttpStatus());

    Map<String, NeoActionContract> sales = ImportInOutLinesIntoInvoiceHandler.contracts(true);
    assertEquals(List.of("availableShipments", "availableShipmentLines", "importShipmentLines"),
        List.copyOf(sales.keySet()));
    assertNull(NeoActionContract.validate(sales, "importShipmentLines",
        new JSONObject().put("shipmentId", "rc1").put("lines", new JSONArray().put(
            new JSONObject().put("sourceLineId", "a").put("quantity", 1)))));
    assertEquals(422, NeoActionContract.validate(sales, "availableShipmentLines",
        new JSONObject()).getHttpStatus());
  }

  @Test
  public void contracts_nameTheOneStepAlternativeAndSayTheInvoiceIsNotCompleted() {
    String purchase = ImportInOutLinesIntoInvoiceHandler.contracts(false).get("importReceiptLines")
        .getDescription();
    assertTrue(purchase, purchase.contains("createPurchaseInvoice")
        && purchase.contains("WITHOUT completing") && purchase.contains("NOTHING"));
    String sales = ImportInOutLinesIntoInvoiceHandler.contracts(true).get("importShipmentLines")
        .getDescription();
    assertTrue(sales, sales.contains("createDraftInvoice"));
  }

  /**
   * Order to invoice then receipt to invoice (and the reverse): the receipt cap counts, by
   * {@code m_inoutline_id}, every non-voided non-closed invoice line — drafts included — so an
   * invoice line the order import bound to the movement line is already taken here, and one
   * created by this import (which the native process also links to its order line) is taken by the
   * order cap. This pins the receipt side: drafts are counted and the statement is the one used.
   */
  @Test
  public void pendingQuantities_countsDraftInvoiceLinesBoundToTheMovementLine() throws Exception {
    java.sql.Connection conn = mock(java.sql.Connection.class);
    java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
    when(dal.getConnection()).thenReturn(conn);
    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    when(conn.prepareStatement(sql.capture())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn("rl1");
    when(rs.getBigDecimal(2)).thenReturn(bd("10"));
    when(rs.getBigDecimal(3)).thenReturn(bd("4"));

    Map<String, BigDecimal> pending = new ImportInOutLinesIntoInvoiceHandler()
        .pendingQuantities("rc1");

    assertEquals(0, bd("6").compareTo(pending.get("rl1")));
    String statement = sql.getValue();
    assertTrue(statement, statement.contains("il2.m_inoutline_id IS NOT NULL")
        && statement.contains("WHERE i2.docstatus NOT IN ('VO','CL') AND i2.isactive = 'Y'"));
    assertTrue("drafts of the same order line are counted too",
        statement.contains("i3.docstatus = 'DR'"));
    assertFalse("REVERSE ORDER: an order import leaves its undelivered remainder as a draft line "
        + "bound to the order line only; those drafts count against the receipt line all the same, "
        + "whether or not the draft line names a movement line",
        statement.contains("il3.m_inoutline_id"));
  }

  // ── review fixes ──────────────────────────────────────────────────────────

  /** N2: a header whose order none of the imported lines belongs to is not reported as linked. */
  @Test
  public void importReceiptLines_aHeaderLinkedToAnotherOrder_isReportedAsItIs() throws Exception {
    Probe probe = purchase();
    org.openbravo.model.common.order.Order other =
        mock(org.openbravo.model.common.order.Order.class);
    when(other.getId()).thenReturn("other");
    when(probe.invoice.getSalesOrder()).thenReturn(other);

    JSONObject data = data(importReceipt(probe, "{\"receiptId\":\"rc1\"}"));

    assertFalse(data.getBoolean("headerLinked"));
    assertEquals("other", data.getString("salesOrder"));
  }

  /** Cross-tenant: a document of another client does not exist for the caller. */
  @Test
  public void aTargetOrSourceOfAnotherClient_isNotFound() throws Exception {
    Probe probe = purchase();
    doReturn(LineImportTestSupport.client("other")).when(probe.invoice).getClient();
    assertEquals(404, importReceipt(probe, "{\"receiptId\":\"rc1\"}").getHttpStatus());
    assertEquals(404, probe.handle(action("availableReceipts", "i1", null)).getHttpStatus());

    probe = purchase();
    doReturn(LineImportTestSupport.client("other")).when(probe.source).getClient();
    assertEquals(404, importReceipt(probe, "{\"receiptId\":\"rc1\"}").getHttpStatus());
    assertEquals(404, probe.handle(action("availableReceiptLines", "i1",
        "{\"receiptId\":\"rc1\"}")).getHttpStatus());
    assertNull(probe.nativeSelection);
  }

  /** W6: discovery says when it left something out. */
  @Test
  public void availableReceipts_reportsHasMoreAndTheLimitsUsed() throws Exception {
    Probe probe = purchase();
    probe.rowLimit = 1;
    probe.scanLimit = 5;
    ShipmentInOutLine extra = inOutLine("rx", 10, product("px", "Extra", false), "3");
    ShipmentInOut second = completedInOut("rc2", false, extra);
    probe.candidates = List.of(probe.source, second);

    JSONObject truncated = probe.handle(action("availableReceipts", "i1", null)).getBody()
        .getJSONObject("response");
    assertEquals(1, truncated.getJSONArray("data").length());
    assertTrue(truncated.getBoolean("hasMore"));
    assertEquals(1, truncated.getInt("limit"));
    assertEquals(5, truncated.getInt("scanLimit"));

    probe.rowLimit = 50;
    assertFalse(probe.handle(action("availableReceipts", "i1", null)).getBody()
        .getJSONObject("response").getBoolean("hasMore"));
    probe.scanLimit = 2;
    assertTrue(probe.handle(action("availableReceipts", "i1", null)).getBody()
        .getJSONObject("response").getBoolean("hasMore"));
    assertFalse(probe.handle(action("availableReceiptLines", "i1", "{\"receiptId\":\"rc1\"}"))
        .getBody().getJSONObject("response").getBoolean("hasMore"));
  }

  // ── returns into a rectificative (ETP-5876) ───────────────────────────────

  private static NeoResponse importReturn(Probe probe, String body) throws Exception {
    return probe.run("importReturnLines", body);
  }

  private static final String RETURN_ID = "{\"returnId\":\"rc1\"}";

  /** A completed return to a vendor: Agua -10 and Pan -5, stored negative as the module does. */
  private Probe returnToVendor() {
    Probe probe = purchase();
    for (ShipmentInOutLine line : probe.source.getMaterialMgmtShipmentInOutLineList()) {
      BigDecimal flipped = line.getMovementQuantity().negate();
      when(line.getMovementQuantity()).thenReturn(flipped);
    }
    when(probe.source.getDocumentType().isReturn()).thenReturn(true);
    return probe;
  }

  /** The sales mirror: a completed customer return into a draft sales rectificative. */
  private Probe customerReturn() {
    Probe probe = returnToVendor();
    when(probe.invoice.isSalesTransaction()).thenReturn(true);
    when(probe.source.isSalesTransaction()).thenReturn(true);
    probe.salesModalRules = true;
    return probe;
  }

  private static BigDecimal selected(Probe probe, int index) throws Exception {
    return new BigDecimal(probe.nativeSelection.getJSONObject(index).getString("orderedQuantity"));
  }

  @Test
  public void importReturnLines_withoutLines_creditsEverythingOfferedInNegative()
      throws Exception {
    Probe probe = returnToVendor();

    NeoResponse response = importReturn(probe, RETURN_ID);

    assertEquals(200, response.getHttpStatus());
    assertSame(ShipmentInOutLine.class, probe.nativeFrom);
    assertEquals(2, probe.nativeSelection.length());
    assertEquals(0, bd("-10").compareTo(selected(probe, 0)));
    assertEquals(0, bd("-5").compareTo(selected(probe, 1)));
    JSONObject data = data(response);
    assertEquals(2, data.getInt("importedCount"));
    assertEquals("rl1", data.getJSONArray("lines").getJSONObject(0).getString("sourceLineId"));
    assertEquals(0, bd("-10").compareTo(
        new BigDecimal(data.getJSONArray("lines").getJSONObject(0).get("quantity").toString())));
  }

  /**
   * The native process stores the price list's standard price and limit; the return modal's body
   * carries neither, so the screen stores 0 in both. Only those two are cleared, only on the new
   * lines, and the price, list price and amounts are left as the process set them.
   */
  @Test
  public void importReturnLines_storesZeroStandardPriceAndLimit_andLeavesTheAmounts()
      throws Exception {
    for (Probe probe : List.of(returnToVendor(), customerReturn())) {
      InvoiceLine existing = invoiceLine("old", product("p9", "Viejo", false), "1");
      probe.invoice.getInvoiceLineList().add(existing);

      assertEquals(200, importReturn(probe, RETURN_ID).getHttpStatus());

      for (InvoiceLine line : probe.invoice.getInvoiceLineList()) {
        if (line == existing) {
          verify(line, never()).setStandardPrice(any());
          continue;
        }
        verify(line).setStandardPrice(BigDecimal.ZERO);
        verify(line).setPriceLimit(BigDecimal.ZERO);
        verify(line, never()).setUnitPrice(any());
        verify(line, never()).setListPrice(any());
        verify(line, never()).setLineNetAmount(any());
        verify(line, never()).setGrossAmount(any());
      }
    }
  }

  /** The order, receipt and shipment imports keep the prices the native process copies. */
  @Test
  public void importReceiptLines_keepsTheStandardPriceOfTheNativeProcess() throws Exception {
    Probe probe = purchase();

    importReceipt(probe, "{\"receiptId\":\"rc1\"}");

    for (InvoiceLine line : probe.invoice.getInvoiceLineList()) {
      verify(line, never()).setStandardPrice(any());
    }
  }

  /** The caller speaks in positive quantities, as the return's own lines do; either sign works. */
  @Test
  public void importReturnLines_theQuantityIsAPositiveNumber_andBecomesACredit()
      throws Exception {
    Probe probe = returnToVendor();
    assertEquals(200, importReturn(probe, "{\"returnId\":\"rc1\",\"lines\":"
        + "[{\"sourceLineId\":\"rl2\",\"quantity\":2}]}").getHttpStatus());
    assertEquals(1, probe.nativeSelection.length());
    assertEquals(0, bd("-2").compareTo(selected(probe, 0)));

    Probe negative = returnToVendor();
    assertEquals(200, importReturn(negative, "{\"returnId\":\"rc1\",\"lines\":"
        + "[{\"sourceLineId\":\"rl2\",\"quantity\":-2}]}").getHttpStatus());
    assertEquals(0, bd("-2").compareTo(selected(negative, 0)));
  }

  /** A return stored positive before ETP-5313 is still credited negative. */
  @Test
  public void importReturnLines_aReturnStoredPositive_isStillCreditedNegative() throws Exception {
    Probe probe = purchase();
    when(probe.source.getDocumentType().isReturn()).thenReturn(true);

    assertEquals(200, importReturn(probe, RETURN_ID).getHttpStatus());

    assertEquals(0, bd("-10").compareTo(selected(probe, 0)));
  }

  // -- purchase: ImportFromGoodsReturnModal ----------------------------------

  /**
   * The purchase modal caps a line at the return line's quantity and blocks it only when THIS
   * invoice already holds a line for it: what other invoices hold, drafts or completed, is not
   * counted, and the database is not even asked.
   */
  @Test
  public void purchase_theCapIsTheReturnLineQuantity_otherInvoicesAreNotCounted()
      throws Exception {
    Probe probe = returnToVendor();
    probe.credited.put("rl1", bd("9"));
    probe.pending.put("rl1", bd("1"));

    assertEquals(200, importReturn(probe, "{\"returnId\":\"rc1\",\"lines\":"
        + "[{\"sourceLineId\":\"rl1\",\"quantity\":10}]}").getHttpStatus());
    assertEquals(0, bd("-10").compareTo(selected(probe, 0)));
    assertFalse("the sum of other invoices is never read", probe.calls.contains("credited"));

    Probe over = returnToVendor();
    NeoResponse refused = importReturn(over, "{\"returnId\":\"rc1\",\"lines\":"
        + "[{\"sourceLineId\":\"rl1\",\"quantity\":11}]}");
    assertEquals("importQuantityExceedsPending", errorCode(refused));
    JSONObject error = refused.getBody().getJSONObject("error");
    assertEquals(11, error.getInt("requested"));
    assertEquals(10, error.getInt("pending"));
    assertNull(over.nativeSelection);
  }

  @Test
  public void purchase_aLineThisInvoiceAlreadyHolds_isNotOfferedAgain_whateverItsQuantity()
      throws Exception {
    Probe probe = returnToVendor();
    ShipmentInOutLine agua = probe.lineById.get("rl1");
    InvoiceLine mine = invoiceLine("mine", agua.getProduct(), "-3");
    when(mine.isActive()).thenReturn(true);
    when(mine.getGoodsShipmentLine()).thenReturn(agua);
    probe.invoice.getInvoiceLineList().add(mine);

    NeoResponse refused = importReturn(probe, "{\"returnId\":\"rc1\",\"lines\":"
        + "[{\"sourceLineId\":\"rl1\",\"quantity\":1}]}");

    assertEquals("importQuantityExceedsPending", errorCode(refused));
    assertEquals(0, refused.getBody().getJSONObject("error").getInt("pending"));
    JSONArray rows = probe.run("availableReturnLines", RETURN_ID).getBody()
        .getJSONObject("response").getJSONArray("data");
    assertEquals(List.of("rl2"), ids(rows));
  }

  @Test
  public void purchase_comparesNoCurrency() throws Exception {
    Probe probe = returnToVendor();
    doReturn(currency("usd")).when(probe.source).getEtgoCurrency();
    Order order = mock(Order.class);
    doReturn(currency("usd")).when(order).getCurrency();
    when(probe.source.getSalesOrder()).thenReturn(order);

    assertEquals(200, importReturn(probe, RETURN_ID).getHttpStatus());
  }

  // -- sales: ImportFromReturnShipmentModal ----------------------------------

  /**
   * The sales modal nets EVERY invoice line linked to the return line, whatever the status of its
   * invoice, from the return line's quantity.
   */
  @Test
  public void sales_theCapNetsEveryInvoiceLineOfAnyStatus() throws Exception {
    Probe probe = customerReturn();
    probe.credited.put("rl1", bd("6"));
    probe.credited.put("rl2", bd("5"));

    JSONArray rows = probe.run("availableReturnLines", RETURN_ID).getBody()
        .getJSONObject("response").getJSONArray("data");

    assertEquals(List.of("rl1"), ids(rows));
    assertEquals(4, rows.getJSONObject(0).getInt("pendingQuantity"));
    assertEquals(200, importReturn(probe, "{\"returnId\":\"rc1\",\"lines\":"
        + "[{\"sourceLineId\":\"rl1\",\"quantity\":4}]}").getHttpStatus());
    assertEquals(0, bd("-4").compareTo(selected(probe, 0)));

    Probe over = customerReturn();
    over.credited.put("rl1", bd("6"));
    NeoResponse refused = importReturn(over, "{\"returnId\":\"rc1\",\"lines\":"
        + "[{\"sourceLineId\":\"rl1\",\"quantity\":5}]}");
    assertEquals("importQuantityExceedsPending", errorCode(refused));
    assertEquals(4, refused.getBody().getJSONObject("error").getInt("pending"));
    assertNull(over.nativeSelection);
  }

  /** A line whose order line is already taken is not offered at all, as in the modal. */
  @Test
  public void sales_aLineWhoseOrderLineIsAlreadyInvoiced_isNotOffered() throws Exception {
    Probe probe = customerReturn();
    OrderLine orderLine = mock(OrderLine.class);
    when(orderLine.getId()).thenReturn("ol9");
    doReturn(orderLine).when(probe.lineById.get("rl1")).getSalesOrderLine();
    probe.blockedOrderLines.add("ol9");

    JSONArray rows = probe.run("availableReturnLines", RETURN_ID).getBody()
        .getJSONObject("response").getJSONArray("data");

    assertEquals(List.of("rl2"), ids(rows));
  }

  /** The sales modal compares the currency of the return's sales order, when it has one. */
  @Test
  public void sales_comparesTheCurrencyOfTheReturnsSalesOrder() throws Exception {
    Probe noOrder = customerReturn();
    doReturn(currency("usd")).when(noOrder.source).getEtgoCurrency();
    assertEquals("a return without a sales order is never excluded", 200,
        importReturn(noOrder, RETURN_ID).getHttpStatus());

    Probe probe = customerReturn();
    Order order = mock(Order.class);
    doReturn(currency("usd")).when(order).getCurrency();
    when(probe.source.getSalesOrder()).thenReturn(order);
    probe.returnCandidates = List.of(probe.source);

    assertEquals("importCurrencyMismatch", errorCode(importReturn(probe, RETURN_ID)));
    assertEquals(0, probe.run("availableReturns", null).getBody().getJSONObject("response")
        .getJSONArray("data").length());
  }

  // -- both sides --------------------------------------------------------------

  /** The window lists only returns whose invoiceStatus is below 100. */
  @Test
  public void aReturnInvoicedInFull_isNotListedNorImported() throws Exception {
    for (Probe probe : List.of(returnToVendor(), customerReturn())) {
      probe.invoiceStatus = bd("100");
      probe.returnCandidates = List.of(probe.source);

      assertEquals(0, probe.run("availableReturns", null).getBody().getJSONObject("response")
          .getJSONArray("data").length());
      assertEquals("importNothingPending", errorCode(importReturn(probe, RETURN_ID)));
      assertNull(probe.nativeSelection);
    }
    Probe partly = returnToVendor();
    partly.invoiceStatus = bd("40");
    assertEquals(200, importReturn(partly, RETURN_ID).getHttpStatus());
  }

  @Test
  public void importReturnLines_aFullyCoveredReturn_isRefusedAsNothingPending() throws Exception {
    Probe probe = customerReturn();
    probe.credited.put("rl1", bd("10"));
    probe.credited.put("rl2", bd("5"));

    assertEquals("importNothingPending", errorCode(importReturn(probe, RETURN_ID)));
  }

  @Test
  public void importReturnLines_aDocumentThatIsNotAReturn_isRefused() throws Exception {
    Probe probe = purchase();

    NeoResponse response = importReturn(probe, RETURN_ID);

    assertEquals(422, response.getHttpStatus());
    assertEquals("importSourceNotReturn", errorCode(response));
    assertNull(probe.nativeSelection);
  }

  /** A normal invoice action still refuses a return, and now says where returns go. */
  @Test
  public void importReceiptLines_aReturn_pointsToTheRectificativeActions() throws Exception {
    Probe probe = returnToVendor();

    NeoResponse response = importReceipt(probe, "{\"receiptId\":\"rc1\"}");

    assertEquals(422, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("importReturnLines"));
  }

  @Test
  public void importReturnLines_theDirectionIsTheInvoices_andItsPreconditionsApply()
      throws Exception {
    Probe probe = customerReturn();
    assertEquals(200, importReturn(probe, RETURN_ID).getHttpStatus());

    Probe crossed = returnToVendor();
    when(crossed.source.isSalesTransaction()).thenReturn(true);
    assertEquals("importDirectionMismatch", errorCode(importReturn(crossed, RETURN_ID)));

    Probe otherPartner = returnToVendor();
    doReturn(partner("other")).when(otherPartner.source).getBusinessPartner();
    assertEquals("importBusinessPartnerMismatch",
        errorCode(importReturn(otherPartner, RETURN_ID)));

    Probe notCompleted = returnToVendor();
    when(notCompleted.source.getDocumentStatus()).thenReturn("DR");
    assertEquals("importSourceNotCompleted", errorCode(importReturn(notCompleted, RETURN_ID)));

    Probe notDraft = returnToVendor();
    when(notDraft.invoice.getDocumentStatus()).thenReturn("CO");
    assertEquals("importTargetNotDraft", errorCode(importReturn(notDraft, RETURN_ID)));
    assertNull(notDraft.nativeSelection);
  }

  @Test
  public void availableReturns_listsOnlyReturnsWithSomethingToCredit() throws Exception {
    Probe probe = returnToVendor();
    ShipmentInOut plain = completedInOut("rc9", false,
        inOutLine("rl9", 10, product("p9", "Otro", false), "3"));
    probe.returnCandidates = List.of(probe.source, plain);

    JSONArray rows = probe.run("availableReturns", null).getBody()
        .getJSONObject("response").getJSONArray("data");

    assertEquals("a document that is not a return is never offered", List.of("rc1"), ids(rows));
    assertEquals(2, rows.getJSONObject(0).getInt("pendingLines"));

    Probe drained = customerReturn();
    drained.credited.put("rl1", bd("10"));
    drained.credited.put("rl2", bd("5"));
    drained.returnCandidates = List.of(drained.source);
    assertEquals(0, drained.run("availableReturns", null).getBody().getJSONObject("response")
        .getJSONArray("data").length());
  }

  @Test
  public void availableReturns_aStandardActionNeverOffersReturns() throws Exception {
    Probe probe = returnToVendor();
    probe.candidates = List.of(probe.source);

    assertEquals(0, probe.handle(action("availableReceipts", "i1", null)).getBody()
        .getJSONObject("response").getJSONArray("data").length());
  }

  @Test
  public void returnContracts_speakOfReturns_requireTheReturnId_andNameEachSidesCap()
      throws Exception {
    for (boolean sales : new boolean[] {true, false}) {
      Map<String, NeoActionContract> contracts =
          ImportInOutLinesIntoInvoiceHandler.returnContracts(sales);
      assertEquals(List.of("availableReturns", "availableReturnLines", "importReturnLines"),
          List.copyOf(contracts.keySet()));
      assertNull(NeoActionContract.validate(contracts, "importReturnLines",
          new JSONObject().put("returnId", "r1")));
      assertEquals(422, NeoActionContract.validate(contracts, "importReturnLines",
          new JSONObject().put("receiptId", "r1")).getHttpStatus());
    }
    String text = ImportInOutLinesIntoInvoiceHandler.returnContracts(true)
        .get("importReturnLines").getDescription();
    assertTrue(text, text.contains("NEGATIVE") && text.contains("CURRENT price list")
        && text.contains("createReturnInvoice") && text.contains("does not link")
        && text.contains("voided ones too"));
    String purchase = ImportInOutLinesIntoInvoiceHandler.returnContracts(false)
        .get("availableReturnLines").getDescription();
    assertTrue(purchase, purchase.contains("NOT deducted")
        && !purchase.contains("voided ones too"));
    assertTrue(ImportInOutLinesIntoInvoiceHandler.contracts(true).get("importShipmentLines")
        .getDescription().contains("importNotAvailableOnRectificative"));
  }

  @Test
  public void candidateReturnsHql_isScopedAndHasNoCurrencyFilter() {
    String hql = ImportInOutLinesIntoInvoiceHandler.candidateReturnsHql();
    assertTrue(hql, hql.contains("io.documentType.return = true")
        && hql.contains("io.client.id = :client") && hql.contains("io.organization.id in (:orgs)")
        && hql.contains("io.active = true"));
    assertFalse(hql.contains("urrency"));
  }
}
