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
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.test.base.OBBaseTest;
import org.openbravo.test.base.TestConstants;
import org.openbravo.test.purchaseOrder.PurchaseOrderUtils;

/**
 * Real-DB regression test for the ETP-4722 follow-up finding: the same class
 * of "negative-quantity line silently dropped" bug that {@link
 * InOutLineFromOrderFactory} had for Order → Receipt/Shipment (already fixed)
 * reappears one step later in the chain, Receipt → Purchase Invoice.
 *
 * <p>Live evidence (from the ETP-4722 Jira thread): a real Goods Receipt with
 * 2 lines (qty 1 and qty -2, both linked to their Purchase Order lines) was
 * converted into a Purchase Invoice via "Crear factura" and the generated
 * invoice only contained the qty-1 line — the qty -2 line vanished.
 *
 * <p>Root cause is NOT textually identical to the {@code
 * InOutLineFromOrderFactory} fix — there is no shared {@code
 * pendingQuantityFor}-style helper here. {@link CreatePurchaseInvoiceHandler}
 * builds the invoice's line input (a {@code JSONArray} later consumed by the
 * native Etendo {@code CreateInvoiceLinesFromProcess}) via three independent,
 * inline sign-unaware filters:
 * <ul>
 *   <li>{@link CreatePurchaseInvoiceHandler#buildSelectedLinesFromReceipt} —
 *       {@code qty.compareTo(BigDecimal.ZERO) > 0} (linked-PO receipt path;
 *       this is the one with concrete DB evidence above).</li>
 *   <li>{@code buildNoPoLineEntry} — {@code qty.compareTo(BigDecimal.ZERO) <= 0}
 *       returns null (no-PO receipt path, same routine family).</li>
 *   <li>{@code parseLineOverrides} — {@code qty.compareTo(BigDecimal.ZERO) > 0}
 *       (explicit per-line quantity overrides from the request body).</li>
 * </ul>
 * All three are fixed together (same conversion routine, same class of bug);
 * this test exercises the first one directly, since it is the exact,
 * concretely-evidenced path ("Crear factura" on a Goods Receipt with no
 * explicit body, i.e. the default/common case).
 *
 * <p>Scoped to {@link CreatePurchaseInvoiceHandler#buildSelectedLinesFromReceipt}
 * rather than the full {@code createFromReceipt}/{@code handle} entry point:
 * the outer method also invokes the native, Weld-managed {@code
 * CreateInvoiceLinesFromProcess}, which is unrelated to this bug (it only
 * ever sees whatever {@code buildSelectedLinesFromReceipt} decided to hand
 * it) and would require a full CDI container to exercise in a test. This
 * mirrors the same "test the routine that owns the decision, not the whole
 * endpoint" scoping used for {@code CreateGoodsReceiptHandlerNegativeQuantityIntegrationTest}.
 *
 * <p><b>ETP-4567 follow-up (QA "Hallazgo 1"):</b> the sign-unaware filter was fixed
 * above (line-item level, Receipt → Invoice) but one sibling was missed: {@link
 * CreatePurchaseInvoiceHandler#getPendingQuantity}, used only by {@link
 * CreatePurchaseInvoiceHandler#buildSelectedLines}, which backs the *direct*
 * PO → Purchase Invoice action (no Goods Receipt involved — {@code createFromOrder}).
 * It still used {@code pending.compareTo(BigDecimal.ZERO) > 0}. That is invisible for
 * a mixed-sign order (a positive line still populates {@code selectedLines}), which is
 * exactly why the ETP-4722 fix and its E2E coverage didn't catch it — but when
 * EVERY line on the order is negative (a fully-negative-total PO), every line is
 * filtered out, {@code selectedLines} ends up empty, and the handler throws
 * "No pending lines to invoice in this purchase order". See {@link
 * #buildSelectedLinesIncludesLinesWhenOrderTotalIsFullyNegative}.
 */
public class CreatePurchaseInvoiceHandlerNegativeQuantityIntegrationTest extends OBBaseTest {

  @Before
  public void setUp() {
    // Same F&B Group / Org ESP context PurchaseOrderUtils.createPurchaseOrder() is
    // already proven to work under (see org.openbravo.test.purchaseOrder.PurchaseOrderStatus).
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN,
        TestConstants.Clients.FB_GRP, TestConstants.Orgs.ESP);
    // PurchaseOrderUtils stamps accountingDate=new Date() on every header it saves; open today's
    // fiscal period so the save doesn't fail on a clean env or once the seeded period prefix lapses.
    PeriodTestUtils.ensureOpenPeriod(new Date());
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void buildSelectedLinesFromReceiptIncludesNegativeQuantityLine() throws Exception {
    // ── Real Purchase Order with a positive line + a real negative line ─────────────
    Order order = PurchaseOrderUtils.createPurchaseOrder();
    OrderLine positiveOrderLine = order.getOrderLineList().get(0);

    OrderLine negativeOrderLine = OBProvider.getInstance().get(OrderLine.class);
    negativeOrderLine.setClient(order.getClient());
    negativeOrderLine.setOrganization(order.getOrganization());
    negativeOrderLine.setSalesOrder(order);
    negativeOrderLine.setOrderDate(new Date());
    negativeOrderLine.setWarehouse(order.getWarehouse());
    negativeOrderLine.setLineNo(20L);
    negativeOrderLine.setProduct(positiveOrderLine.getProduct());
    negativeOrderLine.setOrderedQuantity(new BigDecimal("-2"));
    negativeOrderLine.setUOM(positiveOrderLine.getUOM());
    negativeOrderLine.setUnitPrice(positiveOrderLine.getUnitPrice());
    negativeOrderLine.setLineNetAmount(new BigDecimal("-2").multiply(positiveOrderLine.getUnitPrice()));
    negativeOrderLine.setTax(positiveOrderLine.getTax());
    negativeOrderLine.setCurrency(positiveOrderLine.getCurrency());
    OBDal.getInstance().save(negativeOrderLine);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(order);

    // ── Real Goods Receipt, with 2 real lines linked back to those order lines ──────
    DocumentType receiptDocType = findReceiptDocType(order);
    Locator locator = OBDal.getInstance().get(Locator.class, PurchaseOrderUtils.LOCATOR_RN_ID);

    ShipmentInOut receipt = NeoCommercialDocumentFactory.createShipmentReceiptHeader(
        order, receiptDocType, false, "V+");
    OBDal.getInstance().save(receipt);
    OBDal.getInstance().flush();

    ShipmentInOutLine positiveReceiptLine = newReceiptLine(receipt, positiveOrderLine, locator,
        10L, positiveOrderLine.getOrderedQuantity());
    ShipmentInOutLine negativeReceiptLine = newReceiptLine(receipt, negativeOrderLine, locator,
        20L, negativeOrderLine.getOrderedQuantity());
    OBDal.getInstance().save(positiveReceiptLine);
    OBDal.getInstance().save(negativeReceiptLine);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(receipt);

    assertEquals("sanity check: receipt must carry both lines before conversion to invoice",
        2, receipt.getMaterialMgmtShipmentInOutLineList().size());

    // ── Run the ACTUAL production routine under test, exactly as "Crear factura" does
    // it with no explicit body (qtyOverrides empty -> full movement qty per line) ────
    CreatePurchaseInvoiceHandler handler = new CreatePurchaseInvoiceHandler();
    JSONArray selectedLines = handler.buildSelectedLinesFromReceipt(
        receipt, Collections.emptyMap(), order);

    // ── The bug: only the positive line ever made it into selectedLines ─────────────
    assertEquals("selectedLines must include BOTH order lines, including the negative-quantity "
        + "one that ETP-4722 reports as silently dropped when converting a Goods Receipt into "
        + "a Purchase Invoice", 2, selectedLines.length());

    boolean negativeLineFound = false;
    for (int i = 0; i < selectedLines.length(); i++) {
      JSONObject entry = selectedLines.getJSONObject(i);
      if (negativeOrderLine.getId().equals(entry.getString("id"))) {
        negativeLineFound = true;
        assertEquals("the negative sign must be preserved in the invoice line input",
            0, new BigDecimal("-2").compareTo(new BigDecimal(entry.getString("orderedQuantity"))));
      }
    }
    assertTrue("expected an entry for the negative-quantity order line", negativeLineFound);
  }

  @Test
  public void buildSelectedLinesIncludesLinesWhenOrderTotalIsFullyNegative() throws Exception {
    // ── Real Purchase Order whose ENTIRE total is negative (every line negative,
    // return-style PO — QA's exact repro: "todas las líneas con cantidad negativa") ──
    Order order = PurchaseOrderUtils.createPurchaseOrder();
    OrderLine firstLine = order.getOrderLineList().get(0);
    firstLine.setOrderedQuantity(new BigDecimal("-1"));
    firstLine.setLineNetAmount(new BigDecimal("-1").multiply(firstLine.getUnitPrice()));
    OBDal.getInstance().save(firstLine);

    OrderLine secondLine = OBProvider.getInstance().get(OrderLine.class);
    secondLine.setClient(order.getClient());
    secondLine.setOrganization(order.getOrganization());
    secondLine.setSalesOrder(order);
    secondLine.setOrderDate(new Date());
    secondLine.setWarehouse(order.getWarehouse());
    secondLine.setLineNo(20L);
    secondLine.setProduct(firstLine.getProduct());
    secondLine.setOrderedQuantity(new BigDecimal("-2"));
    secondLine.setUOM(firstLine.getUOM());
    secondLine.setUnitPrice(firstLine.getUnitPrice());
    secondLine.setLineNetAmount(new BigDecimal("-2").multiply(firstLine.getUnitPrice()));
    secondLine.setTax(firstLine.getTax());
    secondLine.setCurrency(firstLine.getCurrency());
    OBDal.getInstance().save(secondLine);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(order);

    assertEquals("sanity check: order must carry both fully-negative lines",
        2, order.getOrderLineList().size());

    // ── Run the ACTUAL production routine under test: the direct PO -> Purchase
    // Invoice path ("Crear factura" straight on a Purchase Order, no Goods Receipt
    // involved), exactly as QA reproduced it ──────────────────────────────────────
    CreatePurchaseInvoiceHandler handler = new CreatePurchaseInvoiceHandler();
    JSONArray selectedLines = handler.buildSelectedLines(order);

    // ── The bug: a strictly-positive pending check drops every line when the WHOLE
    // order total is negative, leaving selectedLines empty and the caller throwing
    // "No pending lines to invoice in this purchase order" (ETP-4567) ───────────────
    assertEquals("selectedLines must include every negative-quantity line when the order's "
        + "entire total is negative, not just when a positive line offsets it",
        2, selectedLines.length());

    boolean firstLineFound = false;
    boolean secondLineFound = false;
    for (int i = 0; i < selectedLines.length(); i++) {
      JSONObject entry = selectedLines.getJSONObject(i);
      if (firstLine.getId().equals(entry.getString("id"))) {
        firstLineFound = true;
        assertEquals("the negative sign must be preserved in the invoice line input",
            0, new BigDecimal("-1").compareTo(new BigDecimal(entry.getString("orderedQuantity"))));
      }
      if (secondLine.getId().equals(entry.getString("id"))) {
        secondLineFound = true;
        assertEquals("the negative sign must be preserved in the invoice line input",
            0, new BigDecimal("-2").compareTo(new BigDecimal(entry.getString("orderedQuantity"))));
      }
    }
    assertTrue("expected an entry for the first negative-quantity order line", firstLineFound);
    assertTrue("expected an entry for the second negative-quantity order line", secondLineFound);
  }

  private ShipmentInOutLine newReceiptLine(ShipmentInOut receipt, OrderLine orderLine,
      Locator locator, long lineNo, BigDecimal movementQty) {
    ShipmentInOutLine line = OBProvider.getInstance().get(ShipmentInOutLine.class);
    line.setClient(receipt.getClient());
    line.setOrganization(receipt.getOrganization());
    line.setShipmentReceipt(receipt);
    line.setLineNo(lineNo);
    line.setProduct(orderLine.getProduct());
    line.setUOM(orderLine.getUOM());
    line.setStorageBin(locator);
    line.setMovementQuantity(movementQty);
    line.setSalesOrderLine(orderLine);
    return line;
  }

  /** Mirrors {@link CreateGoodsReceiptHandler#findReceiptDocType(Order)} (private there). */
  private DocumentType findReceiptDocType(Order order) {
    List<DocumentType> results = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_CLIENT, order.getClient()))
        .add(Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, "MMR"))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, false))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true))
        .addOrderBy(DocumentType.PROPERTY_DEFAULT, false)
        .setMaxResults(1)
        .list();
    return results.isEmpty() ? null : results.get(0);
  }
}
