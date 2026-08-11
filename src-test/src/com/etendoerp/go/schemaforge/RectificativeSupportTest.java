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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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

import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;

/**
 * Unit tests for {@link RectificativeSupport} (ETP-4737, plus the ETP-4738 "saldo a favor"
 * additions: {@link RectificativeSupport#isRectificativeDocType(String)} and
 * {@link RectificativeSupport#resolveRectificativeDocTypes}).
 *
 * <p>Covers {@link RectificativeSupport#isRectificative(DocumentType)}, using the
 * {@link RectificativeSupport#setColumnPresentForTests(Boolean)} hook to avoid depending on a
 * real database connection for the column-presence check.
 */
public class RectificativeSupportTest {

  private static final String CLIENT_ID = "client-1";

  @After
  public void resetColumnPresentCache() {
    RectificativeSupport.setColumnPresentForTests(null);
  }

  @Test
  public void isRectificative_nullDocType_returnsFalse() {
    RectificativeSupport.setColumnPresentForTests(true);
    assertFalse(RectificativeSupport.isRectificative(null));
  }

  @Test
  public void isRectificative_columnAbsent_returnsFalseWithoutTouchingDocType() {
    RectificativeSupport.setColumnPresentForTests(false);
    DocumentType dt = mock(DocumentType.class);

    assertFalse(RectificativeSupport.isRectificative(dt));
  }

  @Test
  public void isRectificative_columnPresentAndFlagTrue_returnsTrue() {
    RectificativeSupport.setColumnPresentForTests(true);
    DocumentType dt = mock(DocumentType.class);
    when(dt.isEtsgIsRectificative()).thenReturn(true);

    assertTrue(RectificativeSupport.isRectificative(dt));
  }

  @Test
  public void isRectificative_columnPresentAndFlagFalse_returnsFalse() {
    RectificativeSupport.setColumnPresentForTests(true);
    DocumentType dt = mock(DocumentType.class);
    when(dt.isEtsgIsRectificative()).thenReturn(false);

    assertFalse(RectificativeSupport.isRectificative(dt));
  }

  @Test
  public void isRectificative_columnPresentAndFlagNull_returnsFalse() {
    RectificativeSupport.setColumnPresentForTests(true);
    DocumentType dt = mock(DocumentType.class);
    when(dt.isEtsgIsRectificative()).thenReturn(null);

    assertFalse(RectificativeSupport.isRectificative(dt));
  }

  // ── isRectificativeDocType(String docTypeId) — id-based lookup (ETP-4738) ───

  @Test
  public void isRectificativeDocType_blankId_falseWithoutQuerying() {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      assertFalse(RectificativeSupport.isRectificativeDocType(" "));
      dalMock.verifyNoInteractions();
    }
  }

  @Test
  public void isRectificativeDocType_columnAbsent_falseWithoutQuerying() {
    RectificativeSupport.setColumnPresentForTests(false);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      assertFalse(RectificativeSupport.isRectificativeDocType("dt-1"));
      dalMock.verifyNoInteractions();
    }
  }

  @Test
  public void isRectificativeDocType_columnPresent_delegatesToEntityLookup() {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      DocumentType dt = mock(DocumentType.class);
      when(dal.get(DocumentType.class, "dt-1")).thenReturn(dt);
      when(dt.isEtsgIsRectificative()).thenReturn(true);

      assertTrue(RectificativeSupport.isRectificativeDocType("dt-1"));
    }
  }

  // ── resolveRectificativeDocTypes (ETP-4738 "saldo a favor" filter) ───────────

  @Test
  public void resolveDocTypes_columnAbsent_emptyWithoutQuerying() {
    RectificativeSupport.setColumnPresentForTests(false);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      List<String> ids = RectificativeSupport.resolveRectificativeDocTypes(CLIENT_ID, true);

      assertTrue(ids.isEmpty());
      dalMock.verifyNoInteractions();
    }
  }

  @Test
  public void resolveDocTypes_blankClient_emptyWithoutQuerying() {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      List<String> ids = RectificativeSupport.resolveRectificativeDocTypes(null, true);

      assertTrue(ids.isEmpty());
      dalMock.verifyNoInteractions();
    }
  }

  @Test
  public void resolveDocTypes_columnPresent_returnsFlaggedIds() throws SQLException {
    RectificativeSupport.setColumnPresentForTests(true);
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

      List<String> ids = RectificativeSupport.resolveRectificativeDocTypes(CLIENT_ID, true);

      assertEquals(Arrays.asList("dt-1", "dt-2"), ids);
      verify(ps).setString(1, CLIENT_ID);
      verify(ps).setString(2, "Y");
    }
  }

  @Test
  public void resolveDocTypes_purchaseSide_bindsIsSOTrxN() throws SQLException {
    RectificativeSupport.setColumnPresentForTests(true);
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

      RectificativeSupport.resolveRectificativeDocTypes(CLIENT_ID, false);

      verify(ps).setString(2, "N");
    }
  }

  @Test
  public void resolveDocTypes_noFlaggedDocType_empty() throws SQLException {
    RectificativeSupport.setColumnPresentForTests(true);
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

      List<String> ids = RectificativeSupport.resolveRectificativeDocTypes(CLIENT_ID, true);

      assertTrue(ids.isEmpty());
    }
  }

  @Test
  public void resolveDocTypes_sqlFailure_fallsBackToEmpty() throws SQLException {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal readOnlyDal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);
      Connection conn = mock(Connection.class);
      when(readOnlyDal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenThrow(new SQLException("boom"));

      List<String> ids = RectificativeSupport.resolveRectificativeDocTypes(CLIENT_ID, true);

      assertTrue(ids.isEmpty());
    }
  }
}
