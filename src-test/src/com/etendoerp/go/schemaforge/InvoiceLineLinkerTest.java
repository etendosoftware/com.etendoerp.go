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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link InvoiceLineLinker}.
 *
 * <p>Covers both link methods plus the null-safety fallback used when the
 * current {@link OBContext} has no user (background processes / system init).
 */
public class InvoiceLineLinkerTest {

  private static final String SYSTEM_USER_ID = "0";

  // ── linkPendingInvoiceLinesToInout ──────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void testLinkPendingInvoiceLinesToInoutUsesCurrentUserWhenAvailable() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery query = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.executeUpdate()).thenReturn(1);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-42");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      ShipmentInOutLine inoutLine = mock(ShipmentInOutLine.class);
      when(inoutLine.getId()).thenReturn("iol-1");

      InvoiceLineLinker.linkPendingInvoiceLinesToInout(inoutLine, "ol-1");

      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session).createNativeQuery(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue(sql.contains("UPDATE C_InvoiceLine"));
      assertTrue(sql.contains("M_InOutLine_ID IS NULL"));

      verify(query).setParameter(eq("inoutLineId"), eq("iol-1"));
      verify(query).setParameter(eq("orderLineId"), eq("ol-1"));
      verify(query).setParameter(eq("userId"), eq("user-42"));
      verify(query).executeUpdate();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testLinkPendingInvoiceLinesToInoutFallsBackToSystemUserWhenContextHasNoUser() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery query = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.executeUpdate()).thenReturn(0);

      // Context exists but has no user (e.g. background process before login).
      OBContext ctx = mock(OBContext.class);
      when(ctx.getUser()).thenReturn(null);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      ShipmentInOutLine inoutLine = mock(ShipmentInOutLine.class);
      when(inoutLine.getId()).thenReturn("iol-2");

      InvoiceLineLinker.linkPendingInvoiceLinesToInout(inoutLine, "ol-2");

      verify(query).setParameter(eq("userId"), eq(SYSTEM_USER_ID));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testLinkPendingInvoiceLinesToInoutFallsBackToSystemUserWhenContextIsNull() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery query = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.executeUpdate()).thenReturn(0);

      // No context at all.
      obContextMock.when(OBContext::getOBContext).thenReturn(null);

      ShipmentInOutLine inoutLine = mock(ShipmentInOutLine.class);
      when(inoutLine.getId()).thenReturn("iol-3");

      InvoiceLineLinker.linkPendingInvoiceLinesToInout(inoutLine, "ol-3");

      verify(query).setParameter(eq("userId"), eq(SYSTEM_USER_ID));
    }
  }

  // ── linkInvoiceLinesToExistingInouts ────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void testLinkInvoiceLinesToExistingInoutsRunsBulkUpdate() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery query = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.executeUpdate()).thenReturn(1);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-99");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      InvoiceLineLinker.linkInvoiceLinesToExistingInouts("invoice-bulk");

      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session).createNativeQuery(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue(sql.contains("UPDATE C_InvoiceLine"));
      assertTrue(sql.contains("MAX(iol.M_InOutLine_ID)"));
      assertTrue(sql.contains("il.C_Invoice_ID = :invoiceId"));
      assertTrue(sql.contains("M_InOutLine_ID IS NULL"));
      assertTrue(sql.contains("C_OrderLine_ID IS NOT NULL"));

      verify(query).setParameter(eq("invoiceId"), eq("invoice-bulk"));
      verify(query).setParameter(eq("userId"), eq("user-99"));
      verify(query).executeUpdate();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testLinkInvoiceLinesToExistingInoutsFallsBackToSystemUserWhenContextHasNoUser() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery query = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.executeUpdate()).thenReturn(0);

      OBContext ctx = mock(OBContext.class);
      when(ctx.getUser()).thenReturn(null);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      InvoiceLineLinker.linkInvoiceLinesToExistingInouts("invoice-no-user");

      verify(query).setParameter(eq("userId"), eq(SYSTEM_USER_ID));
    }
  }
}
