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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.inject.Inject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.test.base.TestConstants;


/**
 * Real-DAL proof that cloning an invoice leaves the copy unlinked from the source's related
 * documents (ETP-5709): after {@code cloneRecord} the PERSISTED clone has a NULL corrected invoice
 * ({@code EM_Aeatsii_Invoice_ID}), a NULL order, and lines with NULL order line, NULL shipment line
 * and NULL source invoice line, it is a draft, and the source invoice is untouched.
 *
 * <p>The existing unit tests ({@link CloneInvoiceLinePriceHookTest}, {@link
 * NeoCloneRecordHandlerTest}) only verify setter calls on mocks. This one runs the real chain —
 * {@link NeoCloneRecordHandler#handle} → {@link CloneInvoiceLinePriceHook} → the core {@code
 * CloneInvoiceHook} → {@code DalUtil.copy} → flush — and reads the result back from the database,
 * after the session is cleared, so it proves the fields are cleared AFTER the core hook's own
 * save/flush/refresh, not just on the in-memory object.
 *
 * <p>Fixture: no rectifying invoice with all the links exists in the F&amp;B demo client, so the
 * source is a real, fully linked sales invoice of that client copied as a new DRAFT (header and
 * lines, links preserved), with {@code aeatsiiInvoice} pointing at the template (a real invoice, as
 * a rectified one would) and every line's {@code etgoSourceInvoiceline} pointing at a real line.
 * Everything is rolled back in {@code tearDown}; the clone handler only flushes.
 *
 * <p>Extends {@code WeldBaseTest} (not plain {@code OBBaseTest}): the core clone hook flushes the copy
 * with a NULL documentNo and relies on the CDI sequence observer to fill it, which only exists with a
 * Weld container. The handler is injected, so the real hook selection (GO hook before core hook) runs.
 *
 * <p>Runs in its own JVM: listed in {@code isolatedDalTests} (see {@code docs/test-jvm-isolation.md}).
 *
 * @covers com.etendoerp.go.schemaforge.NeoCloneRecordHandler
 * @covers com.etendoerp.go.schemaforge.CloneInvoiceLinePriceHook
 */
public class NeoCloneRecordHandlerInvoiceLinksIntegrationTest extends WeldBaseTest {

  /** AD_Tab of the Sales Invoice window, Header tab (table C_Invoice). */
  private static final String SALES_INVOICE_HEADER_TAB_ID = "263";

  /** The handler as CDI wires it: the real hook selection (the GO hook must win over the core one). */
  @Inject
  private NeoCloneRecordHandler handler;

  @Before
  public void setUpCallerAdmin() {
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN,
        TestConstants.Clients.FB_GRP, TestConstants.Orgs.ESP);
    // The sequence observer reads the client from the request's VariablesSecureApp, empty in a
    // test: without it the clone is flushed with a NULL documentNo and the insert fails.
    RequestContext.get().setVariableSecureApp(new VariablesSecureApp(TestConstants.Users.ADMIN,
        TestConstants.Clients.FB_GRP, TestConstants.Orgs.ESP, TestConstants.Roles.FB_GRP_ADMIN,
        "en_US"));
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void cloneRecordPersistsADraftWithNoLinksToTheSourceDocumentsAndLeavesTheSourceUntouched()
      throws Exception {
    Invoice source = createLinkedDraftSource();
    String sourceId = source.getId();
    String sourceDocNo = source.getDocumentNo();
    int sourceLineCount = source.getInvoiceLineList().size();
    assertTrue("fixture: the source needs lines", sourceLineCount > 0);
    assertNotNull("fixture: corrected invoice set", source.getAeatsiiInvoice());
    assertNotNull("fixture: order set", source.getSalesOrder());
    for (InvoiceLine line : source.getInvoiceLineList()) {
      assertNotNull("fixture: order line set", line.getSalesOrderLine());
      assertNotNull("fixture: shipment line set", line.getGoodsShipmentLine());
      assertNotNull("fixture: source invoice line set", line.getETGOSourceInvoiceLine());
    }

    NeoResponse response = handler.handle(NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("cloneRecord")
        .recordId(sourceId)
        .adTab(OBDal.getInstance().get(Tab.class, SALES_INVOICE_HEADER_TAB_ID))
        .build());

    assertEquals(String.valueOf(response.getBody()), 201, response.getHttpStatus());
    String cloneId = response.getBody().getJSONObject("response").getJSONObject("data")
        .getString("id");
    assertFalse("the clone is a new record", sourceId.equals(cloneId));

    // Read everything back from the database, not from the session's first-level cache.
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().clear();

    Invoice clone = OBDal.getInstance().get(Invoice.class, cloneId);
    assertNotNull(clone);
    assertEquals("DR", clone.getDocumentStatus());
    assertFalse(clone.isProcessed());
    assertNull("corrected invoice must be cleared", clone.getAeatsiiInvoice());
    assertNull("order must be cleared", clone.getSalesOrder());
    assertEquals("every source line is cloned", sourceLineCount, clone.getInvoiceLineList().size());
    for (InvoiceLine line : clone.getInvoiceLineList()) {
      assertNull("order line must be cleared", line.getSalesOrderLine());
      assertNull("shipment line must be cleared", line.getGoodsShipmentLine());
      assertNull("source invoice line must be cleared", line.getETGOSourceInvoiceLine());
    }
    assertLinkColumnsAreNullInTheDatabase(cloneId, sourceLineCount);

    Invoice reloadedSource = OBDal.getInstance().get(Invoice.class, sourceId);
    assertEquals(sourceDocNo, reloadedSource.getDocumentNo());
    assertNotNull("the source keeps its corrected invoice", reloadedSource.getAeatsiiInvoice());
    assertNotNull("the source keeps its order", reloadedSource.getSalesOrder());
    assertEquals(sourceLineCount, reloadedSource.getInvoiceLineList().size());
    for (InvoiceLine line : reloadedSource.getInvoiceLineList()) {
      assertNotNull("the source keeps its order lines", line.getSalesOrderLine());
      assertNotNull("the source keeps its shipment lines", line.getGoodsShipmentLine());
      assertNotNull("the source keeps its source invoice lines", line.getETGOSourceInvoiceLine());
    }
  }

  /**
   * Persists a DRAFT copy of a real, fully linked sales invoice, with the corrected-invoice and
   * source-invoice-line links set too. Header and lines keep the template's order, order-line and
   * shipment-line links (DalUtil.copy preserves references).
   */
  private Invoice createLinkedDraftSource() {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    Invoice template = (Invoice) OBDal.getInstance().getSession().createQuery(
        "select i from Invoice i where i.client.id = :client and i.salesTransaction = true "
            + "and i.processed = true and i.salesOrder is not null "
            + "and exists (select 1 from InvoiceLine l where l.invoice = i) "
            + "and not exists (select 1 from InvoiceLine l where l.invoice = i and "
            + "(l.salesOrderLine is null or l.goodsShipmentLine is null)) "
            + "order by i.creationDate")
        .setParameter("client", clientId)
        .setMaxResults(1)
        .uniqueResult();
    assertNotNull("fixture: the F&B demo data must hold a fully linked sales invoice", template);
    InvoiceLine referencedLine = template.getInvoiceLineList().get(0);

    Invoice source = (Invoice) DalUtil.copy(template, true);
    source.setDocumentNo("CLONE-LINKS-IT-" + System.currentTimeMillis());
    source.setDocumentStatus("DR");
    source.setDocumentAction("CO");
    source.setProcessed(false);
    source.setPosted("N");
    source.setAeatsiiInvoice(template);
    for (InvoiceLine line : source.getInvoiceLineList()) {
      line.setETGOSourceInvoiceLine(referencedLine);
    }
    OBDal.getInstance().save(source);
    OBDal.getInstance().flush();
    return source;
  }

  /** Reads the link columns straight from the database for the clone and its lines. */
  private static void assertLinkColumnsAreNullInTheDatabase(String cloneId, int lineCount)
      throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "select em_aeatsii_invoice_id, c_order_id from c_invoice where c_invoice_id = ?")) {
      ps.setString(1, cloneId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertNull(rs.getString(1));
        assertNull(rs.getString(2));
      }
    }
    try (PreparedStatement ps = conn.prepareStatement(
        "select count(*), count(c_orderline_id), count(m_inoutline_id), "
            + "count(em_etgo_source_invoiceline_id) from c_invoiceline where c_invoice_id = ?")) {
      ps.setString(1, cloneId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(lineCount, rs.getInt(1));
        assertEquals("no line may keep C_OrderLine_ID", 0, rs.getInt(2));
        assertEquals("no line may keep M_InOutLine_ID", 0, rs.getInt(3));
        assertEquals("no line may keep EM_Etgo_Source_Invoiceline_ID", 0, rs.getInt(4));
      }
    }
  }
}
