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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link PortalDocumentLinks}: which data links each kind follows, and that every
 * candidate is re-read through {@link PortalDocumentQuery#findAllInScope} so nothing outside the
 * link's scope is ever returned.
 *
 * @covers com.etendoerp.go.portal.PortalDocumentLinks
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class PortalDocumentLinksTest {

  private static final String DOC_ID = "DOC-1";
  private static final PortalSession SESSION = new PortalSession(new PortalLink("LINK-1",
      "CLIENT-1", "Acme", "ORG-1", "BP-1", "Customer SL", PortalDocumentKind.INVOICE, DOC_ID,
      PortalLink.ORIGIN_SEND, null, "LINK-1", "sales-invoice-send", "a@customer.test", null,
      "es_ES", new Date(0L), new Date(1L), true, false));

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<PortalDocumentQuery> scope;
  /** What each candidate query answers, by a fragment of its HQL. */
  private final Map<String, List<String>> answers = new HashMap<>();
  private final List<String> hqls = new ArrayList<>();
  private final Query query = mock(Query.class);

  @Before
  public void setUp() {
    OBDal obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    Session session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    // One query object: list() answers for the HQL most recently built.
    doAnswer(invocation -> {
      hqls.add(invocation.getArgument(0));
      return query;
    }).when(session).createQuery(anyString(), eq(String.class));
    when(query.list()).thenAnswer(invocation -> {
      String hql = hqls.get(hqls.size() - 1);
      for (Map.Entry<String, List<String>> answer : answers.entrySet()) {
        if (hql.contains(answer.getKey())) {
          return answer.getValue();
        }
      }
      return Collections.emptyList();
    });
    scope = mockStatic(PortalDocumentQuery.class);
  }

  @After
  public void tearDown() {
    scope.close();
    obDalStatic.close();
  }

  @Test
  public void eachKindFollowsTheAppsLinksBoundToTheDocument() {
    // { kind, a fragment of every candidate query it must run }
    Object[][] cases = {
        { PortalDocumentKind.QUOTATION, Arrays.asList("o.quotation.id = :id",
            "from Invoice i where i.salesOrder.id = :id", "s.salesOrder.id = :id") },
        { PortalDocumentKind.ORDER, Arrays.asList("select o.quotation.id from Order o",
            "from MaterialMgmtShipmentInOut s where s.salesOrder.id", "l.shipmentReceipt.id from",
            "from Invoice i where i.salesOrder.id", "l.invoice.id from InvoiceLine") },
        { PortalDocumentKind.SHIPMENT, Arrays.asList("s.salesOrder.id from MaterialMgmt",
            "l.salesOrderLine.salesOrder.id from MaterialMgmtShipmentInOutLine",
            "l.goodsShipmentLine.shipmentReceipt.id = :id") },
        { PortalDocumentKind.INVOICE, Arrays.asList("i.salesOrder.id from Invoice i",
            "l.salesOrderLine.salesOrder.id from InvoiceLine",
            "l.goodsShipmentLine.shipmentReceipt.id from InvoiceLine",
            "r.reversedInvoice.id from ReversedInvoices", "r.invoice.id from ReversedInvoices") },
    };
    for (Object[] c : cases) {
      hqls.clear();
      List<String> expected = (List<String>) c[1];

      PortalDocumentLinks.related(SESSION, (PortalDocumentKind) c[0], DOC_ID);

      assertEquals(c[0] + ": " + hqls, expected.size(), hqls.size());
      for (String fragment : expected) {
        assertTrue(c[0] + " lacks " + fragment + ": " + hqls,
            hqls.stream().anyMatch(hql -> hql.contains(fragment)));
      }
    }
    verify(query, times(16)).setParameter("id", DOC_ID);
  }

  @Test
  public void onlyCandidatesInScopeAreReturnedAndNeverTheDocumentItself() {
    // A credit note: its rectified invoice is in scope; the reverse link also names the document
    // itself, and another invoice that belongs to another Business Partner.
    answers.put("r.reversedInvoice.id from ReversedInvoices", Arrays.asList("INV-0"));
    answers.put("r.invoice.id from ReversedInvoices", Arrays.asList(DOC_ID, "OTHER-BP-INV"));
    BaseOBObject rectified = mock(BaseOBObject.class);
    scope.when(() -> PortalDocumentQuery.findAllInScope(any(), eq(PortalDocumentKind.INVOICE),
        any())).thenReturn(Collections.singletonList(rectified));

    List<BaseOBObject> related = PortalDocumentLinks.related(SESSION, PortalDocumentKind.INVOICE,
        DOC_ID);

    assertEquals(Collections.singletonList(rectified), related);
    ArgumentCaptor<Collection> ids = ArgumentCaptor.forClass(Collection.class);
    scope.verify(() -> PortalDocumentQuery.findAllInScope(eq(SESSION),
        eq(PortalDocumentKind.INVOICE), ids.capture()));
    assertEquals(new LinkedHashSet<>(Arrays.asList("INV-0", "OTHER-BP-INV")),
        new LinkedHashSet<>(ids.getValue()));
  }

  @Test
  public void orderCandidatesAreAlsoTriedAsQuotations() {
    // An invoice made straight from a quotation points its C_Order_ID at the quotation.
    answers.put("i.salesOrder.id from Invoice i", Arrays.asList("ORD-OR-QUOTE"));

    PortalDocumentLinks.related(SESSION, PortalDocumentKind.INVOICE, DOC_ID);

    for (PortalDocumentKind kind : Arrays.asList(PortalDocumentKind.ORDER,
        PortalDocumentKind.QUOTATION)) {
      ArgumentCaptor<Collection> ids = ArgumentCaptor.forClass(Collection.class);
      scope.verify(() -> PortalDocumentQuery.findAllInScope(eq(SESSION), eq(kind),
          ids.capture()));
      assertEquals(kind.name(), Collections.singletonList("ORD-OR-QUOTE"),
          new ArrayList<>(ids.getValue()));
    }
    // No candidate shipment or invoice was found, so none is looked up.
    scope.verify(() -> PortalDocumentQuery.findAllInScope(any(), eq(PortalDocumentKind.SHIPMENT),
        any()), never());
  }
}
