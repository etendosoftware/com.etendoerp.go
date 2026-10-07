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
import java.util.ArrayList;
import java.util.Date;

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
 * DB-backed proof of {@code ETGO_GET_DELIVERY_STATUS} (the virtual column
 * {@code C_Invoice.EM_ETGO_Delivery_Status}): delivery % = Σ over invoice lines of
 * {@code min(qtyinvoiced, Σ over movement lines of min(match qty on that movement line,
 * ABS(movementqty)))} / Σ qtyinvoiced, where the match qty unions {@code m_matchsi} and
 * {@code m_matchinv} and only movements in {@code CO}/{@code CL} count.
 *
 * <p>Each case builds one invoice with ONE line of 10 units, so the expected percentage is exact.
 * Movement headers and lines are cloned from the draft shipment template (no
 * {@code m_inout_post}): the function reads only {@code m_inout.docstatus} and
 * {@code m_inoutline.movementqty}, so the status is set directly on the unprocessed clone, which
 * {@code M_INOUT_CHK_RESTRICTIONS_TRG} allows. Match rows are inserted with native SQL. Nothing is
 * committed: every row is rolled back in {@link #tearDown()}, as in
 * {@link EtgoGetTbaiStatusFunctionIntegrationTest}.
 *
 * <p>Unit under test: {@code src-db/database/model/functions/ETGO_GET_DELIVERY_STATUS.xml}. No
 * Java class owns it, so no {@code @covers} tag can resolve under {@code src/}.
 */
public class EtgoGetDeliveryStatusFunctionIntegrationTest extends OBBaseTest {

  // QA Testing client/org and fixtures — same ones ReturnLineQuantitySignIntegrationTest uses.
  private static final String CLIENT_ID = "4028E6C72959682B01295A070852010D";
  private static final String ORG_ID = "357947E87C284935AD1D783CF6F099A1";
  private static final String USER_ID = "100";
  private static final String ROLE_ID = "4028E6C72959682B01295A071429011E";
  // Draft Goods Shipment (IsSOTrx=Y) with exactly one line.
  private static final String SHIPMENT_TEMPLATE_ID = "2BCCC64DA82A48C3976B4D007315C2C9";
  // "VAT 3%" — c_invoiceline_check2 requires a tax whenever LineNetAmt != 0.
  private static final String TAX_ID = "5A74E390B82747F9A5754C8EB1BDB47A";

  private static final BigDecimal INVOICED = new BigDecimal("10");

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

  // (a) A match row is never trusted beyond what its movement line moved.
  @Test
  public void matchAboveTheMovedQuantityCountsOnlyWhatTheCompletedLineMoved() {
    InvoiceLine invoiceLine = newInvoiceWithOneLine();
    ShipmentInOutLine moved4 = newMovementLine(newMovement("CO"), new BigDecimal("4"));
    insertMatchSi(invoiceLine, moved4, INVOICED);

    assertEquals("a match of 10 against a completed line that moved 4 is 4 of 10 delivered",
        40, deliveryStatus(invoiceLine));
  }

  // (b) Match rows of one invoice line are summed per movement line BEFORE the cap.
  @Test
  public void severalMatchesOnTheSameMovementLineAreCappedTogetherAtItsMovedQuantity() {
    InvoiceLine invoiceLine = newInvoiceWithOneLine();
    ShipmentInOutLine moved4 = newMovementLine(newMovement("CO"), new BigDecimal("4"));
    insertMatchSi(invoiceLine, moved4, new BigDecimal("3"));
    insertMatchSi(invoiceLine, moved4, new BigDecimal("3"));

    assertEquals("3 + 3 on a line that moved 4 is 4 delivered (40%), not 6 (60%)",
        40, deliveryStatus(invoiceLine));
  }

  // (c) A draft movement has delivered nothing, even with a match row.
  @Test
  public void matchOnADraftMovementDeliversNothing() {
    InvoiceLine invoiceLine = newInvoiceWithOneLine();
    ShipmentInOutLine line = newMovementLine(newMovement("DR"), INVOICED);
    insertMatchSi(invoiceLine, line, INVOICED);

    assertEquals(0, deliveryStatus(invoiceLine));
  }

  // (d) A voided movement has delivered nothing.
  @Test
  public void matchOnAVoidedMovementDeliversNothing() {
    InvoiceLine invoiceLine = newInvoiceWithOneLine();
    ShipmentInOutLine line = newMovementLine(newMovement("VO"), INVOICED);
    insertMatchSi(invoiceLine, line, INVOICED);

    assertEquals(0, deliveryStatus(invoiceLine));
  }

  // (e) Deliveries above the invoiced quantity are capped at 100, across both match tables.
  @Test
  public void matchesAboveTheInvoicedQuantityAreCappedAtOneHundred() {
    InvoiceLine invoiceLine = newInvoiceWithOneLine();
    ShipmentInOut completed = newMovement("CO");
    ShipmentInOutLine first = newMovementLine(completed, new BigDecimal("8"));
    ShipmentInOutLine second = newMovementLine(completed, new BigDecimal("8"));
    insertMatchSi(invoiceLine, first, new BigDecimal("8"));
    insertMatchInv(invoiceLine, second, new BigDecimal("8"));

    assertEquals("8 (m_matchsi) + 8 (m_matchinv) on an invoice line of 10 is 100, not 160",
        100, deliveryStatus(invoiceLine));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  @SuppressWarnings("rawtypes")
  private static int deliveryStatus(InvoiceLine invoiceLine) {
    OBDal.getInstance().flush();
    NativeQuery query = OBDal.getInstance().getSession()
        .createNativeQuery("SELECT etgo_get_delivery_status(:invoiceId)");
    query.setParameter("invoiceId", invoiceLine.getInvoice().getId());
    Number result = (Number) query.uniqueResult();
    assertNotNull("the function never returns NULL for an existing invoice", result);
    return result.intValue();
  }

  /** A movement header cloned from the draft shipment template, with {@code docStatus}. */
  private static ShipmentInOut newMovement(String docStatus) {
    ShipmentInOut template = OBDal.getInstance().get(ShipmentInOut.class, SHIPMENT_TEMPLATE_ID);
    ShipmentInOut clone = (ShipmentInOut) DalUtil.copy(template, false);
    clone.setId(SequenceIdData.getUUID());
    clone.setNewOBObject(true);
    clone.setDocumentNo("DLVST-" + SequenceIdData.getUUID().substring(0, 8));
    clone.setDocumentStatus(docStatus);
    clone.setProcessed(false);
    clone.setMovementDate(new Date());
    clone.setMaterialMgmtShipmentInOutLineList(new ArrayList<>());
    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    return clone;
  }

  /** A movement line cloned from the template's only line, moving {@code quantity}. */
  private static ShipmentInOutLine newMovementLine(ShipmentInOut movement, BigDecimal quantity) {
    ShipmentInOut templateHeader =
        OBDal.getInstance().get(ShipmentInOut.class, SHIPMENT_TEMPLATE_ID);
    ShipmentInOutLine template = templateHeader.getMaterialMgmtShipmentInOutLineList().get(0);
    ShipmentInOutLine clone = (ShipmentInOutLine) DalUtil.copy(template, false);
    clone.setId(SequenceIdData.getUUID());
    clone.setNewOBObject(true);
    clone.setShipmentReceipt(movement);
    clone.setSalesOrderLine(null);
    clone.setAttributeSetValue(null);
    clone.setMovementQuantity(quantity);
    movement.getMaterialMgmtShipmentInOutLineList().add(clone);
    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    return clone;
  }

  /** A draft sales invoice with a single line of {@link #INVOICED} units. */
  private static InvoiceLine newInvoiceWithOneLine() {
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
    invoice.setDocumentStatus("DR");
    invoice.setDocumentAction("CO");
    invoice.setSalesTransaction(true);
    invoice.setInvoiceDate(new Date());
    invoice.setAccountingDate(new Date());
    invoice.setBusinessPartner(bp);
    invoice.setPartnerAddress(template.getPartnerAddress());
    invoice.setDocumentNo("DLVST-" + SequenceIdData.getUUID().substring(0, 8));
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

  private static void insertMatchSi(InvoiceLine invoiceLine, ShipmentInOutLine movementLine,
      BigDecimal qty) {
    insertMatch("m_matchsi", invoiceLine, movementLine, qty);
  }

  private static void insertMatchInv(InvoiceLine invoiceLine, ShipmentInOutLine movementLine,
      BigDecimal qty) {
    insertMatch("m_matchinv", invoiceLine, movementLine, qty);
  }

  /** Same column set for both tables; {@code table} is one of the two literals above. */
  @SuppressWarnings("rawtypes")
  private static void insertMatch(String table, InvoiceLine invoiceLine,
      ShipmentInOutLine movementLine, BigDecimal qty) {
    OBDal.getInstance().flush();
    NativeQuery insert = OBDal.getInstance().getSession().createNativeQuery("INSERT INTO "
        + table + " (" + table + "_id, ad_client_id, ad_org_id, isactive, created, createdby,"
        + " updated, updatedby, m_inoutline_id, c_invoiceline_id, datetrx, qty,"
        + " processing, processed, posted)"
        + " VALUES (:id, :clientId, :orgId, 'Y', now(), :userId, now(), :userId, :inOutLineId,"
        + " :invoiceLineId, now(), :qty, 'N', 'Y', 'N')");
    insert.setParameter("id", SequenceIdData.getUUID());
    insert.setParameter("clientId", invoiceLine.getClient().getId());
    insert.setParameter("orgId", invoiceLine.getOrganization().getId());
    insert.setParameter("userId", USER_ID);
    insert.setParameter("inOutLineId", movementLine.getId());
    insert.setParameter("invoiceLineId", invoiceLine.getId());
    insert.setParameter("qty", qty);
    insert.executeUpdate();
  }
}
