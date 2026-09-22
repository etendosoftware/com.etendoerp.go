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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.service.db.CallStoredProcedure;

/**
 * Real-DB regression coverage for ETP-5313/ETP-5336: proves, against the actual {@code
 * m_inout_post} stored procedure, that the stored-negative / exposed-positive quantity policy
 * ({@link ReturnLineQuantityPolicy}) produces the correct real-world stock movement for both
 * sales returns (Return Material Receipt) and purchase returns (Return to Vendor Shipment).
 *
 * <p>This is the automated replacement for the manual verification performed against a live
 * local instance while implementing ETP-5313: ship N units (stock -N), return the full/partial
 * quantity (stock back up), and confirm the purchase side — already correct before this change
 * — keeps decreasing stock as expected.
 *
 * <p>Root cause background (see {@link ReturnLineQuantityPolicy}'s javadoc for the full
 * rationale): {@code M_INOUT_TRG_PROV} forces {@code M_InOut.MovementType} from {@code IsSOTrx}
 * alone — {@code 'C-'} for every sales document (shipment AND sales return alike), {@code 'V+'}
 * for every purchase document (receipt AND purchase return alike). {@code M_INOUT_POST} then
 * negates {@code MovementQty}/{@code QuantityOrder} whenever {@code MovementType} ends in
 * {@code '-'} (see {@code src-db/database/model/functions/M_INOUT_POST.xml}, the
 * {@code SUBSTR(Cur_InOut.MovementType, 2)='-'} check). A sales return must INCREASE stock
 * despite carrying {@code 'C-'} (the same code as a normal shipment, which decreases it) — the
 * only lever is storing {@code MovementQty} negative so the negation flips it back positive. A
 * purchase return must DECREASE stock despite carrying {@code 'V+'} (the same code as a normal
 * receipt) — same lever, since {@code 'V+'} is never negated.
 *
 * <p>Fixtures follow the same clone-a-known-template-and-complete-via-{@code m_inout_post}
 * technique already used by {@link CreateGoodsReceiptHandlerNegativeQuantityIntegrationTest} and
 * {@link GoodsReceiptNoStockCompletionIntegrationTest} (in turn mirroring {@code
 * org.openbravo.test.inventoryStatus.InventoryStatusTest}), reusing the SAME QA Testing
 * client/org and "Spain East warehouse" (has exactly one default AND active locator, "M01")
 * those two tests already validated works for this stored procedure.
 */
public class ReturnLineQuantitySignIntegrationTest extends WeldBaseTest {

  // QA Testing client/org — same context GoodsReceiptNoStockCompletionIntegrationTest uses.
  private static final String CLIENT_ID = "4028E6C72959682B01295A070852010D";
  private static final String ORG_ID = "357947E87C284935AD1D783CF6F099A1";
  private static final String USER_ID = "100";
  private static final String ROLE_ID = "4028E6C72959682B01295A071429011E";

  // "Spain East warehouse" — has exactly one default AND active locator ("M01"), unlike the
  // templates' own "Spain warehouse" (15 locators, none flagged default).
  private static final String WAREHOUSE_ID = "4D7B97565A024DB7B4C61650FA2B9560";
  private static final String LOCATOR_ID = "96DEDCC179504711A81497DE68900F49";

  // Draft Purchase Receipt (IsSOTrx=N) with exactly one line — used both to seed stock headroom
  // before a sales shipment, and as the source document for the purchase-return regression.
  private static final String RECEIPT_TEMPLATE_ID = "0450583047434254835B2B36B2E5B018";
  // Draft Goods Shipment (IsSOTrx=Y) with exactly one line — the sales-side source document.
  private static final String SHIPMENT_TEMPLATE_ID = "2BCCC64DA82A48C3976B4D007315C2C9";
  // Stocked (isstocked=Y, producttype=I) product template, cloned fresh per test.
  private static final String PRODUCT_TEMPLATE_ID = "4028E6C72959682B01295ADC211E0237";
  // "RFC Receipt" doc type (MMS, IsSOTrx=Y, IsReturn=Y) for the QA Testing client.
  private static final String RFC_RECEIPT_DOCTYPE_ID = "4683C39FF3B242CD8A5B5825550C4472";
  // "VAT 3%" tax rate for the QA Testing client — same id used by
  // CreateDraftInvoiceHandlerNegativeQuantityIntegrationTest. C_InvoiceLine's own
  // c_invoiceline_check2 constraint requires a tax whenever LineNetAmt != 0.
  private static final String TAX_ID = "5A74E390B82747F9A5754C8EB1BDB47A";

  private static final String PROCESS_INOUT_POST = "m_inout_post";

  @Before
  public void setUp() {
    OBContext.setOBContext(USER_ID, ROLE_ID, CLIENT_ID, ORG_ID);
    OBContext.setAdminMode(true);
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
    // Defensive drain: a failure mid-test can leave more than one admin-mode push on the
    // stack (see GoodsReceiptNoStockCompletionIntegrationTest's tearDown for the same need).
    while (OBContext.getOBContext() != null && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
  }

  // ── Scenario 1: full sales return, no invoice ─────────────────────────────

  @Test
  public void fullSalesReturnRestoresStockToNetZero() throws Exception {
    Product product = cloneUnstockedProduct("ETP5313S1");
    seedStock(product, "ETP5313S1RCP", new BigDecimal("60"));

    ShipmentInOut shipment = cloneDraftShipmentHeader("ETP5313S1SHP");
    ShipmentInOutLine shipmentLine = addShipmentLine(shipment, product, new BigDecimal("45"));
    completeInOut(shipment);

    ShipmentInOut returnDoc = buildSalesReturnHeader(shipment, "ETP5313S1RET");
    ShipmentInOutLine returnLine = buildSalesReturnLine(returnDoc, shipmentLine, 10L,
        new BigDecimal("45"));
    completeInOut(returnDoc);

    assertMovementQty("the full-return line must be stored NEGATIVE", returnLine.getId(),
        new BigDecimal("-45"));
    assertNetStock("a full return of everything shipped must bring net stock back to zero",
        List.of(shipmentLine.getId(), returnLine.getId()), BigDecimal.ZERO);
  }

  // ── Scenario 2: full sales return WITH a pre-existing linked invoice line ─

  /**
   * The invoice option must be irrelevant to the stock math. Builds a minimal, real, persisted
   * {@link Invoice}/{@link InvoiceLine} linked to the source shipment line via {@code
   * goodsShipmentLine} (the same FK {@link ReturnShipmentUtils#findSourceInvoice} reads) BEFORE
   * the return exists, then asserts the return posts identically to scenario 1.
   *
   * <p>Deliberately does NOT go through the full {@code createReturnInvoice} action (needs
   * CDI-injected {@code CreateDraftInvoiceHandler}) NOR the production {@code
   * ReturnShipmentUtils#buildReturnInvoiceHeader} helper — that helper's last step,
   * {@code NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity}, resolves
   * declared field derivations from the "sales-invoice" spec's pushed NEO config, which no
   * existing real-DB integration test exercises and this test DB's ETGO_SF_* state cannot be
   * assumed to satisfy. Instead this builds the SAME header fields {@code
   * buildReturnInvoiceHeader} sets directly via {@link OBProvider} (a "realistic fixture
   * shape", per this ticket's own scoping note), skipping only that one config-dependent call —
   * which is irrelevant to what this scenario actually asserts (the RETURN's stock math).
   */
  @Test
  public void fullSalesReturnWithPreExistingInvoiceLineHasSameNetStock() throws Exception {
    Product product = cloneUnstockedProduct("ETP5313S2");
    seedStock(product, "ETP5313S2RCP", new BigDecimal("60"));

    ShipmentInOut shipment = cloneDraftShipmentHeader("ETP5313S2SHP");
    ShipmentInOutLine shipmentLine = addShipmentLine(shipment, product, new BigDecimal("45"));
    completeInOut(shipment);
    OBDal.getInstance().refresh(shipmentLine);

    DocumentType invDocType = ReturnShipmentUtils.findReturnDocTypeForOrg(
        shipment.getOrganization().getId(), "ARI", true, false, false);
    assertNotNull("test fixture requires an active, non-return AR Invoice doc type",
        invDocType);
    Invoice invoice = buildMinimalInvoiceHeader(shipment, invDocType, "ETP5313S2INV");
    addMinimalInvoiceLine(invoice, shipmentLine, new BigDecimal("45"));

    ShipmentInOut returnDoc = buildSalesReturnHeader(shipment, "ETP5313S2RET");
    ShipmentInOutLine returnLine = buildSalesReturnLine(returnDoc, shipmentLine, 10L,
        new BigDecimal("45"));
    completeInOut(returnDoc);

    assertMovementQty(returnLine.getId(), new BigDecimal("-45"));
    assertNetStock("the stock math must be identical whether or not a rectificative invoice "
        + "already exists for the source shipment line",
        List.of(shipmentLine.getId(), returnLine.getId()), BigDecimal.ZERO);
  }

  // ── Scenario 3: partial return ─────────────────────────────────────────────

  @Test
  public void partialSalesReturnLeavesNetStockAtRemainder() throws Exception {
    Product product = cloneUnstockedProduct("ETP5313S3");
    seedStock(product, "ETP5313S3RCP", new BigDecimal("60"));

    ShipmentInOut shipment = cloneDraftShipmentHeader("ETP5313S3SHP");
    ShipmentInOutLine shipmentLine = addShipmentLine(shipment, product, new BigDecimal("45"));
    completeInOut(shipment);

    ShipmentInOut returnDoc = buildSalesReturnHeader(shipment, "ETP5313S3RET");
    ShipmentInOutLine returnLine = buildSalesReturnLine(returnDoc, shipmentLine, 10L,
        new BigDecimal("20"));
    completeInOut(returnDoc);

    assertMovementQty(returnLine.getId(), new BigDecimal("-20"));
    assertNetStock("a partial return of 20 out of 45 must leave stock at -25, neither -45 "
        + "(nothing returned) nor 0 (everything returned)",
        List.of(shipmentLine.getId(), returnLine.getId()), new BigDecimal("-25"));
  }

  // ── Scenario 4: two successive partial returns + the ABS()-based availability SQL ──

  @Test
  public void twoSuccessivePartialReturnsNetToZeroAndExhaustAvailability() throws Exception {
    Product product = cloneUnstockedProduct("ETP5313S4");
    seedStock(product, "ETP5313S4RCP", new BigDecimal("60"));

    ShipmentInOut shipment = cloneDraftShipmentHeader("ETP5313S4SHP");
    ShipmentInOutLine shipmentLine = addShipmentLine(shipment, product, new BigDecimal("45"));
    completeInOut(shipment);

    JSONArray beforeAny = callAvailableShipmentLines(shipment.getId());
    assertEquals("before any return, the full 45 must be available to return", 1,
        beforeAny.length());
    assertEquals(0, new BigDecimal("45")
        .compareTo(new BigDecimal(beforeAny.getJSONObject(0).getString("movementQuantity"))));

    ShipmentInOut return1 = buildSalesReturnHeader(shipment, "ETP5313S4RET1");
    ShipmentInOutLine returnLine1 = buildSalesReturnLine(return1, shipmentLine, 10L,
        new BigDecimal("20"));
    completeInOut(return1);

    JSONArray afterFirst = callAvailableShipmentLines(shipment.getId());
    assertEquals("after returning 20 of 45, 25 must remain available", 1, afterFirst.length());
    assertEquals(0, new BigDecimal("25")
        .compareTo(new BigDecimal(afterFirst.getJSONObject(0).getString("movementQuantity"))));

    ShipmentInOut return2 = buildSalesReturnHeader(shipment, "ETP5313S4RET2");
    ShipmentInOutLine returnLine2 = buildSalesReturnLine(return2, shipmentLine, 10L,
        new BigDecimal("25"));
    completeInOut(return2);

    JSONArray afterBoth = callAvailableShipmentLines(shipment.getId());
    assertEquals("the ABS()-based already-returned SQL (ReturnMaterialReceiptHeaderHandler."
        + "handleAvailableShipmentLines) must report ZERO returnable rows once the full "
        + "quantity has been returned across two separate partial returns", 0,
        afterBoth.length());

    assertNetStock("two partial returns (20 + 25) summing to the full shipped quantity must "
        + "net stock back to zero, exactly like a single full return",
        List.of(shipmentLine.getId(), returnLine1.getId(), returnLine2.getId()),
        BigDecimal.ZERO);
  }

  // ── Scenario 5: purchase-return regression (unaffected by this change) ───

  @Test
  public void purchaseReturnToVendorStillDecreasesStock() throws Exception {
    Product product = cloneUnstockedProduct("ETP5313S5");
    ShipmentInOut receipt = cloneDraftReceiptHeader("ETP5313S5RCP");
    ShipmentInOutLine receiptLine = addReceiptLine(receipt, product, new BigDecimal("30"));
    completeInOut(receipt);
    OBDal.getInstance().refresh(receiptLine);

    JSONObject requestBody = new JSONObject().put("lines", new JSONArray().put(
        new JSONObject().put("lineId", receiptLine.getId()).put("returnQuantity", 10)));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createPurchaseReturn")
        .recordId(receipt.getId())
        .requestBody(requestBody)
        .build();

    NeoResponse response = new CreatePurchaseReturnHandler().handle(ctx);
    assertNotNull("createPurchaseReturn must produce a created-record response", response);
    String returnId = response.getBody().getJSONObject("response")
        .getJSONObject("data").getString("id");

    ShipmentInOut returnDoc = OBDal.getInstance().get(ShipmentInOut.class, returnId);
    completeInOut(returnDoc);
    OBDal.getInstance().refresh(returnDoc);

    ShipmentInOutLine returnLine = returnDoc.getMaterialMgmtShipmentInOutLineList().get(0);
    assertMovementQty("an RTV Shipment line must still be stored NEGATIVE — this side of the "
        + "policy was already correct before ETP-5313 and must remain so", returnLine.getId(),
        new BigDecimal("-10"));
    assertNetStock("an RTV Shipment (purchase return) must DECREASE stock by the returned "
        + "amount, unlike the sales side which increases it",
        List.of(returnLine.getId()), new BigDecimal("-10"));
  }

  // ── fixture builders ───────────────────────────────────────────────────────

  private static Product cloneUnstockedProduct(String namePrefix) {
    Product template = OBDal.getInstance().get(Product.class, PRODUCT_TEMPLATE_ID);
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

  /** Receives {@code quantity} of {@code product} into {@link #LOCATOR_ID} to seed headroom. */
  private void seedStock(Product product, String docNoPrefix, BigDecimal quantity)
      throws Exception {
    ShipmentInOut receipt = cloneDraftReceiptHeader(docNoPrefix);
    addReceiptLine(receipt, product, quantity);
    completeInOut(receipt);
  }

  private static ShipmentInOut cloneDraftReceiptHeader(String docNoPrefix) {
    ShipmentInOut template = OBDal.getInstance().get(ShipmentInOut.class, RECEIPT_TEMPLATE_ID);
    ShipmentInOut clone = (ShipmentInOut) DalUtil.copy(template, false);
    clone.setId(SequenceIdData.getUUID());
    clone.setNewOBObject(true);
    clone.setDocumentNo(docNoPrefix + "-" + SequenceIdData.getUUID().substring(0, 8));
    clone.setDocumentStatus("DR");
    clone.setDocumentAction("CO");
    clone.setProcessed(false);
    clone.setMovementDate(new Date());
    clone.setWarehouse(OBDal.getInstance().get(Warehouse.class, WAREHOUSE_ID));
    clone.setMaterialMgmtShipmentInOutLineList(new ArrayList<>());
    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    return clone;
  }

  private static ShipmentInOutLine addReceiptLine(ShipmentInOut receipt, Product product,
      BigDecimal quantity) {
    ShipmentInOut templateHeader = OBDal.getInstance().get(ShipmentInOut.class, RECEIPT_TEMPLATE_ID);
    ShipmentInOutLine template = templateHeader.getMaterialMgmtShipmentInOutLineList().get(0);
    ShipmentInOutLine clone = (ShipmentInOutLine) DalUtil.copy(template, false);
    clone.setId(SequenceIdData.getUUID());
    clone.setNewOBObject(true);
    clone.setShipmentReceipt(receipt);
    clone.setSalesOrderLine(null);
    clone.setProduct(product);
    clone.setUOM(product.getUOM());
    clone.setMovementQuantity(quantity);
    clone.setAttributeSetValue(null);
    clone.setStorageBin(OBDal.getInstance().get(Locator.class, LOCATOR_ID));
    // cloneDraftReceiptHeader() explicitly empties receipt's in-memory line list, and within
    // this same Hibernate session `OBDal.get()` on the same PK returns that SAME managed
    // entity — so without appending here, the collection stays stale-empty for any later
    // in-session reader that walks receipt.getMaterialMgmtShipmentInOutLineList() (e.g. the
    // real CreatePurchaseReturnHandler.buildReturnLines(), scenario 5), even though the row is
    // correctly persisted. Mirrors the same append CreatePurchaseReturnHandler.addReturnLine()
    // itself does on the return header.
    receipt.getMaterialMgmtShipmentInOutLineList().add(clone);
    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    return clone;
  }

  private static ShipmentInOut cloneDraftShipmentHeader(String docNoPrefix) {
    ShipmentInOut template = OBDal.getInstance().get(ShipmentInOut.class, SHIPMENT_TEMPLATE_ID);
    ShipmentInOut clone = (ShipmentInOut) DalUtil.copy(template, false);
    clone.setId(SequenceIdData.getUUID());
    clone.setNewOBObject(true);
    clone.setDocumentNo(docNoPrefix + "-" + SequenceIdData.getUUID().substring(0, 8));
    clone.setDocumentStatus("DR");
    clone.setDocumentAction("CO");
    clone.setProcessed(false);
    clone.setMovementDate(new Date());
    clone.setWarehouse(OBDal.getInstance().get(Warehouse.class, WAREHOUSE_ID));
    clone.setMaterialMgmtShipmentInOutLineList(new ArrayList<>());
    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    return clone;
  }

  private static ShipmentInOutLine addShipmentLine(ShipmentInOut shipment, Product product,
      BigDecimal quantity) {
    ShipmentInOut templateHeader = OBDal.getInstance().get(ShipmentInOut.class, SHIPMENT_TEMPLATE_ID);
    ShipmentInOutLine template = templateHeader.getMaterialMgmtShipmentInOutLineList().get(0);
    ShipmentInOutLine clone = (ShipmentInOutLine) DalUtil.copy(template, false);
    clone.setId(SequenceIdData.getUUID());
    clone.setNewOBObject(true);
    clone.setShipmentReceipt(shipment);
    clone.setSalesOrderLine(null);
    clone.setProduct(product);
    clone.setUOM(product.getUOM());
    clone.setMovementQuantity(quantity);
    clone.setAttributeSetValue(null);
    clone.setStorageBin(OBDal.getInstance().get(Locator.class, LOCATOR_ID));
    // Same in-session staleness as addReceiptLine() above — append so any future reader of
    // shipment.getMaterialMgmtShipmentInOutLineList() sees this line too, not just the DB row.
    shipment.getMaterialMgmtShipmentInOutLineList().add(clone);
    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    return clone;
  }

  /**
   * Builds the draft Return Material Receipt (RFC Receipt) header from {@code sourceShipment},
   * through the real {@link NeoCommercialDocumentFactory#createReturnReceiptHeader} production
   * helper — the same one {@code ReturnMaterialReceiptHeaderHandler}'s create path relies on.
   */
  private static ShipmentInOut buildSalesReturnHeader(ShipmentInOut sourceShipment,
      String docNoPrefix) {
    DocumentType docType = OBDal.getInstance().get(DocumentType.class, RFC_RECEIPT_DOCTYPE_ID);
    ShipmentInOut returnDoc = NeoCommercialDocumentFactory
        .createReturnReceiptHeader(sourceShipment, docType);
    returnDoc.setDocumentNo(docNoPrefix + "-" + SequenceIdData.getUUID().substring(0, 8));
    // createReturnReceiptHeader does not set DocumentAction — m_inout_post reads it to decide
    // whether this call completes the document.
    returnDoc.setDocumentAction("CO");
    OBDal.getInstance().save(returnDoc);
    OBDal.getInstance().flush();
    return returnDoc;
  }

  /**
   * Builds and persists a return line through the real {@link
   * ReturnShipmentUtils#buildAndSaveReturnLine} production helper — the exact method {@code
   * ReturnMaterialReceiptHeaderHandler#handleImportShipmentLines} calls for every "import
   * shipment lines" request — then locates the created line by its (caller-chosen, unique)
   * {@code lineNo}, since {@code buildAndSaveReturnLine} itself returns {@code void}.
   */
  private static ShipmentInOutLine buildSalesReturnLine(ShipmentInOut returnDoc,
      ShipmentInOutLine sourceLine, long lineNo, BigDecimal qty) throws Exception {
    ReturnShipmentUtils.buildAndSaveReturnLine(returnDoc, sourceLine, lineNo, qty);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(returnDoc);
    for (ShipmentInOutLine line : returnDoc.getMaterialMgmtShipmentInOutLineList()) {
      if (line.getLineNo() != null && line.getLineNo().longValue() == lineNo) {
        return line;
      }
    }
    throw new IllegalStateException("Return line " + lineNo + " not found after build");
  }

  /**
   * Minimal, real, persisted {@link Invoice} header for {@code sourceShipment} — the same
   * fields the real {@code ReturnShipmentUtils#buildReturnInvoiceHeader} sets for the
   * no-source-invoice branch ({@code documentType}/{@code transactionDocument},
   * {@code businessPartner}/{@code partnerAddress}, {@code priceList}/{@code currency}/{@code
   * paymentTerms}/{@code paymentMethod} from the business partner), MINUS that helper's final
   * {@code applyDeclaredDefaultsToBackgroundEntity} call — see this scenario's javadoc for why.
   */
  private static Invoice buildMinimalInvoiceHeader(ShipmentInOut sourceShipment,
      DocumentType docType, String docNoPrefix) {
    BusinessPartner bp = sourceShipment.getBusinessPartner();
    Invoice invoice = OBProvider.getInstance().get(Invoice.class);
    invoice.setClient(sourceShipment.getClient());
    invoice.setOrganization(sourceShipment.getOrganization());
    invoice.setDocumentType(docType);
    invoice.setTransactionDocument(docType);
    invoice.setDocumentStatus("DR");
    invoice.setDocumentAction("CO");
    invoice.setSalesTransaction(true);
    invoice.setInvoiceDate(new Date());
    invoice.setAccountingDate(new Date());
    invoice.setBusinessPartner(bp);
    invoice.setPartnerAddress(sourceShipment.getPartnerAddress());
    invoice.setDocumentNo(docNoPrefix + "-" + SequenceIdData.getUUID().substring(0, 8));
    invoice.setSummedLineAmount(BigDecimal.ZERO);
    invoice.setGrandTotalAmount(BigDecimal.ZERO);
    invoice.setPriceList(bp.getPriceList());
    invoice.setCurrency(bp.getPriceList().getCurrency());
    invoice.setPaymentTerms(bp.getPaymentTerms());
    invoice.setPaymentMethod(bp.getPaymentMethod());
    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();
    return invoice;
  }

  /**
   * Minimal, real, persisted {@link InvoiceLine} linked to {@code sourceLine} via {@code
   * goodsShipmentLine} — the same FK {@link ReturnShipmentUtils#findSourceInvoice} reads.
   * Deliberately does NOT go through {@code CreateDraftInvoiceHandler#createShipmentInvoiceLine}
   * (a CDI-injected dependency this plain-{@code new} test double avoids); it only satisfies the
   * DB's own NOT NULL columns (see {@code c_invoiceline}'s schema) plus the FK this scenario
   * actually needs.
   */
  private static void addMinimalInvoiceLine(Invoice invoice, ShipmentInOutLine sourceLine,
      BigDecimal quantity) {
    InvoiceLine line = OBProvider.getInstance().get(InvoiceLine.class);
    line.setClient(invoice.getClient());
    line.setOrganization(invoice.getOrganization());
    line.setInvoice(invoice);
    line.setLineNo(10L);
    line.setProduct(sourceLine.getProduct());
    line.setUOM(sourceLine.getUOM());
    line.setGoodsShipmentLine(sourceLine);
    line.setTax(OBDal.getInstance().get(TaxRate.class, TAX_ID));
    BigDecimal unitPrice = new BigDecimal("10");
    line.setInvoicedQuantity(quantity);
    line.setUnitPrice(unitPrice);
    line.setListPrice(unitPrice);
    line.setPriceLimit(unitPrice);
    line.setGrossListPrice(unitPrice);
    line.setGrossUnitPrice(unitPrice);
    line.setLineNetAmount(quantity.multiply(unitPrice));
    OBDal.getInstance().save(line);
    OBDal.getInstance().flush();
  }

  private static void completeInOut(ShipmentInOut doc) throws Exception {
    List<Object> parameters = new ArrayList<>();
    parameters.add(null);
    parameters.add(doc.getId());
    CallStoredProcedure.getInstance().call(PROCESS_INOUT_POST, parameters, null, true, false);
    OBDal.getInstance().flush();
  }

  /**
   * Invokes the REAL {@code availableShipmentLines} action on {@link
   * ReturnMaterialReceiptHeaderHandler} — the handler that owns the {@code ABS()}-based
   * "already returned" SQL under test in scenario 4 — via a plain {@code new}. {@code
   * cloneRecordHandler} is assigned a real (dependency-free for this action name)
   * {@link NeoCloneRecordHandler}, since {@code handle()} unconditionally calls it before
   * dispatching on the action name; {@code createDraftInvoiceHandler} is intentionally left
   * {@code null} — it is only touched by the unrelated {@code createReturnInvoice} action.
   */
  private static JSONArray callAvailableShipmentLines(String shipmentId) throws Exception {
    ReturnMaterialReceiptHeaderHandler handler = new ReturnMaterialReceiptHeaderHandler();
    handler.cloneRecordHandler = new NeoCloneRecordHandler();
    JSONObject requestBody = new JSONObject().put("shipmentId", shipmentId);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("availableShipmentLines")
        .requestBody(requestBody)
        .build();

    NeoResponse response = handler.handle(ctx);
    assertNotNull("availableShipmentLines must produce a response", response);
    return response.getBody().getJSONObject("response").getJSONArray("data");
  }

  // ── assertions ─────────────────────────────────────────────────────────────

  private static void assertMovementQty(String lineId, BigDecimal expected) throws Exception {
    assertMovementQty(null, lineId, expected);
  }

  private static void assertMovementQty(String message, String lineId, BigDecimal expected)
      throws Exception {
    BigDecimal actual = fetchMovementQty(lineId);
    String reason = message != null ? message
        : "M_InOutLine.MovementQty must carry the policy's sign";
    assertEquals(reason, 0, expected.compareTo(actual));
  }

  private static BigDecimal fetchMovementQty(String lineId) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn
        .prepareStatement("SELECT MovementQty FROM M_InOutLine WHERE M_InOutLine_ID = ?")) {
      ps.setString(1, lineId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrueLineExists(rs, lineId);
        return rs.getBigDecimal(1);
      }
    }
  }

  private static void assertTrueLineExists(ResultSet rs, String lineId) throws Exception {
    if (!rs.next()) {
      throw new IllegalStateException("Line " + lineId + " does not exist in M_InOutLine");
    }
  }

  /**
   * Net effect on stock caused by the given set of {@code M_InOutLine} ids — i.e. the SUM of
   * every {@code M_Transaction.MovementQty} posted for them — which is exactly what {@code
   * m_inout_post} feeds into the warehouse's on-hand quantity. Scoped to specific line ids
   * (rather than the whole warehouse/product) so the assertion is independent of the {@code
   * seedStock} headroom receipt and of any other test's fixtures.
   */
  @SuppressWarnings("java:S2077")
  private static void assertNetStock(String message, List<String> lineIds, BigDecimal expected)
      throws Exception {
    String placeholders = lineIds.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql = "SELECT COALESCE(SUM(MovementQty), 0) FROM M_Transaction "
        + "WHERE M_InOutLine_ID IN (" + placeholders + ")";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < lineIds.size(); i++) {
        ps.setString(i + 1, lineIds.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        BigDecimal actual = rs.getBigDecimal(1);
        assertEquals(message, 0, expected.compareTo(actual));
      }
    }
  }
}
