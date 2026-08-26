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
package com.etendoerp.go.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link NeoCurrencySymbolPositions} — the source of the
 * {@code symbolRightSide} map served by {@code GET /sws/neo/currency-format}
 * (ETP-4314 follow-up). Only EUR ships {@code Y} in the real reference data; this
 * suite pins that mapping (`Y` → true, `N` → false) without depending on a live DB.
 */
public class NeoCurrencySymbolPositionsTest {

  @SuppressWarnings("unchecked")
  @Test
  public void fetchAllMapsYToTrueAndNToFalse() {
    List<Object[]> rows = Arrays.asList(
        new Object[]{ "EUR", "Y" },
        new Object[]{ "USD", "N" },
        new Object[]{ "GBP", "N" });

    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    NativeQuery<Object[]> query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn((NativeQuery) query);
    when(query.list()).thenReturn(rows);
    when(obDal.getSession()).thenReturn(session);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      Map<String, Boolean> result = NeoCurrencySymbolPositions.fetchAll();

      assertEquals(3, result.size());
      assertTrue(result.get("EUR"));
      assertFalse(result.get("USD"));
      assertFalse(result.get("GBP"));
      obContextMock.verify(OBContext::setAdminMode);
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void fetchAllMapsYToTrueWhenJdbcReturnsCharacterInsteadOfString() {
    // Regression: verified live against a running instance that CHAR(1)
    // ISSYMBOLRIGHTSIDE can come back as a Character, not a String, for an
    // unaliased native-query column — "Y".equals(Character) is always false,
    // which silently made every currency (EUR included) resolve to false.
    List<Object[]> rows = Arrays.asList(
        new Object[]{ "EUR", Character.valueOf('Y') },
        new Object[]{ "USD", Character.valueOf('N') });

    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    NativeQuery<Object[]> query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn((NativeQuery) query);
    when(query.list()).thenReturn(rows);
    when(obDal.getSession()).thenReturn(session);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      Map<String, Boolean> result = NeoCurrencySymbolPositions.fetchAll();

      assertTrue(result.get("EUR"));
      assertFalse(result.get("USD"));
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void fetchAllReturnsEmptyMapWhenNoActiveCurrencies() {
    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    NativeQuery<Object[]> query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn((NativeQuery) query);
    when(query.list()).thenReturn(Collections.emptyList());
    when(obDal.getSession()).thenReturn(session);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      Map<String, Boolean> result = NeoCurrencySymbolPositions.fetchAll();

      assertTrue(result.isEmpty());
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void fetchAllRestoresContextEvenWhenQueryThrows() {
    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(session.createNativeQuery(anyString())).thenThrow(new RuntimeException("boom"));
    when(obDal.getSession()).thenReturn(session);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      try {
        NeoCurrencySymbolPositions.fetchAll();
      } catch (RuntimeException expected) {
        // propagates to the caller (NeoCurrencyFormatServlet), which fails soft
      }

      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }
}
