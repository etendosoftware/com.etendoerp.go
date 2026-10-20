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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

  /** The default query's result: one column, {@code values} in order. */
  private static ResultSet rowsOf(List<String> values) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    int[] row = { -1 };
    when(rs.next()).thenAnswer(inv -> ++row[0] < values.size());
    when(rs.getString(1)).thenAnswer(inv -> values.get(row[0]));
    return rs;
  }

  /**
   * An OBDal whose id → AD_Client_ID lookup answers from {@code owners}, for whatever ids each
   * lookup binds; ids missing from the map are not found. Counts the lookups in {@code calls}.
   */
  private static OBDal lookupDal(Map<String, String> owners, int[] calls) throws SQLException {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenAnswer(prep -> {
      calls[0]++;
      List<String> bound = new ArrayList<>();
      PreparedStatement ps = mock(PreparedStatement.class);
      doAnswer(set -> {
        bound.add(set.getArgument(1));
        return null;
      }).when(ps).setString(anyInt(), anyString());
      when(ps.executeQuery()).thenAnswer(exec -> {
        List<String> found = new ArrayList<>();
        for (String id : bound) {
          if (owners.containsKey(id)) {
            found.add(id);
          }
        }
        ResultSet rs = mock(ResultSet.class);
        int[] row = { -1 };
        when(rs.next()).thenAnswer(inv -> ++row[0] < found.size());
        when(rs.getString(1)).thenAnswer(inv -> found.get(row[0]));
        when(rs.getString(2)).thenAnswer(inv -> owners.get(found.get(row[0])));
        return rs;
      });
      return ps;
    });
    return dal;
  }

  /** Scans {@code candidates} as the default's rows; {@code owners} alternates id, client. */
  private static String pick(NeoDefaultTenantGuard guard, List<String> candidates,
      String... owners) throws SQLException {
    Map<String, String> ownerById = new HashMap<>();
    for (int k = 0; k < owners.length; k += 2) {
      ownerById.put(owners[k], owners[k + 1]);
    }
    OBDal dal = lookupDal(ownerById, new int[1]);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      return guard.scan(rowsOf(candidates)).value;
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

  // ── scan ──────────────────────────────────────────────────────────

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
      assertEquals("DT-FIRST", guard.scan(rowsOf(Arrays.asList("DT-FIRST", "DT-SECOND"))).value);
    }
  }

  @Test
  public void reportsNoRowsForAnEmptyResultWithoutQuerying() throws SQLException {
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      NeoDefaultTenantGuard.Scan scan = guard.scan(rowsOf(Collections.emptyList()));
      assertFalse(scan.anyRow);
      assertNull(scan.value);
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
      guard.scan(rowsOf(Arrays.asList("DT-1", "DT-2")));
    }
    verify(ps).setString(1, "DT-1");
    verify(ps).setString(2, "DT-2");
  }

  @Test
  public void findsTheOwnRowBeyondTheFirstBatch() throws SQLException {
    // An unscoped default over many tenants: the own row comes after a full batch of foreign ones.
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    List<String> candidates = new ArrayList<>();
    Map<String, String> owners = new HashMap<>();
    for (int n = 0; n < NeoDefaultTenantGuard.BATCH_SIZE + 50; n++) {
      candidates.add("DT-FOREIGN-" + n);
      owners.put("DT-FOREIGN-" + n, FOREIGN_CLIENT);
    }
    candidates.add("DT-OWN");
    owners.put("DT-OWN", OWN_CLIENT);
    int[] lookups = new int[1];
    OBDal dal = lookupDal(owners, lookups);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      assertEquals("DT-OWN", guard.scan(rowsOf(candidates)).value);
    }
    assertEquals(2, lookups[0]);
  }

  @Test
  public void stopsAtTheFirstBatchWithAnAcceptableRow() throws SQLException {
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    List<String> candidates = new ArrayList<>();
    Map<String, String> owners = new HashMap<>();
    for (int n = 0; n < 3 * NeoDefaultTenantGuard.BATCH_SIZE; n++) {
      candidates.add("DT-" + n);
      owners.put("DT-" + n, n == 1 ? OWN_CLIENT : FOREIGN_CLIENT);
    }
    int[] lookups = new int[1];
    OBDal dal = lookupDal(owners, lookups);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      assertEquals("DT-1", guard.scan(rowsOf(candidates)).value);
    }
    assertEquals(1, lookups[0]);
  }

  @Test
  public void fallsBackToTheVeryFirstRowWhenALaterLookupFails() throws SQLException {
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    List<String> candidates = new ArrayList<>();
    for (int n = 0; n < NeoDefaultTenantGuard.BATCH_SIZE + 1; n++) {
      candidates.add("DT-" + n);
    }
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement firstLookup = mock(PreparedStatement.class);
    ResultSet allForeign = mock(ResultSet.class);
    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(firstLookup)
        .thenThrow(new SQLException("boom"));
    // First batch: every id is found and foreign.
    int[] row = { -1 };
    when(firstLookup.executeQuery()).thenReturn(allForeign);
    when(allForeign.next()).thenAnswer(inv -> ++row[0] < NeoDefaultTenantGuard.BATCH_SIZE);
    when(allForeign.getString(1)).thenAnswer(inv -> "DT-" + row[0]);
    when(allForeign.getString(2)).thenReturn(FOREIGN_CLIENT);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      NeoDefaultTenantGuard.Scan scan = guard.scan(rowsOf(candidates));
      assertTrue(scan.anyRow);
      assertEquals("DT-0", scan.value);
    }
  }

  @Test
  public void neverChoosesANullRowAndSkipsTheLookupForAllNullBatches() throws SQLException {
    NeoDefaultTenantGuard guard = new FkFixture().guard(OWN_CLIENT);
    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      NeoDefaultTenantGuard.Scan scan = guard.scan(rowsOf(Arrays.asList(null, null)));
      assertTrue(scan.anyRow);
      assertNull(scan.value);
      verifyNoInteractions(dal);
    }
  }
}
