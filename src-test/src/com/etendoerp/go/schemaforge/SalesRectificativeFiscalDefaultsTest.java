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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link SalesRectificativeFiscalDefaults} (ETP-5747): the TicketBAI and VERI*FACTU
 * rectificative fields of a sales invoice are defaulted on the server after a header write, so an
 * invoice created with its rectificative document type already set (Import from source invoice,
 * MCP, batch) gets them without the doc-type callout ever firing.
 *
 * @covers com.etendoerp.go.schemaforge.SalesRectificativeFiscalDefaults
 */
public class SalesRectificativeFiscalDefaultsTest {

  private static final String INVOICE_ID = "inv-5747";
  private static final String ORG_ID = "org-5747";

  private MockedStatic<OBDal> dalMock;
  private OBDal dal;
  private PreparedStatement tbaiStatement;
  private PreparedStatement verifactuStatement;

  @Before
  public void setUp() throws SQLException {
    RectificativeSupport.setColumnPresentForTests(true);
    dalMock = Mockito.mockStatic(OBDal.class);
    dal = mock(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    tbaiStatement = mock(PreparedStatement.class);
    verifactuStatement = mock(PreparedStatement.class);
    when(conn.prepareStatement(SalesRectificativeFiscalDefaults.TBAI_ACTIVE_SQL))
        .thenReturn(tbaiStatement);
    when(conn.prepareStatement(SalesRectificativeFiscalDefaults.VERIFACTU_ACTIVE_SQL))
        .thenReturn(verifactuStatement);
    configActive(tbaiStatement, false);
    configActive(verifactuStatement, false);
  }

  @After
  public void tearDown() {
    dalMock.close();
    RectificativeSupport.setColumnPresentForTests(null);
    OBContext.clearAdminModeStack();
  }

  private static void configActive(PreparedStatement ps, boolean active) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(active);
    when(ps.executeQuery()).thenReturn(rs);
  }

  private static DocumentType docType(boolean rectificative, String vfInvType,
      String vfReverseType) {
    DocumentType dt = mock(DocumentType.class);
    when(dt.isEtsgIsRectificative()).thenReturn(rectificative);
    when(dt.getEtvfacInvType()).thenReturn(vfInvType);
    when(dt.getEtvfacReverseInvType()).thenReturn(vfReverseType);
    return dt;
  }

  /** A draft sales invoice with the AD defaults: no TicketBAI flag/code, VERI*FACTU {@code F1}. */
  private static Invoice draftInvoice(DocumentType dt) {
    Invoice invoice = mock(Invoice.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(invoice.getOrganization()).thenReturn(org);
    when(invoice.isSalesTransaction()).thenReturn(true);
    when(invoice.getDocumentStatus()).thenReturn("DR");
    when(invoice.isProcessed()).thenReturn(false);
    when(invoice.getTransactionDocument()).thenReturn(dt);
    when(invoice.isTbaiIsreverseinvoice()).thenReturn(false);
    when(invoice.getEtvfacInvType()).thenReturn("F1");
    return invoice;
  }

  /** ETP-5747 repro (TicketBAI): the three reverse-invoice fields the doc-type callout sets. */
  @Test
  public void tbaiOrgRectificativeDraftGetsReverseFlagTypeAndCode() throws Exception {
    configActive(tbaiStatement, true);
    Invoice invoice = draftInvoice(docType(true, null, null));

    Map<String, Object> applied = SalesRectificativeFiscalDefaults.computeAndSet(invoice);

    verify(invoice).setTbaiIsreverseinvoice(true);
    verify(invoice).setTbaiReverseinvoicetype("I");
    verify(invoice).setTbaiReverseinvoicecode("R4");
    assertEquals(Boolean.TRUE, applied.get("tbaiIsreverseinvoice"));
    assertEquals("I", applied.get("tbaiReverseinvoicetype"));
    assertEquals("R4", applied.get("tbaiReverseinvoicecode"));
    assertEquals(3, applied.size());
  }

  /** A code the user (or Classic) already chose is never replaced. */
  @Test
  public void tbaiValuesAlreadyChosenAreKept() throws Exception {
    configActive(tbaiStatement, true);
    Invoice invoice = draftInvoice(docType(true, null, null));
    when(invoice.isTbaiIsreverseinvoice()).thenReturn(true);
    when(invoice.getTbaiReverseinvoicetype()).thenReturn("S");
    when(invoice.getTbaiReverseinvoicecode()).thenReturn("R1");

    Map<String, Object> applied = SalesRectificativeFiscalDefaults.computeAndSet(invoice);

    assertTrue(applied.isEmpty());
    verify(invoice, never()).setTbaiReverseinvoicecode(anyString());
    verify(invoice, never()).setTbaiReverseinvoicetype(anyString());
  }

  /** ETP-5747 repro (VERI*FACTU): the AD default F1 is replaced by the doc type's R code. */
  @Test
  public void verifactuOrgRectificativeDraftTakesTheDocTypeInvoiceType() throws Exception {
    configActive(verifactuStatement, true);
    Invoice invoice = draftInvoice(docType(true, "R1", null));

    Map<String, Object> applied = SalesRectificativeFiscalDefaults.computeAndSet(invoice);

    verify(invoice).setEtvfacInvType("R1");
    verify(invoice).setEtvfacReverseinvtype("I");
    assertEquals("R1", applied.get("etvfacInvType"));
    assertEquals("I", applied.get("etvfacReverseinvtype"));
    verify(invoice, never()).setTbaiReverseinvoicecode(anyString());
  }

  /** The doc type's own reverse type wins over the "I" fallback. */
  @Test
  public void verifactuReverseTypeComesFromTheDocTypeWhenSet() throws Exception {
    configActive(verifactuStatement, true);
    Invoice invoice = draftInvoice(docType(true, "R1", "S"));

    SalesRectificativeFiscalDefaults.computeAndSet(invoice);

    verify(invoice).setEtvfacReverseinvtype("S");
  }

  /** An R code the user picked (R4) is kept even if the doc type says R1. */
  @Test
  public void verifactuRectificativeCodeAlreadyChosenIsKept() throws Exception {
    configActive(verifactuStatement, true);
    Invoice invoice = draftInvoice(docType(true, "R1", null));
    when(invoice.getEtvfacInvType()).thenReturn("R4");
    when(invoice.getEtvfacReverseinvtype()).thenReturn("I");

    Map<String, Object> applied = SalesRectificativeFiscalDefaults.computeAndSet(invoice);

    assertTrue(applied.isEmpty());
    verify(invoice, never()).setEtvfacInvType(anyString());
  }

  /** A doc type with no VERI*FACTU invoice type configured leaves the invoice as it is. */
  @Test
  public void verifactuWithoutDocTypeInvoiceTypeChangesNothing() throws Exception {
    configActive(verifactuStatement, true);
    Invoice invoice = draftInvoice(docType(true, null, null));

    assertTrue(SalesRectificativeFiscalDefaults.computeAndSet(invoice).isEmpty());
  }

  /** No regression on a plain invoice: nothing is written, even with every system active. */
  @Test
  public void nonRectificativeInvoiceIsUntouched() throws Exception {
    configActive(tbaiStatement, true);
    configActive(verifactuStatement, true);
    Invoice invoice = draftInvoice(docType(false, "F1", null));

    assertTrue(SalesRectificativeFiscalDefaults.computeAndSet(invoice).isEmpty());
    verify(invoice, never()).setTbaiIsreverseinvoice(anyBoolean());
    verify(invoice, never()).setEtvfacInvType(anyString());
  }

  /** A completed invoice, a purchase invoice, and an org with no fiscal system: untouched. */
  @Test
  public void completedPurchaseOrUnconfiguredInvoicesAreUntouched() throws Exception {
    Invoice unconfigured = draftInvoice(docType(true, "R1", null));
    assertTrue(SalesRectificativeFiscalDefaults.computeAndSet(unconfigured).isEmpty());

    configActive(tbaiStatement, true);
    configActive(verifactuStatement, true);
    Invoice completed = draftInvoice(docType(true, "R1", null));
    when(completed.getDocumentStatus()).thenReturn("CO");
    when(completed.isProcessed()).thenReturn(true);
    assertTrue(SalesRectificativeFiscalDefaults.computeAndSet(completed).isEmpty());

    Invoice purchase = draftInvoice(docType(true, "R1", null));
    when(purchase.isSalesTransaction()).thenReturn(false);
    assertTrue(SalesRectificativeFiscalDefaults.computeAndSet(purchase).isEmpty());
  }

  /** {@code apply} saves and flushes only when something changed, and never throws. */
  @Test
  public void applyFlushesOnlyWhenSomethingChanged() throws Exception {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      configActive(tbaiStatement, true);
      Invoice invoice = draftInvoice(docType(true, null, null));
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);

      Map<String, Object> applied = SalesRectificativeFiscalDefaults.apply(INVOICE_ID);

      assertEquals("R4", applied.get("tbaiReverseinvoicecode"));
      verify(dal).save(invoice);
      verify(dal).flush();

      Invoice plain = draftInvoice(docType(false, null, null));
      when(dal.get(Invoice.class, "plain")).thenReturn(plain);
      assertTrue(SalesRectificativeFiscalDefaults.apply("plain").isEmpty());
      verify(dal, never()).save(plain);

      when(dal.get(Invoice.class, "boom")).thenThrow(new IllegalStateException("boom"));
      assertTrue(SalesRectificativeFiscalDefaults.apply("boom").isEmpty());
      assertTrue(SalesRectificativeFiscalDefaults.apply(null).isEmpty());
      verify(dal, Mockito.times(1)).save(any());
    }
  }
}
