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

import static org.junit.Assert.assertEquals;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReturnShipmentUtils#resolvePriceFromSourceInvoiceLine}.
 *
 * <p>Covers:
 * <ul>
 *   <li>null shipmentLineId → returns fallback without touching OBDal.</li>
 *   <li>Session returns a row with price &gt; 0 → returns that price.</li>
 *   <li>Session returns a row with null price → returns fallback.</li>
 *   <li>Session returns an empty list → returns fallback (logs warn).</li>
 *   <li>Session.createQuery throws → returns fallback (logs warn, no rethrow).</li>
 * </ul>
 */
public class ReturnShipmentUtilsTest {

  private static final BigDecimal FALLBACK = new BigDecimal("9.99");

  // ── Case 1: null shipmentLineId → fallback, OBDal never called ──────────────

  @Test
  public void resolvePriceFromSourceInvoiceLine_nullId_returnsFallbackWithoutCallingOBDal() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          null, "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
      // OBDal.getInstance() must not be touched when shipmentLineId is null
      verify(dal, never()).getSession();
    }
  }

  // ── Case 2: session returns a row with price > 0 → returns that price ───────

  @Test
  @SuppressWarnings("unchecked")
  public void resolvePriceFromSourceInvoiceLine_rowWithPositivePrice_returnsThatPrice()
      throws Exception {
    BigDecimal expectedPrice = new BigDecimal("42.50");

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<BigDecimal> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(expectedPrice));

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-001", "unitPrice", FALLBACK);

      assertEquals(expectedPrice, result);
    }
  }

  // ── Case 3: session returns a row with null price → fallback ────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void resolvePriceFromSourceInvoiceLine_rowWithNullPrice_returnsFallback()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<BigDecimal> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      // The list has one element but it is null
      when(query.list()).thenReturn(Collections.singletonList(null));

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-002", "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
    }
  }

  // ── Case 4: session returns empty list → fallback (log warn) ────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void resolvePriceFromSourceInvoiceLine_emptyList_returnsFallback() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<BigDecimal> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      when(query.list()).thenReturn(Collections.<BigDecimal>emptyList());

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-003", "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
    }
  }

  // ── Case 5: session throws → fallback (log warn, no rethrow) ────────────────

  @Test
  public void resolvePriceFromSourceInvoiceLine_sessionThrows_returnsFallbackWithoutRethrow() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      when(session.createQuery(anyString(), eq(BigDecimal.class)))
          .thenThrow(new RuntimeException("HibernateException: session closed"));

      // Must not throw — exception is swallowed and fallback is returned
      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-004", "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
    }
  }

  // ── Edge: row with price = 0 → treated as "no price" → fallback ─────────────

  @Test
  @SuppressWarnings("unchecked")
  public void resolvePriceFromSourceInvoiceLine_rowWithZeroPrice_returnsFallback()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<BigDecimal> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(BigDecimal.ZERO));

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-005", "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
    }
  }
}
