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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.test.base.OBBaseTest;

/**
 * ETP-5547 — real-PostgreSQL proof of the savepoint contract behind
 * {@link JdbcSavepoints} and {@link ConversionRateDocumentSync}.
 *
 * <p>The unit tests mock the savepoint, so they can only show that {@code rollback(savepoint)} is
 * called. The property that actually matters is a database one: on PostgreSQL the first failed
 * statement marks the whole transaction as aborted ({@code SQLSTATE 25P02}), and every later
 * statement — including the commit of a confirmed payment or a cloned invoice — fails or is lost.
 * Only a real connection can show that rolling back to the savepoint clears that state.
 *
 * <p>Each test first proves its own premise (the control: without the savepoint, the same failure
 * really does abort the transaction), then that the savepoint contains it.
 *
 * <p><b>No data is left behind.</b> Nothing here commits: scratch rows go to a
 * {@code ON COMMIT DROP} temporary table, the rate-row writes against posted invoices are rejected
 * by Core's own trigger, and {@link #rollbackChanges()} rolls the request transaction back.
 *
 * <p><b>Fixture data.</b> The trigger-backed tests need a posted ({@code Posted = 'Y'}) invoice
 * in the test database and are skipped via {@code Assume} (not failed) when there is none, so a
 * bare database cannot break the build. The pure-savepoint tests need no fixture at all.
 */
public class ConversionRateDocumentSavepointIntegrationTest extends OBBaseTest {

  /** PostgreSQL SQLSTATE for "current transaction is aborted, commands ignored…". */
  private static final String SQLSTATE_IN_FAILED_TRANSACTION = "25P02";

  /** Core's "document posted" message key, raised by {@code c_conversion_rate_document_trg}. */
  private static final String POSTED_DOCUMENT_MESSAGE = "@20501@";

  private static final String SCRATCH_TABLE = "etp5547_it_scratch";

  private static final String FAILING_STATEMENT = "SELECT 1 / 0";

  private Connection conn;

  @Before
  public void openConnection() throws SQLException {
    setTestUserContext();
    conn = OBDal.getInstance().getConnection();
    try (Statement st = conn.createStatement()) {
      st.execute("CREATE TEMPORARY TABLE " + SCRATCH_TABLE + " (v INTEGER) ON COMMIT DROP");
    }
  }

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  // ── Pure JdbcSavepoints contract (no fixture data needed) ─────────────────

  /**
   * Control: this is the bug. Without a savepoint, one failed statement leaves the transaction
   * aborted and the very next statement on the same connection is refused with 25P02.
   */
  @Test
  public void testFailedStatementWithoutSavepointAbortsTransaction() throws Exception {
    expectSqlException(() -> execute(FAILING_STATEMENT));

    SQLException next = expectSqlException(() -> execute("SELECT 1"));
    assertEquals("Without a savepoint PostgreSQL must refuse every later statement",
        SQLSTATE_IN_FAILED_TRANSACTION, next.getSQLState());
  }

  /**
   * The fix: the same failure inside {@link JdbcSavepoints#run} propagates to the caller, and the
   * connection stays usable for both raw JDBC and a DAL read.
   */
  @Test
  public void testFailedStatementInsideSavepointLeavesTransactionUsable() throws Exception {
    SQLException failure = expectSqlException(
        () -> JdbcSavepoints.run(conn, () -> execute(FAILING_STATEMENT)));
    assertTrue("The original failure must be rethrown, got: " + failure.getMessage(),
        failure.getMessage().toLowerCase().contains("division by zero"));

    assertEquals(1, queryInt("SELECT 1"));
    assertDalReadWorks();
  }

  /**
   * Rolling back to the savepoint undoes only the failed unit of work: a write made earlier in the
   * same transaction (the confirmed payment, the clone) survives, and the write made inside the
   * failed unit, before its failing statement, is undone.
   */
  @Test
  public void testSavepointRollbackUndoesOnlyTheFailedWork() throws Exception {
    execute("INSERT INTO " + SCRATCH_TABLE + " (v) VALUES (1)");

    expectSqlException(() -> JdbcSavepoints.run(conn, () -> {
      execute("INSERT INTO " + SCRATCH_TABLE + " (v) VALUES (2)");
      execute(FAILING_STATEMENT);
    }));

    assertEquals("Only the write made before the failed unit must remain",
        List.of(1), scratchValues());
  }

  /** On success the work is kept and the connection keeps working (savepoint released). */
  @Test
  public void testSuccessfulWorkInsideSavepointIsKept() throws Exception {
    JdbcSavepoints.run(conn, () -> execute("INSERT INTO " + SCRATCH_TABLE + " (v) VALUES (3)"));

    assertEquals(List.of(3), scratchValues());
    assertDalReadWorks();
  }

  // ── The real Core trigger on a posted invoice (needs a posted invoice) ────

  /**
   * Control for the trigger tests: a raw INSERT of a rate row for a posted invoice is rejected by
   * {@code c_conversion_rate_document_trg} with {@code @20501@} and, outside a savepoint, aborts
   * the transaction — the exact production failure of ETP-5547.
   */
  @Test
  public void testRateRowWriteOnPostedInvoiceWithoutSavepointAbortsTransaction()
      throws Exception {
    PostedFixture fx = postedInvoiceFixture();

    SQLException rejected = expectSqlException(() -> rawInsertRateRow(fx));
    assertTrue("Core trigger must reject the rate row of a posted invoice, got: "
        + rejected.getMessage(), rejected.getMessage().contains(POSTED_DOCUMENT_MESSAGE));

    SQLException next = expectSqlException(() -> execute("SELECT 1"));
    assertEquals(SQLSTATE_IN_FAILED_TRANSACTION, next.getSQLState());
  }

  /**
   * {@link ConversionRateDocumentSync#insertConversionRateDocument} on a posted invoice: the
   * trigger's {@code @20501@} still reaches the caller, but the transaction survives, an earlier
   * write of the same request is kept, and no rate row was created.
   */
  @Test
  public void testSyncInsertOnPostedInvoiceDoesNotAbortTransaction() throws Exception {
    PostedFixture fx = postedInvoiceFixture();
    int rowsBefore = rateRowCount(fx.invoice.getId());
    execute("INSERT INTO " + SCRATCH_TABLE + " (v) VALUES (42)");

    SQLException rejected = expectSqlException(
        () -> ConversionRateDocumentSync.insertConversionRateDocument(conn, fx.invoice,
            fx.otherCurrencyId, new BigDecimal("0.862069"), new BigDecimal("25.34")));
    assertTrue(rejected.getMessage(), rejected.getMessage().contains(POSTED_DOCUMENT_MESSAGE));

    assertEquals(1, queryInt("SELECT 1"));
    assertDalReadWorks();
    assertEquals("The request's earlier write must survive", List.of(42), scratchValues());
    assertEquals("No rate row may be created for a posted invoice",
        rowsBefore, rateRowCount(fx.invoice.getId()));
  }

  /**
   * {@link ConversionRateDocumentSync#upsert} changing the rate of a posted invoice that already
   * HAS a rate row: this is the UPDATE Core rejected in production (the payment/clone case). The
   * rejection is contained and the row keeps its booked values. Skipped when the database holds
   * no posted invoice with a rate row.
   */
  @Test
  public void testSyncUpdateOnPostedInvoiceWithRateRowDoesNotAbortTransaction()
      throws Exception {
    OBContext.setAdminMode(true);
    try {
      String[] row = findPostedInvoiceRateRow();
      assumeNotNull("No posted invoice with a C_Conversion_Rate_Document row in this database",
          (Object) row);
      Invoice invoice = OBDal.getInstance().get(Invoice.class, row[0]);
      String orgCurrencyId = row[1];
      BigDecimal bookedRate = new BigDecimal(row[2]);
      BigDecimal differentRate = bookedRate.add(new BigDecimal("0.123456"));
      execute("INSERT INTO " + SCRATCH_TABLE + " (v) VALUES (7)");

      SQLException rejected = expectSqlException(
          () -> ConversionRateDocumentSync.upsert(invoice, orgCurrencyId, differentRate, null));
      assertTrue(rejected.getMessage(), rejected.getMessage().contains(POSTED_DOCUMENT_MESSAGE));

      assertEquals(1, queryInt("SELECT 1"));
      assertDalReadWorks();
      assertEquals(List.of(7), scratchValues());
      assertEquals("The booked rate of a posted invoice must be untouched", 0,
          bookedRate.compareTo(rateOf(invoice.getId(), orgCurrencyId)));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The handler entry point on a posted invoice: {@code autoCreateOrUpdateConversionRateDocument}
   * returns quietly (the {@code Posted = 'Y'} gate), leaves the transaction usable and writes
   * nothing.
   */
  @Test
  public void testAutoSyncOnPostedInvoiceLeavesTransactionUsableAndWritesNothing()
      throws Exception {
    PostedFixture fx = postedInvoiceFixture();
    String invoiceId = fx.invoice.getId();
    int rowsBefore = rateRowCount(invoiceId);
    execute("INSERT INTO " + SCRATCH_TABLE + " (v) VALUES (9)");

    AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(invoiceId);

    assertEquals(1, queryInt("SELECT 1"));
    assertDalReadWorks();
    assertEquals(List.of(9), scratchValues());
    assertEquals(rowsBefore, rateRowCount(invoiceId));
  }

  // ── Fixture helpers ───────────────────────────────────────────────────────

  /** A posted invoice plus a currency different from its own (to pass the SameCurrency check). */
  private static final class PostedFixture {
    private final Invoice invoice;
    private final String otherCurrencyId;

    private PostedFixture(Invoice invoice, String otherCurrencyId) {
      this.invoice = invoice;
      this.otherCurrencyId = otherCurrencyId;
    }
  }

  private PostedFixture postedInvoiceFixture() throws SQLException {
    OBContext.setAdminMode(true);
    try {
      Invoice invoice = (Invoice) OBDal.getInstance().createCriteria(Invoice.class)
          .add(Restrictions.eq(Invoice.PROPERTY_POSTED, "Y"))
          .setFilterOnReadableClients(false)
          .setFilterOnReadableOrganization(false)
          .setMaxResults(1)
          .uniqueResult();
      assumeNotNull("No posted invoice in this database", invoice);
      String otherCurrencyId = null;
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT c_currency_id FROM c_currency WHERE c_currency_id <> ? LIMIT 1")) {
        ps.setString(1, invoice.getCurrency().getId());
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            otherCurrencyId = rs.getString(1);
          }
        }
      }
      assumeNotNull("Need a second currency in this database", otherCurrencyId);
      // Touch the lazy associations the sync reads while the admin mode is on.
      assertNotNull(invoice.getClient().getId());
      assertNotNull(invoice.getOrganization().getId());
      return new PostedFixture(invoice, otherCurrencyId);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** {invoiceId, c_currency_id_to, rate} of a posted invoice's rate row, or {@code null}. */
  private String[] findPostedInvoiceRateRow() throws SQLException {
    String sql = "SELECT crd.c_invoice_id, crd.c_currency_id_to, crd.rate"
        + " FROM c_conversion_rate_document crd"
        + " JOIN c_invoice i ON i.c_invoice_id = crd.c_invoice_id"
        + " WHERE i.posted = 'Y' AND crd.rate IS NOT NULL LIMIT 1";
    try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
      return rs.next() ? new String[] { rs.getString(1), rs.getString(2), rs.getString(3) } : null;
    }
  }

  private void rawInsertRateRow(PostedFixture fx) throws SQLException {
    String sql = "INSERT INTO c_conversion_rate_document ("
        + " c_conversion_rate_document_id, ad_client_id, ad_org_id, isactive,"
        + " created, createdby, updated, updatedby,"
        + " c_invoice_id, c_currency_id, c_currency_id_to, rate"
        + ") VALUES (get_uuid(), ?, ?, 'Y', NOW(), '100', NOW(), '100', ?, ?, ?, 1.5)";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, fx.invoice.getClient().getId());
      ps.setString(2, fx.invoice.getOrganization().getId());
      ps.setString(3, fx.invoice.getId());
      ps.setString(4, fx.invoice.getCurrency().getId());
      ps.setString(5, fx.otherCurrencyId);
      ps.executeUpdate();
    }
  }

  private int rateRowCount(String invoiceId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT COUNT(*) FROM c_conversion_rate_document WHERE c_invoice_id = ?")) {
      ps.setString(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private BigDecimal rateOf(String invoiceId, String orgCurrencyId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT rate FROM c_conversion_rate_document WHERE c_invoice_id = ?"
            + " AND c_currency_id_to = ? ORDER BY created DESC LIMIT 1")) {
      ps.setString(1, invoiceId);
      ps.setString(2, orgCurrencyId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBigDecimal(1);
      }
    }
  }

  // ── JDBC helpers ──────────────────────────────────────────────────────────

  /** A block of JDBC work expected to fail (the JUnit on the classpath predates assertThrows). */
  @FunctionalInterface
  private interface SqlBlock {
    void run() throws Exception;
  }

  private static SQLException expectSqlException(SqlBlock block) {
    try {
      block.run();
    } catch (SQLException e) {
      return e;
    } catch (Exception e) {
      throw new AssertionError("Expected an SQLException, got " + e, e);
    }
    fail("Expected an SQLException, but the block completed normally");
    return null;
  }

  private void execute(String sql) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute(sql);
    }
  }

  private int queryInt(String sql) throws SQLException {
    try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private List<Integer> scratchValues() throws SQLException {
    List<Integer> values = new ArrayList<>();
    try (Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery("SELECT v FROM " + SCRATCH_TABLE + " ORDER BY v")) {
      while (rs.next()) {
        values.add(rs.getInt(1));
      }
    }
    return values;
  }

  /** A DAL read on the same transaction — what the rest of the request does after the sync. */
  private static void assertDalReadWorks() {
    OBContext.setAdminMode(true);
    try {
      // A criteria query always hits the database (a get() could be served by the L1 cache).
      assertNotNull("DAL read on the same transaction must still work",
          OBDal.getInstance().createCriteria(Client.class)
              .add(Restrictions.eq(Client.PROPERTY_ID, TEST_CLIENT_ID))
              .setFilterOnReadableClients(false)
              .uniqueResult());
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
