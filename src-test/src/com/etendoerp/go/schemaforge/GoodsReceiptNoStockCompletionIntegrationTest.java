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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.service.db.CallStoredProcedure;

/**
 * Integration coverage for ETP-4671 bug 1b: proves, end-to-end against a real database, whether
 * a Goods Receipt actually completes for a manually-added line whose product has zero prior
 * stock — the exact repro from the ticket ("create a product with NO stock, add it as a line,
 * try to confirm/complete the document").
 *
 * <p>Two scenarios: {@link #testCompletionFailsWhenUnstockedReceiptLineHasNoLocator()} builds
 * the line the way today's (pre-fix) create path leaves it — no locator at all — and confirms
 * completion is rejected. {@link #testCompletionSucceedsWhenHandlerInjectsDefaultLocator()}
 * builds the exact same scenario but routes line creation through the real
 * {@link GoodsReceiptLineHandler#handle(NeoContext)} POST pre-hook first, and confirms
 * completion now succeeds.
 *
 * <h3>What this test is — and is NOT — proving</h3>
 * {@code M_INOUT_POST}'s own {@code InoutLineWithoutLocator} rejection (see
 * {@code src-db/database/model/functions/M_INOUT_POST.xml}, the "check inout line instance
 * location" block) is CORRECT, standing core behavior: any {@code M_InOutLine} for a
 * stocked/purchased product must carry a locator before completion, receipt or shipment alike —
 * that check has no {@code IsSOTrx} condition and this test does not touch or dispute it. The
 * bug is one layer up: Schema Forge's create path leaves {@code storageBin} (M_Locator_ID)
 * unset for a manually-added receipt line whose product has no prior stock, because the raw AD
 * default ({@code @OnHandLocatorDefault@}) resolves to "the locator where this product already
 * has stock" — which is nothing for a brand-new product. So completion fails even though
 * receiving stock structurally should never require pre-existing stock to already exist.
 *
 * <p>Mirrors the clone-and-complete-via-{@code m_inout_post} technique already used in
 * {@code org.openbravo.test.inventoryStatus.InventoryStatusTest} (same
 * {@code CallStoredProcedure.getInstance().call("m_inout_post", ...)} call, same
 * {@code @Token@}-in-exception-message assertion style) rather than the heavier
 * {@code TestCostingBase} costing-rule bootstrap, which this scenario does not need.
 */
public class GoodsReceiptNoStockCompletionIntegrationTest extends WeldBaseTest {

  // QA Testing client/org — same context TestCostingConstants uses for its Goods Receipt
  // fixtures (org.openbravo.test.costing.utils.TestCostingConstants.QATESTING_CLIENT_ID /
  // SPAIN_ORGANIZATION_ID), reused here directly to avoid a second, parallel fixture universe.
  private static final String CLIENT_ID = "4028E6C72959682B01295A070852010D";
  private static final String ORG_ID = "357947E87C284935AD1D783CF6F099A1";
  private static final String USER_ID = "100";
  private static final String ROLE_ID = "4028E6C72959682B01295A071429011E";

  // Draft Purchase Receipt (IsSOTrx=N) with exactly one line — the same template
  // TestCostingUtils.cloneMovement() clones for its own Goods Receipt fixtures.
  private static final String RECEIPT_TEMPLATE_ID = "0450583047434254835B2B36B2E5B018";
  // Product to clone as a brand-new, zero-stock product (isstocked=Y, producttype=I).
  private static final String STOCKED_PRODUCT_TEMPLATE_ID = "4028E6C72959682B01295ADC211E0237";

  // "Spain East warehouse" — unlike the receipt template's own "Spain warehouse" (15 locators,
  // NONE flagged default), this one has exactly one default *and* active locator ("M01"). The
  // fix's warehouse-default-locator lookup needs a warehouse where that actually resolves, so
  // the header is deliberately moved here (same client/org as the template).
  private static final String WAREHOUSE_WITH_DEFAULT_LOCATOR_ID = "4D7B97565A024DB7B4C61650FA2B9560";
  private static final String EXPECTED_DEFAULT_LOCATOR_ID = "96DEDCC179504711A81497DE68900F49";

  private static final String DOCUMENT_STATUS_DRAFT = "DR";
  private static final String DOCUMENT_ACTION_COMPLETE = "CO";
  private static final String PROCESS_INOUT_POST = "m_inout_post";
  private static final String ERROR_INOUT_LINE_WITHOUT_LOCATOR = "@InoutLineWithoutLocator@";

  @Test
  public void testCompletionFailsWhenUnstockedReceiptLineHasNoLocator() throws Exception {
    OBContext.setOBContext(USER_ID, ROLE_ID, CLIENT_ID, ORG_ID);
    OBContext.setAdminMode(true);
    try {
      Product unstockedProduct = cloneUnstockedProduct("ETP4671NoStockA");
      ShipmentInOut receipt = cloneDraftReceiptHeader("ETP4671RcptA");
      addReceiptLine(receipt, unstockedProduct, new BigDecimal("5"), null /* no locator */);

      try {
        completeReceipt(receipt);
        fail("Expected M_INOUT_POST to reject this document: Schema Forge left storageBin "
            + "unset for a manually-added line whose product has zero prior stock, so the "
            + "classic locator-integrity check should still (correctly) refuse to complete it. "
            + "This is NOT proof that core's check is broken — the bug is that nothing "
            + "upstream supplied a locator for this line.");
      } catch (AssertionError ae) {
        throw ae;
      } catch (Exception e) {
        assertThat(
            "Completion must fail on the missing-locator integrity check specifically, not "
                + "some unrelated error: " + e.getMessage(),
            StringUtils.contains(e.getMessage(), ERROR_INOUT_LINE_WITHOUT_LOCATOR), equalTo(true));
        OBDal.getInstance().rollbackAndClose();
      }
    } finally {
      // Completion above is EXPECTED to fail, and a stored-procedure call that throws can
      // leave its own admin-mode push on the stack, so a single restorePreviousMode() does
      // not always unwind it and OBBaseTest then fails the test for leaking admin mode.
      // Drain the stack, as ReactivatePaymentHandlerRemoveIntegrationTest#rollbackChanges
      // does. The two sibling tests complete successfully, never leak, and keep the single
      // restore below.
      while (OBContext.getOBContext() != null && OBContext.getOBContext().isInAdministratorMode()) {
        OBContext.restorePreviousMode();
      }
    }
  }

  /**
   * Same repro as above, but this time the line is created the way {@code NeoCrudHandler} would
   * really do it: {@link GoodsReceiptLineHandler#handle(NeoContext)} — the actual POST pre-hook
   * added in a prior commit — runs on the create request body BEFORE the line is persisted.
   * It must inject the receiving warehouse's own default active locator, and completion must
   * then succeed.
   */
  @Test
  public void testCompletionSucceedsWhenHandlerInjectsDefaultLocator() throws Exception {
    OBContext.setOBContext(USER_ID, ROLE_ID, CLIENT_ID, ORG_ID);
    OBContext.setAdminMode(true);
    try {
      Product unstockedProduct = cloneUnstockedProduct("ETP4671NoStockB");
      ShipmentInOut receipt = cloneDraftReceiptHeader("ETP4671RcptB");

      // Simulate the real NEO create-line request: parentId + product, no storageBin —
      // exactly the payload the frontend sends for a manually-added row.
      JSONObject createBody = new JSONObject()
          .put("parentId", receipt.getId())
          .put("product", unstockedProduct.getId());
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST")
          .endpointType(NeoEndpointType.CRUD)
          .requestBody(createBody)
          .build();

      new GoodsReceiptLineHandler().handle(ctx);

      assertEquals("GoodsReceiptLineHandler.handle() must default storageBin to the receiving "
          + "warehouse's own default active locator when the product has no prior stock",
          EXPECTED_DEFAULT_LOCATOR_ID, createBody.getString("storageBin"));

      addReceiptLine(receipt, unstockedProduct, new BigDecimal("5"),
          createBody.getString("storageBin"));

      completeReceipt(receipt);
      OBDal.getInstance().refresh(receipt);

      assertEquals("Completion must succeed once the fix supplies a locator, even though the "
          + "product had zero prior stock", "CO", receipt.getDocumentStatus());
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Reproduces the LIVE production repro found when re-verifying this fix: the frontend does
   * NOT omit {@code storageBin} from the create payload — it sends the raw, unresolved AD
   * default literal {@code "@OnHandLocatorDefault@"} (confirmed via the live Tomcat log's
   * {@code NeoFieldFilter} "body recibido" entry). handle() must recognize that literal as
   * "not a real value" and override it, exactly as {@link #testCompletionSucceedsWhenHandlerInjectsDefaultLocator()}
   * proves for a genuinely missing key.
   */
  @Test
  public void testCompletionSucceedsWhenHandlerOverridesUnresolvedTokenLiteral() throws Exception {
    OBContext.setOBContext(USER_ID, ROLE_ID, CLIENT_ID, ORG_ID);
    OBContext.setAdminMode(true);
    try {
      Product unstockedProduct = cloneUnstockedProduct("ETP4671NoStockC");
      ShipmentInOut receipt = cloneDraftReceiptHeader("ETP4671RcptC");

      // Exact body shape observed live: storageBin is PRESENT, holding the raw AD default
      // token — not absent, not null. A naive "is the key missing?" guard would skip
      // injection here and let the literal reach persistence.
      JSONObject createBody = new JSONObject()
          .put("parentId", receipt.getId())
          .put("product", unstockedProduct.getId())
          .put("storageBin", "@OnHandLocatorDefault@");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST")
          .endpointType(NeoEndpointType.CRUD)
          .requestBody(createBody)
          .build();

      new GoodsReceiptLineHandler().handle(ctx);

      assertEquals("An unresolved '@Token@' literal must be overridden, not treated as an "
          + "already-supplied real value",
          EXPECTED_DEFAULT_LOCATOR_ID, createBody.getString("storageBin"));

      addReceiptLine(receipt, unstockedProduct, new BigDecimal("10"),
          createBody.getString("storageBin"));

      completeReceipt(receipt);
      OBDal.getInstance().refresh(receipt);

      assertEquals("Completion must succeed once the fix overrides the unresolved token, "
          + "matching the real browser repro (create product, add line, save)",
          "CO", receipt.getDocumentStatus());
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.restorePreviousMode();
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static Product cloneUnstockedProduct(String namePrefix) {
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

  private static ShipmentInOut cloneDraftReceiptHeader(String docNoPrefix) {
    ShipmentInOut template = OBDal.getInstance().get(ShipmentInOut.class, RECEIPT_TEMPLATE_ID);
    ShipmentInOut clone = (ShipmentInOut) DalUtil.copy(template, false);
    clone.setId(SequenceIdData.getUUID());
    clone.setNewOBObject(true);
    clone.setDocumentNo(docNoPrefix + "-" + SequenceIdData.getUUID().substring(0, 8));
    clone.setDocumentStatus(DOCUMENT_STATUS_DRAFT);
    clone.setDocumentAction(DOCUMENT_ACTION_COMPLETE);
    clone.setProcessed(false);
    clone.setMovementDate(new Date());
    clone.setWarehouse(
        OBDal.getInstance().get(Warehouse.class, WAREHOUSE_WITH_DEFAULT_LOCATOR_ID));
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
    // unrelated to this ticket's locator bug, so keep it in sync with the swapped product.
    clone.setUOM(product.getUOM());
    clone.setMovementQuantity(quantity);
    clone.setAttributeSetValue(null);
    clone.setStorageBin(locatorId == null ? null : OBDal.getInstance().get(Locator.class, locatorId));
    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    return clone;
  }

  private static void completeReceipt(ShipmentInOut receipt) throws Exception {
    List<Object> parameters = new ArrayList<>();
    parameters.add(null);
    parameters.add(receipt.getId());
    CallStoredProcedure.getInstance().call(PROCESS_INOUT_POST, parameters, null, true, false);
    OBDal.getInstance().flush();
  }
}
