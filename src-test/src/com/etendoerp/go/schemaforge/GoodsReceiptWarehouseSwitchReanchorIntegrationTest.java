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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.transaction.MaterialTransaction;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.service.db.CallStoredProcedure;

/**
 * Integration coverage for the REOPENED ETP-4863 bug: proves, end-to-end against a real
 * database (no mocks), the full invariant the fix promises — after a line is created while the
 * header points at warehouse A, and the header is then switched to warehouse B BEFORE the
 * document is confirmed, BOTH the line's {@code storageBin} AND the {@code M_Transaction} row(s)
 * that {@code M_INOUT_POST} generates on completion must end up anchored to warehouse B, never
 * to the stale warehouse A. This is the exact production repro (RFC/albarán doc {@code 10000120}
 * in the experimental environment): header in "Almacén Secundario", stock transaction landed in
 * "Almacén Principal" because nothing re-anchored the line after the header's warehouse changed.
 *
 * <p>Deliberately a separate file from {@link GoodsReceiptNoStockCompletionIntegrationTest}
 * (ETP-4671 scope: a line with NO locator at all). This class starts from a line that already
 * has a VALID locator — just one belonging to the WRONG (now-stale) warehouse — which is the
 * actual shape of the reopened bug and was not covered by any existing test, mocked or real.
 *
 * <p>Exercises the real wiring: {@link GoodsReceiptHeaderHandler#handle(NeoContext)} with a
 * {@code documentAction}/POST {@link NeoContext}, exactly as {@code NeoServlet} would dispatch
 * it — not just the internal {@code NeoHandlerUtils.reanchorLinesToHeaderWarehouse} helper in
 * isolation, which is already covered by mocked unit tests in {@code NeoHandlerUtilsTest} and
 * {@code GoodsReceiptHeaderHandlerTest}.
 *
 * <p>Warehouse B ("Spain warehouse", {@code 4028E6C72959682B01295ECFEF4502A0}) is the SAME
 * client/org's second active warehouse, deliberately the one with NO {@code isDefault} locator
 * (15 active bins, none flagged default — the exact "no default bin" shape excluded from
 * {@link GoodsReceiptNoStockCompletionIntegrationTest}'s own {@code WAREHOUSE_WITH_DEFAULT_LOCATOR_ID}
 * choice), so this test additionally exercises cascade step 3
 * ({@code NeoHandlerUtils.findAnyActiveLocatorForWarehouse}) end-to-end against real data, not
 * just step 2.
 *
 * <p>A Goods Receipt only ever ADDS stock (inbound, {@code IsSOTrx = 'N'}), so — unlike a Goods
 * Shipment or Return to Vendor Shipment — completion can never be rejected here for insufficient
 * stock at the destination locator; that scenario needs a warehouse with pre-existing stock and
 * is intentionally left out of THIS test (see {@code docs/} / the QA report for this ticket for
 * why the Shipment side is a documented gap, not silently skipped).
 */
public class GoodsReceiptWarehouseSwitchReanchorIntegrationTest extends WeldBaseTest {

  // QA Testing client/org — same fixtures GoodsReceiptNoStockCompletionIntegrationTest (ETP-4671)
  // uses, reused here directly to avoid a second, parallel fixture universe.
  private static final String CLIENT_ID = "4028E6C72959682B01295A070852010D";
  private static final String ORG_ID = "357947E87C284935AD1D783CF6F099A1";
  private static final String USER_ID = "100";
  private static final String ROLE_ID = "4028E6C72959682B01295A071429011E";

  // Draft Purchase Receipt (IsSOTrx=N) with exactly one line — same template the sibling class
  // clones.
  private static final String RECEIPT_TEMPLATE_ID = "0450583047434254835B2B36B2E5B018";
  // Product to clone as a fresh product for this test's own line.
  private static final String STOCKED_PRODUCT_TEMPLATE_ID = "4028E6C72959682B01295ADC211E0237";

  // Warehouse A — the header's warehouse AT LINE-CREATE TIME. "Spain East warehouse": has an
  // isDefault active locator, so the line starts out correctly anchored to A — until the header
  // switches to B below, which is exactly what leaves it stale.
  private static final String WAREHOUSE_A_ID = "4D7B97565A024DB7B4C61650FA2B9560";
  private static final String LOCATOR_IN_A_ID = "96DEDCC179504711A81497DE68900F49";

  // Warehouse B — the header's warehouse AT CONFIRM TIME (user switched it AFTER the line was
  // created, BEFORE completing — the exact ETP-4863 repro). "Spain warehouse": no isDefault
  // locator among its 15 active bins, so the reanchor must fall back to cascade step 3
  // (lowest-searchKey active locator) to resolve it, deterministically "B01".
  private static final String WAREHOUSE_B_ID = "4028E6C72959682B01295ECFEF4502A0";
  private static final String EXPECTED_LOCATOR_IN_B_ID = "ABD3492972FA40E9A273FFAE91033F45";

  private static final String DOCUMENT_STATUS_DRAFT = "DR";
  private static final String DOCUMENT_ACTION_COMPLETE = "CO";
  private static final String PROCESS_INOUT_POST = "m_inout_post";

  /**
   * The core ETP-4863 invariant, proven end-to-end:
   * <ol>
   *   <li>line is created while the header points at warehouse A → line's own {@code storageBin}
   *       is A's default locator (simulating what {@code GoodsReceiptLineHandler}'s create hook
   *       would have set at the time);</li>
   *   <li>the header's warehouse is switched to B (the user re-picks the warehouse before
   *       confirming, without touching the line);</li>
   *   <li>the real {@link GoodsReceiptHeaderHandler#handle(NeoContext)} is invoked with a
   *       {@code documentAction}/POST context — the exact hook the fix wired in — BEFORE the
   *       native completion flow runs;</li>
   *   <li>the line's {@code storageBin} must already be re-anchored to B at that point, before
   *       {@code M_INOUT_POST} even starts;</li>
   *   <li>{@code M_INOUT_POST} completes the document for real (via
   *       {@code CallStoredProcedure}, same technique as the ETP-4671 sibling test);</li>
   *   <li>the document must be {@code Processed}/{@code CO};</li>
   *   <li>AND — the part no earlier test (mocked or real) had verified — the
   *       {@code M_Transaction} row(s) {@code M_INOUT_POST} generates for this line must be
   *       booked at a locator belonging to warehouse B, never to the stale warehouse A. This is
   *       the actual, observable stock-movement outcome QA's original bug report was about: the
   *       transaction landing in the wrong warehouse, not just the line's displayed bin.</li>
   * </ol>
   */
  @Test
  public void testWarehouseSwitchAfterLineCreationReanchorsBinAndStockTransactionToNewWarehouse()
      throws Exception {
    OBContext.setOBContext(USER_ID, ROLE_ID, CLIENT_ID, ORG_ID);
    OBContext.setAdminMode(true);
    try {
      Product product = cloneProduct("ETP4863SwitchWH");
      ShipmentInOut receipt = cloneDraftReceiptHeader("ETP4863Rcpt", WAREHOUSE_A_ID);
      ShipmentInOutLine line = addReceiptLine(receipt, product, new BigDecimal("7"), LOCATOR_IN_A_ID);

      // Sanity check on the fixture itself: the line really does start out anchored to A.
      assertEquals("Fixture setup: line must start anchored to warehouse A's own locator",
          LOCATOR_IN_A_ID, line.getStorageBin().getId());

      // The user switches the header's warehouse to B BEFORE confirming — nothing touches the
      // line itself at this point. This is the exact ETP-4863 repro step.
      receipt.setWarehouse(OBDal.getInstance().get(Warehouse.class, WAREHOUSE_B_ID));
      OBDal.getInstance().save(receipt);
      OBDal.getInstance().flush();

      // Exercise the REAL wired fix — GoodsReceiptHeaderHandler.handle() with a
      // documentAction/POST NeoContext, exactly as NeoServlet dispatches it — not just the
      // internal helper directly.
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName("documentAction")
          .recordId(receipt.getId())
          .build();
      assertNull("handle() must return null so the native completion flow still runs",
          new GoodsReceiptHeaderHandler().handle(ctx));

      OBDal.getInstance().refresh(line);
      assertEquals(
          "Line storageBin must already be re-anchored to warehouse B's own locator BEFORE "
              + "M_INOUT_POST runs",
          EXPECTED_LOCATOR_IN_B_ID, line.getStorageBin().getId());

      completeReceipt(receipt);
      OBDal.getInstance().refresh(receipt);
      assertEquals("Document must complete", DOCUMENT_ACTION_COMPLETE, receipt.getDocumentStatus());

      OBDal.getInstance().refresh(line);
      assertEquals("Line storageBin must still belong to warehouse B after completion",
          EXPECTED_LOCATOR_IN_B_ID, line.getStorageBin().getId());

      // The actual invariant QA's bug report was about: the STOCK TRANSACTION, not just the
      // line's displayed bin, must have landed in warehouse B.
      List<MaterialTransaction> transactions = fetchTransactionsForLine(line.getId());
      assertFalse("M_INOUT_POST must have generated at least one M_Transaction for this line",
          transactions.isEmpty());
      for (MaterialTransaction transaction : transactions) {
        Locator transactionLocator = transaction.getStorageBin();
        assertNotNull("Every M_Transaction row for this line must carry a locator",
            transactionLocator);
        assertNotNull(transactionLocator.getWarehouse());
        assertEquals(
            "M_Transaction locator must belong to warehouse B — this is the exact production "
                + "bug (doc 10000120): the stock movement following the line's STALE bin into "
                + "warehouse A instead of the header's actual warehouse",
            WAREHOUSE_B_ID, transactionLocator.getWarehouse().getId());
        assertNotEquals(
            "M_Transaction locator must NOT belong to the stale warehouse A",
            WAREHOUSE_A_ID, transactionLocator.getWarehouse().getId());
      }
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.restorePreviousMode();
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static Product cloneProduct(String namePrefix) {
    Product template = OBDal.getInstance().get(Product.class, STOCKED_PRODUCT_TEMPLATE_ID);
    Product clone = (Product) DalUtil.copy(template, false);
    String suffix = SequenceIdData.getUUID().substring(0, 8);
    clone.setId(SequenceIdData.getUUID());
    clone.setNewOBObject(true);
    clone.setSearchKey(namePrefix + "-" + suffix);
    clone.setName(namePrefix + "-" + suffix);
    clone.setMaterialMgmtMaterialTransactionList(new ArrayList<>());
    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    return clone;
  }

  private static ShipmentInOut cloneDraftReceiptHeader(String docNoPrefix, String warehouseId) {
    ShipmentInOut template = OBDal.getInstance().get(ShipmentInOut.class, RECEIPT_TEMPLATE_ID);
    ShipmentInOut clone = (ShipmentInOut) DalUtil.copy(template, false);
    clone.setId(SequenceIdData.getUUID());
    clone.setNewOBObject(true);
    clone.setDocumentNo(docNoPrefix + "-" + SequenceIdData.getUUID().substring(0, 8));
    clone.setDocumentStatus(DOCUMENT_STATUS_DRAFT);
    clone.setDocumentAction(DOCUMENT_ACTION_COMPLETE);
    clone.setProcessed(false);
    clone.setMovementDate(new Date());
    clone.setWarehouse(OBDal.getInstance().get(Warehouse.class, warehouseId));
    clone.setMaterialMgmtShipmentInOutLineList(new ArrayList<>());
    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    return clone;
  }

  private static ShipmentInOutLine addReceiptLine(ShipmentInOut receipt, Product product,
      BigDecimal quantity, String locatorId) {
    ShipmentInOut templateHeader = OBDal.getInstance().get(ShipmentInOut.class, RECEIPT_TEMPLATE_ID);
    ShipmentInOutLine template = templateHeader.getMaterialMgmtShipmentInOutLineList().get(0);
    ShipmentInOutLine clone = (ShipmentInOutLine) DalUtil.copy(template, false);
    clone.setId(SequenceIdData.getUUID());
    clone.setNewOBObject(true);
    clone.setShipmentReceipt(receipt);
    clone.setSalesOrderLine(null);
    clone.setProduct(product);
    // M_INOUTLINE_TRG raises @20111@ if C_UOM_ID doesn't match the (new) product's own UOM —
    // unrelated to this ticket's bug, so keep it in sync with the swapped product.
    clone.setUOM(product.getUOM());
    clone.setMovementQuantity(quantity);
    clone.setAttributeSetValue(null);
    clone.setStorageBin(locatorId == null ? null : OBDal.getInstance().get(Locator.class, locatorId));
    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    // cloneDraftReceiptHeader() replaced the header's line collection with a bare in-memory
    // ArrayList (not a Hibernate-managed PersistentBag), so it is never auto-populated just by
    // setting the FK on the line side — assignBinsToLines() iterates THIS list, so the line must
    // be added to it explicitly or the reanchor call sees zero lines and silently no-ops.
    receipt.getMaterialMgmtShipmentInOutLineList().add(clone);
    return clone;
  }

  private static void completeReceipt(ShipmentInOut receipt) throws Exception {
    List<Object> parameters = new ArrayList<>();
    parameters.add(null);
    parameters.add(receipt.getId());
    CallStoredProcedure.getInstance().call(PROCESS_INOUT_POST, parameters, null, true, false);
    OBDal.getInstance().flush();
  }

  @SuppressWarnings("unchecked")
  private static List<MaterialTransaction> fetchTransactionsForLine(String lineId) {
    return OBDal.getInstance().createCriteria(MaterialTransaction.class)
        .add(Restrictions.eq(MaterialTransaction.PROPERTY_GOODSSHIPMENTLINE + ".id", lineId))
        .list();
  }
}
