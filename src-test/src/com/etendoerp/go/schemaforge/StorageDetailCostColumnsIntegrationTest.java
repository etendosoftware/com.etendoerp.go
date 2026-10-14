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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.test.base.OBBaseTest;

/**
 * DB-backed proof of the stored computed columns {@code M_Storage_Detail.EM_Etgo_Cost} and
 * {@code M_Storage_Detail.EM_Etgo_Valuation} (the persisted columns that
 * {@link BinContentsHandler} reads): the two SQL functions {@code etgo_storage_detail_cost} /
 * {@code etgo_storage_detail_valuation}, the engine wiring that keeps the columns fresh, and the
 * shape of their {@code AD_COLUMN} configuration.
 *
 * <p><strong>Why plain JDBC and committed steps.</strong> Both columns are
 * {@code Refresh_Mode = 'S'}: the engine recomputes them in a {@code DEFERRABLE INITIALLY DEFERRED}
 * constraint trigger that only fires at COMMIT and carries a transaction-level recursion guard.
 * A rolled-back DAL session therefore never observes a refresh, and two source writes in one
 * transaction would be refreshed once. Every step that must be observed here runs on its own
 * auto-commit connection, so each statement is its own transaction and the refresh has really
 * happened when the next query runs.</p>
 *
 * <p><strong>Committed fixtures are cleaned explicitly.</strong> Every {@code m_costing} row and
 * every cloned {@code m_storage_detail} row this class creates is tracked and deleted in
 * {@link #cleanUp()} (costing rows first, so the engine restores the previous stored values).
 * The engine scenarios use a product that has stock rows and no costing row at all, so deleting
 * the fixtures returns every storage row to {@code NULL}.</p>
 *
 * @covers com.etendoerp.go.schemaforge.BinContentsHandler
 */
public class StorageDetailCostColumnsIntegrationTest extends OBBaseTest {

  private static final String COST_COLUMN_ID = "0629983CB2A04B5ABE6666FD67642A89";
  private static final String VALUATION_COLUMN_ID = "3C477C6C7A824F43B699B53EE60DEDC8";
  private static final String M_COSTING_TABLE_ID = "800043";
  private static final String M_STORAGE_DETAIL_TABLE_ID = "800036";

  private Connection connection;
  private final List<String> costingIds = new ArrayList<>();
  private final List<String> storageIds = new ArrayList<>();

  /** A product with stock rows and no costing row: etgo_product_cost() is NULL for it. */
  private String noCostProduct;
  /** A product with stock rows and a cost: etgo_product_cost() is not NULL for it. */
  private String costProduct;
  private String currencyId;

  @Before
  public void openConnection() throws Exception {
    Properties props = OBPropertiesProvider.getInstance().getOpenbravoProperties();
    connection = DriverManager.getConnection(
        props.getProperty("bbdd.url") + "/" + props.getProperty("bbdd.sid"),
        props.getProperty("bbdd.user"), props.getProperty("bbdd.password"));
    connection.setAutoCommit(true);

    noCostProduct = string("SELECT sd.m_product_id FROM m_storage_detail sd"
        + " WHERE sd.qtyonhand > 0"
        + " AND NOT EXISTS (SELECT 1 FROM m_costing c WHERE c.m_product_id = sd.m_product_id)"
        + " GROUP BY sd.m_product_id ORDER BY count(*), sd.m_product_id LIMIT 1");
    costProduct = string("SELECT sd.m_product_id FROM m_storage_detail sd"
        + " WHERE etgo_product_cost(sd.m_product_id) IS NOT NULL"
        + " GROUP BY sd.m_product_id ORDER BY count(*), sd.m_product_id LIMIT 1");
    currencyId = string("SELECT c_currency_id FROM m_costing LIMIT 1");
    assertNotNull("The database must hold a product with stock and no cost", noCostProduct);
    assertNotNull("The database must hold a product with stock and a cost", costProduct);
    assertNotNull("The database must hold a costing row to borrow a currency from", currencyId);
  }

  @After
  public void cleanUp() throws Exception {
    if (connection == null) {
      return;
    }
    try {
      for (String id : costingIds) {
        update("DELETE FROM m_costing WHERE m_costing_id = ?", id);
      }
      for (String id : storageIds) {
        update("DELETE FROM m_storage_detail WHERE m_storage_detail_id = ?", id);
      }
    } finally {
      connection.close();
    }
  }

  // ---------------------------------------------------------------- the SQL functions

  @Test
  public void productWithCostGetsUnitCostAndQuantityTimesCost() throws Exception {
    String rowId = string("SELECT m_storage_detail_id FROM m_storage_detail"
        + " WHERE m_product_id = ? AND qtyonhand > 0 LIMIT 1", costProduct);
    assertNotNull(rowId);
    BigDecimal expectedCost = decimal("SELECT etgo_product_cost(?)", costProduct);
    BigDecimal qty = decimal("SELECT qtyonhand FROM m_storage_detail WHERE m_storage_detail_id = ?",
        rowId);

    BigDecimal cost = decimal("SELECT etgo_storage_detail_cost(?)", rowId);
    BigDecimal valuation = decimal("SELECT etgo_storage_detail_valuation(?)", rowId);

    assertNotNull(cost);
    assertTrue("a product with a cost must produce a positive unit cost", cost.signum() > 0);
    assertEquals(0, expectedCost.compareTo(cost));
    assertEquals(0, qty.multiply(expectedCost).compareTo(valuation));
  }

  @Test
  public void productWithoutCostGetsNullNotZero() throws Exception {
    String rowId = string("SELECT m_storage_detail_id FROM m_storage_detail"
        + " WHERE m_product_id = ? LIMIT 1", noCostProduct);

    assertNull("no cost must read as empty, never as 0",
        decimal("SELECT etgo_storage_detail_cost(?)", rowId));
    assertNull(decimal("SELECT etgo_storage_detail_valuation(?)", rowId));
  }

  @Test
  public void nullAndUnknownIdsAnswerNullInsteadOfRaising() throws Exception {
    assertNull(decimal("SELECT etgo_storage_detail_cost(NULL)"));
    assertNull(decimal("SELECT etgo_storage_detail_valuation(NULL)"));
    assertNull(decimal("SELECT etgo_storage_detail_cost(?)", "NO-SUCH-STORAGE-DETAIL"));
    assertNull(decimal("SELECT etgo_storage_detail_valuation(?)", "NO-SUCH-STORAGE-DETAIL"));
  }

  @Test
  public void zeroQuantityWithACostIsValuedAtZeroNotNull() throws Exception {
    String rowId = cloneStorageRow(costProduct, "0");

    BigDecimal valuation = decimal("SELECT etgo_storage_detail_valuation(?)", rowId);

    assertNotNull("a zero quantity with a cost is a real zero, not 'no cost'", valuation);
    assertEquals(0, BigDecimal.ZERO.compareTo(valuation));
    assertNotNull(decimal("SELECT etgo_storage_detail_cost(?)", rowId));
  }

  // ---------------------------------------------------------------- the engine wiring

  @Test
  public void newStandardCostPopulatesEveryStorageRowOfTheProduct() throws Exception {
    assertEquals("precondition: the fixture product starts without cost", 0,
        count("SELECT count(*) FROM m_storage_detail WHERE m_product_id = ?"
            + " AND (em_etgo_cost IS NOT NULL OR em_etgo_valuation IS NOT NULL)", noCostProduct));

    insertCost(noCostProduct, "7.25", 0);

    long rows = count("SELECT count(*) FROM m_storage_detail WHERE m_product_id = ?",
        noCostProduct);
    assertTrue(rows > 0);
    assertEquals(rows, count("SELECT count(*) FROM m_storage_detail WHERE m_product_id = ?"
        + " AND em_etgo_cost = 7.25 AND em_etgo_valuation = qtyonhand * 7.25", noCostProduct));
  }

  @Test
  public void updatingQuantityOnHandRefreshesTheValuation() throws Exception {
    insertCost(noCostProduct, "7.25", 0);
    String rowId = string("SELECT m_storage_detail_id FROM m_storage_detail"
        + " WHERE m_product_id = ? LIMIT 1", noCostProduct);
    BigDecimal originalQty = decimal(
        "SELECT qtyonhand FROM m_storage_detail WHERE m_storage_detail_id = ?", rowId);
    try {
      update("UPDATE m_storage_detail SET qtyonhand = 40 WHERE m_storage_detail_id = ?", rowId);

      assertEquals(0, new BigDecimal("290.00").compareTo(decimal(
          "SELECT em_etgo_valuation FROM m_storage_detail WHERE m_storage_detail_id = ?", rowId)));
    } finally {
      update("UPDATE m_storage_detail SET qtyonhand = ? WHERE m_storage_detail_id = ?",
          originalQty, rowId);
    }
  }

  @Test
  public void aNewStorageRowIsPopulatedOnInsert() throws Exception {
    insertCost(noCostProduct, "7.25", 0);

    String rowId = cloneStorageRow(noCostProduct, "4");

    assertEquals(0, new BigDecimal("7.25").compareTo(decimal(
        "SELECT em_etgo_cost FROM m_storage_detail WHERE m_storage_detail_id = ?", rowId)));
    assertEquals(0, new BigDecimal("29.00").compareTo(decimal(
        "SELECT em_etgo_valuation FROM m_storage_detail WHERE m_storage_detail_id = ?", rowId)));
  }

  @Test
  public void aCostScheduledInTheFutureDoesNotReplaceTheCostInForce() throws Exception {
    insertCost(noCostProduct, "7.25", 0);

    insertCost(noCostProduct, "99.00", 30);

    assertEquals("the started cost keeps applying", 0,
        count("SELECT count(*) FROM m_storage_detail WHERE m_product_id = ?"
            + " AND em_etgo_cost IS DISTINCT FROM 7.25", noCostProduct));
  }

  @Test
  public void deletingTheNewestCostRestoresThePreviousOneAndTheLastDeleteRestoresNull()
      throws Exception {
    String older = insertCost(noCostProduct, "7.25", -2);
    String newer = insertCost(noCostProduct, "9.50", 0);
    assertEquals(0, count("SELECT count(*) FROM m_storage_detail WHERE m_product_id = ?"
        + " AND em_etgo_cost IS DISTINCT FROM 9.50", noCostProduct));

    deleteCost(newer);
    assertEquals("deleting the newest cost falls back to the previous one", 0,
        count("SELECT count(*) FROM m_storage_detail WHERE m_product_id = ?"
            + " AND (em_etgo_cost IS DISTINCT FROM 7.25"
            + " OR em_etgo_valuation IS DISTINCT FROM qtyonhand * 7.25)", noCostProduct));

    deleteCost(older);
    assertEquals("with no cost left the columns go back to NULL, not 0", 0,
        count("SELECT count(*) FROM m_storage_detail WHERE m_product_id = ?"
            + " AND (em_etgo_cost IS NOT NULL OR em_etgo_valuation IS NOT NULL)", noCostProduct));
  }

  // ---------------------------------------------------------------- the configuration

  @Test
  public void bothColumnsAreStoredSynchronousWithTheirFunctions() throws Exception {
    assertColumn(COST_COLUMN_ID, "etgo_storage_detail_cost", 20);
    assertColumn(VALUATION_COLUMN_ID, "etgo_storage_detail_valuation", 30);
  }

  @Test
  public void costingDependenciesWatchTheColumnsThatChangeTheCurrentCost() throws Exception {
    for (String columnId : new String[] { COST_COLUMN_ID, VALUATION_COLUMN_ID }) {
      String depId = string("SELECT ad_column_comp_dependency_id FROM ad_column_comp_dependency"
          + " WHERE ad_column_id = ? AND source_table_id = ?", columnId, M_COSTING_TABLE_ID);
      assertNotNull("missing M_Costing dependency for " + columnId, depId);
      assertEquals(
          "[cost, costtype, datefrom, dateto, m_warehouse_id]", watchedColumns(depId).toString());
    }
  }

  @Test
  public void valuationSelfDependencyWatchesQuantityOnHand() throws Exception {
    String depId = string("SELECT ad_column_comp_dependency_id FROM ad_column_comp_dependency"
        + " WHERE ad_column_id = ? AND source_table_id = ?", VALUATION_COLUMN_ID,
        M_STORAGE_DETAIL_TABLE_ID);
    assertNotNull(depId);
    assertTrue(watchedColumns(depId).contains("qtyonhand"));
  }

  @Test
  public void everyColumnHasAtLeastOneDependency() throws Exception {
    assertTrue(count("SELECT count(*) FROM ad_column_comp_dependency WHERE ad_column_id = ?",
        COST_COLUMN_ID) >= 1);
    assertTrue(count("SELECT count(*) FROM ad_column_comp_dependency WHERE ad_column_id = ?",
        VALUATION_COLUMN_ID) >= 1);
  }

  // ---------------------------------------------------------------- helpers

  private void assertColumn(String columnId, String function, int sequence) throws SQLException {
    String shape = string("SELECT computation_mode || '/' || refresh_mode || '/'"
        + " || lower(computation_function) || '/' || computation_sequence_number"
        + " FROM ad_column WHERE ad_column_id = ?", columnId);
    assertEquals("S/S/" + function + "/" + sequence, shape);
  }

  private List<String> watchedColumns(String dependencyId) throws SQLException {
    List<String> names = new ArrayList<>();
    try (PreparedStatement ps = connection.prepareStatement(
        "SELECT lower(c.columnname) FROM ad_compdep_watched_col w"
            + " JOIN ad_column c ON c.ad_column_id = w.ad_column_id"
            + " WHERE w.ad_column_comp_dependency_id = ? ORDER BY 1")) {
      ps.setString(1, dependencyId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          names.add(rs.getString(1));
        }
      }
    }
    return names;
  }

  /** Inserts a committed manual STA cost starting {@code startOffsetDays} from today. */
  private String insertCost(String productId, String cost, int startOffsetDays)
      throws SQLException {
    String id = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    costingIds.add(id);
    update("INSERT INTO m_costing (m_costing_id, ad_client_id, ad_org_id, isactive, created,"
        + " createdby, updated, updatedby, m_product_id, datefrom, dateto, ismanual, costtype,"
        + " ispermanent, cost, c_currency_id)"
        + " SELECT ?, sd.ad_client_id, '0', 'Y', now(), '0', now(), '0', sd.m_product_id,"
        + " current_date + ?, current_date + ? + 3650, 'Y', 'STA', 'N', ?::numeric, ?"
        + " FROM m_storage_detail sd WHERE sd.m_product_id = ? LIMIT 1",
        id, startOffsetDays, startOffsetDays, cost, currencyId, productId);
    return id;
  }

  private void deleteCost(String id) throws SQLException {
    update("DELETE FROM m_costing WHERE m_costing_id = ?", id);
    costingIds.remove(id);
  }

  /** Clones one storage row of the product into a locator the product does not use yet. */
  private String cloneStorageRow(String productId, String qty) throws SQLException {
    String id = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    storageIds.add(id);
    int inserted = update("INSERT INTO m_storage_detail (m_storage_detail_id, ad_client_id,"
        + " ad_org_id, isactive, created, createdby, updated, updatedby, m_product_id,"
        + " m_locator_id, m_attributesetinstance_id, c_uom_id, qtyonhand, reservedqty,"
        + " allocatedqty)"
        + " SELECT ?, src.ad_client_id, src.ad_org_id, 'Y', now(), '0', now(), '0',"
        + " src.m_product_id, l.m_locator_id, src.m_attributesetinstance_id, src.c_uom_id,"
        + " ?::numeric, 0, 0"
        + " FROM (SELECT * FROM m_storage_detail WHERE m_product_id = ? LIMIT 1) src"
        + " JOIN m_locator l ON l.ad_client_id = src.ad_client_id"
        + " WHERE NOT EXISTS (SELECT 1 FROM m_storage_detail x"
        + " WHERE x.m_product_id = src.m_product_id AND x.m_locator_id = l.m_locator_id)"
        + " LIMIT 1", id, qty, productId);
    assertEquals("no free locator to clone a storage row into", 1, inserted);
    return id;
  }

  private int update(String sql, Object... args) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      bind(ps, args);
      return ps.executeUpdate();
    }
  }

  private String string(String sql, Object... args) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      bind(ps, args);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private BigDecimal decimal(String sql, Object... args) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      bind(ps, args);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : null;
      }
    }
  }

  private long count(String sql, Object... args) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      bind(ps, args);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      ps.setObject(i + 1, args[i]);
    }
  }
}
