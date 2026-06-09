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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.ad.system.Client;

import java.util.Date;

/**
 * Unit tests for {@link TotalDiscountService#recalculate}.
 *
 * <p>All Etendo static singletons ({@link OBDal}, {@link OBContext}, {@link OBProvider}) are
 * mocked with {@link MockedStatic} per test to ensure full suite isolation.</p>
 *
 * <p>JDBC interactions (Connection / PreparedStatement / ResultSet) are fully stubbed,
 * so no live database is required.</p>
 */
public class TotalDiscountServiceTest {

  // ── Static singleton mocks (opened in @Before, closed in @After) ──────────

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBProvider> obProviderMock;

  private OBDal dal;
  private OBProvider provider;

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    provider = mock(OBProvider.class);

    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    obProviderMock = mockStatic(OBProvider.class);

    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(provider);

    // OBContext static methods are void — stub them to do nothing so the
    // finally-block in recalculate() doesn't throw.
    obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
    obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
  }

  @After
  public void tearDown() {
    // Close in reverse-open order to honour MockedStatic scoping rules.
    obProviderMock.close();
    obContextMock.close();
    obDalMock.close();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Builds a mock Invoice with the bare minimum non-null fields. */
  private Invoice mockInvoice(String id) {
    Invoice inv = mock(Invoice.class);
    when(inv.getId()).thenReturn(id);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(inv.getClient()).thenReturn(client);
    when(inv.getOrganization()).thenReturn(org);
    return inv;
  }

  /** Builds a mock Order with the bare minimum non-null fields. */
  private Order mockOrder(String id) {
    Order order = mock(Order.class);
    when(order.getId()).thenReturn(id);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Warehouse warehouse = mock(Warehouse.class);
    Currency currency = mock(Currency.class);
    when(order.getClient()).thenReturn(client);
    when(order.getOrganization()).thenReturn(org);
    when(order.getWarehouse()).thenReturn(warehouse);
    when(order.getCurrency()).thenReturn(currency);
    when(order.getOrderDate()).thenReturn(new Date());
    return order;
  }

  // ── Tests: pct is null (no discount row in DB) ────────────────────────────

  /**
   * When the header row has no em_etgo_total_discount value ({@code rs.next()} returns false),
   * recalculate must skip line creation for an invoice.
   */
  @Test
  public void recalculate_invoiceNullPct_noLineCreated() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    // readDiscountPct → no row → null
    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(false);
    when(psPct.executeQuery()).thenReturn(rsPct);

    // deleteExistingDiscountLine → executeUpdate
    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)   // first call = readDiscountPct
        .thenReturn(psDel);  // second call = deleteExistingDiscountLine

    new TotalDiscountService().recalculate("inv-001", true);

    // No entity should have been saved.
    verify(dal, never()).save(any(Object.class));
  }

  /**
   * Same guard for order documents.
   */
  @Test
  public void recalculate_orderNullPct_noLineCreated() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(false);
    when(psPct.executeQuery()).thenReturn(rsPct);

    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel);

    new TotalDiscountService().recalculate("ord-001", false);

    verify(dal, never()).save(any(Object.class));
  }

  // ── Tests: pct is zero ────────────────────────────────────────────────────

  /**
   * A pct value of 0 (zero) must also skip line creation.
   */
  @Test
  public void recalculate_invoiceZeroPct_noLineCreated() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    // readDiscountPct → 0.00
    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(BigDecimal.ZERO);
    when(psPct.executeQuery()).thenReturn(rsPct);

    // deleteExistingDiscountLine
    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel);

    new TotalDiscountService().recalculate("inv-002", true);

    verify(dal, never()).save(any(Object.class));
  }

  /**
   * A negative pct must also skip line creation.
   */
  @Test
  public void recalculate_invoiceNegativePct_noLineCreated() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("-5"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel);

    new TotalDiscountService().recalculate("inv-003", true);

    verify(dal, never()).save(any(Object.class));
  }

  // ── Tests: pct > 0 but no tax groups found ────────────────────────────────

  /**
   * When the net-subtotal-by-tax query returns no rows, no lines should be created.
   */
  @Test
  public void recalculate_invoicePositivePctEmptyTaxGroups_noLineCreated() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    // readDiscountPct → 10%
    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("10"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    // deleteExistingDiscountLine
    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    // readNetSubtotalByTax → no rows
    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(false);
    when(psTax.executeQuery()).thenReturn(rsTax);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax);

    new TotalDiscountService().recalculate("inv-004", true);

    verify(dal, never()).save(any(Object.class));
  }

  // ── Tests: tax group with zero net subtotal is skipped ───────────────────

  /**
   * A tax group whose linenetamt sum is zero must not produce a discount line.
   */
  @Test
  public void recalculate_invoiceTaxGroupWithZeroNet_noLineCreated() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    // readDiscountPct → 10%
    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("10"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    // deleteExistingDiscountLine
    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    // readNetSubtotalByTax → one row with zero net
    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, false);
    when(rsTax.getString(1)).thenReturn("tax-001");
    when(rsTax.getBigDecimal(2)).thenReturn(BigDecimal.ZERO);
    when(psTax.executeQuery()).thenReturn(rsTax);

    // readNextLineNo → called before loop but loop will skip all entries
    PreparedStatement psLine = mock(PreparedStatement.class);
    ResultSet rsLine = mock(ResultSet.class);
    when(rsLine.next()).thenReturn(true);
    when(rsLine.getLong(1)).thenReturn(20L);
    when(psLine.executeQuery()).thenReturn(rsLine);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLine);

    new TotalDiscountService().recalculate("inv-005", true);

    verify(dal, never()).save(any(Object.class));
  }

  // ── Tests: happy path — invoice discount line created ─────────────────────

  /**
   * Full happy path for invoice: 10% discount on a single tax group produces one
   * InvoiceLine with the correct negative amount (100 * 10% = 10.00 negated = -10.00).
   */
  @Test
  @SuppressWarnings("unchecked")
  public void recalculate_invoiceHappyPath_createsDiscountLine() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    // readDiscountPct → 10%
    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("10"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    // deleteExistingDiscountLine
    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    // readNetSubtotalByTax → one row: tax="tax-001", net=100
    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, false);
    when(rsTax.getString(1)).thenReturn("tax-001");
    when(rsTax.getBigDecimal(2)).thenReturn(new BigDecimal("100"));
    when(psTax.executeQuery()).thenReturn(rsTax);

    // readNextLineNo → MAX(line) = 10 → returns 20
    PreparedStatement psLineNo = mock(PreparedStatement.class);
    ResultSet rsLineNo = mock(ResultSet.class);
    when(rsLineNo.next()).thenReturn(true);
    when(rsLineNo.getLong(1)).thenReturn(10L);
    when(psLineNo.executeQuery()).thenReturn(rsLineNo);

    // readFirstLineUomId (called inside createInvoiceDiscountLine) → no UOM
    PreparedStatement psUom = mock(PreparedStatement.class);
    ResultSet rsUom = mock(ResultSet.class);
    when(rsUom.next()).thenReturn(false);
    when(psUom.executeQuery()).thenReturn(rsUom);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLineNo)
        .thenReturn(psUom);

    // OBDal.get stubs
    Invoice invoice = mockInvoice("inv-006");
    Product product = mock(Product.class);
    TaxRate tax = mock(TaxRate.class);
    when(dal.get(Invoice.class, "inv-006")).thenReturn(invoice);
    when(dal.get(Product.class, TotalDiscountService.DISCOUNT_PRODUCT_ID)).thenReturn(product);
    when(dal.get(TaxRate.class, "tax-001")).thenReturn(tax);

    // OBProvider.get(InvoiceLine.class)
    InvoiceLine line = mock(InvoiceLine.class);
    when(provider.get(InvoiceLine.class)).thenReturn(line);

    new TotalDiscountService().recalculate("inv-006", true);

    // The line must be saved.
    verify(dal).save(line);
    // Flush must be called after saving.
    verify(dal).flush();
    // Verify the calculated discount amount: 100 * 10% = 10.00, negated = -10.00
    ArgumentCaptor<BigDecimal> amtCaptor = ArgumentCaptor.forClass(BigDecimal.class);
    verify(line).setUnitPrice(amtCaptor.capture());
    assertEquals(new BigDecimal("-10.00"), amtCaptor.getValue());
  }

  // ── Tests: happy path — order discount line created ───────────────────────

  /**
   * Full happy path for order: 20% discount on 50-net produces a -10.00 discount line.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void recalculate_orderHappyPath_createsDiscountLine() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    // readDiscountPct → 20%
    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("20"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    // deleteExistingDiscountLine
    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    // readNetSubtotalByTax → net=50, tax="tax-002"
    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, false);
    when(rsTax.getString(1)).thenReturn("tax-002");
    when(rsTax.getBigDecimal(2)).thenReturn(new BigDecimal("50"));
    when(psTax.executeQuery()).thenReturn(rsTax);

    // readNextLineNo → 30 (MAX=20 + 10)
    PreparedStatement psLineNo = mock(PreparedStatement.class);
    ResultSet rsLineNo = mock(ResultSet.class);
    when(rsLineNo.next()).thenReturn(true);
    when(rsLineNo.getLong(1)).thenReturn(20L);
    when(psLineNo.executeQuery()).thenReturn(rsLineNo);

    // readFirstLineUomId → no UOM row
    PreparedStatement psUom = mock(PreparedStatement.class);
    ResultSet rsUom = mock(ResultSet.class);
    when(rsUom.next()).thenReturn(false);
    when(psUom.executeQuery()).thenReturn(rsUom);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLineNo)
        .thenReturn(psUom);

    // OBDal.get stubs
    Order order = mockOrder("ord-002");
    Product product = mock(Product.class);
    TaxRate tax = mock(TaxRate.class);
    when(dal.get(Order.class, "ord-002")).thenReturn(order);
    when(dal.get(Product.class, TotalDiscountService.DISCOUNT_PRODUCT_ID)).thenReturn(product);
    when(dal.get(TaxRate.class, "tax-002")).thenReturn(tax);

    OrderLine line = mock(OrderLine.class);
    when(provider.get(OrderLine.class)).thenReturn(line);

    new TotalDiscountService().recalculate("ord-002", false);

    verify(dal).save(line);
    verify(dal).flush();

    ArgumentCaptor<BigDecimal> amtCaptor = ArgumentCaptor.forClass(BigDecimal.class);
    verify(line).setUnitPrice(amtCaptor.capture());
    assertEquals(new BigDecimal("-10.00"), amtCaptor.getValue());
  }

  // ── Tests: multiple tax groups → multiple lines ───────────────────────────

  /**
   * Two tax groups each receive their proportional negative discount line.
   * Tax A: net=200, 10% → -20.00.  Tax B: net=300, 10% → -30.00.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void recalculate_invoiceMultipleTaxGroups_createsOneLinePerGroup() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    // readDiscountPct → 10%
    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("10"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    // deleteExistingDiscountLine
    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    // readNetSubtotalByTax → two rows
    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, true, false);
    when(rsTax.getString(1)).thenReturn("tax-A", "tax-B");
    when(rsTax.getBigDecimal(2)).thenReturn(new BigDecimal("200"), new BigDecimal("300"));
    when(psTax.executeQuery()).thenReturn(rsTax);

    // readNextLineNo → next = 10
    PreparedStatement psLineNo = mock(PreparedStatement.class);
    ResultSet rsLineNo = mock(ResultSet.class);
    when(rsLineNo.next()).thenReturn(true);
    when(rsLineNo.getLong(1)).thenReturn(0L);
    when(psLineNo.executeQuery()).thenReturn(rsLineNo);

    // readFirstLineUomId calls: one per createInvoiceDiscountLine invocation (2 calls total)
    PreparedStatement psUom1 = mock(PreparedStatement.class);
    ResultSet rsUom1 = mock(ResultSet.class);
    when(rsUom1.next()).thenReturn(false);
    when(psUom1.executeQuery()).thenReturn(rsUom1);

    PreparedStatement psUom2 = mock(PreparedStatement.class);
    ResultSet rsUom2 = mock(ResultSet.class);
    when(rsUom2.next()).thenReturn(false);
    when(psUom2.executeQuery()).thenReturn(rsUom2);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLineNo)
        .thenReturn(psUom1)
        .thenReturn(psUom2);

    Invoice invoice = mockInvoice("inv-007");
    Product product = mock(Product.class);
    TaxRate taxA = mock(TaxRate.class);
    TaxRate taxB = mock(TaxRate.class);
    when(dal.get(Invoice.class, "inv-007")).thenReturn(invoice);
    when(dal.get(Product.class, TotalDiscountService.DISCOUNT_PRODUCT_ID)).thenReturn(product);
    when(dal.get(TaxRate.class, "tax-A")).thenReturn(taxA);
    when(dal.get(TaxRate.class, "tax-B")).thenReturn(taxB);

    InvoiceLine lineA = mock(InvoiceLine.class);
    InvoiceLine lineB = mock(InvoiceLine.class);
    when(provider.get(InvoiceLine.class)).thenReturn(lineA, lineB);

    new TotalDiscountService().recalculate("inv-007", true);

    verify(dal).save(lineA);
    verify(dal).save(lineB);
    verify(dal).flush();

    ArgumentCaptor<BigDecimal> captorA = ArgumentCaptor.forClass(BigDecimal.class);
    verify(lineA).setUnitPrice(captorA.capture());
    assertEquals(new BigDecimal("-20.00"), captorA.getValue());

    ArgumentCaptor<BigDecimal> captorB = ArgumentCaptor.forClass(BigDecimal.class);
    verify(lineB).setUnitPrice(captorB.capture());
    assertEquals(new BigDecimal("-30.00"), captorB.getValue());
  }

  // ── Tests: discount product not found → no line saved ─────────────────────

  /**
   * If the ETGO_DTO product does not exist in the database, the service must skip
   * line creation (just logs a warning) rather than throwing.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void recalculate_invoiceDiscountProductMissing_noLineSaved() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    // readDiscountPct → 10%
    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("10"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    // deleteExistingDiscountLine
    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    // readNetSubtotalByTax → one row
    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, false);
    when(rsTax.getString(1)).thenReturn("tax-001");
    when(rsTax.getBigDecimal(2)).thenReturn(new BigDecimal("100"));
    when(psTax.executeQuery()).thenReturn(rsTax);

    // readNextLineNo → 10
    PreparedStatement psLineNo = mock(PreparedStatement.class);
    ResultSet rsLineNo = mock(ResultSet.class);
    when(rsLineNo.next()).thenReturn(true);
    when(rsLineNo.getLong(1)).thenReturn(0L);
    when(psLineNo.executeQuery()).thenReturn(rsLineNo);

    // readFirstLineUomId → no UOM
    PreparedStatement psUom = mock(PreparedStatement.class);
    ResultSet rsUom = mock(ResultSet.class);
    when(rsUom.next()).thenReturn(false);
    when(psUom.executeQuery()).thenReturn(rsUom);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLineNo)
        .thenReturn(psUom);

    Invoice invoice = mockInvoice("inv-008");
    when(dal.get(Invoice.class, "inv-008")).thenReturn(invoice);
    // Product lookup returns null → line creation is skipped
    when(dal.get(Product.class, TotalDiscountService.DISCOUNT_PRODUCT_ID)).thenReturn(null);

    new TotalDiscountService().recalculate("inv-008", true);

    verify(dal, never()).save(any(Object.class));
    // flush() is still called because the loop ran (even though every iteration was skipped)
    verify(dal).flush();
  }

  // ── Tests: invoice header not found → no line saved ───────────────────────

  /**
   * If the invoice record itself cannot be loaded, the service must skip silently.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void recalculate_invoiceHeaderNotFound_noLineSaved() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("10"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, false);
    when(rsTax.getString(1)).thenReturn("tax-001");
    when(rsTax.getBigDecimal(2)).thenReturn(new BigDecimal("100"));
    when(psTax.executeQuery()).thenReturn(rsTax);

    PreparedStatement psLineNo = mock(PreparedStatement.class);
    ResultSet rsLineNo = mock(ResultSet.class);
    when(rsLineNo.next()).thenReturn(true);
    when(rsLineNo.getLong(1)).thenReturn(0L);
    when(psLineNo.executeQuery()).thenReturn(rsLineNo);

    PreparedStatement psUom = mock(PreparedStatement.class);
    ResultSet rsUom = mock(ResultSet.class);
    when(rsUom.next()).thenReturn(false);
    when(psUom.executeQuery()).thenReturn(rsUom);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLineNo)
        .thenReturn(psUom);

    // Invoice header returns null
    when(dal.get(Invoice.class, "inv-missing")).thenReturn(null);

    new TotalDiscountService().recalculate("inv-missing", true);

    verify(dal, never()).save(any(Object.class));
    verify(dal).flush();
  }

  // ── Tests: order header not found → no line saved ─────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void recalculate_orderHeaderNotFound_noLineSaved() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("10"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, false);
    when(rsTax.getString(1)).thenReturn("tax-001");
    when(rsTax.getBigDecimal(2)).thenReturn(new BigDecimal("100"));
    when(psTax.executeQuery()).thenReturn(rsTax);

    PreparedStatement psLineNo = mock(PreparedStatement.class);
    ResultSet rsLineNo = mock(ResultSet.class);
    when(rsLineNo.next()).thenReturn(true);
    when(rsLineNo.getLong(1)).thenReturn(0L);
    when(psLineNo.executeQuery()).thenReturn(rsLineNo);

    PreparedStatement psUom = mock(PreparedStatement.class);
    ResultSet rsUom = mock(ResultSet.class);
    when(rsUom.next()).thenReturn(false);
    when(psUom.executeQuery()).thenReturn(rsUom);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLineNo)
        .thenReturn(psUom);

    when(dal.get(Order.class, "ord-missing")).thenReturn(null);

    new TotalDiscountService().recalculate("ord-missing", false);

    verify(dal, never()).save(any(Object.class));
    verify(dal).flush();
  }

  // ── Tests: readNextLineNo falls back to 10 when no rows returned ──────────

  /**
   * When MAX(line) query returns no rows, readNextLineNo must default to 10.
   * This is verified indirectly: line.setLineNo(10) must be called.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void recalculate_invoiceMaxLineNoQueryNoRows_defaultsToLineNo10() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("10"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, false);
    when(rsTax.getString(1)).thenReturn("tax-001");
    when(rsTax.getBigDecimal(2)).thenReturn(new BigDecimal("100"));
    when(psTax.executeQuery()).thenReturn(rsTax);

    // readNextLineNo → rs.next() returns false → defaults to 10
    PreparedStatement psLineNo = mock(PreparedStatement.class);
    ResultSet rsLineNo = mock(ResultSet.class);
    when(rsLineNo.next()).thenReturn(false);
    when(psLineNo.executeQuery()).thenReturn(rsLineNo);

    // readFirstLineUomId → no UOM
    PreparedStatement psUom = mock(PreparedStatement.class);
    ResultSet rsUom = mock(ResultSet.class);
    when(rsUom.next()).thenReturn(false);
    when(psUom.executeQuery()).thenReturn(rsUom);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLineNo)
        .thenReturn(psUom);

    Invoice invoice = mockInvoice("inv-009");
    Product product = mock(Product.class);
    TaxRate tax = mock(TaxRate.class);
    when(dal.get(Invoice.class, "inv-009")).thenReturn(invoice);
    when(dal.get(Product.class, TotalDiscountService.DISCOUNT_PRODUCT_ID)).thenReturn(product);
    when(dal.get(TaxRate.class, "tax-001")).thenReturn(tax);

    InvoiceLine line = mock(InvoiceLine.class);
    when(provider.get(InvoiceLine.class)).thenReturn(line);

    new TotalDiscountService().recalculate("inv-009", true);

    // readNextLineNo defaults to 10 when no rows
    verify(line).setLineNo(10L);
    verify(dal).save(line);
  }

  // ── Tests: UOM resolved and set on invoice line ───────────────────────────

  /**
   * When readFirstLineUomId returns a UOM ID that resolves via OBDal, it must be
   * set on the new invoice discount line.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void recalculate_invoiceWithUom_uomSetOnLine() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("5"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, false);
    when(rsTax.getString(1)).thenReturn("tax-001");
    when(rsTax.getBigDecimal(2)).thenReturn(new BigDecimal("200"));
    when(psTax.executeQuery()).thenReturn(rsTax);

    PreparedStatement psLineNo = mock(PreparedStatement.class);
    ResultSet rsLineNo = mock(ResultSet.class);
    when(rsLineNo.next()).thenReturn(true);
    when(rsLineNo.getLong(1)).thenReturn(10L);
    when(psLineNo.executeQuery()).thenReturn(rsLineNo);

    // readFirstLineUomId → returns "uom-id-001"
    PreparedStatement psUom = mock(PreparedStatement.class);
    ResultSet rsUom = mock(ResultSet.class);
    when(rsUom.next()).thenReturn(true);
    when(rsUom.getString(1)).thenReturn("uom-id-001");
    when(psUom.executeQuery()).thenReturn(rsUom);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLineNo)
        .thenReturn(psUom);

    Invoice invoice = mockInvoice("inv-010");
    Product product = mock(Product.class);
    TaxRate tax = mock(TaxRate.class);
    UOM uom = mock(UOM.class);
    when(dal.get(Invoice.class, "inv-010")).thenReturn(invoice);
    when(dal.get(Product.class, TotalDiscountService.DISCOUNT_PRODUCT_ID)).thenReturn(product);
    when(dal.get(TaxRate.class, "tax-001")).thenReturn(tax);
    when(dal.get(UOM.class, "uom-id-001")).thenReturn(uom);

    InvoiceLine line = mock(InvoiceLine.class);
    when(provider.get(InvoiceLine.class)).thenReturn(line);

    new TotalDiscountService().recalculate("inv-010", true);

    verify(line).setUOM(uom);
    verify(dal).save(line);
  }

  // ── Tests: UOM resolved and set on order line ─────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void recalculate_orderWithUom_uomSetOnLine() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("5"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, false);
    when(rsTax.getString(1)).thenReturn("tax-002");
    when(rsTax.getBigDecimal(2)).thenReturn(new BigDecimal("100"));
    when(psTax.executeQuery()).thenReturn(rsTax);

    PreparedStatement psLineNo = mock(PreparedStatement.class);
    ResultSet rsLineNo = mock(ResultSet.class);
    when(rsLineNo.next()).thenReturn(true);
    when(rsLineNo.getLong(1)).thenReturn(0L);
    when(psLineNo.executeQuery()).thenReturn(rsLineNo);

    PreparedStatement psUom = mock(PreparedStatement.class);
    ResultSet rsUom = mock(ResultSet.class);
    when(rsUom.next()).thenReturn(true);
    when(rsUom.getString(1)).thenReturn("uom-id-002");
    when(psUom.executeQuery()).thenReturn(rsUom);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLineNo)
        .thenReturn(psUom);

    Order order = mockOrder("ord-003");
    Product product = mock(Product.class);
    TaxRate tax = mock(TaxRate.class);
    UOM uom = mock(UOM.class);
    when(dal.get(Order.class, "ord-003")).thenReturn(order);
    when(dal.get(Product.class, TotalDiscountService.DISCOUNT_PRODUCT_ID)).thenReturn(product);
    when(dal.get(TaxRate.class, "tax-002")).thenReturn(tax);
    when(dal.get(UOM.class, "uom-id-002")).thenReturn(uom);

    OrderLine line = mock(OrderLine.class);
    when(provider.get(OrderLine.class)).thenReturn(line);

    new TotalDiscountService().recalculate("ord-003", false);

    verify(line).setUOM(uom);
    verify(dal).save(line);
  }

  // ── Tests: JDBC exception during readDiscountPct is swallowed ────────────

  /**
   * If the JDBC call for reading the discount percentage throws (e.g., column missing),
   * recalculate must log a warning and skip line creation — it must not propagate.
   */
  @Test
  public void recalculate_jdbcExceptionOnReadPct_doesNotThrow() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    // First prepareStatement call (readDiscountPct) throws
    when(conn.prepareStatement(anyString())).thenThrow(new RuntimeException("column not found"));

    // Must not throw; recalculate catches Exception internally.
    new TotalDiscountService().recalculate("inv-err", true);

    verify(dal, never()).save(any(Object.class));
  }

  // ── Tests: JDBC exception during deleteExistingDiscountLine is swallowed ──

  /**
   * An error deleting the old discount line must be caught without propagating.
   */
  @Test
  public void recalculate_jdbcExceptionOnDelete_doesNotThrow() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    // readDiscountPct → null (no rows → return null → early return)
    // so the only JDBC call is readDiscountPct then deleteExistingDiscountLine
    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(false);
    when(psPct.executeQuery()).thenReturn(rsPct);

    // deleteExistingDiscountLine PreparedStatement throws on executeUpdate
    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenThrow(new RuntimeException("delete failed"));

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel);

    // Must not throw
    new TotalDiscountService().recalculate("inv-del-err", true);

    verify(dal, never()).save(any(Object.class));
  }

  // ── Tests: tax rate is null in DB (TaxRate.get returns null) ─────────────

  /**
   * When OBDal.get(TaxRate.class, taxId) returns null, the line must still be
   * created but without a tax assigned (the tax setter is simply not called).
   */
  @Test
  @SuppressWarnings("unchecked")
  public void recalculate_invoiceTaxRateNotFound_lineCreatedWithoutTax() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("10"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, false);
    when(rsTax.getString(1)).thenReturn("tax-missing");
    when(rsTax.getBigDecimal(2)).thenReturn(new BigDecimal("100"));
    when(psTax.executeQuery()).thenReturn(rsTax);

    PreparedStatement psLineNo = mock(PreparedStatement.class);
    ResultSet rsLineNo = mock(ResultSet.class);
    when(rsLineNo.next()).thenReturn(true);
    when(rsLineNo.getLong(1)).thenReturn(0L);
    when(psLineNo.executeQuery()).thenReturn(rsLineNo);

    PreparedStatement psUom = mock(PreparedStatement.class);
    ResultSet rsUom = mock(ResultSet.class);
    when(rsUom.next()).thenReturn(false);
    when(psUom.executeQuery()).thenReturn(rsUom);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLineNo)
        .thenReturn(psUom);

    Invoice invoice = mockInvoice("inv-011");
    Product product = mock(Product.class);
    when(dal.get(Invoice.class, "inv-011")).thenReturn(invoice);
    when(dal.get(Product.class, TotalDiscountService.DISCOUNT_PRODUCT_ID)).thenReturn(product);
    when(dal.get(TaxRate.class, "tax-missing")).thenReturn(null);

    InvoiceLine line = mock(InvoiceLine.class);
    when(provider.get(InvoiceLine.class)).thenReturn(line);

    new TotalDiscountService().recalculate("inv-011", true);

    // Line is still saved
    verify(dal).save(line);
    // setTax is never called (null-checked in createInvoiceDiscountLine)
    verify(line, never()).setTax(any());
  }

  // ── Tests: DISCOUNT_PRODUCT_ID constant value ─────────────────────────────

  /**
   * Asserts the constant has the expected value, acting as a regression guard.
   */
  @Test
  public void discountProductIdConstant_hasExpectedValue() {
    assertEquals("E4BC94E71D664E73A066DAF78BF39DB3", TotalDiscountService.DISCOUNT_PRODUCT_ID);
  }

  // ── Tests: rounding — HALF_UP at 2 decimal places ─────────────────────────

  /**
   * 33.33% of 100 = 33.33 (rounded HALF_UP from 33.3333...). Negated → -33.33.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void recalculate_invoiceRoundingHalfUp_discountAmountRoundedCorrectly() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("33.33"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, false);
    when(rsTax.getString(1)).thenReturn("tax-001");
    when(rsTax.getBigDecimal(2)).thenReturn(new BigDecimal("100"));
    when(psTax.executeQuery()).thenReturn(rsTax);

    PreparedStatement psLineNo = mock(PreparedStatement.class);
    ResultSet rsLineNo = mock(ResultSet.class);
    when(rsLineNo.next()).thenReturn(true);
    when(rsLineNo.getLong(1)).thenReturn(0L);
    when(psLineNo.executeQuery()).thenReturn(rsLineNo);

    PreparedStatement psUom = mock(PreparedStatement.class);
    ResultSet rsUom = mock(ResultSet.class);
    when(rsUom.next()).thenReturn(false);
    when(psUom.executeQuery()).thenReturn(rsUom);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLineNo)
        .thenReturn(psUom);

    Invoice invoice = mockInvoice("inv-012");
    Product product = mock(Product.class);
    TaxRate tax = mock(TaxRate.class);
    when(dal.get(Invoice.class, "inv-012")).thenReturn(invoice);
    when(dal.get(Product.class, TotalDiscountService.DISCOUNT_PRODUCT_ID)).thenReturn(product);
    when(dal.get(TaxRate.class, "tax-001")).thenReturn(tax);

    InvoiceLine line = mock(InvoiceLine.class);
    when(provider.get(InvoiceLine.class)).thenReturn(line);

    new TotalDiscountService().recalculate("inv-012", true);

    ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
    verify(line).setUnitPrice(captor.capture());
    assertEquals(new BigDecimal("-33.33"), captor.getValue());
  }

  // ── Tests: order sets mandatory NOT NULL columns ──────────────────────────

  /**
   * Verifies that the order discount line explicitly sets the three mandatory
   * non-nullable columns required by the c_orderline schema:
   * orderDate, warehouse, and currency.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void recalculate_orderMandatoryColumnsSetOnLine() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(true);
    when(rsPct.getBigDecimal(1)).thenReturn(new BigDecimal("10"));
    when(psPct.executeQuery()).thenReturn(rsPct);

    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true, false);
    when(rsTax.getString(1)).thenReturn("tax-003");
    when(rsTax.getBigDecimal(2)).thenReturn(new BigDecimal("100"));
    when(psTax.executeQuery()).thenReturn(rsTax);

    PreparedStatement psLineNo = mock(PreparedStatement.class);
    ResultSet rsLineNo = mock(ResultSet.class);
    when(rsLineNo.next()).thenReturn(true);
    when(rsLineNo.getLong(1)).thenReturn(10L);
    when(psLineNo.executeQuery()).thenReturn(rsLineNo);

    PreparedStatement psUom = mock(PreparedStatement.class);
    ResultSet rsUom = mock(ResultSet.class);
    when(rsUom.next()).thenReturn(false);
    when(psUom.executeQuery()).thenReturn(rsUom);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel)
        .thenReturn(psTax)
        .thenReturn(psLineNo)
        .thenReturn(psUom);

    Order order = mockOrder("ord-004");
    Product product = mock(Product.class);
    TaxRate tax = mock(TaxRate.class);
    when(dal.get(Order.class, "ord-004")).thenReturn(order);
    when(dal.get(Product.class, TotalDiscountService.DISCOUNT_PRODUCT_ID)).thenReturn(product);
    when(dal.get(TaxRate.class, "tax-003")).thenReturn(tax);

    OrderLine line = mock(OrderLine.class);
    when(provider.get(OrderLine.class)).thenReturn(line);

    new TotalDiscountService().recalculate("ord-004", false);

    // All three mandatory NOT NULL columns must be propagated from the order header.
    verify(line).setOrderDate(order.getOrderDate());
    verify(line).setWarehouse(order.getWarehouse());
    verify(line).setCurrency(order.getCurrency());
    verify(dal).save(line);
  }

  // ── Tests: OBContext admin-mode lifecycle ─────────────────────────────────

  /**
   * recalculate must always call OBContext.setAdminMode(true) at the start and
   * OBContext.restorePreviousMode() in the finally block — even when pct is null.
   */
  @Test
  public void recalculate_adminModeAlwaysSetAndRestored_evenOnEarlyReturn() throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);

    // readDiscountPct → no row → null → early return
    PreparedStatement psPct = mock(PreparedStatement.class);
    ResultSet rsPct = mock(ResultSet.class);
    when(rsPct.next()).thenReturn(false);
    when(psPct.executeQuery()).thenReturn(rsPct);

    PreparedStatement psDel = mock(PreparedStatement.class);
    when(psDel.executeUpdate()).thenReturn(0);

    when(conn.prepareStatement(anyString()))
        .thenReturn(psPct)
        .thenReturn(psDel);

    new TotalDiscountService().recalculate("inv-adm", true);

    obContextMock.verify(() -> OBContext.setAdminMode(true));
    obContextMock.verify(OBContext::restorePreviousMode);
  }
}
