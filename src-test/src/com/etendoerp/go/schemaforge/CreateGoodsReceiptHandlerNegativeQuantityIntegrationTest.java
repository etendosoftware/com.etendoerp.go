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
import java.util.Date;
import java.util.List;

import javax.enterprise.inject.Vetoed;

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
import org.openbravo.test.base.TestConstants;
import org.openbravo.test.base.OBBaseTest;
import org.openbravo.test.purchaseOrder.PurchaseOrderUtils;

/**
 * Real-DB regression test for ETP-4722: a Purchase Order line with a NEGATIVE
 * ordered quantity (allowed since ETP-4567 removed the {@code min: 0}
 * constraint) is silently dropped when {@link CreateGoodsReceiptHandler}
 * converts the order into a Goods Receipt — the generated {@code M_InOut}
 * only ends up containing the positive lines.
 *
 * <p>Root cause: {@link InOutLineFromOrderFactory#pendingQuantityFor(OrderLine)}
 * — the routine shared by both {@link CreateGoodsReceiptHandler} (purchase
 * side) and {@link CreateShipmentHandler} (sales side) — computes {@code
 * pending = orderedQty - deliveredQty} and only returns it (i.e. only keeps
 * the line) when {@code pending > 0}. For a negative-quantity line with no
 * prior delivery, {@code pending} is itself negative, fails the {@code > 0}
 * check, and the line is skipped instead of being carried over with its sign
 * preserved. This is 100% {@code com.etendoerp.go} code — not a call into
 * {@code org.openbravo.*} core stock logic — so the fix belongs here.
 *
 * <p>This test exercises the actual production method under test,
 * {@link CreateGoodsReceiptHandler#createReceiptLines(ShipmentInOut, Order)},
 * against a REAL, persisted {@link Order} + {@link OrderLine}s and a REAL
 * {@link ShipmentInOut} header — not mocks — because the risk here is a real
 * DB-side effect of a real conversion routine (order lines → M_InOut /
 * M_InOutLine), not a pure function whose behavior can be trusted from a
 * mocked unit test alone. Scoped to the purchase-order → goods-receipt side
 * (the flow with concrete SQL-documented evidence in ETP-4722); the sales
 * side ({@link CreateShipmentHandler#createShipmentLines}) shares the exact
 * same {@link InOutLineFromOrderFactory} routine fixed here, and is covered
 * at the UI level by the existing
 * {@code sales-quotation-full-flow.integration.spec.js} E2E in
 * {@code etendo_schema_forge}.
 */
public class CreateGoodsReceiptHandlerNegativeQuantityIntegrationTest extends OBBaseTest {

  /**
   * Test double that overrides {@link CreateGoodsReceiptHandler#findDefaultLocator}
   * to bypass the warehouse-locator lookup, matching the pattern already used by
   * {@link CreateGoodsReceiptHandlerTest}.
   */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class TestableHandler extends CreateGoodsReceiptHandler {
    Locator locatorToReturn;

    @Override
    protected Locator findDefaultLocator(Order order) {
      return locatorToReturn;
    }
  }

  @Before
  public void setUp() {
    // Same F&B Group / Org ESP context PurchaseOrderUtils.createPurchaseOrder() is
    // already proven to work under (see org.openbravo.test.purchaseOrder.PurchaseOrderStatus).
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN,
        TestConstants.Clients.FB_GRP, TestConstants.Orgs.ESP);
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void createReceiptLinesIncludesNegativeQuantityOrderLine() {
    // ── Real Purchase Order with 1 positive line, via the established test fixture ──
    Order order = PurchaseOrderUtils.createPurchaseOrder();
    OrderLine positiveLine = order.getOrderLineList().get(0);

    // ── Add a second, real, NEGATIVE-quantity line to the same order ────────────────
    OrderLine negativeLine = OBProvider.getInstance().get(OrderLine.class);
    negativeLine.setClient(order.getClient());
    negativeLine.setOrganization(order.getOrganization());
    negativeLine.setSalesOrder(order);
    negativeLine.setOrderDate(new Date());
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

    assertEquals("sanity check: order must have both lines before conversion",
        2, order.getOrderLineList().size());

    // ── Real Goods Receipt header, built exactly like the production handler does ───
    DocumentType docType = findReceiptDocType(order);
    ShipmentInOut receipt = NeoCommercialDocumentFactory.createShipmentReceiptHeader(
        order, docType, false, "V+");
    OBDal.getInstance().save(receipt);
    OBDal.getInstance().flush();

    Locator locator = OBDal.getInstance().get(Locator.class, PurchaseOrderUtils.LOCATOR_RN_ID);

    // ── Run the ACTUAL production conversion routine under test ─────────────────────
    TestableHandler handler = new TestableHandler();
    handler.locatorToReturn = locator;
    handler.createReceiptLines(receipt, order);

    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(receipt);

    // ── The bug: the receipt only ever had the positive line ────────────────────────
    List<ShipmentInOutLine> receiptLines = receipt.getMaterialMgmtShipmentInOutLineList();
    assertEquals("the receipt must include BOTH order lines, including the negative-quantity "
        + "one that ETP-4722 reports as silently dropped", 2, receiptLines.size());

    boolean negativeLineFound = false;
    for (ShipmentInOutLine line : receiptLines) {
      if (line.getMovementQuantity().compareTo(BigDecimal.ZERO) < 0) {
        negativeLineFound = true;
        assertEquals("the negative sign must be preserved on the generated receipt line",
            0, new BigDecimal("-2").compareTo(line.getMovementQuantity()));
      }
    }
    assertTrue("expected one receipt line with a negative movement quantity", negativeLineFound);
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
