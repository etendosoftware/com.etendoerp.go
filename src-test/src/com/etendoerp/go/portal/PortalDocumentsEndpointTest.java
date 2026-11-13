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


package com.etendoerp.go.portal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
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
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

import com.etendoerp.go.schemaforge.NeoAttachmentsHelper;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link PortalDocumentsEndpoint}: the home aggregates, the list of one kind, one
 * document's detail and the shape of a document row.
 *
 * <p>{@link PortalDocumentQuery} is mocked statically (its pure helpers {@code amount},
 * {@code dateOf} and {@code visibleStatuses} keep their real behaviour), so each test states what
 * the database answered and reads what the endpoint made of it.
 *
 * @covers com.etendoerp.go.portal.PortalDocumentsEndpoint
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class PortalDocumentsEndpointTest {

  private static final String CLIENT_ID = "CLIENT-1";
  private static final PortalSession SESSION = new PortalSession(new PortalLink("LINK-1",
      CLIENT_ID, "Acme", "ORG-1", "BP-1", "Customer SL", PortalDocumentKind.INVOICE, "INV-1",
      PortalLink.ORIGIN_SEND, null, "LINK-1", "sales-invoice-send", "a@customer.test", null,
      "es_ES", new Date(0L), new Date(1L), true, false));

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<PortalDocumentQuery> query;
  private MockedStatic<PortalDocumentLinks> links;
  private MockedStatic<NeoAttachmentsHelper> attachments;
  private OBDal obDal;
  /** Rows the detail's line, tax and instalment queries answer, by a fragment of their HQL. */
  private final Map<String, Query> detailQueries = new HashMap<>();
  private final Query emptyQuery = mock(Query.class);

  @Before
  public void setUp() {
    obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    Session session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    doAnswer(invocation -> {
      String hql = invocation.getArgument(0);
      for (Map.Entry<String, Query> entry : detailQueries.entrySet()) {
        if (hql.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      return emptyQuery;
    }).when(session).createQuery(anyString(), eq(BaseOBObject.class));

    query = mockStatic(PortalDocumentQuery.class);
    query.when(() -> PortalDocumentQuery.amount(any())).thenCallRealMethod();
    query.when(() -> PortalDocumentQuery.dateOf(any())).thenCallRealMethod();
    query.when(() -> PortalDocumentQuery.visibleStatuses(any())).thenCallRealMethod();
    links = mockStatic(PortalDocumentLinks.class);
    attachments = mockStatic(NeoAttachmentsHelper.class);
  }

  @After
  public void tearDown() {
    attachments.close();
    links.close();
    query.close();
    obDalStatic.close();
  }

  // ── list ────────────────────────────────────────────────────────────────────

  @Test
  public void aListOfAnUnknownTypeIsNotFound() throws Exception {
    for (String type : Arrays.asList("receipt", "", null)) {
      assertNull(type, PortalDocumentsEndpoint.list(SESSION, type, null, null, null, null, null,
          null));
    }
    query.verify(() -> PortalDocumentQuery.list(any(), any(), any(), anyInt(),
        anyInt()), never());
  }

  @Test
  public void limitAndOffsetAreClampedAndMalformedValuesIgnored() throws Exception {
    // { limit, offset, expected limit, expected offset }
    Object[][] cases = { { null, null, 50, 0 }, { "0", "-5", 1, 0 }, { "500", "20", 200, 20 },
        { "abc", "x", 50, 0 }, { " 30 ", "10", 30, 10 } };
    for (Object[] c : cases) {
      query.clearInvocations();

      JSONObject body = PortalDocumentsEndpoint.list(SESSION, "invoice", null, null, null, null,
          (String) c[0], (String) c[1]);

      assertEquals(c[2], body.getInt("limit"));
      assertEquals(c[3], body.getInt("offset"));
      query.verify(() -> PortalDocumentQuery.list(eq(SESSION), eq(PortalDocumentKind.INVOICE),
          any(), eq((int) (Integer) c[3]), eq((int) (Integer) c[2])));
    }
  }

  @Test
  public void totalCountIsTheSumOfThePerCurrencyCounts() throws Exception {
    List<Object[]> perCurrency = Arrays.asList(
        new Object[] { "EUR", 3L, new BigDecimal("100"), new BigDecimal("121") },
        new Object[] { "USD", 2L, new BigDecimal("50"), new BigDecimal("60.5") });
    query.when(() -> PortalDocumentQuery.totals(any(), eq(PortalDocumentKind.INVOICE), any()))
        .thenReturn(perCurrency);

    JSONObject body = PortalDocumentsEndpoint.list(SESSION, "INVOICE", null, null, null, null,
        null, null);

    assertEquals(5L, body.getLong("totalCount"));
    JSONArray totals = body.getJSONArray("totals");
    assertEquals(2, totals.length());
    assertEquals("USD", totals.getJSONObject(1).getString("currency"));
    assertEquals(2L, totals.getJSONObject(1).getLong("count"));
  }

  @Test
  public void theChosenDateToIsIncludedAndMalformedFiltersAreDropped() throws Exception {
    PortalDocumentsEndpoint.list(SESSION, "invoice", "DR,CO", "2026-03-01", "2026-03-31", "abc",
        null, null);
    PortalDocumentQuery.Filter filter = capturedFilter();
    assertEquals(day(2026, 3, 1), filter.dateFrom);
    // The whole of 31 March: the query compares with < the next day.
    assertEquals(day(2026, 4, 1), filter.dateToExclusive);
    assertEquals(Arrays.asList("DR", "CO"), filter.statuses);
    assertEquals("abc", filter.search);

    query.clearInvocations();
    PortalDocumentsEndpoint.list(SESSION, "invoice", null, "31/03/2026", "2026-02-30", null,
        null, null);
    filter = capturedFilter();
    assertNull(filter.dateFrom);
    assertNull(filter.dateToExclusive);
  }

  @Test
  public void aRowNamesCreditNotesFallsBackToTheDescriptionAndShipmentsCarryNoAmounts()
      throws Exception {
    Invoice creditNote = invoice("INV-9", null, "Rectifica F-1", "ARC");
    Map<String, Object[]> schedules = new HashMap<>();
    schedules.put("INV-9", new Object[] { day(2026, 1, 10), 1L });
    query.when(() -> PortalDocumentQuery.list(any(), eq(PortalDocumentKind.INVOICE), any(),
        anyInt(), anyInt())).thenReturn(Collections.singletonList(creditNote));
    query.when(() -> PortalDocumentQuery.invoiceSchedules(any(), any())).thenReturn(schedules);
    ShipmentInOut shipment = mock(ShipmentInOut.class);
    when(shipment.getEntityName()).thenReturn(ShipmentInOut.ENTITY_NAME);
    when(shipment.getId()).thenReturn("SHP-1");
    when(shipment.get("orderReference")).thenReturn("PO-7");
    query.when(() -> PortalDocumentQuery.list(any(), eq(PortalDocumentKind.SHIPMENT), any(),
        anyInt(), anyInt())).thenReturn(Collections.singletonList(shipment));

    JSONObject invoiceRow = PortalDocumentsEndpoint.list(SESSION, "invoice", null, null, null,
        null, null, null).getJSONArray("documents").getJSONObject(0);
    JSONObject shipmentRow = PortalDocumentsEndpoint.list(SESSION, "shipment", null, null, null,
        null, null, null).getJSONArray("documents").getJSONObject(0);

    assertEquals("INVOICE", invoiceRow.getString("kind"));
    assertTrue(invoiceRow.getBoolean("isCreditNote"));
    assertEquals("Rectifica F-1", invoiceRow.getString("reference"));
    assertEquals("EUR", invoiceRow.getString("currency"));
    assertEquals(0, new BigDecimal("121").compareTo(
        new BigDecimal(invoiceRow.getString("grandTotal"))));
    assertEquals("2026-01-10", invoiceRow.getString("dueDate"));
    assertTrue(invoiceRow.getBoolean("overdue"));

    assertEquals("SHIPMENT", shipmentRow.getString("kind"));
    assertFalse(shipmentRow.getBoolean("isCreditNote"));
    assertEquals("PO-7", shipmentRow.getString("reference"));
    for (String amount : Arrays.asList("currency", "taxBase", "grandTotal")) {
      assertTrue(amount, shipmentRow.isNull(amount));
    }
  }

  // ── detail ──────────────────────────────────────────────────────────────────

  @Test
  public void aDocumentOutOfScopeOfTheWrongKindOrOfAnUnknownKindIsNotFound() throws Exception {
    Invoice inScope = invoice("INV-1", null, null, null);
    query.when(() -> PortalDocumentQuery.findInScope(SESSION, PortalDocumentKind.INVOICE,
        "INV-1")).thenReturn(inScope);

    assertNull(PortalDocumentsEndpoint.detail(SESSION, "invoice", "OTHER-BPS-INVOICE"));
    assertNull("an invoice id asked for as an order",
        PortalDocumentsEndpoint.detail(SESSION, "order", "INV-1"));
    assertNull(PortalDocumentsEndpoint.detail(SESSION, "receipt", "INV-1"));

    query.verify(() -> PortalDocumentQuery.findInScope(any(), any(), any()),
        times(2));
    links.verifyNoInteractions();
    attachments.verifyNoInteractions();
  }

  @Test
  public void anInvoiceDetailCarriesLinesTaxesInstalmentsRelatedDocumentsAndItsPrintable()
      throws Exception {
    Invoice document = invoice("INV-1", "PO-1", null, null);
    query.when(() -> PortalDocumentQuery.findInScope(SESSION, PortalDocumentKind.INVOICE,
        "INV-1")).thenReturn(document);
    detailRows("from InvoiceLine", mock(BaseOBObject.class), mock(BaseOBObject.class));
    detailRows("from InvoiceTax", mock(BaseOBObject.class));
    detailRows("from FIN_Payment_Schedule", mock(BaseOBObject.class), mock(BaseOBObject.class));
    Order order = order("ORD-1", "SO");
    links.when(() -> PortalDocumentLinks.related(SESSION, PortalDocumentKind.INVOICE, "INV-1"))
        .thenReturn(Collections.<BaseOBObject>singletonList(order));
    printable("C_Invoice", "INV-1", CLIENT_ID);

    JSONObject body = PortalDocumentsEndpoint.detail(SESSION, "invoice", "INV-1");

    assertEquals(2, body.getJSONArray("lines").length());
    assertEquals(1, body.getJSONArray("taxes").length());
    assertEquals(2, body.getJSONArray("installments").length());
    assertEquals("ORDER", body.getJSONArray("related").getJSONObject(0).getString("kind"));
    assertEquals("ORD-1", body.getJSONArray("related").getJSONObject(0).getString("id"));
    assertTrue(body.getBoolean("hasPdf"));
    assertEquals("Acme", body.getString("issuer"));
  }

  @Test
  public void anOrderDetailHasNoInstalmentsAndNoPrintableOfAnotherTenant() throws Exception {
    Order document = order("ORD-1", "SO");
    query.when(() -> PortalDocumentQuery.findInScope(SESSION, PortalDocumentKind.ORDER,
        "ORD-1")).thenReturn(document);
    detailRows("from OrderLine", mock(BaseOBObject.class));
    printable("C_Order", "ORD-1", "CLIENT-2");

    JSONObject body = PortalDocumentsEndpoint.detail(SESSION, "order", "ORD-1");

    assertEquals("ORDER", body.getString("kind"));
    assertEquals(1, body.getJSONArray("lines").length());
    assertFalse("instalments are an invoice's only", body.has("installments"));
    assertFalse("another tenant's attachment is no printable", body.getBoolean("hasPdf"));
  }

  // ── home ────────────────────────────────────────────────────────────────────

  @Test
  public void homeBalancesArePerCurrencyWithTheOverdueOfThatSameCurrency() throws Exception {
    query.when(() -> PortalDocumentQuery.outstandingByCurrency(SESSION)).thenReturn(Arrays.asList(
        new Object[] { "EUR", new BigDecimal("300"), 4L },
        new Object[] { "USD", new BigDecimal("80"), 1L }));
    query.when(() -> PortalDocumentQuery.overdueByCurrency(eq(SESSION), any()))
        .thenReturn(Collections.singletonList(
            new Object[] { "EUR", new BigDecimal("50"), day(2026, 1, 10), 2L }));

    JSONArray balances = PortalDocumentsEndpoint.home(SESSION).getJSONArray("balances");

    JSONObject eur = balances.getJSONObject(0);
    assertEquals("EUR", eur.getString("currency"));
    assertEquals(0, new BigDecimal("300").compareTo(new BigDecimal(eur.getString("outstanding"))));
    assertEquals(0, new BigDecimal("50").compareTo(new BigDecimal(eur.getString("overdue"))));
    assertEquals("2026-01-10", eur.getString("overdueSince"));
    assertEquals(2L, eur.getLong("overdueCount"));
    JSONObject usd = balances.getJSONObject(1);
    assertEquals("USD", usd.getString("currency"));
    assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(usd.getString("overdue"))));
    assertTrue(usd.isNull("overdueSince"));
    assertEquals(0L, usd.getLong("overdueCount"));
  }

  @Test
  public void homeHighlightsWhatEachKindNeedsFromTheCustomer() throws Exception {
    query.when(() -> PortalDocumentQuery.overdueByCurrency(eq(SESSION), any()))
        .thenReturn(Arrays.asList(new Object[] { "EUR", BigDecimal.ONE, day(2026, 1, 1), 2L },
            new Object[] { "USD", BigDecimal.ONE, day(2026, 1, 1), 1L }));
    query.when(() -> PortalDocumentQuery.count(any(), any(), any())).thenAnswer(invocation -> {
      PortalDocumentQuery.Filter filter = invocation.getArgument(2);
      if (filter.statuses != null) {
        return 4L;
      }
      return filter.dateFrom != null ? 6L : 10L;
    });

    JSONObject tiles = PortalDocumentsEndpoint.home(SESSION).getJSONObject("tiles");

    assertEquals(10L, tiles.getJSONObject("QUOTATION").getLong("count"));
    assertHighlight(tiles, "QUOTATION", "awaitingAnswer", 4L);
    assertHighlight(tiles, "INVOICE", "overdue", 3L);
    assertHighlight(tiles, "SHIPMENT", "thisMonth", 6L);
    assertTrue(tiles.getJSONObject("ORDER").isNull("highlight"));
    // Quotations awaiting an answer are the UE ones only; shipments count from the 1st.
    ArgumentCaptor<PortalDocumentQuery.Filter> quotation =
        ArgumentCaptor.forClass(PortalDocumentQuery.Filter.class);
    query.verify(() -> PortalDocumentQuery.count(eq(SESSION), eq(PortalDocumentKind.QUOTATION),
        quotation.capture()), times(2));
    assertEquals(Collections.singletonList("UE"), quotation.getAllValues().get(1).statuses);
    ArgumentCaptor<PortalDocumentQuery.Filter> shipment =
        ArgumentCaptor.forClass(PortalDocumentQuery.Filter.class);
    query.verify(() -> PortalDocumentQuery.count(eq(SESSION), eq(PortalDocumentKind.SHIPMENT),
        shipment.capture()), times(2));
    assertEquals(Date.from(LocalDate.now().withDayOfMonth(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant()),
        shipment.getAllValues().get(1).dateFrom);
  }

  @Test
  public void homeRecentDocumentsAreTheFiveNewestInTheQuerysOrder() throws Exception {
    Invoice newest = invoice("INV-2", null, null, null);
    Order older = order("ORD-1", "OB");
    query.when(() -> PortalDocumentQuery.recent(SESSION, 5))
        .thenReturn(Arrays.<BaseOBObject>asList(newest, older));

    JSONArray recent = PortalDocumentsEndpoint.home(SESSION).getJSONArray("recent");

    assertEquals(2, recent.length());
    assertEquals("INV-2", recent.getJSONObject(0).getString("id"));
    assertEquals("QUOTATION", recent.getJSONObject(1).getString("kind"));
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private PortalDocumentQuery.Filter capturedFilter() {
    ArgumentCaptor<PortalDocumentQuery.Filter> filter =
        ArgumentCaptor.forClass(PortalDocumentQuery.Filter.class);
    query.verify(() -> PortalDocumentQuery.list(any(), any(), filter.capture(),
        anyInt(), anyInt()));
    return filter.getValue();
  }

  private static void assertHighlight(JSONObject tiles, String kind, String code, long count)
      throws Exception {
    JSONObject highlight = tiles.getJSONObject(kind).getJSONObject("highlight");
    assertEquals(kind, code, highlight.getString("code"));
    assertEquals(kind, count, highlight.getLong("count"));
  }

  private void detailRows(String hqlFragment, BaseOBObject... rows) {
    Query rowsQuery = mock(Query.class);
    when(rowsQuery.list()).thenReturn(Arrays.asList(rows));
    detailQueries.put(hqlFragment, rowsQuery);
  }

  private void printable(String table, String recordId, String attachmentClientId)
      throws Exception {
    NeoResponse main = new NeoResponse(200, new JSONObject().put("id", "ATT-1"));
    attachments.when(() -> NeoAttachmentsHelper.handleGetMain(table, recordId)).thenReturn(main);
    Client owner = mock(Client.class);
    when(owner.getId()).thenReturn(attachmentClientId);
    Attachment attachment = mock(Attachment.class);
    when(attachment.getClient()).thenReturn(owner);
    when(obDal.get(Attachment.class, "ATT-1")).thenReturn(attachment);
  }

  private static Invoice invoice(String id, String reference, String description,
      String category) {
    Currency euro = mock(Currency.class);
    when(euro.getISOCode()).thenReturn("EUR");
    DocumentType documentType = null;
    if (category != null) {
      documentType = mock(DocumentType.class);
      when(documentType.getDocumentCategory()).thenReturn(category);
    }
    Invoice invoice = mock(Invoice.class);
    when(invoice.getEntityName()).thenReturn(Invoice.ENTITY_NAME);
    when(invoice.getId()).thenReturn(id);
    when(invoice.get("orderReference")).thenReturn(reference);
    when(invoice.get("description")).thenReturn(description);
    when(invoice.get("currency")).thenReturn(euro);
    when(invoice.get("summedLineAmount")).thenReturn(new BigDecimal("100"));
    when(invoice.get("grandTotalAmount")).thenReturn(new BigDecimal("121"));
    when(invoice.getTransactionDocument()).thenReturn(documentType);
    when(invoice.getOutstandingAmount()).thenReturn(new BigDecimal("121"));
    return invoice;
  }

  private static Order order(String id, String subType) {
    DocumentType documentType = mock(DocumentType.class);
    when(documentType.getSOSubType()).thenReturn(subType);
    Order order = mock(Order.class);
    when(order.getEntityName()).thenReturn(Order.ENTITY_NAME);
    when(order.getId()).thenReturn(id);
    when(order.getTransactionDocument()).thenReturn(documentType);
    return order;
  }

  private static Date day(int year, int month, int dayOfMonth) {
    return Date.from(LocalDate.of(year, month, dayOfMonth).atStartOfDay(ZoneId.systemDefault())
        .toInstant());
  }
}
