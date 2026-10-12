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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.test.base.OBBaseTest;

/**
 * DB-backed proof that a <b>second partial goods receipt</b> of an invoice line is read as
 * invoiced by the three readers ETP-5576 fixed, with real values rather than SQL text.
 *
 * <p>An invoice line reaches its FIRST receipt line through {@code C_InvoiceLine.M_InOutLine_ID};
 * a column holds one id, so every further partial receipt is linked only through a
 * {@code M_MatchInv} row ({@link InOutInvoiceLinks}). Before the fix the three readers looked at
 * the column and at {@code M_MatchSI} only, so receipt B below read as 0 % invoiced, fully pending
 * and with {@code invoicedQuantity} 0.</p>
 *
 * <p>Scenario: one completed invoice line of 3 units; receipt A moved 1 and is linked by the
 * column; receipt B moved 2 and is linked only by {@code M_MatchInv}. Every assertion is about B.
 * The second case matches 3 against B's line, which moved 2: core can write a match above what a
 * movement line moved, and each reader caps it at the moved quantity.</p>
 *
 * <p>Fixtures follow {@link EtgoGetDeliveryStatusFunctionIntegrationTest}: movements are cloned
 * from a draft template and never posted (the readers do not filter the movement's status), match
 * rows are inserted with native SQL, and nothing is committed — {@link #tearDown()} rolls back.
 * The readers run their SQL on {@code OBDal.getInstance().getConnection()}, the same transaction,
 * so the flushed rows are visible to them. The invoice is built from the shipment template's
 * partner (a known-good invoice fixture); the readers join invoice and movement lines only by
 * the column and the match table, never by product or partner.</p>
 *
 * @covers com.etendoerp.go.schemaforge.InOutInvoiceLinks
 * @covers com.etendoerp.go.schemaforge.GoodsReceiptHeaderHandler
 * @covers com.etendoerp.go.schemaforge.NeoInvoiceSupport
 * @covers com.etendoerp.go.schemaforge.AbstractInOutLineHandler
 */
public class InOutInvoiceLinksPartialReceiptIntegrationTest extends OBBaseTest {

  // QA Testing client/org — same fixtures EtgoGetDeliveryStatusFunctionIntegrationTest uses.
  private static final String CLIENT_ID = "4028E6C72959682B01295A070852010D";
  private static final String ORG_ID = "357947E87C284935AD1D783CF6F099A1";
  private static final String USER_ID = "100";
  private static final String ROLE_ID = "4028E6C72959682B01295A071429011E";
  // Draft Purchase Receipt (IsSOTrx=N) with exactly one line — the template
  // GoodsReceiptWarehouseSwitchReanchorIntegrationTest clones.
  private static final String RECEIPT_TEMPLATE_ID = "0450583047434254835B2B36B2E5B018";
  // Draft Goods Shipment with exactly one line: partner, product and UOM of the invoice.
  private static final String SHIPMENT_TEMPLATE_ID = "2BCCC64DA82A48C3976B4D007315C2C9";
  // "VAT 3%" — c_invoiceline_check2 requires a tax whenever LineNetAmt != 0.
  private static final String TAX_ID = "5A74E390B82747F9A5754C8EB1BDB47A";

  private static final BigDecimal INVOICED = new BigDecimal("3");
  private static final BigDecimal MOVED_BY_A = BigDecimal.ONE;
  private static final BigDecimal MOVED_BY_B = new BigDecimal("2");

  @Before
  public void setUp() {
    OBContext.setOBContext(USER_ID, ROLE_ID, CLIENT_ID, ORG_ID);
    OBContext.setAdminMode(true);
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void secondPartialReceiptLinkedOnlyByTheMatchTableReadsAsInvoiced() throws Exception {
    assertReceiptBReadsFullyInvoiced(MOVED_BY_B);
  }

  @Test
  public void matchAboveTheMovedQuantityIsCappedAtWhatTheReceiptLineMoved() throws Exception {
    assertReceiptBReadsFullyInvoiced(INVOICED);
  }

  /**
   * Builds the scenario with a {@code M_MatchInv} row of {@code matchedOnB} against receipt B's
   * line, and asserts the three readers on B.
   */
  private static void assertReceiptBReadsFullyInvoiced(BigDecimal matchedOnB) throws Exception {
    ShipmentInOutLine lineA = newReceiptLine(newReceipt(), MOVED_BY_A);
    ShipmentInOut receiptB = newReceipt();
    ShipmentInOutLine lineB = newReceiptLine(receiptB, MOVED_BY_B);
    InvoiceLine invoiceLine = newCompletedInvoiceLineLinkedTo(lineA);

    // Fixture check: before the match row, B's whole line is pending — so what clears it below
    // is the match row, not some other link.
    Map<String, BigDecimal> pendingBefore =
        NeoInvoiceSupport.computePendingQtyPerLineOrThrow(receiptB.getId(), false);
    assertEquals(1, pendingBefore.size());
    assertEquals(0, MOVED_BY_B.compareTo(pendingBefore.get(lineB.getId())));

    insertMatchInv(invoiceLine, lineB, matchedOnB);

    assertEquals("GoodsReceiptHeaderHandler: receipt B is 100 % invoiced",
        100, receiptInvoiceStatus(receiptB.getId()));
    assertTrue("NeoInvoiceSupport: nothing of receipt B is pending",
        NeoInvoiceSupport.computePendingQtyPerLineOrThrow(receiptB.getId(), false).isEmpty());
    BigDecimal invoicedQuantity = lineInvoicedQuantity(lineB.getId());
    assertEquals("AbstractInOutLineHandler: invoicedQuantity is what B's line moved, got "
        + invoicedQuantity, 0, MOVED_BY_B.compareTo(invoicedQuantity));
  }

  // ── readers ────────────────────────────────────────────────────────────────

  /** {@code invoiceStatus} as {@link GoodsReceiptHeaderHandler#afterHandle} puts it on a list row. */
  private static int receiptInvoiceStatus(String receiptId) throws Exception {
    JSONObject row = new JSONObject();
    row.put("id", receiptId);
    JSONObject result = afterHandleGet(new GoodsReceiptHeaderHandler(), row);
    return result.getInt("invoiceStatus");
  }

  /** {@code invoicedQuantity} as {@link AbstractInOutLineHandler#afterHandle} puts it on a line. */
  private static BigDecimal lineInvoicedQuantity(String lineId) throws Exception {
    JSONObject row = new JSONObject();
    row.put("id", lineId);
    JSONObject result = afterHandleGet(new GoodsReceiptLineHandler(), row);
    return new BigDecimal(String.valueOf(result.get("invoicedQuantity")));
  }

  /** Runs a GET list {@code afterHandle} over a one-row response and returns the enriched row. */
  private static JSONObject afterHandleGet(NeoHandler handler, JSONObject row) throws Exception {
    OBDal.getInstance().flush();
    JSONObject response = new JSONObject();
    response.put("data", new JSONArray().put(row));
    JSONObject body = new JSONObject();
    body.put("response", response);
    NeoContext context = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(body))
        .build();

    NeoResponse enriched = handler.afterHandle(context);

    assertNotNull("the handler replaces the GET result with the enriched one", enriched);
    return enriched.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
  }

  // ── fixtures ───────────────────────────────────────────────────────────────

  /** A draft receipt header cloned from the receipt template, with no lines. */
  private static ShipmentInOut newReceipt() {
    ShipmentInOut template = OBDal.getInstance().get(ShipmentInOut.class, RECEIPT_TEMPLATE_ID);
    ShipmentInOut clone = (ShipmentInOut) DalUtil.copy(template, false);
    clone.setId(SequenceIdData.getUUID());
    clone.setNewOBObject(true);
    clone.setDocumentNo("PARTRC-" + SequenceIdData.getUUID().substring(0, 8));
    clone.setDocumentStatus("DR");
    clone.setProcessed(false);
    clone.setMovementDate(new Date());
    clone.setMaterialMgmtShipmentInOutLineList(new ArrayList<>());
    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    return clone;
  }

  /** A receipt line cloned from the template's only line, moving {@code quantity}. */
  private static ShipmentInOutLine newReceiptLine(ShipmentInOut receipt, BigDecimal quantity) {
    ShipmentInOut templateHeader =
        OBDal.getInstance().get(ShipmentInOut.class, RECEIPT_TEMPLATE_ID);
    ShipmentInOutLine template = templateHeader.getMaterialMgmtShipmentInOutLineList().get(0);
    ShipmentInOutLine clone = (ShipmentInOutLine) DalUtil.copy(template, false);
    clone.setId(SequenceIdData.getUUID());
    clone.setNewOBObject(true);
    clone.setShipmentReceipt(receipt);
    clone.setSalesOrderLine(null);
    clone.setAttributeSetValue(null);
    clone.setMovementQuantity(quantity);
    receipt.getMaterialMgmtShipmentInOutLineList().add(clone);
    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    return clone;
  }

  /**
   * A completed ({@code CO}, not processed) invoice with one line of {@link #INVOICED} units whose
   * {@code M_InOutLine_ID} points at {@code firstReceiptLine} — the column arm.
   */
  private static InvoiceLine newCompletedInvoiceLineLinkedTo(ShipmentInOutLine firstReceiptLine) {
    ShipmentInOut template = OBDal.getInstance().get(ShipmentInOut.class, SHIPMENT_TEMPLATE_ID);
    ShipmentInOutLine templateLine = template.getMaterialMgmtShipmentInOutLineList().get(0);
    DocumentType docType = ReturnShipmentUtils.findReturnDocTypeForOrg(
        template.getOrganization().getId(), "ARI", true, false, false);
    assertNotNull("test fixture requires an active, non-return AR Invoice doc type", docType);
    BusinessPartner bp = template.getBusinessPartner();

    Invoice invoice = OBProvider.getInstance().get(Invoice.class);
    invoice.setClient(template.getClient());
    invoice.setOrganization(template.getOrganization());
    invoice.setDocumentType(docType);
    invoice.setTransactionDocument(docType);
    // Counted by every reader: not VO, CL or DR. Not processed, so the line can still be added.
    invoice.setDocumentStatus("CO");
    invoice.setDocumentAction("CO");
    invoice.setSalesTransaction(true);
    invoice.setInvoiceDate(new Date());
    invoice.setAccountingDate(new Date());
    invoice.setBusinessPartner(bp);
    invoice.setPartnerAddress(template.getPartnerAddress());
    invoice.setDocumentNo("PARTRC-" + SequenceIdData.getUUID().substring(0, 8));
    invoice.setSummedLineAmount(BigDecimal.ZERO);
    invoice.setGrandTotalAmount(BigDecimal.ZERO);
    invoice.setPriceList(bp.getPriceList());
    invoice.setCurrency(bp.getPriceList().getCurrency());
    invoice.setPaymentTerms(bp.getPaymentTerms());
    invoice.setPaymentMethod(bp.getPaymentMethod());
    OBDal.getInstance().save(invoice);

    InvoiceLine line = OBProvider.getInstance().get(InvoiceLine.class);
    line.setClient(invoice.getClient());
    line.setOrganization(invoice.getOrganization());
    line.setInvoice(invoice);
    line.setLineNo(10L);
    line.setProduct(templateLine.getProduct());
    line.setUOM(templateLine.getUOM());
    line.setTax(OBDal.getInstance().get(TaxRate.class, TAX_ID));
    line.setGoodsShipmentLine(firstReceiptLine);
    BigDecimal unitPrice = BigDecimal.ONE;
    line.setInvoicedQuantity(INVOICED);
    line.setUnitPrice(unitPrice);
    line.setListPrice(unitPrice);
    line.setPriceLimit(unitPrice);
    line.setGrossListPrice(unitPrice);
    line.setGrossUnitPrice(unitPrice);
    line.setLineNetAmount(INVOICED.multiply(unitPrice));
    OBDal.getInstance().save(line);
    OBDal.getInstance().flush();
    return line;
  }

  /** A {@code M_MatchInv} row — the purchase-side match table — inserted with native SQL. */
  @SuppressWarnings("rawtypes")
  private static void insertMatchInv(InvoiceLine invoiceLine, ShipmentInOutLine receiptLine,
      BigDecimal qty) {
    OBDal.getInstance().flush();
    NativeQuery insert = OBDal.getInstance().getSession().createNativeQuery("INSERT INTO"
        + " m_matchinv (m_matchinv_id, ad_client_id, ad_org_id, isactive, created, createdby,"
        + " updated, updatedby, m_inoutline_id, c_invoiceline_id, datetrx, qty,"
        + " processing, processed, posted)"
        + " VALUES (:id, :clientId, :orgId, 'Y', now(), :userId, now(), :userId, :inOutLineId,"
        + " :invoiceLineId, now(), :qty, 'N', 'Y', 'N')");
    insert.setParameter("id", SequenceIdData.getUUID());
    insert.setParameter("clientId", invoiceLine.getClient().getId());
    insert.setParameter("orgId", invoiceLine.getOrganization().getId());
    insert.setParameter("userId", USER_ID);
    insert.setParameter("inOutLineId", receiptLine.getId());
    insert.setParameter("invoiceLineId", invoiceLine.getId());
    insert.setParameter("qty", qty);
    insert.executeUpdate();
  }
}
