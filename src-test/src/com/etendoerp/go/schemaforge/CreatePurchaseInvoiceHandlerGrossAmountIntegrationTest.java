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
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.test.base.OBBaseTest;
import org.openbravo.test.base.TestConstants;
import org.openbravo.test.purchaseOrder.PurchaseOrderUtils;

/**
 * Real-DB regression coverage for ETP-4726: {@code Line_Gross_Amount} always
 * reads {@code 0.00} on Purchase Invoices generated via the Receipt→Invoice
 * conversion ("Crear factura" on a Goods Receipt), regardless of sign.
 *
 * <p>Root cause (see the Jira thread): {@link InvoiceFromOrderSupport#ensureLineGrossAmounts}
 * — the method that computes {@code grossAmount = lineNetAmount * (1 + taxRate/100)},
 * preserving sign — is already called by {@link CreatePurchaseInvoiceHandler#createFromOrder},
 * but was never wired into {@link CreatePurchaseInvoiceHandler#createFromReceipt} or
 * {@link CreatePurchaseInvoiceHandler#createFromReceiptNoPo} (confirmed via {@code git log}:
 * the method didn't even exist yet when {@code createFromReceipt} was first added in ETP-4032).
 * The fix is the two one-line additions in those methods, made alongside this test.
 *
 * <p><b>Test-tier note — why this is NOT a literal red→green test of {@code
 * createFromReceipt}/{@code createFromReceiptNoPo} themselves:</b> both methods call
 * {@code WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class)}
 * to build the invoice lines. Empirically confirmed (a throwaway probe run before writing
 * this test) that this fails with a {@code NullPointerException} on {@code
 * DalContextListener.getServletContext()} in this sandbox — there is no live servlet
 * container / CDI BeanManager available to a bare {@code OBBaseTest} JUnit run here, the
 * same class of environment gap already documented for other tests in this module (see
 * {@code ReactivatePaymentHandlerRemoveIntegrationTest}). {@code
 * InvoiceFromOrderSupport#ensureLineGrossAmounts}/{@code calculateLineGross} themselves
 * already have thorough existing Mockito coverage in {@code InvoiceFromOrderSupportTest}
 * (including a dedicated negative-net case) — their calculation correctness is not in
 * question here.
 *
 * <p>What this test verifies instead, against a REAL, persisted Invoice built with the
 * established {@link PurchaseOrderUtils} fixture chain (Order → Receipt → Invoice, the
 * exact shape {@code createFromReceipt} produces, including a negative-quantity line for
 * continuity with ETP-4567/ETP-4722): (1) that such an invoice's lines genuinely read back
 * {@code Line_Gross_Amount = 0} after a real DB round-trip when nothing computes it — this
 * is a real reproduction of the reported symptom, not a mock — and (2) that calling the
 * exact method now wired into production, {@code getSupport().ensureLineGrossAmounts(invoice)},
 * corrects both lines with the sign preserved, surviving a real reload from the database.
 *
 * <p>Because neither assertion here is driven through {@code createFromReceipt} itself
 * (impossible in this sandbox, per above), this pair does not literally go red before the
 * production fix and green after — both hold regardless of the production change. The
 * wiring change itself was verified by direct code review/diff (see the Jira comment).
 * Flagged explicitly rather than glossed over, per the team's own "make the case in
 * writing" allowance for a test tier that genuinely cannot be automated end-to-end.
 */
public class CreatePurchaseInvoiceHandlerGrossAmountIntegrationTest extends OBBaseTest {

  @Before
  public void setUp() {
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN,
        TestConstants.Clients.FB_GRP, TestConstants.Orgs.ESP);
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void receiptInvoiceLinesReadZeroGrossAmountWhenNeverComputed() {
    Invoice invoice = createReceiptInvoiceWithNegativeLine();

    reloadFromDb(invoice.getId());
    Invoice reloaded = OBDal.getInstance().get(Invoice.class, invoice.getId());

    for (InvoiceLine il : reloaded.getInvoiceLineList()) {
      BigDecimal gross = il.getGrossAmount();
      assertTrue("reproduces the exact reported symptom: a receipt-generated invoice line "
              + "with non-zero net amount (" + il.getLineNetAmount() + ") reads back a zero "
              + "gross amount when nothing ever computes it",
          gross == null || gross.compareTo(BigDecimal.ZERO) == 0);
    }
  }

  @Test
  public void ensureLineGrossAmountsCorrectsReceiptInvoiceLinesWithSignPreserved() {
    Invoice invoice = createReceiptInvoiceWithNegativeLine();

    // The exact call now wired into CreatePurchaseInvoiceHandler#createFromReceipt /
    // #createFromReceiptNoPo, right before they return the invoice.
    new CreatePurchaseInvoiceHandler().getSupport().ensureLineGrossAmounts(invoice);
    OBDal.getInstance().flush();

    reloadFromDb(invoice.getId());
    Invoice reloaded = OBDal.getInstance().get(Invoice.class, invoice.getId());

    boolean negativeLineFound = false;
    for (InvoiceLine il : reloaded.getInvoiceLineList()) {
      BigDecimal net = il.getLineNetAmount();
      BigDecimal gross = il.getGrossAmount();
      assertTrue("gross amount must actually be computed (non-zero) once net/tax are non-zero",
          gross != null && gross.compareTo(BigDecimal.ZERO) != 0);
      assertEquals("gross and net must carry the same sign", Integer.signum(net.signum()),
          Integer.signum(gross.signum()));
      if (net.compareTo(BigDecimal.ZERO) < 0) {
        negativeLineFound = true;
      }
    }
    assertTrue("sanity check: fixture must include the negative-quantity line", negativeLineFound);
  }

  /**
   * Builds a real Purchase Order (1 positive line via {@link PurchaseOrderUtils#createPurchaseOrder()},
   * plus a real negative line added here), a real Goods Receipt from it, and a real Purchase
   * Invoice from that receipt — the exact Order → Receipt → Invoice chain {@code
   * createFromReceipt} serves, with {@code Line_Gross_Amount} left untouched by construction
   * (neither {@link PurchaseOrderUtils#createPurchaseInvoice} nor the native invoice-line
   * creation it stands in for sets it).
   */
  private Invoice createReceiptInvoiceWithNegativeLine() {
    Order order = PurchaseOrderUtils.createPurchaseOrder();
    OrderLine positiveLine = order.getOrderLineList().get(0);

    OrderLine negativeLine = OBProvider.getInstance().get(OrderLine.class);
    negativeLine.setClient(order.getClient());
    negativeLine.setOrganization(order.getOrganization());
    negativeLine.setSalesOrder(order);
    negativeLine.setOrderDate(order.getOrderDate());
    negativeLine.setWarehouse(order.getWarehouse());
    negativeLine.setLineNo(20L);
    negativeLine.setProduct(positiveLine.getProduct());
    negativeLine.setOrderedQuantity(new BigDecimal("-2"));
    negativeLine.setUOM(positiveLine.getUOM());
    negativeLine.setUnitPrice(positiveLine.getUnitPrice());
    negativeLine.setLineNetAmount(new BigDecimal("-2").multiply(positiveLine.getUnitPrice()));
    negativeLine.setTax(positiveLine.getTax());
    negativeLine.setCurrency(positiveLine.getCurrency());
    OBDal.getInstance().save(negativeLine);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(order);

    ShipmentInOut receipt = PurchaseOrderUtils.createGoodsReceipt(order);
    return PurchaseOrderUtils.createPurchaseInvoice(receipt);
  }

  private void reloadFromDb(String invoiceId) {
    OBDal.getInstance().getSession().clear();
  }
}
