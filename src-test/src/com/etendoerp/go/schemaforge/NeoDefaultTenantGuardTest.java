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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;

/**
 * Unit tests for {@link NeoDefaultTenantGuard}: an {@code @SQL=} foreign-key default never
 * resolves to another tenant's row (ETP-5682).
 *
 * @covers com.etendoerp.go.schemaforge.NeoDefaultTenantGuard
 */
public class NeoDefaultTenantGuardTest {

  private static final String OWN_CLIENT = "OWN-CLIENT";
  private static final String FOREIGN_CLIENT = "FOREIGN-CLIENT";
  private static final String TABLE_ID = "224";
  private static final String DOC_TYPE_COLUMN = "C_DocType_ID";

  /** A GL_Journal.C_DocType_ID-shaped column: FK to a client-enabled table with one id. */
  private static final class FkFixture {
    final Column column = mock(Column.class);
    final Entity entity = mock(Entity.class);
    final Entity target = mock(Entity.class);
    final Property property = mock(Property.class);
    final Property idProperty = mock(Property.class);
    final ModelProvider modelProvider = mock(ModelProvider.class);

    FkFixture() {
      Table table = mock(Table.class);
      when(table.getId()).thenReturn(TABLE_ID);
      when(column.getTable()).thenReturn(table);
      when(column.getDBColumnName()).thenReturn(DOC_TYPE_COLUMN);
      when(modelProvider.getEntityByTableId(TABLE_ID)).thenReturn(entity);
      when(entity.getPropertyByColumnName(DOC_TYPE_COLUMN, false)).thenReturn(property);
      when(property.isPrimitive()).thenReturn(false);
      when(property.getTargetEntity()).thenReturn(target);
      when(target.isClientEnabled()).thenReturn(true);
      when(target.getTableName()).thenReturn("C_DocType");
      when(target.getIdProperties()).thenReturn(Collections.singletonList(idProperty));
      when(idProperty.getColumnName()).thenReturn(DOC_TYPE_COLUMN);
    }

    NeoDefaultTenantGuard guard(String clientId) {
      try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class)) {
        mp.when(ModelProvider::getInstance).thenReturn(modelProvider);
        return NeoDefaultTenantGuard.forColumn(column, clientId);
      }
    }
  }

  /** Mocks the id → AD_Client_ID lookup; {@code rows} alternates id, client. */
  private static String pick(NeoDefaultTenantGuard guard, List<String> candidates,
      String... rows) throws SQLException {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    int[] row = { -1 };
    when(rs.next()).thenAnswer(inv -> ++row[0] < rows.length / 2);
    when(rs.getString(1)).thenAnswer(inv -> rows[2 * row[0]]);
    when(rs.getString(2)).thenAnswer(inv -> rows[2 * row[0] + 1]);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      return guard.firstSameTenant(candidates);
    }
  }

  // ── forColumn ────────────────────────────────────────────────────────────────

  @Test
  public void forColumnAppliesToClientScopedForeignKey() {
    assertNotNull(new FkFixture().guard(OWN_CLIENT));
  }

  @Test
  public void forColumnDoesNotApplyWithoutColumnOrClient() {
    FkFixture fx = new FkFixture();
    assertNull(NeoDefaultTenantGuard.forColumn(null, OWN_CLIENT));
    assertNull(fx.guard(null));
    assertNull(fx.guard(""));
  }

  @Test
  public void forColumnDoesNotApplyToSystemContext() {
    assertNull(new FkFixture().guard(NeoDefaultTenantGuard.SYSTEM_CLIENT_ID));
  }

  @Test
  public void forColumnDoesNotApplyToPrimitiveColumn() {
    FkFixture fx = new FkFixture();
    when(fx.property.isPrimitive()).thenReturn(true);
    assertNull(fx.guard(OWN_CLIENT));
  }

  @Test
  public void forColumnDoesNotApplyToTargetWithoutClient() {
    FkFixture fx = new FkFixture();
    when(fx.target.isClientEnabled()).thenReturn(false);
    assertNull(fx.guard(OWN_CLIENT));
  }

  @Test
  public void forColumnDoesNotApplyWhenReferencingNonIdProperty() {
    FkFixture fx = new FkFixture();
    Property referenced = mock(Property.class);
    when(referenced.isId()).thenReturn(false);
    when(fx.property.getReferencedProperty()).thenReturn(referenced);
    assertNull(fx.guard(OWN_CLIENT));
  }

  @Test
  public void forColumnDoesNotApplyWhenColumnHasNoTable() {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn(DOC_TYPE_COLUMN);
    assertNull(NeoDefaultTenantGuard.forColumn(column, OWN_CLIENT));
  }

  // ── firstSameTenant ──────────────────────────────────────────────────────────

  @Test
  public void skipsForeignRowsAndKeepsTheFirstOwnRow() throws SQLException {
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    String chosen = pick(guard, Arrays.asList("DT-FOREIGN", "DT-OWN", "DT-OWN-2"),
        "DT-FOREIGN", FOREIGN_CLIENT, "DT-OWN", OWN_CLIENT, "DT-OWN-2", OWN_CLIENT);
    assertEquals("DT-OWN", chosen);
  }

  @Test
  public void keepsTheFirstRowWhenItIsOwn() throws SQLException {
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    String chosen = pick(guard, Arrays.asList("DT-OWN", "DT-FOREIGN"),
        "DT-OWN", OWN_CLIENT, "DT-FOREIGN", FOREIGN_CLIENT);
    assertEquals("DT-OWN", chosen);
  }

  @Test
  public void acceptsSystemClientRows() throws SQLException {
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    String chosen = pick(guard, Arrays.asList("DT-FOREIGN", "DT-SYSTEM"),
        "DT-FOREIGN", FOREIGN_CLIENT, "DT-SYSTEM", NeoDefaultTenantGuard.SYSTEM_CLIENT_ID);
    assertEquals("DT-SYSTEM", chosen);
  }

  @Test
  public void returnsNullWhenEveryRowIsForeign() throws SQLException {
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    String chosen = pick(guard, Arrays.asList("DT-A", "DT-B"),
        "DT-A", FOREIGN_CLIENT, "DT-B", "ANOTHER-CLIENT");
    assertNull(chosen);
  }

  @Test
  public void acceptsACandidateItCannotFind() throws SQLException {
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    String chosen = pick(guard, Collections.singletonList("NOT-A-PK"));
    assertEquals("NOT-A-PK", chosen);
  }

  @Test
  public void fallsBackToTheFirstRowWhenTheLookupFails() throws SQLException {
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenThrow(new SQLException("boom"));
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      assertEquals("DT-FIRST", guard.firstSameTenant(Arrays.asList("DT-FIRST", "DT-SECOND")));
    }
  }

  @Test
  public void returnsNullForNoCandidatesWithoutQuerying() {
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      assertNull(guard.firstSameTenant(Collections.emptyList()));
      verifyNoInteractions(dal);
    }
  }

  @Test
  public void bindsEveryCandidateIntoTheLookup() throws SQLException {
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(
        "SELECT C_DocType_ID, AD_Client_ID FROM C_DocType WHERE C_DocType_ID IN (?,?)"))
        .thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      guard.firstSameTenant(Arrays.asList("DT-1", "DT-2"));
    }
    verify(ps).setString(1, "DT-1");
    verify(ps).setString(2, "DT-2");
  }
}
