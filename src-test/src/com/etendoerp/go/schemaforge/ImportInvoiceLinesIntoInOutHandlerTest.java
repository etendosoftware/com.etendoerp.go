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

import static com.etendoerp.go.schemaforge.LineImportTestSupport.CLIENT;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.CURRENCY;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.PARTNER;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.action;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.bd;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.client;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.currency;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.draftInOut;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.errorCode;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.ids;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.orderLine;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.partner;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.product;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import com.etendoerp.go.schemaforge.ImportInvoiceLinesIntoInOutHandler.DraftHolding;
import com.etendoerp.go.schemaforge.ImportInvoiceLinesIntoInOutHandler.SourcePolicy;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link ImportInvoiceLinesIntoInOutHandler}: the two reads behind the "Import from
 * invoice" popup of a draft goods shipment / goods receipt ({@code availableInvoices},
 * {@code availableInvoiceLines}). The DAL reads are seams replaced by a recording double; the
 * per-direction rule is the header's real {@link SourcePolicy} ({@link GoodsShipmentHeaderHandler}
 * / {@link GoodsReceiptHeaderHandler}), so a change of either policy shows here.
 *
 * <p>New class (no existing test covers this new delegate; the sibling
 * {@code ImportOrderLinesIntoInOutHandlerTest} is about another delegate and another source).
 *
 * @covers com.etendoerp.go.schemaforge.ImportInvoiceLinesIntoInOutHandler
 */
public class ImportInvoiceLinesIntoInOutHandlerTest {

  private static final SourcePolicy SHIPMENT = GoodsShipmentHeaderHandler.INVOICE_IMPORT_POLICY;
  private static final SourcePolicy RECEIPT = GoodsReceiptHeaderHandler.INVOICE_IMPORT_POLICY;
  private static final String LINES = "availableInvoiceLines";
  private static final String INVOICES = "availableInvoices";

  @Before
  public void noRectificativeColumnLookup() {
    // The rectificative flag column is probed with SQL; a unit test decides by doc category.
    RectificativeSupport.setColumnPresentForTests(false);
  }

  @After
  public void resetRectificativeColumnLookup() {
    RectificativeSupport.setColumnPresentForTests(null);
  }

  /** Vetoed: Weld scans the test classes, and a bean subclass would make the injection ambiguous. */
  @javax.enterprise.inject.Vetoed
  private static final class Probe extends ImportInvoiceLinesIntoInOutHandler {
    ShipmentInOut target;
    final Map<String, Invoice> invoices = new java.util.HashMap<>();
    List<Invoice> candidates = new ArrayList<>();
    final List<DraftHolding> holdings = new ArrayList<>();
    Integer lastOffset;
    Integer lastScan;
    String lastPartner;
    String lastCurrency;
    Boolean lastSales;
    Boolean holdingsSales;

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
      // nothing written by these reads
    }

    @Override
    protected String sessionClientId() {
      return CLIENT;
    }

    @Override
    protected ShipmentInOut loadTarget(String id) {
      return target;
    }

    @Override
    protected Invoice loadInvoice(String id) {
      return invoices.get(id);
    }

    @Override
    protected List<Invoice> findCandidateInvoices(String partnerId, boolean sales,
        String currencyId, int offset, int scan) {
      lastPartner = partnerId;
      lastSales = sales;
      lastCurrency = currencyId;
      lastOffset = offset;
      lastScan = scan;
      return new ArrayList<>(candidates.subList(Math.min(offset, candidates.size()),
          candidates.size()));
    }

    @Override
    protected List<DraftHolding> draftHoldings(Collection<String> orderLineIds, boolean sales) {
      holdingsSales = sales;
      List<DraftHolding> held = new ArrayList<>();
      for (DraftHolding holding : holdings) {
        if (orderLineIds.contains(holding.orderLineId)) {
          held.add(holding);
        }
      }
      return held;
    }
  }

  private static Probe probe(boolean sales) {
    Probe probe = new Probe();
    probe.target = draftInOut("t1", sales);
    return probe;
  }

  /** A completed invoice of {@link LineImportTestSupport#PARTNER} in its currency. */
  private static Invoice invoice(Probe probe, String id, boolean sales, InvoiceLine... lines) {
    Invoice invoice = mock(Invoice.class);
    BusinessPartner bp = partner(PARTNER);
    Client owner = client(CLIENT);
    Currency cur = currency(CURRENCY);
    when(invoice.getId()).thenReturn(id);
    when(invoice.getDocumentNo()).thenReturn("F-" + id);
    when(invoice.getDocumentStatus()).thenReturn("CO");
    when(invoice.isSalesTransaction()).thenReturn(sales);
    doReturn(bp).when(invoice).getBusinessPartner();
    doReturn(owner).when(invoice).getClient();
    doReturn(cur).when(invoice).getCurrency();
    doReturn(new ArrayList<>(Arrays.asList(lines))).when(invoice).getInvoiceLineList();
    probe.invoices.put(id, invoice);
    return invoice;
  }

  private static InvoiceLine line(String id, long lineNo, Product product, String quantity,
      OrderLine orderLine, boolean linked) {
    InvoiceLine line = mock(InvoiceLine.class);
    UOM uom = mock(UOM.class);
    when(uom.getId()).thenReturn("uom1");
    ShipmentInOutLine movement = linked ? mock(ShipmentInOutLine.class) : null;
    when(line.getId()).thenReturn(id);
    when(line.getLineNo()).thenReturn(lineNo);
    when(line.isActive()).thenReturn(true);
    when(line.getProduct()).thenReturn(product);
    when(line.getInvoicedQuantity()).thenReturn(bd(quantity));
    when(line.getSalesOrderLine()).thenReturn(orderLine);
    when(line.getGoodsShipmentLine()).thenReturn(movement);
    when(line.getUOM()).thenReturn(uom);
    return line;
  }

  private static Product discountProduct() {
    Product discount = mock(Product.class);
    when(discount.getId()).thenReturn(TotalDiscountService.DISCOUNT_PRODUCT_ID);
    when(discount.getName()).thenReturn("Descuento");
    return discount;
  }

  private static NeoResponse run(Probe probe, String action, String body, SourcePolicy policy)
      throws Exception {
    NeoContext context = action(action, "t1", body);
    if (policy != null) {
      context.setAttribute(ImportInvoiceLinesIntoInOutHandler.ATTRIBUTE, policy);
    }
    return probe.handle(context);
  }

  private static JSONObject page(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response");
  }

  private static JSONArray rows(NeoResponse response) throws Exception {
    return page(response).getJSONArray("data");
  }

  private static BigDecimal pendingOf(JSONObject row) throws Exception {
    return new BigDecimal(row.getString("pendingQuantity"));
  }

  private static List<String> strings(JSONArray array) throws Exception {
    List<String> values = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      values.add(array.getString(i));
    }
    return values;
  }

  private static String linesOf(String invoiceId, boolean includeCovered) {
    return "{\"invoiceId\":\"" + invoiceId + "\"" + (includeCovered ? ",\"includeCovered\":true"
        : "") + "}";
  }

  // ── pending per line, by direction ────────────────────────────────────────

  /**
   * Goods shipment: a line with an order line pends invoiced - delivered - every draft (the
   * target's own included), never below 0; one without pends its quantity, 0 once linked. The
   * draft note names only the OTHER drafts. The total-discount and inactive lines are never listed.
   */
  @Test
  public void availableInvoiceLines_shipment_deductsDeliveredAndEveryDraft() throws Exception {
    Probe probe = probe(true);
    OrderLine sold = orderLine("olA", 10, product("p1", "Agua", true), "10", "3", "8");
    OrderLine full = orderLine("olB", 20, product("p2", "Pan", true), "5", "5", "5");
    InvoiceLine withOrder = line("il1", 10, sold.getProduct(), "8", sold, false);
    InvoiceLine plain = line("il2", 20, product("p3", "Sal", false), "4", null, false);
    InvoiceLine linkedPlain = line("il3", 30, product("p4", "Té", false), "2", null, true);
    InvoiceLine overHeld = line("il4", 40, full.getProduct(), "5", full, false);
    InvoiceLine discount = line("il0", 5, discountProduct(), "-1", null, false);
    InvoiceLine inactive = line("il9", 50, product("p9", "Viejo", false), "7", null, false);
    when(inactive.isActive()).thenReturn(false);
    invoice(probe, "inv1", true, overHeld, plain, discount, withOrder, inactive, linkedPlain);
    probe.holdings.add(new DraftHolding("olA", "other", "DS-2", bd("2")));
    probe.holdings.add(new DraftHolding("olA", "t1", "DS-1", bd("1")));
    probe.holdings.add(new DraftHolding("olB", "third", "DS-3", bd("1")));

    JSONArray all = rows(run(probe, LINES, linesOf("inv1", true), SHIPMENT));

    assertEquals("line order, no discount nor inactive line",
        List.of("il1", "il2", "il3", "il4"), ids(all));
    Object[][] expected = {
        { "8 - 3 delivered - 2 other draft - 1 this draft", "2", "olA", false, List.of("DS-2") },
        { "no order line, not linked: its quantity", "4", null, false, List.of() },
        { "no order line, already linked", "0", null, true, List.of() },
        { "5 - 5 delivered - 1 draft clamps at 0", "0", "olB", false, List.of("DS-3") },
    };
    for (int i = 0; i < expected.length; i++) {
      JSONObject row = all.getJSONObject(i);
      String label = (String) expected[i][0];
      assertEquals(label, 0, bd((String) expected[i][1]).compareTo(pendingOf(row)));
      assertEquals(label, expected[i][2] == null ? JSONObject.NULL : expected[i][2],
          row.get("orderLine"));
      assertEquals(label, expected[i][3], row.getBoolean("linked"));
      assertEquals(label, expected[i][4], strings(row.getJSONArray("inDraftDocuments")));
    }
    assertEquals("signed invoiced quantity", "8", all.getJSONObject(0).get("invoicedQuantity")
        .toString());
    assertTrue("shipment drafts are read on the sales side", probe.holdingsSales);
    assertEquals("without includeCovered only what pends", List.of("il1", "il2"),
        ids(rows(run(probe, LINES, linesOf("inv1", false), SHIPMENT))));
  }

  /**
   * Goods receipt (the popup's rule): a linked line has 0, any other its quantity minus what the
   * OTHER draft receipts hold of its order line; neither delivered nor the target's own draft is
   * deducted.
   */
  @Test
  public void availableInvoiceLines_receipt_deductsOnlyOtherDrafts_neverDelivered()
      throws Exception {
    Probe probe = probe(false);
    OrderLine bought = orderLine("olA", 10, product("p1", "Agua", true), "10", "6", "8");
    OrderLine linkedOl = orderLine("olB", 20, product("p2", "Pan", true), "4", "0", "4");
    OrderLine overHeld = orderLine("olC", 30, product("p3", "Sal", true), "5", "0", "5");
    invoice(probe, "inv1", false,
        line("il1", 10, bought.getProduct(), "8", bought, false),
        line("il2", 20, linkedOl.getProduct(), "4", linkedOl, true),
        line("il3", 30, product("p4", "Té", false), "3", null, false),
        line("il4", 40, overHeld.getProduct(), "5", overHeld, false));
    probe.holdings.add(new DraftHolding("olA", "t1", "DR-1", bd("3")));
    probe.holdings.add(new DraftHolding("olA", "other", "DR-2", bd("2")));
    probe.holdings.add(new DraftHolding("olC", "other", "DR-2", bd("9")));

    JSONArray all = rows(run(probe, LINES, linesOf("inv1", true), RECEIPT));

    String[][] expected = {
        { "8 - 2 other draft; delivered 6 and this draft 3 not deducted", "6" },
        { "linked, even with an order line", "0" },
        { "no order line: its quantity", "3" },
        { "5 - 9 other draft clamps at 0", "0" },
    };
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i][0], 0, bd(expected[i][1]).compareTo(pendingOf(all.getJSONObject(i))));
    }
    assertEquals("the target's own draft is not named", List.of("DR-2"),
        strings(all.getJSONObject(0).getJSONArray("inDraftDocuments")));
    assertFalse("receipt drafts are read on the purchase side", probe.holdingsSales);
  }

  /**
   * A line without its own order line takes the header order's line of the same product, the
   * last one winning (the popup's map); a line with its own keeps it.
   */
  @Test
  public void availableInvoiceLines_fallsBackToTheHeaderOrdersLineOfTheProduct_lastWins()
      throws Exception {
    Probe probe = probe(true);
    Product agua = product("p1", "Agua", true);
    OrderLine first = orderLine("olX", 10, agua, "10", "0", "0");
    OrderLine last = orderLine("olY", 20, agua, "10", "1", "0");
    OrderLine own = orderLine("olZ", 30, agua, "10", "0", "0");
    Order header = mock(Order.class);
    when(header.getId()).thenReturn("o1");
    doReturn(new ArrayList<>(List.of(first, last))).when(header).getOrderLineList();
    Invoice invoice = invoice(probe, "inv1", true,
        line("il1", 10, agua, "5", null, false),
        line("il2", 20, agua, "5", own, false));
    when(invoice.getSalesOrder()).thenReturn(header);
    probe.holdings.add(new DraftHolding("olY", "other", "DS-2", bd("1")));

    JSONArray all = rows(run(probe, LINES, linesOf("inv1", true), SHIPMENT));

    assertEquals("olY", all.getJSONObject(0).getString("orderLine"));
    assertEquals("5 - 1 delivered (olY) - 1 draft (olY)", 0,
        bd("3").compareTo(pendingOf(all.getJSONObject(0))));
    assertEquals(List.of("DS-2"), strings(all.getJSONObject(0).getJSONArray("inDraftDocuments")));
    assertEquals("own order line kept", "olZ", all.getJSONObject(1).getString("orderLine"));
    assertEquals(0, bd("5").compareTo(pendingOf(all.getJSONObject(1))));
  }

  /**
   * Without a policy on the context the delegate uses {@link SourcePolicy#DEFAULT}: every invoice
   * offered, delivered and every draft (the target's included) deducted.
   */
  @Test
  public void availableInvoiceLines_withoutAPolicyOnTheContext_usesTheDefault() throws Exception {
    Probe probe = probe(false);
    OrderLine bought = orderLine("olA", 10, product("p1", "Agua", true), "10", "6", "8");
    Invoice invoice = invoice(probe, "inv1", false,
        line("il1", 10, bought.getProduct(), "8", bought, false));
    when(invoice.getGrandTotalAmount()).thenReturn(bd("-5"));
    probe.holdings.add(new DraftHolding("olA", "t1", "DR-1", bd("1")));

    NeoResponse byDefault = run(probe, LINES, linesOf("inv1", true), null);

    assertEquals("default: 8 - 6 delivered - 1 this draft, and a negative total is offered", 0,
        bd("1").compareTo(pendingOf(rows(byDefault).getJSONObject(0))));
    assertEquals("the same source under the receipt policy is refused",
        DocumentLineImport.ERROR_SOURCE_NOT_STANDARD,
        errorCode(run(probe, LINES, linesOf("inv1", true), RECEIPT)));
  }

  // ── refusals ──────────────────────────────────────────────────────────────

  /** What availableInvoiceLines refuses, one difference from a valid source at a time. */
  @Test
  public void availableInvoiceLines_refusesASourceTheListWouldNotOffer() throws Exception {
    DocumentType rectificative = mock(DocumentType.class);
    when(rectificative.getDocumentCategory()).thenReturn("ARC");
    Object[][] cases = {
        { "rectificative into a shipment", true, SHIPMENT, "rect",
            DocumentLineImport.ERROR_SOURCE_NOT_STANDARD },
        { "negative total into a receipt", false, RECEIPT, "negative",
            DocumentLineImport.ERROR_SOURCE_NOT_STANDARD },
        { "another partner", true, SHIPMENT, "partner", DocumentLineImport.ERROR_PARTNER_MISMATCH },
        { "another currency", true, SHIPMENT, "currency",
            DocumentLineImport.ERROR_CURRENCY_MISMATCH },
        { "a purchase invoice into a shipment", true, SHIPMENT, "direction",
            DocumentLineImport.ERROR_DIRECTION_MISMATCH },
        { "a draft invoice", true, SHIPMENT, "draft",
            DocumentLineImport.ERROR_SOURCE_NOT_COMPLETED },
    };
    for (Object[] c : cases) {
      boolean sales = (Boolean) c[1];
      Probe probe = probe(sales);
      String change = (String) c[3];
      Invoice invoice = invoice(probe, "inv1", "direction".equals(change) != sales,
          line("il1", 10, product("p1", "Agua", true), "3", null, false));
      if ("rect".equals(change)) {
        when(invoice.getTransactionDocument()).thenReturn(rectificative);
      } else if ("negative".equals(change)) {
        when(invoice.getGrandTotalAmount()).thenReturn(bd("-1"));
      } else if ("partner".equals(change)) {
        BusinessPartner other = partner("other");
        doReturn(other).when(invoice).getBusinessPartner();
      } else if ("currency".equals(change)) {
        Currency usd = currency("usd");
        doReturn(usd).when(invoice).getCurrency();
      } else if ("draft".equals(change)) {
        when(invoice.getDocumentStatus()).thenReturn("DR");
      }

      NeoResponse response = run(probe, LINES, linesOf("inv1", true), (SourcePolicy) c[2]);

      assertEquals((String) c[0], 422, response.getHttpStatus());
      assertEquals((String) c[0], c[4], errorCode(response));
    }
  }

  @Test
  public void missingTargetInvoiceOrInvoiceId_isAnsweredWithoutListing() throws Exception {
    Probe probe = probe(true);
    assertEquals("unknown invoice", 404, run(probe, LINES, linesOf("nope", false), SHIPMENT)
        .getHttpStatus());
    NeoResponse noId = run(probe, LINES, "{}", SHIPMENT);
    assertEquals(400, noId.getHttpStatus());
    assertEquals(DocumentLineImport.ERROR_INVALID_LINE, errorCode(noId));

    Invoice foreign = invoice(probe, "inv1", true);
    Client other = client("other");
    doReturn(other).when(foreign).getClient();
    assertEquals("an invoice of another client does not exist", 404,
        run(probe, LINES, linesOf("inv1", false), SHIPMENT).getHttpStatus());

    probe.target = null;
    assertEquals(404, run(probe, INVOICES, null, SHIPMENT).getHttpStatus());
    assertNull("never scanned", probe.lastScan);
  }

  // ── availableInvoices ─────────────────────────────────────────────────────

  /**
   * Listed: what the policy offers with a line pending (pendingLines counts them) and the
   * invoice's order id; with includeCovered also an invoice whose lines are all covered, at 0 —
   * never one without any listed line. detail adds creationDate (ISO instant, UTC, or null).
   */
  @Test
  public void availableInvoices_listsByPolicy_includeCoveredAndDetail() throws Exception {
    Probe probe = probe(true);
    DocumentType rectificative = mock(DocumentType.class);
    when(rectificative.getDocumentCategory()).thenReturn("ARC");
    Invoice rect = invoice(probe, "rect", true, line("ir", 10, product("p1", "A", false), "3",
        null, false));
    when(rect.getTransactionDocument()).thenReturn(rectificative);
    Invoice open = invoice(probe, "open", true,
        line("io1", 10, product("p1", "A", false), "3", null, false),
        line("io2", 20, product("p2", "B", false), "2", null, false),
        line("io3", 30, product("p3", "C", false), "1", null, true));
    Order header = mock(Order.class);
    when(header.getId()).thenReturn("o1");
    doReturn(new ArrayList<>()).when(header).getOrderLineList();
    when(open.getSalesOrder()).thenReturn(header);
    when(open.getCreationDate()).thenReturn(Date.from(Instant.parse("2026-11-05T09:12:33.456Z")));
    invoice(probe, "covered", true, line("ic", 10, product("p1", "A", false), "3", null, true));
    invoice(probe, "onlyDiscount", true, line("id", 10, discountProduct(), "-2", null, false));
    probe.candidates = new ArrayList<>(List.of(rect, open, probe.invoices.get("covered"),
        probe.invoices.get("onlyDiscount")));

    JSONArray plain = rows(run(probe, INVOICES, null, SHIPMENT));
    assertEquals(List.of("open"), ids(plain));
    assertEquals(2, plain.getJSONObject(0).getInt("pendingLines"));
    assertEquals("o1", plain.getJSONObject(0).getString("order"));
    assertFalse("no detail, no creationDate", plain.getJSONObject(0).has("creationDate"));
    assertEquals("scanned with the target's partner, direction and currency",
        Arrays.asList(PARTNER, true, CURRENCY),
        Arrays.asList(probe.lastPartner, probe.lastSales, probe.lastCurrency));

    JSONArray all = rows(run(probe, INVOICES, "{\"includeCovered\":true,\"detail\":true}",
        SHIPMENT));
    assertEquals(List.of("open", "covered"), ids(all));
    assertEquals(0, all.getJSONObject(1).getInt("pendingLines"));
    assertTrue(all.getJSONObject(1).isNull("order"));
    assertEquals("2026-11-05T09:12:33Z", all.getJSONObject(0).getString("creationDate"));
    assertTrue("missing creation date is null", all.getJSONObject(1).has("creationDate")
        && all.getJSONObject(1).isNull("creationDate"));
  }

  /**
   * Paging: with limit / offset the page echoes offset and gives nextOffset while hasMore, null
   * once complete; without them the answer keeps its default limits and carries neither key.
   */
  @Test
  public void availableInvoices_pagesThroughTheScan() throws Exception {
    Probe probe = probe(true);
    for (String id : new String[] { "a", "b", "c" }) {
      probe.candidates.add(invoice(probe, id, true,
          line("l" + id, 10, product("p1", "A", false), "1", null, false)));
    }

    JSONObject first = page(run(probe, INVOICES, "{\"limit\":2}", SHIPMENT));
    assertEquals(List.of("a", "b"), ids(first.getJSONArray("data")));
    assertTrue(first.getBoolean("hasMore"));
    assertEquals(0, first.getInt("offset"));
    assertEquals(2, first.getInt("nextOffset"));

    JSONObject second = page(run(probe, INVOICES, "{\"limit\":2,\"offset\":2}", SHIPMENT));
    assertEquals(Integer.valueOf(2), probe.lastOffset);
    assertEquals(List.of("c"), ids(second.getJSONArray("data")));
    assertFalse(second.getBoolean("hasMore"));
    assertEquals(2, second.getInt("offset"));
    assertTrue(second.has("nextOffset") && second.isNull("nextOffset"));

    JSONObject unpaged = page(run(probe, INVOICES, null, SHIPMENT));
    assertEquals(Arrays.asList(50, 200, false, false), Arrays.asList(unpaged.getInt("limit"),
        unpaged.getInt("scanLimit"), unpaged.has("offset"), unpaged.has("nextOffset")));

    NeoResponse bad = run(probe, INVOICES, "{\"offset\":-1}", SHIPMENT);
    assertEquals(400, bad.getHttpStatus());
    assertEquals(DocumentLineImport.ERROR_INVALID_PARAMETER, errorCode(bad));
  }

  // ── contracts and queries ─────────────────────────────────────────────────

  @Test
  public void contracts_declareTheListingParameters_andNameTheSidesRule() throws Exception {
    for (boolean sales : new boolean[] { true, false }) {
      Map<String, NeoActionContract> contracts =
          ImportInvoiceLinesIntoInOutHandler.contracts(sales);
      assertEquals(List.of(INVOICES, LINES), List.copyOf(contracts.keySet()));
      assertFalse(contracts.get(INVOICES).isMutating() || contracts.get(LINES).isMutating());
      assertNull(NeoActionContract.validate(contracts, INVOICES, new JSONObject(
          "{\"limit\":10,\"offset\":5,\"includeCovered\":true,\"detail\":true}")));
      assertNull(NeoActionContract.validate(contracts, LINES, new JSONObject(
          "{\"invoiceId\":\"i\",\"includeCovered\":true}")));
      assertEquals("invoiceId is required", 422,
          NeoActionContract.validate(contracts, LINES, new JSONObject()).getHttpStatus());
      assertEquals("the lines take no detail", 422, NeoActionContract.validate(contracts, LINES,
          new JSONObject("{\"invoiceId\":\"i\",\"detail\":true}")).getHttpStatus());
    }
    assertTrue(ImportInvoiceLinesIntoInOutHandler.contracts(true).get(INVOICES).getDescription()
        .contains("Rectificative invoices are never listed"));
    assertTrue(ImportInvoiceLinesIntoInOutHandler.contracts(false).get(LINES).getDescription()
        .contains("not deducted"));
    assertNull("not its action", probe(true).handle(action("importOrderLines", "t1", null)));
  }

  @Test
  public void candidateInvoicesHql_isScoped_andNarrowsByCurrencyOnlyWhenAsked() {
    for (boolean withCurrency : new boolean[] { false, true }) {
      String hql = ImportInvoiceLinesIntoInOutHandler.candidateInvoicesHql(withCurrency);
      assertTrue(hql, hql.contains("i.documentStatus = 'CO'")
          && hql.contains("i.salesTransaction = :sot") && hql.contains("i.businessPartner.id = :bp")
          && hql.contains("i.client.id = :client") && hql.contains("i.organization.id in (:orgs)")
          && hql.endsWith("order by i.creationDate desc, i.documentNo desc, i.id desc"));
      assertEquals(hql, withCurrency, hql.contains("i.currency.id = :cur"));
    }
  }

  /**
   * draftHoldings reads every line, active or not, of the DRAFT documents of the direction on the
   * given order lines (the same draft set as InOutLineFromOrderFactory.loadDraftQuantities), and
   * maps each row; no order line, no query.
   */
  @Test
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void draftHoldings_readsEveryDraftLineOfTheDirection() {
    ImportInvoiceLinesIntoInOutHandler handler = new ImportInvoiceLinesIntoInOutHandler();
    assertTrue(handler.draftHoldings(List.of(), true).isEmpty());

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      Query query = mock(Query.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      when(session.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
      when(query.setParameter(anyString(), Mockito.any())).thenReturn(query);
      when(query.setParameterList(anyString(), Mockito.anyCollection())).thenReturn(query);
      List<Object[]> result = new ArrayList<>();
      result.add(new Object[] { "olA", "io1", "DS-1", bd("2") });
      when(query.list()).thenReturn((List) result);

      List<DraftHolding> holdings = handler.draftHoldings(List.of("olA"), true);

      ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
      verify(session).createQuery(hql.capture(), eq(Object[].class));
      assertTrue(hql.getValue(), hql.getValue().contains("io.documentStatus = 'DR'")
          && hql.getValue().contains("io.salesTransaction = :sot")
          && !hql.getValue().contains("iol.active")
          && hql.getValue().contains("iol.salesOrderLine.id in (:ids)"));
      verify(query).setParameter("sot", true);
      verify(query).setParameterList("ids", List.of("olA"));
      assertEquals(1, holdings.size());
      DraftHolding held = holdings.get(0);
      assertEquals(Arrays.asList("olA", "io1", "DS-1"),
          Arrays.asList(held.orderLineId, held.inOutId, held.documentNo));
      assertEquals(0, bd("2").compareTo(held.quantity));
    }
  }
}
