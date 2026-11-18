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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

import com.etendoerp.go.schemaforge.AnonymousAttachmentStreamer;


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
  private MockedStatic<PortalPrintable> printables;
  private OBDal obDal;
  private Session session;
  /** Rows the detail's line, tax and instalment queries answer, by a fragment of their HQL. */
  private final Map<String, Query> detailQueries = new HashMap<>();
  private final Query emptyQuery = mock(Query.class);

  @Before
  public void setUp() {
    obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    session = mock(Session.class);
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
    query.when(() -> PortalDocumentQuery.groupCodes(any())).thenCallRealMethod();
    query.when(() -> PortalDocumentQuery.group(any(), any())).thenCallRealMethod();
    query.when(() -> PortalDocumentQuery.invoicesSince(any(), any(), any()))
        .thenReturn(new long[] { 0L, 0L });
    // No server-side render unless a test says so.
    printables = mockStatic(PortalPrintable.class);
    links = mockStatic(PortalDocumentLinks.class);
  }

  @After
  public void tearDown() {
    printables.close();
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
    // Columns: limit, offset, expected limit, expected offset.
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
  public void requestedStatusesAreTrimmedAndBlanksDropped() throws Exception {
    // Columns: ?status=, statuses handed to the query (null: no status filter).
    Object[][] cases = { { "CO, CL", Arrays.asList("CO", "CL") },
        { " CO ,, CL ,", Arrays.asList("CO", "CL") }, { " , ,", null }, { "  ", null } };
    for (Object[] c : cases) {
      query.clearInvocations();

      PortalDocumentsEndpoint.list(SESSION, "invoice", (String) c[0], null, null, null, null,
          null);

      assertEquals("status=" + c[0], c[1], capturedFilter().statuses);
    }
  }

  @Test
  public void notesThatAreOnlyThePortalDecisionLineLeaveTheReferenceEmpty() throws Exception {
    Invoice rejected = invoice("INV-8", null,
        "Rechazado por el cliente desde el portal. Motivo: Precio demasiado alto.", "ARI");
    query.when(() -> PortalDocumentQuery.list(any(), eq(PortalDocumentKind.INVOICE), any(),
        anyInt(), anyInt())).thenReturn(Collections.singletonList(rejected));
    query.when(() -> PortalDocumentQuery.invoiceSchedules(any(), any()))
        .thenReturn(new HashMap<String, Object[]>());

    JSONObject row = PortalDocumentsEndpoint.list(SESSION, "invoice", null, null, null, null, null,
        null).getJSONArray("documents").getJSONObject(0);

    assertEquals("", row.getString("reference"));
  }

  @Test
  public void aRowNamesCreditNotesFallsBackToTheDescriptionAndShipmentsCarryNoAmounts()
      throws Exception {
    // ETP-6209: the notes stand in for the reference without the portal's decision line.
    Invoice creditNote = invoice("INV-9", null,
        "Rectifica F-1\nRechazado por el cliente desde el portal. Motivo: Precio demasiado alto.",
        "ARC");
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
    // No attachment lookup either: the printable is only looked for once the document is found.
    verify(session, never()).createNativeQuery(anyString());
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
    printable(CLIENT_ID);

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
    printable("CLIENT-2");

    JSONObject body = PortalDocumentsEndpoint.detail(SESSION, "order", "ORD-1");

    assertEquals("ORDER", body.getString("kind"));
    assertEquals(1, body.getJSONArray("lines").length());
    assertFalse("instalments are an invoice's only", body.has("installments"));
    assertFalse("another tenant's attachment is no printable", body.getBoolean("hasPdf"));
  }

  @Test
  public void hasPdfAlsoWhenTheServerCanRenderTheMissingPrintable() throws Exception {
    Invoice document = invoice("INV-1", null, null, null);
    query.when(() -> PortalDocumentQuery.findInScope(SESSION, PortalDocumentKind.INVOICE,
        "INV-1")).thenReturn(document);
    try (MockedStatic<AnonymousAttachmentStreamer> stored =
        mockStatic(AnonymousAttachmentStreamer.class)) {
      // No stored printable in either case.
      for (boolean renderable : new boolean[] { true, false }) {
        printables.when(() -> PortalPrintable.isRenderable(PortalDocumentKind.INVOICE))
            .thenReturn(renderable);

        JSONObject body = PortalDocumentsEndpoint.detail(SESSION, "invoice", "INV-1");

        assertEquals("renderable=" + renderable, renderable, body.getBoolean("hasPdf"));
      }
    }
  }

  @Test
  public void theIssuerIsTheDocumentsOrganizationUnlessItIsStar() throws Exception {
    // Columns: organization id, its name, issuer shown.
    Object[][] cases = { { "ORG-9", "Acme Norte SL", "Acme Norte SL" }, { "0", "*", "Acme" },
        { "ORG-9", " ", "Acme" } };
    // No stored printable: the detail's hasPdf must not reach the real attachment lookup.
    try (MockedStatic<AnonymousAttachmentStreamer> stored =
        mockStatic(AnonymousAttachmentStreamer.class)) {
      for (Object[] c : cases) {
        Organization organization = mock(Organization.class);
        when(organization.getId()).thenReturn((String) c[0]);
        when(organization.getName()).thenReturn((String) c[1]);
        Invoice document = invoice("INV-1", null, null, null);
        when(document.get("organization")).thenReturn(organization);
        query.when(() -> PortalDocumentQuery.findInScope(SESSION, PortalDocumentKind.INVOICE,
            "INV-1")).thenReturn(document);

        JSONObject body = PortalDocumentsEndpoint.detail(SESSION, "invoice", "INV-1");

        assertEquals(Arrays.toString(c), c[2], body.getString("issuer"));
      }
    }
  }

  @Test
  public void aShipmentDetailHasNoPaymentDataAndNeverReadsPropertiesItLacks() throws Exception {
    // A shipment's entity has no payment method or terms; the real DAL throws on get() of a
    // property the entity lacks, so the detail must not ask for them.
    Entity entity = mock(Entity.class);
    when(entity.hasProperty(anyString())).thenReturn(true);
    when(entity.hasProperty("paymentMethod")).thenReturn(false);
    when(entity.hasProperty("paymentTerms")).thenReturn(false);
    ShipmentInOut shipment = mock(ShipmentInOut.class);
    when(shipment.getEntityName()).thenReturn(ShipmentInOut.ENTITY_NAME);
    when(shipment.getEntity()).thenReturn(entity);
    when(shipment.getId()).thenReturn("SH-1");
    when(shipment.get("paymentMethod")).thenThrow(new IllegalStateException("no such property"));
    when(shipment.get("paymentTerms")).thenThrow(new IllegalStateException("no such property"));
    query.when(() -> PortalDocumentQuery.findInScope(SESSION, PortalDocumentKind.SHIPMENT,
        "SH-1")).thenReturn(shipment);

    try (MockedStatic<AnonymousAttachmentStreamer> stored =
        mockStatic(AnonymousAttachmentStreamer.class)) {
      JSONObject body = PortalDocumentsEndpoint.detail(SESSION, "shipment", "SH-1");

      assertEquals("", body.getString("paymentMethod"));
      assertEquals("", body.getString("paymentTerms"));
    }
  }

  @Test
  public void aQuotationDetailSaysWhetherTheCustomerCanDecideNow() throws Exception {
    Order quotation = order("Q-1", "OB");
    query.when(() -> PortalDocumentQuery.findInScope(SESSION, PortalDocumentKind.QUOTATION,
        "Q-1")).thenReturn(quotation);
    // Columns: why the decision is blocked (null: it is not), canDecide, blockedReason.
    Object[][] cases = { { null, true, null },
        { PortalDecisionService.Blocked.EXPIRED, false, "expired" },
        { PortalDecisionService.Blocked.NOT_PENDING, false, "not_pending" } };
    // No stored printable: the detail's hasPdf must not reach the real attachment lookup.
    try (MockedStatic<AnonymousAttachmentStreamer> stored =
        mockStatic(AnonymousAttachmentStreamer.class)) {
      for (Object[] c : cases) {
        try (MockedConstruction<PortalDecisionService> decisions = mockConstruction(
            PortalDecisionService.class, (service, context) -> {
              when(service.blockedReason(any(), any()))
                  .thenReturn((PortalDecisionService.Blocked) c[0]);
              when(service.confirmationRecipient(any())).thenReturn(" juan@example.com ");
            })) {

          JSONObject decision = PortalDocumentsEndpoint.detail(SESSION, "quotation", "Q-1")
              .getJSONObject("decision");

          assertEquals(c[1], decision.getBoolean("canDecide"));
          if (c[2] == null) {
            assertTrue(decision.isNull("blockedReason"));
          } else {
            assertEquals(c[2], decision.getString("blockedReason"));
          }
          assertEquals(PortalDecisionService.ACCEPT_AVAILABLE,
              decision.getBoolean("acceptAvailable"));
          // The popups name the recipient in full (trimmed), and only when the customer can decide.
          if (Boolean.TRUE.equals(c[1])) {
            assertEquals("juan@example.com", decision.getString("email"));
          } else {
            assertFalse(decision.has("email"));
          }
          verify(decisions.constructed().get(0)).blockedReason(SESSION, quotation);
        }
      }
      // Only a quotation carries the block: an order's detail has none.
      Order order = order("ORD-1", "SO");
      query.when(() -> PortalDocumentQuery.findInScope(SESSION, PortalDocumentKind.ORDER,
          "ORD-1")).thenReturn(order);
      assertFalse(PortalDocumentsEndpoint.detail(SESSION, "order", "ORD-1").has("decision"));
    }
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
      if (filter.group != null) {
        // The tile counts exactly the list tab's group, so the tile and the tab agree.
        return filter.group == PortalDocumentQuery.group(PortalDocumentKind.QUOTATION, "awaiting")
            ? 4L : filter.group == PortalDocumentQuery.group(PortalDocumentKind.ORDER,
                "inProgress") ? 5L : -1L;
      }
      return filter.dateFrom != null ? 6L : 10L;
    });

    JSONObject tiles = PortalDocumentsEndpoint.home(SESSION).getJSONObject("tiles");

    assertEquals(10L, tiles.getJSONObject("QUOTATION").getLong("count"));
    assertHighlight(tiles, "QUOTATION", "awaitingAnswer", 4L);
    assertHighlight(tiles, "INVOICE", "overdue", 3L);
    assertHighlight(tiles, "ORDER", "inProgress", 5L);
    assertHighlight(tiles, "SHIPMENT", "thisMonth", 6L);
    ArgumentCaptor<PortalDocumentQuery.Filter> shipment =
        ArgumentCaptor.forClass(PortalDocumentQuery.Filter.class);
    query.verify(() -> PortalDocumentQuery.count(eq(SESSION), eq(PortalDocumentKind.SHIPMENT),
        shipment.capture()), times(2));
    assertEquals(Date.from(LocalDate.now().withDayOfMonth(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant()),
        shipment.getAllValues().get(1).dateFrom);
  }

  @Test
  public void homeAddsWhatWasPaidThisYearTheYearsInvoicesAndAMonthlyChart() throws Exception {
    query.when(() -> PortalDocumentQuery.paidSince(eq(SESSION), any())).thenReturn(
        Collections.singletonList(new Object[] { "EUR", new BigDecimal("500"), 3L }));
    query.when(() -> PortalDocumentQuery.invoicesSince(eq(SESSION), any(), any()))
        .thenReturn(new long[] { 10L, 2L });
    LocalDate thisMonth = LocalDate.now().withDayOfMonth(1);
    LocalDate lastMonth = thisMonth.minusMonths(1);
    // EUR invoiced in two of the months, USD in one: the chart is in EUR.
    query.when(() -> PortalDocumentQuery.invoicedByMonth(eq(SESSION), any())).thenReturn(
        Arrays.asList(
            new Object[] { thisMonth.getYear(), thisMonth.getMonthValue(), "EUR",
                new BigDecimal("100") },
            new Object[] { lastMonth.getYear(), lastMonth.getMonthValue(), "EUR",
                new BigDecimal("40") },
            new Object[] { thisMonth.getYear(), thisMonth.getMonthValue(), "USD",
                new BigDecimal("999") }));

    JSONObject home = PortalDocumentsEndpoint.home(SESSION, "3");

    JSONObject paid = home.getJSONArray("paidThisYear").getJSONObject(0);
    assertEquals("EUR", paid.getString("currency"));
    assertEquals(0, new BigDecimal("500").compareTo(new BigDecimal(paid.getString("paid"))));
    assertEquals(3L, paid.getLong("count"));
    assertEquals(10L, home.getJSONObject("invoicesThisYear").getLong("total"));
    assertEquals(8L, home.getJSONObject("invoicesThisYear").getLong("upToDate"));
    JSONObject chart = home.getJSONObject("chart");
    assertEquals(3, chart.getInt("months"));
    assertEquals("EUR", chart.getString("currency"));
    JSONArray bars = chart.getJSONArray("bars");
    assertEquals(3, bars.length());
    // Oldest first, the current month last; a month with nothing invoiced is a 0 bar.
    assertEquals(String.format("%04d-%02d", thisMonth.getYear(), thisMonth.getMonthValue()),
        bars.getJSONObject(2).getString("month"));
    assertEquals(0, new BigDecimal("100").compareTo(
        new BigDecimal(bars.getJSONObject(2).getString("amount"))));
    assertEquals(0, new BigDecimal("40").compareTo(
        new BigDecimal(bars.getJSONObject(1).getString("amount"))));
    assertEquals(0, BigDecimal.ZERO.compareTo(
        new BigDecimal(bars.getJSONObject(0).getString("amount"))));
    // The chart starts on the 1st of the oldest month shown.
    ArgumentCaptor<Date> from = ArgumentCaptor.forClass(Date.class);
    query.verify(() -> PortalDocumentQuery.invoicedByMonth(eq(SESSION), from.capture()));
    assertEquals(Date.from(thisMonth.minusMonths(2).atStartOfDay(ZoneId.systemDefault())
        .toInstant()), from.getValue());
  }

  @Test
  public void theChartPeriodIsThreeSixOrTwelveMonthsAndTwelveOtherwise() throws Exception {
    // Columns: months=, bars.
    Object[][] cases = { { "3", 3 }, { "6", 6 }, { "12", 12 }, { "7", 12 }, { null, 12 },
        { "abc", 12 } };
    for (Object[] c : cases) {
      JSONObject chart = PortalDocumentsEndpoint.home(SESSION, (String) c[0])
          .getJSONObject("chart");

      assertEquals("months=" + c[0], c[1], chart.getInt("months"));
      assertEquals(c[1], chart.getJSONArray("bars").length());
      assertTrue("nothing invoiced: no currency", chart.isNull("currency"));
    }
  }

  @Test
  public void searchListsTheMatchesOfEveryKindAsRows() throws Exception {
    Invoice invoice = invoice("INV-5", null, null, null);
    Order order = order("ORD-5", "SO");
    query.when(() -> PortalDocumentQuery.search(SESSION, "F-5", 8))
        .thenReturn(Arrays.<BaseOBObject>asList(invoice, order));

    JSONArray documents = PortalDocumentsEndpoint.search(SESSION, "F-5")
        .getJSONArray("documents");

    assertEquals(2, documents.length());
    assertEquals("INVOICE", documents.getJSONObject(0).getString("kind"));
    assertEquals("ORDER", documents.getJSONObject(1).getString("kind"));
  }

  @Test
  public void aListGroupReplacesTheStatusFilterAndIsEchoed() throws Exception {
    JSONObject body = PortalDocumentsEndpoint.list(SESSION, "invoice", "pending", "VO", null,
        null, null, null, null);
    PortalDocumentQuery.Filter filter = capturedFilter();
    assertTrue(filter.group == PortalDocumentQuery.group(PortalDocumentKind.INVOICE,
        "pending"));
    assertNull("a group replaces ?status=", filter.statuses);
    assertEquals("pending", body.getString("group"));
    assertEquals(Arrays.asList("pending", "overdue", "paid"),
        Arrays.asList(body.getJSONArray("groups").getString(0),
            body.getJSONArray("groups").getString(1), body.getJSONArray("groups").getString(2)));

    query.clearInvocations();
    body = PortalDocumentsEndpoint.list(SESSION, "invoice", "nope", "VO", null, null, null, null,
        null);
    filter = capturedFilter();
    assertNull("an unknown group is \"all\"", filter.group);
    assertEquals(Collections.singletonList("VO"), filter.statuses);
    assertTrue(body.isNull("group"));
  }

  @Test
  public void aQuotationRowNamesTheOrderMadeFromIt() throws Exception {
    Order quotation = order("Q-1", "OB");
    query.when(() -> PortalDocumentQuery.list(any(), eq(PortalDocumentKind.QUOTATION), any(),
        anyInt(), anyInt())).thenReturn(Collections.<BaseOBObject>singletonList(quotation));
    Map<String, String[]> orders = new HashMap<>();
    orders.put("Q-1", new String[] { "ORD-9", "PV-0009" });
    query.when(() -> PortalDocumentQuery.ordersOfQuotations(eq(SESSION), any()))
        .thenReturn(orders);

    JSONObject row = PortalDocumentsEndpoint.list(SESSION, "quotation", null, null, null, null,
        null, null).getJSONArray("documents").getJSONObject(0);

    assertEquals("ORD-9", row.getString("orderId"));
    assertEquals("PV-0009", row.getString("orderNo"));
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

  /**
   * The record's main attachment, found the session-less way (native {@code ad_table} and
   * {@code C_File} lookups, as {@code NeoDocumentDownloadServiceTest} stubs them) and owned by
   * {@code attachmentClientId}.
   */
  private void printable(String attachmentClientId) {
    NativeQuery tableQuery = mock(NativeQuery.class);
    when(tableQuery.setParameter(anyString(), any())).thenReturn(tableQuery);
    when(tableQuery.list()).thenReturn(Collections.singletonList("TABLE1"));
    NativeQuery mainQuery = mock(NativeQuery.class);
    when(mainQuery.setParameter(anyString(), any())).thenReturn(mainQuery);
    when(mainQuery.list()).thenReturn(Collections.singletonList("ATT-1"));
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.contains("ad_table"))))
        .thenReturn(tableQuery);
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.contains("C_File"))))
        .thenReturn(mainQuery);
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
