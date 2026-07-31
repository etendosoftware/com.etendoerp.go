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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link RectificativeDocTypeSupport} — the single source of truth for
 * {@code c_doctype.em_etsg_isrectificative} (the "Factura Rectificativa" flag, owned by the
 * optional SIF General module), shared by {@link AbstractInvoiceHeaderHandler} and the
 * ETP-4738 "saldo a favor" filter ({@link PaymentCreditSourcesService},
 * {@link PaymentCreditConsumer}).
 */
class RectificativeDocTypeSupportTest {

  private static final String CLIENT_ID = "client-1";

  @AfterEach
  void tearDown() {
    RectificativeDocTypeSupport.setRectificativeColumnPresentForTests(null);
  }

  // ── isRectificativeColumnPresent / hook sharing ──────────────────────────────

  @Test
  @DisplayName("The test hook is the single shared cache used by AbstractInvoiceHeaderHandler too")
  void columnPresenceHook_isSharedWithAbstractInvoiceHeaderHandler() {
    AbstractInvoiceHeaderHandler.setRectificativeColumnPresentForTests(true);
    assertTrue(RectificativeDocTypeSupport.isRectificativeColumnPresent());

    AbstractInvoiceHeaderHandler.setRectificativeColumnPresentForTests(false);
    assertFalse(RectificativeDocTypeSupport.isRectificativeColumnPresent());
  }

  // ── resolveRectificativeDocTypes ─────────────────────────────────────────────

  @Test
  @DisplayName("Column absent -> unavailable without ever querying OBDal (a failed SELECT on a "
      + "missing column would abort the shared read-only transaction)")
  void resolveDocTypes_columnAbsent_emptyWithoutQuerying() {
    RectificativeDocTypeSupport.setRectificativeColumnPresentForTests(false);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      List<String> ids = RectificativeDocTypeSupport.resolveRectificativeDocTypes(CLIENT_ID, true);

      assertTrue(ids.isEmpty());
      dalMock.verifyNoInteractions();
    }
  }

  @Test
  @DisplayName("Blank clientId -> empty without querying OBDal")
  void resolveDocTypes_blankClient_emptyWithoutQuerying() {
    RectificativeDocTypeSupport.setRectificativeColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      List<String> ids = RectificativeDocTypeSupport.resolveRectificativeDocTypes(null, true);

      assertTrue(ids.isEmpty());
      dalMock.verifyNoInteractions();
    }
  }

  @Test
  @DisplayName("Column present -> returns the flagged doc type ids, scoped by client and sales side")
  void resolveDocTypes_columnPresent_returnsFlaggedIds() throws SQLException {
    RectificativeDocTypeSupport.setRectificativeColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal readOnlyDal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);
      Connection conn = mock(Connection.class);
      when(readOnlyDal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true, true, false);
      when(rs.getString(1)).thenReturn("dt-1", "dt-2");

      List<String> ids = RectificativeDocTypeSupport.resolveRectificativeDocTypes(CLIENT_ID, true);

      assertEquals(Arrays.asList("dt-1", "dt-2"), ids);
      verify(ps).setString(1, CLIENT_ID);
      verify(ps).setString(2, "Y");
    }
  }

  @Test
  @DisplayName("Purchase side (isSalesTransaction=false) binds issotrx = 'N'")
  void resolveDocTypes_purchaseSide_bindsIsSOTrxN() throws SQLException {
    RectificativeDocTypeSupport.setRectificativeColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal readOnlyDal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);
      Connection conn = mock(Connection.class);
      when(readOnlyDal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      RectificativeDocTypeSupport.resolveRectificativeDocTypes(CLIENT_ID, false);

      verify(ps).setString(2, "N");
    }
  }

  @Test
  @DisplayName("No doc type flagged -> available but empty (callers must skip building the HQL "
      + "'in ()' query, not treat this as unrestricted)")
  void resolveDocTypes_noFlaggedDocType_empty() throws SQLException {
    RectificativeDocTypeSupport.setRectificativeColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal readOnlyDal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);
      Connection conn = mock(Connection.class);
      when(readOnlyDal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      List<String> ids = RectificativeDocTypeSupport.resolveRectificativeDocTypes(CLIENT_ID, true);

      assertTrue(ids.isEmpty());
    }
  }

  @Test
  @DisplayName("A SQL failure falls back to empty rather than propagating the exception")
  void resolveDocTypes_sqlFailure_fallsBackToEmpty() throws SQLException {
    RectificativeDocTypeSupport.setRectificativeColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal readOnlyDal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);
      Connection conn = mock(Connection.class);
      when(readOnlyDal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenThrow(new SQLException("boom"));

      List<String> ids = RectificativeDocTypeSupport.resolveRectificativeDocTypes(CLIENT_ID, true);

      assertTrue(ids.isEmpty());
    }
  }

  // ── isRectificativeDocType ────────────────────────────────────────────────────

  @Test
  @DisplayName("isRectificativeDocType reads the flag for a single doc type id")
  void isRectificativeDocType_flagY_true() throws SQLException {
    RectificativeDocTypeSupport.setRectificativeColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal readOnlyDal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);
      Connection conn = mock(Connection.class);
      when(readOnlyDal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getString(1)).thenReturn("Y");

      assertTrue(RectificativeDocTypeSupport.isRectificativeDocType("dt-1"));
    }
  }

  @Test
  @DisplayName("isRectificativeDocType is false for a blank id, without querying OBDal")
  void isRectificativeDocType_blankId_falseWithoutQuerying() {
    RectificativeDocTypeSupport.setRectificativeColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      assertFalse(RectificativeDocTypeSupport.isRectificativeDocType(" "));
      dalMock.verifyNoInteractions();
    }
  }

  @Test
  @DisplayName("isRectificativeDocType is false when the column is absent, without querying")
  void isRectificativeDocType_columnAbsent_falseWithoutQuerying() {
    RectificativeDocTypeSupport.setRectificativeColumnPresentForTests(false);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      assertFalse(RectificativeDocTypeSupport.isRectificativeDocType("dt-1"));
      dalMock.verifyNoInteractions();
    }
  }
}
