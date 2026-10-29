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
import static com.etendoerp.go.schemaforge.LineImportTestSupport.client;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.currency;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.data;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.draftInvoice;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.errorCode;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.ids;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.partner;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.product;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.tax.TaxRate;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link ImportOriginInvoiceLinesHandler} (ETP-5876): the import of lines of a
 * completed invoice into a draft rectificative, which stands for the window's "Import from source
 * invoice" modal. The DAL and the candidate query are seams, replaced here by a recording double.
 *
 * @covers com.etendoerp.go.schemaforge.ImportOriginInvoiceLinesHandler
 * @covers com.etendoerp.go.schemaforge.NeoQuotaCreateCheck
 */
public class ImportOriginInvoiceLinesHandlerTest {

  /** Vetoed: Weld scans the test classes, and a bean subclass would make the injection ambiguous. */
  @javax.enterprise.inject.Vetoed
  private static final class Probe extends ImportOriginInvoiceLinesHandler {
    Invoice target;
    final Map<String, Invoice> invoices = new java.util.LinkedHashMap<>();
    List<Invoice> candidates = new ArrayList<>();
    final List<InvoiceLine> created = new ArrayList<>();
    final List<String> links = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    boolean rolledBack;
    int scanLimit = 200;
    int rowLimit = 50;
    /** Which invoices are rectificatives for the header that would serve the action. */
    final Set<String> rectificatives = new java.util.HashSet<>();
    boolean keepsOrderLink;

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
    protected Invoice loadInvoice(String id) {
      return invoices.get(id);
    }

    @Override
    protected List<Invoice> findCandidateInvoices(String partnerId, boolean sales,
        String currencyId, String targetId) {
      return candidates;
    }

    @Override
    protected InvoiceLine newLine() {
      return recordingLine("n" + (created.size() + 1));
    }

    @Override
    protected void save(Object entity) {
      if (entity instanceof InvoiceLine) {
        created.add((InvoiceLine) entity);
        calls.add("save");
      }
    }

    @Override
    protected void flush() {
      calls.add("flush");
    }

    @Override
    protected void lockTarget(String invoiceId) {
      calls.add("lock:" + invoiceId);
    }

    @Override
    protected boolean linkIfMissing(Invoice invoice, Invoice origin) {
      links.add(invoice.getId() + "->" + origin.getId());
      calls.add("link");
      return true;
    }

    NeoResponse run(String action, String body) throws Exception {
      NeoContext context = action(action, "i1", body);
      context.setAttribute(RectificativeImportPolicy.ATTRIBUTE, new RectificativeImportPolicy(
          invoice -> rectificatives.contains(invoice.getId()), keepsOrderLink));
      return handle(context);
    }
  }

  /** A line that remembers what the handler wrote on it (a mock does not). */
  private static InvoiceLine recordingLine(String id) {
    InvoiceLine line = mock(InvoiceLine.class);
    AtomicReference<BigDecimal> quantity = new AtomicReference<>();
    AtomicReference<OrderLine> orderLine = new AtomicReference<>();
    when(line.getId()).thenReturn(id);
    when(line.isActive()).thenReturn(true);
    when(line.getInvoicedQuantity()).thenAnswer(call -> quantity.get());
    when(line.getSalesOrderLine()).thenAnswer(call -> orderLine.get());
    doAnswer(call -> {
      quantity.set(call.getArgument(0));
      return null;
    }).when(line).setInvoicedQuantity(any());
    doAnswer(call -> {
      orderLine.set(call.getArgument(0));
      return null;
    }).when(line).setSalesOrderLine(any());
    return line;
  }

  private static InvoiceLine sourceLine(String id, long lineNo, Product product, String quantity,
      String unitPrice) {
    InvoiceLine line = mock(InvoiceLine.class);
    when(line.getId()).thenReturn(id);
    when(line.isActive()).thenReturn(true);
    when(line.getLineNo()).thenReturn(lineNo);
    when(line.getProduct()).thenReturn(product);
    when(line.getInvoicedQuantity()).thenReturn(bd(quantity));
    when(line.getUnitPrice()).thenReturn(bd(unitPrice));
    when(line.getListPrice()).thenReturn(bd(unitPrice));
    UOM uom = mock(UOM.class);
    when(uom.getId()).thenReturn("uom1");
    when(line.getUOM()).thenReturn(uom);
    when(line.getTax()).thenReturn(mock(TaxRate.class));
    return line;
  }

  /** A completed invoice of the partner and currency of {@link #target}. */
  private static Invoice completed(String id, boolean sales, InvoiceLine... lines) {
    Invoice invoice = draftInvoice(id, sales);
    when(invoice.getDocumentStatus()).thenReturn("CO");
    when(invoice.getInvoiceDate()).thenReturn(new Date(0L));
    invoice.getInvoiceLineList().addAll(List.of(lines));
    return invoice;
  }

  /** A draft rectificative purchase invoice and a completed standard one: Agua 10 and Pan 4. */
  private Probe purchase() {
    Probe probe = new Probe();
    probe.target = draftInvoice("i1", false);
    probe.invoices.put("i1", probe.target);
    Invoice source = completed("src", false,
        sourceLine("sl1", 10, product("p1", "Agua", true), "10", "2.50"),
        sourceLine("sl2", 20, product("p2", "Pan", false), "4", "1.25"));
    probe.invoices.put("src", source);
    probe.invoiceFromOrderSupport = mock(InvoiceFromOrderSupport.class);
    return probe;
  }

  private Probe sales() {
    Probe probe = new Probe();
    probe.target = draftInvoice("i1", true);
    probe.invoices.put("i1", probe.target);
    Invoice source = completed("src", true,
        sourceLine("sl1", 10, product("p1", "Agua", true), "10", "2.50"),
        sourceLine("sl2", 20, product("p2", "Pan", false), "4", "1.25"));
    probe.invoices.put("src", source);
    probe.invoiceFromOrderSupport = mock(InvoiceFromOrderSupport.class);
    probe.keepsOrderLink = true;
    return probe;
  }

  private static BigDecimal quantityOf(InvoiceLine line) {
    return line.getInvoicedQuantity();
  }

  private static boolean sameNumber(BigDecimal actual, String expected) {
    return actual != null && actual.compareTo(bd(expected)) == 0;
  }

  // ── routing ───────────────────────────────────────────────────────────────

  @Test
  public void handle_ignoresWhatIsNotItsOwn() throws Exception {
    Probe probe = purchase();
    assertNull(probe.run("importOrderLines", "{}"));
    assertNull(probe.run("importReturnLines", "{}"));
    assertTrue(probe.created.isEmpty());
  }

  // ── import ────────────────────────────────────────────────────────────────

  /** No lines: every offered line, in full, NEGATIVE, with what the source line carries. */
  @Test
  public void import_withoutLines_importsEveryLineInFullAsACredit() throws Exception {
    Probe probe = purchase();

    NeoResponse response = probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(2, probe.created.size());
    assertTrue(sameNumber(quantityOf(probe.created.get(0)), "-10"));
    assertTrue(sameNumber(quantityOf(probe.created.get(1)), "-4"));
    InvoiceLine first = probe.created.get(0);
    verify(first).setInvoice(probe.target);
    verify(first).setLineNo(10L);
    verify(probe.created.get(1)).setLineNo(20L);
    verify(first).setUnitPrice(argThat(price -> sameNumber(price, "2.50")));
    verify(first).setListPrice(argThat(price -> sameNumber(price, "2.50")));
    verify(first).setLineNetAmount(argThat(net -> sameNumber(net, "-25.00")));
    verify(first).setStandardPrice(BigDecimal.ZERO);
    verify(first).setPriceLimit(BigDecimal.ZERO);
    verify(first).setUOM(any(UOM.class));
    verify(first).setTax(any(TaxRate.class));
    verify(first).setETGOSourceInvoiceLine(any(InvoiceLine.class));
    JSONObject data = data(response);
    assertEquals(2, data.getInt("importedCount"));
    assertEquals("sl1", data.getJSONArray("lines").getJSONObject(0).getString("sourceLineId"));
    assertEquals("Agua", data.getJSONArray("lines").getJSONObject(0).getString("productName"));
    assertEquals("src", data.getJSONArray("originInvoices").getJSONObject(0).getString("id"));
  }

  /** Partial quantities are the point of the modal: 1 of 2, and what is left is not re-offered. */
  @Test
  public void import_aPartialQuantity_isHonouredExactly() throws Exception {
    Probe probe = purchase();

    NeoResponse response = probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\","
        + "\"lines\":[{\"sourceLineId\":\"sl1\",\"quantity\":3}]}");

    assertEquals(200, response.getHttpStatus());
    assertEquals(1, probe.created.size());
    assertTrue(sameNumber(quantityOf(probe.created.get(0)), "-3"));
  }

  @Test
  public void import_aNegativeQuantityIsTakenAsItsMagnitude() throws Exception {
    Probe probe = purchase();

    probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\","
        + "\"lines\":[{\"sourceLineId\":\"sl2\",\"quantity\":-2}]}");

    assertTrue(sameNumber(quantityOf(probe.created.get(0)), "-2"));
  }

  /** The cap is the line itself: nothing deducted for other drafts or returns, as in the window. */
  @Test
  public void import_moreThanTheSourceLine_is422AndCreatesNothing() throws Exception {
    Probe probe = purchase();

    NeoResponse response = probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\","
        + "\"lines\":[{\"sourceLineId\":\"sl2\",\"quantity\":1},"
        + "{\"sourceLineId\":\"sl1\",\"quantity\":11}]}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importQuantityExceedsPending", errorCode(response));
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("Agua", error.getString("product"));
    assertEquals(11, error.getInt("requested"));
    assertEquals(10, error.getInt("pending"));
    assertTrue(probe.created.isEmpty());
    assertTrue(probe.links.isEmpty());
  }

  /**
   * ETP-5051 — the lines this import added are checked against the plan quotas before the answer
   * ({@code AbstractLineImportHandler#requireQuotaAllowed}): at a {@code block} limit the
   * rectificative line import answers 403 {@code QUOTA_EXCEEDED} and the whole import is rolled
   * back.
   */
  @Test
  public void import_overABlockQuota_answers403AndRollsBackTheImport() throws Exception {
    Probe probe = purchase();
    OBDal dal = mock(OBDal.class);

    NeoResponse response;
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoQuotaCreateCheck> quota = QuotaRefusalTestSupport.everyCreateRefused()) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      response = probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}");

      quota.verify(() -> NeoQuotaCreateCheck.requireAllowed(
          org.mockito.ArgumentMatchers.eq(InvoiceLine.ENTITY_NAME),
          argThat(ids -> ids != null && ids.size() == 2)));
    }

    QuotaRefusalTestSupport.assertQuotaExceeded(response);
    verify(dal).rollbackAndClose();
    verify(dal, never()).commitAndClose();
  }

  /** The same line listed twice counts both. */
  @Test
  public void import_theSameLineTwice_countsBoth() throws Exception {
    Probe probe = purchase();

    NeoResponse response = probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\","
        + "\"lines\":[{\"sourceLineId\":\"sl2\",\"quantity\":3},"
        + "{\"sourceLineId\":\"sl2\",\"quantity\":2}]}");

    assertEquals(422, response.getHttpStatus());
  }

  /**
   * A line already imported into THIS rectificative is not offered again, even when only part of it
   * came in: the modal tracks presence, not quantity.
   */
  @Test
  public void import_aLineAlreadyImportedHere_isNotOfferedAgain() throws Exception {
    Probe probe = purchase();
    InvoiceLine imported = sourceLine("mine", 10, product("p1", "Agua", true), "-3", "2.50");
    InvoiceLine firstSourceLine = probe.invoices.get("src").getInvoiceLineList().get(0);
    when(imported.getETGOSourceInvoiceLine()).thenReturn(firstSourceLine);
    probe.target.getInvoiceLineList().add(imported);

    NeoResponse response = probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\","
        + "\"lines\":[{\"sourceLineId\":\"sl1\",\"quantity\":1}]}");

    assertEquals(422, response.getHttpStatus());
    assertEquals(0, response.getBody().getJSONObject("error").getInt("pending"));

    NeoResponse rest = probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}");
    assertEquals(200, rest.getHttpStatus());
    assertEquals(1, probe.created.size());
    assertTrue("only the line not imported yet comes in",
        sameNumber(quantityOf(probe.created.get(0)), "-4"));
  }

  @Test
  public void import_everythingAlreadyImported_isRefusedAsNothingPending() throws Exception {
    Probe probe = purchase();
    for (InvoiceLine line : probe.invoices.get("src").getInvoiceLineList()) {
      InvoiceLine mine = mock(InvoiceLine.class);
      when(mine.isActive()).thenReturn(true);
      when(mine.getETGOSourceInvoiceLine()).thenReturn(line);
      probe.target.getInvoiceLineList().add(mine);
    }

    assertEquals("importNothingPending",
        errorCode(probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}")));
  }

  /**
   * Two calls into the same draft must serialise: the target is locked BEFORE the lines it holds
   * are read, and nothing is locked when the request is refused earlier.
   */
  @Test
  public void import_locksTheTargetBeforeReadingItsLines() throws Exception {
    Probe probe = purchase();
    List<InvoiceLine> recording = new ArrayList<InvoiceLine>() {
      private static final long serialVersionUID = 1L;

      @Override
      public java.util.Iterator<InvoiceLine> iterator() {
        probe.calls.add("read");
        return super.iterator();
      }
    };
    doReturn(recording).when(probe.target).getInvoiceLineList();

    probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}");

    assertTrue(probe.calls.toString(), probe.calls.indexOf("lock:i1") >= 0);
    assertTrue("the lock comes before the first read of the target's lines",
        probe.calls.indexOf("lock:i1") < probe.calls.indexOf("read"));

    Probe refused = purchase();
    refused.run("importOriginInvoiceLines", "{\"invoiceId\":\"zz\"}");
    assertFalse(refused.calls.contains("lock:i1"));
  }

  /** The window's lines grid hides the total-discount line, so it is never offered. */
  @Test
  public void import_theTotalDiscountLine_isNeverOffered() throws Exception {
    Probe probe = purchase();
    Invoice source = probe.invoices.get("src");
    source.getInvoiceLineList().add(sourceLine("disc", 30,
        product(TotalDiscountService.DISCOUNT_PRODUCT_ID, "Descuento", false), "1", "-5"));

    probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}");

    assertEquals(2, probe.created.size());
  }

  /**
   * The origin invoice is linked once per import, on both sides, after the target is locked, and
   * the header's total discount is never carried. A refused request links nothing.
   */
  @Test
  public void import_linksTheOriginInvoiceOnce_afterTheLock_andNeverCarriesTheTotalDiscount()
      throws Exception {
    for (Probe probe : List.of(purchase(), sales())) {
      probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}");

      assertEquals(List.of("i1->src"), probe.links);
      assertTrue("the link follows the lock", probe.calls.indexOf("lock:i1")
          < probe.calls.indexOf("link"));
      verify(probe.target, never()).setEtgoTotalDiscount(any());
    }
  }

  /** What is posted nothing leaves behind: it is all flushed once, and the invoice never completes. */
  @Test
  public void import_postProcessesOnlyTheNewLines() throws Exception {
    Probe probe = purchase();

    probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}");

    verify(probe.invoiceFromOrderSupport).alignLineListPrices(any(Invoice.class), any());
    verify(probe.invoiceFromOrderSupport).ensureLineGrossAmounts(any(Invoice.class), any());
    verify(probe.invoiceFromOrderSupport, never()).copyLineDiscountsFromOrder(any(), any());
    assertEquals(2, probe.target.getInvoiceLineList().size());
  }

  /** Lines keep their discount and gross prices; the list price falls back to the unit price. */
  @Test
  public void import_copiesTheDiscountAndGrossPricesOfTheSourceLine() throws Exception {
    Probe probe = purchase();
    InvoiceLine line = probe.invoices.get("src").getInvoiceLineList().get(0);
    when(line.getEtgoDiscount()).thenReturn(bd("10"));
    when(line.getGrossUnitPrice()).thenReturn(bd("3.00"));
    when(line.getGrossListPrice()).thenReturn(bd("3.30"));
    when(line.getListPrice()).thenReturn(bd("0"));

    probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\","
        + "\"lines\":[{\"sourceLineId\":\"sl1\",\"quantity\":2}]}");

    InvoiceLine created = probe.created.get(0);
    verify(created).setEtgoDiscount(argThat(d -> sameNumber(d, "10")));
    verify(created).setGrossUnitPrice(argThat(g -> sameNumber(g, "3.00")));
    verify(created).setGrossListPrice(argThat(g -> sameNumber(g, "3.30")));
    verify(created).setListPrice(argThat(l -> sameNumber(l, "2.50")));
  }

  /**
   * The modals' body carries neither the standard price nor the price limit, so the screen stores 0
   * in both even when the source line holds a price-list value; price, list price and amounts are
   * the source line's, unchanged.
   */
  @Test
  public void import_storesZeroStandardPriceAndLimit_whateverTheSourceLineHolds() throws Exception {
    for (Probe probe : List.of(purchase(), sales())) {
      InvoiceLine line = probe.invoices.get("src").getInvoiceLineList().get(0);
      when(line.getStandardPrice()).thenReturn(bd("44"));
      when(line.getPriceLimit()).thenReturn(bd("40"));

      probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\","
          + "\"lines\":[{\"sourceLineId\":\"sl1\",\"quantity\":2}]}");

      InvoiceLine created = probe.created.get(0);
      verify(created).setStandardPrice(BigDecimal.ZERO);
      verify(created).setPriceLimit(BigDecimal.ZERO);
      verify(created, never()).setStandardPrice(bd("44"));
      verify(created).setUnitPrice(argThat(price -> sameNumber(price, "2.50")));
      verify(created).setListPrice(argThat(price -> sameNumber(price, "2.50")));
      verify(created).setLineNetAmount(argThat(net -> sameNumber(net, "-5.00")));
    }
  }

  /** The sales modal sends the order line along; the purchase one does not. */
  @Test
  public void import_salesKeepsTheOrderLineOfTheSourceLine_purchaseDoesNot() throws Exception {
    Probe salesProbe = sales();
    Order order = mock(Order.class);
    when(order.getId()).thenReturn("o1");
    OrderLine orderLine = mock(OrderLine.class);
    when(orderLine.getSalesOrder()).thenReturn(order);
    when(salesProbe.invoices.get("src").getInvoiceLineList().get(0).getSalesOrderLine())
        .thenReturn(orderLine);

    JSONObject data = data(salesProbe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\","
        + "\"lines\":[{\"sourceLineId\":\"sl1\",\"quantity\":2}]}"));

    verify(salesProbe.created.get(0)).setSalesOrderLine(orderLine);
    verify(salesProbe.target).setSalesOrder(order);
    assertEquals("o1", data.getString("salesOrder"));
    assertEquals("a sales source that has an order is linked all the same", List.of("i1->src"),
        salesProbe.links);

    Probe purchaseProbe = purchase();
    when(purchaseProbe.invoices.get("src").getInvoiceLineList().get(0).getSalesOrderLine())
        .thenReturn(orderLine);
    purchaseProbe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\","
        + "\"lines\":[{\"sourceLineId\":\"sl1\",\"quantity\":2}]}");
    verify(purchaseProbe.created.get(0), never()).setSalesOrderLine(any());
    verify(purchaseProbe.target, never()).setSalesOrder(any());
  }

  // ── the preconditions ─────────────────────────────────────────────────────

  @Test
  public void import_aRectificativeSource_isRefused() throws Exception {
    Probe probe = purchase();
    probe.rectificatives.add("src");

    NeoResponse response = probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}");

    assertEquals(422, response.getHttpStatus());
    assertEquals("importSourceInvoiceNotStandard", errorCode(response));
    assertTrue(probe.created.isEmpty());
    assertTrue(probe.links.isEmpty());
  }

  @Test
  public void import_theInvoiceItself_isRefused() throws Exception {
    Probe probe = purchase();

    assertEquals("importSourceIsTarget",
        errorCode(probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"i1\"}")));
  }

  @Test
  public void import_preconditionsOfTheSourceAndTheTarget() throws Exception {
    Probe notCompleted = purchase();
    when(notCompleted.invoices.get("src").getDocumentStatus()).thenReturn("DR");
    assertEquals("importSourceNotCompleted",
        errorCode(notCompleted.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}")));

    Probe otherPartner = purchase();
    doReturn(partner("other")).when(otherPartner.invoices.get("src")).getBusinessPartner();
    assertEquals("importBusinessPartnerMismatch",
        errorCode(otherPartner.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}")));

    Probe otherCurrency = sales();
    doReturn(currency("usd")).when(otherCurrency.invoices.get("src")).getCurrency();
    assertEquals("the sales modal filters by currency", "importCurrencyMismatch",
        errorCode(otherCurrency.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}")));

    Probe otherSide = purchase();
    when(otherSide.invoices.get("src").isSalesTransaction()).thenReturn(true);
    assertEquals("importDirectionMismatch",
        errorCode(otherSide.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}")));

    Probe notDraft = purchase();
    when(notDraft.target.getDocumentStatus()).thenReturn("CO");
    assertEquals("importTargetNotDraft",
        errorCode(notDraft.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}")));
    assertTrue(notDraft.created.isEmpty());
  }

  @Test
  public void import_missingTargetSourceOrId_andForeignClient() throws Exception {
    Probe probe = purchase();
    assertEquals(404, probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"zz\"}")
        .getHttpStatus());
    assertEquals(400, probe.run("importOriginInvoiceLines", "{}").getHttpStatus());

    Probe foreign = purchase();
    doReturn(client("other")).when(foreign.invoices.get("src")).getClient();
    assertEquals(404, foreign.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}")
        .getHttpStatus());
    assertEquals(404, foreign.run("availableOriginInvoiceLines", "{\"invoiceId\":\"src\"}")
        .getHttpStatus());

    Probe noTarget = purchase();
    noTarget.invoices.remove("i1");
    assertEquals(404, noTarget.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}")
        .getHttpStatus());
  }

  /** The purchase modal compares no currency, so neither does the server on that side. */
  @Test
  public void import_purchaseComparesNoCurrency() throws Exception {
    Probe probe = purchase();
    doReturn(currency("usd")).when(probe.invoices.get("src")).getCurrency();

    assertEquals(200, probe.run("importOriginInvoiceLines", "{\"invoiceId\":\"src\"}")
        .getHttpStatus());
    assertEquals(2, probe.created.size());
  }

  // ── discovery ─────────────────────────────────────────────────────────────

  @Test
  public void availableOriginInvoices_listsStandardInvoicesWithSomethingToImport() throws Exception {
    Probe probe = purchase();
    Invoice rectificative = completed("rect1", false,
        sourceLine("rl1", 10, product("p1", "Agua", true), "-1", "2.50"));
    probe.rectificatives.add("rect1");
    Invoice drained = completed("drained", false,
        sourceLine("dl1", 10, product("p1", "Agua", true), "2", "2.50"));
    InvoiceLine mine = mock(InvoiceLine.class);
    InvoiceLine drainedLine = drained.getInvoiceLineList().get(0);
    when(mine.isActive()).thenReturn(true);
    when(mine.getETGOSourceInvoiceLine()).thenReturn(drainedLine);
    probe.target.getInvoiceLineList().add(mine);
    probe.candidates = List.of(probe.invoices.get("src"), rectificative, drained);

    JSONArray rows = probe.run("availableOriginInvoices", null).getBody()
        .getJSONObject("response").getJSONArray("data");

    assertEquals("a rectificative is never listed, nor an invoice whose lines are all here",
        List.of("src"), ids(rows));
    assertEquals(2, rows.getJSONObject(0).getInt("pendingLines"));
    assertEquals(0, probe.created.size());
  }

  @Test
  public void availableOriginInvoiceLines_reportsThePositiveQuantityOfTheSourceLine()
      throws Exception {
    Probe probe = sales();

    JSONArray rows = probe.run("availableOriginInvoiceLines", "{\"invoiceId\":\"src\"}")
        .getBody().getJSONObject("response").getJSONArray("data");

    assertEquals(List.of("sl1", "sl2"), ids(rows));
    assertEquals(10, rows.getJSONObject(0).getInt("pendingQuantity"));
    assertEquals("Agua", rows.getJSONObject(0).getString("product$_identifier"));
    assertTrue("reading changes nothing", probe.created.isEmpty() && probe.links.isEmpty());
  }

  @Test
  public void availableOriginInvoiceLines_aCreditSourceReportsItsMagnitude() throws Exception {
    Probe probe = purchase();
    probe.invoices.put("neg", completed("neg", false,
        sourceLine("nl1", 10, product("p1", "Agua", true), "-6", "2.50")));

    JSONArray rows = probe.run("availableOriginInvoiceLines", "{\"invoiceId\":\"neg\"}")
        .getBody().getJSONObject("response").getJSONArray("data");

    assertEquals(6, rows.getJSONObject(0).getInt("pendingQuantity"));
  }

  @Test
  public void availableOriginInvoiceLines_aDraftTargetIsNotRequiredToRead() throws Exception {
    Probe probe = purchase();
    when(probe.target.getDocumentStatus()).thenReturn("CO");

    assertEquals(200, probe.run("availableOriginInvoiceLines", "{\"invoiceId\":\"src\"}")
        .getHttpStatus());
  }

  /** W6: discovery says when it left something out. */
  @Test
  public void availableOriginInvoices_reportsHasMoreAndTheLimitsUsed() throws Exception {
    Probe probe = purchase();
    probe.rowLimit = 1;
    probe.scanLimit = 5;
    Invoice second = completed("src2", false,
        sourceLine("s2l1", 10, product("p1", "Agua", true), "1", "2.50"));
    probe.candidates = List.of(probe.invoices.get("src"), second);

    JSONObject page = probe.run("availableOriginInvoices", null).getBody()
        .getJSONObject("response");

    assertEquals(1, page.getJSONArray("data").length());
    assertTrue(page.getBoolean("hasMore"));
    assertEquals(5, page.getInt("scanLimit"));
    probe.rowLimit = 50;
    assertFalse(probe.run("availableOriginInvoices", null).getBody().getJSONObject("response")
        .getBoolean("hasMore"));
  }

  // ── contracts and queries ─────────────────────────────────────────────────

  @Test
  public void contracts_nameTheThreeActions_andRequireTheInvoiceId() throws Exception {
    for (boolean sales : new boolean[] {true, false}) {
      Map<String, NeoActionContract> contracts = ImportOriginInvoiceLinesHandler.contracts(sales);
      assertEquals(List.of("availableOriginInvoices", "availableOriginInvoiceLines",
          "importOriginInvoiceLines"), List.copyOf(contracts.keySet()));
      assertNull(NeoActionContract.validate(contracts, "importOriginInvoiceLines",
          new JSONObject().put("invoiceId", "src").put("lines", new JSONArray().put(
              new JSONObject().put("sourceLineId", "a").put("quantity", 1)))));
      assertEquals(422, NeoActionContract.validate(contracts, "availableOriginInvoiceLines",
          new JSONObject()).getHttpStatus());
    }
    String text = ImportOriginInvoiceLinesHandler.contracts(true).get("importOriginInvoiceLines")
        .getDescription();
    assertTrue(text, text.contains("NEGATIVE") && text.contains("Rectificaciones")
        && text.contains("NOT deducted") && text.contains("order line"));
    assertFalse(ImportOriginInvoiceLinesHandler.contracts(false).get("importOriginInvoiceLines")
        .getDescription().contains("order line"));
  }

  @Test
  public void candidateInvoicesHql_isScopedToTheClientAndTheReadableOrganizations() {
    String hql = ImportOriginInvoiceLinesHandler.candidateInvoicesHql(true);
    assertTrue(hql, hql.contains("i.documentStatus = 'CO'") && hql.contains("i.active = true")
        && hql.contains("i.client.id = :client") && hql.contains("i.organization.id in (:orgs)")
        && hql.contains("i.id <> :target") && hql.contains("i.currency.id = :cur"));
    assertFalse(ImportOriginInvoiceLinesHandler.candidateInvoicesHql(false).contains(":cur"));
  }
}
