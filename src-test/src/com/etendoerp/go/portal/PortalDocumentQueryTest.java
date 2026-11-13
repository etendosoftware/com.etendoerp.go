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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * Unit tests for {@link PortalDocumentQuery}, the only place the portal composes a document query:
 * every builder carries the session's scope, a kind's own discriminator and its status allow-list,
 * and nothing a request sends ever becomes query text.
 *
 * <p>The Hibernate session is a mock: each test reads back the HQL that was built and the
 * parameters that were bound.
 *
 * @covers com.etendoerp.go.portal.PortalDocumentQuery
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class PortalDocumentQueryTest {

  private static final String CLIENT_ID = "CLIENT-1";
  private static final String BP_ID = "BP-1";
  private static final PortalSession SESSION = new PortalSession(new PortalLink("LINK-1",
      CLIENT_ID, "Acme", "ORG-1", BP_ID, "Customer SL", PortalDocumentKind.INVOICE, "INV-1",
      PortalLink.ORIGIN_SEND, null, "LINK-1", "sales-invoice-send", "a@customer.test", null,
      "es_ES", new Date(0L), new Date(1L), true, false));

  private MockedStatic<OBDal> obDalStatic;
  private Session session;
  /** Every query built during a test, in order, with the HQL it was built from. */
  private final Map<Query, String> built = new LinkedHashMap<>();

  @Before
  public void setUp() {
    OBDal obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    doAnswer(invocation -> {
      Query query = mock(Query.class);
      built.put(query, invocation.getArgument(0));
      return query;
    }).when(session).createQuery(anyString(), any(Class.class));
  }

  @After
  public void tearDown() {
    obDalStatic.close();
  }

  @Test
  public void everyBuilderBindsTheScopeTheKindFilterAndTheStatusAllowList() {
    // { label, kind the query is about, the call }
    Object[][] cases = {
        { "count quotations", PortalDocumentKind.QUOTATION, (Runnable) () ->
            PortalDocumentQuery.count(SESSION, PortalDocumentKind.QUOTATION, none()) },
        { "count orders", PortalDocumentKind.ORDER, (Runnable) () ->
            PortalDocumentQuery.count(SESSION, PortalDocumentKind.ORDER, none()) },
        { "count shipments", PortalDocumentKind.SHIPMENT, (Runnable) () ->
            PortalDocumentQuery.count(SESSION, PortalDocumentKind.SHIPMENT, none()) },
        { "list invoices", PortalDocumentKind.INVOICE, (Runnable) () ->
            PortalDocumentQuery.list(SESSION, PortalDocumentKind.INVOICE, none(), 0, 10) },
        { "totals of orders", PortalDocumentKind.ORDER, (Runnable) () ->
            PortalDocumentQuery.totals(SESSION, PortalDocumentKind.ORDER, none()) },
        { "find quotations", PortalDocumentKind.QUOTATION, (Runnable) () ->
            PortalDocumentQuery.findAllInScope(SESSION, PortalDocumentKind.QUOTATION,
                Collections.singletonList("ORD-1")) },
        { "outstanding", PortalDocumentKind.INVOICE, (Runnable) () ->
            PortalDocumentQuery.outstandingByCurrency(SESSION) },
        { "overdue", PortalDocumentKind.INVOICE, (Runnable) () ->
            PortalDocumentQuery.overdueByCurrency(SESSION, new Date(0L)) },
    };
    for (Object[] c : cases) {
      built.clear();
      PortalDocumentKind kind = (PortalDocumentKind) c[1];

      ((Runnable) c[2]).run();

      assertEquals(c[0].toString(), 1, built.size());
      Query query = built.keySet().iterator().next();
      String hql = built.get(query);
      for (String scope : Arrays.asList("e.client.id = :clientId",
          "e.businessPartner.id = :bpartnerId", "e.active = true", "e.salesTransaction = true",
          "e.documentStatus in (:statuses)", discriminator(kind))) {
        assertTrue(c[0] + " lacks [" + scope + "]: " + hql, hql.contains(scope));
      }
      verify(query).setParameter("clientId", CLIENT_ID);
      verify(query).setParameter("bpartnerId", BP_ID);
      verify(query).setParameterList("statuses",
          (Collection) new LinkedHashSet<>(PortalDocumentQuery.visibleStatuses(kind)));
    }
  }

  @Test
  public void requestedStatusesAreIntersectedWithTheAllowList() {
    // { kind, requested, bound }
    Object[][] cases = {
        { PortalDocumentKind.INVOICE, Arrays.asList("DR", "CO"), Arrays.asList("CO") },
        { PortalDocumentKind.INVOICE, Arrays.asList("DR"), Arrays.asList("CO", "CL", "VO") },
        { PortalDocumentKind.INVOICE, Arrays.asList("UE"), Arrays.asList("CO", "CL", "VO") },
        { PortalDocumentKind.QUOTATION, Arrays.asList("UE", "DR", "IP"), Arrays.asList("UE") },
    };
    for (Object[] c : cases) {
      built.clear();

      PortalDocumentQuery.count(SESSION, (PortalDocumentKind) c[0],
          new PortalDocumentQuery.Filter((List<String>) c[1], null, null, null));

      Query query = built.keySet().iterator().next();
      verify(query).setParameterList("statuses", (Collection) new LinkedHashSet<>((List) c[2]));
    }
  }

  @Test
  public void searchAndDatesAreBoundAndNeverWrittenIntoTheQuery() {
    Date from = new Date(1_000L);
    Date toExclusive = new Date(2_000L);
    String typed = "  Zz'Top%_\\ or 1=1 ";

    PortalDocumentQuery.list(SESSION, PortalDocumentKind.INVOICE,
        new PortalDocumentQuery.Filter(Arrays.asList("CO'--"), from, toExclusive, typed), 0, 10);

    Query query = built.keySet().iterator().next();
    String hql = built.get(query);
    // Lower-cased, wrapped in %, and % _ \ escaped so they match themselves.
    verify(query).setParameter("search", "%zz'top\\%\\_\\\\ or 1=1%");
    verify(query).setParameter("dateFrom", from);
    verify(query).setParameter("dateTo", toExclusive);
    assertTrue(hql, hql.contains("lower(e.documentNo) like :search"));
    // dateTo arrives as the next day and is compared with <: the day the customer chose is in.
    assertTrue(hql, hql.contains("e.invoiceDate >= :dateFrom"));
    assertTrue(hql, hql.contains("e.invoiceDate < :dateTo"));
    for (String typedPart : Arrays.asList("zz", "Zz", "Top", "1=1", "CO'--")) {
      assertFalse("request input reached the query text: " + hql, hql.contains(typedPart));
    }
  }

  @Test
  public void totalsAreGroupedPerCurrencyAndAShipmentCarriesNoSums() {
    PortalDocumentQuery.totals(SESSION, PortalDocumentKind.INVOICE, none());
    String invoiceTotals = built.values().iterator().next();
    assertTrue(invoiceTotals, invoiceTotals.contains("sum(e.grandTotalAmount)"));
    assertTrue(invoiceTotals, invoiceTotals.endsWith("group by e.currency.iSOCode"));

    Query countQuery = mock(Query.class);
    when(countQuery.uniqueResult()).thenReturn(4L);
    doReturn(countQuery).when(session).createQuery(anyString(), any(Class.class));

    List<Object[]> shipments = PortalDocumentQuery.totals(SESSION, PortalDocumentKind.SHIPMENT,
        none());

    assertEquals(1, shipments.size());
    assertEquals(Arrays.asList(null, 4L, null, null), Arrays.asList(shipments.get(0)));
  }

  @Test
  public void aBlankIdIsNotFoundWithoutQuerying() {
    assertNull(PortalDocumentQuery.findInScope(SESSION, PortalDocumentKind.INVOICE, " "));
    assertNull(PortalDocumentQuery.findInScope(SESSION, PortalDocumentKind.INVOICE, null));
    assertTrue(PortalDocumentQuery.findAllInScope(SESSION, PortalDocumentKind.INVOICE,
        Collections.<String>emptyList()).isEmpty());

    verify(session, never()).createQuery(anyString(), any(Class.class));
  }

  @Test
  public void recentIsTheFiveNewestAcrossKindsNewestFirst() {
    BaseOBObject quotation = document(Order.ENTITY_NAME, Order.PROPERTY_ORDERDATE, 3);
    BaseOBObject order = document(Order.ENTITY_NAME, Order.PROPERTY_ORDERDATE, 7);
    BaseOBObject oldOrder = document(Order.ENTITY_NAME, Order.PROPERTY_ORDERDATE, 1);
    BaseOBObject shipment = document(ShipmentInOut.ENTITY_NAME,
        ShipmentInOut.PROPERTY_MOVEMENTDATE, 5);
    BaseOBObject invoice = document(Invoice.ENTITY_NAME, Invoice.PROPERTY_INVOICEDATE, 9);
    BaseOBObject oldInvoice = document(Invoice.ENTITY_NAME, Invoice.PROPERTY_INVOICEDATE, 2);
    // Each kind's own page, in PortalDocumentKind order.
    List<Query> pages = new ArrayList<>(Arrays.asList(page(quotation), page(order, oldOrder),
        page(shipment), page(invoice, oldInvoice)));
    doAnswer(invocation -> pages.remove(0)).when(session)
        .createQuery(anyString(), any(Class.class));

    List<BaseOBObject> recent = PortalDocumentQuery.recent(SESSION, 5);

    assertEquals(Arrays.asList(invoice, order, shipment, quotation, oldInvoice), recent);
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private static PortalDocumentQuery.Filter none() {
    return PortalDocumentQuery.Filter.none();
  }

  private static String discriminator(PortalDocumentKind kind) {
    switch (kind) {
      case QUOTATION:
        return "e.transactionDocument.sOSubType in ('OB', 'ON')";
      case ORDER:
        return "e.transactionDocument.sOSubType not in ('OB', 'ON', 'RM')";
      case SHIPMENT:
        return "e.movementType = 'C-'";
      default:
        return "e.documentStatus";
    }
  }

  private static Query page(BaseOBObject... documents) {
    Query query = mock(Query.class);
    when(query.list()).thenReturn(Arrays.asList(documents));
    return query;
  }

  private static BaseOBObject document(String entityName, String dateProperty, int day) {
    BaseOBObject document = mock(BaseOBObject.class);
    when(document.getEntityName()).thenReturn(entityName);
    when(document.get(dateProperty)).thenReturn(new Date(day * 86_400_000L));
    return document;
  }
}
